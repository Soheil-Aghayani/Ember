import 'dart:async';
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import 'about_screen.dart';
import 'ember_api.dart';
import 'ember_strings.dart';
import 'ember_ui.dart';
import 'home_screen.dart';
import 'logs_screen.dart';
import 'servers_screen.dart';
import 'settings_screen.dart';
import 'whats_new_screen.dart';

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
  String _appearance = 'mint';

  @override
  void initState() {
    super.initState();
    _subscription = _api.changes.listen(_accept, onError: (_) {});
    _loadState();
    _loadAppearance();
  }

  Future<void> _loadAppearance() async {
    try {
      final appearance = await _api.getAppearance();
      if (mounted && const {'system', 'mint', 'dark'}.contains(appearance)) {
        setState(() => _appearance = appearance);
      }
    } catch (_) {
      // Keep the Mint default if the native preference is unavailable.
    }
  }

  Future<void> _setAppearance(String appearance) async {
    if (!const {'system', 'mint', 'dark'}.contains(appearance) ||
        appearance == _appearance) {
      return;
    }
    final previous = _appearance;
    setState(() => _appearance = appearance);
    try {
      await _api.setAppearance(appearance);
    } catch (_) {
      if (!mounted) return;
      setState(() => _appearance = previous);
      _showMessage(
        EmberStrings(_effectivePersian(_state!)).get('appearanceSaveFailed'),
      );
    }
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
      final updateVersion = next.notice!.startsWith('update_available:')
          ? next.notice!.substring('update_available:'.length)
          : null;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        if (updateVersion != null) {
          _showUpdateAvailable(updateVersion, next);
        } else {
          _showMessage(next.notice!);
        }
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

  void _showUpdateAvailable(String version, EmberState state) {
    final strings = EmberStrings(_effectivePersian(state));
    _messengerKey.currentState
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(strings.get('updateAvailable', {'version': version})),
          duration: const Duration(seconds: 12),
          action: SnackBarAction(
            label: strings.get('viewUpdate'),
            onPressed: () => unawaited(_api.openUpdates().catchError((_) {})),
          ),
        ),
      );
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isTv = _state?.isTv ?? false;
    final themeMode = switch (_appearance) {
      'mint' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };
    return MaterialApp(
      title: 'Ember',
      debugShowCheckedModeBanner: false,
      theme: _theme(Brightness.light, isTv: isTv),
      darkTheme: _theme(Brightness.dark, isTv: isTv),
      themeMode: themeMode,
      scaffoldMessengerKey: _messengerKey,
      home: _state == null
          ? const _LaunchScreen()
          : _EmberShell(
              ui: EmberUi(
                api: _api,
                state: _state!,
                strings: EmberStrings(_effectivePersian(_state!)),
                appearance: _appearance,
                onAppearanceChanged: (value) => unawaited(
                  _setAppearance(value),
                ),
                run: _run,
                tell: _showMessage,
              ),
            ),
      builder: (context, child) {
        final brightness = Theme.of(context).brightness;
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

  ThemeData _theme(Brightness brightness, {required bool isTv}) {
    const light = ColorScheme.light(
      primary: EmberColors.mintPrimary,
      onPrimary: Color(0xFFFFFFFF),
      primaryContainer: Color(0xFFD7EEE5),
      onPrimaryContainer: Color(0xFF0A4E3D),
      secondary: EmberColors.mintOrb,
      onSecondary: Color(0xFFFFFFFF),
      secondaryContainer: EmberColors.mintSurfaceVariant,
      onSecondaryContainer: Color(0xFF0A6B52),
      tertiary: Color(0xFF1E7A3C),
      error: Color(0xFFBA1A1A),
      surface: Color(0xFFFFFFFF),
      onSurface: Color(0xFF231712),
      onSurfaceVariant: Color(0xFF51443C),
      outline: Color(0xFF7E978A),
      outlineVariant: EmberColors.mintOutline,
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
      focusColor: isTv ? EmberFocus.overlay(scheme.primary, isTv: true) : null,
      fontFamily: 'Inter',
      scaffoldBackgroundColor: brightness == Brightness.dark
          ? const Color(0xFF1A1714)
          : EmberColors.mintBackground,
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
        color: brightness == Brightness.dark
            ? scheme.surface
            : scheme.surface.withValues(alpha: .78),
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide(color: scheme.outlineVariant.withValues(alpha: .75)),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: brightness == Brightness.dark
            ? scheme.surface.withValues(alpha: .96)
            : scheme.surface.withValues(alpha: .84),
        indicatorColor: brightness == Brightness.dark
            ? scheme.secondaryContainer
            : scheme.primaryContainer,
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

enum _Page { home, servers, settings, logs, whatsNew, about }

class _EmberShell extends StatefulWidget {
  const _EmberShell({required this.ui});

  final EmberUi ui;

  @override
  State<_EmberShell> createState() => _EmberShellState();
}

class _EmberShellState extends State<_EmberShell> {
  _Page _page = _Page.home;

  static const _tvPages = [
    _Page.home,
    _Page.servers,
    _Page.settings,
    _Page.logs,
    _Page.whatsNew,
    _Page.about,
  ];
  static const _phonePages = [
    _Page.home,
    _Page.servers,
    _Page.settings,
    _Page.about,
  ];
  static const _phoneIcons = ['home', 'server_2', 'settings', 'info_circle'];
  static const _phoneLabels = ['home', 'servers', 'settings', 'about'];

  @override
  Widget build(BuildContext context) {
    final ui = widget.ui;
    final copy = ui.strings;
    final rtl = copy.persian;
    final selected = _Page.values.indexOf(_page);
    final tvSelected = _tvPages.indexOf(_page);
    final phoneSelected = _phonePages.indexOf(_page);
    final phoneNavigationIndex = phoneSelected < 0
        ? _phonePages.indexOf(_Page.about)
        : phoneSelected;
    final content = _PageMotionStack(
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
          child: SettingsScreen(ui: ui, onOpenHome: () => _select(_Page.home)),
        ),
        LogsScreen(ui: ui),
        WhatsNewScreen(ui: ui),
        TickerMode(
          enabled: selected == 5,
          child: AboutScreen(
            ui: ui,
            onOpenWhatsNew: () => _select(_Page.whatsNew),
          ),
        ),
      ],
    );

    return Directionality(
      textDirection: rtl ? TextDirection.rtl : TextDirection.ltr,
      child: Scaffold(
        body: Stack(
          children: [
            const Positioned.fill(child: EmberBackdrop()),
            SafeArea(
              child: ui.state.isTv
                  ? Row(
                      textDirection: TextDirection.ltr,
                      children: [
                        _TvRail(
                          selectedIndex: tvSelected,
                          label: (key) => copy.get(key),
                          onSelect: (index) => _select(_tvPages[index]),
                        ),
                        VerticalDivider(
                          width: 1,
                          thickness: 1,
                          color: Colors.white.withValues(alpha: .7),
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
          ],
        ),
        bottomNavigationBar: ui.state.isTv
            ? null
            : NavigationBar(
                selectedIndex: phoneNavigationIndex,
                onDestinationSelected: (index) => _select(_phonePages[index]),
                destinations: List.generate(_phonePages.length, (index) {
                  return NavigationDestination(
                    icon: SolarIcon(_phoneIcons[index], size: 23),
                    selectedIcon: SolarIcon(
                      _phoneIcons[index],
                      size: 23,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    label: copy.get(_phoneLabels[index]),
                  );
                }),
              ),
      ),
    );
  }

  void _select(_Page page) => setState(() => _page = page);
}

class _PageMotionStack extends StatefulWidget {
  const _PageMotionStack({required this.index, required this.children});

  final int index;
  final List<Widget> children;

  @override
  State<_PageMotionStack> createState() => _PageMotionStackState();
}

class _PageMotionStackState extends State<_PageMotionStack> {
  final Set<int> _outgoingIndices = <int>{};

  @override
  void didUpdateWidget(covariant _PageMotionStack oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.index != widget.index) {
      _outgoingIndices
        ..add(oldWidget.index)
        ..remove(widget.index);
    }
  }

  void _finishExit(int index) {
    if (!mounted ||
        index == widget.index ||
        !_outgoingIndices.contains(index)) {
      return;
    }
    setState(() => _outgoingIndices.remove(index));
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    return Stack(
      fit: StackFit.expand,
      clipBehavior: Clip.hardEdge,
      children: [
        for (var index = 0; index < widget.children.length; index++)
          _MotionPage(
            key: ValueKey(index),
            active: index == widget.index,
            exiting: !reduceMotion && _outgoingIndices.contains(index),
            reduceMotion: reduceMotion,
            onExitComplete: () => _finishExit(index),
            child: widget.children[index],
          ),
      ],
    );
  }
}

class _MotionPage extends StatefulWidget {
  const _MotionPage({
    super.key,
    required this.active,
    required this.exiting,
    required this.reduceMotion,
    required this.onExitComplete,
    required this.child,
  });

  final bool active;
  final bool exiting;
  final bool reduceMotion;
  final VoidCallback onExitComplete;
  final Widget child;

  @override
  State<_MotionPage> createState() => _MotionPageState();
}

class _MotionPageState extends State<_MotionPage> {
  static const _enterDuration = Duration(milliseconds: 220);
  static const _exitDuration = Duration(milliseconds: 145);
  static const _restOffset = Offset(0, .012);
  static const _exitOffset = Offset(0, -.008);
  static const _restScale = .985;

  late bool _offstage;

  @override
  void initState() {
    super.initState();
    _offstage = !widget.active;
  }

  @override
  void didUpdateWidget(covariant _MotionPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active) _offstage = false;
  }

  void _finishExit() {
    if (widget.active || _offstage) return;
    setState(() => _offstage = true);
    widget.onExitComplete();
  }

  @override
  Widget build(BuildContext context) {
    final active = widget.active;
    final duration = widget.reduceMotion
        ? Duration.zero
        : active
        ? _enterDuration
        : _exitDuration;
    final curve = active ? Curves.easeOutCubic : Curves.easeInCubic;

    return Offstage(
      offstage: !active && (_offstage || widget.reduceMotion),
      child: IgnorePointer(
        ignoring: !active,
        child: ExcludeSemantics(
          excluding: !active,
          child: TweenAnimationBuilder<double>(
            tween: Tween<double>(begin: active ? 1 : 0, end: active ? 1 : 0),
            duration: duration,
            curve: curve,
            onEnd: _finishExit,
            builder: (context, progress, child) {
              final offset = active
                  ? Offset.lerp(_restOffset, Offset.zero, progress)!
                  : widget.exiting
                  ? Offset.lerp(_exitOffset, Offset.zero, progress)!
                  : _restOffset;
              final scale = _restScale + ((1 - _restScale) * progress);

              return Opacity(
                opacity: progress,
                child: FractionalTranslation(
                  translation: offset,
                  child: Transform.scale(
                    scale: scale,
                    alignment: Alignment.center,
                    child: child,
                  ),
                ),
              );
            },
            child: TickerMode(enabled: active, child: widget.child),
          ),
        ),
      ),
    );
  }
}

class _TvRail extends StatefulWidget {
  const _TvRail({
    required this.selectedIndex,
    required this.label,
    required this.onSelect,
  });

  final int selectedIndex;
  final String Function(String key) label;
  final ValueChanged<int> onSelect;

  static const _icons = [
    'home',
    'server_2',
    'settings',
    'file_text',
    'star_fall',
    'info_circle',
  ];
  static const _labels = [
    'home',
    'servers',
    'settings',
    'logs',
    'whatsNew',
    'about',
  ];

  @override
  State<_TvRail> createState() => _TvRailState();
}

class _TvRailState extends State<_TvRail> {
  int? _focusedIndex;

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
            for (var index = 0; index < 4; index++)
              _item(context, index, colors),
            const Spacer(),
            for (var index = 4; index < _TvRail._icons.length; index++)
              _item(context, index, colors),
          ],
        ),
      ),
    );
  }

  Widget _item(BuildContext context, int index, ColorScheme colors) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
    child: Material(
      color: widget.selectedIndex == index
          ? colors.primaryContainer.withValues(alpha: .88)
          : Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: _focusedIndex == index ? colors.primary : Colors.transparent,
          width: 3,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        focusColor: EmberFocus.overlay(colors.primary, isTv: true),
        autofocus: index == widget.selectedIndex,
        onFocusChange: (focused) {
          if (focused && _focusedIndex != index) {
            setState(() => _focusedIndex = index);
          } else if (!focused && _focusedIndex == index) {
            setState(() => _focusedIndex = null);
          }
        },
        onTap: () => widget.onSelect(index),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Row(
            children: [
              SolarIcon(
                _TvRail._icons[index],
                color: widget.selectedIndex == index
                    ? colors.primary
                    : colors.onSurface,
              ),
              const SizedBox(width: 14),
              Text(
                widget.label(_TvRail._labels[index]),
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: widget.selectedIndex == index
                      ? colors.primary
                      : colors.onSurface,
                  fontWeight: widget.selectedIndex == index
                      ? FontWeight.w600
                      : FontWeight.w400,
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
