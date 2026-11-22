package com.allpdf.pro.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.PageFrame
import com.allpdf.pro.core.PageRenderer
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.IdentityHashMap

object OptimizeEngine {

    enum class Level(val label: String, val maxSide: Int, val quality: Float) {
        EXTREME("Extreme", 1100, 0.42f),
        RECOMMENDED("Recommended", 1700, 0.62f),
        LESS("Less compression", 2600, 0.82f),
    }

    data class CompressResult(val out: File, val before: Long, val after: Long, val imagesRecompressed: Int)

    fun compress(input: File, level: Level, out: File, ctl: Ctl): CompressResult {
        val before = input.length()
        var count = 0
        Pdf.load(input).use { doc ->
            val done = IdentityHashMap<COSBase, PDImageXObject>()
            val visitedForms = IdentityHashMap<COSBase, Boolean>()
            val n = doc.numberOfPages
            for (i in 0 until n) {
                ctl.step(i.toFloat() / n * 0.9f, "Optimizing page ${i + 1} of $n")
                val page = doc.getPage(i)
                page.resources?.let { count += processResources(doc, it, level, done, visitedForms, ctl) }
                compressContent(doc, page)
                if (level == Level.EXTREME) page.cosObject.removeItem(COSName.getPDFName("Thumb"))
            }
            if (level == Level.EXTREME) doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            ctl.step(0.95f, "Writing optimized file…")
            doc.save(out)
        }
        // If re-encoding didn't help, keep the smaller original structure.
        if (out.length() >= before) input.copyTo(out, overwrite = true)
        ctl.step(1f)
        return CompressResult(out, before, out.length(), count)
    }

