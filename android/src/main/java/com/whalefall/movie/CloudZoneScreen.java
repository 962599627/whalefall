package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.24「云盘专区 · 本地」：只显示 App 本地凭据与本地操作（登录/退出/查看文件），
 * 不显示源插件登录态、不带源动作卡（源动作只在源侧入口）。
 */
final class CloudZoneScreen {
    private CloudZoneScreen() {}

    private static final String[] DRIVES = {
        NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
    };

    static void show(final MainActivity a) {
        a.renderPage("云盘专区 · 本地", "LOCAL CLOUD");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body != null) a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        renderInto(a, a.body, () -> show(a));
    }

    static void renderInto(
            final MainActivity a, final LinearLayout body, final Runnable onChanged) {
        body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        AppLog.i("Wallpaper", "page=CloudZone bg=transparent");
        AppLog.i("CloudZone", "renderInto inline local-only");
        TextView note = a.text("本页是 App 本地直连登录，不经过源插件", 11.5f, MainActivity.MUTED);
        note.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        body.addView(note);
        // 1.9.0：文件管理器入口
        TextView fileMgr = a.text("📁  文件管理（本地/网盘下载）", 12.5f, UiTokens.TEXT_PRIMARY);
        fileMgr.setGravity(Gravity.CENTER);
        fileMgr.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        fileMgr.setBackground(MobileDetailScreen.glassBg(a, 0x24ffffff, 0x33ffffff, 999));
        fileMgr.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(fileMgr);
        fileMgr.setOnClickListener(v -> FileManagerScreen.showFrom(a, 1));
        LinearLayout.LayoutParams fileMgrLp = new LinearLayout.LayoutParams(-1, -2);
        fileMgrLp.leftMargin = a.dp(16);
        fileMgrLp.rightMargin = a.dp(16);
        fileMgrLp.topMargin = a.dp(8);
        body.addView(fileMgr, fileMgrLp);
        // 1.8.27：源插件登录（只读状态 + 退出本机登录数据）
        addSourceSection(a, body, onChanged);

        float density = a.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(a.windowWidth() / density);
        int cols = widthDp < 600 ? 2 : widthDp < 900 ? 3 : (a.isTvDevice() || !a.isPortrait() ? 5 : 4);
        int gap = a.dp(12);
        int available = a.windowWidth() - a.dp(32);
        int cardW = Math.min((available - gap * (cols - 1)) / cols, a.dp(150));
        int cardH = Math.max(a.dp(178), Math.round(cardW * 1.22f));
        java.util.List<Runnable> click = new java.util.ArrayList<>();
        java.util.List<Integer> icons = new java.util.ArrayList<>();
        java.util.List<Integer> tints = new java.util.ArrayList<>();
        java.util.List<String> titles = new java.util.ArrayList<>();
        java.util.List<String> states = new java.util.ArrayList<>();

        icons.add(UiIcon.PLAY_CIRCLE);
        tints.add(UiTokens.ACCENT);
        titles.add("登入自己网盘");
        states.add("App 内四盘二维码登录");
        click.add(
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("App 内扫码登录（本地）")
                                .setItems(
                                        new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                        (d, index) ->
                                                NetdiskQrLogin.show(
                                                        a,
                                                        DRIVES[index],
                                                        () -> a.ui.postDelayed(onChanged, 200)))
                                .setNegativeButton("取消", null)
                                .show());

        for (final String drive : DRIVES) {
            boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
            boolean src = CloudInlineView.hasSourceLogin(a, drive);
            String appState =
                    logged
                            ? "App 内已登录" + NetdiskQrLogin.capacityText(a, drive)
                            : NetdiskQrLogin.hasCredential(a, drive) ? "App 内登录未通过验证" : "App 内未登录";
            String state =
                    "源插件：" + (src ? "已登录" : "未检测到") + "\n" + appState;
            if (NetdiskQrLogin.BAIDU.equals(drive) || NetdiskQrLogin.ALI.equals(drive))
                state = state + "\n仅登录/退出 · 暂不支持浏览";
            AppLog.i(
                    "CloudZone",
                    "drive="
                            + drive
                            + " app="
                            + (logged ? "logged" : "absent")
                            + " source="
                            + (src ? "logged" : "absent"));
            icons.add(UiIcon.CLOUD);
            tints.add(
                    NetdiskQrLogin.QUARK.equals(drive)
                            ? 0xFF5B8CFF
                            : NetdiskQrLogin.UC.equals(drive)
                                    ? 0xFFFF8A3D
                                    : NetdiskQrLogin.BAIDU.equals(drive) ? 0xFF3D7EFF : 0xFF8A6BFF);
            titles.add("退出 App 内" + NetdiskQrLogin.name(drive) + "登录");
            states.add(state);
            click.add(
                    () -> {
                        if (!NetdiskQrLogin.hasCredential(a, drive)) {
                            a.toast("App 内未登录" + NetdiskQrLogin.name(drive));
                            return;
                        }
                        new AppDialog.Builder(a)
                                .setTitle("退出 App 内" + NetdiskQrLogin.name(drive) + "登录？")
                                .setMessage("只清除 App 本地凭据与 WebView Cookie，不影响源插件登录。")
                                .setPositiveButton(
                                        "退出",
                                        (d, i) -> {
                                            NetdiskQrLogin.logout(a, drive);
                                            CloudConfigScreen.clearWebCookies(drive);
                                            a.toast(
                                                    "已退出 App 内"
                                                            + NetdiskQrLogin.name(drive)
                                                            + "登录");
                                            AppLog.i("CloudZone", "logout drive=" + drive);
                                            if (onChanged != null) onChanged.run();
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    });
        }

        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < titles.size()) {
            LinearLayout row = a.row();
            for (int c = 0; c < cols && index < titles.size(); c++, index++) {
                final int cardIndex = index;
                LinearLayout card = a.vertical();
                card.setGravity(Gravity.CENTER);
                card.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(8));
                card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
                card.setFocusable(true);
                MobileDetailScreen.bindTouchFeedback(card);
                card.setOnClickListener(v -> click.get(cardIndex).run());
                ImageView icon = new ImageView(a);
                icon.setImageResource(UiIcon.res(icons.get(cardIndex)));
                icon.setColorFilter(tints.get(cardIndex), PorterDuff.Mode.SRC_IN);
                card.addView(icon, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
                TextView title = a.text(titles.get(cardIndex), 12, MainActivity.WHITE);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setGravity(Gravity.CENTER);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
                titleLp.topMargin = a.dp(6);
                card.addView(title, titleLp);
                TextView state = a.text(states.get(cardIndex), 9.5f, MainActivity.MUTED);
                state.setGravity(Gravity.CENTER);
                state.setMaxLines(2);
                state.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(state, new LinearLayout.LayoutParams(-1, -2));
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
        body.addView(grid, gridLp);

        // 1.8.25：夸克/UC 文件入口常驻；App 凭据优先，其次源插件 spUtils 登录数据
        TextView head = a.text("本地网盘文件", 14, UiTokens.ACCENT_SOFT);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
        body.addView(head);
        body.addView(fileRow(a, QuarkUcApi.QUARK, true));
        body.addView(fileRow(a, QuarkUcApi.UC, true));
    }

    /** 解析源插件 spUtils 登录 Cookie（quark_ck/quarkSt、uc_ck/ucSt；Base64 解码）。 */
    static String sourceCookie(MainActivity a, QuarkUcApi.Provider provider) {
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            String prefix = provider.key;
            String ck = null;
            String ticket = null;
            for (String key : prefs.getAll().keySet()) {
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                if (!lower.startsWith(prefix)) continue;
                Object value = prefs.getAll().get(key);
                if (value == null) continue;
                String text = String.valueOf(value).trim();
                if (text.isEmpty()) continue;
                if (lower.endsWith("ck") || lower.contains("cookie")) ck = text;
                else if (lower.endsWith("st") || lower.contains("ticket")) ticket = text;
            }
            if (ck != null) {
                String decoded = decodeBase64(ck);
                if (decoded != null && decoded.contains("=")) return decoded;
                if (ck.contains("=")) return ck;
            }
            if (ticket != null) return prefix + "_st=" + ticket;
        } catch (Throwable error) {
            AppLog.i("CloudZone", "sourceCookie error=" + error.getClass().getSimpleName());
        }
        return "";
    }

    private static String decodeBase64(String value) {
        try {
            byte[] bytes =
                    android.util.Base64.decode(
                            value, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
        try {
            byte[] bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LinearLayout fileRow(
            MainActivity a, final QuarkUcApi.Provider provider, boolean alwaysVisible) {
        final String appCookie = QuarkUcApi.cookie(a, provider);
        final boolean appVerified = QuarkUcApi.verified(a, provider);
        final String sourceCookie = sourceCookie(a, provider);
        String primary;
        boolean primarySource;
        String alt;
        boolean altSource;
        String reason;
        if (!appCookie.isEmpty() && appVerified) {
            primary = appCookie;
            primarySource = false;
            alt = sourceCookie;
            altSource = true;
            reason = "verified";
        } else if (!sourceCookie.isEmpty()) {
            primary = sourceCookie;
            primarySource = true;
            alt = appCookie;
            altSource = false;
            reason = appCookie.isEmpty() ? "absent" : "no-verify";
        } else if (!appCookie.isEmpty()) {
            primary = appCookie;
            primarySource = false;
            alt = "";
            altSource = false;
            reason = "no-verify";
        } else {
            primary = "";
            primarySource = false;
            alt = "";
            altSource = false;
            reason = "absent";
        }
        AppLog.i(
                "QuarkUc",
                "pick credential="
                        + (primary.isEmpty() ? "none" : primarySource ? "source" : "app")
                        + " reason="
                        + reason);
        final String chosen = primary;
        final boolean chosenSource = primarySource;
        final String backup = alt;
        final boolean backupSource = altSource;
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView name =
                a.text(
                        "查看" + provider.name + "网盘文件"
                                + (chosen.isEmpty()
                                        ? ""
                                        : chosenSource ? "（源插件）" : "（App 内）"),
                        13,
                        MainActivity.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = a.text(chosen.isEmpty() ? "未登录" : "打开", 12, 0xFFFFB3C8);
        go.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        row.addView(go);
        row.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                v -> {
                    if (!chosen.isEmpty()) {
                        AppLog.i(
                                "QuarkUc",
                                "open files provider="
                                        + provider.key
                                        + " credential="
                                        + (chosenSource ? "source" : "app")
                                        + " fallback="
                                        + (!backup.isEmpty()));
                        QuarkUcFiles.show(
                                a, provider, chosen, chosenSource, backup, backupSource);
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle(provider.name + "网盘未登录")
                            .setMessage("该盘未登录：可扫码登录（App 内），或在源面板登录后使用源插件数据。")
                            .setPositiveButton(
                                    "去登录",
                                    (d, i) ->
                                            NetdiskQrLogin.show(
                                                    a,
                                                    provider.key,
                                                    () -> a.ui.postDelayed(() -> show(a), 200)))
                            .setNegativeButton("取消", null)
                            .show();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.bottomMargin = a.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    // ---------------- 源插件登录（本机 spUtils，可退出） ----------------

    static void addSourceSection(
            final MainActivity a, final LinearLayout body, final Runnable onChanged) {
        TextView head = a.text("源插件登录（本机数据，可退出）", 14, UiTokens.ACCENT_SOFT);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(a.dp(16), a.dp(12), 0, a.dp(8));
        LinearLayout headRow = a.row();
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(a.dp(16), a.dp(8), a.dp(16), 0);
        headRow.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView panelLogin = a.text("打开源面板登录", 11, 0xff0b1220);
        panelLogin.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        panelLogin.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
        panelLogin.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(panelLogin);
        panelLogin.setOnClickListener(
                v -> {
                    AppLog.i("SourceLogout", "open source panel login");
                    new SettingsScreen(a).sourceAccountAction("LoginShow", null);
                });
        headRow.addView(panelLogin);
        body.addView(headRow);
        for (final String drive : DRIVES) {
            final java.util.List<String> keys = sourceKeys(a, drive);
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
            row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
            TextView name = a.text(NetdiskQrLogin.name(drive) + "网盘", 13, MainActivity.WHITE);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView state =
                    a.text(
                            keys.isEmpty() ? "未检测到登录" : "已登录（本机键 " + keys.size() + "）",
                            11,
                            keys.isEmpty() ? MainActivity.MUTED : 0xff2ad47a);
            row.addView(state);
            // 1.9.8：每行「登录」按钮 → 源面板（登录只能在那里完成）
            TextView login = a.text("登录", 11.5f, 0xff0b1220);
            login.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            login.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
            login.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(login);
            login.setOnClickListener(
                    v -> {
                        AppLog.i("SourceLogout", "open source panel login drive=" + drive);
                        new SettingsScreen(a).sourceAccountAction("LoginShow", null);
                    });
            LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(-2, -2);
            loginLp.leftMargin = a.dp(8);
            row.addView(login, loginLp);
            TextView exit =
                    a.text("退出源插件登录", 11.5f, keys.isEmpty() ? 0x66ffffff : 0xFFFFB3C8);
            exit.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            exit.setBackground(
                    MobileDetailScreen.glassBg(
                            a, keys.isEmpty() ? 0x14ffffff : 0x24ff8fb1, 0x1affffff, 999));
            exit.setFocusable(!keys.isEmpty());
            LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-2, -2);
            exitLp.leftMargin = a.dp(8);
            row.addView(exit, exitLp);
            if (keys.isEmpty()) {
                exit.setAlpha(0.6f);
                exit.setOnClickListener(
                        v -> a.toast("源插件未检测到" + NetdiskQrLogin.name(drive) + "登录"));
            } else {
                MobileDetailScreen.bindTouchFeedback(exit);
                exit.setOnClickListener(
                        v ->
                                new AppDialog.Builder(a)
                                        .setTitle("退出源插件" + NetdiskQrLogin.name(drive) + "登录？")
                                        .setMessage(
                                                "将删除本机保存的源插件登录数据（"
                                                        + join(keys)
                                                        + "），并重载插件使其显示未登录。"
                                                        + "可能需要在源面板重新扫码。此操作只影响本机，不影响云端账号。")
                                        .setPositiveButton(
                                                "退出",
                                                (d, i) -> sourceLogout(a, drive, keys, onChanged))
                                        .setNegativeButton("取消", null)
                                        .show());
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.leftMargin = a.dp(16);
            rowLp.rightMargin = a.dp(16);
            rowLp.topMargin = a.dp(6);
            body.addView(row, rowLp);
        }
        boolean stale = false;
        for (String drive : DRIVES) if (!sourceKeys(a, drive).isEmpty()) stale = true;
        if (stale && System.currentTimeMillis() - lastLogoutAt < 8000) {
            TextView again = a.text("插件可能仍缓存，点此再次刷新", 11.5f, UiTokens.ACCENT);
            again.setGravity(Gravity.CENTER);
            again.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            again.setBackground(MobileDetailScreen.glassBg(a, 0x24ffffff, 0x33ffffff, 999));
            again.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(again);
            again.setOnClickListener(
                    v -> {
                        reloadPlugin(a);
                        AppLog.i("SourceLogout", "manual refresh");
                        a.toast("已刷新");
                        a.ui.postDelayed(() -> onChanged.run(), 200);
                    });
            LinearLayout.LayoutParams againLp = new LinearLayout.LayoutParams(-1, -2);
            againLp.leftMargin = a.dp(16);
            againLp.rightMargin = a.dp(16);
            againLp.topMargin = a.dp(8);
            body.addView(again, againLp);
        }
        TextView all = a.text("清除全部源插件登录数据", 12, 0xffef4444);
        all.setGravity(Gravity.CENTER);
        all.setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9));
        all.setBackground(MobileDetailScreen.glassBg(a, 0x14ff6b81, 0x66ff6b81, 999));
        all.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(all);
        all.setOnClickListener(
                v -> {
                    final java.util.List<String> allKeys = new java.util.ArrayList<>();
                    for (String drive : DRIVES) allKeys.addAll(sourceKeys(a, drive));
                    if (allKeys.isEmpty()) {
                        a.toast("未检测到源插件登录数据");
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("清除全部源插件登录数据？")
                            .setMessage(
                                    "将删除四个盘在本机保存的源插件登录键（"
                                            + join(allKeys)
                                            + "），并重载插件。可能需要在源面板重新扫码；只影响本机。")
                            .setPositiveButton(
                                    "全部清除",
                                    (d, i) -> {
                                        int removed = removeSourceKeys(a, allKeys);
                                        AppLog.i(
                                                "SourceLogout",
                                                "drive=all keys=" + allKeys + " removed=" + removed);
                                        reloadPlugin(a);
                                        lastLogoutAt = System.currentTimeMillis();
                                        AppLog.i(
                                                "SourceLogout",
                                                "refresh drive=all keys=" + allKeys);
                                        a.toast("已清除，正在刷新源插件状态…");
                                        a.ui.postDelayed(
                                                () -> {
                                                    if (onChanged != null) onChanged.run();
                                                },
                                                200);
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-1, -2);
        allLp.leftMargin = a.dp(16);
        allLp.rightMargin = a.dp(16);
        allLp.topMargin = a.dp(8);
        body.addView(all, allLp);
    }

    /** 精确匹配该盘登录键：quark_ck/quarkSt、uc_ck/ucSt、bdSt/bd_ck/baidu_*、aliSt/ali_ck/ali_*。 */
    static java.util.List<String> sourceKeys(MainActivity a, String drive) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            for (String key : prefs.getAll().keySet())
                if (CloudInlineView.isSourceCredentialKey(key, drive)) out.add(key);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static long lastLogoutAt;

    static void sourceLogout(
            final MainActivity a,
            final String drive,
            final java.util.List<String> keys,
            final Runnable onChanged) {
        int removed = removeSourceKeys(a, keys);
        AppLog.i("SourceLogout", "drive=" + drive + " keys=" + keys + " removed=" + removed);
        reloadPlugin(a);
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            AppLog.i(
                    "SourceLogout",
                    "drive=" + drive + " remainingSpUtilsKeys=" + prefs.getAll().keySet());
        } catch (Throwable ignored) {
        }
        a.toast("已清除，正在刷新源插件状态…");
        lastLogoutAt = System.currentTimeMillis();
        AppLog.i("SourceLogout", "refresh drive=" + drive + " keys=" + keys);
        if (onChanged != null) a.ui.postDelayed(() -> onChanged.run(), 200);
    }

    static int removeSourceKeys(MainActivity a, java.util.List<String> keys) {
        int removed = 0;
        try {
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("spUtils", android.content.Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : keys) {
                edit.remove(key);
                removed++;
            }
            edit.apply();
        } catch (Throwable ignored) {
        }
        return removed;
    }

    private static void reloadPlugin(MainActivity a) {
        try {
            a.closeEngines();
        } catch (Throwable ignored) {
        }
    }

    private static String join(java.util.List<String> keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append(", ");
            out.append(key);
        }
        return out.toString();
    }
}
