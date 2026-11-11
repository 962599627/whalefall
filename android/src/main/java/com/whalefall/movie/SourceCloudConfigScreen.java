package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.28「云盘配置 · 源插件」：用我们统一的玻璃 UI 管理源插件登录（不弹插件白底面板）。
 * 只读展示四盘源插件登录态、退出源插件登录（删 spUtils 键 + closeEngines）、打开源面板登录；
 * 夸克/UC 有源插件凭据时可查看源插件网盘文件。不混入 App 内登录与源动作（排序/推送）。
 */
final class SourceCloudConfigScreen {
 private SourceCloudConfigScreen() {}

 private static final String[] DRIVES = {
 NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
 };

 static void show(final MainActivity a) {
 a.renderPage("云盘配置 · 源插件", "SOURCE CLOUD");
 if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.body != null) {
 a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.body.setTag("source-cloud-config");
 }
 AppLog.i("CloudConfig", "page=SourceCloudConfigScreen ui=our-glass");
 LinearLayout body = a.body;
 TextView note =
 a.text(
 "本页用 App 的玻璃 UI 管理源插件登录；只有点「打开源面板登录」才打开插件面板。",
 11.5f,
 MainActivity.MUTED);
 note.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
 body.addView(note);

 // 源插件登录状态 + 退出（复用云盘专区同款区块）
 CloudZoneScreen.addSourceSection(a, body, () -> show(a));

 // 查看源插件网盘文件（仅源插件 Cookie）
 TextView fileHead = a.text("源插件网盘文件", 14, UiTokens.ACCENT_SOFT);
 fileHead.setTypeface(null, android.graphics.Typeface.BOLD);
 fileHead.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
 body.addView(fileHead);
 body.addView(sourceFileRow(a, QuarkUcApi.QUARK));
 body.addView(sourceFileRow(a, QuarkUcApi.UC));

 // 打开源面板登录（唯一会打开插件白底面板的入口）
 TextView open = a.text("打开源面板登录", 13, 0xff0b1220);
 open.setGravity(Gravity.CENTER);
 open.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
 open.setBackground(UiTokens.round(a, 20, UiTokens.ACCENT));
 open.setFocusable(true);
 MobileDetailScreen.bindTouchFeedback(open);
 open.setOnClickListener(
 v -> {
 AppLog.i("CloudConfig", "open source panel for login");
 new SettingsScreen(a).sourceAccountAction("LoginShow", null);
 });
 LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, a.dp(48));
 openLp.leftMargin = a.dp(16);
 openLp.rightMargin = a.dp(16);
 openLp.topMargin = a.dp(10);
 openLp.bottomMargin = a.dp(20);
 body.addView(open, openLp);
 }

 private static LinearLayout sourceFileRow(MainActivity a, final QuarkUcApi.Provider provider) {
 final String cookie = CloudZoneScreen.sourceCookie(a, provider);
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
 row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
 TextView name =
 a.text(
 "查看" + provider.name + "源插件网盘文件"
 + (cookie.isEmpty() ? "（未检测到登录）" : ""),
 13,
 MainActivity.WHITE);
 row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView go = a.text(cookie.isEmpty() ? "未登录" : "打开", 12, 0xFFFFB3C8);
 go.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
 row.addView(go);
 row.setFocusable(!cookie.isEmpty());
 MobileDetailScreen.bindTouchFeedback(row);
 row.setOnClickListener(
 v -> {
 if (cookie.isEmpty()) {
 a.toast("源插件未检测到" + provider.name + "登录，请先打开源面板登录");
 return;
 }
 AppLog.i("CloudConfig", "open source files provider=" + provider.key);
 QuarkUcFiles.show(a, provider, cookie, true);
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.leftMargin = a.dp(16);
 lp.rightMargin = a.dp(16);
 lp.bottomMargin = a.dp(8);
 row.setLayoutParams(lp);
 return row;
 }
}
