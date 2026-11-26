import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

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
        _sectionTitle(context, copy.get('appearance')),
        EmberCard(
          padding: EdgeInsets.zero,
          child: PopupMenuButton<String>(
            tooltip: copy.get('appearance'),
            onSelected: ui.onAppearanceChanged,
            itemBuilder: (context) => [
              for (final appearance in EmberUi.appearanceOptions)
                PopupMenuItem(
                  value: appearance,
                  child: Row(
                    children: [
                      Expanded(child: Text(copy.appearanceLabel(appearance))),
                      if (appearance == ui.appearance) ...[
                        const SizedBox(width: 10),
                        SolarIcon(
                          'check_circle',
                          size: 19,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                      ],
                    ],
                  ),
                ),
            ],
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          copy.appearanceLabel(ui.appearance),
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 3),
                        Text(
                          copy.get('appearanceHelp'),
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(
                                color: Theme.of(context)
                                    .colorScheme
                                    .onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  SolarIcon(
                    'alt_arrow_down',
                    size: 21,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ],
              ),
            ),
          ),
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
        const SizedBox(height: 8),
        EmberActionTile(
          icon: 'refresh',
          title: copy.get('autoReconnectTitle'),
          subtitle: copy.get('autoReconnectHelp'),
          onPressed: () => ui.run(() => ui.api.openAlwaysOnSettings()),
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
        _sectionTitle(context, copy.get('profiles')),
        _ProfilesSection(ui: ui),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
          child: Text(
            copy.get('profilesHelp'),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        const SizedBox(height: 14),
        _sectionTitle(context, copy.get('backupTitle')),
        _BackupSection(ui: ui),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
          child: Text(
            copy.get('backupHelp'),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
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
        const SizedBox(height: 8),
        EmberActionTile(
          icon: 'info_circle',
          title: copy.get('diagnosticsTitle'),
          subtitle: copy.get('diagnosticsHelp'),
          onPressed: () => _runDiagnostics(context),
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

  Future<void> _runDiagnostics(BuildContext context) async {
    final result = await ui.run(() => ui.api.runDiagnostics());
    if (result == null || !context.mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(ui.strings.get('diagnosticsTitle')),
        content: SizedBox(
          width: 560,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(ui.strings.get('diagnosticsPrivacy')),
                if (result.directIp != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    ui.strings.get('outsideIp'),
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                  SelectableText(result.directIp!),
                ],
                if (result.tunnelIp != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    ui.strings.get('tunnelIp'),
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                  SelectableText(result.tunnelIp!),
                ],
                if (result.directIp == null && result.tunnelIp == null) ...[
                  const SizedBox(height: 12),
                  Text(
                    '${ui.strings.get('outsideIp')}: ${ui.strings.get('ipUnavailable')}',
                  ),
                ],
                const SizedBox(height: 12),
                SelectableText(result.report),
              ],
            ),
          ),
        ),
        actions: [
          TextButton.icon(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: result.report));
              if (dialogContext.mounted) Navigator.pop(dialogContext);
              ui.tell(ui.strings.get('copied'));
            },
            icon: const SolarIcon('copy'),
            label: Text(ui.strings.get('copy')),
          ),
          TextButton.icon(
            onPressed: () => Navigator.pop(dialogContext),
            icon: const SolarIcon('close'),
            label: Text(ui.strings.get('close')),
          ),
        ],
      ),
    );
  }
}

class _ProfilesSection extends StatefulWidget {
  const _ProfilesSection({required this.ui});

  final EmberUi ui;

  @override
  State<_ProfilesSection> createState() => _ProfilesSectionState();
}

