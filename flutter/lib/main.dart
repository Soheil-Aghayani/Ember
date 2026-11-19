import 'dart:async';
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'about_screen.dart';
import 'ember_api.dart';
import 'ember_strings.dart';
import 'ember_ui.dart';
import 'home_screen.dart';
import 'servers_screen.dart';
import 'settings_screen.dart';

void main() => runApp(const EmberApp());

class EmberApp extends StatefulWidget {
  const EmberApp({super.key});

  @override
  State<EmberApp> createState() => _EmberAppState();
}

class _EmberAppState extends State<EmberApp> {
  final _api = EmberApi();
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();
  StreamSubscription<EmberState>? _subscription;
  EmberState? _state;
  String? _lastNotice;

  @override
  void initState() {
    super.initState();
    _subscription = _api.changes.listen(_accept, onError: (_) {});
    _loadState();
  }

  Future<void> _loadState() async {
    try {
      _accept(await _api.getState());
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Ember could not load its Android state.');
    }
  }

  void _accept(EmberState next) {
    if (!mounted) return;
    setState(() => _state = next);
    if (next.notice == null) {
      _lastNotice = null;
    } else if (next.notice != _lastNotice) {
      _lastNotice = next.notice;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _showMessage(next.notice!);
        unawaited(_api.clearNotice());
      });
    }
  }

  Future<T?> _run<T>(Future<T> Function() action, {String? success}) async {
    try {
      final value = await action();
      if (success != null) _showMessage(success);
      return value;
    } on PlatformException catch (error) {
      _showMessage(
        error.message?.trim().isNotEmpty == true
            ? error.message!
            : 'The operation could not be completed.',
      );
    } on MissingPluginException {
      _showMessage(
        'The Android bridge is unavailable. Restart Ember and try again.',
      );
    } catch (_) {
      _showMessage('The operation could not be completed.');
    }
    return null;
  }

  void _showMessage(String message) {
    _messengerKey.currentState
      ?..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final brightness = MediaQuery.platformBrightnessOf(context);
    return MaterialApp(
      title: 'Ember',
      debugShowCheckedModeBanner: false,
      theme: _theme(Brightness.light),
      darkTheme: _theme(Brightness.dark),
      themeMode: ThemeMode.system,
      scaffoldMessengerKey: _messengerKey,
      home: _state == null
          ? const _LaunchScreen()
          : _EmberShell(
              ui: EmberUi(
                api: _api,
                state: _state!,
                strings: EmberStrings(_effectivePersian(_state!)),
                run: _run,
                tell: _showMessage,
              ),
            ),
      builder: (context, child) {
        SystemChrome.setSystemUIOverlayStyle(
          SystemUiOverlayStyle(
            statusBarColor: Colors.transparent,
            statusBarIconBrightness: brightness == Brightness.dark
                ? Brightness.light
                : Brightness.dark,
            systemNavigationBarColor: Theme.of(context).colorScheme.surface,
            systemNavigationBarIconBrightness: brightness == Brightness.dark
                ? Brightness.light
                : Brightness.dark,
          ),
        );
        return child ?? const SizedBox.shrink();
      },
    );
  }

  bool _effectivePersian(EmberState state) =>
      state.language == 'fa' ||
      (state.language == 'system' &&
          ui.PlatformDispatcher.instance.locale.languageCode == 'fa');

  ThemeData _theme(Brightness brightness) {
    const light = ColorScheme.light(
      primary: Color(0xFF9C3107),
      onPrimary: Color(0xFFFFFFFF),
      primaryContainer: Color(0xFFFBE6DA),
      onPrimaryContainer: Color(0xFF5A1C00),
      secondary: Color(0xFF6A3BD1),
      onSecondary: Color(0xFFFFFFFF),
      secondaryContainer: Color(0xFFECE5FA),
      onSecondaryContainer: Color(0xFF2A1566),
      tertiary: Color(0xFF1E7A3C),
      error: Color(0xFFBA1A1A),
      surface: Color(0xFFFFFFFF),
      onSurface: Color(0xFF1F1B16),
      onSurfaceVariant: Color(0xFF534C44),
      outline: Color(0xFF8A8277),
      outlineVariant: Color(0xFFE4DDD1),
    );
    const dark = ColorScheme.dark(
      primary: Color(0xFFFF8D5C),
      onPrimary: Color(0xFF1A1714),
      primaryContainer: Color(0xFF3A2218),
      onPrimaryContainer: Color(0xFFFFD9C7),
      secondary: Color(0xFFB79CFF),
      onSecondary: Color(0xFF2A1566),
      secondaryContainer: Color(0xFF2C2540),
      onSecondaryContainer: Color(0xFFE6DCFF),
      tertiary: Color(0xFF5FD17F),
      error: Color(0xFFFFB4AB),
      surface: Color(0xFF24201C),
      onSurface: Color(0xFFF3EEE6),
      onSurfaceVariant: Color(0xFFCDC5BA),
      outline: Color(0xFF80786C),
      outlineVariant: Color(0xFF3A342D),
    );
    final scheme = brightness == Brightness.dark ? dark : light;
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      fontFamily: 'Inter',
      scaffoldBackgroundColor: brightness == Brightness.dark
          ? const Color(0xFF1A1714)
          : const Color(0xFFFAF7F2),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(
          fontSize: 32,
          height: 1.2,
          fontWeight: FontWeight.w700,
        ),
        headlineMedium: TextStyle(
          fontSize: 28,
          height: 1.22,
          fontWeight: FontWeight.w700,
        ),
        headlineSmall: TextStyle(
          fontSize: 22,
          height: 1.28,
          fontWeight: FontWeight.w600,
        ),
        titleLarge: TextStyle(
          fontSize: 20,
          height: 1.25,
          fontWeight: FontWeight.w600,
        ),
        titleMedium: TextStyle(
          fontSize: 17,
          height: 1.3,
          fontWeight: FontWeight.w600,
        ),
        bodyLarge: TextStyle(fontSize: 17, height: 1.35),
        bodyMedium: TextStyle(fontSize: 15, height: 1.35),
        bodySmall: TextStyle(fontSize: 13, height: 1.4),
        labelLarge: TextStyle(
          fontSize: 15,
          height: 1.3,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        color: scheme.surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide(color: scheme.outlineVariant.withValues(alpha: .75)),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: scheme.surface.withValues(alpha: .96),
        indicatorColor: scheme.secondaryContainer,
      ),
      navigationRailTheme: NavigationRailThemeData(
        backgroundColor: Colors.transparent,
        indicatorColor: scheme.secondaryContainer,
        selectedIconTheme: IconThemeData(color: scheme.onSecondaryContainer),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 52),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
    );
  }
}

