package com.example.overlay_mobile

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Process-wide bridge between the background capture thread and the Flutter
 * EventChannel.
 *
 * The capture thread calls [emit] freely from any thread.  FrameBus marshals
 * every emission onto the Android main looper before forwarding to the Flutter
 * engine, satisfying the EventSink's thread-safety contract.
 *
 * FrameBus also acts as the [EventChannel.StreamHandler] so that MainActivity
 * can pass it directly to [EventChannel.setStreamHandler].
 */
object FrameBus : EventChannel.StreamHandler {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var sink: EventChannel.EventSink? = null

    // ── EventChannel.StreamHandler ─────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    // ── Called from capture thread ─────────────────────────────────────────

    /**
     * Emits [bytes] to the Flutter stream.  No-ops if no Dart listener is
     * attached.  The local capture of [sink] prevents a race between the
     * null check and the post.
     */
    fun emit(bytes: ByteArray) {
        val s = sink ?: return
        mainHandler.post { s.success(bytes) }
    }

    fun emitError(code: String, message: String) {
        val s = sink ?: return
        mainHandler.post { s.error(code, message, null) }
    }
}
