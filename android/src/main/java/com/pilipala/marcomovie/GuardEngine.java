package com.pilipala.marcomovie;

import android.content.Context;
import com.pilipala.catpaw.CatPawHost;
import com.pilipala.catpaw.NodeRuntime;
import java.io.*;
import java.net.HttpURLConnection;
import org.json.JSONObject;

/** Reviewed data-only plugin adapters; unknown Android plugins retain their original host. */
final class GuardEngine {
    interface Fallback {
        SourceEngine open() throws Exception;
    }

    static boolean eligible(Cms.Site site) {
        return site.type == 3 && ("csp_Dm84Guard".equals(site.api) || "csp_Dm84".equals(site.api));
    }

    static SourceEngine open(Context context, Cms.Config config, Cms.Site site, Fallback fallback)
            throws Exception {
        if (!eligible(site)) throw new IOException("此插件没有已审阅的移植适配器");
        String declaration =
                site.jar != null && !site.jar.trim().isEmpty() ? site.jar : config.spider;
        // All acquisition, declared digest and ZIP checks occur before any permissible fallback.
        File artifact = CspEngine.obtain(context, declaration);
        byte[] data;
        try (InputStream input = new FileInputStream(artifact);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (output.size() + count > 16 * 1024 * 1024)
                    throw new IOException("移植插件包超过 16 MB 限制");
                output.write(buffer, 0, count);
            }
            data = output.toByteArray();
        }
        JSONObject result;
        try {
            result =
                    new CatPawHost(context)
                            .bootstrapGuard(
                                    data,
                                    new CatPawHost.ConnectionObserver() {
                                        public void opened(HttpURLConnection connection)
                                                throws IOException {
                                            RequestScope.attach(connection);
                                        }

                                        public void closed(HttpURLConnection connection) {
                                            RequestScope.detach(connection);
                                        }
                                    });
        } catch (CatPawHost.UnsupportedGuardArtifactException
                | NodeRuntime.RuntimeUnavailableException unavailable) {
            return fallback.open();
        }
        return new DrpyEngine(context, config, portableSite(config, site, result));
    }

    static Cms.Site portableSite(Cms.Config config, Cms.Site source, JSONObject result)
            throws Exception {
        String rule = result.getString("rule");
        if (!Cms.looksLikeDrpyRule(rule)) throw new IOException("插件移植服务未返回独立 DRPY 规则");
        Cms.Site site = new Cms.Site();
        site.key = source.key;
        site.name = source.name;
        site.api = "csp_Drpy";
        site.type = 3;
        site.playUrl = source.playUrl;
        site.searchable = source.searchable;
        site.headers.putAll(
                Cms.inheritedHeaders(config.origin, result.getString("origin"), source.headers));
        site.ext = new JSONObject().put("rule", rule).put("origin", result.getString("origin"));
        return site;
    }
}
