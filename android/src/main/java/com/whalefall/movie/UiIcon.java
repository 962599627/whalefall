package com.whalefall.movie;

import android.graphics.PorterDuff;
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

    /** 工具面板单元格：可点整格 + 涟漪，内部 24dp 图标 + 11.5sp 标签（MD3 网格样式）。 */
    static FrameLayout toolCell(
            MainActivity a, int kind, String label, boolean active, Runnable click) {
        FrameLayout cell = new FrameLayout(a);
        cell.setClickable(true);
        cell.setFocusable(a.isTvDevice());
        cell.setContentDescription(label);
        cell.setBackground(UiTokens.roundedRipple(a, UiTokens.RADIUS_16, 0x26ffffff));
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
        inner.setPadding(0, a.dp(10), 0, a.dp(8));
        ImageView icon = new ImageView(a);
        icon.setImageResource(res(kind));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(
                active ? UiTokens.ACCENT : UiTokens.PLAYER_ICON, PorterDuff.Mode.SRC_IN);
        inner.addView(icon, new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        TextView text = new TextView(a);
        text.setText(label);
        text.setTextSize(UiTokens.TYPE_CAPTION);
        text.setTextColor(active ? UiTokens.ACCENT_SOFT : UiTokens.TEXT_SECONDARY);
        text.setGravity(android.view.Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        LinearLayout.LayoutParams textLp =
                new LinearLayout.LayoutParams(-2, -2);
        textLp.topMargin = a.dp(6);
        inner.addView(text, textLp);
        cell.addView(
                inner,
                new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        cell.setOnClickListener(click == null ? null : v -> click.run());
        cell.setMinimumHeight(a.dp(72));
        return cell;
    }

    static void setContentDescription(View v, String desc) {
        if (v != null) v.setContentDescription(desc);
    }
}
