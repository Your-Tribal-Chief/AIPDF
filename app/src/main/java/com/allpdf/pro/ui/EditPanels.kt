package com.allpdf.pro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.allpdf.pro.engine.EditEngine
import com.allpdf.pro.engine.ImageIO
import com.allpdf.pro.engine.OrganizeEngine
import com.allpdf.pro.engine.Overlay
import com.allpdf.pro.engine.ProductivityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable
fun ResizePanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var size by s.remembered("size") { "A4" }
    var unit by s.remembered("unit") { "mm" }
    var cw by s.remembered("cw") { "210" }
    var ch by s.remembered("ch") { "297" }
    var fit by s.remembered("fit") { EditEngine.Fit.FIT }
    var auto by s.remembered("auto") { true }
    fun toPt(v: String): Float = (v.toFloatOrNull() ?: 0f) * when (unit) { "mm" -> 72f / 25.4f; "in" -> 72f; else -> 0.75f }
    val target: Pair<Float, Float>? = if (size == "Custom") (toPt(cw) to toPt(ch)).takeIf { it.first > 20 && it.second > 20 } else ProductivityEngine.SIZES[size]
    OptionsLayout("Resize to $size", target != null, Icons.Filled.AspectRatio, onRun = {
        val (w, h) = target!!; val m = fit; val a = auto
        s.run(ctx) { c -> ToolResult(listOf(EditEngine.resize(f, w, h, m, a, File(s.workDir, "${f.baseName()}_$size.pdf"), c)), "Pages resized to $size") }
    }) {
        FileRow(f)
        Section("Page size") { Choice(listOf("A3", "A4", "A5", "Letter", "Legal", "Custom"), size, { it }) { size = it } }
        if (size == "Custom") {
            Choice(listOf("mm", "in", "px"), unit, { it }) { unit = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(cw, { cw = it }, "Width ($unit)", Modifier.weight(1f), number = true)
                Field(ch, { ch = it }, "Height ($unit)", Modifier.weight(1f), number = true)
            }
        }
        Section("Scaling") { Choice(EditEngine.Fit.entries, fit, { when (it) { EditEngine.Fit.FIT -> "Fit"; EditEngine.Fit.FILL -> "Fill"; EditEngine.Fit.CENTER -> "Center (no scale)" } }) { fit = it } }
        SwitchRow("Match each page's orientation", auto) { auto = it }
    }
}

@Composable
fun CropPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val pages = rememberPages(f)
    var index by s.remembered("idx") { 0 }
    var rect by s.remembered("rect") { floatArrayOf(0.08f, 0.08f, 0.92f, 0.92f) }
    var scope by s.remembered("scope") { "all" }
    var range by s.remembered("range") { "1-" }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Choice(listOf("page", "all", "range"), scope, { when (it) { "page" -> "This page"; "all" -> "All pages"; else -> "Page range" } }) { scope = it }
            if (scope == "range") Field(range, { range = it }, "Pages, e.g. 1-3, 6")
        }
        PageCanvas(pages, index, Modifier.weight(1f).padding(12.dp)) { sz ->
            CropOverlay(sz, rect) { rect = it }
        }
        PageNavigator(index, pages.count) { index = it }
        ActionBar("Crop", icon = Icons.Filled.Crop) {
            val r = rect; val sc = scope; val rg = range; val i = index
            s.run(ctx) { c ->
                val set: Set<Int>? = when (sc) { "page" -> setOf(i); "range" -> OrganizeEngine.parseRanges(rg, Pdf.pageCount(f)).flatMap { it.toList() }.toSet(); else -> null }
                ToolResult(listOf(EditEngine.crop(f, r[0], r[1], r[2], r[3], set, File(s.workDir, "${f.baseName()}_cropped.pdf"), c)), "Cropped ${set?.size ?: "all"} page(s)")
            }
        }
    }
}

