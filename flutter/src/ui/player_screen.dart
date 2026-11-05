import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import '../core/cms_client.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';

/// Apple AVPlayerViewController / Apple TV+ inspired video player with:
/// - Large center transport controls (Rewind 15s, Play/Pause, Fast-Forward 15s)
/// - Dedicated floating Progress Bar capsule with Portrait/Landscape fullscreen toggle
/// - Separate scrollable Tool Dock (Bottom horizontal in Portrait, Right vertical in Landscape)
///   exposing all adjustable playback controls.
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
  final CmsClient _client = CmsClient();

  late int _lineIndex;
  late int _episodeIndex;
  late double _normalSpeed;
  late int _skipIntroSec;
  late int _skipOutroSec;
  late bool _autoNext;
  late String _repeatMode;

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
  bool _introSkippedForSession = false;
  bool _outroSkippedForSession = false;

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
    _skipIntroSec = widget.storage.skipIntroSec;
    _skipOutroSec = widget.storage.skipOutroSec;
    _autoNext = widget.storage.autoNext;
    _repeatMode = widget.storage.repeatMode;

    _player = Player();
    _controller = VideoController(_player);

    _player.stream.position.listen((pos) {
      if (!mounted) return;
      setState(() {
        _position = pos;
        if (pos > Duration.zero && _loading) {
          _loading = false;
        }
      });
      if (!_outroSkippedForSession &&
          _skipOutroSec > 0 &&
          _duration.inSeconds > _skipOutroSec + 30 &&
          pos.inSeconds > 10 &&
          (_duration - pos).inSeconds <= _skipOutroSec) {
        _outroSkippedForSession = true;
        _showHud('已自动跳过片尾 ${_skipOutroSec}s');
        _handleEpisodeEnd();
      }
    });

    _player.stream.duration.listen((dur) {
      if (!mounted) return;
      setState(() {
        _duration = dur;
        if (dur > Duration.zero && _loading) {
          _loading = false;
        }
      });
      if (dur > Duration.zero && _pendingResumeMs > 0) {
        final targetMs = _pendingResumeMs.clamp(0, dur.inMilliseconds);
        _pendingResumeMs = 0;
        _introSkippedForSession = true;
        _player.seek(Duration(milliseconds: targetMs));
      } else if (dur > Duration.zero &&
          !_introSkippedForSession &&
          _skipIntroSec > 0 &&
          dur.inSeconds > _skipIntroSec + 30) {
        _introSkippedForSession = true;
        _player.seek(Duration(seconds: _skipIntroSec));
        _showHud('已自动跳过片头 ${_skipIntroSec}s');
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
      if (completed && mounted && !_outroSkippedForSession) {
        _handleEpisodeEnd();
      }
    });

    _openCurrentEpisode(resumeMs: widget.initialPositionMs);
    _historyTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _saveProgress(),
    );
  }

  void _handleEpisodeEnd() {
    if (_repeatMode == 'one') {
      _player.seek(Duration.zero);
      _player.play();
      _outroSkippedForSession = false;
      return;
    }
    if (_autoNext) {
      if (_episodeIndex < _currentLine.episodes.length - 1) {
        _stepEpisode(1);
      } else if (_repeatMode == 'all' && _currentLine.episodes.isNotEmpty) {
        _saveProgress();
        setState(() => _episodeIndex = 0);
        _openCurrentEpisode();
      }
    }
  }

  Future<void> _openCurrentEpisode({int resumeMs = 0}) async {
    final session = ++_playSession;
    _pendingResumeMs = resumeMs > 0 ? resumeMs : 0;
    _introSkippedForSession = resumeMs > 0;
    _outroSkippedForSession = false;

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

    final resolvedEpisode = _currentEpisode.isLocalFile
        ? _currentEpisode
        : await _client.resolveEpisodeForPlayback(
            widget.site,
            _currentEpisode,
          );
    if (!mounted || session != _playSession) return;

    final headers = <String, String>{
      ...widget.site.headers,
      ...resolvedEpisode.headers,
    };
    await _player.open(
      Media(resolvedEpisode.url, httpHeaders: headers),
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

  Future<void> _toggleOrientation() async {
    final size = MediaQuery.sizeOf(context);
    final currentlyLandscape = size.width > size.height;
    if (currentlyLandscape) {
      await SystemChrome.setPreferredOrientations(const [
        DeviceOrientation.portraitUp,
      ]);
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
      _showHud('已切换为竖屏观看');
    } else {
      await SystemChrome.setPreferredOrientations(const [
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
      _showHud('已切换为全屏横屏观看');
    }
    _scheduleHide();
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
    if (!mounted) return;
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

  String get _fitLabel {
    if (_fit == BoxFit.cover) return '裁切';
    if (_fit == BoxFit.fill) return '铺满';
    return '适应';
  }

  String get _repeatLabel {
    if (_repeatMode == 'one') return '单集';
    if (_repeatMode == 'all') return '列表';
    return '关闭';
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _historyTimer?.cancel();
    _saveProgress();
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
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
          if (event.logicalKey == LogicalKeyboardKey.keyF) {
            _toggleOrientation();
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
    final size = MediaQuery.sizeOf(context);
    final isLandscape = size.width > size.height;

    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xB8000000),
            Color(0x26000000),
            Color(0x26000000),
            Color(0xD8000000),
          ],
        ),
      ),
      child: SafeArea(
        child: Stack(
          fit: StackFit.expand,
          children: [
            // 1. Top Header Bar (Back + Title + Quick Orientation Button)
            Positioned(
              left: 16,
              right: isLandscape ? 92 : 16,
              top: 10,
              child: _buildTopHeader(context, isLandscape),
            ),

            // 2. Large Center Transport Buttons (Rewind 15s / Play-Pause / Fast-Forward 15s)
            if (!_loading && !_buffering)
              Center(
                child: Padding(
                  padding: EdgeInsets.only(right: isLandscape ? 72 : 0),
                  child: _buildCenterTransportButtons(),
                ),
              ),

            // 3. Landscape Right-side Scrollable Tool Dock
            if (isLandscape)
              Positioned(
                right: 14,
                top: 14,
                bottom: 14,
                child: Center(
                  child: _buildScrollableToolDock(isLandscape: true),
                ),
              ),

            // 4. Bottom Floating Progress Bar Capsule (+ Bottom Scrollable Tool Dock in Portrait)
            Positioned(
              left: 16,
              right: isLandscape ? 96 : 16,
              bottom: 12,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildProgressBarCapsule(context, isLandscape),
                  if (!isLandscape) ...[
                    const SizedBox(height: 10),
                    _buildScrollableToolDock(isLandscape: false),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTopHeader(BuildContext context, bool isLandscape) {
    return Row(
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
    );
  }

  /// Large center screen transport controls: Rewind 15s, Play/Pause, Fast-Forward 15s.
  Widget _buildCenterTransportButtons() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildCenterCircleButton(
          key: const Key('player-center-rewind'),
          icon: CupertinoIcons.gobackward_15,
          size: 62,
          iconSize: 28,
          hero: false,
          onTap: () => _seekRelative(-15),
        ),
        const SizedBox(width: 32),
        _buildCenterCircleButton(
          key: const Key('player-center-play-pause'),
          icon: _playing ? CupertinoIcons.pause_fill : CupertinoIcons.play_fill,
          size: 82,
          iconSize: 38,
          hero: true,
          onTap: () {
            _player.playOrPause();
            _scheduleHide();
          },
        ),
        const SizedBox(width: 32),
        _buildCenterCircleButton(
          key: const Key('player-center-forward'),
          icon: CupertinoIcons.goforward_15,
          size: 62,
          iconSize: 28,
          hero: false,
          onTap: () => _seekRelative(15),
        ),
      ],
    );
  }

  Widget _buildCenterCircleButton({
    Key? key,
    required IconData icon,
    required double size,
    required double iconSize,
    required bool hero,
    required VoidCallback onTap,
  }) {
    return CupertinoButton(
      key: key,
      padding: EdgeInsets.zero,
      minSize: size,
      onPressed: () {
        _scheduleHide();
        onTap();
      },
      child: AppleGlassSurface(
        width: size,
        height: size,
        padding: EdgeInsets.zero,
        borderRadius: BorderRadius.circular(size / 2),
        backgroundColor: hero
            ? const Color(0xD8FFFFFF)
            : const Color(0x78242429),
        child: Center(
          child: Icon(
            icon,
            color: hero ? Colors.black : AppleColors.labelPrimary,
            size: iconSize,
          ),
        ),
      ),
    );
  }

  /// Standalone floating Progress Bar capsule with Fullscreen / Orientation toggle button.
  Widget _buildProgressBarCapsule(BuildContext context, bool isLandscape) {
    final maxMs = _duration.inMilliseconds > 0
        ? _duration.inMilliseconds.toDouble()
        : 1.0;
    final curMs = _position.inMilliseconds.toDouble().clamp(0.0, maxMs);
    final remaining = _duration > _position
        ? _duration - _position
        : Duration.zero;

    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 740),
      child: AppleGlassSurface(
        key: const Key('player-progress-bar'),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        borderRadius: BorderRadius.circular(22),
        child: Row(
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
                  trackHeight: 4.5,
                  thumbShape: const RoundSliderThumbShape(
                    enabledThumbRadius: 6.5,
                  ),
                  overlayShape: const RoundSliderOverlayShape(
                    overlayRadius: 13,
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
                    _player.seek(Duration(milliseconds: value.round()));
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
            const SizedBox(width: 10),
            CupertinoButton(
              key: const Key('player-fullscreen-toggle'),
              padding: EdgeInsets.zero,
              minSize: 34,
              onPressed: _toggleOrientation,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: const Color(0x28FFFFFF),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: AppleColors.glassBorder, width: 0.5),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      isLandscape
                          ? CupertinoIcons.fullscreen_exit
                          : CupertinoIcons.fullscreen,
                      color: AppleColors.labelPrimary,
                      size: 15,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      isLandscape ? '竖屏' : '全屏',
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
      ),
    );
  }

  /// Separate Scrollable Tool Dock:
  /// - Portrait: horizontal scrollable strip at the bottom.
  /// - Landscape: vertical scrollable strip on the right edge.
  /// Exposes all adjustable playback controls.
  Widget _buildScrollableToolDock({required bool isLandscape}) {
    final actions = <(IconData, String, VoidCallback)>[
      if (_episodeIndex > 0)
        (CupertinoIcons.backward_end_fill, '上集', () => _stepEpisode(-1)),
      if (_episodeIndex < _currentLine.episodes.length - 1)
        (CupertinoIcons.forward_end_fill, '下集', () => _stepEpisode(1)),
      (
        CupertinoIcons.square_grid_2x2_fill,
        '选集',
        _showEpisodesSheet,
      ),
      (
        CupertinoIcons.layers_alt_fill,
        '线路',
        _showLinesSheet,
      ),
      (
        CupertinoIcons.speedometer,
        '${_normalSpeed}×',
        _showSpeedSheet,
      ),
      (
        CupertinoIcons.aspectratio,
        '画面·$_fitLabel',
        _cycleAspect,
      ),
      (
        isLandscape
            ? CupertinoIcons.fullscreen_exit
            : CupertinoIcons.fullscreen,
        isLandscape ? '竖屏' : '全屏',
        _toggleOrientation,
      ),
      (
        CupertinoIcons.forward_fill,
        _skipIntroSec > 0 ? '片头${_skipIntroSec}s' : '跳片头',
        _showSkipIntroSheet,
      ),
      (
        CupertinoIcons.forward_end_alt_fill,
        _skipOutroSec > 0 ? '片尾${_skipOutroSec}s' : '跳片尾',
        _showSkipOutroSheet,
      ),
      (
        CupertinoIcons.repeat,
        '连播·${_autoNext ? "开" : "关"}',
        _toggleAutoNext,
      ),
      (
        CupertinoIcons.arrow_2_squarepath,
        '循环·$_repeatLabel',
        _cycleRepeatMode,
      ),
      (
        CupertinoIcons.speaker_2_fill,
        '音轨',
        _showAudioTracksSheet,
      ),
      (
        CupertinoIcons.captions_bubble_fill,
        '字幕',
        _showSubtitleTracksSheet,
      ),
      (
        CupertinoIcons.refresh,
        '重播',
        () {
          _player.seek(Duration.zero);
          _player.play();
          _showHud('已从头开始播放');
        },
      ),
    ];

    if (isLandscape) {
      return AppleGlassSurface(
        key: const Key('player-tool-dock-landscape'),
        width: 72,
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 10),
        borderRadius: BorderRadius.circular(24),
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          scrollDirection: Axis.vertical,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (int i = 0; i < actions.length; i++) ...[
                if (i > 0) const SizedBox(height: 8),
                _buildToolTile(
                  icon: actions[i].$1,
                  label: actions[i].$2,
                  onTap: actions[i].$3,
                  verticalLayout: true,
                ),
              ],
            ],
          ),
        ),
      );
    }

    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 740),
      child: AppleGlassSurface(
        key: const Key('player-tool-dock-portrait'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        borderRadius: BorderRadius.circular(22),
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          scrollDirection: Axis.horizontal,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (int i = 0; i < actions.length; i++) ...[
                if (i > 0) const SizedBox(width: 8),
                _buildToolTile(
                  icon: actions[i].$1,
                  label: actions[i].$2,
                  onTap: actions[i].$3,
                  verticalLayout: false,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildToolTile({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
    required bool verticalLayout,
  }) {
    return CupertinoButton(
      padding: EdgeInsets.zero,
      minSize: 34,
      onPressed: () {
        _scheduleHide();
        onTap();
      },
      child: Container(
        width: verticalLayout ? 60 : null,
        padding: EdgeInsets.symmetric(
          horizontal: verticalLayout ? 6 : 12,
          vertical: verticalLayout ? 8 : 7,
        ),
        decoration: BoxDecoration(
          color: const Color(0x24FFFFFF),
          borderRadius: BorderRadius.circular(verticalLayout ? 14 : 999),
          border: Border.all(color: AppleColors.glassBorder, width: 0.5),
        ),
        child: verticalLayout
            ? Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, color: AppleColors.labelPrimary, size: 17),
                  const SizedBox(height: 4),
                  Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 10.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              )
            : Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, color: AppleColors.labelPrimary, size: 14),
                  const SizedBox(width: 5),
                  Text(
                    label,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
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

  void _toggleAutoNext() {
    final next = !_autoNext;
    setState(() => _autoNext = next);
    widget.storage.setAutoNext(next);
    _showHud('自动连播：${next ? "已开启" : "已关闭"}');
  }

  void _cycleRepeatMode() {
    final next = _repeatMode == 'off'
        ? 'one'
        : (_repeatMode == 'one' ? 'all' : 'off');
    setState(() => _repeatMode = next);
    widget.storage.setRepeatMode(next);
    _showHud('循环模式：$_repeatLabel');
  }

  void _showSkipIntroSheet() {
    const options = [0, 15, 30, 45, 60, 90, 120, 150];
    showAppleModalSheet<void>(
      context: context,
      title: '跳过片头时长',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: options.map((sec) {
              final selected = _skipIntroSec == sec;
              return AppleGroupedTile(
                icon: CupertinoIcons.forward_fill,
                iconBgColor: selected
                    ? AppleColors.systemBlue
                    : AppleColors.groupedTertiary,
                title: sec == 0 ? '不跳过片头' : '跳过前 $sec 秒',
                trailing: selected
                    ? const Icon(
                        CupertinoIcons.checkmark_alt,
                        color: AppleColors.systemBlue,
                        size: 18,
                      )
                    : null,
                onTap: () {
                  Navigator.of(ctx).pop();
                  setState(() => _skipIntroSec = sec);
                  widget.storage.setSkipIntroSec(sec);
                  _showHud(sec == 0 ? '已关闭跳过片头' : '片头跳过已设为 ${sec}s');
                },
              );
            }).toList(),
          ),
        ],
      ),
    );
  }

  void _showSkipOutroSheet() {
    const options = [0, 15, 30, 45, 60, 90, 120, 150];
    showAppleModalSheet<void>(
      context: context,
      title: '跳过片尾时长',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: options.map((sec) {
              final selected = _skipOutroSec == sec;
              return AppleGroupedTile(
                icon: CupertinoIcons.forward_end_alt_fill,
                iconBgColor: selected
                    ? AppleColors.systemBlue
                    : AppleColors.groupedTertiary,
                title: sec == 0 ? '不跳过片尾' : '跳过最后 $sec 秒',
                trailing: selected
                    ? const Icon(
                        CupertinoIcons.checkmark_alt,
                        color: AppleColors.systemBlue,
                        size: 18,
                      )
                    : null,
                onTap: () {
                  Navigator.of(ctx).pop();
                  setState(() => _skipOutroSec = sec);
                  widget.storage.setSkipOutroSec(sec);
                  _showHud(sec == 0 ? '已关闭跳过片尾' : '片尾跳过已设为 ${sec}s');
                },
              );
            }).toList(),
          ),
        ],
      ),
    );
  }

  void _showAudioTracksSheet() {
    final tracks = _player.state.tracks.audio;
    final active = _player.state.track.audio;
    showAppleModalSheet<void>(
      context: context,
      title: '切换音轨',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: tracks.isEmpty
                ? [
                    const AppleGroupedTile(
                      icon: CupertinoIcons.speaker_2_fill,
                      title: '默认音轨',
                      subtitle: '当前片源仅包含单一音轨',
                    ),
                  ]
                : tracks.map((t) {
                    final selected = t == active;
                    final label = (t.title ?? t.language ?? t.id).trim();
                    return AppleGroupedTile(
                      icon: CupertinoIcons.speaker_2_fill,
                      iconBgColor: selected
                          ? AppleColors.systemBlue
                          : AppleColors.groupedTertiary,
                      title: label.isEmpty ? '音轨 ${t.id}' : label,
                      trailing: selected
                          ? const Icon(
                              CupertinoIcons.checkmark_alt,
                              color: AppleColors.systemBlue,
                              size: 18,
                            )
                          : null,
                      onTap: () {
                        Navigator.of(ctx).pop();
                        _player.setAudioTrack(t);
                        _showHud('已切换音轨：${label.isEmpty ? t.id : label}');
                      },
                    );
                  }).toList(),
          ),
        ],
      ),
    );
  }

  void _showSubtitleTracksSheet() {
    final tracks = _player.state.tracks.subtitle;
    final active = _player.state.track.subtitle;
    showAppleModalSheet<void>(
      context: context,
      title: '切换字幕',
      builder: (ctx) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          AppleGroupedSection(
            children: [
              AppleGroupedTile(
                icon: CupertinoIcons.captions_bubble,
                iconBgColor: active == SubtitleTrack.no()
                    ? AppleColors.systemBlue
                    : AppleColors.groupedTertiary,
                title: '关闭内嵌字幕',
                onTap: () {
                  Navigator.of(ctx).pop();
                  _player.setSubtitleTrack(SubtitleTrack.no());
                  _showHud('已关闭内嵌字幕');
                },
              ),
              ...tracks.map((t) {
                final selected = t == active;
                final label = (t.title ?? t.language ?? t.id).trim();
                return AppleGroupedTile(
                  icon: CupertinoIcons.captions_bubble_fill,
                  iconBgColor: selected
                      ? AppleColors.systemBlue
                      : AppleColors.groupedTertiary,
                  title: label.isEmpty ? '字幕 ${t.id}' : label,
                  trailing: selected
                      ? const Icon(
                          CupertinoIcons.checkmark_alt,
                          color: AppleColors.systemBlue,
                          size: 18,
                        )
                      : null,
                  onTap: () {
                    Navigator.of(ctx).pop();
                    _player.setSubtitleTrack(t);
                    _showHud('已切换字幕：${label.isEmpty ? t.id : label}');
                  },
                );
              }),
            ],
          ),
        ],
      ),
    );
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
