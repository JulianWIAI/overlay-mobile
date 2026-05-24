import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'vision_mode.dart';

// ── Low-level channel wrapper ──────────────────────────────────────────────────

/// Raw platform-channel interface to [ProcessorChannel.kt].
///
/// Prefer [VisionState] for widget integration — it wraps this class and
/// adds [ChangeNotifier] semantics so the UI rebuilds automatically.
class ProcessorChannel {
  ProcessorChannel._();

  static const _channel = MethodChannel('overlay_mobile/processor');

  /// Sends [mode.id] to the native layer.  The transform is swapped atomically
  /// on the Kotlin side; the next frame processed after this returns will use
  /// the new transform.  No frame is dropped or duplicated.
  static Future<void> setMode(VisionMode mode) =>
      _channel.invokeMethod<void>('setMode', {'modeId': mode.id});

  /// Reads the currently active mode ID from native.  Useful for restoring
  /// state after a hot-restart during development.
  static Future<int> getMode() async =>
      await _channel.invokeMethod<int>('getMode') ?? 0;
}

// ── ChangeNotifier state ───────────────────────────────────────────────────────

/// Observable vision-mode state for Flutter widget trees.
///
/// ```dart
/// // 1. Provide near the root:
/// ChangeNotifierProvider(create: (_) => VisionState()),
///
/// // 2. Switch modes from a button:
/// context.read<VisionState>().setMode(const DogVision());
///
/// // 3. Read in a builder:
/// context.watch<VisionState>().current.displayName
/// ```
///
/// [setMode] completes before notifying listeners, so the UI always reflects
/// the state that the native layer has committed.
class VisionState extends ChangeNotifier {
  VisionState([VisionMode initial = const NormalVision()]) : _current = initial;

  VisionMode _current;

  /// The currently active vision mode.
  VisionMode get current => _current;

  /// Whether a [setMode] call is in flight.
  bool get isSwitching => _switching;
  bool _switching = false;

  /// Switches to [mode] by calling the native layer, then notifies listeners.
  ///
  /// No-ops if [mode] is already active, avoiding an unnecessary channel call.
  Future<void> setMode(VisionMode mode) async {
    if (mode.id == _current.id) return;

    _switching = true;
    notifyListeners();

    try {
      await ProcessorChannel.setMode(mode);
      _current = mode;
    } on PlatformException catch (e) {
      debugPrint('VisionState.setMode failed: ${e.message}');
      rethrow;
    } finally {
      _switching = false;
      notifyListeners();
    }
  }

  /// Synchronises [current] with whatever the native layer reports.
  /// Call once at startup if you need to survive hot-restarts.
  Future<void> syncFromNative() async {
    final id = await ProcessorChannel.getMode();
    final mode = kAllVisionModes.firstWhere(
      (m) => m.id == id,
      orElse: () => const NormalVision(),
    );
    if (mode.id != _current.id) {
      _current = mode;
      notifyListeners();
    }
  }
}
