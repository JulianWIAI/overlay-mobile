import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../native/capture_channel.dart';
import '../native/capture_config.dart';

/// Renders the live capture frame stream as a full-screen image.
///
/// Subscribes to [CaptureChannel.frames], converts each raw RGBA [Uint8List]
/// into a [ui.Image] via [ui.ImageDescriptor.raw], and paints it using a
/// [RawImage] widget fitted to cover the available space.
///
/// Frame-drop guard: while one frame is being decoded ([_decoding] == true),
/// incoming frames are dropped.  This keeps the UI thread responsive when the
/// pixel-processing pipeline outpaces the Flutter raster thread.
///
/// Every decoded [ui.Image] is disposed as soon as the next one arrives, so
/// the GPU texture pool doesn't grow unbounded.
class FrameView extends StatefulWidget {
  const FrameView({super.key, required this.config});

  final CaptureConfig config;

  @override
  State<FrameView> createState() => _FrameViewState();
}

class _FrameViewState extends State<FrameView> {
  StreamSubscription<Uint8List>? _sub;
  ui.Image? _image;
  bool _decoding = false;

  // Dimensions are derived lazily from the first frame to avoid a synchronous
  // querySize() call on widget build.
  int _width  = 0;
  int _height = 0;

  @override
  void initState() {
    super.initState();
    _sub = CaptureChannel.frames.listen(_onFrame, onError: _onError);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _image?.dispose();
    super.dispose();
  }

  // ── Frame pipeline ──────────────────────────────────────────────────────────

  Future<void> _onFrame(Uint8List rgba) async {
    if (_decoding) return;   // drop frame — previous decode still running
    _decoding = true;

    try {
      // Derive dimensions from the byte array length the first time.
      // After that, trust the cached values (they are constant for a session).
      int w = _width;
      int h = _height;
      if (w == 0) {
        final maxW = widget.config.maxWidthPx;
        w = maxW;
        h = rgba.length ~/ (maxW * 4);
        if (h == 0) { _decoding = false; return; }
      }

      final buffer     = await ui.ImmutableBuffer.fromUint8List(rgba);
      final descriptor = ui.ImageDescriptor.raw(
        buffer,
        width:       w,
        height:      h,
        pixelFormat: ui.PixelFormat.rgba8888,
      );
      final codec     = await descriptor.instantiateCodec();
      final frameInfo = await codec.getNextFrame();
      final newImage  = frameInfo.image;
      descriptor.dispose();
      codec.dispose();

      if (!mounted) { newImage.dispose(); return; }

      final old = _image;
      setState(() {
        _width  = w;
        _height = h;
        _image  = newImage;
      });
      old?.dispose();
    } finally {
      _decoding = false;
    }
  }

  void _onError(Object error) {
    // PROJECTION_STOPPED is handled by the CaptureScreen; ignore here.
    debugPrint('FrameView stream error: $error');
  }

  // ── Build ────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final img = _image;
    if (img == null) {
      return const ColoredBox(
        color: Colors.black,
        child: Center(
          child: CircularProgressIndicator(color: Colors.white54),
        ),
      );
    }
    return FittedBox(
      fit: BoxFit.cover,
      child: SizedBox(
        width:  _width.toDouble(),
        height: _height.toDouble(),
        child: RawImage(image: img, fit: BoxFit.fill),
      ),
    );
  }
}
