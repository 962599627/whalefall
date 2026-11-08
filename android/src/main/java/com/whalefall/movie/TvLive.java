package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 电视台直播：学习 WebViewTvLive 的思路，把官方电视台直播网页内置进 App。
 *
 * <p>频道列表内置央视各频道，支持自定义频道与 JSON 导入；播放时用全屏 WebView 加载官方页面，
 * 自动注入脚本点击播放，支持数字键换台、上下滑调亮/音量、返回键退出。
 */
final class TvLive {
    private TvLive() {}

    static final String CUSTOM_FILE = "tv_channels.json";
    static final String FAVORITES = "tv_favorites";
    static final String LAST = "tv_last";

    /** 一个频道：名称、官方直播页地址、分组。 */
    static final class Channel {
        final String name;
        final String url;
        final String group;

        Channel(String name, String url, String group) {
            this.name = name;
            this.url = url;
            this.group = group;
        }
    }

    static final String GROUP_CCTV = "央视频道";
    static final String GROUP_SAT = "卫视频道";
    static final String GROUP_MINE = "我的收藏";

    static final Object[][] CCTV = {
        {"CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"},
        {"CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"},
        {"CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"},
        {"CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"},
        {"CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"},
        {"CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"},
        {"CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"},
        {"CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"},
        {"CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"},
        {"CCTV-9 纪录", "https://tv.cctv.com/live/cctv9/"},
        {"CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"},
        {"CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"},
        {"CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"},
        {"CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"},
        {"CCTV-14 少儿", "https://tv.cctv.com/live/cctv14/"},
        {"CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"},
        {"CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"},
        {"CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"},
    };

    static final Object[][] SAT = {
        {"湖南卫视", "https://live.mgtv.com/"},
        {"东方卫视", "https://live.kankanews.com/"},
        {"江苏卫视", "https://live.jstv.com/"},
        {"山东卫视", "https://v.iqilu.com/live/sdtv/"},
        {"山东齐鲁", "https://v.iqilu.com/live/qlpd/"},
        {"山东公共", "https://v.iqilu.com/live/ggpd/"},
        {"山东农科", "https://v.iqilu.com/live/nkpd/"},
        {"江西卫视", "https://www.jxntv.cn/live/"},
        {"福建新闻", "http://live.fjtv.net/zhpd/"},
        {"广西卫视", "https://live.gxtv.cn/"},
        {"云南都市", "https://www.yntv.cn/mobile/live.html?pid=yunnandushi"},
        {"云南公共", "https://www.yntv.cn/mobile/live.html?pid=yunnangonggong"},
        {"云南国际", "https://www.yntv.cn/mobile/live.html?pid=yunnanguoji"},
        {"贵州卫视", "https://www.gzstv.com/"},
        {"四川卫视", "https://www.sctv.com/"},
        {"重庆卫视", "https://www.cqnews.net/"},
        {"湖北卫视", "http://www.hbtv.com.cn/"},
        {"黑龙江卫视", "https://www.hljtv.com/"},
        {"山西卫视", "https://www.sxrtv.com/"},
        {"新疆卫视", "https://www.xjtvs.com.cn/"},
        {"海南卫视", "https://www.hnntv.cn/"},
        {"深圳卫视", "http://www.sztv.com.cn/"},
        {"广东卫视", "https://www.gdtv.cn/"},
        {"安徽卫视", "https://www.ahtv.cn/"},
        {"河北卫视", "https://www.hebtv.com/"},
        {"宁夏卫视", "https://www.nxtv.com.cn/"},
        {"甘肃卫视", "https://www.gstv.com.cn/"},
        {"内蒙古卫视", "https://www.nmtv.cn/"},
        {"TVB 翡翠台", "https://www.tvb.com/"},
    };

    static final class Store {
        final List<Channel> builtin = new ArrayList<>();
        final List<Channel> custom = new ArrayList<>();
        final Set<String> favorites = new LinkedHashSet<>();

        Store(MainActivity a) {
            for (Object[] row : CCTV)
                builtin.add(new Channel((String) row[0], (String) row[1], GROUP_CCTV));
            for (Object[] row : SAT)
                builtin.add(new Channel((String) row[0], (String) row[1], GROUP_SAT));
            custom.addAll(readCustom(a));
            favorites.addAll(readFavorites(a));
        }

        /** 全部频道（内置 + 自定义），顺序稳定，不做收藏置顶。 */
        List<Channel> every() {
            List<Channel> list = new ArrayList<>(builtin);
            list.addAll(custom);
            return list;
        }

        /** 旧接口：收藏频道在前，播放器/兼容调用仍在用。 */
        List<Channel> all() {
            List<Channel> list = every();
            List<Channel> fav = new ArrayList<>();
            for (Channel c : list) if (favorites.contains(c.name)) fav.add(c);
            if (!fav.isEmpty()) {
                List<Channel> out = new ArrayList<>(fav);
                out.addAll(list);
                return out;
            }
            return list;
        }

        List<Channel> group(String group) {
            List<Channel> source = every();
            if (GROUP_MINE.equals(group)) {
                List<Channel> fav = new ArrayList<>();
                for (Channel c : source) if (favorites.contains(c.name)) fav.add(c);
                return fav;
            }
            List<Channel> list = new ArrayList<>();
            for (Channel c : source) if (group.equals(c.group)) list.add(c);
            return list;
        }

        Channel byName(String name) {
            for (Channel c : every()) if (c.name.equals(name)) return c;
            return null;
        }
    }

    // ======================= 数据持久化 =======================

    // ======================= 页面 =======================

    /** 电视台直播页的交互状态：分组 / 搜索 / 当前高亮频道 / 重渲染入口。 */
    static final class PageState {
        final MainActivity a;
        final Store store;
        final boolean wide;
        final int sidePad;
        final int railWidth;
        final int previewWidth;
        FrameLayout contentSlot;
        TextView subTitle;
        EditText search;
        TextView clearBtn;
        String group = GROUP_CCTV;
        String query = "";
        String selected;
        int listScrollY;
        Runnable render;

        PageState(
                MainActivity a,
                Store store,
                boolean wide,
                int sidePad,
                int railWidth,
                int previewWidth) {
            this.a = a;
            this.store = store;
            this.wide = wide;
            this.sidePad = sidePad;
            this.railWidth = railWidth;
            this.previewWidth = previewWidth;
        }

        void refresh() {
            if (render != null) render.run();
        }

        /** 切换分组：清空搜索、重置高亮，然后整页重绘。 */
        void pickGroup(String g) {
            group = g;
            selected = null;
            query = "";
            if (search != null) search.setText("");
            refresh();
        }

        /** 实时搜索：频道名 / 去分隔符全名 / 拼音首字母都能命中。 */
        void setSearch(String raw) {
            String next = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
            if (clearBtn != null) {
                clearBtn.setVisibility(next.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            }
            if (next.equals(query)) return;
            query = next;
            selected = null;
            refresh();
        }
    }

    static View screen(MainActivity a) {
        final Store store = new Store(a);
        final float density = a.getResources().getDisplayMetrics().density;
        final int windowDp = Math.round(a.windowWidth() / density);
        // 平板横向：左侧分类栏 + 中间编号列表 + 右侧频道信息三段式；手机/竖屏退化为单列列表
        final boolean wide = windowDp >= 720;
        final int sidePad = a.dp(wide ? 20 : 16);
        final PageState page =
                new PageState(
                        a,
                        store,
                        wide,
                        sidePad,
                        a.dp(wide ? 150 : 0),
                        a.dp(wide ? 252 : 0));

        FrameLayout root = new FrameLayout(a);
        root.setBackground(Skin.backdrop(a));

        LinearLayout shell = a.vertical();
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        // ---------------- 吸顶头部：标题 / 说明 / 实时搜索 ----------------
        LinearLayout header = a.vertical();
        header.setPadding(
                sidePad,
                MobileDetailScreen.getStatusBarHeight(a) + a.dp(6),
                sidePad,
                0);
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = a.text("电视台直播", wide ? 27 : 24, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        titleRow.addView(title);

        if (wide) {
            LinearLayout livePill = a.row();
            livePill.setGravity(Gravity.CENTER_VERTICAL);
            livePill.setPadding(a.dp(9), 0, a.dp(11), 0);
            livePill.setBackground(MobileDetailScreen.glassBg(a, 0x1fff8fb1, 0x55ffb3c8, 999));
            livePill.addView(
                    TvUiKit.dot(a, 0xffff6f96, 6),
                    new LinearLayout.LayoutParams(a.dp(6), a.dp(6)));
            TextView liveTxt = a.text("直播中", 11, 0xffffb3c8);
            liveTxt.setTypeface(null, Typeface.BOLD);
            liveTxt.setIncludeFontPadding(false);
            LinearLayout.LayoutParams liveTxtLp = new LinearLayout.LayoutParams(-2, -2);
            liveTxtLp.leftMargin = a.dp(5);
            livePill.addView(liveTxt, liveTxtLp);
            LinearLayout.LayoutParams livePillLp = new LinearLayout.LayoutParams(-2, a.dp(26));
            livePillLp.leftMargin = a.dp(10);
            titleRow.addView(livePill, livePillLp);
        }

        View titleSpacer = new View(a);
        titleRow.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        if (!wide) {
            TextView addBtn = TvUiKit.pill(a, "＋ 自定义", 12, false);
            addBtn.setOnClickListener(v -> addChannelDialog(a, store, () -> page.refresh()));
            titleRow.addView(addBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));
            TextView importBtn = TvUiKit.pill(a, "导入列表", 12, false);
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-2, a.dp(34));
            importLp.leftMargin = a.dp(8);
            importBtn.setOnClickListener(v -> importDialog(a, store));
            titleRow.addView(importBtn, importLp);
        }
        header.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(6), 0, 0);
        if (!wide) {
            subRow.addView(
                    TvUiKit.dot(a, 0xffff6f96, 5),
                    new LinearLayout.LayoutParams(a.dp(5), a.dp(5)));
        }
        TextView subTitle = a.text("", 11.5f, 0xffa9b7d0);
        subTitle.setSingleLine(true);
        subTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (!wide) subLp.leftMargin = a.dp(6);
        subRow.addView(subTitle, subLp);
        header.addView(subRow, new LinearLayout.LayoutParams(-1, -2));
        page.subTitle = subTitle;

