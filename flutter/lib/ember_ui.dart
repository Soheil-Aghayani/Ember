import 'package:material_ui/material_ui.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'ember_api.dart';
import 'ember_strings.dart';

abstract final class EmberColors {
  static const strawberryPrimary = Color(0xFF9C3107);
  static const strawberryBackground = Color(0xFFFAF7F2);
  static const strawberrySurfaceVariant = Color(0xFFF1ECE3);
  static const strawberryOutline = Color(0xFFE4DDD1);

  static const darkChocoPrimary = Color(0xFFFF8D5C);
  static const darkChocoBackground = Color(0xFF1A1714);
  static const darkChocoSurfaceVariant = Color(0xFF3A342D);
  static const darkChocoOutline = Color(0xFF3A342D);

  static const mintPrimary = Color(0xFF0A6B52);
  static const mintBackground = Color(0xFFF1FAF5);
  static const mintSurfaceVariant = Color(0xFFE3F0E8);
  static const mintOutline = Color(0xFFCBE2D6);
  static const mintOrb = Color(0xFF2F8F78);

  static const kitkatPrimary = Color(0xFFBA0018);
  static const kitkatBackground = Color(0xFFFFF7EE);
  static const kitkatSurface = Color(0xFFFFFCF7);
  static const kitkatSurfaceVariant = Color(0xFFF4E7DA);
  static const kitkatOutline = Color(0xFFE9D3C3);
  static const kitkatChocolate = Color(0xFF684032);
}

class EmberPalette {
  const EmberPalette({
    required this.scheme,
    required this.background,
    required this.orbitColor,
    required this.orbOff,
    required this.orbOn,
  });

  final ColorScheme scheme;
  final Color background;
  final Color orbitColor;
  final List<Color> orbOff;
  final List<Color> orbOn;
}

abstract final class EmberPalettes {
  static const connectingOrb = <Color>[
    Color(0xFFB1BECD),
    Color(0xFF9AAEBE),
    Color(0xFF849DAE),
    Color(0xFF7D969F),
    Color(0xFF9BB6AB),
    Color(0xFFD4DCC8),
  ];

  static const _orbOff = <Color>[
    Color(0xFFB4BCD6),
    Color(0xFF929BBC),
    Color(0xFF767FA4),
    Color(0xFF6A7298),
    Color(0xFF8C82A6),
    Color(0xFFD0A294),
  ];

  static const _orbOn = <Color>[
    Color(0xFF4E1CD6),
    Color(0xFF8222D6),
    Color(0xFFCE2496),
    Color(0xFFF03446),
    Color(0xFFFF801C),
    Color(0xFFFFCD1E),
  ];

  static const _mintOrbOn = <Color>[
    Color(0xFF0B4F6C),
    Color(0xFF0F7A7A),
    Color(0xFF1FB08A),
    Color(0xFF4FD6A0),
    Color(0xFF9AF0B8),
    Color(0xFFE0FFC8),
  ];

  static const _kitkatOrbOff = <Color>[
    Color(0xFFFFE4BC),
    Color(0xFFD99864),
    Color(0xFF9B5034),
    Color(0xFF5A261E),
    Color(0xFF84351F),
    Color(0xFFE4AC64),
  ];

  static const _kitkatOrbOn = <Color>[
    Color(0xFF541B21),
    Color(0xFF9A0016),
    Color(0xFFD21E25),
    Color(0xFFF0442B),
    Color(0xFFFF8A2D),
    Color(0xFFFFD568),
  ];

  static const strawberry = EmberPalette(
    background: EmberColors.strawberryBackground,
    orbitColor: EmberColors.strawberryPrimary,
    orbOff: _orbOff,
    orbOn: _orbOn,
    scheme: ColorScheme.light(
      primary: EmberColors.strawberryPrimary,
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
      outlineVariant: EmberColors.strawberryOutline,
    ),
  );

  static const darkChoco = EmberPalette(
    background: EmberColors.darkChocoBackground,
    orbitColor: Color(0xFFFFFFFF),
    orbOff: _orbOff,
    orbOn: _orbOn,
    scheme: ColorScheme.dark(
      primary: EmberColors.darkChocoPrimary,
      onPrimary: EmberColors.darkChocoBackground,
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
      outlineVariant: EmberColors.darkChocoOutline,
    ),
  );

  static const mint = EmberPalette(
    background: EmberColors.mintBackground,
    orbitColor: EmberColors.mintOrb,
    orbOff: _orbOff,
    orbOn: _mintOrbOn,
    scheme: ColorScheme.light(
      primary: EmberColors.mintPrimary,
      onPrimary: Color(0xFFFFFFFF),
      primaryContainer: Color(0xFFD7EEE5),
      onPrimaryContainer: Color(0xFF0A4E3D),
      secondary: EmberColors.mintOrb,
      onSecondary: Color(0xFFFFFFFF),
      secondaryContainer: EmberColors.mintSurfaceVariant,
      onSecondaryContainer: EmberColors.mintPrimary,
      tertiary: Color(0xFF1E7A3C),
      error: Color(0xFFBA1A1A),
      surface: Color(0xFFFFFFFF),
      onSurface: Color(0xFF231712),
      onSurfaceVariant: Color(0xFF51443C),
      outline: Color(0xFF7E978A),
      outlineVariant: EmberColors.mintOutline,
    ),
  );

