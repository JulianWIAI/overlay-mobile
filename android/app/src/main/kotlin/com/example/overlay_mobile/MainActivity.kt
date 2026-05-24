package com.example.overlay_mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val PERMISSIONS_CHANNEL      = "overlay_mobile/permissions"
    private val CAPTURE_CHANNEL          = "overlay_mobile/capture"
    private val FRAMES_CHANNEL           = "overlay_mobile/frames"
    private val PROCESSOR_CHANNEL        = "overlay_mobile/processor"

    private val OVERLAY_REQUEST_CODE     = 1001
    private val SCREEN_CAPTURE_REQUEST_CODE = 1002

    // Held so we can reply after the system dialog returns.
    private var pendingPermResult: MethodChannel.Result? = null

    // ── Engine configuration ───────────────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        // ── Permissions channel (overlay + MediaProjection consent) ───────────
        MethodChannel(messenger, PERMISSIONS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        pendingPermResult = result
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        startActivityForResult(intent, OVERLAY_REQUEST_CODE)
                    } else {
                        result.success(true)
                    }
                }
                "requestMediaProjection" -> {
                    pendingPermResult = result
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    startActivityForResult(
                        mpm.createScreenCaptureIntent(),
                        SCREEN_CAPTURE_REQUEST_CODE,
                    )
                }
                else -> result.notImplemented()
            }
        }

        // ── Capture channel (start / stop / querySize) ─────────────────────────
        MethodChannel(messenger, CAPTURE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    if (!ProjectionConsent.isGranted) {
                        result.error("NO_CONSENT",
                            "MediaProjection consent not granted", null)
                        return@setMethodCallHandler
                    }
                    val config = CaptureConfig.fromMap(
                        @Suppress("UNCHECKED_CAST")
                        (call.arguments as? Map<String, Any>) ?: emptyMap()
                    )
                    val intent = Intent(this, ScreenCaptureService::class.java)
                        .also { config.applyToIntent(it) }
                    startForegroundService(intent)
                    result.success(null)
                }
                "stop" -> {
                    stopService(Intent(this, ScreenCaptureService::class.java))
                    result.success(null)
                }
                "querySize" -> {
                    val config = CaptureConfig.fromMap(
                        @Suppress("UNCHECKED_CAST")
                        (call.arguments as? Map<String, Any>) ?: emptyMap()
                    )
                    val wm     = getSystemService(WindowManager::class.java)
                    val bounds = wm.currentWindowMetrics.bounds
                    val (w, h) = config.scaledSize(bounds.width(), bounds.height())
                    result.success(mapOf("width" to w, "height" to h))
                }
                else -> result.notImplemented()
            }
        }

        // ── Frame event channel (raw RGBA byte arrays) ─────────────────────────
        EventChannel(messenger, FRAMES_CHANNEL).setStreamHandler(FrameBus)

        // ── Processor channel (mode switching) ─────────────────────────────────
        ProcessorChannel(messenger)
    }

    // ── Activity results ───────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_REQUEST_CODE -> {
                pendingPermResult?.success(Settings.canDrawOverlays(this))
                pendingPermResult = null
            }
            SCREEN_CAPTURE_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Store consent token — consumed once by CaptureController.start().
                    ProjectionConsent.resultCode = resultCode
                    ProjectionConsent.data       = data
                    pendingPermResult?.success(true)
                } else {
                    pendingPermResult?.success(false)
                }
                pendingPermResult = null
            }
        }
    }

    // Keep a legacy onCreate only for the v1-embedding path (no-op for v2).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
