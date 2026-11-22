package com.allpdf.pro.core

import android.content.Context
import java.io.File
import java.util.UUID

object Env {
    lateinit var app: Context
        private set

    fun init(ctx: Context) { app = ctx.applicationContext }

    val workRoot: File get() = File(app.cacheDir, "work").apply { mkdirs() }

    /** Fresh scratch directory for one job. */
    fun newWorkDir(tag: String = "job"): File =
        File(workRoot, tag + "_" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }

    val inputsDir: File get() = File(app.cacheDir, "inputs").apply { mkdirs() }
    val outputsDir: File get() = File(app.filesDir, "outputs").apply { mkdirs() }

    fun clearOldWork(maxAgeMs: Long = 6L * 3600 * 1000) {
        val now = System.currentTimeMillis()
        listOf(workRoot, inputsDir).forEach { root ->
            root.listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.deleteRecursively() }
        }
    }
}

fun File.baseName(): String = name.substringBeforeLast('.', name)

fun safeName(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "file" }.take(120)

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.2f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