class _LaunchScreen extends StatelessWidget {
  const _LaunchScreen();

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Image.asset('assets/brand/ember-logo.png', width: 54, height: 54),
    ),
  );
}

enum _Page { home, servers, settings, about }

class _EmberShell extends StatefulWidget {
  const _EmberShell({required this.ui});

  final EmberUi ui;

  @override
  State<_EmberShell> createState() => _EmberShellState();
}

class _EmberShellState extends State<_EmberShell> {
  _Page _page = _Page.home;

  static const _pages = _Page.values;
  static const _icons = ['power', 'global', 'settings', 'info_circle'];
  static const _labels = ['home', 'servers', 'settings', 'about'];

  @override
  Widget build(BuildContext context) {
    final ui = widget.ui;
    final copy = ui.strings;
    final rtl = copy.persian;
    final selected = _pages.indexOf(_page);
    final content = IndexedStack(
      index: selected,
      children: [
        TickerMode(
          enabled: selected == 0,
          child: HomeScreen(
            ui: ui,
            onOpenServers: () => _select(_Page.servers),
          ),
        ),
        TickerMode(
          enabled: selected == 1,
          child: ServersScreen(ui: ui),
        ),
        TickerMode(
          enabled: selected == 2,
          child: SettingsScreen(ui: ui),
        ),
        TickerMode(
          enabled: selected == 3,
          child: AboutScreen(ui: ui),
        ),
      ],
    );

    return Directionality(
      textDirection: rtl ? TextDirection.rtl : TextDirection.ltr,
      child: Scaffold(
        body: SafeArea(
          child: ui.state.isTv
              ? Row(
                  textDirection: TextDirection.ltr,
                  children: [
                    _TvRail(
                      selectedIndex: selected,
                      label: (key) => copy.get(key),
                      onSelect: (index) => _select(_pages[index]),
                    ),
                    VerticalDivider(
                      width: 1,
                      thickness: 1,
                      color: Theme.of(context).colorScheme.outlineVariant,
                    ),
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: content,
                      ),
                    ),
                  ],
                )
              : content,
        ),
        bottomNavigationBar: ui.state.isTv
            ? null
            : NavigationBar(
                selectedIndex: selected,
                onDestinationSelected: (index) => _select(_pages[index]),
                destinations: List.generate(_pages.length, (index) {
                  return NavigationDestination(
                    icon: SolarIcon(_icons[index], size: 23),
                    selectedIcon: SolarIcon(
                      _icons[index],
                      size: 23,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    label: copy.get(_labels[index]),
                  );
                }),
              ),
      ),
    );
  }

  void _select(_Page page) => setState(() => _page = page);
}

class _TvRail extends StatelessWidget {
  const _TvRail({
    required this.selectedIndex,
    required this.label,
    required this.onSelect,
  });

  final int selectedIndex;
  final String Function(String key) label;
  final ValueChanged<int> onSelect;

  static const _icons = ['power', 'global', 'settings', 'info_circle'];
  static const _labels = ['home', 'servers', 'settings', 'about'];

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return FocusTraversalGroup(
      child: SizedBox(
        width: 232,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 20, 16, 26),
              child: Row(
                children: [
                  Image.asset(
                    'assets/brand/ember-logo.png',
                    width: 42,
                    height: 42,
                  ),
                  const SizedBox(width: 12),
                  Text('Ember', style: Theme.of(context).textTheme.titleLarge),
                ],
              ),
            ),
            for (var index = 0; index < _icons.length; index++)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 4,
                ),
                child: Material(
                  color: selectedIndex == index
                      ? colors.secondaryContainer
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(16),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => onSelect(index),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 16,
                      ),
                      child: Row(
                        children: [
                          SolarIcon(
                            _icons[index],
                            color: selectedIndex == index
                                ? colors.onSecondaryContainer
                                : colors.onSurface,
                          ),
                          const SizedBox(width: 14),
                          Text(
                            label(_labels[index]),
                            style: Theme.of(context).textTheme.bodyLarge
                                ?.copyWith(
                                  color: selectedIndex == index
                                      ? colors.onSecondaryContainer
                                      : colors.onSurface,
                                  fontWeight: selectedIndex == index
                                      ? FontWeight.w600
                                      : FontWeight.w400,
                                ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
