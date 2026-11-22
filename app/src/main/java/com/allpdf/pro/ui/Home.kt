package com.allpdf.pro.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allpdf.pro.BuildConfig
import com.allpdf.pro.core.Env
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.HistoryItem
import com.allpdf.pro.core.Store
import com.allpdf.pro.core.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Apps, null) }, label = { Text("Tools") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Folder, null) }, label = { Text("Files") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) { 0 -> ToolsTab(nav); 1 -> FilesTab(nav); else -> SettingsTab() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsTab(nav: Nav) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<Cat?>(null) }
    val list = Tool.entries.filter { t ->
        (cat == null || t.cat == cat) &&
            (query.isBlank() || t.title.contains(query, true) || t.desc.contains(query, true) || t.cat.label.contains(query, true))
    }
    val favs = Store.favorites.mapNotNull { id -> Tool.entries.firstOrNull { it.name == id } }
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(BrandRed, Color(0xFFB91C1C)))).padding(20.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PictureAsPdf, null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("AllPDF Pro", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Text("35 PDF tools • 100% offline • nothing leaves your phone", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search tools") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } }, singleLine = true, shape = RoundedCornerShape(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(cat == null, { cat = null }, { Text("All") })
                    Cat.entries.forEach { c -> FilterChip(cat == c, { cat = if (cat == c) null else c }, { Text(c.label) }) }
                }
                if (favs.isNotEmpty() && query.isBlank() && cat == null) {
                    Text("Favorites", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        favs.forEach { t -> AssistChip(onClick = { nav.openTool(t) }, label = { Text(t.title) }, leadingIcon = { Icon(t.icon, null, tint = t.color) }) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        items(list, key = { it.name }) { t -> ToolCard(t, t.name in Store.favorites, { nav.openTool(t) }, { Store.toggleFavorite(t.name) }) }
        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No tools match \"$query\"", Modifier.padding(24.dp)) }
    }
}

@Composable
private fun ToolCard(t: Tool, fav: Boolean, onOpen: () -> Unit, onFav: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth().height(150.dp)) {
        Box {
            Column(Modifier.padding(14.dp)) {
                ToolIcon(t, 46)
                Spacer(Modifier.height(10.dp))
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFav, Modifier.align(Alignment.TopEnd)) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "Unpin" else "Pin to favorites", tint = if (fav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(t.color))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesTab(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<HistoryItem?>(null) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val f = withContext(Dispatchers.IO) { Files.importUri(ctx, uri) }
            Store.addRecent(uri.toString(), f.name); nav.openViewer(f)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { opener.launch(arrayOf("application/pdf")) }) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Open PDF") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Output history (${Store.history.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Recent (${Store.recents.size})") })
        }
        if (tab == 0) {
            if (Store.history.isEmpty()) Empty("Files you create appear here and in Downloads/AllPDF Pro")
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Store.history.toList(), key = { it.path + it.time }) { h ->
                    val f = File(h.path)
                    ListItem(
                        headlineContent = { Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${h.tool} • ${humanSize(h.size)} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.time))}") },
                        leadingContent = { Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { IconButton(onClick = { menuFor = h }) { Icon(Icons.Filled.MoreVert, "More") } },
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { if (f.extension.equals("pdf", true)) nav.openViewer(f) else Files.openExternal(ctx, f) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
        } else {
            if (Store.recents.isEmpty()) Empty("PDFs you open are listed here")
            else TextButton(onClick = { Store.clearRecents() }, Modifier.padding(horizontal = 8.dp)) { Text("Clear list") }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Store.recents.toList(), key = { it.uri }) { r ->
                    ListItem(headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.time))) },
                        leadingContent = { Icon(Icons.Filled.History, null) },
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                            scope.launch {
                                try {
                                    val f = withContext(Dispatchers.IO) { Files.importUri(ctx, Uri.parse(r.uri)) }
                                    if (f.extension.equals("pdf", true)) nav.openViewer(f) else Files.openExternal(ctx, f)
                                } catch (e: Exception) { Toast.makeText(ctx, "File is no longer available", Toast.LENGTH_SHORT).show() }
                            }
                        }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
                }
            }
        }
    }
    menuFor?.let { h ->
        val f = File(h.path)
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Text(h.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Saved to ${h.location}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            ListItem({ Text("Share") }, leadingContent = { Icon(Icons.Filled.Share, null) }, modifier = Modifier.clickable { menuFor = null; Files.share(ctx, listOf(f)) })
            ListItem({ Text("Open with another app") }, leadingContent = { Icon(Icons.Filled.OpenInNew, null) }, modifier = Modifier.clickable { menuFor = null; Files.openExternal(ctx, f) })
            if (f.extension.equals("pdf", true)) {
                Text("Continue with a tool", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(Tool.entries.filter { it.input in setOf(Input.PDF, Input.PDFS, Input.PDF_OR_IMAGES) }) { t ->
                        ListItem({ Text(t.title) }, leadingContent = { ToolIcon(t, 30) }, modifier = Modifier.clickable { menuFor = null; nav.openTool(t, listOf(f)) })
                    }
                }
            }
            ListItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { menuFor = null; Store.removeHistory(h) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Empty(text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Inbox, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SettingsTab() {
    val ctx = LocalContext.current
    ScrollBody {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Section("Theme") { Choice(listOf(0, 1, 2), Store.darkMode.value, { listOf("System", "Light", "Dark")[it] }) { Store.setDark(it) } }
        Section("Storage") {
            InfoCard("Results are saved to Downloads/AllPDF Pro (Android 10+) via MediaStore, and kept in the app's Output history.")
            OutlinedButton(onClick = {
                Env.workRoot.deleteRecursively(); Env.inputsDir.deleteRecursively()
                Toast.makeText(ctx, "Temporary files cleared", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.CleaningServices, null); Spacer(Modifier.width(6.dp)); Text("Clear temporary files") }
        }
        Section("Privacy") {
            InfoCard("AllPDF Pro has no INTERNET permission. Every tool — OCR, conversion, compression, encryption and summarization — runs on your phone's CPU.", Icons.Filled.Shield)
        }
        Section("About") {
            Text("AllPDF Pro ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text("Built with PdfBox-Android (Apache 2.0), Apache POI (Apache 2.0) and ML Kit on-device text recognition.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
