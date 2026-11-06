package com.pilipala.marcomovie;

import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playback resolution (direct, plugin/JS player, web resolver, magnet stream), player page
 * lifecycle, mini-player transitions, remote seek gesture, control overlay auto-hide, and watch
 * history bookmark persistence extracted from {@link MainActivity}.
 */
final class PlaybackController {
    private PlaybackController() {}

    static String formatTime(int ms) {
        int sec = Math.max(0, ms / 1000);
        return sec >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                : String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    static void stopMagnet(MainActivity a) {
        if (a.magnetStream != null) {
            a.magnetStream.close();
            a.magnetStream = null;
        }
    }

    static void stopWeb(MainActivity a) {
        if (a.webResolver != null) {
            a.webResolver.stop();
            a.webResolver = null;
        }
    }

    static void resolveWebPage(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String prefix) {
        String address = (prefix == null ? "" : prefix) + ep.url;
        if (!Cms.isHttp(address)) {
            a.changingMiniEpisode = false;
            clearPlaybackLoading(a);
            failInPlaceSwitch(a, "解析页面地址不受支持。");
            return;
        }
        final int ticket = a.generation;
        final int serial = a.playbackResolveSerial;
        try {
            a.webResolver =
                    new WebResolver(
                            a,
                            address,
                            ep.headers,
                            new WebResolver.Listener() {
                                public void media(String url, Map<String, String> headers) {
                                    if (ticket != a.generation || serial != a.playbackResolveSerial)
                                        return;
                                    a.webResolver = null;
                                    clearPlaybackLoading(a);
                                    Cms.Episode resolved = new Cms.Episode();
                                    resolved.name = ep.name;
                                    resolved.url = url;
                                    resolved.headers.putAll(headers);
                                    resolved.media = ep.media;
                                    openPlayer(a, s, v, resolved);
                                }

                                public void failed(String reason) {
                                    if (ticket == a.generation
                                            && serial == a.playbackResolveSerial) {
                                        a.changingMiniEpisode = false;
                                        clearPlaybackLoading(a);
                                        stopWeb(a);
                                        failInPlaceSwitch(a, reason);
                                    }
                                }
                            });
            a.playbackLoading.addView(a.webResolver.view(), new FrameLayout.LayoutParams(1, 1));
        } catch (Exception error) {
            AppLog.w("PlaybackController", "WebResolver init failed", error);
            a.changingMiniEpisode = false;
            clearPlaybackLoading(a);
            failInPlaceSwitch(a, "这台设备无法打开网页播放组件。");
        }
    }

    static void failInPlaceSwitch(MainActivity a, String message) {
        if (a.playerChrome != null) a.playerChrome.setLoading(false);
        if (a.current != null && a.current.player) {
            if (a.playStatus != null) a.playStatus.setText(message);
            showControls(a, true);
        }
        a.toast(message);
    }

    static WatchHistory.Entry lookupSavedBookmark(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        if (a == null
                || s == null
                || s.key == null
                || "live".equals(s.key)
                || v == null
                || v.id == null
                || ep == null) {
            return null;
        }
        int epIdx = line != null ? line.episodes.indexOf(ep) : -1;
        for (WatchHistory.Entry entry : a.watchHistory()) {
            if (s.key.equals(entry.site) && v.id.equals(entry.id)) {
                boolean sameName = ep.name != null && ep.name.equals(entry.episode);
                boolean sameIndex =
                        line != null
                                && line.name != null
                                && line.name.equals(entry.line)
                                && epIdx >= 0
                                && epIdx == entry.index;
                if (sameName || sameIndex) {
                    return entry;
                }
                break;
            }
        }
        return null;
    }

    static void resolvePlayer(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        if (a.miniMode) {
            a.changingMiniEpisode = true;
            showEpisodeDetails(a, s, v);
        }
        a.activeLine = line;
        a.activeEpisode = ep;
        if (a.resumeAt == 0) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, line, ep);
            if (saved != null) {
                a.resumeAt = saved.resume();
            }
        }
        if (s.supported()) {
            openPlayer(a, s, v, ep);
            return;
        }
        if (a.playbackLoading != null) return;
        showPlaybackLoading(a);
        final int serial = a.playbackResolveSerial, ticket = a.generation;
        Future<?> request =
                RequestScope.submit(
                        a.details,
                        () -> {
                            try {
                                JSONObject result =
                                        new JSONObject(a.engine(s).player(line.name, ep.url));
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation
                                                    || a.isFinishing()) return;
                                            try {
                                                String url = result.optString("url");
                                                Cms.Episode resolved =
                                                        MediaOptions.episode(s, ep, result);
                                                if (result.optInt("parse", 0) != 0
                                                        || result.optInt("jx", 0) != 0) {
                                                    resolveWebPage(
                                                            a,
                                                            s,
                                                            v,
                                                            resolved,
                                                            result.optString("playUrl", s.playUrl));
                                                    return;
                                                }
                                                clearPlaybackLoading(a);
                                                if (!Cms.isMedia(url)
                                                        && !url.startsWith("magnet:?")) {
                                                    a.changingMiniEpisode = false;
                                                    failInPlaceSwitch(a, "来源返回的播放协议暂不支持");
                                                    return;
                                                }
                                                openPlayer(a, s, v, resolved);
                                            } catch (Exception error) {
                                                AppLog.w(
                                                        "PlaybackController",
                                                        "parse player response failed",
                                                        error);
                                                a.changingMiniEpisode = false;
                                                clearPlaybackLoading(a);
                                                failInPlaceSwitch(a, "播放信息无法读取，请重试");
                                            }
                                        });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "engine.player failed", error);
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation) return;
                                            a.changingMiniEpisode = false;
                                            clearPlaybackLoading(a);
                                            failInPlaceSwitch(a, a.message(error));
                                        });
                            }
                        });
        a.pageRequests.add(request);
    }

    static void showPlaybackLoading(MainActivity a) {
        a.playbackLoadingFocus = a.getCurrentFocus();
        a.playbackLoading = new PlaybackLoading(a);
        a.root.addView(a.playbackLoading, new FrameLayout.LayoutParams(-1, -1));
        if (a.playerChrome != null) a.playerChrome.update();
        a.playbackLoading.requestFocus();
    }

    static void clearPlaybackLoading(MainActivity a) {
        a.playbackResolveSerial++;
        if (a.playbackLoading != null) {
            if (a.playbackLoading.getParent() instanceof ViewGroup)
                ((ViewGroup) a.playbackLoading.getParent()).removeView(a.playbackLoading);
            a.playbackLoading = null;
            if (a.playerChrome != null) a.playerChrome.update();
            if (a.playbackLoadingFocus != null && UiCompat.isAttached(a.playbackLoadingFocus))
                a.playbackLoadingFocus.requestFocus();
            a.playbackLoadingFocus = null;
        }
    }

    static void openPlayer(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode e) {
        if (e.url.startsWith("magnet:?")) {
            magnetPlayer(a, s, v, e);
            return;
        }
        if (!Cms.isMedia(e.url)) {
            if (a.playerChrome != null) a.playerChrome.setLoading(false);
            a.notice("这条剧集需要解析或使用了特殊协议，基础版尚未支持。");
            return;
        }
        MainActivity.Screen screen = new MainActivity.Screen(() -> playerPage(a, s, v, e));
        screen.player = true;
        a.open(screen, a.current == null || !a.current.player);
    }

    static void magnetPlayer(MainActivity a, Cms.Site site, Cms.Video video, Cms.Episode episode) {
        a.open(
                new MainActivity.Screen(
                        () -> {
                            a.renderPage(video.title, "MAGNET PLAYBACK");
                            TextView state = a.text("正在获取磁力文件列表…", 17, MainActivity.MUTED);
                            a.body.addView(state);
                            final int ticket = a.generation;
                            try {
                                a.magnetStream =
                                        MagnetStream.start(
                                                a,
                                                episode.url,
                                                new MagnetStream.Listener() {
                                                    public void progress(String message) {
                                                        if (ticket == a.generation)
                                                            state.setText(message);
                                                    }

                                                    public void files(
                                                            List<MagnetStream.MediaFile> files,
                                                            int suggested,
                                                            MagnetStream.Selection selection) {
                                                        if (ticket != a.generation) return;
                                                        String[] names = new String[files.size()];
                                                        for (int i = 0; i < names.length; i++)
                                                            names[i] = files.get(i).name;
                                                        new AppDialog.Builder(a)
                                                                .setTitle("选择要播放的视频")
                                                                .setSingleChoiceItems(
                                                                        names,
                                                                        suggested,
                                                                        (dialog, index) -> {
                                                                            dialog.dismiss();
                                                                            selection.select(index);
                                                                        })
                                                                .setNegativeButton(
                                                                        "取消",
                                                                        (dialog, index) ->
                                                                                a.onBackPressed())
                                                                .setOnCancelListener(
                                                                        dialog -> a.onBackPressed())
                                                                .show();
                                                    }

                                                    public void ready(
                                                            String url, String mime, String name) {
                                                        if (ticket != a.generation) return;
                                                        Cms.Episode resolved = new Cms.Episode();
                                                        resolved.name = name;
                                                        resolved.url = url;
                                                        resolved.headers.putAll(episode.headers);
                                                        try {
                                                            resolved.media =
                                                                    new JSONObject(
                                                                                    episode.media
                                                                                            .toString())
                                                                            .put("mimeType", mime);
                                                        } catch (Exception ignored) {
                                                        }
                                                        if (!a.back.isEmpty())
                                                            a.current = a.back.pop();
                                                        openPlayer(a, site, video, resolved);
                                                    }

                                                    public void error(String message) {
                                                        if (ticket == a.generation) {
                                                            state.setText(message);
                                                            a.addButton(
                                                                    a.body,
                                                                    a.button(
                                                                            "重试",
                                                                            "primary",
                                                                            a::refresh));
                                                        }
                                                    }
                                                });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "MagnetStream start failed", error);
                                state.setText("磁力播放初始化失败：" + error.getClass().getSimpleName());
                            }
                        }),
                true);
    }

    static void changeEpisode(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            Cms.Line line,
            Cms.Episode episode,
            int position) {
        a.changingMiniEpisode = a.miniMode;
        a.resumeAt = position;
        if (a.miniMode) {
            showEpisodeDetails(a, site, video);
        } else {
            rememberEpisodeOnBackStack(a, line, episode);
            if (a.current != null && a.current.player) {
                prepareInPlaceSwitch(a, line, episode);
            }
        }
        resolvePlayer(a, site, video, line, episode);
    }

    static void rememberEpisodeOnBackStack(MainActivity a, Cms.Line line, Cms.Episode episode) {
        if (a.back.isEmpty() || line == null || episode == null) return;
        MainActivity.Screen prev = a.back.peek();
        if (prev == null) return;
        int epIdx = line.episodes.indexOf(episode);
        if (epIdx >= 0) {
            boolean desc = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
            int page = EpisodeSelection.pageFor(epIdx, line.episodes.size(), desc);
            prev.selection.select(line.name, page, line.episodes.size());
            prev.focus = "episode:" + line.name + ":" + epIdx;
            prev.episodeFocus = epIdx;
            prev.episodeFocusLine = line.name;
        }
    }

    static void prepareInPlaceSwitch(MainActivity a, Cms.Line line, Cms.Episode episode) {
        finishSeek(a, false);
        savePlaybackHistory(a, true);
        stopWeb(a);
        stopMagnet(a);
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        a.prepared = false;
        a.playing = false;
        if (a.playerChrome != null) {
            a.playerChrome.switchEpisode(episode, line);
        }
    }

    static void showEpisodeDetails(MainActivity a, Cms.Site site, Cms.Video video) {
        releasePlayer(a);
        if (!a.isTvDevice()) {
            a.getWindow().getDecorView().setSystemUiVisibility(0);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
        }
        if (a.current != null && a.current.player)
            a.current =
                    a.back.isEmpty()
                            ? new MainActivity.Screen(() -> a.detail(site, video))
                            : a.back.pop();
        if (a.current != null) {
            a.detail(site, video, video);
            a.restore(a.current);
        }
    }

    static void leavePlayback(MainActivity a) {
        if (!a.isTvDevice()) {
            a.getWindow().getDecorView().setSystemUiVisibility(0);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
        }
        if (!a.back.isEmpty()) {
            releasePlayer(a);
            a.current = a.back.pop();
            a.current.render.run();
            a.restore(a.current);
        } else a.onBackPressed();
    }

    static void minimizePlayback(MainActivity a) {
        if (a.playbackControls == null || a.miniMode) return;
        a.miniMode = true;
        showControls(a, false);
        a.playbackControls.subtitle.setVisibility(View.GONE);
        a.playbackControls.danmaku.setVisibility(View.GONE);
        a.miniDetails = new MiniDetails(a);
    }

    static void expandPlayback(MainActivity a) {
        if (!a.miniMode) return;
        a.miniMode = false;
        if (a.miniDetails != null) {
            a.miniDetails.remove();
            a.miniDetails = null;
        }
        UiCompat.setClipToOutline(a.playbackLayer, false);
        a.playbackLayer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        a.playbackControls.subtitle.setVisibility(View.VISIBLE);
        a.playbackControls.danmaku.setVisibility(View.VISIBLE);
        a.playbackLayer.post(
                () -> {
                    if (a.playbackControls != null) {
                        a.playbackControls.resize(
                                a.playbackControls.videoWidth, a.playbackControls.videoHeight);
                        showControls(a, true);
                        a.playbackControls.timeline.requestFocus();
                    }
                });
    }

    static void playerPage(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep) {
        ep.url = LocalProxy.rewrite(ep.url);
        try {
            JSONArray subtitles = ep.media.optJSONArray("subtitles");
            if (subtitles != null)
                for (int i = 0; i < subtitles.length(); i++) {
                    JSONObject sub = subtitles.optJSONObject(i);
                    if (sub != null) sub.put("url", LocalProxy.rewrite(sub.optString("url")));
                }
        } catch (Exception ignored) {
        }
        releasePlayer(a);
        a.playingBookmark = null;
        if (s.key != null
                && !s.key.equals("live")
                && v.id != null
                && a.activeLine != null
                && a.activeEpisode != null) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, a.activeLine, a.activeEpisode);
            int initialResume =
                    a.resumeAt > 0
                            ? a.resumeAt
                            : (a.resumeAt < 0 ? 0 : (saved != null ? saved.resume() : 0));
            a.playingBookmark = new WatchHistory.Entry();
            a.playingBookmark.site = s.key;
            a.playingBookmark.id = v.id;
            a.playingBookmark.title = v.title;
            a.playingBookmark.poster = v.poster;
            a.playingBookmark.line = a.activeLine.name;
            a.playingBookmark.episode = a.activeEpisode.name;
            a.playingBookmark.index = a.activeLine.episodes.indexOf(a.activeEpisode);
            a.playingBookmark.position = Math.max(0, initialResume);
            a.playingBookmark.duration = saved != null ? Math.max(0, saved.duration) : 0;
        }
        a.generation++;
        final int ticket = a.generation;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!a.isTvDevice()) {
            a.getWindow().getDecorView().setSystemUiVisibility(5894);
            a.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(Color.BLACK);
        a.root.setFocusable(true);
        a.root.setFocusableInTouchMode(true);
        a.root.setTag("player-surface");
        a.scroll = null;
        VideoOutput surface = new VideoOutput(a, a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0) == 1);
        a.playbackLayer = new FrameLayout(a);
        a.playbackLayer.setTag("playback-layer");
        a.root.addView(a.playbackLayer, new FrameLayout.LayoutParams(-1, -1));
        a.playbackLayer.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        a.playerChrome = new PlayerChrome(a, v, ep);
        a.playbackControls =
                new PlaybackControls(
                        a, a.playerChrome.dock, a.playerChrome.progress, surface, s, v, ep);
        TouchPlayerGesture.attach(a, surface);
        final PlaybackControls controls = a.playbackControls;
        a.setContentView(a.root);
        surface.listen(
                new VideoOutput.Listener() {
                    Surface renderSurface;
                    int surfaceRevision;

                    public void available(Surface value) {
                        renderSurface = value;
                        final int revision = ++surfaceRevision;
                        if (ticket != a.generation) return;
                        if (a.player != null) {
                            a.player.setSurface(value);
                            return;
                        }
                        a.worker.execute(
                                () -> {
                                    MoviePlayer candidate = null;
                                    try {
                                        Map<String, String> mediaHeaders =
                                                new LinkedHashMap<>(s.headers);
                                        mediaHeaders.putAll(ep.headers);
                                        candidate =
                                                a.prefs.getInt(Prefs.PLAYER_ENGINE, 0) == 1
                                                        ? new SystemPlayer(a)
                                                        : ep.media.optBoolean("_softwareFallback")
                                                                ? new MoviePlayer(
                                                                        a,
                                                                        ep.media.optInt(
                                                                                "_fallbackMode", 2))
                                                                : new MoviePlayer(a);
                                        candidate.configureDefaultAudio();
                                        candidate.setDataSource(
                                                a,
                                                Uri.parse(
                                                        Cms.isHttp(ep.url)
                                                                ? Cms.normalizeHttp(ep.url)
                                                                : ep.url),
                                                mediaHeaders);
                                        candidate.setOptions(ep.media);
                                        final MoviePlayer ready = candidate;
                                        a.ui.post(
                                                () -> {
                                                    if (ticket != a.generation
                                                            || revision != surfaceRevision
                                                            || a.isFinishing()
                                                            || renderSurface == null
                                                            || !renderSurface.isValid()) {
                                                        ready.release();
                                                        return;
                                                    }
                                                    a.player = ready;
                                                    ready.setSurface(renderSurface);
                                                    ready.bindSubtitles(
                                                            controls.subtitle,
                                                            text -> {
                                                                if (ticket == a.generation) {
                                                                    controls.lastSubtitle =
                                                                            String.valueOf(text);
                                                                }
                                                            });
                                                    ready.setOnPreparedListener(
                                                            mp -> {
                                                                if (ticket != a.generation) return;
                                                                a.prepared = true;
                                                                if (a.playerChrome != null)
                                                                    a.playerChrome.setLoading(
                                                                            false);
                                                                a.playing = a.foreground;
                                                                if (a.foreground) mp.start();
                                                                else mp.pause();
                                                                controls.prepared();
                                                                savePlaybackHistoryOnPrepared(a);
                                                                a.playStatus.setText(
                                                                        a.foreground
                                                                                ? ("live"
                                                                                                .equals(
                                                                                                        s.key)
                                                                                        ? (a
                                                                                                        .isTvDevice()
                                                                                                ? "正在直播 · 上下换台 · 菜单键工具条"
                                                                                                : "正在直播 · 单击显示选台工具栏")
                                                                                        : (a
                                                                                                        .isTvDevice()
                                                                                                ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                                                                                : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量"))
                                                                                : (a.isTvDevice()
                                                                                        ? "已暂停 · 按确认继续"
                                                                                        : "已暂停 · 双击继续"));
                                                                clock(a, ticket);
                                                                if (a.changingMiniEpisode) {
                                                                    a.changingMiniEpisode = false;
                                                                    minimizePlayback(a);
                                                                } else if (a.foreground) {
                                                                    if ("live".equals(s.key)) {
                                                                        showControls(a, false);
                                                                        a.toast(
                                                                                "频道 "
                                                                                        + (a.liveSession
                                                                                                        == null
                                                                                                ? ""
                                                                                                : (a.liveSession
                                                                                                                        .index
                                                                                                                + 1)
                                                                                                        + " · ")
                                                                                        + v.title);
                                                                    } else scheduleHide(a);
                                                                }
                                                            });
                                                    ready.setOnVideoSizeChangedListener(
                                                            (mp, w, h) -> {
                                                                if (ticket == a.generation)
                                                                    controls.resize(w, h);
                                                            });
                                                    ready.setOnCompletionListener(
                                                            mp -> {
                                                                if (ticket != a.generation) return;
                                                                a.playing = false;
                                                                a.playStatus.setText("播放结束");
                                                                controls.completed();
                                                            });
                                                    ready.setOnErrorListener(
                                                            (mp, what, extra) -> {
                                                                if (ticket != a.generation)
                                                                    return true;
                                                                if (PlaybackFailure.decoderFallback(
                                                                                what)
                                                                        && a.prefs.getInt(
                                                                                        Prefs
                                                                                                .DECODER_MODE,
                                                                                        0)
                                                                                == 0
                                                                        && a.prefs.getInt(
                                                                                        Prefs
                                                                                                .PLAYER_ENGINE,
                                                                                        0)
                                                                                == 0
                                                                        && !ep.media.optBoolean(
                                                                                "_softwareFallback")) {
                                                                    try {
                                                                        ep.media.put(
                                                                                "_softwareFallback",
                                                                                true);
                                                                        ep.media.put(
                                                                                "_fallbackMode",
                                                                                mp
                                                                                        .softFallbackMode());
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                    a.resumeAt =
                                                                            mp.getCurrentPosition();
                                                                    a.playStatus.setText(
                                                                            "正在尝试内置软解码…");
                                                                    a.ui.post(
                                                                            () -> {
                                                                                if (ticket
                                                                                        == a.generation)
                                                                                    playerPage(
                                                                                            a, s, v,
                                                                                            ep);
                                                                            });
                                                                    return true;
                                                                }
                                                                a.prepared = false;
                                                                a.playing = false;
                                                                if (a.playerChrome != null)
                                                                    a.playerChrome.setLoading(
                                                                            false);
                                                                a.playStatus.setText(
                                                                        mp.getFailureMessage(what)
                                                                                + "（"
                                                                                + what
                                                                                + "）");
                                                                showControls(a, true);
                                                                return true;
                                                            });
                                                    a.playStatus.setText("正在加载播放…");
                                                    ready.prepareAsync();
                                                });
                                    } catch (Exception ex) {
                                        AppLog.w("PlaybackController", "player init failed", ex);
                                        if (candidate != null) candidate.release();
                                        a.ui.post(
                                                () -> {
                                                    if (ticket == a.generation) {
                                                        if (a.playerChrome != null)
                                                            a.playerChrome.setLoading(false);
                                                        a.playStatus.setText(a.message(ex));
                                                        showControls(a, true);
                                                    }
                                                });
                                    }
                                });
                    }

                    public void destroyed() {
                        surfaceRevision++;
                        if (ticket == a.generation && a.player != null) a.player.setSurface(null);
                        renderSurface = null;
                    }
                });
        a.playbackControls.timeline.requestFocus();
        if ("live".equals(s.key)) showControls(a, false);
    }

    static void showControls(MainActivity a, boolean visible) {
        if (!visible) finishSeek(a, false);
        if (a.miniMode && visible) return;
        boolean changed = a.controlsVisible != visible;
        a.controlsVisible = visible;
        if (a.playbackControls != null) a.playbackControls.visible(visible);
        if (a.playerChrome != null) a.playerChrome.update();
        if (a.playerOverlay != null) {
            a.playerOverlay.animate().cancel();
            if (visible) {
                a.playerOverlay.setVisibility(View.VISIBLE);
                if (changed && !a.reduced) {
                    a.playerOverlay.setAlpha(0);
                    a.playerOverlay.setTranslationY(0);
                }
                a.playerOverlay
                        .animate()
                        .alpha(1)
                        .translationY(0)
                        .setDuration(a.reduced ? 0 : 180)
                        .start();
            } else {
                long hideDur = a.reduced ? 0 : 150;
                UiCompat.startWithEndAction(
                        a.playerOverlay.animate().alpha(0).translationY(0).setDuration(hideDur),
                        a.playerOverlay,
                        hideDur,
                        () -> {
                            if (!a.controlsVisible && a.playerOverlay != null)
                                a.playerOverlay.setVisibility(View.GONE);
                        });
                a.root.requestFocus();
            }
        }
        if (visible) {
            if (changed && a.playbackControls != null) a.playbackControls.timeline.requestFocus();
            scheduleHide(a);
        } else if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
    }

    static void scheduleHide(MainActivity a) {
        if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
        a.hideControls =
                () -> {
                    if (a.prepared
                            && a.playing
                            && (a.playbackControls == null || !a.playbackControls.dialogOpen))
                        showControls(a, false);
                };
        a.ui.postDelayed(a.hideControls, 5000);
    }

    static void clock(MainActivity a, int ticket) {
        if (ticket != a.generation || a.player == null || !a.prepared) return;
        savePlaybackHistory(a, false);
        try {
            if (a.seekGesture.active) previewSeek(a);
            else if (a.playbackControls == null || !a.playbackControls.isUserSeeking()) {
                int rawPos = a.player.getCurrentPosition();
                int displayPos =
                        a.playbackControls != null
                                ? a.playbackControls.displayPosition(rawPos)
                                : rawPos;
                a.playClock.setText(
                        formatTime(displayPos)
                                + " / "
                                + (a.player.getDuration() > 0
                                        ? formatTime(a.player.getDuration())
                                        : "直播"));
                if (a.playbackControls != null) a.playbackControls.tick();
            }
            if (a.playerChrome != null) a.playerChrome.update();
        } catch (Exception ignored) {
        }
        a.ui.postDelayed(() -> clock(a, ticket), 1000);
    }

    static void togglePlayback(MainActivity a) {
        if (a.player == null || !a.prepared) return;
        try {
            if (a.player.isPlaying()) {
                a.player.pause();
                a.playing = false;
                a.playStatus.setText(a.isTvDevice() ? "已暂停 · 按确认继续" : "已暂停 · 双击继续");
            } else {
                if (a.player.getDuration() > 0
                        && a.player.getCurrentPosition() >= a.player.getDuration() - 300)
                    a.player.seekTo(0);
                a.player.start();
                a.playing = true;
                a.playStatus.setText(
                        a.isTvDevice()
                                ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量");
            }
            if (a.playerChrome != null) a.playerChrome.update();
        } catch (Exception error) {
            AppLog.w("PlaybackController", "togglePlayback failed", error);
        }
    }

    static void beginSeek(MainActivity a, int direction) {
        if (a.player == null || !a.prepared || a.player.getDuration() <= 0) return;
        int position = a.seekGesture.active ? a.seekGesture.target : a.player.getCurrentPosition();
        if (!a.seekGesture.active) a.seekStatus = a.playStatus.getText().toString();
        a.seekGesture.begin(
                direction, position, a.player.getDuration(), SystemClock.uptimeMillis());
        showControls(a, true);
        if (a.playbackControls != null) a.playbackControls.timeline.requestFocus();
        previewSeek(a);
        a.ui.removeCallbacks(a.seekPulse);
        a.ui.postDelayed(a.seekPulse, 100);
    }

    static void previewSeek(MainActivity a) {
        if (!a.seekGesture.active || a.playbackControls == null) return;
        a.playbackControls.timeline.setProgress(a.seekGesture.target);
        a.playClock.setText(
                formatTime(a.seekGesture.target) + " / " + formatTime(a.seekGesture.duration));
        a.playStatus.setText(
                (a.seekGesture.accelerated(SystemClock.uptimeMillis()) ? "加速" : "")
                        + (a.seekGesture.direction > 0 ? "快进" : "快退")
                        + " · 松开跳转");
    }

    static void finishSeek(MainActivity a, boolean commit) {
        if (!a.seekGesture.active) return;
        a.seekGesture.advance(SystemClock.uptimeMillis());
        int target = a.seekGesture.target;
        a.seekGesture.cancel();
        a.ui.removeCallbacks(a.seekPulse);
        if (commit && a.player != null && a.prepared) a.player.seekTo(target);
        if (a.playStatus != null) a.playStatus.setText(a.seekStatus);
        if (a.player != null && a.prepared && a.playbackControls != null) {
            int position = commit ? target : a.player.getCurrentPosition();
            a.playbackControls.timeline.setProgress(position);
            a.playClock.setText(formatTime(position) + " / " + formatTime(a.player.getDuration()));
        }
        if (commit) scheduleHide(a);
    }

    static void savePlaybackHistoryOnPrepared(MainActivity a) {
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        try {
            int rawPos = a.player.getCurrentPosition();
            int displayPos =
                    a.playbackControls != null
                            ? a.playbackControls.displayPosition(rawPos)
                            : rawPos;
            if (a.playbackControls != null
                    && a.playbackControls.pendingResumeMs > 0
                    && displayPos < 2000) {
                displayPos = a.playbackControls.pendingResumeMs;
            }
            if (displayPos >= 1000 || a.playingBookmark.position <= 0) {
                a.playingBookmark.position = Math.max(0, displayPos);
            }
            int dur = a.player.getDuration();
            if (dur > 0) {
                a.playingBookmark.duration = dur;
            }
            a.playingBookmark.updated = System.currentTimeMillis();
            a.prefs
                    .edit()
                    .putString(
                            Prefs.WATCH_HISTORY,
                            WatchHistory.upsert(
                                    a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                    a.playingBookmark))
                    .apply();
            a.lastBookmarkSave = a.playingBookmark.updated;
        } catch (Exception error) {
            AppLog.w("PlaybackController", "savePlaybackHistoryOnPrepared failed", error);
        }
    }

    static void savePlaybackHistory(MainActivity a, boolean force) {
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - a.lastBookmarkSave < 5000) return;
        try {
            int rawPos = a.player.getCurrentPosition();
            int position =
                    a.playbackControls != null
                            ? a.playbackControls.displayPosition(rawPos)
                            : rawPos;
            if (a.playbackControls != null
                    && a.playbackControls.pendingResumeMs > 0
                    && position < 2000) {
                position = a.playbackControls.pendingResumeMs;
            }
            if (position < 1000 && a.playingBookmark.position == 0) return;
            if (position < 1000 && a.playingBookmark.position >= 3000) {
                position = a.playingBookmark.position;
            }
            a.playingBookmark.position = Math.max(0, position);
            int dur = a.player.getDuration();
            if (dur > 0) {
                a.playingBookmark.duration = dur;
            }
            a.playingBookmark.updated = now;
            a.prefs
                    .edit()
                    .putString(
                            Prefs.WATCH_HISTORY,
                            WatchHistory.upsert(
                                    a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                    a.playingBookmark))
                    .apply();
            a.lastBookmarkSave = now;
        } catch (Exception error) {
            AppLog.w("PlaybackController", "savePlaybackHistory failed", error);
        }
    }

    static void releasePlayer(MainActivity a) {
        finishSeek(a, false);
        a.clearLiveNumber();
        savePlaybackHistory(a, true);
        a.miniMode = false;
        a.miniDetails = null;
        if (a.playbackControls != null) a.playbackControls.close();
        a.playbackControls = null;
        a.prepared = false;
        a.playing = false;
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static boolean dispatchPlaybackKey(MainActivity a, KeyEvent event) {
        if (a.current == null
                || !a.current.player
                || a.miniMode
                || (a.playbackControls != null && a.playbackControls.dialogOpen)) return false;
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        View focus = a.getCurrentFocus();
        boolean horizontal =
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (a.seekGesture.active && !horizontal && down)
            finishSeek(a, key != KeyEvent.KEYCODE_BACK);
        boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (confirm
                && (!a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            if (down && event.getRepeatCount() == 0) {
                togglePlayback(a);
                showControls(a, true);
            }
            return true;
        }
        if (!a.controlsVisible
                && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_MENU)) {
            if (down) {
                showControls(a, true);
                if (a.playbackControls != null)
                    a.playbackControls.buttons.get("next-episode").requestFocus();
            }
            return true;
        }
        if (horizontal
                && !a.isLive()
                && (a.seekGesture.active
                        || !a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (down) {
                if (!a.seekGesture.active && event.getRepeatCount() == 0
                        || a.seekGesture.active
                                && a.seekGesture.direction != direction
                                && event.getRepeatCount() == 0) beginSeek(a, direction);
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && a.seekGesture.active
                    && a.seekGesture.direction == direction) finishSeek(a, !event.isCanceled());
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || (key == KeyEvent.KEYCODE_MEDIA_PLAY && !a.playing)
                        || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && a.playing)) togglePlayback(a);
                showControls(a, true);
            }
            return true;
        }
        if (a.controlsVisible && down) scheduleHide(a);
        return false;
    }
}
