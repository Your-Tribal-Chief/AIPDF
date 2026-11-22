package com.allpdf.pro.core

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Progress + cooperative cancellation handle passed into every engine. */
class Ctl(
    private val job: Job? = null,
    private val onProgress: (Float, String) -> Unit = { _, _ -> },
    private val start: Float = 0f,
    private val end: Float = 1f,
    private val flag: AtomicBoolean = AtomicBoolean(false),
) {
    fun cancel() = flag.set(true)
    val isCancelled get() = flag.get() || job?.isActive == false

    fun check() {
        if (isCancelled) throw CancellationException("Cancelled by user")
    }

    fun step(fraction: Float, message: String = "") {
        check()
        onProgress(start + (end - start) * fraction.coerceIn(0f, 1f), message)
    }

    /** Child handle that maps 0..1 onto [from, to] of this handle's range. */
    fun sub(from: Float, to: Float): Ctl =
        Ctl(job, onProgress, start + (end - start) * from, start + (end - start) * to, flag)
}
