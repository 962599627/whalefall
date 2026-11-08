package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

/**
 * 首页底部常驻筛选摘要条（44dp 玻璃胶囊）。
 *
 * <p>未选条件时是「类型 / 地区 / 年份」占位入口；已选条件时最多展示 2 个樱花粉条件 chip，
 * 超出部分折叠成「+N」。整条点击打开 {@link VideoFilterDialog} 底部浮层。
 *
 * <p>视觉与电视台直播页胶囊一致：44dp 高、999 圆角、玻璃 #1AFFFFFF + #33FFFFFF 描边、
 * 选中色 #FFB3C8，未选中文字 #7F8FA9。
 */
final class FilterSummaryBar {
    static final int HEIGHT_DP = 48;

    private final MainActivity a;
    private final Map<String, String> selections;
    private final Runnable onExpand;
    private final LinearLayout root;
    private final LinearLayout chips;
    private final TvUiKit.FilterGlyph icon;
    private final TvUiKit.ChevronUpGlyph chevron;
    private final TextView placeholder;
    private final TextView countView;

    FilterSummaryBar(MainActivity a, Map<String, String> selections, Runnable onExpand) {
        this.a = a;
        this.selections = selections;
        this.onExpand = onExpand;

        root = a.row();
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(a.dp(14), 0, a.dp(14), 0);
        root.setBackground(MobileDetailScreen.glassBg(a, 0xf20b1220, 0x26ffffff, 16));
        root.setElevation(a.dp(8));
        root.setClickable(true);
        MobileDetailScreen.bindTouchFeedback(root);
        root.setOnClickListener(v -> onExpand.run());

        icon = new TvUiKit.FilterGlyph(a, UiTokens.ACCENT);
        root.addView(icon, new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));

        TextView label = a.text("筛选", 13, UiTokens.TEXT_PRIMARY);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
        labelLp.leftMargin = a.dp(9);
        root.addView(label, labelLp);

        chips = a.row();
        chips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        chipsLp.leftMargin = a.dp(10);
        chipsLp.rightMargin = a.dp(8);
        root.addView(chips, chipsLp);

        placeholder = a.text("类型 / 地区 / 年份", 11.5f, UiTokens.TEXT_TERTIARY);
        placeholder.setSingleLine(true);
        placeholder.setEllipsize(android.text.TextUtils.TruncateAt.END);

        countView = a.text("", 11, UiTokens.ACCENT_SOFT);
        countView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.rightMargin = a.dp(8);
        root.addView(countView, countLp);
        chevron = new TvUiKit.ChevronUpGlyph(a, UiTokens.ACCENT);
        root.addView(chevron, new LinearLayout.LayoutParams(a.dp(14), a.dp(14)));

        refresh(null);
    }

    View view() {
        return root;
    }

    /** selections 变化后调用；definitions 用来把远程筛选的 value 还原成可读 label。 */
    void refresh(List<VideoFilters.Definition> definitions) {
        if (chips == null) return;
        chips.removeAllViews();
        boolean active = !selections.isEmpty();
        icon.setColor(active ? UiTokens.ACCENT : UiTokens.TEXT_SECONDARY);
        int picked = 0;
        for (String v : selections.values())
            if (v != null && !v.isEmpty()) picked += v.split(",").length;
        countView.setText(picked == 0 ? "筛选" : "已选 " + picked);
        countView.setTextColor(picked == 0 ? UiTokens.TEXT_SECONDARY : UiTokens.ACCENT_SOFT);
        if (!active) {
            chips.addView(placeholder);
            return;
        }
        int shown = 0;
        for (Map.Entry<String, String> entry : selections.entrySet()) {
            if (shown >= 2) break;
            TextView chip =
                    a.text(
                            displayLabel(definitions, entry.getKey(), entry.getValue()),
                            11.5f,
                            UiTokens.ACCENT_SOFT);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setMaxWidth(a.dp(92));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(9), 0, a.dp(9), 0);
            chip.setBackground(MobileDetailScreen.glassBg(a, 0x26ff8fb1, 0x4dffb3c8, 999));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(28));
            lp.rightMargin = a.dp(6);
            chips.addView(chip, lp);
            shown++;
        }
        int extra = selections.size() - shown;
        if (extra > 0) {
            TextView more = a.text("+" + extra, 11, UiTokens.TEXT_TERTIARY);
            more.setGravity(Gravity.CENTER);
            more.setPadding(a.dp(8), 0, a.dp(8), 0);
            more.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
            chips.addView(more, new LinearLayout.LayoutParams(-2, a.dp(28)));
        }
    }

    private String displayLabel(
            List<VideoFilters.Definition> definitions, String dimension, String value) {
        if (definitions != null) {
            for (VideoFilters.Definition def : definitions) {
                if (!def.dimension.equals(dimension)) continue;
                for (VideoFilters.Choice choice : def.choices) {
                    if (choice.value.equals(value)) return choice.label;
                }
            }
        }
        if (value != null && value.contains(",")) {
            StringBuilder out = new StringBuilder();
            for (String one : value.split(",")) {
                if (one.isEmpty()) continue;
                if (out.length() > 0) out.append("·");
                String label = null;
                if (definitions != null)
                    for (VideoFilters.Definition def : definitions) {
                        if (!def.dimension.equals(dimension)) continue;
                        for (VideoFilters.Choice choice : def.choices)
                            if (choice.value.equals(one)) label = choice.label;
                    }
                out.append(label != null ? label : one);
            }
            return out.toString();
        }
        return value;
    }
}
