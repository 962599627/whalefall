package com.pilipala.marcomovie;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated Android Phone & Tablet application shell featuring a persistent bottom navigation bar
 * (or tablet side rail), horizontal scrollable category tabs, native system keyboard search,
 * two-column live TV browser, and touch-first configuration/settings cards.
 */
final class MobileShell {
    static int activeTab = 0;
    static String pendingSearchKeyword = "";

    private final MainActivity a;
    private FrameLayout contentContainer;
    private final View[] navTabs = new View[5];
    private final TextView[] navLabels = new TextView[5];
    private final NavGlyph[] navIcons = new NavGlyph[5];
    private static final String[] TAB_NAMES = {"首页", "搜索", "直播", "我的", "设置"};

    // Home tab state
    private Cms.Site currentSite;
    private String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    private List<Cms.Category> currentCategories = new ArrayList<>();

    MobileShell(MainActivity activity) {
        this.a = activity;
    }

    static void show(MainActivity a) {
        new MobileShell(a).render();
    }

    static void openSearchTab(MainActivity a, String initialKeyword) {
        activeTab = 1;
        pendingSearchKeyword = initialKeyword == null ? "" : initialKeyword.trim();
        new MobileShell(a).render();
    }

    static void showMobileImportDialog(MainActivity a) {
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip = a.text("在下方输入或粘贴配置网址（HTTP/HTTPS）或 JSON 文本：", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        final EditText input = new EditText(a);
        input.setHint("https://…/config.json");
        input.setHintTextColor(0xff6b7280);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(true);
        input.setLongClickable(true);
        input.setTextIsSelectable(true);
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff262834, 12));
        String savedOrigin = a.prefs.getString(Prefs.ORIGIN, "");
        if (!savedOrigin.isEmpty()) {
            input.setText(savedOrigin);
            input.setSelection(savedOrigin.length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView pasteBtn = a.text("📋 一键读取手机剪贴板并直接导入", 13, Color.WHITE);
        pasteBtn.setTypeface(null, Typeface.BOLD);
        pasteBtn.setGravity(Gravity.CENTER);
        pasteBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pasteBtn.setBackground(a.bg(0xff3b82f6, 12));
        MobileDetailScreen.bindTouchFeedback(pasteBtn);
        panel.addView(pasteBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView fileBtn = a.text("📂 选择手机本地 JSON 配置文件", 13, MainActivity.WHITE);
        fileBtn.setGravity(Gravity.CENTER);
        fileBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        fileBtn.setBackground(a.bg(0xff262834, 12));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        panel.addView(fileBtn, new LinearLayout.LayoutParams(-1, -2));

        final AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("导入或更新配置")
                        .setView(panel)
                        .setNegativeButton("取消", null)
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
            a.toast("剪贴板为空，请先复制配置网址或 JSON 内容");
            return;
        }
        importTextOrUrl(a, text);
    }

    static void importTextOrUrl(MainActivity a, String rawInput) {
        final String pasted = rawInput == null ? "" : rawInput.trim();
        if (pasted.isEmpty()) {
            a.toast("请输入有效的配置网址或 JSON 内容");
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
                            rawJson = Cms.fetch(origin, null);
                        }
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
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("配置读取失败：" + a.message(e)));
                    }
                });
    }

    void render() {
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

        // Show system status bar and navigation bar on phones/tablets while browsing
        try {
            a.getWindow().getDecorView().setSystemUiVisibility(0);
            a.getWindow().setStatusBarColor(0xff12131a);
            a.getWindow().setNavigationBarColor(0xff161720);
        } catch (Exception ignored) {
        }

        resolveActiveSite();

        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(0xff0f1015);
        a.setContentView(a.root);

        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        boolean tabletSideRail = widthDp >= 640 && !a.isPortrait();

        if (tabletSideRail) {
            LinearLayout mainRow = a.row();
            mainRow.setGravity(Gravity.TOP);
            a.root.addView(mainRow, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout sideRail = buildNavigationBar(true);
            mainRow.addView(sideRail, new LinearLayout.LayoutParams(a.dp(76), -1));

            LinearLayout rightArea = a.vertical();
            mainRow.addView(rightArea, new LinearLayout.LayoutParams(0, -1, 1));
            rightArea.addView(buildTopAppBar(), new LinearLayout.LayoutParams(-1, -2));

            contentContainer = new FrameLayout(a);
            rightArea.addView(contentContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            LinearLayout mainCol = a.vertical();
            a.root.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

            mainCol.addView(buildTopAppBar(), new LinearLayout.LayoutParams(-1, -2));

            contentContainer = new FrameLayout(a);
            mainCol.addView(contentContainer, new LinearLayout.LayoutParams(-1, 0, 1));

            View divider = new View(a);
            divider.setBackgroundColor(0x18ffffff);
            mainCol.addView(divider, new LinearLayout.LayoutParams(-1, 1));

            LinearLayout bottomBar = buildNavigationBar(false);
            mainCol.addView(bottomBar, new LinearLayout.LayoutParams(-1, a.dp(58)));
        }

        switchTab(activeTab);
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

    private View buildTopAppBar() {
        LinearLayout bar = a.row();
        bar.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        bar.setBackgroundColor(0xff15161e);

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(a.dp(26), a.dp(26));
        logoLp.rightMargin = a.dp(8);
        bar.addView(logo, logoLp);

        // Site selector pill
        String siteLabel = currentSite != null ? currentSite.name + " ▾" : "选择片源 ▾";
        TextView sitePill = a.text(siteLabel, 14, MainActivity.WHITE);
        sitePill.setTag("source-switcher");
        sitePill.setTypeface(null, Typeface.BOLD);
        sitePill.setSingleLine(true);
        sitePill.setEllipsize(TextUtils.TruncateAt.END);
        sitePill.setMaxWidth(a.dp(136));
        sitePill.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        sitePill.setBackground(a.bg(0xff232530, 16));
        MobileDetailScreen.bindTouchFeedback(sitePill);
        sitePill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else a.chooseSources();
                });
        bar.addView(sitePill, new LinearLayout.LayoutParams(-2, -2));

        // Fake Search Input Pill (tapping jumps to Search tab)
        TextView searchPill = a.text("🔍 搜索片名、演员…", 13, 0xff9ca3af);
        searchPill.setSingleLine(true);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        searchPill.setBackground(a.bg(0xff1f212b, 18));
        MobileDetailScreen.bindTouchFeedback(searchPill);
        searchPill.setOnClickListener(v -> switchTab(1));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, a.dp(34), 1);
        searchLp.setMargins(a.dp(10), 0, a.dp(8), 0);
        bar.addView(searchPill, searchLp);

        // Quick Config Button
        TextView importBtn = a.text("＋配置", 13, 0xff8ab4f8);
        importBtn.setTypeface(null, Typeface.BOLD);
        importBtn.setGravity(Gravity.CENTER);
        importBtn.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        importBtn.setBackground(a.bg(0xff1e293b, 16));
        MobileDetailScreen.bindTouchFeedback(importBtn);
        importBtn.setOnClickListener(v -> showMobileImportDialog(a));
        bar.addView(importBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));

        return bar;
    }

    private LinearLayout buildNavigationBar(boolean verticalRail) {
        LinearLayout bar = verticalRail ? a.vertical() : a.row();
        bar.setTag("home-dock");
        bar.setBackgroundColor(0xff161720);
        bar.setGravity(Gravity.CENTER);
        if (verticalRail) {
            bar.setPadding(a.dp(4), a.dp(16), a.dp(4), a.dp(16));
        } else {
            bar.setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4));
        }
        String[] navKeys = {"nav-home", "nav-search", "nav-live", "nav-favorites", "nav-settings"};

        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, i);
            navIcons[i] = glyph;
            tab.addView(glyph, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));

            TextView label = a.text(TAB_NAMES[i], 11, MainActivity.MUTED);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, a.dp(2), 0, 0);
            navLabels[i] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));

            tab.setOnClickListener(v -> switchTab(index));
            navTabs[i] = tab;

            LinearLayout.LayoutParams lp =
                    verticalRail
                            ? new LinearLayout.LayoutParams(-1, a.dp(64))
                            : new LinearLayout.LayoutParams(0, -1, 1);
            bar.addView(tab, lp);
        }
        return bar;
    }

    void switchTab(int index) {
        activeTab = Math.max(0, Math.min(4, index));
        a.cancelPageRequests();
        for (int i = 0; i < 5; i++) {
            boolean selected = i == activeTab;
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(selected ? 0xff3b82f6 : 0xff9ca3af);
                navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (navIcons[i] != null) {
                navIcons[i].setSelectedState(selected);
            }
        }
        contentContainer.removeAllViews();
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
        }
    }

    // =========================================================================
    // TAB 0: MOBILE HOME (Horizontal Category Tabs + Compact History + 3-Col Grid)
    // =========================================================================
    private void renderHomeTab() {
        if (a.config == null || currentSite == null) {
            renderEmptyWelcome();
            return;
        }

        LinearLayout container = a.vertical();
        contentContainer.addView(container, new FrameLayout.LayoutParams(-1, -1));

        // Horizontal Scrollable Category Bar
        HorizontalScrollView catScroll = new HorizontalScrollView(a);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setBackgroundColor(0xff15161e);
        catScroll.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(8));
        final LinearLayout catRow = a.row();
        catScroll.addView(catRow);
        container.addView(catScroll, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(a);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        a.scroll = scrollView;
        container.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout body = a.vertical();
        body.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(28));
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Continue Watching Horizontal Strip
        List<WatchHistory.Entry> history = a.watchHistory();
        if (!history.isEmpty()) {
            LinearLayout histHeader = a.row();
            TextView hTitle = a.text("继续观看", 15, MainActivity.WHITE);
            hTitle.setTypeface(null, Typeface.BOLD);
            histHeader.addView(hTitle, new LinearLayout.LayoutParams(0, -2, 1));
            TextView moreHist = a.text("全部记录 ›", 12, 0xff8ab4f8);
            MobileDetailScreen.bindTouchFeedback(moreHist);
            moreHist.setOnClickListener(v -> switchTab(3));
            histHeader.addView(moreHist);
            body.addView(histHeader, new LinearLayout.LayoutParams(-1, -2));
            a.gap(body, 8);

            HorizontalScrollView histScroll = new HorizontalScrollView(a);
            histScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout histRow = a.row();
            histScroll.addView(histRow);
            for (int i = 0; i < Math.min(8, history.size()); i++) {
                final WatchHistory.Entry e = history.get(i);
                LinearLayout card = a.row();
                card.setTag("home-history:" + i);
                card.setPadding(a.dp(8), a.dp(8), a.dp(10), a.dp(8));
                card.setBackground(a.bg(0xff1b1d27, 12));
                MobileDetailScreen.bindTouchFeedback(card);
                card.setOnClickListener(v -> a.resumeHistory(e));

                ImageView thumb = new ImageView(a);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(a.bg(0xff2a2d3a, 8));
                thumb.setClipToOutline(true);
                LinearLayout.LayoutParams thumbLp =
                        new LinearLayout.LayoutParams(a.dp(38), a.dp(52));
                thumbLp.rightMargin = a.dp(10);
                card.addView(thumb, thumbLp);
                if (e.poster != null && !e.poster.isEmpty()) a.poster(e.poster, thumb);

                LinearLayout meta = a.vertical();
                TextView t = a.text(e.title, 13, MainActivity.WHITE);
                t.setTypeface(null, Typeface.BOLD);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                meta.addView(t);

                TextView sub = a.text(a.historyLabel(e), 11, MainActivity.MUTED);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(0, a.dp(4), 0, 0);
                meta.addView(sub);
                card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));

                LinearLayout.LayoutParams cardLp =
                        new LinearLayout.LayoutParams(a.dp(205), a.dp(68));
                cardLp.rightMargin = a.dp(10);
                histRow.addView(card, cardLp);
            }
            body.addView(histScroll, new LinearLayout.LayoutParams(-1, -2));
            a.gap(body, 14);
        }

        final TextView statusText = a.text("正在加载片单…", 13, MainActivity.MUTED);
        statusText.setPadding(a.dp(4), a.dp(16), a.dp(4), a.dp(16));
        body.addView(statusText);

        final LinearLayout gridContainer = a.vertical();
        body.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        final TextView loadMoreBtn = a.text("加载下一页", 14, MainActivity.WHITE);
        loadMoreBtn.setTag("home-load-more");
        loadMoreBtn.setGravity(Gravity.CENTER);
        loadMoreBtn.setPadding(a.dp(20), a.dp(11), a.dp(20), a.dp(11));
        loadMoreBtn.setBackground(a.bg(0xff232530, 20));
        loadMoreBtn.setVisibility(View.GONE);
        MobileDetailScreen.bindTouchFeedback(loadMoreBtn);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.topMargin = a.dp(14);
        body.addView(loadMoreBtn, moreLp);

        final Runnable[] fetchCategoryPage = new Runnable[1];
        final Runnable renderCategoryChips =
                new Runnable() {
                    @Override
                    public void run() {
                        catRow.removeAllViews();
                        addCategoryChip(catRow, "推荐", "", this, fetchCategoryPage[0]);
                        for (Cms.Category c : currentCategories) {
                            addCategoryChip(catRow, c.name, c.id, this, fetchCategoryPage[0]);
                        }
                    }
                };

        fetchCategoryPage[0] =
                () -> {
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("正在加载…");
                    loadMoreBtn.setVisibility(View.GONE);
                    final Cms.Site siteSnapshot = currentSite;
                    final String catSnapshot = selectedCategoryId;
                    final int pageSnapshot = currentPage;
                    final int ticket = a.generation;
                    a.submitPage(
                            () -> {
                                try {
                                    SourceEngine eng = a.engine(siteSnapshot);
                                    List<Cms.Category> cats = currentCategories;
                                    Cms.Listing list;
                                    if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                        String raw = eng.home();
                                        cats = Cms.categories(raw);
                                        list =
                                                new JSONObject(raw).has("list")
                                                        ? Cms.listing(raw)
                                                        : new Cms.Listing();
                                        if (list.videos.isEmpty()) {
                                            try {
                                                list = Cms.listing(eng.homeVideos());
                                            } catch (Exception ignored) {
                                            }
                                        }
                                        if (list.videos.isEmpty() && !cats.isEmpty()) {
                                            list = Cms.listing(eng.category(cats.get(0).id, 1));
                                        }
                                    } else {
                                        String targetCat =
                                                catSnapshot.isEmpty() && !cats.isEmpty()
                                                        ? cats.get(0).id
                                                        : catSnapshot;
                                        list = Cms.listing(eng.category(targetCat, pageSnapshot));
                                    }
                                    final List<Cms.Category> finalCats = cats;
                                    final Cms.Listing finalList = list;
                                    a.learnTitles(finalList.videos);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                currentCategories = finalCats;
                                                renderCategoryChips.run();
                                                if (pageSnapshot == 1) currentVideos.clear();
                                                for (Cms.Video v : finalList.videos) {
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
                                                    statusText.setText("当前分类暂无影片");
                                                }
                                                renderVideoGrid(
                                                        gridContainer, siteSnapshot, currentVideos);
                                                if (currentPage < totalPages) {
                                                    loadMoreBtn.setVisibility(View.VISIBLE);
                                                    loadMoreBtn.setText(
                                                            "加载更多 ("
                                                                    + currentPage
                                                                    + "/"
                                                                    + totalPages
                                                                    + ")");
                                                } else {
                                                    loadMoreBtn.setVisibility(View.GONE);
                                                }
                                            });
                                } catch (Exception e) {
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
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
                    if (currentPage < totalPages) currentPage++;
                    fetchCategoryPage[0].run();
                });

        renderCategoryChips.run();
        fetchCategoryPage[0].run();
    }

    private void addCategoryChip(
            LinearLayout row,
            String label,
            String catId,
            Runnable refreshChips,
            Runnable loadPage) {
        boolean active = selectedCategoryId.equals(catId);
        TextView chip = a.text(label, 13, active ? Color.WHITE : 0xff9ca3af);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        chip.setBackground(a.bg(active ? 0xff3b82f6 : 0xff222430, 16));
        MobileDetailScreen.bindTouchFeedback(chip);
        chip.setOnClickListener(
                v -> {
                    if (selectedCategoryId.equals(catId)) return;
                    selectedCategoryId = catId;
                    currentPage = 1;
                    refreshChips.run();
                    loadPage.run();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = a.dp(8);
        row.addView(chip, lp);
    }

    private void renderVideoGrid(LinearLayout container, Cms.Site site, List<Cms.Video> videos) {
        container.removeAllViews();
        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int cols = widthDp >= 720 ? 5 : (widthDp >= 520 ? 4 : 3);
        int horizontalPaddingDp = widthDp >= 640 && !a.isPortrait() ? 110 : 28;
        int cardWidthDp =
                Math.max(92, (int) ((widthDp - horizontalPaddingDp - (cols - 1) * 10) / cols));
        int posterHeightDp = Math.round(cardWidthDp * 1.42f);

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
            card.setOnClickListener(
                    view -> a.open(new MainActivity.Screen(() -> a.detail(site, v)), true));

            FrameLayout art = new FrameLayout(a);
            art.setBackground(a.bg(0xff1e2230, 10));
            art.setClipToOutline(true);

            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, img);

            if (v.remarks != null && !v.remarks.trim().isEmpty()) {
                TextView badge = a.text(v.remarks.trim(), 10, Color.WHITE);
                badge.setSingleLine(true);
                badge.setEllipsize(TextUtils.TruncateAt.END);
                badge.setMaxWidth(a.dp(cardWidthDp - 12));
                badge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
                badge.setBackground(a.bg(0xcc111827, 6));
                FrameLayout.LayoutParams badgeLp =
                        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
                badgeLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
                art.addView(badge, badgeLp);
            }

            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(posterHeightDp)));

            TextView title = a.text(v.title, 13, MainActivity.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(a.dp(2), a.dp(6), a.dp(2), a.dp(2));
            card.addView(title);

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1);
            cellLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(10));
            currentRow.addView(card, cellLp);
        }
        if (currentRow != null) {
            int rem = videos.size() % cols;
            if (rem > 0) {
                for (int i = rem; i < cols; i++) {
                    LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                    blankLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(10));
                    currentRow.addView(new View(a), blankLp);
                }
            }
        }
    }

    private void renderEmptyWelcome() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout wrap = a.vertical();
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(a.dp(22), a.dp(24), a.dp(22), a.dp(24));
        sv.addView(wrap, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = a.vertical();
        card.setPadding(a.dp(20), a.dp(22), a.dp(20), a.dp(22));
        card.setBackground(a.bg(0xff181922, 20));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView badge = a.text("MARCO MOVIE · 手机/平板端", 11, 0xff8ab4f8);
        badge.setTypeface(null, Typeface.BOLD);
        card.addView(badge);
        a.gap(card, 8);

        TextView heading = a.text("把今晚，留给好故事。", 24, MainActivity.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        card.addView(heading);
        a.gap(card, 10);

        TextView desc =
                a.text("导入你自己合法获取的影视配置，即可在手机上用触屏滑动浏览、系统键盘搜索、手势控制播放。", 14, MainActivity.MUTED);
        desc.setLineSpacing(a.dp(4), 1f);
        card.addView(desc);
        a.gap(card, 20);

        LinearLayout urlRow = a.row();
        final EditText urlInput = new EditText(a);
        urlInput.setHint("输入或长按粘贴配置网址 https://…");
        urlInput.setHintTextColor(0xff6b7280);
        urlInput.setTextColor(MainActivity.WHITE);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(true);
        urlInput.setShowSoftInputOnFocus(true);
        urlInput.setLongClickable(true);
        urlInput.setTextIsSelectable(true);
        urlInput.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        urlInput.setBackground(a.bg(0xff252734, 12));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, -2, 1);
        urlLp.rightMargin = a.dp(8);
        urlRow.addView(urlInput, urlLp);

        TextView goBtn = a.text("导入", 13, Color.WHITE);
        goBtn.setTypeface(null, Typeface.BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
        goBtn.setBackground(a.bg(0xff3b82f6, 12));
        MobileDetailScreen.bindTouchFeedback(goBtn);
        goBtn.setOnClickListener(v -> importTextOrUrl(a, urlInput.getText().toString()));
        urlRow.addView(goBtn);
        card.addView(urlRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(card, 12);

        addMobileActionBtn(
                card,
                "📋 一键读取手机剪贴板并导入",
                0xff3b82f6,
                Color.WHITE,
                () -> pasteConfigFromClipboard(a));
        a.gap(card, 10);
        addMobileActionBtn(
                card, "📂 选择手机本地 JSON 配置文件", 0xff252734, MainActivity.WHITE, a::pickConfig);

        a.gap(wrap, 18);
        TextView legal =
                a.text("本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。请自行合法获取内容与配置，并确保拥有相应使用权。", 12, 0xff6b7280);
        legal.setLineSpacing(a.dp(3), 1f);
        wrap.addView(legal);
    }

    private void addMobileActionBtn(
            LinearLayout parent, String text, int bgColor, int textColor, Runnable onClick) {
        TextView btn = a.text(text, 14, textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(a.dp(16), a.dp(13), a.dp(16), a.dp(13));
        btn.setBackground(a.bg(bgColor, 14));
        MobileDetailScreen.bindTouchFeedback(btn);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn, new LinearLayout.LayoutParams(-1, -2));
    }

    // =========================================================================
    // TAB 1: MOBILE SEARCH (System Soft Keyboard + Multi-Site Live Results)
    // =========================================================================
    private void renderSearchTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Search input row
        LinearLayout inputRow = a.row();
        inputRow.setPadding(a.dp(12), a.dp(4), a.dp(6), a.dp(4));
        inputRow.setBackground(a.bg(0xff1c1e28, 22));

        final EditText input = new EditText(a);
        input.setHint("输入片名、演员或拼音首字母…");
        input.setHintTextColor(0xff6b7280);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(a.dp(4), a.dp(8), a.dp(8), a.dp(8));
        if (!pendingSearchKeyword.isEmpty()) {
            input.setText(pendingSearchKeyword);
            input.setSelection(pendingSearchKeyword.length());
        }
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        TextView clearBtn = a.text("✕", 14, MainActivity.MUTED);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        clearBtn.setOnClickListener(v -> input.setText(""));
        inputRow.addView(clearBtn);

        TextView searchBtn = a.text("搜索", 13, Color.WHITE);
        searchBtn.setTypeface(null, Typeface.BOLD);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        searchBtn.setBackground(a.bg(0xff3b82f6, 16));
        MobileDetailScreen.bindTouchFeedback(searchBtn);
        inputRow.addView(searchBtn);

        page.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));

        // Search history chips
        final LinearLayout historyWrap = a.vertical();
        page.addView(historyWrap, new LinearLayout.LayoutParams(-1, -2));

        final TextView searchStatus = a.text("输入关键词搜索全部已启用的片源", 12, MainActivity.MUTED);
        searchStatus.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(8));
        page.addView(searchStatus);

        ScrollView resultScroll = new ScrollView(a);
        resultScroll.setFillViewport(true);
        resultScroll.setVerticalScrollBarEnabled(false);
        page.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout resultList = a.vertical();
        resultScroll.addView(resultList, new ScrollView.LayoutParams(-1, -2));

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
                        TextView clearHist = a.text("清空", 12, 0xff8ab4f8);
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
                            TextView chip = a.text(kw, 12, 0xffd1d5db);
                            chip.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
                            chip.setBackground(a.bg(0xff222430, 14));
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

                    // Save to search history
                    try {
                        JSONArray old =
                                new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
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

                    a.cancelPageRequests();
                    resultList.removeAllViews();
                    List<Cms.Site> searchableSites = new ArrayList<>();
                    for (Cms.Site s : a.config.sites) {
                        if (a.available(s) && s.searchable) searchableSites.add(s);
                    }
                    if (searchableSites.isEmpty()) {
                        searchStatus.setText("当前没有可搜索的片源");
                        return;
                    }
                    searchStatus.setText("正在并发搜索 " + searchableSites.size() + " 个片源…");
                    final int ticket = a.generation;
                    final int[] completed = {0};
                    final int[] hitCount = {0};

                    for (final Cms.Site site : searchableSites) {
                        Future<?> f =
                                RequestScope.submit(
                                        a.worker,
                                        () -> {
                                            List<Cms.Video> matches = new ArrayList<>();
                                            try {
                                                Cms.Listing l =
                                                        Cms.listing(a.engine(site).search(kw, 1));
                                                for (Cms.Video v : l.videos) {
                                                    if (v.action == null || v.action.isEmpty()) {
                                                        matches.add(v);
                                                    }
                                                }
                                            } catch (Exception ignored) {
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        if (ticket != a.generation
                                                                || a.isFinishing()) return;
                                                        completed[0]++;
                                                        for (final Cms.Video v : matches) {
                                                            hitCount[0]++;
                                                            resultList.addView(
                                                                    buildSearchResultRow(site, v));
                                                        }
                                                        if (completed[0] < searchableSites.size()) {
                                                            searchStatus.setText(
                                                                    "已找到 "
                                                                            + hitCount[0]
                                                                            + " 条结果（已完成 "
                                                                            + completed[0]
                                                                            + "/"
                                                                            + searchableSites.size()
                                                                            + " 个片源）");
                                                        } else {
                                                            searchStatus.setText(
                                                                    hitCount[0] == 0
                                                                            ? "未找到与“" + kw
                                                                                    + "”相关的影片"
                                                                            : "搜索完成，共找到 "
                                                                                    + hitCount[0]
                                                                                    + " 条结果");
                                                        }
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
            pendingSearchKeyword = "";
            triggerSearch[0].run();
        }
    }

    private View buildSearchResultRow(final Cms.Site site, final Cms.Video v) {
        LinearLayout row = a.row();
        row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
        row.setBackground(a.bg(0xff181922, 14));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                view -> a.open(new MainActivity.Screen(() -> a.detail(site, v)), true));

        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff252734, 8));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(60), a.dp(84));
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

        TextView badge = a.text("来源：" + site.name, 12, 0xff8ab4f8);
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
    // TAB 2: MOBILE LIVE TV (Two-Column Group & Channel Selector)
    // =========================================================================
    private void renderLiveTab() {
        LinearLayout page = a.vertical();
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        if (a.config == null) {
            renderCenteredHint(page, "请先导入包含直播列表的配置");
            return;
        }
        final List<LiveCatalog.Source> sources;
        try {
            sources = LiveCatalog.sources(a.config.raw, a.configOrigin);
        } catch (Exception e) {
            renderCenteredHint(page, "无法读取直播源配置");
            return;
        }
        if (sources.isEmpty()) {
            renderCenteredHint(page, "当前配置没有包含 M3U / TXT 直播源");
            return;
        }

        // Top Horizontal Source Selector
        HorizontalScrollView srcScroll = new HorizontalScrollView(a);
        srcScroll.setHorizontalScrollBarEnabled(false);
        srcScroll.setBackgroundColor(0xff15161e);
        srcScroll.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        final LinearLayout srcRow = a.row();
        srcScroll.addView(srcRow);
        page.addView(srcScroll, new LinearLayout.LayoutParams(-1, -2));

        final TextView stateView = a.text("正在加载直播频道…", 13, MainActivity.MUTED);
        stateView.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        page.addView(stateView);

        final LinearLayout splitRow = a.row();
        splitRow.setGravity(Gravity.TOP);
        page.addView(splitRow, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView groupScroll = new ScrollView(a);
        groupScroll.setVerticalScrollBarEnabled(false);
        groupScroll.setBackgroundColor(0xff14151d);
        final LinearLayout groupCol = a.vertical();
        groupCol.setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(20));
        groupScroll.addView(groupCol);
        splitRow.addView(groupScroll, new LinearLayout.LayoutParams(a.dp(116), -1));

        ScrollView channelScroll = new ScrollView(a);
        channelScroll.setVerticalScrollBarEnabled(false);
        final LinearLayout channelCol = a.vertical();
        channelCol.setPadding(a.dp(10), a.dp(8), a.dp(12), a.dp(24));
        channelScroll.addView(channelCol);
        splitRow.addView(channelScroll, new LinearLayout.LayoutParams(0, -1, 1));

        final int[] activeSrcIdx = {0};
        final Runnable[] loadSource = new Runnable[1];

        loadSource[0] =
                () -> {
                    srcRow.removeAllViews();
                    for (int i = 0; i < sources.size(); i++) {
                        final int idx = i;
                        boolean active = i == activeSrcIdx[0];
                        TextView chip =
                                a.text(sources.get(i).name, 13, active ? Color.WHITE : 0xff9ca3af);
                        chip.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
                        chip.setBackground(a.bg(active ? 0xff3b82f6 : 0xff222430, 16));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    activeSrcIdx[0] = idx;
                                    loadSource[0].run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(8);
                        srcRow.addView(chip, lp);
                    }

                    groupCol.removeAllViews();
                    channelCol.removeAllViews();
                    stateView.setVisibility(View.VISIBLE);
                    stateView.setText("正在加载频道列表…");
                    final LiveCatalog.Source selectedSource = sources.get(activeSrcIdx[0]);
                    final int ticket = a.generation;
                    a.submitPage(
                            () -> {
                                try {
                                    if (selectedSource.isLocalProxy()) {
                                        if (!a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false))
                                            throw new IOException("请先在设置中开启实验性扩展支持");
                                        a.ensureProxy(a.config);
                                        selectedSource.url = LocalProxy.rewrite(selectedSource.url);
                                    }
                                    final LiveCatalog.Catalog catalog =
                                            LiveCatalog.load(selectedSource);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                a.liveSession =
                                                        new LiveSession(selectedSource, catalog);
                                                stateView.setVisibility(View.GONE);
                                                renderLiveGroupsAndChannels(
                                                        selectedSource,
                                                        catalog,
                                                        0,
                                                        groupCol,
                                                        channelCol);
                                            });
                                } catch (Exception e) {
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
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
            final int selectedGroupIdx,
            final LinearLayout groupCol,
            final LinearLayout channelCol) {
        groupCol.removeAllViews();
        channelCol.removeAllViews();
        if (catalog.groups.isEmpty()) return;

        for (int i = 0; i < catalog.groups.size(); i++) {
            final int gIdx = i;
            boolean active = i == selectedGroupIdx;
            LiveCatalog.Group g = catalog.groups.get(i);
            TextView gBtn =
                    a.text(
                            g.name + " (" + g.channels.size() + ")",
                            13,
                            active ? Color.WHITE : 0xff9ca3af);
            gBtn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            gBtn.setSingleLine(true);
            gBtn.setEllipsize(TextUtils.TruncateAt.END);
            gBtn.setPadding(a.dp(10), a.dp(11), a.dp(10), a.dp(11));
            gBtn.setBackground(a.bg(active ? 0xff28354a : Color.TRANSPARENT, 10));
            MobileDetailScreen.bindTouchFeedback(gBtn);
            gBtn.setOnClickListener(
                    v -> renderLiveGroupsAndChannels(source, catalog, gIdx, groupCol, channelCol));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(4);
            groupCol.addView(gBtn, lp);
        }

        LiveCatalog.Group activeGroup = catalog.groups.get(selectedGroupIdx);
        for (int i = 0; i < activeGroup.channels.size(); i++) {
            final LiveCatalog.Channel ch = activeGroup.channels.get(i);
            LinearLayout row = a.row();
            row.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
            row.setBackground(a.bg(0xff181922, 12));
            MobileDetailScreen.bindTouchFeedback(row);

            TextView name = a.text(ch.name, 14, MainActivity.WHITE);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

            TextView lineCount = a.text(ch.streams.size() + " 条线路 ›", 12, 0xff8ab4f8);
            row.addView(lineCount);

            row.setOnClickListener(
                    v -> {
                        if (ch.streams.isEmpty()) return;
                        a.playLive(source, ch, ch.streams.get(0));
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            channelCol.addView(row, lp);
        }
    }

    // =========================================================================
    // TAB 3: MY LIBRARY (Watch History & Favorites)
    // =========================================================================
    private void renderMyTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        final boolean[] showFavorites = {false};
        final LinearLayout headerRow = a.row();
        final TextView tabHist = a.text("观看历史", 15, Color.WHITE);
        final TextView tabFav = a.text("我的收藏", 15, MainActivity.MUTED);
        final TextView clearBtn = a.text("清空历史", 12, 0xffef4444);

        headerRow.addView(tabHist);
        View gapView = new View(a);
        headerRow.addView(gapView, new LinearLayout.LayoutParams(a.dp(12), 1));
        headerRow.addView(tabFav);
        headerRow.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        headerRow.addView(clearBtn);
        page.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(page, 12);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout listBody = a.vertical();
        sv.addView(listBody, new ScrollView.LayoutParams(-1, -2));

        final Runnable renderSubTab =
                new Runnable() {
                    @Override
                    public void run() {
                        boolean favMode = showFavorites[0];
                        tabHist.setTextColor(favMode ? MainActivity.MUTED : Color.WHITE);
                        tabHist.setTypeface(null, favMode ? Typeface.NORMAL : Typeface.BOLD);
                        tabHist.setBackground(a.bg(favMode ? 0xff1c1e28 : 0xff3b82f6, 16));
                        tabHist.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));

                        tabFav.setTextColor(favMode ? Color.WHITE : MainActivity.MUTED);
                        tabFav.setTypeface(null, favMode ? Typeface.BOLD : Typeface.NORMAL);
                        tabFav.setBackground(a.bg(favMode ? 0xff3b82f6 : 0xff1c1e28, 16));
                        tabFav.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));

                        clearBtn.setVisibility(favMode ? View.GONE : View.VISIBLE);
                        listBody.removeAllViews();

                        if (!favMode) {
                            List<WatchHistory.Entry> items = a.watchHistory();
                            if (items.isEmpty()) {
                                renderCenteredHint(listBody, "暂无观看历史记录");
                                return;
                            }
                            for (final WatchHistory.Entry e : items) {
                                LinearLayout row = a.row();
                                row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                                row.setBackground(a.bg(0xff181922, 14));
                                MobileDetailScreen.bindTouchFeedback(row);
                                row.setOnClickListener(v -> a.resumeHistory(e));

                                ImageView thumb = new ImageView(a);
                                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                thumb.setBackground(a.bg(0xff252734, 8));
                                thumb.setClipToOutline(true);
                                LinearLayout.LayoutParams thumbLp =
                                        new LinearLayout.LayoutParams(a.dp(52), a.dp(72));
                                thumbLp.rightMargin = a.dp(12);
                                row.addView(thumb, thumbLp);
                                if (e.poster != null && !e.poster.isEmpty())
                                    a.poster(e.poster, thumb);

                                LinearLayout info = a.vertical();
                                TextView t = a.text(e.title, 15, MainActivity.WHITE);
                                t.setTypeface(null, Typeface.BOLD);
                                t.setSingleLine(true);
                                t.setEllipsize(TextUtils.TruncateAt.END);
                                info.addView(t);

                                TextView sub = a.text(a.historyLabel(e), 12, MainActivity.MUTED);
                                sub.setPadding(0, a.dp(5), 0, 0);
                                info.addView(sub);
                                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                                TextView del = a.text("删除", 12, 0xffef4444);
                                del.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                                MobileDetailScreen.bindTouchFeedback(del);
                                del.setOnClickListener(
                                        v -> {
                                            a.prefs
                                                    .edit()
                                                    .putString(
                                                            Prefs.WATCH_HISTORY,
                                                            WatchHistory.remove(
                                                                    a.prefs.getString(
                                                                            Prefs.WATCH_HISTORY,
                                                                            "[]"),
                                                                    e.key()))
                                                    .apply();
                                            run();
                                        });
                                row.addView(del);

                                LinearLayout.LayoutParams lp =
                                        new LinearLayout.LayoutParams(-1, -2);
                                lp.bottomMargin = a.dp(8);
                                listBody.addView(row, lp);
                            }
                        } else {
                            List<Favorites.Entry> favs =
                                    Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
                            if (favs.isEmpty()) {
                                renderCenteredHint(listBody, "还没有收藏任何影片");
                                return;
                            }
                            for (final Favorites.Entry f : favs) {
                                LinearLayout row = a.row();
                                row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                                row.setBackground(a.bg(0xff181922, 14));
                                MobileDetailScreen.bindTouchFeedback(row);

                                ImageView thumb = new ImageView(a);
                                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                thumb.setBackground(a.bg(0xff252734, 8));
                                thumb.setClipToOutline(true);
                                LinearLayout.LayoutParams thumbLp =
                                        new LinearLayout.LayoutParams(a.dp(52), a.dp(72));
                                thumbLp.rightMargin = a.dp(12);
                                row.addView(thumb, thumbLp);
                                if (f.poster != null && !f.poster.isEmpty())
                                    a.poster(f.poster, thumb);

                                LinearLayout info = a.vertical();
                                TextView t = a.text(f.title, 15, MainActivity.WHITE);
                                t.setTypeface(null, Typeface.BOLD);
                                t.setSingleLine(true);
                                t.setEllipsize(TextUtils.TruncateAt.END);
                                info.addView(t);

                                TextView sub = a.text("已收藏 · 点击查看详情", 12, MainActivity.MUTED);
                                sub.setPadding(0, a.dp(5), 0, 0);
                                info.addView(sub);
                                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                                row.setOnClickListener(
                                        v -> {
                                            Cms.Site targetSite = null;
                                            if (a.config != null) {
                                                for (Cms.Site s : a.config.sites) {
                                                    if (s.key.equals(f.site)) {
                                                        targetSite = s;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (targetSite == null) targetSite = currentSite;
                                            if (targetSite == null) {
                                                a.toast("该收藏所属的片源不可用");
                                                return;
                                            }
                                            final Cms.Site resolvedSite = targetSite;
                                            Cms.Video video = f.video();
                                            a.open(
                                                    new MainActivity.Screen(
                                                            () -> a.detail(resolvedSite, video)),
                                                    true);
                                        });

                                LinearLayout.LayoutParams lp =
                                        new LinearLayout.LayoutParams(-1, -2);
                                lp.bottomMargin = a.dp(8);
                                listBody.addView(row, lp);
                            }
                        }
                    }
                };

        MobileDetailScreen.bindTouchFeedback(tabHist);
        MobileDetailScreen.bindTouchFeedback(tabFav);
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        tabHist.setOnClickListener(
                v -> {
                    showFavorites[0] = false;
                    renderSubTab.run();
                });
        tabFav.setOnClickListener(
                v -> {
                    showFavorites[0] = true;
                    renderSubTab.run();
                });
        clearBtn.setOnClickListener(
                v -> {
                    a.prefs.edit().putString(Prefs.WATCH_HISTORY, "[]").apply();
                    renderSubTab.run();
                });

        renderSubTab.run();
    }

    // =========================================================================
    // TAB 4: MOBILE SETTINGS (Grouped Touch Cards)
    // =========================================================================
    private void renderSettingsTab() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = a.vertical();
        body.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(32));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));

        addSectionHeader(body, "片源与配置管理");
        addSettingCard(
                body, "📋 从剪贴板一键粘贴配置", "直接读取你在手机上复制的配置网址或 JSON", () -> pasteConfigFromClipboard(a));
        addSettingCard(
                body,
                "🌐 输入或修改 HTTP(S) 配置网址",
                a.configOrigin.isEmpty() ? "当前未设置远程配置网址" : a.configOrigin,
                () -> showMobileImportDialog(a));
        addSettingCard(body, "📂 选择手机本地 JSON 文件", "从手机存储导入兼容配置文件", a::pickConfig);
        addSettingCard(
                body,
                "🔀 切换默认片源",
                currentSite == null ? "未选择" : "当前：" + currentSite.name,
                a::chooseSources);
        boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false);
        addSettingCard(
                body,
                "🧩 启用第三方扩展片源 (JAR / JS)",
                cspEnabled ? "当前状态：已开启" : "当前状态：已关闭（点击开启）",
                () -> {
                    a.prefs.edit().putBoolean(Prefs.EXPERIMENTAL_CSP, !cspEnabled).apply();
                    a.closeEngines();
                    renderSettingsTab();
                    a.toast(!cspEnabled ? "已开启扩展片源支持" : "已关闭扩展片源支持");
                });

        a.gap(body, 12);
        addSectionHeader(body, "播放与解码偏好");
        int engine = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
        addSettingCard(
                body,
                "🎬 播放器内核",
                engine == 0 ? "Media3（自带软解与字幕增强）" : "Android 系统播放器",
                () -> {
                    int next = engine == 0 ? 1 : 0;
                    a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, next).apply();
                    renderSettingsTab();
                });
        int decoder = a.prefs.getInt(Prefs.DECODER_MODE, 0);
        String[] decNames = {"自动选择（推荐）", "硬件解码优先", "软件解码优先"};
        addSettingCard(
                body,
                "⚡ 视频解码方式",
                decNames[Math.max(0, Math.min(2, decoder))],
                () -> {
                    int next = (decoder + 1) % 3;
                    a.prefs.edit().putInt(Prefs.DECODER_MODE, next).apply();
                    renderSettingsTab();
                });
        boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
        addSettingCard(
                body,
                "⏭ 自动连播下一集",
                autoNext ? "已开启" : "已关闭",
                () -> {
                    a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, !autoNext).apply();
                    renderSettingsTab();
                });

        a.gap(body, 12);
        addSectionHeader(body, "备份、更新与系统诊断");
        addSettingCard(
                body,
                "💾 完整设置与数据高级选项",
                "备份导出与恢复、网盘账号登录、字幕与壁纸设置",
                () -> a.open(new MainActivity.Screen(() -> new SettingsScreen(a).show()), true));
        addSettingCard(
                body,
                "🔄 检查软件更新",
                "当前版本：0.4.16 Preview",
                () -> a.open(new MainActivity.Screen(() -> new UpdateScreen(a).show()), true));
        addSettingCard(
                body, "📋 查看运行诊断日志", "排查片源网络请求与播放解码报错", () -> new SettingsScreen(a).diagnostics());
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView h = a.text(title, 12, 0xff8ab4f8);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        parent.addView(h);
    }

    private void addSettingCard(
            LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout row = a.row();
        row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        row.setBackground(a.bg(0xff181922, 14));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(v -> action.run());

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 14, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, 12, MainActivity.MUTED);
        s.setPadding(0, a.dp(3), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 20, MainActivity.MUTED));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(8);
        parent.addView(row, lp);
    }

    private void renderCenteredHint(LinearLayout parent, String message) {
        TextView t = a.text(message, 14, MainActivity.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(a.dp(20), a.dp(48), a.dp(20), a.dp(48));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Custom vector icons for the 5 mobile bottom navigation tabs. */
    static final class NavGlyph extends View {
        private final MainActivity a;
        private final int kind;
        private boolean selected;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavGlyph(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        void setSelectedState(boolean value) {
            this.selected = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            paint.setColor(selected ? 0xff3b82f6 : 0xff9ca3af);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(a.dp(1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (kind == 0) {
                // Home icon
                Path p = new Path();
                p.moveTo(a.dp(3), cy);
                p.lineTo(cx, a.dp(3));
                p.lineTo(w - a.dp(3), cy);
                c.drawPath(p, paint);
                c.drawRoundRect(
                        new RectF(a.dp(5), cy - a.dp(1), w - a.dp(5), h - a.dp(3)),
                        a.dp(2),
                        a.dp(2),
                        paint);
            } else if (kind == 1) {
                // Search icon
                float r = a.dp(6);
                c.drawCircle(cx - a.dp(1.5f), cy - a.dp(1.5f), r, paint);
                c.drawLine(cx + a.dp(3), cy + a.dp(3), w - a.dp(3), h - a.dp(3), paint);
            } else if (kind == 2) {
                // Live TV icon
                c.drawRoundRect(
                        new RectF(a.dp(3), a.dp(6), w - a.dp(3), h - a.dp(4)),
                        a.dp(3),
                        a.dp(3),
                        paint);
                c.drawLine(cx - a.dp(4), a.dp(2), cx, a.dp(6), paint);
                c.drawLine(cx + a.dp(4), a.dp(2), cx, a.dp(6), paint);
            } else if (kind == 3) {
                // Bookmark / Library icon
                Path p = new Path();
                p.moveTo(a.dp(5), a.dp(3));
                p.lineTo(w - a.dp(5), a.dp(3));
                p.lineTo(w - a.dp(5), h - a.dp(3));
                p.lineTo(cx, h - a.dp(7));
                p.lineTo(a.dp(5), h - a.dp(3));
                p.close();
                c.drawPath(p, paint);
            } else {
                // Settings sliders icon
                c.drawLine(a.dp(3), a.dp(6), w - a.dp(3), a.dp(6), paint);
                c.drawLine(a.dp(3), cy, w - a.dp(3), cy, paint);
                c.drawLine(a.dp(3), h - a.dp(6), w - a.dp(3), h - a.dp(6), paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - a.dp(3), a.dp(6), a.dp(2.3f), paint);
                c.drawCircle(cx + a.dp(3), cy, a.dp(2.3f), paint);
                c.drawCircle(cx - a.dp(1), h - a.dp(6), a.dp(2.3f), paint);
            }
        }
    }
}
