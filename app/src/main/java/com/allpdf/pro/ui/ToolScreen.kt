package com.allpdf.pro.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.humanSize
import com.allpdf.pro.text.TextDiff
import java.io.File

/** Shared file-picker hook for a session. */
class Picker(val launchMulti: () -> Unit, val launchSingle: () -> Unit) {
    fun launch(multi: Boolean) = if (multi) launchMulti() else launchSingle()
}

@Composable
fun rememberPicker(s: ToolSession, mimes: Array<String> = s.tool.mimes, replace: Boolean = false): Picker {
    val ctx = LocalContext.current
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> s.addUris(ctx, uris, replace) }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) s.addUris(ctx, listOf(uri), replace) }
    return remember(s) { Picker({ multi.launch(mimes) }, { single.launch(mimes) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreen(s: ToolSession, nav: Nav) {
    val picker = rememberPicker(s, replace = !s.tool.multi)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolIcon(s.tool, 32); Spacer(Modifier.width(10.dp))
                        Text(s.tool.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    if (s.phase == Phase.READY && s.tool.input != Input.NONE && s.tool.input != Input.HTML) {
                        IconButton(onClick = { picker.launch(s.tool.multi) }) { Icon(if (s.tool.multi) Icons.Filled.Add else Icons.Filled.SwapHoriz, if (s.tool.multi) "Add files" else "Change file") }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s.phase) {
                Phase.PICK -> PickCard(s.tool, s.importing) { picker.launch(s.tool.multi) }
                Phase.READY -> ToolPanel(s, nav, picker)
                Phase.RUNNING -> ProgressPanel(s.tool, s.progress, s.message) { s.cancel() }
                Phase.DONE -> ResultPanel(s, nav)
                Phase.ERROR -> ErrorPanel(s)
            }
            if (s.importing && s.phase != Phase.PICK) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    s.passwordFor?.let { f -> PasswordDialog(f.name, s.passwordError, { s.submitPassword(it) }, { s.skipPassword() }) }
    val err = s.error
    if (err != null && s.phase != Phase.ERROR) {
        val ctx = LocalContext.current
        LaunchedEffect(err) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); s.error = null }
    }
}

@Composable
private fun ErrorPanel(s: ToolSession) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
        Text(s.error ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Back to options", icon = Icons.Filled.Tune) { s.backToOptions() }
    }
}

/** Primary action bar pinned at the bottom of options panels. */
@Composable
fun ActionBar(text: String, enabled: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { PrimaryButton(text, enabled, icon, onClick = onClick) }
    }
}

