package com.whalefall.movie;

/** Only shipped engine. An unsupported SourceEngine is never selected silently. */
public final class JsonCmsEngine implements SourceEngine {
    private final Cms.Site site;

    public JsonCmsEngine(Cms.Site site) {
        if (!site.supported()) throw new IllegalArgumentException("Unsupported source type");
        this.site = site;
    }

    private String detailAction() {
        return site.type == 0 ? "videolist" : "detail";
    }

    public String home() throws Exception {
        return Cms.fetch(Cms.query(site.api, "ac", "list"), site.headers);
    }

    public String category(String id, int page) throws Exception {
        return category(id, page, java.util.Collections.emptyMap());
    }

    public String category(String id, int page, java.util.Map<String, String> filters)
            throws Exception {
        String url = Cms.query(site.api, "ac", detailAction(), "t", id, "pg", "" + page);
        for (java.util.Map.Entry<String, String> entry : filters.entrySet()) {
            // 1.9.45：按源 schema 的 key 透传为 query；仅拦截路由/鉴权保留参数
            if (VideoFilters.requestKeyAllowed(entry.getKey()))
                url = Cms.query(url, entry.getKey(), entry.getValue());
        }
        AppLog.i(
                "Filter",
                "request url="
                        + mask(url)
                        + " keys="
                        + filters.keySet());
        String raw = Cms.fetch(url, site.headers);
        // 1.9.45：组合筛选返回空 → 回退无筛选请求
        if (!filters.isEmpty() && emptyListing(raw)) {
            String plain =
                    Cms.query(site.api, "ac", detailAction(), "t", id, "pg", "" + page);
            AppLog.i("Filter", "fallback no-filters site=" + site.key + " keys=" + filters.keySet());
            raw = Cms.fetch(plain, site.headers);
        }
        return raw;
    }

    static boolean emptyListing(String raw) {
        try {
            return Cms.listing(raw).videos.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 脱敏：去掉 query 中的 t/ids/key/token 等长值，避免日志泄露 token。 */
    static String mask(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        if (q < 0) return url;
        return url.substring(0, q + 1)
                + url.substring(q + 1)
                        .replaceAll("(?i)(token|key|ids)=([^&]{3})[^&]*", "$1=$2***");
    }

    public String detail(String id) throws Exception {
        return Cms.fetch(Cms.query(site.api, "ac", detailAction(), "ids", id), site.headers);
    }

    public String search(String keyword, int page) throws Exception {
        return Cms.fetch(
                Cms.query(site.api, "ac", detailAction(), "wd", keyword, "pg", "" + page),
                site.headers);
    }

    public boolean requiresExternalRuntime() {
        return false;
    }
}
