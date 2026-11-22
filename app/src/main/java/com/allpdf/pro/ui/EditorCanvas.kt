package com.allpdf.pro.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.allpdf.pro.engine.ImageIO
import com.allpdf.pro.engine.Overlay
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class EditMode { SELECT, TEXT, RECT, CIRCLE, LINE, ARROW, HIGHLIGHT, PEN, REDACT, FIELD_TEXT, FIELD_CHECK }

private val imageCache = HashMap<File, ImageBitmap>()
private fun imageFor(f: File): ImageBitmap? = imageCache[f] ?: ImageIO.decode(f, 900)?.asImageBitmap()?.also { imageCache[f] = it }

/**
 * Overlay layer on top of a PageCanvas. Coordinates are normalized to the page (0..1).
 * [pageWidthPt] is used to scale text so on-screen size matches the saved PDF.
 */
@Composable
fun OverlayLayer(
    size: IntSize, pageIndex: Int, pageWidthPt: Float, overlays: SnapshotStateList<Overlay>, mode: EditMode,
    color: Int, stroke: Float, selected: MutableState<Int?>,
    onTap: (Float, Float) -> Unit = { _, _ -> }, onFieldDrawn: (Overlay.Field) -> Unit = {},
) {
    val w = size.width.toFloat(); val h = size.height.toFloat()
    val pxPerPt = w / pageWidthPt.coerceAtLeast(1f)
    var draft by remember { mutableStateOf<Overlay?>(null) }
    val modeNow by rememberUpdatedState(mode)
    val colorNow by rememberUpdatedState(color)
    val strokeNow by rememberUpdatedState(stroke)

    fun bounds(o: Overlay): FloatArray? = when (o) {
        is Overlay.Image -> floatArrayOf(o.x, o.y, o.x + o.w, o.y + o.h)
        is Overlay.Text -> {
            val lines = o.text.split('\n')
            val tw = lines.maxOf { it.length } * o.sizePt * 0.55f / pageWidthPt
            val th = lines.size * o.sizePt * 1.2f * pxPerPt / h
            floatArrayOf(o.x, o.y, o.x + tw, o.y + th)
        }
        is Overlay.Shape -> floatArrayOf(minOf(o.x1, o.x2), minOf(o.y1, o.y2), maxOf(o.x1, o.x2), maxOf(o.y1, o.y2))
        is Overlay.Redact -> floatArrayOf(minOf(o.x1, o.x2), minOf(o.y1, o.y2), maxOf(o.x1, o.x2), maxOf(o.y1, o.y2))
        is Overlay.Field -> floatArrayOf(minOf(o.x1, o.x2), minOf(o.y1, o.y2), maxOf(o.x1, o.x2), maxOf(o.y1, o.y2))
        is Overlay.Ink -> null
    }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(pageIndex, size) {
                detectTapGestures { p ->
                    val nx = p.x / w; val ny = p.y / h
                    if (modeNow == EditMode.SELECT) {
                        val hit = overlays.indices.reversed().firstOrNull { i -> overlays[i].page == pageIndex && bounds(overlays[i])?.let { b -> nx in b[0]..b[2] && ny in b[1]..b[3] } == true }
                        selected.value = hit
                    }
                    onTap(nx, ny)
                }
            }
            .pointerInput(pageIndex, size) {
                var start = Offset.Zero
                var moving: Int? = null
                var resizing = false
                var points = ArrayList<Pair<Float, Float>>()
                detectDragGestures(
                    onDragStart = { p ->
                        start = p
                        val nx = p.x / w; val ny = p.y / h
                        if (modeNow == EditMode.SELECT) {
                            val sel = selected.value
                            val sb = sel?.let { overlays.getOrNull(it) }?.let { bounds(it) }
                            if (sel != null && sb != null && abs(nx - sb[2]) < 0.06f && abs(ny - sb[3]) < 0.06f && overlays[sel] is Overlay.Image) {
                                moving = sel; resizing = true
                            } else {
                                val hit = overlays.indices.reversed().firstOrNull { i -> overlays[i].page == pageIndex && bounds(overlays[i])?.let { b -> nx in b[0]..b[2] && ny in b[1]..b[3] } == true }
                                moving = hit; resizing = false; selected.value = hit
                            }
                        } else if (modeNow == EditMode.PEN) {
                            points = arrayListOf(nx to ny)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val p = change.position
                        val dx = amount.x / w; val dy = amount.y / h
                        when (modeNow) {
                            EditMode.SELECT -> moving?.let { i ->
                                val o = overlays.getOrNull(i) ?: return@let
                                overlays[i] = when (o) {
                                    is Overlay.Image -> if (resizing) {
                                        val nw = (o.w + dx).coerceIn(0.03f, 1f)
                                        o.copy(w = nw, h = nw * o.h / o.w)
                                    } else o.copy(x = o.x + dx, y = o.y + dy)
                                    is Overlay.Text -> o.copy(x = o.x + dx, y = o.y + dy)
                                    is Overlay.Shape -> o.copy(x1 = o.x1 + dx, x2 = o.x2 + dx, y1 = o.y1 + dy, y2 = o.y2 + dy)
                                    is Overlay.Redact -> o.copy(x1 = o.x1 + dx, x2 = o.x2 + dx, y1 = o.y1 + dy, y2 = o.y2 + dy)
                                    is Overlay.Field -> o.copy(x1 = o.x1 + dx, x2 = o.x2 + dx, y1 = o.y1 + dy, y2 = o.y2 + dy)
                                    is Overlay.Ink -> o.copy(points = o.points.map { (a, b) -> a + dx to b + dy })
                                }
                            }
                            EditMode.PEN -> { points.add(p.x / w to p.y / h); draft = Overlay.Ink(pageIndex, ArrayList(points), colorNow, strokeNow) }
                            EditMode.RECT, EditMode.CIRCLE, EditMode.LINE, EditMode.ARROW, EditMode.HIGHLIGHT -> {
                                val kind = when (modeNow) { EditMode.RECT -> Overlay.ShapeKind.RECT; EditMode.CIRCLE -> Overlay.ShapeKind.CIRCLE; EditMode.LINE -> Overlay.ShapeKind.LINE; EditMode.ARROW -> Overlay.ShapeKind.ARROW; else -> Overlay.ShapeKind.HIGHLIGHT }
                                val c = if (modeNow == EditMode.HIGHLIGHT && colorNow == 0xFF000000.toInt()) 0xFFFFEB3B.toInt() else colorNow
                                draft = Overlay.Shape(pageIndex, kind, start.x / w, start.y / h, p.x / w, p.y / h, c, strokeNow)
                            }
                            EditMode.REDACT -> draft = Overlay.Redact(pageIndex, start.x / w, start.y / h, p.x / w, p.y / h)
                            EditMode.FIELD_TEXT, EditMode.FIELD_CHECK -> {
                                val kind = if (modeNow == EditMode.FIELD_TEXT) Overlay.FieldKind.TEXT else Overlay.FieldKind.CHECKBOX
                                var x2 = p.x / w; var y2 = p.y / h
                                if (kind == Overlay.FieldKind.CHECKBOX) { val side = maxOf(abs(x2 - start.x / w), abs(y2 - start.y / h) * h / w); x2 = start.x / w + side; y2 = start.y / h + side * w / h }
                                draft = Overlay.Field(pageIndex, kind, start.x / w, start.y / h, x2, y2, "")
                            }
                            else -> {}
                        }
                    },
                    onDragEnd = {
                        val d = draft
                        if (d != null) {
                            val ok = bounds(d)?.let { b -> b[2] - b[0] > 0.01f || b[3] - b[1] > 0.01f } ?: true
                            if (ok) { if (d is Overlay.Field) onFieldDrawn(d) else { overlays.add(d); selected.value = overlays.size - 1 } }
                        }
                        draft = null; moving = null; resizing = false
                    },
                    onDragCancel = { draft = null; moving = null },
                )
            },
    ) {
        (overlays.filter { it.page == pageIndex } + listOfNotNull(draft)).forEach { o -> drawOverlay(o, w, h, pxPerPt) }
        selected.value?.let { overlays.getOrNull(it) }?.takeIf { it.page == pageIndex }?.let { o ->
            bounds(o)?.let { b ->
                drawRect(Color(0xFF2563EB), Offset(b[0] * w - 4, b[1] * h - 4), Size((b[2] - b[0]) * w + 8, (b[3] - b[1]) * h + 8), style = Stroke(2f))
                if (o is Overlay.Image) drawCircle(Color(0xFF2563EB), 14f, Offset(b[2] * w, b[3] * h))
            }
        }
    }
}

