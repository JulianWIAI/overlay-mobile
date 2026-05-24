package com.example.overlay_mobile

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * Bridges the Dart [VisionState] to [PixelProcessor].
 *
 * Registers a single method channel for mode switching.  Hot-swap latency is
 * bounded only by the platform-channel round-trip (~1 ms); the frame loop is
 * never paused or interrupted.
 *
 * Channel: "overlay_mobile/processor"
 * Methods:
 *   setMode({ modeId: Int }) → null | error("INVALID_MODE")
 *   getMode()               → Int  (current active mode ID)
 */
class ProcessorChannel(messenger: BinaryMessenger) {

    companion object {
        const val CHANNEL = "overlay_mobile/processor"
    }

    init {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setMode" -> handleSetMode(call.argument("modeId"), result)
                "getMode" -> result.success(currentModeId())
                else      -> result.notImplemented()
            }
        }
    }

    private fun handleSetMode(modeId: Int?, result: MethodChannel.Result) {
        if (modeId == null) {
            result.error("MISSING_ARG", "modeId argument is required", null)
            return
        }
        val transform = TransformCatalog.forId(modeId)
        if (transform == null) {
            result.error(
                "INVALID_MODE",
                "Mode ID $modeId is not registered. Valid IDs: ${TransformCatalog.allIds}",
                null,
            )
            return
        }
        PixelProcessor.setTransform(transform)
        result.success(null)
    }

    private fun currentModeId(): Int =
        TransformCatalog.allIds.firstOrNull { id ->
            TransformCatalog.forId(id) === PixelProcessor.activeTransform
        } ?: TransformCatalog.NORMAL
}
