package com.allpdf.pro.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Files {
    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "file").name
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Streams a SAF document into the app cache (no full read into memory). */
    fun importUri(ctx: Context, uri: Uri): File {
        val name = safeName(displayName(ctx, uri))
        val dir = File(Env.inputsDir, System.nanoTime().toString(36)).apply { mkdirs() }
        val out = File(dir, name)
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $name")
        input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 64 * 1024) } }
        return out
    }

    fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "md" -> "text/markdown"
            "zip" -> "application/zip"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files[0])
                putExtra(Intent.EXTRA_STREAM, uriFor(ctx, files[0]))
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(ctx, it) }))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openExternal(ctx: Context, f: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, f), mimeOf(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++ }
        return f
    }

    /** Copies to Downloads/AllPDF Pro (scoped storage on Android 10+). Returns a user-facing location. */
    fun exportToDownloads(ctx: Context, f: File): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AllPDF Pro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "App storage"
                resolver.openOutputStream(uri)?.use { o -> FileInputStream(f).use { it.copyTo(o, 64 * 1024) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/AllPDF Pro"
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "AllPDF Pro").apply { mkdirs() }
                f.copyTo(unique(dir, f.name))
                "Android/data/${ctx.packageName}/files/Documents/AllPDF Pro"
            }
        } catch (e: Exception) {
            "App storage"
        }
    }

    /** Saves an image into the gallery (Pictures/AllPDF Pro). */
    fun exportImageToGallery(ctx: Context, f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AllPDF Pro")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri != null && ctx.contentResolver.openOutputStream(uri)?.use { o -> FileInputStream(f).use { it.copyTo(o) }; true } == true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AllPDF Pro").apply { mkdirs() }
            f.copyTo(unique(dir, f.name)); true
        }
    } catch (_: Exception) { false }

    fun zip(files: List<File>, out: File, ctl: Ctl? = null): File {
        ZipOutputStream(FileOutputStream(out).buffered()).use { z ->
            val used = HashSet<String>()
            files.forEachIndexed { i, f ->
                ctl?.step(i.toFloat() / files.size, "Zipping ${f.name}")
                var n = f.name; var k = 2
                while (!used.add(n)) { n = f.baseName() + "_$k." + f.extension; k++ }
                z.putNextEntry(ZipEntry(n))
                FileInputStream(f).use { it.copyTo(z, 64 * 1024) }
                z.closeEntry()
            }
        }
        return out
    }
}