@Composable
fun CropOverlay(size: androidx.compose.ui.unit.IntSize, rect: FloatArray, onChange: (FloatArray) -> Unit) {
    val w = size.width.toFloat(); val h = size.height.toFloat()
    val current by rememberUpdatedState(rect)
    Canvas(Modifier.fillMaxSize().pointerInput(size) {
        var handle = -1
        detectDragGestures(onDragStart = { p ->
            val r = current
            val corners = listOf(r[0] to r[1], r[2] to r[1], r[0] to r[3], r[2] to r[3])
            handle = corners.indexOfFirst { (x, y) -> abs(p.x - x * w) < 60 && abs(p.y - y * h) < 60 }
            if (handle < 0 && p.x / w in r[0]..r[2] && p.y / h in r[1]..r[3]) handle = 4
        }, onDrag = { ch, d ->
            ch.consume()
            val r = current.copyOf(); val dx = d.x / w; val dy = d.y / h
            when (handle) {
                0 -> { r[0] += dx; r[1] += dy }; 1 -> { r[2] += dx; r[1] += dy }
                2 -> { r[0] += dx; r[3] += dy }; 3 -> { r[2] += dx; r[3] += dy }
                4 -> { val mx = dx.coerceIn(-r[0], 1 - r[2]); val my = dy.coerceIn(-r[1], 1 - r[3]); r[0] += mx; r[2] += mx; r[1] += my; r[3] += my }
                else -> return@detectDragGestures
            }
            for (k in 0..3) r[k] = r[k].coerceIn(0f, 1f)
            if (r[2] - r[0] > 0.05f && r[3] - r[1] > 0.05f) onChange(r)
        })
    }) {
        val l = rect[0] * w; val t = rect[1] * h; val r = rect[2] * w; val b = rect[3] * h
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, Offset.Zero, Size(w, t)); drawRect(shade, Offset(0f, b), Size(w, h - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t)); drawRect(shade, Offset(r, t), Size(w - r, b - t))
        drawRect(Color(0xFF7C3AED), Offset(l, t), Size(r - l, b - t), style = Stroke(4f))
        listOf(l to t, r to t, l to b, r to b).forEach { (x, y) -> drawCircle(Color.White, 16f, Offset(x, y)); drawCircle(Color(0xFF7C3AED), 11f, Offset(x, y)) }
    }
}

@Composable
fun NumbersPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var top by s.remembered("top") { false }
    var align by s.remembered("align") { 1 }
    var format by s.remembered("fmt") { 0 }
    var size by s.remembered("size") { 11f }
    var color by s.remembered("color") { 0xFF000000.toInt() }
    var start by s.remembered("start") { "1" }
    var skip by s.remembered("skip") { false }
    OptionsLayout("Add page numbers", icon = Icons.Filled.FormatListNumbered, onRun = {
        val o = EditEngine.NumberOpts(top, align, format, size, color, start.toIntOrNull() ?: 1, skip)
        s.run(ctx) { c -> ToolResult(listOf(EditEngine.pageNumbers(f, o, File(s.workDir, "${f.baseName()}_numbered.pdf"), c)), "Page numbers added") }
    }) {
        FileRow(f)
        Section("Position") {
            Choice(listOf(false, true), top, { if (it) "Top" else "Bottom" }) { top = it }
            Spacer(Modifier.height(4.dp))
            Choice(listOf(0, 1, 2), align, { listOf("Left", "Center", "Right")[it] }) { align = it }
        }
        Section("Format") { Choice(listOf(0, 1, 2), format, { listOf("1", "1 / N", "Page 1 of N")[it] }) { format = it } }
        SliderRow("Font size", size, 6f..36f) { size = it.toInt().toFloat() }
        Section("Color") { ColorDots(color, { color = it }) }
        Field(start, { start = it.filter(Char::isDigit) }, "Start numbering at", number = true)
        SwitchRow("Skip cover page", skip, "First page gets no number") { skip = it }
    }
}

@Composable
fun WatermarkPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val scope = rememberCoroutineScope()
    var kind by s.remembered("kind") { "text" }
    var text by s.remembered("text") { "CONFIDENTIAL" }
    var image by s.remembered<File?>("image") { null }
    var opacity by s.remembered("op") { 0.3f }
    var rotation by s.remembered("rot") { 45f }
    var size by s.remembered("size") { 48f }
    var pos by s.remembered("pos") { 4 }
    var under by s.remembered("under") { false }
    var color by s.remembered("color") { 0xFFE5322D.toInt() }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { image = withContext(Dispatchers.IO) { Files.importUri(ctx, uri) } }
    }
    OptionsLayout("Add watermark", (kind == "text" && text.isNotBlank()) || (kind == "image" && image != null), Icons.Filled.BrandingWatermark, onRun = {
        val o = EditEngine.WatermarkOpts(if (kind == "text") text else null, if (kind == "image") image else null, opacity, rotation, size, pos, under, color)
        s.run(ctx) { c -> ToolResult(listOf(EditEngine.watermark(f, o, File(s.workDir, "${f.baseName()}_watermarked.pdf"), c)), "Watermark applied") }
    }) {
        FileRow(f)
        Section("Type") { Choice(listOf("text", "image"), kind, { if (it == "text") "Text" else "Image" }) { kind = it } }
        if (kind == "text") {
            Field(text, { text = it }, "Watermark text")
            Spacer(Modifier.height(8.dp))
            ColorDots(color, { color = it })
            SliderRow("Font size", size, 12f..160f) { size = it.toInt().toFloat() }
        } else {
            OutlinedButton(onClick = { imgPicker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text(image?.name ?: "Choose image (PNG with transparency works best)")
            }
            SliderRow("Width", size.coerceIn(5f, 100f), 5f..100f, format = { "${it.toInt()}% of page" }) { size = it }
        }
        SliderRow("Opacity", opacity, 0.05f..1f, format = { "${(it * 100).toInt()}%" }) { opacity = it }
        SliderRow("Rotation", rotation, -180f..180f, format = { "${it.toInt()}°" }) { rotation = it }
        Section("Position") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0..2) { val p = r * 3 + c
                        FilterChip(selected = pos == p, onClick = { pos = p }, label = { Text(listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")[p]) }) }
                }
                FilterChip(selected = pos == 9, onClick = { pos = 9 }, label = { Text("Tiled mosaic") }, leadingIcon = { Icon(Icons.Filled.GridView, null) })
            }
        }
        Section("Layer") { Choice(listOf(false, true), under, { if (it) "Below content" else "Over content" }) { under = it } }
    }
}

