package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 播放/解析期间的全屏加载反馈层（1.8.3 起统一使用 {@link WhaleLoadingView} 圆形帧动画）。
 * 播放页内用 LARGE，非播放页（详情页解析等）用 SMALL 简洁模式；失败态保留「加载失败 + 重试」。
 */
final class PlaybackLoading extends FrameLayout {
    private final MainActivity a;
    private final boolean inPlayer;
    private final WhaleLoadingView whale;
    private final LinearLayout column;
    private Button retryButton;

    PlaybackLoading(MainActivity a) {
        super(a);
        this.a = a;
        setTag("playback-loading");
        setBackgroundColor(0x66000000);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        inPlayer = a.current != null && a.current.player && !a.miniMode;
        column = a.vertical();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        String label;
        if (inPlayer) {
            String episode =
                    a.activeEpisode == null || a.activeEpisode.name == null
                            ? ""
                            : a.activeEpisode.name.trim();
            label = episode.isEmpty() ? "正在加载…" : "正在加载… · " + episode;
        } else {
            label = a.isTvDevice() ? "正在加载播放信息…" : "正在加载中…";
        }
        whale = new WhaleLoadingView(a, inPlayer ? WhaleLoadingView.LARGE : WhaleLoadingView.SMALL, !inPlayer);
        whale.setText(label);
        column.addView(whale);
        addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        whale.start();
        AppLog.i(
                "PlaybackLoading",
                "show size=" + (inPlayer ? "large" : "small") + " label=" + label);
    }

    /** 显示失败态：停止动画，显示「加载失败」+ 重试按钮。 */
    void showError(String message, final Runnable retry) {
        whale.stop();
        whale.setText("加载失败");
        if (retryButton == null) {
            retryButton =
                    a.button(
                            "重试",
                            "playback-loading-retry",
                            () -> {
                                if (retry != null) retry.run();
                            });
            retryButton.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(150), a.dp(44));
            lp.topMargin = a.dp(12);
            column.addView(retryButton, lp);
        }
        retryButton.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
        if (message != null && !message.trim().isEmpty()) a.toast(message);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        whale.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        whale.stop();
        whale.release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) whale.start();
        else whale.stop();
    }
}
