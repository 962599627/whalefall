package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.17 云盘配置（原地、两套登录严格分区）：
 * A 源插件登录（只读，来自 spUtils 登录键；唯一操作=打开源面板）
 * B App 本地登录（可管理：二维码登录 / 退出登录，真清 App 内凭据 + WebView Cookie）
 * C 源动作（自定排序 / 线路排序 / 推送授权，2.5s 无响应如实提示）
 */
final class CloudInlineView {
    private CloudInlineView() {}

    private static final int QUARK_TINT = 0xFF5B8CFF;
    private static final int UC_TINT = 0xFFFF8A3D;
    private static final int BAIDU_TINT = 0xFF3D7EFF;
    private static final int ALI_TINT = 0xFF8A6BFF;

    static void build(final MainActivity a, final LinearLayout parent, final Runnable onChanged) {
        parent.removeAllViews();
        TextView title = a.text("云盘配置", 17, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(2));
        parent.addView(title);
        TextView sub =
                a.text(
                        "「源插件登录」与「App 本地登录」是两套，互不影响",
                        11.5f,
                        MainActivity.MUTED);
        sub.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        parent.addView(sub);

        // ---------------- A. 源插件登录（只读） ----------------
        section(a, parent, "源插件登录（由配置源管理）");
        LinearLayout srcBox = a.vertical();
        srcBox.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        srcBox.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        String[] drives = {
            NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
        };
        for (String drive : drives) {
            boolean logged = hasSourceLogin(a, drive);
            AppLog.i(
                    "CloudConfig",
                    "section=source drive=" + drive + " state=" + (logged ? "logged" : "absent"));
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, a.dp(6), 0, a.dp(6));
            TextView name = a.text(NetdiskQrLogin.name(drive) + "网盘", 13, MainActivity.WHITE);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView state =
                    a.text(
                            logged ? "已登录" : "未检测到",
                            12,
                            logged ? 0xff2ad47a : MainActivity.MUTED);
            row.addView(state);
            srcBox.addView(row);
        }
        parent.addView(srcBox, new LinearLayout.LayoutParams(-1, -2));
        TextView note = a.text("状态由源插件提供，停用/退出请在源面板操作", 11, MainActivity.MUTED);
        note.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(8));
        parent.addView(note);
        parent.addView(
                button(a, "打开源面板", 0x24ff8fb1, 0xFFFFB3C8, 0x1affffff,
                        () -> new SettingsScreen(a).sourceAccountAction("LoginShow", null)));

        // ---------------- B. App 本地登录（可管理） ----------------
        section(a, parent, "App 本地登录（本 App 直连，可选）");
        buildAppGrid(a, parent, drives, onChanged);

        // ---------------- C. 源动作（可选） ----------------
        section(a, parent, "源动作（由配置源提供，可选）");
        buildSourceActions(a, parent, onChanged);
        AppLog.i("CloudConfig", "sections=3 drives=" + drives.length);
    }

    private static void section(MainActivity a, LinearLayout parent, String title) {
        TextView header = a.text(title, 14, UiTokens.ACCENT_SOFT);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
        parent.addView(header);
    }

    private static TextView button(
            MainActivity a,
            String label,
            int fill,
            int textColor,
            int stroke,
            Runnable click) {
        TextView view = a.text(label, 13, textColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        view.setBackground(MobileDetailScreen.glassBg(a, fill, stroke, 999));
        view.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(view);
        view.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, a.dp(46));
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.topMargin = a.dp(4);
        view.setLayoutParams(lp);
        return view;
    }

    private static void buildAppGrid(
            MainActivity a,
            LinearLayout parent,
            String[] drives,
            final Runnable onChanged) {
        float density = a.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(a.windowWidth() / density);
        int cols;
        if (widthDp < 600) cols = 2;
        else if (widthDp < 900) cols = 3;
        else if (a.isTvDevice() || !a.isPortrait()) cols = 5;
        else cols = 4;
        int gap = a.dp(12);
        int available = a.windowWidth() - a.dp(32);
        int cardW = Math.min((available - gap * (cols - 1)) / cols, a.dp(150));
        int cardH = Math.max(a.dp(170), Math.round(cardW * 1.3f));
        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < drives.length) {
            LinearLayout row = a.row();
            for (int c = 0; c < cols && index < drives.length; c++, index++) {
                LinearLayout card = appCard(a, drives[index], cardW, cardH, onChanged);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) lp.leftMargin = gap;
                row.addView(card, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (grid.getChildCount() > 0) rowLp.topMargin = gap;
            grid.addView(row, rowLp);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.leftMargin = a.dp(16);
        gridLp.rightMargin = a.dp(16);
        parent.addView(grid, gridLp);
    }

    private static LinearLayout appCard(
            MainActivity a, final String drive, int cardW, int cardH, final Runnable onChanged) {
        boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
        boolean pending = !logged && NetdiskQrLogin.hasCredential(a, drive);
        AppLog.i(
                "CloudConfig",
                "section=app drive=" + drive + " state=" + (logged ? "logged" : pending ? "pending" : "absent"));
        String state =
                logged
                        ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                        : pending ? "登录未通过验证" : "未登录";
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER);
        card.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(8));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        int tint;
        switch (drive) {
            case NetdiskQrLogin.QUARK: tint = QUARK_TINT; break;
            case NetdiskQrLogin.UC: tint = UC_TINT; break;
            case NetdiskQrLogin.BAIDU: tint = BAIDU_TINT; break;
            default: tint = ALI_TINT; break;
        }
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
        icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        card.addView(icon, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
        TextView name = a.text(NetdiskQrLogin.name(drive) + "网盘", 12.5f, MainActivity.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = a.dp(6);
        card.addView(name, nameLp);
        TextView stateView = a.text(state, 10, MainActivity.MUTED);
        stateView.setGravity(Gravity.CENTER);
        stateView.setMaxLines(2);
        stateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(stateView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER);
        TextView login = a.text("登录", 11, 0xff0b1220);
        login.setPadding(a.dp(9), a.dp(4), a.dp(9), a.dp(4));
        login.setBackground(UiTokens.round(a, 14, UiTokens.ACCENT));
        login.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(login);
        login.setOnClickListener(
                v -> {
                    AppLog.i("CloudConfig", "section=app drive=" + drive + " login=qrcode-local");
                    NetdiskQrLogin.show(
                            a, drive, () -> a.ui.postDelayed(onChanged, 200));
                });
        actions.addView(login);
        TextView logout = a.text("退出登录", 11, 0xffef4444);
        logout.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
        logout.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(logout);
        logout.setOnClickListener(
                v -> {
                    if (!NetdiskQrLogin.hasCredential(a, drive)) {
                        a.toast("App 内未登录" + NetdiskQrLogin.name(drive) + "，无需退出");
                        return;
                    }
                    NetdiskQrLogin.logout(a, drive);
                    CloudConfigScreen.clearWebCookies(drive);
                    a.toast("已退出 App 内" + NetdiskQrLogin.name(drive) + "登录");
                    AppLog.i("CloudConfig", "section=app drive=" + drive + " logout=1");
                    if (onChanged != null) onChanged.run();
                });
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-2, -2);
        logoutLp.leftMargin = a.dp(6);
        actions.addView(logout, logoutLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = a.dp(6);
        card.addView(actions, actionsLp);
        return card;
    }

    private static String driveOf(int index) {
        switch (index) {
            case 0: return NetdiskQrLogin.QUARK;
            case 1: return NetdiskQrLogin.UC;
            case 2: return NetdiskQrLogin.BAIDU;
            default: return NetdiskQrLogin.ALI;
        }
    }

    private static void buildSourceActions(
            final MainActivity a, LinearLayout parent, final Runnable onChanged) {
        LinearLayout actions = a.vertical();
        actions.setPadding(a.dp(16), 0, a.dp(16), a.dp(4));
        String[][] defs = {
            {"自定网盘排序", "panSortShow"},
            {"网盘线路前后排序", "panSourceSortShow"},
            {"手动推送网盘授权", "pushCkShow"}
        };
        for (final String[] def : defs) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
            row.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(row);
            TextView label = a.text(def[0], 13, MainActivity.WHITE);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView go = a.text("执行", 12, 0xFFFFB3C8);
            go.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
            row.addView(go);
            row.setOnClickListener(
                    v -> {
                        final String action = def[1];
                        AppLog.i("CloudConfig", "sourceAction=" + action);
                        a.toast("正在请求源：" + def[0]);
                        final boolean[] done = {false};
                        new SettingsScreen(a)
                                .sourceAccountAction(
                                        action,
                                        () -> {
                                            done[0] = true;
                                            AppLog.i(
                                                    "CloudConfig",
                                                    "sourceAction done=" + action);
                                            if (onChanged != null) onChanged.run();
                                        });
                        a.ui.postDelayed(
                                () -> {
                                    if (done[0]) return;
                                    AppLog.i(
                                            "CloudConfig",
                                            "sourceAction timeout action=" + action);
                                    a.toast("当前源不支持该操作");
                                },
                                2500);
                    });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = a.dp(8);
            actions.addView(row, rowLp);
        }
        parent.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------- 源插件登录态（只读） ----------------

    static boolean hasSourceLogin(MainActivity a, String drive) {
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!isSourceCredentialKey(entry.getKey(), drive)) continue;
                Object value = entry.getValue();
                if (value != null && !String.valueOf(value).trim().isEmpty()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isSourceCredentialKey(String key, String drive) {
        if (key == null) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("url")
                || lower.contains("path")
                || lower.contains("time")
                || lower.contains("count")
                || lower.contains("down")
                || lower.contains("pid")) return false;
        boolean alias;
        switch (drive) {
            case NetdiskQrLogin.QUARK: alias = lower.contains("quark"); break;
            case NetdiskQrLogin.UC: alias = lower.contains("uc"); break;
            case NetdiskQrLogin.BAIDU:
                alias = lower.contains("baidu") || lower.contains("bduss") || lower.startsWith("bd");
                break;
            default:
                alias = lower.contains("ali") || lower.contains("alipan") || lower.contains("adrive");
                break;
        }
        if (!alias) return false;
        return lower.contains("ck")
                || lower.contains("st")
                || lower.contains("cookie")
                || lower.contains("token")
                || lower.contains("auth")
                || lower.contains("pass");
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
        fail.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(8));
        parent.addView(fail);
        TextView retry = a.text("重试", 13, 0xffffb3c8);
        retry.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        retry.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(retry);
        retry.setOnClickListener(
                v -> {
                    if (retryAction != null) retryAction.run();
                    else parent.setVisibility(View.GONE);
                });
        parent.addView(retry);
    }
}
