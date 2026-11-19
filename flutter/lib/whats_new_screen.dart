import 'package:material_ui/material_ui.dart';

import 'ember_ui.dart';

class WhatsNewScreen extends StatelessWidget {
  const WhatsNewScreen({super.key, required this.ui});

  final EmberUi ui;

  @override
  Widget build(BuildContext context) {
    final copy = ui.strings;
    return ListView(
      key: const PageStorageKey('whats-new'),
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 24),
      children: [
        EmberPageHeader(copy.get('whatsNew')),
        Text(
          copy.get('whatsNewIntro'),
          style: Theme.of(context).textTheme.bodyMedium
              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 14),
        EmberCard(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(
                    ui.state.version,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      copy.get('currentVersion'),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _FeatureLine(text: copy.get('whatsNewFlutter')),
              _FeatureLine(text: copy.get('whatsNewMint')),
              _FeatureLine(text: copy.get('whatsNewEngine')),
            ],
          ),
        ),
      ],
    );
  }
}

class _FeatureLine extends StatelessWidget {
  const _FeatureLine({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 8, right: 12, left: 4),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              shape: BoxShape.circle,
            ),
            child: const SizedBox.square(dimension: 6),
          ),
        ),
        Expanded(
          child: Text(text, style: Theme.of(context).textTheme.bodyLarge),
        ),
      ],
    ),
  );
}
