/// Sealed hierarchy of all supported vision simulation modes.
///
/// Each subclass carries an [id] that maps 1-to-1 with [TransformCatalog]
/// constants on the Kotlin side.  Adding a new mode requires:
///   1. A new subclass here with a unique [id].
///   2. A matching entry in TransformCatalog.kt.
///   Nothing else needs to change.
sealed class VisionMode {
  const VisionMode();

  /// Identifies this mode to the native layer via [ProcessorChannel.setMode].
  int get id;

  /// Human-readable label for UI display.
  String get displayName;

  /// Short description of the perceptual effect.
  String get description;
}

// ── Passthrough ────────────────────────────────────────────────────────────────

class NormalVision extends VisionMode {
  const NormalVision();
  @override int    get id          => 0;
  @override String get displayName => 'Normal';
  @override String get description => 'Unmodified display output.';
}

// ── Animal vision ──────────────────────────────────────────────────────────────

class DogVision extends VisionMode {
  const DogVision();
  @override int    get id          => 1;
  @override String get displayName => 'Dog';
  @override String get description =>
      'Dichromatic (S-cone + L-cone). World appears yellow and blue-violet; '
      'red and green are indistinguishable.';
}

class CatVision extends VisionMode {
  const CatVision();
  @override int    get id          => 2;
  @override String get displayName => 'Cat';
  @override String get description =>
      'Dichromatic + peripheral blur. Sharp horizontal-streak fovea; '
      'rod-heavy periphery loses spatial acuity.';
}

class BullVision extends VisionMode {
  const BullVision();
  @override int    get id          => 3;
  @override String get displayName => 'Bull';
  @override String get description =>
      'Pure blue-yellow axis dichromacy. R and G channels collapse to equal '
      'luminance (yellow); only blue is independently perceived.';
}

// ── Human colour-blindness simulations (Viénot et al. 1999) ──────────────────

class ProtanopiaVision extends VisionMode {
  const ProtanopiaVision();
  @override int    get id          => 4;
  @override String get displayName => 'Protanopia';
  @override String get description => 'Missing L-cones (red-blind). '
      'Viénot 1999 simulation matrix.';
}

class DeuteranopiaVision extends VisionMode {
  const DeuteranopiaVision();
  @override int    get id          => 5;
  @override String get displayName => 'Deuteranopia';
  @override String get description => 'Missing M-cones (green-blind). '
      'Viénot 1999 simulation matrix.';
}

class TritanopiaVision extends VisionMode {
  const TritanopiaVision();
  @override int    get id          => 6;
  @override String get displayName => 'Tritanopia';
  @override String get description => 'Missing S-cones (blue-blind). '
      'Viénot 1999 simulation matrix.';
}

// ── Channel-independent transforms ────────────────────────────────────────────

class MonochromeVision extends VisionMode {
  const MonochromeVision();
  @override int    get id          => 7;
  @override String get displayName => 'Monochrome';
  @override String get description =>
      'Perceptual grayscale: Y = R×0.299 + G×0.587 + B×0.114 (ITU-R BT.601).';
}

class InvertedVision extends VisionMode {
  const InvertedVision();
  @override int    get id          => 8;
  @override String get displayName => 'Inverted';
  @override String get description => 'Bitwise colour inversion (photo negative).';
}

// ── Extended biological simulations ───────────────────────────────────────────

class BeeVision extends VisionMode {
  const BeeVision();
  @override int    get id          => 9;
  @override String get displayName => 'Bee';
  @override String get description =>
      'UV-shifted trichromacy. L-cone (green) → red; M-cone (blue) → green; '
      'S-cone UV ≈ amplified blue. Human red is invisible to bees.';
}

class FrogVision extends VisionMode {
  const FrogVision();
  @override int    get id          => 10;
  @override String get displayName => 'Frog';
  @override String get description =>
      'Cold-hue high contrast. Blue-green dominant spectrum (430–520 nm); '
      'red suppressed; rod-gain bloom on bright surfaces via channel clamping.';
}

class EagleVision extends VisionMode {
  const EagleVision();
  @override int    get id          => 11;
  @override String get displayName => 'Eagle';
  @override String get description =>
      'Foveal contrast + UV bias. Centre 50 % amplified 1.25×; '
      'blue channel amplified 1.75× for UV-cone sensitivity; periphery dimmed to 60 %.';
}

// ── Technology / sensor simulations ───────────────────────────────────────────

class ThermalVision extends VisionMode {
  const ThermalVision();
  @override int    get id          => 12;
  @override String get displayName => 'Thermal';
  @override String get description =>
      'Pit-viper IR "iron" palette. Black → violet → red → orange → yellow → white '
      'maps cold (0) to hot (255) luma.';
}

class NightVisionMode extends VisionMode {
  const NightVisionMode();
  @override int    get id          => 13;
  @override String get displayName => 'Night Vision';
  @override String get description =>
      'Phosphor-green image-intensifier tube. Luma amplified ×4 (cap 210); '
      'XorShift-32 grain in ±16 range; P31 phosphor green output.';
}

class EcholocationVision extends VisionMode {
  const EcholocationVision();
  @override int    get id          => 14;
  @override String get displayName => 'Echolocation';
  @override String get description =>
      'Sobel edge-detection gradient mapped to deep-blue → cyan. '
      'Silent open space = near-black; reflective edges = full cyan.';
}

class CompoundEyeVision extends VisionMode {
  const CompoundEyeVision();
  @override int    get id          => 15;
  @override String get displayName => 'Compound Eye';
  @override String get description =>
      'Hexagonal facet mosaic (pointy-top, radius 22 px). '
      'Each facet shows the colour of its centre pixel; borders are black.';
}

class InfraredVision extends VisionMode {
  const InfraredVision();
  @override int    get id          => 16;
  @override String get displayName => 'Infrared';
  @override String get description =>
      'Near-IR warm inversion. Luma inverted then mapped to a '
      'red-dominant warm cast (R×1.3, G×0.95, B×0.6).';
}

class UltravioletVision extends VisionMode {
  const UltravioletVision();
  @override int    get id          => 17;
  @override String get displayName => 'Ultraviolet';
  @override String get description =>
      'Spectrum-shifted UV trichromacy. Human red is invisible; '
      'green → red, blue → green, UV proxy (violet) → blue channel.';
}

class HighContrastVision extends VisionMode {
  const HighContrastVision();
  @override int    get id          => 18;
  @override String get displayName => 'High Contrast';
  @override String get description =>
      '4×4 tile CLAHE (clip factor 3). Per-tile histogram equalisation '
      'with bilinear LUT interpolation across tile boundaries.';
}

// ── Catalogue ──────────────────────────────────────────────────────────────────

/// All modes in display order. Use this directly as a ListView/DropdownButton
/// item source — it is a compile-time const, so it costs nothing at runtime.
const List<VisionMode> kAllVisionModes = [
  NormalVision(),
  // Animal
  DogVision(),
  CatVision(),
  BullVision(),
  BeeVision(),
  FrogVision(),
  EagleVision(),
  // Human colour-blindness (Viénot 1999)
  ProtanopiaVision(),
  DeuteranopiaVision(),
  TritanopiaVision(),
  // Synthetic
  MonochromeVision(),
  InvertedVision(),
  // Technology / sensor
  ThermalVision(),
  NightVisionMode(),
  EcholocationVision(),
  CompoundEyeVision(),
  InfraredVision(),
  UltravioletVision(),
  HighContrastVision(),
];