  static const kitkat = EmberPalette(
    background: EmberColors.kitkatBackground,
    orbitColor: EmberColors.kitkatPrimary,
    orbOff: _kitkatOrbOff,
    orbOn: _kitkatOrbOn,
    scheme: ColorScheme.light(
      primary: EmberColors.kitkatPrimary,
      onPrimary: Color(0xFFFFFFFF),
      primaryContainer: Color(0xFFF9DDDA),
      onPrimaryContainer: Color(0xFF73000F),
      secondary: EmberColors.kitkatChocolate,
      onSecondary: Color(0xFFFFFFFF),
      secondaryContainer: Color(0xFFEEDBCB),
      onSecondaryContainer: Color(0xFF3A2017),
      tertiary: Color(0xFF1E7A3C),
      error: Color(0xFFBA1A1A),
      surface: EmberColors.kitkatSurface,
      onSurface: Color(0xFF351C1B),
      onSurfaceVariant: Color(0xFF68504A),
      outline: Color(0xFF94796C),
      outlineVariant: EmberColors.kitkatOutline,
    ),
  );

  static EmberPalette resolve(String appearance, Brightness brightness) {
    if (appearance == 'dark' || appearance == 'dark_choco') {
      return darkChoco;
    }
    if (appearance == 'mint') return mint;
    if (appearance == 'kitkat') return kitkat;
    if (appearance == 'system' && brightness == Brightness.dark) {
      return darkChoco;
    }
    return strawberry;
  }
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
  static const appearanceOptions = [
    'strawberry',
    'dark_choco',
    'mint',
    'kitkat',
    'system',
  ];

  const EmberUi({
    required this.api,
    required this.state,
    required this.strings,
    required this.appearance,
    required this.onAppearanceChanged,
    required this.run,
    required this.tell,
  });

  final EmberApi api;
  final EmberState state;
  final EmberStrings strings;
  final String appearance;
  final ValueChanged<String> onAppearanceChanged;
  final EmberRunner run;
  final ValueChanged<String> tell;
}

class EmberBackdrop extends StatelessWidget {
  const EmberBackdrop({super.key, required this.appearance});

  final String appearance;

  @override
  Widget build(BuildContext context) {
    final palette = EmberPalettes.resolve(
      appearance,
      Theme.of(context).brightness,
    );
    if (palette == EmberPalettes.darkChoco) {
      return Stack(
        fit: StackFit.expand,
        children: [
          Image.asset('assets/art/bg-dark.png', fit: BoxFit.cover),
          const ColoredBox(color: Color(0x66000000)),
        ],
      );
    }
    if (palette == EmberPalettes.mint) {
      return SvgPicture.asset('assets/art/bg-mint.svg', fit: BoxFit.cover);
    }
    if (palette == EmberPalettes.kitkat) {
      return SvgPicture.asset(
        'assets/art/bg-kitkat.svg',
        fit: BoxFit.cover,
        alignment: Alignment.centerRight,
      );
    }
    return Image.asset('assets/art/bg-light.png', fit: BoxFit.cover);
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

class EmberNavigationIcon extends StatelessWidget {
  const EmberNavigationIcon({
    super.key,
    required this.name,
    required this.appearance,
    this.size = 22,
    this.kitKatSize,
    this.color,
  });

  final String name;
  final String appearance;
  final double size;
  final double? kitKatSize;
  final Color? color;

  static const _kitKatNames = <String, String>{
    'home': 'home',
    'server_2': 'servers',
    'settings': 'settings',
    'file_text': 'logs',
    'star_fall': 'whats-new',
    'info_circle': 'about',
  };

  @override
  Widget build(BuildContext context) {
    final kitKatName = _kitKatNames[name];
    if (appearance == 'kitkat' && kitKatName != null) {
      return SvgPicture.asset(
        'assets/art/kitkat-icons/$kitKatName.svg',
        width: kitKatSize ?? size + 6,
        height: kitKatSize ?? size + 6,
        fit: BoxFit.contain,
      );
    }
    return SolarIcon(name, size: size, color: color);
  }
}

class EmberCard extends StatelessWidget {
  const EmberCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.focused = false,
    this.selected = false,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final bool focused;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final cardColor = Theme.of(context).cardTheme.color;
    final baseColor = dark
        ? colors.surface
        : cardColor ?? colors.surface.withValues(alpha: .78);
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: selected ? Color.lerp(baseColor, Colors.black, .11) : baseColor,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: focused
              ? colors.primary
              : dark
              ? colors.outlineVariant.withValues(alpha: .75)
              : Colors.white.withValues(alpha: .72),
          width: focused ? 2.5 : 1,
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
