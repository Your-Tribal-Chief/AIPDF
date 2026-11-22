package com.allpdf.pro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import com.allpdf.pro.engine.ConvertToEngine
import com.allpdf.pro.engine.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ScanFilter(val label: String) { ORIGINAL("Original"), GRAY("Grayscale"), BW("High-contrast B&W") }

class ScanPage(val source: File) {
    var rotation by mutableIntStateOf(0)
    var filter by mutableStateOf(ScanFilter.ORIGINAL)
    var crop by mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f))
    var version by mutableIntStateOf(0)
}

object ScanImaging {
    /** Rotate -> crop -> filter, decoded at most 3000px. */
    fun process(p: ScanPage, maxSide: Int = 3000, crop: FloatArray = p.crop): Bitmap? {
        var b = ImageIO.decode(p.source, maxSide) ?: return null
        if (p.rotation != 0) { val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(p.rotation.toFloat()) }, true); if (r !== b) b.recycle(); b = r }
        val c = crop
        if (c[0] > 0.001f || c[1] > 0.001f || c[2] < 0.999f || c[3] < 0.999f) {
            val x = (c[0] * b.width).toInt(); val y = (c[1] * b.height).toInt()
            val w = ((c[2] - c[0]) * b.width).toInt().coerceAtLeast(8); val h = ((c[3] - c[1]) * b.height).toInt().coerceAtLeast(8)
            val r = Bitmap.createBitmap(b, x.coerceIn(0, b.width - 8), y.coerceIn(0, b.height - 8), w.coerceAtMost(b.width - x), h.coerceAtMost(b.height - y)); if (r !== b) b.recycle(); b = r
        }
        if (p.filter != ScanFilter.ORIGINAL) {
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix().apply { setSaturation(0f) }
            if (p.filter == ScanFilter.BW) {
                val contrast = 2.6f; val t = (-0.5f * contrast + 0.5f) * 255f + 40f
                cm.postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            }
            Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            b.recycle(); b = out
        }
        return b
    }

    /** Finds the document region: bounding box of pixels that differ from the border background. */
    fun autoCrop(p: ScanPage): FloatArray {
        val b = ImageIO.decode(p.source, 400) ?: return floatArrayOf(0f, 0f, 1f, 1f)
        val src = if (p.rotation != 0) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(p.rotation.toFloat()) }, true) else b
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(c: Int) = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        // Background = median luminance along the border; document = pixels much brighter (paper) than it.
        val border = ArrayList<Int>()
        for (x in 0 until w) { border.add(lum(px[x])); border.add(lum(px[(h - 1) * w + x])) }
        for (y in 0 until h) { border.add(lum(px[y * w])); border.add(lum(px[y * w + w - 1])) }
        border.sort()
        val bg = border[border.size / 2]
        var all = 0L; for (c in px) all += lum(c)
        val mean = (all / px.size).toInt()
        val paperBright = mean > bg
        val thr = (bg + mean) / 2
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        val rowCount = IntArray(h); val colCount = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            val l = lum(px[y * w + x])
            if (if (paperBright) l > thr + 12 else l < thr - 12) { rowCount[y]++; colCount[x]++ }
        }
        for (y in 0 until h) if (rowCount[y] > w / 6) { minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        for (x in 0 until w) if (colCount[x] > h / 6) { minX = minOf(minX, x); maxX = maxOf(maxX, x) }
        if (src !== b) src.recycle(); b.recycle()
        if (maxX - minX < w / 4 || maxY - minY < h / 4) return floatArrayOf(0f, 0f, 1f, 1f)
        return floatArrayOf(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1f) / w, (maxY + 1f) / h)
    }
}

