package com.allpdf.pro.ui

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.launch
import java.io.File

sealed class Screen {
    data object Home : Screen()
    class ToolS(val session: ToolSession) : Screen()
    class Viewer(val file: File) : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top: Screen get() = stack[stack.size - 1]

    fun openTool(t: Tool, files: List<File> = emptyList()) {
        if (t == Tool.VIEWER && files.isNotEmpty()) { openViewer(files[0]); return }
        val s = ToolSession(t)
        stack.add(Screen.ToolS(s))
        if (files.isNotEmpty()) s.scope.launch { files.forEach { s.addFile(it) } }
    }

    fun openViewer(f: File) { stack.add(Screen.Viewer(f)) }

    fun replaceWithViewer(f: File) { pop(); openViewer(f) }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val s = stack.removeAt(stack.size - 1)
        if (s is Screen.ToolS) s.session.dispose()
        return true
    }
}