    private fun processResources(
        doc: PDDocument, res: PDResources, level: Level,
        done: IdentityHashMap<COSBase, PDImageXObject>, forms: IdentityHashMap<COSBase, Boolean>, ctl: Ctl,
    ): Int {
        var count = 0
        for (name in res.xObjectNames.toList()) {
            ctl.check()
            val xo = try { res.getXObject(name) } catch (_: Exception) { null } ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    val cached = done[key]
                    if (cached != null) { if (cached !== xo) res.put(name, cached); continue }
                    val replacement = recompress(doc, xo, level)
                    done[key] = replacement ?: xo
                    if (replacement != null) { res.put(name, replacement); count++ }
                }
                is PDFormXObject -> {
                    if (forms.put(xo.cosObject, true) == null) xo.resources?.let { count += processResources(doc, it, level, done, forms, ctl) }
                }
            }
        }
        return count
    }

    private fun recompress(doc: PDDocument, img: PDImageXObject, level: Level): PDImageXObject? {
        try {
            if (img.isStencil || img.bitsPerComponent == 1) return null
            val origLen = img.cosObject.getLength()
            if (origLen < 12 * 1024) return null
            val w = img.width; val h = img.height
            if (w <= 0 || h <= 0) return null
            val src = img.image ?: return null
            val scale = minOf(1f, level.maxSide.toFloat() / maxOf(w, h))
            val tw = (w * scale).toInt().coerceAtLeast(1); val th = (h * scale).toInt().coerceAtLeast(1)
            // Flatten onto white (JPEG has no alpha); the original SMask is re-attached below.
            val flat = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val scaled = if (tw == src.width && th == src.height) src else Bitmap.createScaledBitmap(src, tw, th, true)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            if (scaled !== src) scaled.recycle()
            src.recycle()
            val gray = img.colorSpace?.numberOfComponents == 1
            val candidate = JPEGFactory.createFromImage(doc, if (gray) toGray(flat) else flat, level.quality)
            flat.recycle()
            if (candidate.cosObject.getLength() >= origLen * 0.92) return null
            img.cosObject.getDictionaryObject(COSName.SMASK)?.let { candidate.cosObject.setItem(COSName.SMASK, it) }
            img.cosObject.getDictionaryObject(COSName.INTERPOLATE)?.let { candidate.cosObject.setItem(COSName.INTERPOLATE, it) }
            return candidate
        } catch (_: Throwable) {
            return null
        }
    }

    private fun toGray(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /** Flate-compresses uncompressed page content streams. */
    private fun compressContent(doc: PDDocument, page: PDPage) {
        try {
            val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
            if (contents is COSStream && contents.filters == null) {
                val bytes = contents.createInputStream().use { it.readBytes() }
                val ps = PDStream(doc, bytes.inputStream(), COSName.FLATE_DECODE)
                page.setContents(ps)
            }
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- Repair

    data class RepairResult(val out: File, val method: String, val pages: Int)

    fun repair(input: File, out: File, dir: File, ctl: Ctl): RepairResult {
        ctl.step(0.05f, "Scanning file structure…")
        tryRebuild(input, out)?.let { return RepairResult(out, "Cross-reference table and object streams rebuilt", it) }

        ctl.step(0.3f, "Trying salvage of damaged header/trailer…")
        val salvaged = salvage(input, File(dir, "salvaged.pdf"))
        if (salvaged != null) tryRebuild(salvaged, out)?.let { return RepairResult(out, "Recovered after trimming junk and restoring trailer", it) }

        ctl.step(0.5f, "Re-rendering readable pages…")
        val src = salvaged ?: input
        try {
            PageRenderer(src).use { r ->
                val n = r.pageCount
                Pdf.newDoc().use { doc ->
                    for (i in 0 until n) {
                        ctl.step(0.5f + 0.5f * i / n, "Recovering page ${i + 1} of $n")
                        val bmp = try { r.renderAtDpi(i, 150) } catch (_: Throwable) { null } ?: continue
                        val (pw, ph) = r.pageSize(i)
                        val page = PDPage(PDRectangle(pw, ph))
                        doc.addPage(page)
                        val x = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                        PDPageContentStream(doc, page).use { it.drawImage(x, 0f, 0f, pw, ph) }
                        bmp.recycle()
                    }
                    if (doc.numberOfPages == 0) error("No pages could be recovered")
                    doc.save(out)
                    return RepairResult(out, "Pages re-rendered from the recoverable content (text becomes image)", doc.numberOfPages)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("This file is too damaged to recover: ${e.message}")
        }
    }

    private fun tryRebuild(f: File, out: File): Int? = try {
        Pdf.load(f).use { doc ->
            if (doc.numberOfPages == 0) null else {
                // Touch every page so lazy-loading errors surface now
                for (p in doc.pages) { p.mediaBox; p.resources }
                doc.save(out); doc.numberOfPages
            }
        }
    } catch (_: Throwable) { null }

    /** Strips bytes before %PDF and appends a trailer marker if missing. */
    private fun salvage(f: File, out: File): File? = try {
        val bytes = FileInputStream(f).use { it.readBytes() }
        val head = indexOf(bytes, "%PDF".toByteArray(), 0)
        if (head < 0) null else {
            FileOutputStream(out).use { o ->
                o.write(bytes, head, bytes.size - head)
                val tail = String(bytes, maxOf(head, bytes.size - 1024), minOf(1024, bytes.size - head), Charsets.ISO_8859_1)
                if (!tail.contains("%%EOF")) o.write("\n%%EOF\n".toByteArray())
            }
            out
        }
    } catch (_: Throwable) { null }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return -1
    }

    // ---------------------------------------------------------------- OCR

    data class OcrResult(val pdf: File, val text: File, val words: Int)

    private fun recognizer() = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** OCR a PDF: keeps original pages and adds an invisible, selectable text layer. */
    fun ocrPdf(input: File, dir: File, ctl: Ctl): OcrResult {
        val rec = recognizer()
        val textOut = StringBuilder()
        var words = 0
        val out = File(dir, "${input.baseName()}_ocr.pdf")
        try {
            PageRenderer(input).use { r ->
                Pdf.load(input).use { doc ->
                    val font = Pdf.unicodeFont(doc)
                    val n = doc.numberOfPages
                    for (i in 0 until n) {
                        ctl.step(i.toFloat() / n, "Recognizing page ${i + 1} of $n")
                        val bmp = r.renderAtDpi(i, 220, 16_000_000L)
                        val page = doc.getPage(i)
                        val frame = PageFrame(page)
                        val scale = frame.width / bmp.width
                        val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
                        bmp.recycle()
                        textOut.append("===== Page ").append(i + 1).append(" =====\n").append(result.text).append("\n\n")
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
                                val box = el.boundingBox ?: continue
                                if (drawInvisible(cs, font, frame, el.text, box.left * scale, box.bottom * scale, box.width() * scale, box.height() * scale)) words++
                            }
                        }
                    }
                    ctl.step(0.97f, "Saving searchable PDF…")
                    doc.save(out)
                }
            }
        } finally { rec.close() }
        val txt = File(dir, "${input.baseName()}_ocr.txt").apply { writeText(textOut.toString()) }
        ctl.step(1f)
        return OcrResult(out, txt, words)
    }

    /** OCR images into a searchable PDF (image page + invisible text). */
    fun ocrImages(images: List<File>, dir: File, ctl: Ctl): OcrResult {
        val rec = recognizer()
        val textOut = StringBuilder()
        var words = 0
        val out = File(dir, "${images.first().baseName()}_ocr.pdf")
        try {
            Pdf.newDoc().use { doc ->
                val font = Pdf.unicodeFont(doc)
                images.forEachIndexed { i, f ->
                    ctl.step(i.toFloat() / images.size, "Recognizing image ${i + 1} of ${images.size}")
                    val bmp = ImageIO.decode(f, 3000) ?: return@forEachIndexed
                    val pw = bmp.width * 72f / 200f; val ph = bmp.height * 72f / 200f
                    val page = PDPage(PDRectangle(pw, ph)); doc.addPage(page)
                    val frame = PageFrame(page)
                    val scale = pw / bmp.width
                    val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
                    val x = JPEGFactory.createFromImage(doc, bmp, 0.88f)
                    bmp.recycle()
                    textOut.append("===== ").append(f.name).append(" =====\n").append(result.text).append("\n\n")
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(x, 0f, 0f, pw, ph)
                        for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
                            val box = el.boundingBox ?: continue
                            if (drawInvisible(cs, font, frame, el.text, box.left * scale, box.bottom * scale, box.width() * scale, box.height() * scale)) words++
                        }
                    }
                }
                doc.save(out)
            }
        } finally { rec.close() }
        val txt = File(dir, "${images.first().baseName()}_ocr.txt").apply { writeText(textOut.toString()) }
        ctl.step(1f)
        return OcrResult(out, txt, words)
    }

    private fun drawInvisible(cs: PDPageContentStream, font: PDFont, frame: PageFrame, raw: String, left: Float, bottom: Float, w: Float, h: Float): Boolean {
        val text = Pdf.encodable(font, raw)
        if (text.isBlank() || w <= 0 || h <= 0) return false
        val tw = Pdf.textWidth(font, text, 1f).takeIf { it > 0 } ?: return false
        val size = h * 0.92f
        val sx = w / tw
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        cs.setFont(font, 1f)
        cs.setTextMatrix(frame.matrix(left, bottom - h * 0.18f, sx, size))
        cs.showText(text)
        cs.endText()
        return true
    }
}

/** Memory-safe image decoding with EXIF orientation. */
object ImageIO {
    fun decode(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = try {
            when (androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90; 3 -> 180; 8 -> 270; else -> 0
            }
        } catch (_: Exception) { 0 }
        var out = bmp
        if (rot != 0) {
            val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
            out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
        }
        val longSide = maxOf(out.width, out.height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(out, (out.width * s).toInt().coerceAtLeast(1), (out.height * s).toInt().coerceAtLeast(1), true)
            if (scaled !== out) out.recycle()
            out = scaled
        }
        return out
    }

    fun hasAlpha(f: File): Boolean = f.extension.lowercase() in setOf("png", "webp", "gif")

    /** Embeds a bitmap choosing lossless for alpha/graphics and JPEG for photos. */
    fun toXObject(doc: PDDocument, bmp: Bitmap, preferLossless: Boolean, quality: Float = 0.9f): PDImageXObject =
        if (preferLossless || bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp)
        else JPEGFactory.createFromImage(doc, bmp, quality)

    fun pngBytes(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    fun jpgBytes(b: Bitmap, q: Int = 88): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
}