// ---------------------------------------------------------------- Edit PDF

private data class ToolBtn(val mode: EditMode, val icon: ImageVector, val label: String)

@Composable
fun EditPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = s.inputs.first()
    val pages = rememberPages(f)
    val overlays = s.remembered("ov") { mutableStateListOf<Overlay>() }.value
    var index by s.remembered("idx") { 0 }
    var mode by s.remembered("mode") { EditMode.TEXT }
    var color by s.remembered("color") { 0xFF000000.toInt() }
    var stroke by s.remembered("stroke") { 2f }
    var textSize by s.remembered("ts") { 14f }
    val selected = s.remembered<Int?>("sel") { null }
    var textAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val pageW by produceState(595f, pages, index, pages.count) { if (pages.count > index) value = pages.size(index).first }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val file = withContext(Dispatchers.IO) { Files.importUri(ctx, uri) }
            val b = withContext(Dispatchers.IO) { ImageIO.decode(file, 400) }
            if (b != null) {
                val (pw, ph) = pages.size(index)
                val w = 0.4f; val h = w * pw / ph * b.height / b.width
                overlays.add(Overlay.Image(index, 0.3f, 0.3f, w, h, file)); selected.value = overlays.size - 1; mode = EditMode.SELECT
            }
        }
    }
    val tools = listOf(
        ToolBtn(EditMode.SELECT, Icons.Filled.PanTool, "Move"), ToolBtn(EditMode.TEXT, Icons.Filled.TextFields, "Text"),
        ToolBtn(EditMode.PEN, Icons.Filled.Gesture, "Draw"), ToolBtn(EditMode.HIGHLIGHT, Icons.Filled.BorderColor, "Highlight"),
        ToolBtn(EditMode.RECT, Icons.Filled.CropSquare, "Rect"), ToolBtn(EditMode.CIRCLE, Icons.Filled.RadioButtonUnchecked, "Circle"),
        ToolBtn(EditMode.LINE, Icons.Filled.HorizontalRule, "Line"), ToolBtn(EditMode.ARROW, Icons.Filled.ArrowRightAlt, "Arrow"),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            tools.forEach { t ->
                FilterChip(selected = mode == t.mode, onClick = { mode = t.mode }, label = { Text(t.label) }, leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(end = 6.dp))
            }
            AssistChip(onClick = { imgPicker.launch(arrayOf("image/*")) }, label = { Text("Image") }, leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null, Modifier.size(18.dp)) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ColorDots(color, { color = it }) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode == EditMode.TEXT) { Text("Size ${textSize.toInt()}", Modifier.width(64.dp)); Slider(textSize, { textSize = it.toInt().toFloat() }, valueRange = 6f..72f, modifier = Modifier.weight(1f)) }
            else { Text("Stroke ${stroke.toInt()}", Modifier.width(72.dp)); Slider(stroke, { stroke = it }, valueRange = 1f..12f, modifier = Modifier.weight(1f)) }
        }
        PageCanvas(pages, index, Modifier.weight(1f).padding(horizontal = 12.dp)) { sz ->
            OverlayLayer(sz, index, pageW, overlays, mode, color, stroke, selected, onTap = { x, y -> if (mode == EditMode.TEXT) textAt = x to y })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (overlays.isNotEmpty()) { overlays.removeAt(overlays.size - 1); selected.value = null } }, enabled = overlays.isNotEmpty()) { Icon(Icons.Filled.Undo, "Undo") }
            IconButton(onClick = { selected.value?.let { if (it in overlays.indices) overlays.removeAt(it) }; selected.value = null }, enabled = selected.value != null) { Icon(Icons.Filled.Delete, "Delete selected") }
            Box(Modifier.weight(1f)) { PageNavigator(index, pages.count) { index = it; selected.value = null } }
        }
        ActionBar("Save ${overlays.size} edit${if (overlays.size == 1) "" else "s"}", overlays.isNotEmpty(), Icons.Filled.Save) {
            val list = overlays.toList()
            s.run(ctx) { c -> ToolResult(listOf(EditEngine.applyOverlays(f, list, File(s.workDir, "${f.baseName()}_edited.pdf"), c)), "Edits saved permanently into the PDF") }
        }
    }
    textAt?.let { (x, y) ->
        var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { textAt = null }, title = { Text("Add text") },
            text = { Field(t, { t = it }, "Text", singleLine = false, minLines = 2) },
            confirmButton = { TextButton(onClick = {
                if (t.isNotBlank()) { overlays.add(Overlay.Text(index, x, y, t, textSize, color)); selected.value = overlays.size - 1 }
                textAt = null
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- Forms

@Composable
fun FormsPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val fields by produceState<List<EditEngine.FormField>?>(null, f) { value = withContext(Dispatchers.IO) { try { EditEngine.readFields(f) } catch (_: Exception) { emptyList() } } }
    val values = s.remembered("vals") { mutableStateMapOf<String, String>() }.value
    val added = s.remembered("added") { mutableStateListOf<Overlay>() }.value
    var tab by s.remembered("tab") { 0 }
    var flatten by s.remembered("flat") { false }
    LaunchedEffect(fields) { fields?.forEach { if (it.name !in values) values[it.name] = it.value } }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Fill fields (${fields?.size ?: 0})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Add fields (${added.size})") })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                val list = fields
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() -> Column(Modifier.padding(24.dp)) { InfoCard("No fillable AcroForm fields were found. Use \"Add fields\" to place new text boxes and checkboxes.") }
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list) { fl ->
                            val v = values[fl.name] ?: ""
                            when (fl.type) {
                                "checkbox" -> SwitchRow(fl.name, v == "true") { values[fl.name] = if (it) "true" else "false" }
                                "radio", "dropdown", "list" -> Column {
                                    Text(fl.name, style = MaterialTheme.typography.labelLarge)
                                    Choice(fl.options.ifEmpty { listOf(v) }, v, { it.ifEmpty { "—" } }) { values[fl.name] = it }
                                }
                                else -> Field(v, { values[fl.name] = it }, fl.name)
                            }
                        }
                        item { SwitchRow("Flatten form", flatten, "Make filled values permanent, non-editable") { flatten = it } }
                    }
                }
            } else {
                AddFieldsEditor(f, added)
            }
        }
        ActionBar("Save form", icon = Icons.Filled.Save) {
            val vals = values.toMap(); val newF = added.filterIsInstance<Overlay.Field>(); val fl = flatten
            s.run(ctx) { c -> ToolResult(listOf(EditEngine.fillForm(f, vals, newF, fl, File(s.workDir, "${f.baseName()}_form.pdf"), c)), "Filled ${vals.size} fields, added ${newF.size}") }
        }
    }
}

