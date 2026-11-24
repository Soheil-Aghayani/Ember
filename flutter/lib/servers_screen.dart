import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';

import 'ember_api.dart';
import 'ember_ui.dart';

class ServersScreen extends StatelessWidget {
  const ServersScreen({super.key, required this.ui});

  final EmberUi ui;

  @override
  Widget build(BuildContext context) {
    final state = ui.state;
    final copy = ui.strings;
    final entries = <_Entry>[];
    for (final subscription in state.subscriptions) {
      entries.add(_Entry.subscription(subscription));
      final nodes = subscription.nodes.toList()
        ..sort((a, b) => _compareNodes(state, a, b));
      entries.addAll(nodes.map(_Entry.node));
    }

    return CustomScrollView(
      key: const PageStorageKey('servers'),
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 10),
            child: EmberPageHeader(
              copy.get('servers'),
              trailing: _actions(context),
            ),
          ),
        ),
        if (state.testLimitReason != null)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: EmberCard(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                child: Row(
                  children: [
                    const SolarIcon('info_circle'),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        copy.get(switch (state.testLimitReason) {
                          'battery_saver' => 'testLimitedBattery',
                          'low_battery' => 'testLimitedCharge',
                          _ => 'testLimitedMetered',
                        }),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        if (state.subscriptions.isNotEmpty)
          SliverToBoxAdapter(child: _autoRow(context)),
        if (state.siteLabel != null)
          SliverToBoxAdapter(child: _siteSummary(context)),
        if (state.subscriptions.isEmpty)
          SliverToBoxAdapter(
            child: EmberCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(copy.get('emptyList')),
                  const SizedBox(height: 14),
                  FilledButton.icon(
                    onPressed: () => _showAddDialog(context),
                    icon: const SolarIcon('add_circle', color: Colors.white),
                    label: Text(copy.get('addServers')),
                  ),
                ],
              ),
            ),
          )
        else
          SliverList.builder(
            itemCount: entries.length,
            itemBuilder: (context, index) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: entries[index].subscription != null
                  ? _SubscriptionRow(
                      ui: ui,
                      subscription: entries[index].subscription!,
                    )
                  : _ServerRow(ui: ui, node: entries[index].node!),
            ),
          ),
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ],
    );
  }

  Widget _actions(BuildContext context) {
    final state = ui.state;
    final hasServers = state.subscriptions.isNotEmpty;
    final canTest = state.disconnected && hasServers;
    final isTesting = state.testProgress != null;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: ui.strings.get('checkSite'),
          onPressed: canTest ? () => _showSiteDialog(context) : null,
          icon: const SolarIcon('magnifier'),
        ),
        IconButton(
          tooltip: ui.strings.get(isTesting ? 'stop' : 'testSpeed'),
          onPressed: isTesting
              ? () => ui.run(() => ui.api.cancelTest())
              : canTest
              ? () => ui.run(() => ui.api.testServers())
              : null,
          icon: SolarIcon(isTesting ? 'close' : 'sun_2'),
        ),
        PopupMenuButton<String>(
          tooltip: ui.strings.get('addServers'),
          icon: const SolarIcon('add_circle'),
          onSelected: (action) => _runMenuAction(context, action),
          itemBuilder: (context) => [
            _menuItem('addLink', 'add_circle'),
            _menuItem('fromClipboard', 'copy'),
            if (!state.isTv) _menuItem('scanQr', 'qr'),
            _menuItem('fromFile', 'code'),
            _menuItem('warpFree', 'global'),
            _menuItem('warpThrough', 'share'),
          ],
        ),
      ],
    );
  }

  PopupMenuItem<String> _menuItem(String value, String icon) => PopupMenuItem(
    value: value,
    child: Row(
      children: [
        SolarIcon(icon, size: 20),
        const SizedBox(width: 12),
        Expanded(child: Text(ui.strings.get(value))),
      ],
    ),
  );

  Future<void> _runMenuAction(BuildContext context, String action) async {
    switch (action) {
      case 'addLink':
        await _showAddDialog(context);
        break;
      case 'fromClipboard':
        final count = await ui.run(() => ui.api.addFromClipboard());
        if (count != null) {
          ui.tell(ui.strings.get('serversAdded', {'count': count}));
        }
        break;
      case 'scanQr':
        final count = await ui.run(() => ui.api.scanQr());
        if (count != null) {
          ui.tell(ui.strings.get('serversAdded', {'count': count}));
        }
        break;
      case 'fromFile':
        final count = await ui.run(() => ui.api.pickFile());
        if (count != null) {
          ui.tell(ui.strings.get('serversAdded', {'count': count}));
        }
        break;
      case 'warpFree':
        final count = await ui.run(() => ui.api.addWarp());
        if (count != null) {
          ui.tell(ui.strings.get('serversAdded', {'count': count}));
        }
        break;
      case 'warpThrough':
        await ui.run(() => ui.api.addWarpThroughServer());
        break;
    }
  }

  Widget _autoRow(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: InkWell(
      onTap: () => ui.run(() => ui.api.selectServer('auto')),
      borderRadius: BorderRadius.circular(18),
      child: EmberCard(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            const SolarIcon('sun', size: 22),
            const SizedBox(width: 12),
            Expanded(child: Text(ui.strings.get('auto'))),
            if (ui.state.selectedId == 'auto')
              SolarIcon(
                'check_circle',
                color: Theme.of(context).colorScheme.primary,
              ),
          ],
        ),
      ),
    ),
  );

  Widget _siteSummary(BuildContext context) {
    final siteResults = ui.state.siteResults;
    final working = siteResults.values.where((result) => result.ok).length;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: EmberCard(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Text(
                ui.strings.get('siteSummary', {
                  'site': ui.state.siteLabel ?? '',
                  'working': working,
                  'total': siteResults.length,
                }),
              ),
            ),
            IconButton(
              tooltip: ui.strings.get('clear'),
              onPressed: () => ui.run(() => ui.api.clearSiteCheck()),
              icon: const SolarIcon('close'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showAddDialog(BuildContext context) async {
    final textController = TextEditingController();
    final nameController = TextEditingController();
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(ui.strings.get('addLink')),
          content: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: textController,
                  autofocus: true,
                  minLines: 1,
                  maxLines: 4,
                  decoration: InputDecoration(
                    labelText: ui.strings.get('linkLabel'),
                    border: const OutlineInputBorder(),
                  ),
                  onChanged: (_) => setDialogState(() {}),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: nameController,
                  decoration: InputDecoration(
                    labelText: ui.strings.get('nameOptional'),
                    border: const OutlineInputBorder(),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () => Navigator.pop(context, false),
              icon: const SolarIcon('close'),
              label: Text(ui.strings.get('cancel')),
            ),
            FilledButton.icon(
              onPressed: textController.text.trim().isEmpty
                  ? null
                  : () => Navigator.pop(context, true),
              icon: const SolarIcon('check_circle', color: Colors.white),
              label: Text(ui.strings.get('add')),
            ),
          ],
        ),
      ),
    );
    if (accepted == true && textController.text.trim().isNotEmpty) {
      final count = await ui.run(
        () => ui.api.addSubscription(
          textController.text.trim(),
          name: nameController.text.trim(),
        ),
      );
      if (count != null) {
        ui.tell(ui.strings.get('serversAdded', {'count': count}));
      }
    }
    textController.dispose();
    nameController.dispose();
  }

  Future<void> _showSiteDialog(BuildContext context) async {
    final controller = TextEditingController();
    String? selected;
    final url = await showDialog<String>(
      context: context,
      builder: (context) {
        final presets = [
          ('siteSpotify', 'https://apresolve.spotify.com/?type=spclient'),
          ('siteYoutube', 'https://www.youtube.com/'),
          ('siteChatgpt', 'https://api.openai.com/v1/models'),
        ];
        return StatefulBuilder(
          builder: (context, setDialogState) => AlertDialog(
            title: Text(ui.strings.get('siteTitle')),
            content: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 600),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(ui.strings.get('siteHelp')),
                  const SizedBox(height: 14),
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final (key, address) in presets)
                        ChoiceChip(
                          label: Text(ui.strings.get(key)),
                          selected: selected == key,
                          onSelected: (_) {
                            setDialogState(() {
                              selected = key;
                              controller.text = address;
                            });
                          },
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: controller,
                    autofocus: true,
                    decoration: InputDecoration(
                      labelText: ui.strings.get('siteHint'),
                      border: const OutlineInputBorder(),
                    ),
                    onChanged: (_) => setDialogState(() => selected = null),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton.icon(
                onPressed: () => Navigator.pop(context),
                icon: const SolarIcon('close'),
                label: Text(ui.strings.get('cancel')),
              ),
              FilledButton.icon(
                onPressed: controller.text.trim().isEmpty
                    ? null
                    : () => Navigator.pop(context, controller.text.trim()),
                icon: const SolarIcon('check_circle', color: Colors.white),
                label: Text(ui.strings.get('check')),
              ),
            ],
          ),
        );
      },
    );
    if (url != null) await ui.run(() => ui.api.checkSite(url));
    controller.dispose();
  }

  static int _compareNodes(EmberState state, EmberNode a, EmberNode b) {
    final aSite = state.siteResults[a.id];
    final bSite = state.siteResults[b.id];
    if (state.siteLabel != null) {
      int rank(EmberSiteResult? result) => result == null
          ? 1
          : result.ok
          ? 0
          : 2;
      final bySite = rank(aSite).compareTo(rank(bSite));
      if (bySite != 0) return bySite;
    }
    final byFavorite = (state.favorites.contains(b.id) ? 1 : 0).compareTo(
      state.favorites.contains(a.id) ? 1 : 0,
    );
    if (byFavorite != 0) return byFavorite;
    int recentRank(String id) {
      final index = state.recentIds.indexOf(id);
      return index < 0 ? 0x7fffffff : index;
    }

    final byRecent = recentRank(a.id).compareTo(recentRank(b.id));
    if (byRecent != 0) return byRecent;
    final aResult = state.results[a.id];
    final bResult = state.results[b.id];
    final aReliability =
        aResult?.successRate ?? (aResult?.pingMs == null ? 0.0 : 1.0);
    final bReliability =
        bResult?.successRate ?? (bResult?.pingMs == null ? 0.0 : 1.0);
    final byReliability = bReliability.compareTo(aReliability);
    if (byReliability != 0) return byReliability;
    final byJitter = (aResult?.jitterMs ?? 0x7fffffff).compareTo(
      bResult?.jitterMs ?? 0x7fffffff,
    );
    if (byJitter != 0) return byJitter;
    if (aResult?.mbps != null && bResult?.mbps != null) {
      final bySpeed = bResult!.mbps!.compareTo(aResult!.mbps!);
      if (bySpeed != 0) return bySpeed;
    }
    if (aResult?.pingMs != null && bResult?.pingMs != null) {
      final byPing = aResult!.pingMs!.compareTo(bResult!.pingMs!);
      if (byPing != 0) return byPing;
    }
    return a.title.toLowerCase().compareTo(b.title.toLowerCase());
  }
}

