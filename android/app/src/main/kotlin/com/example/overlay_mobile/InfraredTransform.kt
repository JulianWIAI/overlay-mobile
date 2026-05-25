package com.example.overlay_mobile

/**
 * Aerochrome false-colour near-infrared simulation.
 *
 * Real NIR photography (Kodak Aerochrome, drone multispectral imaging):
 *   • Chlorophyll-rich vegetation reflects 800–1000 nm NIR strongly
 *     → appears vivid CRIMSON / MAGENTA in false-colour composites.
 *   • Clear sky and water absorb NIR
 *     → appear deep INDIGO / DARK BLUE.
 *   • Neutral surfaces (concrete, bare soil, skin)
 *     → appear warm AMBER / ORANGE.
 *
 * NIR reflectance proxy (estimated from visible channels, zero allocation):
 *   nir = clamp( G×3/2 + R/3 − B×2/3,  0, 255 )
 *   Green channel dominates (chlorophyll proxy); blue subtracts (sky/water
 *   absorb NIR); red adds warmth (skin, warm soil).
 *
 * Visually distinct from Thermal (which maps LUMINANCE → heat palette).
 * Here REFLECTANCE drives colour: a bright blue sky (high luma, low NIR)
 * appears indigo while dark green vegetation (low luma, high NIR) appears
 * crimson — the opposite of what Thermal would show.
 *
 * Output palette (piecewise linear, three stops):
 *   nir =   0 → (  0,   0, 180)  deep indigo   — NIR-dark (sky / water)
 *   nir = 128 → (200,  40,  60)  crimson        — NIR-mid  (mixed surfaces)
 *   nir = 255 → (255, 160,  20)  amber-orange   — NIR-hot  (vegetation / skin)
 */
class InfraredTransform : PixelTransform() {

    // 256 × 3 bytes: [R₀, G₀, B₀, R₁, G₁, B₁, …]  — built once at construction
    private val lut: ByteArray = ByteArray(768).also { buildPalette(it) }

    override fun transformInPlace(rgba: ByteArray) {
        val l   = lut
        val len = rgba.size
        var i   = 0
        while (i < len) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF

            // NIR reflectance proxy: high on vegetation/warm tones, low on sky/water.
            val nir  = (g * 3 / 2 + r / 3 - b * 2 / 3).coerceIn(0, 255)
            val base = nir * 3
            rgba[i    ] = l[base    ]
            rgba[i + 1] = l[base + 1]
            rgba[i + 2] = l[base + 2]
            i += 4
        }
    }

    companion object {
        // (nir_value, R, G, B) key-point stops for the NIR palette.
        private val STOPS = arrayOf(
            intArrayOf(  0,   0,   0, 180),   // deep indigo — NIR-dark (sky / water)
            intArrayOf(128, 200,  40,  60),   // crimson     — NIR-mid  (mixed surfaces)
            intArrayOf(255, 255, 160,  20),   // amber       — NIR-hot  (vegetation / skin)
        )

        fun buildPalette(lut: ByteArray) {
            val stops = STOPS
            for (nir in 0..255) {
                var s = 0
                while (s < stops.size - 2 && stops[s + 1][0] <= nir) s++
                val n0 = stops[s][0]; val n1 = stops[s + 1][0]
                val t  = if (n1 == n0) 0f else (nir - n0).toFloat() / (n1 - n0)
                val base = nir * 3
                lut[base    ] = lerp(stops[s][1], stops[s + 1][1], t)
                lut[base + 1] = lerp(stops[s][2], stops[s + 1][2], t)
                lut[base + 2] = lerp(stops[s][3], stops[s + 1][3], t)
            }
        }

        private fun lerp(a: Int, b: Int, t: Float): Byte =
            (a + (b - a) * t).toInt().coerceIn(0, 255).toByte()
    }
}
