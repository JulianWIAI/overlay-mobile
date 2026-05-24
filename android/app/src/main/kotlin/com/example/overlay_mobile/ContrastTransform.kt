package com.example.overlay_mobile

/**
 * High-Contrast mode: 4 × 4 tile CLAHE (Contrast-Limited Adaptive Histogram
 * Equalisation) with bilinear LUT interpolation across tile boundaries.
 *
 * Pipeline:
 *   Pass 1 — build one luma histogram per tile (16 histograms, pre-allocated).
 *   Pass 2 — clip each histogram at [clipLimit], redistribute excess uniformly,
 *             then integrate into a cumulative LUT.
 *   Pass 3 — for every pixel bilinearly interpolate the four nearest tile LUTs
 *             to get the new luma value, then scale RGB proportionally so hue
 *             and saturation are preserved.
 *
 * Clip limit:
 *   clipLimit = tilePixels / 256 × CLIP_FACTOR + 1
 *   CLIP_FACTOR = 3 gives a moderate contrast boost without posterisation.
 *
 * Bilinear interpolation:
 *   Tile centre i sits at x = (i + 0.5) × tileWidth.  For a pixel at (px, py)
 *   the fractional tile position is txF = (px − tileW/2) / tileW.
 *   The two surrounding tile indices are floor(txF) and ceil(txF); the weights
 *   are the fractional remainder and its complement.  Edge tiles are clamped.
 *
 * Allocation policy:
 *   All 16 histogram IntArrays and 16 LUT IntArrays are allocated once at
 *   class-load time and reused on every frame.  No allocation occurs in the
 *   hot path.
 */
class ContrastTransform : PixelTransform() {

    companion object {
        private const val TILES       = 4    // tiles per axis (TILES² total)
        private const val CLIP_FACTOR = 3    // histogram clip multiplier
    }

    private val histograms = Array(TILES * TILES) { IntArray(256) }
    private val luts       = Array(TILES * TILES) { IntArray(256) }

    // ── Non-spatial fallback: global histogram equalisation ──────────────────────

    override fun transformInPlace(rgba: ByteArray) {
        val hist = histograms[0]
        hist.fill(0)
        val n = rgba.size / 4

        var i = 0
        while (i < rgba.size) {
            hist[(rgba[i].toInt() and 0xFF) * 299 / 1_000 +
                 (rgba[i + 1].toInt() and 0xFF) * 587 / 1_000 +
                 (rgba[i + 2].toInt() and 0xFF) * 114 / 1_000]++
            i += 4
        }

        buildLut(hist, n, 1, luts[0])

        val lut = luts[0]
        i = 0
        while (i < rgba.size) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val y  = (r * 299 + g * 587 + b * 114) / 1_000
            val ny = lut[y]
            if (y > 0) {
                rgba[i    ] = (r * ny / y).coerceAtMost(255).toByte()
                rgba[i + 1] = (g * ny / y).coerceAtMost(255).toByte()
                rgba[i + 2] = (b * ny / y).coerceAtMost(255).toByte()
            } else {
                rgba[i    ] = 0; rgba[i + 1] = 0; rgba[i + 2] = 0
            }
            i += 4
        }
    }

    // ── Spatial CLAHE ─────────────────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        if (width < TILES * 4 || height < TILES * 4) { transformInPlace(rgba); return }

        val tW    = width  / TILES
        val tH    = height / TILES
        val tSize = tW * tH

        // ── Pass 1: per-tile luma histograms ─────────────────────────────────────
        for (tj in 0 until TILES) {
            for (ti in 0 until TILES) {
                val hist = histograms[tj * TILES + ti]
                hist.fill(0)
                val x0 = ti * tW
                val y0 = tj * tH
                val x1 = minOf(x0 + tW, width)
                val y1 = minOf(y0 + tH, height)
                for (py in y0 until y1) {
                    var ri = (py * width + x0) * 4
                    for (x in x0 until x1) {
                        val y = ((rgba[ri    ].toInt() and 0xFF) * 299 +
                                 (rgba[ri + 1].toInt() and 0xFF) * 587 +
                                 (rgba[ri + 2].toInt() and 0xFF) * 114) / 1_000
                        hist[y]++
                        ri += 4
                    }
                }
            }
        }

        // ── Pass 2: clip and build per-tile LUTs ─────────────────────────────────
        for (t in 0 until TILES * TILES) {
            buildLut(histograms[t], tSize, CLIP_FACTOR, luts[t])
        }

        // ── Pass 3: apply LUT with bilinear tile interpolation ───────────────────
        val tWH = tW / 2
        val tHH = tH / 2

        for (py in 0 until height) {
            val tyF  = (py - tHH).toFloat() / tH
            val ty0  = tyF.toInt().coerceIn(0, TILES - 1)
            val ty1  = (ty0 + 1).coerceIn(0, TILES - 1)
            val wy1  = (tyF - ty0).coerceIn(0f, 1f)
            val wy0  = 1f - wy1

            for (px in 0 until width) {
                val txF = (px - tWH).toFloat() / tW
                val tx0 = txF.toInt().coerceIn(0, TILES - 1)
                val tx1 = (tx0 + 1).coerceIn(0, TILES - 1)
                val wx1 = (txF - tx0).coerceIn(0f, 1f)
                val wx0 = 1f - wx1

                val ri = (py * width + px) * 4
                val r  = rgba[ri    ].toInt() and 0xFF
                val g  = rgba[ri + 1].toInt() and 0xFF
                val b  = rgba[ri + 2].toInt() and 0xFF
                val y  = (r * 299 + g * 587 + b * 114) / 1_000

                val ny = (wx0 * wy0 * luts[ty0 * TILES + tx0][y] +
                          wx1 * wy0 * luts[ty0 * TILES + tx1][y] +
                          wx0 * wy1 * luts[ty1 * TILES + tx0][y] +
                          wx1 * wy1 * luts[ty1 * TILES + tx1][y]).toInt().coerceIn(0, 255)

                if (y > 0) {
                    rgba[ri    ] = (r * ny / y).coerceAtMost(255).toByte()
                    rgba[ri + 1] = (g * ny / y).coerceAtMost(255).toByte()
                    rgba[ri + 2] = (b * ny / y).coerceAtMost(255).toByte()
                } else {
                    rgba[ri    ] = 0; rgba[ri + 1] = 0; rgba[ri + 2] = 0
                }
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    private fun buildLut(hist: IntArray, tileSize: Int, clipFactor: Int, lut: IntArray) {
        val clipLimit = tileSize / 256 * clipFactor + 1

        // Clip and accumulate excess
        var excess = 0
        for (v in hist) excess += maxOf(0, v - clipLimit)
        val redistribute = excess / 256

        var cdf    = 0
        var cdfMin = -1
        for (i in 0..255) {
            cdf += minOf(hist[i], clipLimit) + redistribute
            if (cdfMin < 0 && cdf > 0) cdfMin = cdf
            lut[i] = if (tileSize > cdfMin && cdfMin >= 0) {
                ((cdf - cdfMin) * 255 / (tileSize - cdfMin)).coerceIn(0, 255)
            } else 0
        }
    }
}