class _Entry {
  const _Entry.subscription(this.subscription) : node = null;
  const _Entry.node(this.node) : subscription = null;

  final EmberSubscription? subscription;
  final EmberNode? node;
}

class _SubscriptionRow extends StatelessWidget {
  const _SubscriptionRow({required this.ui, required this.subscription});

  final EmberUi ui;
  final EmberSubscription subscription;

  @override
  Widget build(BuildContext context) {
    final usage =
        subscription.usedBytes != null && subscription.totalBytes != null
        ? ' · ${_formatBytes(subscription.usedBytes!)} / ${_formatBytes(subscription.totalBytes!)}'
        : '';
    return EmberCard(
      padding: const EdgeInsets.fromLTRB(16, 6, 6, 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  subscription.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                Text(
                  '${ui.strings.get('serverCount', {'count': subscription.nodes.length})}$usage',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (subscription.canRefresh)
            IconButton(
              tooltip: ui.strings.get('refresh'),
              onPressed: () => _previewRefresh(context),
              icon: const SolarIcon('refresh'),
            ),
          IconButton(
            tooltip: ui.strings.get('remove'),
            onPressed: () => _confirmRemove(context),
            icon: const SolarIcon('trash_bin_trash'),
          ),
        ],
      ),
    );
  }

  Future<void> _previewRefresh(BuildContext context) async {
    final preview = await ui.run(() => ui.api.previewRefresh(subscription.id));
    if (preview == null || !context.mounted) return;
    final apply = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(ui.strings.get('refreshPreviewTitle')),
        content: Text(
          ui.strings.get('refreshPreviewBody', {
            'added': preview.added,
            'removed': preview.removed,
            'unchanged': preview.unchanged,
          }),
        ),
        actions: [
          TextButton.icon(
            onPressed: () => Navigator.pop(dialogContext, false),
            icon: const SolarIcon('close'),
            label: Text(ui.strings.get('cancel')),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(dialogContext, true),
            icon: const SolarIcon('check_circle', color: Colors.white),
            label: Text(ui.strings.get('refreshNow')),
          ),
        ],
      ),
    );
    if (apply == true) {
      await ui.run(
        () => ui.api.applyRefreshPreview(preview.token),
        success: ui.strings.get('updated'),
      );
    } else {
      await ui.run(() => ui.api.discardRefreshPreview(preview.token));
    }
  }

  Future<void> _confirmRemove(BuildContext context) async {
    final remove = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(ui.strings.get('remove')),
        content: Text(ui.strings.get('confirmRemove')),
        actions: [
          TextButton.icon(
            onPressed: () => Navigator.pop(context, false),
            icon: const SolarIcon('close'),
            label: Text(ui.strings.get('cancel')),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(context, true),
            icon: const SolarIcon('trash_bin_trash', color: Colors.white),
            label: Text(ui.strings.get('remove')),
          ),
        ],
      ),
    );
    if (remove == true) {
      await ui.run(() => ui.api.removeSubscription(subscription.id));
    }
  }
}

