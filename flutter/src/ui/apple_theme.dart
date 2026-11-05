import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// Apple Liquid Glass (iOS 26 / visionOS / macOS Tahoe / Apple TV+)
/// design tokens, refractive glass surfaces, and floating capsule Dock.
class AppleColors {
  static const Color background = Color(0xFF03050A);
  static const Color canvas = Color(0xFF080C14);
  static const Color groupedPrimary = Color(0x88161B26);
  static const Color groupedSecondary = Color(0x99242C3D);
  static const Color groupedTertiary = Color(0xAA323C52);
  static const Color systemBlue = Color(0xFF0A84FF);
  static const Color systemCyan = Color(0xFF64D2FF);
  static const Color systemIndigo = Color(0xFF5E5CE6);
  static const Color systemGreen = Color(0xFF30D158);
  static const Color systemOrange = Color(0xFFFF9F0A);
  static const Color systemRed = Color(0xFFFF453A);
  static const Color labelPrimary = Color(0xFFF5F7FA);
  static const Color labelSecondary = Color(0xB3EBEBF5);
  static const Color labelTertiary = Color(0x66EBEBF5);
  static const Color separator = Color(0x40FFFFFF);
  static const Color glassBorder = Color(0x48FFFFFF);
  static const Color glassHighlight = Color(0x78FFFFFF);

  static Map<String, String> posterHeaders(String url) {
    final lower = url.toLowerCase();
    if (lower.contains('doubanio.com') || lower.contains('douban.com')) {
      return const {
        'User-Agent':
            'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
        'Referer': 'https://m.douban.com/',
      };
    }
    if (lower.contains('hdslb.com') || lower.contains('bilibili.com')) {
      return const {
        'User-Agent':
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.15',
        'Referer': 'https://www.bilibili.com/',
      };
    }
    return const {
      'User-Agent':
          'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
    };
  }
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

/// Apple Liquid Glass surface with multi-stop optical refraction, backdrop blur,
/// top-left specular rim highlight, and soft elevation shadow.
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
    this.color = const Color(0x72141A26),
    this.blurSigma = 36,
    this.border,
    this.elevated = true,
  });
  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? BorderRadius.circular(20);
    return Container(
      decoration: BoxDecoration(
        borderRadius: radius,
        boxShadow: elevated
            ? const [
                BoxShadow(
                  color: Color(0x66000000),
                  blurRadius: 24,
                  offset: Offset(0, 10),
                ),
                BoxShadow(
                  color: Color(0x1464D2FF),
                  blurRadius: 18,
                  offset: Offset(0, 2),
                ),
              ]
            : null,
      ),
      child: ClipRRect(
        borderRadius: radius,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
          child: Container(
            padding: padding,
            decoration: BoxDecoration(
              color: color,
              borderRadius: radius,
              gradient: const LinearGradient(
                begin: Alignment(-0.9, -1.0),
                end: Alignment(0.9, 1.0),
                colors: [
                  Color(0x36FFFFFF),
                  Color(0x12FFFFFF),
                  Color(0x0AFFFFFF),
                  Color(0x2264D2FF),
                ],
                stops: [0.0, 0.35, 0.72, 1.0],
              ),
              border: border ??
                  Border.all(
                    color: AppleColors.glassBorder,
                    width: 0.9,
                  ),
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// Floating Apple Liquid Glass Dock bar:
/// - Portrait (`isLandscape == false`): Horizontal floating capsule Dock at the bottom center.
/// - Landscape (`isLandscape == true`): Vertical floating capsule Dock on the right side.
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

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(999);
    return Container(
      decoration: BoxDecoration(
        borderRadius: radius,
        boxShadow: const [
          BoxShadow(
            color: Color(0x99000000),
            blurRadius: 34,
            offset: Offset(0, 14),
          ),
          BoxShadow(
            color: Color(0x260A84FF),
            blurRadius: 26,
            offset: Offset(0, 4),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: radius,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 44, sigmaY: 44),
          child: Container(
            padding: isLandscape
                ? const EdgeInsets.symmetric(vertical: 10, horizontal: 7)
                : const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: const Color(0x78101624),
              borderRadius: radius,
              gradient: const LinearGradient(
                begin: Alignment(-0.85, -1.0),
                end: Alignment(0.85, 1.0),
                colors: [
                  Color(0x42FFFFFF),
                  Color(0x18FFFFFF),
                  Color(0x0EFFFFFF),
                  Color(0x2C64D2FF),
                ],
                stops: [0.0, 0.32, 0.68, 1.0],
              ),
              border: Border.all(
                color: const Color(0x58FFFFFF),
                width: 1.0,
              ),
            ),
            child: isLandscape
                ? Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (int i = 0; i < items.length; i++) ...[
                        _buildDockItem(i, vertical: true),
                        if (i < items.length - 1) const SizedBox(height: 6),
                      ],
                    ],
                  )
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (int i = 0; i < items.length; i++)
                        Expanded(child: _buildDockItem(i, vertical: false)),
                    ],
                  ),
          ),
        ),
      ),
    );
  }

  Widget _buildDockItem(int index, {required bool vertical}) {
    final selected = index == selectedIndex;
    final item = items[index];
    final pillRadius = BorderRadius.circular(999);
    return GestureDetector(
      onTap: () => onSelect(index),
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        width: vertical ? 58 : null,
        padding: vertical
            ? const EdgeInsets.symmetric(vertical: 11, horizontal: 6)
            : const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
        decoration: BoxDecoration(
          borderRadius: pillRadius,
          gradient: selected
              ? const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Color(0x56FFFFFF),
                    Color(0x2E64D2FF),
                    Color(0x380A84FF),
                  ],
                )
              : null,
          border: selected
              ? Border.all(color: const Color(0x88FFFFFF), width: 0.9)
              : Border.all(color: Colors.transparent, width: 0.9),
          boxShadow: selected
              ? const [
                  BoxShadow(
                    color: Color(0x380A84FF),
                    blurRadius: 14,
                    offset: Offset(0, 4),
                  ),
                ]
              : null,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              selected ? item.$2 : item.$1,
              size: 21,
              color: selected
                  ? const Color(0xFFFFFFFF)
                  : const Color(0xCCDAE2F0),
            ),
            const SizedBox(height: 3),
            Text(
              item.$3,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: selected
                    ? const Color(0xFFFFFFFF)
                    : const Color(0xB8DAE2F0),
                fontSize: 10.5,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                letterSpacing: -0.2,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Apple Liquid Glass capsule button with refractive highlight and press opacity.
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
        : (primary ? const Color(0xFFFFFFFF) : AppleColors.labelPrimary);
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: compact ? 32 : 42,
      pressedOpacity: 0.72,
      onPressed: onPressed,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
          child: Container(
            padding: EdgeInsets.symmetric(
              horizontal: compact ? 14 : 20,
              vertical: compact ? 7 : 11,
            ),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              gradient: destructive
                  ? const LinearGradient(
                      colors: [Color(0x38FF453A), Color(0x22FF453A)],
                    )
                  : primary
                      ? const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Color(0xE60A84FF),
                            Color(0xCC3B9EFF),
                            Color(0xD95E5CE6),
                          ],
                        )
                      : const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Color(0x38FFFFFF),
                            Color(0x16FFFFFF),
                            Color(0x2464D2FF),
                          ],
                        ),
              border: Border.all(
                color: destructive
                    ? const Color(0x66FF453A)
                    : primary
                        ? const Color(0x88FFFFFF)
                        : AppleColors.glassBorder,
                width: 0.85,
              ),
              boxShadow: primary
                  ? const [
                      BoxShadow(
                        color: Color(0x440A84FF),
                        blurRadius: 16,
                        offset: Offset(0, 6),
                      ),
                    ]
                  : null,
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
                    fontWeight: primary ? FontWeight.w700 : FontWeight.w600,
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

