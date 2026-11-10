package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.21「云盘专区 · 本地」：复用 CloudInlineView 的 8 卡网格（图三样式），
 * 功能为 App 本地直连登录：四盘二维码/清除本地凭据/源动作；夸克/UC 已登录可查看文件。
 */
final class CloudZoneScreen {
    private CloudZoneScreen() {}

    static void show(final MainActivity a) {
        a.renderPage("云盘专区 · 本地", "LOCAL CLOUD");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body != null) a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        renderInto(a, a.body, () -> show(a));
    }

    /** 1.8.22：供 MobileShell 内嵌 tab 调用（不新建页面）。 */
    static void renderInto(
            final MainActivity a, final LinearLayout body, final Runnable onChanged) {
        body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        AppLog.i("Wallpaper", "page=CloudZone bg=transparent");
        AppLog.i("CloudZone", "renderInto inline");
        TextView note = a.text("本页是 App 本地直连登录，不经过源插件", 11.5f, MainActivity.MUTED);
        note.setPadding(a.dp(16), 0, a.dp(16), a.dp(6));
        body.addView(note);
        CloudInlineView.build(
                a,
                body,
                onChanged,
                "云盘专区 · 本地",
                "本 App 直连登录（不经过源插件）：扫码/清除均为本地凭据");
        // 查看文件（夸克/UC 已登录才有）
        boolean quark = NetdiskQrLogin.QUARK != null && NetdiskQrLogin.hasCredential(a, NetdiskQrLogin.QUARK);
        boolean uc = NetdiskQrLogin.hasCredential(a, NetdiskQrLogin.UC);
        if (quark || uc) {
            TextView head = a.text("本地网盘文件", 14, UiTokens.ACCENT_SOFT);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
            body.addView(head);
            if (quark) body.addView(fileRow(a, QuarkUcApi.QUARK));
            if (uc) body.addView(fileRow(a, QuarkUcApi.UC));
        }
    }

    private static LinearLayout fileRow(MainActivity a, final QuarkUcApi.Provider provider) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView name = a.text("查看" + provider.name + "网盘文件", 13, MainActivity.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = a.text("打开", 12, 0xFFFFB3C8);
        go.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        row.addView(go);
        row.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                v -> {
                    AppLog.i("CloudZone", "open files provider=" + provider.key);
                    QuarkUcFiles.show(a, provider);
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.bottomMargin = a.dp(8);
        row.setLayoutParams(lp);
        return row;
    }
}
