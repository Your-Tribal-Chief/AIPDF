package com.allpdf.pro.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.Closeable
import java.io.File

object Pdf {
    /** Keeps at most 48 MB of parsed PDF in RAM, spills the rest to temp files (OOM-safe). */
    fun mem(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024).setTempDir(Env.workRoot)

    fun load(f: File, password: String = ""): PDDocument = PDDocument.load(f, password, mem())

    fun newDoc(): PDDocument = PDDocument(mem())

    /** True when the file can't be opened without a user password. */
    fun needsPassword(f: File): Boolean = try {
        load(f).use { false }
    } catch (e: InvalidPasswordException) {
        true
    }

    /** Returns a decrypted copy (or the original file if it was never encrypted). Throws on wrong password. */
    fun decryptedCopy(f: File, password: String, dir: File): File {
        load(f, password).use { doc ->
            if (!doc.isEncrypted) return f
            doc.isAllSecurityToBeRemoved = true
            val out = File(dir, f.baseName() + "_unlocked.pdf")
            doc.save(out)
            return out
        }
    }

    fun pageCount(f: File): Int = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) {
        load(f).use { it.numberOfPages }
    }

    /** Effective visible box of a page (crop box), in PDF user space. */
    fun box(page: PDPage): PDRectangle = page.cropBox ?: page.mediaBox

    private val fontCandidates = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    )

    /** A Unicode-capable embedded font when available, else Helvetica. */
    fun unicodeFont(doc: PDDocument, bold: Boolean = false): PDFont {
        val boldCandidates = listOf("/system/fonts/Roboto-Bold.ttf", "/system/fonts/RobotoStatic-Bold.ttf", "/system/fonts/NotoSans-Bold.ttf")
        for (p in (if (bold) boldCandidates else emptyList()) + fontCandidates) {
            val f = File(p)
            if (f.exists() && f.length() > 0) {
                try { return PDType0Font.load(doc, f) } catch (_: Throwable) { }
            }
        }
        return if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
    }

    /** Removes characters the font can't encode so showText never throws. */
    fun encodable(font: PDFont, text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            try { font.encode(s); sb.append(s) } catch (_: Throwable) {
                if (cp == '\t'.code) sb.append(' ')
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun textWidth(font: PDFont, text: String, size: Float): Float =
        try { font.getStringWidth(text) / 1000f * size } catch (_: Throwable) { text.length * size * 0.5f }
}

/** Page rasteriser: Android PdfRenderer (fast, pdfium) with PDFBox fallback. Not thread-safe; one per thread. */
class PageRenderer(private val file: File) : Closeable {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var boxDoc: PDDocument? = null
    private var boxRenderer: PDFRenderer? = null

    init {
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
        } catch (e: Exception) {
            renderer = null
            pfd?.close(); pfd = null
            boxDoc = Pdf.load(file)
            boxRenderer = PDFRenderer(boxDoc)
        }
    }

    val pageCount: Int get() = renderer?.pageCount ?: boxDoc!!.numberOfPages

    /** Page size in points (after page rotation, as displayed). */
    @Synchronized
    fun pageSize(index: Int): Pair<Float, Float> {
        renderer?.let { r -> r.openPage(index).use { return it.width.toFloat() to it.height.toFloat() } }
        val p = boxDoc!!.getPage(index)
        val b = Pdf.box(p)
        return if (p.rotation % 180 != 0) b.height to b.width else b.width to b.height
    }

    /** Renders page so its width is [targetWidthPx] (height follows aspect). Caps total pixels. */
    @Synchronized
    fun render(index: Int, targetWidthPx: Int, maxPixels: Long = 24_000_000L, forPrint: Boolean = false): Bitmap {
        val (w, h) = pageSize(index)
        var tw = targetWidthPx.coerceAtLeast(16)
        var th = (tw * h / w).toInt().coerceAtLeast(16)
        if (tw.toLong() * th > maxPixels) {
            val s = Math.sqrt(maxPixels.toDouble() / (tw.toLong() * th))
            tw = (tw * s).toInt().coerceAtLeast(16); th = (th * s).toInt().coerceAtLeast(16)
        }
        renderer?.let { r ->
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            r.openPage(index).use {
                it.render(bmp, null, null, if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            return bmp
        }
        val scale = tw / w
        val src = boxRenderer!!.renderImage(index, scale)
        if (src.width == tw && src.height == th) return src
        val out = Bitmap.createScaledBitmap(src, tw, th, true)
        if (out !== src) src.recycle()
        return out
    }

    fun renderAtDpi(index: Int, dpi: Int, maxPixels: Long = 36_000_000L): Bitmap {
        val (w, _) = pageSize(index)
        return render(index, (w / 72f * dpi).toInt(), maxPixels, forPrint = true)
    }

    override fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { boxDoc?.close() } catch (_: Exception) {}
    }
}
