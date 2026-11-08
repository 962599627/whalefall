package com.whalefall.movie;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * 电视台直播页与播放器 chrome 共用的品牌色与小部件。
 *
 * <p>纯代码绘制，不引入图片资源；整体仍是「深海蓝底 + 蓝白玻璃 + 樱花粉强调」，
 * 台标色块只借用各频道自己的品牌色做识别（CCTV 深红、卫视各自主色）。
 */
final class TvUiKit {
    private TvUiKit() {}

    /** CCTV 深红色块（比品牌红略压暗，叠在深海底上不刺眼）。 */
    private static final int CCTV_A = 0xffc62b31;
    private static final int CCTV_B = 0xff7c1219;

    /** CGTN 深蓝。 */
    private static final int CGTN_A = 0xff1f4e9c;
    private static final int CGTN_B = 0xff102a5c;

    /** 未知频道（自定义 / 导入）的备选色板，都是深调子，白字可读。 */
    private static final int[] FALLBACK = {
        0xff2c5f8a, 0xff4a4e8a, 0xff6b4a7a, 0xff2f6b5e,
        0xff7a5a38, 0xff8a3a5a, 0xff3d6b8a, 0xff5a4a2e
    };

    /** 一种频道的台标视觉：小字品牌 + 大字编号/省份 + 搜索用拼音首字母 + 品牌渐变色。 */
    static final class Brand {
        final String wordmark;
        final String mark;
        final String initials;
        final int colorA;
        final int colorB;

        Brand(String wordmark, String mark, String initials, int colorA, int colorB) {
            this.wordmark = wordmark;
            this.mark = mark;
            this.initials = initials == null ? "" : initials;
            this.colorA = colorA;
            this.colorB = colorB;
        }
    }

    /** 根据频道名推导台标：CCTV 用「CCTV / 数字」，卫视用「卫视 / 省份」，其它用名称前两字。 */
    static Brand brand(String name) {
        String n = name == null ? "" : name.trim();
        String upper = n.toUpperCase(Locale.US);
        if (upper.startsWith("CCTV")) {
            String rest = n.substring(4).trim();
            while (rest.startsWith("-") || rest.startsWith("·")) rest = rest.substring(1).trim();
            int space = rest.indexOf(' ');
            String num = space > 0 ? rest.substring(0, space).trim() : rest;
            String desc = space > 0 ? rest.substring(space + 1).trim() : "";
            if (num.isEmpty()) num = "1";
            String initials = "cctv" + num.toLowerCase(Locale.US).replace("+", "plus");
            String descInit = descriptorInitials(desc);
            if (!descInit.isEmpty()) initials = initials + descInit;
            return new Brand("CCTV", num, initials, CCTV_A, CCTV_B);
        }
        if (upper.equals("CGTN") || upper.startsWith("CGTN ")) {
            return new Brand("CGTN", "国际", "cgtngj", CGTN_A, CGTN_B);
        }
        if (n.endsWith("卫视")) {
            String prov = n.substring(0, n.length() - 2).trim();
            if (prov.isEmpty()) prov = n;
            Province p = province(prov);
            if (p != null) return new Brand("卫视", prov, p.initial + "ws", p.a, p.b);
            int color = hashColor(prov);
            return new Brand("卫视", prov, "ws", color, shade(color, 0.62f));
        }
        String mark = n.length() > 2 ? n.substring(0, 2) : n;
        if (mark.isEmpty()) mark = "直播";
        int color = hashColor(n);
        return new Brand("直播", mark, n.toLowerCase(Locale.US), color, shade(color, 0.62f));
    }

    /** 已知卫视的主色，用来做省份色块；不在表里的走散列色板。 */
    private static final class Province {
        final String name;
        final String initial;
        final int a;
        final int b;

        Province(String name, String initial, int a, int b) {
            this.name = name;
            this.initial = initial;
            this.a = a;
            this.b = b;
        }
    }

