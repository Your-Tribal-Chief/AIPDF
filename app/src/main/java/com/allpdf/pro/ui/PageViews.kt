package com.allpdf.pro.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thread-safe, cached page bitmaps for UI previews. */
class PdfPages(val file: File) {
    private val mutex = Mutex()
    private var renderer: PageRenderer? = null
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt().coerceAtLeast(8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sizes = HashMap<Int, Pair<Float, Float>>()
    var count by mutableIntStateOf(0)
        private set

    private fun r(): PageRenderer = renderer ?: PageRenderer(file).also { renderer = it; count = it.pageCount }

    suspend fun load(): Int = withContext(Dispatchers.IO) { mutex.withLock { r().pageCount.also { count = it } } }

    suspend fun size(index: Int): Pair<Float, Float> = withContext(Dispatchers.IO) {
        mutex.withLock { sizes.getOrPut(index) { r().pageSize(index) } }
    }

    suspend fun bitmap(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$width"
        cache.get(key)?.let { return@withContext it }
        try {
            mutex.withLock { r().render(index, width.coerceIn(32, 2600), 8_000_000L).also { cache.put(key, it) } }
        } catch (e: Throwable) { null }
    }

    fun close() { try { renderer?.close() } catch (_: Exception) {}; renderer = null; cache.evictAll() }
}

@Composable
fun rememberPages(file: File): PdfPages {
    val pages = remember(file) { PdfPages(file) }
    LaunchedEffect(pages) { try { pages.load() } catch (_: Throwable) {} }
    DisposableEffect(pages) { onDispose { pages.close() } }
    return pages
}

@Composable
fun PageThumb(pages: PdfPages, index: Int, widthPx: Int, modifier: Modifier = Modifier, rotation: Int = 0) {
    val bmp by produceState<Bitmap?>(null, pages, index, widthPx) { value = pages.bitmap(index, widthPx) }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Image(b.asImageBitmap(), null, Modifier.fillMaxSize().rotate(rotation.toFloat()), contentScale = ContentScale.Fit)
    }
}

/** Grid of pages with tap-to-select. [danger] styles selection as removal. */
@Composable
fun SelectGrid(pages: PdfPages, selected: Set<Int>, danger: Boolean = false, onToggle: (Int) -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), modifier = modifier, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(pages.count) { i ->
            val sel = i in selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.aspectRatio(0.72f).fillMaxWidth().shadow(if (sel) 0.dp else 2.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .border(if (sel) 3.dp else 1.dp, if (sel) accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { onToggle(i) }) {
                    PageThumb(pages, i, 280, Modifier.fillMaxSize())
                    if (sel) {
                        Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.18f)))
                        Icon(if (danger) Icons.Filled.Delete else Icons.Filled.CheckCircle, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(accent).padding(3.dp))
                    }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Drag-to-reorder grid. Items are opaque; [content] renders each. Long-press then drag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> ReorderGrid(items: List<T>, key: (T) -> Any, onMove: (Int, Int) -> Unit, modifier: Modifier = Modifier, content: @Composable (Int, T, Boolean) -> Unit) {
    val state = rememberLazyGridState()
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val currentItems by rememberUpdatedState(items)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp), state = state, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { off ->
                    val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { off.x.toInt() in it.offset.x..(it.offset.x + it.size.width) && off.y.toInt() in it.offset.y..(it.offset.y + it.size.height) }
                    dragKey = hit?.key; pointer = off
                },
                onDragEnd = { dragKey = null }, onDragCancel = { dragKey = null },
                onDrag = { change, amount ->
                    change.consume()
                    pointer += amount
                    val k = dragKey ?: return@detectDragGesturesAfterLongPress
                    val from = currentItems.indexOfFirst { key(it) == k }
                    val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
                        pointer.x.toInt() in it.offset.x..(it.offset.x + it.size.width) && pointer.y.toInt() in it.offset.y..(it.offset.y + it.size.height)
                    }
                    val to = target?.let { t -> currentItems.indexOfFirst { key(it) == t.key } } ?: -1
                    if (from >= 0 && to >= 0 && from != to) onMove(from, to)
                    // auto-scroll near edges
                    val h = state.layoutInfo.viewportSize.height
                    if (pointer.y > h - 120) state.dispatchRawDelta(18f) else if (pointer.y < 120) state.dispatchRawDelta(-18f)
                },
            )
        },
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { i, it ->
            Box(Modifier.animateItem()) { content(i, it, key(it) == dragKey) }
        }
    }
}

/**
 * Displays one page fitted to the available width. [overlay] receives the on-screen page size in px,
 * so tools can map gestures to normalized (0..1) page coordinates.
 */
@Composable
fun PageCanvas(pages: PdfPages, index: Int, modifier: Modifier = Modifier, overlay: @Composable BoxScope.(IntSize) -> Unit) {
    var aspect by remember(pages, index) { mutableFloatStateOf(0.72f) }
    LaunchedEffect(pages, index, pages.count) {
        if (pages.count > index) { val (w, h) = pages.size(index); if (w > 0 && h > 0) aspect = w / h }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else maxW / aspect
        val w = minOf(maxW, maxH * aspect); val h = w / aspect
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).shadow(4.dp).background(Color.White).onSizeChanged { size = it }) {
            if (pages.count > index) PageThumb(pages, index, (w * 1.5f).toInt().coerceAtMost(2000), Modifier.fillMaxSize())
            if (size.width > 0) overlay(size)
        }
    }
}

@Composable
fun PageNavigator(index: Int, count: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onChange(index - 1) }, enabled = index > 0) { Icon(Icons.Filled.ChevronLeft, "Previous page") }
        Text("Page ${index + 1} of ${maxOf(count, 1)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onChange(index + 1) }, enabled = index < count - 1) { Icon(Icons.Filled.ChevronRight, "Next page") }
    }
}
