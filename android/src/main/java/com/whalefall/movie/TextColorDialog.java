package com.whalefall.movie;

import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 字体颜色自定义：9 格预设色板（默认 #4CFF80 亮绿 + 8 色）+ RGB 滑条（手机另有 HEX）+ 即时预览。
 *
 * <p>手机与 TV 共用：TV 用方向键在色块/滑条间移动，左右键每次调 5；手机滑条精度 1 并支持 HEX。
 * 设计规范见 docs/DESIGN_REVIEW_1.4.6.md：只改主色，次要 72%、弱文字 66% 自动派生。
 */
final class TextColorDialog {
 /** 9 格色板：第 0 格是默认 #4CFF80 亮绿。 */
 private static final int[] PRESETS = {
 0xff4cff80, 0xff2b313b, 0xffffb3c8,
 0xffff8fb1, 0xff7fb2ff, 0xff28c7c7,
 0xff43d17a, 0xffffd54f, 0xffa97bff
 };
 private static final String[] NAMES = {
 "默认 · 亮绿 #4CFF80", "亮壁纸深灰", "樱粉",
 "樱花粉", "深海蓝", "青色",
 "绿色", "黄色", "紫色"
 };
 /** 推荐组合：只改主色，次要/弱文字按 72%/66% 自动派生。 */
 private static final int[] RECOMMENDED = {0xff4cff80, 0xff2b313b, 0xffffb3c8};
 private static final String[] RECOMMENDED_NAMES = {"亮绿", "亮壁纸深灰", "樱粉"};

 private TextColorDialog() {}

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

 static String hexOf(int[] rgb) {
 return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
 }