    private static final Province[] PROVINCES = {
        new Province("湖南", "hn", 0xffe0762b, 0xff9c3f16),
        new Province("浙江", "zj", 0xff2e6fd9, 0xff1b3f7a),
        new Province("江苏", "js", 0xffc0392b, 0xff7e1e14),
        new Province("东方", "df", 0xffd7263d, 0xff8a1220),
        new Province("北京", "bj", 0xff9e2a2b, 0xff5c1416),
        new Province("广东", "gd", 0xff1f6fa8, 0xff123f63),
        new Province("深圳", "sz", 0xff0f7c8a, 0xff084a54),
        new Province("吉林", "jl", 0xff2c8a5a, 0xff175036),
    };

    private static Province province(String prov) {
        for (Province p : PROVINCES) if (p.name.equals(prov)) return p;
        return null;
    }

    /** 央视频道描述词的拼音首字母，例如「体育赛事」→ tyss，用于搜索「tyss」。 */
    private static final String[][] DESCRIPTORS = {
        {"综合", "zh"}, {"财经", "cj"}, {"综艺", "zy"}, {"中文国际", "zwgj"},
        {"体育赛事", "tyss"}, {"体育", "ty"}, {"电影", "dy"}, {"国防军事", "gfjs"},
        {"电视剧", "dsj"}, {"纪录", "jl"}, {"科教", "kj"}, {"戏曲", "xq"},
        {"社会与法", "shyf"}, {"新闻", "xw"}, {"少儿", "se"}, {"音乐", "yy"},
        {"奥林匹克", "aolpk"}, {"农业农村", "ncync"},
    };

    private static String descriptorInitials(String desc) {
        if (desc == null || desc.isEmpty()) return "";
        for (String[] row : DESCRIPTORS) if (desc.equals(row[0])) return row[1];
        return "";
    }

    private static int hashColor(String s) {
        int h = s == null ? 0 : Math.abs(s.hashCode());
        return FALLBACK[h % FALLBACK.length];
    }

    /** 把颜色压暗，用来做渐变的暗端。 */
    static int shade(int color, float factor) {
        int r = Math.min(255, Math.round(Color.red(color) * factor));
        int g = Math.min(255, Math.round(Color.green(color) * factor));
        int b = Math.min(255, Math.round(Color.blue(color) * factor));
        return Color.rgb(r, g, b);
    }

