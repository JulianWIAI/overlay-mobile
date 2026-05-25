package com.example.overlay_mobile

import kotlin.math.sqrt

/**
 * Eagle foveal-contrast vision.
 *
 * Eagles have two foveas per eye and ~8× the cone density of the human fovea,
 * plus a fourth UV cone type peaking at ~370 nm.
 *
 * Simulation:
 *   Centre circle (≤ 50 % of the shorter half-axis radius, ≈25 % of frame area):
 *     R × 1.40, G × 1.20, B × 1.50  — vivid, hyper-real colours with UV bias.
 *     Stability: α × 1.50 = 0.627 × 1.50 = 0.941 < 1 ✓
 *   Periphery:
 *     Dimmed to 65 % so the foveal centre dominates without losing context.
 *
 * Performance: scanline span pre-computation keeps sqrt() calls ≤ height/frame.
 */
class EagleVisionTransform : PixelTransform() {

    // ── Non-spatial fallback ───────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            rgba[i    ] = clamp(rgba[i    ].u() * 1.40f)
            rgba[i + 1] = clamp(rgba[i + 1].u() * 1.20f)
            rgba[i + 2] = clamp(rgba[i + 2].u() * 1.50f)
            i += 4
        }
    }

    // ── Spatial path ──────────────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        val cx = width  * 0.5f
        val cy = height * 0.5f

        val centerRSq = minOf(cx, cy).let { r -> (r * 0.50f) * (r * 0.50f) }

        for (y in 0 until height) {
            val dy    = (y - cy)
            val dySq  = dy * dy

            val remaining  = centerRSq - dySq
            val xHalfSpan  = if (remaining > 0f) sqrt(remaining.toDouble()).toInt() else 0

            val cxInt  = cx.toInt()
            val xLeft  = (cxInt - xHalfSpan).coerceAtLeast(0)
            val xRight = (cxInt + xHalfSpan).coerceAtMost(width - 1)

            val rowBase = y * width

            // Left periphery ─ dim to 65 %
            for (x in 0 until xLeft) {
                val i = (rowBase + x) * 4
                rgba[i    ] = dim(rgba[i    ].u())
                rgba[i + 1] = dim(rgba[i + 1].u())
                rgba[i + 2] = dim(rgba[i + 2].u())
            }

            // Centre ─ vivid hyper-real colours with UV-bias blue boost
            for (x in xLeft..xRight) {
                val i = (rowBase + x) * 4
                rgba[i    ] = clamp(rgba[i    ].u() * 1.40f)
                rgba[i + 1] = clamp(rgba[i + 1].u() * 1.20f)
                rgba[i + 2] = clamp(rgba[i + 2].u() * 1.50f)
            }

            // Right periphery ─ dim to 65 %
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

    // 65 % ≈ 166/256 = 0.6484
    private inline fun dim(v: Int): Byte = ((v * 166) ushr 8).toByte()
}
