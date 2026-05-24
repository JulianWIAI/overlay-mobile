package com.example.overlay_mobile

import android.content.Intent
import android.graphics.PixelFormat

/**
 * Immutable capture parameters passed from Flutter into the service Intent.
 *
 * @param maxWidthPx  Longest side ceiling in pixels.  Aspect ratio is preserved.
 *                    Typical values: 480 (fast), 720 (balanced), 1080 (high-fidelity).
 * @param targetFps   Frame-delivery rate cap sent to the processing pipeline.
 *                    The display may refresh faster; extra frames are dropped cheaply.
 */
data class CaptureConfig(
    val maxWidthPx: Int = 720,
    val targetFps: Int = 30,
) {
    /** Minimum milliseconds between two frames forwarded to the pipeline. */
    val frameIntervalMs: Long = 1_000L / targetFps.coerceIn(1, 120)

    /**
     * Returns (width, height) scaled so the width ≤ [maxWidthPx] while
     * preserving the display aspect ratio.  Both dimensions are rounded down
     * to even numbers, which is required by most GPU/codec pipelines.
     */
    fun scaledSize(displayW: Int, displayH: Int): Pair<Int, Int> {
        val scale = (maxWidthPx.toFloat() / displayW).coerceAtMost(1f)
        val w = (displayW * scale).toInt().evenFloor()
        val h = (displayH * scale).toInt().evenFloor()
        return w to h
    }

    private fun Int.evenFloor() = if (this % 2 == 0) this else this - 1

    companion object {
        /** RGBA_8888 is the native format produced by a VirtualDisplay surface. */
        const val PIXEL_FORMAT: Int = PixelFormat.RGBA_8888

        /** Bytes per pixel for RGBA_8888 (used when stripping row-padding). */
        const val BYTES_PER_PIXEL: Int = 4

        /** Two slots: one being consumed, one being written by the display compositor. */
        const val IMAGE_READER_BUFFER_COUNT: Int = 2

        fun fromIntent(intent: Intent?): CaptureConfig = CaptureConfig(
            maxWidthPx = intent?.getIntExtra("maxWidthPx", 720) ?: 720,
            targetFps  = intent?.getIntExtra("targetFps",  30)  ?: 30,
        )

        fun fromMap(map: Map<String, Any>): CaptureConfig = CaptureConfig(
            maxWidthPx = (map["maxWidthPx"] as? Int) ?: 720,
            targetFps  = (map["targetFps"]  as? Int) ?: 30,
        )
    }

    /** Writes [maxWidthPx] and [targetFps] as Intent extras for [ScreenCaptureService]. */
    fun applyToIntent(intent: Intent) {
        intent.putExtra("maxWidthPx", maxWidthPx)
        intent.putExtra("targetFps",  targetFps)
    }
}