        // 搜索：实时过滤频道名 / 拼音首字母
        final EditText input = new EditText(a);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setHint("搜索频道或拼音首字母，如 hnws、cctv5");
        input.setHintTextColor(0xff7f8fa9);
        input.setTextColor(0xfff5f7fc);
        input.setTextSize(13.5f);
        input.setIncludeFontPadding(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(a.dp(8), 0, a.dp(8), 0);
        page.search = input;

        LinearLayout searchRow = a.row();
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, a.dp(8), 0, a.dp(2));

        LinearLayout searchBox = a.row();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(a.dp(10), 0, a.dp(6), 0);
        searchBox.setBackground(MobileDetailScreen.glassBg(a, 0x17ffffff, 0x2effffff, 16));
        searchBox.setClickable(true);
        searchBox.addView(
                new TvUiKit.SearchGlyph(a, 0xff8d9cb6),
                new LinearLayout.LayoutParams(a.dp(16), a.dp(16)));
        searchBox.addView(input, new LinearLayout.LayoutParams(0, a.dp(42), 1f));

        final TextView clearBtn = a.text("×", 15, 0x99e6edf9);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setVisibility(View.INVISIBLE);
        clearBtn.setOnClickListener(
                v -> {
                    input.setText("");
                    hideSoftKeyboard(a, input);
                });
        searchBox.addView(clearBtn, new LinearLayout.LayoutParams(a.dp(28), a.dp(28)));
        page.clearBtn = clearBtn;

