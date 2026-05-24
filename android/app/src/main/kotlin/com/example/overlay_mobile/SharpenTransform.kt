package com.example.overlay_mobile

/**
 * Laplacian-sharpening secondary post-process effect.
 *
 * Applies the 5-tap sharpening kernel to each interior pixel:
 *
 *     [ 0  −1   0 ]
 *     [−1  +5  −1 ]
 *     [ 0  −1   0 ]
 *
 * Equivalent to: output = 5·centre − north − south − west − east.
 * Result is clamped to [0, 255].
 *
 * A scratch copy of the input is made once before the loop so reads and
 * writes do not alias.  Border pixels are left unchanged.
 */
class SharpenTransform : PixelTransform() {

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
                val pN  = (rN + x    ) * 4
                val pW  = (rC + x - 1) * 4
                val pC  = (rC + x    ) * 4
                val pE  = (rC + x + 1) * 4
                val pS  = (rS + x    ) * 4

                rgba[pC    ] = (5 * src[pC    ].u() - src[pN    ].u() - src[pS    ].u() -
                                src[pW    ].u() - src[pE    ].u()).coerceIn(0, 255).toByte()

                rgba[pC + 1] = (5 * src[pC + 1].u() - src[pN + 1].u() - src[pS + 1].u() -
                                src[pW + 1].u() - src[pE + 1].u()).coerceIn(0, 255).toByte()

                rgba[pC + 2] = (5 * src[pC + 2].u() - src[pN + 2].u() - src[pS + 2].u() -
                                src[pW + 2].u() - src[pE + 2].u()).coerceIn(0, 255).toByte()
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
