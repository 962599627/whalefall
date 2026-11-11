package com.whalefall.movie;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.26 TV「手机遥控 / 扫码输入」：显示二维码，手机浏览器扫码后当遥控器/输入法。
 */
final class RemoteInputScreen {
 private RemoteInputScreen() {}

 private static LanRemoteServer server;
 private static Runnable pumpRunnable;
 private static MainActivity pumpHost;

 static void stop() {
 stop("explicit");
 }

 /** 1.9.30：只由「停止手机遥控」按钮、通知停止或进程销毁调用；离开页面不再关闭服务。 */
 static void stop(String reason) {
 if (pumpRunnable != null && pumpHost != null) {
 try {
 pumpHost.ui.removeCallbacks(pumpRunnable);
 } catch (Throwable ignored) {
 }
 }
 pumpRunnable = null;
 pumpHost = null;
 if (server != null) {
 try {
 server.close();
 } catch (Throwable ignored) {
 }
 server = null;
 }
 cancelNotification(MainActivity.activeActivity);
 AppLog.i("LanRemote", "keepalive stop reason=" + reason);
 }

 static void show(final MainActivity a) {
 String address;
 boolean reuse = server != null && !server.closed && !server.expired();
 try {
 if (!reuse) {
 if (server != null) {
 try {
 server.close();
 } catch (Throwable ignored) {
 }
 }
 server = new LanRemoteServer(a);
 AppLog.i(
 "LanRemote",
 "keepalive start reason=reopen port="
 + server.socket.getLocalPort()
 + " tokenLen="
 + server.token.length());
 } else {
 server.resetIdle();
 AppLog.i("LanRemote", "keepalive start reason=reuse port=" + server.socket.getLocalPort());
 }
 address = server.address();
 } catch (Throwable error) {
 a.toast("无法启动手机遥控：" + error.getMessage());
 AppLog.i("LanRemote", "start failed error=" + error.getMessage());
 return;
 }
 pumpHost = a;
 startNotification(a);
 a.renderPage("手机遥控 / 扫码输入", "LAN REMOTE");
 if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.body == null) {
 stop("render-failed");
 return;
 }
 a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.body.setTag("lan-remote");
 boolean tv = a.isTvDevice();
 float tipSize = tv ? 15f : 11.5f;
 TextView tip =
 a.text(
 "手机浏览器扫码即可遥控/输入；可离开本页继续用手机遥控，点「停止手机遥控」才关闭。",
 tipSize,
 UiTokens.TEXT_SECONDARY);
 tip.setGravity(Gravity.CENTER);
 tip.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(12));
 a.body.addView(tip);
 // P0：二维码按 dp 换算 + 屏高 44% 上限，白卡包裹
 int qrPx =
 Math.min(
 a.dp(tv ? 240 : 200),
 Math.round(a.windowHeight() * (tv ? 0.40f : 0.44f)));
 Bitmap qr = PanQr.render(address, Math.max(a.dp(140), qrPx));
 LinearLayout qrCard = a.vertical();
 qrCard.setGravity(Gravity.CENTER);
 qrCard.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
 qrCard.setBackground(MobileDetailScreen.glassBg(a, 0xffffffff, 0x33ffffff, 16));
 if (qr != null) {
 ImageView image = new ImageView(a);
 image.setImageBitmap(qr);
 qrCard.addView(image);
 }
 LinearLayout.LayoutParams qrCardLp =
 new LinearLayout.LayoutParams(-2, -2);
 qrCardLp.gravity = Gravity.CENTER_HORIZONTAL;
 qrCardLp.topMargin = a.dp(16);
 a.body.addView(qrCard, qrCardLp);
 TextView addr = a.text(address, tv ? 15f : 12, UiTokens.ACCENT_SOFT);
 addr.setGravity(Gravity.CENTER);
 addr.setMaxLines(2);
 addr.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
 addr.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(6));
 a.body.addView(addr);
 // P1：连接状态（等待/已连接/中断）
 LinearLayout statusRow = a.row();
 statusRow.setGravity(Gravity.CENTER);
 View dot = new View(a);
 android.graphics.drawable.GradientDrawable circle =
 new android.graphics.drawable.GradientDrawable();
 circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
 circle.setColor(UiTokens.TEXT_TERTIARY);
 dot.setBackground(circle);
 LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(a.dp(8), a.dp(8));
 dotLp.rightMargin = a.dp(6);
 statusRow.addView(dot, dotLp);
 TextView statusText = a.text("等待手机连接", tv ? 15f : 12.5f, UiTokens.TEXT_TERTIARY);
 statusRow.addView(statusText);
 a.body.addView(statusRow);
 TextView statusSub =
 a.text("同一 WiFi · 打开后自动连接", 11.5f, UiTokens.TEXT_TERTIARY);
 statusSub.setGravity(Gravity.CENTER);
 statusSub.setPadding(0, a.dp(2), 0, a.dp(10));
 a.body.addView(statusSub);
 TextView stop = a.text("停止手机遥控", tv ? 15f : 12.5f, 0xff0b1220);
 stop.setGravity(Gravity.CENTER);
 stop.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
 stop.setFocusable(true);
 stop.setFocusableInTouchMode(true);
 MobileDetailScreen.bindTouchFeedback(stop);
 FocusMotion.style(
 a,
 stop,
 false,
 0xffff6b81,
 0xffff9aaa,
 999,
 0xff0b1220,
 0xff0b1220,
 false,
 1.004f);
 stop.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 stop,
 focused,
 0xffff6b81,
 0xffff9aaa,
 999,
 0xff0b1220,
 0xff0b1220,
 focused,
 1.04f));
 stop.setOnClickListener(
 v -> {
 stop("button");
 a.toast("已停止手机遥控");
 // 1.9.30：统一走返回逻辑，避免页面栈残留
 a.onBackPressed();
 });
 LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, a.dp(48));
 stopLp.leftMargin = a.dp(16);
 stopLp.rightMargin = a.dp(16);
 a.body.addView(stop, stopLp);
 TextView foot =
 a.text(
 "可离开本页继续用手机遥控；点「停止手机遥控」或通知里的停止才关闭",
 11.5f,
 UiTokens.TEXT_TERTIARY);
 foot.setGravity(Gravity.CENTER);
 foot.setPadding(0, a.dp(6), 0, a.dp(4));
 a.body.addView(foot);
 a.body.setGravity(Gravity.CENTER_HORIZONTAL);
 final TextView statusRef = statusText;
 final View dotRef = dot;
 final android.graphics.drawable.GradientDrawable circleRef = circle;
 a.ui.post(stop::requestFocus);
 // TV UI 线程每 100ms 消费一次手机命令
 final Runnable pump =
 new Runnable() {
 @Override
 public void run() {
 // 1.9.30：离开页面后服务与 pump 继续，方便边浏览边遥控
 final LanRemoteServer snapshot = server;
 if (snapshot == null) return;
 snapshot.pump();
 // 1.9.30：无客户端 20 分钟自动停止并释放端口
 long idle =
 snapshot.sawClient
 ? System.currentTimeMillis() - snapshot.lastSeen
 : System.currentTimeMillis() - snapshot.startedAt();
 if (idle > 20 * 60_000L) {
 stop("idle-timeout");
 return;
 }
 final boolean pageVisible =
 a.body != null && "lan-remote".equals(a.body.getTag());
 if (pageVisible) {
 final boolean sawClient = snapshot.sawClient;
 final long lastSeen = snapshot.lastSeen;
 boolean waiting = !sawClient;
 boolean connected =
 sawClient && System.currentTimeMillis() - lastSeen < 6000;
 if (waiting) {
 statusRef.setText("等待手机连接");
 statusRef.setTextColor(UiTokens.TEXT_TERTIARY);
 circleRef.setColor(UiTokens.TEXT_TERTIARY);
 } else if (connected) {
 statusRef.setText("已连接");
 statusRef.setTextColor(0xff2ad47a);
 circleRef.setColor(0xff2ad47a);
 } else {
 statusRef.setText("连接中断（重新打开网页即可）");
 statusRef.setTextColor(0xffffb454);
 circleRef.setColor(0xffffb454);
 }
 }
 // stop() 可能在 snapshot.pump() 内被同步触发；此时不再续跑
 if (server != snapshot) return;
 a.ui.postDelayed(this, 100);
 }
 };
 pumpHost = a;
 if (a.ui != null) {
 if (pumpRunnable != null) a.ui.removeCallbacks(pumpRunnable);
 pumpRunnable = pump;
 a.ui.postDelayed(pump, 100);
 }
 }

 // 1.9.30：常驻通知（服务保持运行，点停止或通知停止才关闭）
 static final int NOTIF_ID = 7320;
 static final String CHANNEL = "whalefall_lan_remote";

 static void startNotification(MainActivity a) {
 if (a == null) return;
 try {
 android.app.NotificationManager nm =
 (android.app.NotificationManager)
 a.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
 if (android.os.Build.VERSION.SDK_INT >= 26) {
 nm.createNotificationChannel(
 new android.app.NotificationChannel(
 CHANNEL,
 "手机遥控",
 android.app.NotificationManager.IMPORTANCE_LOW));
 }
 android.content.Intent stop =
 new android.content.Intent(LanRemoteReceiver.ACTION_STOP)
 .setPackage(a.getPackageName());
 android.app.PendingIntent stopPi =
 android.app.PendingIntent.getBroadcast(
 a,
 11,
 stop,
 android.app.PendingIntent.FLAG_UPDATE_CURRENT
 | android.app.PendingIntent.FLAG_IMMUTABLE);
 android.content.Intent open =
 new android.content.Intent(a, MainActivity.class);
 android.app.PendingIntent openPi =
 android.app.PendingIntent.getActivity(
 a,
 12,
 open,
 android.app.PendingIntent.FLAG_UPDATE_CURRENT
 | android.app.PendingIntent.FLAG_IMMUTABLE);
 android.app.Notification.Builder builder =
 android.os.Build.VERSION.SDK_INT >= 26
 ? new android.app.Notification.Builder(a, CHANNEL)
 : new android.app.Notification.Builder(a);
 builder.setSmallIcon(android.R.drawable.ic_menu_compass)
 .setContentTitle("手机遥控服务运行中")
 .setContentText("手机浏览器可继续遥控 · 点击停止")
 .setContentIntent(openPi)
 .setOnlyAlertOnce(true)
 .setOngoing(true)
 .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
 nm.notify(NOTIF_ID, builder.build());
 } catch (Throwable error) {
 AppLog.i("LanRemote", "notify failed error=" + error.getClass().getSimpleName());
 }
 }

 static void cancelNotification(android.content.Context context) {
 if (context == null) return;
 try {
 ((android.app.NotificationManager)
 context.getSystemService(android.content.Context.NOTIFICATION_SERVICE))
 .cancel(NOTIF_ID);
 } catch (Throwable ignored) {
 }
 }

 static boolean handleBack(MainActivity a) {
 // 1.9.30：BACK 只离开页面，服务保持；仅停止按钮/通知/进程销毁才关闭
 AppLog.i("LanRemote", "keepalive continue reason=back");
 return false;
 }

 private static void back(MainActivity a) {
 if (a.isTvDevice()) {
 a.current = new MainActivity.Screen(a::home);
 a.current.home = true;
 a.renderReturn(a.current);
 } else {
 MobileShell.show(a);
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(4);
 }
 }
}
