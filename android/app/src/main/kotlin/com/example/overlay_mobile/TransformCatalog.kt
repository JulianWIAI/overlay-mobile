package com.example.overlay_mobile

/**
 * Immutable registry of every supported [PixelTransform].
 *
 * All transform instances are allocated exactly once at class-load time and
 * never recreated — the hot swap in [PixelProcessor] is a single volatile
 * reference write.
 *
 * Mode-ID integers are the sole coupling point between Kotlin and Dart.
 * The [VisionMode] sealed class in Dart mirrors these constants 1-to-1.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Matrix sources
 * ─────────────────────────────────────────────────────────────────────────────
 *  Animal matrices — approximations from published cone-sensitivity data:
 *    Jacobs (1993) "The distribution and nature of colour vision among mammals"
 *    Neitz & Neitz (2011) "The genetics of normal and defective color vision"
 *
 *  Bee UV mapping — Chittka & Menzel (1992) "The evolutionary adaptation of
 *    flower colours and the insect pollinators' colour vision"
 *
 *  Human dichromacy — Viénot, Mollon & Brettel (1999) "Digital video colourmaps
 *    for checking the legibility of displays by dichromats"
 *    Applied in gamma-encoded sRGB (linear-space gamma costs ~2× with imperceptible
 *    accuracy gain at real-time overlay frame rates).
 * ─────────────────────────────────────────────────────────────────────────────
 */
object TransformCatalog {

    // ── Mode IDs ──────────────────────────────────────────────────────────────
    // These must stay in sync with VisionMode.id values in vision_mode.dart.

    const val NORMAL       = 0
    const val DOG          = 1
    const val CAT          = 2    // spatial: dichromatic + peripheral blur
    const val BULL         = 3    // pure blue-yellow axis dichromacy
    const val PROTANOPIA   = 4    // Viénot 1999
    const val DEUTERANOPIA = 5    // Viénot 1999
    const val TRITANOPIA   = 6    // Viénot 1999
    const val MONOCHROME   = 7
    const val INVERTED     = 8
    const val BEE          = 9    // UV-shifted trichromacy
    const val FROG         = 10   // cold-hue high contrast
    const val EAGLE        = 11   // spatial: foveal contrast + UV bias
    const val THERMAL      = 12   // iron-palette heat map
    const val NIGHT_VISION = 13   // phosphor-green intensifier tube
    const val ECHOLOCATION = 14   // spatial: Sobel edge → blue-cyan gradient
    const val INFRARED     = 16   // near-IR warm inverted
    const val ULTRAVIOLET  = 17   // spectrum-shifted UV trichromacy
    const val HIGH_CONTRAST = 18  // spatial: 4×4 tile CLAHE

    // ── Animal dichromacy matrices ─────────────────────────────────────────────
    //
    // Design principle: every matrix is row-stochastic (row sums = 1.0) so the
    // spectral radius ρ(M) ≤ 1.0 and the feedback loop is stable at any α < 1.
    // With OVERLAY_ALPHA = 160/255 ≈ 0.627, α × ρ ≤ 0.627 < 1 for all modes.
    //
    // Visual lever: identical R' and G' rows → complete red/green confusion.
    // Low R weight in those rows → red objects appear noticeably dim/dark, which
    // is the single most recognisable feature of dichromatic animal vision.

    // Dog: S-cone (~440 nm) + L-cone (~560 nm).
    // Reds contribute almost nothing → appear dark brownish at 63 % blend.
    // Blues are vivid (S-cone dominant); greens appear yellowish.
    // Identical R'/G' rows = zero red/green discrimination.
    private val DOG_MATRIX = floatArrayOf(
        0.150f, 0.760f, 0.090f,   // R' = mostly G + tiny B  (yellow-blue axis)
        0.150f, 0.760f, 0.090f,   // G' = identical → no R/G discrimination
        0.020f, 0.210f, 0.770f,   // B' = strong S-cone (blue-violet dominance)
    )

