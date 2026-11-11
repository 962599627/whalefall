package com.whalefall.movie;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 推送播放队列：管理后台把多个视频链接加入队列，App 依次播放。 */
final class QueueStore {
 static final String KEY = "push_queue";

 static final class Item {
 String url, title;

 Item(String url, String title) {
 this.url = url;
 this.title = title == null || title.trim().isEmpty() ? "网页推送" : title.trim();
 }
 }

 private QueueStore() {}

 static List<Item> list(MainActivity a) {
 List<Item> out = new ArrayList<>();
 try {
 JSONArray arr = new JSONArray(a.prefs.getString(KEY, "[]"));
 for (int i = 0; i < arr.length(); i++) {
 JSONObject o = arr.getJSONObject(i);
 out.add(new Item(o.optString("url"), o.optString("title")));
 }
 } catch (Exception ignored) {
 }
 return out;
 }

 static void save(MainActivity a, List<Item> items) {
 JSONArray arr = new JSONArray();
 for (Item i : items) {
 try {
 arr.put(new JSONObject().put("url", i.url).put("title", i.title));
 } catch (Exception ignored) {
 }
 }
 a.prefs.edit().putString(KEY, arr.toString()).apply();
 }

 static void add(MainActivity a, String url, String title) {
 List<Item> items = list(a);
 items.add(new Item(url, title));
 save(a, items);
 }

 static void remove(MainActivity a, String url) {
 List<Item> items = list(a);
 for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).url.equals(url)) items.remove(i);
 save(a, items);
 }

 static void clear(MainActivity a) {
 a.prefs.edit().putString(KEY, "[]").apply();
 }

 static Item popFirst(MainActivity a) {
 List<Item> items = list(a);
 if (items.isEmpty()) return null;
 Item first = items.remove(0);
 save(a, items);
 return first;
 }

 /** 播放结束后自动接续队列里的下一个链接。 */
 static boolean playNext(MainActivity a) {
 Item next = popFirst(a);
 if (next == null) return false;
 PushFiles.playUrl(a, next.url, next.title);
 return true;
 }
}
