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

    // Dog: S-cone (~440 nm) + L-cone (~555 nm).  Red and green are conflated;
    // the world maps to yellow and blue-violet bands.
    private val DOG_MATRIX = floatArrayOf(
        0.625f, 0.375f, 0.000f,   // R' = 0.625R + 0.375G  (yellow-ish)
        0.700f, 0.300f, 0.000f,   // G' = 0.700R + 0.300G
        0.000f, 0.300f, 0.700f,   // B' = 0.300G + 0.700B
    )

    // Cat dichromatic matrix is embedded in CatVisionTransform (spatial transform).

    // Bull: pure blue-yellow axis dichromacy.
    // R' = G' = equal mix of input R and G → reds and greens are indistinguishable;
    // both appear as yellow/grey of the same luminance.  Blue channel is preserved
    // to give the blue-yellow perceptual axis.
    private val BULL_MATRIX = floatArrayOf(
        0.500f, 0.500f, 0.000f,   // R' = (R + G) / 2  — no red/green distinction
        0.500f, 0.500f, 0.000f,   // G' = same
        0.000f, 0.000f, 1.000f,   // B' = B              — blue fully preserved
    )

    // ── Ultraviolet spectrum-shifted matrix ───────────────────────────────────
    //
    // Creatures with UV sensitivity (butterflies, birds, bees) perceive a
    // world where human-red is invisible and deep UV appears as a distinct
    // colour.  We simulate this by shifting the perceived spectrum one band
    // toward shorter wavelengths:
    //   human red   → invisible (zeroed)
    //   human green → displayed as red
    //   human blue  → displayed as green
    //   UV proxy    → amplified blue + faint red bleed (violet hue)
    private val UV_MATRIX = floatArrayOf(
        0.000f, 1.000f, 0.000f,   // R' ← visible green (UV creature's long-wave cone)
        0.000f, 0.000f, 1.000f,   // G' ← visible blue  (UV creature's medium-wave cone)
        0.300f, 0.000f, 1.400f,   // B' ← UV proxy: amplified blue + faint red → violet cast
    )

    // ── Bee UV-shifted trichromacy ─────────────────────────────────────────────
    //
    // Bees have three receptor types:
    //   S-cone ≈ 350 nm (UV)       — mapped to our blue slot
    //   M-cone ≈ 440 nm (blue)     — mapped to our green slot
    //   L-cone ≈ 540 nm (green)    — mapped to our red slot
    //
    // Human red (~700 nm) is invisible to bees.  The UV S-cone is approximated
    // by amplifying the blue channel (nearest visible proxy); values above 255
    // are clamped, producing a "blown-out" indigo cast on bright-blue surfaces.
    private val BEE_MATRIX = floatArrayOf(
        0.000f, 0.900f, 0.000f,   // R' ← bee L-cone input (green)
        0.000f, 0.000f, 1.000f,   // G' ← bee M-cone input (blue)
        0.000f, 0.450f, 1.600f,   // B' ← simulated UV S-cone: amplified blue + green spill
    )

    // ── Frog cold-hue high-contrast ───────────────────────────────────────────
    //
    // Frogs are tetrachromats with strong rod-dominated gain in dim light.
    // The simulation emphasises their blue-green spectral bias (peak ~430-520 nm)
    // and suppresses red, while >1.0 coefficients allow clamping to create the
    // over-exposed, high-contrast "rod-bloom" appearance.
    private val FROG_MATRIX = floatArrayOf(
        0.250f, 0.150f, 0.000f,   // R' — strongly suppressed red
        0.000f, 1.300f, 0.100f,   // G' — amplified green + slight blue bleed; clamps on bright greens
        0.000f, 0.500f, 1.600f,   // B' — dominant blue channel; heavy clamp creates contrast bloom
    )

    // ── Human dichromacy matrices (Viénot et al. 1999) ────────────────────────

    private val PROTANOPIA_MATRIX = floatArrayOf(
        0.56667f, 0.43333f, 0.00000f,
        0.55833f, 0.44167f, 0.00000f,
        0.00000f, 0.24167f, 0.75833f,
    )

    private val DEUTERANOPIA_MATRIX = floatArrayOf(
        0.62500f, 0.37500f, 0.00000f,
        0.70000f, 0.30000f, 0.00000f,
        0.00000f, 0.30000f, 0.70000f,
    )

    private val TRITANOPIA_MATRIX = floatArrayOf(
        0.95000f, 0.05000f, 0.00000f,
        0.00000f, 0.43333f, 0.56667f,
        0.00000f, 0.47500f, 0.52500f,
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
