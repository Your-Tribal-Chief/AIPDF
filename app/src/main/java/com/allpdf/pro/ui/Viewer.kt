package com.allpdf.pro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import kotlinx.coroutines.launch
import java.io.File

private val invert = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(file: File, nav: Nav) {
    val ctx = LocalContext.current
    val pages = rememberPages(file)
    var scale by remember { mutableFloatStateOf(1f) }
    val darkTheme = isDark()
    var night by remember { mutableStateOf(darkTheme) }
    var toolsOpen by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("Page ${list.firstVisibleItemIndex + 1} of ${pages.count}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { jump = true })
                }
            },
            navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { night = !night }) { Icon(if (night) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Night mode") }
                IconButton(onClick = { Files.share(ctx, listOf(file)) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { toolsOpen = true }) { Icon(Icons.Filled.Build, "Tools") }
            },
        )
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().background(if (night) Color(0xFF0B0B0E) else MaterialTheme.colorScheme.surfaceVariant)) {
            val density = LocalDensity.current
            val viewportW = constraints.maxWidth
            val hScroll = rememberScrollState()
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) {
                                        val old = scale
                                        scale = (scale * z).coerceIn(1f, 5f)
                                        val ratio = scale / old
                                        scope.launch { hScroll.scrollTo(((hScroll.value + viewportW / 2f) * ratio - viewportW / 2f).toInt().coerceAtLeast(0)) }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .horizontalScroll(hScroll, enabled = scale > 1.01f),
            ) {
                LazyColumn(state = list, modifier = Modifier.width(with(density) { (viewportW * scale).toDp() }).fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pages.count) { i -> ViewerPage(pages, i, (viewportW * scale).toInt(), night) }
                }
            }
            if (scale > 1.01f) SmallFloatingActionButton(onClick = { scale = 1f }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.ZoomOutMap, "Reset zoom") }
            if (pages.count == 0) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    if (toolsOpen) ModalBottomSheet(onDismissRequest = { toolsOpen = false }) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(Tool.entries.filter { it.input in setOf(Input.PDF, Input.PDFS, Input.PDF_OR_IMAGES, Input.TWO_PDFS) && it != Tool.VIEWER }) { t ->
                ListItem({ Text(t.title) }, supportingContent = { Text(t.desc) }, leadingContent = { ToolIcon(t, 34) }, modifier = Modifier.clickable { toolsOpen = false; nav.openTool(t, listOf(file)) })
            }
        }
    }
    if (jump) {
        var v by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { jump = false }, title = { Text("Go to page") }, text = { Field(v, { v = it.filter(Char::isDigit) }, "1–${pages.count}", number = true) },
            confirmButton = { TextButton(onClick = { v.toIntOrNull()?.let { p -> scope.launch { list.scrollToItem((p - 1).coerceIn(0, maxOf(0, pages.count - 1))) } }; jump = false }) { Text("Go") } })
    }
}

@Composable
private fun ViewerPage(pages: PdfPages, i: Int, widthPx: Int, night: Boolean) {
    val density = LocalDensity.current
    var aspect by remember(pages, i) { mutableFloatStateOf(0.707f) }
    LaunchedEffect(pages, i) { val (w, h) = pages.size(i); if (w > 0 && h > 0) aspect = w / h }
    val horizontalPad = with(density) { 12.dp.toPx() }
    val w = (widthPx - 2 * horizontalPad).coerceAtLeast(50f)
    val renderW = (w.coerceAtMost(2400f)).toInt()
    // Coarse buckets so zooming doesn't re-render every frame.
    val bucket = ((renderW + 199) / 200) * 200
    val bmp by produceState<Bitmap?>(null, pages, i, bucket) { value = pages.bitmap(i, bucket) }
    Box(Modifier.padding(horizontal = 12.dp).width(with(density) { w.toDp() }).aspectRatio(aspect).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color.White)) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = if (night) invert else null)
        else CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
    }
}
