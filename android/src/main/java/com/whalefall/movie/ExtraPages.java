package com.whalefall.movie;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 手机 / 电视共用的设置子页面：关于鲸落、存储空间与缓存清理、观影统计、遥控器按键说明。
 *
 * <p>页面构建代码从 {@link MobileShell} 原样抽取（手机版行为零变化），TV 版 {@link SettingsScreen}
 * 复用同一套；另外提供 {@link #tvFocus} 给 TV 端把触屏控件变成可遥控聚焦、带焦点态。
 */
final class ExtraPages {
    private ExtraPages() {}

    /** 关于鲸落：品牌块 + 检查更新 / 开源许可 / 源码地址 / 设备与包名。 */
    static void about(MainActivity a, LinearLayout body) {
        LinearLayout brand = a.vertical();
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView logo = new android.widget.ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        int logoDp = a.isTvDevice() ? 96 : 76;
        brand.addView(logo, new LinearLayout.LayoutParams(a.dp(logoDp), a.dp(logoDp)));
        TextView name = a.text("鲸落", a.isTvDevice() ? 32 : 26, MainActivity.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = a.dp(12);
        brand.addView(name, nameLp);
        TextView sub = a.text("WHALEFALL · 沉入故事深处", a.isTvDevice() ? 15 : 11.5f, 0xffffb3c8);
        sub.setGravity(Gravity.CENTER);
        brand.addView(sub);
        TextView version =
                a.text(
                        "版本 " + UpdateManager.installedVersion(a),
                        a.isTvDevice() ? 15 : 12.5f,
                        0xff8d9cb6);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(-1, -2);
        verLp.topMargin = a.dp(6);
        brand.addView(version, verLp);
        a.gap(body, 14);
        body.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        sectionHeader(a, body, "关于软件");
        settingCard(
                a,
                body,
                6,
                "检查更新",
                "当前版本：" + UpdateManager.installedVersion(a),
                () -> a.open(new MainActivity.Screen(() -> new UpdateScreen(a).show()), true));
        settingCard(
                a,
                body,
                6,
                "开源许可",
                "GPL-3.0-only · 衍生自 MarcoMovie（Pilipala9919）",
                () -> {
                    String text =
                            "鲸落基于 MarcoMovie 二次开发，遵循 GNU General Public License v3.0（GPL-3.0-only）发布。\n\n"
                                    + "上游项目：MarcoMovie（作者 Pilipala）\n"
                                    + "本衍生版本保留原版权与许可声明，衍生代码同样以 GPL-3.0 开源。\n\n"
                                    + "本应用不提供任何影视内容，所有内容均来自用户自行配置的第三方片源。";
                    new AppDialog.Builder(a)
                            .setTitle("开源许可")
                            .setMessage(text)
                            .setNegativeButton("知道了", null)
                            .show();
                });
        // TV 无剪贴板（R3）：隐藏「复制源码地址」卡片
        if (!a.isTvDevice())
            settingCard(
                    a,
                    body,
                    6,
                    "复制项目源码地址",
                    "GitHub · 962599627/whalefall",
                    () -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        a.getSystemService(
                                                android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                        "whalefall",
                                        "https://github.com/962599627/whalefall"));
                        a.toast("源码地址已复制");
                    });
        settingCard(
                a,
                body,
                6,
                "设备与包名",
                android.os.Build.MANUFACTURER
                        + " "
                        + android.os.Build.MODEL
                        + " · Android "
                        + android.os.Build.VERSION.RELEASE
                        + " · "
                        + a.getPackageName(),
                () -> a.toast("应用包名：" + a.getPackageName()));
        a.gap(body, 12);
        TextView footer =
                a.text(
                        "本应用为开源软件，不提供任何影视资源；\n内容均来自用户自行配置的第三方接口。",
                        11.5f,
                        0xff8d9cb6);
        footer.setGravity(Gravity.CENTER);
        body.addView(footer);
    }

    /** 存储空间与缓存清理：统计各类占用并提供清理。 */
    static void storage(MainActivity a, LinearLayout body) {
        final java.io.File cache = a.getCacheDir();
        final java.io.File csp = new java.io.File(a.getFilesDir(), "csp");
        final java.io.File updates = new java.io.File(cache, "updates");
        long cacheBytes = dirSize(cache) - dirSize(updates);
        long cspBytes = dirSize(csp);
        long logBytes = logBytes(a);
        long pushBytes = dirSize(PushFiles.root(a));
        long total = cacheBytes + cspBytes + logBytes;
        sectionHeader(a, body, "缓存占用");
        body.addView(
                a.text(
                        "可清理缓存合计："
                                + android.text.format.Formatter.formatFileSize(
                                        a, Math.max(0, total)),
                        13.5f,
                        0xffffb3c8));
        a.gap(body, 8);
        storageRow(
                a,
                body,
                "临时缓存（图片/网页/JS 模块/磁力临时文件）",
                cacheBytes,
                () -> clearDir(cache, false));
        storageRow(a, body, "已下载的更新安装包", dirSize(updates), () -> clearDir(updates, true));
        storageRow(a, body, "插件缓存（JS/PY/JAR 运行缓存）", cspBytes, () -> clearDir(csp, true));
        storageRow(a, body, "运行日志", logBytes, () -> clearLogs(a));
        storageRow(
                a,
                body,
                "文件管理里上传的文件",
                pushBytes,
                () -> FilesScreen.show(a));
        a.gap(body, 12);
        settingCard(
                a,
                body,
                5,
                "一键清理全部缓存",
                "清理临时缓存、插件缓存和日志；不会删除观看历史、收藏和上传的文件",
                () -> {
                    clearDir(cache, true);
                    clearDir(csp, true);
                    clearLogs(a);
                    a.toast("缓存已清理");
                    a.refresh();
                });
    }

    /** 遥控器按键说明：电视端遥控器 / 键盘 / 手机手势对照。 */
    static void keymap(MainActivity a, LinearLayout body) {
        String[][] rows = {
            {"确定 / 回车", "播放 / 暂停；弹出控件时确认当前选项"},
            {"返回", "收起控件 → 再按退出播放"},
            {"上 / 下", "呼出工具条后选择按钮；直播时换台"},
            {"左 / 右", "快退 / 快进 10 秒；列表内移动焦点"},
            {"菜单键", "打开片源选择"},
            {"数字键", "直播时直接输入频道号换台"},
            {"首页三击上键", "回到首页顶部"},
            {"长按确定", "收藏当前频道（直播页）"},
            {"空格 / 回车（键盘）", "播放 / 暂停"},
            {"Esc（键盘）", "退出播放或返回上一页"},
            {"手机上双击", "播放 / 暂停"},
            {"手机左右滑动", "快退 / 快进"},
            {"手机左半屏上下滑", "调节屏幕亮度"},
            {"手机右半屏上下滑", "调节音量"},
            {"手机长按", "2 倍速快进"}
        };
        sectionHeader(a, body, "电视遥控器");
        for (int i = 0; i < 8; i++) keymapRow(a, body, rows[i][0], rows[i][1]);
        if (!a.isTvDevice()) {
            a.gap(body, 14);
            sectionHeader(a, body, "键盘 / 手机手势");
            for (int i = 8; i < rows.length; i++) keymapRow(a, body, rows[i][0], rows[i][1]);
        }
    }

    /** 观影统计：按本地观看历史统计本周数据。 */
    static void stats(MainActivity a, LinearLayout body) {
        java.util.List<WatchHistory.Entry> entries = a.watchHistory();
        long now = System.currentTimeMillis() / 1000;
        long weekAgo = now - 7 * 24 * 3600L;
        int weekTitles = 0;
        long weekSeconds = 0;
        java.util.Set<String> days = new java.util.HashSet<>();
        java.util.List<WatchHistory.Entry> week = new java.util.ArrayList<>();
        for (WatchHistory.Entry e : entries) {
            if (e.updated < weekAgo) continue;
            week.add(e);
            weekTitles++;
            weekSeconds += Math.max(0, Math.min(e.position, Math.max(1, e.duration))) / 1000L;
            days.add(
                    new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                            .format(new java.util.Date(e.updated * 1000L)));
        }
        long minutes = weekSeconds / 60;
        sectionHeader(a, body, "本周观影");
        LinearLayout metrics = a.row();
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        if (a.isTvDevice()) {
            // 只读页：让指标区可聚焦，上下键滚动页面（设计：首焦点=页面滚动区）
            metrics.setFocusable(true);
            metrics.setFocusableInTouchMode(true);
            metrics.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    v,
                                    focused,
                                    0xcc16283f,
                                    0xff3b5273,
                                    16,
                                    0xff8d9cb6,
                                    0xff8d9cb6,
                                    true,
                                    1.0f));
            tvScrollKeys(a, metrics);
        }
        metrics.addView(
                metricCard(a, "本周观影", weekTitles + " 部", 0xffffb3c8),
                new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, -2, 1f);
        m2.leftMargin = a.dp(10);
        metrics.addView(
                metricCard(
                        a,
                        "本周时长",
                        (minutes >= 60 ? (minutes / 60) + " 小时 " : "") + (minutes % 60) + " 分",
                        0xff8fc5ff),
                m2);
        LinearLayout.LayoutParams m3 = new LinearLayout.LayoutParams(0, -2, 1f);
        m3.leftMargin = a.dp(10);
        metrics.addView(metricCard(a, "活跃天数", days.size() + " 天", 0xff5ee0a0), m3);
        body.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
        a.gap(body, 14);

        sectionHeader(a, body, "最近观看");
        if (week.isEmpty()) {
            body.addView(a.text("本周还没有观看记录", 13, 0xff8d9cb6));
            return;
        }
        int limit = Math.min(10, week.size());
        for (int i = 0; i < limit; i++) {
            WatchHistory.Entry e = week.get(i);
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            row.setBackground(
                    MobileShell.glassBg(
                            0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
            TextView t = a.text(e.title, 13.5f, MainActivity.WHITE);
            t.setTypeface(null, Typeface.BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            int pct =
                    e.duration > 0
                            ? Math.min(100, Math.round(e.position * 100f / e.duration))
                            : 0;
            TextView p = a.text(pct + "%", 11.5f, 0xffffb3c8);
            row.addView(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(6);
            body.addView(row, lp);
        }
    }

    /**
     * TV 端焦点适配：把原本触屏的控件变成可被遥控聚焦，并用项目统一的 FocusMotion 焦点态。
     * 手机端直接 no-op，保证手机行为零变化。
     */
    static void tvFocus(final MainActivity a, final View view, final int radiusDp) {
        if (a == null || view == null || !a.isTvDevice()) return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener(
                (v, focused) ->
                        FocusMotion.style(
                                a,
                                v,
                                focused,
                                0xcc16283f,
                                0xff3b5273,
                                radiusDp,
                                0xffe8eff9,
                                0xffffffff,
                                true,
                                1.02f));
    }

    /** TV 崩溃日志整页（只读日志卡 + 另存到文件管理 / 清空记录）。 */
    static void crashLog(final MainActivity a, LinearLayout body) {
        java.io.File src = new java.io.File(a.getFilesDir(), "crash-last.txt");
        String text = null;
        if (src.isFile()) {
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = new java.io.FileInputStream(src)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        }
        final String content = text != null && !text.trim().isEmpty() ? text : null;
        String caption;
        if (content == null) {
            caption = "暂无崩溃记录";
        } else {
            caption =
                    "上次崩溃记录 · "
                            + new java.text.SimpleDateFormat(
                                            "yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                                    .format(new java.util.Date(src.lastModified()));
        }
        body.addView(a.text(caption, a.isTvDevice() ? 13 : 12, 0xff8d9cb6));
        a.gap(body, 8);

        final TextView logView =
                a.text(
                        content == null
                                ? "App 正常运行时不会产生此文件；发生崩溃后会自动保存最近一次堆栈。"
                                : content,
                        a.isTvDevice() ? 13 : 11.5f,
                        0xffd5e1f2);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0, 1.2f);
        logView.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        logView.setBackground(
                MobileShell.glassBg(0x66000000, 0x33ffffff, a.dp(14), a.dp(0.8f), 0x33ffffff));
        if (a.isTvDevice()) {
            // 日志区可聚焦：上下键滚动日志（设计：首焦点=日志滚动区）
            logView.setFocusable(true);
            logView.setFocusableInTouchMode(true);
            logView.setMinHeight(a.dp(240));
            logView.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    v,
                                    focused,
                                    0xcc16283f,
                                    0xff3b5273,
                                    14,
                                    0xffd5e1f2,
                                    0xffffffff,
                                    true,
                                    1.0f));
            logView.setOnKeyListener(
                    (v, keyCode, event) -> {
                        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                        int dy;
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) dy = a.dp(72);
                        else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) dy = -a.dp(72);
                        else return false;
                        if (!(a.scroll instanceof android.widget.ScrollView)) return false;
                        android.widget.ScrollView scroller =
                                (android.widget.ScrollView) a.scroll;
                        int max = 0;
                        if (scroller.getChildCount() > 0)
                            max = scroller.getChildAt(0).getHeight() - scroller.getHeight();
                        int y = scroller.getScrollY();
                        // 到边界放行给焦点系统，才能把焦点移到下方「另存到文件管理 / 清空记录」
                        if (dy > 0 && y >= max - 1) return false;
                        if (dy < 0 && y <= 0) return false;
                        scroller.scrollBy(0, dy);
                        return true;
                    });
        }
        body.addView(logView, new LinearLayout.LayoutParams(-1, -2));

        if (content == null) return;
        a.gap(body, 14);
        LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button save =
                a.button(
                        "另存到文件管理",
                        "crash-save",
                        () -> {
                            try {
                                String rel =
                                        PushFiles.writeText(
                                                a,
                                                "诊断/崩溃日志-"
                                                        + System.currentTimeMillis()
                                                        + ".txt",
                                                content);
                                a.toast("已保存到文件管理：" + rel);
                            } catch (Throwable e) {
                                a.notice("另存失败：" + a.message(e));
                            }
                        });
        actions.addView(save, new LinearLayout.LayoutParams(0, a.dp(a.isTvDevice() ? 48 : 44), 1f));
        Button clear =
                a.button(
                        "清空记录",
                        "crash-clear",
                        () ->
                                confirmDanger(
                                        a,
                                        "清空崩溃记录？",
                                        "会删除本机保存的崩溃堆栈，不可恢复。",
                                        "清空",
                                        () -> {
                                            clearCrashFiles(a);
                                            a.toast("已清空崩溃记录");
                                            a.refresh();
                                        }));
        LinearLayout.LayoutParams clearLp =
                new LinearLayout.LayoutParams(0, a.dp(a.isTvDevice() ? 48 : 44), 1f);
        clearLp.leftMargin = a.dp(10);
        actions.addView(clear, clearLp);
        body.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 统一降级弹窗：需要键鼠的操作不唤起软键盘/文件选择器（默认焦点「知道了」）。 */
    static void degrade(final MainActivity a, boolean adminEntry) {
        AppDialog.Builder builder =
                new AppDialog.Builder(a)
                        .setTitle("需要手机或电脑操作")
                        .setMessage(
                                "电视端不支持键盘输入或文件选择。请在手机/电脑浏览器打开「管理员后台」完成；完成后回到电视刷新即可。")
                        .setPositiveButton("知道了", null);
        if (adminEntry) builder.setNegativeButton("查看管理员后台", (d, w) -> AdminPanel.show(a));
        focusDialog(a, builder.show(), "dialog-action:-1");
    }

    /** 破坏性操作二次确认：默认焦点给「取消」。 */
    static void confirmDanger(
            final MainActivity a, String title, String message, String confirmLabel, Runnable action) {
        AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle(title)
                        .setMessage(message)
                        .setNegativeButton("取消", null)
                        .setPositiveButton(confirmLabel, (d, w) -> action.run())
                        .show();
        focusDialog(a, dialog, "dialog-action:-2");
    }

    static void focusDialog(final MainActivity a, final AppDialog dialog, final String tag) {
        a.ui.post(
                () -> {
                    if (dialog == null || dialog.getWindow() == null) return;
                    View button = dialog.getWindow().getDecorView().findViewWithTag(tag);
                    if (button != null) button.requestFocus();
                });
    }

    /** TV 只读页：聚焦控件上按上下键滚动页面（不弹软键盘）。 */
    static void tvScrollKeys(final MainActivity a, final View view) {
        view.setOnKeyListener(
                (v, keyCode, event) -> {
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    int dy;
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) dy = a.dp(72);
                    else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) dy = -a.dp(72);
                    else return false;
                    if (a.scroll instanceof android.widget.ScrollView)
                        ((android.widget.ScrollView) a.scroll).scrollBy(0, dy);
                    return true;
                });
    }

    private static void clearCrashFiles(MainActivity a) {
        try {
            java.io.File src = new java.io.File(a.getFilesDir(), "crash-last.txt");
            if (src.exists()) src.delete();
            java.io.File pushed = new java.io.File(a.getFilesDir(), "pushed/崩溃日志/上次崩溃.txt");
            if (pushed.exists()) pushed.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 内部小工具（与 MobileShell 原实现一致） ----------------

    private static void sectionHeader(MainActivity a, LinearLayout parent, String title) {
        TextView h = a.text(title, 12.5f, 0xff5ac8fa);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        parent.addView(h);
    }

    private static void settingCard(
            MainActivity a,
            LinearLayout parent,
            int iconKind,
            String title,
            String subtitle,
            Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(a.isTvDevice() ? a.dp(64) : 0);
        row.setPadding(
                a.dp(UiTokens.SP_14),
                a.dp(UiTokens.SP_12),
                a.dp(UiTokens.SP_14),
                a.dp(UiTokens.SP_12));
        row.setBackground(
                MobileShell.glassBg(
                        0xcc1e3557,
                        0xcc17294a,
                        a.dp(UiTokens.RADIUS_20),
                        a.dp(0.7f),
                        0x33ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(v -> action.run());
        tvFocus(a, row, UiTokens.RADIUS_20);

        MobileShell.SettingIconView icon = new MobileShell.SettingIconView(a, iconKind);
        int iconDp = a.isTvDevice() ? 40 : 36;
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(iconDp), a.dp(iconDp));
        iconLp.rightMargin = a.dp(12);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, a.isTvDevice() ? 17 : 14.5f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, a.isTvDevice() ? 13 : 12, MainActivity.MUTED);
        s.setPadding(0, a.dp(3), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        if (!subtitle.isEmpty()) textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 20, MainActivity.MUTED));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(10);
        parent.addView(row, lp);
    }

    private static void storageRow(
            MainActivity a, LinearLayout body, String label, long bytes, Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(a.isTvDevice() ? a.dp(64) : 0);
        row.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        row.setBackground(
                MobileShell.glassBg(
                        0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
        LinearLayout text = a.vertical();
        TextView t = a.text(label, a.isTvDevice() ? 15 : 12.5f, 0xffe6edf9);
        text.addView(t);
        TextView size =
                a.text(
                        android.text.format.Formatter.formatFileSize(a, Math.max(0, bytes)),
                        11.5f,
                        0xffffb3c8);
        size.setPadding(0, a.dp(3), 0, 0);
        text.addView(size);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clean = a.text("清理", a.isTvDevice() ? 13 : 12, 0xffff8fb1);
        clean.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        if (a.isTvDevice()) clean.setMinHeight(a.dp(44));
        clean.setBackground(
                MobileShell.glassBg(0x1affffff, 0x33ffffff, a.dp(999), a.dp(0.8f), 0));
        MobileDetailScreen.bindTouchFeedback(clean);
        clean.setOnClickListener(v -> action.run());
        tvFocus(a, clean, 999);
        row.addView(clean);
        // 手机保持原行为（只有「清理」按钮可点）；TV 端整行可聚焦，按 OK 即清理
        if (a.isTvDevice()) {
            row.setOnClickListener(v -> action.run());
            tvFocus(a, row, 14);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(6);
        body.addView(row, lp);
    }

    private static void keymapRow(MainActivity a, LinearLayout body, String key, String desc) {
        boolean tv = a.isTvDevice();
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        row.setMinimumHeight(tv ? a.dp(56) : 0);
        row.setBackground(
                MobileShell.glassBg(
                        0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
        TextView keyView = a.text(key, tv ? 17 : 12.5f, 0xffffb3c8);
        keyView.setTypeface(null, Typeface.BOLD);
        keyView.setMinWidth(a.dp(tv ? 220 : 120));
        row.addView(keyView);
        TextView descView = a.text(desc, tv ? 15 : 12.5f, 0xffc9d4e6);
        descView.setPadding(a.dp(10), 0, 0, 0);
        row.addView(descView, new LinearLayout.LayoutParams(0, -2, 1f));
        if (tv) {
            // 只读页：行可聚焦，上下键滚动（设计：首焦点=页面滚动区）
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    v,
                                    focused,
                                    0xcc16283f,
                                    0xff3b5273,
                                    14,
                                    0xffc9d4e6,
                                    0xffc9d4e6,
                                    true,
                                    1.0f));
            tvScrollKeys(a, row);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(6);
        body.addView(row, lp);
    }

    private static LinearLayout metricCard(MainActivity a, String label, String value, int accent) {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
        card.setMinimumHeight(a.isTvDevice() ? a.dp(100) : 0);
        card.setBackground(
                MobileShell.glassBg(
                        0xcc16283f, 0xb3121d2e, a.dp(16), a.dp(0.8f), 0x33ffffff));
        TextView v = a.text(value, a.isTvDevice() ? 24 : 18, accent);
        v.setTypeface(null, Typeface.BOLD);
        card.addView(v);
        TextView l = a.text(label, a.isTvDevice() ? 13 : 11, 0xff8d9cb6);
        l.setPadding(0, a.dp(3), 0, 0);
        card.addView(l);
        return card;
    }

    private static long dirSize(java.io.File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long total = 0;
        java.io.File[] children = dir.listFiles();
        if (children == null) return 0;
        for (java.io.File child : children) total += dirSize(child);
        return total;
    }

    private static long logBytes(MainActivity a) {
        long bytes = 0;
        java.io.File dir = a.getFilesDir();
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            String name = f.getName();
            if (f.isFile() && (name.endsWith(".log") || name.equals("crash-last.txt")))
                bytes += f.length();
        }
        return bytes;
    }

    private static void clearLogs(MainActivity a) {
        java.io.File dir = a.getFilesDir();
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            String name = f.getName();
            if (f.isFile() && (name.endsWith(".log") || name.equals("crash-last.txt")))
                f.delete();
        }
    }

    private static void clearDir(java.io.File dir, boolean removeRoot) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] children = dir.listFiles();
        if (children != null)
            for (java.io.File child : children) {
                if (child.isDirectory()) clearDir(child, true);
                else child.delete();
            }
        if (removeRoot) dir.delete();
    }
}
