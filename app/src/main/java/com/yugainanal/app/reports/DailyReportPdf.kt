package com.yugainanal.app.reports

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Size
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

object DailyReportPdf {
    private val fileTime = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)

    fun suggestedFileName(report: DailyReport): String {
        val name = when {
            report.id <= 0L -> "요약"
            report.kind == ReportKind.INSTANT -> "즉시-${report.createdAt.format(fileTime)}"
            else -> "자동"
        }
        return "육아의나날-${report.date}-$name.pdf"
    }

    fun write(
        report: DailyReport,
        output: OutputStream,
        photos: List<File> = emptyList(),
        resolver: ContentResolver? = null,
        galleryPhotos: List<Uri> = emptyList(),
        videoCount: Int = 0,
    ) {
        val document = PdfDocument()
        try {
            ReportPages(document, report).draw(photos, resolver, galleryPhotos, videoCount)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 44
private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
private const val CONTENT_BOTTOM = PAGE_HEIGHT - 72

private val ink = Color.rgb(48, 34, 28)
private val accent = Color.rgb(136, 58, 29)
private val muted = Color.rgb(108, 81, 69)
private val pale = Color.rgb(247, 235, 223)
private val rule = Color.rgb(231, 213, 196)
private val reportDate = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)
private val reportTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val printedAt = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.ROOT)

