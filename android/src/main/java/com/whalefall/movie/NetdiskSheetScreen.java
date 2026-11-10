package com.whalefall.movie;

import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.20「我的云盘 · 扫码与账号配置」（移植上游 flutter/src/ui/netdisk_sheet.dart 结构）：
 * 盘序 banner + 选中盘扫码卡 + 四盘 tile（登录/手动配置/清除）+ 手动 Cookie/Token。
 * 登录复用真实官方接口（NetdiskQrLogin），不使用上游的「模拟确认」假凭据逻辑。
 */
final class NetdiskSheetScreen {
    private NetdiskSheetScreen() {}

    private static final String ORDER_KEY = "netdisk_sheet_order";
    private static final String[] DRIVES = {
        NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
    };
    private static final String[] ALIAS = {"我的夸父", "我的优汐", "我的嘟嘟", "我的阿狸"};

    static void show(final MainActivity a) {
        a.renderPage("我的云盘 · 扫码与账号配置", "NETDISK · ACCOUNT");
        AppLog.i("NetdiskSheet", "open order=" + a.prefs.getInt(ORDER_KEY, 0));
        final int order = ((a.prefs.getInt(ORDER_KEY, 0) % 4) + 4) % 4;
        final String selectedDrive = DRIVES[order];

        // 盘序 banner
        LinearLayout banner = a.row();
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        banner.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView orderText = a.text("盘序优先：" + orderLabels(order), 12, MainActivity.WHITE);
        banner.addView(orderText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView switchOrder = a.text("切换盘序", 12, 0xFFFFB3C8);
        switchOrder.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        switchOrder.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x1affffff, 999));
        switchOrder.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(switchOrder);
        switchOrder.setOnClickListener(
                v -> {
                    a.prefs.edit().putInt(ORDER_KEY, (order + 1) % 4).apply();
                    AppLog.i("NetdiskSheet", "switch order=" + ((order + 1) % 4));
                    show(a);
                });
        banner.addView(switchOrder);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.leftMargin = a.dp(16);
        bannerLp.rightMargin = a.dp(16);
        a.body.addView(banner, bannerLp);

