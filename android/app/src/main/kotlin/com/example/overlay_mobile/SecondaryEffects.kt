package com.example.overlay_mobile

/**
 * Catalogue of optional secondary post-process transforms.
 *
 * A secondary transform is applied AFTER the primary vision-mode transform
 * inside [PixelProcessor.processInPlace].  It is independent of the primary
 * mode — any secondary effect can be combined with any primary mode.
 *
 * All instances are allocated once at class-load time and reused.
 * Switching between effects is a single volatile write in [PixelProcessor].
 */
object SecondaryEffects {

    const val NONE            = 0
    const val BLUR            = 1
    const val SHARPEN         = 2
    const val MATRIX_ANALYZER = 3

    private val blur     = BoxBlurTransform()
    private val sharpen  = SharpenTransform()
    private val analyzer = MatrixAnalyzerTransform()

    fun forId(id: Int): PixelTransform? = when (id) {
        BLUR            -> blur
        SHARPEN         -> sharpen
        MATRIX_ANALYZER -> analyzer
        else            -> null
    }
}
