package com.example.overlay_mobile

/**
 * Pit-viper / thermal-camera simulation using an "iron" heat palette LUT.
 *
 * Pipeline (per pixel, zero allocation after construction):
 *   1. Convert RGBA to perceptual luma Y (integer Rec.601 coefficients).
 *   2. Index the pre-computed 256-entry palette and write three bytes.
 *
 * The palette maps Y = 0 (cold) → Y = 255 (hot):
 *   black → deep violet → red → orange → yellow → pale yellow → white
 *
 * The LUT is 768 bytes (256 × 3) computed once in the companion init.
 * Hot-loop cost: 1 divide + 3 array reads + 3 array writes per pixel.
 */
class ThermalTransform : PixelTransform() {

    // 256 × 3 = 768 bytes: [R₀, G₀, B₀, R₁, G₁, B₁, …]
    private val lut: ByteArray = ByteArray(768).also { buildIronPalette(it) }

    override fun transformInPlace(rgba: ByteArray) {
        val l   = lut
        val len = rgba.size
        var i   = 0
        while (i < len) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            // Rec.601 luma (integer, no float division)
            val y    = (r * 299 + g * 587 + b * 114) / 1_000
            val base = y * 3
            rgba[i    ] = l[base    ]
            rgba[i + 1] = l[base + 1]
            rgba[i + 2] = l[base + 2]
            i += 4
        }
    }

    companion object {
        // Piecewise-linear "iron" palette: (luminance, R, G, B) key points.
        private val STOPS = arrayOf(
            intArrayOf(  0,   0,   0,   0),   // black
            intArrayOf( 43,  64,   0, 128),   // deep violet
            intArrayOf( 85, 255,   0,  64),   // crimson
            intArrayOf(128, 255, 128,   0),   // orange
            intArrayOf(171, 255, 255,   0),   // yellow
            intArrayOf(213, 255, 255, 160),   // pale yellow
            intArrayOf(255, 255, 255, 255),   // white
        )

        fun buildIronPalette(lut: ByteArray) {
            val stops = STOPS
            for (y in 0..255) {
                var s = 0
                while (s < stops.size - 2 && stops[s + 1][0] <= y) s++
                val y0 = stops[s][0]
                val y1 = stops[s + 1][0]
                val t  = if (y1 == y0) 0f else (y - y0).toFloat() / (y1 - y0)
                val base = y * 3
                lut[base    ] = lerp(stops[s][1], stops[s + 1][1], t)
                lut[base + 1] = lerp(stops[s][2], stops[s + 1][2], t)
                lut[base + 2] = lerp(stops[s][3], stops[s + 1][3], t)
            }
        }

        private fun lerp(a: Int, b: Int, t: Float): Byte =
            (a + (b - a) * t).toInt().coerceIn(0, 255).toByte()
    }
}
