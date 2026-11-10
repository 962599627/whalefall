package com.whalefall.movie;

import android.graphics.Bitmap;
import android.view.Gravity;
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

    /** 1.9.27：所有离开路径统一关闭服务、移除 pump、日志原因。 */
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
        AppLog.i("LanRemote", "stop reason=" + reason);
    }

    static void show(final MainActivity a) {
        stop("restart");
        String address;
        try {
            server = new LanRemoteServer(a);
            address = server.address();
        } catch (Throwable error) {
            a.toast("无法启动手机遥控：" + error.getMessage());
            AppLog.i("LanRemote", "start failed error=" + error.getMessage());
            return;
        }
        AppLog.i("LanRemote", "start port=" + server.socket.getLocalPort() + " tokenLen=" + server.token.length());
        a.renderPage("手机遥控 / 扫码输入", "LAN REMOTE");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body == null) {
            stop("no-body");
            return;
        }
        a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.body.setTag("lan-remote");
        boolean tv = a.isTvDevice();
        float tipSize = tv ? 15f : 11.5f;
        TextView tip =
                a.text(
                        "手机浏览器扫码即可遥控/输入，无需安装 App；需与电视连接同一 WiFi。",
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
        TextView stop = a.text("停止手机遥控", tv ? 15f : 12.5f, 0xffffffff);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        stop.setBackground(UiTokens.round(a, 999, 0xffff6b81));
        stop.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(stop);
        stop.setOnClickListener(
                v -> {
                    stop("button");
                    a.toast("已停止手机遥控");
                    back(a);
                });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, a.dp(48));
        stopLp.leftMargin = a.dp(16);
        stopLp.rightMargin = a.dp(16);
        a.body.addView(stop, stopLp);
        TextView foot =
                a.text("按返回键或点击停止都会关闭服务", 11.5f, UiTokens.TEXT_TERTIARY);
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
                        if (a.body == null || !"lan-remote".equals(a.body.getTag())) {
                            stop("page-left");
                            return;
                        }
                        if (server != null) {
                            server.pump();
                            boolean waiting = !server.sawClient;
                            boolean connected =
                                    server.sawClient
                                            && System.currentTimeMillis() - server.lastSeen < 6000;
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
                        } else return;
                        a.ui.postDelayed(this, 100);
                    }
                };
        pumpRunnable = pump;
        pumpHost = a;
        a.ui.postDelayed(pump, 100);
    }

    static boolean handleBack(MainActivity a) {
        stop("back");
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
