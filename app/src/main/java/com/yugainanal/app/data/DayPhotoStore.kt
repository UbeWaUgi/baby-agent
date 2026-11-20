package com.yugainanal.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DayPhoto(val id: Long, val childId: Long, val date: LocalDate, val fileName: String)

data class PhotoImportResult(val added: Int, val duplicates: Int, val skipped: Int)

class DayPhotoStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "day-photos")

    fun photos(childId: Long): Flow<List<DayPhoto>> = changes
        .map { readPhotos(childId) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun importPhotos(childId: Long, date: LocalDate, uris: List<Uri>): PhotoImportResult =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val directory = directoryFor(childId, date)
                if (uris.isEmpty()) return@withLock PhotoImportResult(0, 0, 0)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("Could not create day photo directory")
                }

                var added = 0
                var duplicates = 0
                var skipped = 0
                try {
                    for (uri in uris) {
                        currentCoroutineContext().ensureActive()
                        try {
                            if (importPhoto(uri, directory)) added++ else duplicates++
                        } catch (_: IOException) {
                            skipped++
                        } catch (_: SecurityException) {
                            skipped++
                        } catch (_: IllegalArgumentException) {
                            skipped++
                        }
                    }
                } finally {
                    if (added > 0) changes.value++
                }
                PhotoImportResult(added, duplicates, skipped)
            }
        }

    suspend fun removePhoto(photo: DayPhoto): Unit = withContext(Dispatchers.IO) {
        writeLock.withLock {
            if (Files.deleteIfExists(fileFor(photo).toPath())) changes.value++
        }
    }

    fun fileFor(photo: DayPhoto): File {
        require(storedName.matches(photo.fileName))
        return File(directoryFor(photo.childId, photo.date), photo.fileName)
    }

    private fun directoryFor(childId: Long, date: LocalDate): File {
        require(childId > 0)
        return File(File(root, childId.toString()), date.toString())
    }

    private fun readPhotos(childId: Long): List<DayPhoto> {
        val days = File(root, childId.toString()).listFiles().orEmpty()
        return days.flatMap { directory ->
            if (!directory.isDirectory) return@flatMap emptyList()
            val date = try {
                LocalDate.parse(directory.name)
            } catch (_: DateTimeParseException) {
                return@flatMap emptyList()
            }
            if (directory.name != date.toString()) return@flatMap emptyList()
            directory.listFiles().orEmpty()
                .filter { it.isFile && storedName.matches(it.name) }
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
                .map { DayPhoto(photoId(childId, date, it.name), childId, date, it.name) }
        }.sortedByDescending { it.date }
    }

    private suspend fun importPhoto(uri: Uri, directory: File): Boolean {
        require(uri.scheme == "content")
        val source = File.createTempFile("day-photo-source-", ".tmp", appContext.cacheDir)
        try {
            val name = copyAndHash(uri, source) + ".jpg"
            val destination = File(directory, name)
            if (destination.isFile) return false
            val temporary = File.createTempFile("day-photo-", ".tmp", directory)
            try {
                encodePhoto(source, temporary)
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            return true
        } finally {
            source.delete()
        }
    }

    private suspend fun copyAndHash(uri: Uri, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val source = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not read chosen image")
        var size = 0L
        source.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_SOURCE_BYTES) throw IOException("Image file is too large")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun encodePhoto(source: File, destination: File) {
        val image = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder scales to the requested size and applies the source EXIF orientation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                validateDimensions(width, height)
                val longest = max(width, height)
                if (longest > MAX_EDGE) {
                    decoder.setTargetSize(max(1, width * MAX_EDGE / longest), max(1, height * MAX_EDGE / longest))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(source)
        }
        try {
            FileOutputStream(destination).use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw IOException("Could not encode chosen image")
                }
                output.fd.sync()
            }
        } finally {
            image.recycle()
        }
    }

    private fun decodeLegacy(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        validateDimensions(bounds.outWidth, bounds.outHeight)
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longest / sampleSize > MAX_EDGE * 2) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("Unsupported image")
        try {
            val matrix = legacyOrientation(source)
            val decodedLongest = max(bitmap.width, bitmap.height)
            if (decodedLongest > MAX_EDGE) {
                val ratio = MAX_EDGE.toFloat() / decodedLongest
                matrix.postScale(ratio, ratio)
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (failure: Exception) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun legacyOrientation(source: File): Matrix = Matrix().apply {
        val orientation = try {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private fun validateDimensions(width: Int, height: Int) {
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            throw IOException("Invalid or oversized image dimensions")
        }
    }

    private fun photoId(childId: Long, date: LocalDate, name: String): Long {
        val key = "$childId/$date/$name".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        return (ByteBuffer.wrap(hash).long and Long.MAX_VALUE).coerceAtLeast(1)
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        const val MAX_DIMENSION = 32_768
        const val MAX_PIXELS = 150_000_000L
        const val MAX_EDGE = 1_600
        val storedName = Regex("[a-f0-9]{64}\\.jpg")
        val changes = MutableStateFlow(0L)
        val writeLock = Mutex()
    }
}
