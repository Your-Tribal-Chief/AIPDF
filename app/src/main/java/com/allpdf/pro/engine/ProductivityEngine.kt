package com.allpdf.pro.engine

import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.PageRenderer
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.allpdf.pro.text.Summarizer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One step of a saved workflow. */
data class WorkflowStep(val type: String, val params: Map<String, String> = emptyMap()) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("params", JSONObject(params))
    fun label(): String = when (type) {
        "merge" -> "Merge"
        "compress" -> "Compress (${params["level"] ?: "RECOMMENDED"})"
        "numbers" -> "Page numbers"
        "watermark" -> "Watermark \"${params["text"] ?: ""}\""
        "protect" -> "Protect"
        "rotate" -> "Rotate ${params["deg"] ?: "90"}°"
        "resize" -> "Resize to ${params["size"] ?: "A4"}"
        "pdfa" -> "PDF/A"
        "ocr" -> "OCR"
        else -> type
    }
    companion object {
        fun fromJson(o: JSONObject): WorkflowStep {
            val p = o.optJSONObject("params") ?: JSONObject()
            val m = HashMap<String, String>()
            p.keys().forEach { m[it] = p.getString(it) }
            return WorkflowStep(o.getString("type"), m)
        }
        val TYPES = listOf("merge", "compress", "rotate", "resize", "numbers", "watermark", "ocr", "pdfa", "protect")
    }
}

data class Workflow(val name: String, val steps: List<WorkflowStep>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })
    companion object {
        fun fromJson(o: JSONObject) = Workflow(o.getString("name"),
            (0 until o.getJSONArray("steps").length()).map { WorkflowStep.fromJson(o.getJSONArray("steps").getJSONObject(it)) })
    }
}

object ProductivityEngine {

    val SIZES = mapOf("A3" to (842f to 1191f), "A4" to (595f to 842f), "A5" to (420f to 595f), "Letter" to (612f to 792f), "Legal" to (612f to 1008f))

    /** Runs steps in order. Merge collapses all current files into one; every other step maps file -> file. */
    fun runWorkflow(inputs: List<File>, wf: Workflow, dir: File, ctl: Ctl): List<File> {
        require(wf.steps.isNotEmpty()) { "Add at least one step" }
        var current = inputs
        wf.steps.forEachIndexed { si, step ->
            val sub = ctl.sub(si.toFloat() / wf.steps.size, (si + 1f) / wf.steps.size)
            sub.step(0f, "Step ${si + 1}: ${step.label()}")
            val p = step.params
            current = if (step.type == "merge") {
                if (current.size < 2) current
                else listOf(OrganizeEngine.merge(current, File(dir, "merged_s$si.pdf"), sub))
            } else current.mapIndexed { fi, f ->
                val c = sub.sub(fi.toFloat() / current.size, (fi + 1f) / current.size)
                val out = File(dir, "${f.baseName().substringBefore("_s")}_s$si.pdf")
                when (step.type) {
                    "compress" -> OptimizeEngine.compress(f, OptimizeEngine.Level.valueOf(p["level"] ?: "RECOMMENDED"), out, c).out
                    "rotate" -> OrganizeEngine.rotate(f, (p["deg"] ?: "90").toInt(), null, out, c)
                    "resize" -> { val (w, h) = SIZES[p["size"] ?: "A4"] ?: (595f to 842f); EditEngine.resize(f, w, h, EditEngine.Fit.FIT, true, out, c) }
                    "numbers" -> EditEngine.pageNumbers(f, EditEngine.NumberOpts(format = (p["format"] ?: "0").toInt(), hAlign = (p["align"] ?: "1").toInt()), out, c)
                    "watermark" -> EditEngine.watermark(f, EditEngine.WatermarkOpts(text = p["text"] ?: "CONFIDENTIAL", opacity = (p["opacity"] ?: "0.25").toFloat()), out, c)
                    "ocr" -> OptimizeEngine.ocrPdf(f, dir, c).pdf.let { it.renameTo(out); out }
                    "pdfa" -> ConvertFromEngine.toPdfA(f, false, out, c).out
                    "protect" -> SecurityEngine.protect(f, p["password"] ?: error("Protect step needs a password"), "", true, true, false, out, c)
                    else -> error("Unknown step ${step.type}")
                }
            }
        }
        // Friendly final names
        return current.mapIndexed { i, f ->
            val name = (if (current.size == 1) wf.name else "${wf.name}_${i + 1}").replace(Regex("[^\\w\\- ]"), "_") + ".pdf"
            val dest = File(dir, name)
            if (f.renameTo(dest)) dest else f
        }
    }

    data class SummaryResult(val markdown: String, val file: File, val usedOcr: Boolean)

    fun summarize(input: File, points: Int, dir: File, ctl: Ctl): SummaryResult {
        ctl.step(0.05f, "Extracting text…")
        var text = Pdf.load(input).use { doc -> PDFTextStripper().getText(doc) }
        var usedOcr = false
        if (text.trim().length < 80) {
            usedOcr = true
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val sb = StringBuilder()
            try {
                PageRenderer(input).use { r ->
                    val n = minOf(r.pageCount, 40)
                    for (i in 0 until n) {
                        ctl.step(0.1f + 0.6f * i / n, "No text layer — reading page ${i + 1} with OCR")
                        val bmp = r.renderAtDpi(i, 200, 12_000_000L)
                        sb.append(Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0))).text).append("\n\n")
                        bmp.recycle()
                    }
                }
            } finally { rec.close() }
            text = sb.toString()
        }
        if (text.trim().length < 40) error("Not enough readable text to summarize")
        ctl.step(0.8f, "Scoring sentences (TF-IDF + TextRank)…")
        val res = Summarizer.summarize(text, points)
        val md = res.toMarkdown(input.baseName())
        val f = File(dir, "${input.baseName()}_summary.md").apply { writeText(md) }
        ctl.step(1f)
        return SummaryResult(md, f, usedOcr)
    }
}
