import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// Apple iOS 27 Liquid Glass design tokens (iOS / iPadOS / macOS dark appearance):
/// deep obsidian canvas, refractive translucent glass materials, specular rims.
class AppleColors {
  static const Color background = Color(0xFF000000);
  static const Color canvas = Color(0xFF09090C);
  static const Color groupedPrimary = Color(0xA81C1C21);
  static const Color groupedSecondary = Color(0xBF28282E);
  static const Color groupedTertiary = Color(0xFF36363D);
  static const Color fill = Color(0x38787884);
  static const Color fillStrong = Color(0x59787884);
  static const Color systemBlue = Color(0xFF0A84FF);
  static const Color systemCyan = Color(0xFF64D2FF);
  static const Color systemIndigo = Color(0xFF5E5CE6);
  static const Color systemGreen = Color(0xFF30D158);
  static const Color systemOrange = Color(0xFFFF9F0A);
  static const Color systemRed = Color(0xFFFF453A);
  static const Color labelPrimary = Color(0xFFF5F5F7);
  static const Color labelSecondary = Color(0x9EEBEBF5);
  static const Color labelTertiary = Color(0x52EBEBF5);
  static const Color separator = Color(0x29FFFFFF);
  static const Color glassBorder = Color(0x26FFFFFF);
  static const Color glassBorderStrong = Color(0x3BFFFFFF);
  static const Color glassHighlight = Color(0x52FFFFFF);
}

/// Brand logo widget rendering the user's frosted-glass clapperboard squircle icon
/// (`assets/logo_mark.png`) and the signature two-tone `Marco Movie` wordmark.
class MarcoBrandLogo extends StatelessWidget {
  final double iconSize;
  final double fontSize;
  final bool showWordmark;
  final bool stacked;
  const MarcoBrandLogo({
    super.key,
    this.iconSize = 32,
    this.fontSize = 17,
    this.showWordmark = true,
    this.stacked = false,
  });
  @override
  Widget build(BuildContext context) {
    final iconWidget = SizedBox(
      width: iconSize,
      height: iconSize,
      child: Image.asset(
        'assets/logo_mark.png',
        width: iconSize,
        height: iconSize,
        fit: BoxFit.contain,
        errorBuilder: (_, __, ___) => _FallbackLogoMark(size: iconSize),
      ),
    );
    if (!showWordmark) return iconWidget;
    final wordmark = RichText(
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      text: TextSpan(
        style: TextStyle(
          fontSize: fontSize,
          letterSpacing: -0.4,
          height: 1.1,
        ),
        children: const [
          TextSpan(
            text: 'Marco',
            style: TextStyle(
              color: Color(0xFFF8FAFC),
              fontWeight: FontWeight.w700,
            ),
          ),
          TextSpan(
            text: ' Movie',
            style: TextStyle(
              color: Color(0xFFB8C2D4),
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
    if (stacked) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          iconWidget,
          const SizedBox(height: 10),
          wordmark,
        ],
      );
    }
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        iconWidget,
        const SizedBox(width: 8),
        Flexible(child: wordmark),
      ],
    );
  }
}

class _FallbackLogoMark extends StatelessWidget {
  final double size;
  const _FallbackLogoMark({required this.size});
  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFF4F5F7), Color(0xFFD6DAE0)],
        ),
        borderRadius: BorderRadius.circular(size * 0.24),
        border: Border.all(color: Colors.white54, width: 0.8),
      ),
      alignment: Alignment.center,
      child: Container(
        width: size * 0.62,
        height: size * 0.54,
        decoration: BoxDecoration(
          color: const Color(0xFF2B3038),
          borderRadius: BorderRadius.circular(size * 0.12),
        ),
        alignment: Alignment.center,
        child: Icon(
          CupertinoIcons.play_fill,
          color: const Color(0xFFF4F5F7),
          size: size * 0.3,
        ),
      ),
    );
  }
}

