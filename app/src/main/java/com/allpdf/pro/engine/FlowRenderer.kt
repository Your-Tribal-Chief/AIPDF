package com.allpdf.pro.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.office.DocModel
import com.allpdf.pro.office.SheetModel
import com.allpdf.pro.office.SlideModel
import java.io.File
import java.io.FileOutputStream

/** Paginating vector renderer on Android's PdfDocument (Unicode text via the system font stack). */
class FlowRenderer(val pageW: Float = 595f, val pageH: Float = 842f, val margin: Float = 54f) {
    private val pdf = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNo = 0
    var y = 0f
        private set
    val contentW get() = pageW - 2 * margin
    val bottom get() = pageH - margin
    val canvas: Canvas get() = page?.canvas ?: newPage().canvas

    fun newPage(): PdfDocument.Page {
        page?.let { pdf.finishPage(it) }
        pageNo++
        val p = pdf.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNo).create())
        page = p
        y = margin
        return p
    }

    fun ensure(h: Float) { if (page == null || y + h > bottom) newPage() }

    fun gap(h: Float) { if (page == null) newPage(); y += h; if (y > bottom) newPage() }

    /** Draws a layout, splitting it across pages line by line when needed. */
    fun draw(layout: StaticLayout, x: Float = margin) {
        if (page == null) newPage()
        var line = 0
        while (line < layout.lineCount) {
            val startTop = layout.getLineTop(line).toFloat()
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - startTop <= bottom - y) last++
            val h = layout.getLineBottom(last) - startTop
            if (h > bottom - y && y > margin + 1) { newPage(); continue }
            val c = canvas
            c.save()
            c.translate(x, y - startTop)
            c.clipRect(0f, startTop, layout.width.toFloat() + 2f, startTop + h)
            layout.draw(c)
            c.restore()
            y += h
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
    }

    fun drawImage(bytes: ByteArray, wantW: Float, wantH: Float) {
        val b = decodeSampled(bytes, 1800) ?: return
        var w = if (wantW > 0) wantW else b.width * 0.75f
        var h = if (wantH > 0) wantH else b.height * 0.75f
        if (w > contentW) { h *= contentW / w; w = contentW }
        val maxH = pageH - 2 * margin
        if (h > maxH) { w *= maxH / h; h = maxH }
        ensure(h)
        canvas.drawBitmap(b, null, RectF(margin, y, margin + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        b.recycle()
        y += h + 6f
    }

    fun finish(out: File): File {
        if (page == null) newPage()
        page?.let { pdf.finishPage(it) }
        page = null
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    companion object {
        fun layout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1.15f): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
                .setAlignment(align).setLineSpacing(0f, spacing).setIncludePad(false).build()

        fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return null
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
}

object OfficeRender {
    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun spans(p: DocModel.Para, base: Float): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (r in p.runs) {
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            if (end == start) continue
            val style = (if (r.bold) Typeface.BOLD else 0) or (if (r.italic) Typeface.ITALIC else 0)
            if (style != 0) sb.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.sizePt > 0 && r.sizePt != base) sb.setSpan(AbsoluteSizeSpan(r.sizePt.toInt().coerceIn(4, 96)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.color != 0 && r.color != 0xFF000000.toInt()) sb.setSpan(ForegroundColorSpan(r.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun align(a: Int) = when (a) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }

    fun renderDoc(model: DocModel, out: File, ctl: Ctl): File {
        val r = FlowRenderer()
        val n = model.blocks.size.coerceAtLeast(1)
        model.blocks.forEachIndexed { i, b ->
            if (i % 20 == 0) ctl.step(i.toFloat() / n, "Laying out content…")
            when (b) {
                is DocModel.Para -> renderPara(r, b)
                is DocModel.Table -> renderTable(r, b.rows.map { row -> row.map { it.text() } }, b.columns())
                is DocModel.Image -> r.drawImage(b.data, b.widthPt, b.heightPt)
                is DocModel.PageBreak -> r.newPage()
            }
        }
        ctl.step(1f)
        return r.finish(out)
    }

    fun renderPara(r: FlowRenderer, p: DocModel.Para) {
        val (size, bold, before, after) = when (p.heading) {
            9 -> listOf(26f, 1f, 10f, 10f)
            1 -> listOf(20f, 1f, 14f, 6f)
            2 -> listOf(16f, 1f, 12f, 4f)
            3 -> listOf(13.5f, 1f, 10f, 3f)
            4, 5, 6 -> listOf(12f, 1f, 8f, 2f)
            else -> listOf(11f, 0f, 0f, 6f)
        }
        val tp = paint(size, bold > 0, if (p.heading in 1..3 || p.heading == 9) 0xFF1F3864.toInt() else Color.BLACK)
        val text = spans(p, size)
        if (text.isEmpty() || text.toString().isBlank()) { r.gap(size * 0.6f); return }
        if (p.bullet) {
            val marker = if (p.numberedIndex > 0) "${p.numberedIndex}.  " else "•  "
            text.insert(0, marker)
            val indent = (18 + 18 * p.indentLevel)
            text.setSpan(LeadingMarginSpan.Standard(indent, indent + 12), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        r.gap(before)
        r.draw(FlowRenderer.layout(text, tp, r.contentW.toInt(), align(p.align)))
        r.gap(after)
    }

    fun renderTable(r: FlowRenderer, rows: List<List<String>>, colsHint: Int = 0) {
        if (rows.isEmpty()) return
        val cols = maxOf(colsHint, rows.maxOf { it.size }).coerceAtLeast(1)
        val tp = paint(if (cols > 6) 8.5f else 10f)
        // Proportional widths by content length (bounded).
        val weights = FloatArray(cols) { c -> rows.maxOf { (it.getOrNull(c) ?: "").length.coerceIn(3, 40) }.toFloat() }
        val total = weights.sum()
        val widths = FloatArray(cols) { r.contentW * weights[it] / total }
        val line = Paint().apply { color = 0xFF9CA3AF.toInt(); strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val head = Paint().apply { color = 0xFFF3F4F6.toInt() }
        val pad = 4f
        rows.forEachIndexed { ri, row ->
            val layouts = (0 until cols).map { c -> FlowRenderer.layout(row.getOrNull(c) ?: "", tp, (widths[c] - 2 * pad).toInt()) }
            val h = (layouts.maxOf { it.height } + 2 * pad).coerceAtMost(r.bottom - r.margin)
            r.ensure(h)
            val c = r.canvas
            var x = r.margin
            for (ci in 0 until cols) {
                val rect = RectF(x, r.y, x + widths[ci], r.y + h)
                if (ri == 0) c.drawRect(rect, head)
                c.drawRect(rect, line)
                c.save(); c.clipRect(rect); c.translate(x + pad, r.y + pad); layouts[ci].draw(c); c.restore()
                x += widths[ci]
            }
            r.gap(h)
        }
        r.gap(8f)
    }

    // ---------------------------------------------------------------- Slides

    fun renderSlides(model: SlideModel, out: File, ctl: Ctl): File {
        val w = model.widthEmu / 12700f; val h = model.heightEmu / 12700f
        val pdf = PdfDocument()
        model.slides.forEachIndexed { i, s ->
            ctl.step(i.toFloat() / model.slides.size.coerceAtLeast(1), "Rendering slide ${i + 1}")
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), i + 1).create())
            drawSlide(page.canvas, s, w, h)
            pdf.finishPage(page)
        }
        if (model.slides.isEmpty()) pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), 1).create()))
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        ctl.step(1f)
        return out
    }

    private fun drawSlide(c: Canvas, s: SlideModel.Slide, w: Float, h: Float) {
        c.drawColor(if (s.background != 0) s.background else Color.WHITE)
        s.backgroundImage?.let { bytes ->
            FlowRenderer.decodeSampled(bytes, 2000)?.let { c.drawBitmap(it, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG)); it.recycle() }
        }
        val e = 12700f
        for (sh in s.shapes) {
            val rect = RectF(sh.x / e, sh.y / e, (sh.x + sh.w) / e, (sh.y + sh.h) / e)
            c.save()
            if (sh.rotation != 0) c.rotate(sh.rotation.toFloat(), rect.centerX(), rect.centerY())
            if (sh.fill != 0) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sh.fill }
                when (sh.geom) {
                    "ellipse" -> c.drawOval(rect, p)
                    "roundRect" -> c.drawRoundRect(rect, rect.height() * 0.15f, rect.height() * 0.15f, p)
                    else -> c.drawRect(rect, p)
                }
            }
            if (sh.line != 0) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sh.line; style = Paint.Style.STROKE; strokeWidth = 1f }
                if (sh.geom == "ellipse") c.drawOval(rect, p) else if (sh.geom == "line" || sh.geom == "straightConnector1") c.drawLine(rect.left, rect.top, rect.right, rect.bottom, p) else c.drawRect(rect, p)
            }
            sh.image?.let { bytes ->
                FlowRenderer.decodeSampled(bytes, 1600)?.let { c.drawBitmap(it, null, rect, Paint(Paint.FILTER_BITMAP_FLAG)); it.recycle() }
            }
            sh.table?.let { t ->
                if (t.isNotEmpty()) {
                    val cols = t.maxOf { it.size }.coerceAtLeast(1)
                    val rh = rect.height() / t.size; val cw = rect.width() / cols
                    val tp = paint((rh * 0.45f).coerceIn(6f, 16f))
                    val ln = Paint().apply { color = 0xFF6B7280.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f }
                    t.forEachIndexed { ri, row ->
                        for (ci in 0 until cols) {
                            val cr = RectF(rect.left + ci * cw, rect.top + ri * rh, rect.left + (ci + 1) * cw, rect.top + (ri + 1) * rh)
                            if (ri == 0) c.drawRect(cr, Paint().apply { color = 0xFFE5E7EB.toInt() })
                            c.drawRect(cr, ln)
                            val txt = TextUtils.ellipsize(row.getOrNull(ci) ?: "", tp, cw - 6, TextUtils.TruncateAt.END)
                            c.drawText(txt, 0, txt.length, cr.left + 3, cr.centerY() + tp.textSize / 3, tp)
                        }
                    }
                }
            }
            if (sh.paras.isNotEmpty() && sh.paras.any { it.text().isNotBlank() }) drawTextBox(c, sh, rect)
            c.restore()
        }
    }

    private fun drawTextBox(c: Canvas, sh: SlideModel.Shape, rect: RectF) {
        val inset = 7f
        val width = (rect.width() - 2 * inset).toInt().coerceAtLeast(10)
        var scale = 1f
        var layouts: List<StaticLayout>
        while (true) {
            layouts = sh.paras.map { p ->
                val base = (p.runs.firstOrNull { it.sizePt > 0 }?.sizePt ?: sh.defaultSize) * scale
                val tp = paint(base, p.runs.isNotEmpty() && p.runs.all { it.bold })
                val sp = SpannableStringBuilder()
                if (p.bullet && p.text().isNotBlank()) sp.append("• ")
                for (r in p.runs) {
                    val st = sp.length; sp.append(r.text); val en = sp.length
                    if (en == st) continue
                    if (r.sizePt > 0) sp.setSpan(AbsoluteSizeSpan((r.sizePt * scale).toInt().coerceAtLeast(4)), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.color != 0) sp.setSpan(ForegroundColorSpan(r.color), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val style = (if (r.bold) Typeface.BOLD else 0) or (if (r.italic) Typeface.ITALIC else 0)
                    if (style != 0) sp.setSpan(StyleSpan(style), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (p.indentLevel > 0) sp.setSpan(LeadingMarginSpan.Standard(18 * p.indentLevel), 0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                FlowRenderer.layout(sp, tp, width, align(p.align), 1.05f)
            }
            val total = layouts.sumOf { it.height } + inset * 2
            if (total <= rect.height() * 1.05f || scale < 0.45f) break
            scale *= 0.88f
        }
        val total = layouts.sumOf { it.height }.toFloat()
        var y = when (sh.anchor) { 1 -> rect.top + (rect.height() - total) / 2; 2 -> rect.bottom - total - inset; else -> rect.top + inset }
        for (l in layouts) {
            c.save(); c.translate(rect.left + inset, y); l.draw(c); c.restore()
            y += l.height
        }
    }

    // ---------------------------------------------------------------- Sheets

    fun renderSheets(model: SheetModel, out: File, ctl: Ctl): File {
        // Landscape A4 for spreadsheets.
        val r = FlowRenderer(842f, 595f, 30f)
        val title = paint(13f, true, 0xFF1F3864.toInt())
        model.sheets.forEachIndexed { si, sheet ->
            ctl.step(si.toFloat() / model.sheets.size.coerceAtLeast(1), "Rendering sheet ${sheet.name}")
            r.newPage()
            r.draw(FlowRenderer.layout(sheet.name, title, r.contentW.toInt()))
            r.gap(6f)
            val maxCol = sheet.maxCol()
            if (maxCol < 0) { r.draw(FlowRenderer.layout("(empty sheet)", paint(10f), r.contentW.toInt())); return@forEachIndexed }
            val font = 8.5f
            val tp = paint(font); val tpb = paint(font, true)
            val charW = tp.measureText("0")
            val widths = FloatArray(maxCol + 1) { c ->
                val declared = sheet.colWidthChars[c]
                val content = sheet.cells.values.maxOfOrNull { (it[c] ?: "").length } ?: 0
                val chars = declared ?: content.coerceIn(4, 40).toFloat()
                (chars.coerceIn(2f, 60f) * charW + 8f)
            }
            // Horizontal bands of columns that fit the page width.
            val bands = ArrayList<IntRange>()
            var start = 0; var acc = 0f
            val rowHead = 28f
            for (c in 0..maxCol) {
                if (acc + widths[c] > r.contentW - rowHead && c > start) { bands.add(start until c); start = c; acc = 0f }
                acc += widths[c]
            }
            bands.add(start..maxCol)
            val grid = Paint().apply { color = 0xFFD1D5DB.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.5f }
            val headFill = Paint().apply { color = 0xFFF3F4F6.toInt() }
            val rowH = font * 1.9f
            val rowsIdx = sheet.cells.keys.toList()
            for ((bi, band) in bands.withIndex()) {
                if (bi > 0) { r.newPage(); r.draw(FlowRenderer.layout("${sheet.name} (columns ${SheetModel.colName(band.first)}–${SheetModel.colName(band.last)})", title, r.contentW.toInt())); r.gap(6f) }
                fun header() {
                    r.ensure(rowH)
                    var x = r.margin + rowHead
                    val c = r.canvas
                    c.drawRect(RectF(r.margin, r.y, x, r.y + rowH), headFill)
                    for (col in band) {
                        val rect = RectF(x, r.y, x + widths[col], r.y + rowH)
                        c.drawRect(rect, headFill); c.drawRect(rect, grid)
                        c.drawText(SheetModel.colName(col), rect.centerX() - tpb.measureText(SheetModel.colName(col)) / 2, rect.centerY() + font / 3, tpb)
                        x += widths[col]
                    }
                    r.gap(rowH)
                }
                header()
                var prevRow = -1
                for (row in rowsIdx) {
                    ctl.check()
                    if (r.y + rowH > r.bottom) { r.newPage(); header() }
                    val cells = sheet.cells[row] ?: continue
                    val c = r.canvas
                    if (prevRow >= 0 && row > prevRow + 1 && row - prevRow > 50) { /* skip big empty spans */ }
                    prevRow = row
                    c.drawRect(RectF(r.margin, r.y, r.margin + rowHead, r.y + rowH), headFill)
                    val rn = (row + 1).toString()
                    c.drawText(rn, r.margin + rowHead - 3 - tp.measureText(rn), r.y + rowH / 2 + font / 3, tp)
                    var x = r.margin + rowHead
                    for (col in band) {
                        val rect = RectF(x, r.y, x + widths[col], r.y + rowH)
                        c.drawRect(rect, grid)
                        cells[col]?.let { v ->
                            val key = "$row:$col"
                            val p = if (sheet.bold.contains(key)) tpb else tp
                            val txt = TextUtils.ellipsize(v.replace('\n', ' '), p, widths[col] - 6, TextUtils.TruncateAt.END)
                            val tx = if (sheet.numeric.contains(key)) rect.right - 3 - p.measureText(txt, 0, txt.length) else rect.left + 3
                            c.drawText(txt, 0, txt.length, tx, rect.centerY() + font / 3, p)
                        }
                        x += widths[col]
                    }
                    r.gap(rowH)
                }
            }
        }
        ctl.step(1f)
        return r.finish(out)
    }
}
