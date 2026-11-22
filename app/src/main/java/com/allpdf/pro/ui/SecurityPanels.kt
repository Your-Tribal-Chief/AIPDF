package com.allpdf.pro.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.baseName
import com.allpdf.pro.engine.EditEngine
import com.allpdf.pro.engine.ImageIO
import com.allpdf.pro.engine.Overlay
import com.allpdf.pro.engine.SecurityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ProtectPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var pw by s.remembered("pw") { "" }
    var pw2 by s.remembered("pw2") { "" }
    var owner by s.remembered("owner") { "" }
    var print by s.remembered("print") { true }
    var copy by s.remembered("copy") { true }
    var edit by s.remembered("edit") { false }
    val ok = pw.length >= 1 && pw == pw2
    OptionsLayout("Encrypt PDF", ok, Icons.Filled.Lock, onRun = {
        val a = pw; val o = owner; val p = print; val c2 = copy; val e = edit
        s.run(ctx) { c -> ToolResult(listOf(SecurityEngine.protect(f, a, o, p, c2, e, File(s.workDir, "${f.baseName()}_protected.pdf"), c)), "AES-256 encrypted") }
    }) {
        FileRow(f)
        Section("Open password") {
            Field(pw, { pw = it }, "Password", password = true)
            Spacer(Modifier.height(8.dp))
            Field(pw2, { pw2 = it }, "Repeat password", password = true)
            if (pw2.isNotEmpty() && pw != pw2) Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Section("Permissions") {
            Field(owner, { owner = it }, "Owner password (optional)", password = true)
            SwitchRow("Allow printing", print) { print = it }
            SwitchRow("Allow copying text & images", copy) { copy = it }
            SwitchRow("Allow editing & page assembly", edit) { edit = it }
        }
        InfoCard("Without an owner password a random one is generated, so restrictions can't be removed with the open password.")
    }
}

@Composable
fun UnlockPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var pw by s.remembered("pw") { "" }
    OptionsLayout("Unlock PDF", icon = Icons.Filled.LockOpen, onRun = {
        val p = pw
        s.run(ctx) { c -> ToolResult(listOf(SecurityEngine.unlock(f, p, File(s.workDir, "${f.baseName()}_unlocked.pdf"), c)), "Password removed") }
    }) {
        FileRow(f)
        Field(pw, { pw = it }, "Current password", password = true)
        InfoCard("The password is verified on-device before an unprotected copy is saved. Leave empty for PDFs that only restrict printing/copying.")
    }
}

// ---------------------------------------------------------------- Sign