/// iOS 27 Liquid Glass surface: multi-layer optical blur, subtle diagonal
/// refractive sheen, 0.6pt specular rim border, and soft depth shadow.
class AppleGlassSurface extends StatelessWidget {
  final Widget child;
  final double? width;
  final double? height;
  final EdgeInsetsGeometry? padding;
  final BorderRadius? borderRadius;
  final Color color;
  final Color? backgroundColor;
  final double blurSigma;
  final Border? border;
  final bool elevated;
  const AppleGlassSurface({
    super.key,
    required this.child,
    this.width,
    this.height,
    this.padding,
    this.borderRadius,
    this.color = const Color(0x8C202026),
    this.backgroundColor,
    this.blurSigma = 34,
    this.border,
    this.elevated = false,
  });
  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? BorderRadius.circular(18);
    final effectiveColor = backgroundColor ?? color;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: radius,
        boxShadow: elevated
            ? const [
                BoxShadow(
                  color: Color(0x48000000),
                  blurRadius: 24,
                  offset: Offset(0, 10),
                ),
              ]
            : null,
      ),
      child: ClipRRect(
        borderRadius: radius,
        clipBehavior: Clip.antiAlias,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
          child: Container(
            width: width,
            height: height,
            padding: padding,
            decoration: BoxDecoration(
              color: effectiveColor,
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color.alphaBlend(
                    const Color(0x18FFFFFF),
                    effectiveColor,
                  ),
                  effectiveColor,
                ],
              ),
              borderRadius: radius,
              border: border ??
                  Border.all(color: AppleColors.glassBorder, width: 0.6),
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// iOS 27 Liquid Glass Dock:
/// - Portrait: refined floating capsule dock (`64dp` total height) above the home indicator.
/// - Landscape: compact vertical floating dock on the right edge.
/// Features a continuous-corner (superellipse) refractive lens pill that glides
/// smoothly between items with specular rim highlights.
class AppleLiquidGlassDock extends StatelessWidget {
  final int selectedIndex;
  final ValueChanged<int> onSelect;
  final bool isLandscape;
  final List<(IconData, IconData, String)> items;

  const AppleLiquidGlassDock({
    super.key,
    required this.selectedIndex,
    required this.onSelect,
    required this.isLandscape,
    required this.items,
  });

  static const double _dockRadius = 24;
  static const double _pillRadius = 16;
  static const double _pad = 5;
  static const Duration _motion = Duration(milliseconds: 400);
  static const Curve _curve = Cubic(0.22, 1.0, 0.36, 1.0);

  /// Width a landscape dock occupies (including its outer margin).
  static double landscapeFootprint(BuildContext context) {
    final right = MediaQuery.paddingOf(context).right;
    return 60 + _pad * 2 + (right > 0 ? right + 4 : 10) + 8;
  }

  @override
  Widget build(BuildContext context) {
    final safe = MediaQuery.paddingOf(context);
    final margin = isLandscape
        ? EdgeInsets.only(
            right: safe.right > 0 ? safe.right + 4 : 10,
            top: 12,
            bottom: 12,
          )
        : EdgeInsets.only(
            left: 18,
            right: 18,
            bottom: safe.bottom > 0 ? math.max(safe.bottom - 2, 8) : 12,
          );
    return Padding(
      padding: margin,
      child: isLandscape ? _buildVertical() : _buildHorizontal(),
    );
  }

  int get _safeIndex =>
      items.isEmpty ? 0 : selectedIndex.clamp(0, items.length - 1);

  Widget _shell({required Widget child}) {
    final shape = RoundedSuperellipseBorder(
      borderRadius: BorderRadius.circular(_dockRadius),
    );
    return DecoratedBox(
      decoration: ShapeDecoration(
        shape: shape,
        shadows: const [
          BoxShadow(
            color: Color(0x52000000),
            blurRadius: 28,
            offset: Offset(0, 10),
          ),
        ],
      ),
      child: ClipPath(
        clipper: ShapeBorderClipper(shape: shape),
        clipBehavior: Clip.antiAlias,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 38, sigmaY: 38),
          child: DecoratedBox(
            decoration: ShapeDecoration(
              shape: RoundedSuperellipseBorder(
                borderRadius: BorderRadius.circular(_dockRadius),
                side: const BorderSide(color: Color(0x36FFFFFF), width: 0.65),
              ),
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0x96383842),
                  Color(0x82202027),
                  Color(0x9015151A),
                ],
                stops: [0.0, 0.52, 1.0],
              ),
            ),
            child: child,
          ),
        ),
      ),
    );
  }

  Widget _buildHorizontal() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final n = math.max(items.length, 1);
        final available =
            constraints.maxWidth.isFinite ? constraints.maxWidth : 400.0;
        final width = math.min(available, 460.0);
        const itemH = 54.0;
        final itemW = (width - _pad * 2) / n;
        return _shell(
          child: SizedBox(
            width: width,
            height: itemH + _pad * 2,
            child: Stack(
              children: [
                AnimatedPositioned(
                  duration: _motion,
                  curve: _curve,
                  left: _pad + itemW * _safeIndex + 2,
                  top: _pad,
                  width: itemW - 4,
                  height: itemH,
                  child: const _DockSelectionPill(radius: _pillRadius),
                ),
                Positioned.fill(
                  child: Padding(
                    padding: const EdgeInsets.all(_pad),
                    child: Row(
                      children: [
                        for (int i = 0; i < items.length; i++)
                          Expanded(child: _item(i)),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildVertical() {
    const itemW = 60.0;
    const itemH = 54.0;
    final n = math.max(items.length, 1);
    return _shell(
      child: SizedBox(
        width: itemW + _pad * 2,
        height: itemH * n + _pad * 2,
        child: Stack(
          children: [
            AnimatedPositioned(
              duration: _motion,
              curve: _curve,
              left: _pad,
              top: _pad + itemH * _safeIndex + 2,
              width: itemW,
              height: itemH - 4,
              child: const _DockSelectionPill(radius: _pillRadius),
            ),
            Positioned.fill(
              child: Padding(
                padding: const EdgeInsets.all(_pad),
                child: Column(
                  children: [
                    for (int i = 0; i < items.length; i++)
                      SizedBox(height: itemH, child: _item(i)),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _item(int index) {
    final item = items[index];
    return _DockItem(
      icon: item.$1,
      selectedIcon: item.$2,
      label: item.$3,
      selected: index == _safeIndex,
      onTap: () => onSelect(index),
    );
  }
}

class _DockSelectionPill extends StatelessWidget {
  final double radius;
  const _DockSelectionPill({required this.radius});
  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: ShapeDecoration(
        shape: RoundedSuperellipseBorder(
          borderRadius: BorderRadius.circular(radius),
          side: const BorderSide(color: Color(0x45FFFFFF), width: 0.65),
        ),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color(0x42FFFFFF),
            Color(0x22FFFFFF),
            Color(0x16FFFFFF),
          ],
          stops: [0.0, 0.55, 1.0],
        ),
      ),
    );
  }
}

class _DockItem extends StatelessWidget {
  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final bool selected;
  final VoidCallback onTap;
  const _DockItem({
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.selected,
    required this.onTap,
  });
  @override
  Widget build(BuildContext context) {
    const duration = Duration(milliseconds: 240);
    final color = selected ? const Color(0xFFFFFFFF) : const Color(0x9EEBEBF5);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          TweenAnimationBuilder<Color?>(
            tween: ColorTween(end: color),
            duration: duration,
            curve: Curves.easeOut,
            builder: (context, value, _) => Icon(
              selected ? selectedIcon : icon,
              size: 21.5,
              color: value ?? color,
            ),
          ),
          const SizedBox(height: 2.5),
          AnimatedDefaultTextStyle(
            duration: duration,
            curve: Curves.easeOut,
            style: TextStyle(
              color: color,
              fontSize: 10.5,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
              letterSpacing: 0.1,
            ),
            child: Text(label, maxLines: 1, overflow: TextOverflow.clip),
          ),
        ],
      ),
    );
  }
}

/// iOS 27 Liquid Glass capsule button: pearl-white primary lens or translucent
/// specular glass for secondary actions.
class AppleCapsuleButton extends StatelessWidget {
  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final bool primary;
  final bool destructive;
  final bool compact;
  const AppleCapsuleButton({
    super.key,
    required this.label,
    this.icon,
    this.onPressed,
    this.primary = false,
    this.destructive = false,
    this.compact = false,
  });
  @override
  Widget build(BuildContext context) {
    final fg = destructive
        ? AppleColors.systemRed
        : (primary ? const Color(0xFF000000) : AppleColors.labelPrimary);
    final bg = destructive
        ? const Color(0x2EFF453A)
        : (primary ? const Color(0xFFF5F5F7) : const Color(0x42787884));
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: compact ? 32 : 42,
      pressedOpacity: 0.72,
      onPressed: onPressed,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        clipBehavior: Clip.antiAlias,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
          child: Container(
            padding: EdgeInsets.symmetric(
              horizontal: compact ? 13 : 20,
              vertical: compact ? 6.5 : 11,
            ),
            decoration: BoxDecoration(
              color: bg,
              gradient: primary
                  ? const LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Color(0xFFFFFFFF), Color(0xFFEAECEF)],
                    )
                  : LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color.alphaBlend(const Color(0x1AFFFFFF), bg),
                        bg,
                      ],
                    ),
              borderRadius: BorderRadius.circular(999),
              border: primary
                  ? Border.all(color: const Color(0x80FFFFFF), width: 0.5)
                  : Border.all(
                      color: destructive
                          ? const Color(0x59FF453A)
                          : AppleColors.glassBorder,
                      width: 0.6,
                    ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (icon != null) ...[
                  Icon(icon, size: compact ? 14.5 : 17, color: fg),
                  SizedBox(width: compact ? 5 : 7),
                ],
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: fg,
                      fontSize: compact ? 12.5 : 15,
                      fontWeight: FontWeight.w600,
                      letterSpacing: -0.2,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Inset grouped section (Settings-app style) with Liquid Glass translucency.
class AppleGroupedSection extends StatelessWidget {
  final String? header;
  final String? footer;
  final List<Widget> children;
  const AppleGroupedSection({
    super.key,
    this.header,
    this.footer,
    required this.children,
  });
  @override
  Widget build(BuildContext context) {
    final separated = <Widget>[];
    for (int i = 0; i < children.length; i++) {
      separated.add(children[i]);
      if (i < children.length - 1) {
        separated.add(
          Container(
            height: 0.5,
            margin: const EdgeInsets.only(left: 58),
            color: AppleColors.separator,
          ),
        );
      }
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (header != null && header!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(left: 16, bottom: 8),
            child: Text(
              header!.toUpperCase(),
              style: const TextStyle(
                color: AppleColors.labelSecondary,
                fontSize: 12,
                fontWeight: FontWeight.w500,
                letterSpacing: 0.3,
              ),
            ),
          ),
        AppleGlassSurface(
          borderRadius: BorderRadius.circular(16),
          padding: EdgeInsets.zero,
          color: const Color(0xB81C1C22),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: separated,
          ),
        ),
        if (footer != null && footer!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(left: 16, top: 7, right: 16),
            child: Text(
              footer!,
              style: const TextStyle(
                color: AppleColors.labelSecondary,
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ),
      ],
    );
  }
}

/// Settings-style list row with a rounded icon badge, title, subtitle and trailing widget.
class AppleGroupedTile extends StatelessWidget {
  final IconData icon;
  final Color iconBgColor;
  final String title;
  final String? subtitle;
  final String? trailingText;
  final Widget? trailing;
  final VoidCallback? onTap;
  const AppleGroupedTile({
    super.key,
    required this.icon,
    this.iconBgColor = AppleColors.systemBlue,
    required this.title,
    this.subtitle,
    this.trailingText,
    this.trailing,
    this.onTap,
  });
  @override
  Widget build(BuildContext context) {
    final content = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      child: Row(
        children: [
          DecoratedBox(
            decoration: ShapeDecoration(
              color: iconBgColor,
              shape: RoundedSuperellipseBorder(
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            child: SizedBox(
              width: 30,
              height: 30,
              child: Icon(icon, color: Colors.white, size: 17),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                if (subtitle != null && subtitle!.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    subtitle!,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppleColors.labelSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (trailingText != null && trailingText!.isNotEmpty) ...[
            const SizedBox(width: 8),
            Text(
              trailingText!,
              style: const TextStyle(
                color: AppleColors.labelSecondary,
                fontSize: 14,
              ),
            ),
          ],
          if (trailing != null) ...[
            const SizedBox(width: 8),
            trailing!,
          ] else if (onTap != null) ...[
            const SizedBox(width: 6),
            const Icon(
              CupertinoIcons.chevron_right,
              color: AppleColors.labelTertiary,
              size: 15,
            ),
          ],
        ],
      ),
    );
    if (onTap == null) return content;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        highlightColor: Colors.white10,
        splashColor: Colors.transparent,
        child: content,
      ),
    );
  }
}

/// iOS 27 Liquid Glass modal sheet: deep optical blur, specular rim, grabber, and close button.
Future<T?> showAppleModalSheet<T>({
  required BuildContext context,
  required String title,
  required Widget Function(BuildContext) builder,
  Widget? trailingAction,
  double? maxWidth,
}) {
  return showCupertinoModalPopup<T>(
    context: context,
    builder: (ctx) {
      final viewInsets = MediaQuery.viewInsetsOf(ctx);
      final screenSize = MediaQuery.sizeOf(ctx);
      final isLandscape = screenSize.width > screenSize.height;
      final effectiveMaxWidth =
          maxWidth ?? (isLandscape ? math.min(screenSize.width - 28, 720.0) : 560.0);
      return Padding(
        padding: EdgeInsets.only(bottom: viewInsets.bottom, left: 10, right: 10),
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: effectiveMaxWidth,
              maxHeight: screenSize.height * 0.88,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(26),
              clipBehavior: Clip.antiAlias,
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 44, sigmaY: 44),
                child: Container(
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color(0xDE24242C),
                        Color(0xE817171C),
                      ],
                    ),
                    borderRadius: BorderRadius.circular(26),
                    border: Border.all(
                      color: AppleColors.glassBorderStrong,
                      width: 0.65,
                    ),
                  ),
                  child: Material(
                    color: Colors.transparent,
                    child: SafeArea(
                      top: false,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const SizedBox(height: 8),
                          Container(
                            width: 36,
                            height: 5,
                            decoration: BoxDecoration(
                              color: const Color(0x66EBEBF5),
                              borderRadius: BorderRadius.circular(3),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(20, 10, 12, 10),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    title,
                                    style: const TextStyle(
                                      color: AppleColors.labelPrimary,
                                      fontSize: 17,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: -0.3,
                                    ),
                                  ),
                                ),
                                if (trailingAction != null) trailingAction,
                                CupertinoButton(
                                  padding: const EdgeInsets.all(6),
                                  minSize: 30,
                                  onPressed: () => Navigator.of(ctx).pop(),
                                  child: const Icon(
                                    CupertinoIcons.xmark_circle_fill,
                                    color: AppleColors.labelTertiary,
                                    size: 26,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Container(height: 0.5, color: AppleColors.separator),
                          Flexible(child: builder(ctx)),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

/// iOS 27 Liquid Glass ambient app background: deep obsidian canvas with
/// soft refractive indigo/cyan ambient light and blurred artwork bleed.
class AppleAmbientBackground extends StatelessWidget {
  final Widget? artwork;
  const AppleAmbientBackground({super.key, this.artwork});
  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        const DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Color(0xFF15161E),
                Color(0xFF0A0B0F),
                Color(0xFF000000),
              ],
              stops: [0.0, 0.36, 0.78],
            ),
          ),
        ),
        if (artwork != null)
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            height: 520,
            child: IgnorePointer(
              child: ShaderMask(
                blendMode: BlendMode.dstIn,
                shaderCallback: (rect) => const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0x88FFFFFF), Color(0x00FFFFFF)],
                ).createShader(rect),
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 60, sigmaY: 60),
                  child: artwork,
                ),
              ),
            ),
          ),
        const Positioned(
          top: -240,
          left: -80,
          right: -80,
          height: 540,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  radius: 0.75,
                  colors: [Color(0x1864D2FF), Color(0x00000000)],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
