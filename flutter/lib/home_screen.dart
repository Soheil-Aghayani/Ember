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
    final status = state.connected
        ? copy.get('connected')
        : state.connecting
        ? copy.get('connecting')
        : copy.get('disconnected');
    final detail = state.connected
        ? _duration(state.connectedAt)
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
    final copy = widget.ui.strings;
    return Padding(
      padding: const EdgeInsets.fromLTRB(32, 24, 36, 28),
      child: Row(
        children: [
          Expanded(
            flex: 9,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                EmberPageHeader(copy.get('home')),
                const SizedBox(height: 8),
                _orb(context, state, 310),
                const SizedBox(height: 8),
                Text(status, style: Theme.of(context).textTheme.headlineLarge),
                if (detail != null) ...[
                  const SizedBox(height: 8),
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
              ],
            ),
          ),
          const SizedBox(width: 32),
          Expanded(
            flex: 11,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  copy.get('chooseServer'),
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 14),
                _serverCard(context, state, node),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: widget.onOpenServers,
                  icon: const SolarIcon('global', color: Colors.white),
                  label: Text(copy.get('servers')),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _orb(BuildContext context, EmberState state, double size) {
    final connected = state.connected;
    final connecting = state.connecting;
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
                  orbitColor: Theme.of(context).colorScheme.tertiary,
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
                    gradient: RadialGradient(
                      center: const Alignment(-.25, -.35),
                      radius: 1.15,
                      colors: connected
                          ? const [
                              Color(0xFF8BBDA5),
                              Color(0xFF4F7890),
                              Color(0xFF343956),
                            ]
                          : connecting
                          ? const [
                              Color(0xFFB4B9D3),
                              Color(0xFF766E9D),
                              Color(0xFF5C567B),
                            ]
                          : const [
                              Color(0xFFAA9AE2),
                              Color(0xFF7467A3),
                              Color(0xFF554D75),
                            ],
                    ),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: .35),
                      width: 1.2,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color:
                            (connected
                                    ? const Color(0xFF4C9675)
                                    : const Color(0xFF7162A5))
                                .withValues(alpha: .22),
                        blurRadius: 24,
                        spreadRadius: 1,
                      ),
                    ],
                  ),
                  child: Center(
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
                            size: size * .22,
                            color: Colors.white.withValues(alpha: .96),
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

  Widget _serverCard(BuildContext context, EmberState state, EmberNode? node) {
    final copy = widget.ui.strings;
    return InkWell(
      onTap: state.disconnected ? widget.onOpenServers : null,
      borderRadius: BorderRadius.circular(18),
      child: EmberCard(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
        child: Row(
          children: [
            EmberFlagBadge(node?.flagCode, size: 34),
            const SizedBox(width: 12),
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
            if (node != null && state.results[node.id]?.mbps != null)
              Text(
                '${state.results[node.id]!.mbps!.toStringAsFixed(1)} Mbps',
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
