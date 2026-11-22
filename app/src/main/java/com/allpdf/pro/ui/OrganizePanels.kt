package com.allpdf.pro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.allpdf.pro.engine.OrganizeEngine
import com.allpdf.pro.engine.PageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ToolPanel(s: ToolSession, nav: Nav, picker: Picker) {
    when (s.tool) {
        Tool.MERGE -> MergePanel(s)
        Tool.SPLIT -> SplitPanel(s)
        Tool.REMOVE -> RemovePanel(s)
        Tool.EXTRACT -> ExtractPanel(s)
        Tool.ORGANIZE -> OrganizePanel(s)
        Tool.SCAN -> ScanPanel(s)
        Tool.COMPRESS -> CompressPanel(s)
        Tool.REPAIR -> RepairPanel(s)
        Tool.OCR -> OcrPanel(s)
        Tool.IMG_TO_PDF -> ImagesPanel(s)
        Tool.WORD_TO_PDF, Tool.PPT_TO_PDF, Tool.EXCEL_TO_PDF -> OfficePanel(s)
        Tool.HTML_TO_PDF -> HtmlPanel(s)
        Tool.PDF_TO_IMG -> PdfToImagesPanel(s)
        Tool.PDF_TO_WORD -> PdfToWordPanel(s)
        Tool.PDF_TO_PPT -> PdfToPptPanel(s)
        Tool.PDF_TO_EXCEL -> PdfToExcelPanel(s)
        Tool.PDF_TO_PDFA -> PdfAPanel(s)
        Tool.PDF_TO_MD -> MarkdownPanel(s)
        Tool.RESIZE -> ResizePanel(s)
        Tool.CROP -> CropPanel(s)
        Tool.ROTATE -> RotatePanel(s)
        Tool.NUMBERS -> NumbersPanel(s)
        Tool.WATERMARK -> WatermarkPanel(s)
        Tool.EDIT -> EditPanel(s)
        Tool.FORMS -> FormsPanel(s)
        Tool.PROTECT -> ProtectPanel(s)
        Tool.UNLOCK -> UnlockPanel(s)
        Tool.SIGN -> SignPanel(s)
        Tool.REDACT -> RedactPanel(s)
        Tool.COMPARE -> ComparePanel(s, picker)
        Tool.WORKFLOWS -> WorkflowPanel(s)
        Tool.SUMMARIZE -> SummarizePanel(s)
        Tool.VIEWER -> { LaunchedEffect(Unit) { s.inputs.firstOrNull()?.let { nav.replaceWithViewer(it) } } }
    }
}

/** Reorderable list of the session's input files. */
@Composable
fun InputList(s: ToolSession, reorder: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.inputs.forEachIndexed { i, f ->
            FileRow(f, if (reorder) i else null, onRemove = { s.removeInput(i) },
                onUp = if (reorder && i > 0) ({ s.move(i, i - 1) }) else null,
                onDown = if (reorder && i < s.inputs.size - 1) ({ s.move(i, i + 1) }) else null)
        }
    }
}

@Composable
fun MergePanel(s: ToolSession) {
    val ctx = LocalContext.current
    OptionsLayout("Merge ${s.inputs.size} PDFs", s.inputs.size >= 2, Icons.Filled.MergeType, onRun = {
        val files = s.inputs.toList()
        s.run(ctx) { c ->
            val out = File(s.workDir, "${files.first().baseName()}_merged.pdf")
            OrganizeEngine.merge(files, out, c)
            ToolResult(listOf(out), "Merged ${files.size} files")
        }
    }) {
        Section("Order (use arrows to reorder)") { InputList(s, true) }
        if (s.inputs.size < 2) InfoCard("Add at least one more PDF with the + button.")
    }
}

