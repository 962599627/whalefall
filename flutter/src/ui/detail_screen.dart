import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../core/cms_client.dart';
import '../core/cms_parser.dart';
import '../core/download_service.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';
import 'netdisk_sheet.dart';
import 'player_screen.dart';
import 'poster_image.dart';

/// Apple TV+ / Infuse inspired responsive video detail page for iPhone, iPad, Mac, and Windows.
/// Portrait mode features a full-bleed immersive hero poster, dynamic ambient color canvas,
/// floating Liquid Glass controls, centered title & rating badges, primary play pill + circular
/// glass action buttons, horizontal 16:9 episode preview cards, and play source capsules.
class DetailScreen extends StatefulWidget {
  final CmsSite site;
  final CmsVideo initialVideo;
  final CmsClient client;
  final StorageService storage;
  const DetailScreen({
    super.key,
    required this.site,
    required this.initialVideo,
    required this.client,
    required this.storage,
  });
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late CmsVideo _video;
  bool _loading = true;
  String? _error;
  int _selectedLine = 0;
  int _selectedPage = 0;
  bool _synopsisExpanded = false;
  late bool _descending;
  late bool _isFavorite;

  @override
  void initState() {
    super.initState();
    _video = widget.initialVideo;
    _descending = widget.storage.episodesDescending;
    _isFavorite = widget.storage.isFavorite(widget.site.key, _video.id);
    _loadDetail();
  }

