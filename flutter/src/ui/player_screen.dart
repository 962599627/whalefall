import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import '../core/cms_client.dart';
import '../core/csp_bridge.dart';
import '../core/models.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';

/// Apple AVPlayerViewController / Apple TV+ inspired video player with:
/// - Large center transport controls (Rewind 15s, Play/Pause, Fast-Forward 15s)
/// - Dedicated floating Progress Bar capsule (automatically hidden during Live TV)
/// - Compact scrollable Tool Dock (Bottom horizontal in Portrait, Right edge in Landscape)
/// - Full-width iOS 27 Liquid Glass Episode & Line Picker sheet.
class PlayerScreen extends StatefulWidget {
  final CmsSite site;
  final CmsVideo video;
  final int initialLineIndex;
  final int initialEpisodeIndex;
  final int initialPositionMs;
  final StorageService storage;
  final bool isLive;

  const PlayerScreen({
    super.key,
    required this.site,
    required this.video,
    required this.initialLineIndex,
    required this.initialEpisodeIndex,
    required this.storage,
    this.initialPositionMs = 0,
    this.isLive = false,
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

  bool get _isLiveStream =>
      widget.isLive ||
      widget.site.key == 'live' ||
      (widget.site.api.isEmpty && widget.site.type == 1) ||
      CspBridge.isHuyaSite(widget.site) ||
      CspBridge.isDouyuSite(widget.site);

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
            if (_isLiveStream) return;
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
            if (_isLiveStream) return;
            _dragStartPosMs = _position.inMilliseconds.toDouble();
          },
          onHorizontalDragUpdate: (details) {
            if (_isLiveStream || _duration.inMilliseconds <= 0) return;
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
            if (_isLiveStream) return;
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
    final safePad = MediaQuery.paddingOf(context);
    final leftInset =
        isLandscape ? math.max(16.0, safePad.left * 0.45) : 14.0;
    final rightDockEdge =
        isLandscape ? math.max(8.0, safePad.right * 0.16) : 14.0;

    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xB8000000),
            Color(0x22000000),
            Color(0x22000000),
            Color(0xD8000000),
          ],
        ),
      ),
      child: SafeArea(
        left: !isLandscape,
        right: !isLandscape,
        child: Stack(
          fit: StackFit.expand,
          children: [
            // 1. Top Header Bar (Back + Title + Live Badge / Orientation Button)
            Positioned(
              left: leftInset,
              right: isLandscape ? rightDockEdge + 64 : 14,
              top: 10,
              child: _buildTopHeader(context, isLandscape),
            ),

            // 2. Large Center Transport Buttons (Rewind 15s / Play-Pause / Fast-Forward 15s)
            if (!_loading && !_buffering)
              Center(
                child: Padding(
                  padding: EdgeInsets.only(right: isLandscape ? 32 : 0),
                  child: _buildCenterTransportButtons(),
                ),
              ),

            // 3. Landscape Right-side Compact Scrollable Tool Dock (tucked near right edge)
            if (isLandscape)
              Positioned(
                right: rightDockEdge,
                top: 14,
                bottom: 14,
                child: Center(
                  child: _buildScrollableToolDock(isLandscape: true),
                ),
              ),

            // 4. Bottom Floating Progress Bar Capsule (hidden during Live TV)
            //    + Bottom Scrollable Tool Dock in Portrait
            Positioned(
              left: leftInset,
              right: isLandscape ? rightDockEdge + 64 : 14,
              bottom: 10,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (!_isLiveStream)
                    _buildProgressBarCapsule(context, isLandscape),
                  if (!isLandscape) ...[
                    if (!_isLiveStream) const SizedBox(height: 10),
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
          minSize: 36,
          onPressed: () => Navigator.of(context).maybePop(),
          child: const AppleGlassSurface(
            padding: EdgeInsets.symmetric(horizontal: 13, vertical: 7.5),
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
              Row(
                children: [
                  Flexible(
                    child: Text(
                      widget.video.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppleColors.labelPrimary,
                        fontSize: 16.5,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.3,
                      ),
                    ),
                  ),
                  if (_isLiveStream) ...[
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 7,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppleColors.systemRed.withValues(alpha: 0.85),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: const Text(
                        '直播中',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 10.5,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ],
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
        if (_isLiveStream) ...[
          const SizedBox(width: 10),
          CupertinoButton(
            key: const Key('player-fullscreen-toggle'),
            padding: EdgeInsets.zero,
            minSize: 34,
            onPressed: _toggleOrientation,
            child: AppleGlassSurface(
              padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
              borderRadius: BorderRadius.circular(999),
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
      ],
    );
  }

  /// Large center screen transport controls: Rewind 15s, Play/Pause, Fast-Forward 15s.
  /// For Live TV streams, only the central Play/Pause button is shown.
  Widget _buildCenterTransportButtons() {
    if (_isLiveStream) {
      return _buildCenterCircleButton(
        key: const Key('player-center-play-pause'),
        icon: _playing ? CupertinoIcons.pause_fill : CupertinoIcons.play_fill,
        size: 78,
        iconSize: 36,
        hero: true,
        onTap: () {
          _player.playOrPause();
          _scheduleHide();
        },
      );
    }
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildCenterCircleButton(
          key: const Key('player-center-rewind'),
          icon: CupertinoIcons.gobackward_15,
          size: 60,
          iconSize: 26,
          hero: false,
          onTap: () => _seekRelative(-15),
        ),
        const SizedBox(width: 30),
        _buildCenterCircleButton(
          key: const Key('player-center-play-pause'),
          icon: _playing ? CupertinoIcons.pause_fill : CupertinoIcons.play_fill,
          size: 78,
          iconSize: 36,
          hero: true,
          onTap: () {
            _player.playOrPause();
            _scheduleHide();
          },
        ),
        const SizedBox(width: 30),
        _buildCenterCircleButton(
          key: const Key('player-center-forward'),
          icon: CupertinoIcons.goforward_15,
          size: 60,
          iconSize: 26,
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
  /// - Landscape: compact vertical scrollable strip tucked near the right edge.
  /// Exposes all adjustable playback controls (omitting VOD-only items during Live TV).
  Widget _buildScrollableToolDock({required bool isLandscape}) {
    final live = _isLiveStream;
    final actions = <(IconData, String, VoidCallback)>[
      if (_episodeIndex > 0)
        (
          CupertinoIcons.backward_end_fill,
          live ? '上源' : '上集',
          () => _stepEpisode(-1),
        ),
      if (_episodeIndex < _currentLine.episodes.length - 1)
        (
          CupertinoIcons.forward_end_fill,
          live ? '下源' : '下集',
          () => _stepEpisode(1),
        ),
      if (_currentLine.episodes.length > 1 || !live)
        (
          CupertinoIcons.square_grid_2x2_fill,
          live ? '信号' : '选集',
          _showEpisodesSheet,
        ),
      if (widget.video.lines.length > 1 || !live)
        (
          CupertinoIcons.layers_alt_fill,
          '线路',
          _showLinesSheet,
        ),
      if (!live)
        (
          CupertinoIcons.speedometer,
          '${_normalSpeed}×',
          _showSpeedSheet,
        ),
      (
        CupertinoIcons.play_rectangle,
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
      if (!live) ...[
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
      ],
      (
        CupertinoIcons.speaker_2_fill,
        '音轨',
        _showAudioTracksSheet,
      ),
      if (!live)
        (
          CupertinoIcons.captions_bubble_fill,
          '字幕',
          _showSubtitleTracksSheet,
        ),
      (
        CupertinoIcons.refresh,
        live ? '刷新' : '重播',
        () {
          if (live) {
            _openCurrentEpisode();
            _showHud('正在刷新直播流…');
          } else {
            _player.seek(Duration.zero);
            _player.play();
            _showHud('已从头开始播放');
          }
        },
      ),
    ];

    if (isLandscape) {
      return AppleGlassSurface(
        key: const Key('player-tool-dock-landscape'),
        width: 54,
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
        borderRadius: BorderRadius.circular(18),
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          scrollDirection: Axis.vertical,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (int i = 0; i < actions.length; i++) ...[
                if (i > 0) const SizedBox(height: 6),
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
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
        borderRadius: BorderRadius.circular(20),
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          scrollDirection: Axis.horizontal,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (int i = 0; i < actions.length; i++) ...[
                if (i > 0) const SizedBox(width: 7),
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
      minSize: verticalLayout ? 30 : 34,
      onPressed: () {
        _scheduleHide();
        onTap();
      },
      child: Container(
        width: verticalLayout ? 46 : null,
        padding: EdgeInsets.symmetric(
          horizontal: verticalLayout ? 2 : 11,
          vertical: verticalLayout ? 5.5 : 6.5,
        ),
        decoration: BoxDecoration(
          color: const Color(0x26FFFFFF),
          borderRadius: BorderRadius.circular(verticalLayout ? 11 : 999),
          border: Border.all(color: AppleColors.glassBorder, width: 0.5),
        ),
        child: verticalLayout
            ? Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, color: AppleColors.labelPrimary, size: 15),
                  const SizedBox(height: 2.5),
                  Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppleColors.labelPrimary,
                      fontSize: 9.5,
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
    final screenSize = MediaQuery.sizeOf(context);
    final isLandscape = screenSize.width > screenSize.height;
    showAppleModalSheet<void>(
      context: context,
      title: '选集与线路 (${_currentLine.episodes.length})',
      maxWidth: isLandscape
          ? math.min(screenSize.width - 24, 920.0)
          : 560.0,
      builder: (ctx) => _PlayerEpisodePickerSheet(
        lines: widget.video.lines,
        initialLineIndex: _lineIndex,
        activeLineIndex: _lineIndex,
        activeEpisodeIndex: _episodeIndex,
        onSelect: (lineIdx, epIdx) {
          Navigator.of(ctx).pop();
          setState(() {
            _lineIndex = lineIdx;
            _episodeIndex = epIdx;
          });
          _openCurrentEpisode();
        },
      ),
    );
  }
}

class _ParsedEpisodeLabel {
  final String title;
  final String sizeBadge;

  const _ParsedEpisodeLabel({
    required this.title,
    required this.sizeBadge,
  });

  factory _ParsedEpisodeLabel.parse(String raw, int index) {
    var text = raw.trim();
    var badge = '';
    final bracketMatch = RegExp(r'^\[([^\]]+)\]\s*(.*)$').firstMatch(text);
    if (bracketMatch != null) {
      badge = (bracketMatch.group(1) ?? '').trim();
      text = (bracketMatch.group(2) ?? '').trim();
    }
    if (text.isEmpty) {
      text = raw.trim().isNotEmpty ? raw.trim() : '第${index + 1}集';
    }
    return _ParsedEpisodeLabel(title: text, sizeBadge: badge);
  }
}

class _PlayerEpisodePickerSheet extends StatefulWidget {
  final List<CmsLine> lines;
  final int initialLineIndex;
  final int activeLineIndex;
  final int activeEpisodeIndex;
  final void Function(int lineIndex, int episodeIndex) onSelect;

  const _PlayerEpisodePickerSheet({
    required this.lines,
    required this.initialLineIndex,
    required this.activeLineIndex,
    required this.activeEpisodeIndex,
    required this.onSelect,
  });

  @override
  State<_PlayerEpisodePickerSheet> createState() =>
      _PlayerEpisodePickerSheetState();
}

class _PlayerEpisodePickerSheetState extends State<_PlayerEpisodePickerSheet> {
  static const int _groupSize = 40;
  late int _previewLineIndex;
  late int _groupIndex;
  bool _descending = false;

  @override
  void initState() {
    super.initState();
    _previewLineIndex = widget.initialLineIndex
        .clamp(0, math.max(0, widget.lines.length - 1))
        .toInt();
    _groupIndex = (widget.activeEpisodeIndex ~/ _groupSize);
  }

  CmsLine get _line => widget.lines.isEmpty
      ? const CmsLine(name: '默认线路', episodes: [])
      : widget.lines[
          _previewLineIndex.clamp(0, widget.lines.length - 1).toInt()];

  @override
  Widget build(BuildContext context) {
    final episodes = _line.episodes;
    final total = episodes.length;
    final groupCount = total <= 0 ? 1 : ((total - 1) ~/ _groupSize) + 1;
    final safeGroup =
        _groupIndex.clamp(0, math.max(0, groupCount - 1)).toInt();
    final int startIdx = safeGroup * _groupSize;
    final int endIdx = math.min(startIdx + _groupSize, total);

    final indices = <int>[
      for (int i = startIdx; i < endIdx; i++) i,
    ];
    if (_descending) {
      indices.sort((a, b) => b.compareTo(a));
    }

    final screenSize = MediaQuery.sizeOf(context);
    final isLandscape = screenSize.width > screenSize.height;
    final crossAxisCount = isLandscape
        ? (screenSize.width >= 820 ? 5 : 4)
        : 3;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 18),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 顶部线路切换 + 当前播放状态 + 正倒序切换
          Row(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  physics: const BouncingScrollPhysics(),
                  child: Row(
                    children: List.generate(widget.lines.length, (i) {
                      final line = widget.lines[i];
                      final selected = i == _previewLineIndex;
                      final isPlayingLine = i == widget.activeLineIndex;
                      return Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: GestureDetector(
                          onTap: () {
                            setState(() {
                              _previewLineIndex = i;
                              final newGroupCount = line.episodes.isEmpty
                                  ? 1
                                  : ((line.episodes.length - 1) ~/ _groupSize) +
                                      1;
                              if (_groupIndex >= newGroupCount) {
                                _groupIndex = 0;
                              }
                            });
                          },
                          child: AnimatedContainer(
                            duration: const Duration(milliseconds: 200),
                            curve: Curves.easeOutCubic,
                            padding: const EdgeInsets.symmetric(
                              horizontal: 13,
                              vertical: 7,
                            ),
                            decoration: BoxDecoration(
                              gradient: selected
                                  ? const LinearGradient(
                                      begin: Alignment.topLeft,
                                      end: Alignment.bottomRight,
                                      colors: [
                                        Color(0xFF2897FF),
                                        Color(0xFF0A84FF),
                                      ],
                                    )
                                  : const LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [
                                        Color(0xD82C2C32),
                                        Color(0xCC1E1E24),
                                      ],
                                    ),
                              borderRadius: BorderRadius.circular(999),
                              border: Border.all(
                                color: selected
                                    ? const Color(0x90FFFFFF)
                                    : const Color(0x2EFFFFFF),
                                width: 0.7,
                              ),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                if (isPlayingLine) ...[
                                  Icon(
                                    CupertinoIcons.play_fill,
                                    size: 11.5,
                                    color: selected
                                        ? Colors.white
                                        : AppleColors.systemBlue,
                                  ),
                                  const SizedBox(width: 5),
                                ],
                                Text(
                                  '${line.name} (${line.episodes.length})',
                                  style: TextStyle(
                                    color: selected
                                        ? Colors.white
                                        : AppleColors.labelPrimary,
                                    fontSize: 12.5,
                                    fontWeight: selected
                                        ? FontWeight.w700
                                        : FontWeight.w500,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      );
                    }),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: () => setState(() => _descending = !_descending),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 11,
                    vertical: 7,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xCC26262C),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: const Color(0x30FFFFFF),
                      width: 0.65,
                    ),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        _descending
                            ? CupertinoIcons.sort_down
                            : CupertinoIcons.sort_up,
                        size: 13.5,
                        color: AppleColors.labelSecondary,
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
          // 分段区间快速切换（超过 40 集自动显示分段胶囊）
          if (groupCount > 1) ...[
            const SizedBox(height: 10),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              physics: const BouncingScrollPhysics(),
              child: Row(
                children: List.generate(groupCount, (g) {
                  final gStart = g * _groupSize + 1;
                  final gEnd = math.min((g + 1) * _groupSize, total);
                  final active = g == safeGroup;
                  return Padding(
                    padding: const EdgeInsets.only(right: 7),
                    child: GestureDetector(
                      onTap: () => setState(() => _groupIndex = g),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 180),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 5.5,
                        ),
                        decoration: BoxDecoration(
                          color: active
                              ? const Color(0x330A84FF)
                              : const Color(0x99202026),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: active
                                ? AppleColors.systemBlue
                                : const Color(0x24FFFFFF),
                            width: 0.75,
                          ),
                        ),
                        child: Text(
                          '$gStart-$gEnd',
                          style: TextStyle(
                            color: active
                                ? const Color(0xFF64B5FF)
                                : AppleColors.labelSecondary,
                            fontSize: 12,
                            fontWeight:
                                active ? FontWeight.w700 : FontWeight.w500,
                          ),
                        ),
                      ),
                    ),
                  );
                }),
              ),
            ),
          ],
          const SizedBox(height: 12),
          // 剧集网格卡片
          ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: isLandscape
                  ? math.min(screenSize.height * 0.56, 320.0)
                  : math.min(screenSize.height * 0.52, 420.0),
            ),
            child: GridView.builder(
              shrinkWrap: true,
              physics: const BouncingScrollPhysics(),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: crossAxisCount,
                mainAxisSpacing: 9,
                crossAxisSpacing: 9,
                mainAxisExtent: 56,
              ),
              itemCount: indices.length,
              itemBuilder: (ctx, idx) {
                final epIndex = indices[idx];
                final ep = episodes[epIndex];
                final selected = _previewLineIndex == widget.activeLineIndex &&
                    epIndex == widget.activeEpisodeIndex;
                final parsed = _ParsedEpisodeLabel.parse(ep.name, epIndex);
                final numBadge =
                    '#${(epIndex + 1).toString().padLeft(2, '0')}';

                return GestureDetector(
                  onTap: () => widget.onSelect(_previewLineIndex, epIndex),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 11,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      gradient: selected
                          ? const LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [
                                Color(0xFF1D8BFF),
                                Color(0xFF0A6CE8),
                              ],
                            )
                          : const LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [
                                Color(0xCC28282F),
                                Color(0xB81D1D23),
                              ],
                            ),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: selected
                            ? const Color(0xAAFFFFFF)
                            : const Color(0x28FFFFFF),
                        width: selected ? 0.9 : 0.65,
                      ),
                      boxShadow: selected
                          ? const [
                              BoxShadow(
                                color: Color(0x450A84FF),
                                blurRadius: 12,
                                offset: Offset(0, 4),
                              ),
                            ]
                          : null,
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            if (selected) ...[
                              const Icon(
                                CupertinoIcons.play_fill,
                                size: 11,
                                color: Colors.white,
                              ),
                              const SizedBox(width: 4),
                            ],
                            Text(
                              numBadge,
                              style: TextStyle(
                                color: selected
                                    ? const Color(0xE6FFFFFF)
                                    : AppleColors.labelTertiary,
                                fontSize: 10.5,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const Spacer(),
                            if (parsed.sizeBadge.isNotEmpty)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 5.5,
                                  vertical: 1.5,
                                ),
                                decoration: BoxDecoration(
                                  color: selected
                                      ? const Color(0x33FFFFFF)
                                      : const Color(0x22FFFFFF),
                                  borderRadius: BorderRadius.circular(5),
                                ),
                                child: Text(
                                  parsed.sizeBadge,
                                  style: TextStyle(
                                    color: selected
                                        ? Colors.white
                                        : AppleColors.labelSecondary,
                                    fontSize: 9.5,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 3),
                        Text(
                          parsed.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 12.5,
                            fontWeight:
                                selected ? FontWeight.w700 : FontWeight.w600,
                            letterSpacing: -0.2,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
