import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';
/// Apple AVPlayerViewController / Apple TV+ inspired video player with frosted-glass
/// floating HUD, keyboard shortcuts (macOS/Windows), and touch gestures (iOS/iPadOS).
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
  bool _loading = true;
  bool _buffering = false;
  double _dragStartPosMs = 0;
  int _playSession = 0;
  int _pendingResumeMs = 0;
  CmsLine get _currentLine => widget.video.lines[_lineIndex];
  CmsEpisode get _currentEpisode => _currentLine.episodes[_episodeIndex];
  @override
  void initState() {
    super.initState();
    _lineIndex =
        widget.initialLineIndex.clamp(0, widget.video.lines.length - 1);
    _episodeIndex = widget.initialEpisodeIndex
        .clamp(0, _currentLine.episodes.length - 1);
    _normalSpeed = widget.storage.playSpeed;
    _player = Player();
    _controller = VideoController(_player);
    _player.stream.position.listen((pos) {
      if (mounted) {
        setState(() {
          _position = pos;
          if (pos > Duration.zero && _loading) {
            _loading = false;
          }
        });
      }
    });
    _player.stream.duration.listen((dur) {
      if (mounted) {
        setState(() {
          _duration = dur;
          if (dur > Duration.zero && _loading) {
            _loading = false;
          }
        });
        if (dur > Duration.zero && _pendingResumeMs > 0) {
          final targetMs = _pendingResumeMs.clamp(0, dur.inMilliseconds);
          _pendingResumeMs = 0;
          _player.seek(Duration(milliseconds: targetMs));
        }
      }
    });
    _player.stream.playing.listen((playing) {
      if (mounted) {
        setState(() {
          _playing = playing;
          if (playing) _loading = false;
        });
      }
    });
    _player.stream.buffering.listen((buffering) {
      if (mounted) setState(() => _buffering = buffering);
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
    final session = ++_playSession;
    _pendingResumeMs = resumeMs > 0 ? resumeMs : 0;
    if (!_currentEpisode.isDirectPlayable) {
      _pendingResumeMs = 0;
      if (mounted) {
        setState(() {
          _loading = false;
          _playing = false;
          _position = Duration.zero;
          _duration = Duration.zero;
          _hudText = '当前剧集地址需专用插件解析，无法直接播放';
        });
      }
      return;
    }
    if (mounted) {
      setState(() {
        _loading = true;
        _position = Duration.zero;
        _duration = Duration.zero;
      });
    }
    final headers = <String, String>{
      ...widget.site.headers,
      ..._currentEpisode.headers,
    };
    await _player.open(
      Media(_currentEpisode.url, httpHeaders: headers),
      play: true,
    );
    if (!mounted || session != _playSession) return;
    await _player.setRate(_normalSpeed);
    if (!mounted || session != _playSession) return;
    if (_pendingResumeMs > 0) {
      Future.delayed(const Duration(milliseconds: 600), () {
        if (!mounted || session != _playSession || _pendingResumeMs <= 0) {
          return;
        }
        final targetMs = _pendingResumeMs;
        _pendingResumeMs = 0;
        _player.seek(Duration(milliseconds: targetMs));
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
    _showHud(
      '${seconds >= 0 ? "快进" : "快退"} ${_formatDuration(Duration(milliseconds: targetMs))}',
    );
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
                  alignment: const Alignment(0, -0.74),
                  child: AppleGlassSurface(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 18,
                      vertical: 10,
                    ),
                    borderRadius: BorderRadius.circular(999),
                    child: Text(
                      _hudText,
                      style: const TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              if (_controlsVisible) _buildAppleChromeOverlay(context),
              if (_loading || _buffering)
                Center(
                  child: AppleGlassSurface(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 22,
                      vertical: 15,
                    ),
                    borderRadius: BorderRadius.circular(999),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const CupertinoActivityIndicator(radius: 11),
                        const SizedBox(width: 12),
                        Text(
                          '正在加载 ${_currentEpisode.name}…',
                          style: const TextStyle(
                            color: AppleColors.labelPrimary,
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
  Widget _buildAppleChromeOverlay(BuildContext context) {
    final maxMs = _duration.inMilliseconds > 0
        ? _duration.inMilliseconds.toDouble()
        : 1.0;
    final curMs = _position.inMilliseconds.toDouble().clamp(0.0, maxMs);
    final remaining = _duration > _position
        ? _duration - _position
        : Duration.zero;
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xB8000000),
            Color(0x00000000),
            Color(0x00000000),
            Color(0xD8000000),
          ],
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      child: SafeArea(
        child: Column(
          children: [
            // Top Frosted Header Pill
            Row(
              children: [
                CupertinoButton(
                  padding: EdgeInsets.zero,
                  minSize: 38,
                  onPressed: () => Navigator.of(context).maybePop(),
                  child: const AppleGlassSurface(
                    padding: EdgeInsets.symmetric(horizontal: 13, vertical: 8),
                    borderRadius: BorderRadius.all(Radius.circular(999)),
                    child: Row(
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
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.video.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: AppleColors.labelPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.3,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${_currentEpisode.name} · ${_currentLine.name}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: AppleColors.labelSecondary,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const Spacer(),
            // Bottom Floating Apple Glass Console
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 680),
              child: AppleGlassSurface(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 12),
                borderRadius: BorderRadius.circular(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        Text(
                          _formatDuration(_position),
                          style: const TextStyle(
                            color: AppleColors.labelSecondary,
                            fontSize: 12,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                        Expanded(
                          child: SliderTheme(
                            data: SliderTheme.of(context).copyWith(
                              trackHeight: 4,
                              thumbShape: const RoundSliderThumbShape(
                                enabledThumbRadius: 6,
                              ),
                              overlayShape: const RoundSliderOverlayShape(
                                overlayRadius: 12,
                              ),
                              activeTrackColor: AppleColors.labelPrimary,
                              inactiveTrackColor: const Color(0x44FFFFFF),
                              thumbColor: AppleColors.labelPrimary,
                            ),
                            child: Slider(
                              value: curMs,
                              min: 0,
                              max: maxMs,
                              onChanged: (value) {
                                _player.seek(
                                  Duration(milliseconds: value.round()),
                                );
                                _scheduleHide();
                              },
                            ),
                          ),
                        ),
                        Text(
                          _duration.inMilliseconds > 0
                              ? '-${_formatDuration(remaining)}'
                              : 'LIVE',
                          style: const TextStyle(
                            color: AppleColors.labelSecondary,
                            fontSize: 12,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      alignment: WrapAlignment.center,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        _buildIconControl(
                          CupertinoIcons.gobackward_15,
                          () => _seekRelative(-15),
                        ),
                        _buildIconControl(
                          _playing
                              ? CupertinoIcons.pause_fill
                              : CupertinoIcons.play_fill,
                          () {
                            _player.playOrPause();
                            _scheduleHide();
                          },
                          large: true,
                        ),
                        _buildIconControl(
                          CupertinoIcons.goforward_15,
                          () => _seekRelative(15),
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
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
  Widget _buildIconControl(
    IconData icon,
    VoidCallback onTap, {
    bool large = false,
  }) {
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: large ? 42 : 34,
      onPressed: () {
        _scheduleHide();
        onTap();
      },
      child: Container(
        width: large ? 42 : 34,
        height: large ? 42 : 34,
        decoration: BoxDecoration(
          color: large ? AppleColors.labelPrimary : const Color(0x22FFFFFF),
          shape: BoxShape.circle,
        ),
        alignment: Alignment.center,
        child: Icon(
          icon,
          color: large ? Colors.black : AppleColors.labelPrimary,
          size: large ? 20 : 16,
        ),
      ),
    );
  }
  Widget _dockButton(String label, VoidCallback onPressed) {
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: 32,
      onPressed: () {
        _scheduleHide();
        onPressed();
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
        decoration: BoxDecoration(
          color: const Color(0x22FFFFFF),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: AppleColors.glassBorder, width: 0.5),
        ),
        child: Text(
          label,
          style: const TextStyle(
            color: AppleColors.labelPrimary,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
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
    showAppleModalSheet<void>(
      context: context,
      title: '播放倍速',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: speeds.map((s) {
              final selected = _normalSpeed == s;
              return AppleGroupedTile(
                icon: CupertinoIcons.speedometer,
                iconBgColor: selected
                    ? AppleColors.systemBlue
                    : AppleColors.groupedTertiary,
                title: '$s× 倍速',
                trailing: selected
                    ? const Icon(
                        CupertinoIcons.checkmark_alt,
                        color: AppleColors.systemBlue,
                        size: 18,
                      )
                    : null,
                onTap: () {
                  Navigator.of(ctx).pop();
                  setState(() => _normalSpeed = s);
                  _player.setRate(s);
                  widget.storage.setPlaySpeed(s);
                },
              );
            }).toList(),
          ),
        ],
      ),
    );
  }
  void _showLinesSheet() {
    showAppleModalSheet<void>(
      context: context,
      title: '切换播放线路',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: List.generate(widget.video.lines.length, (i) {
              final selected = i == _lineIndex;
              return AppleGroupedTile(
                icon: CupertinoIcons.layers_alt_fill,
                iconBgColor: selected
                    ? AppleColors.systemBlue
                    : AppleColors.groupedTertiary,
                title: widget.video.lines[i].name,
                subtitle: '${widget.video.lines[i].episodes.length} 集',
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
                    _lineIndex = i;
                    _episodeIndex = _episodeIndex.clamp(
                      0,
                      widget.video.lines[i].episodes.length - 1,
                    );
                  });
                  _openCurrentEpisode();
                },
              );
            }),
          ),
        ],
      ),
    );
  }
  void _showEpisodesSheet() {
    showAppleModalSheet<void>(
      context: context,
      title: '切换剧集 (${_currentLine.episodes.length})',
      builder: (ctx) => GridView.builder(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 148,
          mainAxisSpacing: 8,
          crossAxisSpacing: 8,
          childAspectRatio: 2.7,
        ),
        itemCount: _currentLine.episodes.length,
        itemBuilder: (ctx, i) {
          final ep = _currentLine.episodes[i];
          final selected = i == _episodeIndex;
          return GestureDetector(
            onTap: () {
              Navigator.of(ctx).pop();
              setState(() => _episodeIndex = i);
              _openCurrentEpisode();
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              decoration: BoxDecoration(
                color: selected
                    ? AppleColors.systemBlue
                    : AppleColors.groupedSecondary,
                borderRadius: BorderRadius.circular(10),
              ),
              alignment: Alignment.center,
              child: Text(
                ep.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: AppleColors.labelPrimary,
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
