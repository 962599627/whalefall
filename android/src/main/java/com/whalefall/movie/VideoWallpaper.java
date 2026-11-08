package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
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
 * <p>支持文件选择器能返回的视频（MP4 / WebM / MKV 等，取决于设备解码能力），
 * 作为首页背景动态播放，不影响播放器本身的进度与音量。
 */
final class VideoWallpaper extends FrameLayout {
    static final String FILE = "wallpaper-video.mp4";

    private final ExoPlayer player;
    private final PlayerView view;
    private final View dim;
    private boolean released;
    private boolean moving;

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    VideoWallpaper(MainActivity host) {
        super(host);
        setTag("wallpaper-video");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view = new PlayerView(host);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        view.setShutterBackgroundColor(Color.BLACK);
        addView(view, new LayoutParams(-1, -1));
        dim = new View(host);
        addView(dim, new LayoutParams(-1, -1));
        applyDim(host.prefs.getInt(Prefs.WALLPAPER_DIM, 35));
        player = new ExoPlayer.Builder(host).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(host.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false) ? 1f : 0f);
        view.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file(host))));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    void applyDim(int percent) {
        int level = Math.max(0, Math.min(100, percent));
        dim.setBackgroundColor(Color.argb(Math.round(level * 2.55f), 4, 8, 18));
    }

    void release() {
        if (released) return;
        released = true;
        try {
            player.setPlayWhenReady(false);
        } catch (Throwable ignored) {
        }
        try {
            view.setPlayer(null);
        } catch (Throwable ignored) {
        }
        try {
            player.release();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!released) player.setPlayWhenReady(true);
    }

    boolean isReleased() {
        return released;
    }

    /** 换页面时把同一个播放器视图搬过去，避免重建导致的黑屏/首帧闪烁。 */
    void attachTo(android.widget.FrameLayout root) {
        moving = true;
        if (getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) getParent()).removeView(this);
        root.addView(this, new android.widget.FrameLayout.LayoutParams(-1, -1));
        moving = false;
        player.setPlayWhenReady(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!released && !moving) player.setPlayWhenReady(false);
        super.onDetachedFromWindow();
    }
}
