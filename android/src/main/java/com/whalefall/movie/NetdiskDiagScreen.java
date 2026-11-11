package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 1.9.11 网盘诊断：最近 50 条 QuarkGet/QuarkPlay/QuarkList 日志（已脱敏），
 * 支持复制全部 / 清空 / 重试失败下载，便于用户一键把 400/405 响应体发给开发者。
 */
final class NetdiskDiagScreen {
 private NetdiskDiagScreen() {}

 static void show(final MainActivity a) {
 a.renderPage("网盘诊断", "NETDISK DIAG");
 if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.body == null) return;
 a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.body.setTag("netdisk-diag");
 a.body.setPadding(a.dp(16), a.dp(12) + statusBar(a), a.dp(16), a.dp(24));
 AppLog.i("DownCenter", "page=NetdiskDiag");

 LinearLayout actions = a.row();
 actions.setGravity(Gravity.CENTER_VERTICAL);
 TextView back = a.text("‹ 文件管理", 12, UiTokens.TEXT_PRIMARY);
 back.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(back);
 back.setOnClickListener(v -> FileManagerScreen.resume(a));
 actions.addView(back);
 actions.addView(action(a, "复制全部日志", () -> copyAll(a)));
 actions.addView(action(a, "清空", () -> {
 AppLog.clear();
 show(a);
 }));
 actions.addView(action(a, "重试下载", () -> retryFailed(a)));
 a.body.addView(actions);

 TextView tip =
 a.text(
 "把下面的日志复制发给开发者，可定位具体接口/HTTP 状态/响应体。",
 11,
 UiTokens.TEXT_TERTIARY);
 tip.setPadding(0, a.dp(8), 0, a.dp(6));
 a.body.addView(tip);

 ScrollView scroll = new ScrollView(a);
 scroll.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x33ffffff, 14));
 TextView log = a.text("", 10.5f, UiTokens.TEXT_SECONDARY);
 log.setTypeface(android.graphics.Typeface.MONOSPACE);
 log.setTextIsSelectable(true);
 log.setLineSpacing(a.dp(1), 1f);
 log.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(10));
 String text = QuarkUcApi.recentQuarkLog(50);
 log.setText(text.isEmpty() ? "暂无诊断日志" : text);
 scroll.addView(log, new ScrollView.LayoutParams(-1, -2));
 a.body.addView(scroll, new LinearLayout.LayoutParams(-1, a.dp(600)));
 }

 private static TextView action(MainActivity a, String label, Runnable run) {
 TextView view = a.text(label, 11.5f, UiTokens.ACCENT);
 view.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 view.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(view);
 view.setOnClickListener(v -> run.run());
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.leftMargin = a.dp(6);
 view.setLayoutParams(lp);
 return view;
 }

 private static void copyAll(MainActivity a) {
 try {
 String text = QuarkUcApi.recentQuarkLog(50);
 android.content.ClipboardManager manager =
 (android.content.ClipboardManager)
 a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
 manager.setPrimaryClip(
 android.content.ClipData.newPlainText("NetdiskDiag", text == null ? "" : text));
 a.toast(text == null || text.isEmpty() ? "暂无诊断日志" : "已复制全部日志，可发给开发者");
 } catch (Throwable error) {
 a.toast("复制失败：" + error.getMessage());
 }
 }

 private static void retryFailed(MainActivity a) {
 int retried = 0;
 List<FileManagerCore.Info> tasks = FileManagerCore.tasks();
 for (FileManagerCore.Info info : tasks) {
 if (info.state == 4) {
 FileManagerCore.retry(info.id);
 retried++;
 }
 }
 a.toast(retried > 0 ? "已重试 " + retried + " 个失败任务" : "没有失败任务可重试");
 if (retried > 0) FileManagerScreen.resume(a);
 }

 private static int statusBar(MainActivity a) {
 try {
 int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
 if (id > 0) return a.getResources().getDimensionPixelSize(id);
 } catch (Throwable ignored) {
 }
 return a.dp(24);
 }
}
