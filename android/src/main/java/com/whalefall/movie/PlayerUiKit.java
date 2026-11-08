package com.whalefall.movie;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * 原生播放器 UI 的纯 Canvas 绘制资源：图标、播放/暂停圆钮、15s 圆钮、进度条轨道、拖拽圆点等。
 *
 * <p>配色沿用鲸落主题：玻璃白 12%–33%、樱花粉 {@code #FFA9C4}、深海蓝 {@code #0B1220}；
 * 所有图标画在 24×24 的逻辑网格里再按 View 尺寸缩放，避免引入图片资源。
 */
final class PlayerUiKit {
    private PlayerUiKit() {}

    // 图标类型
    static final int ICON_BACK = 1;
    static final int ICON_PREV = 2;
    static final int ICON_NEXT = 3;
    static final int ICON_EPISODES = 4;
    static final int ICON_LINES = 5;
    static final int ICON_SPEED = 6;
    static final int ICON_ASPECT = 7;
    static final int ICON_SUBTITLE = 8;
    static final int ICON_ORIENTATION = 9;
    static final int ICON_MORE = 10;
    static final int ICON_AUDIO = 11;
    static final int ICON_DECODER = 12;
    static final int ICON_DANMAKU = 13;
    static final int ICON_SKIP_INTRO = 14;
    static final int ICON_SKIP_OUTRO = 15;
    static final int ICON_REPEAT = 16;
    static final int ICON_RESTART = 17;
    static final int ICON_FULLSCREEN = 18;
    static final int ICON_CHEVRON_LEFT = 19;
    static final int ICON_CHEVRON_RIGHT = 20;
    static final int ICON_PLAY = 21;
    static final int ICON_PAUSE = 22;
    static final int ICON_SEEK_BACK = 23;
    static final int ICON_SEEK_FWD = 24;
    static final int ICON_CHEVRON_DOWN = 25;
    static final int ICON_CHEVRON_UP = 26;

    static final int INK = 0xffe8eff9;
    static final int PINK = 0xffff8fb1;
    static final int PINK_BRIGHT = 0xffffa9c4;
    static final int GLASS_FILL = 0x730b1220;
    static final int GLASS_STROKE = 0x33ffffff;

    /** 播放器 Dock 的工具 key → 图标类型。 */
    static int iconFor(String key) {
        if (key == null) return ICON_MORE;
        switch (key) {
            case "prev-episode":
                return ICON_PREV;
            case "next-episode":
                return ICON_NEXT;
            case "episodes":
                return ICON_EPISODES;
            case "lines":
                return ICON_LINES;
            case "speed":
                return ICON_SPEED;
            case "aspect":
                return ICON_ASPECT;
            case "subtitles":
                return ICON_SUBTITLE;
            case "fullscreen-tool":
                return ICON_ORIENTATION;
            case "audio-tracks":
                return ICON_AUDIO;
            case "decoder":
                return ICON_DECODER;
            case "danmaku-tool":
                return ICON_DANMAKU;
            case "skip-intro-tool":
                return ICON_SKIP_INTRO;
            case "skip-outro-tool":
                return ICON_SKIP_OUTRO;
            case "repeat-tool":
                return ICON_REPEAT;
            case "restart-tool":
                return ICON_RESTART;
            default:
                return ICON_MORE;
        }
    }

    // ===================== 圆形玻璃背景 / 胶囊 =====================

    static GradientDrawable circleBg(int fill, int stroke, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    static GradientDrawable capsuleBg(int fill, int stroke, int strokePx, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    // ===================== Button 图标 =====================

    /** 给工具 Button 设置「图标在上、文字在下」的 compound drawable。 */
    static void setToolIcon(TextView button, String key, int color, int sizePx) {
        IconDrawable icon = new IconDrawable(iconFor(key), color, sizePx);
        button.setCompoundDrawables(null, icon, null, null);
    }

    /** 给按钮左侧设置图标（底部「退出全屏」胶囊用）。 */
    static void setLeftIcon(TextView button, int type, int color, int sizePx) {
        IconDrawable icon = new IconDrawable(type, color, sizePx);
        button.setCompoundDrawables(icon, null, null, null);
    }

    /** 切换图标/文字着色（用于「选中粉色」状态）。 */
    static void tintTool(TextView button, int color) {
        button.setTextColor(color);
        Drawable[] parts = button.getCompoundDrawables();
        for (Drawable d : parts) {
            if (d instanceof IconDrawable) ((IconDrawable) d).setColor(color);
        }
    }

    // ===================== 进度条 =====================

    /** 4dp 三段式进度轨道：底轨 30% 白、缓冲 50% 白、已播樱花粉（纯黑遮罩上清晰）。 */
    static Drawable seekProgress(MainActivity a) {
        int radius = Math.max(1, a.dp(2));
        GradientDrawable bg = capsuleBg(0x4dffffff, 0, 0, radius);
        GradientDrawable secondary = capsuleBg(0x80ffffff, 0, 0, radius);
        GradientDrawable played = capsuleBg(0xffff8fb1, 0, 0, radius);
        ClipDrawable secondaryClip =
                new ClipDrawable(secondary, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        ClipDrawable playedClip = new ClipDrawable(played, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers =
                new LayerDrawable(new Drawable[] {bg, secondaryClip, playedClip});
        int inset = Math.max(0, a.dp(14)); // SeekBar 高 32dp → 轨道居中 4dp
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        layers.setLayerInset(2, 0, inset, 0, inset);
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.secondaryProgress);
        layers.setId(2, android.R.id.progress);
        return layers;
    }

    /** 进度拖拽圆点：白色圆 + 深色描边；拖拽时放大。 */
    static Drawable seekThumb(MainActivity a, boolean dragging) {
        int size = a.dp(dragging ? 18 : 12);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xffffffff);
        d.setStroke(Math.max(1, a.dp(1f)), 0x66000000);
        d.setSize(size, size);
        return d;
    }

    // ===================== 自定义 View =====================

    /** 纯图标 View（返回 / 全屏 / 更多 / 折叠箭头等），点击由调用方设置。 */
    static final class IconView extends View {
        private IconDrawable icon;

        IconView(Context c, int type, int color, int sizePx) {
            super(c);
            icon = new IconDrawable(type, color, sizePx);
            setClickable(true);
        }

        void setIcon(int type) {
            icon = new IconDrawable(type, icon.getColor(), icon.getIntrinsicWidth());
            invalidate();
        }

        void setColor(int color) {
            icon.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (icon == null) return;
            icon.setBounds(0, 0, getWidth(), getHeight());
            icon.draw(canvas);
        }
    }

    /** 中央 64dp 播放/暂停：玻璃外环 + 樱花粉渐变内圆 + 深海蓝字形。 */
    static final class PlayPauseButton extends View {
        private final MainActivity a;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        PlayPauseButton(MainActivity host) {
            super(host);
            this.a = host;
            setClickable(true);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) / 2f;

            // 实心深色圆 + 细白描边：亮画面上不脏
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xcc0b1220);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, a.dp(1f)));
            paint.setColor(0x33ffffff);
            canvas.drawCircle(cx, cy, radius - a.dp(0.8f), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff2f6ff);
            if (!a.prepared) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(a.dp(2.4f));
                canvas.drawCircle(cx, cy, radius * 0.26f, paint);
                paint.setStyle(Paint.Style.FILL);
                return;
            }
            if (a.playing) {
                float barW = radius * 0.14f;
                float barH = radius * 0.44f;
                float gap = radius * 0.12f;
                canvas.drawRoundRect(
                        new RectF(cx - gap - barW, cy - barH / 2f, cx - gap, cy + barH / 2f),
                        barW / 2f,
                        barW / 2f,
                        paint);
                canvas.drawRoundRect(
                        new RectF(cx + gap, cy - barH / 2f, cx + gap + barW, cy + barH / 2f),
                        barW / 2f,
                        barW / 2f,
                        paint);
            } else {
                path.reset();
                path.moveTo(cx - radius * 0.18f, cy - radius * 0.28f);
                path.lineTo(cx + radius * 0.30f, cy);
                path.lineTo(cx - radius * 0.18f, cy + radius * 0.28f);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    /** 左右 15 秒玻璃圆钮：弧形箭头 + 「15」文字。 */
    static final class SkipCircle extends View {
        private final MainActivity a;
        private final boolean forward;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final Path path = new Path();

        SkipCircle(MainActivity host, boolean forward) {
            super(host);
            this.a = host;
            this.forward = forward;
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) / 2f;

            // 深色实心圆 + 细白描边：亮画面上不脏
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xb30b1220);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, a.dp(1f)));
            paint.setColor(0x2effffff);
            canvas.drawCircle(cx, cy, radius - a.dp(0.6f), paint);

            // 弧形箭头
            float rr = radius * 0.46f;
            oval.set(cx - rr, cy - rr - a.dp(4), cx + rr, cy + rr - a.dp(4));
            paint.setColor(0xffffffff);
            paint.setStrokeWidth(Math.max(1, a.dp(1.6f)));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(oval, forward ? -60f : 120f, forward ? 240f : -240f, false, paint);

            // 箭头尖
            float angle = forward ? 60f : 120f;
            double rad = Math.toRadians(angle);
            float ax = cx + (float) (Math.cos(rad) * rr);
            float ay = (cy - a.dp(4)) + (float) (Math.sin(rad) * rr);
            float dir = forward ? -1f : 1f;
            path.reset();
            path.moveTo(ax + dir * a.dp(4f), ay);
            path.lineTo(ax - dir * a.dp(1.5f), ay - a.dp(4f));
            path.lineTo(ax - dir * a.dp(1.5f), ay + a.dp(4f));
            path.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);

            // 15 文字
            paint.setColor(0xffffffff);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * 0.42f);
            paint.setShadowLayer(a.dp(3), 0, a.dp(1), 0x99000000);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText("15", cx, cy + radius * 0.16f - (fm.ascent + fm.descent) / 2f, paint);
            paint.clearShadowLayer();
        }
    }

    // ===================== 24×24 图标绘制 =====================

    /** 可着色、可放大缩小的矢量图标 Drawable。 */
    static final class IconDrawable extends Drawable {
        private final int type;
        private final int sizePx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        IconDrawable(int type, int color, int sizePx) {
            this.type = type;
            this.sizePx = sizePx;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        int getColor() {
            return paint.getColor();
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            if (w <= 0 || h <= 0) return;
            float scale = Math.min(w, h) / 24f;
            canvas.save();
            canvas.translate(b.left + (w - 24 * scale) / 2f, b.top + (h - 24 * scale) / 2f);
            canvas.scale(scale, scale);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f);
            drawIcon(canvas);
            canvas.restore();
        }

        private void drawIcon(Canvas c) {
            switch (type) {
                case ICON_BACK:
                    path.reset();
                    path.moveTo(15f, 5f);
                    path.lineTo(8f, 12f);
                    path.lineTo(15f, 19f);
                    c.drawPath(path, paint);
                    break;
                case ICON_PREV:
                    c.drawLine(6f, 5f, 6f, 19f, paint);
                    path.reset();
                    path.moveTo(19f, 5f);
                    path.lineTo(9f, 12f);
                    path.lineTo(19f, 19f);
                    path.close();
                    c.drawPath(path, fill());
                    break;
                case ICON_NEXT:
                    c.drawLine(18f, 5f, 18f, 19f, paint);
                    path.reset();
                    path.moveTo(5f, 5f);
                    path.lineTo(15f, 12f);
                    path.lineTo(5f, 19f);
                    path.close();
                    c.drawPath(path, fill());
                    break;
                case ICON_EPISODES:
                    c.drawRoundRect(new RectF(4f, 4f, 11f, 11f), 1.6f, 1.6f, paint);
                    c.drawRoundRect(new RectF(13f, 4f, 20f, 11f), 1.6f, 1.6f, paint);
                    c.drawRoundRect(new RectF(4f, 13f, 11f, 20f), 1.6f, 1.6f, paint);
                    c.drawRoundRect(new RectF(13f, 13f, 20f, 20f), 1.6f, 1.6f, paint);
                    break;
                case ICON_LINES:
                    c.drawLine(5f, 9f, 17f, 9f, paint);
                    path.reset();
                    path.moveTo(17f, 9f);
                    path.lineTo(13.5f, 5.5f);
                    path.moveTo(17f, 9f);
                    path.lineTo(13.5f, 12.5f);
                    c.drawPath(path, paint);
                    c.drawLine(19f, 15f, 7f, 15f, paint);
                    path.reset();
                    path.moveTo(7f, 15f);
                    path.lineTo(10.5f, 11.5f);
                    path.moveTo(7f, 15f);
                    path.lineTo(10.5f, 18.5f);
                    c.drawPath(path, paint);
                    break;
                case ICON_SPEED:
                    oval.set(4f, 5f, 20f, 21f);
                    c.drawArc(oval, 200f, 140f, false, paint);
                    c.drawLine(12f, 13f, 17f, 8f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12f, 13f, 1.6f, paint);
                    break;
                case ICON_ASPECT:
                    c.drawRect(new RectF(4f, 6f, 20f, 18f), paint);
                    c.drawLine(8f, 10f, 8f, 14f, paint);
                    c.drawLine(16f, 10f, 16f, 14f, paint);
                    break;
                case ICON_SUBTITLE:
                    c.drawRoundRect(new RectF(3f, 5f, 21f, 17f), 3f, 3f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(7f, 12f, 11f, 14f), 1f, 1f, paint);
                    c.drawRoundRect(new RectF(13f, 12f, 17f, 14f), 1f, 1f, paint);
                    break;
                case ICON_ORIENTATION:
                    c.drawRoundRect(new RectF(9f, 3f, 15f, 17f), 2.2f, 2.2f, paint);
                    path.reset();
                    path.moveTo(4f, 20f);
                    path.quadTo(8f, 23f, 12f, 20f);
                    path.quadTo(16f, 17f, 20f, 20f);
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(20f, 20f);
                    path.lineTo(18.5f, 16.5f);
                    path.lineTo(16.5f, 19f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case ICON_MORE:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(6f, 12f, 1.7f, paint);
                    c.drawCircle(12f, 12f, 1.7f, paint);
                    c.drawCircle(18f, 12f, 1.7f, paint);
                    break;
                case ICON_AUDIO:
                    paint.setStrokeWidth(2.4f);
                    c.drawLine(6f, 10f, 6f, 14f, paint);
                    c.drawLine(12f, 6f, 12f, 18f, paint);
                    c.drawLine(18f, 9f, 18f, 15f, paint);
                    break;
                case ICON_DECODER:
                    c.drawRoundRect(new RectF(6f, 6f, 18f, 18f), 2f, 2f, paint);
                    paint.setStrokeWidth(1.5f);
                    c.drawLine(9f, 3f, 9f, 6f, paint);
                    c.drawLine(15f, 3f, 15f, 6f, paint);
                    c.drawLine(9f, 18f, 9f, 21f, paint);
                    c.drawLine(15f, 18f, 15f, 21f, paint);
                    c.drawLine(3f, 9f, 6f, 9f, paint);
                    c.drawLine(18f, 9f, 21f, 9f, paint);
                    c.drawLine(3f, 15f, 6f, 15f, paint);
                    c.drawLine(18f, 15f, 21f, 15f, paint);
                    break;
                case ICON_DANMAKU:
                    c.drawRoundRect(new RectF(3f, 5f, 21f, 16f), 3f, 3f, paint);
                    path.reset();
                    path.moveTo(8f, 16f);
                    path.lineTo(7f, 21f);
                    path.lineTo(12f, 16f);
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(8f, 10.5f, 1.2f, paint);
                    c.drawCircle(12f, 10.5f, 1.2f, paint);
                    c.drawCircle(16f, 10.5f, 1.2f, paint);
                    break;
                case ICON_SKIP_INTRO:
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(5f, 6f);
                    path.lineTo(15f, 12f);
                    path.lineTo(5f, 18f);
                    path.close();
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawLine(18f, 6f, 18f, 18f, paint);
                    break;
                case ICON_SKIP_OUTRO:
                    path.reset();
                    path.moveTo(5f, 7f);
                    path.lineTo(11f, 12f);
                    path.lineTo(5f, 17f);
                    path.moveTo(12f, 7f);
                    path.lineTo(18f, 12f);
                    path.lineTo(12f, 17f);
                    c.drawPath(path, paint);
                    break;
                case ICON_REPEAT:
                    path.reset();
                    path.addRoundRect(new RectF(4f, 6f, 20f, 18f), 4f, 4f, Path.Direction.CW);
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(19f, 12f);
                    path.lineTo(15.5f, 9.5f);
                    path.lineTo(15.5f, 14.5f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case ICON_RESTART:
                    oval.set(4f, 4f, 20f, 20f);
                    c.drawArc(oval, 40f, 280f, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(19f, 7f);
                    path.lineTo(19f, 12f);
                    path.lineTo(14f, 10f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case ICON_FULLSCREEN:
                    paint.setStrokeWidth(2f);
                    c.drawLine(4f, 9f, 4f, 4f, paint);
                    c.drawLine(4f, 4f, 9f, 4f, paint);
                    c.drawLine(15f, 4f, 20f, 4f, paint);
                    c.drawLine(20f, 4f, 20f, 9f, paint);
                    c.drawLine(20f, 15f, 20f, 20f, paint);
                    c.drawLine(20f, 20f, 15f, 20f, paint);
                    c.drawLine(9f, 20f, 4f, 20f, paint);
                    c.drawLine(4f, 20f, 4f, 15f, paint);
                    break;
                case ICON_CHEVRON_LEFT:
                    path.reset();
                    path.moveTo(15f, 6f);
                    path.lineTo(9f, 12f);
                    path.lineTo(15f, 18f);
                    c.drawPath(path, paint);
                    break;
                case ICON_CHEVRON_RIGHT:
                    path.reset();
                    path.moveTo(9f, 6f);
                    path.lineTo(15f, 12f);
                    path.lineTo(9f, 18f);
                    c.drawPath(path, paint);
                    break;
                case ICON_PLAY:
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(7f, 4f);
                    path.lineTo(20f, 12f);
                    path.lineTo(7f, 20f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case ICON_PAUSE:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(6f, 4f, 10f, 20f), 1.5f, 1.5f, paint);
                    c.drawRoundRect(new RectF(14f, 4f, 18f, 20f), 1.5f, 1.5f, paint);
                    break;
                case ICON_SEEK_BACK:
                case ICON_SEEK_FWD:
                    // 圆环箭头 + 「10」秒
                    oval.set(2.5f, 2.5f, 21.5f, 21.5f);
                    c.drawArc(oval, 40f, 280f, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    if (type == ICON_SEEK_BACK) {
                        path.moveTo(2.5f, 7.5f);
                        path.lineTo(7.2f, 9.8f);
                        path.lineTo(3.6f, 12.4f);
                    } else {
                        path.moveTo(21.5f, 7.5f);
                        path.lineTo(16.8f, 9.8f);
                        path.lineTo(20.4f, 12.4f);
                    }
                    path.close();
                    c.drawPath(path, paint);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                    paint.setTextSize(7.2f);
                    c.drawText("10", 12f, 14.2f, paint);
                    paint.setTextAlign(Paint.Align.LEFT);
                    break;
                case ICON_CHEVRON_DOWN:
                    path.reset();
                    path.moveTo(6f, 9f);
                    path.lineTo(12f, 15f);
                    path.lineTo(18f, 9f);
                    c.drawPath(path, paint);
                    break;
                case ICON_CHEVRON_UP:
                    path.reset();
                    path.moveTo(6f, 15f);
                    path.lineTo(12f, 9f);
                    path.lineTo(18f, 15f);
                    c.drawPath(path, paint);
                    break;
                default:
                    break;
            }
        }

        /** 实心绘制（部分图标需要 fill）。 */
        private Paint fill() {
            paint.setStyle(Paint.Style.FILL);
            return paint;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }
}
