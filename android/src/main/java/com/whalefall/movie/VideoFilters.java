package com.whalefall.movie;

import java.util.*;
import org.json.*;

/** Source-advertised filter values stay opaque; local filters use video metadata only. */
final class VideoFilters {
    static final String[] DIMENSIONS = {"class", "area", "year"};
    static final String[] LABELS = {"类型", "地区", "年份"};

    static final class Choice {
        final String label, value;

        Choice(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    static final class Definition {
        final String dimension, key, label;
        boolean remote;
        final List<Choice> choices = new ArrayList<>();

        Definition(String dimension, String key, String label, boolean remote) {
            this.dimension = dimension;
            this.key = key;
            this.label = label;
            this.remote = remote;
            choices.add(new Choice("全部", ""));
        }

        String labelFor(String value) {
            for (Choice choice : choices) if (choice.value.equals(value)) return choice.label;
            return "全部";
        }
    }

    static boolean allowedKey(String key) {
        return Arrays.asList("class", "genre", "area", "region", "country", "year").contains(key);
    }

    static String dimension(String key, String label) {
        if (key.equals("year") || label.contains("年份") || label.equals("年代")) return "year";
        if (Arrays.asList("area", "region", "country").contains(key)
                || label.contains("地区")
                || label.contains("国家")) return "area";
        if (key.equals("class") || key.equals("genre") || label.equals("类型") || label.equals("题材"))
            return "class";
        return "";
    }

    static List<Definition> definitions(String home, String category, List<Cms.Video> videos) {
        Map<String, Definition> remote = new LinkedHashMap<>();
        try {
            Object filters = new JSONObject(home).opt("filters");
            JSONArray groups =
                    filters instanceof JSONArray
                            ? (JSONArray) filters
                            : filters instanceof JSONObject
                                    ? ((JSONObject) filters).optJSONArray(category)
                                    : null;
            if (groups != null)
                for (int i = 0; i < Math.min(groups.length(), 32); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    String key = group.optString("key"), label = group.optString("name");
                    String dim = dimension(key, label);
                    // Preserve arbitrary Spider keys for recognized dimensions. HTTP engines
                    // additionally restrict keys that could alter their request routing.
                    if (dim.isEmpty() || key.isEmpty() || key.length() > 64) continue;
                    JSONArray values = group.optJSONArray("value");
                    if (values == null) values = group.optJSONArray("values");
                    Definition def =
                            new Definition(
                                    dim, key, LABELS[Arrays.asList(DIMENSIONS).indexOf(dim)], true);
                    Set<String> seen = new HashSet<>();
                    seen.add("");
                    if (values != null)
                        for (int n = 0; n < Math.min(values.length(), 256); n++) {
                            JSONObject item = values.optJSONObject(n);
                            String value =
                                    item == null
                                            ? values.optString(n)
                                            : item.optString("v", item.optString("value"));
                            String name =
                                    item == null
                                            ? value
                                            : item.optString("n", item.optString("name", value));
                            if (name.equals("全部") || name.equals("不限")) continue;
                            if (!value.isEmpty() && !name.isEmpty() && seen.add(value))
                                def.choices.add(new Choice(name, value));
                        }
                    if (def.choices.size() > 1) remote.put(dim, def);
                }
        } catch (JSONException ignored) {
        }
        List<Definition> result = new ArrayList<>();
        for (int i = 0; i < DIMENSIONS.length; i++) {
            String dim = DIMENSIONS[i];
            Definition def = remote.get(dim);
            if (def == null) {
                def = new Definition(dim, dim, LABELS[i], false);
                Set<String> values = new LinkedHashSet<>();
                if (dim.equals("class"))
                    Collections.addAll(
                            values, "剧情", "喜剧", "动作", "爱情", "科幻", "悬疑", "犯罪", "惊悚", "动画", "纪录片");
                if (dim.equals("area"))
                    Collections.addAll(
                            values, "中国大陆", "中国香港", "中国台湾", "美国", "英国", "日本", "韩国", "法国", "印度",
                            "泰国");
                if (dim.equals("year")) {
                    int year = Calendar.getInstance().get(Calendar.YEAR);
                    for (int n = year; n >= year - 15; n--) values.add(String.valueOf(n));
                }
                for (Cms.Video video : videos) {
                    String raw =
                            dim.equals("class")
                                    ? video.genre
                                    : dim.equals("area") ? video.area : video.year;
                    if (raw != null)
                        for (String value : raw.split("[ /、,，;；|]+")) {
                            value = value.trim();
                            if (!value.isEmpty() && value.length() <= 24 && values.size() < 256)
                                values.add(value);
                        }
                }
                for (String value : values) def.choices.add(new Choice(value, value));
            }
            result.add(def);
        }
        return result;
    }

    /** 1.9.35：分类是否有真实筛选能力（只看片源 filters schema，不用本地兜底推导）。 */
    static boolean hasSchemaFilters(String home, String category) {
        try {
            Object filters = new JSONObject(home).opt("filters");
            JSONArray groups =
                    filters instanceof JSONArray
                            ? (JSONArray) filters
                            : filters instanceof JSONObject
                                    ? ((JSONObject) filters).optJSONArray(category)
                                    : null;
            if (groups == null) return false;
            for (int i = 0; i < Math.min(groups.length(), 32); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                String key = group.optString("key"), label = group.optString("name");
                String dim = dimension(key, label);
                if (dim.isEmpty() || key.isEmpty() || key.length() > 64) continue;
                JSONArray values = group.optJSONArray("value");
                if (values == null) values = group.optJSONArray("values");
                if (values == null) continue;
                int usable = 0;
                for (int n = 0; n < Math.min(values.length(), 256); n++) {
                    JSONObject item = values.optJSONObject(n);
                    String value =
                            item == null
                                    ? values.optString(n)
                                    : item.optString("v", item.optString("value"));
                    if (item != null && value.isEmpty())
                        value = item.optString("v", item.optString("value"));
                    if (value == null || value.isEmpty()) continue;
                    String name =
                            item == null
                                    ? value
                                    : item.optString("n", item.optString("name", value));
                    if ("全部".equals(name) || "不限".equals(name)) continue;
                    usable++;
                }
                if (usable > 0) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static Map<String, String> remoteValues(
            List<Definition> definitions, Map<String, String> selected) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Definition def : definitions) {
            String value = selected.get(def.dimension);
            // 远程接口只接受单值：多选时取第一个值发过去（本地再用 OR 精确筛）
            if (def.remote && value != null && !value.isEmpty())
                values.put(def.key, value.split(",", 2)[0]);
        }
        return values;
    }

    /** 不区分远程/本地，全部按已加载影片资料匹配（给不支持筛选的片源兜底）。 */
    static List<Cms.Video> applyAll(
            List<Cms.Video> videos, List<Definition> definitions, Map<String, String> selected) {
        List<Cms.Video> result = new ArrayList<>();
        for (Cms.Video video : videos) {
            boolean match = true;
            for (Definition def : definitions) {
                String value = selected.get(def.dimension);
                if (value == null || value.isEmpty()) continue;
                String raw =
                        def.dimension.equals("class")
                                ? video.genre
                                : def.dimension.equals("area") ? video.area : video.year;
                if (raw == null || raw.trim().isEmpty()) {
                    match = false;
                    break;
                }
                boolean any = false;
                for (String one : value.split(",")) {
                    if (one.isEmpty()) continue;
                    if (matches(raw, def.labelFor(one), def.dimension)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    match = false;
                    break;
                }
            }
            if (match) result.add(video);
        }
        return result;
    }

    static boolean hasLocal(List<Definition> definitions, Map<String, String> selected) {
        for (Definition def : definitions)
            if (!def.remote && selected.containsKey(def.dimension)) return true;
        return false;
    }

    static List<Cms.Video> apply(
            List<Cms.Video> videos, List<Definition> definitions, Map<String, String> selected) {
        List<Cms.Video> result = new ArrayList<>();
        for (Cms.Video video : videos) {
            boolean match = true;
            for (Definition def : definitions) {
                String value = selected.get(def.dimension);
                if (value == null || value.isEmpty()) continue;
                String raw =
                        def.dimension.equals("class")
                                ? video.genre
                                : def.dimension.equals("area") ? video.area : video.year;
                // 资料缺失时不硬判失败（远程源可能已经筛过），交给远程结果
                if (raw == null || raw.trim().isEmpty()) continue;
                boolean any = false;
                for (String one : value.split(",")) {
                    if (one.isEmpty()) continue;
                    if (matches(raw, def.labelFor(one), def.dimension)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    match = false;
                    break;
                }
            }
            if (match) result.add(video);
        }
        return result;
    }

    static boolean matches(String raw, String value, String dimension) {
        if (raw == null || raw.trim().isEmpty()) return false;
        if (dimension.equals("area")) {
            raw =
                    raw.replace("中国大陆", "大陆")
                            .replace("内地", "大陆")
                            .replace("中国香港", "香港")
                            .replace("中国台湾", "台湾");
            value =
                    value.replace("中国大陆", "大陆")
                            .replace("内地", "大陆")
                            .replace("中国香港", "香港")
                            .replace("中国台湾", "台湾");
        }
        if (dimension.equals("year"))
            return raw.matches(
                    ".*(?<![0-9])" + java.util.regex.Pattern.quote(value) + "(?![0-9]).*");
        return raw.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }
}
