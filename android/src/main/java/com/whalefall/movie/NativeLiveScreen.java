package com.whalefall.movie;

import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.87：旧 TV（WebView 不支持 HLS）用的原生直播播放页。
 * 只负责 m3u8 直播流：ExoPlayer + SurfaceView，BACK 释放并返回。
 */
final class NativeLiveScreen {
 private NativeLiveScreen() {}

 private static MoviePlayer player;
 private static VideoOutput output;
 // 1.9.150：开源 IPTV 同名频道备用 URL 列表（播放失败自动尝试下一个）
 private static java.util.List<String> fallbackUrls;
 private static int fallbackIndex;
 private static android.view.Surface currentSurface;

 static void show(final MainActivity a, final String name, final String url) {
 show(a, name, java.util.Collections.singletonList(url));
 }

 static void show(final MainActivity a, final String name, final java.util.List<String> urls) {
 close(a);
 fallbackUrls = urls == null || urls.isEmpty() ? null : new java.util.ArrayList<>(urls);
 fallbackIndex = 0;
 final String firstUrl =
 fallbackUrls == null ? "" : fallbackUrls.get(0);
 a.renderPage("", "");
 a.body.setTag("native-live");
 FrameLayout root = a.root;
 root.setBackgroundColor(Color.BLACK);

 output = new VideoOutput(a, false);
 root.addView(output, new FrameLayout.LayoutParams(-1, -1));

 final TextView status = a.text("正在加载 " + name + " …", 16, Color.WHITE);
 status.setTag("native-live-status");
 status.setGravity(Gravity.CENTER);
 status.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
 root.addView(
 status,
 new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

 final TextView title = a.text("直播 · " + name, 18, 0xffffffff);
 title.setTag("native-live-title");
 title.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
 FrameLayout.LayoutParams titleLp =
 new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
 titleLp.setMargins(a.dp(24), a.dp(18), 0, 0);
 root.addView(title, titleLp);
 // 1.9.154：IPTV 播放页 OK 唤出频道选择器（透明捕获层）
 View catcher = new View(a);
 catcher.setFocusable(true);
 catcher.setFocusableInTouchMode(true);
 catcher.setBackgroundColor(Color.TRANSPARENT);
 root.addView(catcher, new FrameLayout.LayoutParams(-1, -1));
 catcher.setOnKeyListener(
 (v, keyCode, event) -> {
 if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
 if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
 AppLog.i("IptvOrg", "picker open current=" + name);
 IptvPickerPanel.show(a, name);
 return true;
 }
 return false;
 });
 catcher.requestFocus();

 output.listen(
 new VideoOutput.Listener() {
 @Override
 public void available(android.view.Surface surface) {
 currentSurface = surface;
 if (player != null) player.setSurface(surface);
 else start(a, name, firstUrl, surface, status);
 }

 @Override
 public void destroyed() {
 if (player != null) player.setSurface(null);
 }
 });
 AppLog.i("TvLive", "weblive native=yes channel=" + name + " url=" + firstUrl);
 }

 private static void start(
 final MainActivity a,
 final String name,
 final String url,
 android.view.Surface surface,
 final TextView status) {
 try {
 MoviePlayer candidate = new MoviePlayer(a);
 candidate.configureDefaultAudio();
 candidate.setDataSource(a, Uri.parse(url), java.util.Collections.emptyMap());
 candidate.setSurface(surface);
 candidate.setOnPreparedListener(
 p -> {
 status.setText("直播中 · " + name + " · 按返回键退出");
 p.start();
 });
 candidate.setOnErrorListener(
 (p, code, extra) -> {
 AppLog.i(
 "TvLive",
 "weblive native=error channel="
 + name
 + " code="
 + code
 + " url="
 + url
 + " next="
 + (fallbackUrls == null ? 0 : fallbackUrls.size() - fallbackIndex - 1));
 // 1.9.150：失败自动尝试同名频道的下一个源
 if (fallbackUrls != null && fallbackIndex + 1 < fallbackUrls.size()) {
 fallbackIndex++;
 String next = fallbackUrls.get(fallbackIndex);
 status.setText(
 "该源不可用，正在尝试备用源 "
 + (fallbackIndex + 1)
 + "/"
 + fallbackUrls.size()
 + " …");
 try {
 player = null;
 p.release();
 } catch (Throwable ignored) {
 }
 start(a, name, next, currentSurface, status);
 return true;
 }
 status.setText("该源不可用（" + code + "）。请换台或稍后重试。");
 return true;
 });
 player = candidate;
 candidate.prepareAsync();
 } catch (Throwable error) {
 AppLog.w("TvLive", "native live start failed", error);
 status.setText("直播流加载失败：" + a.message(error));
 }
 }

 static void close(MainActivity a) {
 if (player != null) {
 try {
 player.release();
 } catch (Throwable ignored) {
 }
 player = null;
 }
 if (output != null) {
 output = null;
 }
 fallbackUrls = null;
 fallbackIndex = 0;
 currentSurface = null;
 }

 static boolean handleBack(MainActivity a) {
 close(a);
 a.popBack();
 return true;
 }

