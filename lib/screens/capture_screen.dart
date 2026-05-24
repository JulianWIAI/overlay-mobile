import 'package:flutter/material.dart';

import '../native/capture_channel.dart';
import '../native/capture_config.dart';
import '../native/processor_channel.dart';
import '../widgets/frame_view.dart';
import '../widgets/vision_mode_bubble.dart';

/// Main overlay screen: full-screen live capture with a vision-mode FAB.
///
/// Layout (Stack, front to back):
///   [0] FrameView       — fills the screen, shows the transformed frame stream
///   [1] _ModeBanner     — top-left corner, current mode name in a translucent chip
///   [2] VisionModeBubble — bottom-right FAB; opens the mode picker sheet
///
/// Lifecycle:
///   • initState  — creates [VisionState]; syncs the active mode from native.
///   • dispose    — stops the capture service and resets the frame stream.
class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key, required this.config});

  final CaptureConfig config;

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  late final VisionState _visionState;

  @override
  void initState() {
    super.initState();
    _visionState = VisionState();
    _visionState.syncFromNative();
  }

  @override
  void dispose() {
    CaptureChannel.stop();
    CaptureChannel.resetStream();
    _visionState.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          FrameView(config: widget.config),
          Positioned(
            top: MediaQuery.of(context).padding.top + 12,
            left: 16,
            child: _ModeBanner(state: _visionState),
          ),
          Positioned(
            bottom: MediaQuery.of(context).padding.bottom + 20,
            right: 20,
            child: VisionModeBubble(state: _visionState),
          ),
        ],
      ),
    );
  }
}

// ── Top-left mode banner ───────────────────────────────────────────────────────

class _ModeBanner extends StatelessWidget {
  const _ModeBanner({required this.state});

  final VisionState state;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.remove_red_eye_outlined,
                  color: Colors.white70, size: 16),
              const SizedBox(width: 6),
              Text(
                state.current.displayName,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
              if (state.isSwitching) ...[
                const SizedBox(width: 8),
                const SizedBox.square(
                  dimension: 12,
                  child: CircularProgressIndicator(
                    strokeWidth: 1.5,
                    color: Colors.white54,
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}