private class ReportPages(
    private val document: PdfDocument,
    private val report: DailyReport,
) {
    private val labelPaint = textPaint(11f, accent, bold = true)
    private val titlePaint = textPaint(28f, ink, bold = true)
    private val metadataPaint = textPaint(11f, muted)
    private val sectionPaint = textPaint(15f, ink, bold = true)
    private val bodyPaint = textPaint(12.5f, ink)
    private val footerPaint = textPaint(10f, muted)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var cursor = 0f

    private val canvas: Canvas
        get() = checkNotNull(page).canvas

    fun draw(photos: List<File>, resolver: ContentResolver?, galleryPhotos: List<Uri>, videoCount: Int) {
        try {
            startPage()
            for (section in report.sections) {
                drawSection(section.title, section.lines)
            }
            if (report.note.isNotBlank()) {
                drawSection("보호자 메모", listOf(report.note))
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = if (photos.size > 12) 4 else 2 }
            val imageLoaders = buildList<() -> Bitmap?> {
                for (file in photos) add { BitmapFactory.decodeFile(file.path, decodeOptions) }
                if (resolver != null) {
                    for (uri in galleryPhotos) add { loadGalleryPhoto(resolver, uri) }
                }
            }
            if (imageLoaders.isNotEmpty()) drawPhotos(imageLoaders)
            if (videoCount > 0) drawSection("이날 영상", listOf("${videoCount}개 · 앱의 하루 리포트에서 재생할 수 있어요."))
        } finally {
            finishPage()
        }
    }

    private fun startPage() {
        finishPage()
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        canvas.drawColor(Color.WHITE)
        cursor = MARGIN.toFloat()
        drawText(makeLayout("육아의나날", labelPaint))
        cursor += 8f
        drawRule(cursor)
        cursor += 13f

        val date = report.date.format(reportDate)
        if (pageNumber > 1) {
            drawText(makeLayout("$date 하루 리포트", metadataPaint))
            cursor += 16f
            return
        }

        drawText(makeLayout("하루 리포트", titlePaint))
        cursor += 5f
        val kind = when {
            report.id <= 0L -> "기록 요약"
            report.kind == ReportKind.INSTANT -> "즉시 리포트"
            else -> "자동 리포트"
        }
        drawText(makeLayout("$date  ·  $kind", metadataPaint))
        cursor += 15f

        val cutoff = when {
            report.cutoff == report.date.plusDays(1).atStartOfDay() -> "24:00"
            report.cutoff.toLocalDate() == report.date -> report.cutoff.format(reportTime)
            else -> report.cutoff.format(printedAt)
        }
        val details = makeLayout(
            "${report.childName}\n기록 범위  00:00 - $cutoff\n작성 시각  ${report.createdAt.format(printedAt)}",
            metadataPaint,
            CONTENT_WIDTH - 28,
        )
        shapePaint.color = pale
        canvas.drawRoundRect(
            MARGIN.toFloat(),
            cursor,
            (PAGE_WIDTH - MARGIN).toFloat(),
            cursor + details.height + 22f,
            8f,
            8f,
            shapePaint,
        )
        cursor += 11f
        drawText(details, x = MARGIN + 14f)
        cursor += 27f
    }

    private fun drawPhotos(photos: List<() -> Bitmap?>) {
        val heading = makeLayout("이날 사진", sectionPaint)
        val rowHeight = 140f
        val gap = 10f
        val tileWidth = (CONTENT_WIDTH - gap) / 2
        if (cursor + heading.height + rowHeight + gap > CONTENT_BOTTOM) startPage()
        drawText(heading)
        cursor += gap
        for (row in photos.chunked(2)) {
            if (cursor + rowHeight > CONTENT_BOTTOM) {
                startPage()
                drawText(makeLayout("이날 사진 (계속)", sectionPaint))
                cursor += gap
            }
            row.forEachIndexed { index, load ->
                val left = MARGIN + index * (tileWidth + gap)
                val bitmap = load()
                if (bitmap == null) {
                    canvas.drawText("사진을 불러오지 못했어요.", left, cursor + 18f, metadataPaint)
                    return@forEachIndexed
                }
                val scale = min(tileWidth / bitmap.width, rowHeight / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF(left, cursor, left + width, cursor + height), photoPaint)
            }
            cursor += rowHeight + gap
        }
    }

    private fun drawSection(title: String, lines: List<String>) {
        val heading = makeLayout(title, sectionPaint)
        val paragraphs = lines.map { makeLayout(it, bodyPaint) }
        val firstLineHeight = paragraphs.firstOrNull()?.getLineBottom(0) ?: 0
        if (cursor + heading.height + 7f + firstLineHeight > CONTENT_BOTTOM) {
            startPage()
        }
        drawText(heading)
        cursor += 5f
        for (layout in paragraphs) {
            drawText(layout)
            cursor += 3f
        }
        cursor += 7f
        if (cursor + 3f < CONTENT_BOTTOM) {
            drawRule(cursor)
        }
        cursor += 12f
    }

    private fun drawText(layout: StaticLayout, x: Float = MARGIN.toFloat()) {
        var firstLine = 0
        while (firstLine < layout.lineCount) {
            val top = layout.getLineTop(firstLine)
            if (cursor + layout.getLineBottom(firstLine) - top > CONTENT_BOTTOM) {
                startPage()
            }
            var endLine = firstLine
            while (endLine + 1 < layout.lineCount &&
                cursor + layout.getLineBottom(endLine + 1) - top <= CONTENT_BOTTOM
            ) {
                endLine++
            }
            val height = layout.getLineBottom(endLine) - top
            val checkpoint = canvas.save()
            canvas.clipRect(x, cursor, x + layout.width, cursor + height)
            canvas.translate(x, cursor - top)
            layout.draw(canvas)
            canvas.restoreToCount(checkpoint)
            cursor += height
            firstLine = endLine + 1
        }
    }

    private fun drawRule(y: Float) {
        shapePaint.color = rule
        shapePaint.strokeWidth = 0.7f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, shapePaint)
    }

    private fun finishPage() {
        val finished = page ?: return
        try {
            drawRule(PAGE_HEIGHT - 57f)
            val footer = "육아의나날  ·  아이 돌봄 기록"
            val number = pageNumber.toString()
            canvas.drawText(footer, MARGIN.toFloat(), PAGE_HEIGHT - 39f, footerPaint)
            canvas.drawText(
                number,
                PAGE_WIDTH - MARGIN - footerPaint.measureText(number),
                PAGE_HEIGHT - 39f,
                footerPaint,
            )
        } finally {
            document.finishPage(finished)
            page = null
        }
    }

    private fun makeLayout(text: String, paint: TextPaint, width: Int = CONTENT_WIDTH): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setIncludePad(true)
            .setLineSpacing(2f, 1.08f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUseLineSpacingFromFallbacks(true)
                }
            }
            .build()
}

private fun loadGalleryPhoto(resolver: ContentResolver, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolver.loadThumbnail(uri, Size(400, 400), null)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 800) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun textPaint(size: Float, color: Int, bold: Boolean = false): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textLocale = Locale.KOREAN
    }
