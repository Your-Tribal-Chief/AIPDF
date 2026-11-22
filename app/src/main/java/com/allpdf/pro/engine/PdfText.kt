package com.allpdf.pro.engine

import android.graphics.Bitmap
import com.allpdf.pro.core.PageFrame
import com.allpdf.pro.text.Layout
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream

/** Extracts positioned words (display coordinates, top-down) per page. */
class WordStripper : PDFTextStripper() {
    val words = ArrayList<Layout.Word>()

    init { sortByPosition = true }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (textPositions.isNullOrEmpty() || text.isNullOrBlank()) return
        // split on internal spaces so each Layout.Word is a real word
        var cur = ArrayList<TextPosition>()
        fun flush() {
            if (cur.isEmpty()) return
            val f = cur.first(); val l = cur.last()
            val size = cur.maxOf { tpSize(it) }
            val baseline = cur.maxOf { it.yDirAdj }
            val t = cur.joinToString("") { it.unicode ?: "" }.trim()
            if (t.isNotEmpty()) {
                val fontName = try { f.font?.name ?: "" } catch (_: Exception) { "" }
                val bold = fontName.contains("Bold", true) || fontName.contains("Black", true) || fontName.contains("Heavy", true) || fontName.contains("Semibold", true)
                words.add(Layout.Word(t, f.xDirAdj, l.xDirAdj + l.widthDirAdj, baseline - size * 0.8f, baseline + size * 0.2f, size, bold).also {
                    it.italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true)
                })
            }
            cur = ArrayList()
        }
        for (tp in textPositions) {
            if ((tp.unicode ?: "").isBlank()) flush() else cur.add(tp)
        }
        flush()
    }

    private fun tpSize(tp: TextPosition): Float {
        val s = tp.fontSizeInPt
        return if (s > 0.5f) s else maxOf(tp.heightDir, 4f)
    }

    fun page(doc: PDDocument, index: Int): Layout.Page {
        words.clear()
        startPage = index + 1; endPage = index + 1
        getText(doc)
        val p = Layout.Page()
        val frame = PageFrame(doc.getPage(index))
        p.width = frame.width; p.height = frame.height
        p.lines.addAll(Layout.toLines(words))
        return p
    }

    fun plainText(doc: PDDocument, index: Int): String {
        startPage = index + 1; endPage = index + 1
        val s = getText(doc)
        words.clear()
        return s
    }
}

/** Locates images drawn on a page (display-space boxes), recursing into forms. */
class ImageLocator(private val page: PDPage) : PDFStreamEngine() {
    data class Placed(val image: PDImageXObject, val x: Float, val y: Float, val w: Float, val h: Float)

    val found = ArrayList<Placed>()
    private val frame = PageFrame(page)

    init {
        addOperator(Concatenate()); addOperator(SetGraphicsStateParameters())
        addOperator(Save()); addOperator(Restore()); addOperator(SetMatrix())
    }

    fun run(): List<Placed> { try { processPage(page) } catch (_: Throwable) {}; return found }

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        if (operator.name == "Do" && operands.isNotEmpty() && operands[0] is COSName) {
            val name = operands[0] as COSName
            val xo = try { resources.getXObject(name) } catch (_: Exception) { null }
            if (xo is PDImageXObject) {
                val m = graphicsState.currentTransformationMatrix
                val pts = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (a, b) ->
                    val ux = m.scaleX * a + m.shearX * b + m.translateX
                    val uy = m.shearY * a + m.scaleY * b + m.translateY
                    frame.display(ux, uy)
                }
                val x0 = pts.minOf { it[0] }; val x1 = pts.maxOf { it[0] }
                val y0 = pts.minOf { it[1] }; val y1 = pts.maxOf { it[1] }
                if (x1 - x0 > 2 && y1 - y0 > 2) found.add(Placed(xo, x0, y0, x1 - x0, y1 - y0))
            } else if (xo is PDFormXObject) {
                showForm(xo)
            }
            return
        }
        super.processOperator(operator, operands)
    }
}

object PdfImages {
    /** Original bytes for JPEG/JPEG2000 streams, else a PNG/JPEG encoding of the decoded image. */
    fun bytesOf(img: PDImageXObject): Pair<ByteArray, String>? = try {
        val suffix = img.suffix ?: "png"
        if ((suffix == "jpg" || suffix == "jp2") && img.cosObject.getDictionaryObject(COSName.SMASK) == null) {
            val stop = if (suffix == "jpg") listOf(COSName.DCT_DECODE.name) else listOf(COSName.JPX_DECODE.name)
            val b = img.stream.createInputStream(stop).use { it.readBytes() }
            b to suffix
        } else {
            val bmp = img.image
            val bos = ByteArrayOutputStream()
            val big = bmp.width * bmp.height > 1_500_000 && !bmp.hasAlpha()
            bmp.compress(if (big) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, bos)
            bmp.recycle()
            bos.toByteArray() to (if (big) "jpg" else "png")
        }
    } catch (_: Throwable) { null }
}
