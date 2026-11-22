package com.allpdf.pro.core

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maps "display" coordinates (what the user sees: rotation applied, origin top-left, y down, points)
 * to PDF user space for a page, so overlays land correctly on rotated/cropped pages.
 */
class PageFrame(page: PDPage) {
    private val box = Pdf.box(page)
    val rotation = ((page.rotation % 360) + 360) % 360
    private val w = box.width
    private val h = box.height
    private val llx = box.lowerLeftX
    private val lly = box.lowerLeftY
    val width: Float = if (rotation % 180 == 0) w else h
    val height: Float = if (rotation % 180 == 0) h else w

    /** Display point (dx, dy from top-left) -> user space point. */
    fun user(dx: Float, dy: Float): FloatArray = when (rotation) {
        90 -> floatArrayOf(llx + dy, lly + dx)
        180 -> floatArrayOf(llx + w - dx, lly + dy)
        270 -> floatArrayOf(llx + w - dy, lly + h - dx)
        else -> floatArrayOf(llx + dx, lly + h - dy)
    }

    /** User space point -> display point (inverse of [user]). */
    fun display(ux: Float, uy: Float): FloatArray = when (rotation) {
        90 -> floatArrayOf(uy - lly, ux - llx)
        180 -> floatArrayOf(llx + w - ux, uy - lly)
        270 -> floatArrayOf(lly + h - uy, llx + w - ux)
        else -> floatArrayOf(ux - llx, lly + h - uy)
    }

    /** User-space unit vector of display +x. */
    private val ax: FloatArray = when (rotation) { 90 -> floatArrayOf(0f, 1f); 180 -> floatArrayOf(-1f, 0f); 270 -> floatArrayOf(0f, -1f); else -> floatArrayOf(1f, 0f) }
    /** User-space unit vector of display "up" (-y). */
    private val up: FloatArray = when (rotation) { 90 -> floatArrayOf(-1f, 0f); 180 -> floatArrayOf(0f, -1f); 270 -> floatArrayOf(1f, 0f); else -> floatArrayOf(0f, 1f) }

    /**
     * Matrix whose unit square maps to a display-space box with bottom-left at (dx, dyBottom),
     * x-extent [sx] and up-extent [sy]; optional extra counter-clockwise rotation in degrees about that origin.
     * Works for text (sx = horizontal scale * size, sy = size) and images (sx = width, sy = height).
     */
    fun matrix(dx: Float, dyBottom: Float, sx: Float, sy: Float, rotDeg: Float = 0f): Matrix {
        val o = user(dx, dyBottom)
        val t = Math.toRadians(rotDeg.toDouble())
        val c = cos(t).toFloat(); val s = sin(t).toFloat()
        val xx = ax[0] * c + up[0] * s; val xy = ax[1] * c + up[1] * s
        val ux = -ax[0] * s + up[0] * c; val uy = -ax[1] * s + up[1] * c
        return Matrix(xx * sx, xy * sx, ux * sy, uy * sy, o[0], o[1])
    }
}
