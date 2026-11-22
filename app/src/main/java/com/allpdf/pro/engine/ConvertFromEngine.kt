package com.allpdf.pro.engine

import android.graphics.Bitmap
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.Env
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.PageFrame
import com.allpdf.pro.core.PageRenderer
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.allpdf.pro.ooxml.DocxWriter
import com.allpdf.pro.ooxml.PptxWriter
import com.allpdf.pro.ooxml.XlsxWriter
import com.allpdf.pro.text.Layout
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

object ConvertFromEngine {

    // ---------------------------------------------------------------- PDF -> images

    /** Renders pages in parallel (one PdfRenderer per worker thread). */
    suspend fun renderPages(input: File, pages: List<Int>?, dpi: Int, png: Boolean, dir: File, ctl: Ctl): List<File> = coroutineScope {
        val total = pages ?: (0 until Pdf.pageCount(input)).toList()
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val done = AtomicInteger(0)
        val outs = arrayOfNulls<File>(total.size)
        (0 until workers).map { w ->
            async(Dispatchers.Default) {
                PageRenderer(input).use { r ->
                    var k = w
                    while (k < total.size) {
                        ctl.check()
                        val idx = total[k]
                        val bmp = r.renderAtDpi(idx, dpi)
                        val f = File(dir, "${input.baseName()}_page${idx + 1}.${if (png) "png" else "jpg"}")
                        FileOutputStream(f).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                        outs[k] = f
                        ctl.step(done.incrementAndGet().toFloat() / total.size, "Rendered ${done.get()} of ${total.size} pages")
                        k += workers
                    }
                }
            }
        }.awaitAll()
        outs.filterNotNull()
    }

    fun extractImages(input: File, dir: File, ctl: Ctl): List<File> {
        val outs = ArrayList<File>()
        Pdf.load(input).use { doc ->
            val seen = IdentityHashMap<COSBase, Boolean>()
            val forms = IdentityHashMap<COSBase, Boolean>()
            val n = doc.numberOfPages
            for (i in 0 until n) {
                ctl.step(i.toFloat() / n, "Scanning page ${i + 1} of $n")
                val res = doc.getPage(i).resources ?: continue
                collect(res, seen, forms) { img ->
                    if (img.width < 16 || img.height < 16) return@collect
                    val (bytes, ext) = PdfImages.bytesOf(img) ?: return@collect
                    val f = File(dir, "${input.baseName()}_p${i + 1}_img${outs.size + 1}.$ext")
                    f.writeBytes(bytes); outs.add(f)
                }
            }
        }
        if (outs.isEmpty()) error("No embedded images were found in this PDF")
        ctl.step(1f)
        return outs
    }

    private fun collect(res: PDResources, seen: IdentityHashMap<COSBase, Boolean>, forms: IdentityHashMap<COSBase, Boolean>, sink: (PDImageXObject) -> Unit) {
        for (name in res.xObjectNames) {
            val xo = try { res.getXObject(name) } catch (_: Exception) { null } ?: continue
            when (xo) {
                is PDImageXObject -> if (seen.put(xo.cosObject, true) == null) sink(xo)
                is PDFormXObject -> if (forms.put(xo.cosObject, true) == null) xo.resources?.let { collect(it, seen, forms, sink) }
            }
        }
    }

    private fun analyze(doc: PDDocument, ctl: Ctl): List<Layout.Page> {
        val s = WordStripper()
        val n = doc.numberOfPages
        return (0 until n).map { i -> ctl.step(i.toFloat() / n, "Reading page ${i + 1} of $n"); s.page(doc, i) }
    }

    // ---------------------------------------------------------------- PDF -> Word

