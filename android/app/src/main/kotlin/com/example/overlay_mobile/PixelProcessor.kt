package com.example.overlay_mobile

/**
 * Process-wide pixel-processing state: the active primary transform.
 *
 * The capture thread calls [processInPlace] once per acquired frame.
 * The UI thread calls [setTransform] when the user switches modes.
 *
 * Thread safety: [activeTransform] is @Volatile — a single reference write is
 * atomic on the JVM.  The worst case is one frame rendered with the previous
 * transform, which is imperceptible.
 */
object PixelProcessor {

    @Volatile
    var activeTransform: PixelTransform = IdentityTransform
        private set

    /** Called exclusively from the ImageReader's [HandlerThread]. */
    fun processInPlace(rgba: ByteArray, width: Int, height: Int) {
        activeTransform.transformInPlace(rgba, width, height)
    }

    /** Atomically swaps the active transform. Safe to call from any thread. */
    fun setTransform(transform: PixelTransform) {
        activeTransform = transform
    }
}
