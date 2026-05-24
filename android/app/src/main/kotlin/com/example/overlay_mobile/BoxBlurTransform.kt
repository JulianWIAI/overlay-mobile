package com.example.overlay_mobile

/**
 * 3 × 3 box-blur secondary post-process effect.
 *
 * Each output pixel is the unweighted mean of its 3 × 3 neighbourhood.
 * Border pixels (top/bottom row, left/right column) are left unchanged
 * to avoid per-pixel boundary checks in the hot loop.
 *
 * The source pixels are read from a pre-allocated scratch copy of the
 * input so writes do not corrupt reads within the same pass.
 *
 * Cache behaviour: for each row of width W, the loop reads from three
 * consecutive rows (north, centre, south).  Each row is W × 4 bytes
 * (≈ 2.9 KB at 720 px).  Three rows = ≈ 8.6 KB — well within a 32 KB
 * L1 data cache, so all nine neighbourhood reads are cache-warm.
 */
class BoxBlurTransform : PixelTransform() {

    @Volatile private var scratch: ByteArray? = null

    override fun transformInPlace(rgba: ByteArray) = Unit   // spatial only

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        if (width < 3 || height < 3) return

        val src = ensureScratch(rgba.size)
        rgba.copyInto(src)

        for (y in 1 until height - 1) {
            val rN = (y - 1) * width
            val rC =  y      * width
            val rS = (y + 1) * width

            for (x in 1 until width - 1) {
                val p0 = (rN + x - 1) * 4;  val p1 = (rN + x) * 4;  val p2 = (rN + x + 1) * 4
                val p3 = (rC + x - 1) * 4;  val p4 = (rC + x) * 4;  val p5 = (rC + x + 1) * 4
                val p6 = (rS + x - 1) * 4;  val p7 = (rS + x) * 4;  val p8 = (rS + x + 1) * 4

                rgba[p4    ] = ((src[p0    ].u() + src[p1    ].u() + src[p2    ].u() +
                                 src[p3    ].u() + src[p4    ].u() + src[p5    ].u() +
                                 src[p6    ].u() + src[p7    ].u() + src[p8    ].u()) / 9).toByte()

                rgba[p4 + 1] = ((src[p0 + 1].u() + src[p1 + 1].u() + src[p2 + 1].u() +
                                 src[p3 + 1].u() + src[p4 + 1].u() + src[p5 + 1].u() +
                                 src[p6 + 1].u() + src[p7 + 1].u() + src[p8 + 1].u()) / 9).toByte()

                rgba[p4 + 2] = ((src[p0 + 2].u() + src[p1 + 2].u() + src[p2 + 2].u() +
                                 src[p3 + 2].u() + src[p4 + 2].u() + src[p5 + 2].u() +
                                 src[p6 + 2].u() + src[p7 + 2].u() + src[p8 + 2].u()) / 9).toByte()
            }
        }
    }

    private fun ensureScratch(size: Int): ByteArray {
        val b = scratch
        if (b != null && b.size >= size) return b
        return ByteArray(size).also { scratch = it }
    }

    private fun Byte.u() = toInt() and 0xFF
}