    // Cat dichromatic matrix is embedded in CatVisionTransform (spatial transform).

    // Bull: extreme dichromacy — red objects nearly vanish.
    // Bulls are dichromats; this simulation is intentionally more extreme than
    // Dog to illustrate the "bull ignores the red cape" effect clearly.
    // Red (255,0,0) → near-black; greens → vivid yellow.
    private val BULL_MATRIX = floatArrayOf(
        0.020f, 0.960f, 0.020f,   // R' ≈ pure G  — red input nearly zeroed
        0.020f, 0.960f, 0.020f,   // G' = identical → total R/G confusion
        0.000f, 0.080f, 0.920f,   // B' = almost pure blue
    )

    // ── Ultraviolet spectrum-shifted matrix ───────────────────────────────────
    //
    // UV-sensitive creatures (butterflies, birds) perceive a world where human
    // red is invisible and deep UV appears as a distinct vivid colour.
    // Spectrum shifted one band toward shorter wavelengths:
    //   human green → displayed as red (creature's long-wave cone)
    //   human blue  → displayed as green (creature's medium-wave cone)
    //   UV proxy    → amplified blue + faint red bleed → violet cast
    // Spectral radius ≈ 1.52; at α = 0.627: 0.627 × 1.52 = 0.953 < 1.
    private val UV_MATRIX = floatArrayOf(
        0.000f, 1.000f, 0.000f,   // R' ← visible green (long-wave cone)
        0.000f, 0.000f, 1.000f,   // G' ← visible blue  (medium-wave cone)
        0.300f, 0.000f, 1.400f,   // B' ← UV proxy: amplified blue + red bleed → violet
    )

    // ── Bee UV-shifted trichromacy ─────────────────────────────────────────────
    //
    // Bees have three cone types peaking at ≈350 nm (UV), ≈440 nm (blue),
    // ≈540 nm (green).  Human red (~700 nm) is completely invisible to bees.
    //
    // Channel mapping (display proxy):
    //   Bee L-cone (green) → R channel  — greens appear warm/orange
    //   Bee M-cone (blue)  → G channel  — blues appear as bee's mid-colour
    //   UV S-cone proxy    → B channel  — encoded as blue + tiny R bleed → violet
    //
    // Previous matrix used B coefficient 1.6 → blue channel saturated every
    // frame → solid blue cast.  All coefficients now ≤ 1.0; row sums = 1.0.
    private val BEE_MATRIX = floatArrayOf(
        0.000f, 1.000f, 0.000f,   // R' ← bee L-cone: only green visible (human red absent)
        0.000f, 0.000f, 1.000f,   // G' ← bee M-cone: blue band
        0.200f, 0.100f, 0.700f,   // B' ← UV S-cone proxy: blue dominant + R/G bleed → indigo
    )

    // ── Frog tetrachromat — vivid green world ─────────────────────────────────
    //
    // Frogs are tetrachromats (UV, S, M, L cones) with heavy rod gain for dim
    // conditions.  Their spectral sensitivity peaks in the blue-green band
    // (430–560 nm) and they live in a world dominated by aquatic vegetation.
    //
    // Simulation: green channel heavily amplified (vivid pond/leaf colours),
    // red strongly suppressed (warm tones appear dim), blue elevated (S+UV
    // cones contribute to cool hues).  All coefficients ≤ 1.0; row sums = 1.0.
    // Previous FROG had B coefficient 1.6 → blue saturation; fixed here.
    private val FROG_MATRIX = floatArrayOf(
        0.100f, 0.800f, 0.100f,   // R' = green dominant (warm tones muted)
        0.050f, 0.900f, 0.050f,   // G' = strongly green (vegetation vivid)
        0.000f, 0.400f, 0.600f,   // B' = blue/UV elevated (S + UV cones)
    )

    // ── Human dichromacy matrices ─────────────────────────────────────────────
    //
    // Amplified beyond Viénot 1999 values to remain perceptually distinct at
    // 63 % alpha blend.  The core structure (which rows are identical, which
    // channels are suppressed) faithfully represents each condition.

