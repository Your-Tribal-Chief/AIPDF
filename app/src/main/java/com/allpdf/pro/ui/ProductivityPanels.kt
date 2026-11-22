package com.allpdf.pro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Store
import com.allpdf.pro.engine.ProductivityEngine
import com.allpdf.pro.engine.Workflow
import com.allpdf.pro.engine.WorkflowStep
import org.json.JSONArray

object Workflows {
    val builtIn = listOf(
        Workflow("Merge, compress & secure", listOf(WorkflowStep("merge"), WorkflowStep("compress", mapOf("level" to "RECOMMENDED")),
            WorkflowStep("numbers", mapOf("format" to "2")), WorkflowStep("watermark", mapOf("text" to "CONFIDENTIAL", "opacity" to "0.2")), WorkflowStep("protect", mapOf("password" to "")))),
        Workflow("Archive scans", listOf(WorkflowStep("ocr"), WorkflowStep("compress", mapOf("level" to "RECOMMENDED")), WorkflowStep("pdfa"))),
        Workflow("Print-ready A4", listOf(WorkflowStep("merge"), WorkflowStep("resize", mapOf("size" to "A4")), WorkflowStep("numbers", mapOf("format" to "1")))),
    )
    fun saved(): List<Workflow> = try {
        val a = JSONArray(Store.getJson("workflows", "[]")); (0 until a.length()).map { Workflow.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
    fun save(list: List<Workflow>) { Store.putJson("workflows", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()) }
}

@Composable
fun WorkflowPanel(s: ToolSession) {
    val ctx = LocalContext.current
    var name by s.remembered("name") { "My workflow" }
    val steps = s.remembered("steps") { mutableStateListOf<WorkflowStep>().apply { addAll(Workflows.builtIn[0].steps) } }.value
    var saved by remember { mutableStateOf(Workflows.saved()) }
    var adding by remember { mutableStateOf(false) }
    val needsPw = steps.any { it.type == "protect" && it.params["password"].isNullOrEmpty() }
    OptionsLayout("Run ${steps.size} steps on ${s.inputs.size} file${if (s.inputs.size == 1) "" else "s"}", steps.isNotEmpty() && !needsPw && s.inputs.isNotEmpty(), Icons.Filled.PlayArrow, onRun = {
        val wf = Workflow(name.ifBlank { "Workflow" }, steps.toList()); val files = s.inputs.toList()
        s.run(ctx) { c -> val outs = ProductivityEngine.runWorkflow(files, wf, s.workDir, c); ToolResult(outs, "${wf.steps.size} steps: ${wf.steps.joinToString(" → ") { it.label() }}") }
    }) {
        Section("Presets") {
            (Workflows.builtIn + saved).forEach { w ->
                Surface(onClick = { name = w.name; steps.clear(); steps.addAll(w.steps) }, shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, null, tint = Cat.WORKFLOWS.color)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(w.name, fontWeight = FontWeight.SemiBold)
                            Text(w.steps.joinToString(" → ") { it.label() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (w in saved) IconButton(onClick = { saved = saved - w; Workflows.save(saved) }) { Icon(Icons.Filled.Delete, "Delete preset") }
                    }
                }
            }
        }
        Section("Steps") {
            steps.forEachIndexed { i, st ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${st.label()}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { if (i > 0) steps.add(i - 1, steps.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
                        }
                        StepParams(st) { steps[i] = it }
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add step") }
        }
        Section("Save as preset") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(name, { name = it }, "Workflow name", Modifier.weight(1f))
                IconButton(onClick = {
                    val wf = Workflow(name.ifBlank { "Workflow" }, steps.map { if (it.type == "protect") it.copy(params = it.params - "password") else it })
                    saved = saved.filter { it.name != wf.name } + wf; Workflows.save(saved)
                }) { Icon(Icons.Filled.Save, "Save preset") }
            }
            Text("Passwords are never saved in presets.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("Input files") { InputList(s, true) }
        if (needsPw) InfoCard("Enter a password for the Protect step.")
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add step") }, text = {
        Column { WorkflowStep.TYPES.forEach { t -> TextButton(onClick = { steps.add(WorkflowStep(t)); adding = false }, Modifier.fillMaxWidth()) { Text(WorkflowStep(t).label(), Modifier.fillMaxWidth()) } } }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("Close") } })
}

@Composable
private fun StepParams(st: WorkflowStep, onChange: (WorkflowStep) -> Unit) {
    fun set(k: String, v: String) = onChange(st.copy(params = st.params + (k to v)))
    when (st.type) {
        "compress" -> Choice(listOf("EXTREME", "RECOMMENDED", "LESS"), st.params["level"] ?: "RECOMMENDED", { it.lowercase().replaceFirstChar(Char::uppercase) }) { set("level", it) }
        "rotate" -> Choice(listOf("90", "180", "270"), st.params["deg"] ?: "90", { "$it°" }) { set("deg", it) }
        "resize" -> Choice(listOf("A3", "A4", "A5", "Letter", "Legal"), st.params["size"] ?: "A4", { it }) { set("size", it) }
        "numbers" -> Choice(listOf("0", "1", "2"), st.params["format"] ?: "0", { listOf("1", "1 / N", "Page 1 of N")[it.toInt()] }) { set("format", it) }
        "watermark" -> Field(st.params["text"] ?: "CONFIDENTIAL", { set("text", it) }, "Watermark text")
        "protect" -> Field(st.params["password"] ?: "", { set("password", it) }, "Password", password = true)
        else -> {}
    }
}

@Composable
fun SummarizePanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var points by s.remembered("pts") { 8f }
    OptionsLayout("Summarize", icon = Icons.Filled.Summarize, onRun = {
        val n = points.toInt()
        s.run(ctx) { c ->
            val r = ProductivityEngine.summarize(f, n, s.workDir, c)
            ToolResult(listOf(r.file), if (r.usedOcr) "Summarized via on-device OCR" else "Extractive summary", text = r.markdown)
        }
    }) {
        FileRow(f)
        SliderRow("Key points", points, 3f..20f) { points = it.toInt().toFloat() }
        InfoCard("100% on-device: sentences are ranked with TF-IDF and TextRank centrality, then de-duplicated. No AI service is contacted.")
    }
}
