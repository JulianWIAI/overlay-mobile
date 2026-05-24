/// Parameters sent to the native capture engine on [CaptureChannel.start].
class CaptureConfig {
  const CaptureConfig({
    this.maxWidthPx = 720,
    this.targetFps = 30,
  })  : assert(maxWidthPx > 0),
        assert(targetFps > 0 && targetFps <= 120);

  /// Maximum capture width in pixels.  Height is derived from the device's
  /// aspect ratio.  Common values: 480 (fast), 720 (balanced), 1080 (sharp).
  final int maxWidthPx;

  /// Frame-delivery cap.  Frames that arrive faster than this rate are dropped
  /// before byte-copying, so the CPU load scales with [targetFps], not the
  /// display refresh rate.
  final int targetFps;

  Map<String, dynamic> toMap() => {
        'maxWidthPx': maxWidthPx,
        'targetFps': targetFps,
      };
}
