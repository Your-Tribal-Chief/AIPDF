package com.allpdf.pro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.Env
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.engine.*
import com.allpdf.pro.ooxml.DocxWriter
import com.allpdf.pro.ooxml.PptxWriter
import com.allpdf.pro.ooxml.XlsxWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream

/** Runs every engine on-device against generated fixtures and validates the outputs. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnginesTest {
    companion object {
        lateinit var dir: File
        lateinit var pdf: File   // 3 pages, text + photo
        lateinit var pdf2: File  // modified copy
        lateinit var img: File

        @BeforeClass @JvmStatic fun setup() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Env.init(ctx); PDFBoxResourceLoader.init(ctx)
            dir = File(ctx.cacheDir, "itest").apply { deleteRecursively(); mkdirs() }
            img = File(dir, "photo.jpg").also { f ->
                val b = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
                val c = Canvas(b); c.drawColor(Color.rgb(30, 120, 200))
                val p = Paint().apply { textSize = 140f; color = Color.WHITE; isAntiAlias = true }
                for (i in 0 until 1200 step 7) { p.color = Color.rgb(i % 255, (i * 3) % 255, 200); c.drawLine(0f, i.toFloat(), 1600f, (1200 - i).toFloat(), p) }
                p.color = Color.WHITE; c.drawText("Invoice 2026", 200f, 650f, p)
                FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            }
            pdf = makePdf("sample.pdf", "Quarterly Report")
            pdf2 = makePdf("sample2.pdf", "Quarterly Report Revised")
        }

        fun makePdf(name: String, title: String): File {
            val f = File(dir, name)
            Pdf.newDoc().use { d ->
                for (pg in 0 until 3) {
                    val page = PDPage(PDRectangle.A4); d.addPage(page)
                    PDPageContentStream(d, page).use { cs ->
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 24f); cs.newLineAtOffset(60f, 760f); cs.showText("$title page ${pg + 1}"); cs.endText()
                        val lines = listOf("Solar energy is the radiant light and heat from the Sun harnessed by technology.",
                            "Photovoltaic panels convert sunlight directly into electricity using semiconductors.",
                            "Storage remains a challenge because the Sun does not shine at night.",
                            "Many countries are investing heavily in solar energy and storage infrastructure.")
                        cs.setFont(PDType1Font.HELVETICA, 11f)
                        lines.forEachIndexed { i, l -> cs.beginText(); cs.newLineAtOffset(60f, 720f - i * 16f); cs.showText(l); cs.endText() }
                        // a small table
                        listOf(listOf("Item", "Qty", "Price"), listOf("Panel", "4", "1200"), listOf("Battery", "2", "800"), listOf("Inverter", "1", "450")).forEachIndexed { r, row ->
                            row.forEachIndexed { c, v -> cs.beginText(); cs.newLineAtOffset(60f + c * 150f, 600f - r * 18f); cs.showText(v); cs.endText() }
                        }
                        val x = JPEGFactory.createFromStream(d, img.inputStream())
                        cs.drawImage(x, 60f, 180f, 400f, 300f)
                    }
                }
                d.save(f)
            }
            return f
        }

        fun out(n: String) = File(dir, n)
        fun valid(f: File, pages: Int? = null) {
            assertTrue("${f.name} missing", f.exists() && f.length() > 100)
            if (f.extension == "pdf") Pdf.load(f).use { d -> if (pages != null) assertEquals(f.name, pages, d.numberOfPages) else assertTrue(d.numberOfPages > 0) }
        }
    }
    private val c = Ctl()

    @Test fun a01_merge() = valid(OrganizeEngine.merge(listOf(pdf, pdf2), out("m.pdf"), c), 6)
    @Test fun a02_split() { val o = OrganizeEngine.split(pdf, "ranges", "1, 2-3", 1, false, dir, c); assertEquals(2, o.size); valid(o[1], 2)
        assertEquals(3, OrganizeEngine.split(pdf, "burst", "", 1, false, dir, c).size) }
    @Test fun a03_remove() = valid(OrganizeEngine.removePages(pdf, setOf(1), out("r.pdf"), c), 2)
    @Test fun a04_extract() { valid(OrganizeEngine.extractPages(pdf, listOf(0, 2), false, dir, c)[0], 2); assertTrue(OrganizeEngine.extractPages(pdf, listOf(0, 2), true, dir, c)[0].name.endsWith(".zip")) }
    @Test fun a05_organize() = valid(OrganizeEngine.organize(listOf(pdf, pdf2), listOf(PageRef.Source(0, 2, 90), PageRef.Blank(), PageRef.Source(1, 0)), out("o.pdf"), c), 3)
    @Test fun a06_rotate() { OrganizeEngine.rotate(pdf, 90, null, out("rot.pdf"), c); Pdf.load(out("rot.pdf")).use { assertEquals(90, it.getPage(0).rotation) } }
    @Test fun b01_compress() { val r = OptimizeEngine.compress(pdf, OptimizeEngine.Level.EXTREME, out("c.pdf"), c); valid(r.out, 3); assertTrue("not smaller ${r.before}->${r.after}", r.after < r.before) }
    @Test fun b02_repair() { val broken = out("broken.pdf"); val b = pdf.readBytes(); broken.writeBytes("GARBAGE".toByteArray() + b.copyOf(b.size - 30))
        valid(OptimizeEngine.repair(broken, out("rep.pdf"), dir, c).out) }
    @Test fun b03_ocr() { val r = OptimizeEngine.ocrImages(listOf(img), dir, c); valid(r.pdf, 1); assertTrue("ocr text: " + r.text.readText(), r.text.readText().contains("Invoice", true)) }
    @Test fun c01_images() = valid(ConvertToEngine.imagesToPdf(listOf(img, img), ConvertToEngine.PageSize.A4, ConvertToEngine.Orientation.AUTO, ConvertToEngine.Margin.SMALL, out("i.pdf"), c), 2)
    @Test fun c02_word() { val w = DocxWriter(); w.heading("Title", 1); w.paragraph("Hello world"); w.bullet("Item"); w.table(listOf(listOf("a", "b"), listOf("1", "2"))); w.image(img.readBytes(), 200f, 150f)
        val d = out("t.docx"); FileOutputStream(d).use { w.write(it) }; valid(ConvertToEngine.wordToPdf(d, out("w.pdf"), c)) }
    @Test fun c03_ppt() { val p = PptxWriter(9144000, 6858000); p.addSlide(); p.textBox(914400, 914400, 5000000, 800000, "Slide title", 32f, true, null); p.addSlide(); p.pictureFull(img.readBytes())
        val f = out("t.pptx"); FileOutputStream(f).use { p.write(it) }; valid(ConvertToEngine.pptToPdf(f, out("p.pdf"), c), 2) }
    @Test fun c04_excel() { val x = XlsxWriter(); x.sheet("S", (0 until 120).map { listOf<Any>("row $it", it.toDouble(), "x") }, true)
        val f = out("t.xlsx"); FileOutputStream(f).use { x.write(it) }; valid(ConvertToEngine.excelToPdf(f, out("x.pdf"), c)) }
    @Test fun c05_html() = runBlocking { valid(ConvertToEngine.htmlToPdf(InstrumentationRegistry.getInstrumentation().targetContext, "<h1>Hi</h1>" + "<p>para</p>".repeat(200), null, false, out("h.pdf"), c)) }
    @Test fun d01_toImages() = runBlocking { assertEquals(3, ConvertFromEngine.renderPages(pdf, null, 100, false, dir, c).size); assertTrue(ConvertFromEngine.extractImages(pdf, dir, c).isNotEmpty()) }
    @Test fun d02_toDocx() = valid(ConvertFromEngine.toDocx(pdf, true, out("o.docx"), c))
    @Test fun d03_toPptx() { valid(ConvertFromEngine.toPptx(pdf, false, out("a.pptx"), c)); valid(ConvertFromEngine.toPptx(pdf, true, out("b.pptx"), c)) }
    @Test fun d04_toXlsx() = valid(ConvertFromEngine.toXlsx(pdf, false, out("o.xlsx"), c))
    @Test fun d05_pdfa() { valid(ConvertFromEngine.toPdfA(pdf, false, out("a1.pdf"), c).out); valid(ConvertFromEngine.toPdfA(pdf, true, out("a2.pdf"), c).out, 3) }
    @Test fun d06_md() { val md = ConvertFromEngine.toMarkdown(pdf, out("o.md"), c).readText(); assertTrue(md, md.contains("#") && md.contains("|")) }
    @Test fun e01_resize() { EditEngine.resize(pdf, 612f, 792f, EditEngine.Fit.FIT, true, out("rs.pdf"), c); Pdf.load(out("rs.pdf")).use { assertEquals(612f, it.getPage(0).mediaBox.width, 0.5f) } }
    @Test fun e02_crop() = valid(EditEngine.crop(pdf, 0.1f, 0.1f, 0.9f, 0.5f, null, out("cr.pdf"), c), 3)
    @Test fun e03_numbers() = valid(EditEngine.pageNumbers(pdf, EditEngine.NumberOpts(format = 2, skipFirst = true), out("n.pdf"), c), 3)
    @Test fun e04_watermark() { valid(EditEngine.watermark(pdf, EditEngine.WatermarkOpts(position = 9), out("wm.pdf"), c)); valid(EditEngine.watermark(pdf, EditEngine.WatermarkOpts(text = null, image = img, under = true), out("wm2.pdf"), c)) }
    @Test fun e05_edit() = valid(EditEngine.applyOverlays(pdf, listOf(
        Overlay.Text(0, 0.1f, 0.1f, "Hello\nনমস্কার", 14f, Color.RED), Overlay.Image(0, 0.5f, 0.5f, 0.3f, 0.2f, img),
        Overlay.Shape(1, Overlay.ShapeKind.CIRCLE, 0.1f, 0.1f, 0.4f, 0.3f, Color.BLUE, 2f), Overlay.Shape(1, Overlay.ShapeKind.ARROW, 0.1f, 0.5f, 0.6f, 0.6f, Color.BLACK, 2f),
        Overlay.Shape(2, Overlay.ShapeKind.HIGHLIGHT, 0.1f, 0.1f, 0.6f, 0.15f, Color.YELLOW, 1f), Overlay.Ink(2, listOf(0.1f to 0.5f, 0.2f to 0.6f, 0.3f to 0.5f), Color.BLACK, 3f)), out("ed.pdf"), c), 3)
    @Test fun e06_forms() {
        val f1 = EditEngine.fillForm(pdf, emptyMap(), listOf(Overlay.Field(0, Overlay.FieldKind.TEXT, 0.1f, 0.1f, 0.5f, 0.15f, "name"), Overlay.Field(0, Overlay.FieldKind.CHECKBOX, 0.6f, 0.1f, 0.65f, 0.13f, "agree")), false, out("f1.pdf"), c)
        val fields = EditEngine.readFields(f1); assertEquals(2, fields.size)
        val f2 = EditEngine.fillForm(f1, mapOf("name" to "Sajid", "agree" to "true"), emptyList(), false, out("f2.pdf"), c)
        assertEquals("Sajid", EditEngine.readFields(f2).first { it.name == "name" }.value)
        valid(EditEngine.fillForm(f2, emptyMap(), emptyList(), true, out("f3.pdf"), c))
    }
    @Test fun f01_protectUnlock() { SecurityEngine.protect(pdf, "secret", "", false, false, false, out("pr.pdf"), c)
        assertTrue(Pdf.needsPassword(out("pr.pdf"))); valid(SecurityEngine.unlock(out("pr.pdf"), "secret", out("un.pdf"), c), 3); assertTrue(!Pdf.needsPassword(out("un.pdf"))) }
    @Test fun f00_textExtraction() { Pdf.load(pdf).use { d -> assertTrue(WordStripper().plainText(d, 0).contains("Quarterly")) } }
    @Test fun f02_redact() { SecurityEngine.redact(pdf, listOf(Overlay.Redact(0, 0.05f, 0.05f, 0.95f, 0.2f)), 120, true, out("rd.pdf"), c)
        Pdf.load(out("rd.pdf")).use { d -> val t = WordStripper().plainText(d, 0); assertTrue("text survived: $t", !t.contains("Quarterly")) } }
    @Test fun f03_compare() { val r = SecurityEngine.compare(pdf, pdf2, out("cmp.pdf"), c); valid(r.report); assertTrue(r.stats.modified + r.stats.added + r.stats.removed > 0) }
    @Test fun g01_workflow() { val o = ProductivityEngine.runWorkflow(listOf(pdf, pdf2), Workflow("wf", listOf(WorkflowStep("merge"), WorkflowStep("compress"), WorkflowStep("numbers"), WorkflowStep("watermark"), WorkflowStep("protect", mapOf("password" to "x")))), dir, c)
        assertEquals(1, o.size); assertTrue(Pdf.needsPassword(o[0])) }
    @Test fun g02_summarize() { val r = ProductivityEngine.summarize(pdf, 4, dir, c); assertTrue(r.markdown, r.markdown.contains("Key points")) }
}
