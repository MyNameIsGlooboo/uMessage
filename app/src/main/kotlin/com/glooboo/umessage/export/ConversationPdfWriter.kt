package com.glooboo.umessage.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.export.PdfText.isolate
import java.io.OutputStream

/** The words printed on every page, ready made. */
class PdfTexts(
    val header: ExportHeader,
    val notCertified: String,
    /** The hash line and the export time, shown in the footer of every page. */
    val verification: String,
    val page: (number: Int, total: Int) -> String,
    val from: (String) -> String,
    val to: (String) -> String,
)

/**
 * Writes a [ConversationExport] as an A4 PDF with [PdfDocument]. Text is laid out with StaticLayout, which shapes and
 * orders Persian and mixed text; the font is the Vazirmatn that ships with the app, so Persian and Latin look the same
 * on every phone. The layout is done twice: first the heights of every message, so the pages and "Page n of N" are
 * known, then the drawing. Nothing is held between the two passes but the heights, so a long conversation stays small.
 */
class ConversationPdfWriter(context: Context) {
    private val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
    private val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD

    private val bodyPaint = paint(BODY_SIZE, Color.BLACK, regular)
    private val footerPaint = paint(FOOTER_SIZE, Color.DKGRAY, regular)
    private val linePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = RULE_WIDTH
    }

    /** Writes the PDF to [out]; false (and nothing useful written) when [isCancelled] became true. */
    fun write(export: ConversationExport, texts: PdfTexts, out: OutputStream, isCancelled: () -> Boolean): Boolean {
        val blocks = listOf(headerText(texts.header)) + export.rows.map { rowText(it, texts) }
        val heights = ArrayList<List<Float>>(blocks.size)
        for (block in blocks) {
            if (isCancelled()) return false
            heights.add(PdfText.lineHeights(layout(block)))
        }
        val pages = PdfPaginator.paginate(heights, CONTENT_HEIGHT, BLOCK_GAP)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, slices ->
                if (isCancelled()) return false
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(info)
                drawPage(page.canvas, slices, blocks, texts, index + 1, pages.size)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return true
    }

    private fun drawPage(
        canvas: Canvas, slices: List<PdfPaginator.Slice>, blocks: List<CharSequence>, texts: PdfTexts,
        number: Int, total: Int,
    ) {
        drawRunningHeader(canvas, texts.header.title + " - " + texts.header.lines.first())
        var current = -1
        var layout: StaticLayout? = null
        for (slice in slices) {
            if (slice.block != current) {
                current = slice.block
                layout = layout(blocks[current])
            }
            drawSlice(canvas, layout ?: continue, slice)
        }
        drawFooter(canvas, texts, number, total)
    }

    private fun drawSlice(canvas: Canvas, layout: StaticLayout, slice: PdfPaginator.Slice) {
        canvas.save()
        canvas.translate(MARGIN, CONTENT_TOP + slice.y)
        canvas.clipRect(0f, 0f, CONTENT_WIDTH.toFloat(), slice.height)
        canvas.translate(0f, -layout.getLineTop(slice.firstLine).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawRunningHeader(canvas: Canvas, text: String) {
        val line = TextUtils.ellipsize(text, footerPaint, CONTENT_WIDTH.toFloat(), TextUtils.TruncateAt.END)
        val layout = layout(line, footerPaint)
        canvas.save()
        canvas.translate(MARGIN, MARGIN)
        layout.draw(canvas)
        canvas.restore()
        canvas.drawLine(MARGIN, CONTENT_TOP - RULE_GAP, MARGIN + CONTENT_WIDTH, CONTENT_TOP - RULE_GAP, linePaint)
    }

    private fun drawFooter(canvas: Canvas, texts: PdfTexts, number: Int, total: Int) {
        val top = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
        canvas.drawLine(MARGIN, top - RULE_GAP, MARGIN + CONTENT_WIDTH, top - RULE_GAP, linePaint)
        val text = listOf(texts.notCertified, texts.verification, texts.page(number, total))
            .joinToString("\n", transform = PdfText::isolate)
        canvas.save()
        canvas.translate(MARGIN, top)
        layout(text, footerPaint, Layout.Alignment.ALIGN_CENTER).draw(canvas)
        canvas.restore()
    }

    private fun headerText(header: ExportHeader): CharSequence {
        val text = SpannableStringBuilder()
        text.appendStyled(header.title, TITLE_SIZE, Color.BLACK, bold)
        header.lines.forEach { text.append('\n').appendStyled(it, SMALL_SIZE + 1f, Color.DKGRAY, regular) }
        return text
    }

    private fun rowText(row: ExportRow, texts: PdfTexts): CharSequence {
        val text = SpannableStringBuilder()
        text.appendStyled(isolate(row.time) + "  -  " + isolate(row.status), SMALL_SIZE + 1f, Color.BLACK, bold)
        text.append('\n')
        val people = isolate(texts.from(row.from)) + "   " + isolate(texts.to(row.to))
        text.appendStyled(people, SMALL_SIZE, Color.DKGRAY, regular)
        if (row.body.isNotEmpty()) text.append('\n').append(isolate(row.body))
        row.attachments.forEach { text.append('\n').appendStyled(isolate(it), SMALL_SIZE, Color.DKGRAY, regular) }
        row.note?.let { text.append('\n').appendStyled(isolate(it), SMALL_SIZE, Color.DKGRAY, bold) }
        return text
    }

    private fun SpannableStringBuilder.appendStyled(text: String, size: Float, color: Int, face: Typeface) =
        apply {
            val start = length
            append(text)
            setSpan(StyleSpan(face, size, color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun layout(
        text: CharSequence,
        paint: TextPaint = bodyPaint,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
        .setAlignment(alignment)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, LINE_SPACING)
        .setIncludePad(false)
        .build()

    private fun paint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = face
    }

    /** A run of text with its own font, size and colour inside one layout. */
    private class StyleSpan(private val face: Typeface, private val size: Float, private val color: Int) :
        MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = style(paint)
        override fun updateMeasureState(paint: TextPaint) = style(paint)

        private fun style(paint: TextPaint) {
            paint.typeface = face
            paint.textSize = size
            paint.color = color
        }
    }

    companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40f
        private const val MARGIN_PX = 40
        private const val RUNNING_HEADER_HEIGHT = 16f
        private const val FOOTER_HEIGHT = 40f
        private const val RULE_GAP = 4f
        private const val RULE_WIDTH = 0.5f
        private const val BLOCK_GAP = 10f
        private const val LINE_SPACING = 1.15f
        private const val BODY_SIZE = 10.5f
        private const val SMALL_SIZE = 8.5f
        private const val FOOTER_SIZE = 7f
        private const val TITLE_SIZE = 18f
        private const val CONTENT_TOP = MARGIN + RUNNING_HEADER_HEIGHT + RULE_GAP * 2
        private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_PX
        private const val CONTENT_HEIGHT = PAGE_HEIGHT - CONTENT_TOP - FOOTER_HEIGHT - MARGIN - RULE_GAP * 2
    }
}
