package com.example.overlay_mobile

/**
 * Near-infrared camera simulation.
 *
 * In real NIR photography the spectral sensitivity is inverted relative to
 * human vision: materials that strongly absorb visible light (dark bark, water)
 * appear dark, while materials that reflect NIR heavily (chlorophyll, skin)
 * appear bright.  The overall cast is warm red-orange.
 *
 * Pipeline per pixel (zero allocation):
 *   1. Compute perceptual luma Y (Rec.601 integer coefficients).
 *   2. Invert: ir = 255 − Y  →  "hot" surfaces that absorbed visible light
 *      become bright in the IR image.
 *   3. Map through a warm-cast LUT:
 *        R′ = ir × 1.297  (clamped at 255) — dominant warm channel
 *        G′ = ir × 0.949
 *        B′ = ir × 0.602  — cool channel suppressed
 *
 * Integer approximation using multiply + unsigned-right-shift (no division):
 *   R′ = (ir × 332) ushr 8   ≈ ir × 1.297   (332 / 256 = 1.297)
 *   G′ = (ir × 243) ushr 8   ≈ ir × 0.949   (243 / 256 = 0.949)
 *   B′ = (ir × 154) ushr 8   ≈ ir × 0.602   (154 / 256 = 0.602)
 * R′ requires a final coerceAtMost(255) since 255 × 332 ushr 8 = 329.
 */
class InfraredTransform : PixelTransform() {

    override fun transformInPlace(rgba: ByteArray) {
        val len = rgba.size
        var i   = 0
        while (i < len) {
            val r  = rgba[i    ].toInt() and 0xFF
            val g  = rgba[i + 1].toInt() and 0xFF
            val b  = rgba[i + 2].toInt() and 0xFF
            val y  = (r * 299 + g * 587 + b * 114) / 1_000
            val ir = 255 - y
            rgba[i    ] = ((ir * 332) ushr 8).coerceAtMost(255).toByte()
            rgba[i + 1] = ((ir * 243) ushr 8).toByte()
            rgba[i + 2] = ((ir * 154) ushr 8).toByte()
            i += 4
        }
    }
}
