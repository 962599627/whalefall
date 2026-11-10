package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.18「云盘专区」：只做 App 本地直连登录（不经过源插件）。
 * 四盘状态/登录（二维码）/退出登录；夸克/UC 有凭据时可查看文件（App 内直连）。
 */
final class CloudDriveZoneScreen {
    private CloudDriveZoneScreen() {}

    private static final String[] DRIVES = {
        NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
    };

    static void show(final MainActivity a) {
        a.renderPage("云盘专区 · 本地", "LOCAL CLOUD");
        AppLog.i("CloudZone", "open local zone");
        LinearLayout root = a.body;
        TextView note = a.text("本页是 App 本地直连登录，不经过源插件", 12, MainActivity.MUTED);
        note.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        root.addView(note);
        for (final String drive : DRIVES) root.addView(card(a, drive));
        TextView sourcePanel = a.text("源插件登录请用「网盘与账号（源面板）」", 11, MainActivity.MUTED);
        sourcePanel.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
        root.addView(sourcePanel);
    }

    private static View card(MainActivity a, final String drive) {
        boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
        boolean pending = !logged && NetdiskQrLogin.hasCredential(a, drive);
        AppLog.i(
                "CloudZone",
                "drive="
                        + drive
                        + " state="
                        + (logged ? "logged" : pending ? "pending" : "absent"));
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = a.dp(16);
        cardLp.rightMargin = a.dp(16);
        cardLp.bottomMargin = a.dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout head = a.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        int tint;
        switch (drive) {
            case NetdiskQrLogin.QUARK: tint = 0xFF5B8CFF; break;
            case NetdiskQrLogin.UC: tint = 0xFFFF8A3D; break;
            case NetdiskQrLogin.BAIDU: tint = 0xFF3D7EFF; break;
            default: tint = 0xFF8A6BFF; break;
        }
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
        icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        head.addView(icon, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        LinearLayout info = a.vertical();
        info.setPadding(a.dp(10), 0, 0, 0);
        TextView name = a.text(NetdiskQrLogin.name(drive) + "网盘", 14, MainActivity.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);
        TextView state =
                a.text(
                        logged
                                ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                                : pending ? "登录未通过验证" : "未登录",
                        11,
                        logged ? 0xff2ad47a : MainActivity.MUTED);
        state.setPadding(0, a.dp(3), 0, 0);
        info.addView(state);
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(head);

        LinearLayout actions = a.row();
        TextView login = a.text("登录", 12, 0xff0b1220);
        login.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        login.setBackground(UiTokens.round(a, 16, UiTokens.ACCENT));
        login.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(login);
        login.setOnClickListener(
                v -> NetdiskQrLogin.show(a, drive, () -> a.ui.postDelayed(() -> show(a), 200)));
        actions.addView(login);
        TextView logout = a.text("退出登录", 12, 0xffef4444);
        logout.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        logout.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(logout);
        logout.setOnClickListener(
                v -> {
                    if (!NetdiskQrLogin.hasCredential(a, drive)) {
                        a.toast("App 内未登录" + NetdiskQrLogin.name(drive));
                        return;
                    }
                    NetdiskQrLogin.logout(a, drive);
                    CloudConfigScreen.clearWebCookies(drive);
                    a.toast("已退出 App 内" + NetdiskQrLogin.name(drive) + "登录");
                    show(a);
                });
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-2, -2);
        logoutLp.leftMargin = a.dp(8);
        actions.addView(logout, logoutLp);
        if ((NetdiskQrLogin.QUARK.equals(drive) || NetdiskQrLogin.UC.equals(drive))
                && NetdiskQrLogin.hasCredential(a, drive)) {
            TextView files = a.text("查看文件", 12, 0xFFFFB3C8);
            files.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
            files.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(files);
            final QuarkUcApi.Provider provider =
                    NetdiskQrLogin.QUARK.equals(drive) ? QuarkUcApi.QUARK : QuarkUcApi.UC;
            files.setOnClickListener(v -> QuarkUcFiles.show(a, provider));
            LinearLayout.LayoutParams filesLp = new LinearLayout.LayoutParams(-2, -2);
            filesLp.leftMargin = a.dp(8);
            actions.addView(files, filesLp);
        }
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = a.dp(10);
        card.addView(actions, actionsLp);
        return card;
    }
}
