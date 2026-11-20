package com.yugainanal.app.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF

enum class GalleryMatch { MATCH, NO_MATCH, REVIEW }

internal const val GALLERY_CLASSIFIER_REVISION = 2

data class ChildFaceProfile(val revision: String, val embedding: FloatArray)

class ChildFaceProfileStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "child-face-profiles")

    fun load(childId: Long): ChildFaceProfile? = synchronized(lock) {
        readProfile(profileFile(childId))
    }

    fun save(childId: Long, embedding: FloatArray): ChildFaceProfile = synchronized(lock) {
        require(isEmbedding(embedding))
        val file = profileFile(childId)
        val old = readProfile(file)
        if (old != null && old.embedding.contentEquals(embedding)) return@synchronized old
        val profile = ChildFaceProfile(UUID.randomUUID().toString(), embedding.copyOf())
        val stream = file.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeUTF(FACE_MODEL_VERSION)
            data.writeUTF(profile.revision)
            for (value in profile.embedding) data.writeFloat(value)
            data.flush()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        profile
    }

    fun clear(childId: Long) = synchronized(lock) {
        val file = profileFile(childId)
        file.delete()
        if (readProfile(file) != null) throw IOException("Could not remove reference")
    }

    private fun profileFile(childId: Long): AtomicFile {
        require(childId > 0L)
        return AtomicFile(File(directory, "$childId.bin"))
    }

    private fun readProfile(file: AtomicFile): ChildFaceProfile? = try {
        DataInputStream(file.openRead()).use { input ->
            if (input.readUTF() != FACE_MODEL_VERSION) return@use null
            val revision = input.readUTF()
            val embedding = FloatArray(FACE_FEATURE_SIZE) { input.readFloat() }
            if (revision.isBlank() || !isEmbedding(embedding) || input.read() != -1) null
            else ChildFaceProfile(revision, embedding)
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        val lock = Any()
    }
}

class ChildFaceMatcher(context: Context) : Closeable {
    private val app = context.applicationContext

