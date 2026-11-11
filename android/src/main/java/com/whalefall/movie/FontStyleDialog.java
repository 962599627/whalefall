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
 boolean tv = a.isTvDevice();
 LinearLayout box = a.vertical();
 box.setPadding(0, a.dp(4), 0, a.dp(8));
 for (int i = 0; i < NAMES.length; i++) {
 final String name = NAMES[i];
 boolean active = name.equals(current);
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
 row.setMinimumHeight(a.dp(tv ? 64 : 56));
 android.graphics.drawable.GradientDrawable rowBg =
 new android.graphics.drawable.GradientDrawable();
 rowBg.setColor(active ? 0x1aff8fb1 : 0x14ffffff);
 rowBg.setCornerRadius(a.dp(14));
 rowBg.setStroke(a.dp(active ? 2 : 1), active ? UiTokens.ACCENT : 0x33ffffff);
 row.setBackground(rowBg);
 row.setFocusable(true);
 MobileDetailScreen.bindTouchFeedback(row);
 LinearLayout texts = a.vertical();
 TextView label =
 a.text(
 LABELS[i],
 tv ? 15f : 13f,
 active ? UiTokens.ACCENT : UiTokens.TEXT_SECONDARY);
 texts.addView(label);
 TextView sample =
 a.text(
 "鲸落 WhaleFall 字体样例 0123",
 tv ? 22f : 18f,
 UiTokens.TEXT_PRIMARY);
 sample.setGravity(Gravity.START);
 // 1.9.25：显式双参 typeface，避免被 a.text 全局字体覆盖
 try {
 sample.setTypeface(Typeface.create(name, Typeface.NORMAL), Typeface.NORMAL);
 } catch (Throwable ignored) {
 }
 sample.setPadding(0, a.dp(4), 0, 0);
 texts.addView(sample);
 TextView second =
 a.text("次要文字示例 Secondary 0123", tv ? 15f : 13f, UiTokens.TEXT_TERTIARY);
 texts.addView(second);
 row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
 if (active) {
 TextView check = a.text("✓ 当前", tv ? 15f : 13f, UiTokens.ACCENT);
 check.setPadding(a.dp(8), 0, 0, 0);
 row.addView(check);
 }
 row.setOnClickListener(v -> save(a, name));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.bottomMargin = a.dp(10);
 box.addView(row, lp);
 }
 // 1.9.27：去掉固定 460dp 内层滚动，交由 AppDialog 单层滚动
 ScrollView scroll = new ScrollView(a);
 scroll.addView(box, new ScrollView.LayoutParams(-1, -2));
 new AppDialog.Builder(a)
 .setTitle("字体样式")
 .setMessage("点击即保存并生效（界面会重启一次）。")
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
