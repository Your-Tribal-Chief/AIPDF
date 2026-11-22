package com.allpdf.pro.engine

import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.PageFrame
import com.allpdf.pro.core.Pdf
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

object EditEngine {

    // ---------------------------------------------------------------- Resize

    enum class Fit { FIT, FILL, CENTER }

    fun resize(input: File, targetW: Float, targetH: Float, mode: Fit, autoOrient: Boolean, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val n = doc.numberOfPages
            for (i in 0 until n) {
                ctl.step(i.toFloat() / n, "Resizing page ${i + 1} of $n")
                val page = doc.getPage(i)
                val frame = PageFrame(page)
                var dw = targetW; var dh = targetH
                if (autoOrient && (frame.width > frame.height) != (dw > dh)) { val t = dw; dw = dh; dh = t }
                // target in user space (unrotated)
                val tw = if (frame.rotation % 180 == 0) dw else dh
                val th = if (frame.rotation % 180 == 0) dh else dw
                val box = Pdf.box(page)
                val sx = tw / box.width; val sy = th / box.height
                val s = when (mode) { Fit.FIT -> minOf(sx, sy); Fit.FILL -> maxOf(sx, sy); Fit.CENTER -> 1f }
                val tx = (tw - box.width * s) / 2 - box.lowerLeftX * s
                val ty = (th - box.height * s) / 2 - box.lowerLeftY * s
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(Matrix(s, 0f, 0f, s, tx, ty))
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, false).use { it.restoreGraphicsState() }
                val nb = PDRectangle(0f, 0f, tw, th)
                page.mediaBox = nb; page.cropBox = nb
                page.cosObject.removeItem(COSName.BLEED_BOX); page.cosObject.removeItem(COSName.TRIM_BOX); page.cosObject.removeItem(COSName.ART_BOX)
                for (a in page.annotations) {
                    val r = a.rectangle ?: continue
                    a.rectangle = PDRectangle(r.lowerLeftX * s + tx, r.lowerLeftY * s + ty, r.width * s, r.height * s)
                }
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- Crop

    /** rect in normalized display coords; [pages] null = all. */
    fun crop(input: File, x0: Float, y0: Float, x1: Float, y1: Float, pages: Set<Int>?, out: File, ctl: Ctl): File {
        require(x1 - x0 > 0.01f && y1 - y0 > 0.01f) { "Crop area is too small" }
        Pdf.load(input).use { doc ->
            val n = doc.numberOfPages
            for (i in 0 until n) {
                if (pages != null && i !in pages) continue
                ctl.step(i.toFloat() / n)
                val page = doc.getPage(i)
                val f = PageFrame(page)
                val a = f.user(x0 * f.width, y0 * f.height)
                val b = f.user(x1 * f.width, y1 * f.height)
                val r = PDRectangle(minOf(a[0], b[0]), minOf(a[1], b[1]), kotlin.math.abs(a[0] - b[0]), kotlin.math.abs(a[1] - b[1]))
                page.cropBox = r
                page.cosObject.removeItem(COSName.TRIM_BOX); page.cosObject.removeItem(COSName.ART_BOX); page.cosObject.removeItem(COSName.BLEED_BOX)
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- Page numbers

    data class NumberOpts(
        val top: Boolean = false, val hAlign: Int = 1, // 0 left 1 center 2 right
        val format: Int = 0, // 0 "1", 1 "1 / N", 2 "Page 1 of N"
        val size: Float = 11f, val color: Int = 0xFF000000.toInt(), val start: Int = 1, val skipFirst: Boolean = false,
        val margin: Float = 24f,
    )

    fun pageNumbers(input: File, o: NumberOpts, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val font = PDType1Font.HELVETICA
            val n = doc.numberOfPages
            val counted = if (o.skipFirst) n - 1 else n
            for (i in 0 until n) {
                ctl.step(i.toFloat() / n, "Numbering page ${i + 1}")
                if (o.skipFirst && i == 0) continue
                val num = o.start + (if (o.skipFirst) i - 1 else i)
                val last = o.start + counted - 1
                val label = when (o.format) { 1 -> "$num / $last"; 2 -> "Page $num of $last"; else -> "$num" }
                val page = doc.getPage(i)
                val f = PageFrame(page)
                val tw = Pdf.textWidth(font, label, o.size)
                val x = when (o.hAlign) { 0 -> o.margin; 2 -> f.width - o.margin - tw; else -> (f.width - tw) / 2 }
                val baseline = if (o.top) o.margin + o.size * 0.8f else f.height - o.margin
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 1f)
                    cs.setNonStrokingColor(o.color.r(), o.color.g(), o.color.b())
                    cs.setTextMatrix(f.matrix(x, baseline, o.size, o.size))
                    cs.showText(label)
                    cs.endText()
                }
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- Watermark

    data class WatermarkOpts(
        val text: String? = "CONFIDENTIAL", val image: File? = null,
        val opacity: Float = 0.3f, val rotation: Float = 45f, val size: Float = 48f, // text pt, or image width fraction*100
        val position: Int = 4, // 0..8 grid, 9 = tiled
        val under: Boolean = false, val color: Int = 0xFFE5322D.toInt(), val pages: Set<Int>? = null,
    )

    fun watermark(input: File, o: WatermarkOpts, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            val font = if (o.text != null) Pdf.unicodeFont(doc, bold = true) else null
            val text = if (font != null) Pdf.encodable(font, o.text!!) else null
            val img: PDImageXObject? = o.image?.let { f -> ImageIO.decode(f, 2000)?.let { b -> LosslessFactory.createFromImage(doc, b).also { b.recycle() } } }
            require(img != null || !text.isNullOrBlank()) { "Enter watermark text or choose an image" }
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = o.opacity; strokingAlphaConstant = o.opacity }
            val n = doc.numberOfPages
            for (i in 0 until n) {
                if (o.pages != null && i !in o.pages) continue
                ctl.step(i.toFloat() / n, "Watermarking page ${i + 1}")
                val page = doc.getPage(i)
                val f = PageFrame(page)
                val (iw, ih) = if (img != null) {
                    val w = f.width * (o.size / 100f).coerceIn(0.05f, 1f)
                    w to w * img.height / img.width
                } else Pdf.textWidth(font!!, text!!, o.size) to o.size * 0.72f
                val spots: List<Pair<Float, Float>> = if (o.position == 9) {
                    val stepX = (iw * 1.6f).coerceAtLeast(60f); val stepY = (ih * 3f + 40f).coerceAtLeast(60f)
                    val list = ArrayList<Pair<Float, Float>>()
                    var y = stepY / 2
                    var row = 0
                    while (y < f.height + stepY) {
                        var x = if (row % 2 == 0) stepX / 4 else stepX * 0.75f
                        while (x < f.width + stepX) { list.add(x to y); x += stepX }
                        y += stepY; row++
                    }
                    list
                } else {
                    val col = o.position % 3; val rowi = o.position / 3
                    val m = 36f
                    val cx = when (col) { 0 -> m + iw / 2; 2 -> f.width - m - iw / 2; else -> f.width / 2 }
                    val cy = when (rowi) { 0 -> m + ih / 2 + 20; 2 -> f.height - m - ih / 2 - 20; else -> f.height / 2 }
                    listOf(cx to cy)
                }
                val mode = if (o.under) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
                PDPageContentStream(doc, page, mode, true, !o.under).use { cs ->
                    cs.saveGraphicsState()
                    cs.setGraphicsStateParameters(gs)
                    for ((cx, cy) in spots) {
                        // origin so the item is centered at (cx, cy) after rotation around its center
                        val rad = Math.toRadians(o.rotation.toDouble())
                        val ox = cx - (iw / 2 * Math.cos(rad) + ih / 2 * Math.sin(rad)).toFloat()
                        val oyBottom = cy + (iw / 2 * Math.sin(rad) - ih / 2 * Math.cos(rad)).toFloat() + 0f
                        if (img != null) {
                            cs.drawImage(img, f.matrix(ox, oyBottom, iw, ih, o.rotation))
                        } else {
                            cs.beginText()
                            cs.setFont(font!!, 1f)
                            cs.setNonStrokingColor(o.color.r(), o.color.g(), o.color.b())
                            cs.setTextMatrix(f.matrix(ox, oyBottom, o.size, o.size, o.rotation))
                            cs.showText(text!!)
                            cs.endText()
                        }
                    }
                    cs.restoreGraphicsState()
                }
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    // ---------------------------------------------------------------- Visual edits (Edit / Sign)

    fun applyOverlays(input: File, overlays: List<Overlay>, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            var font: PDFont? = null
            var bold: PDFont? = null
            val byPage = overlays.groupBy { it.page }
            val images = HashMap<File, PDImageXObject>()
            byPage.entries.forEachIndexed { k, (pi, items) ->
                ctl.step(k.toFloat() / byPage.size.coerceAtLeast(1), "Applying edits on page ${pi + 1}")
                val page = doc.getPage(pi)
                val f = PageFrame(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (o in items) {
                        when (o) {
                            is Overlay.Text -> {
                                val ft = if (o.bold) (bold ?: Pdf.unicodeFont(doc, true).also { bold = it }) else (font ?: Pdf.unicodeFont(doc).also { font = it })
                                cs.setNonStrokingColor(o.color.r(), o.color.g(), o.color.b())
                                var baseline = o.y * f.height + o.sizePt * 0.85f
                                for (line in o.text.split('\n')) {
                                    val t = Pdf.encodable(ft, line)
                                    if (t.isNotEmpty()) {
                                        cs.beginText(); cs.setFont(ft, 1f)
                                        cs.setTextMatrix(f.matrix(o.x * f.width, baseline, o.sizePt, o.sizePt))
                                        cs.showText(t); cs.endText()
                                    }
                                    baseline += o.sizePt * 1.2f
                                }
                            }
                            is Overlay.Image -> {
                                var x = images[o.file]
                                if (x == null) {
                                    val b = ImageIO.decode(o.file, 1600) ?: continue
                                    x = LosslessFactory.createFromImage(doc, b)
                                    b.recycle()
                                    images[o.file] = x
                                }
                                val w = o.w * f.width; val h = o.h * f.height
                                cs.drawImage(x!!, f.matrix(o.x * f.width, o.y * f.height + h, w, h))
                            }
                            is Overlay.Shape -> drawShape(cs, f, o)
                            is Overlay.Ink -> {
                                if (o.points.size < 2) continue
                                cs.saveGraphicsState()
                                cs.setStrokingColor(o.color.r(), o.color.g(), o.color.b())
                                cs.setLineWidth(o.stroke); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                                val p0 = f.user(o.points[0].first * f.width, o.points[0].second * f.height)
                                cs.moveTo(p0[0], p0[1])
                                for (p in o.points.drop(1)) { val u = f.user(p.first * f.width, p.second * f.height); cs.lineTo(u[0], u[1]) }
                                cs.stroke()
                                cs.restoreGraphicsState()
                            }
                            else -> {}
                        }
                    }
                }
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    private fun drawShape(cs: PDPageContentStream, f: PageFrame, o: Overlay.Shape) {
        val x1 = o.x1 * f.width; val y1 = o.y1 * f.height; val x2 = o.x2 * f.width; val y2 = o.y2 * f.height
        fun pt(x: Float, y: Float) = f.user(x, y)
        cs.saveGraphicsState()
        if (o.kind == Overlay.ShapeKind.HIGHLIGHT) {
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.38f })
        }
        cs.setStrokingColor(o.color.r(), o.color.g(), o.color.b())
        cs.setNonStrokingColor(o.color.r(), o.color.g(), o.color.b())
        cs.setLineWidth(o.stroke); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
        val l = minOf(x1, x2); val r = maxOf(x1, x2); val t = minOf(y1, y2); val b = maxOf(y1, y2)
        when (o.kind) {
            Overlay.ShapeKind.RECT, Overlay.ShapeKind.HIGHLIGHT -> {
                val a = pt(l, t); val bb = pt(r, t); val c = pt(r, b); val d = pt(l, b)
                cs.moveTo(a[0], a[1]); cs.lineTo(bb[0], bb[1]); cs.lineTo(c[0], c[1]); cs.lineTo(d[0], d[1]); cs.closePath()
                if (o.kind == Overlay.ShapeKind.HIGHLIGHT || o.filled) cs.fill() else cs.stroke()
            }
            Overlay.ShapeKind.CIRCLE -> {
                val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = (r - l) / 2; val ry = (b - t) / 2
                val k = 0.5523f
                val p = pt(cx + rx, cy); cs.moveTo(p[0], p[1])
                val segs = listOf(
                    floatArrayOf(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry),
                    floatArrayOf(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy),
                    floatArrayOf(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry),
                    floatArrayOf(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy),
                )
                for (s in segs) { val c1 = pt(s[0], s[1]); val c2 = pt(s[2], s[3]); val e = pt(s[4], s[5]); cs.curveTo(c1[0], c1[1], c2[0], c2[1], e[0], e[1]) }
                cs.closePath()
                if (o.filled) cs.fill() else cs.stroke()
            }
            Overlay.ShapeKind.LINE, Overlay.ShapeKind.ARROW -> {
                val a = pt(x1, y1); val e = pt(x2, y2)
                cs.moveTo(a[0], a[1]); cs.lineTo(e[0], e[1]); cs.stroke()
                if (o.kind == Overlay.ShapeKind.ARROW) {
                    val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    val len = (o.stroke * 4 + 8)
                    for (d in listOf(Math.PI * 0.85, -Math.PI * 0.85)) {
                        val hx = x2 + (len * Math.cos(ang + d)).toFloat(); val hy = y2 + (len * Math.sin(ang + d)).toFloat()
                        val h = pt(hx, hy); cs.moveTo(e[0], e[1]); cs.lineTo(h[0], h[1]); cs.stroke()
                    }
                }
            }
        }
        cs.restoreGraphicsState()
    }

    // ---------------------------------------------------------------- Forms

    data class FormField(val name: String, val type: String, val value: String, val options: List<String>, val onValue: String? = null)

    fun readFields(input: File): List<FormField> = Pdf.load(input).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        val out = ArrayList<FormField>()
        for (f in form.fieldTree) {
            if (f !is PDTerminalField) continue
            val name = f.fullyQualifiedName ?: continue
            when (f) {
                is PDTextField -> out.add(FormField(name, "text", f.value ?: "", emptyList()))
                is PDCheckBox -> out.add(FormField(name, "checkbox", if (f.isChecked) "true" else "false", emptyList(), f.onValue))
                is PDRadioButton -> out.add(FormField(name, "radio", f.value ?: "", f.onValues.toList()))
                is PDChoice -> out.add(FormField(name, if (f is PDComboBox) "dropdown" else "list", f.value?.firstOrNull() ?: "", f.optionsDisplayValues ?: emptyList()))
                else -> {}
            }
        }
        out
    }

    fun fillForm(input: File, values: Map<String, String>, newFields: List<Overlay.Field>, flatten: Boolean, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            var form = doc.documentCatalog.acroForm
            if (form == null && newFields.isNotEmpty()) {
                form = PDAcroForm(doc); doc.documentCatalog.acroForm = form
            }
            if (form != null) ensureDefaults(form)
            ctl.step(0.2f, "Filling fields…")
            if (form != null) {
                for ((name, v) in values) {
                    val f = form.getField(name) ?: continue
                    try {
                        when (f) {
                            is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                            is PDRadioButton -> if (v.isNotEmpty()) f.value = v
                            is PDChoice -> if (v.isNotEmpty()) f.setValue(v)
                            is PDTextField -> f.value = v
                            else -> {}
                        }
                    } catch (e: Exception) {
                        // Fall back to raw value; viewers regenerate the appearance.
                        f.cosObject.setString(COSName.V, v)
                        form.needAppearances = true
                    }
                }
                ctl.step(0.5f, "Adding new fields…")
                for (nf in newFields) addField(doc, form, nf)
                if (flatten) {
                    ctl.step(0.8f, "Flattening…")
                    try { form.flatten() } catch (_: Exception) { form.needAppearances = true }
                }
            }
            doc.save(out)
        }
        ctl.step(1f)
        return out
    }

    private fun ensureDefaults(form: PDAcroForm) {
        val dr = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        if (dr.getFont(COSName.getPDFName("Helv")) == null) dr.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        if (dr.getFont(COSName.getPDFName("ZaDb")) == null) dr.put(COSName.getPDFName("ZaDb"), PDType1Font.ZAPF_DINGBATS)
        if (form.defaultAppearance.isNullOrEmpty()) form.defaultAppearance = "/Helv 0 Tf 0 g"
    }

    private fun addField(doc: PDDocument, form: PDAcroForm, o: Overlay.Field) {
        val page = doc.getPage(o.page)
        val f = PageFrame(page)
        val a = f.user(minOf(o.x1, o.x2) * f.width, minOf(o.y1, o.y2) * f.height)
        val b = f.user(maxOf(o.x1, o.x2) * f.width, maxOf(o.y1, o.y2) * f.height)
        val rect = PDRectangle(minOf(a[0], b[0]), minOf(a[1], b[1]), kotlin.math.abs(a[0] - b[0]), kotlin.math.abs(a[1] - b[1]))
        var name = o.name.ifBlank { "field" }
        var k = 2
        while (form.getField(name) != null) name = "${o.name.ifBlank { "field" }}_${k++}"
        when (o.kind) {
            Overlay.FieldKind.TEXT -> {
                val field = PDTextField(form)
                field.partialName = name
                field.defaultAppearance = "/Helv 10 Tf 0 g"
                val w: PDAnnotationWidget = field.widgets[0]
                w.rectangle = rect; w.page = page; w.isPrinted = true
                page.annotations = page.annotations.also { it.add(w) }
                form.fields.add(field)
                field.value = ""
            }
            Overlay.FieldKind.CHECKBOX -> {
                val field = PDCheckBox(form)
                field.partialName = name
                val w: PDAnnotationWidget = field.widgets[0]
                w.rectangle = rect; w.page = page; w.isPrinted = true
                w.appearance = checkboxAppearance(doc, rect.width, rect.height)
                w.cosObject.setName(COSName.AS, "Off")
                field.cosObject.setName(COSName.V, "Off")
                page.annotations = page.annotations.also { it.add(w) }
                form.fields.add(field)
            }
        }
    }

    private fun checkboxAppearance(doc: PDDocument, w: Float, h: Float): PDAppearanceDictionary {
        fun stream(checked: Boolean): PDAppearanceStream {
            val s = PDAppearanceStream(doc)
            s.bBox = PDRectangle(w, h)
            s.resources = PDResources()
            PDPageContentStream(doc, s).use { cs ->
                cs.setStrokingColor(0.2f, 0.2f, 0.2f); cs.setLineWidth(1f)
                cs.addRect(0.5f, 0.5f, w - 1, h - 1); cs.stroke()
                if (checked) {
                    cs.setLineWidth(maxOf(1.2f, w / 9)); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                    cs.moveTo(w * 0.2f, h * 0.52f); cs.lineTo(w * 0.42f, h * 0.25f); cs.lineTo(w * 0.8f, h * 0.78f); cs.stroke()
                }
            }
            return s
        }
        val ap = PDAppearanceDictionary()
        val normal = COSDictionary()
        normal.setItem(COSName.getPDFName("Yes"), stream(true))
        normal.setItem(COSName.getPDFName("Off"), stream(false))
        ap.cosObject.setItem(COSName.N, normal)
        return ap
    }
}
