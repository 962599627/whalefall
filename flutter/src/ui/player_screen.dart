import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import '../core/models.dart';
import '../core/storage_service.dart';

/// Cross-platform video player supporting Windows/macOS keyboard shortcuts and iOS/iPadOS touch gestures.
class PlayerScreen extends StatefulWidget {
  final CmsSite site;
  final CmsVideo video;
  final int initialLineIndex;
  final int initialEpisodeIndex;
  final int initialPositionMs;
  final StorageService storage;

  const PlayerScreen({
    super.key,
    required this.site,
    required this.video,
    required this.initialLineIndex,
    required this.initialEpisodeIndex,
    required this.storage,
    this.initialPositionMs = 0,
  });

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  late final Player _player;
  late final VideoController _controller;
  late int _lineIndex;
  late int _episodeIndex;
  late double _normalSpeed;
  bool _controlsVisible = true;
  bool _speedBoosting = false;
  String _hudText = '';
  BoxFit _fit = BoxFit.contain;
  Timer? _hideTimer;
  Timer? _historyTimer;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  bool _playing = false;
  double _dragStartPosMs = 0;

  CmsLine get _currentLine => widget.video.lines[_lineIndex];
  CmsEpisode get _currentEpisode => _currentLine.episodes[_episodeIndex];

