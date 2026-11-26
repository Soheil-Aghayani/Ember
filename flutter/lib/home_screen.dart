import 'dart:async';
import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

import 'ember_api.dart';
import 'ember_ui.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.ui, required this.onOpenServers});

  final EmberUi ui;
  final VoidCallback onOpenServers;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _orbit;
  Timer? _clock;
  bool _orbFocused = false;
  bool _serverCardFocused = false;

  @override
  void initState() {
    super.initState();
    _orbit = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 18),
    )..repeat();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncClock();
  }

  @override
  void didUpdateWidget(covariant HomeScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncClock();
  }

  void _syncClock() {
    final state = widget.ui.state;
    final shouldTick =
        TickerMode.valuesOf(context).enabled &&
        state.connected &&
        state.connectedAt != null;
    if (shouldTick && _clock == null) {
      _clock = Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
    } else if (!shouldTick) {
      _clock?.cancel();
      _clock = null;
    }
  }

  @override
  void dispose() {
    _clock?.cancel();
    _orbit.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ui = widget.ui;
    final state = ui.state;
    final copy = ui.strings;
    final node = state.connectedNode ?? state.selectedNode;
    final kitkat = ui.appearance == 'kitkat';
    final statusKey = kitkat
        ? state.connected
              ? 'kitkatConnected'
              : state.connecting
              ? 'kitkatConnecting'
              : 'kitkatDisconnected'
        : state.connected
        ? 'connected'
        : state.connecting
        ? 'connecting'
        : 'disconnected';
    final status = copy.get(statusKey);
    final detail = state.connected
        ? copy.get('sessionStats', {
            'time': _duration(state.connectedAt) ?? '00:00:00',
            'up': _formatBytes(state.uploadBytes),
            'down': _formatBytes(state.downloadBytes),
          })
        : state.connecting
        ? state.progress
        : node != null && state.selectedId == 'auto'
        ? copy.get('fastest', {'name': node.title})
        : null;

    if (state.isTv) {
      return _tvLayout(context, state, node, status, detail);
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: Column(
        children: [
          EmberPageHeader(copy.get('home')),
          const SizedBox(height: 8),
          _orb(context, state, 252),
          const SizedBox(height: 10),
          Text(status, style: Theme.of(context).textTheme.headlineMedium),
          if (detail != null) ...[
            const SizedBox(height: 6),
            Text(
              detail,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
          if (state.error != null && state.disconnected) ...[
            const SizedBox(height: 12),
            Text(
              state.error!,
              textAlign: TextAlign.center,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          const SizedBox(height: 28),
          _serverCard(context, state, node),
        ],
      ),
    );
  }

  Widget _tvLayout(
    BuildContext context,
    EmberState state,
    EmberNode? node,
    String status,
    String? detail,
  ) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final orbSize = math.min(310.0, constraints.maxHeight * .58);
        return Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _orb(context, state, orbSize),
                  const SizedBox(height: 4),
                  Text(
                    status,
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineLarge,
                  ),
                  if (detail != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      detail,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                  if (state.error != null && state.disconnected) ...[
                    const SizedBox(height: 12),
                    Text(
                      state.error!,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  const SizedBox(height: 30),
                  _serverCard(context, state, node, compact: true),
                  const SizedBox(height: 14),
                  _tvQuickServers(context, state),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _tvQuickServers(BuildContext context, EmberState state) {
    final byId = {for (final node in state.allNodes) node.id: node};
    final ids = <String>{...state.recentIds, ...state.favorites};
    final nodes = ids
        .map((id) => byId[id])
        .whereType<EmberNode>()
        .take(4)
        .toList();
    if (nodes.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 6),
          child: Text(
            widget.ui.strings.get('recentServers'),
            style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (final quick in nodes)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: SizedBox(
                    width: 184,
                    child: OutlinedButton(
                      onPressed: state.disconnected
                          ? () => widget.ui.run(
                              () => widget.ui.api.selectServer(quick.id),
                            )
                          : null,
                      child: Row(
                        children: [
                          EmberFlagBadge(quick.flagCode, size: 24),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              quick.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (state.selectedId == quick.id)
                            SolarIcon(
                              'check_circle',
                              size: 18,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _orb(BuildContext context, EmberState state, double size) {
    final connected = state.connected;
    final connecting = state.connecting;
    final palette = EmberPalettes.resolve(
      widget.ui.appearance,
      Theme.of(context).brightness,
    );
    final orbColors = connected
        ? palette.orbOn
        : connecting
        ? EmberPalettes.connectingOrb
        : palette.orbOff;
    return RepaintBoundary(
      child: SizedBox(
        width: size,
        height: size,
        child: Stack(
          alignment: Alignment.center,
          clipBehavior: Clip.none,
          children: [
            Positioned.fill(
              child: CustomPaint(
                painter: _OrbitPainter(
                  animation: _orbit,
                  orbitColor: palette.orbitColor,
                  connected: connected,
                ),
              ),
            ),
            Semantics(
              button: true,
              label: connected
                  ? widget.ui.strings.get('disconnect')
                  : connecting
                  ? widget.ui.strings.get('cancel')
                  : widget.ui.strings.get('connect'),
              child: InkWell(
                customBorder: const CircleBorder(),
                onFocusChange: (focused) {
                  if (state.isTv && _orbFocused != focused) {
                    setState(() => _orbFocused = focused);
                  }
                },
                focusColor: EmberFocus.overlay(
                  Theme.of(context).colorScheme.primary,
                  isTv: state.isTv,
                ),
                onTap: () {
                  if (!state.disconnected) {
                    widget.ui.run(() => widget.ui.api.disconnect());
                  } else if (state.selectedNode == null) {
                    widget.onOpenServers();
                  } else {
                    widget.ui.run(() => widget.ui.api.connect());
                  }
                },
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOutCubic,
                  width: size * .68,
                  height: size * .68,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: orbColors,
                      stops: const [0, .22, .42, .60, .78, 1],
                    ),
                    border: Border.all(
                      color: state.isTv && _orbFocused
                          ? Theme.of(context).colorScheme.primary
                          : Colors.white.withValues(alpha: .35),
                      width: state.isTv && _orbFocused ? 3 : 1.2,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: .14),
                        blurRadius: 24,
                        offset: const Offset(0, 8),
                      ),
                      BoxShadow(
                        color:
                            (connected ? palette.orbOn[3] : palette.orbOff[4])
                                .withValues(alpha: .19),
                        blurRadius: 28,
                        spreadRadius: 1,
                      ),
                    ],
                  ),
                  child: Stack(
                    children: [
                      Positioned.fill(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: RadialGradient(
                              center: const Alignment(-.28, -.38),
                              radius: 1.05,
                              colors: [
                                Colors.white.withValues(alpha: .36),
                                Colors.white.withValues(alpha: .12),
                                Colors.transparent,
                              ],
                              stops: const [0, .38, 1],
                            ),
                          ),
                        ),
                      ),
                      Center(
                        child: connecting
                            ? const SizedBox(
                                width: 38,
                                height: 38,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2.5,
                                  color: Colors.white,
                                ),
                              )
                            : SolarIcon(
                                'power',
                                size: size * .17,
                                color: Colors.white.withValues(alpha: .96),
                              ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _serverCard(
    BuildContext context,
    EmberState state,
    EmberNode? node, {
    bool compact = false,
  }) {
    final copy = widget.ui.strings;
    final speed = node == null ? null : state.results[node.id]?.mbps;
    return InkWell(
      onTap: state.disconnected ? widget.onOpenServers : null,
      onFocusChange: (focused) {
        if (state.isTv && _serverCardFocused != focused) {
          setState(() => _serverCardFocused = focused);
        }
      },
      borderRadius: BorderRadius.circular(18),
      focusColor: EmberFocus.overlay(
        Theme.of(context).colorScheme.primary,
        isTv: state.isTv,
        mobileAlpha: .08,
      ),
      child: EmberCard(
        focused: state.isTv && _serverCardFocused,
        padding: EdgeInsets.symmetric(
          horizontal: compact ? 20 : 16,
          vertical: compact ? 14 : 18,
        ),
        child: Row(
          children: [
            EmberFlagBadge(node?.flagCode, size: compact ? 32 : 34),
            SizedBox(width: compact ? 14 : 12),
            if (compact) ...[
              Expanded(
                child: Text(
                  node?.title ?? copy.get('chooseServer'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodyLarge,
                ),
              ),
              if (speed != null)
                Text(
                  '${speed.toStringAsFixed(1)} Mbps',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              const SizedBox(width: 10),
              SolarIcon(
                'alt_arrow_down',
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ] else ...[
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      node?.title ?? copy.get('chooseServer'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyLarge,
                    ),
                    if (node != null)
                      Text(
                        node.protocol.toUpperCase(),
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                  ],
                ),
              ),
              if (speed != null)
                Text(
                  '${speed.toStringAsFixed(1)} Mbps',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              const SizedBox(width: 6),
              SolarIcon(
                'alt_arrow_right',
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ],
          ],
        ),
      ),
    );
  }

  String? _duration(int? start) {
    if (start == null) return null;
    final elapsed = (DateTime.now().millisecondsSinceEpoch - start) ~/ 1000;
    final hours = (elapsed ~/ 3600).toString().padLeft(2, '0');
    final minutes = ((elapsed % 3600) ~/ 60).toString().padLeft(2, '0');
    final seconds = (elapsed % 60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  String _formatBytes(int value) {
    if (value < 1000) return '$value B';
    if (value < 1000000) return '${(value / 1000).toStringAsFixed(1)} KB';
    if (value < 1000000000) return '${(value / 1000000).toStringAsFixed(1)} MB';
    return '${(value / 1000000000).toStringAsFixed(1)} GB';
  }
}

class _OrbitPainter extends CustomPainter {
  _OrbitPainter({
    required this.animation,
    required this.orbitColor,
    required this.connected,
  }) : super(repaint: animation);

  final Animation<double> animation;
  final Color orbitColor;
  final bool connected;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final base = math.min(size.width, size.height);
    final line = Paint()
      ..color = orbitColor.withValues(alpha: connected ? .28 : .2)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.1;
    final dot = Paint()
      ..color = orbitColor.withValues(alpha: .64)
      ..style = PaintingStyle.fill;
    final phase = animation.value * math.pi * 2;

    for (var index = 0; index < 2; index++) {
      final tilt = index == 0 ? -.27 : .24;
      final rx = base * .49;
      final ry = base * (index == 0 ? .19 : .16);
      canvas.save();
      canvas.translate(center.dx, center.dy);
      canvas.rotate(tilt);
      canvas.drawOval(
        Rect.fromCenter(center: Offset.zero, width: rx * 2, height: ry * 2),
        line,
      );
      for (var point = 0; point < 2; point++) {
        final angle = phase * (index == 0 ? 1 : -.83) + point * math.pi;
        canvas.drawCircle(
          Offset(rx * math.cos(angle), ry * math.sin(angle)),
          index == 0 ? 3.4 : 2.8,
          dot,
        );
      }
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _OrbitPainter oldDelegate) =>
      oldDelegate.orbitColor != orbitColor ||
      oldDelegate.connected != connected;
}
