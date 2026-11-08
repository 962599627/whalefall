package com.whalefall.movie;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/**
 * 「鲸落」设计系统 token：语义色、字号 scale、4/8 间距、圆角、触控与动效，以及通用
 * Material 风格 drawable 构造器（圆角/圆形/涟漪）。
 *
 * <p>颜色对比度基于深海蓝底 {@link #BG} 实测：主文字 17.3:1、次文字 9.1:1、弱文字 6.7:1、
 * 樱花粉强调 8.7:1，全部达到 WCAG AA（4.5:1）。播放器是内容优先场景，只用黑/白/单点粉，
 * 彩色玻璃叠加画面会在暖色画面上发脏（见 docs/TV_UI_DESIGN.md §12 的结论）。
 *
 * <p>完整规格见 docs/WHALEFALL_DESIGN_SYSTEM.md。
 */
final class UiTokens {
    private UiTokens() {}

    // ---------------- 语义色 ----------------
    static final int BG = 0xff0b1220;
    static final int BG_DEEP = 0xff070d18;
    static final int SURFACE_1 = 0xff141c2a;
    static final int SURFACE_2 = 0xff1b2536;
    static final int TEXT_PRIMARY = 0xfff5f7fc;
    static final int TEXT_SECONDARY = 0xffa9b7d0;
    static final int TEXT_TERTIARY = 0xff8d9cb6;
    static final int ACCENT = 0xffff8fb1;
    static final int ACCENT_SOFT = 0xffffb3c8;
    static final int ACCENT_DEEP = 0xffe96c96;
    static final int SUCCESS = 0xff2ad47a;
    static final int DANGER = 0xffff6b81;
    static final int GLASS_FILL = 0x1affffff;
    static final int STROKE = 0x33ffffff;
    static final int STROKE_SOFT = 0x1affffff;

    // ---------------- 播放器专用（内容优先：黑 / 白 / 单点粉） ----------------
    static final int PLAYER_ICON = 0xfff2f6ff;
    static final int PLAYER_ACTIVE = 0xffff8fb1;
    static final int PLAYER_SCRIM = 0xe6000000;
    static final int PLAYER_BTN_FILL = 0x66000000;
    static final int PLAYER_BTN_STRONG = 0xcc0b1220;
    static final int PLAYER_BTN_STROKE = 0x40ffffff;
    static final int PLAYER_RIPPLE = 0x33ffffff;
    static final int PLAYER_CHIP = 0x99000000;
    static final int PLAYER_SHEET = 0xf20b1220;

    // ---------------- 字号 scale (sp) ----------------
    static final float TYPE_CLOCK = 24f;
    static final float TYPE_TITLE = 17f;
    static final float TYPE_TITLE_COMPACT = 16f;
    static final float TYPE_BODY = 15f;
    static final float TYPE_BODY_SM = 13f;
    static final float TYPE_CAPTION = 11.5f;
    static final float TYPE_MICRO = 10.5f;

    // ---------------- 间距 / 圆角 / 尺寸 (dp) ----------------
    static final int SP_2 = 2;
    static final int SP_4 = 4;
    static final int SP_6 = 6;
    static final int SP_8 = 8;
    static final int SP_10 = 10;
    static final int SP_12 = 12;
    static final int SP_16 = 16;
    static final int SP_20 = 20;
    static final int SP_24 = 24;
    static final int SP_32 = 32;
    static final int SP_40 = 40;
    static final int SP_48 = 48;
    static final int RADIUS_6 = 6;
    static final int RADIUS_10 = 10;
    static final int RADIUS_12 = 12;
    static final int RADIUS_16 = 16;
    static final int RADIUS_20 = 20;
    static final int RADIUS_24 = 24;
    static final int TOUCH_MIN = 48;
    static final int ICON_ROW_HEIGHT = 56;
    static final int TOOLBAR_HEIGHT = 48;

    // ---------------- 动效 (ms) ----------------
    static final int MOTION_FAST = 120;
    static final int MOTION_BASE = 160;
    static final int MOTION_SLOW = 240;

    // ---------------- drawable 构造器 ----------------

    static GradientDrawable round(MainActivity a, float radiusDp, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(a.dp(radiusDp));
        return d;
    }

    static GradientDrawable round(MainActivity a, float radiusDp, int fill, int strokeColor) {
        GradientDrawable d = round(a, radiusDp, fill);
        d.setStroke(Math.max(1, a.dp(1)), strokeColor);
        return d;
    }

    static GradientDrawable roundPx(int radiusPx, int fill, int strokeColor, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0 && strokeColor != 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    static GradientDrawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    static GradientDrawable circle(int fill, int strokeColor, int strokePx) {
        GradientDrawable d = circle(fill);
        if (strokePx > 0 && strokeColor != 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** 圆形涟漪背景（播放器圆钮）：深色圆 + 白色 20% 涟漪。 */
    static Drawable circleRipple(MainActivity a, int fill, int strokeColor, int rippleColor) {
        GradientDrawable content = circle(fill);
        if (strokeColor != 0) content.setStroke(Math.max(1, a.dp(1)), strokeColor);
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor), content, circle(0xffffffff));
    }

    /** 圆角矩形涟漪背景（图标行 / 工具格 / 列表行）。 */
    static Drawable roundedRipple(MainActivity a, float radiusDp, int rippleColor) {
        GradientDrawable content = round(a, radiusDp, 0x00000000);
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor), content, round(a, radiusDp, 0xffffffff));
    }
}
