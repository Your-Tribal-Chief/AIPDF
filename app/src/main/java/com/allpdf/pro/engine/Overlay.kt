package com.allpdf.pro.engine

import java.io.File

/** Interactive edits, in normalized display coordinates (0..1 of the visible page, origin top-left). */
sealed class Overlay {
    abstract val page: Int

    data class Text(override val page: Int, val x: Float, val y: Float, val text: String, val sizePt: Float, val color: Int, val bold: Boolean = false) : Overlay()
    data class Image(override val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val file: File) : Overlay()
    enum class ShapeKind { RECT, CIRCLE, LINE, ARROW, HIGHLIGHT }
    data class Shape(override val page: Int, val kind: ShapeKind, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val stroke: Float, val filled: Boolean = false) : Overlay()
    data class Ink(override val page: Int, val points: List<Pair<Float, Float>>, val color: Int, val stroke: Float) : Overlay()
    data class Redact(override val page: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Overlay()
    enum class FieldKind { TEXT, CHECKBOX }
    data class Field(override val page: Int, val kind: FieldKind, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val name: String) : Overlay()
}

fun Int.r() = ((this shr 16) and 0xFF) / 255f
fun Int.g() = ((this shr 8) and 0xFF) / 255f
fun Int.b() = (this and 0xFF) / 255f
