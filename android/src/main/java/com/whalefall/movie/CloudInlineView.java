package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.18 云盘配置（图2 八卡网格，真实功能）：
 * 登入自己网盘 / 清除四盘 / 排序 / 线路排序 / 推送授权。
 * 每卡两行状态：源插件：已登录/未检测到；App 内：已登录/未登录。
 * 清除卡 → 选择「退出源插件登录（只打开源面板）」或「退出 App 内登录（真清）」。
 */
final class CloudInlineView {
    private CloudInlineView() {}

    private static final int QUARK_TINT = 0xFF5B8CFF;
    private static final int UC_TINT = 0xFFFF8A3D;
    private static final int BAIDU_TINT = 0xFF3D7EFF;
    private static final int ALI_TINT = 0xFF8A6BFF;

    static void build(final MainActivity a, final LinearLayout parent, final Runnable onChanged) {
        build(
                a,
                parent,
                onChanged,
                "云盘配置",
                "源插件配置：登录/停用请用源面板；下方为 App 内登录（第二套）");
    }

    /** 1.8.21：可自定义标题/副标题，供「云盘专区（本地）」复用同一套 8 卡网格。 */
    static void build(
            final MainActivity a,
            final LinearLayout parent,
            final Runnable onChanged,
            String heading,
            String subtitle) {
        parent.removeAllViews();
        TextView title = a.text(heading, 17, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(2));
        parent.addView(title);
        TextView sub = a.text(subtitle, 11.5f, MainActivity.MUTED);
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
        int cardH = Math.max(a.dp(178), Math.round(cardW * 1.22f));

        java.util.List<Card> cards = new java.util.ArrayList<>();
        cards.add(
                new Card(
                        UiIcon.PLAY_CIRCLE,
                        UiTokens.ACCENT,
                        "登入自己网盘",
                        "",
                        () ->
                                new AppDialog.Builder(a)
                                        .setTitle("App 内扫码登录（本地）")
                                        .setItems(
                                                new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                                (d, index) ->
                                                        NetdiskQrLogin.show(
                                                                a,
                                                                driveOf(index),
                                                                () ->
                                                                        a.ui.postDelayed(
                                                                                onChanged, 200)))
                                        .setNegativeButton("取消", null)
                                        .show(),
                        null));
        cards.add(clearCard(a, NetdiskQrLogin.QUARK, "清除夸克登录", QUARK_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.UC, "清除 UC 登录", UC_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.BAIDU, "清除百度登录", BAIDU_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.ALI, "清除阿里登录", ALI_TINT, onChanged));
        cards.add(
                sourceCard(a, UiIcon.LAYERS, 0xFFFFB3C8, "自定网盘排序", "panSortShow", onChanged));
        cards.add(
                sourceCard(
                        a,
                        UiIcon.SWAP,
                        0xFFFFB3C8,
                        "网盘线路前后排序",
                        "panSourceSortShow",
                        onChanged));
        cards.add(
                sourceCard(a, UiIcon.TV, 0xFFFF8FB1, "手动推送网盘授权", "pushCkShow", onChanged));

        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < cards.size()) {
            LinearLayout row = a.row();
            for (int c = 0; c < cols && index < cards.size(); c++, index++) {
                LinearLayout cardView = cardView(a, cards.get(index), cardH);
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

    private static String driveOf(int index) {
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
                "section=source drive=" + drive + " state=" + (srcLogged ? "logged" : "absent"));
        String src = "源插件：" + (srcLogged ? "已登录" : "未检测到");
        String app =
                "App 内："
                        + (appLogged
                                ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                                : NetdiskQrLogin.hasCredential(a, drive)
                                        ? "未通过验证"
                                        : "未登录");
        final String state = src + "\n" + app;
        return new Card(
                UiIcon.CLOUD,
                tint,
                title,
                state,
                () -> {
                    final java.util.List<String> options = new java.util.ArrayList<>();
                    if (srcLogged) options.add("退出源插件登录（只打开源面板）");
                    if (NetdiskQrLogin.hasCredential(a, drive)) options.add("退出 App 内登录（真清）");
                    if (options.isEmpty()) {
                        a.toast("两套登录都未检测到" + NetdiskQrLogin.name(drive) + "，可先扫码登录");
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("清除" + NetdiskQrLogin.name(drive) + "登录")
                            .setItems(
                                    options.toArray(new String[0]),
                                    (d, index) -> {
                                        if (options.get(index).startsWith("退出源插件")) {
                                            new SettingsScreen(a)
                                                    .sourceAccountAction("LoginShow", null);
                                        } else {
                                            NetdiskQrLogin.logout(a, drive);
                                            CloudConfigScreen.clearWebCookies(drive);
                                            a.toast(
                                                    "已退出 App 内"
                                                            + NetdiskQrLogin.name(drive)
                                                            + "登录");
                                            AppLog.i(
                                                    "CloudConfig",
                                                    "section=app drive="
                                                            + drive
                                                            + " logout=1");
                                            if (onChanged != null) onChanged.run();
                                        }
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                },
                drive);
    }

    private static Card sourceCard(
            final MainActivity a,
            int icon,
            int tint,
            String title,
            final String action,
            final Runnable onChanged) {
        return new Card(
                icon,
                tint,
                title,
                "源动作：未知",
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
                    a.ui.postDelayed(
                            () -> {
                                if (done[0]) return;
                                AppLog.i("CloudConfig", "sourceAction timeout action=" + action);
                                a.toast("当前源不支持该操作");
                            },
                            2500);
                },
                null);
    }

    private static LinearLayout cardView(MainActivity a, final Card card, int cardH) {
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
        view.addView(icon, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
        TextView title = a.text(card.title, 12, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = a.dp(6);
        view.addView(title, titleLp);
        if (!card.subtitle.isEmpty()) {
            TextView sub = a.text(card.subtitle, 9.5f, MainActivity.MUTED);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = a.dp(3);
            view.addView(sub, subLp);
        }
        return view;
    }

    // ---------------- 源插件登录态（只读） ----------------

    /** 1.9.14：每盘精确登录键白名单（优先），避免启发式误判。 */
    static String[] exactLoginKeys(String drive) {
        switch (drive) {
            case NetdiskQrLogin.QUARK:
                return new String[] {"quarkSt", "quark_ck", "quark_cookie", "quark_st", "quark_token"};
            case NetdiskQrLogin.UC:
                return new String[] {"ucSt", "uc_ck", "uc_st", "uc_token"};
            case NetdiskQrLogin.BAIDU:
                return new String[] {"bdSt", "bd_ck", "baidu_ck", "bduss", "baidu_st"};
            default:
                return new String[] {"aliSt", "ali_ck", "ali_token", "ali_st"};
        }
    }

    /** 返回该盘本机登录键列表（只打键名，不打值）。 */
    static java.util.List<String> loginKeys(MainActivity a, String drive) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            java.util.Map<String, ?> all = prefs.getAll();
            for (String exact : exactLoginKeys(drive)) {
                Object value = all.get(exact);
                if (value != null && !String.valueOf(value).trim().isEmpty()) keys.add(exact);
            }
            if (keys.isEmpty()) {
                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                    String key = entry.getKey();
                    if (!isSourceCredentialKey(key, drive)) continue;
                    Object value = entry.getValue();
                    if (value != null && !String.valueOf(value).trim().isEmpty()) keys.add(key);
                }
            }
        } catch (Throwable ignored) {
        }
        AppLog.i(
                "SourceLogin",
                "drive=" + drive + " keys=" + keys + " state=" + (keys.isEmpty() ? "absent" : "logged"));
        return keys;
    }

    static boolean hasSourceLogin(MainActivity a, String drive) {
        return !loginKeys(a, drive).isEmpty();
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
            case NetdiskQrLogin.QUARK:
                alias = lower.startsWith("quark");
                break;
            case NetdiskQrLogin.UC:
                // 1.9.14：不用 contains("uc")，避免 success_token 之类误判
                alias = lower.startsWith("uc_") || lower.startsWith("ucst") || lower.startsWith("ucst");
                break;
            case NetdiskQrLogin.BAIDU:
                alias = lower.startsWith("bd") || lower.startsWith("baidu") || lower.contains("bduss");
                break;
            default:
                alias = lower.startsWith("ali")
                        || lower.startsWith("alipan")
                        || lower.startsWith("adrive");
                break;
        }
        if (!alias) return false;
        return lower.contains("ck")
                || lower.contains("st")
                || lower.contains("cookie")
                || lower.contains("token")
                || lower.contains("auth")
                || lower.contains("pass")
                || lower.contains("bduss");
    }

    private static final class Card {
        final int icon;
        final int tint;
        final String title;
        final String subtitle;
        final Runnable click;
        final String srcDrive;

        Card(int icon, int tint, String title, String subtitle, Runnable click, String srcDrive) {
            this.icon = icon;
            this.tint = tint;
            this.title = title;
            this.subtitle = subtitle;
            this.click = click;
            this.srcDrive = srcDrive;
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
