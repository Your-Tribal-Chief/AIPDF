package com.allpdf.pro.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.Env
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.HistoryItem
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.Store
import com.allpdf.pro.engine.SecurityEngine
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

enum class Phase { PICK, READY, RUNNING, DONE, ERROR }

data class ToolResult(
    val files: List<File> = emptyList(),
    val summary: String = "",
    val details: List<String> = emptyList(),
    val text: String? = null,
    val beforeAfter: Pair<Long, Long>? = null,
    val compare: SecurityEngine.CompareResult? = null,
    val publish: Boolean = true,
)

/** State + background work for one open tool. Lives in the navigation stack so it survives navigation. */
class ToolSession(val tool: Tool, initial: List<File> = emptyList()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val inputs = mutableStateListOf<File>().apply { addAll(initial) }
    var phase by mutableStateOf(if (initial.isEmpty() && tool.input != Input.NONE && tool.input != Input.HTML) Phase.PICK else Phase.READY)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf("")
    var result by mutableStateOf<ToolResult?>(null)
    var published by mutableStateOf<List<HistoryItem>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
    var passwordFor by mutableStateOf<File?>(null)
    var passwordError by mutableStateOf<String?>(null)
    val workDir: File = Env.newWorkDir(tool.name.lowercase())
    private var job: Job? = null
    private var ctl: Ctl? = null
    private val pendingLocked = ArrayDeque<File>()
    private val states = HashMap<String, MutableState<Any?>>()
    /** Bumps whenever inputs change, so panels can reset derived state. */
    var inputVersion by mutableStateOf(0)

    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, init: () -> T): MutableState<T> =
        states.getOrPut(key) { mutableStateOf(init()) as MutableState<Any?> } as MutableState<T>

    fun clearStates(prefix: String = "") { states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it) } }

    private val decrypts: Boolean get() = tool != Tool.UNLOCK

    fun addUris(ctx: Context, uris: List<Uri>, replace: Boolean = false) {
        if (uris.isEmpty()) return
        importing = true
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.map { u ->
                        try { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        val f = Files.importUri(ctx, u)
                        Store.addRecent(u.toString(), f.name)
                        f
                    }
                }
                if (replace) inputs.clear()
                for (f in files) addFile(f)
            } catch (e: Exception) {
                error = "Couldn't open file: ${e.message}"
            } finally { importing = false }
        }
    }

    suspend fun addFile(f: File) {
        if (f.extension.equals("pdf", true) && decrypts) {
            val state = withContext(Dispatchers.IO) {
                try {
                    Pdf.load(f).use { d -> if (d.isEncrypted) 1 else 0 }
                } catch (e: InvalidPasswordException) { 2 } catch (e: Exception) { if (tool == Tool.REPAIR) 0 else 3 }
            }
            when (state) {
                1 -> { inputs.add(withContext(Dispatchers.IO) { Pdf.decryptedCopy(f, "", workDir) }) }
                2 -> { pendingLocked.addLast(f); if (passwordFor == null) passwordFor = pendingLocked.removeFirst() }
                3 -> { error = "\"${f.name}\" isn't a readable PDF. Try Repair PDF."; return }
                else -> inputs.add(f)
            }
        } else inputs.add(f)
        if (inputs.isNotEmpty() && phase == Phase.PICK) phase = Phase.READY
        inputVersion++
    }

    fun submitPassword(pw: String) {
        val f = passwordFor ?: return
        scope.launch {
            try {
                val out = withContext(Dispatchers.IO) { Pdf.decryptedCopy(f, pw, workDir) }
                inputs.add(out); passwordError = null
                passwordFor = pendingLocked.removeFirstOrNull()
                if (phase == Phase.PICK) phase = Phase.READY
                inputVersion++
            } catch (e: InvalidPasswordException) {
                passwordError = "Incorrect password"
            } catch (e: Exception) {
                passwordError = e.message ?: "Couldn't open file"
            }
        }
    }

    fun skipPassword() { passwordError = null; passwordFor = pendingLocked.removeFirstOrNull() }

    fun removeInput(i: Int) { if (i in inputs.indices) { inputs.removeAt(i); inputVersion++ }; if (inputs.isEmpty() && tool.input != Input.NONE && tool.input != Input.HTML) phase = Phase.PICK }

    fun move(from: Int, to: Int) {
        if (from == to || from !in inputs.indices || to !in inputs.indices) return
        val f = inputs.removeAt(from); inputs.add(to, f); inputVersion++
    }

    fun run(ctx: Context, block: suspend (Ctl) -> ToolResult) {
        if (phase == Phase.RUNNING) return
        phase = Phase.RUNNING; progress = 0f; message = "Starting…"; error = null
        job = scope.launch {
            val c = Ctl(null, { p, m -> scope.launch(Dispatchers.Main) { progress = p; if (m.isNotEmpty()) message = m } })
            ctl = c
            try {
                val r = withContext(Dispatchers.Default) { block(c) }
                published = if (r.publish && r.files.isNotEmpty()) withContext(Dispatchers.IO) { Store.publish(ctx, r.files, tool.title) } else emptyList()
                result = r
                phase = Phase.DONE
            } catch (e: CancellationException) {
                phase = Phase.READY; message = ""
            } catch (e: OutOfMemoryError) {
                error = "Not enough memory for this file. Try a lower quality/DPI setting."
                phase = Phase.ERROR
            } catch (e: Throwable) {
                error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                phase = Phase.ERROR
            }
        }
    }

    fun cancel() { ctl?.cancel(); job?.cancel(); phase = Phase.READY }

    fun backToOptions() { result = null; error = null; phase = if (inputs.isEmpty() && tool.input != Input.NONE && tool.input != Input.HTML) Phase.PICK else Phase.READY }

    fun dispose() { ctl?.cancel(); scope.cancel(); try { workDir.deleteRecursively() } catch (_: Exception) {} }
}

@Composable
fun <T> ToolSession.remembered(key: String, init: () -> T): MutableState<T> = remember(this, key) { state(key, init) }
