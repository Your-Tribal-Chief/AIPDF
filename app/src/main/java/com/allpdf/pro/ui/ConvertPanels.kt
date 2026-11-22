package com.allpdf.pro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.baseName
import com.allpdf.pro.engine.ConvertFromEngine
import com.allpdf.pro.engine.ConvertToEngine
import com.allpdf.pro.engine.OptimizeEngine
import java.io.File

@Composable
fun CompressPanel(s: ToolSession) {
    val ctx = LocalContext.current
    var level by s.remembered("level") { OptimizeEngine.Level.RECOMMENDED }
    val f = s.inputs.first()
    OptionsLayout("Compress PDF", icon = Icons.Filled.Compress, onRun = {
        val l = level
        s.run(ctx) { c ->
            val r = OptimizeEngine.compress(f, l, File(s.workDir, "${f.baseName()}_compressed.pdf"), c)
            ToolResult(listOf(r.out), "${l.label} compression", listOf("${r.imagesRecompressed} images re-encoded, content streams Flate-compressed"), beforeAfter = r.before to r.after)
        }
    }) {
        FileRow(f)
        Section("Compression level") {
            OptimizeEngine.Level.entries.forEach { l ->
                Card(onClick = { level = l }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (l == level) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp)) {
                        RadioButton(selected = l == level, onClick = { level = l })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(l.label, style = MaterialTheme.typography.titleMedium)
                            Text(when (l) {
                                OptimizeEngine.Level.EXTREME -> "Smallest file, lower image quality (≈${l.maxSide}px, JPEG ${(l.quality * 100).toInt()})"
                                OptimizeEngine.Level.RECOMMENDED -> "Good quality, good compression (≈${l.maxSide}px, JPEG ${(l.quality * 100).toInt()})"
                                OptimizeEngine.Level.LESS -> "High quality, less compression (≈${l.maxSide}px, JPEG ${(l.quality * 100).toInt()})"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RepairPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    OptionsLayout("Repair PDF", icon = Icons.Filled.Healing, onRun = {
        s.run(ctx) { c ->
            val r = OptimizeEngine.repair(f, File(s.workDir, "${f.baseName()}_repaired.pdf"), s.workDir, c)
            ToolResult(listOf(r.out), "Recovered ${r.pages} pages", listOf(r.method))
        }
    }) {
        FileRow(f)
        InfoCard("Repair rebuilds the cross-reference table and object streams, strips junk before the header, restores a missing trailer, and as a last resort re-renders every readable page.")
    }
}

@Composable
fun OcrPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val isPdf = s.inputs.firstOrNull()?.extension.equals("pdf", true)
    OptionsLayout("Run OCR", s.inputs.isNotEmpty(), Icons.Filled.TextFields, onRun = {
        val files = s.inputs.toList()
        s.run(ctx) { c ->
            val r = if (isPdf) OptimizeEngine.ocrPdf(files.first(), s.workDir, c)
                    else OptimizeEngine.ocrImages(files.filter { !it.extension.equals("pdf", true) }, s.workDir, c)
            ToolResult(listOf(r.pdf, r.text), "Recognized ${r.words} words", listOf("Searchable PDF with an invisible text layer + plain text file"), text = r.text.readText().take(6000))
        }
    }) {
        Section(if (isPdf) "PDF" else "Images (in page order)") { InputList(s, !isPdf) }
        InfoCard("Uses an on-device recognition model bundled in the app (Latin script). Nothing is uploaded.")
    }
}

@Composable
fun ImagesPanel(s: ToolSession) {
    val ctx = LocalContext.current
    var size by s.remembered("size") { ConvertToEngine.PageSize.A4 }
    var orient by s.remembered("orient") { ConvertToEngine.Orientation.AUTO }
    var margin by s.remembered("margin") { ConvertToEngine.Margin.SMALL }
    OptionsLayout("Convert ${s.inputs.size} image${if (s.inputs.size == 1) "" else "s"}", s.inputs.isNotEmpty(), Icons.Filled.PictureAsPdf, onRun = {
        val files = s.inputs.toList(); val sz = size; val o = orient; val m = margin
        s.run(ctx) { c ->
            val out = File(s.workDir, "${files.first().baseName()}_images.pdf")
            ToolResult(listOf(ConvertToEngine.imagesToPdf(files, sz, o, m, out, c)), "${files.size} pages")
        }
    }) {
        Section("Page size") { Choice(ConvertToEngine.PageSize.entries, size, { it.label }) { size = it } }
        Section("Orientation") { Choice(ConvertToEngine.Orientation.entries, orient, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { orient = it } }
        Section("Margin") { Choice(ConvertToEngine.Margin.entries, margin, { it.label }) { margin = it } }
        Section("Images (page order)") { InputList(s, true) }
    }
}

@Composable
fun OfficePanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    OptionsLayout("Convert to PDF", icon = Icons.Filled.PictureAsPdf, onRun = {
        s.run(ctx) { c ->
            val out = File(s.workDir, "${f.baseName()}.pdf")
            when (s.tool) {
                Tool.WORD_TO_PDF -> ConvertToEngine.wordToPdf(f, out, c)
                Tool.PPT_TO_PDF -> ConvertToEngine.pptToPdf(f, out, c)
                else -> ConvertToEngine.excelToPdf(f, out, c)
            }
            ToolResult(listOf(out), "Converted ${f.extension.uppercase()} to PDF")
        }
    }) {
        FileRow(f)
        InfoCard(when (s.tool) {
            Tool.WORD_TO_PDF -> "Headings, paragraphs, bold/italic, lists, tables and embedded images are laid out with the system fonts (full Unicode)."
            Tool.PPT_TO_PDF -> "Each slide becomes a page: backgrounds, shapes, pictures, tables and text boxes with layout/master placeholders."
            else -> "Each sheet is paginated on landscape pages with gridlines, numeric alignment and column bands for wide sheets."
        })
    }
}

@Composable
fun HtmlPanel(s: ToolSession) {
    val ctx = LocalContext.current
    var html by s.remembered("html") { "<h1>Hello</h1>\n<p>Paste or type <b>HTML</b> here.</p>" }
    var landscape by s.remembered("land") { false }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) s.addUris(ctx, listOf(uri), replace = true) }
    val file = s.inputs.firstOrNull()
    OptionsLayout("Convert HTML to PDF", file != null || html.isNotBlank(), Icons.Filled.PictureAsPdf, onRun = {
        val h = html; val land = landscape
        s.run(ctx) { c ->
            val out = File(s.workDir, "${file?.baseName() ?: "page"}.pdf")
            ConvertToEngine.htmlToPdf(ctx, if (file == null) h else null, file, land, out, c)
            ToolResult(listOf(out), "Rendered with the on-device WebView engine")
        }
    }) {
        Section("Source") {
            if (file != null) { FileRow(file, onRemove = { s.removeInput(0) }) }
            else {
                OutlinedButton(onClick = { launcher.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose an .html file")
                }
                Spacer(Modifier.height(10.dp))
                Field(html, { html = it }, "…or paste HTML", singleLine = false, minLines = 8)
            }
        }
        SwitchRow("Landscape pages", landscape) { landscape = it }
        InfoCard("Network access is blocked: only inline content and files next to the HTML are rendered.")
    }
}

@Composable
fun PdfToImagesPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var mode by s.remembered("mode") { "render" }
    var png by s.remembered("png") { false }
    var dpi by s.remembered("dpi") { 150 }
    var zip by s.remembered("zip") { true }
    OptionsLayout(if (mode == "render") "Convert pages" else "Extract images", icon = Icons.Filled.PhotoLibrary, onRun = {
        val m = mode; val p = png; val d = dpi; val z = zip
        s.run(ctx) { c ->
            val imgs = if (m == "render") ConvertFromEngine.renderPages(f, null, d, p, s.workDir, c.sub(0f, 0.9f))
                       else ConvertFromEngine.extractImages(f, s.workDir, c.sub(0f, 0.9f))
            val outs = if (z && imgs.size > 1) listOf(Files.zip(imgs, File(s.workDir, "${f.baseName()}_images.zip"), c.sub(0.9f, 1f)).also { imgs.forEach { it.delete() } })
                       else { if (!z) imgs.forEach { Files.exportImageToGallery(ctx, it) }; imgs }
            ToolResult(outs, "${imgs.size} image${if (imgs.size == 1) "" else "s"}${if (!z) " • also saved to gallery" else ""}")
        }
    }) {
        FileRow(f)
        Section("Mode") { Choice(listOf("render", "extract"), mode, { if (it == "render") "Page to image" else "Extract embedded images" }) { mode = it } }
        if (mode == "render") {
            Section("Format") { Choice(listOf(false, true), png, { if (it) "PNG" else "JPG" }) { png = it } }
            Section("Resolution") { Choice(listOf(72, 150, 220, 300), dpi, { "$it DPI" }) { dpi = it } }
        }
        Section("Output") { Choice(listOf(true, false), zip, { if (it) "ZIP archive" else "Separate files + gallery" }) { zip = it } }
    }
}

@Composable
fun PdfToWordPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var images by s.remembered("img") { true }
    OptionsLayout("Convert to Word", icon = Icons.Filled.Description, onRun = {
        val im = images
        s.run(ctx) { c -> ToolResult(listOf(ConvertFromEngine.toDocx(f, im, File(s.workDir, "${f.baseName()}.docx"), c)), "Editable DOCX") }
    }) {
        FileRow(f)
        SwitchRow("Include images", images, "Embedded pictures; scanned pages are kept as images") { images = it }
        InfoCard("Headings are detected from font size and weight, paragraphs are re-flowed, bullet lists and aligned columns become Word lists and tables.")
    }
}

@Composable
fun PdfToPptPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var editable by s.remembered("edit") { false }
    OptionsLayout("Convert to PowerPoint", icon = Icons.Filled.Slideshow, onRun = {
        val e = editable
        s.run(ctx) { c -> ToolResult(listOf(ConvertFromEngine.toPptx(f, e, File(s.workDir, "${f.baseName()}.pptx"), c)), if (e) "Editable text boxes" else "Exact-look slides") }
    }) {
        FileRow(f)
        Section("Slide content") {
            Choice(listOf(false, true), editable, { if (it) "Editable text boxes + images" else "Exact look (page backgrounds)" }) { editable = it }
        }
    }
}

