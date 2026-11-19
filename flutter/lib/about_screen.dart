import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'ember_ui.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key, required this.ui});

  final EmberUi ui;

  @override
  Widget build(BuildContext context) {
    final copy = ui.strings;
    return ListView(
      key: const PageStorageKey('about'),
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 24),
      children: [
        EmberPageHeader(copy.get('about')),
        const SizedBox(height: 8),
        EmberCard(
          child: Column(
            children: [
              _aboutRow(context, copy.get('version'), ui.state.version),
              _aboutRow(context, copy.get('engine'), 'Xray-core 26.10.31'),
              _aboutRow(context, copy.get('tunnel'), 'hev-socks5-tunnel'),
            ],
          ),
        ),
        const SizedBox(height: 10),
        EmberActionTile(
          icon: 'share',
          title: copy.get('contact'),
          subtitle: 'Telegram  @env_soh',
          onPressed: () => ui.run(() => ui.api.openContact()),
        ),
        const SizedBox(height: 10),
        EmberActionTile(
          icon: 'code',
          title: copy.get('logs'),
          onPressed: () => _showLogs(context),
        ),
        const SizedBox(height: 16),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Text(
            copy.get('aboutBlurb'),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    );
  }

  Widget _aboutRow(BuildContext context, String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      children: [
        Expanded(
          flex: 4,
          child: Text(
            label,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        Expanded(flex: 6, child: Text(value)),
      ],
    ),
  );

  Future<void> _showLogs(BuildContext context) async {
    final future = ui.api.getLogs();
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(ui.strings.get('logs')),
        content: SizedBox(
          width: 720,
          height: 420,
          child: FutureBuilder<String>(
            future: future,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const Center(child: CircularProgressIndicator());
              }
              final text = snapshot.data?.trim() ?? '';
              return SingleChildScrollView(
                child: SelectableText(
                  text.isEmpty ? ui.strings.get('noLogs') : text,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              );
            },
          ),
        ),
        actions: [
          TextButton.icon(
            onPressed: () async {
              final text = await ui.run(() => ui.api.getLogs());
              if (text != null) {
                await Clipboard.setData(ClipboardData(text: text));
                ui.tell(ui.strings.get('copied'));
              }
            },
            icon: const SolarIcon('copy'),
            label: Text(ui.strings.get('copy')),
          ),
          TextButton.icon(
            onPressed: () => Navigator.pop(context),
            icon: const SolarIcon('close'),
            label: Text(ui.strings.get('close')),
          ),
        ],
      ),
    );
  }
}