@Composable
fun ScanPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = s.remembered("pages") { mutableStateListOf<ScanPage>() }.value
    var current by s.remembered("cur") { 0 }
    var pendingShot by remember { mutableStateOf<File?>(null) }
    var size by s.remembered("size") { ConvertToEngine.PageSize.A4 }
    var cropMode by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingShot
        if (ok && f != null && f.length() > 0) { pages.add(ScanPage(f)); current = pages.size - 1
            scope.launch { val p = pages.last(); p.crop = withContext(Dispatchers.Default) { ScanImaging.autoCrop(p) } } }
        pendingShot = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        scope.launch { uris.forEach { u -> pages.add(ScanPage(withContext(Dispatchers.IO) { Files.importUri(ctx, u) })) }; if (pages.isNotEmpty()) current = pages.size - 1 }
    }
    fun shoot() {
        val dir = File(ctx.cacheDir, "scans").apply { mkdirs() }
        val f = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        pendingShot = f
        try { camera.launch(Files.uriFor(ctx, f)) } catch (e: Exception) { s.error = "No camera app available" }
    }
    Column(Modifier.fillMaxSize()) {
        if (pages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ToolIcon(Tool.SCAN, 84); Spacer(Modifier.height(16.dp))
                Text("Capture document pages", style = MaterialTheme.typography.titleLarge)
                Text("Auto-crop, rotate and filter each page, then build one PDF", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Open camera", icon = Icons.Filled.PhotoCamera) { shoot() }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { gallery.launch("image/*") }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Collections, null); Spacer(Modifier.width(6.dp)); Text("Import photos") }
            }
        } else {
            val p = pages[current.coerceIn(0, pages.size - 1)]
            val preview by produceState<Bitmap?>(null, p, p.rotation, p.filter, if (cropMode) null else p.crop, cropMode) {
                value = withContext(Dispatchers.Default) {
                    if (cropMode) ScanImaging.process(p, 1400, floatArrayOf(0f, 0f, 1f, 1f)) else ScanImaging.process(p, 1400)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val bmp = preview
                if (bmp == null) CircularProgressIndicator()
                else {
                    val density = LocalDensity.current
                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                        val aspect = bmp.width.toFloat() / bmp.height
                        val maxW = constraints.maxWidth.toFloat(); val maxH = constraints.maxHeight.toFloat()
                        val w = minOf(maxW, maxH * aspect); val h = w / aspect
                        var sz by remember { mutableStateOf(IntSize.Zero) }
                        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).onSizeChanged { sz = it }) {
                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
                            if (cropMode && sz.width > 0) CropOverlay(sz, p.crop) { p.crop = it }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { p.rotation = (p.rotation + 270) % 360; p.crop = floatArrayOf(0f, 0f, 1f, 1f) }) { Icon(Icons.Filled.RotateLeft, "Rotate left") }
                IconButton(onClick = { p.rotation = (p.rotation + 90) % 360; p.crop = floatArrayOf(0f, 0f, 1f, 1f) }) { Icon(Icons.Filled.RotateRight, "Rotate right") }
                FilledTonalIconToggleButton(checked = cropMode, onCheckedChange = { cropMode = it }) { Icon(Icons.Filled.Crop, "Crop") }
                IconButton(onClick = { scope.launch { p.crop = withContext(Dispatchers.Default) { ScanImaging.autoCrop(p) }; cropMode = false } }) { Icon(Icons.Filled.AutoFixHigh, "Auto crop") }
                IconButton(onClick = { pages.removeAt(current); current = (current - 1).coerceAtLeast(0) }) { Icon(Icons.Filled.Delete, "Delete page") }
            }
            Row(Modifier.padding(horizontal = 12.dp)) { Choice(ScanFilter.entries, p.filter, { it.label }) { p.filter = it } }
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages) { i, pg ->
                    val thumb = remember(pg.source) { ImageIO.decode(pg.source, 200)?.asImageBitmap() }
                    Box(Modifier.size(56.dp, 74.dp).clip(RoundedCornerShape(8.dp)).border(if (i == current) 3.dp else 1.dp, if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { current = i; cropMode = false }) {
                        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize())
                    }
                }
                item {
                    Box(Modifier.size(56.dp, 74.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable { shoot() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AddAPhoto, "Add page")
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp)) { Choice(ConvertToEngine.PageSize.entries, size, { it.label }) { size = it } }
            ActionBar("Create PDF (${pages.size} page${if (pages.size == 1) "" else "s"})", icon = Icons.Filled.PictureAsPdf) {
                val list = pages.toList(); val sz = size
                s.run(ctx) { c ->
                    val processed = list.mapIndexed { i, pg ->
                        c.step(i * 0.6f / list.size, "Enhancing page ${i + 1}")
                        val b = ScanImaging.process(pg) ?: error("Couldn't read page ${i + 1}")
                        File(s.workDir, "scan_page_${i + 1}.jpg").also { f -> FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }; b.recycle() }
                    }
                    val out = File(s.workDir, "Scan_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())}.pdf")
                    ConvertToEngine.imagesToPdf(processed, sz, ConvertToEngine.Orientation.AUTO, ConvertToEngine.Margin.NONE, out, c.sub(0.6f, 1f))
                    ToolResult(listOf(out), "${list.size} scanned pages")
                }
            }
        }
    }
}
