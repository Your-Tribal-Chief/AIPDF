package com.allpdf.pro.engine

import com.allpdf.pro.core.Ctl
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.Pdf
import com.allpdf.pro.core.baseName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** One page in an organized document. */
sealed class PageRef {
    abstract val rotation: Int
    data class Source(val fileIndex: Int, val pageIndex: Int, override val rotation: Int = 0) : PageRef()
    data class Blank(val width: Float = PDRectangle.A4.width, val height: Float = PDRectangle.A4.height, override val rotation: Int = 0) : PageRef()
}

object OrganizeEngine {

    fun merge(inputs: List<File>, out: File, ctl: Ctl): File {
        require(inputs.size >= 2) { "Select at least two PDFs to merge" }
        val m = PDFMergerUtility()
        m.destinationFileName = out.absolutePath
        inputs.forEach { m.addSource(it) }
        ctl.step(0.1f, "Merging ${inputs.size} files…")
        m.mergeDocuments(Pdf.mem())
        ctl.step(1f, "Merged")
        return out
    }

    /** Parses "1-3, 5, 8-" into 0-based inclusive ranges. */
    fun parseRanges(spec: String, pageCount: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (raw in spec.split(',', ';')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val r = if (part.contains('-')) {
                val (a, b) = part.split('-', limit = 2).map { it.trim() }
                val s = if (a.isEmpty()) 1 else a.toIntOrNull() ?: error("Invalid range \"$part\"")
                val e = if (b.isEmpty()) pageCount else b.toIntOrNull() ?: error("Invalid range \"$part\"")
                (minOf(s, e) - 1)..(maxOf(s, e) - 1)
            } else {
                val n = part.toIntOrNull() ?: error("Invalid page \"$part\"")
                (n - 1)..(n - 1)
            }
            if (r.first < 0 || r.last >= pageCount) error("Range \"$part\" is outside 1–$pageCount")
            out.add(r)
        }
        if (out.isEmpty()) error("Enter at least one page range")
        return out
    }

    private fun writeSubset(src: PDDocument, pages: List<Int>, out: File) {
        Pdf.newDoc().use { d ->
            for (i in pages) {
                val imported = d.importPage(src.getPage(i))
                imported.rotation = src.getPage(i).rotation
            }
            d.save(out)
        }
    }

    /** mode: "ranges" | "every" | "burst" */
    fun split(input: File, mode: String, ranges: String, every: Int, mergeRanges: Boolean, dir: File, ctl: Ctl): List<File> {
        Pdf.load(input).use { doc ->
            val n = doc.numberOfPages
            val groups: List<List<Int>> = when (mode) {
                "burst" -> (0 until n).map { listOf(it) }
                "every" -> { val k = every.coerceAtLeast(1); (0 until n).chunked(k) }
                else -> parseRanges(ranges, n).map { it.toList() }.let { if (mergeRanges) listOf(it.flatten()) else it }
            }
            val outs = ArrayList<File>()
            groups.forEachIndexed { gi, pages ->
                ctl.step(gi.toFloat() / groups.size, "Writing part ${gi + 1} of ${groups.size}")
                val label = if (pages.size == 1) "p${pages[0] + 1}" else "p${pages.first() + 1}-${pages.last() + 1}"
                val f = File(dir, "${input.baseName()}_$label.pdf")
                writeSubset(doc, pages, f)
                outs.add(f)
            }
            ctl.step(1f)
            return outs
        }
    }

    fun removePages(input: File, remove: Set<Int>, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            require(remove.isNotEmpty()) { "Tap pages to mark them for removal" }
            require(remove.size < doc.numberOfPages) { "You can't remove every page" }
            remove.sortedDescending().forEachIndexed { i, idx ->
                ctl.step(i.toFloat() / remove.size, "Removing page ${idx + 1}")
                doc.removePage(idx)
            }
            doc.save(out)
        }
        return out
    }

    fun extractPages(input: File, pages: List<Int>, asZip: Boolean, dir: File, ctl: Ctl): List<File> {
        require(pages.isNotEmpty()) { "Select pages to extract" }
        Pdf.load(input).use { doc ->
            if (!asZip) {
                val f = File(dir, "${input.baseName()}_extracted.pdf")
                writeSubset(doc, pages.sorted(), f)
                ctl.step(1f); return listOf(f)
            }
            val parts = pages.sorted().mapIndexed { i, p ->
                ctl.step(i * 0.9f / pages.size, "Page ${p + 1}")
                File(dir, "${input.baseName()}_page${p + 1}.pdf").also { writeSubset(doc, listOf(p), it) }
            }
            val zip = Files.zip(parts, File(dir, "${input.baseName()}_pages.zip"))
            parts.forEach { it.delete() }
            ctl.step(1f); return listOf(zip)
        }
    }

    /** Builds a new document from [pages] referencing [sources] (index 0 = primary, others inserted PDFs). */
    fun organize(sources: List<File>, pages: List<PageRef>, out: File, ctl: Ctl): File {
        require(pages.isNotEmpty()) { "The document has no pages" }
        val docs = sources.map { Pdf.load(it) }
        try {
            Pdf.newDoc().use { dst ->
                pages.forEachIndexed { i, ref ->
                    ctl.step(i.toFloat() / pages.size, "Page ${i + 1} of ${pages.size}")
                    when (ref) {
                        is PageRef.Source -> {
                            val srcPage = docs[ref.fileIndex].getPage(ref.pageIndex)
                            val p = dst.importPage(srcPage)
                            p.rotation = ((srcPage.rotation + ref.rotation) % 360 + 360) % 360
                        }
                        is PageRef.Blank -> {
                            val p = PDPage(PDRectangle(ref.width, ref.height))
                            p.rotation = ref.rotation
                            dst.addPage(p)
                        }
                    }
                }
                dst.save(out)
            }
        } finally {
            docs.forEach { it.close() }
        }
        ctl.step(1f)
        return out
    }

    /** Rotates selected pages (all when [pages] is null) by [degrees]. */
    fun rotate(input: File, degrees: Int, pages: Set<Int>?, out: File, ctl: Ctl): File {
        Pdf.load(input).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                ctl.step(i.toFloat() / doc.numberOfPages)
                if (pages == null || i in pages) {
                    val p = doc.getPage(i)
                    p.rotation = ((p.rotation + degrees) % 360 + 360) % 360
                }
            }
            doc.save(out)
        }
        return out
    }
}
