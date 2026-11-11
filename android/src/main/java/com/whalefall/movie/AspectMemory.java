package com.whalefall.movie;

import org.json.JSONObject;

/** 1.8.11 画面比例按影片记忆（规范化片名），未记忆时回退全局设置。 */
final class AspectMemory {
 static final String KEY = "aspect_memory_v1";

 private AspectMemory() {}

 static String normalize(String title) {
 if (title == null) return "";
 String text = title.trim().toLowerCase(java.util.Locale.ROOT);
 return text.replaceAll("[\\s·・:：\\-—_.,，。!！?？()（）\\[\\]【】]", "");
 }

 static int read(MainActivity a, String title, int fallback) {
 String key = normalize(title);
 if (key.isEmpty()) return fallback;
 try {
 JSONObject root = new JSONObject(a.prefs.getString(KEY, "{}"));
 return root.optInt(key, fallback);
 } catch (Throwable ignored) {
 return fallback;
 }
 }

 static void save(MainActivity a, String title, int mode) {
 String key = normalize(title);
 if (key.isEmpty()) return;
 try {
 JSONObject root = new JSONObject(a.prefs.getString(KEY, "{}"));
 root.put(key, mode);
 a.prefs.edit().putString(KEY, root.toString()).apply();
 AppLog.i("AspectMemory", "save title=" + key + " mode=" + mode);
 } catch (Throwable ignored) {
 }
 }

 static void clear(MainActivity a, String title) {
 String key = normalize(title);
 if (key.isEmpty()) return;
 try {
 JSONObject root = new JSONObject(a.prefs.getString(KEY, "{}"));
 root.remove(key);
 a.prefs.edit().putString(KEY, root.toString()).apply();
 AppLog.i("AspectMemory", "clear title=" + key);
 } catch (Throwable ignored) {
 }
 }

 static String label(int mode) {
 switch (mode) {
 case 0: return "适应画面";
 case 1: return "铺满屏幕";
 case 2: return "16:9";
 case 3: return "4:3";
 case 4: return "裁切填充";
 default: return "跟随设置";
 }
 }
}
