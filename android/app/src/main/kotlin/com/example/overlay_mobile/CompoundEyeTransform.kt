package com.example.overlay_mobile

import kotlin.math.abs

/**
 * Compound-eye simulation: hexagonal facet mosaic with black inter-facet borders.
 *
 * Algorithm (two passes, one scratch allocation reused across frames):
 *
 *   Pass 1 — copy rgba into scratch so the source is stable while we write.
 *
 *   Pass 2 — for every pixel (px, py):
 *     1. Convert (px, py) to fractional axial hex coordinates (q, r) using
 *        the pointy-top hex grid with size HEX_SIZE pixels.
 *     2. Cube-round (q, r) to the nearest integer hex address (qi, ri).
 *     3. Back-project (qi, ri) to the pixel-space centre (cx, cy).
 *     4. If the pixel is within innerRadius of that centre, copy the centre
 *        pixel's colour — all pixels in a facet share one colour sample.
 *        Otherwise fill with black (the chitinous border between ommatidia).
 *
 * Hex coordinate formulae (pointy-top, size s):
 *   q  =  (√3/3 · x  −  1/3 · y) / s
 *   r  =  (2/3 · y) / s
 *   cx =  s · (√3 · qi  +  √3/2 · ri)
 *   cy =  s · 1.5 · ri
 *
 * Cube-rounding: round all three cube coords; if the sum ≠ 0 nudge the
 * coordinate with the largest rounding error.
 */
class CompoundEyeTransform : PixelTransform() {

    @Volatile private var scratch: ByteArray? = null

    private val hexSize    = 22f              // facet radius in pixels
    private val innerRatio = 0.78f            // fraction of hexSize inside the border

    // Non-spatial fallback: no faceting possible without dimensions — leave unchanged.
    override fun transformInPlace(rgba: ByteArray) = Unit

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        if (width < 3 || height < 3) return

        val src = ensureScratch(rgba.size)
        rgba.copyInto(src)

        val s         = hexSize
        val invS      = 1f / s
        val innerSq   = (s * innerRatio).let { it * it }

        // Pre-compute coefficients (avoid redundant multiplication in inner loop)
        // Pointy-top axial: q = (sqrt3/3 · x − 1/3 · y) / s
        //                   r = (2/3 · y) / s
        val sqrt3     = 1.73205f
        val sqrt3_3   = 0.57735f   // √3 / 3
        val inv3      = 0.33333f   // 1 / 3
        val two3      = 0.66667f   // 2 / 3
        val sqrt3_h   = 0.86603f   // √3 / 2

        for (py in 0 until height) {
            val pyf = py.toFloat()

            for (px in 0 until width) {
                val pxf = px.toFloat()

                // Fractional axial coordinates
                val fq = (sqrt3_3 * pxf - inv3 * pyf) * invS
                val fr = two3 * pyf * invS
                val fc = -fq - fr

                // Cube-round
                var qi = Math.round(fq)
                var ri = Math.round(fr)
                var ci = Math.round(fc)

                val dq = abs(qi - fq)
                val dr = abs(ri - fr)
                val dc = abs(ci - fc)

                // Fix the largest-error coordinate to restore q+r+c = 0
                when {
                    dq > dr && dq > dc -> qi = -ri - ci
                    dr > dc            -> ri = -qi - ci
                    // else: ci is already correct; axial only uses qi, ri
                }

                // Hex centre in pixel space
                val cx = s * (sqrt3 * qi + sqrt3_h * ri)
                val cy = s * 1.5f * ri

                val dx = pxf - cx
                val dy = pyf - cy
                val distSq = dx * dx + dy * dy

                val dstI = (py * width + px) * 4

                if (distSq > innerSq) {
                    rgba[dstI    ] = 0
                    rgba[dstI + 1] = 0
                    rgba[dstI + 2] = 0
                    rgba[dstI + 3] = -1  // 0xFF opaque
                } else {
                    val cxi  = cx.toInt().coerceIn(0, width  - 1)
                    val cyi  = cy.toInt().coerceIn(0, height - 1)
                    val srcI = (cyi * width + cxi) * 4
                    rgba[dstI    ] = src[srcI    ]
                    rgba[dstI + 1] = src[srcI + 1]
                    rgba[dstI + 2] = src[srcI + 2]
                    rgba[dstI + 3] = src[srcI + 3]
                }
            }
        }
    }

    private fun ensureScratch(size: Int): ByteArray {
        val b = scratch
        if (b != null && b.size >= size) return b
        return ByteArray(size).also { scratch = it }
    }
}
