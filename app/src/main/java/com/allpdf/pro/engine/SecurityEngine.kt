package com.allpdf.pro.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.PageRenderer
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.text.TextDiff
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

object SecurityEngine {

    fun protect(input: File, userPw: String, ownerPw: String, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean, out: File, ctl: Ctl): File {
        require(userPw.isNotEmpty() || ownerPw.isNotEmpty()) { "Enter a password" }
        ctl.step(0.2f, "Encrypting with AES-256…")
        Pdf.load(input).use { doc ->
            val ap = AccessPermission()
            ap.setCanPrint(allowPrint)
            ap.setCanPrintFaithful(allowPrint)
            ap.setCanExtractContent(allowCopy)
            ap.setCanExtractForAccessibility(true)
            ap.setCanModify(allowEdit)
            ap.setCanModifyAnnotations(allowEdit)
            ap.setCanFillInForm(true)
            ap.setCanAssembleDocument(allowEdit)
            val owner = ownerPw.ifEmpty {
                // Random owner password so restrictions can't be lifted with the open password.
                val b = ByteArray(18).also { SecureRandom().nextBytes(it) }
                android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
            }
            val policy = StandardProtectionPolicy(owner, userPw, ap)
            policy.encryptionKeyLength = 256
            policy.isPreferAES = true
            doc.protect(policy)
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    fun unlock(input: File, password: String, out: File, ctl: Ctl): File {
        ctl.step(0.2f, "Verifying password…")
        try {
            Pdf.load(input, password).use { doc ->
                if (!doc.isEncrypted) error("This PDF is not password protected")
                doc.isAllSecurityToBeRemoved = true
                ctl.step(0.6f, "Removing encryption…")
                doc.save(out)
            }
        } catch (e: InvalidPasswordException) {
            error("Incorrect password")
        }
        ctl.step(1f)
        return out
    }

    /**
     * True redaction: every page with a redaction is re-rendered to pixels, boxes are burned in,
     * and the page is replaced by that image. No text, vectors or annotations survive underneath.
     */
    fun redact(input: File, boxes: List<Overlay.Redact>, dpi: Int, stripMetadata: Boolean, out: File, ctl: Ctl): File {
        require(boxes.isNotEmpty()) { "Draw at least one redaction box" }
        val byPage = boxes.groupBy { it.page }
        PageRenderer(input).use { r ->
            Pdf.load(input).use { src ->
                Pdf.newDoc().use { dst ->
                    val n = src.numberOfPages
                    for (i in 0 until n) {
                        ctl.step(i.toFloat() / n, "Processing page ${i + 1} of $n")
                        val list = byPage[i]
                        if (list == null) {
                            val p = dst.importPage(src.getPage(i)); p.rotation = src.getPage(i).rotation
                            continue
                        }
                        val bmp = r.renderAtDpi(i, dpi)
                        val c = Canvas(bmp)
                        val black = Paint().apply { color = Color.BLACK }
                        for (b in list) {
                            c.drawRect(RectF(minOf(b.x1, b.x2) * bmp.width, minOf(b.y1, b.y2) * bmp.height, maxOf(b.x1, b.x2) * bmp.width, maxOf(b.y1, b.y2) * bmp.height), black)
                        }
                        val (pw, ph) = r.pageSize(i)
                        val page = PDPage(PDRectangle(pw, ph))
                        dst.addPage(page)
                        val img = JPEGFactory.createFromImage(dst, bmp, 0.9f)
                        bmp.recycle()
                        PDPageContentStream(dst, page).use { it.drawImage(img, 0f, 0f, pw, ph) }
                    }
                    if (!stripMetadata) {
                        val info = src.documentInformation
                        dst.documentInformation.title = info.title
                    }
                    dst.documentInformation.producer = "AllPDF Pro (redacted)"
                    ctl.step(0.97f, "Saving…")
                    dst.save(out)
                }
            }
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- Compare

    data class CompareResult(val lines: List<TextDiff.Line>, val stats: TextDiff.Stats, val pagesA: Int, val pagesB: Int, val report: File, val changedPages: List<Int>)

    fun pageText(f: File): List<String> = Pdf.load(f).use { doc ->
        val s = WordStripper()
        (0 until doc.numberOfPages).flatMap { i -> s.plainText(doc, i).split('\n').map { it.trimEnd() }.filter { it.isNotBlank() } }
    }

    /** Fraction of differing pixels and an overlay bitmap highlighting changes on top of B. */
    fun pixelDiff(a: Bitmap, b: Bitmap, width: Int): Pair<Float, Bitmap> {
        val h = (width * b.height.toFloat() / b.width).toInt().coerceAtLeast(1)
        val sa = Bitmap.createScaledBitmap(a, width, h, true)
        val sb = Bitmap.createScaledBitmap(b, width, h, true)
        val pa = IntArray(width * h); val pb = IntArray(width * h)
        sa.getPixels(pa, 0, width, 0, 0, width, h); sb.getPixels(pb, 0, width, 0, 0, width, h)
        var diff = 0
        val outPx = IntArray(width * h)
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            val la = ((x shr 16 and 0xFF) * 3 + (x shr 8 and 0xFF) * 6 + (x and 0xFF)) / 10
            val lb = ((y shr 16 and 0xFF) * 3 + (y shr 8 and 0xFF) * 6 + (y and 0xFF)) / 10
            if (kotlin.math.abs(la - lb) > 48) {
                diff++
                // added ink (darker in B) red, removed ink (darker in A) blue
                outPx[i] = if (lb < la) 0xFFE5322D.toInt() else 0xFF2563EB.toInt()
            } else {
                // faded B
                val g = 255 - (255 - lb) / 3
                outPx[i] = Color.rgb(g, g, g)
            }
        }
        if (sa !== a) sa.recycle()
        if (sb !== b) sb.recycle()
        val out = Bitmap.createBitmap(outPx, width, h, Bitmap.Config.ARGB_8888)
        return diff.toFloat() / pa.size to out
    }

    fun compare(a: File, b: File, out: File, ctl: Ctl): CompareResult {
        ctl.step(0.05f, "Extracting text…")
        val ta = pageText(a); val tb = pageText(b)
        ctl.step(0.2f, "Comparing text…")
        val lines = TextDiff.diffLines(ta, tb)
        val stats = TextDiff.stats(lines)
        val changed = ArrayList<Int>()
        val pdf = PdfDocument()
        var pagesA = 0; var pagesB = 0
        PageRenderer(a).use { ra ->
            PageRenderer(b).use { rb ->
                pagesA = ra.pageCount; pagesB = rb.pageCount
                val n = maxOf(pagesA, pagesB)
                val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; isFakeBoldText = true }
                val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY }
                for (i in 0 until n) {
                    ctl.step(0.25f + 0.6f * i / n, "Visual diff page ${i + 1} of $n")
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(842, 595, i + 1).create())
                    val c = page.canvas
                    c.drawText("Page ${i + 1}", 24f, 28f, title)
                    val colW = 260f; val top = 44f
                    val labels = listOf("Original (A)", "Changed (B)", "Overlay: red = added, blue = removed")
                    labels.forEachIndexed { k, s -> c.drawText(s, 24f + k * (colW + 12f), top - 4f, small) }
                    val bmA = if (i < pagesA) ra.render(i, 700) else null
                    val bmB = if (i < pagesB) rb.render(i, 700) else null
                    fun place(bm: Bitmap?, k: Int) {
                        val x = 24f + k * (colW + 12f)
                        if (bm == null) { c.drawText("(no page)", x + 8, top + 20, small); return }
                        val h = minOf(530f, colW * bm.height / bm.width)
                        val w = h * bm.width / bm.height
                        c.drawBitmap(bm, null, RectF(x, top, x + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        c.drawRect(RectF(x, top, x + w, top + h), Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY })
                    }
                    place(bmA, 0); place(bmB, 1)
                    if (bmA != null && bmB != null) {
                        val (frac, overlay) = pixelDiff(bmA, bmB, 520)
                        if (frac > 0.0005f) changed.add(i)
                        place(overlay, 2); overlay.recycle()
                        c.drawText(String.format(java.util.Locale.US, "%.2f%% of pixels differ", frac * 100), 24f + 2 * (colW + 12f), 586f, small)
                    } else changed.add(i)
                    bmA?.recycle(); bmB?.recycle()
                    pdf.finishPage(page)
                }
            }
        }
        // Text diff pages
        ctl.step(0.88f, "Writing text report…")
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
        var pageNo = maxOf(pagesA, pagesB)
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() { page?.let { pdf.finishPage(it) }; pageNo++; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = 40f }
        newPage()
        page!!.canvas.drawText("Text differences: +${stats.added}  −${stats.removed}  ~${stats.modified}  (=${stats.same} unchanged)", 36f, y, TextPaint(tp).apply { textSize = 12f; isFakeBoldText = true })
        y += 22f
        for (l in lines) {
            if (l.kind == TextDiff.Kind.SAME) continue
            val (prefix, color, text) = when (l.kind) {
                TextDiff.Kind.ADDED -> Triple("+ ", 0xFF15803D.toInt(), l.right)
                TextDiff.Kind.REMOVED -> Triple("− ", 0xFFB91C1C.toInt(), l.left)
                else -> Triple("~ ", 0xFFB45309.toInt(), "${l.left}  →  ${l.right}")
            }
            val layout = android.text.StaticLayout.Builder.obtain(prefix + text, 0, prefix.length + text.length, TextPaint(tp).apply { this.color = color }, 523).build()
            if (y + layout.height > 806) newPage()
            val c = page!!.canvas
            c.save(); c.translate(36f, y); layout.draw(c); c.restore()
            y += layout.height + 4
        }
        page?.let { pdf.finishPage(it) }
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        ctl.step(1f)
        return CompareResult(lines, stats, pagesA, pagesB, out, changed)
    }
}
