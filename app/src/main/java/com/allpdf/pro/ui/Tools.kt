package com.allpdf.pro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class Cat(val label: String, val color: Color) {
    ORGANIZE("Organize", Color(0xFFE5322D)),
    OPTIMIZE("Optimize", Color(0xFF16A34A)),
    CONVERT("Convert", Color(0xFF2563EB)),
    EDIT("Edit", Color(0xFF7C3AED)),
    SECURITY("Security", Color(0xFF0F766E)),
    WORKFLOWS("Workflows", Color(0xFFEA580C)),
}

enum class Input { PDF, PDFS, IMAGES, WORD, PPT, EXCEL, HTML, NONE, TWO_PDFS, PDF_OR_IMAGES }

enum class Tool(val title: String, val desc: String, val cat: Cat, val icon: ImageVector, val input: Input, val color: Color = cat.color) {
    MERGE("Merge PDF", "Combine PDFs in the order you want", Cat.ORGANIZE, Icons.Filled.MergeType, Input.PDFS),
    SPLIT("Split PDF", "Ranges, fixed intervals or every page", Cat.ORGANIZE, Icons.Filled.CallSplit, Input.PDF),
    REMOVE("Remove pages", "Tap pages to delete them", Cat.ORGANIZE, Icons.Filled.DeleteSweep, Input.PDF),
    EXTRACT("Extract pages", "Export pages as a PDF or ZIP", Cat.ORGANIZE, Icons.Filled.FileCopy, Input.PDF),
    ORGANIZE("Organize PDF", "Reorder, rotate, insert and delete pages", Cat.ORGANIZE, Icons.Filled.Dashboard, Input.PDF),
    SCAN("Scan to PDF", "Capture documents with your camera", Cat.ORGANIZE, Icons.Filled.DocumentScanner, Input.NONE),

    COMPRESS("Compress PDF", "Shrink file size on-device", Cat.OPTIMIZE, Icons.Filled.Compress, Input.PDF, Color(0xFF16A34A)),
    REPAIR("Repair PDF", "Recover damaged or corrupt PDFs", Cat.OPTIMIZE, Icons.Filled.Healing, Input.PDF, Color(0xFF15803D)),
    OCR("OCR PDF", "Make scans searchable and copyable", Cat.OPTIMIZE, Icons.Filled.TextFields, Input.PDF_OR_IMAGES, Color(0xFF059669)),

    IMG_TO_PDF("JPG to PDF", "JPG, PNG and WebP images to PDF", Cat.CONVERT, Icons.Filled.Image, Input.IMAGES, Color(0xFFF59E0B)),
    WORD_TO_PDF("Word to PDF", "DOC and DOCX to PDF", Cat.CONVERT, Icons.Filled.Description, Input.WORD, Color(0xFF2563EB)),
    PPT_TO_PDF("PowerPoint to PDF", "PPT and PPTX slides to PDF", Cat.CONVERT, Icons.Filled.Slideshow, Input.PPT, Color(0xFFEA580C)),
    EXCEL_TO_PDF("Excel to PDF", "XLS, XLSX and CSV to PDF", Cat.CONVERT, Icons.Filled.TableChart, Input.EXCEL, Color(0xFF16A34A)),
    HTML_TO_PDF("HTML to PDF", "Local HTML files or pasted markup", Cat.CONVERT, Icons.Filled.Code, Input.HTML, Color(0xFFCA8A04)),
    PDF_TO_IMG("PDF to JPG", "Render pages or extract images", Cat.CONVERT, Icons.Filled.PhotoLibrary, Input.PDF, Color(0xFFF59E0B)),
    PDF_TO_WORD("PDF to Word", "Editable DOCX with headings & tables", Cat.CONVERT, Icons.Filled.Article, Input.PDF, Color(0xFF1D4ED8)),
    PDF_TO_PPT("PDF to PowerPoint", "PPTX slides from PDF pages", Cat.CONVERT, Icons.Filled.CoPresent, Input.PDF, Color(0xFFC2410C)),
    PDF_TO_EXCEL("PDF to Excel", "Detect tables into XLSX", Cat.CONVERT, Icons.Filled.GridOn, Input.PDF, Color(0xFF15803D)),
    PDF_TO_PDFA("PDF to PDF/A", "Long-term archival PDF/A-2B", Cat.CONVERT, Icons.Filled.Archive, Input.PDF, Color(0xFF475569)),
    PDF_TO_MD("PDF to Markdown", "Clean .md for notes and LLMs", Cat.CONVERT, Icons.Filled.Notes, Input.PDF, Color(0xFF334155)),

    RESIZE("Resize PDF", "A3–Legal or custom page sizes", Cat.EDIT, Icons.Filled.AspectRatio, Input.PDF),
    CROP("Crop PDF", "Trim margins visually", Cat.EDIT, Icons.Filled.Crop, Input.PDF),
    ROTATE("Rotate PDF", "Rotate pages or whole files", Cat.EDIT, Icons.Filled.RotateRight, Input.PDFS),
    NUMBERS("Page numbers", "Position, format, color and start", Cat.EDIT, Icons.Filled.FormatListNumbered, Input.PDF),
    WATERMARK("Watermark", "Text or image, tiled or placed", Cat.EDIT, Icons.Filled.BrandingWatermark, Input.PDF),
    EDIT("Edit PDF", "Text, images, shapes, highlight, draw", Cat.EDIT, Icons.Filled.Edit, Input.PDF),
    FORMS("PDF Forms", "Fill fields or add new ones", Cat.EDIT, Icons.Filled.DynamicForm, Input.PDF),

    PROTECT("Protect PDF", "AES-256 password encryption", Cat.SECURITY, Icons.Filled.Lock, Input.PDF),
    UNLOCK("Unlock PDF", "Remove a known password", Cat.SECURITY, Icons.Filled.LockOpen, Input.PDF),
    SIGN("Sign PDF", "Draw, type or import a signature", Cat.SECURITY, Icons.Filled.Draw, Input.PDF),
    REDACT("Redact PDF", "Permanently black out content", Cat.SECURITY, Icons.Filled.VisibilityOff, Input.PDF),
    COMPARE("Compare PDF", "Visual and text differences", Cat.SECURITY, Icons.Filled.Compare, Input.TWO_PDFS),

    WORKFLOWS("Workflows", "Chain tools and save presets", Cat.WORKFLOWS, Icons.Filled.AccountTree, Input.PDFS),
    SUMMARIZE("Summarize PDF", "Offline key-point summary", Cat.WORKFLOWS, Icons.Filled.Summarize, Input.PDF),
    VIEWER("PDF Viewer", "Read with pinch-zoom and night mode", Cat.WORKFLOWS, Icons.Filled.MenuBook, Input.PDF),
    ;

    val mimes: Array<String>
        get() = when (input) {
            Input.PDF, Input.PDFS, Input.TWO_PDFS -> arrayOf("application/pdf")
            Input.IMAGES -> arrayOf("image/jpeg", "image/png", "image/webp", "image/*")
            Input.PDF_OR_IMAGES -> arrayOf("application/pdf", "image/*")
            Input.WORD -> arrayOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/rtf", "text/plain", "text/rtf")
            Input.PPT -> arrayOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            Input.EXCEL -> arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values")
            Input.HTML -> arrayOf("text/html", "application/xhtml+xml", "text/plain")
            Input.NONE -> arrayOf("image/*")
        }

    val multi: Boolean get() = input == Input.PDFS || input == Input.IMAGES || input == Input.PDF_OR_IMAGES || input == Input.TWO_PDFS
}
