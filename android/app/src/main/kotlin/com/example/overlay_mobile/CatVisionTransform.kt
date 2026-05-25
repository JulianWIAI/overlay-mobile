package com.example.overlay_mobile

/**
 * Cat dichromatic vision with peripheral box blur.
 *
 * Cats are dichromats (S-cone ≈ 450 nm, L-cone ≈ 555 nm) with a horizontal
 * streak fovea giving sharp central vision and coarser peripheral resolution.
 *
 * Pipeline (per frame, zero extra heap allocations after warm-up):
 *   1. Apply the dichromatic 3×3 colour matrix to every pixel.
 *   2. Copy the colour-corrected frame into a pre-allocated scratch buffer
 *      (allocated lazily on the first frame, then reused indefinitely).
 *   3. Walk every non-border pixel; skip pixels inside the sharp inner circle.
 *      For peripheral pixels, compute a 3×3 box-blur average from the scratch
 *      copy and blend it with the colour-corrected pixel by a squared-distance
 *      factor (avoids sqrt(), gives a smooth visual transition).
 *
 * Performance (720 p / 30 FPS):
 *   Step 1 → ~920 k pixels × 9 MADs = fast matrix path.
 *   Step 3 → ~645 k peripheral pixels × 9 byte reads + 3 blends.
 *   Total throughput ~700 MB/s — borderline on low-end SoCs; lower targetFps
 *   to 15 if you observe thermal throttling.
 */
class CatVisionTransform : PixelTransform() {

    // Colour matrix embedded here so the scratch-buffer lifetime matches
    // the transform instance, not a separate MatrixTransform singleton.
    //
    // Distinct from DOG: identical R'/G' rows (dichromat), but the B' row
    // carries much stronger S-cone / UV sensitivity (cats perceive some UV
    // up to ~380 nm).  Row sums = 1.0; ρ(M) ≤ 1.0; stable at any α < 1.
    private val matrixTransform = MatrixTransform(
        floatArrayOf(
            0.120f, 0.760f, 0.120f,   // R' — yellow axis (low R = reds dim)
            0.120f, 0.760f, 0.120f,   // G' — identical → R/G indistinguishable
            0.000f, 0.050f, 0.950f,   // B' — dominant S-cone/UV (cats see near-UV vividly)
        )
    )

    // Scratch buffer: one lazy allocation per CaptureController session.
    // Never overwritten while the blur loop reads from it because the loop
    // reads from `src` and writes to `rgba` (two separate arrays).
    @Volatile private var scratch: ByteArray? = null

    // ── Non-spatial fallback (no blur) ────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray) =
        matrixTransform.transformInPlace(rgba)

    // ── Spatial path (blur applied when dimensions are known) ─────────────────

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        matrixTransform.transformInPlace(rgba)

        if (width < 3 || height < 3) return

        val src = ensureScratch(rgba.size).also { rgba.copyInto(it) }

        val cx      = width  * 0.5f
        val cy      = height * 0.5f
        val minHalf = minOf(cx, cy)

        // Inner circle: fully sharp (no blur)
        val innerRSq = (minHalf * 0.28f).let { it * it }
        // Outer circle: fully blurred beyond this; linear blend in the annulus
        val outerRSq = (minHalf * 0.58f).let { it * it }
        val blendDen = outerRSq - innerRSq    // pre-divided per-pixel

        for (y in 1 until height - 1) {
            val dy   = y - cy
            val dySq = dy * dy

            for (x in 1 until width - 1) {
                val dx     = x - cx
                val distSq = dx * dx + dySq

                if (distSq <= innerRSq) continue    // sharp centre: skip entirely

                // ── 3×3 box-blur sum from the scratch (colour-corrected) copy ──
                //
                // Row byte offsets (4 bytes/pixel, 3 adjacent pixels per row):
                val t0 = ((y - 1) * width + (x - 1)) * 4   // top-left
                val m0 = ( y      * width + (x - 1)) * 4   // mid-left
                val b0 = ((y + 1) * width + (x - 1)) * 4   // bot-left

                val blurR = (src[t0    ].u() + src[t0 + 4].u() + src[t0 + 8].u() +
                             src[m0    ].u() + src[m0 + 4].u() + src[m0 + 8].u() +
                             src[b0    ].u() + src[b0 + 4].u() + src[b0 + 8].u()) / 9

                val blurG = (src[t0 + 1].u() + src[t0 + 5].u() + src[t0 + 9 ].u() +
                             src[m0 + 1].u() + src[m0 + 5].u() + src[m0 + 9 ].u() +
                             src[b0 + 1].u() + src[b0 + 5].u() + src[b0 + 9 ].u()) / 9

                val blurB = (src[t0 + 2].u() + src[t0 + 6].u() + src[t0 + 10].u() +
                             src[m0 + 2].u() + src[m0 + 6].u() + src[m0 + 10].u() +
                             src[b0 + 2].u() + src[b0 + 6].u() + src[b0 + 10].u()) / 9

                val i = (y * width + x) * 4

                if (distSq >= outerRSq) {
                    // Fully in the blur zone: replace with box-blur result.
                    rgba[i    ] = blurR.toByte()
                    rgba[i + 1] = blurG.toByte()
                    rgba[i + 2] = blurB.toByte()
                } else {
                    // Blend zone (annulus between inner and outer radii).
                    // t ∈ (0, 1) using squared-distance ratio (no sqrt needed).
                    val t      = (distSq - innerRSq) / blendDen
                    val origR  = rgba[i    ].u()
                    val origG  = rgba[i + 1].u()
                    val origB  = rgba[i + 2].u()
                    rgba[i    ] = (origR + (blurR - origR) * t).toInt().toByte()
                    rgba[i + 1] = (origG + (blurG - origG) * t).toInt().toByte()
                    rgba[i + 2] = (origB + (blurB - origB) * t).toInt().toByte()
                }
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private inline fun Byte.u(): Int = toInt() and 0xFF   // unsigned byte → Int

    private fun ensureScratch(size: Int): ByteArray {
        val s = scratch
        if (s != null && s.size >= size) return s
        return ByteArray(size).also { scratch = it }
    }
}
