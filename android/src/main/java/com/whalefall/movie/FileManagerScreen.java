package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.9.0 内置文件管理器（按 docs/DESIGN_FILE_MANAGER_1.9.0.md 实现核心 UI）：
 * 顶部存储概览 + 本地/网盘分段 + 面包屑/排序 + 响应式网格 + 多选操作栏 + 下载任务条。
 * 本地操作接 FileManagerCore；网盘浏览复用 QuarkUcApi/QuarkUcFiles 的凭据选择与回退。
 */
final class FileManagerScreen {
    private static FileManagerScreen current;

    private final MainActivity a;
    private int tab; // 0 本地 1 网盘
    private File localDir;
    private LinearLayout breadcrumbRow, grid, content;
    private TextView pathLabel, emptyView, selectionCount;
    private LinearLayout selectionBar, downloadStrip;
    private final List<Object> items = new ArrayList<>();
    private boolean selecting;
    private final java.util.Set<File> selFiles = new java.util.HashSet<>();
    private final java.util.Set<String> selFids = new java.util.HashSet<>();
    private int sort; // 0 名称 1 时间 2 大小
    private String providerKey = NetdiskQrLogin.QUARK;
    private String netdiskCookie = "", netdiskPath = "根目录", netdiskFid = "0";
    private boolean netdiskFromSource;
    private String activeTaskId;
    private boolean stripPaused;
    private long lastPauseToggle;
    private TextView stripPause;
    private LinearLayout stripProgressWrap;

    static void show(MainActivity a) {
        FileManagerScreen screen = new FileManagerScreen(a);
        screen.open();
        current = screen;
    }

    static boolean handleBack() {
        FileManagerScreen screen = current;
        if (screen == null) return false;
        return screen.back();
    }

    private FileManagerScreen(MainActivity a) {
        this.a = a;
        this.localDir = FileManagerCore.downloadDir(a);
    }

    private void open() {
        a.renderPage("文件管理", "FILE MANAGER");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.body.setTag("file-manager");
        selectionBar = null;
        downloadStrip = null;
        stripProgressWrap = null;
        providerBar = null;
        content = null;
        AppLog.i(
                "FileMgr",
                "page=FileManager tab=" + (tab == 0 ? "local" : "netdisk") + " items=0");
        buildStorageCard();
        buildSegment();
        buildToolbar();
        if (tab == 1) buildProviderChips();
        content = a.vertical();
        a.body.addView(content);
        refresh();
        tvFocusFirst();
    }

    // ---------------- 顶部：存储概览 ----------------

    /** 1.9.0：bindTouchFeedback 会关闭 focusable，必须先绑定再打开焦点。 */
    private void focusable(View view) {
        MobileDetailScreen.bindTouchFeedback(view);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener(
                (v, focused) ->
                        FocusMotion.style(
                                a,
                                v,
                                focused,
                                0x00000000,
                                0x33ffffff,
                                16,
                                UiTokens.TEXT_PRIMARY,
                                UiTokens.TEXT_PRIMARY,
                                focused,
                                1.02f));
    }

    private void buildStorageCard() {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(10));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = a.vertical();
        TextView label = a.text("存储空间", 12, UiTokens.TEXT_SECONDARY);
        left.addView(label);
        long total = 0, available = 0;
        try {
            android.os.StatFs stat = new android.os.StatFs(localDir.getAbsolutePath());
            total = stat.getTotalBytes();
            available = stat.getAvailableBytes();
        } catch (Throwable ignored) {
        }
        long used = Math.max(0, total - available);
        TextView value =
                a.text(
                        "已用 " + QuarkUcApi.formatSize(used) + " · 可用 " + QuarkUcApi.formatSize(available),
                        15,
                        UiTokens.TEXT_PRIMARY);
        value.setPadding(0, a.dp(3), 0, 0);
        left.addView(value);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(a.text("本 App 文件", 11.5f, UiTokens.TEXT_TERTIARY));
        card.addView(row);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(total > 0 ? (int) (used * 100 / total) : 0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, a.dp(4));
        barLp.topMargin = a.dp(8);
        card.addView(bar, barLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        a.body.addView(card, lp);
    }

    // ---------------- 分段 ----------------

