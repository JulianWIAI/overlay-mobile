import 'package:flutter/material.dart';

import '../native/capture_channel.dart';
import '../native/capture_config.dart';
import '../native/permission_channel.dart';
import '../native/permission_result.dart';
import 'capture_screen.dart';

/// Guides the user through granting overlay and MediaProjection permissions
/// before the capture engine can start.
class PermissionSetupScreen extends StatefulWidget {
  const PermissionSetupScreen({super.key});

  @override
  State<PermissionSetupScreen> createState() => _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen> {
  _Status _overlayStatus = _Status.idle;
  _Status _projectionStatus = _Status.idle;
  bool _requesting = false;

  // ── Permission flow ────────────────────────────────────────────────────────

  Future<void> _requestPermissions() async {
    setState(() => _requesting = true);

    final results = await PermissionChannel.requestAll(
      onStep: (step) => setState(() {
        if (step == 'overlay') _overlayStatus = _Status.pending;
        if (step == 'mediaProjection') _projectionStatus = _Status.pending;
      }),
    );

    setState(() {
      _overlayStatus = _statusFrom(results.overlay);
      _projectionStatus = _statusFrom(results.projection);
      _requesting = false;
    });

    final allGranted = results.overlay is PermissionGranted &&
        results.projection is PermissionGranted;

    if (allGranted && mounted) {
      const config = CaptureConfig(maxWidthPx: 720, targetFps: 30);
      await CaptureChannel.start(config);
      if (!mounted) return;
      await Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(
          builder: (_) => CaptureScreen(config: config),
        ),
      );
    }
  }

  _Status _statusFrom(PermissionResult result) => switch (result) {
        PermissionGranted() => _Status.granted,
        PermissionDenied() => _Status.denied,
      };

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Setup Permissions')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'This app needs two system permissions to run the overlay engine.',
              style: TextStyle(fontSize: 15),
            ),
            const SizedBox(height: 32),
            _PermissionTile(
              label: 'Draw over other apps',
              description:
                  'Renders the full-screen overlay on top of any running app.',
              status: _overlayStatus,
            ),
            const SizedBox(height: 16),
            _PermissionTile(
              label: 'Screen capture',
              description: 'Captures the live display via MediaProjection.',
              status: _projectionStatus,
            ),
            const Spacer(),
            if (_overlayStatus == _Status.denied ||
                _projectionStatus == _Status.denied)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(
                  'One or more permissions were denied. '
                  'The overlay engine cannot run without them.',
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                  textAlign: TextAlign.center,
                ),
              ),
            FilledButton(
              onPressed: _requesting ? null : _requestPermissions,
              child: _requesting
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Grant Permissions'),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Supporting types ───────────────────────────────────────────────────────────

enum _Status { idle, pending, granted, denied }

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.label,
    required this.description,
    required this.status,
  });

  final String label;
  final String description;
  final _Status status;

  @override
  Widget build(BuildContext context) {
    final (icon, color) = switch (status) {
      _Status.idle    => (Icons.radio_button_unchecked, Colors.grey),
      _Status.pending => (Icons.hourglass_top, Colors.orange),
      _Status.granted => (Icons.check_circle, Colors.green),
      _Status.denied  => (Icons.cancel, Theme.of(context).colorScheme.error),
    };

    return Card(
      child: ListTile(
        leading: Icon(icon, color: color),
        title: Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text(description),
      ),
    );
  }
}