@Composable
fun SignPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val pages = rememberPages(f)
    val overlays = s.remembered("ov") { mutableStateListOf<Overlay>() }.value
    val signatures = s.remembered("sigs") { mutableStateListOf<File>() }.value
    var index by s.remembered("idx") { 0 }
    val selected = s.remembered<Int?>("sel") { null }
    var creating by remember { mutableStateOf(signatures.isEmpty()) }
    val pageW by produceState(595f, pages, index, pages.count) { if (pages.count > index) value = pages.size(index).first }
    val scope = rememberCoroutineScope()

    fun place(sig: File) {
        scope.launch {
            val b = withContext(Dispatchers.IO) { ImageIO.decode(sig, 400) } ?: return@launch
            val (pw, ph) = pages.size(index)
            val w = 0.32f; val h = w * pw / ph * b.height / b.width
            overlays.add(Overlay.Image(index, 0.55f, 0.75f, w, h, sig)); selected.value = overlays.size - 1
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            signatures.forEach { sig ->
                val bmp = remember(sig) { ImageIO.decode(sig, 300)?.asImageBitmap() }
                Surface(onClick = { place(sig) }, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = Color.White) {
                    if (bmp != null) Image(bmp, "Place signature", Modifier.size(96.dp, 44.dp).padding(4.dp), contentScale = ContentScale.Fit)
                }
            }
            AssistChip(onClick = { creating = true }, label = { Text("New signature") }, leadingIcon = { Icon(Icons.Filled.Draw, null) })
        }
        Text(if (signatures.isEmpty()) "Create a signature first" else "Tap a signature to place it • drag to move • drag the blue dot to resize",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        PageCanvas(pages, index, Modifier.weight(1f).padding(12.dp)) { sz ->
            OverlayLayer(sz, index, pageW, overlays, EditMode.SELECT, 0, 1f, selected)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected.value?.let { if (it in overlays.indices) overlays.removeAt(it) }; selected.value = null }, enabled = selected.value != null) { Icon(Icons.Filled.Delete, "Remove") }
            Box(Modifier.weight(1f)) { PageNavigator(index, pages.count) { index = it; selected.value = null } }
        }
        ActionBar("Sign & save (${overlays.size})", overlays.isNotEmpty(), Icons.Filled.Draw) {
            val list = overlays.toList()
            s.run(ctx) { c -> ToolResult(listOf(EditEngine.applyOverlays(f, list, File(s.workDir, "${f.baseName()}_signed.pdf"), c)), "Signature embedded into the page content") }
        }
    }
    if (creating) SignatureDialog(s.workDir, onDone = { file -> creating = false; if (file != null) { signatures.add(file); place(file) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureDialog(dir: File, onDone: (File?) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(0) }
    var ink by remember { mutableStateOf(0xFF1E3A8A.toInt()) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { onDone(withContext(Dispatchers.IO) { Files.importUri(ctx, uri) }) }
    }
    val faces = listOf(Typeface.create("cursive", Typeface.NORMAL), Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC), Typeface.create("casual", Typeface.NORMAL), Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC))
    val composeFaces = listOf(FontFamily.Cursive, FontFamily.Serif, FontFamily.Cursive, FontFamily.SansSerif)
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text("Create signature") }, text = {
        Column {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Draw") }); Tab(tab == 1, { tab = 1 }, text = { Text("Type") }); Tab(tab == 2, { tab = 2 }, text = { Text("Import") })
            }
            Spacer(Modifier.height(12.dp))
            ColorDots(ink, { ink = it }, listOf(0xFF000000.toInt(), 0xFF1E3A8A.toInt(), 0xFFB91C1C.toInt()))
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(12.dp))) {
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        padSize = size
                        detectDragGestures(onDragStart = { current = listOf(it) }, onDrag = { ch, _ -> ch.consume(); current = current + ch.position },
                            onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() })
                    }) {
                        (strokes + listOf(current)).forEach { pts ->
                            if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, Color(ink), style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        drawLine(Color.LightGray, Offset(20f, size.height * 0.78f), Offset(size.width - 20f, size.height * 0.78f), 2f)
                    }
                    if (strokes.isNotEmpty()) TextButton(onClick = { strokes.clear() }, Modifier.align(Alignment.TopEnd)) { Text("Clear") }
                }
                1 -> Column {
                    Field(name, { name = it }, "Your name")
                    Spacer(Modifier.height(8.dp))
                    composeFaces.forEachIndexed { i, fam ->
                        Surface(onClick = { style = i }, shape = RoundedCornerShape(10.dp), color = Color.White,
                            border = BorderStroke(if (style == i) 2.dp else 1.dp, if (style == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(name.ifBlank { "Signature" }, fontFamily = fam, fontStyle = if (i == 1 || i == 3) FontStyle.Italic else FontStyle.Normal, fontSize = 26.sp, color = Color(ink), modifier = Modifier.padding(10.dp))
                        }
                    }
                }
                else -> OutlinedButton(onClick = { importer.launch(arrayOf("image/png", "image/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text("Choose PNG signature") }
            }
        }
    }, confirmButton = {
        TextButton(enabled = (tab == 0 && strokes.isNotEmpty()) || (tab == 1 && name.isNotBlank()), onClick = {
            val bmp = if (tab == 0) renderStrokes(strokes, ink) else renderTyped(name, faces[style], ink)
            val out = File(dir, "signature_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            onDone(out)
        }) { Text("Use") }
    }, dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } })
}

