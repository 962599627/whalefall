package com.whalefall.movie;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * 「管理员后台」入口：启动本机网页服务，展示二维码与局域网地址，
 * 手机/电脑扫码或点链接即可在浏览器里管理片源、收藏、电视台和设置。
 */
final class AdminPanel {
    private AdminPanel() {}

    static void show(MainActivity a) {
        final AdminConsole[] holder = new AdminConsole[1];
        try {
            holder[0] = new AdminConsole(a);
        } catch (Exception e) {
            a.toast("管理后台启动失败：" + e.getMessage());
            return;
        }
        final AdminConsole console = holder[0];
        final String url = console.url();
        MiniFtpServer.start(a);
        final String ftpUrl =
                MiniFtpServer.RUNNING == null
                        ? ""
                        : "ftp://" + AdminConsole.lanIp() + ":2121/（用户 wf / 密码 "
                                + MiniFtpServer.password() + "）";

        LinearLayout panel = a.vertical();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        final boolean tv = a.isTvDevice();

        TextView note =
                a.text(
                        tv
                                ? "用手机/电脑在同一 Wi-Fi 下扫码，或直接打开下面的地址"
                                : "同一 Wi-Fi 下扫码，或直接在浏览器打开地址",
                        tv ? 15 : 14,
                        MainActivity.MUTED);
        note.setGravity(Gravity.CENTER);
        panel.addView(note);
        a.gap(panel, 12);

        int qrSize = a.dp(tv ? 200 : 168);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, qrSize, qrSize);
            int[] pixels = new int[qrSize * qrSize];
            for (int y = 0; y < qrSize; y++)
                for (int x = 0; x < qrSize; x++)
                    pixels[y * qrSize + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            ImageView qr = new ImageView(a);
            qr.setTag("admin-qr");
            qr.setContentDescription("管理后台二维码");
            qr.setImageBitmap(
                    Bitmap.createBitmap(pixels, qrSize, qrSize, Bitmap.Config.ARGB_8888));
            panel.addView(qr, new LinearLayout.LayoutParams(qrSize, qrSize));
        } catch (Exception e) {
            panel.addView(a.text("二维码生成失败：" + e.getMessage(), 12, MainActivity.MUTED));
        }

        TextView tokenLine =
                a.text("访问令牌：" + console.token, tv ? 13 : 12, 0xffffb3c8);
        tokenLine.setGravity(Gravity.CENTER);
        tokenLine.setTextIsSelectable(true);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(-1, -2);
        tokenLp.topMargin = a.dp(6);
        panel.addView(tokenLine, tokenLp);

        TextView address = a.text(url, tv ? 17 : 13, MainActivity.WHITE);
        address.setTag("admin-url");
        address.setGravity(Gravity.CENTER);
        address.setPadding(0, a.dp(12), 0, a.dp(6));
        address.setTextIsSelectable(true);
        panel.addView(address);

        if (!tv) {
            TextView open = a.text("在浏览器打开", 14, 0xff31141f);
            open.setGravity(Gravity.CENTER);
            open.setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(12));
            open.setBackground(glassPill(a, 0xffffc7d6, 0xfff59ab5));
            open.setOnClickListener(
                    v -> {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            a.startActivity(intent);
                        } catch (Exception e) {
                            a.toast("没有可用的浏览器");
                        }
                    });
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-2, -2);
            openLp.topMargin = a.dp(12);
            panel.addView(open, openLp);

            TextView copy = a.text("复制链接", 13, MainActivity.WHITE);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(10));
            copy.setBackground(glassPill(a, 0x33ffffff, 0x44ffffff));
            copy.setOnClickListener(
                    v -> {
                        ClipboardManager clipboard =
                                (ClipboardManager)
                                        a.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("鲸落管理后台", url));
                        a.toast("链接已复制");
                    });
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
            copyLp.topMargin = a.dp(8);
            panel.addView(copy, copyLp);
        }

        if (!ftpUrl.isEmpty()) {
            TextView ftp = a.text("FTP 上传：" + ftpUrl, tv ? 13 : 12, 0xffffb3c8);
            ftp.setGravity(Gravity.CENTER);
            ftp.setPadding(0, a.dp(10), 0, 0);
            ftp.setTextIsSelectable(true);
            panel.addView(ftp);
        }

        TextView hint =
                a.text(
                        "网页支持：片源导入与切换 · 收藏/历史管理 · 电视频道增删 · 壁纸与播放设置\n"
                                + "关闭此窗口后服务停止",
                        tv ? 13 : 11.5f,
                        MainActivity.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, a.dp(12), 0, 0);
        panel.addView(hint);

        AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("管理员后台")
                        .setView(panelScroll(panel))
                        .setNegativeButton(tv ? "关闭后台" : "完成", null)
                        .create();
        dialog.setOnDismissListener(
                d -> {
                    try {
                        console.close();
                    } catch (Throwable ignored) {
                    }
                    MiniFtpServer.stop();
                    if (a.adminConsole == console) a.adminConsole = null;
                });
        a.adminConsole = console;
        dialog.show();
        if (tv)
            a.ui.post(
                    () -> {
                        if (dialog.getWindow() == null) return;
                        android.view.View close =
                                dialog.getWindow()
                                        .getDecorView()
                                        .findViewWithTag("dialog-action:-2");
                        if (close != null) close.requestFocus();
                    });
    }

    private static android.graphics.drawable.GradientDrawable glassPill(
            MainActivity a, int fill, int stroke) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(a.dp(999));
        gd.setStroke(Math.max(1, a.dp(0.8f)), stroke);
        return gd;
    }

    /** 面板内容包进 ScrollView（手机）；TV 由 AppDialog 外层 TvScrollView 滚动，避免双层滚动。 */
    private static android.view.View panelScroll(LinearLayout panel) {
        if (panel.getContext() instanceof MainActivity
                && ((MainActivity) panel.getContext()).isTvDevice()) return panel;
        android.widget.ScrollView scroll = new android.widget.ScrollView(panel.getContext());
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(panel, new android.widget.ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

}
