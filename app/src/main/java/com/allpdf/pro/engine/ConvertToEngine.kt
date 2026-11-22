package com.allpdf.pro.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.allpdf.pro.office.DocModel
import com.allpdf.pro.office.DocxReader
import com.allpdf.pro.office.PptxReader
import com.allpdf.pro.office.SheetModel
import com.allpdf.pro.office.SlideModel
import com.allpdf.pro.office.XlsxReader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

object ConvertToEngine {

    enum class PageSize(val label: String, val rect: PDRectangle?) {
        FIT("Fit to image", null), A4("A4", PDRectangle.A4), LETTER("Letter", PDRectangle.LETTER)
    }
    enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }
    enum class Margin(val label: String, val pt: Float) { NONE("None", 0f), SMALL("Small", 20f), BIG("Big", 48f) }

    fun imagesToPdf(images: List<File>, size: PageSize, orient: Orientation, margin: Margin, out: File, ctl: Ctl): File {
        require(images.isNotEmpty()) { "Select at least one image" }
        Pdf.newDoc().use { doc ->
            images.forEachIndexed { i, f ->
                ctl.step(i.toFloat() / images.size, "Adding image ${i + 1} of ${images.size}")
                val bmp = ImageIO.decode(f, 3500) ?: error("Can't read image ${f.name}")
                val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
                // Fast path: embed original JPEG bytes when no EXIF rotation/downscale happened.
                val ext = f.extension.lowercase()
                val rawJpeg = (ext == "jpg" || ext == "jpeg") && sameDims(f, bmp.width, bmp.height)
                val x = if (rawJpeg) FileInputStream(f).use { JPEGFactory.createFromStream(doc, it) }
                        else ImageIO.toXObject(doc, bmp, ImageIO.hasAlpha(f) || ext == "png", 0.9f)
                bmp.recycle()
                val m = margin.pt
                var pw: Float; var ph: Float
                if (size.rect == null) { pw = iw * 72f / 150f + 2 * m; ph = ih * 72f / 150f + 2 * m }
                else { pw = size.rect.width; ph = size.rect.height }
                val landscape = when (orient) { Orientation.LANDSCAPE -> true; Orientation.PORTRAIT -> false; Orientation.AUTO -> iw > ih }
                if (size.rect != null && landscape != (pw > ph)) { val t = pw; pw = ph; ph = t }
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val aw = pw - 2 * m; val ah = ph - 2 * m
                val s = minOf(aw / iw, ah / ih)
                val dw = iw * s; val dh = ih * s
                PDPageContentStream(doc, page).use { it.drawImage(x, m + (aw - dw) / 2, m + (ah - dh) / 2, dw, dh) }
            }
            ctl.step(0.98f, "Saving PDF…")
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    private fun sameDims(f: File, w: Int, h: Int): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        val rot = try {
            androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
        } catch (_: Exception) { 1 }
        return o.outWidth == w && o.outHeight == h && rot <= 1
    }

    // ---------------------------------------------------------------- Word

    fun wordToPdf(input: File, out: File, ctl: Ctl): File {
        ctl.step(0.05f, "Parsing document…")
        val model = when (input.extension.lowercase()) {
            "doc" -> LegacyOffice.readDoc(input)
            "txt", "md" -> textModel(input.readText())
            "rtf" -> textModel(stripRtf(input.readText()))
            else -> if (isZip(input)) DocxReader.read(input) else LegacyOffice.readDoc(input)
        }
        return OfficeRender.renderDoc(model, out, ctl.sub(0.1f, 1f))
    }

    fun textModel(text: String): DocModel {
        val m = DocModel()
        for (line in text.split("\n")) {
            val p = DocModel.Para()
            val t = line.trimEnd()
            when {
                t.startsWith("### ") -> { p.heading = 3; p.runs.add(DocModel.Run(t.substring(4))) }
                t.startsWith("## ") -> { p.heading = 2; p.runs.add(DocModel.Run(t.substring(3))) }
                t.startsWith("# ") -> { p.heading = 1; p.runs.add(DocModel.Run(t.substring(2))) }
                t.startsWith("- ") || t.startsWith("* ") -> { p.bullet = true; p.runs.add(DocModel.Run(t.substring(2))) }
                else -> p.runs.add(DocModel.Run(t))
            }
            m.blocks.add(p)
        }
        return m
    }

    private fun stripRtf(s: String): String = s
        .replace(Regex("\\\\par[d]?"), "\n")
        .replace(Regex("\\{\\\\\\*[^{}]*\\}"), "")
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        .replace(Regex("[{}]"), "")

    fun isZip(f: File): Boolean = try {
        FileInputStream(f).use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- PowerPoint

    fun pptToPdf(input: File, out: File, ctl: Ctl): File {
        ctl.step(0.05f, "Parsing slides…")
        val model: SlideModel = if (isZip(input)) PptxReader.read(input) else LegacyOffice.readPpt(input)
        return OfficeRender.renderSlides(model, out, ctl.sub(0.1f, 1f))
    }

    // ---------------------------------------------------------------- Excel

    fun excelToPdf(input: File, out: File, ctl: Ctl): File {
        ctl.step(0.05f, "Parsing workbook…")
        val model: SheetModel = when {
            input.extension.lowercase() == "csv" -> csvModel(input)
            isZip(input) -> XlsxReader.read(input)
            else -> LegacyOffice.readXls(input)
        }
        return OfficeRender.renderSheets(model, out, ctl.sub(0.1f, 1f))
    }

    fun csvModel(f: File): SheetModel {
        val m = SheetModel(); val s = SheetModel.Sheet(); s.name = f.baseName()
        f.bufferedReader().useLines { lines ->
            lines.forEachIndexed { r, line ->
                parseCsvLine(line).forEachIndexed { c, v ->
                    s.put(r, c, v)
                    if (v.trim().matches(Regex("-?[\\d,]+(\\.\\d+)?"))) s.numeric.add("$r:$c")
                }
            }
        }
        m.sheets.add(s); return m
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        val sep = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> q = !q
                ch == sep && !q -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString()); return out
    }

    // ---------------------------------------------------------------- HTML

    /** Renders HTML through the system WebView into vector PDF pages. Network loads are blocked. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun htmlToPdf(ctx: Context, html: String?, file: File?, landscape: Boolean, out: File, ctl: Ctl): File = withContext(Dispatchers.Main) {
        ctl.step(0.05f, "Loading HTML…")
        val density = ctx.resources.displayMetrics.density
        val pageW = if (landscape) 842f else 595f
        val pageH = if (landscape) 595f else 842f
        val margin = 28f
        val cssWidth = if (landscape) 1123 else 794
        val viewW = (cssWidth * density).toInt()
        val wv = WebView(ctx)
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                allowFileAccess = true
                loadWithOverviewMode = false
                useWideViewPort = false
            }
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.measure(View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, viewW, 1000)
            withTimeout(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { if (cont.isActive) cont.resume(Unit) }
                    }
                    if (file != null) wv.loadUrl("file://" + file.absolutePath)
                    else wv.loadDataWithBaseURL(null, html ?: "", "text/html", "utf-8", null)
                }
            }
            delay(700)
            ctl.step(0.4f, "Laying out…")
            wv.measure(View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val contentH = maxOf(wv.measuredHeight, (wv.contentHeight * density).toInt(), 1)
            wv.layout(0, 0, viewW, contentH)
            delay(300)
            val scale = (pageW - 2 * margin) / viewW
            val sliceH = ((pageH - 2 * margin) / scale).toInt()
            val pages = ((contentH + sliceH - 1) / sliceH).coerceIn(1, 500)
            val pdf = PdfDocument()
            for (i in 0 until pages) {
                ctl.step(0.4f + 0.55f * i / pages, "Rendering page ${i + 1} of $pages")
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), i + 1).create())
                val c = page.canvas
                c.save()
                c.translate(margin, margin)
                c.scale(scale, scale)
                c.clipRect(0, 0, viewW, sliceH)
                c.translate(0f, -(i * sliceH).toFloat())
                wv.draw(c)
                c.restore()
                pdf.finishPage(page)
            }
            FileOutputStream(out).use { pdf.writeTo(it) }
            pdf.close()
            ctl.step(1f)
            out
        } finally {
            wv.destroy()
        }
    }
}

/** Legacy binary Office formats via Apache POI 3.17 (text, tables and pictures; no java.awt usage). */
object LegacyOffice {
    fun readDoc(f: File): DocModel {
        val m = DocModel()
        FileInputStream(f).use { input ->
            val doc = org.apache.poi.hwpf.HWPFDocument(input)
            val range = doc.range
            val styles = doc.styleSheet
            var i = 0
            while (i < range.numParagraphs()) {
                val p = range.getParagraph(i)
                if (p.isInTable) {
                    try {
                        val t = range.getTable(p)
                        val table = DocModel.Table()
                        for (r in 0 until t.numRows()) {
                            val row = t.getRow(r)
                            val cells = ArrayList<DocModel.Cell>()
                            for (c in 0 until row.numCells()) {
                                val cell = DocModel.Cell()
                                val para = DocModel.Para()
                                para.runs.add(DocModel.Run(clean(row.getCell(c).text())))
                                cell.paras.add(para); cells.add(cell)
                            }
                            table.rows.add(cells)
                        }
                        m.blocks.add(table)
                        i += t.numParagraphs()
                        continue
                    } catch (_: Throwable) { /* fall through as text */ }
                }
                val para = DocModel.Para()
                val styleName = try { styles.getStyleDescription(p.styleIndex.toInt())?.name ?: "" } catch (_: Throwable) { "" }
                val sn = styleName.lowercase().replace(" ", "")
                if (sn == "title") para.heading = 9
                else if (sn.startsWith("heading")) para.heading = sn.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
                if (p.isInList) para.bullet = true
                for (k in 0 until p.numCharacterRuns()) {
                    val cr = p.getCharacterRun(k)
                    val run = DocModel.Run(clean(cr.text()))
                    run.bold = cr.isBold; run.italic = cr.isItalic
                    run.sizePt = cr.fontSize / 2f
                    if (run.text.isNotEmpty()) para.runs.add(run)
                }
                if (para.text().contains('\u000C')) { m.blocks.add(DocModel.PageBreak()) }
                m.blocks.add(para)
                i++
            }
            try {
                for (pic in doc.picturesTable.allPictures) m.blocks.add(DocModel.Image(pic.content, 0f, 0f))
            } catch (_: Throwable) {}
        }
        return m
    }

    private fun clean(s: String): String = s.replace("\u0007", "").replace("\r", "").replace("\u000C", "").replace(Regex("[\\u0000-\\u0008\\u000B\\u000E-\\u001F]"), "")

    fun readXls(f: File): SheetModel {
        val m = SheetModel()
        FileInputStream(f).use { input ->
            val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook(input)
            val fmt = org.apache.poi.ss.usermodel.DataFormatter()
            for (si in 0 until wb.numberOfSheets) {
                if (wb.isSheetHidden(si)) continue
                val sh = wb.getSheetAt(si)
                val s = SheetModel.Sheet(); s.name = wb.getSheetName(si)
                for (row in sh) {
                    for (cell in row) {
                        val r = cell.rowIndex; val c = cell.columnIndex
                        val type = cell.cellTypeEnum
                        val effective = if (type == org.apache.poi.ss.usermodel.CellType.FORMULA) cell.cachedFormulaResultTypeEnum else type
                        val text = try {
                            when (effective) {
                                org.apache.poi.ss.usermodel.CellType.NUMERIC ->
                                    fmt.formatRawCellContents(cell.numericCellValue, cell.cellStyle.dataFormat.toInt(), cell.cellStyle.dataFormatString)
                                org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
                                org.apache.poi.ss.usermodel.CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
                                org.apache.poi.ss.usermodel.CellType.ERROR -> "#ERR"
                                else -> ""
                            }
                        } catch (_: Throwable) { "" }
                        s.put(r, c, text)
                        if (effective == org.apache.poi.ss.usermodel.CellType.NUMERIC) s.numeric.add("$r:$c")
                        try { if (wb.getFontAt(cell.cellStyle.fontIndex).bold) s.bold.add("$r:$c") } catch (_: Throwable) {}
                    }
                }
                for (c in 0..s.maxCol().coerceAtLeast(0)) {
                    try { s.colWidthChars[c] = sh.getColumnWidth(c) / 256f } catch (_: Throwable) {}
                }
                m.sheets.add(s)
            }
        }
        return m
    }

    fun readPpt(f: File): SlideModel {
        val m = SlideModel()
        m.widthEmu = 720L * 12700; m.heightEmu = 540L * 12700
        FileInputStream(f).use { input ->
            val ss = org.apache.poi.hslf.usermodel.HSLFSlideShow(input)
            for (slide in ss.slides) {
                val out = SlideModel.Slide()
                val texts = ArrayList<String>()
                for (shape in slide.shapes) {
                    if (shape is org.apache.poi.hslf.usermodel.HSLFTextShape) {
                        val t = try { shape.text } catch (_: Throwable) { null }
                        if (!t.isNullOrBlank()) texts.add(t)
                    }
                }
                val pics = ArrayList<ByteArray>()
                for (shape in slide.shapes) if (shape is org.apache.poi.hslf.usermodel.HSLFPictureShape) {
                    try { shape.pictureData?.data?.let { pics.add(it) } } catch (_: Throwable) {}
                }
                val e = 12700L
                if (texts.isNotEmpty()) {
                    val title = SlideModel.Shape().apply {
                        x = 40 * e; y = 30 * e; w = 640 * e; h = 80 * e; hasXfrm = true; phType = "title"; defaultSize = 32f; anchor = 1
                        paras.add(DocModel.Para().apply { runs.add(DocModel.Run(texts[0]).apply { bold = true }) })
                    }
                    out.shapes.add(title)
                }
                if (texts.size > 1) {
                    val body = SlideModel.Shape().apply {
                        x = 50 * e; y = 125 * e; w = if (pics.isEmpty()) 620 * e else 360 * e; h = 380 * e; hasXfrm = true; defaultSize = 18f
                        for (t in texts.drop(1)) for (line in t.split('\r', '\n')) if (line.isNotBlank())
                            paras.add(DocModel.Para().apply { bullet = true; runs.add(DocModel.Run(line.trim())) })
                    }
                    out.shapes.add(body)
                }
                pics.firstOrNull()?.let { bytes ->
                    out.shapes.add(SlideModel.Shape().apply {
                        image = bytes; hasXfrm = true
                        if (texts.size > 1) { x = 420 * e; y = 125 * e; w = 260 * e; h = 260 * e } else { x = 110 * e; y = 120 * e; w = 500 * e; h = 380 * e }
                    })
                }
                m.slides.add(out)
            }
        }
        return m
    }
}
