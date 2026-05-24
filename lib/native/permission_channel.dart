import 'package:flutter/services.dart';

import 'permission_result.dart';

/// Thin, typed wrapper around the native permission method channel.
///
/// All public methods are static so callers don't need to manage an instance.
/// The channel name must match [MainActivity.CHANNEL] on the Kotlin side.
class PermissionChannel {
  PermissionChannel._();

  static const _channel = MethodChannel('overlay_mobile/permissions');

  // ── Individual checks ──────────────────────────────────────────────────────

  static Future<bool> checkOverlayPermission() async {
    return await _channel.invokeMethod<bool>('checkOverlayPermission') ?? false;
  }

  static Future<bool> checkMediaProjection() async {
    return await _channel.invokeMethod<bool>('checkMediaProjection') ?? false;
  }

  // ── Individual requests ────────────────────────────────────────────────────

  static Future<PermissionResult> requestOverlayPermission() async {
    try {
      final granted =
          await _channel.invokeMethod<bool>('requestOverlayPermission') ??
              false;
      return granted
          ? const PermissionGranted()
          : const PermissionDenied(permission: 'SYSTEM_ALERT_WINDOW');
    } on PlatformException catch (e) {
      return PermissionDenied(
        permission: 'SYSTEM_ALERT_WINDOW',
        reason: e.message,
      );
    }
  }

  static Future<PermissionResult> requestMediaProjection() async {
    try {
      final granted =
          await _channel.invokeMethod<bool>('requestMediaProjection') ?? false;
      return granted
          ? const PermissionGranted()
          : const PermissionDenied(permission: 'MEDIA_PROJECTION');
    } on PlatformException catch (e) {
      return PermissionDenied(
        permission: 'MEDIA_PROJECTION',
        reason: e.message,
      );
    }
  }

  // ── Sequential flow ────────────────────────────────────────────────────────

  /// Requests both permissions in order: overlay first, then MediaProjection.
  ///
  /// [onStep] is called before each request so the UI can show progress.
  /// Returns a record with the individual results; the caller decides whether
  /// to proceed if either is denied.
  static Future<({PermissionResult overlay, PermissionResult projection})>
      requestAll({
    void Function(String step)? onStep,
  }) async {
    onStep?.call('overlay');
    final overlay = await requestOverlayPermission();

    onStep?.call('mediaProjection');
    final projection = await requestMediaProjection();

    return (overlay: overlay, projection: projection);
  }
}
