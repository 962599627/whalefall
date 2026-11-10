package com.whalefall.movie;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.6「云盘配置」真数据页：读取 MDrive site 的 ext.Cloud-drive 相对地址，
 * 按配置源 origin 解析成完整 URL，worker 拉取文本并按行/JSON 解析网盘条目，
 * 显示真实「名称 + 地址」，支持复制地址与打开源面板；解析失败显示原文，不造假条目。
 */
final class CloudConfigScreen {
    private CloudConfigScreen() {}

    private static final class Item {
        final String name;
        final String address;

        Item(String name, String address) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
        }
    }

    static void show(final MainActivity a) {
        a.renderPage("云盘配置", "SOURCE · CLOUD DRIVE");
        Cms.Site source = findSource(a);
        String origin = a.configOrigin == null ? "" : a.configOrigin;
        if (origin.isEmpty()) origin = a.prefs.getString(Prefs.ORIGIN, "");
        String rel = extCloudDrive(source);
        String extUrl = rel.isEmpty() ? "" : Cms.resolve(origin, rel);
        AppLog.i(
                "CloudConfig",
                "origin=" + origin + " ext=" + rel + " extUrl=" + extUrl);

        if (source == null) {
            a.paragraph("未找到网盘来源（MDrive）站点。");
            addSourcePanelButton(a);
            return;
        }
        if (extUrl.isEmpty()) {
            a.paragraph("该来源没有配置 Cloud-drive 地址（ext.Cloud-drive 为空）。");
            addOpenSourceButton(a, source);
            return;
        }
        final TextView status = a.text("正在读取：" + extUrl, 11.5f, MainActivity.MUTED);
        status.setSingleLine(false);
        status.setPadding(0, a.dp(6), 0, a.dp(8));
        a.body.addView(status);
        final LinearLayout list = a.vertical();
        a.body.addView(list, new LinearLayout.LayoutParams(-1, -2));
        addOpenSourceButton(a, source);

        a.worker.execute(
                () -> {
                    java.util.List<Item> items = new java.util.ArrayList<>();
                    String raw = "";
                    String error = "";
                    try {
                        raw = Cms.fetch(extUrl, source.headers);
                        items = parse(raw);
                        AppLog.i(
                                "CloudConfig",
                                "lines=" + countLines(raw) + " items=" + items.size());
                    } catch (Throwable failure) {
                        error = a.message(failure);
                        AppLog.i("CloudConfig", "fetch error=" + failure.getClass().getSimpleName());
                    }
                    final java.util.List<Item> result = items;
                    final String rawText = raw;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (a.body == null) return;
                                status.setText(
                                        failure.isEmpty()
                                                ? "来源：" + extUrl + " · " + result.size() + " 条"
                                                : "读取失败：" + failure);
                                if (!failure.isEmpty()) {
                                    if (!rawText.isEmpty()) {
                                        TextView text = a.text(rawText, 11, MainActivity.MUTED);
                                        text.setTextIsSelectable(true);
                                        a.body.addView(text);
                                    }
                                    a.addButton(
                                            a.body,
                                            a.button(
                                                    "重试",
                                                    "cloud-config-retry",
                                                    () -> show(a)));
                                    return;
                                }
                                if (result.isEmpty()) {
                                    a.paragraph("该地址没有解析到网盘条目。");
                                    TextView text = a.text(rawText, 11, MainActivity.MUTED);
                                    text.setTextIsSelectable(true);
                                    a.body.addView(text);
                                    return;
                                }
                                for (Item item : result) list.addView(row(a, item));
                            });
                });
    }

    private static View row(MainActivity a, final Item item) {
        LinearLayout row = a.vertical();
        row.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView name = a.text(item.name, 14, MainActivity.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name);
        TextView address = a.text(item.address, 11, 0xff9fb0c9);
        address.setMaxLines(2);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(-1, -2);
        addressLp.topMargin = a.dp(4);
        row.addView(address, addressLp);
        LinearLayout actions = a.row();
        TextView copy = a.text("复制地址", 12, 0xffffb3c8);
        copy.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        copy.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x1affffff, 999));
        MobileDetailScreen.bindTouchFeedback(copy);
        copy.setOnClickListener(
                v -> {
                    copyToClipboard(a, item.address);
                    a.toast("已复制网盘地址");
                });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
        copyLp.topMargin = a.dp(8);
        actions.addView(copy, copyLp);
        row.addView(actions);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = a.dp(8);
        row.setLayoutParams(rowLp);
        return row;
    }

    private static void addSourcePanelButton(MainActivity a) {
        a.addButton(
                a.body,
                a.button(
                        "打开源面板",
                        "cloud-config-source",
                        () -> new SettingsScreen(a).sourceAccountAction("LoginShow", null)));
    }

    private static void addOpenSourceButton(final MainActivity a, final Cms.Site source) {
        a.addButton(
                a.body,
                a.button(
                        "打开源面板",
                        "cloud-config-source",
                        () -> new SettingsScreen(a).sourceAccountAction("LoginShow", null)));
    }

    private static void copyToClipboard(MainActivity a, String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("网盘地址", text));
        } catch (Throwable ignored) {
        }
    }

    static Cms.Site findSource(MainActivity a) {
        if (a.config == null) return null;
        for (Cms.Site site : a.config.sites) {
            String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
            String name = site.name == null ? "" : site.name;
            if (api.contains("mydrive") || name.contains("云盘") || name.contains("網盤")) {
                return site;
            }
        }
        return null;
    }

    static String extCloudDrive(Cms.Site site) {
        if (site == null || site.ext == null) return "";
        try {
            if (site.ext instanceof org.json.JSONObject) {
                org.json.JSONObject ext = (org.json.JSONObject) site.ext;
                for (String key : new String[] {"Cloud-drive", "cloud-drive", "CloudDrive", "cloud_drive"}) {
                    String value = ext.optString(key, "").trim();
                    if (!value.isEmpty()) return value;
                }
                return "";
            }
            String text = String.valueOf(site.ext).trim();
            if (text.startsWith("{")) {
                org.json.JSONObject ext = new org.json.JSONObject(text);
                for (String key : new String[] {"Cloud-drive", "cloud-drive", "CloudDrive", "cloud_drive"}) {
                    String value = ext.optString(key, "").trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 按行解析：支持 JSON（数组/对象）与「名称|地址|参数」纯文本。 */
    static java.util.List<Item> parse(String raw) {
        java.util.List<Item> items = new java.util.ArrayList<>();
        if (raw == null) return items;
        String text = raw.trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                Object parsed =
                        text.startsWith("[")
                                ? new org.json.JSONArray(text)
                                : new org.json.JSONObject(text);
                if (parsed instanceof org.json.JSONArray) {
                    org.json.JSONArray arr = (org.json.JSONArray) parsed;
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String name = o.optString("name", o.optString("title", "网盘" + (i + 1)));
                        String address = o.optString("url", o.optString("address", o.optString("path", "")));
                        if (!name.isEmpty() || !address.isEmpty()) items.add(new Item(name, address));
                    }
                    if (!items.isEmpty()) return items;
                } else {
                    org.json.JSONObject o = (org.json.JSONObject) parsed;
                    java.util.Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = o.opt(key);
                        if (value instanceof String)
                            items.add(new Item(key, String.valueOf(value)));
                        else if (value instanceof org.json.JSONObject) {
                            org.json.JSONObject obj = (org.json.JSONObject) value;
                            String address =
                                    obj.optString(
                                            "url",
                                            obj.optString(
                                                    "address", obj.optString("path", "")));
                            if (!address.isEmpty()) items.add(new Item(key, address));
                        }
                    }
                    if (!items.isEmpty()) return items;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String line : raw.split("\r?\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("//")) continue;
            String[] parts = value.split("\\s*[|｜]\\s*");
            if (parts.length >= 2) items.add(new Item(parts[0], parts[1]));
            else items.add(new Item(value, ""));
        }
        return items;
    }

    private static int countLines(String raw) {
        return raw == null || raw.isEmpty() ? 0 : raw.split("\r?\n").length;
    }
}
