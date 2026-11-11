package com.whalefall.movie;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.53：真正的左上角小窗（不再全屏 video 背景 + scrim）。
 *
 * <p>进入小窗时把 {@code playbackLayer} 缩到左上角固定尺寸，详情页完整显示在小窗下方/背后；
 * 小窗内叠加 播放/暂停、关闭、放大 三个控件；点小窗任意处回全屏。
 */
final class MiniDetails {
    final MainActivity a;
    final FrameLayout page;
    final View hit;
    final PlaybackControls controls;

    MiniDetails(MainActivity host) {
        a = host;
        controls = a.playbackControls;
        boolean tv = a.isTvDevice();
        int width;
        int height;
        if (tv) {
            width = a.dp(480);
            height = a.dp(270);
        } else {
            width = Math.round(a.windowWidth() * 0.45f);
            height = Math.round(width * 9f / 16f);
        }
        final int margin = a.dp(16);
        final int radius = a.dp(12);

        // 视频层缩成左上角小窗
        FrameLayout.LayoutParams videoLp =
                new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        videoLp.leftMargin = margin;
        videoLp.topMargin = margin;
        UiCompat.setClipToOutline(a.playbackLayer, true);
        android.graphics.drawable.GradientDrawable frame =
                new android.graphics.drawable.GradientDrawable();
        frame.setColor(0xff000000);
        frame.setCornerRadius(radius);
        frame.setStroke(a.dp(1), 0x66ffffff);
        a.playbackLayer.setBackground(frame);
        a.playbackLayer.setOutlineProvider(
                new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
        a.playbackLayer.setElevation(a.dp(8));
        a.playbackLayer.setLayoutParams(videoLp);
        AppLog.i("Mini", "enter size=" + width + "x" + height + " at=" + margin + "," + margin);

        // 小窗控制层（与视频同尺寸同位置，透明背景）
        page = new FrameLayout(a);
        page.setTag("mini-details");
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        FrameLayout.LayoutParams pageLp =
                new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        pageLp.leftMargin = margin;
        pageLp.topMargin = margin;
        page.setElevation(a.dp(10));
        page.setOnClickListener(v -> a.expandPlayback());

        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(a.dp(8), 0, a.dp(8), 0);
        GradientDrawable barBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0xcc000000});
        bar.setBackground(barBg);
        TextView playPause = miniButton(tv ? "⏸" : "⏯", "mini-play", () -> {
            a.togglePlayback();
        });
        TextView expand = miniButton("⛶", "mini-expand", () -> a.expandPlayback());
        TextView stop = miniButton("✕", "mini-stop", () -> {
            AppLog.i("Mini", "exit stop");
            PlaybackController.releasePlayer(a);
            a.ui.post(() -> {
                View play = a.root == null ? null : a.root.findViewWithTag("detail-play");
                if (play != null) play.requestFocus();
            });
        });
        bar.addView(playPause, new LinearLayout.LayoutParams(0, a.dp(tv ? 40 : 32), 1f));
        bar.addView(expand, new LinearLayout.LayoutParams(0, a.dp(tv ? 40 : 32), 1f));
        bar.addView(stop, new LinearLayout.LayoutParams(0, a.dp(tv ? 40 : 32), 1f));
        FrameLayout.LayoutParams barLp =
                new FrameLayout.LayoutParams(-1, a.dp(tv ? 40 : 32), Gravity.BOTTOM);
        page.addView(bar, barLp);

        // 小窗内一个不可见可聚焦载体：OK 回全屏，下方向进入详情控件
        hit = new View(a);
        hit.setFocusable(true);
        hit.setFocusableInTouchMode(true);
        hit.setOnClickListener(v -> a.expandPlayback());
        FrameLayout.LayoutParams hitLp = new FrameLayout.LayoutParams(-1, -1);
        page.addView(hit, hitLp);
        hit.setOnFocusChangeListener(
                (v, focused) ->
                        FocusMotion.style(
                                a, page, focused, 0x00000000, 0x66ffffff, 12, 0, 0, false, 1f));

        int layerIdx = a.root.indexOfChild(a.playbackLayer);
        a.root.addView(page, layerIdx >= 0 ? layerIdx + 1 : 0, pageLp);

        a.ui.post(
                () -> {
                    if (!a.miniMode) return;
                    if (controls != null) controls.resize(controls.videoWidth, controls.videoHeight);
                    playPause.requestFocus();
                    View detailPlay = a.root.findViewWithTag("detail-play");
                    if (detailPlay != null) {
                        playPause.setNextFocusDownId(detailPlay.getId());
                        hit.setNextFocusDownId(detailPlay.getId());
                    }
                });
    }

    private TextView miniButton(String label, String tag, Runnable action) {
        TextView b = a.text(label, a.isTvDevice() ? 16 : 13, 0xffffffff);
        b.setTag(tag);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(
                v -> {
                    if (v.hasFocus()) action.run();
                    else v.requestFocus();
                });
        b.setOnKeyListener(
                (v, key, event) -> {
                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && key == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
                        action.run();
                        return true;
                    }
                    return false;
                });
        return b;
    }

    void remove() {
        if (page.getParent() != null) ((FrameLayout) page.getParent()).removeView(page);
        if (hit != null && hit.getParent() == a.playbackLayer)
            a.playbackLayer.removeView(hit);
    }
}
