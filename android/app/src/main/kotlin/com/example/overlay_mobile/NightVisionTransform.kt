package com.example.overlay_mobile

/**
 * Phosphor-green night-vision intensifier tube simulation.
 *
 * Three effects are stacked with zero per-frame allocation:
 *
 * 1. Luma amplification via a pre-computed 256-entry LUT:
 *      amplified = min(luma × 4, 210)
 *    Values are capped at 210 to model the phosphor washout / bloom that
 *    occurs when bright light saturates a real image-intensifier tube.
 *
 * 2. Grain (film/phosphor noise) added via a 32-bit XorShift PRNG:
 *      seed ← seed XOR (seed << 13) XOR (seed >> 17) XOR (seed << 5)
 *    The low 5 bits give a bias in [−16, +15] applied to the green channel.
 *    The PRNG seed is a class field; it evolves frame-to-frame, producing
 *    temporally uncorrelated noise without any heap allocation.
 *
 * 3. Output: R = 0, G = amplified + noise, B = 0
 *    The pure-green monochrome output matches the P31/P43 phosphor hue of
 *    AN/PVS-14 and similar military NVGs.
 */
class NightVisionTransform : PixelTransform() {

    // amplifiedLut[y] = min(y * 4, 210)  — computed once at class load
    private val amplifiedLut: IntArray = IntArray(256) { y -> (y * 4).coerceAtMost(210) }

    // XorShift-32 seed — only touched by the capture HandlerThread
    private var seed: Int = System.currentTimeMillis().toInt() or 1  // never 0

    override fun transformInPlace(rgba: ByteArray) {
        val lut  = amplifiedLut
        var s    = seed
        val len  = rgba.size
        var i    = 0
        while (i < len) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF

            val luma      = (r * 299 + g * 587 + b * 114) / 1_000
            val amplified = lut[luma]

            // XorShift-32 (period 2³²−1, no branching, no allocation)
            s = s xor (s shl  13)
            s = s xor (s ushr 17)
            s = s xor (s shl   5)
            val noise = (s and 0x1F) - 16         // −16 … +15

            val green = (amplified + noise).coerceIn(0, 210)

            rgba[i    ] = 0
            rgba[i + 1] = green.toByte()
            rgba[i + 2] = 0
            i += 4
        }
        seed = s
    }
}