    private void buildSegment() {
        LinearLayout row = a.row();
        row.setPadding(0, a.dp(10), 0, a.dp(6));
        for (int i = 0; i < 2; i++) {
            final int mode = i;
            TextView chip =
                    a.text(
                            i == 0 ? "本地文件" : "网盘",
                            13,
                            tab == i ? 0xff0b1220 : UiTokens.TEXT_PRIMARY);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(8), a.dp(9), a.dp(8), a.dp(9));
            chip.setBackground(
                    tab == i
                            ? UiTokens.round(a, 999, UiTokens.ACCENT)
                            : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            chip.setFocusable(true);
            focusable(chip);
            chip.setOnClickListener(
                    v -> {
                        if (tab == mode) return;
                        exitSelection();
                        tab = mode;
                        rebuild();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = a.dp(8);
            row.addView(chip, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        a.body.addView(row, lp);
    }

    // ---------------- 工具栏：面包屑 + 排序 + 新建 ----------------

    private void buildToolbar() {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(6), 0, a.dp(6));
        pathLabel = a.text("", 11.5f, UiTokens.TEXT_TERTIARY);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.START);
        row.addView(pathLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sortChip = a.text("", 11.5f, UiTokens.ACCENT_SOFT);
        sortChip.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
        sortChip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        sortChip.setFocusable(true);
        focusable(sortChip);
        final TextView sortRef = sortChip;
        sortChip.setOnClickListener(
                v -> {
                    sort = (sort + 1) % 3;
                    sortRef.setText("排序：" + sortLabel());
                    refresh();
                });
        sortRef.setText("排序：" + sortLabel());
        row.addView(sortChip);
        if (tab == 0) {
            TextView newDir = a.text("新建", 11.5f, UiTokens.ACCENT_SOFT);
            newDir.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
            newDir.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            newDir.setFocusable(true);
            focusable(newDir);
            newDir.setOnClickListener(v -> createFolderDialog());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = a.dp(6);
            row.addView(newDir, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        a.body.addView(row, lp);
        breadcrumbRow = a.row();
        a.body.addView(breadcrumbRow);
    }

    private String sortLabel() {
        return sort == 0 ? "名称 ↑" : sort == 1 ? "时间 ↓" : "大小 ↓";
    }

    private void rebuild() {
        a.body.removeAllViews();
        open();
    }

    // ---------------- 刷新内容 ----------------

    private void refresh() {
        if (content == null) return;
        content.removeAllViews();
        if (tab == 0) refreshLocal();
        else refreshNetdisk();
        updateBreadcrumb();
        updateSelectionBar();
        updateDownloadStrip();
    }

    private void updateBreadcrumb() {
        if (breadcrumbRow == null) return;
        breadcrumbRow.removeAllViews();
        String path;
        if (tab == 0) {
            String root = FileManagerCore.downloadDir(a).getAbsolutePath();
            path = localDir.getAbsolutePath();
            path = path.equals(root) ? "文件管理" : "文件管理 / " + localDir.getName();
        } else path = "网盘 / " + providerName() + " / " + netdiskPath;
        pathLabel.setText(path);
        breadcrumbRow.setPadding(a.dp(16), 0, a.dp(16), a.dp(4));
        if (tab == 0 && !localDir.equals(FileManagerCore.downloadDir(a))) {
            TextView up = a.text("‹ 上级", 12, UiTokens.ACCENT);
            up.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
            up.setFocusable(true);
            focusable(up);
            up.setOnClickListener(v -> goUp());
            breadcrumbRow.addView(up);
        }
    }

    private void refreshLocal() {
        items.clear();
        if (localDir.exists() && !localDir.canRead()) {
            showEmpty("读取目录失败", "请检查存储权限后重试", "重试", () -> refresh());
            return;
        }
        List<FileManagerCore.LocalEntry> entries = FileManagerCore.scan(localDir);
        items.addAll(entries);
        sortEntries(entries);
        AppLog.i("FileMgr", "page=FileManager tab=local items=" + entries.size() + " dir=" + localDir.getName());
        if (entries.isEmpty()) {
            showEmpty(
                    "还没有文件",
                    "通过管理后台上传，或从网盘下载到 Download/鲸落/",
                    "刷新本地",
                    () -> refresh());
            return;
        }
        buildGrid(entries.size(), (index) -> buildLocalCard(entries.get(index), index));
    }

    private void sortEntries(List<FileManagerCore.LocalEntry> entries) {
        java.util.Collections.sort(
                entries,
                (x, y) -> {
                    if (x.folder != y.folder) return x.folder ? -1 : 1;
                    if (sort == 1) return Long.compare(y.modified, x.modified);
                    if (sort == 2) return Long.compare(y.size, x.size);
                    return x.name.compareToIgnoreCase(y.name);
                });
    }

    private void refreshNetdisk() {
        AppLog.i(
                "FileMgr",
                "page=FileManager tab=netdisk provider="
                        + providerKey
                        + " fid="
                        + netdiskFid
                        + " credential="
                        + (netdiskCookie.isEmpty() ? "none" : netdiskFromSource ? "source" : "app"));
        // 1.9.0：进入网盘分段时先按凭据优先级初始化 cookie（否则永远提示未登录）
        if (netdiskCookie.isEmpty()) {
            String[] picked = pickCookie(providerKey, null);
            if (picked != null && picked.length >= 2 && picked[0] != null && !picked[0].isEmpty()) {
                netdiskCookie = picked[0];
                netdiskFromSource = Boolean.parseBoolean(picked[1]);
            }
        }
        if (!NetdiskQrLogin.QUARK.equals(providerKey) && !NetdiskQrLogin.UC.equals(providerKey)) {
            showEmpty(
                    "暂不支持浏览" + providerName(),
                    providerName() + "当前仅支持登录/退出，夸克与 UC 可浏览、播放和下载",
                    "去登录",
                    () -> loginProvider(providerKey));
            return;
        }
        if (netdiskCookie.isEmpty()) {
            showEmpty(
                    "未登录" + providerName(),
                    "登录后浏览、播放与下载",
                    "去登录",
                    () -> loginProvider(providerKey));
            return;
        }
        // 1.9.0：chips 已在工具栏区常驻，不随内容区清空
        final TextView loading = a.text("正在读取" + providerName() + "文件…", 13, UiTokens.TEXT_TERTIARY);
        loading.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
        content.addView(loading);
        a.worker.execute(
                () -> {
                    List<QuarkUcApi.FileItem> files = new ArrayList<>();
                    String error = "";
                    try {
                        files = QuarkUcApi.list(
                                QuarkUcApi.of(providerKey), netdiskCookie, netdiskFid);
                    } catch (Throwable failure) {
                        error = failure.getClass().getSimpleName();
                    }
                    final List<QuarkUcApi.FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (content == null || tab != 1) return;
                                content.removeAllViews();
                                if (!failure.isEmpty()) {
                                    showEmpty("读取网盘失败", failure, "重试", () -> refresh());
                                    return;
                                }
                                if (result.isEmpty()) {
                                    showEmpty("这个文件夹是空的", "换一个目录，或稍后刷新", "刷新", () -> refresh());
                                    return;
                                }
                                items.clear();
                                items.addAll(result);
                                AppLog.i(
                                        "FileMgr",
                                        "page=FileManager tab=netdisk items=" + result.size());
                                buildGrid(result.size(), (index) -> buildNetdiskCard(result.get(index)));
                            });
                });
    }

    private LinearLayout providerBar;

    private void buildProviderChips() {
        if (providerBar != null && providerBar.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) providerBar.getParent()).removeView(providerBar);
        final LinearLayout row = a.row();
        providerBar = row;
        row.setPadding(0, 0, 0, a.dp(6));
        String[] names = {"夸克", "UC", "百度", "阿里"};
        String[] keys = {NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            String[] picked = pickCookie(key, null);
            boolean logged = !picked[0].isEmpty();
            boolean source = Boolean.parseBoolean(picked[1]);
            TextView chip =
                    a.text(
                            names[i] + (logged ? (source ? "·源" : "·App") : "·未登录"),
                            11.5f,
                            key.equals(providerKey) ? 0xff0b1220 : UiTokens.TEXT_PRIMARY);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(9), a.dp(7), a.dp(9), a.dp(7));
            chip.setBackground(
                    key.equals(providerKey)
                            ? UiTokens.round(a, 999, UiTokens.ACCENT)
                            : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            chip.setFocusable(true);
            focusable(chip);
            chip.setOnClickListener(
                    v -> {
                        if (key.equals(providerKey)) return;
                        String[] chosen = pickCookie(key, null);
                        if (chosen[0].isEmpty()) {
                            a.toast("未检测到" + names[indexOfKey(key)] + "登录凭据，请先登录");
                            return;
                        }
                        providerKey = key;
                        netdiskFid = "0";
                        netdiskPath = "根目录";
                        netdiskCookie = chosen[0];
                        netdiskFromSource = Boolean.parseBoolean(chosen[1]);
                        exitSelection();
                        buildProviderChips();
                        refresh();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = a.dp(6);
            row.addView(chip, lp);
        }
        // 1.9.0：四盘 chips 固定在工具栏区，不随内容区 clear
        if (content != null && content.getParent() == a.body) {
            int index = a.body.indexOfChild(content);
            a.body.addView(row, index >= 0 ? index : a.body.getChildCount());
        } else {
            a.body.addView(row);
        }
    }

    /** 复用云盘专区凭据策略：App 已验证 > 源插件 > App 未验证。 */
    private String[] pickCookie(String key, QuarkUcApi.Provider ignored) {
        QuarkUcApi.Provider provider = QuarkUcApi.of(key);
        String app = QuarkUcApi.cookie(a, provider);
        boolean verified = QuarkUcApi.verified(a, provider);
        String source = CloudZoneScreen.sourceCookie(a, provider);
        String cookie;
        boolean fromSource;
        if (!app.isEmpty() && verified) {
            cookie = app;
            fromSource = false;
        } else if (!source.isEmpty()) {
            cookie = source;
            fromSource = true;
        } else {
            cookie = app;
            fromSource = false;
        }
        AppLog.i(
                "FileMgr",
                "pick provider="
                        + key
                        + " credential="
                        + (cookie.isEmpty() ? "none" : fromSource ? "source" : "app"));
        return new String[] {cookie, String.valueOf(fromSource)};
    }

    private int indexOfKey(String key) {
        switch (key) {
            case NetdiskQrLogin.QUARK:
                return 0;
            case NetdiskQrLogin.UC:
                return 1;
            case NetdiskQrLogin.BAIDU:
                return 2;
            default:
                return 3;
        }
    }

    private int currentProviderIndex() {
        return indexOfKey(providerKey);
    }

    private void loginProvider(String key) {
        NetdiskQrLogin.show(
                a,
                key,
                () -> {
                    // 1.9.0：登录成功后用新 cookie 重载网盘分段
                    String[] picked = pickCookie(key, null);
                    if (!picked[0].isEmpty()) {
                        netdiskCookie = picked[0];
                        netdiskFromSource = Boolean.parseBoolean(picked[1]);
                        providerKey = key;
                    }
                    if (tab == 1) buildProviderChips();
                    a.ui.postDelayed(this::refresh, 200);
                });
    }

    private String providerName() {
        switch (providerKey) {
            case NetdiskQrLogin.QUARK:
                return "夸克";
            case NetdiskQrLogin.UC:
                return "UC";
            case NetdiskQrLogin.BAIDU:
                return "百度";
            default:
                return "阿里";
        }
    }

    // ---------------- 网格 ----------------

    private interface CardBuilder {
        View build(int index);
    }

    private void buildGrid(int count, CardBuilder builder) {
        int widthDp = Math.round(a.windowWidth() / a.getResources().getDisplayMetrics().density);
        boolean tv = a.isTvDevice();
        int cols = tv ? 5 : widthDp < 420 ? 2 : widthDp < 600 ? 3 : 4;
        int margin = widthDp < 600 ? a.dp(16) : a.dp(24);
        int gap = tv ? a.dp(18) : widthDp < 600 ? a.dp(12) : a.dp(16);
        int contentW = a.windowWidth() - margin * 2;
        int cardW = (contentW - gap * (cols - 1)) / cols;
        int thumbH = Math.round(cardW * 0.625f);
        int cardH = thumbH + a.dp(74);
        LinearLayout wrapper = a.vertical();
        wrapper.setPadding(margin, 0, margin, a.dp(20));
        for (int i = 0; i < count; i += cols) {
            LinearLayout row = a.row();
            for (int c = 0; c < cols && i + c < count; c++) {
                View card = builder.build(i + c);
                card.setTag("filemgr-card:" + (i + c));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) lp.leftMargin = gap;
                row.addView(card, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (wrapper.getChildCount() > 0) lp.topMargin = gap;
            wrapper.addView(row, lp);
        }
        content.addView(wrapper);
    }

    private LinearLayout cardShell(int thumbH) {
        LinearLayout card = a.vertical();
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
        card.setFocusable(true);
        card.setClipToOutline(false);
        focusable(card);
        return card;
    }

    private void addThumb(LinearLayout card, int thumbH, String symbol) {
        FrameLayout thumb = new FrameLayout(a);
        thumb.setBackground(
                MobileDetailScreen.glassBg(a, 0xdd0d1a2b, 0x33000000, 16));
        TextView glyph = a.text(symbol, 34, UiTokens.ACCENT_SOFT);
        glyph.setGravity(Gravity.CENTER);
        thumb.addView(glyph, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        card.addView(
                thumb,
                new LinearLayout.LayoutParams(-1, thumbH));
    }

    private void addTitleAndMeta(LinearLayout card, String title, String meta) {
        LinearLayout info = a.vertical();
        info.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
        TextView name = a.text(title, 12.5f, UiTokens.TEXT_PRIMARY);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        TextView sub = a.text(meta, 10.5f, UiTokens.TEXT_TERTIARY);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sub);
        card.addView(info, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private View buildLocalCard(final FileManagerCore.LocalEntry entry, final int index) {
        LinearLayout card = cardShell(0);
        int widthDp = Math.round(a.windowWidth() / a.getResources().getDisplayMetrics().density);
        boolean tv = a.isTvDevice();
        int cols = tv ? 5 : widthDp < 420 ? 2 : widthDp < 600 ? 3 : 4;
        int margin = widthDp < 600 ? a.dp(16) : a.dp(24);
        int gap = tv ? a.dp(18) : widthDp < 600 ? a.dp(12) : a.dp(16);
        int cardW = (a.windowWidth() - margin * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.round(cardW * 0.625f);
        addThumb(card, thumbH, entry.folder ? "📁" : entry.video ? "▶" : fileSymbol(entry.name));
        String meta =
                entry.folder
                        ? (countChildren(entry.file) + " 项")
                        : QuarkUcApi.formatSize(entry.size);
        meta += " · " + FileManagerCore.formatDate(entry.modified);
        addTitleAndMeta(card, entry.name, meta);
        if (selecting && selFiles.contains(entry.file)) card.setAlpha(0.6f);
        final View cardView = card;
        card.setOnClickListener(
                v -> {
                    if (selecting) toggleSelection(entry.file, null, cardView);
                    else if (entry.folder) {
                        localDir = entry.file;
                        exitSelection();
                        refresh();
                    } else if (entry.video) FileManagerCore.playLocal(a, entry.file);
                    else a.toast("当前仅支持视频播放与下载");
                });
        card.setOnLongClickListener(
                v -> {
                    if (!selecting) {
                        selecting = true;
                        selFiles.clear();
                        selFids.clear();
                    }
                    toggleSelection(entry.file, null, cardView);
                    updateSelectionBar();
                    return true;
                });
        card.setTag("filemgr-card:" + index);
        return card;
    }

    private View buildNetdiskCard(final QuarkUcApi.FileItem item) {
        LinearLayout card = cardShell(0);
        int widthDp = Math.round(a.windowWidth() / a.getResources().getDisplayMetrics().density);
        boolean tv = a.isTvDevice();
        int cols = tv ? 5 : widthDp < 420 ? 2 : widthDp < 600 ? 3 : 4;
        int margin = widthDp < 600 ? a.dp(16) : a.dp(24);
        int gap = tv ? a.dp(18) : widthDp < 600 ? a.dp(12) : a.dp(16);
        int cardW = (a.windowWidth() - margin * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.round(cardW * 0.625f);
        addThumb(card, thumbH, item.folder ? "📁" : item.video ? "▶" : fileSymbol(item.name));
        String dateText = "网盘";
        if (item.updated > 0) {
            long ms = item.updated > 1000000000000L ? item.updated : item.updated * 1000L;
            dateText = FileManagerCore.formatDate(ms);
        }
        String meta =
                (item.folder ? "文件夹" : QuarkUcApi.formatSize(item.size))
                        + " · "
                        + dateText
                        + " · "
                        + (netdiskFromSource ? "源插件" : "App 内");
        addTitleAndMeta(card, item.name, meta);
        if (selecting && selFids.contains(item.fid)) card.setAlpha(0.6f);
        final View cardView = card;
        card.setOnClickListener(
                v -> {
                    if (selecting) toggleSelection(null, item.fid, cardView);
                    else if (item.folder) {
                        netdiskFid = item.fid;
                        netdiskPath = netdiskPath + " / " + item.name;
                        exitSelection();
                        refresh();
                    } else if (item.video) {
                        AppLog.i("FileMgr", "open netdisk video fid=" + item.fid);
                        FileManagerCore.playRemote(
                                a,
                                QuarkUcApi.of(providerKey),
                                netdiskCookie,
                                item,
                                netdiskFromSource);
                    } else a.toast("当前仅支持视频播放与下载");
                });
        card.setOnLongClickListener(
                v -> {
                    if (!selecting) {
                        selecting = true;
                        selFiles.clear();
                        selFids.clear();
                    }
                    toggleSelection(null, item.fid, cardView);
                    updateSelectionBar();
                    return true;
                });
        return card;
    }

    private String fileSymbol(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".md")) return "MD";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".zip") || lower.endsWith(".rar")) return "ZIP";
        if (lower.endsWith(".apk")) return "APK";
        if (lower.matches(".*\\.(png|jpg|jpeg|webp|gif)$")) return "IMG";
        return "FILE";
    }

    private int countChildren(File dir) {
        try {
            File[] files = dir.listFiles();
            return files == null ? 0 : files.length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---------------- 多选 ----------------

    private void toggleSelection(File file, String fid, View card) {
        boolean added;
        if (file != null) added = selFiles.add(file);
        else added = selFids.add(fid);
        if (!added) {
            if (file != null) selFiles.remove(file);
            else selFids.remove(fid);
        }
        card.setAlpha(added ? 0.9f : 1f);
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (selectionBar == null) {
            selectionBar = a.row();
            selectionBar.setGravity(Gravity.CENTER);
            selectionBar.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            selectionBar.setBackground(MobileDetailScreen.glassBg(a, 0xee0d1a2b, 0x33ffffff, 16));
            selectionCount = a.text("", 12, UiTokens.TEXT_PRIMARY);
            selectionCount.setPadding(0, 0, a.dp(8), 0);
            selectionBar.addView(selectionCount, new LinearLayout.LayoutParams(0, -2, 1f));
            a.body.addView(selectionBar);
        }
        if (!selecting) {
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectionBar.setVisibility(View.VISIBLE);
        selectionCount.setText("已选 " + (selFiles.size() + selFids.size()) + " 项");
        while (selectionBar.getChildCount() > 1) selectionBar.removeViewAt(1);
        if (tab == 0) {
            selectionBar.addView(selAction("删除", () -> deleteSelected()));
            selectionBar.addView(selAction("重命名", () -> renameSelected()));
            selectionBar.addView(selAction("移动", () -> moveSelected()));
        } else {
            selectionBar.addView(selAction("下载", () -> downloadSelected()));
        }
        selectionBar.addView(selAction("退出", () -> exitSelection()));
    }

    private TextView selAction(String label, Runnable action) {
        TextView view = a.text(label, 12, UiTokens.TEXT_PRIMARY);
        view.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        view.setBackground(MobileDetailScreen.glassBg(a, 0x24ffffff, 0x33ffffff, 999));
        view.setFocusable(true);
        focusable(view);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(6);
        if (view.getParent() == null) {
            // 首次添加时父容器由调用方设置
        }
        view.setLayoutParams(lp);
        return view;
    }

    private void exitSelection() {
        selecting = false;
        selFiles.clear();
        selFids.clear();
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof LinearLayout)
                for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++)
                    ((LinearLayout) child).getChildAt(j).setAlpha(1f);
        }
    }

    private void deleteSelected() {
        new AppDialog.Builder(a)
                .setTitle("删除所选 " + selFiles.size() + " 项？")
                .setMessage("不可恢复。")
                .setPositiveButton(
                        "删除",
                        (d, i) -> {
                            int deleted = 0;
                            for (File file : selFiles) if (FileManagerCore.delete(file)) deleted++;
                            a.toast("已删除 " + deleted + " 项");
                            exitSelection();
                            refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameSelected() {
        if (selFiles.size() != 1) {
            a.toast("重命名仅支持单选");
            return;
        }
        final File file = selFiles.iterator().next();
        EditText input = new EditText(a);
        input.setSingleLine(true);
        prepareInput(input);
        input.setText(file.getName());
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        new AppDialog.Builder(a)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton(
                        "确定",
                        (d, i) -> {
                            boolean ok = FileManagerCore.rename(file, input.getText().toString());
                            a.toast(ok ? "已重命名" : "重命名失败（可能重名或无权限）");
                            if (ok) {
                                exitSelection();
                                refresh();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void moveSelected() {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        prepareInput(input);
        input.setText(localDir.getAbsolutePath());
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        new AppDialog.Builder(a)
                .setTitle("移动到目录（绝对路径）")
                .setView(input)
                .setPositiveButton(
                        "移动",
                        (d, i) -> {
                            File target = new File(input.getText().toString().trim());
                            if (!target.isDirectory()) {
                                a.toast("目标目录不存在");
                                return;
                            }
                            int moved = 0;
                            for (File file : selFiles) if (FileManagerCore.move(file, target)) moved++;
                            a.toast("已移动 " + moved + " 项");
                            exitSelection();
                            refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 1.9.0：TV 上不弹系统软键盘（用遥控键盘/现有输入）。 */
    private void prepareInput(EditText input) {
        if (a.isTvDevice()) {
            try {
                input.setShowSoftInputOnFocus(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private void createFolderDialog() {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        prepareInput(input);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setHint("文件夹名");
        new AppDialog.Builder(a)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton(
                        "创建",
                        (d, i) -> {
                            String name = FileManagerCore.safeName(input.getText().toString().trim());
                            File target = new File(localDir, name);
                            a.toast(target.mkdirs() ? "已创建" : "创建失败，请检查存储空间");
                            refresh();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 下载 ----------------

    private void downloadSelected() {
        if (selFids.isEmpty()) return;
        final List<QuarkUcApi.FileItem> selected = new ArrayList<>();
        for (Object item : items)
            if (item instanceof QuarkUcApi.FileItem
                    && selFids.contains(((QuarkUcApi.FileItem) item).fid))
                selected.add((QuarkUcApi.FileItem) item);
        if (selected.isEmpty()) return;
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        prepareInput(input);
        String remembered =
                a.prefs.getString("filemgr_target_" + providerKey, providerName());
        input.setText(remembered);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        new AppDialog.Builder(a)
                .setTitle("下载到 Download/鲸落/<目录>")
                .setMessage("目标目录可修改，不存在会自动创建。")
                .setView(input)
                .setPositiveButton(
                        "开始下载",
                        (d, i) -> {
                            String folderName = FileManagerCore.safeName(input.getText().toString().trim());
                            a.prefs.edit().putString("filemgr_target_" + providerKey, folderName).apply();
                            File dir = new File(FileManagerCore.downloadDir(a), folderName);
                            if (!dir.exists()) dir.mkdirs();
                            startDownloads(selected, dir);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startDownloads(List<QuarkUcApi.FileItem> files, File dir) {
        a.toast("已加入下载 " + files.size() + " 项");
        exitSelection();
        final QuarkUcApi.Provider provider = QuarkUcApi.of(providerKey);
        downloadNext(files, 0, dir, provider);
    }

    private void downloadNext(
            final List<QuarkUcApi.FileItem> files,
            final int index,
            final File dir,
            final QuarkUcApi.Provider provider) {
        if (index >= files.size()) return;
        final QuarkUcApi.FileItem item = files.get(index);
        // 1.9.0：同名文件三选（覆盖/自动重命名/取消），默认取消
        File expected = new File(dir, FileManagerCore.safeName(item.name));
        if (expected.exists()) {
            new AppDialog.Builder(a)
                    .setTitle("已存在同名文件")
                    .setMessage(item.name)
                    .setItems(
                            new String[] {"覆盖", "自动重命名", "取消"},
                            (d, which) -> {
                                if (which == 0) {
                                    // 1.9.0：覆盖走 .part 原子替换，不先删原文件
                                    beginDownload(item, dir, provider, files, index + 1, true);
                                } else if (which == 1) {
                                    beginDownload(item, dir, provider, files, index + 1, false);
                                } else {
                                    a.toast("已取消该文件下载");
                                    downloadNext(files, index + 1, dir, provider);
                                }
                            })
                    .show();
            return;
        }
        beginDownload(item, dir, provider, files, index + 1, false);
    }

    private void beginDownload(
            final QuarkUcApi.FileItem item,
            final File dir,
            final QuarkUcApi.Provider provider,
            final List<QuarkUcApi.FileItem> queue,
            final int next,
            final boolean overwrite) {
        // FileManagerCore 下载目录固定为 Download/鲸落；目标子目录通过临时切换不现实，
        // 这里先保证链路可用：下载到鲸落后提示实际路径，再继续队列。
        a.toast("开始下载：" + item.name);
        activeTaskId =
                FileManagerCore.download(
                        a,
                        provider,
                        netdiskCookie,
                        item,
                        (percent, bytes, total) -> {
                            if (stripProgressWrap != null) {
                                ProgressBar bar =
                                        (ProgressBar) stripProgressWrap.getChildAt(0);
                                bar.setProgress(Math.max(0, percent));
                                TextView text = (TextView) stripProgressWrap.getChildAt(1);
                                text.setText(item.name + " · " + (percent < 0 ? "下载中" : percent + "%"));
                            }
                        },
                        file -> {
                            activeTaskId = null;
                            stripPaused = false;
                            removeDownloadStrip();
                            downloadNext(queue, next, dir, provider);
                            if (current == this) refresh();
                        },
                        message -> {
                            activeTaskId = null;
                            stripPaused = false;
                            // 1.9.0：失败任务条保留并可重试
                            buildFailedStrip(
                                    item.name,
                                    message,
                                    () ->
                                            beginDownload(
                                                    item, dir, provider, queue, next, overwrite));
                            AppLog.i("FileMgr", "download failed retry available fid=" + item.fid);
                        },
                        dir,
                        overwrite);
        buildDownloadStrip(item.name);
        AppLog.i("FileMgr", "download queue provider=" + provider.key + " fid=" + item.fid);
    }

    private void buildDownloadStrip(String name) {
        removeDownloadStrip();
        stripPaused = false;
        downloadStrip = a.row();
        downloadStrip.setGravity(Gravity.CENTER_VERTICAL);
        downloadStrip.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        downloadStrip.setBackground(MobileDetailScreen.glassBg(a, 0xee0d1a2b, 0x33ffffff, 14));
        LinearLayout col = a.vertical();
        stripProgressWrap = a.vertical();
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, a.dp(2)));
        stripProgressWrap.addView(bar);
        TextView text = a.text("准备下载…", 11, UiTokens.TEXT_SECONDARY);
        stripProgressWrap.addView(text);
        col.addView(stripProgressWrap);
        downloadStrip.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        stripPause = a.text("暂停", 12, UiTokens.TEXT_PRIMARY);
        stripPause.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        stripPause.setFocusable(true);
        focusable(stripPause);
        stripPause.setOnClickListener(
                v -> {
                    if (activeTaskId == null) return;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastPauseToggle < 300) return; // 1.9.0：300ms 防抖
                    lastPauseToggle = now;
                    if (!stripPaused) {
                        FileManagerCore.pause(activeTaskId);
                        stripPaused = true;
                        stripPause.setText("继续");
                        if (stripProgressWrap != null) {
                            TextView t = (TextView) stripProgressWrap.getChildAt(1);
                            t.setText("已暂停 · " + name);
                        }
                    } else {
                        FileManagerCore.resume(activeTaskId);
                        stripPaused = false;
                        stripPause.setText("暂停");
                    }
                });
        downloadStrip.addView(stripPause);
        TextView cancel = a.text("取消", 12, 0xffef4444);
        cancel.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        cancel.setFocusable(true);
        focusable(cancel);
        cancel.setOnClickListener(
                v -> {
                    if (activeTaskId != null) FileManagerCore.cancel(activeTaskId);
                    removeDownloadStrip();
                });
        downloadStrip.addView(cancel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.topMargin = a.dp(6);
        a.body.addView(downloadStrip, lp);
    }

    private void buildFailedStrip(String name, String message, Runnable retry) {
        removeDownloadStrip();
        downloadStrip = a.row();
        downloadStrip.setGravity(Gravity.CENTER_VERTICAL);
        downloadStrip.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        downloadStrip.setBackground(MobileDetailScreen.glassBg(a, 0xee2a1016, 0x33ff6b81, 14));
        TextView text = a.text("下载失败：" + message, 11, 0xffff8fa3);
        text.setMaxLines(2);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        downloadStrip.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView retryBtn = a.text("重试", 12, UiTokens.ACCENT);
        retryBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        retryBtn.setFocusable(true);
        focusable(retryBtn);
        retryBtn.setOnClickListener(v -> retry.run());
        downloadStrip.addView(retryBtn);
        TextView close = a.text("关闭", 12, UiTokens.TEXT_TERTIARY);
        close.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        close.setFocusable(true);
        focusable(close);
        close.setOnClickListener(v -> removeDownloadStrip());
        downloadStrip.addView(close);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.topMargin = a.dp(6);
        a.body.addView(downloadStrip, lp);
    }

    private void removeDownloadStrip() {
        if (downloadStrip != null && downloadStrip.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) downloadStrip.getParent()).removeView(downloadStrip);
        }
        downloadStrip = null;
        stripProgressWrap = null;
    }

    private void updateDownloadStrip() {
        if (downloadStrip == null) return;
        downloadStrip.setVisibility(View.VISIBLE);
    }

    // ---------------- 状态 ----------------

    private void showEmpty(String title, String note, String action, Runnable run) {
        LinearLayout box = a.vertical();
        box.setGravity(Gravity.CENTER);
        box.setPadding(a.dp(16), a.dp(48), a.dp(16), a.dp(48));
        TextView glyph = a.text("🗂", 44, UiTokens.TEXT_TERTIARY);
        glyph.setGravity(Gravity.CENTER);
        box.addView(glyph);
        TextView heading = a.text(title, 17, UiTokens.TEXT_PRIMARY);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, a.dp(8), 0, 0);
        box.addView(heading);
        TextView sub = a.text(note, 13, UiTokens.TEXT_TERTIARY);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, a.dp(4), 0, a.dp(12));
        box.addView(sub);
        if (action != null && run != null) {
            TextView button = a.text(action, 13, 0xff0b1220);
            button.setGravity(Gravity.CENTER);
            button.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
            button.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
            button.setFocusable(true);
            focusable(button);
            button.setOnClickListener(v -> run.run());
            box.addView(button, new LinearLayout.LayoutParams(-2, -2));
            a.ui.post(button::requestFocus);
        }
        content.addView(box);
    }

    // ---------------- BACK / TV ----------------

    private boolean back() {
        if (selecting) {
            exitSelection();
            return true;
        }
        if (tab == 0 && !localDir.equals(FileManagerCore.downloadDir(a))) {
            localDir = localDir.getParentFile() != null ? localDir.getParentFile() : FileManagerCore.downloadDir(a);
            refresh();
            return true;
        }
        if (tab == 1 && !"0".equals(netdiskFid)) {
            netdiskFid = "0";
            netdiskPath = "根目录";
            refresh();
            return true;
        }
        return false;
    }

    private void goUp() {
        localDir =
                localDir.getParentFile() != null
                        ? localDir.getParentFile()
                        : FileManagerCore.downloadDir(a);
        refresh();
    }

    private void tvFocusFirst() {
        a.ui.post(
                () -> {
                    View card = content.findViewWithTag("filemgr-card:0");
                    if (card != null) card.requestFocus();
                });
    }
}
