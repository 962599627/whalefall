package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
        {"CGTN", "https://tv.cctv.com/live/cgtn/"},
    };

    static final Object[][] SAT = {
        {"湖南卫视", "https://live.mgtv.com/"},
        {"浙江卫视", "https://www.zjstv.com/"},
        {"江苏卫视", "https://live.jstv.com/"},
        {"东方卫视", "https://live.kankanews.com/"},
        {"北京卫视", "https://www.btime.com/btv/btvws_1"},
        {"广东卫视", "https://www.gdtv.cn/"},
        {"深圳卫视", "http://www.sztv.com.cn/"},
        {"吉林卫视", "https://www.jlntv.cn/"},
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

        List<Channel> all() {
            List<Channel> list = new ArrayList<>(builtin);
            list.addAll(custom);
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
            if (GROUP_MINE.equals(group)) {
                List<Channel> fav = new ArrayList<>();
                for (Channel c : all()) if (favorites.contains(c.name)) fav.add(c);
                return fav;
            }
            List<Channel> list = new ArrayList<>();
            for (Channel c : all()) if (group.equals(c.group)) list.add(c);
            return list;
        }

        Channel byName(String name) {
            for (Channel c : all()) if (c.name.equals(name)) return c;
            return null;
        }
    }

    // ======================= 数据持久化 =======================

    // ======================= 页面 =======================

    static View screen(MainActivity a) {
        final Store store = new Store(a);
        final String[] group = {GROUP_CCTV};

        FrameLayout root = new FrameLayout(a);
        root.setBackground(Skin.backdrop(a));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = a.vertical();
        body.setPadding(a.dp(18), statusBar(a) + a.dp(10), a.dp(18), a.dp(110));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = a.text("电视台直播", 24, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = pill(a, "＋ 自定义", 0x38ffffff);
        add.setOnClickListener(v -> addChannelDialog(a, store, () -> refresh(a)));
        header.addView(add, new LinearLayout.LayoutParams(-2, a.dp(34)));
        TextView importBtn = pill(a, "导入列表", 0x38ffffff);
        importBtn.setOnClickListener(v -> importDialog(a, store));
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-2, a.dp(34));
        importLp.leftMargin = a.dp(8);
        header.addView(importBtn, importLp);
        body.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView note =
                a.text(
                        "官方网页直播，加载稍慢；数字键换台，上下滑调亮度/音量。卫视频道页面可能需在网页内点播放。",
                        12,
                        0xffa9b7d0);
        note.setPadding(0, a.dp(8), 0, a.dp(10));
        body.addView(note, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout chipRow = a.row();
        body.addView(chipRow, new LinearLayout.LayoutParams(-1, -2));
        final View[] gridHolder = new View[1];
        String[] groups = {GROUP_CCTV, GROUP_SAT, GROUP_MINE};
        final Runnable[] rebuild = new Runnable[1];
        for (String g : groups) {
            TextView chip = pill(a, g, group[0].equals(g) ? 0xffffc7d6 : 0x38ffffff);
            chip.setTextColor(group[0].equals(g) ? 0xff31141f : MainActivity.WHITE);
            chip.setOnClickListener(
                    v -> {
                        group[0] = g;
                        rebuild[0].run();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(34));
            lp.rightMargin = a.dp(8);
            chipRow.addView(chip, lp);
        }

        LinearLayout gridWrap = a.vertical();
        body.addView(gridWrap, new LinearLayout.LayoutParams(-1, -2));

        rebuild[0] =
                () -> {
                    // 刷新 chips 颜色
                    for (int i = 0; i < chipRow.getChildCount(); i++) {
                        TextView c = (TextView) chipRow.getChildAt(i);
                        boolean on = groups[i].equals(group[0]);
                        c.setBackground(a.bg(on ? 0xffffc7d6 : 0x38ffffff, 999));
                        c.setTextColor(on ? 0xff31141f : MainActivity.WHITE);
                    }
                    gridWrap.removeAllViews();
                    List<Channel> list = store.group(group[0]);
                    if (list.isEmpty()) {
                        TextView empty = a.text("还没有频道，点右上角「＋ 自定义」添加", 13, 0xffa9b7d0);
                        empty.setPadding(0, a.dp(20), 0, a.dp(20));
                        gridWrap.addView(empty);
                        return;
                    }
                    LinearLayout row = null;
                    for (int i = 0; i < list.size(); i++) {
                        if (i % 4 == 0) {
                            row = a.row();
                            gridWrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                        }
                        final Channel ch = list.get(i);
                        LinearLayout card = a.vertical();
                        card.setGravity(Gravity.CENTER);
                        card.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(12));
                        card.setBackground(
                                MobileDetailScreen.glassBg(
                                        a, 0x661e3557, 0x44ffffff, a.dp(16)));
                        MobileDetailScreen.bindTouchFeedback(card);
                        TextView num = a.text(ch.name, 12.5f, MainActivity.WHITE);
                        num.setTypeface(null, android.graphics.Typeface.BOLD);
                        num.setGravity(Gravity.CENTER);
                        num.setMaxLines(2);
                        card.addView(num);
                        TextView play = a.text("▶ 直播中", 10.5f, 0xffff8fb1);
                        play.setPadding(0, a.dp(6), 0, 0);
                        card.addView(play);
                        card.setOnClickListener(
                                v -> open(a, store, group[0], ch));
                        card.setOnLongClickListener(
                                v -> {
                                    toggleFavorite(a, ch);
                                    rebuild[0].run();
                                    a.toast(
                                            store.favorites.contains(ch.name)
                                                    ? "已收藏 " + ch.name
                                                    : "已取消收藏 " + ch.name);
                                    return true;
                                });
                        LinearLayout.LayoutParams lp =
                                new LinearLayout.LayoutParams(0, a.dp(92), 1f);
                        lp.rightMargin = a.dp(8);
                        row.addView(card, lp);
                    }
                };
        rebuild[0].run();
        return root;
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
                                JSONObject json = new JSONObject(input.getText().toString().trim());
                                JSONArray arr = json.optJSONArray("channels");
                                if (arr == null) throw new IllegalArgumentException("缺少 channels");
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.getJSONObject(i);
                                    String n = o.optString("name").trim();
                                    String u = o.optString("url").trim();
                                    if (!n.isEmpty() && Cms.isHttp(u))
                                        store.custom.add(new Channel(n, u, GROUP_SAT));
                                }
                                saveCustom(a, store.custom);
                                a.toast("已导入 " + arr.length() + " 个频道");
                                refresh(a);
                            } catch (Exception e) {
                                a.toast("JSON 格式不对：" + e.getMessage());
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

    static void toggleFavorite(MainActivity a, Channel c) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : a.prefs.getString(FAVORITES, "").split("\n"))
            if (!s.isEmpty()) set.add(s);
        if (!set.remove(c.name)) set.add(c.name);
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        a.prefs.edit().putString(FAVORITES, sb.toString()).apply();
    }

    static Set<String> readFavorites(MainActivity a) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : a.prefs.getString(FAVORITES, "").split("\n"))
            if (!s.isEmpty()) set.add(s);
        return set;
    }

    // ======================= 播放器 =======================

    static void open(MainActivity a, Store store, String group, Channel channel) {
        new Player(a, store, group, channel).show();
    }

    static final class Player {
        final MainActivity a;
        final Store store;
        final List<Channel> list;
        int index;
        FrameLayout overlay;
        WebView web;
        TextView title;
        LinearLayout bar;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable inject =
                new Runnable() {
                    @Override
                    public void run() {
                        if (web == null) return;
                        web.evaluateJavascript(INJECT_JS, null);
                        handler.postDelayed(this, 1800);
                    }
                };

        Player(MainActivity a, Store store, String group, Channel channel) {
            this.a = a;
            this.store = store;
            this.list = store.group(group);
            this.index = Math.max(0, indexOf(list, channel.name));
        }

        static int indexOf(List<Channel> list, String name) {
            for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
            return 0;
        }

        void show() {
            overlay = new FrameLayout(a);
            overlay.setBackgroundColor(0xff000000);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            web = new WebView(a);
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
                            if (progress > 80) handler.post(inject);
                        }
                    });
            web.setWebViewClient(
                    new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView v, String url) {
                            handler.post(inject);
                        }
                    });
            overlay.addView(web, new FrameLayout.LayoutParams(-1, -1));

            // 顶部悬浮条：返回 + 频道名 + 列表
            LinearLayout top = a.row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            top.setBackground(a.bg(0xb3000000, 18));
            TextView back = a.text("‹", 24, Color.WHITE);
            back.setGravity(Gravity.CENTER);
            back.setPadding(0, 0, a.dp(8), a.dp(2));
            back.setOnClickListener(v -> close());
            top.addView(back, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));
            title = a.text(list.isEmpty() ? "" : list.get(index).name, 15, Color.WHITE);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView listBtn = a.text("频道列表", 12, Color.WHITE);
            listBtn.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            listBtn.setBackground(a.bg(0x33ffffff, 999));
            listBtn.setOnClickListener(v -> toggleBar());
            top.addView(listBtn);
            FrameLayout.LayoutParams topLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            topLp.setMargins(a.dp(8), statusBar(a) + a.dp(6), a.dp(8), 0);
            overlay.addView(top, topLp);

            // 频道切换条
            bar = a.row();
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            hs.setBackground(a.bg(0xcc000000, 14));
            hs.addView(bar);
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                TextView chip = a.text(list.get(i).name, 12, Color.WHITE);
                chip.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                chip.setBackground(a.bg(idx == index ? 0xffffc7d6 : 0x38ffffff, 999));
                if (idx == index) chip.setTextColor(0xff31141f);
                chip.setOnClickListener(v -> tune(idx));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = a.dp(6);
                bar.addView(chip, lp);
            }
            FrameLayout.LayoutParams barLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            barLp.setMargins(a.dp(8), 0, a.dp(8), a.dp(18));
            overlay.addView(hs, barLp);
            hs.setVisibility(View.GONE);

            // 手势层：上下滑调亮度/音量（左亮度右音量）
            View gesture = new View(a);
            gesture.setBackgroundColor(Color.TRANSPARENT);
            gesture.setOnTouchListener(new SwipeGesture());
            overlay.addView(gesture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

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
        }

        void tune(int idx) {
            if (idx < 0 || idx >= list.size()) return;
            index = idx;
            title.setText(list.get(idx).name);
            for (int i = 0; i < bar.getChildCount(); i++) {
                TextView c = (TextView) bar.getChildAt(i);
                boolean on = i == idx;
                c.setBackground(a.bg(on ? 0xffffc7d6 : 0x38ffffff, 999));
                c.setTextColor(on ? 0xff31141f : Color.WHITE);
            }
            load();
        }

        void load() {
            if (list.isEmpty()) return;
            handler.removeCallbacks(inject);
            String url = list.get(index).url;
            web.loadUrl(url);
            handler.postDelayed(inject, 2500);
        }

        void toggleBar() {
            bar.getParent().requestDisallowInterceptTouchEvent(true);
            ((View) bar.getParent()).setVisibility(
                    ((View) bar.getParent()).getVisibility() == View.VISIBLE
                            ? View.GONE
                            : View.VISIBLE);
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
            if (overlay != null && overlay.getParent() instanceof FrameLayout)
                ((FrameLayout) overlay.getParent()).removeView(overlay);
            if (web != null) {
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
            overlay = null;
            if (a.tvPlayer == this) a.tvPlayer = null;
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
                    + "var sel=['.vjs-big-play-button','.dplayer-play-icon','.xgplayer-start','.prism-player .play-btn','.play-button','.live-play'];"
                    + "for(var i=0;i<sel.length;i++){var el=document.querySelector(sel[i]);if(el){el.click();break;}}"
                    + "var vs=document.querySelectorAll('video');"
                    + "for(var j=0;j<vs.length;j++){var v=vs[j];try{v.muted=false;v.play();}catch(e){}}"
                    + "}catch(e){}"
                    + "})();";
}