 /** 1.9.155：二级 IPTV 选择器：左分类、右频道；当前频道高亮，OK 切台。 */
 static void showIptvPicker(final MainActivity a, final String currentName) {
 try {
 final TvLive.Store store = new TvLive.Store(a);
 final java.util.List<TvLive.Channel> all = store.group(TvLive.GROUP_IPTV);
 if (all.isEmpty()) {
 a.toast("暂无开源 IPTV 频道");
 return;
 }
 final String[] groups = {"全部", "央视", "卫视", "地方", "其他"};
 int dot = currentName == null ? -1 : currentName.indexOf(" · ");
 String initial = dot > 0 ? currentName.substring(0, dot) : "央视";
 final String[] selectedGroup = {initial};
 final AppDialog[] dialogRef = {null};
 final View[] focusTarget = {null};

 LinearLayout container = a.vertical();
 container.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2));
 LinearLayout columns = a.row();

 LinearLayout left = a.vertical();
 left.setPadding(a.dp(2), 0, a.dp(6), 0);
 LinearLayout right = a.vertical();
 android.widget.ScrollView rightScroll = new android.widget.ScrollView(a);
 rightScroll.setVerticalScrollBarEnabled(false);
 rightScroll.addView(right);

 final Runnable[] renderRight = new Runnable[1];
 final Runnable[] renderLeft = new Runnable[1];
 renderRight[0] =
 () -> {
 right.removeAllViews();
 focusTarget[0] = null;
 String group = selectedGroup[0];
 java.util.List<TvLive.Channel> filtered = new java.util.ArrayList<>();
 for (TvLive.Channel channel : all) {
 if ("全部".equals(group) || channel.name.startsWith(group + " · ")) filtered.add(channel);
 }
 for (final TvLive.Channel channel : filtered) {
 TextView item = a.text(
 channel.name + (store.favorites.contains(channel.name) ? "  ♥" : ""),
 13,
 channel.name.equals(currentName) ? 0xffffd4df : 0xffe6edf9);
 item.setSingleLine(true);
 item.setEllipsize(android.text.TextUtils.TruncateAt.END);
 item.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
 item.setBackground(
 MobileDetailScreen.glassBg(a, 0x14ffffff, 0x24ffffff, 10));
 item.setFocusable(true);
 item.setFocusableInTouchMode(true);
 item.setClickable(true);
 ExtraPages.tvFocus(a, item, 10);
 item.setOnClickListener(
 v -> {
 int dotIndex = channel.name.indexOf(" · ");
 String cat = dotIndex > 0 ? channel.name.substring(0, dotIndex) : "其他";
 java.util.List<String> urls = new java.util.ArrayList<>();
 for (TvLive.Channel candidate : all)
 if (candidate.name.equals(channel.name) && !urls.contains(candidate.url))
 urls.add(candidate.url);
 if (urls.isEmpty()) urls.add(channel.url);
 AppLog.i(
 "IptvOrg",
 "picker level=2 select=" + channel.name + " urls=" + urls.size());
 if (dialogRef[0] != null) dialogRef[0].dismiss();
 show(a, channel.name, urls);
 AppLog.i("IptvOrg", "picker group=" + cat + " switched=true");
 });
 LinearLayout.LayoutParams itemLp =
 new LinearLayout.LayoutParams(-1, a.dp(42));
 itemLp.bottomMargin = a.dp(4);
 right.addView(item, itemLp);
 if (focusTarget[0] == null
 || channel.name.equals(currentName)) focusTarget[0] = item;
 }
 if (right.getChildCount() == 0) {
 right.addView(a.text("该分类暂无频道", 12, 0xff8d9cb6));
 }
 };
 renderLeft[0] =
 () -> {
 left.removeAllViews();
 for (final String name : groups) {
 boolean on = name.equals(selectedGroup[0]);
 TextView chip = a.text(name, 13, on ? 0xff31141f : 0xffd6e1f0);
 chip.setGravity(android.view.Gravity.CENTER);
 chip.setPadding(a.dp(8), a.dp(9), a.dp(8), a.dp(9));
 chip.setBackground(
 on
 ? TvUiKit.accentFill(a, 999)
 : MobileDetailScreen.glassBg(a, 0x14ffffff, 0x24ffffff, 999));
 chip.setFocusable(true);
 chip.setFocusableInTouchMode(true);
 chip.setClickable(true);
 ExtraPages.tvFocus(a, chip, 999);
 chip.setOnClickListener(
 v -> {
 selectedGroup[0] = name;
 renderLeft[0].run();
 renderRight[0].run();
 AppLog.i("IptvOrg", "picker level=1 group=" + name);
 a.ui.postDelayed(
 () -> {
 if (focusTarget[0] != null) focusTarget[0].requestFocus();
 },
 80);
 });
 LinearLayout.LayoutParams lp =
 new LinearLayout.LayoutParams(a.dp(96), a.dp(40));
 lp.bottomMargin = a.dp(6);
 left.addView(chip, lp);
 }
 };
 renderLeft[0].run();
 renderRight[0].run();
 columns.addView(left, new LinearLayout.LayoutParams(a.dp(108), -1));
 columns.addView(rightScroll, new LinearLayout.LayoutParams(0, a.dp(300), 1f));
 container.addView(columns);

 dialogRef[0] =
 new AppDialog.Builder(a)
 .setTitle("开源 IPTV 频道")
 .setView(container)
 .setNegativeButton("关闭", null)
 .show();
 if (dot > 0) AppLog.i("IptvOrg", "picker level=1 group=" + initial);
 a.ui.postDelayed(
 () -> {
 if (focusTarget[0] != null && focusTarget[0].isShown()) focusTarget[0].requestFocus();
 },
 250);
 } catch (Throwable error) {
 AppLog.w("IptvOrg", "picker failed", error);
 a.toast("频道列表加载失败");
 }
 }
}