@Composable
fun SplitPanel(s: ToolSession) {
    val ctx = LocalContext.current
    var mode by s.remembered("mode") { "ranges" }
    var ranges by s.remembered("ranges") { "1-3, 4-" }
    var every by s.remembered("every") { 2f }
    var mergeRanges by s.remembered("mergeRanges") { false }
    val f = s.inputs.first()
    val count by produceState(0, f) { value = withContext(Dispatchers.IO) { try { Pdf.pageCount(f) } catch (_: Exception) { 0 } } }
    OptionsLayout("Split PDF", onRun = {
        s.run(ctx) { c ->
            val outs = OrganizeEngine.split(f, mode, ranges, every.toInt(), mergeRanges, s.workDir, c)
            val zipIt = outs.size > 4
            val files = if (zipIt) listOf(Files.zip(outs, File(s.workDir, "${f.baseName()}_split.zip")).also { outs.forEach { it.delete() } }) else outs
            ToolResult(files, "Created ${outs.size} PDF${if (outs.size == 1) "" else "s"}${if (zipIt) " (zipped)" else ""}")
        }
    }) {
        FileRow(f)
        Text("$count pages", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Section("Split mode") {
            Choice(listOf("ranges", "every", "burst"), mode, { when (it) { "ranges" -> "Custom ranges"; "every" -> "Fixed interval"; else -> "Every page" } }) { mode = it }
        }
        when (mode) {
            "ranges" -> {
                Field(ranges, { ranges = it }, "Page ranges, e.g. 1-3, 5, 8-")
                SwitchRow("Merge all ranges into one PDF", mergeRanges) { mergeRanges = it }
            }
            "every" -> SliderRow("Pages per file", every, 1f..maxOf(2f, count.toFloat()), steps = 0) { every = it.toInt().toFloat() }
            else -> InfoCard("Each of the $count pages becomes its own PDF.")
        }
    }
}

@Composable
fun RemovePanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val pages = rememberPages(f)
    var sel by s.remembered("sel") { setOf<Int>() }
    Column(Modifier.fillMaxSize()) {
        SelectGrid(pages, sel, danger = true, onToggle = { sel = if (it in sel) sel - it else sel + it }, modifier = Modifier.weight(1f), header = {
            Text("Tap pages to remove (${sel.size} selected)", style = MaterialTheme.typography.titleSmall)
        })
        ActionBar("Remove ${sel.size} page${if (sel.size == 1) "" else "s"}", sel.isNotEmpty() && sel.size < pages.count, Icons.Filled.DeleteSweep) {
            val remove = sel
            s.run(ctx) { c -> val out = File(s.workDir, "${f.baseName()}_removed.pdf"); OrganizeEngine.removePages(f, remove, out, c); ToolResult(listOf(out), "Removed ${remove.size} pages") }
        }
    }
}

@Composable
fun ExtractPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    val pages = rememberPages(f)
    var sel by s.remembered("sel") { setOf<Int>() }
    var zip by s.remembered("zip") { false }
    Column(Modifier.fillMaxSize()) {
        SelectGrid(pages, sel, onToggle = { sel = if (it in sel) sel - it else sel + it }, modifier = Modifier.weight(1f), header = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select pages (${sel.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sel = if (sel.size == pages.count) emptySet() else (0 until pages.count).toSet() }) { Text(if (sel.size == pages.count) "Clear" else "Select all") }
                }
                Choice(listOf(false, true), zip, { if (it) "ZIP of single-page PDFs" else "One combined PDF" }) { zip = it }
            }
        })
        ActionBar("Extract ${sel.size} page${if (sel.size == 1) "" else "s"}", sel.isNotEmpty(), Icons.Filled.FileCopy) {
            val list = sel.sorted(); val z = zip
            s.run(ctx) { c -> ToolResult(OrganizeEngine.extractPages(f, list, z, s.workDir, c), "Extracted ${list.size} pages") }
        }
    }
}

private data class OrgItem(val id: Long, val ref: PageRef)

