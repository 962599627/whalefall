package com.whalefall.movie;

import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.87：旧 TV（WebView 不支持 HLS）用的原生直播播放页。
 * 只负责 m3u8 直播流：ExoPlayer + SurfaceView，BACK 释放并返回。
 */
final class NativeLiveScreen {
    private NativeLiveScreen() {}

    private static MoviePlayer player;
    private static VideoOutput output;

    static void show(final MainActivity a, final String name, final String url) {
        close(a);
        a.renderPage("", "");
        a.body.setTag("native-live");
        FrameLayout root = a.root;
        root.setBackgroundColor(Color.BLACK);

        output = new VideoOutput(a, false);
        root.addView(output, new FrameLayout.LayoutParams(-1, -1));

        final TextView status = a.text("正在加载 " + name + " …", 16, Color.WHITE);
        status.setTag("native-live-status");
        status.setGravity(Gravity.CENTER);
        status.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        root.addView(
                status,
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        final TextView title = a.text("直播 · " + name, 18, 0xffffffff);
        title.setTag("native-live-title");
        title.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
        FrameLayout.LayoutParams titleLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        titleLp.setMargins(a.dp(24), a.dp(18), 0, 0);
        root.addView(title, titleLp);

        output.listen(
                new VideoOutput.Listener() {
                    @Override
                    public void available(android.view.Surface surface) {
                        if (player != null) player.setSurface(surface);
                        else start(a, name, url, surface, status);
                    }

                    @Override
                    public void destroyed() {
                        if (player != null) player.setSurface(null);
                    }
                });
        AppLog.i("TvLive", "weblive native=yes channel=" + name + " url=" + url);
    }

    private static void start(
            final MainActivity a,
            final String name,
            final String url,
            android.view.Surface surface,
            final TextView status) {
        try {
            MoviePlayer candidate = new MoviePlayer(a);
            candidate.configureDefaultAudio();
            candidate.setDataSource(a, Uri.parse(url), java.util.Collections.emptyMap());
            candidate.setSurface(surface);
            candidate.setOnPreparedListener(
                    p -> {
                        status.setText("直播中 · " + name + " · 按返回键退出");
                        p.start();
                    });
            candidate.setOnErrorListener(
                    (p, code, extra) -> {
                        status.setText(
                                "直播流加载失败（"
                                        + code
                                        + "）。该频道暂不可用，请换台或稍后重试。");
                        AppLog.i("TvLive", "weblive native=error channel=" + name + " code=" + code);
                        return true;
                    });
            player = candidate;
            candidate.prepareAsync();
        } catch (Throwable error) {
            AppLog.w("TvLive", "native live start failed", error);
            status.setText("直播流加载失败：" + a.message(error));
        }
    }

    static void close(MainActivity a) {
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        if (output != null) {
            output = null;
        }
    }

    static boolean handleBack(MainActivity a) {
        close(a);
        a.popBack();
        return true;
    }
}