@Composable
fun PdfToExcelPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var one by s.remembered("one") { false }
    OptionsLayout("Convert to Excel", icon = Icons.Filled.TableChart, onRun = {
        val o = one
        s.run(ctx) { c -> ToolResult(listOf(ConvertFromEngine.toXlsx(f, o, File(s.workDir, "${f.baseName()}.xlsx"), c)), "Tables detected from column alignment") }
    }) {
        FileRow(f)
        Section("Sheets") { Choice(listOf(false, true), one, { if (it) "Everything in one sheet" else "One sheet per page" }) { one = it } }
        InfoCard("Rows come from text lines; columns are found by clustering aligned cell edges. Numbers become numeric cells.")
    }
}

@Composable
fun PdfAPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    var strict by s.remembered("strict") { false }
    OptionsLayout("Convert to PDF/A", icon = Icons.Filled.Archive, onRun = {
        val st = strict
        s.run(ctx) { c -> val r = ConvertFromEngine.toPdfA(f, st, File(s.workDir, "${f.baseName()}_PDFA.pdf"), c); ToolResult(listOf(r.out), "PDF/A-2B", r.notes) }
    }) {
        FileRow(f)
        Section("Mode") { Choice(listOf(false, true), strict, { if (it) "Strict (rasterize + text layer)" else "Preserve (vector)" }) { strict = it } }
        InfoCard("Adds PDF/A-2B XMP identification, an sRGB output intent, consistent metadata, removes encryption/JavaScript/attachments. Strict mode guarantees font compliance.")
    }
}

@Composable
fun MarkdownPanel(s: ToolSession) {
    val ctx = LocalContext.current
    val f = s.inputs.first()
    OptionsLayout("Convert to Markdown", icon = Icons.Filled.Notes, onRun = {
        s.run(ctx) { c ->
            val out = ConvertFromEngine.toMarkdown(f, File(s.workDir, "${f.baseName()}.md"), c)
            ToolResult(listOf(out), "Headings, lists, paragraphs and tables", text = out.readText().take(15000))
        }
    }) {
        FileRow(f)
        InfoCard("Produces clean GitHub-flavoured Markdown: # headings by font size, - bullets, re-flowed paragraphs and | tables |.")
    }
}