    suspend fun createReference(uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val bitmap = decodeStill(app.contentResolver, uri)
        try {
            val faces = features(bitmap, reference = true)
            require(faces.size == 1) { "아이 얼굴 한 명이 선명하게 나온 사진을 선택해 주세요." }
            faces.single()
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun classify(uri: Uri, isVideo: Boolean, profile: ChildFaceProfile): GalleryMatch = withContext(Dispatchers.IO) {
        require(isEmbedding(profile.embedding))
        if (isVideo) return@withContext classifyVideo(uri, profile.embedding)

        val bitmap = decodeStill(app.contentResolver, uri)
        try {
            matchForScore(score(bitmap, profile.embedding), PHOTO_MATCH_SCORE)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun classifyVideo(uri: Uri, reference: FloatArray): GalleryMatch {
        val video = MediaMetadataRetriever()
        try {
            video.setDataSource(app, uri)
            val duration = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frameEdge = if (width > 0 && height > 0) max(width, height).coerceAtMost(MAX_EDGE) else MAX_EDGE
            if (Build.VERSION.SDK_INT < 27 &&
                (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 16_777_216)
            ) throw IOException("Video dimensions are unsupported on this device")
            val fractions = doubleArrayOf(0.05, 0.22, 0.40, 0.59, 0.77, 0.95)
            val times = if (duration != null && duration in 1L until Long.MAX_VALUE / 1000) {
                fractions.map { (duration * 1000.0 * it).toLong() }.distinct()
            } else {
                listOf(0L)
            }
            var best = -1.0
            var readableFrame = false
            for (time in times) {
                currentCoroutineContext().ensureActive()
                val frame = videoFrame(video, time, frameEdge) ?: continue
                try {
                    readableFrame = true
                    best = max(best, score(frame, reference))
                    if (best >= VIDEO_MATCH_SCORE) return GalleryMatch.MATCH
                } finally {
                    frame.recycle()
                }
            }
            if (!readableFrame) throw IOException("Video did not decode any sampled frames")
            return matchForScore(best, VIDEO_MATCH_SCORE)
        } catch (failure: RuntimeException) {
            if (failure is SecurityException || failure is CancellationException) throw failure
            throw IOException("Could not decode video", failure)
        } finally {
            video.release()
        }
    }

    private suspend fun score(bitmap: Bitmap, reference: FloatArray): Double =
        features(bitmap).maxOfOrNull { embedding ->
            embedding.indices.sumOf { embedding[it].toDouble() * reference[it] }
        } ?: -1.0

    private suspend fun features(bitmap: Bitmap, reference: Boolean = false): List<FloatArray> = engineLock.withLock {
        try {
            val current = engine ?: FaceEngine(app).also { engine = it }
            current.features(bitmap, reference)
        } catch (failure: CvException) {
            throw IOException("The on-device face model could not process the media", failure)
        }
    }

    // OpenCV's generated detector/recognizer wrappers keep a native model for the process lifetime.
    override fun close() = Unit

    private companion object {
        val engineLock = Mutex()
        var engine: FaceEngine? = null
    }
}

private class FaceEngine(context: Context) {
    private val detector: FaceDetectorYN
    private val recognizer: FaceRecognizerSF

    init {
        if (!OpenCVLoader.initLocal()) throw IOException("Could not initialize OpenCV")
        Core.setNumThreads(2)
        val directory = File(context.cacheDir, "gallery-models/$FACE_MODEL_VERSION")
        val yuNet = copyModel(context, directory, "face_detection_yunet_2023mar.onnx", 232_589)
        val sFace = copyModel(context, directory, "face_recognition_sface_2021dec_int8.onnx", 9_896_933)
        detector = FaceDetectorYN.create(yuNet.path, "", Size(320.0, 320.0), 0.88f, 0.3f, 512)
        recognizer = FaceRecognizerSF.create(sFace.path, "")
    }

    fun features(bitmap: Bitmap, reference: Boolean): List<FloatArray> {
        if (bitmap.width < 33 || bitmap.height < 33) return emptyList()
        val fitted = shrink(bitmap)
        val software = if (fitted.config == Bitmap.Config.ARGB_8888) fitted else
            fitted.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Could not prepare pixels")
        val rgba = Mat()
        val image = Mat()
        val detections = Mat()
        val aligned = Mat()
        val representation = Mat()
        try {
            Utils.bitmapToMat(software, rgba)
            Imgproc.cvtColor(rgba, image, Imgproc.COLOR_RGBA2BGR)
            detector.setInputSize(image.size())
            detector.detect(image, detections)
            if (detections.empty() && max(image.cols(), image.rows()) > CLOSE_UP_EDGE) {
                // A close-up can exceed the detector's face-size range at the standard decode size.
                val (width, height) = fittedSize(image.cols(), image.rows(), CLOSE_UP_EDGE)
                Imgproc.resize(image, image, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                detector.setInputSize(image.size())
                detector.detect(image, detections)
            }
            if (reference && detections.rows() != 1) return emptyList()

            val candidates = (0 until detections.rows()).mapNotNull { row ->
                val box = FloatArray(15)
                detections.get(row, 0, box)
                val minSize = if (reference) 54f else 33f
                val minScore = if (reference) 0.92f else 0.88f
                if (box[2] >= minSize && box[3] >= minSize && box[14] >= minScore) {
                    Candidate(row, box[2] * box[3])
                } else null
            }.sortedByDescending(Candidate::area).take(MAX_FACES_PER_SAMPLE)

            return candidates.mapNotNull { face ->
                val detection = detections.row(face.row)
                try {
                    recognizer.alignCrop(image, detection, aligned)
                    recognizer.feature(aligned, representation)
                    if (representation.total() * representation.channels() != FACE_FEATURE_SIZE.toLong()) {
                        throw IOException("Unexpected face embedding dimensions")
                    }
                    val vector = FloatArray(FACE_FEATURE_SIZE)
                    representation.get(0, 0, vector)
                    normalized(vector)
                } finally {
                    detection.release()
                }
            }
        } finally {
            representation.release()
            aligned.release()
            detections.release()
            image.release()
            rgba.release()
            if (software !== fitted) software.recycle()
            if (fitted !== bitmap) fitted.recycle()
        }
    }

    private data class Candidate(val row: Int, val area: Float)
}

private const val FACE_MODEL_VERSION = "yunet2023-sface2021-int8-v1"
private const val FACE_FEATURE_SIZE = 128
private const val MAX_EDGE = 1080
private const val CLOSE_UP_EDGE = 600
private const val MAX_FACES_PER_SAMPLE = 12
private const val PHOTO_MATCH_SCORE = 0.62
private const val VIDEO_MATCH_SCORE = 0.65
private const val REVIEW_SCORE = 0.38

private fun isEmbedding(values: FloatArray): Boolean =
    values.size == FACE_FEATURE_SIZE && values.all { it.isFinite() } &&
        values.sumOf { value -> value.toDouble() * value } in 0.999..1.001

private fun normalized(values: FloatArray): FloatArray? {
    val norm = sqrt(values.sumOf { value -> value.toDouble() * value })
    if (!norm.isFinite() || norm < 1e-8) return null
    for (index in values.indices) values[index] = (values[index] / norm).toFloat()
    return values
}

private fun matchForScore(score: Double, threshold: Double): GalleryMatch = when {
    score >= threshold -> GalleryMatch.MATCH
    score >= REVIEW_SCORE -> GalleryMatch.REVIEW
    else -> GalleryMatch.NO_MATCH
}

private fun copyModel(context: Context, directory: File, name: String, byteCount: Long): File {
    val model = File(directory, name)
    if (model.length() == byteCount) return model
    val atomic = AtomicFile(model)
    val target = atomic.startWrite()
    try {
        val length = context.assets.open("gallery-models/$name").use { source -> source.copyTo(target) }
        if (length != byteCount) throw IOException("Unexpected bundled model length")
        atomic.finishWrite(target)
    } catch (failure: Exception) {
        atomic.failWrite(target)
        throw failure
    }
    return model
}

private fun decodeStill(resolver: ContentResolver, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            validateDimensions(width, height)
            val (targetWidth, targetHeight) = fittedSize(width, height)
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val imageStream = resolver.openInputStream(uri) ?: throw IOException("Could not open image")
    imageStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    validateDimensions(bounds.outWidth, bounds.outHeight)
    val longest = max(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > MAX_EDGE * 2) sample *= 2
    val params = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, params) }
        ?: throw IOException("Could not decode image")
    try {
        val transform = orientationMatrix(resolver, uri)
        if (transform.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } catch (failure: Exception) {
        bitmap.recycle()
        throw failure
    }
}

private fun orientationMatrix(resolver: ContentResolver, uri: Uri): Matrix {
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: IOException) {
        null
    }
    return Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}

private fun videoFrame(video: MediaMetadataRetriever, time: Long, frameEdge: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 27) {
        // Android chooses the aspect ratio within the requested bounding box.
        return video.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, frameEdge, frameEdge)
    }
    return video.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
}

private fun validateDimensions(width: Int, height: Int) {
    if (width !in 1..32_768 || height !in 1..32_768 || width.toLong() * height > 256_000_000) {
        throw IOException("Image dimensions are unsupported")
    }
}

private fun fittedSize(width: Int, height: Int, edge: Int = MAX_EDGE): Pair<Int, Int> {
    val ratio = (edge.toDouble() / max(width, height)).coerceAtMost(1.0)
    return Pair((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
}

private fun shrink(bitmap: Bitmap): Bitmap {
    if (max(bitmap.width, bitmap.height) <= MAX_EDGE) return bitmap
    val (width, height) = fittedSize(bitmap.width, bitmap.height)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
