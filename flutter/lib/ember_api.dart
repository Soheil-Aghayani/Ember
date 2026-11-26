import 'package:flutter/services.dart';

Map<String, dynamic> _map(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

class EmberNode {
  const EmberNode({
    required this.id,
    required this.title,
    required this.protocol,
    required this.host,
    required this.port,
    this.flagCode,
    this.canShare = false,
  });

  final String id;
  final String title;
  final String protocol;
  final String host;
  final int port;
  final String? flagCode;
  final bool canShare;

  factory EmberNode.fromMap(Object? value) {
    final data = _map(value);
    return EmberNode(
      id: data['id'] as String? ?? '',
      title: data['title'] as String? ?? '',
      protocol: data['protocol'] as String? ?? '',
      host: data['host'] as String? ?? '',
      port: (data['port'] as num?)?.toInt() ?? 0,
      flagCode: data['flagCode'] as String?,
      canShare: data['canShare'] as bool? ?? false,
    );
  }
}

class EmberSubscription {
  const EmberSubscription({
    required this.id,
    required this.name,
    required this.canRefresh,
    required this.canShare,
    required this.nodes,
    this.usedBytes,
    this.totalBytes,
  });

  final String id;
  final String name;
  final bool canRefresh;
  final bool canShare;
  final List<EmberNode> nodes;
  final int? usedBytes;
  final int? totalBytes;

  factory EmberSubscription.fromMap(Object? value) {
    final data = _map(value);
    final usage = _map(data['usage']);
    return EmberSubscription(
      id: data['id'] as String? ?? '',
      name: data['name'] as String? ?? '',
      canRefresh: data['canRefresh'] as bool? ?? false,
      canShare: data['canShare'] as bool? ?? false,
      nodes: (data['nodes'] as List? ?? const [])
          .map(EmberNode.fromMap)
          .toList(growable: false),
      usedBytes: (usage['used'] as num?)?.toInt(),
      totalBytes: (usage['total'] as num?)?.toInt(),
    );
  }
}

class EmberResult {
  const EmberResult({this.pingMs, this.mbps, this.jitterMs, this.successRate});

  final int? pingMs;
  final double? mbps;
  final int? jitterMs;
  final double? successRate;

  factory EmberResult.fromMap(Object? value) {
    final data = _map(value);
    return EmberResult(
      pingMs: (data['pingMs'] as num?)?.toInt(),
      mbps: (data['mbps'] as num?)?.toDouble(),
      jitterMs: (data['jitterMs'] as num?)?.toInt(),
      successRate: (data['successRate'] as num?)?.toDouble(),
    );
  }
}

class EmberSiteResult {
  const EmberSiteResult({required this.ok, this.code, this.ms});

  final bool ok;
  final int? code;
  final int? ms;

  factory EmberSiteResult.fromMap(Object? value) {
    final data = _map(value);
    return EmberSiteResult(
      ok: data['ok'] as bool? ?? false,
      code: (data['code'] as num?)?.toInt(),
      ms: (data['ms'] as num?)?.toInt(),
    );
  }
}

class EmberAppChoice {
  const EmberAppChoice(this.packageName, this.label);

  final String packageName;
  final String label;

  factory EmberAppChoice.fromMap(Object? value) {
    final data = _map(value);
    return EmberAppChoice(
      data['packageName'] as String? ?? '',
      data['label'] as String? ?? '',
    );
  }
}

class EmberDiagnostics {
  const EmberDiagnostics({required this.report, this.directIp, this.tunnelIp});

  final String report;
  final String? directIp;
  final String? tunnelIp;

  factory EmberDiagnostics.fromMap(Object? value) {
    final data = _map(value);
    return EmberDiagnostics(
      report: data['report'] as String? ?? '',
      directIp: data['directIp'] as String?,
      tunnelIp: data['tunnelIp'] as String?,
    );
  }
}

class EmberRefreshPreview {
  const EmberRefreshPreview({
    required this.token,
    required this.added,
    required this.removed,
    required this.unchanged,
    required this.total,
  });

  final String token;
  final int added;
  final int removed;
  final int unchanged;
  final int total;

  factory EmberRefreshPreview.fromMap(Object? value) {
    final data = _map(value);
    int number(String key) => (data[key] as num?)?.toInt() ?? 0;
    return EmberRefreshPreview(
      token: data['token'] as String? ?? '',
      added: number('added'),
      removed: number('removed'),
      unchanged: number('unchanged'),
      total: number('total'),
    );
  }
}

class EmberProfile {
  const EmberProfile({
    required this.id,
    required this.name,
    required this.selectedId,
    required this.appMode,
    required this.appPackages,
  });

  final String id;
  final String name;
  final String selectedId;
  final String appMode;
  final Set<String> appPackages;

  factory EmberProfile.fromMap(Object? value) {
    final data = _map(value);
    return EmberProfile(
      id: data['id'] as String? ?? '',
      name: data['name'] as String? ?? '',
      selectedId: data['selectedId'] as String? ?? 'auto',
      appMode: data['appMode'] as String? ?? 'off',
      appPackages: (data['appPackages'] as List? ?? const [])
          .whereType<String>()
          .toSet(),
    );
  }
}

class EmberState {
  const EmberState({
    required this.isTv,
    this.isEmulator = false,
    required this.version,
    required this.phase,
    required this.selectedId,
    required this.language,
    required this.autoFailover,
    required this.appMode,
    required this.appPackages,
    required this.favorites,
    required this.subscriptions,
    required this.results,
    this.recentIds = const [],
    this.profiles = const [],
    this.progress,
    this.error,
    this.connectedAt,
    this.uploadBytes = 0,
    this.downloadBytes = 0,
    this.connectedNode,
    this.selectedNode,
    this.testProgress,
    this.notice,
    this.siteLabel,
    this.siteResults = const {},
    this.testLimitReason,
  });

  final bool isTv;
  final bool isEmulator;
  final String version;
  final String phase;
  final String selectedId;
  final String language;
  final bool autoFailover;
  final String appMode;
  final Set<String> appPackages;
  final Set<String> favorites;
  final List<String> recentIds;
  final List<EmberProfile> profiles;
  final List<EmberSubscription> subscriptions;
  final Map<String, EmberResult> results;
  final String? progress;
  final String? error;
  final int? connectedAt;
  final int uploadBytes;
  final int downloadBytes;
  final EmberNode? connectedNode;
  final EmberNode? selectedNode;
  final String? testProgress;
  final String? notice;
  final String? siteLabel;
  final Map<String, EmberSiteResult> siteResults;
  final String? testLimitReason;

  bool get connected => phase == 'connected';
  bool get connecting => phase == 'connecting';
  bool get disconnected => phase == 'disconnected';
  List<EmberNode> get allNodes => subscriptions.expand((s) => s.nodes).toList();

  factory EmberState.fromMap(Map<String, dynamic> data) {
    final vpn = _map(data['vpn']);
    final settings = _map(data['settings']);
    final check = _map(data['siteCheck']);
    final rawResults = _map(data['results']);
    final rawSiteResults = _map(check['results']);
    return EmberState(
      isTv: data['isTv'] as bool? ?? false,
      isEmulator: data['isEmulator'] as bool? ?? false,
      version: data['version'] as String? ?? '',
      phase: vpn['phase'] as String? ?? 'disconnected',
      selectedId: data['selectedId'] as String? ?? 'auto',
      language: settings['language'] as String? ?? 'system',
      autoFailover: settings['autoFailover'] as bool? ?? true,
      appMode: settings['appMode'] as String? ?? 'off',
      appPackages: (settings['appPackages'] as List? ?? const [])
          .whereType<String>()
          .toSet(),
      favorites: (data['favorites'] as List? ?? const [])
          .whereType<String>()
          .toSet(),
      recentIds: (data['recentIds'] as List? ?? const [])
          .whereType<String>()
          .toList(growable: false),
      profiles: (data['profiles'] as List? ?? const [])
          .map(EmberProfile.fromMap)
          .toList(growable: false),
      subscriptions: (data['subscriptions'] as List? ?? const [])
          .map(EmberSubscription.fromMap)
          .toList(growable: false),
      results: rawResults.map(
        (key, value) => MapEntry(key, EmberResult.fromMap(value)),
      ),
      progress: vpn['progress'] as String?,
      error: vpn['error'] as String?,
      connectedAt: (vpn['connectedAt'] as num?)?.toInt(),
      uploadBytes: (vpn['uploadBytes'] as num?)?.toInt() ?? 0,
      downloadBytes: (vpn['downloadBytes'] as num?)?.toInt() ?? 0,
      connectedNode: vpn['node'] == null
          ? null
          : EmberNode.fromMap(vpn['node']),
      selectedNode: data['selectedNode'] == null
          ? null
          : EmberNode.fromMap(data['selectedNode']),
      testProgress: data['testProgress'] as String?,
      notice: data['notice'] as String?,
      siteLabel: check['label'] as String?,
      siteResults: rawSiteResults.map(
        (key, value) => MapEntry(key, EmberSiteResult.fromMap(value)),
      ),
      testLimitReason: data['testLimitReason'] as String?,
    );
  }
}

class EmberApi {
  static const _methods = MethodChannel('com.soheil.ember/app');
  static const _states = EventChannel('com.soheil.ember/state');

  Stream<EmberState> get changes => _states
      .receiveBroadcastStream()
      .where((value) => value != null)
      .map((value) => EmberState.fromMap(_map(value)))
      .asBroadcastStream();

  Future<EmberState> getState() async => EmberState.fromMap(
    _map(await _methods.invokeMethod<Object?>('getState')),
  );

  Future<String> getAppearance() async =>
      await _methods.invokeMethod<String>('getAppearance') ?? 'mint';

  Future<void> setAppearance(String appearance) =>
      _methods.invokeMethod<void>('setAppearance', {'appearance': appearance});

  Future<void> connect() => _methods.invokeMethod<void>('connect');
  Future<void> disconnect() => _methods.invokeMethod<void>('disconnect');
  Future<void> selectServer(String id) =>
      _methods.invokeMethod<void>('selectServer', {'id': id});
  Future<void> toggleFavorite(String id) =>
      _methods.invokeMethod<void>('toggleFavorite', {'id': id});
  Future<void> testServers({bool measureSpeed = true}) => _methods
      .invokeMethod<void>('testServers', {'measureSpeed': measureSpeed});
  Future<void> testDelay() => _methods.invokeMethod<void>('testDelay');
  Future<void> cancelTest() => _methods.invokeMethod<void>('cancelTest');

  Future<int> addSubscription(String text, {String name = ''}) async {
    final response = await _methods.invokeMapMethod<Object?, Object?>(
      'addSubscription',
      {'text': text, 'name': name},
    );
    return (response?['added'] as num?)?.toInt() ?? 0;
  }

  Future<int> addFromClipboard() => _importCount('addFromClipboard');
  Future<int?> pickFile() => _importCount('pickFile');
  Future<int?> scanQr() => _importCount('scanQr');
  Future<int> addWarp() => _importCount('addWarp');
  Future<void> addWarpThroughServer() =>
      _methods.invokeMethod<void>('addWarpThroughServer');
  Future<void> refreshSubscription(String id) =>
      _methods.invokeMethod<void>('refreshSubscription', {'id': id});
  Future<EmberRefreshPreview> previewRefresh(String id) async =>
      EmberRefreshPreview.fromMap(
        await _methods.invokeMapMethod<Object?, Object?>('previewRefresh', {
          'id': id,
        }),
      );
  Future<bool> applyRefreshPreview(String token) async =>
      (await _methods.invokeMethod<bool>('applyRefreshPreview', {
        'token': token,
      })) ??
      false;
  Future<bool> discardRefreshPreview(String token) async =>
      (await _methods.invokeMethod<bool>('discardRefreshPreview', {
        'token': token,
      })) ??
      false;
  Future<void> removeSubscription(String id) =>
      _methods.invokeMethod<void>('removeSubscription', {'id': id});
  Future<void> removeNode(String id, String subscriptionId) =>
      _methods.invokeMethod<void>('removeNode', {
        'id': id,
        'subscriptionId': subscriptionId,
      });
  Future<void> checkSite(String url) =>
      _methods.invokeMethod<void>('checkSite', {'url': url});
  Future<void> clearSiteCheck() =>
      _methods.invokeMethod<void>('clearSiteCheck');
  Future<void> clearNotice() => _methods.invokeMethod<void>('clearNotice');
  Future<void> setLanguage(String code) =>
      _methods.invokeMethod<void>('setLanguage', {'code': code});
  Future<void> setAutoFailover(bool enabled) =>
      _methods.invokeMethod<void>('setAutoFailover', {'enabled': enabled});
  Future<void> setAppMode(String mode) =>
      _methods.invokeMethod<void>('setAppMode', {'mode': mode});
  Future<void> setAppPackages(Set<String> packages) => _methods
      .invokeMethod<void>('setAppPackages', {'packages': packages.toList()});
  Future<bool> saveProfile(String name) async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'saveProfile',
      {'name': name},
    );
    return result?['id'] is String;
  }

  Future<bool> applyProfile(String id) async =>
      (await _methods.invokeMethod<bool>('applyProfile', {'id': id})) ?? false;
  Future<bool> deleteProfile(String id) async =>
      (await _methods.invokeMethod<bool>('deleteProfile', {'id': id})) ?? false;

  Future<List<EmberAppChoice>> listApps() async =>
      (await _methods.invokeListMethod<Object?>('listApps') ?? const [])
          .map(EmberAppChoice.fromMap)
          .toList(growable: false);

  Future<Uint8List?> getQr(String id) =>
      _methods.invokeMethod<Uint8List>('getQr', {'id': id});
  Future<Uint8List?> getSubscriptionQr(String id) =>
      _methods.invokeMethod<Uint8List>('getSubscriptionQr', {'id': id});
  Future<void> copyServerLink(String id) =>
      _methods.invokeMethod<void>('copyServerLink', {'id': id});
  Future<void> shareServer(String id) =>
      _methods.invokeMethod<void>('shareServer', {'id': id});
  Future<void> copySubscriptionLink(String id) =>
      _methods.invokeMethod<void>('copySubscriptionLink', {'id': id});
  Future<void> shareSubscription(String id) =>
      _methods.invokeMethod<void>('shareSubscription', {'id': id});
  Future<String> getLogs() async =>
      await _methods.invokeMethod<String>('getLogs') ?? '';
  Future<EmberDiagnostics> runDiagnostics() async => EmberDiagnostics.fromMap(
    await _methods.invokeMapMethod<Object?, Object?>('runDiagnostics'),
  );
  Future<bool> exportBackup(String password) async =>
      (await _methods.invokeMethod<bool>('exportBackup', {
        'password': password,
      })) ??
      false;
  Future<bool> importBackup(String password) async =>
      (await _methods.invokeMethod<bool>('importBackup', {
        'password': password,
      })) ??
      false;
  Future<bool> requestTile() async =>
      await _methods.invokeMethod<bool>('requestTile') ?? false;
  Future<bool> requestWidget() async =>
      await _methods.invokeMethod<bool>('requestWidget') ?? false;
  Future<void> openBatterySettings() =>
      _methods.invokeMethod<void>('openBatterySettings');
  Future<void> openAlwaysOnSettings() =>
      _methods.invokeMethod<void>('openAlwaysOnSettings');
  Future<bool> openFirefoxVpn() async =>
      await _methods.invokeMethod<bool>('openFirefoxVpn') ?? false;
  Future<void> openAppSettings() =>
      _methods.invokeMethod<void>('openAppSettings');
  Future<void> openContact() => _methods.invokeMethod<void>('openContact');
  Future<void> openUpdates() => _methods.invokeMethod<void>('openUpdates');

  Future<int> _importCount(String method) async {
    final response = await _methods.invokeMapMethod<Object?, Object?>(method);
    return (response?['added'] as num?)?.toInt() ?? 0;
  }
}