class _ServerRow extends StatelessWidget {
  const _ServerRow({required this.ui, required this.node});

  final EmberUi ui;
  final EmberNode node;

  @override
  Widget build(BuildContext context) {
    final state = ui.state;
    final selected = state.selectedId == node.id;
    final result = state.results[node.id];
    final site = state.siteResults[node.id];
    final colors = Theme.of(context).colorScheme;
    final speed = result?.mbps != null
        ? '${result!.mbps!.toStringAsFixed(1)} Mbps'
        : result?.pingMs != null
        ? '${result!.pingMs} ms'
        : null;
    final stability = result?.jitterMs == null || result?.successRate == null
        ? null
        : ui.strings.get('stabilityDetail', {
            'jitter': result!.jitterMs,
            'reliability': (result.successRate! * 100).round(),
          });
    return InkWell(
      onTap: () => ui.run(() => ui.api.selectServer(node.id)),
      borderRadius: BorderRadius.circular(16),
      child: EmberCard(
        padding: const EdgeInsets.fromLTRB(14, 10, 6, 10),
        child: Row(
          children: [
            EmberFlagBadge(node.flagCode, size: 32),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    node.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  Text(
                    [node.protocol.toUpperCase(), ?stability].join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: colors.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            if (speed != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  speed,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: result?.pingMs == null
                        ? colors.error
                        : colors.onSurfaceVariant,
                  ),
                ),
              ),
            if (site != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6),
                child: Text(
                  site.ok ? '${site.ms ?? ''} ms' : '×',
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: site.ok ? colors.tertiary : colors.error,
                  ),
                ),
              ),
            if (selected && node.canShare)
              IconButton(
                tooltip: ui.strings.get('showQr'),
                onPressed: () => _showQr(context),
                icon: const SolarIcon('qr'),
              ),
            IconButton(
              tooltip: ui.strings.get('favourite'),
              onPressed: () => ui.run(() => ui.api.toggleFavorite(node.id)),
              icon: SolarIcon(
                'star',
                color: state.favorites.contains(node.id)
                    ? colors.primary
                    : colors.onSurfaceVariant,
              ),
            ),
            if (selected)
              SolarIcon('check_circle', color: colors.primary)
            else
              const SizedBox(width: 22),
            const SizedBox(width: 8),
          ],
        ),
      ),
    );
  }

  Future<void> _showQr(BuildContext context) async {
    final future = ui.api.getQr(node.id);
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(node.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: FutureBuilder<Uint8List?>(
            future: future,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const SizedBox(
                  width: 260,
                  height: 260,
                  child: Center(child: CircularProgressIndicator()),
                );
              }
              if (snapshot.hasError || snapshot.data == null) {
                return Text(ui.strings.get('qrTooLong'));
              }
              return Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Image.memory(
                    snapshot.data!,
                    width: 260,
                    height: 260,
                    gaplessPlayback: true,
                  ),
                  const SizedBox(height: 12),
                  Text(ui.strings.get('qrHint'), textAlign: TextAlign.center),
                ],
              );
            },
          ),
        ),
        actions: [
          if (node.canShare)
            TextButton.icon(
              onPressed: () => ui.run(
                () => ui.api.copyServerLink(node.id),
                success: ui.strings.get('copied'),
              ),
              icon: const SolarIcon('copy'),
              label: Text(ui.strings.get('copyLink')),
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

String _formatBytes(int bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${value.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
}
