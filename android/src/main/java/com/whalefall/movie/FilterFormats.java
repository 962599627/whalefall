package com.whalefall.movie;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** 1.9.46：筛选参数格式自动探测（A 标准 query / B filters JSON / C by+原 key）。 */
final class FilterFormats {
    static final String A = "A";
    static final String B = "B";
    static final String C = "C";

    private static final Map<String, String> CACHE = new LinkedHashMap<>();

    private FilterFormats() {}

    static String cached(Cms.Site site) {
        if (site == null) return "";
        String memory = CACHE.get(site.key);
        if (memory != null) return memory;
        try {
            MainActivity a = MainActivity.activeActivity;
            if (a != null) {
                String stored = a.prefs.getString("filter_format_" + site.key, "");
                if (!stored.isEmpty()) {
                    CACHE.put(site.key, stored);
                    return stored;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    static void cache(Cms.Site site, String format) {
        if (site == null || format == null || format.isEmpty()) return;
        CACHE.put(site.key, format);
        try {
            MainActivity a = MainActivity.activeActivity;
            if (a != null)
                a.prefs.edit().putString("filter_format_" + site.key, format).apply();
        } catch (Throwable ignored) {
        }
    }

    static String[] attemptOrder(Cms.Site site) {
        String cached = cached(site);
        if (A.equals(cached)) return new String[] {A, B};
        if (B.equals(cached)) return new String[] {B, A};
        if (C.equals(cached)) return new String[] {C, A};
        return new String[] {A, B, C};
    }

    /** 在基础 URL 上按格式追加筛选参数（返回完整 URL）。 */
    static String build(Cms.Site site, String baseUrl, Map<String, String> filters, String format)
            throws Exception {
        String url = baseUrl;
        if (filters == null || filters.isEmpty()) return url;
        if (B.equals(format)) {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> e : filters.entrySet()) json.put(e.getKey(), e.getValue());
            url = Cms.query(url, "filters", json.toString());
            return url;
        }
        for (Map.Entry<String, String> e : filters.entrySet())
            if (VideoFilters.requestKeyAllowed(e.getKey()))
                url = Cms.query(url, e.getKey(), e.getValue());
        if (C.equals(format)) {
            String first = filters.keySet().iterator().next();
            url = Cms.query(url, "by", first);
        }
        return url;
    }
}