/** Options layout: scrollable content + pinned action button. */
@Composable
fun OptionsLayout(button: String, enabled: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onRun: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), content = content)
        ActionBar(button, enabled, icon, onRun)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultPanel(s: ToolSession, nav: Nav) {
    val ctx = LocalContext.current
    val r = s.result ?: return
    val files: List<File> = if (r.publish && s.published.isNotEmpty()) s.published.map { File(it.path) } else r.files
    var continueFor by remember { mutableStateOf<File?>(null) }
    val clipboard = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFF16A34A).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF16A34A), modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Done!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (r.summary.isNotEmpty()) Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                s.published.firstOrNull()?.let { Text("Saved to ${it.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        r.beforeAfter?.let { (b, a) ->
            item {
                val saved = if (b > 0) (100 - a * 100 / b).coerceAtLeast(0) else 0
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row { Column(Modifier.weight(1f)) { Text("Before", style = MaterialTheme.typography.labelMedium); Text(humanSize(b), style = MaterialTheme.typography.titleLarge) }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text("After", style = MaterialTheme.typography.labelMedium); Text(humanSize(a), style = MaterialTheme.typography.titleLarge, color = Color(0xFF16A34A)) } }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { if (b > 0) a.toFloat() / b else 1f }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = Color(0xFF16A34A))
                        Text(if (saved > 0) "$saved% smaller" else "Already well optimized — no further savings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (r.details.isNotEmpty()) item { r.details.forEach { InfoCard(it) } }
        r.compare?.let { c -> item { CompareSummary(c) } }
        items(files) { f ->
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(humanSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (f.extension.equals("pdf", true)) nav.openViewer(f) else Files.openExternal(ctx, f) }) { Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open") }
                        OutlinedButton(onClick = { Files.share(ctx, listOf(f)) }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                        if (f.extension.lowercase() in setOf("jpg", "jpeg", "png")) OutlinedButton(onClick = {
                            Toast.makeText(ctx, if (Files.exportImageToGallery(ctx, f)) "Saved to gallery" else "Couldn't save", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.Collections, null, Modifier.size(18.dp)) }
                        if (f.extension.equals("pdf", true)) OutlinedButton(onClick = { continueFor = f }) { Icon(Icons.Filled.ArrowForward, null, Modifier.size(18.dp)) }
                    }
                }
            }
        }
        r.text?.let { t ->
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(t)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                        }
                        SelectionContainer { MarkdownText(t.take(20000)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (files.size > 1) OutlinedButton(onClick = { Files.share(ctx, files) }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share all (${files.size})") }
                if (files.size == 1 && files[0].extension.equals("pdf", true)) Button(onClick = { continueFor = files[0] }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.ArrowForward, null); Spacer(Modifier.width(8.dp)); Text("Continue to another tool")
                }
                TextButton(onClick = { s.backToOptions() }, Modifier.fillMaxWidth()) { Text("Run again with other options") }
            }
        }
    }
    continueFor?.let { f ->
        ModalBottomSheet(onDismissRequest = { continueFor = null }) {
            Text("Continue with ${f.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(Tool.entries.filter { it.input in setOf(Input.PDF, Input.PDFS, Input.PDF_OR_IMAGES, Input.TWO_PDFS) && it != s.tool }) { t ->
                    ListItem(headlineContent = { Text(t.title) }, supportingContent = { Text(t.desc) }, leadingContent = { ToolIcon(t, 36) },
                        modifier = Modifier.clickable { continueFor = null; nav.openTool(t, listOf(f)) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Lightweight markdown display: headings, bullets, bold. */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().forEach { line ->
            val t = line.trimEnd()
            when {
                t.startsWith("# ") -> Text(t.removePrefix("# "), style = MaterialTheme.typography.titleLarge)
                t.startsWith("## ") -> Text(t.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                t.startsWith("### ") -> Text(t.removePrefix("### "), style = MaterialTheme.typography.titleSmall)
                t.startsWith("- ") -> Row { Text("•  ", fontWeight = FontWeight.Bold); Text(bold(t.removePrefix("- ")), style = MaterialTheme.typography.bodyMedium) }
                t.startsWith("|") -> Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                t.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(bold(t), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun bold(s: String) = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p.replace("_", "")) }
}

@Composable
fun CompareSummary(c: com.allpdf.pro.engine.SecurityEngine.CompareResult) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Differences", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("+${c.stats.added} added") })
                AssistChip(onClick = {}, label = { Text("−${c.stats.removed} removed") })
                AssistChip(onClick = {}, label = { Text("~${c.stats.modified} changed") })
            }
            Text("Pages: ${c.pagesA} vs ${c.pagesB}. Visual changes on ${if (c.changedPages.isEmpty()) "no pages" else c.changedPages.joinToString { "p${it + 1}" }}.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            val shown = c.lines.filter { it.kind != TextDiff.Kind.SAME }.take(250)
            if (shown.isEmpty()) Text("Text is identical.", style = MaterialTheme.typography.bodyMedium)
            shown.forEach { l ->
                val (bg, txt) = when (l.kind) {
                    TextDiff.Kind.ADDED -> Color(0x3316A34A) to AnnotatedString("+ " + l.right)
                    TextDiff.Kind.REMOVED -> Color(0x33DC2626) to AnnotatedString("− " + l.left)
                    else -> Color(0x33F59E0B) to buildAnnotatedString {
                        append("~ ")
                        l.leftSegs?.forEach { sg -> if (sg.changed) withStyle(SpanStyle(color = Color(0xFFDC2626), textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(sg.text) } else append(sg.text) }
                        append("  →  ")
                        l.rightSegs?.forEach { sg -> if (sg.changed) withStyle(SpanStyle(color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)) { append(sg.text) } else append(sg.text) }
                    }
                }
                Text(txt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(6.dp))
            }
        }
    }
}
