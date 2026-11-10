package com.whalefall.movie;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 1.9.24 字体样式选择：6 种 Android 内置 Typeface，实时预览，保存后 recreate 全量生效。
 */
final class FontStyleDialog {
    private FontStyleDialog() {}

    static final String[] NAMES = {
        "sans-serif",
        "sans-serif-light",
        "sans-serif-medium",
        "serif",
        "sans-serif-condensed",
        "monospace"
    };
    static final String[] LABELS = {"系统默认", "细体", "中黑", "衬线", "紧缩", "等宽"};

    static void show(final MainActivity a) {
        final String current = a.fontStyleName();
        LinearLayout box = a.vertical();
        box.setPadding(0, a.dp(4), 0, a.dp(4));
        for (int i = 0; i < NAMES.length; i++) {
            final String name = NAMES[i];
            boolean active = name.equals(current);
            LinearLayout row = a.vertical();
            row.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            row.setBackground(
                    MobileDetailScreen.glassBg(
                            a, active ? 0xcc1d3350 : 0xcc16283f, active ? UiTokens.ACCENT : 0x33ffffff, 14));
            row.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(row);
            TextView label =
                    a.text(LABELS[i] + (active ? " · 当前" : ""), 12, active ? UiTokens.ACCENT : UiTokens.TEXT_SECONDARY);
            row.addView(label);
            TextView sample =
                    a.text("鲸落 WhaleFall 字体样例 0123", 16, UiTokens.TEXT_PRIMARY);
            sample.setGravity(Gravity.START);
            try {
                sample.setTypeface(Typeface.create(name, Typeface.NORMAL));
            } catch (Throwable ignored) {
            }
            sample.setPadding(0, a.dp(3), 0, 0);
            row.addView(sample);
            row.setOnClickListener(v -> save(a, name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(6);
            box.addView(row, lp);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box, new ScrollView.LayoutParams(-1, -2));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, a.dp(460)));
        new AppDialog.Builder(a)
                .setTitle("字体样式")
                .setMessage("选择后立即预览并保存，返回设置即全量生效。")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .show();
    }

    static void save(MainActivity a, String name) {
        a.prefs.edit().putString(Prefs.FONT_STYLE, name).apply();
        AppLog.i("Font", "style=" + name);
        a.toast("字体样式已保存");
        a.recreate();
    }
}
