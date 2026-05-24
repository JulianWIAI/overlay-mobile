package com.example.overlay_mobile

import kotlin.math.sqrt

/**
 * Eagle foveal-contrast vision.
 *
 * Eagles have two foveas per eye and ~8× the cone density of the human fovea.
 * They also possess a fourth cone type extending into the UV range (~370 nm).
 *
 * Simulation:
 *   Centre ellipse (≤ 50 % of the shorter axis radius):
 *     → 1.25× luminance amplification on all channels.
 *     → 1.75× on blue to approximate UV sensitivity (blue-shifted UV response).
 *   Periphery:
 *     → Dimmed to 60 % brightness so the centre reads as visually dominant.
 *
 * Performance optimisation — scanline span pre-computation:
 *   Naïve per-pixel sqrt would cost one sqrt() per pixel (~920 k calls/frame).
 *   Instead, for each scanline y we solve (dx)² < centerRSq − dySq for the
 *   half-width `xHalfSpan`, giving three contiguous pixel runs (left periphery,
 *   centre, right periphery) with zero branching inside each run.
 *   This reduces sqrt() calls to at most [height] per frame (≤ 720 for 720 p).
 */
class EagleVisionTransform : PixelTransform() {

    // ── Non-spatial fallback ───────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            rgba[i    ] = clamp(rgba[i    ].u() * 1.25f)
            rgba[i + 1] = clamp(rgba[i + 1].u() * 1.25f)
            rgba[i + 2] = clamp(rgba[i + 2].u() * 1.75f)
            i += 4
        }
    }

    // ── Spatial path ──────────────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        val cx = width  * 0.5f
        val cy = height * 0.5f

        // Radius = 50% of the shorter half-dimension (covers ~25% of frame area).
        val centerRSq = minOf(cx, cy).let { r -> (r * 0.50f) * (r * 0.50f) }

        for (y in 0 until height) {
            val dy    = (y - cy)
            val dySq  = dy * dy

            // For this scanline, compute the x-range inside the centre circle.
            // Condition: dx² < centerRSq − dySq  →  |dx| < sqrt(remaining).
            val remaining  = centerRSq - dySq
            val xHalfSpan  = if (remaining > 0f) sqrt(remaining.toDouble()).toInt() else 0

            val cxInt  = cx.toInt()
            val xLeft  = (cxInt - xHalfSpan).coerceAtLeast(0)
            val xRight = (cxInt + xHalfSpan).coerceAtMost(width - 1)

            val rowBase = y * width

            // Left periphery ─ dim to 60 %
            for (x in 0 until xLeft) {
                val i = (rowBase + x) * 4
                rgba[i    ] = dim(rgba[i    ].u())
                rgba[i + 1] = dim(rgba[i + 1].u())
                rgba[i + 2] = dim(rgba[i + 2].u())
            }

            // Centre ─ high contrast + UV-bias blue amplification
            for (x in xLeft..xRight) {
                val i = (rowBase + x) * 4
                rgba[i    ] = clamp(rgba[i    ].u() * 1.25f)
                rgba[i + 1] = clamp(rgba[i + 1].u() * 1.25f)
                rgba[i + 2] = clamp(rgba[i + 2].u() * 1.75f)
            }

            // Right periphery ─ dim to 60 %
            for (x in xRight + 1 until width) {
                val i = (rowBase + x) * 4
                rgba[i    ] = dim(rgba[i    ].u())
                rgba[i + 1] = dim(rgba[i + 1].u())
                rgba[i + 2] = dim(rgba[i + 2].u())
            }
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private inline fun Byte.u(): Int = toInt() and 0xFF

    private inline fun clamp(f: Float): Byte = when {
        f >= 255f -> (-1).toByte()
        f <= 0f   -> 0.toByte()
        else      -> f.toInt().toByte()
    }

    // Integer multiply + right-shift avoids float for the dim path.
    // 60/100 approximated as 154/256 (0.6015…) — indistinguishable on screen.
    private inline fun dim(v: Int): Byte = ((v * 154) ushr 8).toByte()
}
