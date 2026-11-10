package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 1.8.12 云盘四盘卡片原地视图（首页/片库共用）：真实登录/清除，不跳页。 */
final class CloudInlineView {
    private CloudInlineView() {}

    static void build(
            final MainActivity a, final LinearLayout parent, final Runnable onChanged) {
        parent.removeAllViews();
        LinearLayout holder = parent;
        TextView title = a.text("云盘配置", 16, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, a.dp(10), 0, a.dp(6));
        holder.addView(title);
        String[] drives = {
            NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
        };
        for (final String drive : drives) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            row.setBackground(
                    MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
            boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
            String state =
                    logged
                            ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                            : NetdiskQrLogin.hasCredential(a, drive) ? "登录信息未通过验证" : "未登录";
            LinearLayout info = a.vertical();
            TextView name = a.text(NetdiskQrLogin.name(drive) + "网盘", 14, MainActivity.WHITE);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            info.addView(name);
            TextView stateView = a.text(state, 11, logged ? 0xff2ad47a : MainActivity.MUTED);
            stateView.setPadding(0, a.dp(3), 0, 0);
            info.addView(stateView);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView login = a.text("登录", 12, 0xff0b1220);
            login.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            login.setBackground(UiTokens.round(a, 16, UiTokens.ACCENT));
            login.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(login);
            login.setOnClickListener(
                    v ->
                            NetdiskQrLogin.show(
                                    a,
                                    drive,
                                    () -> {
                                        if (onChanged != null)
                                            a.ui.postDelayed(onChanged, 200);
                                    }));
            row.addView(login);
            TextView clear = a.text("清除", 12, 0xffef4444);
            clear.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            clear.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(clear);
            clear.setOnClickListener(
                    v -> {
                        if (!NetdiskQrLogin.hasCredential(a, drive)) {
                            a.toast("该盘当前未登录");
                            return;
                        }
                        NetdiskQrLogin.logout(a, drive);
                        CloudConfigScreen.clearWebCookies(drive);
                        a.toast("已清除" + NetdiskQrLogin.name(drive) + "登录");
                        AppLog.i("CloudConfig", "cleared drive=" + drive);
                        if (onChanged != null) onChanged.run();
                    });
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, -2);
            clearLp.leftMargin = a.dp(8);
            row.addView(clear, clearLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = a.dp(8);
            holder.addView(row, rowLp);
        }
        TextView sourcePanel = a.text("打开源插件账号面板", 13, 0xffffb3c8);
        sourcePanel.setGravity(Gravity.CENTER);
        sourcePanel.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        sourcePanel.setBackground(
                MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x1affffff, 999));
        sourcePanel.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(sourcePanel);
        sourcePanel.setOnClickListener(
                v -> new SettingsScreen(a).sourceAccountAction("LoginShow", null));
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, a.dp(48));
        sourceLp.topMargin = a.dp(6);
        holder.addView(sourcePanel, sourceLp);
    }

    static void showError(MainActivity a, LinearLayout parent, Throwable error) {
        showError(a, parent, error, null);
    }

    static void showError(
            MainActivity a, final LinearLayout parent, Throwable error, final Runnable retryAction) {
        parent.removeAllViews();
        TextView fail =
                a.text(
                        "云盘配置加载失败：" + (error == null ? "" : a.message(error)),
                        13,
                        0xffffb4ad);
        fail.setPadding(0, a.dp(12), 0, a.dp(8));
        parent.addView(fail);
        TextView retry = a.text("重试", 13, 0xffffb3c8);
        retry.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        retry.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(retry);
        retry.setOnClickListener(
                v -> {
                    if (retryAction != null) retryAction.run();
                    else parent.setVisibility(android.view.View.GONE);
                });
        parent.addView(retry);
    }
}
