# overlay_mobile

A Flutter + Kotlin Android app that captures your screen in real time via MediaProjection, applies colour-science vision-simulation filters, and renders the result as a full-screen hardware-composited overlay that persists across every app on the device.

---

## What it does

The app creates a floating **eye-bubble** that stays visible above all other apps. Tap it to open a mode panel, pick a vision mode, then leave the app — the filter keeps running everywhere: home screen, browser, games, camera, everywhere.

Eighteen vision modes are included, covering animal dichromacy/tetrachromacy, human colour-blindness simulations, and synthetic sensor palettes.

---

## Vision modes

| # | Name | Description |
|---|------|-------------|
| 1 | **Normal** | Passthrough — overlay inactive |
| 2 | **Dog** | S + L dichromacy (blue-yellow axis, ~440/555 nm) |
| 3 | **Cat** | Dichromacy + peripheral Gaussian blur |
| 4 | **Bull** | Pure blue-yellow axis, red/green conflated |
| 5 | **Bee** | UV-shifted trichromacy (350/440/540 nm receptors) |
| 6 | **Frog** | Cold-hue high-contrast with rod-bloom amplification |
| 7 | **Eagle** | Foveal contrast enhancement + UV bias |
| 8 | **Protanopia** | Red-blind (Viénot 1999) |
| 9 | **Deuteranopia** | Green-blind (Viénot 1999) |
| 10 | **Tritanopia** | Blue-blind (Viénot 1999) |
| 11 | **Mono** | Luminance greyscale |
| 12 | **Invert** | Photographic negative |
| 13 | **Thermal** | Iron-palette heat map |
| 14 | **Night** | Phosphor-green image-intensifier simulation |
| 15 | **Echo** | Sobel-edge → blue-cyan gradient (echolocation) |
| 16 | **IR** | Near-infrared warm-inverted palette |
| 17 | **UV** | Spectrum-shifted UV trichromacy |
| 18 | **Hi-Con** | 4×4 tile CLAHE-style local contrast enhancement |

Matrix sources: Jacobs 1993, Neitz & Neitz 2011, Viénot et al. 1999, Chittka & Menzel 1992.

---

## Requirements

| | Minimum |
|---|---|
| Android | 10 (API 29) |
| Target SDK | 36 |
| Flutter | 3.x |
| Dart | ≥ 3.0.0 |
| Permissions | `SYSTEM_ALERT_WINDOW`, MediaProjection (granted at launch) |

The app targets **Android only**. iOS does not expose the APIs needed (MediaProjection, TYPE_APPLICATION_OVERLAY).

---

## Build & run

```bash
# Clone
git clone https://github.com/<you>/overlay_mobile.git
cd overlay_mobile

# Install Dart dependencies
flutter pub get

# Run on a connected Android device (USB debugging enabled)
flutter run

# Release APK
flutter build apk --release
```

Grant **"Display over other apps"** when prompted. Accept the screen-recording permission dialog that follows.

---

## How to use

1. Launch the app and grant both permissions.
2. A dark circle with an **👁** icon appears — this is the mode bubble.
3. **Drag** the bubble anywhere on screen. It snaps to the nearest edge on release.
4. **Tap** the bubble to open the mode panel.
5. Select a vision mode — the filter applies immediately.
6. Leave the app. The filter continues running over every other app.
7. Return to the app and tap the bubble again to change or stop the filter (select **Normal** to disable).

---

## Architecture