/// Liquid Glass Inset Grouped Section.
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
            margin: const EdgeInsets.only(left: 52),
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
                fontWeight: FontWeight.w600,
                letterSpacing: 0.5,
              ),
            ),
          ),
        AppleGlassSurface(
          borderRadius: BorderRadius.circular(18),
          padding: EdgeInsets.zero,
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

/// Liquid Glass list row with glowing icon badge, title, subtitle, and trailing widget.
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
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  iconBgColor,
                  iconBgColor.withValues(alpha: 0.72),
                ],
              ),
              borderRadius: BorderRadius.circular(9),
              border: Border.all(color: Colors.white30, width: 0.6),
            ),
            alignment: Alignment.center,
            child: Icon(icon, color: Colors.white, size: 17),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppleColors.labelPrimary,
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
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
              size: 16,
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
        highlightColor: Colors.white12,
        splashColor: Colors.transparent,
        child: content,
      ),
    );
  }
}

/// Shows an Apple Liquid Glass modal sheet with refractive blur and luminous border.
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
              borderRadius: BorderRadius.circular(28),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 44, sigmaY: 44),
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0x98101624),
                    borderRadius: BorderRadius.circular(28),
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Color(0x38FFFFFF),
                        Color(0x14FFFFFF),
                        Color(0x2464D2FF),
                      ],
                    ),
                    border: Border.all(
                      color: const Color(0x55FFFFFF),
                      width: 1.0,
                    ),
                  ),
                  child: Material(
                    color: Colors.transparent,
                    child: SafeArea(
                      top: false,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const SizedBox(height: 10),
                          Container(
                            width: 40,
                            height: 5,
                            decoration: BoxDecoration(
                              color: const Color(0x88FFFFFF),
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
                                      fontSize: 18,
                                      fontWeight: FontWeight.w700,
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
                                    color: AppleColors.labelSecondary,
                                    size: 24,
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
