import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// Apple design tokens (iOS / iPadOS / macOS dark appearance):
/// pure-black canvas, neutral system materials, hairline separators.
class AppleColors {
  static const Color background = Color(0xFF000000);
  static const Color canvas = Color(0xFF0B0B0D);
  static const Color groupedPrimary = Color(0xB31C1C1E);
  static const Color groupedSecondary = Color(0xCC2C2C2E);
  static const Color groupedTertiary = Color(0xFF3A3A3C);
  static const Color fill = Color(0x3D767680);
  static const Color fillStrong = Color(0x5C767680);
  static const Color systemBlue = Color(0xFF0A84FF);
  static const Color systemCyan = Color(0xFF64D2FF);
  static const Color systemIndigo = Color(0xFF5E5CE6);
  static const Color systemGreen = Color(0xFF30D158);
  static const Color systemOrange = Color(0xFFFF9F0A);
  static const Color systemRed = Color(0xFFFF453A);
  static const Color labelPrimary = Color(0xFFF5F5F7);
  static const Color labelSecondary = Color(0x99EBEBF5);
  static const Color labelTertiary = Color(0x4DEBEBF5);
  static const Color separator = Color(0x33FFFFFF);
  static const Color glassBorder = Color(0x1FFFFFFF);
  static const Color glassBorderStrong = Color(0x2EFFFFFF);
  static const Color glassHighlight = Color(0x4DFFFFFF);
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
    this.iconSize = 34,
    this.fontSize = 18,
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
        const SizedBox(width: 10),
        wordmark,
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


/// Apple system material surface: neutral translucent fill, background blur,
/// 0.5pt hairline border, and a soft elevation shadow.
class AppleGlassSurface extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry? padding;
  final BorderRadius? borderRadius;
  final Color color;
  final double blurSigma;
  final Border? border;
  final bool elevated;
  const AppleGlassSurface({
    super.key,
    required this.child,
    this.padding,
    this.borderRadius,
    this.color = const Color(0x991C1C1E),
    this.blurSigma = 30,
    this.border,
    this.elevated = false,
  });
  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? BorderRadius.circular(18);
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: radius,
        boxShadow: elevated
            ? const [
                BoxShadow(
                  color: Color(0x40000000),
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
            padding: padding,
            decoration: BoxDecoration(
              color: color,
              borderRadius: radius,
              border: border ??
                  Border.all(color: AppleColors.glassBorder, width: 0.5),
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// iPhone / iPad style Dock:
/// - Portrait: wide rounded-rectangle dock floating above the home indicator.
/// - Landscape: vertical dock floating on the right edge.
/// The selection is a continuous-corner (superellipse) tile that glides between
/// items; every shape is path-rendered with anti-aliasing for smooth edges.
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
  static const double _pillRadius = 15;
  static const double _pad = 6;
  static const Duration _motion = Duration(milliseconds: 420);
  static const Curve _curve = Cubic(0.22, 1.0, 0.36, 1.0);

  /// Width a landscape dock occupies (including its outer margin).
  static double landscapeFootprint(BuildContext context) {
    final right = MediaQuery.paddingOf(context).right;
    return 64 + _pad * 2 + (right > 0 ? right + 6 : 12) + 8;
  }

  @override
  Widget build(BuildContext context) {
    final safe = MediaQuery.paddingOf(context);
    final margin = isLandscape
        ? EdgeInsets.only(
            right: safe.right > 0 ? safe.right + 6 : 12,
            top: 12,
            bottom: 12,
          )
        : EdgeInsets.only(
            left: 14,
            right: 14,
            bottom: safe.bottom > 0 ? safe.bottom : 12,
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
            color: Color(0x4D000000),
            blurRadius: 28,
            offset: Offset(0, 10),
          ),
        ],
      ),
      child: ClipPath(
        clipper: ShapeBorderClipper(shape: shape),
        clipBehavior: Clip.antiAlias,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
          child: DecoratedBox(
            decoration: ShapeDecoration(
              shape: RoundedSuperellipseBorder(
                borderRadius: BorderRadius.circular(_dockRadius),
                side: const BorderSide(color: Color(0x2EFFFFFF), width: 0.6),
              ),
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0xA8333338), Color(0xA81C1C20)],
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
            constraints.maxWidth.isFinite ? constraints.maxWidth : 420.0;
        final width = math.min(available, 520.0);
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
    const itemW = 64.0;
    const itemH = 58.0;
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
          side: const BorderSide(color: Color(0x29FFFFFF), width: 0.6),
        ),
        gradient: const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x40FFFFFF), Color(0x24FFFFFF)],
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
    const duration = Duration(milliseconds: 260);
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
              size: 22,
              color: value ?? color,
            ),
          ),
          const SizedBox(height: 3),
          AnimatedDefaultTextStyle(
            duration: duration,
            curve: Curves.easeOut,
            style: TextStyle(
              color: color,
              fontSize: 10,
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

/// Apple capsule button: white filled primary (Apple TV style) or neutral
/// translucent fill for secondary actions.
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
        ? const Color(0x33FF453A)
        : (primary ? const Color(0xFFF5F5F7) : AppleColors.fill);
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: compact ? 32 : 42,
      pressedOpacity: 0.7,
      onPressed: onPressed,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        clipBehavior: Clip.antiAlias,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
          child: Container(
            padding: EdgeInsets.symmetric(
              horizontal: compact ? 14 : 20,
              vertical: compact ? 7 : 11,
            ),
            decoration: BoxDecoration(
              color: bg,
              borderRadius: BorderRadius.circular(999),
              border: primary
                  ? null
                  : Border.all(color: AppleColors.glassBorder, width: 0.5),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (icon != null) ...[
                  Icon(icon, size: compact ? 15 : 17, color: fg),
                  SizedBox(width: compact ? 5 : 7),
                ],
                Text(
                  label,
                  style: TextStyle(
                    color: fg,
                    fontSize: compact ? 13 : 15,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.2,
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

/// Inset grouped section (Settings-app style).
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
          borderRadius: BorderRadius.circular(14),
          padding: EdgeInsets.zero,
          color: const Color(0xCC1C1C1E),
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

/// Apple sheet: neutral thick material, background blur, grabber and close button.
Future<T?> showAppleModalSheet<T>({
  required BuildContext context,
  required String title,
  required Widget Function(BuildContext) builder,
  Widget? trailingAction,
}) {
  return showCupertinoModalPopup<T>(
    context: context,
    builder: (ctx) {
      final viewInsets = MediaQuery.viewInsetsOf(ctx);
      final screenHeight = MediaQuery.sizeOf(ctx).height;
      return Padding(
        padding: EdgeInsets.only(bottom: viewInsets.bottom, left: 10, right: 10),
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: 560,
              maxHeight: screenHeight * 0.86,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(26),
              clipBehavior: Clip.antiAlias,
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 40, sigmaY: 40),
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xE61C1C1E),
                    borderRadius: BorderRadius.circular(26),
                    border: Border.all(
                      color: AppleColors.glassBorderStrong,
                      width: 0.5,
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
                            padding: const EdgeInsets.fromLTRB(20, 12, 12, 10),
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

/// Minimal Apple-style app background: true black with a faint cool lift at
/// the top and an optional, heavily blurred ambient artwork (Apple TV style).
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
                Color(0xFF17171B),
                Color(0xFF0A0A0C),
                Color(0xFF000000),
              ],
              stops: [0.0, 0.32, 0.72],
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
                  colors: [Color(0x80FFFFFF), Color(0x00FFFFFF)],
                ).createShader(rect),
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 60, sigmaY: 60),
                  child: artwork,
                ),
              ),
            ),
          ),
        const Positioned(
          top: -260,
          left: 0,
          right: 0,
          height: 560,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  radius: 0.7,
                  colors: [Color(0x14FFFFFF), Color(0x00FFFFFF)],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
