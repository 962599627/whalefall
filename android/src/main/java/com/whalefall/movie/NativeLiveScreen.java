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
 showIptvPicker(a, name);
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

 /** 1.9.154：IPTV 频道选择器（按 央视/卫视/地方/其他 前缀分组显示，当前台高亮）。 */
 static void showIptvPicker(final MainActivity a, final String currentName) {
 try {
 TvLive.Store store = new TvLive.Store(a);
 final java.util.List<TvLive.Channel> list = store.group(TvLive.GROUP_IPTV);
 if (list.isEmpty()) {
 a.toast("暂无开源 IPTV 频道");
 return;
 }
 String[] labels = new String[list.size()];
 int checked = 0;
 for (int i = 0; i < list.size(); i++) {
 TvLive.Channel channel = list.get(i);
 labels[i] =
 channel.name + (store.favorites.contains(channel.name) ? " ♥" : "");
 if (channel.name.equals(currentName)) checked = i;
 }
 new AppDialog.Builder(a)
 .setTitle("开源 IPTV 频道（" + list.size() + "）")
 .setSingleChoiceItems(
 labels,
 checked,
 (dialog, which) -> {
 TvLive.Channel chosen = list.get(which);
 int dot = chosen.name.indexOf(" · ");
 String group = dot > 0 ? chosen.name.substring(0, dot) : "其他";
 java.util.List<String> urls = new java.util.ArrayList<>();
 for (TvLive.Channel candidate : list)
 if (candidate.name.equals(chosen.name) && !urls.contains(candidate.url))
 urls.add(candidate.url);
 if (urls.isEmpty()) urls.add(chosen.url);
 AppLog.i(
 "IptvOrg",
 "picker group=" + group + " select=" + chosen.name + " urls=" + urls.size());
 show(a, chosen.name, urls);
 })
 .setNegativeButton("取消", null)
 .show();
 } catch (Throwable error) {
 AppLog.w("IptvOrg", "picker failed", error);
 a.toast("频道列表加载失败");
 }
 }
}
