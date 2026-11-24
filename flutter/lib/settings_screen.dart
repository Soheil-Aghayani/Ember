import 'package:material_ui/material_ui.dart';

import 'ember_api.dart';
import 'ember_ui.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.ui, required this.onOpenHome});

  final EmberUi ui;
  final VoidCallback onOpenHome;

  @override
  Widget build(BuildContext context) {
    final state = ui.state;
    final copy = ui.strings;
    final labels = {
      'off': copy.get('appsAll'),
      'skip': copy.get('appsSkip'),
      'only': copy.get('appsOnly'),
    };
    final descriptions = {
      'skip': copy.get('appsSkipHelp'),
      'only': copy.get('appsOnlyHelp'),
    };

    return ListView(
      key: const PageStorageKey('settings'),
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 24),
      children: [
        EmberPageHeader(copy.get('settings')),
        const SizedBox(height: 8),
        _sectionTitle(context, copy.get('settingsLanguage')),
        EmberCard(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: _languageOptions(context),
        ),
        const SizedBox(height: 14),
        EmberCard(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: state.autoFailover,
            onChanged: (enabled) =>
                ui.run(() => ui.api.setAutoFailover(enabled)),
            title: Text(copy.get('autoSwitch')),
            subtitle: Text(copy.get('autoSwitchHelp')),
          ),
        ),
        const SizedBox(height: 14),
        _sectionTitle(context, copy.get('apps')),
        EmberCard(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: RadioGroup<String>(
            groupValue: state.appMode,
            onChanged: (value) {
              if (value != null) ui.run(() => ui.api.setAppMode(value));
            },
            child: Column(
              children: [
                for (final mode in ['off', 'skip', 'only'])
                  RadioListTile<String>(
                    value: mode,
                    title: Text(labels[mode]!),
                    subtitle: descriptions[mode] == null
                        ? null
                        : Text(descriptions[mode]!),
                    controlAffinity: ListTileControlAffinity.leading,
                  ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
          child: Text(
            copy.get('appsApply'),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        Row(
          children: [
            Expanded(
              child: Text(
                copy.get('appsInList'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            IconButton.filledTonal(
              tooltip: copy.get('addApp'),
              onPressed: () => _showAppPicker(context),
              icon: const SolarIcon('add_circle'),
            ),
          ],
        ),
        const SizedBox(height: 6),
        EmberCard(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: state.appPackages.isEmpty
              ? Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    copy.get('noApps'),
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                )
              : Column(
                  children: [
                    for (final packageName
                        in (state.appPackages.toList()..sort()))
                      ListTile(
                        title: Text(packageName),
                        trailing: IconButton(
                          tooltip: copy.get('remove'),
                          onPressed: () => ui.run(
                            () => ui.api.setAppPackages(
                              state.appPackages.difference({packageName}),
                            ),
                          ),
                          icon: const SolarIcon('trash_bin_trash'),
                        ),
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 14),
        if (state.isTv) _tvVpnCard(context) else _firefoxVpnCard(context),
        const SizedBox(height: 14),
        if (!state.isTv) ...[
          EmberActionTile(
            icon: 'power',
            title: copy.get('addTileTitle'),
            subtitle: copy.get('addTileHelp'),
            onPressed: _requestTile,
          ),
          const SizedBox(height: 8),
          EmberActionTile(
            icon: 'global',
            title: copy.get('addWidgetTitle'),
            subtitle: copy.get('addWidgetHelp'),
            onPressed: _requestWidget,
          ),
          const SizedBox(height: 8),
        ],
        EmberActionTile(
          icon: 'settings',
          title: copy.get('keepBgTitle'),
          subtitle: copy.get('keepBgHelp'),
          onPressed: () => ui.run(() => ui.api.openBatterySettings()),
        ),
      ],
    );
  }

  Widget _tvVpnCard(BuildContext context) {
    final copy = ui.strings;
    final colors = Theme.of(context).colorScheme;
    return EmberCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.get('firefoxVpnTvTitle'),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(copy.get('firefoxVpnTvHelp')),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: onOpenHome,
              icon: SolarIcon('home', color: colors.onPrimary),
              label: Text(copy.get('useEmberOnTv')),
            ),
          ),
        ],
      ),
    );
  }

  Widget _firefoxVpnCard(BuildContext context) {
    final copy = ui.strings;
    final colors = Theme.of(context).colorScheme;
    return EmberCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.get('firefoxVpnTitle'),
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(copy.get('firefoxVpnSteps')),
          const SizedBox(height: 8),
          Text(
            copy.get('firefoxVpnLimit'),
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: colors.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _openFirefoxVpn,
              icon: SolarIcon('global', color: colors.onPrimary),
              label: Text(copy.get('openFirefox')),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openFirefoxVpn() async {
    final openedFirefox = await ui.run(() => ui.api.openFirefoxVpn());
    if (openedFirefox == false) {
      ui.tell(ui.strings.get('firefoxNotInstalled'));
    }
  }

  Widget _languageOptions(BuildContext context) => RadioGroup<String>(
    groupValue: ui.state.language,
    onChanged: (value) {
      if (value != null) {
        ui.run(
          () => ui.api.setLanguage(value),
          success: ui.strings.get('languageChanged'),
        );
      }
    },
    child: Column(
      children: [
        _languageOption('system', ui.strings.get('system')),
        _languageOption('en', ui.strings.get('english')),
        _languageOption('fa', ui.strings.get('persian')),
      ],
    ),
  );

  Widget _languageOption(String code, String label) => RadioListTile<String>(
    value: code,
    title: Text(label),
    controlAffinity: ListTileControlAffinity.leading,
  );

  Widget _sectionTitle(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
    child: Text(
      title,
      style: Theme.of(context).textTheme.titleMedium
          ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );

  Future<void> _showAppPicker(BuildContext context) async {
    final appsFuture = ui.api.listApps();
    final search = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(ui.strings.get('addApp')),
          content: SizedBox(
            width: 520,
            height: 460,
            child: Column(
              children: [
                TextField(
                  controller: search,
                  autofocus: true,
                  decoration: InputDecoration(
                    labelText: ui.strings.get('search'),
                    prefixIcon: const Padding(
                      padding: EdgeInsets.all(12),
                      child: SolarIcon('magnifier'),
                    ),
                    border: const OutlineInputBorder(),
                  ),
                  onChanged: (_) => setDialogState(() {}),
                ),
                const SizedBox(height: 10),
                Expanded(
                  child: FutureBuilder<List<EmberAppChoice>>(
                    future: appsFuture,
                    builder: (context, snapshot) {
                      if (snapshot.connectionState != ConnectionState.done) {
                        return const Center(child: CircularProgressIndicator());
                      }
                      if (!snapshot.hasData || snapshot.data!.isEmpty) {
                        return Center(child: Text(ui.strings.get('noApps')));
                      }
                      final choices = snapshot.data!
                          .where(
                            (app) =>
                                !ui.state.appPackages.contains(app.packageName),
                          )
                          .where(
                            (app) => app.label.toLowerCase().contains(
                              search.text.toLowerCase(),
                            ),
                          )
                          .toList();
                      return ListView.builder(
                        itemCount: choices.length,
                        itemBuilder: (context, index) {
                          final app = choices[index];
                          return ListTile(
                            leading: const SolarIcon('global'),
                            title: Text(app.label),
                            onTap: () {
                              final packages = {
                                ...ui.state.appPackages,
                                app.packageName,
                              };
                              ui.run(() => ui.api.setAppPackages(packages));
                              Navigator.pop(context);
                            },
                          );
                        },
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () => Navigator.pop(context),
              icon: const SolarIcon('close'),
              label: Text(ui.strings.get('close')),
            ),
          ],
        ),
      ),
    );
    search.dispose();
  }

  Future<void> _requestTile() async {
    final added = await ui.run(() => ui.api.requestTile());
    if (added == false) ui.tell(ui.strings.get('addTileManual'));
  }

  Future<void> _requestWidget() async {
    final added = await ui.run(() => ui.api.requestWidget());
    if (added == false) ui.tell(ui.strings.get('addWidgetManual'));
  }
}
