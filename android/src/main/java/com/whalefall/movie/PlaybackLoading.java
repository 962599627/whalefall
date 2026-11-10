package com.whalefall.movie;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;

/**
 * 播放器「正在加载」反馈层（不创建导航目的地）。
 *
 * <p>播放页内显示 assets/loading/frame_01..07.webp 的循环帧动画（125ms/帧），
 * 动画下方「正在加载…」+ 可选剧集名；非播放页仍使用旧的胶囊+转圈提示。
 * 加载失败可显示「加载失败 + 重试」；页面不可见/切后台时停止动画省电。
 */
final class PlaybackLoading extends FrameLayout {
    private static final long FRAME_INTERVAL_MS = 125L;
    private static Bitmap[] frames;
    private static boolean framesLoading;

    private final MainActivity a;
    private final boolean inPlayer;
    private LinearLayout column;
    private ImageView frameView;
    private TextView statusText;
    private TextView episodeText;
    private Button retryButton;
    private Ring legacyRing;
    private int frameIndex;
    private boolean animating;

    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (!animating) return;
                    Bitmap[] cache = frames;
                    if (cache != null && cache.length > 0 && frameView != null) {
                        frameIndex = (frameIndex + 1) % cache.length;
                        frameView.setImageBitmap(cache[frameIndex]);
                    }
                    a.ui.postDelayed(this, FRAME_INTERVAL_MS);
                }
            };

    PlaybackLoading(MainActivity a) {
        super(a);
        this.a = a;
        setTag("playback-loading");
        setBackgroundColor(0x66000000);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        inPlayer = a.current != null && a.current.player && !a.miniMode;
        if (!inPlayer) {
            buildLegacyPill(a);
            return;
        }
        column = a.vertical();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        frameView = new ImageView(a);
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int side = playerAnimationSize(a);
        column.addView(frameView, new LinearLayout.LayoutParams(side, side));
        statusText = a.text("正在加载…", 12, UiTokens.TEXT_TERTIARY);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = a.dp(10);
        column.addView(statusText, statusLp);
        String episode =
                a.activeEpisode == null || a.activeEpisode.name == null
                        ? ""
                        : a.activeEpisode.name.trim();
        if (!episode.isEmpty()) {
            episodeText = a.text(episode, 11, UiTokens.TEXT_TERTIARY);
            episodeText.setMaxLines(1);
            episodeText.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-2, -2);
            epLp.topMargin = a.dp(3);
            column.addView(episodeText, epLp);
        }
        addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    private static int playerAnimationSize(MainActivity a) {
        int width = a.windowWidth();
        if (a.isTvDevice()) return Math.min(a.dp(260), Math.round(width * 0.3f));
        return Math.min(a.dp(180), Math.round(width * 0.5f));
    }

    /** 非播放页沿用旧的「胶囊 + 转圈」提示，不影响其它出口。 */
    private void buildLegacyPill(MainActivity a) {
        LinearLayout pill = a.row();
        column = pill;
        pill.setPadding(a.dp(24), a.dp(18), a.dp(24), a.dp(18));
        pill.setBackground(a.bg(0xf51a2b47, 24));
        legacyRing = new Ring(a);
        pill.addView(legacyRing, new LinearLayout.LayoutParams(a.dp(26), a.dp(26)));
        String label = a.isTvDevice() ? "正在加载播放信息…  返回取消" : "正在加载中…";
        statusText = a.text(label, 14, MainActivity.WHITE);
        statusText.setPadding(a.dp(16), 0, 0, 0);
        pill.addView(statusText);
        addView(pill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    /** 显示失败态：停止动画，显示「加载失败」+ 重试按钮。 */
    void showError(String message, final Runnable retry) {
        stopAnimation();
        if (frameView != null) frameView.setVisibility(View.GONE);
        if (legacyRing != null) legacyRing.setVisibility(View.GONE);
        if (statusText != null) statusText.setText("加载失败");
        if (episodeText != null) episodeText.setText(message == null ? "" : message);
        else if (column != null && statusText != null && message != null && !message.isEmpty()) {
            // 旧胶囊没有第二行：失败原因放到 toast，胶囊只显示「加载失败」
            a.toast(message);
        }
        if (column == null) return;
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
    }

    // ---------------- 生命周期/动画 ----------------

    private void startAnimation() {
        if (!inPlayer || animating) return;
        ensureFrames(a);
        Bitmap[] cache = frames;
        if (cache == null || cache.length == 0) {
            if (statusText != null) statusText.setText("正在加载…");
            return;
        }
        animating = true;
        if (frameView != null) frameView.setImageBitmap(cache[frameIndex]);
        a.ui.removeCallbacks(ticker);
        a.ui.postDelayed(ticker, FRAME_INTERVAL_MS);
    }

    private void stopAnimation() {
        animating = false;
        a.ui.removeCallbacks(ticker);
    }

    /** assets 帧只解码一次并缓存（7 帧 × 480×480 ARGB）。 */
    private static synchronized void ensureFrames(MainActivity a) {
        if (frames != null || framesLoading) return;
        framesLoading = true;
        try {
            Bitmap[] cache = new Bitmap[7];
            for (int i = 0; i < cache.length; i++) {
                String name =
                        String.format(java.util.Locale.ROOT, "loading/frame_%02d.webp", i + 1);
                try (InputStream in = a.getAssets().open(name)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    cache[i] = BitmapFactory.decodeStream(in, null, options);
                } catch (Throwable ignored) {
                }
            }
            boolean ok = true;
            for (Bitmap bitmap : cache) if (bitmap == null) ok = false;
            if (ok) frames = cache;
            AppLog.i("PlaybackLoading", "frames loaded=" + (frames != null) + " count=" + cache.length);
        } finally {
            framesLoading = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        if (frameView != null) frameView.setImageBitmap(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) startAnimation();
        else stopAnimation();
    }

    // ---------------- 旧胶囊转圈 ----------------

    static final class Ring extends View {
        final Paint paint = new Paint(3);
        ValueAnimator animation;
        float angle;

        Ring(MainActivity a) {
            super(a);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(a.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(MainActivity.WHITE);
        }

        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animation = ValueAnimator.ofFloat(0, 360);
            animation.setDuration(900);
            animation.setRepeatCount(-1);
            animation.setInterpolator(new android.view.animation.LinearInterpolator());
            animation.addUpdateListener(
                    v -> {
                        angle = (float) v.getAnimatedValue();
                        invalidate();
                    });
            animation.start();
        }

        protected void onDetachedFromWindow() {
            if (animation != null) {
                animation.removeAllUpdateListeners();
                animation.cancel();
                animation = null;
            }
            super.onDetachedFromWindow();
        }

        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float inset = paint.getStrokeWidth();
            c.drawArc(
                    new RectF(inset, inset, getWidth() - inset, getHeight() - inset),
                    angle,
                    260,
                    false,
                    paint);
        }
    }
}