private fun DrawScope.drawOverlay(o: Overlay, w: Float, h: Float, pxPerPt: Float) {
    when (o) {
        is Overlay.Text -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.color; textSize = o.sizePt * pxPerPt; typeface = if (o.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            var y = o.y * h + o.sizePt * pxPerPt * 0.85f
            for (line in o.text.split('\n')) { drawContext.canvas.nativeCanvas.drawText(line, o.x * w, y, paint); y += o.sizePt * pxPerPt * 1.2f }
        }
        is Overlay.Image -> imageFor(o.file)?.let { img ->
            drawImage(img, dstOffset = IntOffset((o.x * w).toInt(), (o.y * h).toInt()), dstSize = IntSize((o.w * w).toInt().coerceAtLeast(1), (o.h * h).toInt().coerceAtLeast(1)))
        }
        is Overlay.Shape -> {
            val c = Color(o.color)
            val l = minOf(o.x1, o.x2) * w; val t = minOf(o.y1, o.y2) * h; val r = maxOf(o.x1, o.x2) * w; val b = maxOf(o.y1, o.y2) * h
            val sw = o.stroke * pxPerPt
            when (o.kind) {
                Overlay.ShapeKind.RECT -> drawRect(c, Offset(l, t), Size(r - l, b - t), style = if (o.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(sw))
                Overlay.ShapeKind.HIGHLIGHT -> drawRect(c.copy(alpha = 0.38f), Offset(l, t), Size(r - l, b - t))
                Overlay.ShapeKind.CIRCLE -> drawOval(c, Offset(l, t), Size(r - l, b - t), style = if (o.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(sw))
                Overlay.ShapeKind.LINE, Overlay.ShapeKind.ARROW -> {
                    val a = Offset(o.x1 * w, o.y1 * h); val e = Offset(o.x2 * w, o.y2 * h)
                    drawLine(c, a, e, sw, StrokeCap.Round)
                    if (o.kind == Overlay.ShapeKind.ARROW) {
                        val ang = atan2((e.y - a.y).toDouble(), (e.x - a.x).toDouble())
                        val len = (o.stroke * 4 + 8) * pxPerPt
                        for (d in listOf(Math.PI * 0.85, -Math.PI * 0.85)) drawLine(c, e, Offset(e.x + (len * cos(ang + d)).toFloat(), e.y + (len * sin(ang + d)).toFloat()), sw, StrokeCap.Round)
                    }
                }
            }
        }
        is Overlay.Ink -> {
            if (o.points.size > 1) {
                val path = Path().apply { moveTo(o.points[0].first * w, o.points[0].second * h); o.points.drop(1).forEach { lineTo(it.first * w, it.second * h) } }
                drawPath(path, Color(o.color), style = Stroke(o.stroke * pxPerPt, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        is Overlay.Redact -> drawRect(Color.Black, Offset(minOf(o.x1, o.x2) * w, minOf(o.y1, o.y2) * h), Size(abs(o.x2 - o.x1) * w, abs(o.y2 - o.y1) * h))
        is Overlay.Field -> {
            val off = Offset(minOf(o.x1, o.x2) * w, minOf(o.y1, o.y2) * h); val sz = Size(abs(o.x2 - o.x1) * w, abs(o.y2 - o.y1) * h)
            drawRect(Color(0x332563EB), off, sz); drawRect(Color(0xFF2563EB), off, sz, style = Stroke(2f))
            if (o.name.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(o.name, off.x + 4, off.y + 22, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt(); textSize = 22f })
        }
    }
}
