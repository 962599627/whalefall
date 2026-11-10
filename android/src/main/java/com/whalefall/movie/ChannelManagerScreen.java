// Copyright (C) 2026 WhaleFall
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** 电视频道管理：批量选择、隐藏内置、删除自定义、查看/编辑配置。 */
final class ChannelManagerScreen {

    private ChannelManagerScreen() {}

    private static final class Row {
        final String name;
        final String url;
        final String group;
        final boolean builtin;
        final boolean hidden;
        final boolean edited;

        Row(String name, String url, String group, boolean builtin, boolean hidden, boolean edited) {
            this.name = name;
            this.url = url;
            this.group = group;
            this.builtin = builtin;
            this.hidden = hidden;
            this.edited = edited;
        }
    }

    static void show(MainActivity a) {
        a.open(new MainActivity.Screen(() -> render(a)), true);
    }

    private static void render(MainActivity a) {
        a.clearPlaybackLoading();
        a.cancelPageRequests();
        a.generation++;
        a.applyMobileSystemBars(false);
        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.installBackdrop(a.root);
        a.setContentView(a.root);

        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBar(a) + a.dp(8), a.dp(16), a.dp(12));
        a.root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // 顶部：返回 + 标题 + 配置按钮
        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = a.text("‹", 22, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setBackground(MobileDetailScreen.glassBg(a, 0xcc172842, 0xcc182b4c, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> a.onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));
        TextView title = a.text("频道管理", 19, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(a.dp(10), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView config = a.text("配置", 12.5f, 0xffffb3c8);
        config.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        config.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(config);
        config.setOnClickListener(
                v ->
                        new AppDialog.Builder(a)
                                .setTitle("频道配置")
                                .setItems(
                                        new String[] {
                                            "编辑自定义频道配置",
                                            "导出全部频道配置（含内置）",
                                            "导入全部频道配置"
                                        },
                                        (d, index) -> {
                                            if (index == 0) editConfig(a);
                                            else if (index == 1) exportAllConfig(a);
                                            else importAllConfig(a);
                                        })
                                .setNegativeButton("关闭", null)
                                .show());
        bar.addView(config);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        a.gap(page, 10);

        final Set<String> selected = new HashSet<>();
        final String[] filter = {"all"};
        final String[] query = {""};
        final Adapter[] adapterRef = {null};
        final RecyclerView[] listRef = {null};
        final Runnable[] refresh = new Runnable[1];

        // 搜索框
        EditText search = new EditText(a);
        search.setHint("搜索频道名称或地址…");
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xff8d9cb6);
        search.setBackground(MobileDetailScreen.glassBg(a, 0x33ffffff, 0x33ffffff, 999));
        search.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        page.addView(search, new LinearLayout.LayoutParams(-1, a.dp(42)));
        a.gap(page, 8);

        // 分类筛选胶囊
        android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(a);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = a.row();
        chipsScroll.addView(chips);
        final java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
        filters.put("all", "全部");
        filters.put("cctv", "央视频道");
        filters.put("sat", "卫视频道");
        filters.put("custom", "自定义");
        filters.put("hidden", "已隐藏");
        final java.util.Map<String, TextView> chipViews = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : filters.entrySet()) {
            TextView chip = a.text(e.getValue(), 12, Color.WHITE);
            chip.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
            MobileDetailScreen.bindTouchFeedback(chip);
            chipViews.put(e.getKey(), chip);
            chips.addView(chip, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(a.dp(8), 1);
            chips.addView(new View(a), gapLp);
            chip.setOnClickListener(
                    v -> {
                        filter[0] = e.getKey();
                        refresh[0].run();
                    });
        }
        page.addView(chipsScroll, new LinearLayout.LayoutParams(-1, a.dp(34)));
        a.gap(page, 6);

        final RecyclerView list = new RecyclerView(a);
        list.setLayoutManager(new LinearLayoutManager(a));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, a.dp(8));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        listRef[0] = list;

        refresh[0] =
                () -> {
                    for (java.util.Map.Entry<String, TextView> e : chipViews.entrySet()) {
                        boolean active = e.getKey().equals(filter[0]);
                        e.getValue().setTextColor(active ? 0xff31141f : Color.WHITE);
                        e.getValue()
                                .setBackground(
                                        MobileDetailScreen.glassBg(
                                                a,
                                                active ? 0xffffb3c8 : 0x1affffff,
                                                0x33ffffff,
                                                999));
                    }
                    List<Row> data = filtered(a, filter[0], query[0]);
                    adapterRef[0] = new Adapter(a, data, selected, () -> refresh[0].run());
                    listRef[0].setAdapter(adapterRef[0]);
                };
        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence c, int s2, int e2, int n2) {}