        // 选中盘二维码卡片
        LinearLayout qrCard = a.vertical();
        qrCard.setGravity(Gravity.CENTER_HORIZONTAL);
        qrCard.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        qrCard.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
        TextView qrTitle =
                a.text(
                        ALIAS[order] + "（" + NetdiskQrLogin.name(selectedDrive) + "）扫码登录",
                        14,
                        MainActivity.WHITE);
        qrTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        qrCard.addView(qrTitle);
        boolean logged = NetdiskQrLogin.isLoggedIn(a, selectedDrive);
        TextView qrStatus =
                a.text(
                        logged
                                ? "已启用 " + ALIAS[order]
                                : NetdiskQrLogin.hasCredential(a, selectedDrive)
                                        ? "已有登录信息，可重新扫码"
                                        : "未登录 · 点击下方扫码",
                        12,
                        logged ? 0xff2ad47a : MainActivity.MUTED);
        qrStatus.setPadding(0, a.dp(6), 0, a.dp(10));
        qrCard.addView(qrStatus);
        LinearLayout qrActions = a.row();
        TextView scan = a.text(logged ? "重新扫码" : "扫码登录", 13, 0xff0b1220);
        scan.setPadding(a.dp(16), a.dp(9), a.dp(16), a.dp(9));
        scan.setBackground(UiTokens.round(a, 16, UiTokens.ACCENT));
        scan.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(scan);
        scan.setOnClickListener(
                v ->
                        NetdiskQrLogin.show(
                                a, selectedDrive, () -> a.ui.postDelayed(() -> show(a), 200)));
        qrActions.addView(scan);
        TextView manual = a.text("手动配置 Cookie / Token", 12, 0xFFFFB3C8);
        manual.setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9));
        manual.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(manual);
        manual.setOnClickListener(v -> manualDialog(a, selectedDrive));
        LinearLayout.LayoutParams manualLp = new LinearLayout.LayoutParams(-2, -2);
        manualLp.leftMargin = a.dp(8);
        qrActions.addView(manual, manualLp);
        qrCard.addView(qrActions);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(-1, -2);
        qrLp.leftMargin = a.dp(16);
        qrLp.rightMargin = a.dp(16);
        qrLp.topMargin = a.dp(12);
        a.body.addView(qrCard, qrLp);

        // 四盘 tile
        for (int i = 0; i < DRIVES.length; i++) {
            final String drive = DRIVES[i];
            final int displayIndex = (order + i) % 4;
            LinearLayout tile = a.row();
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
            boolean active = drive.equals(selectedDrive);
            tile.setBackground(
                    MobileDetailScreen.glassBg(
                            a, active ? 0xcc1d3350 : 0xcc16283f, 0xb3121d2e, 14));
            TextView name = a.text(ALIAS[displayIndex] + " · " + NetdiskQrLogin.name(drive), 13, MainActivity.WHITE);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout info = a.vertical();
            info.addView(name);
            boolean ok = NetdiskQrLogin.isLoggedIn(a, drive);
            TextView state =
                    a.text(
                            ok
                                    ? "已登录 · 启用中" + NetdiskQrLogin.capacityText(a, drive)
                                    : NetdiskQrLogin.hasCredential(a, drive)
                                            ? "登录信息未通过验证"
                                            : "未登录 · 点击扫码",
                            11,
                            ok ? 0xff2ad47a : MainActivity.MUTED);
            state.setPadding(0, a.dp(3), 0, 0);
            info.addView(state);
            tile.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView login = a.text(ok ? "管理" : "登录", 12, 0xFFB3C8);
            login.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            login.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(login);
            login.setOnClickListener(
                    v ->
                            NetdiskQrLogin.show(
                                    a, drive, () -> a.ui.postDelayed(() -> show(a), 200)));
            tile.addView(login);
            TextView cfg = a.text("手动配置", 11, 0xFFFFB3C8);
            cfg.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
            cfg.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(cfg);
            cfg.setOnClickListener(v -> manualDialog(a, drive));
            LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(-2, -2);
            cfgLp.leftMargin = a.dp(6);
            tile.addView(cfg, cfgLp);
            TextView clear = a.text("清除", 11, 0xffef4444);
            clear.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
            clear.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(clear);
            clear.setOnClickListener(
                    v -> {
                        if (!NetdiskQrLogin.hasCredential(a, drive)) {
                            a.toast(ALIAS[displayIndex] + " 未登录");
                            return;
                        }
                        NetdiskQrLogin.logout(a, drive);
                        CloudConfigScreen.clearWebCookies(drive);
                        a.toast("已清除 " + ALIAS[displayIndex]);
                        AppLog.i("NetdiskSheet", "clear drive=" + drive);
                        show(a);
                    });
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, -2);
            clearLp.leftMargin = a.dp(6);
            tile.addView(clear, clearLp);
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(-1, -2);
            tileLp.leftMargin = a.dp(16);
            tileLp.rightMargin = a.dp(16);
            tileLp.topMargin = a.dp(8);
            a.body.addView(tile, tileLp);
        }
        TextView tip =
                a.text(
                        "与电视端「我的云盘」一致：登录后可用于网盘线路；数据仅保存在本机。",
                        11,
                        MainActivity.MUTED);
        tip.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
        a.body.addView(tip);
    }

    private static void manualDialog(final MainActivity a, final String drive) {
        int aliasIndex = 0;
        for (int i = 0; i < DRIVES.length; i++) if (DRIVES[i].equals(drive)) aliasIndex = i;
        final String alias = ALIAS[aliasIndex];
        EditText input = new EditText(a);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setHint("粘贴 Cookie 或 Token…");
        input.setTextSize(13);
        new AppDialog.Builder(a)
                .setTitle(alias + " · 手动配置 Cookie / Token")
                .setMessage("可粘贴从电视端或浏览器获取的" + NetdiskQrLogin.name(drive) + " Cookie / RefreshToken：")
                .setView(input)
                .setPositiveButton(
                        "保存并启用",
                        (d, i) ->
                                NetdiskQrLogin.saveManual(
                                        a,
                                        drive,
                                        input.getText().toString(),
                                        () -> a.ui.postDelayed(() -> show(a), 200)))
                .setNegativeButton("取消", null)
                .show();
    }

    private static String orderLabels(int order) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) out.append(" > ");
            out.append(ALIAS[(order + i) % 4]);
        }
        return out.toString();
    }
}
