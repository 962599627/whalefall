package com.pilipala.marcomovie;

import com.pilipala.catpaw.CatPawHost;
import java.net.URLEncoder;
import java.util.Map;
import org.json.JSONObject;

final class CatPawEngine implements SourceEngine {
    private final CatPawHost host;
    private final String api;

    CatPawEngine(MainActivity activity, Cms.Site site) {
        host = new CatPawHost(activity);
        api = site.api;
    }

    private String query(String... pairs) throws Exception {
        StringBuilder address = new StringBuilder(api);
        for (int i = 0; i < pairs.length; i += 2) {
            address.append(address.indexOf("?") < 0 ? '?' : '&');
            address.append(URLEncoder.encode(pairs[i], "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return host.read(address.toString());
    }

    public String home() throws Exception {
        return query();
    }

    public String homeVideos() throws Exception {
        return query();
    }

    public String category(String id, int page) throws Exception {
        return query("ac", "detail", "t", id, "pg", "" + page);
    }

    public String category(String id, int page, Map<String, String> filters) throws Exception {
        return query(
                "ac",
                "detail",
                "t",
                id,
                "pg",
                "" + page,
                "filter",
                "true",
                "ext",
                new JSONObject(filters).toString());
    }

    public String detail(String id) throws Exception {
        return query("ac", "detail", "ids", id);
    }

    public String search(String word, int page) throws Exception {
        return query("wd", word, "pg", "" + page);
    }

    public String player(String flag, String id) throws Exception {
        JSONObject result = new JSONObject(query("play", id, "flag", flag));
        String url = result.optString("url");
        if (url.startsWith("catpaw://")) result.put("url", host.resolve(url));
        return result.toString();
    }

    public boolean requiresExternalRuntime() {
        return false;
    }
}
