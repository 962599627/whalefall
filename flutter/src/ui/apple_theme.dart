import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
/// Apple Human Interface Guidelines (iOS 18 / iPadOS 18 / macOS Sequoia / Apple TV+)
/// dark cinema design tokens and reusable components.
class AppleColors {
  static const Color background = Color(0xFF000000);
  static const Color canvas = Color(0xFF0A0B0E);
  static const Color groupedPrimary = Color(0xFF1C1C1E);
  static const Color groupedSecondary = Color(0xFF2C2C2E);
  static const Color groupedTertiary = Color(0xFF3A3A3C);
  static const Color systemBlue = Color(0xFF0A84FF);
  static const Color systemIndigo = Color(0xFF5E5CE6);
  static const Color systemGreen = Color(0xFF30D158);
  static const Color systemOrange = Color(0xFFFF9F0A);
  static const Color systemRed = Color(0xFFFF453A);
  static const Color labelPrimary = Color(0xFFF5F5F7);
  static const Color labelSecondary = Color(0x99EBEBF5);
  static const Color labelTertiary = Color(0x4DEBEBF5);
  static const Color separator = Color(0x66545458);
  static const Color glassBorder = Color(0x24FFFFFF);
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
              color: Color(0xFFF4F4F6),
              fontWeight: FontWeight.w700,
            ),
          ),
          TextSpan(
            text: ' Movie',
            style: TextStyle(
              color: Color(0xFFADAFB7),
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
/// Frosted glass container with Apple vibrancy blur and hairline border.
class AppleGlassSurface extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry? padding;
  final BorderRadius? borderRadius;
  final Color color;
  final double blurSigma;
  final Border? border;
  const AppleGlassSurface({
    super.key,
    required this.child,
    this.padding,
    this.borderRadius,
    this.color = const Color(0xB81C1C1E),
    this.blurSigma = 26,
    this.border,
  });
  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? BorderRadius.circular(16);
    return ClipRRect(
      borderRadius: radius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
        child: Container(
          padding: padding,
          decoration: BoxDecoration(
            color: color,
            borderRadius: radius,
            border: border ??
                Border.all(
                  color: AppleColors.glassBorder,
                  width: 0.5,
                ),
          ),
          child: child,
        ),
      ),
    );
  }
}
/// Apple TV+ / iOS 18 style capsule button with Cupertino press opacity.
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
    final bg = destructive
        ? const Color(0x28FF453A)
        : (primary ? AppleColors.labelPrimary : AppleColors.groupedSecondary);
    final fg = destructive
        ? AppleColors.systemRed
        : (primary ? const Color(0xFF000000) : AppleColors.labelPrimary);
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: compact ? 32 : 42,
      pressedOpacity: 0.72,
      onPressed: onPressed,
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
              : Border.all(
                  color: destructive
                      ? const Color(0x55FF453A)
                      : AppleColors.glassBorder,
                  width: 0.5,
                ),
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
    );
  }
}
/// iOS Settings.app style Inset Grouped Section.
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
            padding: const EdgeInsets.only(left: 16, bottom: 7),
            child: Text(
              header!.toUpperCase(),
              style: const TextStyle(
                color: AppleColors.labelSecondary,
                fontSize: 12,
                fontWeight: FontWeight.w500,
                letterSpacing: 0.4,
              ),
            ),
          ),
        Container(
          decoration: BoxDecoration(
            color: AppleColors.groupedPrimary,
            borderRadius: BorderRadius.circular(13),
            border: Border.all(color: AppleColors.glassBorder, width: 0.5),
          ),
          clipBehavior: Clip.antiAlias,
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
/// iOS Settings.app style list row with rounded colored icon badge, title, subtitle, and trailing widget.
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
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              color: iconBgColor,
              borderRadius: BorderRadius.circular(7),
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
        highlightColor: Colors.white10,
        splashColor: Colors.transparent,
        child: content,
      ),
    );
  }
}
/// Shows an Apple-style translucent bottom sheet with a top grabber handle.
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
        padding: EdgeInsets.only(bottom: viewInsets.bottom),
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: 560,
              maxHeight: screenHeight * 0.86,
            ),
            child: ClipRRect(
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(22),
                bottom: Radius.circular(22),
              ),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
                child: Material(
                  color: const Color(0xE61C1C1E),
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
      );
    },
  );
}