    fun toDocx(input: File, includeImages: Boolean, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val pages = analyze(doc, ctl.sub(0f, 0.5f))
            val items = Layout.analyze(pages)
            val w = DocxWriter()
            w.title = input.baseName()
            val byPage = items.groupBy { it.page }
            val r = if (includeImages) PageRenderer(input) else null
            try {
                for (pi in pages.indices) {
                    ctl.step(0.5f + 0.5f * pi / pages.size, "Building page ${pi + 1}")
                    val pageItems = byPage[pi].orEmpty()
                    var hasText = false
                    for (it in pageItems) when (it.kind) {
                        Layout.Kind.HEADING -> { w.heading(it.text, it.level); hasText = true }
                        Layout.Kind.PARAGRAPH -> {
                            val run = DocxWriter.Run(it.text); run.bold = it.bold
                            w.paragraph(listOf(run), 0); hasText = true
                        }
                        Layout.Kind.BULLET -> { if (Layout.isSymbolBullet(it.text)) w.bullet(Layout.stripSymbolBullet(it.text)) else w.paragraph(it.text); hasText = true }
                        Layout.Kind.TABLE -> { w.table(it.rows); hasText = true }
                        Layout.Kind.PAGE_BREAK -> {}
                    }
                    if (includeImages) {
                        val page = doc.getPage(pi)
                        val placed = ImageLocator(page).run()
                        for (p in placed) {
                            val (bytes, _) = PdfImages.bytesOf(p.image) ?: continue
                            w.image(bytes, p.w, p.h)
                        }
                        // Scanned page without text: keep the page as a picture.
                        if (!hasText && placed.isEmpty() && r != null) {
                            val bmp = r.renderAtDpi(pi, 130)
                            val (pw, ph) = r.pageSize(pi)
                            w.image(ImageIO.jpgBytes(bmp, 85), pw, ph); bmp.recycle()
                        }
                    }
                    if (pi < pages.size - 1) w.pageBreak()
                }
            } finally { r?.close() }
            FileOutputStream(out).use { w.write(it) }
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- PDF -> PowerPoint

    /** editable=false: every page becomes a slide picture (exact look); true: editable text boxes + images. */
    fun toPptx(input: File, editable: Boolean, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val first = PageFrame(doc.getPage(0))
            val emu = 12700L
            val w = PptxWriter((first.width * emu).toLong(), (first.height * emu).toLong())
            w.title = input.baseName()
            val sx = w.cx / first.width / emu; val sy = w.cy / first.height / emu
            val stripper = if (editable) WordStripper() else null
            PageRenderer(input).use { r ->
                for (i in 0 until doc.numberOfPages) {
                    ctl.step(i.toFloat() / doc.numberOfPages, "Slide ${i + 1} of ${doc.numberOfPages}")
                    w.addSlide()
                    val frame = PageFrame(doc.getPage(i))
                    val fx = first.width / frame.width; val fy = first.height / frame.height
                    if (!editable) {
                        val bmp = r.renderAtDpi(i, 150)
                        w.pictureFull(ImageIO.jpgBytes(bmp, 88)); bmp.recycle()
                    } else {
                        for (p in ImageLocator(doc.getPage(i)).run()) {
                            val (bytes, _) = PdfImages.bytesOf(p.image) ?: continue
                            w.picture(bytes, (p.x * fx * sx * emu).toLong(), (p.y * fy * sy * emu).toLong(), (p.w * fx * sx * emu).toLong(), (p.h * fy * sy * emu).toLong())
                        }
                        val page = stripper!!.page(doc, i)
                        // Group lines into blocks (paragraph-ish) for nicer text boxes.
                        var block = ArrayList<Layout.Line>()
                        fun flush() {
                            if (block.isEmpty()) return
                            val x0 = block.minOf { it.x0 }; val x1 = block.maxOf { it.x1 }
                            val top = block.minOf { it.top }; val bottom = block.maxOf { it.bottom }
                            val size = block.map { it.size }.average().toFloat()
                            w.textBox((x0 * fx * sx * emu).toLong(), (top * fy * sy * emu).toLong(),
                                ((x1 - x0) * fx * sx * emu * 1.08).toLong(), ((bottom - top) * fy * sy * emu).toLong(),
                                block.joinToString("\n") { it.text() }, size * fy * sy, block.all { it.bold }, null)
                            block = ArrayList()
                        }
                        var prev: Layout.Line? = null
                        for (l in page.lines) {
                            val p = prev
                            if (p != null && (l.top - p.bottom > l.size * 0.9f || kotlin.math.abs(l.size - p.size) > 1.5f || kotlin.math.abs(l.x0 - p.x0) > l.size * 3)) flush()
                            block.add(l); prev = l
                        }
                        flush()
                        if (page.lines.isEmpty()) {
                            val bmp = r.renderAtDpi(i, 150)
                            w.pictureFull(ImageIO.jpgBytes(bmp, 88)); bmp.recycle()
                        }
                    }
                }
            }
            FileOutputStream(out).use { w.write(it) }
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- PDF -> Excel

    fun toXlsx(input: File, oneSheet: Boolean, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val pages = analyze(doc, ctl.sub(0f, 0.8f))
            val x = XlsxWriter()
            val all = ArrayList<List<Any>>()
            pages.forEachIndexed { i, p ->
                ctl.step(0.8f + 0.2f * i / pages.size, "Detecting tables on page ${i + 1}")
                val grid = Layout.grid(p.lines).map { row -> row.map { XlsxWriter.autoType(it) as Any } }
                if (oneSheet) { all.addAll(grid); if (grid.isNotEmpty()) all.add(emptyList()) }
                else x.sheet("Page ${i + 1}", grid, false)
            }
            if (oneSheet) x.sheet(input.baseName(), all, false)
            if (pages.all { it.lines.isEmpty() }) error("No text found. For scanned PDFs, run OCR PDF first.")
            FileOutputStream(out).use { x.write(it) }
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- PDF -> Markdown

    fun toMarkdown(input: File, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val pages = analyze(doc, ctl.sub(0f, 0.9f))
            if (pages.all { it.lines.isEmpty() }) error("No text found. For scanned PDFs, run OCR PDF first.")
            val title = doc.documentInformation?.title?.takeIf { it.isNotBlank() }
            val md = (if (title != null) "# $title\n\n" else "") + Layout.toMarkdown(Layout.analyze(pages))
            out.writeText(md)
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- PDF -> PDF/A

    data class PdfAResult(val out: File, val notes: List<String>)

    /** strict=true rasterizes pages (guaranteed font compliance) and keeps text searchable invisibly. */
    fun toPdfA(input: File, strict: Boolean, out: File, ctl: Ctl): PdfAResult {
        val notes = ArrayList<String>()
        val title = input.baseName()
        if (strict) {
            Pdf.load(input).use { src ->
                PageRenderer(input).use { r ->
                    Pdf.newDoc().use { dst ->
                        val font = Pdf.unicodeFont(dst)
                        val stripper = WordStripper()
                        val n = src.numberOfPages
                        for (i in 0 until n) {
                            ctl.step(i * 0.9f / n, "Archiving page ${i + 1} of $n")
                            val (pw, ph) = r.pageSize(i)
                            val bmp = r.renderAtDpi(i, 200)
                            val page = PDPage(PDRectangle(pw, ph)); dst.addPage(page)
                            val img = JPEGFactory.createFromImage(dst, bmp, 0.85f); bmp.recycle()
                            val words = try { stripper.page(src, i).lines.flatMap { it.words } } catch (_: Exception) { emptyList() }
                            val frame = PageFrame(page)
                            PDPageContentStream(dst, page).use { cs ->
                                cs.drawImage(img, 0f, 0f, pw, ph)
                                for (w in words) {
                                    val t = Pdf.encodable(font, w.text); if (t.isBlank()) continue
                                    val tw = Pdf.textWidth(font, t, 1f).takeIf { it > 0 } ?: continue
                                    cs.beginText(); cs.setRenderingMode(RenderingMode.NEITHER); cs.setFont(font, 1f)
                                    cs.setTextMatrix(frame.matrix(w.x0, w.bottom - w.size * 0.2f, (w.x1 - w.x0) / tw, w.size))
                                    cs.showText(t); cs.endText()
                                }
                            }
                        }
                        finishPdfA(dst, title)
                        dst.save(out)
                    }
                }
            }
            notes.add("Pages archived as images at 200 DPI with an invisible text layer (PDF/A-2B).")
        } else {
            Pdf.load(input).use { doc ->
                ctl.step(0.2f, "Checking fonts and annotations…")
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val missing = LinkedHashSet<String>()
                val fontsSeen = IdentityHashMap<COSBase, Boolean>()
                for (page in doc.pages) {
                    page.resources?.let { checkFonts(it, missing, fontsSeen, IdentityHashMap()) }
                    for (a in page.annotations) fixAnnotation(a)
                }
                doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("OpenAction"))
                doc.documentCatalog.cosObject.removeItem(COSName.AA)
                doc.documentCatalog.names?.let { names ->
                    names.cosObject.removeItem(COSName.JAVA_SCRIPT)
                    names.cosObject.removeItem(COSName.EMBEDDED_FILES)
                }
                ctl.step(0.6f, "Writing archival metadata…")
                finishPdfA(doc, doc.documentInformation?.title?.takeIf { it.isNotBlank() } ?: title)
                doc.save(out)
                if (missing.isNotEmpty()) notes.add("Not embedded: ${missing.take(6).joinToString()}${if (missing.size > 6) "…" else ""}. Use Strict mode for guaranteed compliance.")
                else notes.add("All fonts are embedded. Metadata, sRGB output intent and PDF/A-2B identification added.")
            }
        }
        ctl.step(1f)
        return PdfAResult(out, notes)
    }

    private fun checkFonts(res: PDResources, missing: MutableSet<String>, seen: IdentityHashMap<COSBase, Boolean>, forms: IdentityHashMap<COSBase, Boolean>) {
        for (name in res.fontNames) {
            val f = try { res.getFont(name) } catch (_: Exception) { null } ?: continue
            if (seen.put(f.cosObject, true) != null) continue
            if (!f.isEmbedded) missing.add(f.name ?: name.name)
        }
        for (name in res.xObjectNames) {
            val xo = try { res.getXObject(name) } catch (_: Exception) { null }
            if (xo is PDFormXObject && forms.put(xo.cosObject, true) == null) xo.resources?.let { checkFonts(it, missing, seen, forms) }
        }
    }

    private fun fixAnnotation(a: PDAnnotation) {
        a.isPrinted = true
        a.isHidden = false; a.isInvisible = false; a.isNoView = false
    }

    private fun finishPdfA(doc: PDDocument, title: String) {
        val cal = Calendar.getInstance()
        val info = doc.documentInformation
        info.title = title
        info.producer = "AllPDF Pro"
        info.creator = "AllPDF Pro"
        if (info.creationDate == null) info.creationDate = cal
        info.modificationDate = cal
        val created = info.creationDate ?: cal
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        iso.timeZone = TimeZone.getDefault()
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val author = info.author?.takeIf { it.isNotBlank() }
        val xmp = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/"><pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:format>application/pdf</dc:format><dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(title)}</rdf:li></rdf:Alt></dc:title>${if (author != null) "<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>" else ""}</rdf:Description>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:CreatorTool>AllPDF Pro</xmp:CreatorTool><xmp:CreateDate>${iso.format(created.time)}</xmp:CreateDate><xmp:ModifyDate>${iso.format(cal.time)}</xmp:ModifyDate></rdf:Description>
<rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/"><pdf:Producer>AllPDF Pro</pdf:Producer></rdf:Description>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>"""
        val meta = PDMetadata(doc, xmp.toByteArray(Charsets.UTF_8).inputStream())
        doc.documentCatalog.metadata = meta
        val catalogDict: COSDictionary = doc.documentCatalog.cosObject
        catalogDict.removeItem(COSName.getPDFName("OutputIntents"))
        val icc = Env.app.assets.open("srgb.icc")
        val intent = PDOutputIntent(doc, icc)
        intent.info = "sRGB IEC61966-2.1"
        intent.outputCondition = "sRGB IEC61966-2.1"
        intent.outputConditionIdentifier = "sRGB IEC61966-2.1"
        intent.registryName = "http://www.color.org"
        doc.documentCatalog.addOutputIntent(intent)
        icc.close()
        doc.version = maxOf(doc.version, 1.7f)
    }
}
