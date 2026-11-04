import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../core/cms_client.dart';
import '../core/cms_parser.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';
import 'player_screen.dart';
/// Apple TV+ inspired responsive video detail page for iPhone, iPad, Mac, and Windows.
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
  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final wideLayout = width >= 720;
    return Scaffold(
      backgroundColor: AppleColors.background,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // Ambient blurred poster header backdrop
          if (_video.poster.isNotEmpty)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: 380,
              child: Opacity(
                opacity: 0.38,
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 42, sigmaY: 42),
                  child: Image.network(
                    _video.poster,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => const SizedBox.shrink(),
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
                // Cupertino Top Navigation Bar
                Padding(
                  padding: EdgeInsets.symmetric(
                    horizontal: wideLayout ? 28 : 12,
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
                    padding: EdgeInsets.fromLTRB(
                      wideLayout ? 36 : 18,
                      8,
                      wideLayout ? 36 : 18,
                      36,
                    ),
                    child: wideLayout
                        ? Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              SizedBox(
                                width: 240,
                                child: _buildPosterCard(),
                              ),
                              const SizedBox(width: 32),
                              Expanded(child: _buildDetailsContent(wide: true)),
                            ],
                          )
                        : Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  SizedBox(
                                    width: 118,
                                    child: _buildPosterCard(),
                                  ),
                                  const SizedBox(width: 16),
                                  Expanded(child: _buildCompactHeaderInfo()),
                                ],
                              ),
                              const SizedBox(height: 18),
                              _buildDetailsContent(wide: false),
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
        child: _video.poster.isNotEmpty
            ? Image.network(
                _video.poster,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => _posterPlaceholder(),
              )
            : _posterPlaceholder(),
      ),
    );
  }
  Widget _buildCompactHeaderInfo() {
    final badges = [_video.year, _video.area, _video.remarks]
        .where((s) => s.isNotEmpty)
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _video.title,
          maxLines: 3,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: AppleColors.labelPrimary,
            fontSize: 21,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.4,
          ),
        ),
        if (badges.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: badges
                .map(
                  (b) => Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: AppleColors.groupedSecondary,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      b,
                      style: const TextStyle(
                        color: AppleColors.labelSecondary,
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
        ],
        if (_video.actor.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(
            _video.actor,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppleColors.labelSecondary,
              fontSize: 12,
              height: 1.4,
            ),
          ),
        ],
      ],
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
  Widget _buildDetailsContent({required bool wide}) {
    final bookmark = _findBookmark();
    final metadata = [_video.year, _video.area, _video.remarks]
        .where((s) => s.isNotEmpty)
        .join('  ·  ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (wide) ...[
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
        ],
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
                onPressed: () {
                  if (bookmark != null) {
                    final lineIdx = _video.lines
                        .indexWhere((l) => l.name == bookmark.line)
                        .clamp(0, _video.lines.length - 1);
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
                    _openPlayer(0, 0);
                  }
                },
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
          ],
        ),
        if (_video.description.isNotEmpty) ...[
          const SizedBox(height: 16),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppleColors.groupedPrimary,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppleColors.glassBorder, width: 0.5),
            ),
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
          _buildEpisodesSection(bookmark),
      ],
    );
  }
  Widget _buildEpisodesSection(WatchHistoryEntry? bookmark) {
    final safeLineIdx = _selectedLine.clamp(0, _video.lines.length - 1);
    final line = _video.lines[safeLineIdx];
    final total = line.episodes.length;
    final pageCount =
        (total + CmsParser.episodePageSize - 1) ~/ CmsParser.episodePageSize;
    final safePage = _selectedPage.clamp(0, pageCount > 0 ? pageCount - 1 : 0);
    final start = safePage * CmsParser.episodePageSize;
    final end = (start + CmsParser.episodePageSize).clamp(0, total);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Line Selector Row
        SizedBox(
          height: 36,
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
                    vertical: 7,
                  ),
                  decoration: BoxDecoration(
                    color: selected
                        ? AppleColors.labelPrimary
                        : AppleColors.groupedPrimary,
                    borderRadius: BorderRadius.circular(999),
                    border: selected
                        ? null
                        : Border.all(
                            color: AppleColors.glassBorder,
                            width: 0.5,
                          ),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    _video.lines[i].name,
                    style: TextStyle(
                      color: selected
                          ? const Color(0xFF000000)
                          : AppleColors.labelPrimary,
                      fontSize: 13,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Text(
              '选集 ($total)',
              style: const TextStyle(
                color: AppleColors.labelPrimary,
                fontSize: 16,
                fontWeight: FontWeight.w700,
              ),
            ),
            const Spacer(),
            CupertinoButton(
              padding: EdgeInsets.zero,
              minSize: 28,
              onPressed: () {
                final next = !_descending;
                widget.storage.setEpisodesDescending(next);
                setState(() {
                  _descending = next;
                  _selectedPage = 0;
                });
              },
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 11,
                  vertical: 5,
                ),
                decoration: BoxDecoration(
                  color: AppleColors.groupedPrimary,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: AppleColors.glassBorder,
                    width: 0.5,
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _descending
                          ? CupertinoIcons.sort_down
                          : CupertinoIcons.sort_up,
                      color: AppleColors.labelSecondary,
                      size: 14,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      _descending ? '倒序' : '正序',
                      style: const TextStyle(
                        color: AppleColors.labelPrimary,
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
        if (pageCount > 1) ...[
          const SizedBox(height: 10),
          SizedBox(
            height: 32,
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
                      horizontal: 12,
                      vertical: 5,
                    ),
                    decoration: BoxDecoration(
                      color: selected
                          ? AppleColors.systemBlue
                          : AppleColors.groupedPrimary,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      '$pStart-$pEnd',
                      style: TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 12,
                        fontWeight:
                            selected ? FontWeight.w700 : FontWeight.w500,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
        const SizedBox(height: 12),
        GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 156,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 2.8,
          ),
          itemCount: end - start,
          itemBuilder: (context, idx) {
            final visualIdx = start + idx;
            final originalIdx =
                _descending ? (total - 1 - visualIdx) : visualIdx;
            final ep = line.episodes[originalIdx];
            final isBookmarked = bookmark != null &&
                bookmark.line == line.name &&
                bookmark.index == originalIdx;
            return GestureDetector(
              onTap: () => _openPlayer(safeLineIdx, originalIdx),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                decoration: BoxDecoration(
                  color: isBookmarked
                      ? const Color(0x330A84FF)
                      : AppleColors.groupedPrimary,
                  borderRadius: BorderRadius.circular(11),
                  border: Border.all(
                    color: isBookmarked
                        ? AppleColors.systemBlue
                        : AppleColors.glassBorder,
                    width: isBookmarked ? 1 : 0.5,
                  ),
                ),
                alignment: Alignment.center,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (isBookmarked) ...[
                      const Icon(
                        CupertinoIcons.play_fill,
                        color: AppleColors.systemBlue,
                        size: 12,
                      ),
                      const SizedBox(width: 4),
                    ],
                    Flexible(
                      child: Text(
                        ep.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: isBookmarked
                              ? AppleColors.systemBlue
                              : AppleColors.labelPrimary,
                          fontSize: 13,
                          fontWeight:
                              isBookmarked ? FontWeight.w700 : FontWeight.w500,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ],
    );
  }
}
