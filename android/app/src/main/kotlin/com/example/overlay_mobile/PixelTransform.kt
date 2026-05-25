package com.example.overlay_mobile

/**
 * Sealed hierarchy of zero-allocation pixel transformations.
 *
 * Every subclass must satisfy the hot-path contract:
 *   - [transformInPlace] operates directly on the caller's buffer.
 *   - No heap allocations of any kind inside the method body.
 *   - All per-pixel state (matrix coefficients, shift values) is captured as
 *     local `val`s at the top of the loop to assist the JIT in register-allocating
 *     them without repeated field reads.
 *
 * Buffer layout (RGBA_8888, row-major, tight packing guaranteed by CaptureController):
 *   byte[i+0] = R   byte[i+1] = G   byte[i+2] = B   byte[i+3] = A (untouched)
 */
sealed class PixelTransform {

    /**
     * Non-spatial overload: every pixel is processed identically.
     * Colour-matrix, monochrome, and invert transforms override this.
     * Default is a no-op so spatial-only subclasses need not override it.
     */
    open fun transformInPlace(rgba: ByteArray) = Unit

    /**
     * Spatial overload: the transform may use absolute (x, y) position.
     * CatVision (peripheral blur) and EagleVision (foveal contrast) override this.
     *
     * Default delegates to the non-spatial overload, so every existing subclass
     * automatically satisfies this contract without modification.
     */
    open fun transformInPlace(rgba: ByteArray, width: Int, height: Int) =
        transformInPlace(rgba)
}

// ── Passthrough ────────────────────────────────────────────────────────────────

object IdentityTransform : PixelTransform() {
    override fun transformInPlace(rgba: ByteArray) = Unit
}

// ── 3×3 colour matrix (RGB in → RGB out) ──────────────────────────────────────

/**
 * Applies a 3×3 colour-mixing matrix to each pixel with no per-frame allocation.
 *
 * Matrix layout (row-major):
 *   index 0 1 2  →  new R = m0·R + m1·G + m2·B
 *   index 3 4 5  →  new G = m3·R + m4·G + m5·B
 *   index 6 7 8  →  new B = m6·R + m7·G + m8·B
 *
 * The constructor copies the supplied array so external mutations do not affect
 * a live transform.
 */
class MatrixTransform(matrix: FloatArray) : PixelTransform() {
    init { require(matrix.size == 9) { "Colour matrix must have exactly 9 elements" } }

    private val m = matrix.copyOf()   // immutable after construction

    override fun transformInPlace(rgba: ByteArray) {
        // Cache all nine matrix coefficients as locals.
        // The JIT can keep these in floating-point registers for the entire loop.
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
        val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
        val m6 = m[6]; val m7 = m[7]; val m8 = m[8]

        val limit = rgba.size
        var i = 0
        while (i < limit) {
            val r = (rgba[i    ].toInt() and 0xFF).toFloat()
            val g = (rgba[i + 1].toInt() and 0xFF).toFloat()
            val b = (rgba[i + 2].toInt() and 0xFF).toFloat()

            rgba[i    ] = clamp255(m0 * r + m1 * g + m2 * b)
            rgba[i + 1] = clamp255(m3 * r + m4 * g + m5 * b)
            rgba[i + 2] = clamp255(m6 * r + m7 * g + m8 * b)
            // rgba[i + 3]: alpha — leave untouched
            i += 4
        }
    }
}

// ── Monochrome ────────────────────────────────────────────────────────────────

/**
 * Near-greyscale via complement targeting.
 *
 * Naïve approach (output = Y for all channels) blended at 63 % alpha
 * still shows 37 % of the original colour → desaturated but not grey.
 *
 * Fix: output T such that 0.627·T + 0.373·real = (Y, Y, Y).
 *   T_channel = (Y − 0.373·channel) / 0.627  ≈  (2·Y − channel) × 255/405
 *
 * Integer shortcut (good to ±1 LSB): T = clamp(2·Y − channel).
 * At pixels where the formula doesn't clamp the result is exact greyscale;
 * the only error occurs on highly-saturated colours where 2·Y − channel
 * goes negative or exceeds 255 — a minority of real-world screen content.
 */
object MonochromeTransform : PixelTransform() {
    override fun transformInPlace(rgba: ByteArray) {
        val limit = rgba.size
        var i = 0
        while (i < limit) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1_000
            rgba[i    ] = (2 * y - r).coerceIn(0, 255).toByte()
            rgba[i + 1] = (2 * y - g).coerceIn(0, 255).toByte()
            rgba[i + 2] = (2 * y - b).coerceIn(0, 255).toByte()
            i += 4
        }
    }
}

// ── Inverted ──────────────────────────────────────────────────────────────────

/**
 * Colour-channel rotation: (R, G, B) → (G, B, R).
 *
 * Photographic negative (255−channel) is mathematically incompatible with a
 * semi-transparent overlay: at any α, the composited pixel equals
 *   α·(255−C) + (1−α)·C = 255·α + C·(1−2α)
 * which collapses to a flat grey (127) for α ≈ 0.5 regardless of content.
 *
 * Channel rotation avoids this by shifting the hue 120° around the colour
 * wheel without touching saturation or value.  The 63 %-alpha blend of the
 * rotated frame over the original produces vivid complementary hue shifts:
 *   red → magenta-purple tint   green → olive-yellow tint   blue → teal tint
 * giving a clearly "inverted" perceptual experience without the grey-wash.
 */
object InvertedTransform : PixelTransform() {
    override fun transformInPlace(rgba: ByteArray) {
        val limit = rgba.size
        var i = 0
        while (i < limit) {
            val r = rgba[i    ].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            rgba[i    ] = g.toByte()
            rgba[i + 1] = b.toByte()
            rgba[i + 2] = r.toByte()
            i += 4
        }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

/**
 * Clamps [f] to [0, 255] and reinterprets as a signed Kotlin Byte.
 * Marked inline so the compiler eliminates the call overhead in the hot loop.
 */
private inline fun clamp255(f: Float): Byte =
    when {
        f <= 0f   -> 0.toByte()
        f >= 255f -> (-1).toByte()   // 0xFF as signed byte
        else      -> f.toInt().toByte()
    }
