import 'package:flutter/material.dart';
import '../core/cms_client.dart';
import '../core/cms_parser.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'player_screen.dart';

/// Responsive video detail page for Windows/macOS/iPad two-column and iPhone stacked layouts.
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
          MaterialPageRoute<void>(
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
      backgroundColor: const Color(0xFF101013),
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: TextButton.icon(
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const Icon(Icons.chevron_left, color: Color(0xFFA1A1AA)),
          label: const Text('返回', style: TextStyle(color: Color(0xFFA1A1AA))),
        ),
        leadingWidth: 96,
      ),
      body: SafeArea(
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: wideLayout ? 36 : 18,
            vertical: 12,
          ),
          child: wideLayout
              ? Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 260,
                      child: _buildPosterColumn(wideLayout: true),
                    ),
                    const SizedBox(width: 32),
                    Expanded(
                      child: SingleChildScrollView(
                        child: _buildDetailsContent(),
                      ),
                    ),
                  ],
                )
              : SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildPosterColumn(wideLayout: false),
                      const SizedBox(height: 18),
                      _buildDetailsContent(),
                    ],
                  ),
                ),
        ),
      ),
    );
  }

  Widget _buildPosterColumn({required bool wideLayout}) {
    final posterWidget = ClipRRect(
      borderRadius: BorderRadius.circular(16),
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

    if (wideLayout) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _video.title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: Color(0xFFF5F5F7),
              fontSize: 24,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 12),
          Expanded(child: posterWidget),
        ],
      );
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(width: 128, child: posterWidget),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _video.title,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Color(0xFFF5F5F7),
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                [_video.year, _video.area, _video.remarks]
                    .where((s) => s.isNotEmpty)
                    .join('  ·  '),
                style: const TextStyle(color: Color(0xFFA1A1AA), fontSize: 13),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _posterPlaceholder() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF304C70), Color(0xFF141C2C)],
        ),
      ),
      alignment: Alignment.center,
      child: const Icon(Icons.movie_outlined, color: Colors.white38, size: 42),
    );
  }

  Widget _buildDetailsContent() {
    final bookmark = _findBookmark();
    final metadata = [_video.year, _video.area, _video.remarks]
        .where((s) => s.isNotEmpty)
        .join('   ');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (metadata.isNotEmpty) ...[
          Text(
            metadata,
            style: const TextStyle(color: Color(0xFFA1A1AA), fontSize: 14),
          ),
          const SizedBox(height: 10),
        ],
        if (_video.description.isNotEmpty) ...[
          Text(
            _video.description,
            maxLines: 4,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: Color(0xFFCECED3),
              fontSize: 15,
              height: 1.45,
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (_video.actor.isNotEmpty) ...[
          Text(
            '演员  ${_video.actor}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Color(0xFFA1A1AA), fontSize: 13),
          ),
          const SizedBox(height: 12),
        ],
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            if (_video.lines.isNotEmpty)
              FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFFF5F5F7),
                  foregroundColor: const Color(0xFF101013),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 22,
                    vertical: 14,
                  ),
                ),
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
                child: Text(
                  bookmark == null ? '开始播放' : '继续播放 · ${bookmark.episode}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
            OutlinedButton(
              style: OutlinedButton.styleFrom(
                foregroundColor: Colors.white,
                side: const BorderSide(color: Colors.white24),
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 14,
                ),
              ),
              onPressed: () async {
                final fav =
                    await widget.storage.toggleFavorite(widget.site, _video);
                if (mounted) setState(() => _isFavorite = fav);
              },
              child: Text(_isFavorite ? '已收藏' : '加入收藏'),
            ),
          ],
        ),
        const SizedBox(height: 18),
        if (_loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: CircularProgressIndicator(),
          )
        else if (_error != null)
          Text(_error!, style: const TextStyle(color: Colors.redAccent))
        else if (_video.lines.isEmpty)
          const Text(
            '这部影片没有返回可识别的播放线路。',
            style: TextStyle(color: Color(0xFFA1A1AA)),
          )
        else
          _buildEpisodesSection(),
      ],
    );
  }

  Widget _buildEpisodesSection() {
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
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: List.generate(_video.lines.length, (i) {
            final selected = i == safeLineIdx;
            return ChoiceChip(
              label: Text(_video.lines[i].name),
              selected: selected,
              selectedColor: const Color(0xFF465C76),
              backgroundColor: const Color(0xFF242429),
              labelStyle: const TextStyle(color: Colors.white),
              onSelected: (_) => setState(() {
                _selectedLine = i;
                _selectedPage = 0;
              }),
            );
          }),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Text(
              '$total 集',
              style: const TextStyle(color: Color(0xFFA1A1AA), fontSize: 13),
            ),
            const Spacer(),
            TextButton(
              onPressed: () {
                final next = !_descending;
                widget.storage.setEpisodesDescending(next);
                setState(() {
                  _descending = next;
                  _selectedPage = 0;
                });
              },
              child: Text(
                _descending ? '↓ 倒序' : '↑ 正序',
                style: const TextStyle(color: Colors.white),
              ),
            ),
          ],
        ),
        if (pageCount > 1) ...[
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: List.generate(pageCount, (p) {
              final pStart = p * CmsParser.episodePageSize + 1;
              final pEnd = ((p + 1) * CmsParser.episodePageSize).clamp(1, total);
              return ChoiceChip(
                label: Text('$pStart-$pEnd'),
                selected: p == safePage,
                onSelected: (_) => setState(() => _selectedPage = p),
              );
            }),
          ),
          const SizedBox(height: 10),
        ],
        GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 160,
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            childAspectRatio: 2.8,
          ),
          itemCount: end - start,
          itemBuilder: (context, idx) {
            final visualIdx = start + idx;
            final originalIdx =
                _descending ? (total - 1 - visualIdx) : visualIdx;
            final ep = line.episodes[originalIdx];
            return FilledButton.tonal(
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF242429),
                foregroundColor: const Color(0xFFF5F5F7),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              onPressed: () => _openPlayer(safeLineIdx, originalIdx),
              child: Text(
                ep.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13),
              ),
            );
          },
        ),
      ],
    );
  }
}
