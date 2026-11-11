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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean useExo = Build.VERSION.SDK_INT >= 26;
    private boolean released, moving, firstFrame;
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
        else startMediaPlayer();
        handler.postDelayed(firstFrameTimeout, 3000);
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
                        fail("exo:" + error.getErrorCodeName());
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

    private void startMediaPlayer() {
        surfaceView = new SurfaceView(host);
        surfaceView.setZOrderMediaOverlay(false);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(
                new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        if (released) return;
                        // 1.9.117：Surface 重挂后再启动首帧守候（仅日志）
                        handler.removeCallbacks(firstFrameTimeout);
                        handler.postDelayed(firstFrameTimeout, 3000);
                        // 1.9.118：已有 MediaPlayer 时只换 Surface，绝不重建（避免多次解码器/EGL 崩溃）
                        if (mediaPlayer != null) {
                            try {
                                mediaPlayer.setSurface(holder.getSurface());
                                if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                                AppLog.i("Wallpaper", "video keep engine=mediaplayer surface=reused");
                            } catch (Throwable error) {
                                AppLog.w("Wallpaper", "mediaplayer surface reuse failed", error);
                            }
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
                    public void surfaceDestroyed(SurfaceHolder holder) {}
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
        released = true;
        handler.removeCallbacks(firstFrameTimeout);
        AppLog.i("Wallpaper", "video fallback reason=" + reason);
        try {
            if (player != null) player.release();
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Throwable ignored) {
        }
        setVisibility(View.GONE);
        host.videoWallpaperFailed(reason);
    }

    void applyDim(int percent) {
        int level = Math.max(20, Math.min(100, percent));
        dim.setBackgroundColor(Color.argb(Math.round(level * 2.55f), 4, 8, 18));
        dim.bringToFront();
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
        if (!released && player != null) player.setPlayWhenReady(true);
        if (!released && mediaPlayer != null && !mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.start();
            } catch (Throwable ignored) {
            }
        }
    }

    boolean isReleased() {
        return released;
    }

    /** 换页面时把同一个播放器视图搬过去，避免重建导致的黑屏/首帧闪烁。 */
    void attachTo(android.widget.FrameLayout root) {
        moving = true;
        if (getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) getParent()).removeView(this);
        // 始终垫在 base 渐变之上、页面内容之下（index 0 = base）
        root.addView(
                this,
                Math.min(1, root.getChildCount()),
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        moving = false;
        // 1.9.117：页面切换不重建播放器；未出首帧时重置守候（仅日志，不 fallback）
        if (!firstFrame) {
            handler.removeCallbacks(firstFrameTimeout);
            handler.postDelayed(firstFrameTimeout, 3000);
        }
        AppLog.i("Wallpaper", "video page-change keep=true firstFrame=" + firstFrame);
        if (player != null) player.setPlayWhenReady(true);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.start();
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
