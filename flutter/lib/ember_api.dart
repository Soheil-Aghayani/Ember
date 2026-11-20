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
    required this.nodes,
    this.usedBytes,
    this.totalBytes,
  });

  final String id;
  final String name;
  final bool canRefresh;
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
      nodes: (data['nodes'] as List? ?? const [])
          .map(EmberNode.fromMap)
          .toList(growable: false),
      usedBytes: (usage['used'] as num?)?.toInt(),
      totalBytes: (usage['total'] as num?)?.toInt(),
    );
  }
}

class EmberResult {
  const EmberResult({this.pingMs, this.mbps});

  final int? pingMs;
  final double? mbps;

  factory EmberResult.fromMap(Object? value) {
    final data = _map(value);
    return EmberResult(
      pingMs: (data['pingMs'] as num?)?.toInt(),
      mbps: (data['mbps'] as num?)?.toDouble(),
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

class EmberState {
  const EmberState({
    required this.isTv,
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
    this.progress,
    this.error,
    this.connectedAt,
    this.connectedNode,
    this.selectedNode,
    this.testProgress,
    this.notice,
    this.siteLabel,
    this.siteResults = const {},
  });

  final bool isTv;
  final String version;
  final String phase;
  final String selectedId;
  final String language;
  final bool autoFailover;
  final String appMode;
  final Set<String> appPackages;
  final Set<String> favorites;
  final List<EmberSubscription> subscriptions;
  final Map<String, EmberResult> results;
  final String? progress;
  final String? error;
  final int? connectedAt;
  final EmberNode? connectedNode;
  final EmberNode? selectedNode;
  final String? testProgress;
  final String? notice;
  final String? siteLabel;
  final Map<String, EmberSiteResult> siteResults;

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
      subscriptions: (data['subscriptions'] as List? ?? const [])
          .map(EmberSubscription.fromMap)
          .toList(growable: false),
      results: rawResults.map(
        (key, value) => MapEntry(key, EmberResult.fromMap(value)),
      ),
      progress: vpn['progress'] as String?,
      error: vpn['error'] as String?,
      connectedAt: (vpn['connectedAt'] as num?)?.toInt(),
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

  Future<void> connect() => _methods.invokeMethod<void>('connect');
  Future<void> disconnect() => _methods.invokeMethod<void>('disconnect');
  Future<void> selectServer(String id) =>
      _methods.invokeMethod<void>('selectServer', {'id': id});
  Future<void> toggleFavorite(String id) =>
      _methods.invokeMethod<void>('toggleFavorite', {'id': id});
  Future<void> testServers() => _methods.invokeMethod<void>('testServers');
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
  Future<void> removeSubscription(String id) =>
      _methods.invokeMethod<void>('removeSubscription', {'id': id});
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

  Future<List<EmberAppChoice>> listApps() async =>
      (await _methods.invokeListMethod<Object?>('listApps') ?? const [])
          .map(EmberAppChoice.fromMap)
          .toList(growable: false);

  Future<Uint8List?> getQr(String id) =>
      _methods.invokeMethod<Uint8List>('getQr', {'id': id});
  Future<void> copyServerLink(String id) =>
      _methods.invokeMethod<void>('copyServerLink', {'id': id});
  Future<String> getLogs() async =>
      await _methods.invokeMethod<String>('getLogs') ?? '';
  Future<bool> requestTile() async =>
      await _methods.invokeMethod<bool>('requestTile') ?? false;
  Future<bool> requestWidget() async =>
      await _methods.invokeMethod<bool>('requestWidget') ?? false;
  Future<void> openBatterySettings() =>
      _methods.invokeMethod<void>('openBatterySettings');
  Future<void> openAppSettings() =>
      _methods.invokeMethod<void>('openAppSettings');
  Future<void> openContact() => _methods.invokeMethod<void>('openContact');

  Future<int> _importCount(String method) async {
    final response = await _methods.invokeMapMethod<Object?, Object?>(method);
    return (response?['added'] as num?)?.toInt() ?? 0;
  }
}