  @override
  void initState() {
    super.initState();
    _lineIndex = widget.initialLineIndex.clamp(0, widget.video.lines.length - 1);
    _episodeIndex = widget.initialEpisodeIndex
        .clamp(0, _currentLine.episodes.length - 1);
    _normalSpeed = widget.storage.playSpeed;
    _player = Player();
    _controller = VideoController(_player);

    _player.stream.position.listen((pos) {
      if (mounted) setState(() => _position = pos);
    });
    _player.stream.duration.listen((dur) {
      if (mounted) setState(() => _duration = dur);
    });
    _player.stream.playing.listen((playing) {
      if (mounted) setState(() => _playing = playing);
    });
    _player.stream.completed.listen((completed) {
      if (completed && mounted && widget.storage.autoNext) {
        _stepEpisode(1);
      }
    });

    _openCurrentEpisode(resumeMs: widget.initialPositionMs);
    _historyTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _saveProgress(),
    );
  }

  Future<void> _openCurrentEpisode({int resumeMs = 0}) async {
    final headers = <String, String>{
      ...widget.site.headers,
      ..._currentEpisode.headers,
    };
    await _player.open(
      Media(_currentEpisode.url, httpHeaders: headers),
      play: true,
    );
    await _player.setRate(_normalSpeed);
    if (resumeMs > 0) {
      Future.delayed(const Duration(milliseconds: 600), () {
        if (mounted) {
          _player.seek(Duration(milliseconds: resumeMs));
        }
      });
    }
    _scheduleHide();
  }

  Future<void> _saveProgress() async {
    if (widget.site.key == 'live' || _duration.inMilliseconds <= 0) return;
    await widget.storage.upsertWatchHistory(
      WatchHistoryEntry(
        site: widget.site.key,
        id: widget.video.id,
        title: widget.video.title,
        poster: widget.video.poster,
        line: _currentLine.name,
        episode: _currentEpisode.name,
        index: _episodeIndex,
        position: _position.inMilliseconds,
        duration: _duration.inMilliseconds,
        updated: DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  void _scheduleHide() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && _playing) {
        setState(() => _controlsVisible = false);
      }
    });
  }

  void _toggleControls() {
    setState(() => _controlsVisible = !_controlsVisible);
    if (_controlsVisible) _scheduleHide();
  }

  void _stepEpisode(int delta) {
    final next = _episodeIndex + delta;
    if (next < 0 || next >= _currentLine.episodes.length) return;
    _saveProgress();
    setState(() => _episodeIndex = next);
    _openCurrentEpisode();
  }

  void _seekRelative(int seconds) {
    if (_duration.inMilliseconds <= 0) return;
    final targetMs = (_position.inMilliseconds + seconds * 1000)
        .clamp(0, _duration.inMilliseconds);
    _player.seek(Duration(milliseconds: targetMs));
    _showHud('${seconds >= 0 ? "快进" : "快退"} ${_formatDuration(Duration(milliseconds: targetMs))}');
    _scheduleHide();
  }

  void _showHud(String text) {
    setState(() => _hudText = text);
    Future.delayed(const Duration(milliseconds: 1100), () {
      if (mounted && !_speedBoosting && _hudText == text) {
        setState(() => _hudText = '');
      }
    });
  }

  String _formatDuration(Duration d) {
    final totalSeconds = d.inSeconds < 0 ? 0 : d.inSeconds;
    final h = totalSeconds ~/ 3600;
    final m = (totalSeconds % 3600) ~/ 60;
    final s = totalSeconds % 60;
    if (h > 0) {
      return '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
    }
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _historyTimer?.cancel();
    _saveProgress();
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Focus(
        autofocus: true,
        onKeyEvent: (node, event) {
          if (event is! KeyDownEvent) return KeyEventResult.ignored;
          if (event.logicalKey == LogicalKeyboardKey.space ||
              event.logicalKey == LogicalKeyboardKey.enter ||
              event.logicalKey == LogicalKeyboardKey.select) {
            _player.playOrPause();
            setState(() => _controlsVisible = true);
            _scheduleHide();
            return KeyEventResult.handled;
          }
          if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
            _seekRelative(15);
            return KeyEventResult.handled;
          }
          if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
            _seekRelative(-15);
            return KeyEventResult.handled;
          }
          if (event.logicalKey == LogicalKeyboardKey.escape) {
            Navigator.of(context).maybePop();
            return KeyEventResult.handled;
          }
          return KeyEventResult.ignored;
        },
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _toggleControls,
          onDoubleTap: () {
            _player.playOrPause();
            setState(() => _controlsVisible = true);
            _scheduleHide();
          },
          onLongPressStart: (_) {
            if (_playing && _duration.inMilliseconds > 0) {
              setState(() {
                _speedBoosting = true;
                _hudText = '2.0× 倍速播放中 · 松手恢复';
              });
              _player.setRate(2.0);
            }
          },
          onLongPressEnd: (_) {
            if (_speedBoosting) {
              _player.setRate(_normalSpeed);
              setState(() {
                _speedBoosting = false;
                _hudText = '';
              });
            }
          },
          onHorizontalDragStart: (_) {
            _dragStartPosMs = _position.inMilliseconds.toDouble();
          },
          onHorizontalDragUpdate: (details) {
            if (_duration.inMilliseconds <= 0) return;
            final width = MediaQuery.sizeOf(context).width.clamp(200.0, 4000.0);
            final deltaMs = (details.localPosition.dx / width - 0.5) * 120000;
            final previewMs = (_dragStartPosMs + deltaMs)
                .round()
                .clamp(0, _duration.inMilliseconds);
            setState(() {
              _hudText =
                  '跳转至 ${_formatDuration(Duration(milliseconds: previewMs))}';
            });
          },
          onHorizontalDragEnd: (_) {
            setState(() => _hudText = '');
          },
          child: Stack(
            fit: StackFit.expand,
            children: [
              Center(
                child: Video(
                  controller: _controller,
                  fit: _fit,
                  controls: NoVideoControls,
                ),
              ),
              if (_hudText.isNotEmpty)
                Align(
                  alignment: const Alignment(0, -0.75),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 18,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xD8202026),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      _hudText,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              if (_controlsVisible) _buildChromeOverlay(context),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildChromeOverlay(BuildContext context) {
    final maxMs = _duration.inMilliseconds > 0
        ? _duration.inMilliseconds.toDouble()
        : 1.0;
    final curMs = _position.inMilliseconds.toDouble().clamp(0.0, maxMs);

    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xCC000000),
            Color(0x00000000),
            Color(0x00000000),
            Color(0xDD000000),
          ],
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                IconButton(
                  onPressed: () => Navigator.of(context).maybePop(),
                  icon: const Icon(Icons.arrow_back_ios_new, color: Colors.white),
                  tooltip: '返回',
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.video.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${_currentEpisode.name}  ·  ${_currentLine.name}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xFFDDDDDF),
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const Spacer(),
            Row(
              children: [
                Text(
                  _formatDuration(_position),
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
                Expanded(
                  child: Slider(
                    value: curMs,
                    min: 0,
                    max: maxMs,
                    activeColor: Colors.white,
                    inactiveColor: Colors.white24,
                    onChanged: (value) {
                      _player.seek(Duration(milliseconds: value.round()));
                      _scheduleHide();
                    },
                  ),
                ),
                Text(
                  _duration.inMilliseconds > 0
                      ? _formatDuration(_duration)
                      : '直播',
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Center(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: const Color(0xED262629),
                  borderRadius: BorderRadius.circular(30),
                  border: Border.all(color: const Color(0x30FFFFFF)),
                ),
                child: Wrap(
                  spacing: 8,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    IconButton(
                      onPressed: () {
                        _player.playOrPause();
                        _scheduleHide();
                      },
                      icon: Icon(
                        _playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                        color: Colors.white,
                      ),
                      tooltip: _playing ? '暂停' : '播放',
                    ),
                    if (_episodeIndex < _currentLine.episodes.length - 1)
                      _dockButton('下集', () => _stepEpisode(1)),
                    _dockButton('选集', _showEpisodesSheet),
                    if (widget.video.lines.length > 1)
                      _dockButton('线路', _showLinesSheet),
                    _dockButton('${_normalSpeed}×', _showSpeedSheet),
                    _dockButton('画面', _cycleAspect),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _dockButton(String label, VoidCallback onPressed) {
    return TextButton(
      onPressed: () {
        _scheduleHide();
        onPressed();
      },
      style: TextButton.styleFrom(
        foregroundColor: Colors.white,
        backgroundColor: const Color(0x18FFFFFF),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
      child: Text(label, style: const TextStyle(fontSize: 13)),
    );
  }

  void _cycleAspect() {
    setState(() {
      if (_fit == BoxFit.contain) {
        _fit = BoxFit.cover;
        _showHud('画面：裁切填充');
      } else if (_fit == BoxFit.cover) {
        _fit = BoxFit.fill;
        _showHud('画面：铺满屏幕');
      } else {
        _fit = BoxFit.contain;
        _showHud('画面：适应屏幕');
      }
    });
  }

  void _showSpeedSheet() {
    const speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0];
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF1C1C22),
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: speeds
              .map(
                (s) => ListTile(
                  title: Text(
                    '$s×',
                    style: const TextStyle(color: Colors.white),
                  ),
                  trailing: _normalSpeed == s
                      ? const Icon(Icons.check, color: Colors.white)
                      : null,
                  onTap: () {
                    Navigator.pop(ctx);
                    setState(() => _normalSpeed = s);
                    _player.setRate(s);
                    widget.storage.setPlaySpeed(s);
                  },
                ),
              )
              .toList(),
        ),
      ),
    );
  }

  void _showLinesSheet() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF1C1C22),
      builder: (ctx) => SafeArea(
        child: ListView.builder(
          shrinkWrap: true,
          itemCount: widget.video.lines.length,
          itemBuilder: (ctx, i) => ListTile(
            title: Text(
              widget.video.lines[i].name,
              style: const TextStyle(color: Colors.white),
            ),
            trailing: i == _lineIndex
                ? const Icon(Icons.check, color: Colors.white)
                : null,
            onTap: () {
              Navigator.pop(ctx);
              setState(() {
                _lineIndex = i;
                _episodeIndex = _episodeIndex.clamp(
                  0,
                  widget.video.lines[i].episodes.length - 1,
                );
              });
              _openCurrentEpisode();
            },
          ),
        ),
      ),
    );
  }

  void _showEpisodesSheet() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF1C1C22),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: GridView.builder(
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 140,
              mainAxisSpacing: 8,
              crossAxisSpacing: 8,
              childAspectRatio: 2.6,
            ),
            itemCount: _currentLine.episodes.length,
            itemBuilder: (ctx, i) {
              final ep = _currentLine.episodes[i];
              final selected = i == _episodeIndex;
              return FilledButton.tonal(
                style: FilledButton.styleFrom(
                  backgroundColor: selected
                      ? const Color(0xFF465C76)
                      : const Color(0xFF282830),
                  foregroundColor: Colors.white,
                ),
                onPressed: () {
                  Navigator.pop(ctx);
                  setState(() => _episodeIndex = i);
                  _openCurrentEpisode();
                },
                child: Text(
                  ep.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