    // Protanopia (L-cone absent): red wavelengths have reduced luminance.
    // Red objects appear noticeably DARK — near-invisible at full saturation.
    // Identical R'/G' rows = L-cone confusion (can't distinguish L from M).
    private val PROTANOPIA_MATRIX = floatArrayOf(
        0.070f, 0.930f, 0.000f,   // R' = heavy G bias  — reds appear very dark
        0.070f, 0.930f, 0.000f,   // G' = identical     — R and G indistinguishable
        0.000f, 0.280f, 0.720f,   // B' = blue mostly preserved
    )

    // Deuteranopia (M-cone absent): red/green confusion at equal luminance.
    // Unlike protanopia, reds are NOT dark — they appear as brownish-orange
    // (same luminance as greens of equal energy).  Both look similarly yellowish.
    private val DEUTERANOPIA_MATRIX = floatArrayOf(
        0.700f, 0.300f, 0.000f,   // R' = R dominant  — reds stay bright (orange)
        0.700f, 0.300f, 0.000f,   // G' = identical   — greens also look orange
        0.000f, 0.250f, 0.750f,   // B' = blue preserved
    )

    // Tritanopia (S-cone absent): blue/green confusion.
    // S-cones peak at ~420-450 nm (blue-violet); without them, blue wavelengths
    // are perceived only through M-cone overlap → blue objects appear greenish/teal.
    //
    // Matrix design:
    //   G' takes 60 % from blue input → blue pixels shift strongly into green channel.
    //   B' takes only 5 % from blue input → blue objects have low B output.
    //   Net effect: blue → TEAL; sky appears greenish; green barely changes.
    // For sky-blue (100,149,237) at α=0.502: result ≈ (101,175,195) — clearly teal.
    private val TRITANOPIA_MATRIX = floatArrayOf(
        0.950f, 0.050f, 0.000f,   // R' = near-normal red
        0.000f, 0.400f, 0.600f,   // G' = 0.4G + 0.6B  — blue strongly cross-couples to green
        0.000f, 0.950f, 0.050f,   // B' = 0.95G + 0.05B — blue no longer dominates blue output
    )

    // ── Registry ───────────────────────────────────────────────────────────────

    private val registry: Map<Int, PixelTransform> = mapOf(
        NORMAL        to IdentityTransform,
        DOG           to MatrixTransform(DOG_MATRIX),
        CAT           to CatVisionTransform(),                       // spatial
        BULL          to MatrixTransform(BULL_MATRIX),
        PROTANOPIA    to MatrixTransform(PROTANOPIA_MATRIX),
        DEUTERANOPIA  to MatrixTransform(DEUTERANOPIA_MATRIX),
        TRITANOPIA    to MatrixTransform(TRITANOPIA_MATRIX),
        MONOCHROME    to MonochromeTransform,
        INVERTED      to InvertedTransform,
        BEE           to MatrixTransform(BEE_MATRIX),
        FROG          to MatrixTransform(FROG_MATRIX),
        EAGLE         to EagleVisionTransform(),                     // spatial
        THERMAL       to ThermalTransform(),
        NIGHT_VISION  to NightVisionTransform(),
        ECHOLOCATION  to EcholocationTransform(),                    // spatial
        INFRARED      to InfraredTransform(),
        ULTRAVIOLET   to MatrixTransform(UV_MATRIX),
        HIGH_CONTRAST to ContrastTransform(),                        // spatial
    )

    fun forId(modeId: Int): PixelTransform? = registry[modeId]

    val allIds: Set<Int> get() = registry.keys

    /**
     * Returns the mode ID whose pre-allocated transform instance is [transform]
     * (identity comparison), or [NORMAL] if not found.
     * Used by [OverlayBubble] to highlight the currently active cell on panel open.
     */
    fun idOf(transform: PixelTransform): Int =
        registry.entries.firstOrNull { it.value === transform }?.key ?: NORMAL
}
