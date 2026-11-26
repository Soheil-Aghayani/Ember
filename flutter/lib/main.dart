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
      final savedAppearance = await _api.getAppearance();
      final appearance = savedAppearance == 'dark'
          ? 'dark_choco'
          : savedAppearance;
      if (mounted && EmberUi.appearanceOptions.contains(appearance)) {
        setState(() => _appearance = appearance);
      }
    } catch (_) {
      // Keep the Mint default if the native preference is unavailable.
    }
  }

  Future<void> _setAppearance(String appearance) async {
    if (!EmberUi.appearanceOptions.contains(appearance) ||
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
    final themeMode = _appearance == 'system'
        ? ThemeMode.system
        : _appearance == 'dark_choco'
        ? ThemeMode.dark
        : ThemeMode.light;
    final lightAppearance =
        _appearance == 'system' || _appearance == 'dark_choco'
        ? 'strawberry'
        : _appearance;
    return MaterialApp(
      title: 'Ember',
      debugShowCheckedModeBanner: false,
      theme: _theme(Brightness.light, appearance: lightAppearance, isTv: isTv),
      darkTheme: _theme(Brightness.dark, appearance: 'dark_choco', isTv: isTv),
      themeMode: themeMode,
      themeAnimationDuration: const Duration(milliseconds: 350),
      themeAnimationCurve: Curves.easeInOutCubic,
      scaffoldMessengerKey: _messengerKey,
      home: _state == null
          ? const _LaunchScreen()
          : _EmberShell(
              ui: EmberUi(
                api: _api,
                state: _state!,
                strings: EmberStrings(_effectivePersian(_state!)),
                appearance: _appearance,
                onAppearanceChanged: (value) =>
                    unawaited(_setAppearance(value)),
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

  ThemeData _theme(
    Brightness brightness, {
    required String appearance,
    required bool isTv,
  }) {
    final palette = EmberPalettes.resolve(appearance, brightness);
    final scheme = palette.scheme;
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      focusColor: isTv ? EmberFocus.overlay(scheme.primary, isTv: true) : null,
      fontFamily: 'Inter',
      scaffoldBackgroundColor: palette.background,
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
    final content = _PageStack(
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
            Positioned.fill(child: EmberBackdrop(appearance: ui.appearance)),
            SafeArea(
              child: ui.state.isTv
                  ? Row(
                      textDirection: TextDirection.ltr,
                      children: [
                        _TvRail(
                          selectedIndex: tvSelected,
                          appearance: ui.appearance,
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
            : _PhoneNavigationBar(
                selectedIndex: phoneNavigationIndex,
                appearance: ui.appearance,
                icons: _phoneIcons,
                labels: _phoneLabels.map(copy.get).toList(growable: false),
                onSelected: (index) => _select(_phonePages[index]),
              ),
      ),
    );
  }

  void _select(_Page page) => setState(() => _page = page);
}

class _PhoneNavigationBar extends StatefulWidget {
  const _PhoneNavigationBar({
    required this.selectedIndex,
    required this.appearance,
    required this.icons,
    required this.labels,
    required this.onSelected,
  });

  final int selectedIndex;
  final String appearance;
  final List<String> icons;
  final List<String> labels;
  final ValueChanged<int> onSelected;

  @override
  State<_PhoneNavigationBar> createState() => _PhoneNavigationBarState();
}

class _PhoneNavigationBarState extends State<_PhoneNavigationBar>
    with SingleTickerProviderStateMixin {
  late final AnimationController _morph;
  Animation<double> _indicatorRatio = const AlwaysStoppedAnimation<double>(0);
  bool? _isRtl;

  @override
  void initState() {
    super.initState();
    _morph = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 340),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final isRtl = Directionality.of(context) == TextDirection.rtl;
    if (_isRtl == null) {
      _isRtl = isRtl;
      _indicatorRatio = AlwaysStoppedAnimation<double>(
        _ratioFor(widget.selectedIndex, isRtl),
      );
    } else if (_isRtl != isRtl) {
      _isRtl = isRtl;
      _moveIndicator(widget.selectedIndex, isRtl);
    }
  }

  @override
  void didUpdateWidget(covariant _PhoneNavigationBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedIndex != widget.selectedIndex) {
      _moveIndicator(
        widget.selectedIndex,
        Directionality.of(context) == TextDirection.rtl,
      );
    }
  }

  double _ratioFor(int index, bool isRtl) {
    final count = widget.icons.length;
    if (count <= 1) return .5;
    final visualIndex = isRtl ? count - 1 - index : index;
    return (visualIndex + .5) / count;
  }

  void _moveIndicator(int index, bool isRtl) {
    final currentRatio = _indicatorRatio.value;
    final targetRatio = _ratioFor(index, isRtl);
    if ((currentRatio - targetRatio).abs() < .001) return;

    _morph.stop();
    _indicatorRatio = Tween<double>(
      begin: currentRatio,
      end: targetRatio,
    ).animate(CurvedAnimation(parent: _morph, curve: Curves.easeInOutCubic));
    _morph.forward(from: 0);
  }

  @override
  void dispose() {
    _morph.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final isDark = theme.brightness == Brightness.dark;
    final radius = BorderRadius.circular(24);

    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
        child: Container(
          decoration: BoxDecoration(
            borderRadius: radius,
            boxShadow: [
              BoxShadow(
                color: colors.primary.withValues(alpha: isDark ? .12 : .10),
                blurRadius: 22,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: radius,
            child: BackdropFilter(
              filter: ui.ImageFilter.blur(sigmaX: 18, sigmaY: 18),
              child: Container(
                height: 68,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: isDark
                        ? [
                            colors.surface.withValues(alpha: .94),
                            colors.surface.withValues(alpha: .84),
                          ]
                        : [
                            Colors.white.withValues(alpha: .84),
                            Colors.white.withValues(alpha: .68),
                          ],
                  ),
                  border: Border.all(
                    color: isDark
                        ? colors.outlineVariant.withValues(alpha: .72)
                        : Colors.white.withValues(alpha: .92),
                  ),
                  borderRadius: radius,
                ),
                child: LayoutBuilder(
                  builder: (context, constraints) => Stack(
                    fit: StackFit.expand,
                    children: [
                      Positioned.fill(
                        child: Align(
                          alignment: Alignment.center,
                          child: AnimatedBuilder(
                            animation: _morph,
                            builder: (context, _) {
                              final progress = _morph.value;
                              final stretch = progress < .5
                                  ? progress * .36
                                  : (1 - progress) * .36;
                              final indicatorWidth = 34 * (1 + stretch);
                              final offset = Offset(
                                constraints.maxWidth *
                                    (_indicatorRatio.value - .5),
                                -11,
                              );
                              return IgnorePointer(
                                child: Transform.translate(
                                  offset: offset,
                                  child: Container(
                                    width: indicatorWidth,
                                    height: 34,
                                    decoration: BoxDecoration(
                                      color: colors.primaryContainer.withValues(
                                        alpha: .9,
                                      ),
                                      borderRadius: BorderRadius.circular(34),
                                    ),
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                      Row(
                        children: [
                          for (
                            var index = 0;
                            index < widget.icons.length;
                            index++
                          )
                            Expanded(
                              child: _PhoneNavigationItem(
                                icon: widget.icons[index],
                                label: widget.labels[index],
                                appearance: widget.appearance,
                                selected: index == widget.selectedIndex,
                                onTap: () => widget.onSelected(index),
                              ),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PhoneNavigationItem extends StatelessWidget {
  const _PhoneNavigationItem({
    required this.icon,
    required this.label,
    required this.appearance,
    required this.selected,
    required this.onTap,
  });

  final String icon;
  final String label;
  final String appearance;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final foreground = selected ? colors.primary : colors.onSurfaceVariant;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 3, vertical: 4),
      child: Semantics(
        button: true,
        selected: selected,
        label: label,
        onTap: onTap,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            excludeFromSemantics: true,
            borderRadius: BorderRadius.circular(20),
            child: SizedBox.expand(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  TweenAnimationBuilder<Color?>(
                    tween: ColorTween(end: foreground),
                    duration: const Duration(milliseconds: 180),
                    curve: Curves.easeOut,
                    builder: (context, iconColor, _) => EmberNavigationIcon(
                      name: icon,
                      appearance: appearance,
                      size: 21,
                      kitKatSize: 36,
                      color: iconColor ?? foreground,
                    ),
                  ),
                  const SizedBox(height: 8),
                  AnimatedDefaultTextStyle(
                    duration: const Duration(milliseconds: 180),
                    curve: Curves.easeOut,
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.15,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                      color: foreground,
                    ),
                    child: Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PageStack extends StatefulWidget {
  const _PageStack({required this.index, required this.children});

  final int index;
  final List<Widget> children;

  @override
  State<_PageStack> createState() => _PageStackState();
}

class _PageStackState extends State<_PageStack>
    with SingleTickerProviderStateMixin {
  late final AnimationController _transition;
  late final Animation<double> _opacity;
  late final Animation<Offset> _slide;

  @override
  void initState() {
    super.initState();
    _transition = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 220),
      value: 1,
    );
    final curve = CurvedAnimation(
      parent: _transition,
      curve: Curves.easeOutCubic,
    );
    _opacity = Tween<double>(begin: .84, end: 1).animate(curve);
    _slide = Tween<Offset>(
      begin: const Offset(.018, 0),
      end: Offset.zero,
    ).animate(curve);
  }

  @override
  void didUpdateWidget(covariant _PageStack oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.index != widget.index) {
      if (MediaQuery.disableAnimationsOf(context)) {
        _transition.value = 1;
      } else {
        _transition.forward(from: 0);
      }
    }
  }

  @override
  void dispose() {
    _transition.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final stack = IndexedStack(index: widget.index, children: widget.children);
    if (MediaQuery.disableAnimationsOf(context)) return stack;
    return FadeTransition(
      opacity: _opacity,
      child: SlideTransition(position: _slide, child: stack),
    );
  }
}

class _TvRail extends StatefulWidget {
  const _TvRail({
    required this.selectedIndex,
    required this.appearance,
    required this.label,
    required this.onSelect,
  });

  final int selectedIndex;
  final String appearance;
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
              EmberNavigationIcon(
                name: _TvRail._icons[index],
                appearance: widget.appearance,
                size: 24,
                kitKatSize: 30,
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
