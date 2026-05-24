package com.example.overlay_mobile

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager

/**
 * Owns the MediaProjection → VirtualDisplay → ImageReader pipeline.
 *
 * Lifecycle contract (must be respected by the caller):
 *   1. Call [start] once after the service is in the foreground state.
 *   2. Call [release] in onDestroy; never reuse the instance afterwards.
 *
 * Per-frame delivery (both happen on the capture [HandlerThread]):
 *   • [OverlayRenderer.renderFrame] — native SurfaceView overlay (primary display path;
 *     persists when the user leaves the Flutter app).
 *   • [FrameBus.emit]               — EventChannel to Flutter FrameView (in-app preview).
 */
class CaptureController(
    private val context: Context,
    private val config:  CaptureConfig,
    private val overlay: OverlayRenderer,
) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    /** Timestamp of the last frame forwarded to the pipeline (ms). */
    private var lastEmittedMs = 0L

    // Capture dimensions stored at start() time.
    private var frameWidth  = 0
    private var frameHeight = 0

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start() {
        check(ProjectionConsent.isGranted) { "MediaProjection consent not available" }

        val info = resolveDisplayMetrics()
        val captureW   = info.width
        val captureH   = info.height
        val densityDpi = info.densityDpi
        frameWidth     = captureW
        frameHeight    = captureH

        val ht = HandlerThread("capture-reader").also { it.start() }
        readerThread = ht
        val bgHandler = Handler(ht.looper)

        val reader = ImageReader.newInstance(
            captureW, captureH,
            CaptureConfig.PIXEL_FORMAT,
            CaptureConfig.IMAGE_READER_BUFFER_COUNT,
        )
        imageReader = reader
        reader.setOnImageAvailableListener(::onImageAvailable, bgHandler)

        val pm = context.getSystemService(MediaProjectionManager::class.java)
        val projection = pm.getMediaProjection(ProjectionConsent.resultCode, ProjectionConsent.data!!)
        mediaProjection = projection

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                FrameBus.emitError("PROJECTION_STOPPED", "MediaProjection was revoked by the system")
                release()
            }
        }, bgHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "overlay_capture",
            captureW, captureH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, bgHandler,
        )
    }

    fun release() {
        // Teardown order matters: VirtualDisplay → MediaProjection → ImageReader → thread.
        virtualDisplay?.release(); virtualDisplay = null
        mediaProjection?.stop();   mediaProjection = null
        imageReader?.close();      imageReader = null
        readerThread?.quitSafely(); readerThread = null
        ProjectionConsent.clear()
    }

    // ── Frame acquisition ──────────────────────────────────────────────────────

    private fun onImageAvailable(reader: ImageReader) {
        val nowMs = System.currentTimeMillis()

        if (nowMs - lastEmittedMs < config.frameIntervalMs) {
            reader.acquireLatestImage()?.close()
            return
        }
        lastEmittedMs = nowMs

        val image = reader.acquireLatestImage() ?: return
        try {
            val rgba = image.toCompactRgba()
            PixelProcessor.processInPlace(rgba, frameWidth, frameHeight)
            overlay.renderFrame(rgba, frameWidth, frameHeight)
            FrameBus.emit(rgba)
        } finally {
            image.close()
        }
    }

    /**
     * Copies the image's RGBA plane into a contiguous byte array.
     *
     * The display driver often inserts padding at the end of each row so that
     * row width is a multiple of the GPU's preferred stride alignment.  We strip
     * that padding here so the downstream pipeline always receives width × height × 4
     * tightly-packed bytes.
     */
    private fun Image.toCompactRgba(): ByteArray {
        val plane      = planes[0]
        val rowStride  = plane.rowStride
        val pixStride  = plane.pixelStride           // always 4 for RGBA_8888
        val rowWidth   = width * pixStride
        val src        = plane.buffer

        return if (rowStride == rowWidth) {
            // Contiguous layout — single allocation + copy.
            ByteArray(src.remaining()).also { src.get(it) }
        } else {
            // Padded rows — copy each row individually, discarding the padding gap.
            val out = ByteArray(width * height * pixStride)
            val row = ByteArray(rowWidth)
            var dstOffset = 0
            for (y in 0 until height) {
                src.position(y * rowStride)
                src.get(row, 0, rowWidth)
                System.arraycopy(row, 0, out, dstOffset, rowWidth)
                dstOffset += rowWidth
            }
            out
        }
    }

    // ── Display metrics ────────────────────────────────────────────────────────

    private data class DisplayInfo(val width: Int, val height: Int, val densityDpi: Int)

    private fun resolveDisplayMetrics(): DisplayInfo {
        val wm     = context.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds          // API 30+ (minSdk=34 ✓)
        val dpi    = context.resources.displayMetrics.densityDpi
        val (w, h) = config.scaledSize(bounds.width(), bounds.height())
        return DisplayInfo(w, h, dpi)
    }
}