class _ProfilesSectionState extends State<_ProfilesSection> {
  final _name = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ui = widget.ui;
    final state = ui.state;
    final copy = ui.strings;
    return EmberCard(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _name,
                    maxLength: 36,
                    textInputAction: TextInputAction.done,
                    onSubmitted: (_) => _save(),
                    decoration: InputDecoration(
                      labelText: copy.get('profileName'),
                      counterText: '',
                      border: const OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filledTonal(
                  tooltip: copy.get('saveProfile'),
                  onPressed: _save,
                  icon: const SolarIcon('check_circle'),
                ),
              ],
            ),
          ),
          if (state.profiles.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 14),
              child: Align(
                alignment: AlignmentDirectional.centerStart,
                child: Text(
                  copy.get('noProfiles'),
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            )
          else
            for (final profile in state.profiles)
              ListTile(
                title: Text(profile.name),
                subtitle: Text(
                  '${profile.appMode} · ${profile.appPackages.length} ${copy.get('apps')}',
                ),
                leading: const SolarIcon('global'),
                trailing: Wrap(
                  spacing: 4,
                  children: [
                    IconButton(
                      tooltip: copy.get('applyProfile'),
                      onPressed: () async {
                        final done = await ui.run(
                          () => ui.api.applyProfile(profile.id),
                        );
                        if (done == true) ui.tell(copy.get('profileApplied'));
                      },
                      icon: const SolarIcon('check_circle'),
                    ),
                    IconButton(
                      tooltip: copy.get('remove'),
                      onPressed: () =>
                          ui.run(() => ui.api.deleteProfile(profile.id)),
                      icon: const SolarIcon('trash_bin_trash'),
                    ),
                  ],
                ),
              ),
        ],
      ),
    );
  }

  Future<void> _save() async {
    final ui = widget.ui;
    if (_name.text.trim().isEmpty) return;
    final saved = await ui.run(() => ui.api.saveProfile(_name.text.trim()));
    if (saved != null) {
      _name.clear();
      ui.tell(ui.strings.get('profileSaved'));
    }
  }
}

class _BackupSection extends StatelessWidget {
  const _BackupSection({required this.ui});

  final EmberUi ui;

  @override
  Widget build(BuildContext context) => EmberCard(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Column(
      children: [
        ListTile(
          leading: const SolarIcon('copy'),
          title: Text(ui.strings.get('createBackup')),
          onTap: () => _run(context, restore: false),
        ),
        const Divider(height: 1),
        ListTile(
          leading: const SolarIcon('refresh'),
          title: Text(ui.strings.get('restoreBackup')),
          onTap: () => _run(context, restore: true),
        ),
      ],
    ),
  );

  Future<void> _run(BuildContext context, {required bool restore}) async {
    final password = await _passwordDialog(context, restore: restore);
    if (password == null) return;
    final done = await ui.run(
      () => restore
          ? ui.api.importBackup(password)
          : ui.api.exportBackup(password),
    );
    if (done == true) {
      ui.tell(ui.strings.get(restore ? 'backupRestored' : 'backupExported'));
    }
  }

  Future<String?> _passwordDialog(
    BuildContext context, {
    required bool restore,
  }) async {
    final password = TextEditingController();
    final confirm = TextEditingController();
    String? error;
    final result = await showDialog<String>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: Text(
            ui.strings.get(restore ? 'restoreBackup' : 'createBackup'),
          ),
          content: SizedBox(
            width: 460,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(ui.strings.get('backupPasswordHelp')),
                const SizedBox(height: 14),
                TextField(
                  controller: password,
                  autofocus: true,
                  obscureText: true,
                  decoration: InputDecoration(
                    labelText: ui.strings.get('backupPassword'),
                    border: const OutlineInputBorder(),
                  ),
                ),
                if (!restore) ...[
                  const SizedBox(height: 10),
                  TextField(
                    controller: confirm,
                    obscureText: true,
                    onSubmitted: (_) => _submit(
                      dialogContext,
                      restore,
                      password,
                      confirm,
                      setDialogState,
                      (value) => error = value,
                    ),
                    decoration: InputDecoration(
                      labelText: ui.strings.get('confirmBackupPassword'),
                      border: const OutlineInputBorder(),
                    ),
                  ),
                ],
                if (error != null) ...[
                  const SizedBox(height: 8),
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Text(
                      error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () => Navigator.pop(dialogContext),
              icon: const SolarIcon('close'),
              label: Text(ui.strings.get('cancel')),
            ),
            FilledButton.icon(
              onPressed: () => _submit(
                dialogContext,
                restore,
                password,
                confirm,
                setDialogState,
                (value) => error = value,
              ),
              icon: const SolarIcon('check_circle', color: Colors.white),
              label: Text(
                ui.strings.get(restore ? 'restoreBackup' : 'createBackup'),
              ),
            ),
          ],
        ),
      ),
    );
    password.dispose();
    confirm.dispose();
    return result;
  }

  void _submit(
    BuildContext context,
    bool restore,
    TextEditingController password,
    TextEditingController confirm,
    StateSetter setState,
    ValueChanged<String?> setError,
  ) {
    if (password.text.runes.length < 8 || password.text.runes.length > 128) {
      setState(() => setError(ui.strings.get('passwordTooShort')));
      return;
    }
    if (!restore && password.text != confirm.text) {
      setState(() => setError(ui.strings.get('passwordMismatch')));
      return;
    }
    Navigator.pop(context, password.text);
  }
}
