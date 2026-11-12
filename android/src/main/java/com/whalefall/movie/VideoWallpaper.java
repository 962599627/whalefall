package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.io.File;

/**
 * 本地视频动态壁纸：循环、静音、居中裁切，并叠加可调压暗层。
 *
 * <p>1.9.108：SDK&lt;26 使用 MediaPlayer + SurfaceView（旧 GPU 兼容），SDK&gt;=26 用 ExoPlayer；
 * 播放失败或 3s 未出首帧自动回退默认壁纸，避免整屏黑。
 */
final class VideoWallpaper extends FrameLayout {
    static final String FILE = "wallpaper-video.mp4";

    private final MainActivity host;
    private final View dim;
    private ExoPlayer player;
    private PlayerView view;
    private MediaPlayer mediaPlayer;
    private SurfaceView surfaceView;
    /** 1.9.184：老机 SurfaceView 拿不到 surface 时回退 TextureView。 */
    private android.view.TextureView textureView;
    private android.view.Surface textureSurface;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean useExo = Build.VERSION.SDK_INT >= 26;
    private boolean released, moving, firstFrame;
    /** 1.9.220：性能观测。 */
    private String codecName = "?";
    private int videoW = 0, videoH = 0;
    private int lastDropLog;
    /** 1.9.220：宿主暂停期间不要被 attach 自动恢复。 */
    private boolean pausedByHost;
    /** 1.9.180：失败只本次回退，可重试；Surface 每次页面挂载都重建。 */
    private boolean failed, surfaceRecreated;
    private final Runnable firstFrameTimeout =
            () -> {
                // 1.9.117：页面切换/重建导致首帧计时误判，仅记录不 fallback
                if (!released && !firstFrame)
                    AppLog.i("Wallpaper", "video firstFrame msec=3000 action=log-only keep=true");
            };

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    VideoWallpaper(MainActivity host) {
        super(host);
        this.host = host;
        setTag("wallpaper-video");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dim = new View(host);
        addView(dim, new LayoutParams(-1, -1));
        applyDim(host.prefs.getInt(Prefs.WALLPAPER_DIM, 35));
        AppLog.i(
                "Wallpaper",
                "video start engine="
                        + (useExo ? "exo" : "mediaplayer")
                        + " sdk="
                        + Build.VERSION.SDK_INT
                        + " path="
                        + file(host).getAbsolutePath());
        if (useExo) startExo();
        // 1.9.184：MediaPlayer 的 SurfaceView 延迟到窗口 attach 后再创建
        handler.postDelayed(firstFrameTimeout, 3000);
        // 1.9.184：窗口 attach 后再挂 SurfaceView（避免早期拿不到 surface）
        addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        if (released || useExo) return;
                        if (surfaceView == null && textureView == null) {
                            surfaceRecreated = mediaPlayer != null;
                            addSurfaceView();
                        }
                        scheduleSurfaceCheck(0);
                        verifyVisible("window-attach");
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {}
                });
        AppLog.i(
                "Compat",
                "sdk="
                        + Build.VERSION.SDK_INT
                        + " feature=wallpaper engine="
                        + (useExo ? "exo" : "mediaplayer"));
        AppLog.i(
                "Wallpaper",
                "video engine="
                        + (useExo ? "exo" : "mediaplayer")
                        + " sdk="
                        + Build.VERSION.SDK_INT
                        + " firstFrame=pending");
    }

    private void startExo() {
        view = new PlayerView(host);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        view.setKeepContentOnPlayerReset(true);
        view.setShutterBackgroundColor(Color.BLACK);
        addView(view, new LayoutParams(-1, -1));
        player = new ExoPlayer.Builder(host).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        try {
            player.setVideoScalingMode(
                    androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        } catch (Throwable ignored) {
        }
        player.setVolume(host.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false) ? 1f : 0f);
        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        firstFrame = true;
                        handler.removeCallbacks(firstFrameTimeout);
                        AppLog.i("Wallpaper", "video engine=exo firstFrame=true");
                    }

                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        AppLog.i(
                                "Wallpaper",
                                "video error codec="
                                        + codecName
                                        + " reason="
                                        + error.getErrorCodeName());
                        fail("exo:" + error.getErrorCodeName());
                    }
                });
        // 1.9.220：解码器/掉帧观测
        player.addAnalyticsListener(
                new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                    @Override
                    public void onVideoDecoderInitialized(
                            EventTime time,
                            String name,
                            long completed,
                            long duration) {
                        codecName = name;
                        perfLog("playing", "decoder");
                    }

                    @Override
                    public void onDroppedVideoFrames(
                            EventTime time, int dropped, long elapsedMs) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastDropLog >= 5000) {
                            lastDropLog = (int) now;
                            AppLog.i(
                                    "Wallpaper",
                                    "perf codec="
                                            + codecName
                                            + " state=playing dropped="
                                            + dropped);
                        }
                    }
                });
        view.setPlayer(player);
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file(host))));
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Throwable error) {
            fail("exo-prepare:" + error.getClass().getSimpleName());
        }
    }

    /** 1.9.220：壁纸性能日志。 */
    private void perfLog(String state, String reason) {
        try {
            if (player != null) {
                androidx.media3.common.VideoSize vs = player.getVideoSize();
                videoW = vs.width;
                videoH = vs.height;
            }
        } catch (Throwable ignored) {
        }
        boolean hw =
                codecName != null
                        && !codecName.startsWith("c2.android")
                        && !codecName.startsWith("OMX.google")
                        && !codecName.startsWith("c2.google");
        AppLog.i(
                "Wallpaper",
                "perf codec="
                        + (hw ? "hw" : "sw")
                        + " name="
                        + codecName
                        + " size="
                        + videoW
                        + "x"
                        + videoH
                        + " state="
                        + state
                        + " reason="
                        + reason
                        + " sdk="
                        + Build.VERSION.SDK_INT);
    }

    private void addSurfaceView() {
        surfaceView = new SurfaceView(host);
        surfaceView.setZOrderMediaOverlay(false);
        surfaceView.setZOrderOnTop(false);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(
                new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        if (released) return;
                        boolean valid =
                                holder.getSurface() != null && holder.getSurface().isValid();
                        AppLog.i(
                                "Wallpaper",
                                "video surface created valid="
                                        + valid
                                        + " size="
                                        + (surfaceView == null
                                                ? "?"
                                                : surfaceView.getWidth()
                                                        + "x"
                                                        + surfaceView.getHeight()));
                        // 1.9.117：Surface 重挂后再启动首帧守候（仅日志）
                        handler.removeCallbacks(firstFrameTimeout);
                        handler.postDelayed(firstFrameTimeout, 3000);
                        // 1.9.118：已有 MediaPlayer 时只换 Surface，绝不重建（避免多次解码器/EGL 崩溃）
                        if (mediaPlayer != null) {
                            try {
                                mediaPlayer.setSurface(holder.getSurface());
                                if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                                AppLog.i(
                                        "Wallpaper",
                                        "video attach surface=new reused="
                                                + surfaceRecreated
                                                + " valid="
                                                + (holder.getSurface() != null
                                                        && holder.getSurface().isValid()));
                            } catch (Throwable error) {
                                AppLog.w("Wallpaper", "mediaplayer surface reuse failed", error);
                                fail("mediaplayer-surface:" + describe(error));
                            }
                            surfaceRecreated = false;
                            return;
                        }
                        try {
                            final String path = file(host).getAbsolutePath();
                            AppLog.i("Wallpaper", "video engine=mediaplayer path=" + path);
                            mediaPlayer = new MediaPlayer();
                            // 1.9.111：setDataSource 必须最先设置，旧机否则 RuntimeException
                            mediaPlayer.setDataSource(path);
                            mediaPlayer.setSurface(holder.getSurface());
                            mediaPlayer.setLooping(true);
                            float vol =
                                    host.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false) ? 1f : 0f;
                            mediaPlayer.setVolume(vol, vol);
                            mediaPlayer.setVideoScalingMode(
                                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                            mediaPlayer.setOnPreparedListener(
                                    mp -> {
                                        try {
                                            mp.start();
                                        } catch (Throwable error) {
                                            AppLog.w("Wallpaper", "mediaplayer start failed", error);
                                            fail("mediaplayer-start:" + describe(error));
                                        }
                                    });
                            mediaPlayer.setOnInfoListener(
                                    (mp, what, extra) -> {
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            firstFrame = true;
                                            handler.removeCallbacks(firstFrameTimeout);
                                            AppLog.i("Wallpaper", "video engine=mediaplayer firstFrame=true");
                                        }
                                        return false;
                                    });
                            mediaPlayer.setOnErrorListener(
                                    (mp, what, extra) -> {
                                        fail("mediaplayer:" + what + "/" + extra);
                                        return true;
                                    });
                            mediaPlayer.prepareAsync();
                        } catch (Throwable error) {
                            AppLog.w("Wallpaper", "mediaplayer init failed", error);
                            fail("mediaplayer-init:" + describe(error));
                        }
                    }

                    @Override
                    public void surfaceChanged(
                            SurfaceHolder holder, int format, int width, int height) {}

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                        // 1.9.180：Surface 销毁立即解绑，避免 BufferQueue abandoned → -10005
                        try {
                            if (mediaPlayer != null) mediaPlayer.setSurface(null);
                        } catch (Throwable ignored) {
                        }
                        AppLog.i("Wallpaper", "video surface destroyed detach=true");
                    }
                });
        addView(surfaceView, 0, new LayoutParams(-1, -1));
    }

    private static String describe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return error == null
                ? "unknown"
                : error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 播放失败/首帧超时：恢复默认壁纸并提示。 */
    private void fail(String reason) {
        if (released) return;
        failed = true;
        handler.removeCallbacks(firstFrameTimeout);
        AppLog.i("Wallpaper", "video fallback reason=" + reason + " retry=true");
        try {
            if (player != null) player.release();
            player = null;
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.setSurface(null);
                } catch (Throwable ignored) {
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Throwable ignored) {
        }
        setVisibility(View.GONE);
        host.videoWallpaperFailed(reason);
    }

    /** 1.9.180：失败后下次挂载/切回页面自动重试。 */
    void retry() {
        if (released) return;
        failed = false;
        firstFrame = false;
        surfaceRecreated = false;
        setVisibility(View.VISIBLE);
        AppLog.i("Wallpaper", "video retry reason=page");
        try {
            removeAllViews();
            addView(dim, new LayoutParams(-1, -1));
            applyDim(host.prefs.getInt(Prefs.WALLPAPER_DIM, 35));
            if (useExo) startExo();
            else addSurfaceView();
            setVisibility(View.VISIBLE);
            handler.postDelayed(firstFrameTimeout, 3000);
        } catch (Throwable error) {
            AppLog.w("Wallpaper", "video retry failed", error);
            fail("retry:" + describe(error));
        }
    }

    void applyDim(int percent) {
        int level = Math.max(20, Math.min(100, percent));
        dim.setBackgroundColor(Color.argb(Math.round(level * 2.55f), 4, 8, 18));
        dim.bringToFront();
    }

    /** 1.9.179：视频播放时暂停动态壁纸，省 CPU/内存。 */
    void pause() {
        if (released) return;
        pausedByHost = true;
        AppLog.i("Wallpaper", "video engine=" + (useExo ? "exo" : "mediaplayer") + " action=pause");
        perfLog("paused", "pause");
        try {
            if (player != null) player.setPlayWhenReady(false);
        } catch (Throwable ignored) {
        }
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (Throwable ignored) {
        }
    }

    /** 1.9.179：回到非播放页恢复动态壁纸。 */
    /** 1.9.183：自检可见性/层级/根背景，必要时把根背景改透明。 */
    void verifyVisible(String reason) {
        try {
            android.view.ViewParent parent = getParent();
            int index = -1;
            if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) parent;
                index = group.indexOfChild(this);
                if (group instanceof android.view.View) {
                    android.view.View rootView = (android.view.View) group;
                    android.graphics.drawable.Drawable bg = rootView.getBackground();
                    boolean opaque = false;
                    if (bg instanceof android.graphics.drawable.ColorDrawable) {
                        opaque = ((android.graphics.drawable.ColorDrawable) bg).getAlpha() >= 250;
                    }
                    if (opaque) {
                        rootView.setBackground(null);
                        AppLog.i("Wallpaper", "video root background=transparent reason=" + reason);
                    }
                }
            }
            boolean surfaceValid =
                    surfaceView != null
                            && surfaceView.getHolder() != null
                            && surfaceView.getHolder().getSurface() != null
                            && surfaceView.getHolder().getSurface().isValid();
            String surfaceSize =
                    surfaceView == null
                            ? (textureView != null
                                    ? textureView.getWidth() + "x" + textureView.getHeight()
                                    : "?")
                            : surfaceView.getWidth() + "x" + surfaceView.getHeight();
            String parentBg = "none";
            if (parent instanceof android.view.View) {
                android.graphics.drawable.Drawable bg =
                        ((android.view.View) parent).getBackground();
                parentBg =
                        bg == null
                                ? "transparent"
                                : (bg instanceof android.graphics.drawable.ColorDrawable
                                                && ((android.graphics.drawable.ColorDrawable) bg)
                                                                .getAlpha()
                                                        >= 250
                                        ? "opaque"
                                        : "transparent");
            }
            AppLog.i(
                    "Wallpaper",
                    "video visible="
                            + (getVisibility() == View.VISIBLE)
                            + " surfaceValid="
                            + (surfaceValid || (textureView != null && textureView.isAvailable()))
                            + " surfaceSize="
                            + surfaceSize
                            + " attached="
                            + isAttachedToWindow()
                            + " parentBg="
                            + parentBg
                            + " rootAlpha="
                            + (parent instanceof android.view.View
                                    ? ((android.view.View) parent).getAlpha()
                                    : -1f)
                            + " layer="
                            + index
                            + " reason="
                            + reason);
        } catch (Throwable error) {
            AppLog.w("Wallpaper", "video verify failed", error);
        }
    }

    /** 1.9.184：surface 未就绪时 3 次重试，最终回退 TextureView。 */
    private boolean surfaceValid() {
        try {
            return surfaceView != null
                    && surfaceView.getHolder() != null
                    && surfaceView.getHolder().getSurface() != null
                    && surfaceView.getHolder().getSurface().isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void scheduleSurfaceCheck(final int n) {
        handler.postDelayed(
                () -> {
                    if (released) return;
                    boolean valid = surfaceValid();
                    AppLog.i(
                            "Wallpaper",
                            "video surface check n="
                                    + n
                                    + " valid="
                                    + valid
                                    + " attached="
                                    + isAttachedToWindow());
                    if (valid) {
                        verifyVisible("surface");
                        return;
                    }
                    if (n < 3) {
                        AppLog.i("Wallpaper", "video surface retry n=" + n);
                        scheduleSurfaceCheck(n + 1);
                    } else {
                        switchToTexture();
                    }
                },
                n == 0 ? 300 : 500);
    }

    /** 1.9.184：TextureView 兜底（MediaPlayer 支持 SurfaceTexture）。 */
    private void switchToTexture() {
        if (released || useExo || textureView != null) return;
        AppLog.i("Wallpaper", "video surface fallback=textureview");
        try {
            if (mediaPlayer != null) mediaPlayer.setSurface(null);
            if (surfaceView != null && surfaceView.getParent() == this) removeView(surfaceView);
            surfaceView = null;
            textureView = new android.view.TextureView(host);
            textureView.setSurfaceTextureListener(
                    new android.view.TextureView.SurfaceTextureListener() {
                        @Override
                        public void onSurfaceTextureAvailable(
                                android.graphics.SurfaceTexture st, int w, int h) {
                            try {
                                textureSurface = new android.view.Surface(st);
                                if (mediaPlayer != null) {
                                    mediaPlayer.setSurface(textureSurface);
                                    if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                                } else {
                                    addTextureMediaPlayer(st);
                                }
                                AppLog.i(
                                        "Wallpaper",
                                        "video surface created source=textureview valid="
                                                + (textureSurface != null
                                                        && textureSurface.isValid()));
                                verifyVisible("texture");
                            } catch (Throwable error) {
                                fail("texture:" + describe(error));
                            }
                        }

                        @Override
                        public void onSurfaceTextureSizeChanged(
                                android.graphics.SurfaceTexture st, int w, int h) {}

                        @Override
                        public boolean onSurfaceTextureDestroyed(
                                android.graphics.SurfaceTexture st) {
                            try {
                                if (mediaPlayer != null) mediaPlayer.setSurface(null);
                            } catch (Throwable ignored) {
                            }
                            if (textureSurface != null) {
                                textureSurface.release();
                                textureSurface = null;
                            }
                            return true;
                        }

                        @Override
                        public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
                    });
            addView(textureView, 0, new LayoutParams(-1, -1));
            handler.postDelayed(
                    () -> {
                        if (released || textureView == null) return;
                        if (!textureView.isAvailable()) {
                            AppLog.i("Wallpaper", "video surface fallback failed=textureview");
                            fail("surface-unavailable");
                        }
                    },
                    1200);
        } catch (Throwable error) {
            AppLog.w("Wallpaper", "textureview fallback failed", error);
            fail("textureview-init:" + describe(error));
        }
    }

    /** 1.9.184：TextureView 路径下首次创建 MediaPlayer。 */
    private void addTextureMediaPlayer(android.graphics.SurfaceTexture st) {
        try {
            textureSurface = new android.view.Surface(st);
            final String path = file(host).getAbsolutePath();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.setSurface(textureSurface);
            mediaPlayer.setLooping(true);
            float vol = host.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false) ? 1f : 0f;
            mediaPlayer.setVolume(vol, vol);
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            mediaPlayer.setOnPreparedListener(
                    mp -> {
                        try {
                            mp.start();
                        } catch (Throwable ignored) {
                        }
                    });
            mediaPlayer.setOnInfoListener(
                    (mp, what, extra) -> {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            firstFrame = true;
                            AppLog.i("Wallpaper", "video engine=mediaplayer firstFrame=true source=texture");
                        }
                        return false;
                    });
            mediaPlayer.setOnErrorListener(
                    (mp, what, extra) -> {
                        fail("mediaplayer-texture:" + what + "/" + extra);
                        return true;
                    });
            mediaPlayer.prepareAsync();
        } catch (Throwable error) {
            fail("mediaplayer-texture-init:" + describe(error));
        }
    }

    void resume() {
        if (released) return;
        pausedByHost = false;
        AppLog.i("Wallpaper", "video engine=" + (useExo ? "exo" : "mediaplayer") + " action=resume");
        perfLog("playing", "resume");
        try {
            if (player != null) player.setPlayWhenReady(true);
        } catch (Throwable ignored) {
        }
        try {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
        } catch (Throwable ignored) {
        }
    }

    void release() {
        if (released) return;
        released = true;
        handler.removeCallbacks(firstFrameTimeout);
        AppLog.i(
                "Wallpaper",
                "video stop engine=" + (useExo ? "exo" : "mediaplayer") + " reason=destroy");
        try {
            if (player != null) player.setPlayWhenReady(false);
        } catch (Throwable ignored) {
        }
        try {
            if (view != null) view.setPlayer(null);
        } catch (Throwable ignored) {
        }
        try {
            if (player != null) player.release();
        } catch (Throwable ignored) {
        }
        try {
            if (mediaPlayer != null) {
                // 1.9.118：严格顺序 setSurface(null) → stop → release，避免 EGL/Surface 原生崩溃
                mediaPlayer.setSurface(null);
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (surfaceView != null) surfaceView.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!released && !pausedByHost && player != null) player.setPlayWhenReady(true);
        if (!released && !pausedByHost && mediaPlayer != null && !mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.start();
            } catch (Throwable ignored) {
            }
        }
    }

    boolean isReleased() {
        return released;
    }

    /** 1.9.180：本次失败（可重试），不代表永久关闭。 */
    boolean isFailed() {
        return failed;
    }

    /** 换页面时把同一个播放器视图搬过去，避免重建导致的黑屏/首帧闪烁。 */
    void attachTo(android.widget.FrameLayout root) {
        moving = true;
        if (failed) retry();
        // 1.9.180：只复用播放器实例，每次页面挂载都新建 SurfaceView
        if (!useExo && mediaPlayer != null) {
            try {
                mediaPlayer.setSurface(null);
            } catch (Throwable ignored) {
            }
            if (surfaceView != null && surfaceView.getParent() == this) removeView(surfaceView);
            surfaceView = null;
            surfaceRecreated = true;
            addSurfaceView();
        }
        if (getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) getParent()).removeView(this);
        // 1.9.183：视频层固定在最底层（index 0），根背景透明
        root.addView(
                this,
                0,
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        moving = false;
        // 1.9.184：窗口 attach 且可见后再挂 SurfaceView，并重试检测 surface
        post(
                () -> {
                    if (released || useExo) return;
                    if (surfaceView == null) {
                        surfaceRecreated = mediaPlayer != null;
                        addSurfaceView();
                    } else if (surfaceView.getParent() != this) {
                        addView(surfaceView, 0, new LayoutParams(-1, -1));
                    }
                    scheduleSurfaceCheck(0);
                    verifyVisible("attach");
                });
        handler.postDelayed(() -> verifyVisible("attach"), 500);
        // 1.9.117：页面切换不重建播放器；未出首帧时重置守候（仅日志，不 fallback）
        if (!firstFrame) {
            handler.removeCallbacks(firstFrameTimeout);
            handler.postDelayed(firstFrameTimeout, 3000);
        }
        AppLog.i("Wallpaper", "video page-change keep=true firstFrame=" + firstFrame);
        if (player != null) player.setPlayWhenReady(true);
        if (mediaPlayer != null) {
            try {
                // 1.9.179：Surface 未就绪时不要 start，否则黑屏
                if (surfaceView != null && surfaceView.getHolder() != null
                        && surfaceView.getHolder().getSurface() != null
                        && surfaceView.getHolder().getSurface().isValid()) {
                    mediaPlayer.start();
                    AppLog.i("Wallpaper", "video engine=mediaplayer action=resume-valid");
                } else {
                    AppLog.i("Wallpaper", "video engine=mediaplayer action=wait-surface");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!released && !moving && player != null) player.setPlayWhenReady(false);
        super.onDetachedFromWindow();
    }
}
