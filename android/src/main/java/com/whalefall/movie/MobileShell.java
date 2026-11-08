package com.whalefall.movie;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated Android Phone & Tablet application shell styled 1:1 after the iPhone Portrait Liquid
 * Glass UI (`AdaptiveShell` + `AppleLiquidGlassDock`):
 *
 * <ul>
 *   <li>Edge-to-edge transparent status bar over an emerald-obsidian ambient canvas
 *   <li>Floating iOS 27 Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`) with a translucent
 *       selection lens
 *   <li>Scrollable Home header with brand logo, source capsule, netdisk capsule, and search button
 *   <li>3-column `2:3` portrait poster grid with bottom gradient `remarks` overlay and two-line
 *       `title` + `year · area` metadata below
 * </ul>
 */
final class MobileShell {
    static int activeTab = 0;
    /** 配置变更/进程重建后要恢复到的 tab；-1 表示不恢复。 */
    static int pendingRestoreTab = -1;
    /** 重建/切换标签后要恢复的首页滚动位置；-1 表示不恢复。 */
    static int pendingHomeScroll = -1;
    static String pendingRestoreKeyword = "";
    static String pendingSearchKeyword = "";

    /** Persistent search session state so returning from Detail keeps search results resident. */
    static final class CachedSearchHit {
        final Cms.Site site;
        final Cms.Video video;

        CachedSearchHit(Cms.Site site, Cms.Video video) {
            this.site = site;
            this.video = video;
        }
    }

    static String cachedSearchKeyword = "";
    static String cachedSearchStatus = "";
    static final List<CachedSearchHit> cachedSearchHits = new ArrayList<>();
    static int cachedSearchScrollY = 0;

    /** Sub-page of the 片库 tab: 0 history, 1 favorites, 2 downloads. */
    static int mySubTab = 0;

    /**
     * Sub-page of the 设置 tab:
     * 0 = Main 5-card index, 1 = 片源与直播配置, 2 = 播放、解码与字幕, 3 = 界面外观与偏好, 4 = 备份、更新与诊断.
     */
    static int settingsSubPage = 0;

    private final MainActivity a;
    private FrameLayout contentContainer;
    private final View[] navTabs = new View[6];
    private final TextView[] navLabels = new TextView[6];
    private final NavGlyph[] navIcons = new NavGlyph[6];
    private static final String[] TAB_NAMES = {"主页", "搜索", "直播", "片库", "设置", "电视"};
    private static final int[] DOCK_TAB_INDICES = {0, 2, 5, 3, 4};

    // Home tab state
    private Cms.Site currentSite;
    private String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    private List<Cms.Category> currentCategories = new ArrayList<>();
    private String filterSchema = "{}";
    private final java.util.Map<String, String> selectedFilters = new java.util.LinkedHashMap<>();
    private String lastChipTap = "";
    private long lastChipTapAt;
    private Runnable homeFilterApply;
    /** 底部常驻筛选摘要条（44dp）：入口 + 已选条件摘要。 */
    private FilterSummaryBar filterSummaryBar;
    /** 底部常驻筛选摘要条距屏幕底部的距离（手机让开悬浮 Dock，平板侧栏布局贴底）。 */
    private int filterBarBottomDp = 90;
    private int homeRequest;
    private boolean homeReady;
    private Runnable resumeHome;
    private FrameLayout retainedRoot;
    private int retainedWidth, retainedHeight, retainedEpoch;

    boolean resume() {
        if (!homeReady
                || activeTab != 0
                || retainedRoot == null
                || retainedEpoch != a.engineEpoch
                || retainedWidth != a.windowWidth()
                || retainedHeight != a.windowHeight()) return false;
        a.cancelPageRequests();
        a.cancelArtwork();
        a.releasePlayer();
        a.generation++;
        ++homeRequest;
        if (resumeHome != null) resumeHome.run();
        a.root = retainedRoot;
        a.setContentView(retainedRoot);
        // 复用旧页面时补一次背景层：修「返回后静态壁纸黑屏」（不重建内容）
        a.ensureBackdrop(retainedRoot);
        a.applyMobileSystemBars(false);
        a.scroll = homeScroll;
        a.body = homeBody;
        if (a.current != null) a.current.home = true;
        return true;
    }

    MobileShell(MainActivity activity) {
        this.a = activity;
    }

    static void show(MainActivity a) {
        final int restoreTab = pendingRestoreTab;
        final String restoreKeyword = pendingRestoreKeyword;
        pendingRestoreTab = -1;
        pendingRestoreKeyword = "";
        MobileShell shell = new MobileShell(a);
        shell.render();
        if (restoreTab > 0 && restoreTab < 6) {
            if (!restoreKeyword.isEmpty()) shell.pendingSearchKeyword = restoreKeyword;
            shell.switchTab(restoreTab);
        }
    }

    static void openSearchTab(MainActivity a, String initialKeyword) {
        activeTab = 1;
        String kw = initialKeyword == null ? "" : initialKeyword.trim();
        if (!kw.isEmpty()) {
            pendingSearchKeyword = kw;
        }
        if (a.current == null || !a.current.home) {
            boolean pushBack = a.current != null && !a.current.redirect;
            a.open(
                    new MainActivity.Screen(
                            () -> {
                                activeTab = 1;
                                new MobileShell(a).render();
                            }),
                    pushBack);
        } else {
            new MobileShell(a).render();
        }
    }

    private int statusBarHeight() {
        try {
            int resId = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                int h = a.getResources().getDimensionPixelSize(resId);
                if (h > 0) return h;
            }
        } catch (Exception ignored) {
        }
        return a.dp(28);
    }

    static GradientDrawable glassBg(
            int topColor, int bottomColor, float radiusPx, float strokePx, int strokeColor) {
        GradientDrawable gd =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, new int[] {topColor, bottomColor});
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            gd.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        }
        return gd;
    }

    static void showMobileImportDialog(MainActivity a) {
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip =
                a.text("输入或粘贴配置网址（JSON / JS / PY / TXT / M3U / ZIP）或源码文本：", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        final EditText input = new EditText(a);
        input.setHint("https://…/config.json 或 .js / .py / .txt / .zip");
        input.setHintTextColor(0xff7f90ad);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(true);
        input.setLongClickable(true);
        input.setTextIsSelectable(true);
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff122434, 12));
        String savedOrigin = a.prefs.getString(Prefs.ORIGIN, "");
        if (!savedOrigin.isEmpty()) {
            input.setText(savedOrigin);
            input.setSelection(savedOrigin.length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView pasteBtn = a.text("一键读取手机剪贴板并直接导入", 13, Color.WHITE);
        pasteBtn.setTypeface(null, Typeface.BOLD);
        pasteBtn.setGravity(Gravity.CENTER);
        pasteBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pasteBtn.setBackground(a.bg(0xffff8fb1, 999));
        MobileDetailScreen.bindTouchFeedback(pasteBtn);
        panel.addView(pasteBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView fileBtn = a.text("选择本地配置或本地包 (.json/.js/.py/.txt/.zip)", 13, MainActivity.WHITE);
        fileBtn.setGravity(Gravity.CENTER);
        fileBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        fileBtn.setBackground(a.bg(0xff122434, 999));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        panel.addView(fileBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView liveBtn = a.text("导入或管理电视直播源 (M3U / TXT / JSON)", 13, MainActivity.WHITE);
        liveBtn.setGravity(Gravity.CENTER);
        liveBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        liveBtn.setBackground(a.bg(0xff122434, 999));
        MobileDetailScreen.bindTouchFeedback(liveBtn);
        panel.addView(liveBtn, new LinearLayout.LayoutParams(-1, -2));

        final AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("导入或更新配置")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("连接 Emby", (d, w) -> EmbyEngine.connect(a))
                        .setPositiveButton(
                                "导入输入内容",
                                (d, w) -> {
                                    String raw = input.getText().toString().trim();
                                    if (raw.isEmpty()) {
                                        a.toast("请输入配置网址或点击剪贴板导入");
                                        return;
                                    }
                                    importTextOrUrl(a, raw);
                                })
                        .create();

        pasteBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    pasteConfigFromClipboard(a);
                });
        fileBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    a.pickConfig();
                });
        liveBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    SourceController.showLiveImportDialog(a);
                });
        dialog.show();
    }

    static void pasteConfigFromClipboard(MainActivity a) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString().trim();
            }
        }
        if (text.isEmpty()) {
            a.toast("剪贴板为空，请先复制配置网址或源码内容");
            return;
        }
        importTextOrUrl(a, text);
    }

    static void importTextOrUrl(MainActivity a, String rawInput) {
        final String pasted = rawInput == null ? "" : rawInput.trim();
        if (pasted.isEmpty()) {
            a.toast("请输入有效的配置网址或源码内容");
            return;
        }
        a.toast("正在读取并解析配置…");
        a.worker.execute(
                () -> {
                    try {
                        String origin = a.configOrigin;
                        String rawJson = pasted;
                        if (Cms.isHttp(pasted)) {
                            origin = Cms.normalizeHttp(pasted);
                            String lowerOrigin = origin.toLowerCase();
                            if (lowerOrigin.endsWith(".zip") || lowerOrigin.contains(".zip?")) {
                                java.net.HttpURLConnection conn =
                                        (java.net.HttpURLConnection)
                                                new java.net.URL(origin).openConnection();
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(25000);
                                conn.setRequestProperty("User-Agent", Cms.USER_AGENT);
                                byte[] bytes;
                                try (java.io.InputStream in = conn.getInputStream();
                                        java.io.ByteArrayOutputStream bos =
                                                new java.io.ByteArrayOutputStream()) {
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = in.read(buf)) != -1) {
                                        if (bos.size() + n > 28 * 1024 * 1024)
                                            throw new IOException("远程包超过 28 MB 限制");
                                        bos.write(buf, 0, n);
                                    }
                                    bytes = bos.toByteArray();
                                } finally {
                                    conn.disconnect();
                                }
                                LocalSourcePackage pkg =
                                        LocalSourcePackage.open(a.getFilesDir(), bytes);
                                a.ui.post(
                                        () -> {
                                            try {
                                                SourceController.applyLocalPackage(a, pkg);
                                            } catch (Exception ex) {
                                                a.toast("保存配置失败：" + a.message(ex));
                                            }
                                        });
                                return;
                            } else {
                                rawJson = SourceController.fetchImport(a, origin);
                                origin = SourceController.importOrigin(origin);
                            }
                        }
                        try {
                            Cms.Config parsed = Cms.parseConfig(rawJson, origin);
                            final String savedOrigin = origin;
                            a.ui.post(
                                    () -> {
                                        try {
                                            a.saveConfig(parsed, savedOrigin);
                                            a.toast("配置导入成功（共 " + parsed.sites.size() + " 个片源）");
                                        } catch (Exception ex) {
                                            a.toast("保存配置失败：" + a.message(ex));
                                        }
                                    });
                        } catch (Exception parseEx) {
                            String trimmed = rawJson == null ? "" : rawJson.trim();
                            if (trimmed.startsWith("#EXTM3U")
                                    || trimmed.contains("#genre#")
                                    || trimmed.contains(",http://")
                                    || trimmed.contains(",https://")
                                    || trimmed.contains(",rtmp://")
                                    || trimmed.contains(",rtsp://")) {
                                SourceController.importLiveTextOrUrl(a, pasted);
                                return;
                            }
                            throw parseEx;
                        }
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("配置读取失败：" + a.message(e)));
                    }
                });
    }

    void render() {
        VideoFilterDialog.dismiss(a);
        a.clearPlaybackLoading();
        a.cancelArtwork();
        a.cancelPageRequests();
        if (a.current != null) a.current.home = true;
        if (a.searchScreen != null) {
            a.searchScreen.close();
            a.searchScreen = null;
        }
        a.stopWeb();
        a.stopMagnet();
        a.releasePlayer();
        a.generation++;

        // Edge-to-edge transparent status bar over the iPhone emerald-obsidian ambient canvas
        a.applyMobileSystemBars(false);

        resolveActiveSite();

        a.root = new FrameLayout(a);
        a.installBackdrop(a.root);
        a.setContentView(a.root);


        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        boolean tabletSideRail = widthDp >= 640;
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        if (tabletSideRail) {
            LinearLayout mainRow = a.row();
            mainRow.setGravity(Gravity.TOP);
            a.root.addView(mainRow, new FrameLayout.LayoutParams(-1, -1));

            contentContainer = new FrameLayout(a);
            mainRow.addView(contentContainer, new LinearLayout.LayoutParams(0, -1, 1));

            FrameLayout railWrap = new FrameLayout(a);
            railWrap.setPadding(a.dp(8), statusBarHeight() + a.dp(12), a.dp(12), a.dp(12));
            LinearLayout sideRail = buildNavigationBar(true);
            FrameLayout.LayoutParams railLp =
                    new FrameLayout.LayoutParams(
                            a.dp(72 * navScale), a.dp(296 * navScale), Gravity.CENTER_VERTICAL);
            railWrap.addView(sideRail, railLp);
            mainRow.addView(railWrap, new LinearLayout.LayoutParams(-2, -1));
        } else {
            // Layer 1: Full-bleed page container
            contentContainer = new FrameLayout(a);
            a.root.addView(contentContainer, new FrameLayout.LayoutParams(-1, -1));

            // Layer 2: Soft top status-bar edge fade (matching iPhone ShaderMask)
            TopEdgeFadeView topFade = new TopEdgeFadeView(a);
            topFade.setClickable(false);
            topFade.setFocusable(false);
            a.root.addView(
                    topFade,
                    new FrameLayout.LayoutParams(-1, statusBarHeight() + a.dp(24), Gravity.TOP));

            // Layer 3: Bottom edge subtle vignette behind the floating dock
            BottomEdgeFadeView bottomFade = new BottomEdgeFadeView(a);
            bottomFade.setClickable(false);
            bottomFade.setFocusable(false);
            a.root.addView(
                    bottomFade,
                    new FrameLayout.LayoutParams(-1, a.dp(92 * navScale), Gravity.BOTTOM));

            // Layer 4: iPhone Floating Liquid Glass 4-Tab Pill Dock
            LinearLayout bottomDock = buildNavigationBar(false);
            int dockWidthPx =
                    Math.min(a.dp(286 * navScale), Math.max(a.dp(244), a.windowWidth() - a.dp(32)));
            FrameLayout.LayoutParams dockLp =
                    new FrameLayout.LayoutParams(
                            dockWidthPx,
                            a.dp(64 * navScale),
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockLp.bottomMargin = a.dp(16);
            a.root.addView(bottomDock, dockLp);
        }

        switchTab(activeTab);
        retainedRoot = a.root;
        retainedWidth = a.windowWidth();
        retainedHeight = a.windowHeight();
        retainedEpoch = a.engineEpoch;
        if (a.current != null) a.current.mobileHome = this;
    }

    private void resolveActiveSite() {
        currentSite = null;
        if (a.config == null || a.config.sites.isEmpty()) return;
        String wanted = a.prefs.getString(Prefs.SELECTED_SITE, "");
        for (Cms.Site s : a.config.sites) {
            if (s.key.equals(wanted) && a.available(s)) {
                currentSite = s;
                return;
            }
        }
        for (Cms.Site s : a.config.sites) {
            if (a.available(s)) {
                currentSite = s;
                a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
                return;
            }
        }
    }

    /**
     * Builds the iPhone Portrait Header Row inside the scrollable Home tab (or standalone top bar):
     * Left brand logo + title, right Source selector capsule, Netdisk capsule, and circular Search
     * icon button.
     */
    private View buildHomeTopHeaderRow() {
        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));

        // Left: Brand Logo + Title
        LinearLayout brandRow = a.row();
        brandRow.setTag("home-brand");
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(a.dp(28), a.dp(28));
        logoLp.rightMargin = a.dp(8);
        brandRow.addView(logo, logoLp);

        boolean compactBrand =
                a.windowWidth() < a.dp(360) || a.getResources().getConfiguration().fontScale > 1.2f;
        TextView brandTitle =
                a.text(
                        a.privateMode ? "隐私 · 退出" : compactBrand ? "鲸\n落" : "鲸落",
                        compactBrand ? 12 : 14,
                        Color.WHITE);
        brandTitle.setTypeface(null, Typeface.BOLD);
        brandTitle.setSingleLine(!compactBrand);
        brandTitle.setMaxLines(compactBrand ? 2 : 1);
        brandTitle.setContentDescription(a.privateMode ? "退出隐私空间" : "鲸落");
        brandRow.addView(brandTitle);
        brandRow.setMinimumHeight(a.dp(44));
        brandRow.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
        bar.addView(brandRow, new LinearLayout.LayoutParams(-2, -2));

        // 品牌右侧：文件管理入口（管理后台上传的文件在这里管理 / 安装 / 预览）
        android.widget.ImageView fileBtn = new android.widget.ImageView(a);
        fileBtn.setImageDrawable(UiIcon.drawable(a, UiIcon.EPISODES));
        fileBtn.setColorFilter(0xffe6edf9);
        fileBtn.setContentDescription("文件管理");
        fileBtn.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        fileBtn.setBackground(glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.8f), 0x38ffffff));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        fileBtn.setOnClickListener(v -> FilesScreen.show(a));
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        fileLp.leftMargin = a.dp(8);
        bar.addView(fileBtn, fileLp);

        // Flexible middle slot: keeps `sitePill` right-aligned and guarantees it shrinks/truncates
        // before ever pushing `netdiskPill` or `searchCircle` off the right edge of the screen.
        FrameLayout siteSlot = new FrameLayout(a);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
        slotLp.leftMargin = a.dp(8);
        slotLp.rightMargin = a.dp(6);
        bar.addView(siteSlot, slotLp);

        // Right 1: Site selector Liquid Glass capsule (`source-switcher`)
        String siteLabel = currentSite != null ? currentSite.name + " ▾" : "选择片源 ▾";
        TextView sitePill = a.text(siteLabel, 12.5f, Color.WHITE);
        sitePill.setTag("source-switcher");
        sitePill.setTypeface(null, Typeface.BOLD);
        sitePill.setSingleLine(true);
        sitePill.setEllipsize(TextUtils.TruncateAt.END);
        int maxSiteWidth = Math.max(a.dp(48), Math.min(a.dp(136), a.windowWidth() - a.dp(250)));
        sitePill.setMaxWidth(maxSiteWidth);
        sitePill.setGravity(Gravity.CENTER);
        sitePill.setPadding(a.dp(11), a.dp(7), a.dp(11), a.dp(7));
        a.glass(sitePill, 999);
        MobileDetailScreen.bindTouchFeedback(sitePill);
        sitePill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else a.chooseSources();
                });
        siteSlot.addView(
                sitePill,
                new FrameLayout.LayoutParams(-2, a.dp(34), Gravity.END | Gravity.CENTER_VERTICAL));

        // Right 2: Netdisk / Config Liquid Glass capsule
        TextView netdiskPill = a.text(a.config == null ? "＋配置" : "网盘", 12.5f, Color.WHITE);
        netdiskPill.setTag("home-netdisk-button");
        netdiskPill.setTypeface(null, Typeface.BOLD);
        netdiskPill.setGravity(Gravity.CENTER);
        netdiskPill.setPadding(a.dp(11), a.dp(7), a.dp(11), a.dp(7));
        a.glass(netdiskPill, 999);
        MobileDetailScreen.bindTouchFeedback(netdiskPill);
        netdiskPill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else AccountPanel.show(a, null);
                });
        LinearLayout.LayoutParams ndLp = new LinearLayout.LayoutParams(-2, a.dp(34));
        ndLp.rightMargin = a.dp(6);
        bar.addView(netdiskPill, ndLp);

        // Right 3: Circular Liquid Glass Search button (`nav-search`)
        FrameLayout searchCircle = new FrameLayout(a);
        searchCircle.setTag("nav-search");
        a.glass(searchCircle, 999);
        MobileDetailScreen.bindTouchFeedback(searchCircle);
        searchCircle.setOnClickListener(v -> switchTab(1));
        NavGlyph searchGlyph = new NavGlyph(a, 1);
        searchGlyph.setIconTint(0xfff3f4f6);
        searchCircle.addView(
                searchGlyph, new FrameLayout.LayoutParams(a.dp(17), a.dp(17), Gravity.CENTER));
        bar.addView(searchCircle, new LinearLayout.LayoutParams(a.dp(34), a.dp(34)));

        return bar;
    }

    /**
     * Builds the iOS 27 Floating Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`), with an
     * inner translucent glass lens on the active tab and continuous press-and-slide tab switching.
     */
    private LinearLayout buildNavigationBar(final boolean verticalRail) {
        final int touchSlop = a.dp(7);
        LinearLayout bar =
                new LinearLayout(a) {
                    private float downX;
                    private float downY;
                    private boolean sliding;
                    private int pendingTab = -1;

                    @Override
                    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            downX = ev.getX();
                            downY = ev.getY();
                            sliding = false;
                            pendingTab = -1;
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            float delta =
                                    verticalRail
                                            ? Math.abs(ev.getY() - downY)
                                            : Math.abs(ev.getX() - downX);
                            if (delta > touchSlop) {
                                sliding = true;
                                return true;
                            }
                        }
                        return super.onInterceptTouchEvent(ev);
                    }

                    @Override
                    public boolean onTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN
                                || action == android.view.MotionEvent.ACTION_MOVE) {
                            int count = DOCK_TAB_INDICES.length;
                            float pad = a.dp(6);
                            float span = (verticalRail ? getHeight() : getWidth()) - pad * 2f;
                            float coord = (verticalRail ? ev.getY() : ev.getX()) - pad;
                            if (span > 0 && count > 0) {
                                int slot =
                                        Math.max(
                                                0,
                                                Math.min(
                                                        count - 1,
                                                        (int) Math.floor((coord / span) * count)));
                                int targetTab = DOCK_TAB_INDICES[slot];
                                int currentDockTab =
                                        pendingTab >= 0
                                                ? pendingTab
                                                : (activeTab == 1 ? 0 : activeTab);
                                if (targetTab != currentDockTab) {
                                    performHapticFeedback(
                                            android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                    pendingTab = targetTab;
                                }
                            }
                            return true;
                        }
                        if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            sliding = false;
                            if (action == android.view.MotionEvent.ACTION_UP && pendingTab >= 0)
                                switchTab(pendingTab);
                            pendingTab = -1;
                            return true;
                        }
                        return super.onTouchEvent(ev);
                    }
                };
        bar.setOrientation(verticalRail ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setTag("home-dock");
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        a.glass(bar, 26);
        bar.setElevation(a.dp(18));

        String[] navKeys = {"nav-home", "nav-search", "nav-live", "nav-favorites", "nav-settings", "nav-tv"};
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            final int tabIdx = DOCK_TAB_INDICES[pos];
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[tabIdx]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, tabIdx);
            navIcons[tabIdx] = glyph;
            tab.addView(
                    glyph, new LinearLayout.LayoutParams(a.dp(22 * navScale), a.dp(22 * navScale)));

            TextView label = a.text(TAB_NAMES[tabIdx], 10.5f * navScale, UiTokens.TEXT_PRIMARY);
            label.setGravity(Gravity.CENTER);
            // 图标与文字之间 +2dp，避免 Dock 里文字贴着图标底部
            label.setPadding(0, a.dp(4), 0, 0);
            navLabels[tabIdx] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));

            tab.setOnClickListener(v -> switchTab(tabIdx));
            navTabs[tabIdx] = tab;

            LinearLayout.LayoutParams lp =
                    verticalRail
                            ? new LinearLayout.LayoutParams(-1, 0, 1f)
                            : new LinearLayout.LayoutParams(0, -1, 1f);
            bar.addView(tab, lp);
        }
        return bar;
    }

    private static int homeScrollY;
    private ScrollView homeScroll;
    private LinearLayout homeBody;

    void switchTab(int index) {
        ++homeRequest;
        homeReady = false;
        ++a.generation;
        if (activeTab == 0 && homeScroll != null && homeScroll.getScrollY() > 0)
            homeScrollY = homeScroll.getScrollY();
        activeTab = Math.max(0, Math.min(5, index));
        a.cancelPageRequests();
        // When on Search tab (1), highlight Home (0) in the 4-tab dock
        int highlightedDockTab = activeTab == 1 ? 0 : activeTab;
        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            int i = DOCK_TAB_INDICES[pos];
            boolean selected = i == highlightedDockTab;
            if (navTabs[i] != null) {
                if (selected) {
                    navTabs[i].setBackground(
                            glassBg(0x38ffffff, 0x1effffff, a.dp(20), a.dp(0.8f), 0x52ffffff));
                } else {
                    navTabs[i].setBackground(null);
                }
            }
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(
                        selected ? UiTokens.ACCENT : UiTokens.TEXT_PRIMARY);
                navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (navIcons[i] != null) {
                navIcons[i].setSelectedState(selected);
            }
        }
        contentContainer.removeAllViews();
        filterSummaryBar = null;
        switch (activeTab) {
            case 0:
                renderHomeTab();
                break;
            case 1:
                renderSearchTab();
                break;
            case 2:
                renderLiveTab();
                break;
            case 3:
                renderMyTab();
                break;
            case 4:
                renderSettingsTab();
                break;
            case 5:
                renderTvTab();
                break;
        }
    }

    /** TAB 5：电视台直播（官方网页直播，内置央视 + 自定义频道）。 */
    private String tvAspectLabel() {
        int v = a.prefs.getInt(Prefs.TV_ASPECT, 0);
        return v == 1 ? "16:9" : v == 2 ? "4:3" : "自适应（默认）";
    }

    private String tvFillLabel() {
        int v = a.prefs.getInt(Prefs.TV_FILL, 0);
        return v == 1 ? "裁剪（铺满）" : v == 2 ? "拉伸" : "适应（完整画面）";
    }

    private void renderTvTab() {
        contentContainer.addView(TvLive.screen(a), new FrameLayout.LayoutParams(-1, -1));
    }

    // =========================================================================
    // TAB 0: IPHONE PORTRAIT HOME (Scrollable Header + Category Pills + 3-Col Grid)
    // =========================================================================
    private void renderHomeTab() {
        if (a.config == null || currentSite == null) {
            renderEmptyWelcome();
            return;
        }

        final ScrollView scrollView = new ScrollView(a);
        homeScroll = scrollView;
        final int restoreScroll =
                pendingHomeScroll >= 0 ? pendingHomeScroll : homeScrollY;
        pendingHomeScroll = -1;
        scrollView.post(() -> scrollView.scrollTo(0, Math.max(0, restoreScroll)));
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        a.scroll = scrollView;
        contentContainer.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));
        if (filterSummaryBar != null) filterSummaryBar.view().bringToFront();

        // 底部常驻筛选摘要条：未选时是入口，已选时展示条件 chips，点击展开筛选浮层
        final float homeDensity = a.getResources().getDisplayMetrics().density;
        final boolean sideRailLayout = a.windowWidth() / homeDensity >= 640 && !a.isPortrait();
        final float homeNavScale =
                Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        filterBarBottomDp =
                sideRailLayout ? 16 : Math.round(16 + 64 * homeNavScale + 10);
        filterSummaryBar =
                new FilterSummaryBar(a, selectedFilters, () -> showHomeFilters(null));
        FrameLayout.LayoutParams filterBarLp =
                new FrameLayout.LayoutParams(
                        -1, a.dp(FilterSummaryBar.HEIGHT_DP), Gravity.BOTTOM);
        filterBarLp.leftMargin = a.dp(UiTokens.SP_16);
        filterBarLp.rightMargin = a.dp(UiTokens.SP_16);
        filterBarLp.bottomMargin = a.dp(filterBarBottomDp);
        contentContainer.addView(filterSummaryBar.view(), filterBarLp);

        final LinearLayout body = a.vertical();
        a.body = body;
        homeBody = body;
        body.setPadding(
                0,
                statusBarHeight() + a.dp(8),
                0,
                a.dp(filterBarBottomDp + FilterSummaryBar.HEIGHT_DP + 18));
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Scrollable Top Header Row (Brand + Site Capsule + Netdisk Capsule + Search Icon)
        body.addView(buildHomeTopHeaderRow(), new LinearLayout.LayoutParams(-1, -2));

        // 2. History shelf; the newest entry remains first and resumes on tap.
        List<WatchHistory.Entry> history = a.watchHistory();
        if (a.prefs.getBoolean(Prefs.HOME_SHOW_HISTORY, true) && !history.isEmpty()) {
            LinearLayout histHeader = a.row();
            histHeader.setGravity(Gravity.CENTER_VERTICAL);
            histHeader.setPadding(
                    a.dp(UiTokens.SP_16),
                    a.dp(UiTokens.SP_12),
                    a.dp(UiTokens.SP_16),
                    a.dp(UiTokens.SP_8));
            TextView hTitle = a.text("历史记录", 18, MainActivity.WHITE);
            hTitle.setTypeface(null, Typeface.BOLD);
            histHeader.addView(hTitle, new LinearLayout.LayoutParams(0, -2, 1));
            TextView clearHist = a.text("清理", 12.5f, 0xff8d9cb6);
            clearHist.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            MobileDetailScreen.bindTouchFeedback(clearHist);
            clearHist.setOnClickListener(v -> showHistoryCleanup());
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, -2);
            clearLp.rightMargin = a.dp(10);
            histHeader.addView(clearHist, clearLp);
            TextView moreHist = a.text("查看全部", 13, 0xffff8fb1);
            moreHist.setTypeface(null, Typeface.BOLD);
            MobileDetailScreen.bindTouchFeedback(moreHist);
            moreHist.setOnClickListener(
                    v -> {
                        mySubTab = 0;
                        switchTab(3);
                    });
            histHeader.addView(moreHist);
            body.addView(histHeader, new LinearLayout.LayoutParams(-1, -2));

            HorizontalScrollView histScroll = new HorizontalScrollView(a);
            histScroll.setHorizontalScrollBarEnabled(false);
            histScroll.setClipToPadding(false);
            histScroll.setPadding(a.dp(16), a.dp(2), a.dp(16), a.dp(2));
            LinearLayout histRow = a.row();
            histScroll.addView(histRow);
            for (int i = 0; i < Math.min(8, history.size()); i++) {
                final WatchHistory.Entry e = history.get(i);
                LinearLayout card = a.row();
                card.setTag("home-history:" + i);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(
                        a.dp(UiTokens.SP_10),
                        a.dp(UiTokens.SP_10),
                        a.dp(UiTokens.SP_12),
                        a.dp(UiTokens.SP_10));
                card.setBackground(
                        glassBg(
                                0xcc1e3557,
                                0xb8151b19,
                                a.dp(UiTokens.RADIUS_20),
                                a.dp(0.8f),
                                0x38ffffff));
                MobileDetailScreen.bindTouchFeedback(card);
                card.setOnClickListener(v -> a.resumeHistory(e));

                ImageView thumb = new ImageView(a);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(a.bg(0xff1b2e48, 10));
                thumb.setClipToOutline(true);
                LinearLayout.LayoutParams thumbLp =
                        new LinearLayout.LayoutParams(a.dp(46), a.dp(64));
                thumbLp.rightMargin = a.dp(10);
                card.addView(thumb, thumbLp);
                if (e.poster != null && !e.poster.isEmpty()) a.poster(e.poster, thumb);

                LinearLayout meta = a.vertical();
                TextView t = a.text(e.title, 13.5f, MainActivity.WHITE);
                t.setTypeface(null, Typeface.BOLD);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                meta.addView(t);

                TextView sub = a.text(a.historyLabel(e), 11.5f, 0xff9fb0c9);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(0, a.dp(3), 0, a.dp(7));
                meta.addView(sub);

                // Progress bar matching iPhone Continue Watching card
                FrameLayout progTrack = new FrameLayout(a);
                progTrack.setBackground(a.bg(0x33ffffff, 2));
                float frac =
                        e.duration > 0
                                ? Math.max(0.12f, Math.min(1f, (float) e.position / e.duration))
                                : 0.2f;
                View progFill = new View(a);
                progFill.setBackground(a.bg(0xffff8fb1, 2));
                progTrack.addView(
                        progFill,
                        new FrameLayout.LayoutParams(
                                a.dp(Math.round(126 * frac)), a.dp(3), Gravity.START));
                meta.addView(progTrack, new LinearLayout.LayoutParams(a.dp(126), a.dp(3)));

                card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));

                LinearLayout.LayoutParams cardLp =
                        new LinearLayout.LayoutParams(a.dp(212), a.dp(84));
                cardLp.rightMargin = a.dp(12);
                histRow.addView(card, cardLp);
            }
            body.addView(histScroll, new LinearLayout.LayoutParams(-1, -2));
        }

        // 3. Featured Hero Showcase Card Container ("今日首推")
        final FrameLayout heroContainer = new FrameLayout(a);
        heroContainer.setPadding(
                a.dp(UiTokens.SP_16), a.dp(UiTokens.SP_12), a.dp(UiTokens.SP_16), 0);
        heroContainer.setVisibility(View.GONE);
        if (a.prefs.getBoolean(Prefs.HOME_SHOW_HERO, true)) {
            body.addView(heroContainer, new LinearLayout.LayoutParams(-1, -2));
        }

        // 3.5 「今晚看什么」：随机三部，换一批
        final LinearLayout tonightCard = a.vertical();
        tonightCard.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(12));
        tonightCard.setBackground(
                glassBg(0xcc16283f, 0xb3121d2e, a.dp(20), a.dp(0.8f), 0x33ffffff));
        tonightCard.setVisibility(View.GONE);
        LinearLayout tonightHeader = a.row();
        tonightHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tonightTitles = a.vertical();
        TextView tonightTitle = a.text("今晚看什么", 17, MainActivity.WHITE);
        tonightTitle.setTypeface(null, Typeface.BOLD);
        tonightTitles.addView(tonightTitle);
        TextView tonightSub = a.text("随机三部 · 点一下直达详情", 11.5f, 0xff9fb0c9);
        tonightSub.setPadding(0, a.dp(3), 0, 0);
        tonightTitles.addView(tonightSub);
        tonightHeader.addView(tonightTitles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tonightShuffle = a.text("换一批", 12, MainActivity.WHITE);
        tonightShuffle.setGravity(Gravity.CENTER);
        tonightShuffle.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        tonightShuffle.setBackground(glassBg(0x33ffffff, 0x2effffff, a.dp(999), a.dp(0.8f), 0x44ffffff));
        MobileDetailScreen.bindTouchFeedback(tonightShuffle);
        tonightHeader.addView(tonightShuffle);
        tonightCard.addView(tonightHeader, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout tonightRow = a.row();
        LinearLayout.LayoutParams tonightRowLp = new LinearLayout.LayoutParams(-1, -2);
        tonightRowLp.topMargin = a.dp(12);
        tonightCard.addView(tonightRow, tonightRowLp);
        LinearLayout.LayoutParams tonightCardLp =
                new LinearLayout.LayoutParams(-1, -2);
        tonightCardLp.leftMargin = a.dp(16);
        tonightCardLp.rightMargin = a.dp(16);
        tonightCardLp.topMargin = a.dp(12);
        body.addView(tonightCard, tonightCardLp);
        final Runnable[] refreshTonight = new Runnable[1];
        refreshTonight[0] =
                () -> {
                    if (!a.prefs.getBoolean(Prefs.HOME_SHOW_TONIGHT, true)) {
                        tonightCard.setVisibility(View.GONE);
                        return;
                    }
                    if (currentVideos.size() < 3) {
                        tonightCard.setVisibility(View.GONE);
                        return;
                    }
                    tonightRow.removeAllViews();
                    java.util.List<Cms.Video> pool =
                            new java.util.ArrayList<>(currentVideos);
                    java.util.Collections.shuffle(pool, new java.util.Random(System.nanoTime()));
                    int picks = Math.min(3, pool.size());
                    float d = a.getResources().getDisplayMetrics().density;
                    int cardW = Math.max(96, Math.round((a.windowWidth() / d - 64) / 3f) - 8);
                    for (int i = 0; i < picks; i++) {
                        final Cms.Video v = pool.get(i);
                        LinearLayout pick = a.vertical();
                        MobileDetailScreen.bindTouchFeedback(pick);
                        pick.setOnClickListener(x -> a.openListing(currentSite, v));
                        FrameLayout art = new FrameLayout(a);
                        art.setBackground(glassBg(0xff1a2c45, 0xff13233a, a.dp(12), a.dp(0.6f), 0x33ffffff));
                        art.setClipToOutline(true);
                        TextView ph = a.text(v.title == null || v.title.isEmpty() ? "鲸" : v.title.substring(0, 1), 26, 0x40ffffff);
                        ph.setTypeface(null, Typeface.BOLD);
                        ph.setGravity(Gravity.CENTER);
                        art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
                        ImageView poster = new ImageView(a);
                        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
                        if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, poster);
                        pick.addView(
                                art,
                                new LinearLayout.LayoutParams(-1, a.dp(Math.round(cardW * 1.42f))));
                        TextView tt = a.text(v.title, 11.5f, 0xffeaf1fb);
                        tt.setSingleLine(true);
                        tt.setEllipsize(TextUtils.TruncateAt.END);
                        tt.setPadding(a.dp(1), a.dp(6), a.dp(1), 0);
                        pick.addView(tt);
                        LinearLayout.LayoutParams pickLp =
                                new LinearLayout.LayoutParams(
                                        a.dp(cardW), -2);
                        pickLp.rightMargin = a.dp(8);
                        tonightRow.addView(pick, pickLp);
                    }
                    tonightCard.setVisibility(View.VISIBLE);
                };
        tonightShuffle.setOnClickListener(v -> refreshTonight[0].run());

        // 4. Horizontal Scrollable Category Pill Bar
        HorizontalScrollView catScroll =
                new HorizontalScrollView(a) {
                    @Override
                    protected void dispatchDraw(Canvas canvas) {
                        int save = canvas.save();
                        canvas.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
                        super.dispatchDraw(canvas);
                        canvas.restoreToCount(save);
                    }
                };
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setClipChildren(true);
        catScroll.setClipToPadding(false);
        catScroll.setPadding(
                a.dp(UiTokens.SP_16),
                a.dp(UiTokens.SP_12),
                a.dp(UiTokens.SP_8),
                a.dp(UiTokens.SP_8));
        final LinearLayout catRow = a.row();
        catRow.setGravity(Gravity.CENTER_VERTICAL);
        catScroll.addView(catRow);
        LinearLayout categoryBar = a.row();
        categoryBar.setClipChildren(true);
        categoryBar.setGravity(Gravity.CENTER_VERTICAL);
        categoryBar.addView(catScroll, new LinearLayout.LayoutParams(0, -2, 1));
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView filterButton = a.text("筛选", 13 * navScale, MainActivity.WHITE);
        filterButton.setTag("home-filters");
        filterButton.setSingleLine(true);
        filterButton.setGravity(Gravity.CENTER);
        filterButton.setPadding(
                a.dp(14 * navScale), a.dp(9 * navScale), a.dp(14 * navScale), a.dp(9 * navScale));
        a.glass(filterButton, 999);
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(-2, -2);
        filterSize.leftMargin = a.dp(8);
        filterSize.rightMargin = a.dp(16);
        filterSize.topMargin = a.dp(2);
        categoryBar.addView(filterButton, filterSize);
        body.addView(categoryBar, new LinearLayout.LayoutParams(-1, -2));

        // 5. Status & 3-Column Poster Grid Container
        final TextView statusText = a.text("正在加载片单…", 13, MainActivity.MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(a.dp(16), a.dp(24), a.dp(16), a.dp(24));
        body.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout gridContainer = a.vertical();
        gridContainer.setPadding(
                a.dp(UiTokens.SP_16), a.dp(UiTokens.SP_2), a.dp(UiTokens.SP_16), 0);
        body.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        final TextView loadMoreBtn = a.text("加载更多影片", 13.5f, MainActivity.WHITE);
        loadMoreBtn.setTag("home-load-more");
        loadMoreBtn.setTypeface(null, Typeface.BOLD);
        loadMoreBtn.setGravity(Gravity.CENTER);
        loadMoreBtn.setPadding(a.dp(20), a.dp(11), a.dp(20), a.dp(11));
        loadMoreBtn.setBackground(
                glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.8f), 0x33ffffff));
        loadMoreBtn.setVisibility(View.GONE);
        MobileDetailScreen.bindTouchFeedback(loadMoreBtn);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.setMargins(
                a.dp(UiTokens.SP_16),
                a.dp(UiTokens.SP_12),
                a.dp(UiTokens.SP_16),
                a.dp(UiTokens.SP_8));
        body.addView(loadMoreBtn, moreLp);

        final boolean[] loadingPage = {false};
        final boolean[] pageFailed = {false};
        final int[] completedPage = {0};
        resumeHome =
                () -> {
                    if (!loadingPage[0]) return;
                    loadingPage[0] = false;
                    pageFailed[0] = false;
                    currentPage = Math.max(1, completedPage[0]);
                    loadMoreBtn.setVisibility(currentPage < totalPages ? View.VISIBLE : View.GONE);
                    loadMoreBtn.setText("加载更多影片 (" + currentPage + "/" + totalPages + ")");
                };
        final Runnable[] fetchCategoryPage = new Runnable[1];
        final Runnable renderCategoryChips =
                new Runnable() {
                    @Override
                    public void run() {
                        filterButton.setText(
                                selectedFilters.isEmpty()
                                        ? "筛选"
                                        : "筛选 · " + selectedFilters.size());
                        if (filterSummaryBar != null)
                            filterSummaryBar.refresh(mobileFilterDefinitions());
                        catRow.removeAllViews();
                        addCategoryChip(catRow, "推荐", "", this, fetchCategoryPage[0]);
                        for (Cms.Category c : currentCategories) {
                            addCategoryChip(catRow, c.name, c.id, this, fetchCategoryPage[0]);
                        }
                    }
                };

        filterButton.setOnClickListener(
                v ->
                        showHomeFilters(
                                () -> {
                                    if (fetchCategoryPage[0] != null)
                                        fetchCategoryPage[0].run();
                                }));
        homeFilterApply = () -> fetchCategoryPage[0].run();
        fetchCategoryPage[0] =
                () -> {
                    final int request = ++homeRequest;
                    loadingPage[0] = true;
                    if (currentPage == 1) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText("正在加载…");
                        loadMoreBtn.setVisibility(View.GONE);
                    } else {
                        loadMoreBtn.setVisibility(View.VISIBLE);
                        loadMoreBtn.setText("正在加载更多…");
                    }
                    final Cms.Site siteSnapshot = currentSite;
                    final String catSnapshot = selectedCategoryId;
                    final int pageSnapshot = currentPage;
                    final java.util.Map<String, String> filterSnapshot =
                            new java.util.LinkedHashMap<>(selectedFilters);
                    final List<VideoFilters.Definition> definitions = mobileFilterDefinitions();
                    final int ticket = a.generation;
                    final HomeScreen.Snapshot cached =
                            siteSnapshot == null ? null : a.homeData.get(siteSnapshot.key);
                    if (catSnapshot.isEmpty() && pageSnapshot == 1 && siteSnapshot != null) {
                        a.prefs.edit().putString(Prefs.LOADING_SITE, siteSnapshot.key).commit();
                    }
                    a.submitPage(
                            () -> {
                                try {
                                    SourceEngine eng = a.engine(siteSnapshot);
                                    List<Cms.Category> cats = currentCategories;
                                    Cms.Listing list;
                                    HomeScreen.Snapshot homeSnapshot = null;
                                    if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                        String raw =
                                                cached != null
                                                                && System.currentTimeMillis()
                                                                                - cached.time
                                                                        < 300000
                                                        ? cached.filterSchema
                                                        : eng.home();
                                        cats = Cms.categories(raw);
                                        list =
                                                new JSONObject(raw).has("list")
                                                        ? Cms.listing(raw)
                                                        : new Cms.Listing();
                                        if (list.videos.isEmpty()
                                                && cached != null
                                                && System.currentTimeMillis() - cached.time
                                                        < 300000) {
                                            list.videos.addAll(cached.videos);
                                            list.pageCount = cached.pages;
                                        }
                                        if (list.videos.isEmpty()) {
                                            try {
                                                list = Cms.listing(eng.homeVideos());
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                        if (list.videos.isEmpty() && !cats.isEmpty()) {
                                            list = Cms.listing(eng.category(cats.get(0).id, 1));
                                        }
                                        HomeScreen.Snapshot saved =
                                                new HomeScreen.Snapshot(
                                                        list.videos, cats, "", list.pageCount);
                                        saved.filterSchema = raw;
                                        homeSnapshot = saved;
                                    } else {
                                        String targetCat =
                                                catSnapshot.isEmpty() && !cats.isEmpty()
                                                        ? cats.get(0).id
                                                        : catSnapshot;
                                        java.util.List<java.util.Map<String, String>> variants =
                                                filterVariants(definitions, filterSnapshot);
                                        if (variants.size() <= 1) {
                                            list =
                                                    Cms.listing(
                                                            eng.category(
                                                                    targetCat,
                                                                    pageSnapshot,
                                                                    variants.isEmpty()
                                                                            ? java.util
                                                                                    .Collections
                                                                                    .<String, String>
                                                                                            emptyMap()
                                                                            : variants.get(0)));
                                        } else {
                                            // 多选：按每个取值分别请求再合并（片源只支持单值）
                                            java.util.LinkedHashMap<String, Cms.Video> merged =
                                                    new java.util.LinkedHashMap<>();
                                            int pageCount = 1;
                                            for (java.util.Map<String, String> variant :
                                                    variants) {
                                                Cms.Listing part =
                                                        Cms.listing(
                                                                eng.category(
                                                                        targetCat,
                                                                        pageSnapshot,
                                                                        variant));
                                                for (Cms.Video v : part.videos)
                                                    if (!merged.containsKey(v.id))
                                                        merged.put(v.id, v);
                                                pageCount = Math.max(pageCount, part.pageCount);
                                            }
                                            list = new Cms.Listing();
                                            list.videos.addAll(merged.values());
                                            list.pageCount = pageCount;
                                        }
                                    }
                                    final HomeScreen.Snapshot finalHomeSnapshot = homeSnapshot;
                                    final List<Cms.Category> finalCats = cats;
                                    final Cms.Listing finalList = list;
                                    List<Cms.Video> filtered =
                                            VideoFilters.apply(
                                                    list.videos, definitions, filterSnapshot);
                                    java.util.Map<String, String> remoteSent =
                                            VideoFilters.remoteValues(
                                                    definitions, filterSnapshot);
                                    boolean multiRemote = false;
                                    for (String v : filterSnapshot.values())
                                        if (v != null && v.contains(",")) multiRemote = true;
                                    if (multiRemote
                                            && !remoteSent.isEmpty()
                                            && a.prefs.getBoolean(
                                                    "filter_multi_toast", true)) {
                                        a.prefs.edit()
                                                .putBoolean("filter_multi_toast", false)
                                                .apply();
                                        a.ui.post(
                                                () ->
                                                        a.toast(
                                                                "该片源远程筛选只支持单选，多选时先用第一个条件"));
                                    }
                                    boolean remoteIgnored =
                                            !remoteSent.isEmpty()
                                                    && !list.videos.isEmpty()
                                                    && filtered.size() == list.videos.size();
                                    if (remoteIgnored) {
                                        // 该片源忽略筛选参数：退回本地资料筛选
                                        filtered =
                                                VideoFilters.applyAll(
                                                        list.videos,
                                                        definitions,
                                                        filterSnapshot);
                                        if (a.prefs.getBoolean("filter_unsupported_toast", true)) {
                                            a.prefs.edit()
                                                    .putBoolean(
                                                            "filter_unsupported_toast", false)
                                                    .apply();
                                            a.ui.post(
                                                    () ->
                                                            a.toast(
                                                                    "该片源不返回筛选结果，已按本地影片资料筛选"));
                                        }
                                    }
                                    // 片源既不返回筛选结果、本地资料也筛不动：隐藏筛选入口并提示
                                    if (!filterSnapshot.isEmpty()
                                            && remoteSent.isEmpty()
                                            && !list.videos.isEmpty()
                                            && filtered.size() == list.videos.size()) {
                                        a.ui.post(
                                                () -> {
                                                    View fbv =
                                                            a.root == null
                                                                    ? null
                                                                    : a.root.findViewWithTag(
                                                                            "home-filters");
                                                    if (fbv != null)
                                                        fbv.setVisibility(View.GONE);
                                                    if (filterSummaryBar != null)
                                                        filterSummaryBar
                                                                .view()
                                                                .setVisibility(View.GONE);
                                                    a.toast("该片源不支持筛选，已隐藏筛选入口");
                                                });
                                    }
                                    final boolean filterEmpty =
                                            filtered.isEmpty() && !filterSnapshot.isEmpty();
                                    if (filterEmpty) filtered = list.videos;
                                    final List<Cms.Video> finalFiltered = filtered;
                                    a.learnTitles(finalList.videos);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = false;
                                                completedPage[0] = pageSnapshot;
                                                homeReady = true;
                                                if (finalHomeSnapshot != null) {
                                                    filterSchema = finalHomeSnapshot.filterSchema;
                                                    a.homeData.put(
                                                            siteSnapshot.key, finalHomeSnapshot);
                                                }
                                                currentCategories = finalCats;
                                                renderCategoryChips.run();
                                                if (pageSnapshot == 1) currentVideos.clear();
                                                for (Cms.Video v : finalFiltered) {
                                                    if (v.action == null || v.action.isEmpty()) {
                                                        currentVideos.add(v);
                                                    }
                                                }
                                                totalPages = Math.max(1, finalList.pageCount);
                                                statusText.setVisibility(
                                                        currentVideos.isEmpty()
                                                                ? View.VISIBLE
                                                                : View.GONE);
                                                if (currentVideos.isEmpty()) {
                                                    statusText.setText(
                                                            selectedFilters.isEmpty()
                                                                    ? "当前分类暂无影片"
                                                                    : "当前筛选暂无匹配，可继续加载更多影片");
                                                }
                                                // Render Featured Hero Showcase on Recommend tab
                                                if (catSnapshot.isEmpty()
                                                        && !currentVideos.isEmpty()) {
                                                    heroContainer.removeAllViews();
                                                    int heroCardW =
                                                            Math.max(
                                                                    a.dp(170),
                                                                    Math.min(
                                                                            a.dp(340),
                                                                            Math.round(
                                                                                    a.windowWidth()
                                                                                            * 0.25f)));
                                                    int heroCardH =
                                                            Math.max(
                                                                    a.dp(104),
                                                                    Math.round(
                                                                            a.windowHeight()
                                                                                    * 0.25f));
                                                    heroContainer.addView(
                                                            buildHeroShowcaseCard(
                                                                    siteSnapshot,
                                                                    currentVideos.get(0)),
                                                            new FrameLayout.LayoutParams(
                                                                    heroCardW,
                                                                    heroCardH,
                                                                    Gravity.START));
                                                    heroContainer.setVisibility(View.VISIBLE);
                                                } else {
                                                    heroContainer.removeAllViews();
                                                    heroContainer.setVisibility(View.GONE);
                                                }
                                                renderVideoGrid(
                                                        gridContainer, siteSnapshot, currentVideos);
                                                refreshTonight[0].run();
                                                if (currentPage < totalPages) {
                                                    loadMoreBtn.setVisibility(View.VISIBLE);
                                                    loadMoreBtn.setText(
                                                            "加载更多影片 ("
                                                                    + currentPage
                                                                    + "/"
                                                                    + totalPages
                                                                    + ")");
                                                } else {
                                                    loadMoreBtn.setVisibility(View.GONE);
                                                }
                                            });
                                } catch (Throwable e) {
                                    AppLog.w("MobileShell", "fetchCategoryPage failed", e);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = true;
                                                homeReady = !currentVideos.isEmpty();
                                                statusText.setVisibility(View.VISIBLE);
                                                statusText.setText("加载失败：" + a.message(e));
                                                loadMoreBtn.setVisibility(View.VISIBLE);
                                                loadMoreBtn.setText("点击重试");
                                            });
                                }
                            });
                };

        loadMoreBtn.setOnClickListener(
                v -> {
                    if (loadingPage[0]) return;
                    if (!pageFailed[0] && currentPage < totalPages) currentPage++;
                    fetchCategoryPage[0].run();
                });

        // Auto-load next page when scrolling near bottom (matching iPhone infinite scroll)
        scrollView
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (loadingPage[0] || pageFailed[0] || currentPage >= totalPages)
                                return;
                            View child = scrollView.getChildAt(0);
                            if (child == null) return;
                            int diff =
                                    child.getBottom()
                                            - (scrollView.getHeight() + scrollView.getScrollY());
                            if (diff <= a.dp(240)) {
                                currentPage++;
                                fetchCategoryPage[0].run();
                            }
                        });

        renderCategoryChips.run();
        fetchCategoryPage[0].run();
    }

    /**
     * Builds the iPhone Featured Hero Showcase card (`_buildHeroShowcaseCard` in
     * `adaptive_shell.dart`): rounded `24dp` glass container with dimmed backdrop artwork, left
     * `2:3` poster, right `今日首推` pill, bold title, metadata chips, and bottom-right white `▶ 立即观看`
     * capsule button.
     */
    private View buildHeroShowcaseCard(final Cms.Site site, final Cms.Video featured) {
        FrameLayout card = new FrameLayout(a);
        card.setTag("hero-watch-now-button");
        card.setBackground(
                glassBg(
                        0x6616283f,
                        0x55101e33,
                        a.dp(UiTokens.RADIUS_20),
                        a.dp(0.7f),
                        0x44ffffff));
        card.setClipToOutline(true);
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(v -> a.openListing(site, featured));

        // Ambient backdrop image
        ImageView bgImg = new ImageView(a);
        bgImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImg.setAlpha(a.videoWallpaperEnabled() ? 0f : 0.18f);
        card.addView(bgImg, new FrameLayout.LayoutParams(-1, -1));
        if (featured.poster != null && !featured.poster.isEmpty()) a.poster(featured.poster, bgImg);

        // Dark gradient scrim
        View scrim = new View(a);
        scrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x22000000, 0x9906101f, 0xee060c18}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // 25% 宽度的小卡：海报铺底 + 标题叠加，点击即进入
        if (a.windowWidth() > 0 && Math.round(a.windowWidth() * 0.25f) < a.dp(330)) {
            bgImg.setAlpha(a.videoWallpaperEnabled() ? 0.35f : 0.75f);
            TextView miniTag = a.text("✦ 今日首推", 9, 0xfff2f5ff);
            miniTag.setTypeface(null, Typeface.BOLD);
            miniTag.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
            miniTag.setBackground(glassBg(0x38ffffff, 0x30ffffff, a.dp(999), a.dp(0.6f), 0x33ffffff));
            FrameLayout.LayoutParams tagLp =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            tagLp.setMargins(a.dp(10), a.dp(10), 0, 0);
            card.addView(miniTag, tagLp);
            TextView miniTitle = a.text(featured.title, 12.5f, Color.WHITE);
            miniTitle.setTypeface(null, Typeface.BOLD);
            miniTitle.setMaxLines(2);
            miniTitle.setEllipsize(TextUtils.TruncateAt.END);
            miniTitle.setShadowLayer(a.dp(8), 0, a.dp(2), 0xe6000000);
            FrameLayout.LayoutParams titleLp2 =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            titleLp2.setMargins(a.dp(10), 0, a.dp(10), a.dp(10));
            card.addView(miniTitle, titleLp2);
            TextView miniPlay = a.text("▶", 10, 0xff31141f);
            miniPlay.setTypeface(null, Typeface.BOLD);
            miniPlay.setGravity(Gravity.CENTER);
            miniPlay.setBackground(glassBg(0xffffc7d6, 0xfff59ab5, a.dp(999), 0, 0));
            FrameLayout.LayoutParams playLp =
                    new FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.BOTTOM | Gravity.END);
            playLp.setMargins(0, 0, a.dp(10), a.dp(10));
            card.addView(miniPlay, playLp);
            return card;
        }

        LinearLayout contentRow = a.row();
        contentRow.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        float heroDensity = a.getResources().getDisplayMetrics().density;
        int heroHDp =
                Math.max(
                        88,
                        Math.min(
                                150,
                                Math.round(
                                        (a.windowHeight() / heroDensity) * 0.25f)));
        card.addView(contentRow, new FrameLayout.LayoutParams(-1, a.dp(heroHDp)));

        // Left 2:3 Poster
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff122434, 10));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(58), a.dp(88));
        posterLp.rightMargin = a.dp(10);
        contentRow.addView(poster, posterLp);
        if (featured.poster != null && !featured.poster.isEmpty())
            a.poster(featured.poster, poster);

        // Right Column
        LinearLayout rightCol = a.vertical();
        contentRow.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView topBadge = a.text("✦ 今日首推 · " + site.name, 9, Color.WHITE);
        topBadge.setTypeface(null, Typeface.BOLD);
        topBadge.setSingleLine(true);
        topBadge.setEllipsize(TextUtils.TruncateAt.END);
        topBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
        topBadge.setBackground(glassBg(0x38ffffff, 0x30ffffff, a.dp(999), a.dp(0.6f), 0x33ffffff));
        rightCol.addView(topBadge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = a.text(featured.title, 13, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, a.dp(3), 0, a.dp(2));
        rightCol.addView(title);

        LinearLayout chipsRow = a.row();
        String[] tags = {featured.year, featured.area, featured.remarks};
        boolean hasChip = false;
        for (String rawTag : tags) {
            if (rawTag == null || rawTag.trim().isEmpty()) continue;
            TextView chip = a.text(rawTag.trim(), 9f, 0xffd2dced);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(a.dp(110));
            chip.setPadding(a.dp(5), a.dp(1), a.dp(5), a.dp(1));
            chip.setBackground(a.bg(0x29ffffff, 6));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.rightMargin = a.dp(6);
            chipsRow.addView(chip, chipLp);
            hasChip = true;
        }
        if (hasChip) {
            rightCol.addView(chipsRow, new LinearLayout.LayoutParams(-1, -2));
        }

        View flexSpacer = new View(a);
        rightCol.addView(flexSpacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Bottom-right Watch Now white capsule button
        LinearLayout bottomRow = a.row();
        bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView watchBtn = a.text("▶ 立即观看", 10.5f, 0xff0b0f0d);
        watchBtn.setTypeface(null, Typeface.BOLD);
        watchBtn.setGravity(Gravity.CENTER);
        watchBtn.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        watchBtn.setBackground(glassBg(0xffffffff, 0xffe9eef9, a.dp(999), 0, 0));
        bottomRow.addView(watchBtn, new LinearLayout.LayoutParams(-2, -2));
        rightCol.addView(bottomRow, new LinearLayout.LayoutParams(-1, -2));

        return card;
    }

    /** 打开首页筛选面板：筛选按钮与分类标签双击共用。 */
    void showHomeFilters(Runnable after) {
        VideoFilterDialog.show(
                a,
                mobileFilterDefinitions(),
                selectedFilters,
                () -> {
                    if (selectedCategoryId.isEmpty() && !currentCategories.isEmpty())
                        selectedCategoryId = currentCategories.get(0).id;
                    currentPage = 1;
                    View fbv = a.root == null ? null : a.root.findViewWithTag("home-filters");
                    if (fbv instanceof TextView)
                        ((TextView) fbv)
                                .setText(
                                        selectedFilters.isEmpty()
                                                ? "筛选"
                                                : "筛选 · " + selectedFilters.size());
                    if (filterSummaryBar != null)
                        filterSummaryBar.refresh(mobileFilterDefinitions());
                    if (homeFilterApply != null) homeFilterApply.run();
                    if (after != null) after.run();
                },
                0);
    }

    /** 多选筛选拆成多个单值请求（片源只支持单值），最多展开第一个多选维度的 3 个值。 */
    private static java.util.List<java.util.Map<String, String>> filterVariants(
            List<VideoFilters.Definition> definitions,
            java.util.Map<String, String> snapshot) {
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        if (snapshot.isEmpty()) {
            out.add(new java.util.LinkedHashMap<String, String>());
            return out;
        }
        java.util.Map<String, String> first = VideoFilters.remoteValues(definitions, snapshot);
        java.util.Map<String, String> base = new java.util.LinkedHashMap<>(first);
        if (base.isEmpty()) {
            // 无远程维度：直接返回空 map，交给本地筛选
            out.add(new java.util.LinkedHashMap<String, String>());
            return out;
        }
        out.add(base);
        for (VideoFilters.Definition def : definitions) {
            String selected = snapshot.get(def.dimension);
            if (selected == null || !selected.contains(",")) continue;
            String[] parts = selected.split(",");
            for (int i = 1; i < Math.min(parts.length, 3); i++) {
                java.util.Map<String, String> variant =
                        new java.util.LinkedHashMap<>(base);
                variant.put(def.key, parts[i]);
                out.add(variant);
            }
            break;
        }
        return out;
    }

    private List<VideoFilters.Definition> mobileFilterDefinitions() {
        String category =
                selectedCategoryId.isEmpty() && !currentCategories.isEmpty()
                        ? currentCategories.get(0).id
                        : selectedCategoryId;
        List<VideoFilters.Definition> defs =
                VideoFilters.definitions(filterSchema, category, currentVideos);
        if (currentSite != null && currentSite.supported())
            for (VideoFilters.Definition def : defs)
                if (!VideoFilters.allowedKey(def.key)) def.remote = false;
        return defs;
    }

    /**
     * Builds a category pill 1:1 with iPhone `_buildCategoryPill`: white stadium pill with black
     * bold text when selected, translucent dark glass pill with white text when unselected.
     */
    private void addCategoryChip(
            LinearLayout row,
            String label,
            String catId,
            Runnable refreshChips,
            Runnable loadPage) {
        boolean active = selectedCategoryId.equals(catId);
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView chip = a.text(label, 13 * navScale, active ? 0xff000000 : 0xfff3f4f6);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(
                a.dp(15 * navScale), a.dp(7 * navScale), a.dp(15 * navScale), a.dp(7 * navScale));
        if (active) {
            chip.setBackground(glassBg(0xffffffff, 0xffe9eef9, a.dp(999), a.dp(0.7f), 0xffffffff));
        } else {
            chip.setBackground(glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
        }
        MobileDetailScreen.bindTouchFeedback(chip);
        chip.setOnClickListener(
                v -> {
                    long now = android.os.SystemClock.uptimeMillis();
                    boolean doubleTap =
                            catId.equals(lastChipTap) && now - lastChipTapAt < 350;
                    lastChipTap = catId;
                    lastChipTapAt = now;
                    if (doubleTap) {
                        if (!selectedCategoryId.equals(catId)) {
                            selectedCategoryId = catId;
                            selectedFilters.clear();
                            currentPage = 1;
                            refreshChips.run();
                            loadPage.run();
                        }
                        showHomeFilters(null);
                        return;
                    }
                    if (selectedCategoryId.equals(catId)) return;
                    selectedCategoryId = catId;
                    selectedFilters.clear();
                    currentPage = 1;
                    refreshChips.run();
                    loadPage.run();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = a.dp(8);
        row.addView(chip, lp);
    }

    /**
     * Renders the 3-column portrait poster grid matching the iPhone screenshot (`_buildVideoCard`):
     * `12dp` rounded `2:3` poster with a smooth bottom gradient scrim and right-aligned `remarks`
     * inside the poster bottom edge, plus two-line left-aligned `title` + `year · area` below.
     */
    private void renderVideoGrid(LinearLayout container, Cms.Site site, List<Cms.Video> videos) {
        container.removeAllViews();
        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int cols = widthDp >= 720 ? 5 : (widthDp >= 520 ? 4 : 3);
        int horizontalPaddingDp = widthDp >= 640 ? 116 : 32;
        int cardWidthDp =
                Math.max(92, (int) ((widthDp - horizontalPaddingDp - (cols - 1) * 12) / cols));
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);

        LinearLayout currentRow = null;
        for (int i = 0; i < videos.size(); i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setGravity(Gravity.TOP);
                container.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            final Cms.Video v = videos.get(i);
            LinearLayout card = a.vertical();
            card.setTag(i == 0 ? "primary" : "video:" + v.id);
            MobileDetailScreen.bindTouchFeedback(card);
            card.setOnClickListener(view -> a.openListing(site, v));

            FrameLayout art = new FrameLayout(a);
            art.setBackground(glassBg(0xff1a2c45, 0xff13233a, a.dp(12), a.dp(0.6f), 0x33ffffff));
            art.setClipToOutline(true);

            // 无海报/加载失败时的占位：深海蓝渐变 + 片名首字
            TextView placeholder =
                    a.text(
                            v.title == null || v.title.isEmpty()
                                    ? "鲸"
                                    : v.title.substring(0, 1),
                            34,
                            0x40ffffff);
            placeholder.setTypeface(null, Typeface.BOLD);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(
                    new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            new int[] {0xff1b3352, 0xff2a1b33}));
            art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, img);

            // Bottom gradient scrim + bottom-right remarks label inside poster (1:1 with iPhone)
            if (v.remarks != null && !v.remarks.trim().isEmpty()) {
                FrameLayout bottomScrim = new FrameLayout(a);
                bottomScrim.setPadding(a.dp(7), a.dp(16), a.dp(7), a.dp(5));
                bottomScrim.setBackground(
                        new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[] {0x00000000, 0xc8000000}));
                TextView badge = a.text(v.remarks.trim(), 10, 0xfff5f7fa);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setSingleLine(true);
                badge.setEllipsize(TextUtils.TruncateAt.END);
                badge.setGravity(Gravity.END);
                bottomScrim.addView(
                        badge, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.END));
                art.addView(bottomScrim, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            }

            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(posterHeightDp)));

            // Line 1: Title (bold 13sp white)
            TextView title = a.text(v.title, 13, 0xfff5f7fa);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(a.dp(1), a.dp(7), a.dp(1), 0);
            card.addView(title);

            // Line 2: Subtitle (`year · area` or fallback to `remarks` / `site.name`, 11sp muted)
            String subtitleText = formatCardSubtitle(v, site);
            TextView sub = a.text(subtitleText, 11, 0xff8e959e);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(a.dp(1), a.dp(2), a.dp(1), a.dp(2));
            card.addView(sub);

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1);
            int colIdx = i % cols;
            cellLp.leftMargin = colIdx == 0 ? 0 : a.dp(5);
            cellLp.rightMargin = colIdx == cols - 1 ? 0 : a.dp(5);
            cellLp.topMargin = a.dp(4);
            cellLp.bottomMargin = a.dp(14);
            currentRow.addView(card, cellLp);
        }
        if (currentRow != null) {
            int rem = videos.size() % cols;
            if (rem > 0) {
                for (int i = rem; i < cols; i++) {
                    LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                    blankLp.leftMargin = a.dp(5);
                    blankLp.rightMargin = i == cols - 1 ? 0 : a.dp(5);
                    currentRow.addView(new View(a), blankLp);
                }
            }
        }
    }

    private static String formatCardSubtitle(Cms.Video v, Cms.Site site) {
        String year = v.year == null ? "" : v.year.trim();
        String area = v.area == null ? "" : v.area.trim();
        String tag = v.tag == null ? "" : v.tag.trim();
        StringBuilder sb = new StringBuilder();
        if (!year.isEmpty()) sb.append(year);
        if (!area.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(area);
        } else if (!tag.isEmpty() && !"folder".equalsIgnoreCase(tag)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(tag);
        }
        if (sb.length() > 0) return sb.toString();
        if (v.remarks != null && !v.remarks.trim().isEmpty()) return v.remarks.trim();
        return site != null && site.name != null ? site.name : "";
    }

    private void renderEmptyWelcome() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout wrap = a.vertical();
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(a.dp(22), statusBarHeight() + a.dp(20), a.dp(22), a.dp(110));
        sv.addView(wrap, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(a.dp(24), a.dp(26), a.dp(24), a.dp(26));
        card.setBackground(glassBg(0xd81a2e4a, 0xd8101715, a.dp(26), a.dp(0.8f), 0x33ffffff));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        logo.setContentDescription(a.privateMode ? "退出隐私空间" : "鲸落");
        logo.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
        card.addView(logo, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
        a.gap(card, 12);

        if (a.privateMode) {
            addMobileActionBtn(
                    card,
                    "退出隐私空间",
                    0xff13253d,
                    MainActivity.WHITE,
                    () -> PrivacySpace.switchSpace(a, false));
            a.gap(card, 12);
        }

        TextView heading = a.text("把今晚，留给好故事。", 23, MainActivity.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        a.gap(card, 10);

        TextView desc =
                a.text("导入你自己合法获取的影视配置，支持单仓/多仓配置地址、JSON 文本及手机剪贴板一键导入。", 13.5f, MainActivity.MUTED);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(a.dp(4), 1f);
        card.addView(desc);
        a.gap(card, 22);

        LinearLayout urlRow = a.row();
        final EditText urlInput = new EditText(a);
        urlInput.setHint("输入或长按粘贴配置网址 https://…");
        urlInput.setHintTextColor(0xff7f90ad);
        urlInput.setTextColor(MainActivity.WHITE);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(true);
        urlInput.setShowSoftInputOnFocus(true);
        urlInput.setLongClickable(true);
        urlInput.setTextIsSelectable(true);
        urlInput.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        urlInput.setBackground(a.bg(0xff13253d, 999));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, -2, 1);
        urlLp.rightMargin = a.dp(8);
        urlRow.addView(urlInput, urlLp);

        TextView goBtn = a.text("导入", 13, Color.WHITE);
        goBtn.setTypeface(null, Typeface.BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        goBtn.setBackground(a.bg(0xffff8fb1, 999));
        MobileDetailScreen.bindTouchFeedback(goBtn);
        goBtn.setOnClickListener(v -> importTextOrUrl(a, urlInput.getText().toString()));
        urlRow.addView(goBtn);
        card.addView(urlRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(card, 12);

        addMobileActionBtn(
                card, "一键粘贴剪贴板并导入", 0xffff8fb1, Color.WHITE, () -> pasteConfigFromClipboard(a));
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "选择本地配置或本地包 (.json/.js/.py/.zip)",
                0xff13253d,
                MainActivity.WHITE,
                a::pickConfig);
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "导入电视直播源 (M3U / TXT / JSON)",
                0xff13253d,
                MainActivity.WHITE,
                () -> SourceController.showLiveImportDialog(a));

        a.gap(wrap, 18);
        TextView legal = a.text("本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。", 11.5f, 0xff7f90ad);
        legal.setGravity(Gravity.CENTER);
        legal.setLineSpacing(a.dp(3), 1f);
        wrap.addView(legal);
    }

    private void addMobileActionBtn(
            LinearLayout parent, String text, int bgColor, int textColor, Runnable onClick) {
        TextView btn = a.text(text, 14, textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        btn.setBackground(a.bg(bgColor, 999));
        MobileDetailScreen.bindTouchFeedback(btn);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn, new LinearLayout.LayoutParams(-1, -2));
    }

    // =========================================================================
    // TAB 1: MOBILE SEARCH (System Soft Keyboard + Multi-Site Live Results)
    // =========================================================================
    private void renderSearchTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Top row: back circle + search capsule
        LinearLayout topBar = a.row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = a.text("‹", 22, Color.WHITE);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setBackground(glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
        MobileDetailScreen.bindTouchFeedback(backBtn);
        backBtn.setOnClickListener(v -> switchTab(0));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        backLp.rightMargin = a.dp(10);
        topBar.addView(backBtn, backLp);

        LinearLayout inputRow = a.row();
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(a.dp(14), a.dp(3), a.dp(5), a.dp(3));
        inputRow.setBackground(glassBg(0xd41a2c46, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));

        final EditText input = new EditText(a);
        input.setHint("搜索片名、演员或拼音首字母…");
        input.setHintTextColor(0xff8496b2);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(a.dp(2), a.dp(7), a.dp(8), a.dp(7));
        if (!pendingSearchKeyword.isEmpty()) {
            input.setText(pendingSearchKeyword);
            input.setSelection(pendingSearchKeyword.length());
        }
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        TextView clearBtn = a.text("✕", 13, MainActivity.MUTED);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        inputRow.addView(clearBtn);

        TextView searchBtn = a.text("搜索", 13, Color.WHITE);
        searchBtn.setTypeface(null, Typeface.BOLD);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        searchBtn.setBackground(a.bg(0xffff8fb1, 999));
        MobileDetailScreen.bindTouchFeedback(searchBtn);
        inputRow.addView(searchBtn);

        topBar.addView(inputRow, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // Search history chips
        final LinearLayout historyWrap = a.vertical();
        page.addView(historyWrap, new LinearLayout.LayoutParams(-1, -2));

        final TextView searchStatus = a.text("输入关键词搜索全部已启用的片源", 12, MainActivity.MUTED);
        searchStatus.setMaxLines(2);
        searchStatus.setEllipsize(TextUtils.TruncateAt.END);
        searchStatus.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(8));
        page.addView(searchStatus);

        final RecyclerView resultScroll = new RecyclerView(a);
        final LinearLayoutManager searchLayoutManager = new LinearLayoutManager(a);
        resultScroll.setLayoutManager(searchLayoutManager);
        resultScroll.setClipToPadding(false);
        resultScroll.setPadding(0, 0, 0, a.dp(106));
        page.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final int[] searchSerial = {0};
        final TextView sourceNotices = a.text("", 12, MainActivity.MUTED);
        sourceNotices.setPadding(a.dp(4), 0, a.dp(4), a.dp(10));
        sourceNotices.setVisibility(View.GONE);
        final SearchResultsAdapter searchAdapter =
                new SearchResultsAdapter(sourceNotices);
        resultScroll.setAdapter(searchAdapter);
        final Runnable renderSearchStatus =
                () -> {
                    int separator = cachedSearchStatus.indexOf('\n');
                    searchStatus.setText(
                            separator < 0
                                    ? cachedSearchStatus
                                    : cachedSearchStatus.substring(0, separator));
                    sourceNotices.setText(
                            separator < 0 ? "" : cachedSearchStatus.substring(separator + 1));
                    sourceNotices.setVisibility(separator < 0 ? View.GONE : View.VISIBLE);
                };

        clearBtn.setOnClickListener(
                v -> {
                    searchSerial[0]++;
                    a.cancelPageRequests();
                    input.setText("");
                    cachedSearchKeyword = "";
                    cachedSearchStatus = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    searchAdapter.clear();
                    sourceNotices.setText("");
                    sourceNotices.setVisibility(View.GONE);
                    searchStatus.setText("输入关键词搜索全部已启用的片源");
                });

        final Runnable[] triggerSearch = new Runnable[1];
        final Runnable renderHistoryChips =
                () -> {
                    historyWrap.removeAllViews();
                    try {
                        JSONArray arr =
                                new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                        if (arr.length() == 0) return;
                        LinearLayout hRow = a.row();
                        hRow.setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(4));
                        TextView hLabel = a.text("历史搜索", 12, MainActivity.MUTED);
                        hRow.addView(hLabel, new LinearLayout.LayoutParams(0, -2, 1));
                        TextView clearHist = a.text("清空", 12, 0xffff8fb1);
                        MobileDetailScreen.bindTouchFeedback(clearHist);
                        clearHist.setOnClickListener(
                                v -> {
                                    a.prefs
                                            .edit()
                                            .putString(Prefs.GLOBAL_SEARCH_HISTORY, "[]")
                                            .apply();
                                    historyWrap.removeAllViews();
                                });
                        hRow.addView(clearHist);
                        historyWrap.addView(hRow);

                        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
                        chipScroll.setHorizontalScrollBarEnabled(false);
                        LinearLayout chipRow = a.row();
                        chipScroll.addView(chipRow);
                        for (int i = 0; i < Math.min(12, arr.length()); i++) {
                            final String kw = arr.optString(i);
                            if (kw.isEmpty()) continue;
                            TextView chip = a.text(kw, 12, 0xffe6edf9);
                            chip.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
                            chip.setBackground(
                                    glassBg(
                                            0xcc172842,
                                            0xcc182b4c,
                                            a.dp(999),
                                            a.dp(0.7f),
                                            0x38ffffff));
                            MobileDetailScreen.bindTouchFeedback(chip);
                            chip.setOnClickListener(
                                    v -> {
                                        input.setText(kw);
                                        input.setSelection(kw.length());
                                        triggerSearch[0].run();
                                    });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                            lp.rightMargin = a.dp(8);
                            chipRow.addView(chip, lp);
                        }
                        historyWrap.addView(chipScroll);
                    } catch (Exception ignored) {
                    }
                };

        triggerSearch[0] =
                () -> {
                    String kw = input.getText().toString().trim();
                    if (kw.isEmpty()) {
                        a.toast("请输入搜索关键词");
                        return;
                    }
                    if (a.config == null || a.config.sites.isEmpty()) {
                        showMobileImportDialog(a);
                        return;
                    }
                    // Hide keyboard
                    try {
                        InputMethodManager imm =
                                (InputMethodManager)
                                        a.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    } catch (Exception ignored) {
                    }

                    // Private queries never enter history or the shared phonetic dictionary.
                    if (!a.privateMode)
                        try {
                            JSONArray old =
                                    new JSONArray(
                                            a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                            JSONArray updated = new JSONArray();
                            updated.put(kw);
                            for (int i = 0; i < old.length() && updated.length() < 16; i++) {
                                String item = old.optString(i);
                                if (!item.isEmpty() && !item.equals(kw)) updated.put(item);
                            }
                            a.prefs
                                    .edit()
                                    .putString(Prefs.GLOBAL_SEARCH_HISTORY, updated.toString())
                                    .apply();
                        } catch (Exception ignored) {
                        }
                    renderHistoryChips.run();

                    final int searchTicket = ++searchSerial[0];
                    a.cancelPageRequests();
                    searchAdapter.clear();
                    sourceNotices.setText("");
                    sourceNotices.setVisibility(View.GONE);
                    cachedSearchKeyword = kw;
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;

                    List<Cms.Site> searchableSites = new ArrayList<>();
                    for (Cms.Site s : SourcePreferences.prioritized(a, a.config.sites)) {
                        if (a.available(s) && s.searchable) searchableSites.add(s);
                    }
                    if (searchableSites.isEmpty()) {
                        cachedSearchStatus = "当前没有可搜索的片源";
                        searchStatus.setText(cachedSearchStatus);
                        return;
                    }
                    cachedSearchStatus = "正在并发搜索 " + searchableSites.size() + " 个片源…";
                    searchStatus.setText(cachedSearchStatus);
                    final int ticket = a.generation;
                    final int[] completed = {0};
                    final int[] hitCount = {0};
                    final List<String> searchNotices = new ArrayList<>();

                    for (final Cms.Site site : searchableSites) {
                        Future<?> f =
                                RequestScope.submit(
                                        a.worker,
                                        () -> {
                                            List<Cms.Video> matches = new ArrayList<>();
                                            String notice = "";
                                            try {
                                                Cms.Listing l =
                                                        Cms.listing(a.engine(site).search(kw, 1));
                                                for (Cms.Video v : l.videos) {
                                                    if (v.action == null || v.action.isEmpty()) {
                                                        matches.add(v);
                                                    }
                                                }
                                            } catch (
                                                    com.pilipala.catpaw.CatPawHost
                                                                    .DrpySourceException
                                                            error) {
                                                notice =
                                                        "drpy_verification_required"
                                                                        .equals(error.code)
                                                                ? "需要完成网站验证码，暂时无法搜索"
                                                                : "暂时无法读取，请稍后重试";
                                            } catch (Exception ignored) {
                                                notice = "暂时无法读取，请稍后重试";
                                            }
                                            final String sourceNotice = notice;
                                            a.ui.post(
                                                    () -> {
                                                        if (ticket != a.generation
                                                                || searchTicket != searchSerial[0]
                                                                || a.isFinishing()) return;
                                                        completed[0]++;
                                                        if (!sourceNotice.isEmpty())
                                                            searchNotices.add(
                                                                    site.name + "：" + sourceNotice);
                                                        for (final Cms.Video v : matches) {
                                                            hitCount[0]++;
                                                            if (kw.equals(cachedSearchKeyword)) {
                                                                cachedSearchHits.add(
                                                                        new CachedSearchHit(
                                                                                site, v));
                                                            }
                                                            searchAdapter.add(
                                                                    new CachedSearchHit(
                                                                            site, v));
                                                        }
                                                        if (completed[0] < searchableSites.size()) {
                                                            cachedSearchStatus =
                                                                    "已找到 "
                                                                            + hitCount[0]
                                                                            + " 条结果（已完成 "
                                                                            + completed[0]
                                                                            + "/"
                                                                            + searchableSites.size()
                                                                            + " 个片源）";
                                                        } else {
                                                            cachedSearchStatus =
                                                                    hitCount[0] == 0
                                                                            ? (searchNotices
                                                                                            .isEmpty()
                                                                                    ? "未找到与“" + kw
                                                                                            + "”相关的影片"
                                                                                    : "本次搜索未返回影片，请查看片源提示")
                                                                            : "搜索完成，共找到 "
                                                                                    + hitCount[0]
                                                                                    + " 条结果（点击任意线路进入，返回可继续切换）";
                                                        }
                                                        if (!searchNotices.isEmpty())
                                                            cachedSearchStatus +=
                                                                    "\n"
                                                                            + TextUtils.join(
                                                                                    "\n",
                                                                                    searchNotices);
                                                        renderSearchStatus.run();
                                                    });
                                        });
                        a.pageRequests.add(f);
                    }
                };

        searchBtn.setOnClickListener(v -> triggerSearch[0].run());
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null
                                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        triggerSearch[0].run();
                        return true;
                    }
                    return false;
                });

        renderHistoryChips.run();
        if (!pendingSearchKeyword.isEmpty()) {
            String wantedKw = pendingSearchKeyword;
            pendingSearchKeyword = "";
            input.setText(wantedKw);
            input.setSelection(wantedKw.length());
            if (wantedKw.equals(cachedSearchKeyword) && !cachedSearchHits.isEmpty()) {
                searchAdapter.setHits(cachedSearchHits);
                if (cachedSearchStatus.isEmpty())
                    cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
                renderSearchStatus.run();
                final int savedScroll = cachedSearchScrollY;
                if (savedScroll > 0) {
                    resultScroll.post(
                            () -> searchLayoutManager.scrollToPositionWithOffset(0, -savedScroll));
                }
            } else {
                triggerSearch[0].run();
            }
        } else if (!cachedSearchKeyword.isEmpty()) {
            input.setText(cachedSearchKeyword);
            input.setSelection(cachedSearchKeyword.length());
            searchAdapter.setHits(cachedSearchHits);
            if (cachedSearchStatus.isEmpty())
                cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
            renderSearchStatus.run();
            final int savedScroll = cachedSearchScrollY;
            if (savedScroll > 0) {
                resultScroll.post(
                        () -> searchLayoutManager.scrollToPositionWithOffset(0, -savedScroll));
            }
        }
    }

    /** 搜索页结果列表适配器：第 0 项是来源提示，其余是结果行，按需构建视图。 */
    private final class SearchResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final TextView notices;
        private final List<CachedSearchHit> hits = new ArrayList<>();

        SearchResultsAdapter(TextView notices) {
            this.notices = notices;
            if (notices.getParent() instanceof ViewGroup)
                ((ViewGroup) notices.getParent()).removeView(notices);
        }

        void clear() {
            hits.clear();
            notifyDataSetChanged();
        }

        void setHits(List<CachedSearchHit> source) {
            hits.clear();
            hits.addAll(source);
            notifyDataSetChanged();
        }

        void add(CachedSearchHit hit) {
            hits.add(hit);
            notifyItemInserted(hits.size());
        }

        @Override
        public int getItemCount() {
            return 1 + hits.size();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 0) {
                FrameLayout head = new FrameLayout(a);
                head.setPadding(0, 0, 0, a.dp(6));
                return new RowHolder(head);
            }
            FrameLayout cell = new FrameLayout(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            cell.setLayoutParams(lp);
            return new RowHolder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            FrameLayout cell = (FrameLayout) holder.itemView;
            cell.removeAllViews();
            if (position == 0) {
                if (notices.getParent() instanceof ViewGroup)
                    ((ViewGroup) notices.getParent()).removeView(notices);
                cell.addView(notices, new FrameLayout.LayoutParams(-1, -2));
                return;
            }
            CachedSearchHit hit = hits.get(position - 1);
            cell.addView(
                    buildSearchResultRow(hit.site, hit.video),
                    new FrameLayout.LayoutParams(-1, -2));
        }
    }

    private View buildSearchResultRow(final Cms.Site site, final Cms.Video v) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
        row.setBackground(glassBg(0xcc1e3557, 0xcc17294a, a.dp(16), a.dp(0.7f), 0x33ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                view -> {
                    activeTab = 1;
                    a.openListing(site, v);
                });

        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff1b2e48, 10));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(60), a.dp(86));
        posterLp.rightMargin = a.dp(12);
        row.addView(poster, posterLp);
        if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, poster);

        LinearLayout info = a.vertical();
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = a.text(v.title, 15, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        TextView badge = a.text("来源：" + site.name, 12, 0xffff8fb1);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(0, a.dp(4), 0, 0);
        info.addView(badge);

        String sub =
                ((v.year == null ? "" : v.year)
                                + " "
                                + (v.area == null ? "" : v.area)
                                + " "
                                + (v.remarks == null ? "" : v.remarks))
                        .trim();
        if (!sub.isEmpty()) {
            TextView subView = a.text(sub, 12, MainActivity.MUTED);
            subView.setPadding(0, a.dp(4), 0, 0);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(subView);
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = a.dp(10);
        row.setLayoutParams(rowLp);
        return row;
    }

    // =========================================================================
    // TAB 2: MOBILE LIVE TV (Two-Tier Group Pills + Multi-Column Card Grid)
    // =========================================================================
    private void renderLiveTab() {
        LinearLayout page = a.vertical();
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        final List<LiveCatalog.Source> sources = LiveController.sourcesFor(a);
        if (sources.isEmpty()) {
            page.setPadding(a.dp(24), statusBarHeight() + a.dp(24), a.dp(24), 0);
            renderCenteredHint(page, "当前未配置直播源（支持 M3U / TXT / JSON）");
            addMobileActionBtn(
                    page,
                    "导入电视直播源 (M3U / TXT / JSON)",
                    0xffff8fb1,
                    Color.WHITE,
                    () -> SourceController.showLiveImportDialog(a));
            return;
        }

        // Row 1: Top Header with Live Source Selector Pills + Quick Channel Filter
        LinearLayout topHeader = a.vertical();
        topHeader.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), a.dp(8));
        page.addView(topHeader, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleSearchRow = a.row();
        titleSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = a.text("电视直播", 22, Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        titleSearchRow.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

        final EditText searchInput = new EditText(a);
        searchInput.setHint("搜索频道…");
        searchInput.setHintTextColor(0xff8496b2);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(12.5f);
        searchInput.setSingleLine(true);
        searchInput.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
        searchInput.setBackground(
                glassBg(0xd41a2c46, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));
        titleSearchRow.addView(searchInput, new LinearLayout.LayoutParams(a.dp(168), -2));
        topHeader.addView(titleSearchRow, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView srcScroll = new HorizontalScrollView(a);
        srcScroll.setHorizontalScrollBarEnabled(false);
        srcScroll.setPadding(0, a.dp(10), 0, a.dp(2));
        final LinearLayout srcRow = a.row();
        srcScroll.addView(srcRow);
        topHeader.addView(srcScroll, new LinearLayout.LayoutParams(-1, -2));

        // Row 2: Horizontal Channel Group Selector Pills
        HorizontalScrollView groupScroll = new HorizontalScrollView(a);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setPadding(0, a.dp(6), 0, a.dp(2));
        final LinearLayout groupRow = a.row();
        groupScroll.addView(groupRow);
        topHeader.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));

        final TextView stateView = a.text("正在加载直播频道…", 13, MainActivity.MUTED);
        stateView.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        page.addView(stateView);

        // Main Multi-Column Channel Card Grid Area
        ScrollView channelScroll = new ScrollView(a);
        channelScroll.setVerticalScrollBarEnabled(false);
        channelScroll.setClipToPadding(false);
        final LinearLayout channelGridCol = a.vertical();
        channelGridCol.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(106));
        channelScroll.addView(channelGridCol);
        page.addView(channelScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final int[] activeSrcIdx = {0};
        final int[] activeGroupIdx = {0};
        final LiveCatalog.Catalog[] activeCatalog = {null};
        final Runnable[] loadSource = new Runnable[1];
        final int[] liveRequest = {0};

        searchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (activeCatalog[0] != null) {
                            renderLiveGroupsAndChannels(
                                    sources.get(activeSrcIdx[0]),
                                    activeCatalog[0],
                                    activeGroupIdx,
                                    s.toString().trim(),
                                    groupRow,
                                    channelGridCol);
                        }
                    }
                });

        loadSource[0] =
                () -> {
                    final int request = ++liveRequest[0];
                    a.cancelPageRequests();
                    activeCatalog[0] = null;
                    srcRow.removeAllViews();
                    for (int i = 0; i < sources.size(); i++) {
                        final int idx = i;
                        boolean active = i == activeSrcIdx[0];
                        TextView chip =
                                a.text(sources.get(i).name, 12, active ? Color.WHITE : 0xffd2dced);
                        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                        chip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                        chip.setBackground(
                                active
                                        ? a.bg(0xffff8fb1, 999)
                                        : glassBg(
                                                0xcc172842,
                                                0xcc182b4c,
                                                a.dp(999),
                                                a.dp(0.7f),
                                                0x38ffffff));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    activeSrcIdx[0] = idx;
                                    activeGroupIdx[0] = 0;
                                    loadSource[0].run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(8);
                        srcRow.addView(chip, lp);
                    }
                    TextView importLiveChip = a.text("＋ 导入直播", 12, 0xff5ac8fa);
                    importLiveChip.setTypeface(null, Typeface.BOLD);
                    importLiveChip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                    importLiveChip.setBackground(
                            glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
                    MobileDetailScreen.bindTouchFeedback(importLiveChip);
                    importLiveChip.setOnClickListener(
                            v -> SourceController.showLiveImportDialog(a));
                    srcRow.addView(importLiveChip, new LinearLayout.LayoutParams(-2, -2));

                    groupRow.removeAllViews();
                    channelGridCol.removeAllViews();
                    stateView.setVisibility(View.VISIBLE);
                    stateView.setText("正在加载频道列表…");
                    final LiveCatalog.Source selectedSource = sources.get(activeSrcIdx[0]);
                    final int ticket = a.generation;
                    a.submitPage(
                            () -> {
                                try {
                                    final LiveCatalog.Catalog catalog =
                                            LiveController.loadCatalog(a, selectedSource);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
                                                activeCatalog[0] = catalog;
                                                a.liveSession =
                                                        new LiveSession(selectedSource, catalog);
                                                stateView.setVisibility(View.GONE);
                                                renderLiveGroupsAndChannels(
                                                        selectedSource,
                                                        catalog,
                                                        activeGroupIdx,
                                                        searchInput.getText().toString().trim(),
                                                        groupRow,
                                                        channelGridCol);
                                            });
                                } catch (Exception e) {
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
                                                stateView.setVisibility(View.VISIBLE);
                                                stateView.setText("读取直播源失败：" + a.message(e));
                                            });
                                }
                            });
                };

        loadSource[0].run();
    }

    private void renderLiveGroupsAndChannels(
            final LiveCatalog.Source source,
            final LiveCatalog.Catalog catalog,
            final int[] selectedGroupHolder,
            final String searchQuery,
            final LinearLayout groupRow,
            final LinearLayout channelGridCol) {
        groupRow.removeAllViews();
        channelGridCol.removeAllViews();
        if (catalog.groups.isEmpty()) return;

        final int selectedGroupIdx = selectedGroupHolder[0];
        final String qLower = searchQuery == null ? "" : searchQuery.toLowerCase();

        // Pill 0: All Channels
        boolean allActive = qLower.isEmpty() && selectedGroupIdx == -1;
        TextView allBtn =
                a.text(
                        "全部 (" + catalog.channelCount() + ")",
                        12,
                        allActive ? 0xff000000 : 0xffe6edf9);
        allBtn.setTypeface(null, allActive ? Typeface.BOLD : Typeface.NORMAL);
        allBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
        allBtn.setBackground(
                allActive
                        ? glassBg(0xffffffff, 0xffe9eef9, a.dp(999), 0, 0)
                        : glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
        MobileDetailScreen.bindTouchFeedback(allBtn);
        allBtn.setOnClickListener(
                v -> {
                    selectedGroupHolder[0] = -1;
                    renderLiveGroupsAndChannels(
                            source, catalog, selectedGroupHolder, "", groupRow, channelGridCol);
                });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
        allLp.rightMargin = a.dp(7);
        groupRow.addView(allBtn, allLp);

        for (int i = 0; i < catalog.groups.size(); i++) {
            final int gIdx = i;
            boolean active = qLower.isEmpty() && i == selectedGroupIdx;
            LiveCatalog.Group g = catalog.groups.get(i);
            TextView gBtn =
                    a.text(
                            g.name + " (" + g.channels.size() + ")",
                            12,
                            active ? 0xff000000 : 0xffe6edf9);
            gBtn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            gBtn.setSingleLine(true);
            gBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
            gBtn.setBackground(
                    active
                            ? glassBg(0xffffffff, 0xffe9eef9, a.dp(999), 0, 0)
                            : glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(gBtn);
            gBtn.setOnClickListener(
                    v -> {
                        selectedGroupHolder[0] = gIdx;
                        renderLiveGroupsAndChannels(
                                source, catalog, selectedGroupHolder, "", groupRow, channelGridCol);
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = a.dp(7);
            groupRow.addView(gBtn, lp);
        }

        // Collect channels to display
        List<LiveCatalog.Channel> displayList = new ArrayList<>();
        List<String> displayGroups = new ArrayList<>();
        if (!qLower.isEmpty()) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    if (ch.name.toLowerCase().contains(qLower)
                            || g.name.toLowerCase().contains(qLower)) {
                        displayList.add(ch);
                        displayGroups.add(g.name);
                    }
                }
            }
        } else if (selectedGroupIdx == -1) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    displayList.add(ch);
                    displayGroups.add(g.name);
                }
            }
        } else {
            int safeIdx = Math.max(0, Math.min(selectedGroupIdx, catalog.groups.size() - 1));
            LiveCatalog.Group g = catalog.groups.get(safeIdx);
            for (LiveCatalog.Channel ch : g.channels) {
                displayList.add(ch);
                displayGroups.add(g.name);
            }
        }

        if (displayList.isEmpty()) {
            TextView empty = a.text("未找到匹配的直播频道", 13, MainActivity.MUTED);
            empty.setPadding(a.dp(8), a.dp(24), a.dp(8), a.dp(24));
            channelGridCol.addView(empty);
            return;
        }

        int swDp = a.getResources().getConfiguration().screenWidthDp;
        final int cols = swDp >= 880 ? 4 : (swDp >= 580 ? 3 : 2);
        LinearLayout currentRow = null;

        for (int i = 0; i < displayList.size(); i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = a.dp(10);
                channelGridCol.addView(currentRow, rowLp);
            }
            final LiveCatalog.Channel ch = displayList.get(i);
            final String groupName = displayGroups.get(i);
            LinearLayout card = buildMobileLiveChannelCard(source, ch, groupName);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % cols != cols - 1) {
                cardLp.rightMargin = a.dp(10);
            }
            currentRow.addView(card, cardLp);
        }
        int remainder = displayList.size() % cols;
        if (remainder != 0 && currentRow != null) {
            for (int pad = remainder; pad < cols; pad++) {
                View spacer = new View(a);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                if (pad != cols - 1) {
                    spLp.rightMargin = a.dp(10);
                }
                currentRow.addView(spacer, spLp);
            }
        }
    }

    private LinearLayout buildMobileLiveChannelCard(
            final LiveCatalog.Source source, final LiveCatalog.Channel ch, final String groupName) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        card.setBackground(glassBg(0xb8162740, 0xb8141a18, a.dp(16), a.dp(0.8f), 0x33ffffff));
        MobileDetailScreen.bindTouchFeedback(card);

        // Left: Smart Callsign Badge
        TextView badge = buildSmartChannelBadgeView(a, ch.name);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
        badgeLp.rightMargin = a.dp(9);
        card.addView(badge, badgeLp);

        // Middle: Two-Line Info
        LinearLayout infoCol = a.vertical();
        TextView name = a.text(ch.name, 13, MainActivity.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(name);

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(3), 0, 0);
        TextView sub =
                a.text(
                        groupName
                                + " · "
                                + (ch.streams.size() > 1 ? ch.streams.size() + "源" : "直连"),
                        11,
                        0xff9fb0c9);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(sub, new LinearLayout.LayoutParams(0, -2, 1f));
        infoCol.addView(subRow);

        card.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(
                v -> {
                    if (ch.streams.isEmpty()) return;
                    a.playLive(source, ch, ch.streams.get(0));
                });
        if (ch.streams.size() > 1) {
            card.setOnLongClickListener(
                    v -> {
                        String[] names = new String[ch.streams.size()];
                        for (int n = 0; n < names.length; n++) names[n] = "线路 " + (n + 1);
                        new AppDialog.Builder(a)
                                .setTitle(ch.name + " · 选择线路")
                                .setItems(
                                        names,
                                        (dialog, n) -> a.playLive(source, ch, ch.streams.get(n)))
                                .show();
                        return true;
                    });
        }
        return card;
    }

    static TextView buildSmartChannelBadgeView(MainActivity a, String rawName) {
        String name = rawName == null ? "TV" : rawName.trim();
        String upper = name.toUpperCase();
        int bgColor = 0xff2563eb;
        String label;
        if (upper.startsWith("CCTV")) {
            bgColor = 0xffe11d48;
            String num = upper.replaceFirst("^CCTV[-\\s]*", "").replaceAll("[^0-9A-Z+]", "");
            if (num.length() > 3) num = num.substring(0, 3);
            label = num.isEmpty() ? "CCTV" : "CC\n" + num;
        } else if (name.contains("卫视")) {
            bgColor = 0xffd97706;
            String prefix = name.replaceAll("卫视.*$", "").trim();
            label = prefix.length() >= 2 ? prefix.substring(0, 2) : "卫视";
        } else if (name.contains("翡翠")
                || name.contains("明珠")
                || name.contains("凤凰")
                || upper.contains("TVB")) {
            bgColor = 0xff059669;
            label = name.contains("凤凰") ? "凤凰" : "TVB";
        } else if (upper.contains("4K") || upper.contains("8K")) {
            bgColor = 0xff7c3aed;
            label = upper.contains("8K") ? "8K" : "4K";
        } else {
            String clean = name.replaceAll("[\\[\\(（【].*?[\\]\\)）】]", "").trim();
            label = clean.length() >= 2 ? clean.substring(0, 2) : (clean.isEmpty() ? "TV" : clean);
        }
        TextView tv = a.text(label, 11, Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(a.bg(bgColor, 10));
        return tv;
    }

    // =========================================================================
    // TAB 3: LIBRARY ("片库": Watch History, Favorites & Downloads)
    // =========================================================================
    private void renderMyTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView pageTitle = a.text("片库", 22, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        pageTitle.setPadding(0, 0, 0, a.dp(10));
        page.addView(pageTitle);

        final boolean[] showFavorites = {mySubTab == 1};
        final boolean[] showDownloads = {mySubTab == 2};
        final LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView tabHist = a.text("观看历史", 13.5f, Color.WHITE);
        final TextView tabFav = a.text("我的收藏", 13.5f, MainActivity.MUTED);
        final TextView tabDown = a.text("我的下载", 13.5f, MainActivity.MUTED);
        tabDown.setTag("my-downloads");
        final TextView clearBtn = a.text("清空历史", 12, 0xffef4444);

        HorizontalScrollView tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = a.row();
        tabScroll.addView(tabRow);
        tabRow.addView(tabHist);
        tabRow.addView(new View(a), new LinearLayout.LayoutParams(a.dp(8), 1));
        tabRow.addView(tabFav);
        tabRow.addView(new View(a), new LinearLayout.LayoutParams(a.dp(8), 1));
        tabRow.addView(tabDown);
        headerRow.addView(tabScroll, new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(clearBtn);
        page.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(page, 12);

        // RecyclerView：长列表虚拟化（观看历史 / 我的收藏）
        final FrameLayout listHost = new FrameLayout(a);
        page.addView(listHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        final MyListAdapter[] adapterRef = {null};
        final Runnable renderSubTab =
                new Runnable() {
                    @Override
                    public void run() {
                        boolean favMode = showFavorites[0];
                        boolean downMode = showDownloads[0];
                        boolean histMode = !favMode && !downMode;
                        mySubTab = downMode ? 2 : favMode ? 1 : 0;
                        styleLibraryPill(tabHist, histMode);
                        styleLibraryPill(tabFav, favMode);
                        styleLibraryPill(tabDown, downMode);
                        clearBtn.setVisibility(histMode ? View.VISIBLE : View.GONE);
                        listHost.removeAllViews();

                        if (downMode) {
                            ScrollView sv = new ScrollView(a);
                            sv.setFillViewport(true);
                            sv.setVerticalScrollBarEnabled(false);
                            sv.setClipToPadding(false);
                            LinearLayout body = a.vertical();
                            body.setPadding(0, 0, 0, a.dp(106));
                            sv.addView(body, new ScrollView.LayoutParams(-1, -2));
                            MobileDownloads.render(a, body);
                            listHost.addView(sv, new FrameLayout.LayoutParams(-1, -1));
                            return;
                        }

                        RecyclerView rv = new RecyclerView(a);
                        rv.setLayoutManager(new LinearLayoutManager(a));
                        rv.setClipToPadding(false);
                        rv.setPadding(0, 0, 0, a.dp(106));
                        final MyListAdapter adapter = new MyListAdapter(favMode);
                        rv.setAdapter(adapter);
                        adapterRef[0] = adapter;
                        listHost.addView(rv, new FrameLayout.LayoutParams(-1, -1));
                        adapter.reload();
                    }
                };

        MobileDetailScreen.bindTouchFeedback(tabHist);
        MobileDetailScreen.bindTouchFeedback(tabFav);
        MobileDetailScreen.bindTouchFeedback(tabDown);
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        tabHist.setOnClickListener(
                v -> {
                    showFavorites[0] = false;
                    showDownloads[0] = false;
                    renderSubTab.run();
                });
        tabFav.setOnClickListener(
                v -> {
                    showFavorites[0] = true;
                    showDownloads[0] = false;
                    renderSubTab.run();
                });
        tabDown.setOnClickListener(
                v -> {
                    showFavorites[0] = false;
                    showDownloads[0] = true;
                    renderSubTab.run();
                });
        clearBtn.setOnClickListener(
                v -> {
                    a.prefs.edit().putString(Prefs.WATCH_HISTORY, "[]").apply();
                    renderSubTab.run();
                });

        renderSubTab.run();
    }

    /** 片库列表适配器：观看历史 / 我的收藏，使用 RecyclerView 做虚拟化。 */
    private final class MyListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final boolean favoritesMode;
        private List<WatchHistory.Entry> history = new ArrayList<>();
        private List<Favorites.Entry> favorites = new ArrayList<>();
        private boolean empty;

        MyListAdapter(boolean favoritesMode) {
            this.favoritesMode = favoritesMode;
        }

        void reload() {
            if (favoritesMode) {
                favorites = Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
                empty = favorites.isEmpty();
            } else {
                history = a.watchHistory();
                empty = history.isEmpty();
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            if (empty) return 1;
            return favoritesMode ? favorites.size() : history.size();
        }

        @Override
        public int getItemViewType(int position) {
            return empty ? 2 : (favoritesMode ? 1 : 0);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 2) {
                LinearLayout box = a.vertical();
                box.setGravity(Gravity.CENTER);
                box.setPadding(a.dp(16), a.dp(48), a.dp(16), a.dp(48));
                TextView hint =
                        a.text(
                                favoritesMode ? "还没有收藏任何影片" : "暂无观看历史记录",
                                14,
                                MainActivity.MUTED);
                hint.setGravity(Gravity.CENTER);
                box.addView(hint);
                return new RowHolder(box);
            }
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
            row.setBackground(
                    glassBg(0xcc1e3557, 0xcc17294a, a.dp(16), a.dp(0.7f), 0x33ffffff));
            MobileDetailScreen.bindTouchFeedback(row);

            ImageView thumb = new ImageView(a);
            thumb.setTag("row-thumb");
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(a.bg(0xff1b2e48, 10));
            thumb.setClipToOutline(true);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(a.dp(52), a.dp(74));
            thumbLp.rightMargin = a.dp(12);
            row.addView(thumb, thumbLp);

            LinearLayout info = a.vertical();
            TextView title = a.text("", 15, MainActivity.WHITE);
            title.setTag("row-title");
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title);
            TextView sub = a.text("", 12, MainActivity.MUTED);
            sub.setTag("row-sub");
            sub.setPadding(0, a.dp(5), 0, 0);
            info.addView(sub);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            TextView action = a.text("", 12, 0xffef4444);
            action.setTag("row-action");
            action.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            MobileDetailScreen.bindTouchFeedback(action);
            row.addView(action);
            return new RowHolder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (empty) return;
            LinearLayout row = (LinearLayout) holder.itemView;
            ImageView thumb = row.findViewWithTag("row-thumb");
            TextView title = row.findViewWithTag("row-title");
            TextView sub = row.findViewWithTag("row-sub");
            TextView action = row.findViewWithTag("row-action");

            if (favoritesMode) {
                final Favorites.Entry f = favorites.get(position);
                title.setText(f.title);
                sub.setText("已收藏 · 点击查看详情");
                action.setText("详情");
                action.setTextColor(0xffc9d4e6);
                if (f.poster != null && !f.poster.isEmpty()) a.poster(f.poster, thumb);
                row.setOnClickListener(
                        v -> {
                            Cms.Site target = null;
                            if (a.config != null)
                                for (Cms.Site site : a.config.sites)
                                    if (site.key.equals(f.site)) {
                                        target = site;
                                        break;
                                    }
                            if (target == null) target = currentSite;
                            if (target == null) {
                                a.toast("该收藏所属的片源不可用");
                                return;
                            }
                            a.openListing(target, f.video());
                        });
                action.setOnClickListener(v -> row.performClick());
            } else {
                final WatchHistory.Entry e = history.get(position);
                title.setText(e.title);
                sub.setText(a.historyLabel(e));
                action.setText("删除");
                action.setTextColor(0xffef4444);
                if (e.poster != null && !e.poster.isEmpty()) a.poster(e.poster, thumb);
                row.setOnClickListener(v -> a.resumeHistory(e));
                action.setOnClickListener(
                        v -> {
                            a.prefs
                                    .edit()
                                    .putString(
                                            Prefs.WATCH_HISTORY,
                                            WatchHistory.remove(
                                                    a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                                    e.key()))
                                    .apply();
                            reload();
                        });
            }
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        RowHolder(View itemView) {
            super(itemView);
        }
    }

    private void styleLibraryPill(TextView pill, boolean active) {
        pill.setTextColor(active ? 0xff000000 : 0xffe6edf9);
        pill.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        pill.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
        if (active) {
            pill.setBackground(glassBg(0xffffffff, 0xffe9eef9, a.dp(999), 0, 0));
        } else {
            pill.setBackground(glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
        }
    }

    // =========================================================================
    // TAB 4: MOBILE SETTINGS (2-Level Clean Architecture, Zero Emojis, Zero Ghosting)
    // =========================================================================
    private void renderSettingsTab() {
        contentContainer.removeAllViews();

        final ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout body = a.vertical();
        body.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), a.dp(108));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));

        populateSettingsBody(body, sv);
    }

    private void populateSettingsBody(final LinearLayout body, final ScrollView sv) {
        final int savedScrollY = sv.getScrollY();
        body.removeAllViews();

        final Runnable refreshInPlace =
                () -> {
                    populateSettingsBody(body, sv);
                    body.invalidate();
                    sv.invalidate();
                    if (contentContainer != null) contentContainer.invalidate();
                    if (a.root != null) a.root.invalidate();
                };

        if (settingsSubPage != 0) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(14));

            TextView backBtn = a.text("‹", 22, Color.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setBackground(
                    glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(
                    v -> {
                        settingsSubPage = 0;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(10);
            topNav.addView(backBtn, backLp);

            String subTitleText =
                    settingsSubPage == 1
                            ? "片源与直播配置"
                            : settingsSubPage == 2
                                    ? "播放、解码与字幕"
                                    : settingsSubPage == 3
                                            ? "界面外观与偏好"
                                            : settingsSubPage == 5
                                                    ? "首页设置"
                                                    : settingsSubPage == 6
                                                            ? "关于鲸落"
                                                            : settingsSubPage == 7
                                                                    ? "观影统计"
                                                                    : settingsSubPage == 9
                                                                            ? "片源与直播配置教程"
                                                                            : settingsSubPage == 8
                                                                                    ? "遥控器按键说明"
                                                                            : "备份、更新与诊断";
            TextView subTitle = a.text(subTitleText, 20, Color.WHITE);
            subTitle.setTypeface(null, Typeface.BOLD);
            topNav.addView(subTitle, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));

            if (settingsSubPage == 1) {
                addSectionHeader(body, "配置与直播源导入");
                addSettingCard(
                        body,
                        1,
                        "从剪贴板一键粘贴配置",
                        "直接读取手机剪贴板中的 JSON、JS、PY、TXT/M3U 或配置网址",
                        () -> pasteConfigFromClipboard(a));
                addSettingCard(
                        body,
                        1,
                        "输入或修改配置网址与源码",
                        a.configOrigin.isEmpty()
                                ? "支持 JSON / JS / PY / TXT / M3U / ZIP 地址"
                                : a.configOrigin,
                        () -> showMobileImportDialog(a));
                addSettingCard(
                        body,
                        1,
                        "选择本地配置或本地包",
                        "支持导入 .json / .js / .py / .txt / .m3u / .zip 本地文件",
                        a::pickConfig);
                String savedLiveUrl = a.prefs.getString(Prefs.LIVE_URL, "");
                addSettingCard(
                        body,
                        1,
                        "导入或管理电视直播源",
                        savedLiveUrl.isEmpty()
                                ? "支持 M3U、TXT (#genre#)、JSON 及本地直播文件"
                                : "当前直播源：" + savedLiveUrl,
                        () -> SourceController.showLiveImportDialog(a));

                a.gap(body, 12);
                addSectionHeader(body, "片源选择与扩展引擎");
                addSettingCard(
                        body,
                        1,
                        "切换默认片源",
                        currentSite == null ? "未选择" : "当前：" + currentSite.name,
                        a::chooseSources);
                final boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true);
                addSettingCard(
                        body,
                        1,
                        "启用第三方扩展片源 (JAR / JS / PY)",
                        cspEnabled ? "当前状态：已开启" : "当前状态：已关闭（点击开启）",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.EXPERIMENTAL_CSP, !cspEnabled).apply();
                            a.closeEngines();
                            refreshInPlace.run();
                            a.toast(!cspEnabled ? "已开启扩展片源支持" : "已关闭扩展片源支持");
                        });
                a.gap(body, 12);
                addSectionHeader(body, "新手教程");
                addSettingCard(
                        body,
                        1,
                        "片源与直播配置：傻瓜式教程",
                        "用内置 Markdown 阅读器打开完整教程（配置示例 / 直播源 / 导入步骤 / 常见问题）",
                        a::openSourceGuide);
                addSettingCard(
                        body,
                        1,
                        "预览当前片源配置",
                        "把当前配置原文用 Markdown 阅读器打开，方便核对字段",
                        a::previewCurrentConfig);
            } else if (settingsSubPage == 2) {
                addSectionHeader(body, "播放器与解码器");
                final int engine = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                addSettingCard(
                        body,
                        3,
                        "播放器内核",
                        engine == 0 ? "Media3（自带软解与字幕增强）" : "Android 系统播放器",
                        () -> {
                            int next = engine == 0 ? 1 : 0;
                            a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, next).apply();
                            refreshInPlace.run();
                        });
                final int decoder = a.prefs.getInt(Prefs.DECODER_MODE, 0);
                final String[] decNames = {"自动选择（推荐）", "硬件解码优先", "软件解码优先"};
                addSettingCard(
                        body,
                        3,
                        "视频解码方式",
                        decNames[Math.max(0, Math.min(2, decoder))],
                        () -> {
                            int next = (decoder + 1) % 3;
                            a.prefs.edit().putInt(Prefs.DECODER_MODE, next).apply();
                            refreshInPlace.run();
                        });
                final boolean tunnel = a.prefs.getBoolean(Prefs.TUNNELED_PLAYBACK, false);
                addSettingCard(
                        body,
                        3,
                        "4K HDR 隧道模式",
                        tunnel ? "已开启（部分设备可降低高码率掉帧）" : "已关闭（兼容模式）",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.TUNNELED_PLAYBACK, !tunnel).apply();
                            refreshInPlace.run();
                        });
                final boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
                addSettingCard(
                        body,
                        3,
                        "自动连播下一集",
                        autoNext ? "已开启" : "已关闭",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, !autoNext).apply();
                            refreshInPlace.run();
                        });

                a.gap(body, 12);
                addSectionHeader(body, "片头片尾与字幕样式");
                int introSec = a.prefs.getInt(Prefs.SKIP_INTRO_SECONDS, 0);
                int outroSec = a.prefs.getInt(Prefs.SKIP_OUTRO_SECONDS, 0);
                String skipDesc =
                        (introSec > 0
                                        ? "片头 " + PlaybackController.formatTime(introSec * 1000)
                                        : "片头关闭")
                                + " · "
                                + (outroSec > 0
                                        ? "片尾 " + PlaybackController.formatTime(outroSec * 1000)
                                        : "片尾关闭");
                addSettingCard(
                        body,
                        3,
                        "片头片尾跳过时间节点",
                        skipDesc + "（支持手动拖动滑块精准设定）",
                        () ->
                                PlaybackControls.showSkipAdjuster(
                                        a, true, introSec, 0, refreshInPlace));

                int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE_SP, 40));
                int subBg = Prefs.clampSubtitleBgStyle(a.prefs.getInt(Prefs.SUBTITLE_BG_STYLE, 0));
                addSettingCard(
                        body,
                        2,
                        "电视台画面比例",
                        tvAspectLabel(),
                        () -> {
                            int v = a.prefs.getInt(Prefs.TV_ASPECT, 0);
                            a.prefs.edit().putInt(Prefs.TV_ASPECT, (v + 1) % 3).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        0,
                        "遥控器按键说明",
                        "电视端遥控器 / 键盘操作对照表，手机端手势也一并列出",
                        () -> {
                            settingsSubPage = 8;
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        2,
                        "电视台填充模式",
                        tvFillLabel(),
                        () -> {
                            int v = a.prefs.getInt(Prefs.TV_FILL, 0);
                            a.prefs.edit().putInt(Prefs.TV_FILL, (v + 1) % 3).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        3,
                        "字幕大小、位置与样式",
                        "字号 "
                                + subSize
                                + " · "
                                + SubtitleOverlayView.bgStyleLabel(subBg)
                                + "（支持方向键与触控调节）",
                        () -> PlaybackControls.showSubtitleAdjuster(a, null, refreshInPlace));
            } else if (settingsSubPage == 3) {
                addSectionHeader(body, "界面显示与交互");
                addSettingCard(
                        body,
                        4,
                        "屏幕保护",
                        new StringBuilder()
                                .append(a.prefs.getBoolean(Prefs.SCREENSAVER_ENABLED, false) ? "已开启" : "已关闭")
                                .append(" · ")
                                .append(a.prefs.getInt(Prefs.SCREENSAVER_TIMEOUT_MIN, 5))
                                .append(" 分钟 · ")
                                .append(
                                        new String[] {"数字时钟", "极简", "超大字"}[
                                                Math.max(0, Math.min(2, a.prefs.getInt(Prefs.SCREENSAVER_CLOCK_STYLE, 0)))])
                                .toString(),
                        () -> showScreensaverSettings());
                final int scale = a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100);
                addSettingCard(
                        body,
                        4,
                        "界面整体缩放比例",
                        "当前：" + scale + "%（点击切换 90% / 100% / 110%）",
                        () -> {
                            int next = scale == 100 ? 110 : (scale == 110 ? 90 : 100);
                            a.prefs.edit().putInt(Prefs.UI_SCALE_PERCENT, next).apply();
                            a.recreate();
                        });
                final int navScalePct =
                        Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100));
                addSettingCard(
                        body,
                        4,
                        "导航栏字体与按钮大小",
                        "当前：" + navScalePct + "%（自适应排版 · 点击切换紧凑 / 标准 / 放大 / 特大）",
                        () -> {
                            int next =
                                    navScalePct < 100
                                            ? 100
                                            : navScalePct == 100
                                                    ? 115
                                                    : navScalePct == 115
                                                            ? 130
                                                            : navScalePct == 130 ? 140 : 85;
                            a.prefs.edit().putInt(Prefs.NAV_SCALE_PERCENT, next).apply();
                            a.recreate();
                        });
                final boolean reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
                final boolean reducedTransparency =
                        a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false);
                addSettingCard(
                        body,
                        4,
                        "减少透明效果",
                        reducedTransparency ? "已开启（使用实色面板）" : "已关闭（背景玻璃材质）",
                        () -> {
                            a.prefs
                                    .edit()
                                    .putBoolean(Prefs.REDUCED_TRANSPARENCY, !reducedTransparency)
                                    .apply();
                            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root);
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        4,
                        "精简界面动画",
                        reduced ? "已开启（减少过渡动画，提升响应速度）" : "已关闭（保留完整流畅动效）",
                        () -> {
                            a.reduced = !reduced;
                            a.prefs.edit().putBoolean(Prefs.REDUCED, !reduced).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        3,
                        "首页壁纸与动态壁纸",
                        a.prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
                                ? "视频动态壁纸（循环静音）"
                                : a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
                                        ? "自定义图片壁纸"
                                        : "海报配色柔光（默认）",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> new SettingsScreen(a).wallpaper()),
                                        true));
                addSettingCard(
                        body,
                        4,
                        "清空搜索历史记录",
                        "清除搜索页保存的全部历史关键词",
                        () -> {
                            a.prefs.edit().putString(Prefs.GLOBAL_SEARCH_HISTORY, "[]").apply();
                            cachedSearchKeyword = "";
                            cachedSearchStatus = "";
                            cachedSearchHits.clear();
                            a.toast("已清空搜索历史记录");
                        });
            } else if (settingsSubPage == 5) {
                addSectionHeader(body, "首页板块");
                addSettingCard(
                        body,
                        3,
                        "历史记录",
                        a.prefs.getBoolean(Prefs.HOME_SHOW_HISTORY, true)
                                ? "显示在首页顶部，可继续观看"
                                : "已隐藏",
                        () -> {
                            boolean on = !a.prefs.getBoolean(Prefs.HOME_SHOW_HISTORY, true);
                            a.prefs.edit().putBoolean(Prefs.HOME_SHOW_HISTORY, on).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        3,
                        "今日首推",
                        a.prefs.getBoolean(Prefs.HOME_SHOW_HERO, true) ? "首页大卡片推荐" : "已隐藏",
                        () -> {
                            boolean on = !a.prefs.getBoolean(Prefs.HOME_SHOW_HERO, true);
                            a.prefs.edit().putBoolean(Prefs.HOME_SHOW_HERO, on).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        3,
                        "今晚看什么",
                        a.prefs.getBoolean(Prefs.HOME_SHOW_TONIGHT, true) ? "随机三部推荐" : "已隐藏",
                        () -> {
                            boolean on = !a.prefs.getBoolean(Prefs.HOME_SHOW_TONIGHT, true);
                            a.prefs.edit().putBoolean(Prefs.HOME_SHOW_TONIGHT, on).apply();
                            refreshInPlace.run();
                        });
                a.gap(body, 10);
                body.addView(
                        a.text("关闭的板块只在首页隐藏，数据不会删除；随时可以再打开。", 12, 0xff8d9cb6));
            } else if (settingsSubPage == 6) {
                addAboutPage(body);
            } else if (settingsSubPage == 7) {
                addStatsPage(body);
            } else if (settingsSubPage == 8) {
                addKeymapPage(body);
            } else if (settingsSubPage == 9) {
                addSourceGuidePage(body);
            } else {
                addSectionHeader(body, "更新、备份与诊断");
                addSettingCard(
                        body,
                        5,
                        "文件管理（上传 / Markdown 阅读）",
                        "内置文件管理 / Markdown 阅读；右上角可打开「管理员后台」上传文件",
                        () -> FilesScreen.show(a));
                addSettingCard(
                        body,
                        5,
                        "检查软件更新",
                        "当前版本：" + UpdateManager.installedVersion(a) + " · 正式版",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new UpdateScreen(a).show()),
                                        true));
                addSettingCard(
                        body,
                        5,
                        "查看运行诊断日志",
                        "排查片源网络请求、代理与播放解码日志",
                        () -> new SettingsScreen(a).diagnostics());
                addSettingCard(
                        body,
                        5,
                        "完整高级设置与备份恢复",
                        "配置备份导出、恢复及完整系统参数页面",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new SettingsScreen(a).show()),
                                        true));
            }
            if (savedScrollY > 0) {
                sv.scrollTo(0, savedScrollY);
            }
            return;
        }

        // =====================================================================
        // LEVEL 1: CLEAN 5-CARD PRIMARY SETTINGS MENU (Zero Emojis)
        // =====================================================================
        TextView pageTitle = a.text("设置", 24, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        body.addView(pageTitle);

        a.gap(body, 14);
        final String[] categories = {
            "片源与直播配置", "播放、解码与字幕", "界面外观与偏好", "备份、更新与诊断", "首页设置",
            "关于鲸落", "观影统计"
        };
        final int[] icons = {1, 3, 4, 5, 2, 6, 4};
        for (int i = 0; i < categories.length; i++) {
            final int page = i + 1;
            addSettingCard(
                    body,
                    icons[i],
                    categories[i],
                    "",
                    () -> {
                        settingsSubPage = page;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
        }
        addSettingCard(body, 2, "网盘与账号授权", "", () -> AccountPanel.show(a, null));
    }

    /** 设置 → 关于鲸落：版本、开源信息、上游归属与相关操作。 */
    private void addAboutPage(LinearLayout body) {
        LinearLayout brand = a.vertical();
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView logo = new android.widget.ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        brand.addView(logo, new LinearLayout.LayoutParams(a.dp(76), a.dp(76)));
        TextView name = a.text("鲸落", 26, MainActivity.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = a.dp(12);
        brand.addView(name, nameLp);
        TextView sub = a.text("WHALEFALL · 沉入故事深处", 11.5f, 0xffffb3c8);
        sub.setGravity(Gravity.CENTER);
        brand.addView(sub);
        TextView version = a.text("版本 " + UpdateManager.installedVersion(a), 12.5f, 0xff8d9cb6);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(-1, -2);
        verLp.topMargin = a.dp(6);
        brand.addView(version, verLp);
        a.gap(body, 14);
        body.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        addSectionHeader(body, "关于软件");
        addSettingCard(
                body,
                6,
                "检查更新",
                "当前版本：" + UpdateManager.installedVersion(a),
                () -> a.open(new MainActivity.Screen(() -> new UpdateScreen(a).show()), true));
        addSettingCard(
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
        addSettingCard(
                body,
                6,
                "复制项目源码地址",
                "GitHub · 962599627/whalefall",
                () -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                    "whalefall", "https://github.com/962599627/whalefall"));
                    a.toast("源码地址已复制");
                });
        addSettingCard(
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

    /** 首页「继续观看」清理：已完成 / 7 天前 / 全部。 */
    void showHistoryCleanup() {
        String[] options = {"清除已看完的", "清除 7 天前的", "全部清除"};
        new AppDialog.Builder(a)
                .setTitle("清理观看历史")
                .setItems(
                        options,
                        (d, index) -> {
                            java.util.List<WatchHistory.Entry> entries =
                                    new java.util.ArrayList<>(a.watchHistory());
                            StringBuilder raw = new StringBuilder("[");
                            long now = System.currentTimeMillis() / 1000;
                            java.util.List<WatchHistory.Entry> keep = new java.util.ArrayList<>();
                            for (WatchHistory.Entry e : entries) {
                                boolean remove = false;
                                if (index == 0) remove = e.complete();
                                else if (index == 1) remove = now - e.updated > 7 * 24 * 3600L;
                                else remove = true;
                                if (!remove) keep.add(e);
                            }
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (WatchHistory.Entry e : keep)
                                try {
                                    arr.put(e.json());
                                } catch (Exception ignored) {
                                }
                            a.prefs.edit().putString(Prefs.WATCH_HISTORY, arr.toString()).apply();
                            a.toast("已清理 " + (entries.size() - keep.size()) + " 条记录");
                            switchTab(0);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置 → 片源与直播配置教程：面向小白的图文步骤 + 可复制示例。 */
    private void addSourceGuidePage(LinearLayout body) {
        addSectionHeader(body, "1. 片源配置是什么");
        body.addView(
                a.text(
                        "一个 JSON 文件，告诉 App 去哪里找片、怎么搜索。常见 TVBox / CatVod 配置可以直接用。",
                        13,
                        0xffc9d4e6));
        a.gap(body, 10);
        final String jsonExample =
                "{\n"
                        + "  \"sites\": [\n"
                        + "    {\n"
                        + "      \"key\": \"demo\",\n"
                        + "      \"name\": \"示例站点\",\n"
                        + "      \"type\": 1,\n"
                        + "      \"api\": \"https://example.com/api.php/provide/vod/\",\n"
                        + "      \"searchable\": 1\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
        addSettingCard(
                body,
                1,
                "复制最小片源配置示例",
                "含 key / name / type / api / searchable 五个字段，粘贴替换成你自己的接口即可",
                () -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("鲸落片源配置", jsonExample));
                    a.toast("示例已复制到剪贴板");
                });

        addSectionHeader(body, "2. 怎么导入");
        String[] steps = {
            "设置 → 片源与直播配置 → 从剪贴板一键粘贴（复制 JSON 或配置网址）",
            "或用「输入或修改配置网址」填 https://.../config.json",
            "或用「选择本地配置」导入手机里的 .json 文件",
            "导入后在「切换默认片源」里选中它，回到首页刷新即可"
        };
        for (int i = 0; i < steps.length; i++) addGuideStep(body, (i + 1) + "", steps[i]);

        addSectionHeader(body, "3. 直播源格式");
        addGuideStep(body, "M3U", "#EXTM3U + #EXTINF:-1 group-title=\"央视\",CCTV-1 综合 + 地址");
        addGuideStep(body, "TXT", "分组名,#genre#，下一行开始「频道名,播放地址」");
        addGuideStep(body, "JSON", "按导入窗口里的字段说明填写频道分组与地址");
        final String liveExample =
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"央视频道\",CCTV-1 综合\n"
                        + "http://example.com/live/cctv1.m3u8\n"
                        + "#EXTINF:-1 group-title=\"卫视频道\",湖南卫视\n"
                        + "http://example.com/live/hunan.m3u8";
        addSettingCard(
                body,
                1,
                "复制直播源 M3U 示例",
                "两行一个频道：分组标题 + 频道名 + m3u8 地址",
                () -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("鲸落直播源示例", liveExample));
                    a.toast("直播示例已复制到剪贴板");
                });

        addSectionHeader(body, "4. 电视 tab「导入频道列表」");
        String[] liveSteps = {
            "底部「电视」tab → 左下角「导入」",
            "粘贴 JSON 后点「导入」，频道立刻出现",
            "点频道直接播放；长按可收藏",
            "批量维护：设置 → 片源与直播配置 → 导入或管理电视直播源"
        };
        for (int i = 0; i < liveSteps.length; i++) addGuideStep(body, (i + 1) + "", liveSteps[i]);
        final String channelsExample =
                "{\n"
                        + "  \"channels\": [\n"
                        + "    {\"name\": \"CCTV-1 综合\", \"url\": \"https://example.com/live/cctv1.m3u8\"},\n"
                        + "    {\"name\": \"湖南卫视\", \"url\": \"https://example.com/live/hunan.m3u8\"}\n"
                        + "  ]\n"
                        + "}";
        addSettingCard(
                body,
                1,
                "复制「导入频道列表」JSON 示例",
                "电视 tab → 导入 → 粘贴这段 JSON 即可批量添加频道",
                () -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("鲸落频道列表", channelsExample));
                    a.toast("频道列表示例已复制，去电视 tab → 导入 粘贴");
                });

        addSectionHeader(body, "4. 常见问题");
        addGuideStep(body, "没内容", "先确认默认片源已选中；部分站点还需要 ext 参数");
        addGuideStep(body, "需要插件", "JS / PY / JAR 类片源要插件文件，光有 JSON 不行");
        addGuideStep(body, "搜索为空", "检查站点 searchable 是否为 1，以及接口是否支持搜索");
        addGuideStep(body, "直播 403", "源地址过期或限地区，换源即可；App 不代理直播源");

        a.gap(body, 14);
        body.addView(
                a.text(
                        "只用你自己信任、且有权访问的配置与插件。本 App 只是播放器，不内置、不提供任何影视资源。",
                        12,
                        0xff8d9cb6));
    }

    private void addGuideStep(LinearLayout body, String tag, String text) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        row.setBackground(glassBg(0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
        TextView t = a.text(tag, 12.5f, 0xffffb3c8);
        t.setTypeface(null, Typeface.BOLD);
        t.setMinWidth(a.dp(64));
        row.addView(t);
        TextView d = a.text(text, 12.5f, 0xffc9d4e6);
        d.setPadding(a.dp(8), 0, 0, 0);
        row.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(6);
        body.addView(row, lp);
    }

    /** 设置 → 遥控器按键说明：电视端遥控器 / 键盘 / 手机手势对照。 */
    private void addKeymapPage(LinearLayout body) {
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
        addSectionHeader(body, "电视遥控器");
        for (int i = 0; i < 8; i++) addKeymapRow(body, rows[i][0], rows[i][1]);
        a.gap(body, 14);
        addSectionHeader(body, "键盘 / 手机手势");
        for (int i = 8; i < rows.length; i++) addKeymapRow(body, rows[i][0], rows[i][1]);
    }

    private void addKeymapRow(LinearLayout body, String key, String desc) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        row.setBackground(glassBg(0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
        TextView keyView = a.text(key, 12.5f, 0xffffb3c8);
        keyView.setTypeface(null, Typeface.BOLD);
        keyView.setMinWidth(a.dp(120));
        row.addView(keyView);
        TextView descView = a.text(desc, 12.5f, 0xffc9d4e6);
        descView.setPadding(a.dp(10), 0, 0, 0);
        row.addView(descView, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(6);
        body.addView(row, lp);
    }

    /** 设置 → 观影统计：按本地观看历史统计本周数据。 */
    private void addStatsPage(LinearLayout body) {
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
            days.add(new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                    .format(new java.util.Date(e.updated * 1000L)));
        }
        long minutes = weekSeconds / 60;
        addSectionHeader(body, "本周观影");
        LinearLayout metrics = a.row();
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.addView(metricCard("本周观影", weekTitles + " 部", 0xffffb3c8),
                new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, -2, 1f);
        m2.leftMargin = a.dp(10);
        metrics.addView(metricCard("本周时长", (minutes >= 60 ? (minutes / 60) + " 小时 " : "") + (minutes % 60) + " 分", 0xff8fc5ff), m2);
        LinearLayout.LayoutParams m3 = new LinearLayout.LayoutParams(0, -2, 1f);
        m3.leftMargin = a.dp(10);
        metrics.addView(metricCard("活跃天数", days.size() + " 天", 0xff5ee0a0), m3);
        body.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
        a.gap(body, 14);

        addSectionHeader(body, "最近观看");
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
            row.setBackground(glassBg(0xcc16283f, 0xb3121d2e, a.dp(14), a.dp(0.8f), 0x33ffffff));
            TextView t = a.text(e.title, 13.5f, MainActivity.WHITE);
            t.setTypeface(null, Typeface.BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            int pct = e.duration > 0 ? Math.min(100, Math.round(e.position * 100f / e.duration)) : 0;
            TextView p = a.text(pct + "%", 11.5f, 0xffffb3c8);
            row.addView(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(6);
            body.addView(row, lp);
        }
    }

    private LinearLayout metricCard(String label, String value, int accent) {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
        card.setBackground(glassBg(0xcc16283f, 0xb3121d2e, a.dp(16), a.dp(0.8f), 0x33ffffff));
        TextView v = a.text(value, 18, accent);
        v.setTypeface(null, Typeface.BOLD);
        card.addView(v);
        TextView l = a.text(label, 11, 0xff8d9cb6);
        l.setPadding(0, a.dp(3), 0, 0);
        card.addView(l);
        return card;
    }

    /** 屏幕保护设置：开关 / 时长 / 时钟样式统一在一个对话框里。 */
    void showScreensaverSettings() {
        boolean on = a.prefs.getBoolean(Prefs.SCREENSAVER_ENABLED, false);
        int minutes = a.prefs.getInt(Prefs.SCREENSAVER_TIMEOUT_MIN, 5);
        int style = Math.max(0, Math.min(2, a.prefs.getInt(Prefs.SCREENSAVER_CLOCK_STYLE, 0)));
        String[] labels = {
            "开关：" + (on ? "已开启" : "已关闭"),
            "时长：" + minutes + " 分钟",
            "时钟样式：" + new String[] {"数字时钟 · 大号", "极简 · 仅时间", "超大字 · 时间+日期"}[style]
        };
        new AppDialog.Builder(a)
                .setTitle("屏幕保护")
                .setMessage("空闲进入屏保时用动态壁纸铺满屏幕，触摸退出；播放视频时不会触发。")
                .setItems(
                        labels,
                        (d, index) -> {
                            if (index == 0)
                                a.prefs
                                        .edit()
                                        .putBoolean(Prefs.SCREENSAVER_ENABLED, !on)
                                        .apply();
                            else if (index == 1) {
                                int next = minutes == 5 ? 10 : minutes == 10 ? 30 : minutes == 30 ? 1 : 5;
                                a.prefs.edit().putInt(Prefs.SCREENSAVER_TIMEOUT_MIN, next).apply();
                            } else
                                a.prefs
                                        .edit()
                                        .putInt(Prefs.SCREENSAVER_CLOCK_STYLE, (style + 1) % 3)
                                        .apply();
                            a.toast("已更新屏幕保护设置");
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView h = a.text(title, 12.5f, 0xff5ac8fa);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        parent.addView(h);
    }

    private void addSettingCard(
            LinearLayout parent, int iconKind, String title, String subtitle, Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
                a.dp(UiTokens.SP_14),
                a.dp(UiTokens.SP_12),
                a.dp(UiTokens.SP_14),
                a.dp(UiTokens.SP_12));
        row.setBackground(
                glassBg(0xcc1e3557, 0xcc17294a, a.dp(UiTokens.RADIUS_20), a.dp(0.7f), 0x33ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(v -> action.run());

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(36), a.dp(36));
        iconLp.rightMargin = a.dp(12);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 14.5f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, 12, MainActivity.MUTED);
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

    /** 设置卡片图标徽标：彩色圆角底 + 统一 vector 图标（不再 Canvas 手绘字形）。 */
    static final class SettingIconView extends View {
        private final MainActivity a;
        private final int kind;
        private final android.graphics.drawable.Drawable glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingIconView(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
            glyph = UiIcon.drawable(activity, glyphKind(kind));
        }

        static int glyphKind(int kind) {
            switch (kind) {
                case 1:
                    return UiIcon.LAYERS;
                case 2:
                    return UiIcon.CLOUD;
                case 3:
                    return UiIcon.PLAY_CIRCLE;
                case 4:
                    return UiIcon.TUNE;
                case 5:
                    return UiIcon.TV;
                default:
                    return UiIcon.REFRESH;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int badgeBg;
            int strokeColor;
            switch (kind) {
                case 1:
                    // 降低徽标底饱和度（深蓝灰），图标用更亮的蓝，保证图标 vs 徽标 ≥ 4.5:1
                    badgeBg = 0x2E123A5C;
                    strokeColor = 0xFF7CC4FF;
                    break;
                case 2:
                    badgeBg = 0x2E123F32;
                    strokeColor = 0xFF5EE0A0;
                    break;
                case 3:
                    badgeBg = 0x2E4A3A12;
                    strokeColor = 0xFFFFC94D;
                    break;
                case 4:
                    badgeBg = 0x2E3A2252;
                    strokeColor = 0xFFC99BFF;
                    break;
                default:
                    badgeBg = 0x2E123A5C;
                    strokeColor = 0xFF8CC6FF;
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(badgeBg);
            c.drawRoundRect(new RectF(0, 0, w, h), a.dp(10), a.dp(10), paint);
            UiIcon.tintDrawable(glyph, strokeColor);
            UiIcon.drawCentered(c, glyph, (int) w, (int) h, Math.min(w, h) * 0.64f);
        }
    }

    private void renderCenteredHint(LinearLayout parent, String message) {
        TextView t = a.text(message, 14, MainActivity.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(a.dp(20), a.dp(48), a.dp(20), a.dp(48));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * Deep emerald-obsidian ambient canvas matching the iPhone `AppleAmbientBackground` preset.
     */
    private static final class AmbientCanvasView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AmbientCanvasView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0xff152a45, 0xff0a1424, 0xff060b14},
                            new float[] {0f, 0.52f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            float radius = Math.max(w, h) * 0.65f;
            glowPaint.setShader(
                    new RadialGradient(
                            w * 0.5f,
                            h * 0.14f,
                            radius,
                            new int[] {0x33ff9dbb, 0x14243a5e, 0x00060c15},
                            new float[] {0f, 0.55f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, glowPaint);
        }
    }

    /** Soft top-edge gradient veil so scrolling content dissolves gently under the status bar. */
    private static final class TopEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TopEdgeFadeView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0xd80d1c16, 0x780d1c16, 0x000d1c16},
                            new float[] {0f, 0.58f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /** Soft bottom-edge vignette behind the floating Liquid Glass dock. */
    private static final class BottomEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BottomEdgeFadeView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0x00060a08, 0x90060a08, 0xe6060a08},
                            new float[] {0f, 0.55f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /**
     * Custom vector icons matching the iPhone Cupertino dock icons:
     *
     * <ul>
     *   <li>`kind == 0`: `play_rectangle_fill` / `play_rectangle` (`主页`)
     *   <li>`kind == 1`: `search` magnifying glass (`搜索`)
     *   <li>`kind == 2`: `dot_radiowaves_left_right` (`直播`)
     *   <li>`kind == 3`: `square_favorites_alt` (`片库`)
     *   <li>`kind == 4`: `gear_alt` (`设置`)
     * </ul>
     */
    static final class NavGlyph extends View {
        private final MainActivity a;
        private final int kind;
        private final android.graphics.drawable.Drawable glyph;
        private boolean selected;
        private int customTint = 0;

        NavGlyph(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
            glyph = UiIcon.drawable(activity, glyphKind(kind));
        }

        /** Tab 顺序：主页(0) / 搜索(1) / 直播(2) / 片库(3) / 设置(4) / 电视(5)。 */
        static int glyphKind(int kind) {
            switch (kind) {
                case 0:
                    return UiIcon.HOME;
                case 1:
                    return UiIcon.SEARCH;
                case 2:
                    return UiIcon.LIVE;
                case 3:
                    return UiIcon.LIBRARY;
                case 5:
                    return UiIcon.TV;
                default:
                    return UiIcon.TUNE;
            }
        }

        void setSelectedState(boolean value) {
            this.selected = value;
            invalidate();
        }

        void setIconTint(int color) {
            this.customTint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int color =
                    customTint != 0
                            ? customTint
                            : (selected ? UiTokens.ACCENT : UiTokens.TEXT_PRIMARY);
            UiIcon.tintDrawable(glyph, color);
            UiIcon.drawCentered(c, glyph, getWidth(), getHeight(), Math.min(getWidth(), getHeight()));
        }
    }
}