                    @Override
                    public void onTextChanged(CharSequence c, int s2, int b2, int n2) {
                        query[0] = c == null ? "" : c.toString().trim().toLowerCase(java.util.Locale.ROOT);
                        refresh[0].run();
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable e2) {}
                });
        refresh[0].run();

        // 底部操作栏
        LinearLayout actions = a.row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView all = action(a, "全选");
        TextView none = action(a, "清空");
        TextView delete = action(a, "删除所选");
        TextView hide = action(a, "隐藏内置");
        TextView restore = action(a, "恢复隐藏");
        TextView add = action(a, "＋ 新增");
        actions.addView(all);
        actions.addView(none);
        actions.addView(delete);
        actions.addView(hide);
        actions.addView(restore);
        actions.addView(add);
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        all.setOnClickListener(
                v -> {
                    selected.clear();
                    if (adapterRef[0] != null)
                        for (Row r : adapterRef[0].rows) selected.add(r.name);
                    refresh[0].run();
                });
        none.setOnClickListener(
                v -> {
                    selected.clear();
                    refresh[0].run();
                });
        delete.setOnClickListener(
                v -> {
                    if (selected.isEmpty()) {
                        a.toast("先勾选要删除/隐藏的频道");
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("删除所选频道")
                            .setMessage("自定义频道会被删除；内置频道会被隐藏（可随时恢复）。")
                            .setPositiveButton(
                                    "确定",
                                    (d, w) -> {
                                        List<TvLive.Channel> custom =
                                                new ArrayList<>(TvLive.readCustom(a));
                                        Set<String> hidden = TvLive.readHidden(a);
                                        int deleted = 0, hid = 0;
                                        for (String name : new ArrayList<>(selected)) {
                                            if (TvLive.isBuiltin(name)) {
                                                hidden.add(name);
                                                hid++;
                                            } else {
                                                for (int i = custom.size() - 1; i >= 0; i--)
                                                    if (custom.get(i).name.equals(name)) {
                                                        custom.remove(i);
                                                        deleted++;
                                                    }
                                            }
                                        }
                                        TvLive.saveCustom(a, custom);
                                        TvLive.saveHidden(a, hidden);
                                        selected.clear();
                                        a.toast("已删除 " + deleted + " 个，隐藏 " + hid + " 个");
                                        refresh[0].run();
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });
        hide.setOnClickListener(
                v -> {
                    if (selected.isEmpty()) {
                        a.toast("先勾选要隐藏的内置频道");
                        return;
                    }
                    Set<String> hidden = TvLive.readHidden(a);
                    int count = 0;
                    for (String name : selected)
                        if (TvLive.isBuiltin(name)) {
                            hidden.add(name);
                            count++;
                        }
                    TvLive.saveHidden(a, hidden);
                    selected.clear();
                    a.toast("已隐藏 " + count + " 个内置频道");
                    refresh[0].run();
                });
        restore.setOnClickListener(
                v -> {
                    TvLive.saveHidden(a, new LinkedHashSet<>());
                    selected.clear();
                    a.toast("已恢复显示全部内置频道");
                    refresh[0].run();
                });
        add.setOnClickListener(v -> editChannel(a, null, refresh[0]));
    }

    /** 按分组 / 自定义 / 已隐藏 / 搜索词筛选。 */
    private static List<Row> filtered(MainActivity a, String filter, String query) {
        List<Row> all = collect(a);
        List<Row> out = new ArrayList<>();
        for (Row row : all) {
            boolean hidden = row.hidden;
            boolean custom = !row.builtin;
            boolean cctv = TvLive.GROUP_CCTV.equals(row.group);
            boolean sat = TvLive.GROUP_SAT.equals(row.group);
            if ("cctv".equals(filter) && !cctv) continue;
            if ("sat".equals(filter) && !sat) continue;
            if ("custom".equals(filter) && !custom) continue;
            if ("hidden".equals(filter) && !hidden) continue;
            if (!query.isEmpty()) {
                String hay = (row.name + " " + row.url + " " + row.group).toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            out.add(row);
        }
        return out;
    }

    private static List<Row> collect(MainActivity a) {
        List<Row> rows = new ArrayList<>();
        Set<String> hidden = TvLive.readHidden(a);
        java.util.Map<String, String> overrides = TvLive.readUrlOverrides(a);
        for (Object[] row : TvLive.CCTV) {
            String name = (String) row[0];
            String url = overrides.getOrDefault(name, (String) row[1]);
            rows.add(
                    new Row(
                            name,
                            url,
                            TvLive.GROUP_CCTV,
                            true,
                            hidden.contains(name),
                            overrides.containsKey(name)));
        }
        for (Object[] row : TvLive.SAT) {
            String name = (String) row[0];
            String url = overrides.getOrDefault(name, (String) row[1]);
            rows.add(
                    new Row(
                            name,
                            url,
                            TvLive.GROUP_SAT,
                            true,
                            hidden.contains(name),
                            overrides.containsKey(name)));
        }
        for (TvLive.Channel c : TvLive.readCustom(a))
            rows.add(new Row(c.name, c.url, c.group, false, false, false));
        return rows;
    }

    private static TextView action(MainActivity a, String label) {
        TextView t = a.text(label, 11.5f, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(a.dp(8), a.dp(9), a.dp(8), a.dp(9));
        t.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = a.dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final MainActivity a;
        final List<Row> rows;
        final Set<String> selected;
        final Runnable refresh;

        Adapter(MainActivity a, List<Row> rows, Set<String> selected, Runnable refresh) {
            this.a = a;
            this.rows = rows;
            this.selected = selected;
            this.refresh = refresh;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x33ffffff, 12));
            android.widget.CheckBox box = new android.widget.CheckBox(a);
            box.setTag("box");
            row.addView(box, new LinearLayout.LayoutParams(a.dp(34), a.dp(34)));
            LinearLayout text = a.vertical();
            text.setTag("text");
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView more = a.text("⋯", 16, 0xffc9d4e6);
            more.setTag("more");
            more.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(more);
            row.addView(more);
            return new RecyclerView.ViewHolder(row) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            LinearLayout row = (LinearLayout) holder.itemView;
            Row item = rows.get(position);
            android.widget.CheckBox box = row.findViewWithTag("box");
            LinearLayout text = row.findViewWithTag("text");
            TextView more = row.findViewWithTag("more");
            box.setOnCheckedChangeListener(null);
            box.setChecked(selected.contains(item.name));
            box.setOnCheckedChangeListener(
                    (b, checked) -> {
                        if (checked) selected.add(item.name);
                        else selected.remove(item.name);
                    });
            text.removeAllViews();
            TextView name = a.text(item.name, 13.5f, item.hidden ? 0xff8d9cb6 : Color.WHITE);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            text.addView(name);
            String badge = item.builtin ? "内置 · " : "自定义 · ";
            if (item.hidden) badge = "已隐藏 · 内置 · ";
            if (item.edited) badge = "已改地址 · " + badge;
            TextView sub = a.text(badge + item.group + " · " + item.url, 10.5f, 0xff8d9cb6);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, a.dp(3), 0, 0);
            text.addView(sub);
            row.setAlpha(item.hidden ? 0.55f : 1f);
            more.setOnClickListener(
                    v ->
                            new AppDialog.Builder(a)
                                    .setTitle(item.name)
                                    .setItems(
                                            item.builtin
                                                    ? (item.edited
                                                            ? new String[] {
                                                                "编辑地址", "恢复默认地址", "隐藏该频道", "复制地址"
                                                            }
                                                            : new String[] {"编辑地址", "隐藏该频道", "复制地址"})
                                                    : new String[] {"修改频道", "编辑地址", "删除频道", "复制地址"},
                                            (d, index) -> {
                                                if (item.builtin) {
                                                    String choice = item.edited
                                                            ? new String[] {
                                                                "编辑地址", "恢复默认地址", "隐藏该频道", "复制地址"
                                                            }[index]
                                                            : new String[] {"编辑地址", "隐藏该频道", "复制地址"}[index];
                                                    if (choice.equals("编辑地址")) {
                                                        editChannel(a, item, refresh);
                                                        return;
                                                    }
                                                    if (choice.equals("恢复默认地址")) {
                                                        java.util.Map<String, String> overrides =
                                                                TvLive.readUrlOverrides(a);
                                                        overrides.remove(item.name);
                                                        TvLive.saveUrlOverrides(a, overrides);
                                                        a.toast("已恢复默认地址：" + item.name);
                                                    } else if (choice.equals("隐藏该频道")) {
                                                        Set<String> hidden = TvLive.readHidden(a);
                                                        hidden.add(item.name);
                                                        TvLive.saveHidden(a, hidden);
                                                        a.toast("已隐藏：" + item.name);
                                                    } else copy(a, item.url);
                                                } else {
                                                    if (index == 0) {
                                                        editChannel(a, item, refresh);
                                                        return;
                                                    }
                                                    if (index == 1) {
                                                        editChannel(a, item, refresh);
                                                        return;
                                                    }
                                                    if (index == 2) {
                                                        List<TvLive.Channel> custom =
                                                                new ArrayList<>(TvLive.readCustom(a));
                                                        for (int i = custom.size() - 1; i >= 0; i--)
                                                            if (custom.get(i).name.equals(item.name))
                                                                custom.remove(i);
                                                        TvLive.saveCustom(a, custom);
                                                        a.toast("已删除：" + item.name);
                                                    } else copy(a, item.url);
                                                }
                                                refresh.run();
                                            })
                                    .setNegativeButton("关闭", null)
                                    .show());
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static void copy(MainActivity a, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager)
                        a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("频道地址", text));
        a.toast("地址已复制");
    }

    /** 查看 / 编辑自定义频道配置（JSON）。 */
    static void editConfig(MainActivity a) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject json = new JSONObject();
            JSONArray arr = new JSONArray();
            for (TvLive.Channel c : TvLive.readCustom(a))
                arr.put(new JSONObject().put("name", c.name).put("url", c.url));
            json.put("channels", arr);
            sb.append(json.toString(2));
        } catch (Exception ignored) {
        }
        EditText input = new EditText(a);
        input.setText(sb.toString());
        input.setTextSize(12);
        input.setTextColor(Color.WHITE);
        input.setHint("{\"channels\":[{\"name\":\"频道\",\"url\":\"https://...\"}]}");
        input.setBackground(MobileDetailScreen.glassBg(a, 0x33ffffff, 0x33ffffff, 12));
        input.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AppDialog.Builder(a)
                .setTitle("配置（自定义频道 JSON）")
                .setMessage("修改后点保存；可粘贴 {\"channels\":[{\"name\":\"...\",\"url\":\"...\"}]} 批量导入。")
                .setView(input)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            try {
                                JSONObject json = new JSONObject(input.getText().toString());
                                JSONArray arr = json.optJSONArray("channels");
                                List<TvLive.Channel> list = new ArrayList<>();
                                if (arr != null)
                                    for (int i = 0; i < arr.length(); i++) {
                                        JSONObject o = arr.getJSONObject(i);
                                        String name = o.optString("name");
                                        String url = o.optString("url");
                                        if (!name.isEmpty() && !url.isEmpty())
                                            list.add(
                                                    new TvLive.Channel(
                                                            name, url, TvLive.GROUP_SAT));
                                    }
                                TvLive.saveCustom(a, list);
                                a.toast("已保存 " + list.size() + " 个自定义频道");
                                show(a);
                            } catch (Exception e) {
                                a.toast("JSON 格式错误：" + e.getMessage());
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出全部频道配置（内置 + 自定义 + 隐藏/改址状态），写入文件管理并打开预览。 */
    static void exportAllConfig(MainActivity a) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put(
                    "exportedAt",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                            .format(new java.util.Date()));
            JSONArray builtin = new JSONArray();
            for (Row row : collect(a))
                if (row.builtin)
                    builtin.put(
                            new JSONObject()
                                    .put("name", row.name)
                                    .put("url", row.url)
                                    .put("group", row.group)
                                    .put("hidden", row.hidden)
                                    .put("edited", row.edited));
            JSONArray custom = new JSONArray();
            for (TvLive.Channel c : TvLive.readCustom(a))
                custom.put(
                        new JSONObject()
                                .put("name", c.name)
                                .put("url", c.url)
                                .put("group", c.group));
            root.put("builtin", builtin);
            root.put("custom", custom);
            String rel = PushFiles.writeText(a, "频道配置/全部频道配置.json", root.toString(2));
            a.toast("已导出：文件管理/频道配置/全部频道配置.json");
            MarkdownScreen.show(a, rel);
        } catch (Exception e) {
            a.toast("导出失败：" + e.getMessage());
        }
    }

    /** 导入全部频道配置：内置改址/隐藏 + 自定义频道覆盖。 */
    static void importAllConfig(MainActivity a) {
        EditText input = new EditText(a);
        input.setHint("粘贴导出的全部频道配置 JSON…");
        input.setTextSize(11.5f);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff8d9cb6);
        input.setBackground(MobileDetailScreen.glassBg(a, 0x33ffffff, 0x33ffffff, 12));
        input.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AppDialog.Builder(a)
                .setTitle("导入全部频道配置")
                .setMessage("支持本功能导出的 JSON：内置频道会按 url/hidden 更新，自定义频道会被覆盖。")
                .setView(input)
                .setPositiveButton(
                        "导入",
                        (d, w) -> {
                            try {
                                JSONObject root = new JSONObject(input.getText().toString());
                                Set<String> hidden = TvLive.readHidden(a);
                                java.util.Map<String, String> overrides =
                                        TvLive.readUrlOverrides(a);
                                JSONArray builtin = root.optJSONArray("builtin");
                                if (builtin != null)
                                    for (int i = 0; i < builtin.length(); i++) {
                                        JSONObject o = builtin.getJSONObject(i);
                                        String name = o.optString("name");
                                        String url = o.optString("url");
                                        if (o.optBoolean("hidden", false)) hidden.add(name);
                                        else hidden.remove(name);
                                        if (!url.isEmpty()
                                                && !url.equals(TvLive.defaultUrl(name)))
                                            overrides.put(name, url);
                                        else overrides.remove(name);
                                    }
                                JSONArray custom = root.optJSONArray("custom");
                                if (custom != null) {
                                    List<TvLive.Channel> list = new ArrayList<>();
                                    for (int i = 0; i < custom.length(); i++) {
                                        JSONObject o = custom.getJSONObject(i);
                                        String name = o.optString("name");
                                        String url = o.optString("url");
                                        if (!name.isEmpty() && !url.isEmpty())
                                            list.add(new TvLive.Channel(name, url, TvLive.GROUP_SAT));
                                    }
                                    TvLive.saveCustom(a, list);
                                }
                                TvLive.saveHidden(a, hidden);
                                TvLive.saveUrlOverrides(a, overrides);
                                a.toast("已导入频道配置");
                                show(a);
                            } catch (Exception e) {
                                a.toast("导入失败：" + e.getMessage());
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void editChannel(MainActivity a, Row row, Runnable refresh) {
        LinearLayout box = a.vertical();
        EditText name = new EditText(a);
        name.setHint("频道名称");
        if (row != null) name.setText(row.name);
        style(a, name);
        EditText url = new EditText(a);
        url.setHint("直播地址（m3u8 / 官网直播页）");
        if (row != null) url.setText(row.url);
        style(a, url);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        box.addView(url, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tools = a.row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView paste = a.text("粘贴", 12, 0xffffb3c8);
        paste.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        paste.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(paste);
        paste.setOnClickListener(
                v -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(a);
                        if (text != null) url.setText(text.toString().trim());
                        a.toast("已粘贴地址");
                    } else a.toast("剪贴板没有内容");
                });
        TextView copy = a.text("复制", 12, 0xffc9d6ea);
        copy.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        copy.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(copy);
        copy.setOnClickListener(v -> copy(a, url.getText().toString()));
        tools.addView(paste);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
        copyLp.leftMargin = a.dp(8);
        tools.addView(copy, copyLp);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-2, -2);
        toolsLp.topMargin = a.dp(8);
        box.addView(tools, toolsLp);
        new AppDialog.Builder(a)
                .setTitle(row == null ? "新增频道" : "修改频道")
                .setView(box)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            String n = name.getText().toString().trim();
                            String u = url.getText().toString().trim();
                            if (n.isEmpty() || u.isEmpty()) {
                                a.toast("名称和地址不能为空");
                                return;
                            }
                            if (row != null && TvLive.isBuiltin(row.name)) {
                                java.util.Map<String, String> overrides =
                                        TvLive.readUrlOverrides(a);
                                if (u.equals(TvLive.defaultUrl(row.name)))
                                    overrides.remove(row.name);
                                else overrides.put(row.name, u);
                                TvLive.saveUrlOverrides(a, overrides);
                                a.toast("已保存地址：" + n);
                                if (refresh != null) refresh.run();
                                return;
                            }
                            List<TvLive.Channel> list = new ArrayList<>(TvLive.readCustom(a));
                            if (row != null)
                                for (int i = list.size() - 1; i >= 0; i--)
                                    if (list.get(i).name.equals(row.name)) list.remove(i);
                            list.add(new TvLive.Channel(n, u, TvLive.GROUP_SAT));
                            TvLive.saveCustom(a, list);
                            a.toast("已保存：" + n);
                            if (refresh != null) refresh.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void style(MainActivity a, EditText input) {
        input.setTextSize(13);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff8d9cb6);
        input.setBackground(MobileDetailScreen.glassBg(a, 0x33ffffff, 0x33ffffff, 12));
        input.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(6);
        input.setLayoutParams(lp);
    }

    private static int statusBar(MainActivity a) {
        return MobileDetailScreen.getStatusBarHeight(a);
    }
}
