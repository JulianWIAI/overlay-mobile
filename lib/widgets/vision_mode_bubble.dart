import 'package:flutter/material.dart';

import '../native/processor_channel.dart';
import '../native/vision_mode.dart';

/// Floating action button showing the active mode name.
///
/// Tapping opens a [DraggableScrollableSheet] listing all [kAllVisionModes]
/// grouped by category.  Selecting a mode calls [VisionState.setMode] and
/// dismisses the sheet.
class VisionModeBubble extends StatelessWidget {
  const VisionModeBubble({super.key, required this.state});

  final VisionState state;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        return FloatingActionButton.extended(
          heroTag: 'vision_mode_fab',
          onPressed: () => _openSheet(context),
          icon: const Icon(Icons.remove_red_eye_outlined),
          label: Text(state.current.displayName),
        );
      },
    );
  }

  void _openSheet(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _ModeSheet(state: state),
    );
  }
}

// ── Bottom sheet ──────────────────────────────────────────────────────────────

class _ModeSheet extends StatelessWidget {
  const _ModeSheet({required this.state});

  final VisionState state;

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.55,
      minChildSize:     0.30,
      maxChildSize:     0.90,
      expand: false,
      builder: (context, scrollController) {
        return DecoratedBox(
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
          ),
          child: Column(
            children: [
              const _SheetHandle(),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                child: Text(
                  'Vision Mode',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              Expanded(
                child: ListenableBuilder(
                  listenable: state,
                  builder: (context, _) => ListView(
                    controller: scrollController,
                    padding: const EdgeInsets.only(bottom: 24),
                    children: _buildSections(context),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  List<Widget> _buildSections(BuildContext context) {
    final sections = <(String, List<VisionMode>)>[
      ('Animal', kAllVisionModes.whereType<DogVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<CatVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<BullVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<BeeVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<FrogVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<EagleVision>().cast<VisionMode>().toList()),
      ('Colour Blindness', kAllVisionModes.whereType<ProtanopiaVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<DeuteranopiaVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<TritanopiaVision>().cast<VisionMode>().toList()),
      ('Synthetic', kAllVisionModes.whereType<MonochromeVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<InvertedVision>().cast<VisionMode>().toList()),
      ('Technology / Sensor', kAllVisionModes.whereType<ThermalVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<NightVisionMode>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<EcholocationVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<CompoundEyeVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<InfraredVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<UltravioletVision>().cast<VisionMode>().toList()
        + kAllVisionModes.whereType<HighContrastVision>().cast<VisionMode>().toList()),
    ];

    final widgets = <Widget>[
      _ModeTile(mode: const NormalVision(), state: state),
      const Divider(height: 1),
    ];

    for (final (title, modes) in sections) {
      if (modes.isEmpty) continue;
      widgets.add(_SectionHeader(title: title));
      for (final mode in modes) {
        widgets.add(_ModeTile(mode: mode, state: state));
      }
    }
    return widgets;
  }
}

class _SheetHandle extends StatelessWidget {
  const _SheetHandle();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Container(
        width: 40,
        height: 4,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(80),
          borderRadius: BorderRadius.circular(2),
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Text(
        title.toUpperCase(),
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context).colorScheme.primary,
              letterSpacing: 1.2,
            ),
      ),
    );
  }
}

class _ModeTile extends StatelessWidget {
  const _ModeTile({required this.mode, required this.state});

  final VisionMode  mode;
  final VisionState state;

  @override
  Widget build(BuildContext context) {
    final isActive = state.current.id == mode.id;
    return ListTile(
      title: Text(mode.displayName),
      subtitle: Text(
        mode.description,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodySmall,
      ),
      trailing: isActive
          ? Icon(Icons.check_circle, color: Theme.of(context).colorScheme.primary)
          : null,
      selected: isActive,
      onTap: () {
        state.setMode(mode);
        Navigator.of(context).pop();
      },
    );
  }
}