    /** 台标色块：品牌色左上→右下渐变 + 1dp 内高光描边，避免变成「纯色塑料块」。 */
    static GradientDrawable brandBlock(MainActivity a, Brand b, float radiusDp) {
        GradientDrawable gd =
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[] {b.colorA, b.colorB});
        gd.setCornerRadius(a.dp(radiusDp));
        gd.setStroke(Math.max(1, a.dp(0.8f)), 0x3dffffff);
        return gd;
    }

    /** 台标两行字：小字品牌（CCTV / 卫视 / 直播）+ 大字编号或省份。 */
    static LinearLayout brandMarks(
            MainActivity a, Brand b, float wordSize, float markSize, boolean withWordmark) {
        LinearLayout col = a.vertical();
        col.setGravity(Gravity.CENTER);
        if (withWordmark && b.wordmark != null && !b.wordmark.isEmpty()) {
            TextView word = a.text(b.wordmark, wordSize, 0xccffffff);
            word.setGravity(Gravity.CENTER);
            word.setSingleLine(true);
            word.setLetterSpacing(0.12f);
            col.addView(word);
        }
        TextView mark = a.text(b.mark, markSize, 0xfff7fbff);
        mark.setTypeface(null, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setSingleLine(true);
        col.addView(mark);
        return col;
    }

    /** 直播小圆点。 */
    static View dot(MainActivity a, int color, float sizeDp) {
        View v = new View(a);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
        return v;
    }

    /** 樱花粉渐变填充（选中块 / 主按钮），与详情页播放按钮同一色系。 */
    static GradientDrawable accentFill(MainActivity a, float radiusDp) {
        GradientDrawable gd =
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {0xffffc7d6, 0xfff59ab5});
        gd.setCornerRadius(a.dp(radiusDp));
        return gd;
    }

    /** 胶囊按钮：accent=true 时是樱花粉渐变主按钮，否则是蓝白玻璃次按钮。 */
    static TextView pill(MainActivity a, String label, float size, boolean accent) {
        TextView t = a.text(label, size, accent ? 0xff31141f : 0xffeaf1fb);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(a.dp(13), 0, a.dp(13), 0);
        if (accent) {
            t.setTypeface(null, Typeface.BOLD);
            t.setBackground(accentFill(a, 999));
            t.setElevation(a.dp(2));
        } else {
            t.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        }
        MobileDetailScreen.bindTouchFeedback(t);
        return t;
    }

    /** 加号图标：左侧分类栏底部「添加」胶囊用。 */
    static final class PlusGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PlusGlyph(Context c, int color) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            canvas.drawLine(w * 0.5f, h * 0.16f, w * 0.5f, h * 0.84f, paint);
            canvas.drawLine(w * 0.16f, h * 0.5f, w * 0.84f, h * 0.5f, paint);
        }
    }

    /** 导入图标：向下箭头 + 托盘。 */
    static final class ImportGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ImportGlyph(Context c, int color) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            canvas.drawLine(w * 0.5f, h * 0.14f, w * 0.5f, h * 0.60f, paint);
            canvas.drawLine(w * 0.5f, h * 0.60f, w * 0.29f, h * 0.38f, paint);
            canvas.drawLine(w * 0.5f, h * 0.60f, w * 0.71f, h * 0.38f, paint);
            canvas.drawLine(w * 0.18f, h * 0.82f, w * 0.82f, h * 0.82f, paint);
        }
    }

    /** 筛选图标：三条横线 + 三个滑块圆点。 */
    static final class FilterGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;

        FilterGlyph(Context c, int color) {
            super(c);
            this.color = color;
            float density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(color);
        }

        void setColor(int color) {
            this.color = color;
            paint.setColor(color);
            dot.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float[][] rows = {{0.28f, 0.36f}, {0.50f, 0.64f}, {0.72f, 0.46f}};
            for (float[] row : rows) {
                float y = h * row[0];
                canvas.drawLine(w * 0.14f, y, w * 0.86f, y, paint);
                canvas.drawCircle(w * row[1], y, h * 0.075f, dot);
            }
        }
    }

    /** 上折角箭头（筛选摘要条展开提示）。 */
    static final class ChevronUpGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;

        ChevronUpGlyph(Context c, int color) {
            super(c);
            this.color = color;
            float density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
        }

        void setColor(int color) {
            this.color = color;
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setColor(color);
            canvas.drawLine(w * 0.18f, h * 0.66f, w * 0.5f, h * 0.34f, paint);
            canvas.drawLine(w * 0.5f, h * 0.34f, w * 0.82f, h * 0.66f, paint);
        }
    }

    /** 放大镜图标：纯 Canvas 画，避免 emoji 与系统字体差异。 */
    static final class SearchGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        SearchGlyph(Context c, int color) {
            super(c);
            this.color = color;
            float density = c.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(w, h) * 0.29f;
            float cx = w * 0.42f;
            float cy = h * 0.42f;
            paint.setColor(color);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx + r * 0.74f, cy + r * 0.74f, w * 0.86f, h * 0.86f, paint);
        }
    }

    /** 加载指示器：樱花粉 90° 圆弧绕暗色圆环匀速转。 */
    static final class Spinner extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private ValueAnimator animator;
        private float angle;

        Spinner(Context c, int accent) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(2.6f * density);
            track.setColor(0x33ffffff);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(2.6f * density);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(accent);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 360f);
                animator.setDuration(900);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(
                        animation -> {
                            angle = (float) animation.getAnimatedValue();
                            invalidate();
                        });
            }
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float r = Math.min(w, h) / 2f - 3f * density;
            if (r <= 0) return;
            oval.set(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r);
            canvas.save();
            canvas.rotate(angle, w / 2f, h / 2f);
            canvas.drawCircle(w / 2f, h / 2f, r, track);
            canvas.drawArc(oval, -90f, 92f, false, arc);
            canvas.restore();
        }
    }
}
