package com.example.overlay_mobile

/**
 * Echolocation / ultrasound edge-map simulation.
 *
 * Applies a Sobel edge-detection kernel to the luminance channel and maps
 * gradient magnitude to a deep-blue → cyan colour gradient:
 *   gradient = 0  →  (0, 0, 40)  near-black blue (silent/open space)
 *   gradient = 255 → (0, 255, 255) full cyan (hard reflective edge)
 *
 * Pipeline:
 *   Pass 1 — compute luma for every pixel into a pre-allocated scratch buffer
 *            (ByteArray of width × height, lazy, reused across frames).
 *   Pass 2 — apply the 3×3 Sobel kernel using the scratch buffer as the
 *            read source; write the gradient-mapped result to [rgba].
 *   Border pixels (first/last row and column) are filled with the background
 *   colour (0, 0, 40) since the Sobel kernel cannot be applied there.
 *
 * Gradient magnitude approximation:
 *   G ≈ (|Gx| + |Gy|) / 2   (L1 norm, avoids sqrt, range 0–255)
 *
 * Allocation policy:
 *   lumaBuffer is lazily allocated on the first frame and reused thereafter.
 *   No other allocations occur in the hot path.
 */
class EcholocationTransform : PixelTransform() {

    @Volatile private var lumaBuffer: ByteArray? = null

    // Non-spatial fallback: apply the mapping to per-pixel luma without Sobel
    override fun transformInPlace(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1_000
            rgba[i    ] = 0
            rgba[i + 1] = y.toByte()
            rgba[i + 2] = (40 + y * 215 / 255).toByte()
            i += 4
        }
    }

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        if (width < 3 || height < 3) { transformInPlace(rgba); return }

        // ── Pass 1: luma extraction ────────────────────────────────────────────
        val luma = ensureLuma(width * height)
        var ri = 0
        for (li in luma.indices) {
            val r = rgba[ri    ].toInt() and 0xFF
            val g = rgba[ri + 1].toInt() and 0xFF
            val b = rgba[ri + 2].toInt() and 0xFF
            luma[li] = ((r * 299 + g * 587 + b * 114) / 1_000).toByte()
            ri += 4
        }

        // ── Border fill (dark blue — no edge data available) ──────────────────
        fillBorderBlue(rgba, width, height)

        // ── Pass 2: Sobel kernel → gradient → blue-cyan colour map ───────────
        for (y in 1 until height - 1) {
            val rowBase = y * width
            for (x in 1 until width - 1) {
                // 3×3 Sobel neighbourhood (luma values, unsigned)
                val p00 = luma[(y - 1) * width + (x - 1)].toInt() and 0xFF
                val p01 = luma[(y - 1) * width +  x     ].toInt() and 0xFF
                val p02 = luma[(y - 1) * width + (x + 1)].toInt() and 0xFF
                val p10 = luma[ y      * width + (x - 1)].toInt() and 0xFF
                val p12 = luma[ y      * width + (x + 1)].toInt() and 0xFF
                val p20 = luma[(y + 1) * width + (x - 1)].toInt() and 0xFF
                val p21 = luma[(y + 1) * width +  x     ].toInt() and 0xFF
                val p22 = luma[(y + 1) * width + (x + 1)].toInt() and 0xFF

                val gx = -p00 + p02 - 2 * p10 + 2 * p12 - p20 + p22
                val gy = -p00 - 2 * p01 - p02 + p20 + 2 * p21 + p22

                // L1 gradient, halved to stay in 0–255
                val g = (kotlin.math.abs(gx) + kotlin.math.abs(gy)).coerceAtMost(510) / 2

                val i = (rowBase + x) * 4
                rgba[i    ] = 0
                rgba[i + 1] = g.toByte()
                rgba[i + 2] = (40 + g * 215 / 255).toByte()
            }
        }
    }

    private fun fillBorderBlue(rgba: ByteArray, width: Int, height: Int) {
        for (x in 0 until width) {
            val it = x * 4
            val ib = ((height - 1) * width + x) * 4
            rgba[it    ] = 0; rgba[it + 1] = 0; rgba[it + 2] = 40
            rgba[ib    ] = 0; rgba[ib + 1] = 0; rgba[ib + 2] = 40
        }
        for (y in 1 until height - 1) {
            val il = (y * width) * 4
            val ir = (y * width + width - 1) * 4
            rgba[il    ] = 0; rgba[il + 1] = 0; rgba[il + 2] = 40
            rgba[ir    ] = 0; rgba[ir + 1] = 0; rgba[ir + 2] = 40
        }
    }

    private fun ensureLuma(size: Int): ByteArray {
        val b = lumaBuffer
        if (b != null && b.size >= size) return b
        return ByteArray(size).also { lumaBuffer = it }
    }
}