@Composable
private fun AddFieldsEditor(f: File, added: SnapshotStateList<Overlay>) {
    val pages = rememberPages(f)
    var index by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(EditMode.FIELD_TEXT) }
    val selected = remember { mutableStateOf<Int?>(null) }
    var naming by remember { mutableStateOf<Overlay.Field?>(null) }
    val pageW by produceState(595f, pages, index, pages.count) { if (pages.count > index) value = pages.size(index).first }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == EditMode.FIELD_TEXT, { mode = EditMode.FIELD_TEXT }, { Text("Text field") }, leadingIcon = { Icon(Icons.Filled.ShortText, null) })
            FilterChip(mode == EditMode.FIELD_CHECK, { mode = EditMode.FIELD_CHECK }, { Text("Checkbox") }, leadingIcon = { Icon(Icons.Filled.CheckBox, null) })
            FilterChip(mode == EditMode.SELECT, { mode = EditMode.SELECT }, { Text("Move") }, leadingIcon = { Icon(Icons.Filled.PanTool, null) })
        }
        Text("Drag on the page to draw a field", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        PageCanvas(pages, index, Modifier.weight(1f).padding(12.dp)) { sz ->
            OverlayLayer(sz, index, pageW, added, mode, 0xFF2563EB.toInt(), 2f, selected, onFieldDrawn = { naming = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected.value?.let { if (it in added.indices) added.removeAt(it) }; selected.value = null }, enabled = selected.value != null) { Icon(Icons.Filled.Delete, "Delete") }
            Box(Modifier.weight(1f)) { PageNavigator(index, pages.count) { index = it } }
        }
    }
    naming?.let { fld ->
        var name by remember { mutableStateOf(if (fld.kind == Overlay.FieldKind.TEXT) "text_${added.size + 1}" else "check_${added.size + 1}") }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text("Field name") }, text = { Field(name, { name = it }, "Name") },
            confirmButton = { TextButton(onClick = { added.add(fld.copy(name = name.ifBlank { "field" })); naming = null }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } })
    }
}
