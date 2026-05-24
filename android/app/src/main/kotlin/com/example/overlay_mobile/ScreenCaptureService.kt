package com.example.overlay_mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns both the screen-capture and the overlay-render lifecycles.
 *
 * Android requires that any component using [foregroundServiceType="mediaProjection"]
 * be a running foreground service with a visible notification before
 * [MediaProjection.getMediaProjection] is called.
 *
 * Startup sequence (enforced by Android):
 *   1. [onCreate]       → notification channel + [startForeground] (must happen first).
 *                         [OverlayRenderer.show] is called here so the overlay window
 *                         is ready before the first frame arrives.
 *   2. [onStartCommand] → parse [CaptureConfig] from the Intent, start [CaptureController].
 *
 * Shutdown:
 *   Flutter calls capture/stop → [stopService] → [onDestroy]:
 *     [CaptureController.release] tears down the projection pipeline.
 *     [OverlayRenderer.release]   removes the WindowManager overlay window.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID      = "overlay_capture_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var controller: CaptureController?  = null
    private var overlay:    OverlayRenderer?    = null
    private var bubble:     OverlayBubble?      = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // startForeground must be called within 5 s of service start (API 26+)
        // and before getMediaProjection (API 29+).  Calling it first in onCreate
        // satisfies both constraints.
        startForeground(NOTIFICATION_ID, buildNotification())

        // Show the overlay window immediately so the SurfaceView surface has time
        // to complete its creation cycle before the first frame arrives.
        // OverlayRenderer.show() posts to the WindowManager on the main thread —
        // that's exactly where we are inside onCreate().
        overlay = OverlayRenderer(applicationContext).also { it.show() }
        bubble  = OverlayBubble(applicationContext).also { it.show() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controller != null) return START_NOT_STICKY   // already running

        val ov = overlay ?: run {
            // Defensive guard — should never happen since onCreate always sets overlay first.
            FrameBus.emitError("OVERLAY_MISSING", "OverlayRenderer not initialised")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = CaptureConfig.fromIntent(intent)
        val ctrl   = CaptureController(applicationContext, config, ov)

        try {
            ctrl.start()
            controller = ctrl
        } catch (e: Exception) {
            FrameBus.emitError("CAPTURE_START_FAILED", e.message ?: "Unknown error")
            stopSelf()
        }

        // NOT_STICKY: Android must not auto-restart after kill; MediaProjection consent
        // is consumed on first use and cannot be reused after the process dies.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller?.release(); controller = null
        bubble?.hide();        bubble     = null
        overlay?.release();    overlay    = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the overlay engine is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay engine active")
            .setContentText("Vision filter is running — tap to open controls")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