@Composable
fun OrganizePanel(s: ToolSession) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = s.remembered("sources") { mutableStateListOf(s.inputs.first()) }.value
    val items = s.remembered("items") { mutableStateListOf<OrgItem>() }.value
    var nextId by s.remembered("nextId") { 1L }
    var loaded by s.remembered("loaded") { false }
    val pageSets = sources.map { rememberPages(it) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val n = withContext(Dispatchers.IO) { Pdf.pageCount(sources[0]) }
            repeat(n) { items.add(OrgItem(nextId++, PageRef.Source(0, it))) }
            loaded = true
        }
    }
    val insertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { Files.importUri(ctx, uri) }
            val n = try { withContext(Dispatchers.IO) { Pdf.pageCount(f) } } catch (e: Exception) { 0 }
            if (n > 0) {
                sources.add(f)
                val idx = sources.size - 1
                repeat(n) { items.add(OrgItem(nextId++, PageRef.Source(idx, it))) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { items.add(OrgItem(nextId++, PageRef.Blank())) }, label = { Text("Blank page") }, leadingIcon = { Icon(Icons.Filled.NoteAdd, null) })
            AssistChip(onClick = { insertLauncher.launch(arrayOf("application/pdf")) }, label = { Text("Insert PDF") }, leadingIcon = { Icon(Icons.Filled.PostAdd, null) })
            AssistChip(onClick = { for (i in items.indices) items[i] = items[i].copy(ref = rotated(items[i].ref, 90)) }, label = { Text("Rotate all") }, leadingIcon = { Icon(Icons.Filled.RotateRight, null) })
        }
        Text("Long-press and drag to reorder • ${items.size} pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        ReorderGrid(items, key = { it.id }, onMove = { a, b -> items.add(b, items.removeAt(a)) }, modifier = Modifier.weight(1f)) { i, item, dragging ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.aspectRatio(0.72f).fillMaxWidth().shadow(if (dragging) 12.dp else 2.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .border(if (dragging) 3.dp else 1.dp, if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
                    when (val r = item.ref) {
                        is PageRef.Source -> PageThumb(pageSets.getOrNull(r.fileIndex) ?: pageSets[0], r.pageIndex, 260, Modifier.fillMaxSize(), r.rotation)
                        is PageRef.Blank -> Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Text("Blank", color = Color.Gray) }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { items[i] = item.copy(ref = rotated(item.ref, 90)) }, Modifier.size(34.dp)) { Icon(Icons.Filled.RotateRight, "Rotate", tint = Color.White) }
                        IconButton(onClick = { items.removeAt(i) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    }
                    if (item.ref is PageRef.Source && (item.ref as PageRef.Source).fileIndex > 0)
                        Text("+", color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary).padding(horizontal = 6.dp))
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 3.dp))
            }
        }
        ActionBar("Save organized PDF", items.isNotEmpty(), Icons.Filled.Save) {
            val refs = items.map { it.ref }; val srcs = sources.toList()
            s.run(ctx) { c ->
                val out = File(s.workDir, "${srcs[0].baseName()}_organized.pdf")
                OrganizeEngine.organize(srcs, refs, out, c)
                ToolResult(listOf(out), "${refs.size} pages")
            }
        }
    }
}

private fun rotated(r: PageRef, d: Int): PageRef = when (r) {
    is PageRef.Source -> r.copy(rotation = (r.rotation + d) % 360)
    is PageRef.Blank -> r.copy(rotation = (r.rotation + d) % 360)
}

@Composable
fun RotatePanel(s: ToolSession) {
    val ctx = LocalContext.current
    var deg by s.remembered("deg") { 90 }
    var sel by s.remembered("sel") { setOf<Int>() }
    val single = s.inputs.size == 1
    if (single) {
        val f = s.inputs.first()
        val pages = rememberPages(f)
        Column(Modifier.fillMaxSize()) {
            SelectGrid(pages, sel, onToggle = { sel = if (it in sel) sel - it else sel + it }, modifier = Modifier.weight(1f), header = {
                Column {
                    Choice(listOf(90, 180, 270), deg, { "$it°" }) { deg = it }
                    Text(if (sel.isEmpty()) "No pages selected → all pages rotate" else "${sel.size} selected pages rotate", style = MaterialTheme.typography.bodySmall)
                }
            })
            ActionBar("Rotate ${if (sel.isEmpty()) "all pages" else "${sel.size} pages"}", icon = Icons.Filled.RotateRight) {
                val d = deg; val p = sel.takeIf { it.isNotEmpty() }
                s.run(ctx) { c -> val out = File(s.workDir, "${f.baseName()}_rotated.pdf"); OrganizeEngine.rotate(f, d, p, out, c); ToolResult(listOf(out), "Rotated $d°") }
            }
        }
    } else {
        OptionsLayout("Rotate ${s.inputs.size} PDFs", icon = Icons.Filled.RotateRight, onRun = {
            val d = deg; val files = s.inputs.toList()
            s.run(ctx) { c ->
                val outs = files.mapIndexed { i, f -> File(s.workDir, "${f.baseName()}_rotated.pdf").also { OrganizeEngine.rotate(f, d, null, it, c.sub(i.toFloat() / files.size, (i + 1f) / files.size)) } }
                ToolResult(outs, "Rotated ${files.size} files by $d°")
            }
        }) {
            Section("Angle") { Choice(listOf(90, 180, 270), deg, { "$it°" }) { deg = it } }
            Section("Files (every page is rotated)") { InputList(s, false) }
        }
    }
}