        searchRow.addView(
                searchBox,
                wide
                        ? new LinearLayout.LayoutParams(a.dp(520), a.dp(42))
                        : new LinearLayout.LayoutParams(-1, a.dp(42)));
        if (wide) {
            View searchSpacer = new View(a);
            searchRow.addView(searchSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
            TextView searchHint = a.text("点频道直接播放 · 长按收藏", 11, 0xff6f7f99);
            searchRow.addView(searchHint);
        }
        header.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));

        searchBox.setOnClickListener(
                v -> {
                    input.requestFocus();
                    showSoftKeyboard(a, input);
                });
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    hideSoftKeyboard(a, input);
                    return true;
                });
        input.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        page.setSearch(s.toString());
                    }
                });

        // ---------------- 内容区（整块重绘） ----------------
        final FrameLayout contentSlot = new FrameLayout(a);
        shell.addView(contentSlot, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.contentSlot = contentSlot;

        page.render = () -> renderTvPage(page);
        page.refresh();
        return root;
    }

    /** 重绘内容区：三段式（平板横向）或单列（手机竖屏）。 */
    static void renderTvPage(PageState page) {
        final MainActivity a = page.a;
        final Store store = page.store;
        final boolean wide = page.wide;
        final int sidePad = page.sidePad;

        final String q = page.query;
        final boolean searching = !q.isEmpty();
        final List<Channel> list =
                searching ? searchChannels(store, q) : store.group(page.group);
        if (page.selected == null || findChannel(list, page.selected) == null) {
            page.selected = list.isEmpty() ? null : list.get(0).name;
        }
        final Channel chosen = findChannel(list, page.selected);

        final int cctvCount = store.group(GROUP_CCTV).size();
        final int satCount = store.group(GROUP_SAT).size();
        final int favCount = store.favorites.size();
        final int total = store.every().size();
        if (page.subTitle != null) {
            if (wide) {
                page.subTitle.setText(
                        total
                                + " 个频道 · 央视 "
                                + cctvCount
                                + " · 卫视 "
                                + satCount
                                + " · 官方网页直播，数字键换台，上下滑调亮度 / 音量");
            } else {
                page.subTitle.setText("共 " + total + " 个频道 · 官方网页直播，轻点频道直接播放");
            }
        }
        if (page.clearBtn != null) {
            page.clearBtn.setVisibility(searching ? View.VISIBLE : View.INVISIBLE);
        }

        page.contentSlot.removeAllViews();

        if (!wide) {
            LinearLayout body = a.vertical();
            FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(-1, -1);
            // 底部给悬浮 Dock 留出空间
            bodyLp.setMargins(sidePad, a.dp(4), sidePad, a.dp(96));
            page.contentSlot.addView(body, bodyLp);

            HorizontalScrollView chipScroll = new HorizontalScrollView(a);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.setClipToPadding(false);
            LinearLayout chipRow = a.row();
            chipScroll.addView(chipRow);

            String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_MINE};
            int[] counts = {cctvCount, satCount, favCount};
            for (int i = 0; i < gs.length; i++) {
                final String g = gs[i];
                boolean on = !searching && g.equals(page.group);
                LinearLayout chip = a.row();
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(a.dp(13), 0, a.dp(13), 0);
                chip.setBackground(
                        on
                                ? TvUiKit.accentFill(a, 999)
                                : MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
                MobileDetailScreen.bindTouchFeedback(chip);
                TextView nm = a.text(gs[i], 12.5f, on ? 0xff31141f : 0xffd6e1f0);
                nm.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                chip.addView(nm);
                TextView ct =
                        a.text(String.valueOf(counts[i]), 10.5f, on ? 0xa631141f : 0xff8d9cb6);
                LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(-2, -2);
                ctLp.leftMargin = a.dp(6);
                chip.addView(ct, ctLp);
                chip.setOnClickListener(v -> page.pickGroup(g));
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, a.dp(38));
                chipLp.rightMargin = a.dp(8);
                chipRow.addView(chip, chipLp);
            }
            body.addView(chipScroll, new LinearLayout.LayoutParams(-1, a.dp(42)));

            LinearLayout mid = buildMiddle(page, list, chosen);
            LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            midLp.topMargin = a.dp(8);
            body.addView(mid, midLp);
        } else {
            LinearLayout bodyRow = a.row();
            bodyRow.setGravity(Gravity.TOP);
            FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(-1, -1);
            bodyLp.setMargins(sidePad, a.dp(8), sidePad, a.dp(20));
            page.contentSlot.addView(bodyRow, bodyLp);

            bodyRow.addView(
                    buildCategoryRail(page, cctvCount, satCount, favCount),
                    new LinearLayout.LayoutParams(page.railWidth, -1));
            View gap1 = new View(a);
            bodyRow.addView(gap1, new LinearLayout.LayoutParams(a.dp(12), 1));

            bodyRow.addView(
                    buildMiddle(page, list, chosen), new LinearLayout.LayoutParams(0, -1, 1f));
            View gap2 = new View(a);
            bodyRow.addView(gap2, new LinearLayout.LayoutParams(a.dp(12), 1));

            bodyRow.addView(
                    buildPreview(page, chosen),
                    new LinearLayout.LayoutParams(page.previewWidth, -1));
        }
    }

    /** 左侧分组栏：名称 + 数量，选中项用樱花粉渐变块。 */
    static LinearLayout buildCategoryRail(
            PageState page, int cctvCount, int satCount, int favCount) {
        final MainActivity a = page.a;
        LinearLayout rail = a.vertical();
        rail.setPadding(a.dp(9), a.dp(12), a.dp(9), a.dp(12));
        rail.setBackground(MobileDetailScreen.glassBg(a, 0x14ffffff, 0x24ffffff, 22));

        TextView cap = a.text("频道分类", 10.5f, 0xff8d9cb6);
        cap.setLetterSpacing(0.1f);
        cap.setPadding(a.dp(7), 0, 0, a.dp(8));
        rail.addView(cap, new LinearLayout.LayoutParams(-1, -2));

        String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_MINE};
        int[] counts = {cctvCount, satCount, favCount};
        for (int i = 0; i < gs.length; i++) {
            final String g = gs[i];
            boolean on = page.query.isEmpty() && g.equals(page.group);
            LinearLayout item = a.row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(a.dp(10), 0, a.dp(10), 0);
            if (on) item.setBackground(TvUiKit.accentFill(a, 14));
            MobileDetailScreen.bindTouchFeedback(item);
            item.setClickable(true);

            TextView name = a.text(gs[i], 14, on ? 0xff31141f : 0xffd6e1f0);
            name.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            item.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView count = a.text(String.valueOf(counts[i]), 12, on ? 0xa631141f : 0xff8d9cb6);
            count.setTypeface(null, Typeface.BOLD);
            item.addView(count);

            item.setOnClickListener(v -> page.pickGroup(g));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(46));
            if (i > 0) lp.topMargin = a.dp(6);
            rail.addView(item, lp);
        }

        View fill = new View(a);
        rail.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));

        View divider = new View(a);
        divider.setBackgroundColor(0x1affffff);
        LinearLayout.LayoutParams divLp =
                new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
        divLp.topMargin = a.dp(10);
        divLp.bottomMargin = a.dp(6);
        rail.addView(divider, divLp);

        // 底部两个并排胶囊：＋ 添加 / 导入
        LinearLayout actions = a.row();
        LinearLayout addPill = a.row();
        addPill.setGravity(Gravity.CENTER);
        addPill.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x40ffb3c8, 999));
        MobileDetailScreen.bindTouchFeedback(addPill);
        addPill.addView(
                new TvUiKit.PlusGlyph(a, 0xffffb3c8),
                new LinearLayout.LayoutParams(a.dp(11), a.dp(11)));
        TextView addTxt = a.text("添加", 11, 0xffffb3c8);
        LinearLayout.LayoutParams addTxtLp = new LinearLayout.LayoutParams(-2, -2);
        addTxtLp.leftMargin = a.dp(4);
        addPill.addView(addTxt, addTxtLp);
        addPill.setOnClickListener(v -> addChannelDialog(a, page.store, () -> page.refresh()));
        LinearLayout.LayoutParams addPillLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
        addPillLp.rightMargin = a.dp(6);
        actions.addView(addPill, addPillLp);

        LinearLayout impPill = a.row();
        impPill.setGravity(Gravity.CENTER);
        impPill.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
        MobileDetailScreen.bindTouchFeedback(impPill);
        impPill.addView(
                new TvUiKit.ImportGlyph(a, 0xffc9d6ea),
                new LinearLayout.LayoutParams(a.dp(11), a.dp(11)));
        TextView impTxt = a.text("导入", 11, 0xffc9d6ea);
        LinearLayout.LayoutParams impTxtLp = new LinearLayout.LayoutParams(-2, -2);
        impTxtLp.leftMargin = a.dp(4);
        impPill.addView(impTxt, impTxtLp);
        impPill.setOnClickListener(v -> importDialog(a, page.store));
        actions.addView(impPill, new LinearLayout.LayoutParams(0, a.dp(34), 1f));
        rail.addView(actions, new LinearLayout.LayoutParams(-1, a.dp(34)));

        TextView tip = a.text("长按频道可收藏", 9.5f, 0xff6f7f99);
        tip.setPadding(0, a.dp(7), 0, 0);
        rail.addView(tip, new LinearLayout.LayoutParams(-1, -2));
        return rail;
    }

    /** 中间编号频道列表（平板）或单列列表（手机）。 */
    static LinearLayout buildMiddle(PageState page, List<Channel> list, Channel chosen) {
        final MainActivity a = page.a;
        final boolean wide = page.wide;
        LinearLayout mid = a.vertical();
        mid.setBackground(MobileDetailScreen.glassBg(a, 0x10ffffff, 0x1fffffff, 22));

        LinearLayout head = a.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(a.dp(14), a.dp(9), a.dp(14), a.dp(6));
        TextView h = a.text(page.query.isEmpty() ? page.group : "搜索结果", wide ? 15.5f : 15, 0xfff5f7fc);
        h.setTypeface(null, Typeface.BOLD);
        head.addView(h);
        TextView count = a.text(list.size() + " 个", 10.5f, 0xffffb3c8);
        count.setGravity(Gravity.CENTER);
        count.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        count.setBackground(MobileDetailScreen.glassBg(a, 0x1fff8fb1, 0x33ffb3c8, 999));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.leftMargin = a.dp(8);
        head.addView(count, countLp);

        View gap = new View(a);
        head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView hint =
                a.text(page.query.isEmpty() ? "长按收藏" : "× 清空搜索", wide ? 10.5f : 11, 0xff7f8fa9);
        hint.setOnClickListener(
                v -> {
                    if (page.query.isEmpty()) page.a.toast("长按频道即可收藏");
                    else page.search.setText("");
                });
        head.addView(hint);
        mid.addView(head, new LinearLayout.LayoutParams(-1, -2));

        final ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.getViewTreeObserver()
                .addOnScrollChangedListener(() -> page.listScrollY = scroll.getScrollY());

        LinearLayout rows = a.vertical();
        rows.setPadding(a.dp(5), 0, a.dp(5), a.dp(10));
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        mid.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (list.isEmpty()) {
            rows.addView(
                    emptyState(
                            a,
                            !page.query.isEmpty(),
                            page.query.isEmpty() ? "收藏夹还是空的" : "没有找到频道",
                            page.query.isEmpty()
                                    ? "长按任意频道，或点列表右侧的 ♡，就能收藏到这里"
                                    : "换个关键词试试，频道名或拼音首字母都可以，例如 hnws、cctv5",
                            page.query.isEmpty() ? "去央视频道看看" : "清空搜索",
                            () -> {
                                if (page.query.isEmpty()) page.pickGroup(GROUP_CCTV);
                                else page.search.setText("");
                            }),
                    new LinearLayout.LayoutParams(-1, -2));
            return mid;
        }

        final int rowH = a.dp(wide ? 40 : 56);
        for (int i = 0; i < list.size(); i++) {
            final Channel ch = list.get(i);
            final String openGroup = page.query.isEmpty() ? page.group : ch.group;
            final boolean active = chosen != null && ch.name.equals(chosen.name);
            final boolean fav = page.store.favorites.contains(ch.name);
            LinearLayout row =
                    channelRow(
                            page,
                            ch,
                            i + 1,
                            active,
                            fav,
                            () -> {
                                page.selected = ch.name;
                                open(page.a, page.store, openGroup, ch, () -> page.refresh());
                            },
                            () -> {
                                boolean now = toggleFavorite(page.a, page.store, ch);
                                page.a.toast((now ? "已收藏 " : "已取消收藏 ") + ch.name);
                                page.refresh();
                            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, rowH);
            if (i > 0) lp.topMargin = a.dp(1);
            rows.addView(row, lp);
        }
        final int savedY = page.listScrollY;
        if (savedY > 0) {
            scroll.post(() -> scroll.scrollTo(0, savedY));
        } else if (page.query.isEmpty()) {
            String lastWatched = a.prefs.getString(LAST, "");
            if (!lastWatched.isEmpty()) {
                String lastName = lastWatched.split("\n", 2)[0];
                for (int i = 0; i < list.size(); i++)
                    if (list.get(i).name.equals(lastName)) {
                        final int target = i * (rowH + a.dp(1));
                        scroll.post(() -> scroll.scrollTo(0, Math.max(0, target - a.dp(60))));
                        break;
                    }
            }
        }
        return mid;
    }

    /** 一行频道：编号 + 台标色块 + 名称 + 直播状态/收藏。 */
    static LinearLayout channelRow(
            PageState page,
            Channel ch,
            int number,
            boolean active,
            boolean fav,
            Runnable onOpen,
            Runnable onFav) {
        final MainActivity a = page.a;
        final boolean wide = page.wide;
        final TvUiKit.Brand brand = TvUiKit.brand(ch.name);

        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(6), 0, a.dp(8), 0);
        if (active) {
            row.setBackground(MobileDetailScreen.glassBg(a, 0x2bff8fb1, 0x66ffb3c8, 14));
        }
        MobileDetailScreen.bindTouchFeedback(row);
        row.setClickable(true);
        row.setOnClickListener(v -> onOpen.run());
        row.setOnLongClickListener(
                v -> {
                    onFav.run();
                    return true;
                });

        TextView num =
                a.text(
                        String.format(Locale.US, "%02d", number),
                        wide ? 13.5f : 13,
                        active ? 0xffffd4df : 0xff6f7f99);
        num.setTypeface(null, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        row.addView(num, new LinearLayout.LayoutParams(a.dp(30), -2));

        FrameLayout tile = new FrameLayout(a);
        int tileSize = a.dp(26);
        tile.setBackground(TvUiKit.brandBlock(a, brand, 8));
        TextView mk =
                a.text(brand.mark, brand.mark.length() >= 3 ? 8 : 10.5f, 0xfff7fbff);
        mk.setTypeface(null, Typeface.BOLD);
        mk.setGravity(Gravity.CENTER);
        tile.addView(mk, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

        String lastWatched = a.prefs.getString(LAST, "");
        if (lastWatched.startsWith(ch.name + "\n")) {
            TextView last = a.text("上次", 9, 0xff31141f);
            last.setGravity(Gravity.CENTER);
            last.setPadding(a.dp(7), 0, a.dp(7), 0);
            last.setBackground(a.bg(0xffffc7d6, 999));
            LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(-2, a.dp(18));
            lastLp.rightMargin = a.dp(6);
            row.addView(last, lastLp);
        }
        TextView name = a.text(ch.name, wide ? 15.5f : 15, active ? 0xffffe3ea : 0xffe8eff9);
        name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
        nameLp.leftMargin = a.dp(9);
        row.addView(name, nameLp);

        if (active) {
            // 当前台只留一个樱花粉小圆点，避免和编号 / 名称抢横向空间
            View liveDot = TvUiKit.dot(a, 0xffff8fb1, 6);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(a.dp(6), a.dp(6));
            dotLp.leftMargin = a.dp(6);
            row.addView(liveDot, dotLp);
        }

        TextView heart = a.text(fav ? "♥" : "♡", 13, fav ? 0xffff8fb1 : 0x66c9d6ea);
        heart.setGravity(Gravity.CENTER);
        heart.setOnClickListener(v -> onFav.run());
        MobileDetailScreen.bindTouchFeedback(heart);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(a.dp(24), a.dp(24));
        heartLp.leftMargin = a.dp(4);
        row.addView(heart, heartLp);
        return row;
    }

    /** 右侧频道信息 / 预览面板。 */
    static LinearLayout buildPreview(PageState page, Channel chosen) {
        final MainActivity a = page.a;
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(14));
        panel.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 24));

        if (chosen == null) {
            panel.addView(
                    emptyState(
                            a,
                            !page.query.isEmpty(),
                            page.query.isEmpty() ? "收藏夹还是空的" : "没有找到频道",
                            "在左侧切换分组，或换个关键词再试",
                            page.query.isEmpty() ? "去央视频道看看" : "清空搜索",
                            () -> {
                                if (page.query.isEmpty()) page.pickGroup(GROUP_CCTV);
                                else page.search.setText("");
                            }),
                    new LinearLayout.LayoutParams(-1, 0, 1f));
            return panel;
        }

        final TvUiKit.Brand brand = TvUiKit.brand(chosen.name);
        final Channel sel = chosen;

        // 16:9 台标卡：横排台标字 + 右上角直播状态，代替视频预览占位
        FrameLayout hero = new FrameLayout(a);
        hero.setBackground(TvUiKit.brandBlock(a, brand, 18));
        LinearLayout logoRow = a.row();
        logoRow.setGravity(Gravity.CENTER_VERTICAL);
        if ("卫视".equals(brand.wordmark)) {
            TextView prov = a.text(brand.mark, 34, 0xfff7fbff);
            prov.setTypeface(null, Typeface.BOLD);
            prov.setIncludeFontPadding(false);
            logoRow.addView(prov);
            TextView ws = a.text("卫视", 14, 0xc9ffffff);
            ws.setTypeface(null, Typeface.BOLD);
            ws.setIncludeFontPadding(false);
            LinearLayout.LayoutParams wsLp = new LinearLayout.LayoutParams(-2, -2);
            wsLp.leftMargin = a.dp(7);
            logoRow.addView(ws, wsLp);
        } else if (brand.mark.length() <= 2) {
            TextView word = a.text(brand.wordmark, 21, 0xe6ffffff);
            word.setTypeface(null, Typeface.BOLD);
            word.setIncludeFontPadding(false);
            word.setLetterSpacing(0.14f);
            logoRow.addView(word);
            View sep = new View(a);
            sep.setBackgroundColor(0x59ffffff);
            LinearLayout.LayoutParams sepLp =
                    new LinearLayout.LayoutParams(Math.max(1, a.dp(1)), a.dp(34));
            sepLp.leftMargin = a.dp(12);
            sepLp.rightMargin = a.dp(12);
            logoRow.addView(sep, sepLp);
            TextView num = a.text(brand.mark, 34, 0xffffffff);
            num.setTypeface(null, Typeface.BOLD);
            num.setIncludeFontPadding(false);
            logoRow.addView(num);
        } else {
            TextView word = a.text(brand.mark, 24, 0xfff7fbff);
            word.setTypeface(null, Typeface.BOLD);
            word.setIncludeFontPadding(false);
            logoRow.addView(word);
        }
        hero.addView(logoRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout badge = a.row();
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(a.dp(10), a.dp(5), a.dp(11), a.dp(5));
        badge.setBackground(MobileDetailScreen.glassBg(a, 0x66060b14, 0x33ffffff, 999));
        badge.addView(
                TvUiKit.dot(a, 0xffff6f96, 6), new LinearLayout.LayoutParams(a.dp(6), a.dp(6)));
        TextView bt = a.text("直播中", 10, 0xffffd4df);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-2, -2);
        btLp.leftMargin = a.dp(5);
        badge.addView(bt, btLp);
        FrameLayout.LayoutParams badgeLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeLp.topMargin = a.dp(10);
        badgeLp.rightMargin = a.dp(10);
        hero.addView(badge, badgeLp);
        panel.addView(hero, new LinearLayout.LayoutParams(-1, a.dp(112)));

        TextView name = a.text(chosen.name, 19, 0xfff7fbff);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = a.dp(12);
        panel.addView(name, nameLp);

        TextView meta =
                a.text(
                        (page.query.isEmpty() ? page.group : chosen.group)
                                + (page.query.isEmpty() ? "" : " · 搜索结果"),
                        11.5f,
                        0xffffb3c8);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = a.dp(5);
        panel.addView(meta, metaLp);

        TextView desc =
                a.text(
                        "官方网页直播 · 首次加载稍慢\n数字键换台，上下滑调亮度 / 音量",
                        11.5f,
                        0xff9aa9c0);
        desc.setLineSpacing(a.dp(4), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = a.dp(10);
        panel.addView(desc, descLp);

        // 频道信息：分类 / 编号 / 播放方式，填满面板中段留白
        View infoDivider = new View(a);
        infoDivider.setBackgroundColor(0x1affffff);
        LinearLayout.LayoutParams infoDivLp =
                new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
        infoDivLp.topMargin = a.dp(12);
        infoDivLp.bottomMargin = a.dp(3);
        panel.addView(infoDivider, infoDivLp);

        final int totalInList =
                page.query.isEmpty()
                        ? page.store.group(page.group).size()
                        : searchChannels(page.store, page.query).size();
        panel.addView(
                infoRow(
                        a,
                        "分类",
                        page.query.isEmpty() ? page.group : chosen.group + " · 搜索"),
                new LinearLayout.LayoutParams(-1, -2));
        panel.addView(
                infoRow(
                        a,
                        "编号",
                        String.format(
                                Locale.US,
                                "%02d / %02d",
                                numberInList(page, chosen),
                                totalInList)),
                new LinearLayout.LayoutParams(-1, -2));
        panel.addView(
                infoRow(a, "播放", "官方网页直播"),
                new LinearLayout.LayoutParams(-1, -2));

        View fill = new View(a);
        panel.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));

        boolean fav = page.store.favorites.contains(chosen.name);
        TextView favBtn =
                a.text(fav ? "♥  已收藏" : "♡  收藏频道", 12.5f, fav ? 0xffff8fb1 : 0xffe6edf9);
        favBtn.setGravity(Gravity.CENTER);
        favBtn.setSingleLine(true);
        favBtn.setBackground(
                MobileDetailScreen.glassBg(
                        a, fav ? 0x2bff8fb1 : 0x1affffff, fav ? 0x66ffb3c8 : 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(favBtn);
        favBtn.setOnClickListener(
                v -> {
                    boolean now = toggleFavorite(page.a, page.store, sel);
                    page.a.toast((now ? "已收藏 " : "已取消收藏 ") + sel.name);
                    page.refresh();
                });
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-1, a.dp(40));
        favLp.bottomMargin = a.dp(8);
        panel.addView(favBtn, favLp);

        TextView watch = TvUiKit.pill(a, "▶  观看直播", 14, true);
        final String openGroup = page.query.isEmpty() ? page.group : sel.group;
        watch.setOnClickListener(
                v -> {
                    page.selected = sel.name;
                    open(page.a, page.store, openGroup, sel, () -> page.refresh());
                });
        panel.addView(watch, new LinearLayout.LayoutParams(-1, a.dp(46)));
        return panel;
    }

    /** 信息面板的一行：左标签 + 右值。 */
    static LinearLayout infoRow(MainActivity a, String label, String value) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(5), 0, a.dp(5));
        TextView l = a.text(label, 10.5f, 0xff7f8fa9);
        row.addView(l);
        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView v = a.text(value, 11.5f, 0xffe6edf9);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v);
        return row;
    }

    static int numberInList(PageState page, Channel ch) {
        List<Channel> list =
                page.query.isEmpty()
                        ? page.store.group(page.group)
                        : searchChannels(page.store, page.query);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(ch.name)) return i + 1;
        }
        return 1;
    }

    /** 空状态：圆形玻璃徽标 + 标题 + 说明 + 可选主按钮。 */
    static View emptyState(
            MainActivity a,
            boolean searchGlyph,
            String title,
            String body,
            String actionLabel,
            Runnable action) {
        LinearLayout box = a.vertical();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(a.dp(14), a.dp(34), a.dp(14), a.dp(28));

        FrameLayout badge = new FrameLayout(a);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x1fff8fb1);
        circle.setStroke(Math.max(1, a.dp(1)), 0x40ffb3c8);
        badge.setBackground(circle);
        if (searchGlyph) {
            badge.addView(
                    new TvUiKit.SearchGlyph(a, 0xffff8fb1),
                    new FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER));
        } else {
            TextView glyph = a.text("☆", 24, 0xffff8fb1);
            glyph.setGravity(Gravity.CENTER);
            badge.addView(glyph, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        box.addView(badge, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));

        TextView t = a.text(title, 14.5f, 0xfff5f7fc);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = a.dp(14);
        box.addView(t, tLp);

        TextView b = a.text(body, 11.5f, 0xff8d9cb6);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(a.dp(3), 1f);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, -2);
        bLp.topMargin = a.dp(6);
        box.addView(b, bLp);

        if (actionLabel != null && action != null) {
            TextView btn = TvUiKit.pill(a, actionLabel, 12, true);
            btn.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, a.dp(34));
            btnLp.topMargin = a.dp(14);
            btnLp.gravity = Gravity.CENTER_HORIZONTAL;
            box.addView(btn, btnLp);
        }
        return box;
    }

    // ---------------- 搜索 / 键盘辅助 ----------------

    static List<Channel> searchChannels(Store store, String q) {
        List<Channel> out = new ArrayList<>();
        if (q == null || q.isEmpty()) return out;
        String compactQ = compact(q);
        for (Channel c : store.every()) {
            if (matchesChannel(c, q, compactQ)) out.add(c);
        }
        return out;
    }

    static boolean matchesChannel(Channel c, String q, String compactQ) {
        String name = c.name == null ? "" : c.name.toLowerCase(Locale.US);
        if (name.contains(q)) return true;
        if (compact(name).contains(compactQ)) return true;
        String initials = compact(TvUiKit.brand(c.name).initials);
        return !initials.isEmpty() && initials.contains(compactQ);
    }

    /** 去掉空格 / 连字符等分隔符，让「cctv1」也能命中「CCTV-1」。 */
    static String compact(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US)
                .replace("-", "")
                .replace(" ", "")
                .replace("·", "")
                .replace("+", "plus");
    }

    static Channel findChannel(List<Channel> list, String name) {
        if (name == null) return null;
        for (Channel c : list) {
            if (c.name.equals(name)) return c;
        }
        return null;
    }

    static void hideSoftKeyboard(MainActivity a, View v) {
        try {
            InputMethodManager imm =
                    (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    static void showSoftKeyboard(MainActivity a, View v) {
        try {
            InputMethodManager imm =
                    (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    static void refresh(MainActivity a) {
        MobileShell.show(a);
    }

    static int statusBar(MainActivity a) {
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id) : a.dp(24);
    }

    static TextView pill(MainActivity a, String label, int color) {
        TextView v = a.text(label, 12, MainActivity.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(a.dp(12), 0, a.dp(12), 0);
        v.setBackground(a.bg(color, 999));
        MobileDetailScreen.bindTouchFeedback(v);
        return v;
    }

    // ======================= 自定义 / 导入 =======================

    static void addChannelDialog(MainActivity a, Store store, Runnable done) {
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        android.widget.EditText name = new android.widget.EditText(a);
        name.setHint("频道名称，如 东方卫视");
        name.setSingleLine(true);
        android.widget.EditText url = new android.widget.EditText(a);
        url.setHint("官方直播页地址 https://…");
        url.setSingleLine(true);
        box.addView(name);
        box.addView(url);
        new AppDialog.Builder(a)
                .setTitle("添加自定义频道")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            String n = name.getText().toString().trim();
                            String u = url.getText().toString().trim();
                            if (n.isEmpty() || !Cms.isHttp(u)) {
                                a.toast("请填写名称和 http(s) 地址");
                                return;
                            }
                            store.custom.add(new Channel(n, u, GROUP_SAT));
                            saveCustom(a, store.custom);
                            done.run();
                        })
                .show();
    }

    static void importDialog(MainActivity a, Store store) {
        android.widget.EditText input = new android.widget.EditText(a);
        input.setHint("粘贴 JSON：{\"channels\":[{\"name\":\"频道\",\"url\":\"https://…\"}]}");
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AppDialog.Builder(a)
                .setTitle("导入频道列表")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "导入",
                        (d, w) -> {
                            try {
                                String text = input.getText().toString().trim();
                                int added = 0;
                                if (text.startsWith("{")) {
                                    JSONObject json = new JSONObject(text);
                                    JSONArray arr = json.optJSONArray("channels");
                                    if (arr == null) throw new IllegalArgumentException("缺少 channels");
                                    for (int i = 0; i < arr.length(); i++) {
                                        JSONObject o = arr.getJSONObject(i);
                                        String n = o.optString("name").trim();
                                        String u = o.optString("url").trim();
                                        if (!n.isEmpty() && Cms.isHttp(u)) {
                                            store.custom.add(new Channel(n, u, GROUP_SAT));
                                            added++;
                                        }
                                    }
                                } else {
                                    // M3U / TXT 列表：#EXTINF 后面跟播放地址，或「频道名,地址」
                                    String pendingName = null;
                                    for (String raw : text.split("\r?\n")) {
                                        String line = raw.trim();
                                        if (line.isEmpty()) continue;
                                        if (line.startsWith("#EXTINF")) {
                                            int comma = line.lastIndexOf(',');
                                            pendingName =
                                                    comma >= 0
                                                            ? line.substring(comma + 1).trim()
                                                            : "自定义频道";
                                            continue;
                                        }
                                        if (line.startsWith("#")) continue;
                                        if (Cms.isHttp(line)) {
                                            String name = pendingName != null ? pendingName : line;
                                            store.custom.add(new Channel(name, line, GROUP_SAT));
                                            added++;
                                            pendingName = null;
                                            continue;
                                        }
                                        int comma = line.indexOf(',');
                                        if (comma > 0) {
                                            String name = line.substring(0, comma).trim();
                                            String url = line.substring(comma + 1).trim();
                                            if (!name.isEmpty() && Cms.isHttp(url)) {
                                                store.custom.add(new Channel(name, url, GROUP_SAT));
                                                added++;
                                            }
                                        }
                                    }
                                }
                                saveCustom(a, store.custom);
                                a.toast("已导入 " + added + " 个频道");
                                refresh(a);
                            } catch (Exception e) {
                                a.toast("导入失败：" + e.getMessage());
                            }
                        })
                .show();
    }

    static void saveCustom(MainActivity a, List<Channel> channels) {
        try {
            JSONObject json = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Channel c : channels)
                arr.put(new JSONObject().put("name", c.name).put("url", c.url));
            json.put("channels", arr);
            try (FileOutputStream out =
                    new FileOutputStream(new File(a.getFilesDir(), CUSTOM_FILE))) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            AppLog.w("TvLive", "save channels failed", e);
        }
    }

    static List<Channel> readCustom(MainActivity a) {
        List<Channel> list = new ArrayList<>();
        File f = new File(a.getFilesDir(), CUSTOM_FILE);
        if (!f.isFile()) return list;
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONArray arr = json.optJSONArray("channels");
            if (arr != null)
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Channel(o.optString("name"), o.optString("url"), GROUP_SAT));
                }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 切换收藏状态：先改内存（store），再按原格式落盘，返回是否已收藏。 */
    static boolean toggleFavorite(MainActivity a, Store store, Channel c) {
        boolean fav;
        if (store.favorites.remove(c.name)) {
            fav = false;
        } else {
            store.favorites.add(c.name);
            fav = true;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : store.favorites) sb.append(s).append('\n');
        a.prefs.edit().putString(FAVORITES, sb.toString()).apply();
        return fav;
    }

    static Set<String> readFavorites(MainActivity a) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : a.prefs.getString(FAVORITES, "").split("\n"))
            if (!s.isEmpty()) set.add(s);
        return set;
    }

    // ======================= 播放器 =======================

    static void open(MainActivity a, Store store, String group, Channel channel) {
        open(a, store, group, channel, null);
    }

    static void open(
            MainActivity a, Store store, String group, Channel channel, Runnable onClosed) {
        a.prefs.edit().putString(LAST, channel.name + "\n" + channel.url).apply();
        Player player = new Player(a, store, group, channel);
        player.onClosed = onClosed;
        player.show();
    }

    static final class Player {
        /** chrome 无操作 3 秒后自动淡出，点屏幕再唤出。 */
        static final long CHROME_HIDE_MS = 3000L;

        final MainActivity a;
        final Store store;
        List<Channel> list;
        String group;
        String playingName;
        int index;
        FrameLayout overlay;
        TvWebView web;
        FrameLayout chrome;
        LinearLayout topBar;
        TextView title;
        TextView brandMiniMark;
        FrameLayout brandMini;
        LinearLayout infoPanel;
        TextView infoSize;
        TextView infoTime;
        TextView infoFav;
        LinearLayout bottomPanel;
        LinearLayout groupStrip;
        LinearLayout channelStrip;
        HorizontalScrollView channelScroll;
        View currentChip;
        LinearLayout loadingCard;
        TextView loadingTitle;
        TextView loadingSub;
        boolean panelOpen;
        boolean chromeShown = true;
        int chromeGen;
        int loadingGen;
        Runnable onClosed;
        View fullView;
        WebChromeClient.CustomViewCallback fullCallback;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable hideChromeRunnable = () -> fadeOutChrome();
        final Runnable clockTick =
                new Runnable() {
                    @Override
                    public void run() {
                        // 窄屏没有信息面板，直接不排下一轮
                        if (infoTime == null) return;
                        infoTime.setText(
                                android.text.format.DateFormat.format(
                                                "HH:mm", System.currentTimeMillis())
                                        .toString());
                        handler.postDelayed(this, 20000);
                    }
                };
        final Runnable inject =
                new Runnable() {
                    @Override
                    public void run() {
                        if (web == null) return;
                        web.evaluateJavascript(INJECT_JS + fitJs(), null);
                        handler.postDelayed(this, 1800);
                    }
                };

        Player(MainActivity a, Store store, String group, Channel channel) {
            this.a = a;
            this.store = store;
            this.group = group;
            this.list = store.group(group);
            if (this.list.isEmpty()) {
                this.group = GROUP_CCTV;
                this.list = store.group(GROUP_CCTV);
            }
            this.playingName = channel.name;
            this.index = Math.max(0, indexOf(this.list, channel.name));
        }

        static int indexOf(List<Channel> list, String name) {
            for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
            return 0;
        }

        void show() {
            overlay = new FrameLayout(a);
            overlay.setBackgroundColor(0xff05070c);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            web = new TvWebView(a);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setUserAgentString(UA);
            if (Build.VERSION.SDK_INT >= 21)
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            web.setWebChromeClient(
                    new WebChromeClient() {
                        @Override
                        public void onProgressChanged(WebView v, int progress) {
                            if (loadingSub != null && progress > 0 && progress < 100) {
                                loadingSub.setText("正在加载官方页面 " + progress + "%");
                            }
                            if (progress > 80) handler.post(inject);
                        }

                        @Override
                        public void onShowCustomView(View view, CustomViewCallback callback) {
                            if (fullView != null) callback.onCustomViewHidden();
                            fullView = view;
                            fullCallback = callback;
                            overlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
                            if (topBar != null) topBar.setVisibility(View.GONE);
                        }

                        @Override
                        public void onHideCustomView() {
                            if (fullView != null && fullView.getParent() instanceof FrameLayout)
                                ((FrameLayout) fullView.getParent()).removeView(fullView);
                            fullView = null;
                            if (fullCallback != null) {
                                fullCallback.onCustomViewHidden();
                                fullCallback = null;
                            }
                            if (topBar != null) topBar.setVisibility(View.VISIBLE);
                        }
                    });
            web.setWebViewClient(
                    new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                            showLoading(playingName);
                        }

                        @Override
                        public void onPageFinished(WebView v, String url) {
                            hideLoading();
                            if (infoSize != null && web != null) {
                                infoSize.setText(web.getWidth() + " × " + web.getHeight());
                            }
                            handler.post(inject);
                            handler.postDelayed(Player.this::pressFullscreenKey, 1800);
                            handler.postDelayed(Player.this::pressFullscreenKey, 4500);
                        }
                    });
            overlay.addView(web, new FrameLayout.LayoutParams(-1, -1));

            // chrome 容器：顶栏 + 右侧信息面板 + 底部频道条，随 3 秒计时整体淡出
            boolean widePlayer =
                    a.windowWidth() / a.getResources().getDisplayMetrics().density >= 720f;

            loadingCard = buildLoadingCard();
            overlay.addView(
                    loadingCard, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            chrome = new FrameLayout(a);
            topBar = buildTopBar();
            FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            topLp.setMargins(
                    a.dp(10), MobileDetailScreen.getStatusBarHeight(a) + a.dp(8), a.dp(10), 0);
            chrome.addView(topBar, topLp);

            if (widePlayer) {
                infoPanel = buildInfoPanel();
                FrameLayout.LayoutParams infoLp =
                        new FrameLayout.LayoutParams(a.dp(172), -2, Gravity.TOP | Gravity.END);
                infoLp.setMargins(
                        0, MobileDetailScreen.getStatusBarHeight(a) + a.dp(70), a.dp(10), 0);
                chrome.addView(infoPanel, infoLp);
            }

            bottomPanel = buildBottomPanel();
            FrameLayout.LayoutParams panelLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            panelLp.setMargins(a.dp(10), 0, a.dp(10), a.dp(16));
            chrome.addView(bottomPanel, panelLp);
            bottomPanel.setVisibility(View.GONE);

            overlay.addView(chrome, new FrameLayout.LayoutParams(-1, -1));

            // 画面尺寸跟随 WebView 实际像素尺寸
            web.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, orr, ob) -> {
                        if (infoSize != null && web != null) {
                            infoSize.setText(web.getWidth() + " × " + web.getHeight());
                        }
                    });

            overlay.setOnKeyListener(
                    (v, code, event) -> {
                        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                        int digit = code - KeyEvent.KEYCODE_0;
                        if (digit >= 0 && digit <= 9 && !list.isEmpty()) {
                            int target = digit == 0 ? 9 : digit - 1;
                            if (target < list.size()) {
                                tune(target);
                                return true;
                            }
                        }
                        return false;
                    });

            a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            a.tvPlayer = this;
            overlay.requestFocus();
            load();
            handler.postDelayed(inject, 2500);
            showChrome();
            handler.post(clockTick);
        }

        void tune(int idx) {
            if (idx < 0 || idx >= list.size()) return;
            index = idx;
            playingName = list.get(idx).name;
            refreshTopChrome();
            rebuildChannelChips();
            load();
            showLoading(playingName);
            if (panelOpen) {
                panelOpen = false;
                if (bottomPanel != null) bottomPanel.setVisibility(View.GONE);
            }
            showChrome();
        }

        /** 向网页发送 F 键，触发 CCTV / 芒果等网页播放器的全屏（参考 WebViewTvLive）。 */
        /** 按设置生成画面比例/填充模式的 JS（object-fit + aspect-ratio）。 */
        String fitJs() {
            int aspect = a.prefs.getInt(Prefs.TV_ASPECT, 0);
            int fill = a.prefs.getInt(Prefs.TV_FILL, 0);
            String fit = fill == 1 ? "cover" : fill == 2 ? "fill" : "contain";
            String ratio = aspect == 1 ? "16/9" : aspect == 2 ? "4/3" : "";
            return ";(function(){var v=document.querySelector('video');if(!v)return;"
                    + "v.style.objectFit='"
                    + fit
                    + "';"
                    + (ratio.isEmpty()
                            ? "v.style.aspectRatio='';"
                            : "v.style.aspectRatio='" + ratio + "';")
                    + "})();";
        }

        void pressFullscreenKey() {
            if (web == null) return;
            try {
                long now = android.os.SystemClock.uptimeMillis();
                web.dispatchKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0));
                web.dispatchKeyEvent(
                        new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0));
            } catch (Throwable ignored) {
            }
        }

        void load() {
            if (list.isEmpty()) return;
            handler.removeCallbacks(inject);
            String url = list.get(index).url;
            web.loadUrl(url);
            handler.postDelayed(inject, 2500);
        }

        // ---------------- chrome（顶栏 / 信息面板 / 底部频道条） ----------------

        LinearLayout buildTopBar() {
            LinearLayout top = a.row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(a.dp(8), a.dp(7), a.dp(8), a.dp(7));
            top.setBackground(MobileDetailScreen.glassBg(a, 0x730b1220, 0x40ffffff, 20));
            top.setClickable(true);
            top.setOnClickListener(v -> toggleChrome());

            TextView back = a.text("‹", 24, Color.WHITE);
            back.setGravity(Gravity.CENTER);
            back.setPadding(0, 0, a.dp(2), a.dp(2));
            GradientDrawable backBg = new GradientDrawable();
            backBg.setShape(GradientDrawable.OVAL);
            backBg.setColor(0x33ffffff);
            backBg.setStroke(Math.max(1, a.dp(0.8f)), 0x4dffffff);
            back.setBackground(backBg);
            MobileDetailScreen.bindTouchFeedback(back);
            back.setOnClickListener(v -> close());
            top.addView(back, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));

            TvUiKit.Brand brand = TvUiKit.brand(playingName);
            brandMini = new FrameLayout(a);
            brandMini.setBackground(TvUiKit.brandBlock(a, brand, 11));
            brandMiniMark = a.text(brand.mark, brand.mark.length() >= 3 ? 10.5f : 15, 0xfff7fbff);
            brandMiniMark.setTypeface(null, Typeface.BOLD);
            brandMiniMark.setGravity(Gravity.CENTER);
            brandMiniMark.setIncludeFontPadding(false);
            brandMini.addView(
                    brandMiniMark, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
            miniLp.leftMargin = a.dp(9);
            top.addView(brandMini, miniLp);

            LinearLayout titleCol = a.vertical();
            titleCol.setPadding(a.dp(10), 0, a.dp(8), 0);
            title = a.text(playingName, 14.5f, Color.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titleCol.addView(title, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout liveRow = a.row();
            liveRow.setGravity(Gravity.CENTER_VERTICAL);
            liveRow.addView(
                    TvUiKit.dot(a, 0xffff6f96, 5),
                    new LinearLayout.LayoutParams(a.dp(5), a.dp(5)));
            TextView live = a.text("直播中", 10, 0xffffb3c8);
            live.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(-2, -2);
            liveLp.leftMargin = a.dp(4);
            liveRow.addView(live, liveLp);
            liveRow.addView(a.text("  ·  官方网页直播", 10, 0x8ca9b7d0));
            titleCol.addView(liveRow, new LinearLayout.LayoutParams(-1, -2));
            top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView listBtn = TvUiKit.pill(a, "频道", 11.5f, false);
            listBtn.setOnClickListener(v -> toggleChannelPanel());
            LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-2, a.dp(32));
            listLp.rightMargin = a.dp(6);
            top.addView(listBtn, listLp);

            TextView refreshBtn = TvUiKit.pill(a, "刷新", 11.5f, false);
            refreshBtn.setOnClickListener(
                    v -> {
                        load();
                        showChrome();
                    });
            LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-2, a.dp(32));
            refreshLp.rightMargin = a.dp(6);
            top.addView(refreshBtn, refreshLp);

            TextView fullBtn = TvUiKit.pill(a, "全屏", 11.5f, false);
            fullBtn.setOnClickListener(
                    v -> {
                        pressFullscreenKey();
                        showChrome();
                        a.toast("已向网页发送全屏请求");
                    });
            top.addView(fullBtn, new LinearLayout.LayoutParams(-2, a.dp(32)));
            return top;
        }

        LinearLayout buildInfoPanel() {
            LinearLayout panel = a.vertical();
            panel.setPadding(a.dp(13), a.dp(13), a.dp(13), a.dp(12));
            panel.setBackground(MobileDetailScreen.glassBg(a, 0x8c0b1220, 0x33ffffff, 20));
            panel.setClickable(true);
            panel.setOnClickListener(v -> showChrome());

            panel.addView(label("视频源"));
            LinearLayout sources = a.row();
            for (int i = 1; i <= 3; i++) {
                final int n = i;
                TextView item = a.text(String.valueOf(i), 14, n == 1 ? 0xffffb3c8 : 0xff9aa9c0);
                item.setTypeface(null, Typeface.BOLD);
                item.setGravity(Gravity.CENTER);
                item.setOnClickListener(
                        v -> a.toast(n == 1 ? "当前使用官方网页直播源" : "该频道暂无备用官方源"));
                sources.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
            }
            panel.addView(sources, new LinearLayout.LayoutParams(-1, -2));

            panel.addView(label("画面比例"));
            LinearLayout ratios = a.row();
            final String[] ratioNames = {"自适应", "16:9", "4:3"};
            final int[] ratioValues = {0, 1, 2};
            final TextView[] ratioViews = new TextView[ratioNames.length];
            for (int i = 0; i < ratioNames.length; i++) {
                final int value = ratioValues[i];
                boolean on = a.prefs.getInt(Prefs.TV_ASPECT, 0) == value;
                TextView item = a.text(ratioNames[i], 12, on ? 0xffffb3c8 : 0xff9aa9c0);
                item.setTypeface(null, Typeface.BOLD);
                item.setGravity(Gravity.CENTER);
                ratioViews[i] = item;
                item.setOnClickListener(
                        v -> {
                            a.prefs.edit().putInt(Prefs.TV_ASPECT, value).apply();
                            for (int k = 0; k < ratioViews.length; k++)
                                ratioViews[k].setTextColor(
                                        k == value ? 0xffffb3c8 : 0xff9aa9c0);
                            if (web != null) web.evaluateJavascript(fitJs(), null);
                        });
                ratios.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
            }
            panel.addView(ratios, new LinearLayout.LayoutParams(-1, -2));

            panel.addView(label("填充模式"));
            LinearLayout fills = a.row();
            final String[] fillNames = {"适应", "裁剪", "拉伸"};
            final TextView[] fillViews = new TextView[fillNames.length];
            for (int i = 0; i < fillNames.length; i++) {
                final int value = i;
                boolean on = a.prefs.getInt(Prefs.TV_FILL, 0) == value;
                TextView item = a.text(fillNames[i], 12, on ? 0xffffb3c8 : 0xff9aa9c0);
                item.setTypeface(null, Typeface.BOLD);
                item.setGravity(Gravity.CENTER);
                fillViews[i] = item;
                item.setOnClickListener(
                        v -> {
                            a.prefs.edit().putInt(Prefs.TV_FILL, value).apply();
                            for (int k = 0; k < fillViews.length; k++)
                                fillViews[k].setTextColor(
                                        k == value ? 0xffffb3c8 : 0xff9aa9c0);
                            if (web != null) web.evaluateJavascript(fitJs(), null);
                        });
                fills.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
            }
            panel.addView(fills, new LinearLayout.LayoutParams(-1, -2));

            panel.addView(label("画面尺寸"));
            infoSize = a.text("--", 12, 0xffe6edf9);
            panel.addView(infoSize, new LinearLayout.LayoutParams(-1, -2));

            View divider = new View(a);
            divider.setBackgroundColor(0x1affffff);
            LinearLayout.LayoutParams divLp =
                    new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
            divLp.topMargin = a.dp(10);
            divLp.bottomMargin = a.dp(8);
            panel.addView(divider, divLp);

            TextView fav = a.text("♡ 收藏频道", 11.5f, 0xffe6edf9);
            fav.setPadding(a.dp(2), 0, 0, 0);
            fav.setOnClickListener(
                    v -> {
                        if (list.isEmpty()) return;
                        Channel c = list.get(Math.max(0, Math.min(index, list.size() - 1)));
                        boolean now = toggleFavorite(a, store, c);
                        fav.setText(now ? "♥ 已收藏" : "♡ 收藏频道");
                        fav.setTextColor(now ? 0xffff8fb1 : 0xffe6edf9);
                        a.toast((now ? "已收藏 " : "已取消收藏 ") + c.name);
                        refreshTopChrome();
                    });
            infoFav = fav;
            panel.addView(fav, new LinearLayout.LayoutParams(-1, a.dp(30)));

            infoTime = a.text("", 11, 0xff9aa9c0);
            panel.addView(infoTime, new LinearLayout.LayoutParams(-1, -2));
            return panel;
        }

        LinearLayout buildBottomPanel() {
            LinearLayout panel = a.vertical();
            panel.setPadding(a.dp(10), a.dp(9), a.dp(10), a.dp(10));
            panel.setBackground(MobileDetailScreen.glassBg(a, 0x8c0b1220, 0x33ffffff, 22));
            panel.setClickable(true);
            panel.setOnClickListener(v -> showChrome());

            LinearLayout head = a.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            groupStrip = a.row();
            head.addView(groupStrip, new LinearLayout.LayoutParams(0, -2, 1f));
            head.addView(a.text("数字键 1–9 换台", 10, 0x99a9b7d0));
            TextView collapse = TvUiKit.pill(a, "收起", 10.5f, false);
            collapse.setOnClickListener(v -> toggleChannelPanel());
            LinearLayout.LayoutParams collapseLp = new LinearLayout.LayoutParams(-2, a.dp(28));
            collapseLp.leftMargin = a.dp(8);
            head.addView(collapse, collapseLp);
            panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

            channelScroll = new HorizontalScrollView(a);
            channelScroll.setHorizontalScrollBarEnabled(false);
            channelStrip = a.row();
            channelScroll.addView(channelStrip);
            LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-1, -2);
            csLp.topMargin = a.dp(8);
            panel.addView(channelScroll, csLp);

            rebuildGroupChips();
            rebuildChannelChips();
            return panel;
        }

        LinearLayout buildLoadingCard() {
            LinearLayout card = a.vertical();
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(a.dp(24), a.dp(18), a.dp(24), a.dp(18));
            card.setBackground(MobileDetailScreen.glassBg(a, 0x8c101b2e, 0x40ffffff, 22));
            card.setElevation(a.dp(8));
            card.addView(
                    new TvUiKit.Spinner(a, 0xffff8fb1),
                    new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));

            loadingTitle = a.text("正在连接 " + playingName, 13, 0xfff5f7fc);
            loadingTitle.setTypeface(null, Typeface.BOLD);
            loadingTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(-2, -2);
            ltLp.topMargin = a.dp(12);
            card.addView(loadingTitle, ltLp);

            loadingSub = a.text("官方网页直播 · 首次加载稍慢", 10.5f, 0xff9aa9c0);
            loadingSub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-2, -2);
            lsLp.topMargin = a.dp(5);
            card.addView(loadingSub, lsLp);
            return card;
        }

        TextView label(String text) {
            TextView t = a.text(text, 10, 0xff8d9cb6);
            t.setLetterSpacing(0.08f);
            t.setPadding(0, a.dp(10), 0, a.dp(3));
            return t;
        }

        void rebuildGroupChips() {
            if (groupStrip == null) return;
            groupStrip.removeAllViews();
            String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_MINE};
            String[] shortNames = {"央视", "卫视", "收藏"};
            for (int i = 0; i < gs.length; i++) {
                final String g = gs[i];
                boolean on = g.equals(group);
                TextView chip = a.text(shortNames[i], 11, on ? 0xff31141f : 0xffc9d6ea);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(a.dp(10), 0, a.dp(10), 0);
                if (on) {
                    chip.setTypeface(null, Typeface.BOLD);
                    chip.setBackground(TvUiKit.accentFill(a, 999));
                } else {
                    chip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
                }
                chip.setOnClickListener(v -> switchGroup(g));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(28));
                lp.rightMargin = a.dp(6);
                groupStrip.addView(chip, lp);
            }
        }

        void rebuildChannelChips() {
            if (channelStrip == null) return;
            channelStrip.removeAllViews();
            currentChip = null;
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                Channel c = list.get(i);
                boolean on = i == index;
                TvUiKit.Brand brand = TvUiKit.brand(c.name);

                LinearLayout chip = a.row();
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(a.dp(6), a.dp(4), a.dp(12), a.dp(4));
                chip.setBackground(
                        on
                                ? TvUiKit.accentFill(a, 999)
                                : MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
                MobileDetailScreen.bindTouchFeedback(chip);

                FrameLayout tile = new FrameLayout(a);
                tile.setBackground(TvUiKit.brandBlock(a, brand, 7));
                TextView mk = a.text(brand.mark, brand.mark.length() >= 3 ? 7 : 9, 0xfff7fbff);
                mk.setTypeface(null, Typeface.BOLD);
                mk.setGravity(Gravity.CENTER);
                tile.addView(mk, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                LinearLayout.LayoutParams tileLp =
                        new LinearLayout.LayoutParams(a.dp(22), a.dp(22));
                tileLp.rightMargin = a.dp(6);
                chip.addView(tile, tileLp);

                TextView name = a.text(c.name, 11.5f, on ? 0xff31141f : 0xffe6edf9);
                name.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setMaxWidth(a.dp(120));
                chip.addView(name);

                chip.setOnClickListener(v -> tune(idx));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
                lp.rightMargin = a.dp(7);
                channelStrip.addView(chip, lp);
                if (on) currentChip = chip;
            }
        }

        void switchGroup(String g) {
            List<Channel> next = store.group(g);
            if (next.isEmpty()) {
                a.toast("还没有收藏频道");
                return;
            }
            group = g;
            list = next;
            boolean stillHere = indexOf(list, playingName) > 0 || list.get(0).name.equals(playingName);
            if (stillHere) {
                index = indexOf(list, playingName);
                rebuildGroupChips();
                rebuildChannelChips();
            } else {
                // 当前台不在新分组：切到该分组第一个频道，避免顶栏 / 列表 / 网页三者不一致
                index = 0;
                playingName = list.get(0).name;
                refreshTopChrome();
                rebuildGroupChips();
                rebuildChannelChips();
                load();
                showLoading(playingName);
            }
            scrollToCurrentChip();
            showChrome();
        }

        void scrollToCurrentChip() {
            if (channelScroll == null || currentChip == null) return;
            final View target = currentChip;
            channelScroll.post(
                    () -> {
                        int x = target.getLeft() - a.dp(30);
                        channelScroll.smoothScrollTo(Math.max(0, x), 0);
                    });
        }

        void refreshTopChrome() {
            TvUiKit.Brand brand = TvUiKit.brand(playingName);
            if (title != null) title.setText(playingName);
            if (brandMini != null) brandMini.setBackground(TvUiKit.brandBlock(a, brand, 10));
            if (brandMiniMark != null) {
                brandMiniMark.setText(brand.mark);
                brandMiniMark.setTextSize(brand.mark.length() >= 3 ? 10.5f : 15);
            }
            if (infoFav != null && !list.isEmpty()) {
                Channel cur = list.get(Math.max(0, Math.min(index, list.size() - 1)));
                boolean on = store.favorites.contains(cur.name);
                infoFav.setText(on ? "♥ 已收藏" : "♡ 收藏频道");
                infoFav.setTextColor(on ? 0xffff8fb1 : 0xffe6edf9);
            }
        }

        void toggleChannelPanel() {
            if (bottomPanel == null) return;
            panelOpen = !panelOpen;
            if (panelOpen) {
                rebuildGroupChips();
                rebuildChannelChips();
                bottomPanel.setVisibility(View.VISIBLE);
                bottomPanel.setAlpha(0f);
                bottomPanel.animate().alpha(1f).setDuration(180).start();
                scrollToCurrentChip();
            } else {
                bottomPanel.animate().cancel();
                bottomPanel.setAlpha(1f);
                bottomPanel.setVisibility(View.GONE);
            }
            showChrome();
        }

        void showChrome() {
            if (chrome == null || overlay == null) return;
            final boolean fromHidden = !chromeShown;
            chromeShown = true;
            ++chromeGen;
            chrome.animate().cancel();
            chrome.setVisibility(View.VISIBLE);
            chrome.setAlpha(1f);
            // 从隐藏态唤出时，右侧信息面板从右滑入
            if (fromHidden && infoPanel != null) {
                infoPanel.animate().cancel();
                infoPanel.setTranslationX(a.dp(30));
                infoPanel.setAlpha(0f);
                infoPanel.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(240)
                        .start();
            }
            handler.removeCallbacks(hideChromeRunnable);
            handler.postDelayed(hideChromeRunnable, CHROME_HIDE_MS);
        }

        void fadeOutChrome() {
            if (chrome == null || overlay == null) return;
            chromeShown = false;
            final int gen = ++chromeGen;
            chrome.animate().cancel();
            chrome.animate()
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction(
                            () -> {
                                if (gen == chromeGen && chrome != null) {
                                    chrome.setVisibility(View.INVISIBLE);
                                }
                            })
                    .start();
        }

        void toggleChrome() {
            if (chromeShown) {
                handler.removeCallbacks(hideChromeRunnable);
                fadeOutChrome();
            } else {
                showChrome();
            }
        }

        void showLoading(String name) {
            if (loadingCard == null) return;
            ++loadingGen;
            loadingCard.animate().cancel();
            loadingTitle.setText("正在连接 " + name);
            loadingSub.setText("官方网页直播 · 首次加载稍慢");
            loadingCard.setAlpha(1f);
            loadingCard.setVisibility(View.VISIBLE);
        }

        void hideLoading() {
            if (loadingCard == null || loadingCard.getVisibility() != View.VISIBLE) return;
            final int gen = ++loadingGen;
            loadingCard.animate().cancel();
            loadingCard.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(
                            () -> {
                                if (gen == loadingGen && loadingCard != null) {
                                    loadingCard.setVisibility(View.GONE);
                                    loadingCard.setAlpha(1f);
                                }
                            })
                    .start();
        }

        boolean back() {
            if (overlay == null) return false;
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            close();
            return true;
        }

        void close() {
            handler.removeCallbacksAndMessages(null);
            if (chrome != null) chrome.animate().cancel();
            if (loadingCard != null) loadingCard.animate().cancel();
            if (overlay != null && overlay.getParent() instanceof FrameLayout)
                ((FrameLayout) overlay.getParent()).removeView(overlay);
            if (web != null) {
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
            overlay = null;
            if (a.tvPlayer == this) a.tvPlayer = null;
            // 关闭后通知列表页刷新高亮（page::refresh）
            Runnable callback = onClosed;
            onClosed = null;
            if (callback != null) callback.run();
        }

        int volume() {
            android.media.AudioManager am =
                    (android.media.AudioManager)
                            a.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            return max <= 0 ? 0 : Math.round(cur * 100f / max);
        }

        void setVolume(int percent) {
            android.media.AudioManager am =
                    (android.media.AudioManager)
                            a.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    Math.round(max * percent / 100f),
                    0);
        }

        /** 自定义 WebView：普通点击交给网页；明显的上下滑才拦截成亮度/音量手势。 */
        final class TvWebView extends WebView {
            private float startX, startY;
            private int startBrightness, startVolume;
            private boolean vertical;
            private long downTime;

            TvWebView(Context c) {
                super(c);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getX();
                        startY = e.getY();
                        downTime = e.getEventTime();
                        vertical = false;
                        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                        startBrightness =
                                lp.screenBrightness < 0
                                        ? 128
                                        : Math.round(lp.screenBrightness * 255);
                        startVolume = volume();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(e.getX() - startX);
                        float dy = Math.abs(e.getY() - startY);
                        if (dy > a.dp(30) && dy > dx * 1.5f) vertical = true;
                        if (vertical) {
                            int delta =
                                    Math.round((startY - e.getY()) / a.dp(360) * 255);
                            if (startX < getWidth() / 2f) {
                                int level = Math.max(10, Math.min(255, startBrightness + delta));
                                WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
                                attrs.screenBrightness = level / 255f;
                                a.getWindow().setAttributes(attrs);
                            } else {
                                setVolume(Math.max(0, Math.min(100, startVolume + delta * 100 / 255)));
                            }
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (vertical) {
                            vertical = false;
                            return true;
                        }
                        // 轻点（非滑动）唤出 / 收起 chrome；不拦截网页点击
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && fullView == null) {
                            float tapDx = Math.abs(e.getX() - startX);
                            float tapDy = Math.abs(e.getY() - startY);
                            long tapMs = e.getEventTime() - downTime;
                            if (tapDx < a.dp(12) && tapDy < a.dp(12) && tapMs >= 0 && tapMs < 320) {
                                toggleChrome();
                            }
                        }
                        break;
                    default:
                        break;
                }
                return super.dispatchTouchEvent(e);
            }
        }

        final class SwipeGesture implements View.OnTouchListener {
            float startX, startY;
            int startBrightness, startVolume;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getX();
                        startY = e.getY();
                        dragging = false;
                        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                        startBrightness =
                                lp.screenBrightness < 0
                                        ? 128
                                        : Math.round(lp.screenBrightness * 255);
                        startVolume = volume();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = startY - e.getY();
                        if (Math.abs(dy) < a.dp(24)) return true;
                        dragging = true;
                        int delta = Math.round(dy / a.dp(300) * 255);
                        if (startX < overlay.getWidth() / 2f) {
                            int level = Math.max(10, Math.min(255, startBrightness + delta));
                            WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
                            attrs.screenBrightness = level / 255f;
                            a.getWindow().setAttributes(attrs);
                        } else {
                            setVolume(Math.max(0, Math.min(100, startVolume + delta * 100 / 255)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return dragging;
                    default:
                        return false;
                }
            }
        }
    }

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static final String INJECT_JS =
            "(function(){"
                    + "try{"
                    + "var pump=function(){"
                    + "var sels=['.vjs-big-play-button','.dplayer-play-icon','.xgplayer-start','.prism-player .play-btn','.play-button','.live-play'];"
                    + "for(var i=0;i<sels.length;i++){var el=document.querySelector(sels[i]);if(el){try{el.click();}catch(e){}}}"
                    + "var vs=document.querySelectorAll('video');"
                    + "for(var j=0;j<vs.length;j++){var v=vs[j];try{v.muted=false;v.play();}catch(e){}}"
                    + "};"
                    + "pump();"
                    // 真·全屏：把 video 的父容器固定铺满，隐藏其它所有兄弟节点（页面导航/EPG 等）
                    + "var v=document.querySelector('video');"
                    + "if(v&&v.parentElement){"
                    + "var root=v.parentElement;"
                    // 若父容器太小（纯 video 包装），再上提一层，尽量包住控制条
                    + "if(root.clientWidth<200||root.clientHeight<150){if(root.parentElement&&root.parentElement!==document.body)root=root.parentElement;}"
                    + "var node=root;"
                    + "while(node&&node.parentElement){var par=node.parentElement;"
                    + "for(var k=0;k<par.children.length;k++){var sib=par.children[k];if(sib!==node){sib.style.display='none';}}"
                    + "if(par===document.body)break;node=par;}"
                    + "root.style.position='fixed';root.style.top='0';root.style.left='0';"
                    + "root.style.width='100vw';root.style.height='100vh';root.style.zIndex='2147483000';"
                    + "root.style.background='#000';root.style.overflow='hidden';"
                    + "v.style.width='100%';v.style.height='100%';v.style.objectFit='contain';"
                    + "}"
                    + "document.documentElement.style.margin='0';document.body.style.margin='0';"
                    + "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';"
                    + "}catch(e){}"
                    + "})();";
}