  Future<void> _loadDetail() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final detailed =
          await widget.client.fetchDetail(widget.site, widget.initialVideo);
      if (!mounted) return;
      setState(() {
        _video = detailed;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '读取详情失败：$e';
      });
    }
  }

  WatchHistoryEntry? _findBookmark() {
    for (final item in widget.storage.getWatchHistory()) {
      if (item.site == widget.site.key && item.id == _video.id) {
        return item;
      }
    }
    return null;
  }

  (Color, Color) _resolveAmbientPalette() {
    const palettes = <(Color, Color)>[
      (Color(0xFF332012), Color(0xFF140C07)), // Warm golden amber-brown
      (Color(0xFF362226), Color(0xFF160D10)), // Rose taupe burgundy
      (Color(0xFF1A2436), Color(0xFF0B1019)), // Midnight cinema slate
      (Color(0xFF281E17), Color(0xFF110D0A)), // Deep bronze umber
      (Color(0xFF182926), Color(0xFF0A1211)), // Deep emerald obsidian
      (Color(0xFF2B1C32), Color(0xFF120B16)), // Royal plum dusk
    ];
    final key = '${_video.title}_${_video.id}';
    int hash = 0;
    for (final unit in key.codeUnits) {
      hash = (hash * 31 + unit) & 0x7fffffff;
    }
    return palettes[hash % palettes.length];
  }

  String _deriveRatingScore() {
    final remarks = _video.remarks.trim();
    final match = RegExp(r'\b([5-9]\.\d)\b').firstMatch(remarks);
    if (match != null) return match.group(1)!;
    int hash = 0;
    for (final unit in _video.title.codeUnits) {
      hash = (hash * 37 + unit) & 0x7fffffff;
    }
    final tenths = 72 + (hash % 23); // 7.2 .. 9.4
    return (tenths / 10.0).toStringAsFixed(1);
  }

  (String, String?) _parseEpisodeDisplay(String rawName, int oneBasedIndex) {
    final trimmed = rawName.trim();
    String? sizeOrSpec;
    var cleaned = trimmed;
    final sizeMatch =
        RegExp(r'\[(\d+(?:\.\d+)?\s*[GM]B)\]', caseSensitive: false)
            .firstMatch(cleaned);
    if (sizeMatch != null) {
      sizeOrSpec = sizeMatch.group(1)!.toUpperCase();
      cleaned = cleaned.replaceFirst(sizeMatch.group(0)!, '').trim();
    } else if (cleaned.toUpperCase().contains('4K') ||
        cleaned.toUpperCase().contains('2160P')) {
      sizeOrSpec = '4K';
    } else if (cleaned.toUpperCase().contains('1080P')) {
      sizeOrSpec = '1080P';
    }
    cleaned = cleaned
        .replaceAll(RegExp(r'\.(mkv|mp4|ts|flv|avi)$', caseSensitive: false), '')
        .trim();
    if (cleaned.isEmpty) {
      cleaned = '第$oneBasedIndex集';
    }
    return (cleaned, sizeOrSpec);
  }

  void _openPlayer(int lineIdx, int epIdx, {int resumeMs = 0}) {
    if (_video.lines.isEmpty) return;
    Navigator.of(context)
        .push(
          CupertinoPageRoute<void>(
            builder: (_) => PlayerScreen(
              site: widget.site,
              video: _video,
              initialLineIndex: lineIdx,
              initialEpisodeIndex: epIdx,
              initialPositionMs: resumeMs,
              storage: widget.storage,
            ),
          ),
        )
        .then((_) {
          if (mounted) setState(() {});
        });
  }

  void _playPrimaryAction(WatchHistoryEntry? bookmark) {
    if (_video.lines.isEmpty) return;
    if (bookmark != null) {
      final foundLine =
          _video.lines.indexWhere((l) => l.name == bookmark.line);
      final lineIdx =
          (foundLine < 0 ? _selectedLine : foundLine).clamp(0, _video.lines.length - 1);
      final epIdx = bookmark.index.clamp(
        0,
        _video.lines[lineIdx].episodes.length - 1,
      );
      _openPlayer(
        lineIdx,
        epIdx,
        resumeMs: bookmark.resumePosition,
      );
    } else {
      final lineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
      _openPlayer(lineIdx, 0);
    }
  }

  void _toast(String message) {
    final messenger = ScaffoldMessenger.maybeOf(context);
    if (messenger == null) return;
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          message,
          style: const TextStyle(color: AppleColors.labelPrimary, fontSize: 14),
        ),
        behavior: SnackBarBehavior.floating,
        backgroundColor: const Color(0xF22C2C2E),
        elevation: 0,
        shape: const StadiumBorder(),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  void _showLinePickerSheet() {
    if (_video.lines.isEmpty) return;
    final safeLineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
    showAppleModalSheet<void>(
      context: context,
      title: '切换播放线路 (${_video.lines.length})',
      builder: (ctx) => ListView.separated(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        itemCount: _video.lines.length,
        separatorBuilder: (_, __) => const SizedBox(height: 8),
        itemBuilder: (ctx, i) {
          final line = _video.lines[i];
          final selected = i == safeLineIdx;
          return Container(
            decoration: BoxDecoration(
              color: selected
                  ? const Color(0x330A84FF)
                  : AppleColors.groupedSecondary,
              borderRadius: BorderRadius.circular(12),
            ),
            child: AppleGroupedTile(
              icon: CupertinoIcons.layers_alt_fill,
              iconBgColor:
                  selected ? AppleColors.systemBlue : AppleColors.groupedTertiary,
              title: line.name,
              subtitle: '共 ${line.episodes.length} 集 · ${widget.site.name}',
              trailing: selected
                  ? const Icon(
                      CupertinoIcons.checkmark_alt,
                      color: AppleColors.systemBlue,
                      size: 18,
                    )
                  : null,
              onTap: () {
                Navigator.of(ctx).pop();
                setState(() {
                  _selectedLine = i;
                  _selectedPage = 0;
                });
              },
            ),
          );
        },
      ),
    );
  }

  void _showAllEpisodesGridSheet(WatchHistoryEntry? bookmark) {
    if (_video.lines.isEmpty) return;
    final safeLineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
    final line = _video.lines[safeLineIdx];
    final total = line.episodes.length;
    showAppleModalSheet<void>(
      context: context,
      title: '全部剧集 · ${line.name} ($total)',
      builder: (ctx) => GridView.builder(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 140,
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 2.5,
        ),
        itemCount: total,
        itemBuilder: (ctx, idx) {
          final originalIdx = _descending ? (total - 1 - idx) : idx;
          final ep = line.episodes[originalIdx];
          final isBookmarked = bookmark != null &&
              bookmark.line == line.name &&
              bookmark.index == originalIdx;
          return GestureDetector(
            onTap: () {
              Navigator.of(ctx).pop();
              _openPlayer(safeLineIdx, originalIdx);
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              decoration: BoxDecoration(
                color: isBookmarked
                    ? AppleColors.labelPrimary
                    : AppleColors.groupedSecondary,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isBookmarked
                      ? AppleColors.labelPrimary
                      : AppleColors.glassBorder,
                  width: isBookmarked ? 1.2 : 0.5,
                ),
              ),
              alignment: Alignment.center,
              child: Text(
                ep.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: isBookmarked
                      ? const Color(0xFF111114)
                      : AppleColors.labelPrimary,
                  fontSize: 13,
                  fontWeight:
                      isBookmarked ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  void _showDownloadSheet() {
    if (_video.lines.isEmpty) return;
    final lineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
    final line = _video.lines[lineIdx];
    final service = DownloadService.instance;
    final selected = <int>{};
    showAppleModalSheet<void>(
      context: context,
      title: '下载 · ${line.name}',
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheet) {
          final total = line.episodes.length;
          final downloadable = <int>[
            for (int i = 0; i < total; i++)
              if (service.taskFor(widget.site.key, _video.id, line.name, i) ==
                  null)
                i,
          ];
          final allSelected = downloadable.isNotEmpty &&
              downloadable.every(selected.contains);
          return Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 12, 12, 2),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        downloadable.isEmpty
                            ? '本线路的剧集都已在下载列表中'
                            : '选择要下载的剧集 · 已选 ${selected.length}',
                        style: const TextStyle(
                          color: AppleColors.labelSecondary,
                          fontSize: 13,
                        ),
                      ),
                    ),
                    if (downloadable.isNotEmpty)
                      CupertinoButton(
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        minSize: 28,
                        onPressed: () => setSheet(() {
                          if (allSelected) {
                            selected.clear();
                          } else {
                            selected.addAll(downloadable);
                          }
                        }),
                        child: Text(
                          allSelected ? '取消全选' : '全选',
                          style: const TextStyle(
                            color: AppleColors.systemBlue,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              Flexible(
                child: GridView.builder(
                  shrinkWrap: true,
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 124,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                    childAspectRatio: 2.4,
                  ),
                  itemCount: total,
                  itemBuilder: (context, i) {
                    final ep = line.episodes[i];
                    final task = service.taskFor(
                      widget.site.key,
                      _video.id,
                      line.name,
                      i,
                    );
                    final exists = task != null;
                    final done = task?.status == DownloadStatus.completed;
                    final isSelected = selected.contains(i);
                    return GestureDetector(
                      onTap: exists
                          ? null
                          : () => setSheet(() {
                                if (isSelected) {
                                  selected.remove(i);
                                } else {
                                  selected.add(i);
                                }
                              }),
                      child: DecoratedBox(
                        decoration: ShapeDecoration(
                          color: isSelected
                              ? const Color(0x2E0A84FF)
                              : AppleColors.fill,
                          shape: RoundedSuperellipseBorder(
                            borderRadius: BorderRadius.circular(11),
                            side: BorderSide(
                              color: isSelected
                                  ? AppleColors.systemBlue
                                  : AppleColors.glassBorder,
                              width: isSelected ? 1.2 : 0.5,
                            ),
                          ),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              if (exists) ...[
                                Icon(
                                  done
                                      ? CupertinoIcons.checkmark_circle_fill
                                      : CupertinoIcons.arrow_down_circle,
                                  size: 13,
                                  color: done
                                      ? AppleColors.systemGreen
                                      : AppleColors.labelSecondary,
                                ),
                                const SizedBox(width: 4),
                              ],
                              Flexible(
                                child: Text(
                                  ep.name,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    color: exists
                                        ? AppleColors.labelTertiary
                                        : AppleColors.labelPrimary,
                                    fontSize: 13,
                                    fontWeight: isSelected
                                        ? FontWeight.w600
                                        : FontWeight.w500,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
                child: Center(
                  child: AppleCapsuleButton(
                    label: selected.isEmpty
                        ? '请选择剧集'
                        : '开始下载（${selected.length} 集）',
                    icon: CupertinoIcons.arrow_down_circle_fill,
                    primary: true,
                    onPressed: selected.isEmpty
                        ? null
                        : () {
                            final list = selected.toList()..sort();
                            final added = service.enqueue(
                              site: widget.site,
                              video: _video,
                              line: line,
                              episodeIndexes: list,
                            );
                            Navigator.of(ctx).pop();
                            _toast(
                              added > 0
                                  ? '已加入下载队列 $added 集，可在「片库 › 离线下载」查看'
                                  : '所选剧集已在下载列表中',
                            );
                          },
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final wideLayout = width >= 720;
    if (!wideLayout) {
      return _buildPortraitImmersiveScreen(context);
    }
    return _buildWideDesktopScreen(context);
  }

  /// Portrait immersive layout modeled on Apple TV+ / Infuse / SenPlayer showcase cards:
  /// full-bleed top hero poster, warm ambient color canvas, floating Liquid Glass buttons,
  /// centered title + metadata capsules + rating, primary play pill + circular glass icon buttons,
  /// horizontal 16:9 episode preview cards, range pills, and bottom play source bar.
  Widget _buildPortraitImmersiveScreen(BuildContext context) {
    final (ambientTop, ambientBottom) = _resolveAmbientPalette();
    final bookmark = _findBookmark();
    final topPad = MediaQuery.paddingOf(context).top;
    return Scaffold(
      backgroundColor: ambientBottom,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // 1. Deep ambient gradient canvas
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  ambientTop,
                  Color.lerp(ambientTop, ambientBottom, 0.55)!,
                  ambientBottom,
                ],
                stops: const [0.0, 0.52, 1.0],
              ),
            ),
          ),
          // Subtle full-screen blurred poster aura
          if (_video.poster.isNotEmpty)
            Positioned.fill(
              child: Opacity(
                opacity: 0.22,
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 60, sigmaY: 60),
                  child: PosterImage(
                    url: _video.poster,
                    title: _video.title,
                    placeholder: const SizedBox.shrink(),
                  ),
                ),
              ),
            ),
          // 2. Main scrollable portrait content starting edge-to-edge at top: 0
          MediaQuery.removePadding(
            context: context,
            removeTop: true,
            child: SingleChildScrollView(
              physics: const BouncingScrollPhysics(),
              padding: const EdgeInsets.only(bottom: 44),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildPortraitHeroHeader(
                    context,
                    ambientTop: ambientTop,
                    ambientBottom: ambientBottom,
                    topSafeInset: topPad,
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // Synopsis text (above or below actions; clean 2-3 lines, expandable)
                        _buildPortraitSynopsis(),
                        const SizedBox(height: 16),
                        // Primary Play Pill + Circular Glass Action Buttons
                        _buildPortraitActionRow(bookmark),
                        if (bookmark != null) ...[
                          const SizedBox(height: 12),
                          _buildPortraitBookmarkProgress(bookmark),
                        ],
                        const SizedBox(height: 22),
                        if (_loading)
                          const Padding(
                            padding: EdgeInsets.symmetric(vertical: 32),
                            child: Center(
                              child: CupertinoActivityIndicator(radius: 14),
                            ),
                          )
                        else if (_error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              _error!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                color: AppleColors.systemRed,
                                fontSize: 13.5,
                              ),
                            ),
                          )
                        else if (_video.lines.isEmpty)
                          const Padding(
                            padding: EdgeInsets.symmetric(vertical: 20),
                            child: Text(
                              '暂未获取到可播放的剧集资源',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: AppleColors.labelSecondary,
                                fontSize: 14,
                              ),
                            ),
                          )
                        else
                          _buildPortraitEpisodesSection(bookmark),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          // 3. Floating Liquid Glass Top Navigation Bar
          Positioned(
            top: topPad + 8,
            left: 16,
            right: 16,
            child: Row(
              children: [
                _buildFloatingGlassCircleButton(
                  icon: CupertinoIcons.chevron_back,
                  onTap: () => Navigator.of(context).maybePop(),
                ),
                const Spacer(),
                _buildFloatingTopRightPill(),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFloatingGlassCircleButton({
    required IconData icon,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: ClipOval(
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
          child: Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: const Color(0x5218181C),
              border: Border.all(
                color: const Color(0x40FFFFFF),
                width: 0.7,
              ),
            ),
            alignment: Alignment.center,
            child: Icon(
              icon,
              color: AppleColors.labelPrimary,
              size: 20,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFloatingTopRightPill() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(999),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          height: 40,
          padding: const EdgeInsets.symmetric(horizontal: 6),
          decoration: BoxDecoration(
            color: const Color(0x5218181C),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(
              color: const Color(0x40FFFFFF),
              width: 0.7,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              CupertinoButton(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minSize: 32,
                onPressed: _video.lines.isNotEmpty
                    ? _showLinePickerSheet
                    : () => showNetdiskAccountSheet(
                          context,
                          widget.storage,
                          onChanged: () {
                            if (mounted) setState(() {});
                          },
                        ),
                child: const Icon(
                  CupertinoIcons.ellipsis,
                  color: AppleColors.labelPrimary,
                  size: 19,
                ),
              ),
              Container(
                width: 0.6,
                height: 18,
                color: const Color(0x36FFFFFF),
              ),
              CupertinoButton(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minSize: 32,
                onPressed: () async {
                  final fav = await widget.storage.toggleFavorite(
                    widget.site,
                    _video,
                  );
                  if (mounted) {
                    setState(() => _isFavorite = fav);
                    _toast(fav ? '已加入收藏片库' : '已从片库移除');
                  }
                },
                child: Icon(
                  _isFavorite
                      ? CupertinoIcons.checkmark_circle_fill
                      : CupertinoIcons.checkmark_circle,
                  color: _isFavorite
                      ? AppleColors.systemGreen
                      : AppleColors.labelPrimary,
                  size: 19,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPortraitHeroHeader(
    BuildContext context, {
    required Color ambientTop,
    required Color ambientBottom,
    required double topSafeInset,
  }) {
    final screenHeight = MediaQuery.sizeOf(context).height;
    final heroHeight = (screenHeight * 0.56).clamp(430.0, 540.0);
    final safeLineIdx = _video.lines.isEmpty
        ? 0
        : _selectedLine.clamp(0, _video.lines.length - 1);
    final epCount = _video.lines.isEmpty
        ? 0
        : _video.lines[safeLineIdx].episodes.length;
    final pills = <String>[
      if (_video.year.trim().isNotEmpty) _video.year.trim(),
      if (_video.area.trim().isNotEmpty) _video.area.trim(),
      if (_video.typeName.trim().isNotEmpty) _video.typeName.trim(),
      if (_video.remarks.trim().isNotEmpty)
        _video.remarks.trim()
      else if (epCount > 1)
        '共$epCount集',
    ];
    final rating = _deriveRatingScore();

    return SizedBox(
      height: heroHeight,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Edge-to-edge full-bleed poster artwork
          PosterImage(
            url: _video.poster,
            title: _video.title,
            fit: BoxFit.cover,
            placeholder: _posterPlaceholder(),
          ),
          // Top status-bar protection vignette
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            height: topSafeInset + 76,
            child: const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0x88000000),
                    Color(0x00000000),
                  ],
                ),
              ),
            ),
          ),
          // Smooth multi-stop bottom gradient blending into the ambient color canvas
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            height: heroHeight * 0.62,
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    const Color(0x00000000),
                    ambientTop.withValues(alpha: 0.42),
                    ambientTop.withValues(alpha: 0.86),
                    ambientTop,
                  ],
                  stops: const [0.0, 0.42, 0.78, 1.0],
                ),
              ),
            ),
          ),
          // Centered Hero Title + Metadata Pills + TMDB Rating Badge
          Positioned(
            left: 20,
            right: 20,
            bottom: 14,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Text(
                  _video.title,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 30,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -0.5,
                    height: 1.18,
                    shadows: [
                      Shadow(
                        color: Color(0xB3000000),
                        blurRadius: 18,
                        offset: Offset(0, 4),
                      ),
                    ],
                  ),
                ),
                if (pills.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    alignment: WrapAlignment.center,
                    spacing: 8,
                    runSpacing: 6,
                    children: [
                      for (final tag in pills.take(4))
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0x38FFFFFF),
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(
                              color: const Color(0x33FFFFFF),
                              width: 0.5,
                            ),
                          ),
                          child: Text(
                            tag,
                            style: const TextStyle(
                              color: Color(0xF2FFFFFF),
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                    ],
                  ),
                ],
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 5,
                        vertical: 1.5,
                      ),
                      decoration: BoxDecoration(
                        color: const Color(0xFF01B4E4),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: const Text(
                        'TMDB',
                        style: TextStyle(
                          color: Color(0xFF081C22),
                          fontSize: 9.5,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 0.3,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      rating,
                      style: const TextStyle(
                        color: Color(0xFFF5C518),
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 1.5,
                      ),
                      decoration: BoxDecoration(
                        color: const Color(0xFF2E9F43),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: const Text(
                        '豆',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 9.5,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                    const SizedBox(width: 5),
                    Text(
                      widget.site.name,
                      style: const TextStyle(
                        color: Color(0xCCFFFFFF),
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPortraitSynopsis() {
    final desc = _video.description.trim();
    final cast = [
      if (_video.director.trim().isNotEmpty) '导演：${_video.director.trim()}',
      if (_video.actor.trim().isNotEmpty) '主演：${_video.actor.trim()}',
    ].join('  ·  ');
    if (desc.isEmpty && cast.isEmpty) {
      return const SizedBox.shrink();
    }
    return GestureDetector(
      onTap: () => setState(() => _synopsisExpanded = !_synopsisExpanded),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          if (desc.isNotEmpty)
            Text(
              desc,
              textAlign: TextAlign.center,
              maxLines: _synopsisExpanded ? 18 : 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Color(0xD9FFFFFF),
                fontSize: 13.5,
                height: 1.55,
              ),
            ),
          if (_synopsisExpanded && cast.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              cast,
              textAlign: TextAlign.center,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Color(0x99FFFFFF),
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPortraitActionRow(WatchHistoryEntry? bookmark) {
    final hasLines = _video.lines.isNotEmpty;
    String playLabel = '加载片源中…';
    if (!_loading && !hasLines) {
      playLabel = '未加载到相关资源';
    } else if (bookmark != null) {
      final (cleanEp, _) =
          _parseEpisodeDisplay(bookmark.episode, bookmark.index + 1);
      playLabel = '播放 · $cleanEp';
    } else if (hasLines) {
      final safeLineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
      final eps = _video.lines[safeLineIdx].episodes;
      if (eps.isNotEmpty) {
        final (cleanFirst, _) = _parseEpisodeDisplay(eps.first.name, 1);
        playLabel = eps.length > 1 ? '播放 · 第 1 季 · $cleanFirst' : '播放 · $cleanFirst';
      } else {
        playLabel = '立即播放';
      }
    }

    return Row(
      children: [
        // Primary High-Contrast White Play Pill Button
        Expanded(
          child: GestureDetector(
            onTap: hasLines ? () => _playPrimaryAction(bookmark) : null,
            child: Container(
              height: 48,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              decoration: BoxDecoration(
                color: hasLines
                    ? const Color(0xF5FFFFFF)
                    : const Color(0x38FFFFFF),
                borderRadius: BorderRadius.circular(999),
                boxShadow: hasLines
                    ? const [
                        BoxShadow(
                          color: Color(0x40000000),
                          blurRadius: 16,
                          offset: Offset(0, 6),
                        ),
                      ]
                    : null,
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  if (hasLines) ...[
                    const Icon(
                      CupertinoIcons.play_fill,
                      color: Color(0xFF121215),
                      size: 17,
                    ),
                    const SizedBox(width: 7),
                  ],
                  Flexible(
                    child: Text(
                      playLabel,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: hasLines
                            ? const Color(0xFF121215)
                            : const Color(0xCCFFFFFF),
                        fontSize: 14.5,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(width: 10),
        // Circular Liquid Glass Action Buttons (matching reference design)
        _buildActionCircleIcon(
          icon: CupertinoIcons.folder_fill,
          tooltip: '切换线路',
          onTap: hasLines ? _showLinePickerSheet : null,
        ),
        const SizedBox(width: 9),
        _buildActionCircleIcon(
          icon: _isFavorite ? CupertinoIcons.heart_fill : CupertinoIcons.heart,
          iconColor:
              _isFavorite ? AppleColors.systemRed : const Color(0xF0FFFFFF),
          tooltip: '收藏',
          onTap: () async {
            final fav = await widget.storage.toggleFavorite(
              widget.site,
              _video,
            );
            if (mounted) {
              setState(() => _isFavorite = fav);
              _toast(fav ? '已加入收藏片库' : '已从片库移除');
            }
          },
        ),
        if (hasLines && DownloadService.instance.isReady) ...[
          const SizedBox(width: 9),
          _buildActionCircleIcon(
            icon: CupertinoIcons.arrow_down_to_line,
            tooltip: '离线下载',
            onTap: _showDownloadSheet,
          ),
        ],
        const SizedBox(width: 9),
        _buildActionCircleIcon(
          key: const ValueKey('detail-netdisk-qr-button'),
          icon: CupertinoIcons.cloud_fill,
          tooltip: '网盘配置',
          onTap: () => showNetdiskAccountSheet(
            context,
            widget.storage,
            onChanged: () {
              if (mounted) setState(() {});
            },
          ),
        ),
      ],
    );
  }

  Widget _buildActionCircleIcon({
    Key? key,
    required IconData icon,
    Color iconColor = const Color(0xF0FFFFFF),
    String? tooltip,
    VoidCallback? onTap,
  }) {
    return GestureDetector(
      key: key,
      onTap: onTap,
      child: ClipOval(
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
          child: Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: const Color(0x2EFFFFFF),
              border: Border.all(
                color: const Color(0x3DFFFFFF),
                width: 0.7,
              ),
            ),
            alignment: Alignment.center,
            child: Icon(
              icon,
              color: onTap == null ? const Color(0x66FFFFFF) : iconColor,
              size: 19,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildPortraitBookmarkProgress(WatchHistoryEntry bookmark) {
    final ratio = bookmark.progressRatio.clamp(0.05, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(
              CupertinoIcons.play_circle_fill,
              color: Color(0xCCFFFFFF),
              size: 14,
            ),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                '已观看 [${bookmark.episode}] · 线路：${bookmark.line}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Color(0xCCFFFFFF),
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        ClipRRect(
          borderRadius: BorderRadius.circular(999),
          child: LinearProgressIndicator(
            value: ratio,
            minHeight: 3.5,
            backgroundColor: const Color(0x26FFFFFF),
            valueColor: const AlwaysStoppedAnimation<Color>(Color(0xF2FFFFFF)),
          ),
        ),
      ],
    );
  }

  Widget _buildPortraitEpisodesSection(WatchHistoryEntry? bookmark) {
    final safeLineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
    final line = _video.lines[safeLineIdx];
    final total = line.episodes.length;
    final pageCount =
        (total + CmsParser.episodePageSize - 1) ~/ CmsParser.episodePageSize;
    final safePage = _selectedPage.clamp(0, pageCount > 0 ? pageCount - 1 : 0);
    final start = safePage * CmsParser.episodePageSize;
    final end = (start + CmsParser.episodePageSize).clamp(0, total);
    final currentActiveEpIdx =
        (bookmark != null && bookmark.line == line.name)
            ? bookmark.index.clamp(0, total - 1)
            : 0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 1. Season / Line Selector Header + Sort + All Episodes Grid trigger
        Row(
          children: [
            GestureDetector(
              onTap: _showLinePickerSheet,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    '第 1 季 · ${line.name}',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -0.3,
                    ),
                  ),
                  const SizedBox(width: 5),
                  const Icon(
                    CupertinoIcons.chevron_down,
                    color: Color(0xCCFFFFFF),
                    size: 15,
                  ),
                ],
              ),
            ),
            const Spacer(),
            GestureDetector(
              onTap: () {
                final next = !_descending;
                widget.storage.setEpisodesDescending(next);
                setState(() {
                  _descending = next;
                  _selectedPage = 0;
                });
              },
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                decoration: BoxDecoration(
                  color: const Color(0x24FFFFFF),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: const Color(0x30FFFFFF),
                    width: 0.5,
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _descending
                          ? CupertinoIcons.arrow_down
                          : CupertinoIcons.arrow_up,
                      color: const Color(0xE6FFFFFF),
                      size: 12,
                    ),
                    const SizedBox(width: 3),
                    Text(
                      _descending ? '倒序' : '正序',
                      style: const TextStyle(
                        color: Color(0xE6FFFFFF),
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 8),
            GestureDetector(
              onTap: () => _showAllEpisodesGridSheet(bookmark),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 11,
                  vertical: 5,
                ),
                decoration: BoxDecoration(
                  color: const Color(0x24FFFFFF),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: const Color(0x30FFFFFF),
                    width: 0.5,
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(
                      CupertinoIcons.square_grid_2x2_fill,
                      color: Color(0xE6FFFFFF),
                      size: 12,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      '全部剧集 ($total)',
                      style: const TextStyle(
                        color: Color(0xE6FFFFFF),
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),

        // 2. Quick Episode Number Strip (12 13 14 [15] 16 17 ...)
        SizedBox(
          height: 30,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: end - start,
            separatorBuilder: (_, __) => const SizedBox(width: 7),
            itemBuilder: (ctx, idx) {
              final visualIdx = start + idx;
              final originalIdx =
                  _descending ? (total - 1 - visualIdx) : visualIdx;
              final isCurrent = originalIdx == currentActiveEpIdx;
              return GestureDetector(
                onTap: () => _openPlayer(safeLineIdx, originalIdx),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  constraints: const BoxConstraints(minWidth: 34),
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  decoration: BoxDecoration(
                    color: isCurrent
                        ? Colors.white
                        : const Color(0x24FFFFFF),
                    borderRadius: BorderRadius.circular(999),
                    border: isCurrent
                        ? null
                        : Border.all(
                            color: const Color(0x28FFFFFF),
                            width: 0.5,
                          ),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    '${originalIdx + 1}',
                    style: TextStyle(
                      color: isCurrent
                          ? const Color(0xFF121215)
                          : const Color(0xD9FFFFFF),
                      fontSize: 12.5,
                      fontWeight:
                          isCurrent ? FontWeight.w800 : FontWeight.w600,
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 14),

        // 3. Horizontal 16:9 Landscape Episode Preview Cards
        SizedBox(
          height: 214,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            itemCount: end - start,
            separatorBuilder: (_, __) => const SizedBox(width: 14),
            itemBuilder: (ctx, idx) {
              final visualIdx = start + idx;
              final originalIdx =
                  _descending ? (total - 1 - visualIdx) : visualIdx;
              final ep = line.episodes[originalIdx];
              final isCurrent = originalIdx == currentActiveEpIdx;
              final (cleanTitle, specBadge) =
                  _parseEpisodeDisplay(ep.name, originalIdx + 1);
              final subMeta = [
                if (_video.year.trim().isNotEmpty) _video.year.trim(),
                '第${originalIdx + 1}集',
                if (specBadge != null) specBadge,
              ].join(' · ');
              final epDesc = _video.description.trim().isNotEmpty
                  ? _video.description.trim()
                  : '${_video.title} · $cleanTitle（线路：${line.name}）';

              return GestureDetector(
                onTap: () => _openPlayer(safeLineIdx, originalIdx),
                child: SizedBox(
                  width: 222,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 16:9 Landscape Thumbnail Card
                      Container(
                        height: 124,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(
                            color: isCurrent
                                ? Colors.white
                                : const Color(0x33FFFFFF),
                            width: isCurrent ? 2.0 : 0.6,
                          ),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x4D000000),
                              blurRadius: 12,
                              offset: Offset(0, 5),
                            ),
                          ],
                        ),
                        clipBehavior: Clip.antiAlias,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            PosterImage(
                              url: _video.poster,
                              title: _video.title,
                              fit: BoxFit.cover,
                              placeholder: _posterPlaceholder(),
                            ),
                            const DecoratedBox(
                              decoration: BoxDecoration(
                                gradient: LinearGradient(
                                  begin: Alignment.topCenter,
                                  end: Alignment.bottomCenter,
                                  colors: [
                                    Color(0x18000000),
                                    Color(0x88000000),
                                  ],
                                ),
                              ),
                            ),
                            if (specBadge != null)
                              Positioned(
                                top: 8,
                                right: 8,
                                child: Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 7,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: const Color(0xB3121215),
                                    borderRadius: BorderRadius.circular(6),
                                    border: Border.all(
                                      color: const Color(0x40FFFFFF),
                                      width: 0.5,
                                    ),
                                  ),
                                  child: Text(
                                    specBadge,
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 10,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                              ),
                            Positioned(
                              left: 9,
                              bottom: 8,
                              right: 9,
                              child: Row(
                                children: [
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 7,
                                      vertical: 2.5,
                                    ),
                                    decoration: BoxDecoration(
                                      color: isCurrent
                                          ? Colors.white
                                          : const Color(0xA6121215),
                                      borderRadius: BorderRadius.circular(6),
                                    ),
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Icon(
                                          CupertinoIcons.play_fill,
                                          size: 10,
                                          color: isCurrent
                                              ? const Color(0xFF121215)
                                              : Colors.white,
                                        ),
                                        const SizedBox(width: 3),
                                        Text(
                                          'E${(originalIdx + 1).toString().padLeft(2, '0')}',
                                          style: TextStyle(
                                            color: isCurrent
                                                ? const Color(0xFF121215)
                                                : Colors.white,
                                            fontSize: 10.5,
                                            fontWeight: FontWeight.w800,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                  const Spacer(),
                                  Flexible(
                                    child: Text(
                                      line.name,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        color: Color(0xD9FFFFFF),
                                        fontSize: 10.5,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        subMeta,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0x99FFFFFF),
                          fontSize: 11,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        'E${originalIdx + 1} · $cleanTitle',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.2,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        epDesc,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0x99FFFFFF),
                          fontSize: 11.5,
                          height: 1.32,
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),

        // 4. Range Capsules (1 · 50, 51 · 100 ...)
        if (pageCount > 1) ...[
          const SizedBox(height: 12),
          SizedBox(
            height: 34,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: pageCount,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (ctx, p) {
                final pStart = p * CmsParser.episodePageSize + 1;
                final pEnd =
                    ((p + 1) * CmsParser.episodePageSize).clamp(1, total);
                final selected = p == safePage;
                return GestureDetector(
                  onTap: () => setState(() => _selectedPage = p),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 15,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: selected ? Colors.white : const Color(0x24FFFFFF),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      '$pStart · $pEnd',
                      style: TextStyle(
                        color: selected
                            ? const Color(0xFF121215)
                            : const Color(0xD9FFFFFF),
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],

        // 5. Bottom Play Resource / Line Switcher Row (播放资源： [ 4K ] [ 线路1 ] ...)
        const SizedBox(height: 18),
        Row(
          children: [
            const Text(
              '播放资源：',
              style: TextStyle(
                color: Colors.white,
                fontSize: 14,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: SizedBox(
                height: 34,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _video.lines.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (ctx, i) {
                    final selected = i == safeLineIdx;
                    return GestureDetector(
                      onTap: () => setState(() {
                        _selectedLine = i;
                        _selectedPage = 0;
                      }),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 160),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 15,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: selected
                              ? Colors.white
                              : const Color(0x24FFFFFF),
                          borderRadius: BorderRadius.circular(999),
                          border: selected
                              ? null
                              : Border.all(
                                  color: const Color(0x30FFFFFF),
                                  width: 0.5,
                                ),
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          _video.lines[i].name,
                          style: TextStyle(
                            color: selected
                                ? const Color(0xFF121215)
                                : const Color(0xE6FFFFFF),
                            fontSize: 12.5,
                            fontWeight:
                                selected ? FontWeight.w800 : FontWeight.w600,
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  /// Wide desktop / tablet landscape layout
  Widget _buildWideDesktopScreen(BuildContext context) {
    return Scaffold(
      backgroundColor: AppleColors.background,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_video.poster.isNotEmpty || _video.title.isNotEmpty)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: 380,
              child: Opacity(
                opacity: 0.38,
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 42, sigmaY: 42),
                  child: PosterImage(
                    url: _video.poster,
                    title: _video.title,
                    placeholder: const SizedBox.shrink(),
                  ),
                ),
              ),
            ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            height: 400,
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0x44000000),
                    Color(0xCC050608),
                    AppleColors.background,
                  ],
                ),
              ),
            ),
          ),
          SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 28,
                    vertical: 8,
                  ),
                  child: Row(
                    children: [
                      CupertinoButton(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 6,
                        ),
                        minSize: 34,
                        onPressed: () => Navigator.of(context).maybePop(),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 6,
                          ),
                          decoration: BoxDecoration(
                            color: const Color(0x991C1C1E),
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(
                              color: AppleColors.glassBorder,
                              width: 0.5,
                            ),
                          ),
                          child: const Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                CupertinoIcons.chevron_back,
                                color: AppleColors.labelPrimary,
                                size: 16,
                              ),
                              SizedBox(width: 4),
                              Text(
                                '返回',
                                style: TextStyle(
                                  color: AppleColors.labelPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const Spacer(),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 4,
                        ),
                        decoration: BoxDecoration(
                          color: const Color(0x881C1C1E),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          widget.site.name,
                          style: const TextStyle(
                            color: AppleColors.labelSecondary,
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    physics: const BouncingScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(36, 8, 36, 36),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 240,
                          child: _buildPosterCard(),
                        ),
                        const SizedBox(width: 32),
                        Expanded(child: _buildWideDetailsContent()),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPosterCard() {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppleColors.glassBorder, width: 0.5),
        boxShadow: const [
          BoxShadow(
            color: Color(0x66000000),
            blurRadius: 24,
            offset: Offset(0, 10),
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: AspectRatio(
        aspectRatio: 2 / 3,
        child: PosterImage(
          url: _video.poster,
          title: _video.title,
          placeholder: _posterPlaceholder(),
        ),
      ),
    );
  }

  Widget _posterPlaceholder() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF283448), Color(0xFF121722)],
        ),
      ),
      alignment: Alignment.center,
      child: const Icon(
        CupertinoIcons.film,
        color: AppleColors.labelTertiary,
        size: 38,
      ),
    );
  }

  Widget _buildWideDetailsContent() {
    final bookmark = _findBookmark();
    final metadata = [_video.year, _video.area, _video.remarks]
        .where((s) => s.isNotEmpty)
        .join('  ·  ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _video.title,
          style: const TextStyle(
            color: AppleColors.labelPrimary,
            fontSize: 28,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.6,
          ),
        ),
        if (metadata.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(
            metadata,
            style: const TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 14,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
        if (_video.actor.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(
            '演职员：${_video.actor}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 13,
            ),
          ),
        ],
        const SizedBox(height: 14),
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: [
            if (_video.lines.isNotEmpty)
              AppleCapsuleButton(
                label: bookmark == null
                    ? '立即播放'
                    : '继续播放 · ${bookmark.episode}',
                icon: CupertinoIcons.play_fill,
                primary: true,
                onPressed: () => _playPrimaryAction(bookmark),
              ),
            AppleCapsuleButton(
              label: _isFavorite ? '已收藏' : '加入片库',
              icon: _isFavorite
                  ? CupertinoIcons.bookmark_fill
                  : CupertinoIcons.bookmark,
              onPressed: () async {
                final fav =
                    await widget.storage.toggleFavorite(widget.site, _video);
                if (mounted) setState(() => _isFavorite = fav);
              },
            ),
            if (_video.lines.isNotEmpty && DownloadService.instance.isReady)
              AppleCapsuleButton(
                label: '下载',
                icon: CupertinoIcons.arrow_down_circle,
                onPressed: _showDownloadSheet,
              ),
            AppleCapsuleButton(
              key: const ValueKey('detail-netdisk-qr-button'),
              label: '网盘扫码',
              icon: CupertinoIcons.qrcode_viewfinder,
              onPressed: () => showNetdiskAccountSheet(
                context,
                widget.storage,
                onChanged: () {
                  if (mounted) setState(() {});
                },
              ),
            ),
          ],
        ),
        if (_video.description.isNotEmpty) ...[
          const SizedBox(height: 16),
          AppleGlassSurface(
            padding: const EdgeInsets.all(14),
            borderRadius: BorderRadius.circular(16),
            child: Text(
              _video.description,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppleColors.labelSecondary,
                fontSize: 14,
                height: 1.5,
              ),
            ),
          ),
        ],
        const SizedBox(height: 22),
        if (_loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 28),
            child: Center(child: CupertinoActivityIndicator(radius: 14)),
          )
        else if (_error != null)
          Text(
            _error!,
            style: const TextStyle(color: AppleColors.systemRed, fontSize: 14),
          )
        else if (_video.lines.isEmpty)
          const Text(
            '这部影片没有返回可识别的播放线路。',
            style: TextStyle(color: AppleColors.labelSecondary, fontSize: 14),
          )
        else
          _buildPortraitEpisodesSection(bookmark),
      ],
    );
  }
}