 /** 设置页入口摘要文本。 */
 static String summary(MainActivity a) {
 if (!a.prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false)) return "默认亮绿 #4CFF80";
 int color = a.prefs.getInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR);
 return String.format(
 java.util.Locale.ROOT,
 "自定义 #%06X · 次要文字自动降透明度",
 color & 0xffffff);
 }

 static void show(final MainActivity a) {
 show(a, -1);
 }

 /** {@code initialArgb >= 0} 用于对比度二次确认「返回修改」时带回用户刚选的颜色。 */
 static void show(final MainActivity a, final int initialArgb) {
 final int initial =
 initialArgb >= 0
 ? opaque(initialArgb)
 : (a.prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false)
 ? a.prefs.getInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR)
 : Prefs.DEFAULT_TEXT_COLOR);
 final int[] rgb = {(initial >> 16) & 0xff, (initial >> 8) & 0xff, initial & 0xff};
 final boolean[] restoreDefault = {
 initialArgb < 0 && !a.prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false)
 };
 final SeekBar[] bars = new SeekBar[3];
 final TextView[] values = new TextView[3];
 final EditText[] hexField = new EditText[1];
 final Runnable[] refreshAll = new Runnable[1];

 LinearLayout box = a.vertical();

 final TextView preview = a.text("鲸落 · 今晚看什么", 20, MainActivity.WHITE);
 preview.setTextColor(opaque(toRgb(rgb)));
 preview.setGravity(Gravity.CENTER);
 preview.setPadding(a.dp(10), a.dp(14), a.dp(10), a.dp(14));
 preview.setBackground(a.bg(0x33000000, 12));
 preview.setFocusable(true);
 preview.setTag("textcolor-preview");
 box.addView(preview, new LinearLayout.LayoutParams(-1, -2));
 final TextView contrastLabel =
 a.text(
 "对比度（深色底）：" + contrastText(toRgb(rgb)),
 12,
 MainActivity.MUTED);
 contrastLabel.setPadding(a.dp(4), a.dp(4), 0, 0);
 box.addView(contrastLabel);
 TextView hint =
 a.text(
 "亮绿字在浅色壁纸下可能不易看清 · 近黑字在深色界面对比度不足时会二次确认",
 12,
 MainActivity.MUTED);
 hint.setPadding(0, a.dp(4), 0, 0);
 box.addView(hint);
 a.gap(box, 10);

 // ---------------- 9 格 3×3 预设色板 ----------------
 box.addView(a.text("预设色板（点击色块或按 OK 选色）", 13, MainActivity.MUTED));
 final List<Button> chips = new ArrayList<>();
 for (int start = 0; start < PRESETS.length; start += 3) {
 LinearLayout row = a.row();
 for (int i = start; i < Math.min(start + 3, PRESETS.length); i++) {
 final int preset = PRESETS[i];
 final Button chip =
 a.button(
 "",
 "textcolor-preset:" + i,
 () -> {
 restoreDefault[0] = false;
 rgb[0] = (preset >> 16) & 0xff;
 rgb[1] = (preset >> 8) & 0xff;
 rgb[2] = preset & 0xff;
 if (refreshAll[0] != null) refreshAll[0].run();
 });
 chip.setContentDescription(NAMES[i]);
 chip.setBackground(a.bg(preset, 999));
 chip.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 chip,
 focused,
 0xff172742,
 MainActivity.WHITE,
 999,
 MainActivity.WHITE,
 MainActivity.DARK,
 false,
 1.04f));
 LinearLayout.LayoutParams chipLp =
 new LinearLayout.LayoutParams(0, a.dp(44), 1f);
 chipLp.rightMargin = a.dp(6);
 row.addView(chip, chipLp);
 chips.add(chip);
 }
 LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
 rowLp.bottomMargin = a.dp(4);
 box.addView(row, rowLp);
 }
 final View firstFocus = chips.isEmpty() ? preview : chips.get(0);
 a.gap(box, 6);

 // ---------------- 推荐组合 ----------------
 box.addView(a.text("推荐组合（只改主色）", 13, MainActivity.MUTED));
 LinearLayout comboRow = a.row();
 for (int i = 0; i < RECOMMENDED.length; i++) {
 final int preset = RECOMMENDED[i];
 Button combo =
 a.button(
 RECOMMENDED_NAMES[i],
 "textcolor-combo:" + i,
 () -> {
 restoreDefault[0] = false;
 rgb[0] = (preset >> 16) & 0xff;
 rgb[1] = (preset >> 8) & 0xff;
 rgb[2] = preset & 0xff;
 if (refreshAll[0] != null) refreshAll[0].run();
 });
 LinearLayout.LayoutParams comboLp =
 new LinearLayout.LayoutParams(0, a.dp(42), 1f);
 comboLp.rightMargin = a.dp(6);
 comboRow.addView(combo, comboLp);
 }
 box.addView(comboRow, new LinearLayout.LayoutParams(-1, -2));
 a.gap(box, 8);

 // ---------------- RGB 滑条 ----------------
 String[] names = {"R", "G", "B"};
 for (int i = 0; i < 3; i++) {
 final int index = i;
 LinearLayout row = a.row();
 TextView label = a.text(names[i], 13, MainActivity.MUTED);
 label.setWidth(a.dp(20));
 row.addView(label);
 final SeekBar bar = new SeekBar(a);
 bar.setMax(255);
 bar.setProgress(rgb[i]);
 bar.setFocusable(true);
 bar.setContentDescription(names[i] + " 通道，左右键调整");
 row.addView(bar, new LinearLayout.LayoutParams(0, a.dp(40), 1f));
 TextView value = a.text(String.valueOf(rgb[i]), 13, MainActivity.WHITE);
 value.setGravity(Gravity.END);
 value.setWidth(a.dp(42));
 row.addView(value);
 bar.setOnSeekBarChangeListener(
 new SeekBar.OnSeekBarChangeListener() {
 public void onProgressChanged(
 SeekBar seekBar, int progress, boolean fromUser) {
 rgb[index] = progress;
 paint(preview, rgb, values, hexField);
 }

 public void onStartTrackingTouch(SeekBar seekBar) {}

 public void onStopTrackingTouch(SeekBar seekBar) {}
 });
 // TV：左右键每次 ±5（触摸拖动仍是 1 级精度）
 bar.setOnKeyListener(
 (v, keyCode, event) -> {
 if (!a.isTvDevice() || event.getAction() != KeyEvent.ACTION_DOWN)
 return false;
 int delta =
 keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
 ? 5
 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -5 : 0;
 if (delta == 0) return false;
 int next = Math.max(0, Math.min(255, bar.getProgress() + delta));
 bar.setProgress(next);
 rgb[index] = next;
 paint(preview, rgb, values, hexField);
 return true;
 });
 bars[i] = bar;
 values[i] = value;
 box.addView(row, new LinearLayout.LayoutParams(-1, -2));
 }
 a.gap(box, 6);

 // ---------------- HEX（仅手机） ----------------
 if (!a.isTvDevice()) {
 LinearLayout row = a.row();
 EditText hex = new EditText(a);
 hex.setHint("#1A2B3C");
 hex.setSingleLine(true);
 hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
 hex.setTextColor(MainActivity.WHITE);
 hex.setHintTextColor(a.themeColor(MainActivity.MUTED));
 hex.setText(hexOf(rgb));
 hexField[0] = hex;
 row.addView(hex, new LinearLayout.LayoutParams(0, a.dp(46), 1f));
 row.addView(
 a.button(
 "应用",
 "textcolor-hex",
 () -> {
 Integer parsed = parseHex(hex.getText().toString());
 if (parsed == null) {
 a.toast("请输入 #RRGGBB，例如 #1A2B3C");
 return;
 }
 rgb[0] = (parsed >> 16) & 0xff;
 rgb[1] = (parsed >> 8) & 0xff;
 rgb[2] = parsed & 0xff;
 if (refreshAll[0] != null) refreshAll[0].run();
 }),
 new LinearLayout.LayoutParams(a.dp(72), a.dp(40)));
 box.addView(row, new LinearLayout.LayoutParams(-1, -2));
 } else {
 box.addView(
 a.text("电视端用 R/G/B 滑条调色（遥控器左右键每次 5 级）。", 12, MainActivity.MUTED));
 }
 a.gap(box, 8);

 Button reset =
 a.button(
 "恢复默认（亮绿）",
 "textcolor-reset",
 () -> {
 // §2.1：只重置预览/滑条/HEX，不写 prefs；点「确定」才保存
 int defaultColor = Prefs.DEFAULT_TEXT_COLOR;
 rgb[0] = (defaultColor >> 16) & 0xff;
 rgb[1] = (defaultColor >> 8) & 0xff;
 rgb[2] = defaultColor & 0xff;
 restoreDefault[0] = true;
 refreshAll[0].run();
 a.toast("已恢复默认亮绿 #4CFF80 · 点「确定」保存");
 });
 reset.setTextColor(MainActivity.MUTED);
 box.addView(reset, new LinearLayout.LayoutParams(-1, a.dp(42)));

 refreshAll[0] =
 () -> {
 for (int i = 0; i < 3; i++)
 if (bars[i] != null) bars[i].setProgress(rgb[i]);
 paint(preview, rgb, values, hexField);
 contrastLabel.setText("对比度（深色底）：" + contrastText(toRgb(rgb)));
 };

 AppDialog dialog =
 new AppDialog.Builder(a)
 .setTitle("字体颜色")
 .setMessage("作用于应用内主要标题与正文；次要文字自动降透明度，强调色（樱花粉）、焦点描边与状态色保持不变。")
 .setView(box)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "确定",
 (d, i) -> {
 if (restoreDefault[0]
 && toRgb(rgb)
 == (Prefs.DEFAULT_TEXT_COLOR & 0xffffff)) {
 saveDefault(a);
 } else {
 saveOrConfirm(a, rgb);
 }
 })
 .show();
 // 手机/电视默认焦点放到第一个色块（AppDialog 只会找直接子 View）
 dialog.getWindow()
 .getDecorView()
 .post(
 () -> {
 if (firstFocus.isAttachedToWindow()) firstFocus.requestFocus();
 });
 paint(preview, rgb, values, hexField);
 }

 /** 只刷新显示（不动滑条位置，避免拖动时循环回调）。 */
 private static void paint(
 TextView preview, int[] rgb, TextView[] values, EditText[] hexField) {
 preview.setTextColor(opaque(toRgb(rgb)));
 for (int i = 0; i < values.length; i++)
 if (values[i] != null) values[i].setText(String.valueOf(rgb[i]));
 if (hexField[0] != null) hexField[0].setText(hexOf(rgb));
 }

 static String contrastText(int rgb) {
 double ratio = contrastRatio(opaque(rgb), Skin.BG);
 String grade = ratio >= 7 ? "AAA" : ratio >= 4.5 ? "AA" : "不足 4.5:1";
 return String.format(java.util.Locale.ROOT, "%.1f:1 · %s", ratio, grade);
 }

 /** §4 对比度保护：以 App 深色底为基准，<4.5:1 时二次确认；取消则带当前颜色回到弹窗。 */
 static void saveOrConfirm(final MainActivity a, final int[] rgb) {
 final int color = opaque(toRgb(rgb));
 double ratio = contrastRatio(color, Skin.BG);
 if (ratio >= 4.5) {
 save(a, color);
 return;
 }
 if (a.appDialog != null) a.appDialog.dismiss();
 new AppDialog.Builder(a)
 .setTitle("该颜色可能看不清")
 .setMessage(
 String.format(
 java.util.Locale.ROOT,
 "该颜色与深色界面的对比度约 %.1f:1，低于 4.5:1，主要文字可能不易看清。仍要保存吗？",
 ratio))
 .setPositiveButton("仍然保存", (d, i) -> save(a, color))
 .setNegativeButton("返回修改", (d, i) -> show(a, color))
 .show();
 }

 /** 1.9.32：恢复默认亮绿（关闭自定义，回到 #4CFF80）。 */
 static void saveDefault(MainActivity a) {
 a.prefs.edit()
 .putBoolean(Prefs.TEXT_COLOR_CUSTOM, false)
 .putInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR)
 .apply();
 AppLog.i("Theme", "defaultTextColor=#4CFF80 (restored)");
 a.toast("已恢复默认亮绿 #4CFF80");
 a.recreate();
 }

 static void save(MainActivity a, int color) {
 a.prefs.edit()
 .putBoolean(Prefs.TEXT_COLOR_CUSTOM, true)
 .putInt(Prefs.TEXT_COLOR, color)
 .apply();
 a.toast("字体颜色已保存");
 a.recreate();
 }

 static double contrastRatio(int foreground, int background) {
 double first = luminance(foreground);
 double second = luminance(background);
 double light = Math.max(first, second);
 double dark = Math.min(first, second);
 return (light + 0.05) / (dark + 0.05);
 }

 private static double luminance(int rgb) {
 return 0.2126 * linear((rgb >> 16) & 0xff)
 + 0.7152 * linear((rgb >> 8) & 0xff)
 + 0.0722 * linear(rgb & 0xff);
 }

 private static double linear(int channel) {
 double s = channel / 255.0;
 return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
 }
}
