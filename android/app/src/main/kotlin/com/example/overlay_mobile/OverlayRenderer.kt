package com.example.overlay_mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Region
import android.view.Gravity
import android.view.SurfaceView
import android.view.WindowManager

/**
 * Full-screen, click-through overlay rendered onto a hardware-accelerated [SurfaceView].
 *
 * ── Feedback-loop mitigation ────────────────────────────────────────────────────
 *
 *   Each rendered pixel carries alpha = [OVERLAY_ALPHA] (~86 %) instead of 0xFF.
 *   SurfaceFlinger composites the overlay at that opacity; the MediaProjection
 *   VirtualDisplay captures the same composited output (overlay 86 % + real 14 %).
 *
 *   The filter is then applied to that blend.  At steady state the feedback loop
 *   converges: for any linear colour-matrix M the fixed point is
 *       F = (I − α·M)⁻¹ · (1−α) · M · real
 *   For α = 0 (fully transparent) this equals M·real exactly; at α = 0.86 the
 *   result is a close approximation that is visually indistinguishable for all
 *   animal/dichromacy matrices.
 *
 * ── Touch pass-through strategy ────────────────────────────────────────────────
 *
 *   Three independent layers to ensure zero touch interception on all firmware:
 *
 *   1. FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL
 *      Standard Android flags; honoured by stock InputDispatcher.
 *
 *   2. touchableRegion = Region() (empty) via reflection
 *      Explicitly declares zero touch surface area to the input policy layer.
 *      Huawei CTAIFS reads the declared touchable region when deciding whether
 *      to engage system-wide touch suppression; an empty region signals that
 *      this overlay claims no pointer events at all.
 *
 *   3. INPUT_FEATURE_NO_INPUT_CHANNEL via reflection (fallback)
 *      Removes the input channel so the InputDispatcher has no delivery path
 *      regardless of vendor InputDispatcher modifications.
 */
class OverlayRenderer(private val context: Context) {

    companion object {
        /** Pixel alpha for every rendered frame: 128/255 = 50 % opaque.
         *
         *  Stability: feedback loop converges iff α × ρ(M) < 1.
         *    All redesigned matrices are row-stochastic → ρ ≤ 1.0 → 0.502 < 1.
         *    UV spectral radius ≈ 1.52 → 0.502 × 1.52 = 0.763 < 1.
         *    Eagle centre boost max 1.50 → 0.502 × 1.50 = 0.753 < 1.
         *
         *  Ghost decay: moving objects leave a temporal trail that fades as
         *    α^n per frame.  At α = 0.502: <5 % after 4 frames (133 ms at 30 fps).
         *    Higher α amplifies this artifact — do not raise above 160 without
         *    verifying ghost persistence on fast-motion content (video, swipe). */
        private const val OVERLAY_ALPHA = 128
    }

    @Volatile private var surfaceView: SurfaceView? = null
    private var windowManager: WindowManager? = null

    private var statusBarH: Int = 0
    private var navBarH:    Int = 0
    private var screenH:    Int = 0

    private var bitmap:     Bitmap?   = null
    private var argbPixels: IntArray? = null
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var dstW    = 0
    private var dstH    = 0

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun show() {
        val wm = context.getSystemService(WindowManager::class.java)
        windowManager = wm

        val metrics  = wm.currentWindowMetrics
        val insets   = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        statusBarH   = insets.top
        navBarH      = insets.bottom
        screenH      = metrics.bounds.height()
        val contentH = screenH - statusBarH - navBarH

        val sv = SurfaceView(context)
        sv.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView = sv

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            contentH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y       = statusBarH
        }

        // Declare an empty touchable region — tells the input policy layer this window
        // owns zero touch area.  Huawei CTAIFS inspects this field when deciding whether
        // to engage system-wide touch suppression; empty = suppression never fires.
        try {
            val f = WindowManager.LayoutParams::class.java.getDeclaredField("touchableRegion")
            f.isAccessible = true
            f.set(params, Region())
        } catch (_: Exception) { }

        // Belt-and-suspenders: also remove the input channel so the InputDispatcher
        // has no delivery path to this window even on modified vendor firmware.
        try {
            val f = WindowManager.LayoutParams::class.java.getDeclaredField("inputFeatures")
            f.isAccessible = true
            f.setInt(params, 0x00000002) // INPUT_FEATURE_NO_INPUT_CHANNEL
        } catch (_: Exception) { }

        wm.addView(sv, params)
    }

    fun release() {
        val sv = surfaceView ?: return
        surfaceView = null
        try { windowManager?.removeView(sv) } catch (_: IllegalArgumentException) { }
        windowManager = null
        bitmap?.recycle(); bitmap = null
        argbPixels = null
    }

    // ── Frame rendering ────────────────────────────────────────────────────────

    fun renderFrame(rgba: ByteArray, width: Int, height: Int) {
        val sv      = surfaceView ?: return
        val surface = sv.holder.surface
        if (!surface.isValid) return

        val bmp = if (bitmap?.width == width && bitmap?.height == height) {
            bitmap!!
        } else {
            bitmap?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }

        val n      = width * height
        val pixels = if (argbPixels?.size == n) argbPixels!!
                     else IntArray(n).also { argbPixels = it }

        var src = 0; var dst = 0
        while (dst < n) {
            val r = rgba[src    ].toInt() and 0xFF
            val g = rgba[src + 1].toInt() and 0xFF
            val b = rgba[src + 2].toInt() and 0xFF
            pixels[dst] = (OVERLAY_ALPHA shl 24) or (r shl 16) or (g shl 8) or b
            src += 4; dst++
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)

        val sH      = screenH
        val sbRows  = if (sH > 0) (statusBarH.toLong() * height / sH).toInt() else 0
        val navRows = if (sH > 0) (navBarH.toLong()    * height / sH).toInt() else 0
        srcRect.set(0, sbRows, width, height - navRows)

        val canvas = surface.lockHardwareCanvas() ?: return
        try {
            val cw = canvas.width; val ch = canvas.height
            if (cw != dstW || ch != dstH) {
                dstW = cw; dstH = ch; dstRect.set(0, 0, cw, ch)
            }
            // lockHardwareCanvas() returns a triple-buffer slot that retains its previous
            // content.  Without this clear, each draw composites semi-transparent pixels
            // onto old non-zero pixels — over ~10 frames the buffer saturates to full
            // opacity and the overlay appears as a solid opaque layer.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bmp, srcRect, dstRect, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
