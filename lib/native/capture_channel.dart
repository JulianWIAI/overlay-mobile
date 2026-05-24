
import 'package:flutter/services.dart';

import 'capture_config.dart';

/// Controls the native screen-capture foreground service and exposes the
/// resulting raw-RGBA frame stream to the Dart image-processing pipeline.
///
/// Usage:
/// ```dart
/// await CaptureChannel.start(const CaptureConfig(maxWidthPx: 720, targetFps: 30));
///
/// CaptureChannel.frames.listen((Uint8List rgba) {
///   // rgba is width × height × 4 bytes, tightly packed, row-major, RGBA order.
/// });
///
/// await CaptureChannel.stop();
/// ```
class CaptureChannel {
  CaptureChannel._();

  static const _method = MethodChannel('overlay_mobile/capture');
  static const _events = EventChannel('overlay_mobile/frames');

  // Reuse a single broadcast stream so multiple widgets can subscribe.
  static Stream<Uint8List>? _frameStream;

  // ── Control ────────────────────────────────────────────────────────────────

  /// Starts the foreground capture service with the given [config].
  ///
  /// Throws [PlatformException] with code `NO_CONSENT` if the user has not
  /// yet granted MediaProjection permission.
  static Future<void> start(CaptureConfig config) =>
      _method.invokeMethod<void>('start', config.toMap());

  /// Stops the foreground service and releases all native resources.
  /// The [frames] stream will complete after this call.
  static Future<void> stop() => _method.invokeMethod<void>('stop');

  // ── Frame stream ───────────────────────────────────────────────────────────

  /// Broadcast stream of raw RGBA frames from the screen capture pipeline.
  ///
  /// Each event is a [Uint8List] of exactly `width × height × 4` bytes where
  /// `width` and `height` are the capture dimensions chosen by [CaptureConfig].
  /// Pixel layout: R G B A, row-major, top-left origin.
  ///
  /// The stream emits a [PlatformException] with code `PROJECTION_STOPPED`
  /// if the system revokes MediaProjection (e.g. user presses the status-bar
  /// stop button).
  static Stream<Uint8List> get frames {
    _frameStream ??= _events
        .receiveBroadcastStream()
        .map((dynamic event) => event as Uint8List);
    return _frameStream!;
  }

  /// Resets the cached stream reference.  Call this after [stop] if you plan
  /// to call [start] again in the same app session.
  static void resetStream() => _frameStream = null;

  // ── Dimensions ────────────────────────────────────────────────────────────

  /// Returns the pixel dimensions that the capture service will use for [config]
  /// without starting the service.  Useful for pre-allocating a [ui.Image]
  /// canvas before the first frame arrives.
  ///
  /// Returns a named record `({int width, int height})`.
  static Future<({int width, int height})> querySize(CaptureConfig config) async {
    final map = await _method.invokeMapMethod<String, dynamic>(
      'querySize',
      config.toMap(),
    );
    return (
      width:  (map?['width']  as int?) ?? 720,
      height: (map?['height'] as int?) ?? 1280,
    );
  }
}
