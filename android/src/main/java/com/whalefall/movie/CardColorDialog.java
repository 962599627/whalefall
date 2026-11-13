package com.whalefall.movie;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡片调色盘（1.9.321）。
 *
 * <p>固定五段结构：① 标题「卡片调色盘」+ 右上「恢复默认」；② 分段控件「底色 / 选中色」；
 * ③ 固定预览卡（68dp：色块 + HEX + 不透明度/文字对比度，RGB 拖动时始终可见）；④ 唯一滚动区
 * （3×3 色板 → 推荐组合 → RGB 三行 → HEX）；⑤ 底部固定主次按钮（取消 0.3 / 应用 0.7）。
 * 根布局 MATCH_PARENT，弹窗高度固定 {@code min(屏高-32dp, 720dp)}，只有中间 ScrollView
 * weight=1 可滚动，任何机型都不会裁切内容。
 *
 * <p>1.9.321 修复「应用不了」：① 弹窗注册进 {@link ModalStack}——手机遥控注入的 OK/方向键
 * 只会作用于最上层模态，之前没注册导致按键全部落到背景 Activity，点「应用」没反应；
 * ② 所有可交互控件用 {@link #bindConfirm(View, Runnable)} 同时绑点击与遥控确认键（OK/ENTER/
 * SPACE），不再依赖各机型 View 默认的确认键行为；③ 窗口尺寸在 show() 之前设定，首帧就
 * 可触摸/聚焦；④ 全链路加 {@code CardDialog} 日志，可直接判断点击、prefs 落盘与
 * MainActivity 是否真的应用。
 *
 * <p>对比度硬规则（WCAG 2.1 AA）：正文与可见底色一律 ≥4.5:1；色块/按钮/预览按填充色亮度
 * 自动反色（深底浅字、浅底深字），极端中间调会先把填充微调再上字，保证可读；描边 ≥3:1。
 * 所有颜色取自 Skin token，Dialog 内不写死色值。
 *
 * <p>TV：色块、滑条、按钮均可聚焦，方向键可达，色块 OK 选色，滑条左右键每次 ±5。手机：点击
 * 选色、HEX 键盘输入。取消不写任何 prefs；「恢复默认」只重置当前分段。
 */
final class CardColorDialog {
    private static final int BASE_MODE = 0;
    private static final int ACCENT_MODE = 1;
    /** 1.9.326：第三段「文字颜色」，写回原有字体色 prefs（不另造一套）。 */
    private static final int TEXT_MODE = 2;
    private static final int TAB_COUNT = 3;

    /** WCAG 2.1：正文对比度下限 4.5:1。 */
    private static final double MIN_TEXT_CONTRAST = 4.5;
    /** WCAG 2.1：非文字 UI（滑轨/描边）对比度下限 3:1。 */
    private static final double MIN_EDGE_CONTRAST = 3.0;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final float PANEL_RADIUS_DP = 24f;
    private static final int PREVIEW_HEIGHT_DP = 68;
    private static final int PREVIEW_SWATCH_DP = 44;
    private static final int SWATCH_SIZE_DP = 44;
    private static final int SWATCH_RADIUS_DP = 12;
    private static final int SWATCH_GAP_DP = 10;
    private static final int SEGMENT_HEIGHT_DP = 44;
    private static final int SEGMENT_INNER_DP = 38;
    private static final int BUTTON_HEIGHT_DP = 52;
    private static final int DIALOG_MIN_MARGIN_DP = 32;
    private static final int DIALOG_MAX_HEIGHT_DP = 720;
    private static final int DIALOG_MAX_WIDTH_DP = 420;
    private static final int DIALOG_MAX_WIDTH_TV_DP = 560;

    /** 1.9.325：底色色板 = 8 套主题预设（2×4），名称/说明来自 Skin。 */
    private static final String[] BASE_PALETTE_NAMES = Skin.PRESET_NAMES;
    /** 选中色色板：樱花粉 / 珊瑚 / 杏桃 3 套预设。 */
    private static final String[] ACCENT_PALETTE_NAMES = {"樱花粉", "珊瑚", "杏桃"};
    /** 1.9.326：文字颜色色板 = 8 套主题配套字色（2×4）。 */
    private static final String[] TEXT_PALETTE_NAMES = {
            "暖樱白", "墨灰白", "梅粉白", "咖啡暖白",
            "青瓷绿白", "夜蓝白", "紫夜白", "灰岩白"
    };
    private static final String[] BASE_PRESET_NAMES = {"樱夜", "墨夜", "雾梅"};
    private static final String[] ACCENT_PRESET_NAMES = {"樱花粉", "珊瑚", "杏桃"};
    private static final String[] TEXT_PRESET_NAMES = {"暖樱白", "墨灰白", "梅粉白"};
    private static final String[] APPLY_LABELS = {"应用底色", "应用选择色", "应用文字色"};

    private CardColorDialog() {}

    // ------------------------------------------------------------------
    // 颜色基础工具
    // ------------------------------------------------------------------

    static int opaque(int rgb) {
        return 0xff000000 | (rgb & 0xffffff);
    }

    static int toRgb(int[] rgb) {
        return ((rgb[0] & 0xff) << 16) | ((rgb[1] & 0xff) << 8) | (rgb[2] & 0xff);
    }

    static Integer parseHex(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("#")) text = text.substring(1);
        if (text.length() != 6) return null;
        try {
            return Integer.parseInt(text, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String hex(int rgb) {
        return String.format(
                java.util.Locale.ROOT,
                "#%02X%02X%02X",
                (rgb >> 16) & 0xff,
                (rgb >> 8) & 0xff,
                rgb & 0xff);
    }

    static String hexOf(int[] rgb) {
        return hex(toRgb(rgb));
    }

    private static int[] rgbParts(int rgb) {
        return new int[] {((rgb >> 16) & 0xff), ((rgb >> 8) & 0xff), (rgb & 0xff)};
    }

    private static int withAlpha(int color, int alpha) {
        return ((alpha & 0xff) << 24) | (color & 0xffffff);
    }

    /** 线性混合：t=0 取 a，t=1 取 b（用于从 token 派生色板与安全填充）。 */
    private static int mix(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = Math.round(ar + (br - ar) * k);
        int g = Math.round(ag + (bg - ag) * k);
        int bl = Math.round(ab + (bb - ab) * k);
        return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (bl & 0xff);
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }

    private static double luminance(int color) {
        return .2126 * linear((color >> 16) & 0xff)
                + .7152 * linear((color >> 8) & 0xff)
                + .0722 * linear(color & 0xff);
    }

    /** WCAG 对比度（1.0 ~ 21.0）。 */
    static double contrast(int first, int second) {
        double l1 = luminance(first);
        double l2 = luminance(second);
        double light = Math.max(l1, l2);
        double dark = Math.min(l1, l2);
        return (light + .05) / (dark + .05);
    }

    /** 填充上的文字色：深底浅字 / 浅底深字，取对比度更高的一侧。 */
    private static int readableInk(int fill) {
        return contrast(fill, Skin.PINK_ON_FILL_TEXT) >= contrast(fill, Skin.TEXT_PRIMARY_T)
                ? Skin.PINK_ON_FILL_TEXT
                : Skin.TEXT_PRIMARY_T;
    }

    /**
     * 保证「填充 + 文字」≥4.5:1：极端中间调（例如 #707070）两种字的对比都不够时，把填充朝
     * 反方向混合 45% 再上字；色块本体仍显示真实颜色，只有承载文字的填充会被微调。
     */
    private static int readableFill(int source) {
        int fill = source & 0xffffff;
        if (contrast(fill, Skin.PINK_ON_FILL_TEXT) >= MIN_TEXT_CONTRAST
                || contrast(fill, Skin.TEXT_PRIMARY_T) >= MIN_TEXT_CONTRAST) {
            return opaque(fill);
        }
        int ink = readableInk(fill);
        int away = ink == Skin.PINK_ON_FILL_TEXT ? Skin.TEXT_PRIMARY_T : Skin.PINK_ON_FILL_TEXT;
        return opaque(mix(fill, away, .45f));
    }

    /** 滑轨/进度等非文字 UI 的 accent：≥3:1 即可。 */
    private static int accentEdge(int background) {
        int strong = Skin.ACCENT_STRONG & 0xffffff;
        if (contrast(strong, background) >= MIN_EDGE_CONTRAST) return opaque(strong);
        int soft = Skin.ACCENT_SOFT_T & 0xffffff;
        if (contrast(soft, background) >= MIN_EDGE_CONTRAST) return opaque(soft);
        return Skin.FOCUS_RING;
    }

    /** 高亮环：填充上 3:1 可见。 */
    private static int edgeInk(int fill) {
        if (contrast(fill, Skin.PINK_ON_FILL_TEXT) >= MIN_EDGE_CONTRAST) return Skin.PINK_ON_FILL_TEXT;
        if (contrast(fill, Skin.TEXT_PRIMARY_T) >= MIN_EDGE_CONTRAST) return Skin.TEXT_PRIMARY_T;
        return Skin.FOCUS_RING;
    }

    // ------------------------------------------------------------------
    // 色板 / 推荐组合（全部由 Skin token 派生，不写死色值）
    // ------------------------------------------------------------------

    private static int[] paletteOf(int mode) {
        if (mode == TEXT_MODE) {
            // 1.9.326：字体色板 = 8 套主题配套字色，自定义走 RGB/HEX
            int[] inks = new int[Skin.PRESET_INKS.length];
            for (int i = 0; i < inks.length; i++) inks[i] = Skin.PRESET_INKS[i];
            return inks;
        }
        if (mode == ACCENT_MODE) {
            // 1.9.325：选中色只保留 3 套预设（樱花粉/珊瑚/杏桃），自定义走 RGB/HEX
            return new int[] {
                    Skin.ACCENT_DEFAULT,
                    Skin.PALETTE_ACCENT_CORAL,
                    Skin.PALETTE_ACCENT_APRICOT
            };
        }
        // 1.9.325：底色 = 8 套主题预设（2×4），色值/名称/说明统一来自 Skin
        int[] presets = new int[Skin.PRESET_COLORS.length];
        for (int i = 0; i < presets.length; i++) presets[i] = opaque(Skin.PRESET_COLORS[i]);
        return presets;
    }

    /** 色板列数：底色/文字色 2×4，选中色 3 个一行。 */
    private static int paletteColumnsOf(int mode) {
        return mode == ACCENT_MODE ? 3 : 4;
    }

    private static String[] paletteNamesOf(int mode) {
        if (mode == TEXT_MODE) return TEXT_PALETTE_NAMES;
        return mode == ACCENT_MODE ? ACCENT_PALETTE_NAMES : BASE_PALETTE_NAMES;
    }

    /** 底色命中预设时返回「中文名 · 风格说明」，否则 null。 */
    private static String presetTagOf(int[] rgb) {
        int color = toRgb(rgb) & 0xffffff;
        for (int i = 0; i < Skin.PRESET_COLORS.length; i++) {
            if ((Skin.PRESET_COLORS[i] & 0xffffff) == color) {
                return Skin.PRESET_NAMES[i] + " · " + Skin.PRESET_TAGS[i];
            }
        }
        return null;
    }

    private static int[] recommendedOf(int mode) {
        if (mode == TEXT_MODE) {
            return new int[] {Skin.PRESET_INKS[0], Skin.PRESET_INKS[1], Skin.PRESET_INKS[2]};
        }
        if (mode == ACCENT_MODE) {
            return new int[] {
                    Skin.ACCENT_STRONG, Skin.PALETTE_ACCENT_CORAL, Skin.PALETTE_ACCENT_APRICOT
            };
        }
        return new int[] {Skin.CARD_BASE, Skin.PALETTE_BASE_INK, Skin.PALETTE_BASE_PLUM};
    }

    private static String[] recommendedNamesOf(int mode) {
        if (mode == TEXT_MODE) return TEXT_PRESET_NAMES;
        return mode == ACCENT_MODE ? ACCENT_PRESET_NAMES : BASE_PRESET_NAMES;
    }

    // ------------------------------------------------------------------
    // 设置页摘要
    // ------------------------------------------------------------------

    /** 设置页摘要，避免旧版本"字体色"文案残留。 */
    static String summary(MainActivity a) {
        String preset = MainActivity.migrateCardPreset(a.prefs);
        int raw = a.prefs.getInt("card_accent_color", Skin.ACCENT_DEFAULT);
        int accent = a.prefs.getBoolean("card_accent_custom", false)
                ? raw
                : MainActivity.migrateAccent(raw);
        return "卡片 "
                + MainActivity.cardPresetLabel(preset)
                + " · 选中色 #"
                + String.format(java.util.Locale.ROOT, "%06X", accent & 0xffffff);
    }

    // ------------------------------------------------------------------
    // 弹窗
    // ------------------------------------------------------------------

    static void show(final MainActivity a) {
        final Ui ui = new Ui();
        ui.mode = BASE_MODE;
        String basePreset = MainActivity.migrateCardPreset(a.prefs);
        ui.modeRgb[BASE_MODE] = rgbParts(MainActivity.cardPresetBase(basePreset, a.prefs));
        ui.modeRgb[ACCENT_MODE] = rgbParts(opaque(
                a.prefs.getInt("card_accent_color", Skin.ACCENT_DEFAULT)));
        // 1.9.326：第三段文字颜色 = 现有字体色 prefs（TEXT_COLOR / TEXT_COLOR_CUSTOM）
        ui.modeRgb[TEXT_MODE] = rgbParts(opaque(
                a.prefs.getInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR)));
        ui.alphaPct = Math.max(0, Math.min(100, a.prefs.getInt("glass_alpha", 40)));
        copy(ui.modeRgb[BASE_MODE], ui.rgb);

        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        // ---------- 根容器：MATCH_PARENT + 24dp 圆角 + 1dp 描边 ----------
        LinearLayout panel = a.vertical();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(14));
        panel.setBackground(shape(a, Skin.BACKDROP_TOP, Skin.CARD_STROKE, 1f, PANEL_RADIUS_DP));
        dialog.setContentView(panel, new ViewGroup.LayoutParams(MATCH, MATCH));

        // ---------- ① 固定标题行：标题 + 右上「恢复默认」 ----------
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = a.vertical();
        TextView eyebrow = label(a, "外观 · 卡片", 10f, Skin.TEXT_TERTIARY_T, false);
        eyebrow.setLetterSpacing(.14f);
        heading.addView(eyebrow, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView title = label(a, "卡片调色盘", 19f, Skin.TEXT_PRIMARY_T, true);
        title.setPadding(0, a.dp(2), 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));
        header.addView(heading, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final TextView reset = label(a, "恢复默认", 12f, Skin.TEXT_SECONDARY_T, false);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(a.dp(12), 0, a.dp(12), 0);
        reset.setFocusable(true);
        reset.setClickable(true);
        reset.setContentDescription("恢复当前分段的默认颜色");
        reset.setOnFocusChangeListener((v, focused) -> {
            reset.setBackground(
                    focused
                            ? shape(a, Skin.CARD_BASE, Skin.FOCUS_RING, 1.5f, 10f)
                            : shape(a, Color.TRANSPARENT, 0, 0f, 10f));
            reset.setTextColor(focused ? Skin.TEXT_PRIMARY_T : Skin.TEXT_SECONDARY_T);
        });
        header.addView(reset, new LinearLayout.LayoutParams(WRAP, a.dp(44)));
        panel.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        a.gap(panel, 16);

        // ---------- ② 分段控件：底色 / 选中色 ----------
        LinearLayout segments = a.row();
        segments.setGravity(Gravity.CENTER);
        segments.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3));
        segments.setBackground(shape(a, Skin.BACKDROP_LOW, Skin.CARD_STROKE, 1f, 14f));
        ui.baseTab = tab(a, "底色");
        ui.accentTab = tab(a, "选择色");
        ui.textTab = tab(a, "文字颜色");
        segments.addView(ui.baseTab, new LinearLayout.LayoutParams(0, a.dp(SEGMENT_INNER_DP), 1f));
        LinearLayout.LayoutParams accentTabLp =
                new LinearLayout.LayoutParams(0, a.dp(SEGMENT_INNER_DP), 1f);
        accentTabLp.leftMargin = a.dp(2);
        segments.addView(ui.accentTab, accentTabLp);
        LinearLayout.LayoutParams textTabLp =
                new LinearLayout.LayoutParams(0, a.dp(SEGMENT_INNER_DP), 1f);
        textTabLp.leftMargin = a.dp(2);
        segments.addView(ui.textTab, textTabLp);
        panel.addView(segments, new LinearLayout.LayoutParams(MATCH, a.dp(SEGMENT_HEIGHT_DP)));
        a.gap(panel, 14);

        // ---------- ③ 固定预览卡（RGB 调整时始终可见） ----------
        LinearLayout preview = a.row();
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(a.dp(12), 0, a.dp(12), 0);
        preview.setBackground(shape(a, Skin.CARD_BASE, Skin.CARD_STROKE, 1f, 16f));
        ui.swatch = new View(a);
        preview.addView(
                ui.swatch, new LinearLayout.LayoutParams(a.dp(PREVIEW_SWATCH_DP), a.dp(PREVIEW_SWATCH_DP)));
        LinearLayout previewText = a.vertical();
        previewText.setPadding(a.dp(12), 0, a.dp(8), 0);
        ui.previewTitle = label(a, "", 13f, Skin.TEXT_PRIMARY_T, true);
        ui.previewSub = label(a, "", 11f, Skin.TEXT_SECONDARY_T, false);
        ui.previewSub.setSingleLine(true);
        ui.previewSub.setEllipsize(TextUtils.TruncateAt.END);
        previewText.addView(ui.previewTitle, new LinearLayout.LayoutParams(WRAP, WRAP));
        previewText.addView(ui.previewSub, new LinearLayout.LayoutParams(MATCH, WRAP));
        preview.addView(previewText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ui.previewBadge = label(a, "", 11f, Skin.TEXT_PRIMARY_T, true);
        ui.previewBadge.setGravity(Gravity.CENTER);
        ui.previewBadge.setSingleLine(true);
        preview.addView(ui.previewBadge, new LinearLayout.LayoutParams(a.dp(58), a.dp(26)));
        panel.addView(preview, new LinearLayout.LayoutParams(MATCH, a.dp(PREVIEW_HEIGHT_DP)));
        a.gap(panel, 14);

        // ---------- ④ 唯一滚动区 ----------
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout body = a.vertical();
        body.setPadding(0, a.dp(2), 0, a.dp(6));
        scroll.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        panel.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        ui.paletteBox = a.vertical();
        body.addView(sectionLabel(a, "预设色板"), new LinearLayout.LayoutParams(MATCH, WRAP));
        body.addView(ui.paletteBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        a.gap(body, 18);

        ui.recRow = a.row();
        body.addView(sectionLabel(a, "推荐组合"), new LinearLayout.LayoutParams(MATCH, WRAP));
        body.addView(ui.recRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        a.gap(body, 18);

        body.addView(sectionLabel(a, "RGB 精调"), new LinearLayout.LayoutParams(MATCH, WRAP));
        int progressTint = accentEdge(Skin.BACKDROP_TOP);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            final String channel = "RGB".substring(i, i + 1);
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView channelLabel = label(a, channel, 12f, Skin.TEXT_SECONDARY_T, false);
            channelLabel.setLetterSpacing(.08f);
            channelLabel.setWidth(a.dp(20));
            row.addView(channelLabel, new LinearLayout.LayoutParams(a.dp(20), WRAP));

            final SeekBar bar = new SeekBar(a);
            bar.setMax(255);
            bar.setProgress(ui.rgb[i]);
            bar.setFocusable(true);
            bar.setContentDescription(channel + " 通道，左右键微调 5");
            bar.setSplitTrack(false);
            bar.setProgressTintList(ColorStateList.valueOf(progressTint));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Skin.CARD_STROKE));
            bar.setThumbTintList(ColorStateList.valueOf(Skin.TEXT_PRIMARY_T));
            bar.setOnFocusChangeListener((v, focused) -> bar.setBackground(
                    focused
                            ? shape(a, Color.TRANSPARENT, Skin.FOCUS_RING, 1.5f, 9f)
                            : shape(a, Color.TRANSPARENT, 0, 0f, 9f)));
            bar.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if (ui.painting) return;
                            ui.rgb[index] = progress;
                            ui.refresh.run();
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {}
                    });
            bar.setOnKeyListener((v, keyCode, event) -> {
                if (!a.isTvDevice() || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int delta =
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                ? 5
                                : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -5 : 0;
                if (delta == 0) return false;
                bar.setProgress(Math.max(0, Math.min(255, bar.getProgress() + delta)));
                return true;
            });
            row.addView(bar, new LinearLayout.LayoutParams(0, a.dp(40), 1f));

            TextView value = label(a, String.valueOf(ui.rgb[i]), 13f, Skin.TEXT_PRIMARY_T, false);
            value.setGravity(Gravity.END);
            value.setFontFeatureSettings("tnum");
            row.addView(value, new LinearLayout.LayoutParams(a.dp(38), WRAP));
            ui.bars[i] = bar;
            ui.values[i] = value;
            body.addView(row, new LinearLayout.LayoutParams(MATCH, a.dp(44)));
        }
        a.gap(body, 14);

        body.addView(sectionLabel(a, "HEX 色值"), new LinearLayout.LayoutParams(MATCH, WRAP));
        if (!a.isTvDevice()) {
            LinearLayout hexRow = a.row();
            hexRow.setGravity(Gravity.CENTER_VERTICAL);
            final EditText hex = new EditText(a);
            hex.setSingleLine(true);
            hex.setTypeface(a.typeface());
            hex.setTextSize(13);
            hex.setIncludeFontPadding(false);
            hex.setGravity(Gravity.CENTER_VERTICAL);
            hex.setTextColor(Skin.TEXT_PRIMARY_T);
            hex.setHintTextColor(Skin.TEXT_TERTIARY_T);
            hex.setHint("#RRGGBB");
            hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            hex.setFilters(new InputFilter[] {new InputFilter.LengthFilter(7)});
            hex.setPadding(a.dp(12), 0, a.dp(12), 0);
            hex.setSelectAllOnFocus(true);
            hex.setText(hexOf(ui.rgb));
            hex.setBackground(shape(a, Skin.BACKDROP_LOW, Skin.CARD_STROKE, 1f, 12f));
            hex.setOnFocusChangeListener((v, focused) -> hex.setBackground(
                    shape(
                            a,
                            Skin.BACKDROP_LOW,
                            focused ? Skin.FOCUS_RING : Skin.CARD_STROKE,
                            focused ? 1.5f : 1f,
                            12f)));
            ui.hex = hex;
            hexRow.addView(hex, new LinearLayout.LayoutParams(0, a.dp(44), 1f));

            int applyFill = readableFill(Skin.ACCENT_STRONG);
            int applyInk = readableInk(applyFill);
            final TextView apply = label(a, "应用", 13f, applyInk, true);
            apply.setGravity(Gravity.CENTER);
            apply.setFocusable(true);
            apply.setClickable(true);
            apply.setContentDescription("应用输入的 HEX 颜色");
            apply.setBackground(shape(a, applyFill, 0, 0f, 12f));
            apply.setOnFocusChangeListener((v, focused) -> apply.setBackground(
                    shape(a, applyFill, focused ? applyInk : 0, focused ? 2f : 0f, 12f)));
            final Runnable applyHex = () -> {
                Integer parsed = parseHex(String.valueOf(hex.getText()));
                if (parsed == null) {
                    a.toast("请输入 #RRGGBB，例如 " + hex(Skin.CARD_BASE));
                    return;
                }
                ui.rgb[0] = (parsed >> 16) & 0xff;
                ui.rgb[1] = (parsed >> 8) & 0xff;
                ui.rgb[2] = parsed & 0xff;
                AppLog.i("CardDialog", "tap=hex color=" + hex(parsed));
                if (ui.refresh != null) ui.refresh.run();
            };
            bindConfirm(apply, () -> applyHex.run());
            // 软键盘回车等同「应用」
            hex.setOnEditorActionListener((v, actionId, event) -> {
                applyHex.run();
                return true;
            });
            LinearLayout.LayoutParams applyLp =
                    new LinearLayout.LayoutParams(a.dp(76), a.dp(44));
            applyLp.leftMargin = a.dp(10);
            hexRow.addView(apply, applyLp);
            body.addView(hexRow, new LinearLayout.LayoutParams(MATCH, a.dp(44)));
        } else {
            // TV 无软键盘：HEX 只读展示，调色仍走 RGB 滑条。
            LinearLayout hexRow = a.row();
            hexRow.setGravity(Gravity.CENTER_VERTICAL);
            ui.hexValue = label(a, hexOf(ui.rgb), 13f, Skin.TEXT_PRIMARY_T, false);
            ui.hexValue.setGravity(Gravity.CENTER_VERTICAL);
            ui.hexValue.setFontFeatureSettings("tnum");
            hexRow.addView(ui.hexValue, new LinearLayout.LayoutParams(0, a.dp(44), 1f));
            TextView hint = label(a, "遥控器用 RGB 调整", 11f, Skin.TEXT_TERTIARY_T, false);
            hexRow.addView(hint, new LinearLayout.LayoutParams(WRAP, WRAP));
            body.addView(hexRow, new LinearLayout.LayoutParams(MATCH, a.dp(44)));
        }

        // ---------- ⑤ 底部固定主次按钮（0.3 / 0.7） ----------
        a.gap(panel, 12);
        LinearLayout footer = a.row();
        footer.setGravity(Gravity.CENTER_VERTICAL);
        final TextView cancel = label(a, "取消", 15f, Skin.TEXT_PRIMARY_T, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setBackground(shape(a, Skin.CARD_BASE, Skin.CARD_STROKE, 1f, 14f));
        cancel.setOnFocusChangeListener((v, focused) -> {
            cancel.setBackground(
                    shape(
                            a,
                            Skin.CARD_BASE,
                            focused ? Skin.FOCUS_RING : Skin.CARD_STROKE,
                            focused ? 2f : 1f,
                            14f));
            cancel.setTextColor(Skin.TEXT_PRIMARY_T);
        });
        final int primaryFill = readableFill(Skin.ACCENT_STRONG);
        final int primaryInk = readableInk(primaryFill);
        final TextView primary = label(a, "应用底色", 15f, primaryInk, true);
        primary.setGravity(Gravity.CENTER);
        primary.setFocusable(true);
        primary.setClickable(true);
        primary.setContentDescription("保存当前颜色并应用");
        primary.setBackground(shape(a, primaryFill, 0, 0f, 14f));
        primary.setOnFocusChangeListener((v, focused) -> primary.setBackground(
                shape(a, primaryFill, focused ? primaryInk : 0, focused ? 2f : 0f, 14f)));
        footer.addView(cancel, new LinearLayout.LayoutParams(0, a.dp(BUTTON_HEIGHT_DP), .3f));
        LinearLayout.LayoutParams primaryLp =
                new LinearLayout.LayoutParams(0, a.dp(BUTTON_HEIGHT_DP), .7f);
        primaryLp.leftMargin = a.dp(10);
        footer.addView(primary, primaryLp);
        panel.addView(footer, new LinearLayout.LayoutParams(MATCH, a.dp(BUTTON_HEIGHT_DP)));
        ui.primary = primary;

        // ---------- 状态与交互：点击 + 遥控确认键双通道 ----------
        ui.refresh = () -> refreshAll(a, ui);
        bindConfirm(ui.baseTab, () -> switchMode(a, ui, BASE_MODE));
        bindConfirm(ui.accentTab, () -> switchMode(a, ui, ACCENT_MODE));
        bindConfirm(ui.textTab, () -> switchMode(a, ui, TEXT_MODE));
        final Runnable syncTabs = () -> updateTabs(a, ui);
        ui.baseTab.setOnFocusChangeListener((v, focused) -> syncTabs.run());
        ui.accentTab.setOnFocusChangeListener((v, focused) -> syncTabs.run());
        ui.textTab.setOnFocusChangeListener((v, focused) -> syncTabs.run());
        bindConfirm(
                cancel,
                () -> {
                    AppLog.i("CardDialog", "tap=cancel");
                    dialog.dismiss();
                });
        bindConfirm(
                primary,
                () -> {
                    AppLog.i(
                            "CardDialog",
                            "tap=primary tab="
                                    + modeName(ui.mode)
                                    + " color="
                                    + hexOf(ui.rgb)
                                    + " customFlag="
                                    + a.prefs.getBoolean("card_accent_custom", false));
                    if (ui.mode == BASE_MODE) {
                        saveBase(a, dialog, ui, ui.rgb);
                    } else if (ui.mode == ACCENT_MODE) {
                        saveAccent(a, dialog, ui, ui.rgb);
                    } else {
                        saveText(a, dialog, ui, ui.rgb);
                    }
                });
        bindConfirm(
                reset,
                () -> {
                    AppLog.i(
                            "CardDialog",
                            "tap=reset tab=" + modeName(ui.mode));
                    if (ui.mode == BASE_MODE) {
                        resetBase(a, dialog, ui);
                    } else if (ui.mode == ACCENT_MODE) {
                        resetAccent(a, dialog, ui);
                    } else {
                        resetText(a, dialog, ui);
                    }
                });

        rebuildPalette(a, ui);
        rebuildRecommended(a, ui);
        updateTabs(a, ui);
        refreshAll(a, ui);

        // ---------- 窗口：show 之前就定好尺寸，首帧即可触摸/聚焦 ----------
        final int dialogWidth = Math.min(
                a.dp(a.isTvDevice() ? DIALOG_MAX_WIDTH_TV_DP : DIALOG_MAX_WIDTH_DP),
                a.windowWidth() - a.dp(DIALOG_MIN_MARGIN_DP));
        final int dialogHeight = Math.min(
                a.windowHeight() - a.dp(DIALOG_MIN_MARGIN_DP), a.dp(DIALOG_MAX_HEIGHT_DP));
        applyWindow(a, dialog, dialogWidth, dialogHeight);

        final View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
        // 焦点兜底：任何方向/OK 键落下时若弹窗内无焦点（例如切 Tab 重建色板后），先复位焦点。
        dialog.setOnKeyListener(
                (d, keyCode, event) -> {
                    boolean nav = keyCode == KeyEvent.KEYCODE_DPAD_UP
                            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER;
                    if (!nav || decor == null) return false;
                    View focus = decor.findFocus();
                    if (focus != null && focus.isShown()) return false;
                    View target = ui.chips.isEmpty() ? primary : ui.chips.get(0);
                    target.requestFocus();
                    AppLog.i(
                            "CardDialog",
                            "key-focus-recover key=" + KeyEvent.keyCodeToString(keyCode));
                    return true;
                });
        dialog.setOnDismissListener(
                dismissed -> {
                    ModalStack.pop(decor);
                    AppLog.i(
                            "CardDialog",
                            "dismiss saved="
                                    + ui.saved
                                    + " stack="
                                    + ModalStack.size()
                                    + " glass_color="
                                    + a.prefs.getString("glass_color", "?")
                                    + " custom=#"
                                    + hex(a.prefs.getInt("glass_custom_color", 0))
                                    + " accent=#"
                                    + hex(a.prefs.getInt("card_accent_color", 0))
                                    + " customFlag="
                                    + a.prefs.getBoolean("card_accent_custom", false));
                });
        dialog.setOnShowListener(
                shown -> {
                    // 1.9.321：注册模态栈——手机遥控注入的 OK/方向键只作用于弹窗，
                    // 否则按键全部落到背景 Activity，表现为「点应用没反应」。
                    ModalStack.push("card-color", decor, dialog::dismiss);
                    applyWindow(a, dialog, dialogWidth, dialogHeight);
                    if (decor == null) return;
                    decor.post(
                            () -> {
                                if (!ui.chips.isEmpty()) {
                                    ui.chips.get(0).requestFocus();
                                } else {
                                    primary.requestFocus();
                                }
                                ModalStack.focusFirst(a, decor);
                                TvNav.auditDialog(a, "card-color", decor);
                                AppLog.i(
                                        "CardDialog",
                                        "shown decor="
                                                + decor.getWidth()
                                                + "x"
                                                + decor.getHeight()
                                                + " primaryShown="
                                                + (primary.isShown()
                                                        && primary.getHeight() > 0)
                                                + " primaryTop="
                                                + primary.getTop()
                                                + " stack="
                                                + ModalStack.size());
                            });
                });
        dialog.show();
        AppLog.i(
                "CardDialog",
                "show mode=base preset="
                        + basePreset
                        + " base="
                        + hex(MainActivity.cardPresetBase(basePreset, a.prefs))
                        + " accent="
                        + hex(Skin.ACCENT_STRONG)
                        + " alpha="
                        + ui.alphaPct
                        + "% dialog="
                        + dialogWidth
                        + "x"
                        + dialogHeight
                        + " tv="
                        + a.isTvDevice());
    }

    // ------------------------------------------------------------------
    // 视图刷新
    // ------------------------------------------------------------------

    private static void refreshAll(MainActivity a, Ui ui) {
        if (ui.painting) return;
        ui.painting = true;
        try {
            int color = opaque(toRgb(ui.rgb));
            int fill = readableFill(color);
            int ink = readableInk(fill);

            // 预览卡：底色=卡片色块 / 选择色=选中 chip / 文字色=文字样张
            ui.swatch.setBackground(shape(a, color, edgeInk(color), 1f, 12f));
            ui.previewTitle.setTextColor(Skin.TEXT_PRIMARY_T);
            if (ui.mode == BASE_MODE) {
                ui.previewTitle.setText("卡片底色");
                // 1.9.325：命中 8 套预设时显示「中文名 · 风格说明」
                String presetTag = presetTagOf(ui.rgb);
                ui.previewSub.setText(
                        hexOf(ui.rgb)
                                + " · "
                                + (presetTag != null
                                        ? presetTag
                                        : "不透明度 " + ui.alphaPct + "%"));
                ui.previewBadge.setText("Aa");
            } else if (ui.mode == ACCENT_MODE) {
                ui.previewTitle.setText("选择色");
                ui.previewSub.setText(
                        hexOf(ui.rgb)
                                + " · 对比 "
                                + String.format(
                                        java.util.Locale.ROOT, "%.1f:1", contrast(ink, fill)));
                ui.previewBadge.setText("选中");
            } else {
                // 1.9.326：文字颜色预览 = 卡底色上的文字样张
                ui.previewTitle.setText("卡片文字 · 蓝色鲸落");
                ui.previewTitle.setTextColor(color);
                ui.previewSub.setText(
                        hexOf(ui.rgb)
                                + " · 对比 "
                                + String.format(
                                        java.util.Locale.ROOT,
                                        "%.1f:1",
                                        contrast(color, Skin.cardSurface())));
                ui.previewBadge.setText("Aa");
                ui.previewBadge.setTextColor(color);
                ui.previewBadge.setBackground(
                        shape(
                                a,
                                Skin.cardSurface(),
                                withAlpha(color, 0x66),
                                1f,
                                999f));
            }
            if (ui.mode != TEXT_MODE) {
                ui.previewBadge.setTextColor(ink);
                ui.previewBadge.setBackground(
                        shape(a, fill, withAlpha(ink, 0x4D), 1f, 999f));
            }

            for (int i = 0; i < 3; i++) {
                if (ui.bars[i] != null && ui.bars[i].getProgress() != ui.rgb[i]) {
                    ui.bars[i].setProgress(ui.rgb[i]);
                }
                if (ui.values[i] != null) {
                    ui.values[i].setText(String.valueOf(ui.rgb[i]));
                }
            }
            if (ui.hex != null) {
                String text = hexOf(ui.rgb);
                if (!text.contentEquals(ui.hex.getText())) ui.hex.setText(text);
            }
            if (ui.hexValue != null) {
                ui.hexValue.setText(hexOf(ui.rgb));
            }
            for (SwatchView chip : ui.chips) chip.setChecked(chip.matches(ui.rgb));
        } finally {
            ui.painting = false;
        }
    }

    private static void switchMode(MainActivity a, Ui ui, int target) {
        if (ui.mode == target) return;
        copy(ui.rgb, ui.modeRgb[ui.mode]);
        ui.mode = target;
        copy(ui.modeRgb[target], ui.rgb);
        ui.primary.setText(APPLY_LABELS[target]);
        AppLog.i("CardDialog", "tab=" + modeName(target) + " color=" + hexOf(ui.rgb));
        rebuildPalette(a, ui);
        rebuildRecommended(a, ui);
        updateTabs(a, ui);
        refreshAll(a, ui);
    }

    private static void rebuildPalette(MainActivity a, Ui ui) {
        ui.chips.clear();
        ui.paletteBox.removeAllViews();
        int[] colors = paletteOf(ui.mode);
        String[] names = paletteNamesOf(ui.mode);
        final int columns = paletteColumnsOf(ui.mode);
        final boolean base = ui.mode == BASE_MODE;
        for (int start = 0; start < colors.length; start += columns) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER);
            for (int i = start; i < Math.min(start + columns, colors.length); i++) {
                final int color = colors[i];
                // 1.9.325：预设色块 contentDescription 带中文名 + 风格说明
                final SwatchView chip = new SwatchView(
                        a,
                        color,
                        base
                                ? names[i] + "（" + Skin.PRESET_TAGS[i] + "）"
                                : names[i]);                bindConfirm(
                        chip,
                        () -> {
                            ui.rgb[0] = (color >> 16) & 0xff;
                            ui.rgb[1] = (color >> 8) & 0xff;
                            ui.rgb[2] = color & 0xff;
                            AppLog.i(
                                    "CardDialog",
                                    "tap=swatch tab="
                                            + (ui.mode == BASE_MODE ? "base" : "accent")
                                            + " color="
                                            + hex(color));
                            if (ui.refresh != null) ui.refresh.run();
                        });
                chip.setOnFocusChangeListener((v, focused) -> chip.invalidate());
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        a.dp(SWATCH_SIZE_DP), a.dp(SWATCH_SIZE_DP));
                if (i > start) chipLp.leftMargin = a.dp(SWATCH_GAP_DP);
                row.addView(chip, chipLp);
                chip.setChecked(chip.matches(ui.rgb));
                ui.chips.add(chip);
            }
            LinearLayout.LayoutParams rowLp =
                    new LinearLayout.LayoutParams(MATCH, a.dp(SWATCH_SIZE_DP));
            rowLp.bottomMargin = a.dp(SWATCH_GAP_DP);
            ui.paletteBox.addView(row, rowLp);
        }
    }

    private static void rebuildRecommended(MainActivity a, Ui ui) {
        ui.recRow.removeAllViews();
        int[] colors = recommendedOf(ui.mode);
        String[] names = recommendedNamesOf(ui.mode);
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            int fill = readableFill(color);
            int ink = readableInk(fill);
            final TextView pill = label(a, names[i], 12f, ink, true);
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setEllipsize(TextUtils.TruncateAt.END);
            pill.setContentDescription(names[i] + " " + hex(color));
            pill.setFocusable(true);
            pill.setClickable(true);
            pill.setBackground(shape(a, fill, 0, 0f, 999f));
            pill.setOnFocusChangeListener((v, focused) -> pill.setBackground(
                    shape(a, fill, focused ? withAlpha(ink, 0xFF) : 0, focused ? 2f : 0f, 999f)));
            bindConfirm(
                    pill,
                    () -> {
                        ui.rgb[0] = (color >> 16) & 0xff;
                        ui.rgb[1] = (color >> 8) & 0xff;
                        ui.rgb[2] = color & 0xff;
                        AppLog.i(
                                "CardDialog",
                                "tap=recommend tab="
                                        + (ui.mode == BASE_MODE ? "base" : "accent")
                                        + " color="
                                        + hex(color));
                        if (ui.refresh != null) ui.refresh.run();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(44), 1f);
            if (i > 0) lp.leftMargin = a.dp(10);
            ui.recRow.addView(pill, lp);
        }
    }

    private static void updateTabs(MainActivity a, Ui ui) {
        int selectedFill = readableFill(Skin.ACCENT_SOFT_T);
        int selectedInk = readableInk(selectedFill);
        styleSegment(a, ui.baseTab, ui.mode == BASE_MODE, selectedFill, selectedInk);
        styleSegment(a, ui.accentTab, ui.mode == ACCENT_MODE, selectedFill, selectedInk);
        styleSegment(a, ui.textTab, ui.mode == TEXT_MODE, selectedFill, selectedInk);
    }

    /** 分段模式名（日志/按钮文案用）。 */
    private static String modeName(int mode) {
        if (mode == ACCENT_MODE) return "accent";
        if (mode == TEXT_MODE) return "text";
        return "base";
    }

    private static void styleSegment(
            MainActivity a, TextView tab, boolean selected, int fill, int ink) {
        if (selected) {
            tab.setBackground(
                    shape(a, fill, tab.hasFocus() ? ink : 0, tab.hasFocus() ? 2f : 0f, 11f));
            tab.setTextColor(ink);
            tab.setTypeface(null, Typeface.BOLD);
        } else {
            tab.setBackground(
                    tab.hasFocus()
                            ? shape(a, Color.TRANSPARENT, Skin.FOCUS_RING, 1.5f, 11f)
                            : shape(a, Color.TRANSPARENT, 0, 0f, 11f));
            tab.setTextColor(Skin.TEXT_SECONDARY_T);
            tab.setTypeface(null, Typeface.NORMAL);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static TextView label(
            MainActivity a, String value, float size, int color, boolean bold) {
        TextView view = a.text(value, size, color);
        // a.text 会走 userTextColor 全局染字，这里固定为 token 色，保证弹窗对比度可控。
        view.setTextColor(color);
        view.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private static TextView sectionLabel(MainActivity a, String value) {
        TextView view = label(a, value, 11f, Skin.TEXT_TERTIARY_T, false);
        view.setLetterSpacing(.10f);
        view.setPadding(0, 0, 0, a.dp(8));
        return view;
    }

    private static TextView tab(MainActivity a, String value) {
        TextView view = label(a, value, 13f, Skin.TEXT_SECONDARY_T, false);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setContentDescription(value + " 分段");
        return view;
    }

    private static GradientDrawable shape(
            MainActivity a, int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        float density = a.getResources().getDisplayMetrics().density;
        drawable.setCornerRadius(radiusDp * density);
        if (strokeDp > 0f) {
            drawable.setStroke(Math.max(1, Math.round(strokeDp * density)), strokeColor);
        }
        return drawable;
    }

    private static void applyWindow(MainActivity a, Dialog dialog, int width, int height) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(width, height);
        window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = .62f;
        window.setAttributes(params);
        if (a.isTvDevice()) {
            window.getDecorView().setSystemUiVisibility(5894);
        }
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    /**
     * 绑定「点击 + 遥控确认键」双通道。
     *
     * <p>电视遥控 / 手机遥控注入的 OK 键在各机型上并不总会被 View 默认转成 click；这里显式
     * 消费确认键并在抬起时 {@link View#performClick()}，保证应用 / 恢复默认 / 色块 / 分段
     * 在任何输入通道下都一定触发。
     */
    private static void bindConfirm(View view, Runnable action) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(v -> action.run());
        view.setOnKeyListener(
                (v, keyCode, event) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                            && keyCode != KeyEvent.KEYCODE_ENTER
                            && keyCode != KeyEvent.KEYCODE_SPACE) {
                        return false;
                    }
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        v.setPressed(true);
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        v.setPressed(false);
                        if (!event.isCanceled()) v.performClick();
                    }
                    return true;
                });
    }

    private static void copy(int[] from, int[] to) {
        if (from == null || to == null || from.length < 3 || to.length < 3) return;
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }

    // ------------------------------------------------------------------
    // 保存 / 恢复默认（取消不写任何 prefs）
    // ------------------------------------------------------------------

    /** 只改卡片底色：写 custom + 精确 HEX，不走预设吸附；透明度沿用当前值。 */
    private static void saveBase(MainActivity a, Dialog dialog, Ui ui, int[] rgb) {
        ui.saved = true;
        int color = opaque(toRgb(rgb)) & 0xffffff;
        int accentBefore = opaque(a.prefs.getInt("card_accent_color", Skin.ACCENT_DEFAULT));
        AppLog.i(
                "CardDialog",
                "save enter tab=base color=#"
                        + hex(color)
                        + " before glass_color="
                        + a.prefs.getString("glass_color", "?")
                        + " alpha="
                        + a.prefs.getInt("glass_alpha", 40)
                        + " accent=#"
                        + hex(accentBefore));
        dialog.dismiss();
        a.prefs
                .edit()
                .putString("glass_color", "custom")
                .putInt("glass_custom_color", color)
                .apply();
        AppLog.i(
                "CardDialog",
                "saved prefs glass_color="
                        + a.prefs.getString("glass_color", "?")
                        + " custom=#"
                        + hex(a.prefs.getInt("glass_custom_color", 0))
                        + " alpha="
                        + a.prefs.getInt("glass_alpha", 40)
                        + " accent=#"
                        + hex(a.prefs.getInt("card_accent_color", 0))
                        + " customFlag="
                        + a.prefs.getBoolean("card_accent_custom", false));
        a.applyGlassTint();
        // applyGlassTint 会迁移/吸附选中色；保存底色不应改变选中色，按原值写回。
        a.prefs.edit().putInt("card_accent_color", accentBefore).apply();
        setAccentTokens(accentBefore);
        String preset = MainActivity.migrateCardPreset(a.prefs);
        int applied = MainActivity.cardPresetBase(preset, a.prefs) & 0xffffff;
        if (applied != color) {
            AppLog.w(
                    "CardDialog",
                    "BASE NOT APPLIED expected=#"
                            + hex(color)
                            + " applied=#"
                            + hex(applied)
                            + " preset="
                            + preset
                            + "（MainActivity.applyGlassTint/cardBg custom 分支问题）",
                    null);
        } else {
            AppLog.i(
                    "CardDialog",
                    "after apply tab=base preset="
                            + preset
                            + " cardBase=#"
                            + hex(applied)
                            + " cardFill=#"
                            + Integer.toHexString(Skin.cardFill));
        }
        a.toast("卡片底色已应用 " + hex(color));
        AppLog.i("CardDialog", "recreate tab=base color=#" + hex(color));
        a.recreate();
    }

    /** 只改选中色：写精确 HEX + custom 标记，同步 4 个 Skin token。 */
    private static void saveAccent(MainActivity a, Dialog dialog, Ui ui, int[] rgb) {
        ui.saved = true;
        int color = opaque(toRgb(rgb));
        AppLog.i(
                "CardDialog",
                "save enter tab=accent color=#"
                        + hex(color)
                        + " before accent=#"
                        + hex(a.prefs.getInt("card_accent_color", 0))
                        + " customFlag="
                        + a.prefs.getBoolean("card_accent_custom", false));
        dialog.dismiss();
        a.prefs
                .edit()
                .putInt("card_accent_color", color)
                .putBoolean("card_accent_custom", true)
                .commit();
        AppLog.i(
                "CardDialog",
                "saved prefs accent=#"
                        + hex(a.prefs.getInt("card_accent_color", 0))
                        + " customFlag="
                        + a.prefs.getBoolean("card_accent_custom", false)
                        + " glass_color="
                        + a.prefs.getString("glass_color", "?"));
        a.applyGlassTint();
        setAccentTokens(color);
        AppLog.i(
                "Accent",
                "apply=#"
                        + hex(color)
                        + " skin=#"
                        + hex(Skin.ACCENT_STRONG)
                        + " mid=#"
                        + hex(Skin.ACCENT_MID)
                        + " soft=#"
                        + hex(Skin.ACCENT_SOFT_T)
                        + " flag="
                        + a.prefs.getBoolean("card_accent_custom", false));
        if ((Skin.ACCENT_STRONG & 0xffffff) != (color & 0xffffff)) {
            AppLog.w(
                    "CardDialog",
                    "ACCENT NOT APPLIED expected=#"
                            + hex(color)
                            + " skin=#"
                            + Integer.toHexString(Skin.ACCENT_STRONG)
                            + "（MainActivity.applyGlassTint custom 分支问题）",
                    null);
        }
        a.toast("选中色已应用 " + hex(color));
        AppLog.i("CardDialog", "recreate tab=accent color=#" + hex(color));
        a.recreate();
    }

    /** 只重置当前分段：底色 → sakura-night + 40%。 */
    private static void resetBase(MainActivity a, Dialog dialog, Ui ui) {
        ui.saved = true;
        int accentBefore = opaque(a.prefs.getInt("card_accent_color", Skin.ACCENT_DEFAULT));
        AppLog.i("CardDialog", "reset tab=base → sakura-night + 40%");
        dialog.dismiss();
        a.prefs
                .edit()
                .putString("glass_color", "sakura-night")
                .putInt("glass_alpha", 40)
                .apply();
        a.applyGlassTint();
        a.prefs.edit().putInt("card_accent_color", accentBefore).apply();
        setAccentTokens(accentBefore);
        a.toast("已恢复默认卡片底色（樱夜 · 40%）");
        a.recreate();
    }

    /**
     * 只重置当前分段：选中色 → #FF8FB1。
     *
     * <p>写 custom=true 而不是 false：MainActivity.migrateAccent() 返回 24 位 RGB（alpha=0），
     * 走吸附分支会把全局强调色写成透明；custom=true 保留精确 #FF8FB1，视觉上即默认樱花粉。
     */
    private static void resetAccent(MainActivity a, Dialog dialog, Ui ui) {
        ui.saved = true;
        int color = Skin.ACCENT_DEFAULT;
        AppLog.i("CardDialog", "reset tab=accent → " + hex(color));
        dialog.dismiss();
        a.prefs
                .edit()
                .putInt("card_accent_color", color)
                .putBoolean("card_accent_custom", true)
                .apply();
        a.applyGlassTint();
        setAccentTokens(color);
        a.toast("已恢复默认选中色 " + hex(color));
        a.recreate();
    }

    /**
     * 1.9.326：第三段「文字颜色」——写回原有字体色 prefs（TEXT_COLOR_CUSTOM / TEXT_COLOR）。
     * 与卡片底对比 <3:1 时提示，但仍按用户选择保存。
     */
    private static void saveText(MainActivity a, Dialog dialog, Ui ui, int[] rgb) {
        ui.saved = true;
        int color = opaque(toRgb(rgb));
        double ratio = contrast(color, Skin.cardSurface());
        AppLog.i(
                "CardDialog",
                "save enter tab=text color=#"
                        + hex(color)
                        + " ratio="
                        + String.format(java.util.Locale.ROOT, "%.1f", ratio));
        dialog.dismiss();
        a.prefs
                .edit()
                .putBoolean(Prefs.TEXT_COLOR_CUSTOM, true)
                .putInt(Prefs.TEXT_COLOR, color)
                .commit();
        AppLog.i(
                "TextColor",
                "apply custom="
                        + a.prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false)
                        + " pref=#"
                        + hex(color)
                        + " read=#"
                        + hex(a.prefs.getInt(Prefs.TEXT_COLOR, 0))
                        + " default=#"
                        + hex(Prefs.DEFAULT_TEXT_COLOR));
        if (ratio < 3.0) {
            a.toast("该文字色与卡片底对比不足 3:1，部分文字可能不易看清");
        }
        a.toast("卡片文字色已应用 " + hex(color));
        a.recreate();
    }

    /** 1.9.326：文字色恢复默认 #FFE3EC。 */
    private static void resetText(MainActivity a, Dialog dialog, Ui ui) {
        ui.saved = true;
        int color = Skin.CARD_TEXT_SAKURA;
        AppLog.i("CardDialog", "reset tab=text → " + hex(color));
        dialog.dismiss();
        a.prefs
                .edit()
                .putBoolean(Prefs.TEXT_COLOR_CUSTOM, true)
                .putInt(Prefs.TEXT_COLOR, color)
                .apply();
        a.toast("已恢复默认文字色 " + hex(color));
        a.recreate();
    }

    private static void setAccentTokens(int color) {
        int fill = opaque(color);
        Skin.cardAccent = fill;
        Skin.ACCENT_STRONG = fill;
        Skin.ACCENT_MID = opaque(MainActivity.lighten(fill, 0.15f));
        Skin.ACCENT_SOFT_T = opaque(MainActivity.lighten(fill, 0.30f));
    }

    // ------------------------------------------------------------------
    // 自绘色块：44×44 / 圆角 12 / 选中 2dp 高亮环 + 中心勾
    // ------------------------------------------------------------------

    private static final class SwatchView extends View {
        private final float radiusPx;
        private final float ringPx;
        private final float hairPx;
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final RectF box = new RectF();
        private final int swatchColor;
        private boolean checked;

        SwatchView(MainActivity a, int color, String name) {
            super(a);
            swatchColor = opaque(color);
            radiusPx = a.dp(SWATCH_RADIUS_DP);
            ringPx = a.dp(2);
            hairPx = Math.max(1f, a.dp(1) * .75f);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setStrokeJoin(Paint.Join.ROUND);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setContentDescription(name + " " + hex(swatchColor));
            setBackground(null);
        }

        boolean matches(int[] rgb) {
            return (swatchColor & 0xffffff) == (toRgb(rgb) & 0xffffff);
        }

        void setChecked(boolean value) {
            if (checked == value) return;
            checked = value;
            invalidate();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            float w = width;
            float h = height;
            float inset = ringPx / 2f;
            box.set(inset, inset, w - inset, h - inset);

            // 填充
            face.setStyle(Paint.Style.FILL);
            face.setColor(swatchColor);
            canvas.drawRoundRect(box, radiusPx, radiusPx, face);

            // 常驻 1dp 对比描边：深色色块在深色弹窗上也不会"看不见"
            int ink = readableInk(swatchColor);
            edge.setStrokeWidth(hairPx);
            edge.setColor(withAlpha(ink, 0x66));
            canvas.drawRoundRect(box, radiusPx, radiusPx, edge);

            // 选中/焦点：2dp 高亮环
            if (checked || isFocused()) {
                edge.setStrokeWidth(checked ? ringPx : ringPx * 1.25f);
                edge.setColor(ink);
                canvas.drawRoundRect(box, radiusPx, radiusPx, edge);
            }

            // 选中：中心勾
            if (checked) {
                edge.setStrokeWidth(ringPx * 1.2f);
                edge.setColor(ink);
                check.reset();
                check.moveTo(w * .31f, h * .53f);
                check.lineTo(w * .44f, h * .66f);
                check.lineTo(w * .70f, h * .37f);
                canvas.drawPath(check, edge);
            }

            // 按下反馈
            if (isPressed()) {
                scrim.setStyle(Paint.Style.FILL);
                scrim.setColor(withAlpha(ink, 0x2E));
                canvas.drawRoundRect(box, radiusPx, radiusPx, scrim);
            }
        }
    }

    /** 一次 show() 的可变 UI 状态，供 lambda 共享。 */
    private static final class Ui {
        final int[] rgb = new int[3];
        final int[][] modeRgb = new int[TAB_COUNT][];
        final List<SwatchView> chips = new ArrayList<>();
        final SeekBar[] bars = new SeekBar[3];
        final TextView[] values = new TextView[3];
        int mode = BASE_MODE;
        int alphaPct = 40;
        boolean painting;
        boolean saved;
        LinearLayout paletteBox;
        LinearLayout recRow;
        TextView baseTab;
        TextView accentTab;
        TextView textTab;
        TextView primary;
        View swatch;
        TextView previewTitle;
        TextView previewSub;
        TextView previewBadge;
        EditText hex;
        TextView hexValue;
        Runnable refresh;
    }
}
