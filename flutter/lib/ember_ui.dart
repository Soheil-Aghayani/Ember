import 'package:material_ui/material_ui.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'ember_api.dart';
import 'ember_strings.dart';

abstract final class EmberColors {
  static const mintPrimary = Color(0xFF0A6B52);
  static const mintBackground = Color(0xFFF1FAF5);
  static const mintSurfaceVariant = Color(0xFFE3F0E8);
  static const mintOutline = Color(0xFFCBE2D6);
  static const mintOrb = Color(0xFF2F8F78);
}

abstract final class EmberFocus {
  static const tvAlpha = .24;

  static Color overlay(
    Color primary, {
    required bool isTv,
    double mobileAlpha = .12,
  }) => primary.withValues(alpha: isTv ? tvAlpha : mobileAlpha);
}

typedef EmberRunner = Future<T?> Function<T>(
  Future<T> Function() action, {
  String? success,
});

class EmberUi {
  const EmberUi({
    required this.api,
    required this.state,
    required this.strings,
    required this.run,
    required this.tell,
  });

  final EmberApi api;
  final EmberState state;
  final EmberStrings strings;
  final EmberRunner run;
  final ValueChanged<String> tell;
}

class EmberBackdrop extends StatelessWidget {
  const EmberBackdrop({super.key});

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Stack(
      fit: StackFit.expand,
      children: [
        dark
            ? Image.asset('assets/art/bg-dark.png', fit: BoxFit.cover)
            : SvgPicture.asset('assets/art/bg-mint.svg', fit: BoxFit.cover),
        if (dark) const ColoredBox(color: Color(0x66000000)),
      ],
    );
  }
}

class SolarIcon extends StatelessWidget {
  const SolarIcon(this.name, {super.key, this.size = 22, this.color});

  final String name;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) => SvgPicture.asset(
    'assets/icons/solar/ic_solar_$name.svg',
    width: size,
    height: size,
    colorFilter: ColorFilter.mode(
      color ?? Theme.of(context).colorScheme.onSurface,
      BlendMode.srcIn,
    ),
  );
}

class EmberCard extends StatelessWidget {
  const EmberCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
  });

  final Widget child;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: dark ? colors.surface : colors.surface.withValues(alpha: .78),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: dark
              ? colors.outlineVariant.withValues(alpha: .75)
              : Colors.white.withValues(alpha: .72),
        ),
      ),
      child: child,
    );
  }
}

class EmberPageHeader extends StatelessWidget {
  const EmberPageHeader(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
    child: Row(
      children: [
        Expanded(
          child: Text(title, style: Theme.of(context).textTheme.headlineMedium),
        ),
        ?trailing,
      ],
    ),
  );
}

class EmberFlagBadge extends StatelessWidget {
  const EmberFlagBadge(this.code, {super.key, this.size = 36});

  final String? code;
  final double size;

  @override
  Widget build(BuildContext context) {
    final label = code?.toUpperCase();
    if (label == null || label.length != 2) {
      return SizedBox.square(dimension: size);
    }
    return ClipOval(
      child: SizedBox.square(
        dimension: size,
        child: SvgPicture.asset(
          'assets/flags/${label.toLowerCase()}.svg',
          fit: BoxFit.cover,
          errorBuilder: (context, error, stackTrace) => Container(
            alignment: Alignment.center,
            color: Theme.of(context).colorScheme.secondaryContainer,
            child: Text(
              label,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                fontWeight: FontWeight.w700,
                color: Theme.of(context).colorScheme.onSecondaryContainer,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class EmberActionTile extends StatelessWidget {
  const EmberActionTile({
    super.key,
    required this.icon,
    required this.title,
    required this.onPressed,
    this.subtitle,
  });

  final String icon;
  final String title;
  final String? subtitle;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onPressed,
    borderRadius: BorderRadius.circular(18),
    child: EmberCard(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          SolarIcon(icon, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.bodyLarge),
                if (subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    subtitle!,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          SolarIcon(
            Directionality.of(context) == TextDirection.rtl
                ? 'alt_arrow_left'
                : 'alt_arrow_right',
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ],
      ),
    ),
  );
}
