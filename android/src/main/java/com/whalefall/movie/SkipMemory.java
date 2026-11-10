package com.whalefall.movie;

import java.util.Locale;
import org.json.JSONObject;

/** 1.8.1 智能片头片尾记忆：按「规范化剧名」记住每部剧的片头秒数/片尾剩余秒数（本机持久化）。 */
final class SkipMemory {
    static final String KEY = "skip_memory_v1";
    static final int MAX_SECONDS = 180;
    static final int LIMIT = 500;

    static final class Mem {
        int intro;
        int outro;
    }

    private SkipMemory() {}

    static String normalize(String title) {
        if (title == null) return "";
        String text = title.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[\\s·・:：\\-—_.,，。!！?？()（）\\[\\]【】]", "");
        text = text.replaceAll("第\\d+[季集话話].*$", "");
        return text;
    }

    static Mem read(MainActivity a, String title) {
        Mem mem = new Mem();
        String key = normalize(title);
        if (key.isEmpty()) return mem;
        try {
            JSONObject root =
                    new JSONObject(a.prefs.getString(KEY, "{}"));
            JSONObject row = root.optJSONObject(key);
            if (row != null) {
                mem.intro = Math.max(0, Math.min(MAX_SECONDS, row.optInt("intro", 0)));
                mem.outro = Math.max(0, Math.min(MAX_SECONDS, row.optInt("outro", 0)));
            }
        } catch (Throwable ignored) {
        }
        return mem;
    }

    static void save(MainActivity a, String title, int intro, int outro) {
        String key = normalize(title);
        if (key.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(a.prefs.getString(KEY, "{}"));
            JSONObject row =
                    new JSONObject()
                            .put("intro", Math.max(0, Math.min(MAX_SECONDS, intro)))
                            .put("outro", Math.max(0, Math.min(MAX_SECONDS, outro)));
            root.put(key, row);
            a.prefs.edit().putString(KEY, root.toString()).apply();
            AppLog.i(
                    "SkipMemory",
                    "save title=" + key + " intro=" + intro + " outro=" + outro);
        } catch (Throwable error) {
            AppLog.w("SkipMemory", "save failed", error);
        }
    }

    static void clear(MainActivity a, String title) {
        String key = normalize(title);
        if (key.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(a.prefs.getString(KEY, "{}"));
            root.remove(key);
            a.prefs.edit().putString(KEY, root.toString()).apply();
            AppLog.i("SkipMemory", "clear title=" + key);
        } catch (Throwable ignored) {
        }
    }

    static String summary(MainActivity a, String title) {
        Mem mem = read(a, title);
        if (mem.intro <= 0 && mem.outro <= 0) return "";
        StringBuilder out = new StringBuilder("本剧跳过：");
        if (mem.intro > 0)
            out.append("片头 ").append(PlaybackController.formatTime(mem.intro * 1000));
        if (mem.outro > 0)
            out.append(mem.intro > 0 ? " · " : "")
                    .append("片尾 ")
                    .append(PlaybackController.formatTime(mem.outro * 1000));
        return out.toString();
    }
}