```
Flutter UI (Dart)
  └── MethodChannel / EventChannel
        │
        ├── ScreenCaptureService  (foreground service, mediaProjection type)
        │     ├── CaptureController
        │     │     ├── MediaProjection → VirtualDisplay (AUTO_MIRROR, 720 px wide)
        │     │     └── ImageReader (RGBA_8888, 30 fps cap)
        │     │           └── onImageAvailable
        │     │                 ├── Image.toCompactRgba()   (stride-safe row copy)
        │     │                 ├── PixelProcessor.processInPlace()
        │     │                 ├── OverlayRenderer.renderFrame()
        │     │                 └── FrameBus.emit()          (in-app EventChannel preview)
        │     │
        │     ├── OverlayRenderer  (TYPE_APPLICATION_OVERLAY SurfaceView)
        │     └── OverlayBubble    (draggable bubble + mode panel)
        │
        └── TransformCatalog  (18 pre-allocated PixelTransform singletons)
```

### Key design decisions

**Feedback-loop stabilisation** — The VirtualDisplay mirrors every SurfaceFlinger layer including the overlay itself. Rendering pixels at `OVERLAY_ALPHA = 128` (50 %) keeps the recursive capture stable: for any linear matrix *M* the fixed point is *F = (I − αM)⁻¹ · (1−α) · M · real*, which converges for all matrices whose maximum eigenvalue satisfies *λ < 1/α = 2.0*. Our highest coefficient is 1.6 (BEE, FROG) so the loop is stable with margin.

**Canvas clearing** — `lockHardwareCanvas()` returns triple-buffer slots that retain previous content. Every frame begins with `canvas.drawColor(0, PorterDuff.Mode.CLEAR)` to prevent semi-transparent pixels accumulating into an opaque layer.

**Status-bar / nav-bar exclusion** — The overlay window sits at `y = statusBarH` with height `= contentH`. Captured frame rows are proportionally cropped via `srcRect` before rendering so the overlay never doubles the system bars.

**Stride-safe capture** — GPU drivers often pad rows to alignment boundaries. `Image.toCompactRgba()` copies row-by-row when `rowStride ≠ width × 4`, discarding the padding so the downstream pipeline always receives tightly-packed RGBA.

**Touch pass-through** — Three layers:
1. `FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL`
2. `touchableRegion = Region()` (empty) via reflection — tells the input policy layer the window claims zero touch area
3. `INPUT_FEATURE_NO_INPUT_CHANNEL` via reflection — removes the input channel so the dispatcher has no delivery path

---

## Known limitations

**Huawei / EMUI (CTAIFS)** — Huawei's custom InputDispatcher includes a context-triggered touch-suppression system (CTAIFS) that detects simultaneous full-screen overlays + MediaProjection and blocks system-wide touch input. No public or reflection-accessible API can opt out of this policy without a system signature. The overlay displays correctly on Huawei devices; touch pass-through is blocked.

**FLAG_SECURE apps** — Apps that set `FLAG_SECURE` (banking, streaming) will appear black in the capture. This is an Android-enforced privacy boundary.

---

## Project structure

```
lib/                    Dart / Flutter UI layer
android/app/src/main/
  kotlin/.../
    MainActivity.kt
    ScreenCaptureService.kt
    CaptureController.kt      MediaProjection pipeline
    CaptureConfig.kt          Resolution + FPS config
    OverlayRenderer.kt        SurfaceView overlay window
    OverlayBubble.kt          Floating UI bubble + panel
    PixelProcessor.kt         Active-transform hot-swap
    TransformCatalog.kt       Mode-ID registry
    PixelTransform.kt         Transform interface
    MatrixTransform.kt        3×3 colour-matrix path
    MonochromeTransform.kt
    InvertedTransform.kt
    CatVisionTransform.kt     Spatial: blur + dichromacy
    EagleVisionTransform.kt   Spatial: foveal contrast
    ThermalTransform.kt
    NightVisionTransform.kt
    EcholocationTransform.kt  Spatial: Sobel edge
    InfraredTransform.kt
    ContrastTransform.kt      Spatial: CLAHE-style
    FrameBus.kt               EventChannel → Flutter preview
    ProjectionConsent.kt      Consent token store
    ProcessorChannel.kt       MethodChannel bridge
```

---

## License

MIT — see [LICENSE](LICENSE).
