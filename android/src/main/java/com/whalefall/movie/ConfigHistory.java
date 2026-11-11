package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** 1.8.7 配置地址历史：最多 10 条、去重、最新在前；成功导入后记录。 */
final class ConfigHistory {
 static final int LIMIT = 10;

 private ConfigHistory() {}

 static List<String> read(MainActivity a) {
 List<String> out = new ArrayList<>();
 try {
 JSONArray arr = new JSONArray(a.prefs.getString(Prefs.CONFIG_HISTORY, "[]"));
 for (int i = 0; i < arr.length() && out.size() < LIMIT; i++) {
 String value = arr.optString(i, "").trim();
 if (!value.isEmpty() && !out.contains(value)) out.add(value);
 }
 } catch (Throwable ignored) {
 }
 return out;
 }

 static void write(MainActivity a, List<String> list) {
 JSONArray arr = new JSONArray();
 for (String value : list) arr.put(value);
 a.prefs.edit().putString(Prefs.CONFIG_HISTORY, arr.toString()).apply();
 }

 static void add(MainActivity a, String address) {
 if (a == null || address == null) return;
 String value = address.trim();
 if (!Cms.isHttp(value)) return;
 List<String> list = read(a);
 list.remove(value);
 list.add(0, value);
 while (list.size() > LIMIT) list.remove(list.size() - 1);
 write(a, list);
 AppLog.i("ConfigHistory", "added=" + mask(value) + " total=" + list.size());
 }

 static void remove(MainActivity a, String address) {
 List<String> list = read(a);
 list.remove(address);
 write(a, list);
 }

 static void clear(MainActivity a) {
 write(a, new ArrayList<>());
 }

 /** 启动时用当前 origin 补齐一次（不重复）。 */
 static void backfill(MainActivity a) {
 String origin = a.configOrigin == null ? "" : a.configOrigin;
 if (origin.isEmpty()) origin = a.prefs.getString(Prefs.ORIGIN, "");
 if (Cms.isHttp(origin)) add(a, origin);
 }

 /** 历史列表弹窗：点行应用，右侧删除；底部清空历史。 */
 static void showDialog(final MainActivity a, final MainActivity.Done<String> onPick) {
 final List<String> list = read(a);
 final AppDialog[] holder = new AppDialog[1];
 LinearLayout box = a.vertical();
 String current = a.prefs.getString(Prefs.ORIGIN, "");
 if (list.isEmpty()) {
 TextView empty = a.text("暂无历史配置", 14, MainActivity.MUTED);
 empty.setGravity(Gravity.CENTER);
 empty.setPadding(0, a.dp(24), 0, a.dp(24));
 box.addView(empty);
 } else {
 for (final String address : list) {
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(0, a.dp(6), 0, a.dp(6));
 boolean active = address.equals(current) || address.equals(a.configOrigin);
 TextView text =
 a.text(
 (active ? "✓ " : "") + mask(address),
 13,
 active ? 0xffffb3c8 : UiTokens.TEXT_PRIMARY);
 text.setSingleLine(true);
 text.setEllipsize(android.text.TextUtils.TruncateAt.END);
 text.setFocusable(true);
 MobileDetailScreen.bindTouchFeedback(text);
 text.setOnClickListener(
 v -> {
 try {
 onPick.accept(address);
 } catch (Exception error) {
 a.toast("导入失败：" + a.message(error));
 }
 });
 row.addView(text, new LinearLayout.LayoutParams(0, a.dp(40), 1f));
 TextView del = a.text("删除", 12, 0xffef4444);
 del.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
 del.setFocusable(true);
 MobileDetailScreen.bindTouchFeedback(del);
 del.setOnClickListener(
 v -> {
 if (holder[0] != null) holder[0].dismiss();
 remove(a, address);
 showDialog(a, onPick);
 });
 row.addView(del);
 box.addView(row);
 }
 TextView clear = a.text("清空历史", 12, 0xffef4444);
 clear.setGravity(Gravity.CENTER);
 clear.setPadding(0, a.dp(12), 0, a.dp(8));
 clear.setFocusable(true);
 MobileDetailScreen.bindTouchFeedback(clear);
 clear.setOnClickListener(
 v ->
 new AppDialog.Builder(a)
 .setTitle("清空配置历史？")
 .setMessage("只清除历史记录，不影响当前配置。")
 .setPositiveButton(
 "清空",
 (d, i) -> {
 if (holder[0] != null) holder[0].dismiss();
 clear(a);
 showDialog(a, onPick);
 })
 .setNegativeButton("取消", null)
 .show());
 box.addView(clear);
 }
 holder[0] =
 new AppDialog.Builder(a)
 .setTitle("配置历史列表")
 .setView(box)
 .setNegativeButton("关闭", null)
 .show();
 }

 static String mask(String address) {
 if (address == null) return "";
 String value = address.trim();
 int query = value.indexOf('?');
 return query > 0 ? value.substring(0, query) + "?…" : value;
 }
}
