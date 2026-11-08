package com.whalefall.movie;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 统一图标入口：全部使用 vector drawable（media3-ui 官方 Material 图标 + 本项目补齐的同风格
 * 填充图标），不再用 Canvas 手绘、也不再使用 {@code TextView.setCompoundDrawables}。
 *
 * <p>每个可点图标都是 48dp 触控目标 + 涟漪反馈 + contentDescription（可访问性）。
 * 图标统一白色，按状态用 {@link #tint} 改成樱花粉（激活态同时改变图标与颜色，不靠颜色单通道）。
 */
final class UiIcon {
    private UiIcon() {}

    // 图标种类
    static final int BACK = 1;
    static final int MORE = 2;
    static final int PLAY = 3;
    static final int PAUSE = 4;
    static final int PREV = 5;
    static final int NEXT = 6;
    static final int REW = 7;
    static final int FF = 8;
    static final int EPISODES = 9;
    static final int SPEED = 10;
    static final int SWAP = 11;
    static final int SUBTITLE = 12;
    static final int ASPECT = 13;
    static final int DANMAKU = 14;
    static final int AUDIO = 15;
    static final int TUNE = 16;
    static final int REPEAT = 17;
    static final int REPLAY = 18;
    static final int FULLSCREEN = 19;
    static final int INFO = 20;
    static final int SKIP_INTRO = 21;
    static final int SKIP_OUTRO = 22;
    static final int CLOSE = 23;
    static final int CHEVRON_LEFT = 24;
    static final int CHEVRON_RIGHT = 25;
    static final int REFRESH = 26;
    // 全局导航 / 通用图标（Dock、Tab、筛选条、设置卡片、电视台）
    static final int HOME = 27;
    static final int SEARCH = 28;
    static final int LIVE = 29;
    static final int FAVORITE = 30;
    static final int CLOUD = 31;
    static final int LIBRARY = 32;
    static final int PLUS = 33;
    static final int IMPORT = 34;
    static final int FILTER = 35;
    static final int CHEVRON_UP = 36;
    static final int CHEVRON_DOWN = 37;
    static final int TV = 38;
    static final int LAYERS = 39;
    static final int PLAY_CIRCLE = 40;

    /** 图标资源：官方 media3 Material 图标 + 本项目 res/drawable/ic_whale_*。 */
    static int res(int kind) {
        switch (kind) {
            case BACK:
                return R.drawable.ic_whale_back;
            case MORE:
                return R.drawable.ic_whale_more;
            case PLAY:
                return androidx.media3.ui.R.drawable.exo_icon_play;
            case PAUSE:
                return androidx.media3.ui.R.drawable.exo_icon_pause;
            case PREV:
                return androidx.media3.ui.R.drawable.exo_ic_skip_previous;
            case NEXT:
                return androidx.media3.ui.R.drawable.exo_ic_skip_next;
            case REW:
                return androidx.media3.ui.R.drawable.exo_icon_rewind;
            case FF:
                return androidx.media3.ui.R.drawable.exo_icon_fastforward;
            case EPISODES:
                return R.drawable.ic_whale_episodes;
            case SPEED:
                return androidx.media3.ui.R.drawable.exo_ic_speed;
            case SWAP:
                return R.drawable.ic_whale_swap;
            case SUBTITLE:
                return androidx.media3.ui.R.drawable.exo_ic_subtitle_on;
            case ASPECT:
                return R.drawable.ic_whale_aspect;
            case DANMAKU:
                return R.drawable.ic_whale_danmaku;
            case AUDIO:
                return androidx.media3.ui.R.drawable.exo_ic_audiotrack;
            case TUNE:
                return androidx.media3.ui.R.drawable.exo_ic_settings;
            case REPEAT:
                return androidx.media3.ui.R.drawable.exo_icon_repeat_all;
            case REPLAY:
                return R.drawable.ic_whale_replay;
            case FULLSCREEN:
                return androidx.media3.ui.R.drawable.exo_ic_fullscreen_enter;
            case INFO:
                return R.drawable.ic_whale_info;
            case SKIP_INTRO:
                return androidx.media3.ui.R.drawable.exo_ic_skip_next;
            case SKIP_OUTRO:
                return R.drawable.ic_whale_skip_outro;
            case CLOSE:
                return R.drawable.ic_whale_close;
            case CHEVRON_LEFT:
                return androidx.media3.ui.R.drawable.exo_ic_chevron_left;
            case CHEVRON_RIGHT:
                return androidx.media3.ui.R.drawable.exo_ic_chevron_right;
            case REFRESH:
                return R.drawable.ic_whale_refresh;
            case HOME:
                return R.drawable.ic_whale_home;
            case SEARCH:
                return R.drawable.ic_whale_search;
            case LIVE:
                return R.drawable.ic_whale_live;
            case FAVORITE:
                return R.drawable.ic_whale_favorite;
            case CLOUD:
                return R.drawable.ic_whale_cloud;
            case LIBRARY:
                return R.drawable.ic_whale_library;
            case PLUS:
                return R.drawable.ic_whale_plus;
            case IMPORT:
                return R.drawable.ic_whale_import;
            case FILTER:
                return R.drawable.ic_whale_filter;
            case CHEVRON_UP:
                return R.drawable.ic_whale_chevron_up;
            case CHEVRON_DOWN:
                return R.drawable.ic_whale_chevron_down;
            case TV:
                return R.drawable.ic_whale_tv;
            case LAYERS:
                return R.drawable.ic_whale_layers;
            case PLAY_CIRCLE:
                return androidx.media3.ui.R.drawable.exo_ic_play_circle_filled;
            default:
                return R.drawable.ic_whale_more;
        }
    }

    /** 工具 key → 图标种类（保留 PlaybackControls 原有 key 语义）。 */
    static int kindForKey(String key) {
        if (key == null) return MORE;
        switch (key) {
            case "prev-episode":
                return PREV;
            case "next-episode":
                return NEXT;
            case "episodes":
                return EPISODES;
            case "lines":
                return SWAP;
            case "speed":
                return SPEED;
            case "aspect":
                return ASPECT;
            case "subtitles":
                return SUBTITLE;
            case "fullscreen-tool":
                return FULLSCREEN;
            case "audio-tracks":
                return AUDIO;
            case "decoder":
                return TUNE;
            case "danmaku-tool":
                return DANMAKU;
            case "skip-intro-tool":
                return SKIP_INTRO;
            case "skip-outro-tool":
                return SKIP_OUTRO;
            case "repeat-tool":
                return REPEAT;
            case "restart-tool":
                return REPLAY;
            case "more":
                return MORE;
            default:
                return MORE;
        }
    }

    /**
     * 图标单元格：可点、48dp 触控、圆角涟漪；内部固定 24dp 图标居中。
     * 宽度由调用方（权重/固定宽）决定，图标不会被拉伸——避免 ImageButton FIT_CENTER
     * 在宽格子里把 24dp 图标放大。
     */
    static FrameLayout iconCell(
            MainActivity a, int kind, String desc, int iconDp, Runnable click) {
        FrameLayout cell = new FrameLayout(a);
        cell.setClickable(true);
        cell.setFocusable(false);
        cell.setContentDescription(desc);
        cell.setBackground(UiTokens.roundedRipple(a, UiTokens.RADIUS_12, 0x1fffffff));
        ImageView icon = new ImageView(a);
        icon.setImageResource(res(kind));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(UiTokens.PLAYER_ICON, PorterDuff.Mode.SRC_IN);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(
                icon,
                new FrameLayout.LayoutParams(a.dp(iconDp), a.dp(iconDp), android.view.Gravity.CENTER));
        cell.setOnClickListener(click == null ? null : v -> click.run());
        cell.setMinimumWidth(a.dp(UiTokens.TOUCH_MIN));
        cell.setMinimumHeight(a.dp(UiTokens.TOUCH_MIN));
        return cell;
    }

    /** 给单元格或 ImageView 上色（用于倍速等激活态变粉）。 */
    static void tintIconCell(View v, int color) {
        if (v instanceof ImageView) {
            tint((ImageView) v, color);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ImageView) {
                    tint((ImageView) child, color);
                    return;
                }
            }
        }
    }

    /** 静态图标。 */
    static ImageView view(MainActivity a, int kind, int color, int sizeDp) {
        ImageView v = new ImageView(a);
        v.setImageResource(res(kind));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        v.setAdjustViewBounds(false);
        v.setLayoutParams(new LinearLayout.LayoutParams(a.dp(sizeDp), a.dp(sizeDp)));
        return v;
    }

    static void setIcon(ImageView v, int kind) {
        if (v == null) return;
        v.setImageResource(res(kind));
    }

    static void tint(ImageView v, int color) {
        if (v == null) return;
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    /**
     * 方形图标按钮：transparent 圆角涟漪背景，48dp 触控目标，图标白色居中。
     * 用于底部图标行、顶部返回/更多等。
     */
    static ImageButton button(
            MainActivity a, int kind, String desc, int sizeDp, int iconDp, Runnable click) {
        return build(
                a, kind, desc, sizeDp, iconDp, UiTokens.PLAYER_ICON, false, 0, 0, click);
    }

    /** 圆形图标按钮：深色圆底 + 细描边 + 圆形涟漪，用于中央播放钮 / 快退快进 / 折叠钮。 */
    static ImageButton circleButton(
            MainActivity a,
            int kind,
            String desc,
            int sizeDp,
            int iconDp,
            int fill,
            int strokeColor,
            Runnable click) {
        return build(
                a,
                kind,
                desc,
                sizeDp,
                iconDp,
                UiTokens.PLAYER_ICON,
                true,
                fill,
                strokeColor,
                click);
    }

    private static ImageButton build(
            MainActivity a,
            int kind,
            String desc,
            int sizeDp,
            int iconDp,
            int color,
            boolean circle,
            int fill,
            int strokeColor,
            Runnable click) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(res(kind));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        int pad = Math.max(0, (a.dp(sizeDp) - a.dp(iconDp)) / 2);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(
                circle
                        ? UiTokens.circleRipple(
                                a, fill, strokeColor, UiTokens.PLAYER_RIPPLE)
                        : UiTokens.roundedRipple(a, UiTokens.RADIUS_12, 0x1fffffff));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setContentDescription(desc);
        b.setFocusable(a.isTvDevice());
        b.setClickable(true);
        b.setOnClickListener(click == null ? null : v -> click.run());
        if (a.isTvDevice()) {
            b.setOnFocusChangeListener(
                    (v, focused) -> {
                        v.animate()
                                .scaleX(focused ? 1.1f : 1f)
                                .scaleY(focused ? 1.1f : 1f)
                                .setDuration(UiTokens.MOTION_FAST)
                                .start();
                    });
        }
        return b;
    }

    /** 中央播放/暂停圆钮：72dp 实心深色圆 + 细描边 + 白色 glyph。 */
    static ImageButton playPause(MainActivity a, Runnable click) {
        ImageButton b =
                circleButton(
                        a,
                        PLAY,
                        "播放",
                        72,
                        40,
                        UiTokens.PLAYER_BTN_STRONG,
                        UiTokens.PLAYER_BTN_STROKE,
                        click);
        b.setElevation(a.dp(4));
        return b;
    }

    /** 工具面板单元格：可点整格 + 涟漪，内部 22dp 图标 + 10.5sp 标签（MD3 网格样式）。 */
    static FrameLayout toolCell(
            MainActivity a, int kind, String label, boolean active, Runnable click) {
        FrameLayout cell = new FrameLayout(a);
        cell.setClickable(true);
        cell.setFocusable(a.isTvDevice());
        cell.setContentDescription(label);
        cell.setBackground(UiTokens.roundedRipple(a, UiTokens.RADIUS_14, 0x26ffffff));
        if (a.isTvDevice()) {
            cell.setOnFocusChangeListener(
                    (v, focused) -> {
                        v.animate()
                                .scaleX(focused ? 1.06f : 1f)
                                .scaleY(focused ? 1.06f : 1f)
                                .setDuration(UiTokens.MOTION_FAST)
                                .start();
                    });
        }
        LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        inner.setPadding(0, a.dp(6), 0, a.dp(4));
        ImageView icon = new ImageView(a);
        icon.setImageResource(res(kind));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(
                active ? UiTokens.ACCENT : UiTokens.PLAYER_ICON, PorterDuff.Mode.SRC_IN);
        inner.addView(icon, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
        TextView text = new TextView(a);
        text.setText(label);
        text.setTextSize(UiTokens.TYPE_MICRO);
        text.setTextColor(active ? UiTokens.ACCENT_SOFT : UiTokens.TEXT_SECONDARY);
        text.setGravity(android.view.Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        LinearLayout.LayoutParams textLp =
                new LinearLayout.LayoutParams(-2, -2);
        textLp.topMargin = a.dp(4);
        inner.addView(text, textLp);
        cell.addView(
                inner,
                new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        cell.setOnClickListener(click == null ? null : v -> click.run());
        cell.setMinimumHeight(a.dp(60));
        return cell;
    }

    static void setContentDescription(View v, String desc) {
        if (v != null) v.setContentDescription(desc);
    }

    // ---------------- 直接画在 Canvas 上的矢量图标（Dock / Tab / 筛选条 / 设置卡片） ----------------

    /** 取一份可着色的 vector drawable（供自绘 View 使用，替代 Canvas 手绘路径）。 */
    static Drawable drawable(Context context, int kind) {
        Drawable d = context.getDrawable(res(kind));
        return d == null ? null : d.mutate();
    }

    static void tintDrawable(Drawable d, int color) {
        if (d != null) d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    /** 把 24×24 的 vector 等比缩放到 sizePx 并居中画到 viewW×viewH 上。 */
    static void drawCentered(Canvas canvas, Drawable d, int viewW, int viewH, float sizePx) {
        if (d == null || viewW <= 0 || viewH <= 0) return;
        int size = Math.max(1, Math.round(sizePx));
        int left = (viewW - size) / 2;
        int top = (viewH - size) / 2;
        d.setBounds(left, top, left + size, top + size);
        d.draw(canvas);
    }
}
