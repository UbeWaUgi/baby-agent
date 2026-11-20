package com.yugainanal.app.media

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import com.yugainanal.app.data.CareZone
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQueries
import java.util.Locale

private val exifDateTime = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val compactMediaDateTime = DateTimeFormatterBuilder()
    .appendPattern("uuuuMMdd'T'HHmmss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .optionalStart()
    .appendOffsetId()
    .optionalEnd()
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val compactOffset = Regex("([+-]\\d{2})(\\d{2})$")

/** Reads embedded date only when MediaStore does not provide a capture timestamp. */
internal fun embeddedGalleryCaptureDate(context: Context, media: GalleryMedia): LocalDate? =
    try {
        if (media.kind == GalleryMediaKind.PHOTO) {
            context.contentResolver.openInputStream(media.uri)?.use { source ->
                val metadata = ExifInterface(source)
                parsePhotoCaptureDate(
                    metadata.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    metadata.getAttribute("OffsetTimeOriginal"),
                )
            }
        } else {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, media.uri)
                parseVideoCaptureDate(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            } finally {
                metadata.release()
            }
        }
    } catch (_: Exception) {
        // The system date inference is still usable for unsupported or inaccessible metadata.
        null
    }

internal fun parsePhotoCaptureDate(value: String?, timeOffset: String?): LocalDate? {
    val cleaned = value?.trimMetadata() ?: return null
    val local = runCatching { LocalDateTime.parse(cleaned, exifDateTime) }.getOrNull() ?: return null
    val offset = timeOffset?.trimMetadata()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
    return normalizedDate(local, offset)
}

internal fun parseVideoCaptureDate(value: String?): LocalDate? {
    val cleaned = value?.trimMetadata()?.replace(compactOffset, "$1:$2") ?: return null
    for (formatter in listOf(DateTimeFormatter.ISO_DATE_TIME, compactMediaDateTime)) {
        val parsed = runCatching {
            val fields = formatter.parse(cleaned)
            normalizedDate(LocalDateTime.from(fields), fields.query(TemporalQueries.offset()))
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return runCatching { LocalDate.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        ?: runCatching { LocalDate.parse(cleaned, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
}

private fun normalizedDate(local: LocalDateTime, offset: ZoneOffset?): LocalDate =
    if (offset == null) local.atZone(CareZone).toLocalDate()
    else local.atOffset(offset).atZoneSameInstant(CareZone).toLocalDate()

private fun String.trimMetadata(): String = trim { it.isWhitespace() || it == '\u0000' }
