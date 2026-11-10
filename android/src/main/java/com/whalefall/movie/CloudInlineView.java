package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.14 云盘配置原地卡片网格（对齐友商图三）：深色玻璃方卡、上半大图标、下半标题+状态。
 * 真实功能：四盘二维码登录 / 真实清除登录 / 源动作排序与推送（失败如实提示，不造假数据）。
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
                        "源插件登录状态可在此查看/退出；App 内登录是第二套（二维码登录）",
                        11.5f,
                        MainActivity.MUTED);
        sub.setPadding(a.dp(16), 0, a.dp(16), a.dp(10));
        parent.addView(sub);

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
        int cardH = Math.round(cardW * 1.15f);

        java.util.List<Card> cards = new java.util.ArrayList<>();
        cards.add(
                new Card(
                        UiIcon.PLAY_CIRCLE,
                        UiTokens.ACCENT,
                        "登入自己网盘",
                        "四盘二维码登录",
                        () ->
                                new AppDialog.Builder(a)
                                        .setTitle("选择要登录的网盘")
                                        .setItems(
                                                new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                                (d, index) ->
                                                        NetdiskQrLogin.show(
                                                                a,
                                                                driveAt(index),
                                                                () ->
                                                                        a.ui.postDelayed(
                                                                                onChanged, 200)))
                                        .setNegativeButton("取消", null)
                                        .show()));
        cards.add(clearCard(a, NetdiskQrLogin.QUARK, "清除 App 内夸克登录", QUARK_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.UC, "清除 App 内优汐登录", UC_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.BAIDU, "清除 App 内嘟嘟登录", BAIDU_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.ALI, "清除 App 内阿狸登录", ALI_TINT, onChanged));
        cards.add(sourceCard(a, UiIcon.LAYERS, 0xFFFFB3C8, "自定网盘排序", "源动作 panSortShow", "panSortShow", onChanged));
        cards.add(sourceCard(a, UiIcon.SWAP, 0xFFFFB3C8, "网盘线路前后排序", "源动作 panSourceSortShow", "panSourceSortShow", onChanged));
        cards.add(sourceCard(a, UiIcon.TV, 0xFFFF8FB1, "手动推送网盘授权", "源动作 pushCkShow", "pushCkShow", onChanged));

        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < cards.size()) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.LEFT);
            for (int c = 0; c < cols && index < cards.size(); c++, index++) {
                LinearLayout cardView = cardView(a, cards.get(index), cardW, cardH, onChanged);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) lp.leftMargin = gap;
                row.addView(cardView, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (grid.getChildCount() > 0) rowLp.topMargin = gap;
            grid.addView(row, rowLp);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.leftMargin = a.dp(16);
        gridLp.rightMargin = a.dp(16);
        parent.addView(grid, gridLp);
        AppLog.i("CloudConfig", "grid cards=" + cards.size() + " cols=" + cols + " cardW=" + cardW);
    }

    private static String driveAt(int index) {
        switch (index) {
            case 0: return NetdiskQrLogin.QUARK;
            case 1: return NetdiskQrLogin.UC;
            case 2: return NetdiskQrLogin.BAIDU;
            default: return NetdiskQrLogin.ALI;
        }
    }

    private static Card clearCard(
            MainActivity a, final String drive, String title, final int tint, final Runnable onChanged) {
        boolean appLogged = NetdiskQrLogin.isLoggedIn(a, drive);
        boolean srcLogged = hasSourceLogin(a, drive);
        AppLog.i(
                "CloudConfig",
                "srcLogin drive=" + drive + " state=" + (srcLogged ? "logged" : "absent"));
        String appState =
                appLogged
                        ? "App 内已登录" + NetdiskQrLogin.capacityText(a, drive)
                        : NetdiskQrLogin.hasCredential(a, drive) ? "App 内登录未通过验证" : "App 内未登录";
        String state =
                (srcLogged ? "源插件已登录" : "源插件未登录")
                        + " · "
                        + appState;
        return new Card(
                UiIcon.CLOUD,
                tint,
                "清除" + NetdiskQrLogin.name(drive) + "登录",
                state,
                () -> {
                    java.util.List<String> options = new java.util.ArrayList<>();
                    if (srcLogged) options.add("退出源插件登录");
                    if (NetdiskQrLogin.hasCredential(a, drive)) options.add("退出 App 内登录");
                    if (options.isEmpty()) {
                        AppLog.i(
                                "CloudConfig",
                                "clear no-credential drive=" + drive + " prompt=source-panel");
                        new AppDialog.Builder(a)
                                .setTitle("App 内没有该盘登录")
                                .setMessage(
                                        "App 内与源插件都没有检测到"
                                                + NetdiskQrLogin.name(drive)
                                                + "登录。源内账号（你之前扫码登录的）需在源面板退出。要现在打开源面板吗？")
                                .setPositiveButton(
                                        "打开源面板",
                                        (d, i) ->
                                                new SettingsScreen(a)
                                                        .sourceAccountAction("LoginShow", null))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("清除" + NetdiskQrLogin.name(drive) + "登录")
                            .setItems(
                                    options.toArray(new String[0]),
                                    (d, index) -> {
                                        String chosen = options.get(index);
                                        if (chosen.startsWith("退出源插件")) {
                                            clearSourceLoginConfirm(a, drive, onChanged);
                                        } else {
                                            NetdiskQrLogin.logout(a, drive);
                                            CloudConfigScreen.clearWebCookies(drive);
                                            a.toast(
                                                    "已清除 App 内"
                                                            + NetdiskQrLogin.name(drive)
                                                            + "登录");
                                            AppLog.i(
                                                    "CloudConfig",
                                                    "cleared app-login drive=" + drive);
                                            if (onChanged != null) onChanged.run();
                                        }
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                },
                drive,
                srcLogged);
    }

    // ---------------- 源插件登录态（spUtils，可读可清） ----------------

    private static android.content.SharedPreferences spUtils(MainActivity a) {
        try {
            return a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasSourceLogin(MainActivity a, String drive) {
        android.content.SharedPreferences prefs = spUtils(a);
        if (prefs == null) return false;
        try {
            for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (isSourceCredentialKey(entry.getKey(), drive)) {
                    Object value = entry.getValue();
                    if (value != null && !String.valueOf(value).trim().isEmpty()) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 只删登录类键：排除 url/path/time/count/down 等非凭据键。 */
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
            case NetdiskQrLogin.QUARK:
                alias = lower.contains("quark");
                break;
            case NetdiskQrLogin.UC:
                alias = lower.contains("uc");
                break;
            case NetdiskQrLogin.BAIDU:
                alias = lower.contains("baidu") || lower.contains("bduss") || lower.startsWith("bd");
                break;
            default:
                alias =
                        lower.contains("ali")
                                || lower.contains("alipan")
                                || lower.contains("adrive");
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

    static void clearSourceLoginConfirm(
            final MainActivity a, final String drive, final Runnable onChanged) {
        new AppDialog.Builder(a)
                .setTitle("退出源插件" + NetdiskQrLogin.name(drive) + "登录？")
                .setMessage("将删除本机源插件的该盘登录数据，可能需要在源面板重新扫码登录。")
                .setPositiveButton(
                        "退出",
                        (d, i) -> {
                            int removed = clearSourceLogin(a, drive);
                            AppLog.i(
                                    "CloudConfig",
                                    "srcLogout drive=" + drive + " keysRemoved=" + removed);
                            a.toast(
                                    "已清除源插件"
                                            + NetdiskQrLogin.name(drive)
                                            + "登录，请回源面板确认已退出");
                            if (onChanged != null) onChanged.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    static int clearSourceLogin(MainActivity a, String drive) {
        android.content.SharedPreferences prefs = spUtils(a);
        if (prefs == null) return 0;
        int removed = 0;
        try {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : new java.util.ArrayList<>(prefs.getAll().keySet())) {
                if (isSourceCredentialKey(key, drive)) {
                    edit.remove(key);
                    removed++;
                }
            }
            edit.apply();
        } catch (Throwable ignored) {
        }
        return removed;
    }

    private static Card sourceCard(
            final MainActivity a,
            int icon,
            int tint,
            String title,
            final String action,
            final String subtitle,
            final Runnable onChanged) {
        return new Card(
                icon,
                tint,
                title,
                subtitle,
                () -> {
                    AppLog.i("CloudConfig", "sourceAction=" + action);
                    a.toast("正在请求源：" + title);
                    final boolean[] done = {false};
                    new SettingsScreen(a)
                            .sourceAccountAction(
                                    action,
                                    () -> {
                                        done[0] = true;
                                        AppLog.i("CloudConfig", "sourceAction done=" + action);
                                        if (onChanged != null) onChanged.run();
                                    });
                    // 源无响应/不支持：2.5s 内没有回调则如实提示
                    a.ui.postDelayed(
                            () -> {
                                if (done[0]) return;
                                AppLog.i("CloudConfig", "sourceAction timeout action=" + action);
                                a.toast("当前源不支持该操作");
                            },
                            2500);
                });
    }

    private static LinearLayout cardView(
            MainActivity a,
            final Card card,
            int cardW,
            int cardH,
            final Runnable onChanged) {
        LinearLayout view = a.vertical();
        view.setGravity(Gravity.CENTER);
        view.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(8));
        view.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        view.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(view);
        view.setOnClickListener(v -> card.click.run());
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(card.icon));
        icon.setColorFilter(card.tint, PorterDuff.Mode.SRC_IN);
        view.addView(icon, new LinearLayout.LayoutParams(a.dp(46), a.dp(46)));
        TextView title = a.text(card.title, 12.5f, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = a.dp(8);
        view.addView(title, titleLp);
        TextView sub = a.text(card.subtitle, 10.5f, MainActivity.MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(3);
        view.addView(sub, subLp);
        if (card.srcDrive != null) {
            // 1.8.16 独立「退出源插件登录」按钮（无键置灰，点击提示）
            TextView out =
                    a.text("退出源插件登录", 9.5f, card.srcLogged ? 0xFFFFB3C8 : 0x66ffffff);
            out.setGravity(Gravity.CENTER);
            out.setSingleLine(true);
            out.setEllipsize(android.text.TextUtils.TruncateAt.END);
            out.setPadding(a.dp(6), a.dp(3), a.dp(6), a.dp(3));
            out.setBackground(
                    MobileDetailScreen.glassBg(
                            a,
                            card.srcLogged ? 0x24ff8fb1 : 0x14ffffff,
                            0x1affffff,
                            999));
            out.setFocusable(card.srcLogged);
            LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(-1, -2);
            outLp.topMargin = a.dp(5);
            if (card.srcLogged) {
                MobileDetailScreen.bindTouchFeedback(out);
                out.setOnClickListener(
                        v ->
                                clearSourceLoginConfirm(a, card.srcDrive, onChanged));
            } else {
                out.setAlpha(0.6f);
                out.setOnClickListener(
                        v -> a.toast("源插件未检测到" + NetdiskQrLogin.name(card.srcDrive) + "登录"));
            }
            view.addView(out, outLp);
        }
        return view;
    }

    private static final class Card {
        final int icon;
        final int tint;
        final String title;
        final String subtitle;
        final Runnable click;
        final String srcDrive;
        final boolean srcLogged;

        Card(int icon, int tint, String title, String subtitle, Runnable click) {
            this(icon, tint, title, subtitle, click, null, false);
        }

        Card(
                int icon,
                int tint,
                String title,
                String subtitle,
                Runnable click,
                String srcDrive,
                boolean srcLogged) {
            this.icon = icon;
            this.tint = tint;
            this.title = title;
            this.subtitle = subtitle;
            this.click = click;
            this.srcDrive = srcDrive;
            this.srcLogged = srcLogged;
        }
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
