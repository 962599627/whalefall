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
import androidx.recyclerview.widget.GridLayoutManager;
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
        int sources = 1;
        /** 1.9.31：同一标题合并的多个来源，供手机来源筛选使用。 */
        final List<Cms.Site> sites = new ArrayList<>();

        CachedSearchHit(Cms.Site site, Cms.Video video) {
            this.site = site;
            this.video = video;
            sites.add(site);
        }
    }

    static String cachedSearchKeyword = "";
    static String cachedSearchStatus = "";
    static final List<CachedSearchHit> cachedSearchHits = new ArrayList<>();
    /** 1.9.31：手机搜索页来源筛选（空=全部）。 */
    static final java.util.Set<String> cachedSearchSourceFilter = new java.util.HashSet<>();
    static int cachedSearchScrollY = 0;

    /** Sub-page of the 片库 tab: 0 history, 1 favorites, 2 downloads. */
    static int mySubTab = 0;

    /** 1.8.9 首页分类原地渲染：云盘配置内容容器与列表显隐引用。 */
    LinearLayout homeCloudHolder;
    RecyclerView homeRecyclerRef;
    LinearLayout homeStatusWrapRef;
    TextView homeLoadMoreRef;
    TextView homeFilterBtnRef;
    boolean cloudInlineActive;

    /** 1.8.10：原地渲染 4 盘卡片（简化实现，不依赖 CloudConfigScreen 页面容器）。 */
    private void showCloudConfigInline() {
        if (homeCloudHolder == null) {
            AppLog.i("CloudConfig", "inline show holder=null");
            return;
        }
        try {
            homeCloudHolder.removeAllViews();
            homeCloudHolder.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            homeCloudHolder.setMinimumHeight(a.dp(360));
            homeCloudHolder.setVisibility(View.VISIBLE);
            homeCloudHolder.requestLayout();
            // 1.8.13 根因：holder 在 header item 内，隐藏 recycler 会连同 holder 一起隐藏。
            // 改为 recycler 保持可见，仅让 adapter 只显示 header item（含 holder）。
            cloudInlineActive = true;
            if (homeStatusWrapRef != null) homeStatusWrapRef.setVisibility(View.GONE);
            if (homeLoadMoreRef != null) homeLoadMoreRef.setVisibility(View.GONE);
            if (homeFilterBtnRef != null) homeFilterBtnRef.setVisibility(View.GONE);
            if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
            if (homeList != null) homeList.scrollToPosition(0);
            buildCloudRowsInline();
            final LinearLayout holder = homeCloudHolder;
            holder.post(
                    () -> AppLog.i(
                            "CloudConfig",
                            "holder parent="
                                    + (holder.getParent() == null
                                            ? "null"
                                            : holder.getParent().getClass().getSimpleName())
                                    + " w="
                                    + holder.getWidth()
                                    + " h="
                                    + holder.getHeight()
                                    + " y="
                                    + holder.getY()
                                    + " alpha="
                                    + holder.getAlpha()
                                    + " childCount="
                                    + holder.getChildCount()));
            AppLog.i(
                    "CloudConfig",
                    "inline show holder="
                            + System.identityHashCode(homeCloudHolder)
                            + " visible="
                            + (homeCloudHolder.getVisibility() == View.VISIBLE)
                            + " rows="
                            + homeCloudHolder.getChildCount()
                            + " error=");
        } catch (Throwable error) {
            AppLog.w("CloudConfig", "inline show failed", error);
            homeCloudHolder.removeAllViews();
            TextView fail =
                    a.text("云盘配置加载失败：" + a.message(error), 13, 0xffffb4ad);
            fail.setPadding(0, a.dp(12), 0, a.dp(8));
            homeCloudHolder.addView(fail);
            homeCloudHolder.addView(
                    a.button("重试", "cloud-inline-retry", this::showCloudConfigInline));
            homeCloudHolder.setVisibility(View.VISIBLE);
        }
    }

    private void buildCloudRowsInline() {
        // 1.8.12：与首页共用同一套四盘卡片（CloudInlineView）
        CloudInlineView.build(a, homeCloudHolder, this::showCloudConfigInline);
    }

    private void hideCloudConfigInline() {
        if (homeCloudHolder == null) return;
        homeCloudHolder.setVisibility(View.GONE);
        homeCloudHolder.setMinimumHeight(0);
        cloudInlineActive = false;
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        if (homeStatusWrapRef != null) homeStatusWrapRef.setVisibility(View.VISIBLE);
        if (homeFilterBtnRef != null) homeFilterBtnRef.setVisibility(View.VISIBLE);
    }

    /**
     * Sub-page of the 设置 tab:
     * 0 = Main 5-card index, 1 = 片源与直播配置, 2 = 播放、解码与字幕, 3 = 界面外观与偏好, 4 = 备份、更新与诊断.
     */
    static int settingsSubPage = 0;

    private final MainActivity a;
    private FrameLayout contentContainer;
    private final View[] navTabs = new View[7];
    private final TextView[] navLabels = new TextView[7];
    private final NavGlyph[] navIcons = new NavGlyph[7];
    private static final String[] TAB_NAMES = {"主页", "搜索", "直播", "片库", "设置", "电视", "云盘专区"};
    private static final int[] DOCK_TAB_INDICES = {0, 2, 5, 3, 6, 4};

    // Home tab state
    private Cms.Site currentSite;
    private String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    private List<Cms.Category> currentCategories = new ArrayList<>();
    private String filterSchema = "{}";
    private final java.util.Map<String, String> selectedFilters = new java.util.LinkedHashMap<>();
    /** 1.9.33：手机首页 L2/L3 筛选行。 */
    private LinearLayout homeFilterTagRow, homeFilterOptionRow;
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
        a.scroll = homeList;
        a.body = homeBody;
        if (a.current != null) a.current.home = true;
        return true;
    }

    MobileShell(MainActivity activity) {
        this.a = activity;
        activeShell = this;
    }

    /** 1.8.22：Dock「云盘专区」切到 shell 内嵌 tab（不打开新 Screen）。 */
    static MobileShell activeShell;

    static boolean switchToCloudZone(MainActivity activity) {
        MobileShell shell = activeShell;
        if (shell == null || shell.a != activity) return false;
        AppLog.i("Nav", "cloud-zone open rail=shell tab");
        shell.switchTab(6);
        return true;
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
        input.setHintTextColor(a.themeColor(0xff7f90ad));
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
        a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.installBackdrop(a.root);
        a.setContentView(a.root);


        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        // 手机上竖屏一律用底部 Dock；侧栏只用于横屏/平板（与首页 sideRailLayout 的守卫一致），
        // 否则横屏重建后转回竖屏会残留「左侧栏 + 三段式」的错乱布局。
        boolean tabletSideRail = widthDp >= 640 && !a.isPortrait();
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        if (tabletSideRail) {
            LinearLayout mainRow = a.row();
            mainRow.setGravity(Gravity.TOP);
            a.root.addView(mainRow, new FrameLayout.LayoutParams(-1, -1));

            contentContainer = new FrameLayout(a);
            contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
            contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
            dockView = bottomDock;
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
                    else a.openSourceAccountPanel();
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
                            if (action == android.view.MotionEvent.ACTION_UP && pendingTab >= 0) {
                                if (pendingTab == 6) {
                                    if (!MainActivity.cloudEnabled(a)) return true;
                                    AppLog.i("Nav", "cloud-zone open rail=shell tab");
                                    switchTab(6);
                                } else switchTab(pendingTab);
                            }
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

        String[] navKeys = {
            "nav-home",
            "nav-search",
            "nav-live",
            "nav-favorites",
            "nav-settings",
            "nav-tv",
            "nav-cloud-zone"
        };
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            final int tabIdx = DOCK_TAB_INDICES[pos];
            // 防御：任何数组长度不匹配都不允许崩溃（历史 AIOOBE length=6 index=6）
            if (tabIdx < 0
                    || tabIdx >= navKeys.length
                    || tabIdx >= TAB_NAMES.length
                    || tabIdx >= navLabels.length
                    || tabIdx >= navIcons.length
                    || tabIdx >= navTabs.length) {
                AppLog.i("Nav", "skip out-of-range tabIdx=" + tabIdx
                        + " navKeys=" + navKeys.length + " TAB_NAMES=" + TAB_NAMES.length);
                continue;
            }
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[tabIdx]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, tabIdx);
            navIcons[tabIdx] = glyph;
            tab.addView(
                    glyph, new LinearLayout.LayoutParams(a.dp(22 * navScale), a.dp(22 * navScale)));

            TextView label =
                    a.text(
                            TAB_NAMES[tabIdx],
                            (tabIdx == 6 ? 9f : 10.5f) * navScale,
                            UiTokens.TEXT_PRIMARY);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            // 图标与文字之间 +2dp，避免 Dock 里文字贴着图标底部
            label.setPadding(0, a.dp(4), 0, 0);
            navLabels[tabIdx] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));

            tab.setOnClickListener(
                    v -> {
                        if (tabIdx == 6) {
                            if (!MainActivity.cloudEnabled(a)) {
                                a.toast("云盘功能已暂停，可在配置中恢复");
                                return;
                            }
                            AppLog.i("Nav", "cloud-zone open rail=shell tab");
                            switchTab(6);
                        } else switchTab(tabIdx);
                    });
            navTabs[tabIdx] = tab;
            if (tabIdx == 6 && !MainActivity.cloudEnabled(a)) tab.setVisibility(View.GONE);

            LinearLayout.LayoutParams lp =
                    verticalRail
                            ? new LinearLayout.LayoutParams(-1, 0, 1f)
                            : new LinearLayout.LayoutParams(0, -1, 1f);
            bar.addView(tab, lp);
        }
        return bar;
    }

    private RecyclerView homeList;
    private LinearLayout homeBody;
    private static View dockView;

    // ---- 首页网格虚拟化（E3-S1）：头部区块 / 影片行 / 加载更多都是 RecyclerView item ----
    private static final int HOME_ITEM_HEADER = 0;
    private static final int HOME_ITEM_ROW = 1;
    private static final int HOME_ITEM_FOOTER = 2;
    /** 首页头部区块（item 0，单例复用，不随滚动重建）。 */
    private LinearLayout homeHeaderBody;
    /** 底部「加载更多」外层容器（末尾 item，单例复用）。 */
    private FrameLayout homeFooterWrap;
    private TextView homeFilterButton;
    private HomeAdapter homeAdapter;
    private int homeCols = 3, homeCardWidthDp, homePosterHeightDp;
    /** 首屏数据到达后要恢复的滚动锚点（item position + 顶部偏移 px）。 */
    private int homeRestorePos = -1, homeRestoreOffset;
    /** 切换 tab 时保存的首页锚点；pending* 来自 onSaveInstanceState 恢复。 */
    private static int homeAnchorPos = -1, homeAnchorOffset;
    static int pendingHomeAnchorPos = -1, pendingHomeAnchorOffset;

    /** 全屏播放时隐藏底部 Dock，避免与播放器控件/网页控件叠加。 */
    static void setDockVisible(boolean visible) {
        if (dockView != null) dockView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void switchTab(int index) {
        // 1.9.31：切换 tab 重置「再按一次退出」窗口，避免跨页误退出
        a.lastRootBackAt = 0;
        ++homeRequest;
        homeReady = false;
        ++a.generation;
        if (activeTab == 0) captureHomeAnchor();
        activeTab = Math.max(0, Math.min(6, index));
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
            case 6:
                renderCloudZoneTab();
                break;
        }
    }

    /** 1.8.22 内嵌「云盘专区」tab：标题 + 图三 8 卡网格 + 查看文件入口。 */
    private void renderCloudZoneTab() {
        contentContainer.removeAllViews();
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        LinearLayout body = a.vertical();
        body.setPadding(0, statusBarHeight() + a.dp(10), 0, a.dp(24));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        TextView title = a.text("云盘专区 · 本地", 22, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(a.dp(16), 0, a.dp(16), a.dp(6));
        body.addView(title);
        AppLog.i("CloudZone", "render tab inline");
        CloudZoneScreen.renderInto(a, body, this::renderCloudZoneTab);
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

        // E3-S1：首页改为 RecyclerView 虚拟化。头部区块 / 影片行 / 加载更多各占 item，
        // 影片只为「可见区 + 预取」创建卡片，不再一次性 addView 全部海报。
        final int restorePos =
                pendingHomeAnchorPos >= 0 ? pendingHomeAnchorPos : homeAnchorPos;
        final int restoreOffset =
                pendingHomeAnchorPos >= 0 ? pendingHomeAnchorOffset : homeAnchorOffset;
        pendingHomeAnchorPos = -1;
        final RecyclerView recycler = new RecyclerView(a);
        homeList = recycler;
        homeRestorePos = restorePos;
        homeRestoreOffset = restoreOffset;
        float homeWidthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        homeCols = homeWidthDp >= 720 ? 5 : (homeWidthDp >= 520 ? 4 : 3);
        int homeHorizontalPaddingDp = homeWidthDp >= 640 ? 116 : 32;
        homeCardWidthDp =
                Math.max(
                        92,
                        (int)
                                ((homeWidthDp - homeHorizontalPaddingDp - (homeCols - 1) * 12)
                                        / homeCols));
        homePosterHeightDp = Math.round(homeCardWidthDp * 1.48f);
        recycler.setLayoutManager(new LinearLayoutManager(a));
        recycler.setItemAnimator(null);
        recycler.setVerticalScrollBarEnabled(false);
        recycler.setClipToPadding(false);
        a.scroll = recycler;
        contentContainer.addView(recycler, new FrameLayout.LayoutParams(-1, -1));
        if (filterSummaryBar != null) filterSummaryBar.view().bringToFront();

        // 底部常驻筛选摘要条：未选时是入口，已选时展示条件 chips，点击展开筛选浮层
        final float homeDensity = a.getResources().getDisplayMetrics().density;
        final boolean sideRailLayout = a.windowWidth() / homeDensity >= 640 && !a.isPortrait();
        final float homeNavScale =
                Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        // 1.9.33：删除底部常驻筛选条，底部留给 Dock（64+16+8）
        filterSummaryBar = null;
        recycler.setPadding(0, statusBarHeight() + a.dp(8), 0, a.dp(88));
        final LinearLayout body = a.vertical();
        a.body = body;
        homeBody = body;
        homeHeaderBody = body;

        // 1. Scrollable Top Header Row (Brand + Site Capsule + Netdisk Capsule + Search Icon)
        body.addView(buildHomeTopHeaderRow(), new LinearLayout.LayoutParams(-1, -2));

        // 2. History shelf; 同一部剧折叠为最近一集，点击直接续播
        List<WatchHistory.Entry> history = WatchHistory.collapsed(a.watchHistory());
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
        final LinearLayout tonightGrid = a.vertical();
        LinearLayout.LayoutParams tonightGridLp = new LinearLayout.LayoutParams(-1, -2);
        tonightGridLp.topMargin = a.dp(12);
        tonightCard.addView(tonightGrid, tonightGridLp);
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
                    tonightGrid.removeAllViews();
                    java.util.List<Cms.Video> pool =
                            new java.util.ArrayList<>(currentVideos);
                    java.util.Collections.shuffle(pool, new java.util.Random(System.nanoTime()));
                    int picks = Math.min(3, pool.size());
                    float d = a.getResources().getDisplayMetrics().density;
                    int widthDp = Math.round(a.windowWidth() / d);
                    int cols, gap, capW, capH, sideBudget;
                    if (widthDp < 360) {
                        cols = 2; gap = 10; capW = 120; capH = 180; sideBudget = 64;
                    } else if (widthDp < 600) {
                        cols = 3; gap = 10; capW = 120; capH = 180; sideBudget = 64;
                    } else if (widthDp < 900) {
                        cols = 4; gap = 12; capW = 150; capH = 225; sideBudget = 72;
                    } else {
                        cols = 5; gap = 16; sideBudget = 88;
                        capW = a.isTvDevice() ? 170 : 150;
                        capH = a.isTvDevice() ? 255 : 225;
                    }
                    int contentW = Math.max(0, widthDp - sideBudget);
                    int cardWdp = Math.min((contentW - gap * (cols - 1)) / cols, capW);
                    cardWdp = Math.max(96, cardWdp);
                    final int cardHdp = Math.min(Math.round(cardWdp * 1.5f), capH);
                    int rows = (picks + cols - 1) / cols;
                    for (int r = 0; r < rows; r++) {
                        LinearLayout row = a.row();
                        if (r > 0) row.setPadding(0, a.dp(gap), 0, 0);
                        for (int c = 0; c < cols; c++) {
                            int index = r * cols + c;
                            if (index >= picks) break;
                            final Cms.Video v = pool.get(index);
                            FrameLayout pick = new FrameLayout(a);
                            pick.setFocusable(true);
                            pick.setClipToOutline(true);
                            pick.setBackground(
                                    glassBg(
                                            0xff1a2c45,
                                            0xff13233a,
                                            a.dp(widthDp < 600 ? 12 : 14),
                                            a.dp(0.6f),
                                            0x33ffffff));
                            MobileDetailScreen.bindTouchFeedback(pick);
                            pick.setOnClickListener(x -> a.openListing(currentSite, v));
                            ImageView poster = new ImageView(a);
                            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            pick.addView(poster, new FrameLayout.LayoutParams(-1, -1));
                            TextView ph =
                                    a.text(
                                            v.title == null || v.title.isEmpty()
                                                    ? "鲸"
                                                    : v.title.substring(0, 1),
                                            26,
                                            0x40ffffff);
                            ph.setTypeface(null, Typeface.BOLD);
                            ph.setGravity(Gravity.CENTER);
                            pick.addView(ph, new FrameLayout.LayoutParams(-1, -1));
                            if (v.poster != null && !v.poster.isEmpty())
                                a.poster(v.poster, poster);
                            View shade = new View(a);
                            android.graphics.drawable.GradientDrawable bottomFade =
                                    new android.graphics.drawable.GradientDrawable(
                                            android.graphics.drawable.GradientDrawable.Orientation
                                                    .TOP_BOTTOM,
                                            new int[] {0x00000000, 0xcc000000});
                            shade.setBackground(bottomFade);
                            pick.addView(
                                    shade,
                                    new FrameLayout.LayoutParams(
                                            -1, a.dp(48), Gravity.BOTTOM));
                            TextView tt = a.text(v.title, widthDp < 600 ? 12 : (a.isTvDevice() ? 14 : 13), MainActivity.WHITE);
                            tt.setSingleLine(true);
                            tt.setEllipsize(TextUtils.TruncateAt.END);
                            tt.setTypeface(null, Typeface.BOLD);
                            tt.setPadding(a.dp(8), 0, a.dp(8), a.dp(8));
                            pick.addView(tt, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                            LinearLayout.LayoutParams pickLp =
                                    new LinearLayout.LayoutParams(
                                            a.dp(cardWdp), a.dp(cardHdp));
                            if (c > 0) pickLp.leftMargin = a.dp(gap);
                            row.addView(pick, pickLp);
                        }
                        tonightGrid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    }
                    tonightCard.setVisibility(View.VISIBLE);
                    if (a.isTvDevice() && tonightGrid.getChildCount() > 0) {
                        LinearLayout firstRow = (LinearLayout) tonightGrid.getChildAt(0);
                        if (firstRow.getChildCount() > 0) firstRow.getChildAt(0).requestFocus();
                    }
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
        homeFilterButton = filterButton;
        filterButton.setVisibility(View.GONE); // 1.9.33：右上「筛选」按钮不再显示
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
        // 1.9.33：L2 筛选标签行 / L3 选项行
        homeFilterTagRow = a.row();
        homeFilterTagRow.setClipChildren(false);
        homeFilterTagRow.setVisibility(View.GONE);
        body.addView(homeFilterTagRow, new LinearLayout.LayoutParams(-1, -2));
        homeFilterOptionRow = a.row();
        homeFilterOptionRow.setClipChildren(false);
        homeFilterOptionRow.setVisibility(View.GONE);
        body.addView(homeFilterOptionRow, new LinearLayout.LayoutParams(-1, -2));

        // 1.8.9 云盘配置内容原地容器（仅动作型 chip 使用，默认隐藏）
        homeCloudHolder = a.vertical();
        homeCloudHolder.setTag("home-cloud-inline");
        homeCloudHolder.setVisibility(View.GONE);
        body.addView(homeCloudHolder, new LinearLayout.LayoutParams(-1, -2));

        // 5. Status & 3-Column Poster Grid Container
        final LinearLayout statusWrap = a.vertical();
        statusWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        final WhaleLoadingView shelfWhale =
                new WhaleLoadingView(a, WhaleLoadingView.SMALL, true);
        shelfWhale.setText("正在加载片单…");
        statusWrap.addView(shelfWhale);
        final TextView statusText = a.text("正在加载片单…", 12, MainActivity.MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(24));
        statusWrap.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        body.addView(statusWrap, new LinearLayout.LayoutParams(-1, -2));

        // 影片网格改为 RecyclerView 的「影片行」item（见 HomeAdapter），不再建全量容器。

        final TextView loadMoreBtn = a.text("加载更多影片", 13.5f, MainActivity.WHITE);
        homeRecyclerRef = recycler;
        homeStatusWrapRef = statusWrap;
        homeLoadMoreRef = loadMoreBtn;
        homeFilterBtnRef = filterButton;
        loadMoreBtn.setTag("home-load-more");
        loadMoreBtn.setTypeface(null, Typeface.BOLD);
        loadMoreBtn.setGravity(Gravity.CENTER);
        loadMoreBtn.setPadding(a.dp(20), a.dp(11), a.dp(20), a.dp(11));
        loadMoreBtn.setBackground(
                glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.8f), 0x33ffffff));
        loadMoreBtn.setVisibility(View.GONE);
        MobileDetailScreen.bindTouchFeedback(loadMoreBtn);
        // 末尾 item：「加载更多」。用单例容器包住，隐藏时高度为 0，保持原本无占位的观感。
        homeFooterWrap = new FrameLayout(a);
        FrameLayout.LayoutParams moreFlp = new FrameLayout.LayoutParams(-1, -2);
        moreFlp.setMargins(
                a.dp(UiTokens.SP_16),
                a.dp(UiTokens.SP_12),
                a.dp(UiTokens.SP_16),
                a.dp(UiTokens.SP_8));
        homeFooterWrap.addView(loadMoreBtn, moreFlp);
        homeAdapter = new HomeAdapter();
        recycler.setAdapter(homeAdapter);

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
                        shelfWhale.setText("正在加载…");
                        shelfWhale.start();
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
                                                    if (homeFilterButton != null)
                                                        homeFilterButton.setVisibility(
                                                                View.GONE);
                                                    if (filterSummaryBar != null)
                                                        filterSummaryBar
                                                                .view()
                                                                .setVisibility(View.GONE);
                                                    if (homeFilterTagRow != null)
                                                        homeFilterTagRow.setVisibility(View.GONE);
                                                    if (homeFilterOptionRow != null)
                                                        homeFilterOptionRow.setVisibility(
                                                                View.GONE);
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
                                                shelfWhale.stop();
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
                                                homeAdapter.submit(
                                                        currentVideos, siteSnapshot);
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
                                                statusText.setVisibility(
                                                        View.VISIBLE);
                                                statusText.setText("加载失败：" + a.message(e));
                                                shelfWhale.setText("加载失败");
                                                shelfWhale.stop();
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
        recycler.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                        // 只在向下滚动时触发，避免上滑回顶时误加载下一页
                        if (dy <= 0) return;
                        if (loadingPage[0] || pageFailed[0] || currentPage >= totalPages)
                            return;
                        RecyclerView.LayoutManager layoutManager =
                                recyclerView.getLayoutManager();
                        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                        if (!(layoutManager instanceof LinearLayoutManager)
                                || adapter == null) return;
                        // 和旧 ScrollView 一致：离内容底部 <= 240dp 时自动加载下一页
                        View contentLast =
                                layoutManager.findViewByPosition(adapter.getItemCount() - 1);
                        if (contentLast == null) return;
                        int diff =
                                contentLast.getBottom()
                                        + recyclerView.getPaddingBottom()
                                        - recyclerView.getHeight();
                        if (diff <= a.dp(240)) {
                            currentPage++;
                            fetchCategoryPage[0].run();
                        }
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
                    if (homeFilterButton != null)
                        homeFilterButton.setText(
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
    static boolean isCloudConfigCategory(String label, String id) {
        String text = ((label == null ? "" : label) + " " + (id == null ? "" : id))
                .toLowerCase(java.util.Locale.ROOT);
        return text.contains("云盘") || text.contains("云盤") || text.contains("网盘配置");
    }

    /** 1.9.33：手机首页 L2 标签行 / L3 选项行。 */
    boolean hasMobileFilters() {
        try {
            return !mobileFilterDefinitions().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    void toggleMobileFilterTags() {
        if (homeFilterTagRow == null) return;
        if (homeFilterTagRow.getVisibility() == View.VISIBLE) {
            homeFilterTagRow.setVisibility(View.GONE);
            if (homeFilterOptionRow != null) homeFilterOptionRow.setVisibility(View.GONE);
            return;
        }
        renderMobileFilterTags();
    }

    void renderMobileFilterTags() {
        if (homeFilterTagRow == null) return;
        homeFilterTagRow.removeAllViews();
        if (homeFilterOptionRow != null) homeFilterOptionRow.setVisibility(View.GONE);
        List<VideoFilters.Definition> defs = mobileFilterDefinitions();
        if (defs.isEmpty()) {
            homeFilterTagRow.setVisibility(View.GONE);
            return;
        }
        homeFilterTagRow.setVisibility(View.VISIBLE);
        TextView label = a.text("筛选：", 12, MainActivity.MUTED);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(0, 0, a.dp(6), 0);
        homeFilterTagRow.addView(label);
        for (final VideoFilters.Definition def : defs) {
            String value = selectedFilters.get(def.dimension);
            boolean active = value != null && !value.isEmpty();
            TextView chip =
                    a.text(
                            def.label + " · " + (active ? def.labelFor(value) : "全部"),
                            12,
                            active ? 0xff0b1220 : 0xffe6edfb);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            chip.setBackground(
                    active
                            ? a.bg(0xffff8fb1, 999)
                            : glassBg(0x14ffffff, 0x33ffffff, a.dp(999), a.dp(0.8f), 0x33ffffff));
            MobileDetailScreen.bindTouchFeedback(chip);
            chip.setFocusable(true);
            chip.setMinHeight(a.dp(32));
            chip.setOnClickListener(v -> showMobileFilterOptions(def));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
            lp.rightMargin = a.dp(6);
            homeFilterTagRow.addView(chip, lp);
        }
    }

    void showMobileFilterOptions(final VideoFilters.Definition def) {
        if (homeFilterOptionRow == null) return;
        if (homeFilterOptionRow.getVisibility() == View.VISIBLE
                && String.valueOf(homeFilterOptionRow.getTag()).equals(def.dimension)) {
            homeFilterOptionRow.setVisibility(View.GONE);
            homeFilterOptionRow.setTag("");
            return;
        }
        homeFilterOptionRow.removeAllViews();
        homeFilterOptionRow.setTag(def.dimension);
        homeFilterOptionRow.setVisibility(View.VISIBLE);
        for (final VideoFilters.Choice choice : def.choices) {
            String current = selectedFilters.get(def.dimension);
            boolean active = choice.value.equals(current == null ? "" : current);
            TextView chip = a.text(choice.label, 12, active ? 0xff0b1220 : 0xffe6edfb);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            chip.setBackground(
                    active
                            ? a.bg(0xffff8fb1, 999)
                            : glassBg(0x14ffffff, 0x33ffffff, a.dp(999), a.dp(0.8f), 0x33ffffff));
            MobileDetailScreen.bindTouchFeedback(chip);
            chip.setFocusable(true);
            chip.setMinHeight(a.dp(32));
            chip.setOnClickListener(
                    v -> {
                        if (choice.value.isEmpty()) selectedFilters.remove(def.dimension);
                        else selectedFilters.put(def.dimension, choice.value);
                        AppLog.i(
                                "Filter",
                                "category="
                                        + selectedCategoryId
                                        + " tab="
                                        + def.label
                                        + " value="
                                        + choice.value);
                        homeFilterOptionRow.setVisibility(View.GONE);
                        currentPage = 1;
                        if (homeFilterApply != null) homeFilterApply.run();
                        renderMobileFilterTags();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
            lp.rightMargin = a.dp(6);
            homeFilterOptionRow.addView(chip, lp);
        }
    }

    boolean backFilterUi() {
        if (homeFilterOptionRow != null && homeFilterOptionRow.getVisibility() == View.VISIBLE) {
            homeFilterOptionRow.setVisibility(View.GONE);
            return true;
        }
        if (homeFilterTagRow != null && homeFilterTagRow.getVisibility() == View.VISIBLE) {
            homeFilterTagRow.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    private void addCategoryChip(
            LinearLayout row,
            String label,
            String catId,
            Runnable refreshChips,
            Runnable loadPage) {
        boolean active = selectedCategoryId.equals(catId);
        boolean hasFilters = hasMobileFilters();
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView chip =
                a.text(
                        hasFilters ? label + "  ≡" : label,
                        13 * navScale,
                        active ? 0xff000000 : 0xfff3f4f6);
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
                    // 1.8.28：云盘配置 → 我们的玻璃 UI 管理源插件登录（不再直接弹插件白底面板）
                    if (isCloudConfigCategory(label, catId)) {
                        AppLog.i(
                                "CloudConfig",
                                "chip -> SourceCloudConfigScreen label=" + label + " id=" + catId);
                        SourceCloudConfigScreen.show(a);
                        return;
                    }
                    hideCloudConfigInline();
                    // 1.9.33：有筛选能力时，点击分类 = 选中并展开 L2；已选中 = 展开/收起 L2
                    if (hasFilters && catId.equals(selectedCategoryId)) {
                        toggleMobileFilterTags();
                        return;
                    }
                    if (selectedCategoryId.equals(catId)) return;
                    selectedCategoryId = catId;
                    selectedFilters.clear();
                    currentPage = 1;
                    refreshChips.run();
                    loadPage.run();
                    if (hasFilters) renderMobileFilterTags();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = a.dp(8);
        row.addView(chip, lp);
    }

    // =========================================================================
    // E3-S1：首页网格虚拟化。头部区块（item 0）与「加载更多」（末尾 item）各占一个
    // 单例 item；影片按「每行 homeCols 张」作为可复用 item，卡片只为可见区 + 预取绑定，
    // 不再一次性 addView 全部海报。
    // =========================================================================
    private final class HomeAdapter extends RecyclerView.Adapter<HomeItemHolder> {
        private final List<Cms.Video> videos = new ArrayList<>();
        private Cms.Site site;
        private int rowCount;

        /** 数据更新（首屏 / 下拉分页 / 换分类）后整体刷新；只重绑可见行。 */
        void submit(List<Cms.Video> all, Cms.Site currentSite) {
            videos.clear();
            if (all != null) videos.addAll(all);
            site = currentSite;
            rowCount = (videos.size() + homeCols - 1) / homeCols;
            notifyDataSetChanged();
            applyHomeRestore();
            logVirtualization();
        }

        @Override
        public int getItemCount() {
            if (cloudInlineActive) return 1; // 云盘配置：只显示 header item（holder 在其中）
            return 2 + rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return HOME_ITEM_HEADER;
            if (position == getItemCount() - 1) return HOME_ITEM_FOOTER;
            return HOME_ITEM_ROW;
        }

        @Override
        public HomeItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == HOME_ITEM_HEADER) {
                FrameLayout wrap = new FrameLayout(a);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new HomeItemHolder(wrap);
            }
            if (viewType == HOME_ITEM_FOOTER) {
                FrameLayout wrap = new FrameLayout(a);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new HomeItemHolder(wrap);
            }
            LinearLayout row = a.row();
            row.setGravity(Gravity.TOP);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new HomeItemHolder(row);
        }

        @Override
        public void onBindViewHolder(HomeItemHolder holder, int position) {
            // 头部与底部的单例内容挂到当前 item 壳里（换壳时先摘父），影片行按行复用绑定。
            if (position == 0) {
                attachHomeSingleton(holder.itemView, homeHeaderBody);
                return;
            }
            if (position == getItemCount() - 1) {
                attachHomeSingleton(holder.itemView, homeFooterWrap);
                return;
            }
            bindHomeRow((LinearLayout) holder.itemView, position - 1);
        }

        /** 首屏数据到达后再恢复锚点（首帧只有头部时先跳过）。 */
        void applyHomeRestore() {
            if (homeRestorePos < 0 || homeList == null) return;
            if (homeRestorePos >= getItemCount()) return;
            RecyclerView.LayoutManager layoutManager = homeList.getLayoutManager();
            if (!(layoutManager instanceof LinearLayoutManager)) return;
            final LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
            final int pos = homeRestorePos;
            final int offset = homeRestoreOffset;
            homeRestorePos = -1;
            homeList.post(
                    () -> {
                        if (homeList == null) return;
                        linear.scrollToPositionWithOffset(pos, offset);
                    });
        }

        /** 运行日志证据：实际绑定的卡片数 = 可见区 + 预取，远小于视频总数（每次数据更新一条）。 */
        void logVirtualization() {
            if (homeList == null) return;
            homeList.post(
                    () -> {
                        if (homeList == null) return;
                        RecyclerView.Adapter<?> bound = homeList.getAdapter();
                        int items = bound == null ? 0 : bound.getItemCount();
                        int rows = 0, cards = 0;
                        for (int i = 0; i < homeList.getChildCount(); i++) {
                            View child = homeList.getChildAt(i);
                            // item 壳是新建的 FrameLayout，头部/底部内容挂在壳里：壳跳过
                            View inner =
                                    child instanceof ViewGroup
                                                    && ((ViewGroup) child).getChildCount() > 0
                                            ? ((ViewGroup) child).getChildAt(0)
                                            : null;
                            if (inner == homeHeaderBody || inner == homeFooterWrap) continue;
                            rows++;
                            if (!(child instanceof ViewGroup)) continue;
                            ViewGroup group = (ViewGroup) child;
                            for (int j = 0; j < group.getChildCount(); j++) {
                                Object tag = group.getChildAt(j).getTag();
                                if ("primary".equals(tag)
                                        || (tag instanceof String
                                                && ((String) tag).startsWith("video:"))) cards++;
                            }
                        }
                        AppLog.i(
                                "MobileShell",
                                "首页虚拟化：视频数="
                                        + videos.size()
                                        + "，列表item数="
                                        + items
                                        + "，可见卡数="
                                        + cards
                                        + "（可见行="
                                        + rows
                                        + "）");
                    });
        }
    }

    /** 把单例头部/底部内容挂到当前 item 壳里；被 RecyclerView 回收换壳时先摘父，避免“child already has a parent”。 */
    private void attachHomeSingleton(View host, View content) {
        if (!(host instanceof ViewGroup) || content == null) return;
        ViewGroup group = (ViewGroup) host;
        if (content.getParent() == group) return;
        if (content.getParent() instanceof ViewGroup)
            ((ViewGroup) content.getParent()).removeView(content);
        group.addView(content, new FrameLayout.LayoutParams(-1, -2));
    }

    /** 绑定一行影片卡：行视图来自 RecyclerView 复用池，卡片只为该行创建。 */
    private void bindHomeRow(LinearLayout row, int rowIndex) {
        row.removeAllViews();
        row.setPadding(
                a.dp(UiTokens.SP_16),
                rowIndex == 0 ? a.dp(UiTokens.SP_2) : 0,
                a.dp(UiTokens.SP_16),
                0);
        for (int col = 0; col < homeCols; col++) {
            int index = rowIndex * homeCols + col;
            if (index < homeAdapter.videos.size()) {
                LinearLayout card = buildVideoCard(homeAdapter.videos.get(index), index);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1);
                cellLp.leftMargin = col == 0 ? 0 : a.dp(5);
                cellLp.rightMargin = col == homeCols - 1 ? 0 : a.dp(5);
                cellLp.topMargin = a.dp(4);
                cellLp.bottomMargin = a.dp(14);
                row.addView(card, cellLp);
            } else {
                LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                blankLp.leftMargin = a.dp(5);
                blankLp.rightMargin = col == homeCols - 1 ? 0 : a.dp(5);
                row.addView(new View(a), blankLp);
            }
        }
    }

    /**
     * 单张 3 列海报卡（与 iPhone `_buildVideoCard` 逐项一致）：`12dp` 圆角 `2:3` 海报 +
     * 底部渐变遮罩与右下 `remarks`，下方两行 `title` + `year · area`。
     */
    private LinearLayout buildVideoCard(Cms.Video v, int index) {
        final Cms.Site site = homeAdapter.site;
        LinearLayout card = a.vertical();
        card.setTag(index == 0 ? "primary" : "video:" + v.id);
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(view -> a.openListing(site, v));

        FrameLayout art = new FrameLayout(a);
        art.setBackground(glassBg(0xff1a2c45, 0xff13233a, a.dp(12), a.dp(0.6f), 0x33ffffff));
        art.setClipToOutline(true);

        // 无海报/加载失败时的占位：深海蓝渐变 + 片名首字
        TextView placeholder =
                a.text(
                        v.title == null || v.title.isEmpty() ? "鲸" : v.title.substring(0, 1),
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

        card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(homePosterHeightDp)));

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
        return card;
    }

    /** 保存首页滚动锚点（position + 顶部偏移），供切换 tab / 进程重建后恢复。 */
    private void captureHomeAnchor() {
        if (homeList == null) return;
        RecyclerView.LayoutManager layoutManager = homeList.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) return;
        LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
        int pos = linear.findFirstVisibleItemPosition();
        if (pos == RecyclerView.NO_POSITION) return;
        View first = linear.findViewByPosition(pos);
        homeAnchorPos = pos;
        homeAnchorOffset = first == null ? 0 : first.getTop();
    }

    /** 任意 RecyclerView 列表的滚动锚点（position + 顶部偏移），给 MainActivity 保存/恢复用。 */
    static int[] captureListAnchor(RecyclerView list) {
        if (list == null) return new int[] {-1, 0};
        RecyclerView.LayoutManager layoutManager = list.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) return new int[] {-1, 0};
        LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
        int pos = linear.findFirstVisibleItemPosition();
        if (pos == RecyclerView.NO_POSITION) return new int[] {-1, 0};
        View first = linear.findViewByPosition(pos);
        return new int[] {pos, first == null ? 0 : first.getTop()};
    }

    /** 恢复锚点；数据未到时短暂重试，避免首帧 item 数不足把滚动位置丢掉。 */
    static void restoreListAnchor(final RecyclerView list, final int pos, final int offset) {
        if (list == null || pos < 0) return;
        list.post(
                new Runnable() {
                    private int tries;

                    @Override
                    public void run() {
                        RecyclerView.LayoutManager layoutManager = list.getLayoutManager();
                        RecyclerView.Adapter<?> adapter = list.getAdapter();
                        if (!(layoutManager instanceof LinearLayoutManager) || adapter == null)
                            return;
                        if (pos >= adapter.getItemCount()) {
                            if (tries++ < 12) list.postDelayed(this, 80);
                            return;
                        }
                        ((LinearLayoutManager) layoutManager)
                                .scrollToPositionWithOffset(pos, offset);
                    }
                });
    }

    /** RecyclerView item 的通用 ViewHolder（头部 / 影片行 / 加载更多共用）。 */
    private static final class HomeItemHolder extends RecyclerView.ViewHolder {
        HomeItemHolder(View itemView) {
            super(itemView);
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
        urlInput.setHintTextColor(a.themeColor(0xff7f90ad));
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
        input.setHintTextColor(a.themeColor(0xff8496b2));
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

        // 1.9.31：来源筛选固定行（历史之下、统计行之上），独立于结果 adapter
        final HorizontalScrollView mobileFilterScroll = new HorizontalScrollView(a);
        mobileFilterScroll.setHorizontalScrollBarEnabled(false);
        mobileFilterScroll.setClipChildren(false);
        mobileFilterScroll.setClipToPadding(false);
        final LinearLayout mobileFilterRow = a.row();
        mobileFilterRow.setPadding(a.dp(2), a.dp(6), a.dp(2), a.dp(2));
        mobileFilterScroll.addView(mobileFilterRow);
        page.addView(mobileFilterScroll, new LinearLayout.LayoutParams(-1, a.dp(46)));
        final java.util.Set<String> failedSourceNames = new java.util.LinkedHashSet<>();
        final Runnable[] renderMobileFilters = new Runnable[1];

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
                    String head =
                            separator < 0
                                    ? cachedSearchStatus
                                    : cachedSearchStatus.substring(0, separator);
                    // 1.9.32：有来源筛选时统计数字按过滤后的可见条目重算
                    if (!cachedSearchSourceFilter.isEmpty()) {
                        int visible = searchAdapter.visibleCount();
                        head = head.replaceFirst("已找到 \\d+ 条结果", "已找到 " + visible + " 条结果");
                        if (head.isEmpty() || !head.startsWith("已找到"))
                            head =
                                    "已筛选 "
                                            + cachedSearchSourceFilter.size()
                                            + " 个来源 · 找到 "
                                            + visible
                                            + " 条结果";
                    }
                    searchStatus.setText(head);
                    sourceNotices.setText(
                            separator < 0 ? "" : cachedSearchStatus.substring(separator + 1));
                    sourceNotices.setVisibility(separator < 0 ? View.GONE : View.VISIBLE);
                };

        // 1.9.31：手机来源筛选行渲染（全部 / 各源数量 / 失败 / 清除）
        final Runnable buildMobileFilters =
                () -> {
                    mobileFilterRow.removeAllViews();
                    TextView label = a.text("来源筛选：", 12, MainActivity.MUTED);
                    label.setPadding(0, 0, a.dp(4), 0);
                    mobileFilterRow.addView(label);
                    java.util.Map<String, Cms.Site> sites = searchAdapter.sourceMap();
                    if (sites.isEmpty()) {
                        TextView empty = a.text("搜索后显示来源", 11.5f, MainActivity.MUTED);
                        mobileFilterRow.addView(empty);
                        return;
                    }
                    java.util.List<Cms.Site> ordered = new java.util.ArrayList<>(sites.values());
                    java.util.Collections.sort(
                            ordered,
                            (x, y) ->
                                    Integer.compare(
                                            searchAdapter.countFor(y.key),
                                            searchAdapter.countFor(x.key)));
                    android.view.View all =
                            mobileFilterChip(
                                    "全部",
                                    cachedSearchSourceFilter.isEmpty(),
                                    MainActivity.WHITE,
                                    () -> {
                                        cachedSearchSourceFilter.clear();
                                        searchAdapter.setFilter(cachedSearchSourceFilter);
                                        renderMobileFilters[0].run();
                                        renderSearchStatus.run();
                                    });
                    mobileFilterRow.addView(all, mobileFilterChipLp());
                    int mobileChips = 0;
                    for (final Cms.Site site : ordered) {
                        int count = searchAdapter.countFor(site.key);
                        boolean selected = cachedSearchSourceFilter.contains(site.key);
                        boolean failed = failedSourceNames.contains(site.name);
                        String text =
                                site.name
                                        + (count > 0 ? " " + count : "")
                                        + (failed ? " 失败" : "");
                        android.view.View chip =
                                mobileFilterChip(
                                        text,
                                        selected,
                                        failed && count == 0 ? 0xff9aa3b2 : 0xffc2cbd7,
                                        () -> {
                                            if (!cachedSearchSourceFilter.remove(site.key))
                                                cachedSearchSourceFilter.add(site.key);
                                            searchAdapter.setFilter(cachedSearchSourceFilter);
                                            renderMobileFilters[0].run();
                                            renderSearchStatus.run();
                                        });
                        mobileFilterRow.addView(chip, mobileFilterChipLp());
                        mobileChips++;
                    }
                    if (!cachedSearchSourceFilter.isEmpty()) {
                        TextView clear = a.text("清除", 12, 0xffff8fb1);
                        clear.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
                        MobileDetailScreen.bindTouchFeedback(clear);
                        clear.setFocusable(true);
                        clear.setOnClickListener(
                                v -> {
                                    cachedSearchSourceFilter.clear();
                                    searchAdapter.setFilter(cachedSearchSourceFilter);
                                    renderMobileFilters[0].run();
                                    renderSearchStatus.run();
                                });
                        mobileFilterRow.addView(clear, mobileFilterChipLp());
                    }
                    AppLog.i(
                            "SearchFilter",
                            "mobile chips="
                                    + mobileChips
                                    + " sources="
                                    + cachedSearchSourceFilter
                                    + " failed="
                                    + failedSourceNames);
                };
        renderMobileFilters[0] = buildMobileFilters;

        clearBtn.setOnClickListener(
                v -> {
                    searchSerial[0]++;
                    a.cancelPageRequests();
                    input.setText("");
                    cachedSearchKeyword = "";
                    cachedSearchStatus = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    cachedSearchSourceFilter.clear();
                    failedSourceNames.clear();
                    searchAdapter.clear();
                    sourceNotices.setText("");
                    sourceNotices.setVisibility(View.GONE);
                    searchStatus.setText("输入关键词搜索全部已启用的片源");
                    renderMobileFilters[0].run();
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
                    // 1.9.31：新搜索先清空来源筛选/失败源（手动搜索、历史词、清空后重搜均走这里）
                    cachedSearchSourceFilter.clear();
                    failedSourceNames.clear();
                    renderMobileFilters[0].run();
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
                    cachedSearchSourceFilter.clear();
                    failedSourceNames.clear();
                    renderMobileFilters[0].run();

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
                                                        if (!sourceNotice.isEmpty()) {
                                                            searchNotices.add(
                                                                    site.name + "：" + sourceNotice);
                                                            failedSourceNames.add(site.name);
                                                        }
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
                                                                                    "；",
                                                                                    searchNotices);
                                                        searchAdapter.setFilter(
                                                                cachedSearchSourceFilter);
                                                        renderMobileFilters[0].run();
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
        renderMobileFilters[0].run();
        if (!pendingSearchKeyword.isEmpty()) {
            String wantedKw = pendingSearchKeyword;
            pendingSearchKeyword = "";
            input.setText(wantedKw);
            input.setSelection(wantedKw.length());
            if (wantedKw.equals(cachedSearchKeyword) && !cachedSearchHits.isEmpty()) {
                searchAdapter.setHits(cachedSearchHits);
                searchAdapter.setFilter(cachedSearchSourceFilter);
                if (cachedSearchStatus.isEmpty())
                    cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
                renderSearchStatus.run();
                renderMobileFilters[0].run();
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
            searchAdapter.setFilter(cachedSearchSourceFilter);
            if (cachedSearchStatus.isEmpty())
                cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
            renderSearchStatus.run();
            renderMobileFilters[0].run();
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
        /** 过滤+合并后用于展示的列表。 */
        private final List<CachedSearchHit> hits = new ArrayList<>();
        /** 1.9.32：原始结果（每条只属于一个来源），筛选在合并之前作用于它。 */
        private final List<CachedSearchHit> raw = new ArrayList<>();
        private final java.util.Set<String> filter = new java.util.HashSet<>();

        SearchResultsAdapter(TextView notices) {
            this.notices = notices;
            if (notices.getParent() instanceof ViewGroup)
                ((ViewGroup) notices.getParent()).removeView(notices);
        }

        void clear() {
            raw.clear();
            hits.clear();
            notifyDataSetChanged();
        }

        void setHits(List<CachedSearchHit> source) {
            raw.clear();
            for (CachedSearchHit hit : source) raw.add(hit);
            rebuild();
        }

        void add(CachedSearchHit hit) {
            raw.add(hit);
            rebuild();
        }

        void setFilter(java.util.Set<String> keys) {
            filter.clear();
            filter.addAll(keys);
            rebuild();
        }

        int visibleCount() {
            return hits.size();
        }

        java.util.Map<String, Cms.Site> sourceMap() {
            java.util.Map<String, Cms.Site> map = new java.util.LinkedHashMap<>();
            for (CachedSearchHit hit : raw)
                for (Cms.Site site : hit.sites) map.put(site.key, site);
            return map;
        }

        int countFor(String key) {
            int count = 0;
            for (CachedSearchHit hit : raw)
                for (Cms.Site site : hit.sites)
                    if (site.key.equals(key)) {
                        count++;
                        break;
                    }
            return count;
        }

        /** 1.9.32：先按来源 key 过滤原始结果，再做标题合并；卡片 sources 只统计选中来源。 */
        private void rebuild() {
            List<CachedSearchHit> base = new ArrayList<>();
            for (CachedSearchHit hit : raw) {
                if (filter.isEmpty()) {
                    base.add(hit);
                    continue;
                }
                for (Cms.Site site : hit.sites)
                    if (filter.contains(site.key)) {
                        base.add(hit);
                        break;
                    }
            }
            hits.clear();
            for (CachedSearchHit hit : base) {
                String key =
                        SearchAggregator.normalize(hit.video == null ? "" : hit.video.title);
                CachedSearchHit merged = null;
                if (!key.isEmpty()) {
                    for (CachedSearchHit existing : hits)
                        if (key.equals(
                                SearchAggregator.normalize(
                                        existing.video == null ? "" : existing.video.title))) {
                            merged = existing;
                            break;
                        }
                }
                if (merged == null) {
                    merged = new CachedSearchHit(hit.site, hit.video);
                    hits.add(merged);
                } else {
                    for (Cms.Site site : hit.sites) {
                        boolean has = false;
                        for (Cms.Site existingSite : merged.sites)
                            if (existingSite.key.equals(site.key)) {
                                has = true;
                                break;
                            }
                        if (!has) merged.sites.add(site);
                    }
                    merged.sources = merged.sites.size();
                }
            }
            AppLog.i(
                    "SearchFilter",
                    "applied filter="
                            + filter
                            + " hits before="
                            + raw.size()
                            + " after="
                            + hits.size());
            notifyDataSetChanged();
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
                    buildSearchResultRow(hit),
                    new FrameLayout.LayoutParams(-1, -2));
        }
    }

    /** 1.9.31：手机来源筛选 chip。 */
    private TextView mobileFilterChip(
            String label, boolean selected, int textColor, final Runnable onClick) {
        TextView chip = a.text(selected ? "✓ " + label : label, 12, selected ? 0xff0b1220 : textColor);
        chip.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        if (selected)
            chip.setBackground(a.bg(0xffff8fb1, 999));
        else
            chip.setBackground(glassBg(0x14ffffff, 0x33ffffff, a.dp(999), a.dp(0.8f), 0x33ffffff));
        MobileDetailScreen.bindTouchFeedback(chip);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private LinearLayout.LayoutParams mobileFilterChipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(34));
        lp.setMargins(a.dp(2), 0, a.dp(6), 0);
        return lp;
    }

    private View buildSearchResultRow(final CachedSearchHit hit) {
        final Cms.Site site = hit.site;
        final Cms.Video v = hit.video;
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
        row.setOnLongClickListener(
                view -> {
                    // 1.8.1 #6：长按选择来源（同名片合并后的真实来源列表）
                    String key = SearchAggregator.normalize(v.title);
                    java.util.List<CachedSearchHit> matched = new java.util.ArrayList<>();
                    for (CachedSearchHit candidate : cachedSearchHits)
                        if (key.equals(
                                SearchAggregator.normalize(
                                        candidate.video == null ? "" : candidate.video.title)))
                            matched.add(candidate);
                    if (matched.size() <= 1) return false;
                    String[] names = new String[matched.size()];
                    for (int i = 0; i < matched.size(); i++)
                        names[i] =
                                matched.get(i).site.name
                                        + (matched.get(i).site.key.equals(site.key)
                                                ? "（当前）"
                                                : "");
                    new AppDialog.Builder(a)
                            .setTitle("选择来源 · " + v.title)
                            .setItems(
                                    names,
                                    (d, index) -> {
                                        CachedSearchHit chosen = matched.get(index);
                                        activeTab = 1;
                                        a.openListing(chosen.site, chosen.video);
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
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

        TextView badge =
                a.text(
                        hit.sources > 1
                                ? hit.sources + " 个来源 · 当前：" + site.name
                                : "来源：" + site.name,
                        12,
                        0xffff8fb1);
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
        searchInput.setHintTextColor(a.themeColor(0xff8496b2));
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

        final LinearLayout liveStateWrap = a.vertical();
        liveStateWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        final WhaleLoadingView liveWhale =
                new WhaleLoadingView(a, WhaleLoadingView.SMALL, true);
        liveWhale.setText("正在加载直播频道…");
        liveStateWrap.addView(liveWhale);
        final TextView stateView = a.text("正在加载直播频道…", 12, MainActivity.MUTED);
        stateView.setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(16));
        liveStateWrap.addView(stateView, new LinearLayout.LayoutParams(-1, -2));
        page.addView(liveStateWrap);

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
                    liveWhale.setText("正在加载频道列表…");
                    liveWhale.start();
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
                                                liveWhale.stop();
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
                                                liveWhale.setText("加载失败");
                                                liveWhale.stop();
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
        a.gap(page, 8);

        final MyListAdapter[] adapterRef = {null};

        // 1.8.6 本地搜索：收藏/历史内关键词过滤
        LinearLayout searchRow = a.row();
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText librarySearch = new EditText(a);
        librarySearch.setSingleLine(true);
        librarySearch.setHint("搜索片名 / 来源");
        librarySearch.setHintTextColor(a.themeColor(MainActivity.MUTED));
        librarySearch.setTextColor(MainActivity.WHITE);
        librarySearch.setTextSize(13);
        librarySearch.setBackground(
                glassBg(0xcc172842, 0xcc182b4c, a.dp(999), a.dp(0.7f), 0x38ffffff));
        librarySearch.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        searchRow.addView(librarySearch, new LinearLayout.LayoutParams(0, a.dp(40), 1f));
        final TextView clearSearch = a.text("×", 16, 0xffef4444);
        clearSearch.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        MobileDetailScreen.bindTouchFeedback(clearSearch);
        searchRow.addView(clearSearch);
        page.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
        Runnable clearSearchAction =
                () -> {
                    librarySearch.setText("");
                    if (adapterRef[0] != null) adapterRef[0].setQuery("");
                };
        clearSearch.setOnClickListener(v -> clearSearchAction.run());
        librarySearch.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}

                    public void onTextChanged(CharSequence s, int a1, int b, int c) {}

                    public void afterTextChanged(android.text.Editable s) {
                        if (adapterRef[0] != null) adapterRef[0].setQuery(s.toString());
                        clearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                    }
                });
        clearSearch.setVisibility(View.GONE);

        // 1.8.6 收藏分组胶囊（仅收藏页签）
        final LinearLayout groupBar = a.row();
        HorizontalScrollView groupScroll = new HorizontalScrollView(a);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.addView(groupBar);
        final Runnable[] renderGroups = new Runnable[1];
        renderGroups[0] =
                () -> {
                    groupBar.removeAllViews();
                    String current = adapterRef[0] == null ? "" : adapterRef[0].groupFilter;
                    java.util.LinkedHashMap<String, String> chips = new java.util.LinkedHashMap<>();
                    chips.put("全部", "");
                    chips.put("默认", "__default");
                    for (String g : favoriteGroups(a)) chips.put(g, g);
                    for (java.util.Map.Entry<String, String> entry : chips.entrySet()) {
                        TextView chip = a.text(entry.getKey(), 12, MainActivity.WHITE);
                        chip.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
                        chip.setBackground(
                                glassBg(
                                        entry.getValue().equals(current)
                                                ? 0xffffffff
                                                : 0xcc172842,
                                        entry.getValue().equals(current)
                                                ? 0xffe9eef9
                                                : 0xcc182b4c,
                                        a.dp(999),
                                        a.dp(0.7f),
                                        entry.getValue().equals(current)
                                                ? 0xffffffff
                                                : 0x38ffffff));
                        chip.setTextColor(
                                entry.getValue().equals(current) ? 0xff000000 : a.userTextColor(0xffe6edf9));
                        chip.setFocusable(true);
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    if (adapterRef[0] != null)
                                        adapterRef[0].setGroupFilter(entry.getValue());
                                    renderGroups[0].run();
                                });
                        LinearLayout.LayoutParams lp =
                                new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(6);
                        groupBar.addView(chip, lp);
                    }
                    TextView manage = a.text("管理分组", 12, 0xffffb3c8);
                    manage.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
                    MobileDetailScreen.bindTouchFeedback(manage);
                    manage.setOnClickListener(v -> manageFavoriteGroups(a, renderGroups[0]));
                    groupBar.addView(manage);
                };
        page.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));
        groupScroll.setVisibility(View.GONE);

        // 1.8.6 多选操作栏
        final LinearLayout selectBar = a.row();
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(0, a.dp(4), 0, a.dp(4));
        final TextView selectStatus = a.text("已选 0 项", 12, MainActivity.MUTED);
        selectBar.addView(selectStatus, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView selectAllBtn = a.text("全选", 12, MainActivity.WHITE);
        final TextView batchBtn = a.text("删除", 12, 0xffef4444);
        final TextView moveBtn = a.text("移动分组", 12, 0xffffb3c8);
        final TextView exitSelect = a.text("退出", 12, MainActivity.MUTED);
        for (TextView b : new TextView[] {selectAllBtn, batchBtn, moveBtn, exitSelect}) {
            b.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            b.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = a.dp(6);
            selectBar.addView(b, lp);
        }
        page.addView(selectBar, new LinearLayout.LayoutParams(-1, -2));
        selectBar.setVisibility(View.GONE);

        // RecyclerView：长列表虚拟化（观看历史 / 我的收藏）
        final FrameLayout listHost = new FrameLayout(a);
        page.addView(listHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Runnable[] updateSelectBar = new Runnable[1];
        updateSelectBar[0] =
                () -> {
                    MyListAdapter ad = adapterRef[0];
                    boolean sel = ad != null && ad.selecting;
                    librarySelecting = sel;
                    selectBar.setVisibility(sel ? View.VISIBLE : View.GONE);
                    if (sel) {
                        selectStatus.setText("已选 " + ad.selectedCount() + " 项");
                        batchBtn.setText(showFavorites[0] ? "取消收藏" : "删除");
                        moveBtn.setVisibility(showFavorites[0] ? View.VISIBLE : View.GONE);
                    }
                };
        exitSelectionAction =
                () -> {
                    if (adapterRef[0] != null) adapterRef[0].exitSelecting();
                };
        selectAllBtn.setOnClickListener(
                v -> {
                    if (adapterRef[0] != null) adapterRef[0].selectAll();
                });
        exitSelect.setOnClickListener(
                v -> {
                    if (adapterRef[0] != null) adapterRef[0].exitSelecting();
                });
        batchBtn.setOnClickListener(
                v -> {
                    MyListAdapter ad = adapterRef[0];
                    if (ad == null || ad.selectedCount() == 0) return;
                    final boolean fav = showFavorites[0];
                    final String rawKey = fav ? Prefs.FAVORITES : Prefs.WATCH_HISTORY;
                    final String snapshot = a.prefs.getString(rawKey, "[]");
                    final int n = ad.selectedCount();
                    final java.util.Set<String> keys =
                            new java.util.LinkedHashSet<>(ad.selected);
                    new AppDialog.Builder(a)
                            .setTitle(fav ? "取消收藏？" : "删除观看记录？")
                            .setMessage("将对 " + n + " 项执行操作，可撤销。")
                            .setPositiveButton(
                                    fav ? "取消收藏" : "删除",
                                    (d, i) -> {
                                        if (fav) {
                                            a.prefs.edit()
                                                    .putString(
                                                            Prefs.FAVORITES,
                                                            Favorites.removeKeys(snapshot, keys))
                                                    .apply();
                                        } else {
                                            String hist = snapshot;
                                            for (WatchHistory.Entry e :
                                                    WatchHistory.read(snapshot)) {
                                                if (keys.contains(e.key()))
                                                    hist = WatchHistory.removeGroup(hist, e);
                                            }
                                            a.prefs.edit()
                                                    .putString(Prefs.WATCH_HISTORY, hist)
                                                    .apply();
                                        }
                                        ad.exitSelecting();
                                        ad.reload();
                                        new AppDialog.Builder(a)
                                                .setTitle("已处理 " + n + " 项")
                                                .setPositiveButton(
                                                        "撤销",
                                                        (dd, ii) -> {
                                                            a.prefs.edit()
                                                                    .putString(rawKey, snapshot)
                                                                    .apply();
                                                            ad.reload();
                                                        })
                                                .setNegativeButton("知道了", null)
                                                .show();
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });
        moveBtn.setOnClickListener(
                v -> {
                    MyListAdapter ad = adapterRef[0];
                    if (ad == null || ad.selectedCount() == 0) return;
                    java.util.List<String> groups = favoriteGroups(a);
                    String[] options = new String[groups.size() + 1];
                    options[0] = "默认分组";
                    for (int i = 0; i < groups.size(); i++) options[i + 1] = groups.get(i);
                    final java.util.Set<String> keys = new java.util.LinkedHashSet<>(ad.selected);
                    new AppDialog.Builder(a)
                            .setTitle("移动 " + keys.size() + " 项到…")
                            .setItems(
                                    options,
                                    (d, index) -> {
                                        String group = index == 0 ? "" : options[index];
                                        a.prefs.edit()
                                                .putString(
                                                        Prefs.FAVORITES,
                                                        Favorites.moveGroup(
                                                                a.prefs.getString(
                                                                        Prefs.FAVORITES, "[]"),
                                                                keys,
                                                                group))
                                                .apply();
                                        ad.exitSelecting();
                                        ad.reload();
                                        renderGroups[0].run();
                                        a.toast("已移动到" + (index == 0 ? "默认分组" : "「" + group + "」"));
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });

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
                        searchRow.setVisibility(downMode ? View.GONE : View.VISIBLE);
                        groupScroll.setVisibility(favMode ? View.VISIBLE : View.GONE);
                        if (adapterRef[0] != null) adapterRef[0].exitSelecting();
                        listHost.removeAllViews();
                        if (librarySearch.length() > 0) {
                            librarySearch.setText("");
                            if (adapterRef[0] != null) adapterRef[0].setQuery("");
                        }

                        if (downMode) {
                            ScrollView sv = new ScrollView(a);
                            sv.setFillViewport(true);
                            sv.setVerticalScrollBarEnabled(false);
                            sv.setClipToPadding(false);
                            LinearLayout body = a.vertical();
                            body.setPadding(0, 0, 0, a.dp(106));
                            int downloadW =
                                    Math.min(
                                            a.windowWidth() > 0 ? a.windowWidth() : a.usableWidth,
                                            a.dp(760));
                            sv.addView(body, new ScrollView.LayoutParams(downloadW, -2));
                            MobileDownloads.render(a, body);
                            listHost.addView(sv, new FrameLayout.LayoutParams(-1, -1));
                            return;
                        }

                        final boolean favModeFinal = favMode;
                        listHost.post(
                                () -> {
                                    buildLibraryList(
                                            listHost,
                                            favModeFinal,
                                            adapterRef,
                                            updateSelectBar[0],
                                            clearSearchAction);
                                    renderGroups[0].run();
                                });
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

    /** 片库列表：手机单列行，平板/TV 自适应网格（列数按内容宽度计算）。 */
    private void buildLibraryList(
            FrameLayout host,
            boolean favoritesMode,
            MyListAdapter[] adapterRef,
            Runnable onSelection,
            Runnable onClearQuery) {
        int avail = host.getWidth();
        if (avail <= 0) avail = a.usableWidth > 0 ? a.usableWidth : a.windowWidth();
        boolean phone = a.formFactor == TvLayoutPolicy.FORM_PHONE;
        int sidePad = a.dp(phone ? 16 : 20);
        int gap = a.dp(phone ? 12 : 14);
        int cols = 1;
        int widthDp = Math.round(avail / a.getResources().getDisplayMetrics().density);
        if (!phone) {
            if (widthDp >= 1080) cols = 5;
            else if (widthDp >= 840) cols = 4;
            else if (widthDp >= 600) cols = 3;
        }
        int cardW = cols > 1 ? (avail - sidePad * 2 - gap * (cols - 1)) / cols : avail;
        RecyclerView rv = new RecyclerView(a);
        final MyListAdapter adapter = new MyListAdapter(favoritesMode, cols, cardW, gap);
        adapter.onSelectionChanged = onSelection;
        adapter.onClearQuery = onClearQuery;
        if (cols > 1) {
            final int spanCount = cols;
            GridLayoutManager glm = new GridLayoutManager(a, spanCount);
            glm.setSpanSizeLookup(
                    new GridLayoutManager.SpanSizeLookup() {
                        @Override
                        public int getSpanSize(int position) {
                            return adapter.getItemViewType(position) == 2 ? spanCount : 1;
                        }
                    });
            rv.setLayoutManager(glm);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(a));
        }
        rv.setClipToPadding(false);
        rv.setPadding(sidePad, 0, sidePad, a.dp(106));
        rv.setAdapter(adapter);
        if (adapterRef != null) adapterRef[0] = adapter;
        host.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        adapter.reload();
    }

    /** 片库列表适配器：观看历史 / 我的收藏；手机为行布局，平板/TV 为 2:3 封面网格。 */
    private final class MyListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final boolean favoritesMode;
        private final int cols, cardW, gap;
        private List<WatchHistory.Entry> history = new ArrayList<>();
        private List<Favorites.Entry> favorites = new ArrayList<>();
        private boolean empty;
        String query = "";
        String groupFilter = "";
        boolean selecting;
        final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        Runnable onSelectionChanged;

        MyListAdapter(boolean favoritesMode, int cols, int cardW, int gap) {
            this.favoritesMode = favoritesMode;
            this.cols = cols;
            this.cardW = cardW;
            this.gap = gap;
        }

        void setQuery(String value) {
            String next = value == null ? "" : value.trim();
            if (next.equals(query)) return;
            query = next;
            selected.clear();
            selecting = false;
            reload();
        }

        void setGroupFilter(String value) {
            String next = value == null ? "" : value;
            if (next.equals(groupFilter)) return;
            groupFilter = next;
            selected.clear();
            reload();
        }

        void reload() {
            if (favoritesMode) {
                favorites = Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
                if (!groupFilter.isEmpty()) {
                    for (java.util.Iterator<Favorites.Entry> it = favorites.iterator();
                            it.hasNext(); ) {
                        Favorites.Entry f = it.next();
                        String g = f.group == null ? "" : f.group;
                        boolean match =
                                "__default".equals(groupFilter)
                                        ? g.isEmpty()
                                        : groupFilter.equals(g);
                        if (!match) it.remove();
                    }
                }
                if (!query.isEmpty()) {
                    for (java.util.Iterator<Favorites.Entry> it = favorites.iterator();
                            it.hasNext(); ) {
                        Favorites.Entry f = it.next();
                        if (!f.title.toLowerCase(java.util.Locale.ROOT)
                                .contains(query.toLowerCase(java.util.Locale.ROOT)))
                            it.remove();
                    }
                }
                empty = favorites.isEmpty();
            } else {
                history = WatchHistory.collapsed(a.watchHistory());
                if (!query.isEmpty()) {
                    for (java.util.Iterator<WatchHistory.Entry> it = history.iterator();
                            it.hasNext(); ) {
                        WatchHistory.Entry e = it.next();
                        String text =
                                ((e.title == null ? "" : e.title)
                                                + " "
                                                + (e.site == null ? "" : e.site))
                                        .toLowerCase(java.util.Locale.ROOT);
                        if (!text.contains(query.toLowerCase(java.util.Locale.ROOT)))
                            it.remove();
                    }
                }
                empty = history.isEmpty();
            }
            notifyDataSetChanged();
        }

        int selectedCount() {
            return selected.size();
        }

        void toggleSelect(String key) {
            if (!selected.remove(key)) selected.add(key);
            selecting = !selected.isEmpty();
            notifyDataSetChanged();
            if (onSelectionChanged != null) onSelectionChanged.run();
        }

        void selectAll() {
            if (empty) return;
            selected.clear();
            for (int i = 0; i < getItemCount(); i++) {
                if (favoritesMode) selected.add(favorites.get(i).key());
                else selected.add(history.get(i).key());
            }
            selecting = !selected.isEmpty();
            notifyDataSetChanged();
            if (onSelectionChanged != null) onSelectionChanged.run();
        }

        void exitSelecting() {
            selected.clear();
            selecting = false;
            notifyDataSetChanged();
            if (onSelectionChanged != null) onSelectionChanged.run();
        }

        String keyAt(int position) {
            return favoritesMode ? favorites.get(position).key() : history.get(position).key();
        }

        @Override
        public int getItemCount() {
            if (empty) return 1;
            return favoritesMode ? favorites.size() : history.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (empty) return 2;
            return cols > 1 ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 2) return buildEmpty();
            RowHolder holder = viewType == 0 ? buildRow() : buildCard();
            RecyclerView.LayoutParams lp =
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (viewType == 1) {
                lp.rightMargin = gap;
                lp.bottomMargin = gap;
            }
            holder.itemView.setLayoutParams(lp);
            return holder;
        }

        /** 手机：横向行 100dp 高，缩略图 56×80。 */
        private RowHolder buildRow() {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(10), a.dp(8), a.dp(12), a.dp(8));
            row.setBackground(
                    glassBg(0xcc1e3557, 0xcc17294a, a.dp(16), a.dp(0.7f), 0x33ffffff));
            MobileDetailScreen.bindTouchFeedback(row);
            row.setFocusable(true);
            RowHolder holder = new RowHolder(row);

            ImageView thumb = new ImageView(a);
            holder.thumb = thumb;
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(a.bg(0xff1b2e48, 10));
            thumb.setClipToOutline(true);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(a.dp(56), a.dp(80));
            thumbLp.rightMargin = a.dp(12);
            row.addView(thumb, thumbLp);

            LinearLayout info = a.vertical();
            TextView title = a.text("", 15, MainActivity.WHITE);
            holder.title = title;
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title);
            TextView sub = a.text("", 12, MainActivity.MUTED);
            holder.sub = sub;
            sub.setPadding(0, a.dp(6), 0, 0);
            info.addView(sub);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            TextView action = a.text("", 12, 0xffef4444);
            holder.action = action;
            action.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            MobileDetailScreen.bindTouchFeedback(action);
            row.addView(action);
            return holder;
        }

        /** 平板/TV：2:3 封面 + 固定两行标题 + meta + 封面底部进度。 */
        private RowHolder buildCard() {
            LinearLayout card = a.vertical();
            RecyclerView.LayoutParams cardLp =
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.rightMargin = gap;
            cardLp.bottomMargin = gap;
            card.setLayoutParams(cardLp);

            RowHolder holder = new RowHolder(card);
            int coverH = Math.round(cardW * 1.5f);

            FrameLayout coverWrap = new FrameLayout(a);
            ImageView cover = new ImageView(a);
            holder.thumb = cover;
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(a.bg(0xff1b2e48, 12));
            cover.setClipToOutline(true);
            coverWrap.addView(cover, new FrameLayout.LayoutParams(-1, -1));

            View track = new View(a);
            track.setBackgroundColor(0x4dffffff);
            FrameLayout.LayoutParams trackLp =
                    new FrameLayout.LayoutParams(-1, a.dp(3), Gravity.BOTTOM);
            coverWrap.addView(track, trackLp);
            final View progress = new View(a);
            progress.setBackgroundColor(UiTokens.ACCENT);
            FrameLayout.LayoutParams progressLp =
                    new FrameLayout.LayoutParams(0, a.dp(3), Gravity.BOTTOM);
            coverWrap.addView(progress, progressLp);
            holder.progress = progress;

            TextView badge = a.text(favoritesMode ? "♥" : "✕", 12, Color.WHITE);
            holder.action = badge;
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(UiTokens.round(a, a.dp(12), 0x99000000));
            FrameLayout.LayoutParams badgeLp =
                    new FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.TOP | Gravity.RIGHT);
            badgeLp.topMargin = a.dp(6);
            badgeLp.rightMargin = a.dp(6);
            coverWrap.addView(badge, badgeLp);
            card.addView(coverWrap, new LinearLayout.LayoutParams(-1, coverH));

            TextView title = a.text("", 15, MainActivity.WHITE);
            holder.title = title;
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, a.dp(46));
            titleLp.topMargin = a.dp(8);
            card.addView(title, titleLp);

            TextView meta = a.text("", 11.5f, MainActivity.MUTED);
            holder.sub = meta;
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(meta, new LinearLayout.LayoutParams(-1, a.dp(18)));
            card.setTag("library-card");
            card.setFocusable(true);
            return holder;
        }

        /** 空状态：居中提示 + 去首页按钮。 */
        private RowHolder buildEmpty() {
            LinearLayout box = a.vertical();
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(a.dp(16), a.dp(64), a.dp(16), a.dp(64));
            FrameLayout iconWrap = new FrameLayout(a);
            iconWrap.setBackground(glassBg(0x33ffffff, 0x1affffff, a.dp(32), a.dp(0.8f), 0x33ffffff));
            ImageView icon = new ImageView(a);
            android.graphics.drawable.Drawable glyph = UiIcon.drawable(a, UiIcon.EPISODES);
            glyph.setTint(UiTokens.ACCENT_SOFT);
            icon.setImageDrawable(glyph);
            FrameLayout.LayoutParams iconLp =
                    new FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.CENTER);
            iconWrap.addView(icon, iconLp);
            box.addView(iconWrap, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
            TextView title =
                    a.text(
                            !query.isEmpty()
                                    ? "没有匹配的收藏/历史"
                                    : (favoritesMode ? "还没有收藏任何影片" : "还没有观看记录"),
                            17,
                            MainActivity.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
            titleLp.topMargin = a.dp(14);
            box.addView(title, titleLp);
            TextView desc =
                    a.text(
                            !query.isEmpty()
                                    ? "换个关键词，或点下方「清除搜索」恢复"
                                    : (favoritesMode
                                            ? "在影片详情页点「收藏」，这里就会显示"
                                            : "看过的影片会自动记录，方便继续播放"),
                            13,
                            MainActivity.MUTED);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-2, -2);
            descLp.topMargin = a.dp(8);
            box.addView(desc, descLp);
            TextView go = a.text(!query.isEmpty() ? "清除搜索" : "去首页看看", 13, 0xffffb3c8);
            go.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
            go.setBackground(glassBg(0x24ff8fb1, 0x1affffff, 999, a.dp(0.8f), 0x40ffb3c8));
            MobileDetailScreen.bindTouchFeedback(go);
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(-2, -2);
            goLp.topMargin = a.dp(18);
            go.setOnClickListener(
                    v -> {
                        if (!query.isEmpty()) {
                            setQuery("");
                            if (onClearQuery != null) onClearQuery.run();
                        } else switchTab(0);
                    });
            box.addView(go, goLp);
            RowHolder holder = new RowHolder(box);
            holder.emptyRow = true;
            return holder;
        }

        Runnable onClearQuery;

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            RowHolder h = (RowHolder) holder;
            if (h.emptyRow || empty) return;
            if (h.title == null || h.sub == null) return;
            if (favoritesMode) {
                final Favorites.Entry f = favorites.get(position);
                bindSelection(h, f.key());
                h.title.setText((selected.contains(f.key()) ? "✓ " : "") + f.title);
                h.sub.setText("已收藏 · 点击查看详情");
                if (h.progress != null) h.progress.setVisibility(View.GONE);
                if (f.poster != null && !f.poster.isEmpty()) a.poster(f.poster, h.thumb);
                h.itemView.setOnLongClickListener(
                        v -> {
                            toggleSelect(f.key());
                            return true;
                        });
                if (selecting) {
                    h.itemView.setOnClickListener(v -> toggleSelect(f.key()));
                } else {
                    h.itemView.setOnClickListener(v -> openFavorite(f));
                }
                if (h.action != null) {
                    h.action.setVisibility(selecting ? View.GONE : View.VISIBLE);
                    if (!selecting) {
                        h.action.setOnClickListener(v -> removeFavorite(f));
                        if (cols == 1) {
                            h.action.setText("详情");
                            h.action.setTextColor(a.userTextColor(0xffc9d4e6));
                        }
                    }
                }
            } else {
                final WatchHistory.Entry e = history.get(position);
                bindSelection(h, e.key());
                h.title.setText((selected.contains(e.key()) ? "✓ " : "") + e.title);
                h.sub.setText(a.historyLabel(e));
                if (e.poster != null && !e.poster.isEmpty()) a.poster(e.poster, h.thumb);
                if (h.progress != null) {
                    int total = Math.max(1, e.duration);
                    float frac = Math.max(0f, Math.min(1f, (float) e.position / total));
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) h.progress.getLayoutParams();
                    lp.width = Math.round(cardW * frac);
                    h.progress.setLayoutParams(lp);
                }
                h.itemView.setOnLongClickListener(
                        v -> {
                            toggleSelect(e.key());
                            return true;
                        });
                if (selecting) {
                    h.itemView.setOnClickListener(v -> toggleSelect(e.key()));
                } else {
                    h.itemView.setOnClickListener(v -> a.resumeHistory(e));
                }
                if (h.action != null) {
                    h.action.setVisibility(selecting ? View.GONE : View.VISIBLE);
                    if (!selecting) {
                        h.action.setOnClickListener(v -> removeHistory(e));
                        if (cols == 1) {
                            h.action.setText("删除");
                            h.action.setTextColor(0xffef4444);
                        }
                    }
                }
            }
        }

        private void bindSelection(RowHolder h, String key) {
            h.itemView.setAlpha(selected.contains(key) ? 0.72f : 1f);
        }

        private void openFavorite(Favorites.Entry f) {
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
        }

        private void removeFavorite(Favorites.Entry f) {
            a.prefs.edit()
                    .putString(
                            Prefs.FAVORITES,
                            Favorites.toggle(
                                    a.prefs.getString(Prefs.FAVORITES, "[]"), f.site, f.video()))
                    .apply();
            reload();
        }

        private void removeHistory(WatchHistory.Entry e) {
            a.prefs.edit()
                    .putString(
                            Prefs.WATCH_HISTORY,
                            WatchHistory.remove(a.prefs.getString(Prefs.WATCH_HISTORY, "[]"), e.key()))
                    .apply();
            reload();
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView title, sub, action;
        View progress;
        boolean emptyRow;

        RowHolder(View itemView) {
            super(itemView);
        }
    }

    /** 1.8.6 片库多选状态（供 BACK 退出多选）。 */
    static boolean librarySelecting;
    static Runnable exitSelectionAction;

    static boolean exitLibrarySelection() {
        if (!librarySelecting) return false;
        if (exitSelectionAction != null) exitSelectionAction.run();
        librarySelecting = false;
        return true;
    }

    private static java.util.List<String> favoriteGroups(MainActivity a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray arr =
                    new org.json.JSONArray(a.prefs.getString("fav_groups", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String g = arr.optString(i, "").trim();
                if (!g.isEmpty() && !out.contains(g)) out.add(g);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveFavoriteGroups(MainActivity a, java.util.List<String> groups) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String g : groups) arr.put(g);
        a.prefs.edit().putString("fav_groups", arr.toString()).apply();
    }

    /** 收藏分组管理：新建 / 重命名 / 删除（删除不删收藏，回到默认组）。 */
    private void manageFavoriteGroups(final MainActivity a, final Runnable onChanged) {
        String[] options = {"新建分组", "重命名分组", "删除分组"};
        new AppDialog.Builder(a)
                .setTitle("管理收藏分组")
                .setItems(
                        options,
                        (d, index) -> {
                            if (index == 0) {
                                final EditText input = groupNameInput(a);
                                new AppDialog.Builder(a)
                                        .setTitle("新建分组")
                                        .setView(input)
                                        .setPositiveButton(
                                                "创建",
                                                (dd, ii) -> {
                                                    String name =
                                                            input.getText().toString().trim();
                                                    if (name.isEmpty() || name.length() > 8) {
                                                        a.toast("分组名需 1–8 个字");
                                                        return;
                                                    }
                                                    java.util.List<String> groups =
                                                            favoriteGroups(a);
                                                    if (!groups.contains(name)) groups.add(name);
                                                    saveFavoriteGroups(a, groups);
                                                    a.toast("已创建「" + name + "」");
                                                    if (onChanged != null) onChanged.run();
                                                })
                                        .setNegativeButton("取消", null)
                                        .show();
                            } else {
                                java.util.List<String> groups = favoriteGroups(a);
                                if (groups.isEmpty()) {
                                    a.toast("还没有自定义分组");
                                    return;
                                }
                                String[] names = groups.toArray(new String[0]);
                                new AppDialog.Builder(a)
                                        .setTitle(index == 1 ? "选择要重命名的分组" : "选择要删除的分组")
                                        .setItems(
                                                names,
                                                (dd, sel) -> {
                                                    String target = names[sel];
                                                    if (index == 1) {
                                                        final EditText input = groupNameInput(a);
                                                        input.setText(target);
                                                        new AppDialog.Builder(a)
                                                                .setTitle("重命名分组")
                                                                .setView(input)
                                                                .setPositiveButton(
                                                                        "保存",
                                                                        (ddd, iii) -> {
                                                                            String name =
                                                                                    input.getText()
                                                                                            .toString()
                                                                                            .trim();
                                                                            if (name.isEmpty()
                                                                                    || name
                                                                                            .length()
                                                                                            > 8) {
                                                                                a.toast(
                                                                                        "分组名需 1–8 个字");
                                                                                return;
                                                                            }
                                                                            java.util.List<String>
                                                                                    list =
                                                                                            favoriteGroups(
                                                                                                    a);
                                                                            int at =
                                                                                    list.indexOf(
                                                                                            target);
                                                                            if (at >= 0)
                                                                                list.set(at, name);
                                                                            saveFavoriteGroups(
                                                                                    a, list);
                                                                            a.prefs.edit()
                                                                                    .putString(
                                                                                            Prefs.FAVORITES,
                                                                                            moveGroupName(
                                                                                                    a.prefs
                                                                                                            .getString(
                                                                                                                    Prefs.FAVORITES,
                                                                                                                    "[]"),
                                                                                                    target,
                                                                                                    name))
                                                                                    .apply();
                                                                            a.toast(
                                                                                    "已重命名为「"
                                                                                            + name
                                                                                            + "」");
                                                                            if (onChanged != null)
                                                                                onChanged.run();
                                                                        })
                                                                .setNegativeButton("取消", null)
                                                                .show();
                                                    } else {
                                                        new AppDialog.Builder(a)
                                                                .setTitle("删除分组「" + target + "」？")
                                                                .setMessage(
                                                                        "组内收藏会回到默认分组，不会删除收藏。")
                                                                .setPositiveButton(
                                                                        "删除",
                                                                        (ddd, iii) -> {
                                                                            java.util.List<String>
                                                                                    list =
                                                                                            favoriteGroups(
                                                                                                    a);
                                                                            list.remove(target);
                                                                            saveFavoriteGroups(
                                                                                    a, list);
                                                                            a.prefs.edit()
                                                                                    .putString(
                                                                                            Prefs.FAVORITES,
                                                                                            moveGroupName(
                                                                                                    a.prefs
                                                                                                            .getString(
                                                                                                                    Prefs.FAVORITES,
                                                                                                                    "[]"),
                                                                                                    target,
                                                                                                    ""))
                                                                                    .apply();
                                                                            a.toast("已删除分组");
                                                                            if (onChanged != null)
                                                                                onChanged.run();
                                                                        })
                                                                .setNegativeButton("取消", null)
                                                                .show();
                                                    }
                                                })
                                        .setNegativeButton("取消", null)
                                        .show();
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static EditText groupNameInput(MainActivity a) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("分组名（1–8 字）");
        input.setHintTextColor(a.themeColor(MainActivity.MUTED));
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        return input;
    }

    private static String moveGroupName(String raw, String from, String to) {
        java.util.List<Favorites.Entry> list = Favorites.read(raw);
        for (Favorites.Entry e : list)
            if (from.equals(e.group == null ? "" : e.group)) e.group = to;
        // 复用 Favorites 的写回：通过 moveGroup 全量匹配
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (Favorites.Entry e : list) keys.add(e.key());
        java.util.List<Favorites.Entry> changed = Favorites.read(raw);
        for (Favorites.Entry e : changed) {
            if (from.equals(e.group == null ? "" : e.group)) e.group = to;
        }
        return Favorites.moveGroup(raw, keysForGroup(raw, from), to);
    }

    private static java.util.Set<String> keysForGroup(String raw, String group) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (Favorites.Entry e : Favorites.read(raw))
            if (group.equals(e.group == null ? "" : e.group)) keys.add(e.key());
        return keys;
    }

    private void styleLibraryPill(TextView pill, boolean active) {
        pill.setTextColor(active ? 0xff000000 : a.userTextColor(0xffe6edf9));
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
    /** 1.9.24：字体样式名 → 显示下标。 */
    private static int fontStyleIndex(String name) {
        for (int i = 0; i < FontStyleDialog.NAMES.length; i++)
            if (FontStyleDialog.NAMES[i].equals(name)) return i;
        return 0;
    }

    private void renderSettingsTab() {
        contentContainer.removeAllViews();

        final ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        AppLog.i("Wallpaper", "page=MobileShellSettings bg=transparent");

        final LinearLayout body = a.vertical();
        body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
                                                                    : settingsSubPage == 10
                                                                            ? "存储空间与缓存清理"
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
                addSettingCard(
                        body,
                        1,
                        "聚合搜索（跨源合并）",
                        a.prefs.getBoolean("search_aggregate_enabled", true)
                                ? "已开启：跨源按标题合并，显示 N 个来源与排序"
                                : "已关闭：按来源分组显示，不跨源合并",
                        () -> {
                            boolean next = !a.prefs.getBoolean("search_aggregate_enabled", true);
                            a.prefs.edit().putBoolean("search_aggregate_enabled", next).apply();
                            AppLog.i("SearchAgg", "aggregate=" + (next ? "on" : "off"));
                            refreshInPlace.run();
                            a.toast(next ? "已开启聚合搜索" : "已关闭聚合搜索（按来源分组）");
                        });
                a.gap(body, 12);
                addSectionHeader(body, "文件与存储");
                addSettingCard(
                        body,
                        1,
                        "文件管理器（本地 / 网盘 / 下载）",
                        "浏览本地与网盘文件、播放、下载到 Download/鲸落/、查看下载中心",
                        () -> {
                            MainActivity.Screen s =
                                    new MainActivity.Screen(
                                            () -> FileManagerScreen.show(a));
                            s.resume = () -> FileManagerScreen.resume(a);
                            a.open(s, true);
                        });
                addSettingCard(
                        body,
                        1,
                        "App 内置文件（教程 / 配置 / 上传）",
                        "App 内置配置/教程文件与管理员后台上传；本地与网盘文件请用「文件管理器」",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> FilesScreen.show(a)),
                                        true));
                addSettingCard(
                        body,
                        1,
                        "下载中心",
                        "查看下载进度、暂停/继续/取消/重试与完成历史",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> DownloadCenterScreen.show(a)),
                                        true));
                addSettingCard(
                        body,
                        1,
                        "片源体检 / 失效源清理",
                        "检测每个片源可用性与耗时，失效源可禁用或恢复",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> SourceCheckScreen.show(a)),
                                        true));
                addSettingCard(
                        body,
                        1,
                        "手机遥控 / 扫码输入",
                        "电视端扫码用手机浏览器当遥控器/输入法（也可在本机打开二维码）",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> RemoteInputScreen.show(a)),
                                        true));
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
                addSettingCard(
                        body,
                        3,
                        "后台播放",
                        a.prefs.getBoolean("bg_play", true)
                                ? "已开启：按 HOME/锁屏后音频继续，通知栏可暂停/停止"
                                : "已关闭：离开 App 自动暂停",
                        () -> {
                            boolean next = !a.prefs.getBoolean("bg_play", true);
                            a.prefs.edit().putBoolean("bg_play", next).apply();
                            refreshInPlace.run();
                            a.toast(next ? "已开启后台播放" : "已关闭后台播放");
                        });
                addSettingCard(
                        body,
                        3,
                        "播放失败自动换源",
                        a.prefs.getBoolean("auto_switch_source", true)
                                ? "已开启：播放失败/卡 5 秒自动换同片其它线路（最多 2 次）"
                                : "已关闭：失败后仅提示手动换源",
                        () -> {
                            boolean next = !a.prefs.getBoolean("auto_switch_source", true);
                            a.prefs.edit().putBoolean("auto_switch_source", next).apply();
                            refreshInPlace.run();
                            a.toast(next ? "已开启自动换源" : "已关闭自动换源");
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
                        "电视频道管理",
                        "批量选择、隐藏内置频道、删除自定义频道、查看/编辑频道配置",
                        () -> ChannelManagerScreen.show(a));
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
                        "字体颜色",
                        TextColorDialog.summary(a),
                        () -> TextColorDialog.show(a));
                addSettingCard(
                        body,
                        3,
                        "字体样式",
                        FontStyleDialog.LABELS[fontStyleIndex(a.fontStyleName())],
                        () -> FontStyleDialog.show(a));
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
                ExtraPages.about(a, body);
            } else if (settingsSubPage == 7) {
                ExtraPages.stats(a, body);
            } else if (settingsSubPage == 8) {
                ExtraPages.keymap(a, body);
            } else if (settingsSubPage == 9) {
                addSourceGuidePage(body);
            } else if (settingsSubPage == 10) {
                ExtraPages.storage(a, body);
            } else {
                addSectionHeader(body, "更新、备份与诊断");
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
                        "查看崩溃日志",
                        "读取上次异常退出保存的堆栈，可复制或另存到文件管理",
                        a::showCrashLog);
                addSettingCard(
                        body,
                        5,
                        "存储空间与缓存清理",
                        "查看播放/插件/日志/上传占用，一键清理临时缓存",
                        () -> {
                            settingsSubPage = 10;
                            refreshInPlace.run();
                        });
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
            "数据源：片源与直播配置",
            "播放器：解码与字幕",
            "外观：界面与偏好",
            "首页：布局与推荐",
            "备份与诊断",
            "关于鲸落",
            "观影统计"
        };
        final String[] subtitles = {
            "导入配置、直播源、来源扩展与网盘账号",
            "播放器内核、解码、字幕与画面比例",
            "缩放、导航栏、壁纸、动效与透明效果",
            "首页区块、推荐、继续观看与历史展示",
            "备份/还原、检查更新、缓存与诊断日志",
            "版本信息、免责声明与开源致谢",
            "播放次数、观看时长与本机统计"
        };
        final int[] icons = {1, 3, 4, 2, 5, 6, 4};
        final int[] pages = {1, 2, 3, 5, 4, 6, 7};
        for (int i = 0; i < categories.length; i++) {
            final int page = pages[i];
            addSettingCard(
                    body,
                    icons[i],
                    categories[i],
                    subtitles[i],
                    () -> {
                        settingsSubPage = page;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
        }
        addSettingCard(body, 2, "网盘与账号（源面板）", "直接打开配置源自带的账号面板", a::openSourceAccountPanel);
        // 1.8.1 安全模式：Switch 手动开关（开=立即禁用壁纸/加载动效，持久化）
        LinearLayout safeRow = a.row();
        safeRow.setGravity(Gravity.CENTER_VERTICAL);
        safeRow.setPadding(a.dp(6), a.dp(12), a.dp(6), a.dp(12));
        safeRow.setBackground(glassBg(0xcc16283f, 0xb3121d2e, a.dp(16), a.dp(0.8f), 0x33ffffff));
        LinearLayout safeText = a.vertical();
        TextView safeTitle = a.text("安全模式", 15, MainActivity.WHITE);
        safeTitle.setTypeface(null, Typeface.BOLD);
        safeText.addView(safeTitle);
        TextView safeDetail =
                a.text("开启后禁用壁纸与加载动效，减少崩溃/卡顿", 12, MainActivity.MUTED);
        safeDetail.setPadding(0, a.dp(3), 0, 0);
        safeText.addView(safeDetail);
        safeRow.addView(safeText, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch safeSwitch = new android.widget.Switch(a);
        safeSwitch.setChecked(SafeMode.enabled(a));
        safeSwitch.setOnCheckedChangeListener(
                (v, checked) -> {
                    SafeMode.set(a, checked);
                    a.applySafeModeVisual();
                    if (checked && retainedRoot != null) a.applyWallpaper(retainedRoot);
                    if (!checked && retainedRoot != null) a.ensureBackdrop(retainedRoot);
                    a.toast(checked ? "已开启安全模式（壁纸与加载动效已禁用）" : "已关闭安全模式（已恢复）");
                    // 1.8.2：下一帧再补一次，覆盖页面重建/返回首页的时序
                    a.ui.postDelayed(a::applySafeModeVisual, 120);
                    a.ui.postDelayed(a::applySafeModeVisual, 400);
                });
        safeRow.addView(safeSwitch, new LinearLayout.LayoutParams(-2, a.dp(44)));
        LinearLayout.LayoutParams safeRowLp = new LinearLayout.LayoutParams(-1, -2);
        safeRowLp.topMargin = a.dp(10);
        body.addView(safeRow, safeRowLp);
    }

    /** 设置 → 关于鲸落：版本、开源信息、上游归属与相关操作。 */
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
                case 6:
                    return UiIcon.CLOUD;
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