private fun renderStrokes(strokes: List<List<Offset>>, color: Int): Bitmap {
    val all = strokes.flatten()
    val minX = all.minOf { it.x } - 10; val minY = all.minOf { it.y } - 10
    val maxX = all.maxOf { it.x } + 10; val maxY = all.maxOf { it.y } + 10
    val scale = 2f
    val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceAtLeast(10), ((maxY - minY) * scale).toInt().coerceAtLeast(10), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 6f * scale; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (s in strokes) {
        val path = android.graphics.Path()
        s.forEachIndexed { i, o -> val x = (o.x - minX) * scale; val y = (o.y - minY) * scale; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        c.drawPath(path, p)
    }
    return bmp
}

private fun renderTyped(text: String, face: Typeface, color: Int): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 160f; typeface = face }
    val w = p.measureText(text).toInt() + 60
    val fm = p.fontMetrics
    val h = (fm.descent - fm.ascent).toInt() + 40
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawText(text, 30f, 20f - fm.ascent, p)
    return bmp
}

// ---------------------------------------------------------------- Redact

@Composable
fun RedactPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val pages = rememberPages(f)
    val overlays = s.remembered("ov") { mutableStateListOf<Overlay>() }.value
    var index by s.remembered("idx") { 0 }
    var mode by s.remembered("mode") { EditMode.REDACT }
    var strip by s.remembered("strip") { true }
    val selected = s.remembered<Int?>("sel") { null }
    val pageW by produceState(595f, pages, index, pages.count) { if (pages.count > index) value = pages.size(index).first }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(mode == EditMode.REDACT, { mode = EditMode.REDACT }, { Text("Draw box") }, leadingIcon = { Icon(Icons.Filled.Rectangle, null) })
            FilterChip(mode == EditMode.SELECT, { mode = EditMode.SELECT }, { Text("Move") }, leadingIcon = { Icon(Icons.Filled.PanTool, null) })
            FilterChip(strip, { strip = !strip }, { Text("Strip metadata") })
        }
        PageCanvas(pages, index, Modifier.weight(1f).padding(12.dp)) { sz ->
            OverlayLayer(sz, index, pageW, overlays, mode, 0, 1f, selected)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (overlays.isNotEmpty()) overlays.removeAt(overlays.size - 1); selected.value = null }, enabled = overlays.isNotEmpty()) { Icon(Icons.Filled.Undo, "Undo") }
            Box(Modifier.weight(1f)) { PageNavigator(index, pages.count) { index = it } }
        }
        InfoCard("Redacted pages are flattened to pixels so the hidden text and data are destroyed, not just covered.", Icons.Filled.Shield)
        ActionBar("Redact ${overlays.size} area${if (overlays.size == 1) "" else "s"} permanently", overlays.isNotEmpty(), Icons.Filled.VisibilityOff) {
            val boxes = overlays.filterIsInstance<Overlay.Redact>(); val st = strip
            s.run(ctx) { c -> ToolResult(listOf(SecurityEngine.redact(f, boxes, 200, st, File(s.workDir, "${f.baseName()}_redacted.pdf"), c)), "${boxes.size} areas destroyed on ${boxes.map { it.page }.distinct().size} page(s)") }
        }
    }
}

// ---------------------------------------------------------------- Compare

@Composable
fun ComparePanel(s: ToolSession, picker: Picker) {
    val ctx = LocalContext.current
    OptionsLayout("Compare", s.inputs.size >= 2, Icons.Filled.Compare, onRun = {
        val a = s.inputs[0]; val b = s.inputs[1]
        s.run(ctx) { c ->
            val r = SecurityEngine.compare(a, b, File(s.workDir, "compare_${a.baseName()}_vs_${b.baseName()}.pdf"), c)
            ToolResult(listOf(r.report), "Comparison report", compare = r)
        }
    }) {
        Section("Original (A) and changed (B)") { InputList(s, true) }
        if (s.inputs.size < 2) OutlinedButton(onClick = { picker.launch(true) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text("Add second PDF") }
        if (s.inputs.size > 2) InfoCard("Only the first two files are compared.")
        InfoCard("You'll get a text diff (additions, deletions, modifications with word-level highlights) and a visual report with side-by-side pages and a pixel overlay.")
    }
}
