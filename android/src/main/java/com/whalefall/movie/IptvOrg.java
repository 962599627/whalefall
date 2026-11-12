package com.whalefall.movie;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 1.9.150：开源 IPTV（iptv-org）M3U 源。
 *
 * <p>后台下载 cn.m3u → 手写解析 → 缓存 m3u/json，离线可读；不依赖 WebView/X5。
 */
final class IptvOrg {
  private IptvOrg() {}

  static final String URL_CN = "https://iptv-org.github.io/iptv/countries/cn.m3u";
  static final String URL_CN_HTTP = "http://iptv-org.github.io/iptv/countries/cn.m3u";
  static final String URL_ZHO_HTTP = "http://iptv-org.github.io/iptv/languages/zho.m3u";
  static final String URL_ZHO = "https://iptv-org.github.io/iptv/languages/zho.m3u";
  static final String CACHE_M3U = "iptv-org-cn.m3u";
  static final String CACHE_JSON = "iptv-org-channels.json";

  interface Callback {
    void done(List<TvLive.Channel> channels, boolean fromCache, String error);
  }

  static File cacheM3u(MainActivity a) {
    return new File(a.getFilesDir(), CACHE_M3U);
  }

  static File cacheJson(MainActivity a) {
    return new File(a.getFilesDir(), CACHE_JSON);
  }

  /** 读取本地缓存（m3u 优先，其次 json）；无缓存返回空列表。 */
  static List<TvLive.Channel> cached(MainActivity a) {
    try {
      File m3u = cacheM3u(a);
      if (m3u.isFile()) {
        List<TvLive.Channel> list = parse(readText(m3u));
        if (!list.isEmpty()) return list;
      }
    } catch (Throwable ignored) {
    }
    try {
      File json = cacheJson(a);
      if (json.isFile()) return parseJson(readText(json));
    } catch (Throwable ignored) {
    }
    return new ArrayList<>();
  }

  /** 后台下载并解析；失败回退缓存。完成后回主线程。 */
  static void fetch(final MainActivity a, final Callback callback) {
    if (a == null) return;
    a.worker.execute(
        () -> {
          List<TvLive.Channel> channels = null;
          String error = null;
          boolean fromCache = false;
          String usedUrl = "";
          try {
            Download download = downloadWithFallback();
            usedUrl = download.url;
            channels = parse(download.text);
            if (channels.isEmpty()) throw new IOException("播放列表为空");
            writeText(cacheM3u(a), download.text);
            writeText(cacheJson(a), toJson(channels));
          } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            channels = cached(a);
            fromCache = !channels.isEmpty();
          }
          final List<TvLive.Channel> result = channels == null ? new ArrayList<>() : channels;
          final boolean cachedResult = fromCache;
          final String errorResult = error;
          final String finalUrl = usedUrl;
          AppLog.i(
              "IptvOrg",
              "fetch url="
                  + (finalUrl.isEmpty() ? URL_CN : finalUrl)
                  + " channels="
                  + result.size()
                  + " try="
                  + (finalUrl.startsWith("http://") ? "http" : "https")
                  + " fromCache="
                  + cachedResult
                  + (errorResult == null ? "" : " error=" + errorResult));
          if (a.ui == null) return;
          a.ui.post(() -> callback.done(result, cachedResult, errorResult));
        });
  }

  /** 清缓存后重新下载（源页「刷新开源»按钮）。 */
  static void refresh(final MainActivity a, final Callback callback) {
    cacheM3u(a).delete();
    cacheJson(a).delete();
    fetch(a, callback);
  }

  // ---------------- HTTP / 缓存 ----------------

  static final class Download {
    final String url;
    final String text;

    Download(String url, String text) {
      this.url = url;
      this.text = text;
    }
  }

  /** 1.9.151：https 证书链不认（老电视）时降级 http，再失败换中文语言列表。 */
  private static Download downloadWithFallback() throws Exception {
    Exception first;
    try {
      String text = httpGet(URL_CN);
      AppLog.i("IptvOrg", "try=" + URL_CN + " result=ok");
      return new Download(URL_CN, text);
    } catch (Exception error) {
      first = error;
      AppLog.i(
          "IptvOrg",
          "try=" + URL_CN + " result=fail reason=" + error.getClass().getSimpleName());
    }
    // 仅在 SSL/证书异常时降级到 HTTP（公开列表，不涉及用户数据）
    if (!isSslRelated(first)) throw first;
    try {
      String text = httpGet(URL_CN_HTTP);
      AppLog.i("IptvOrg", "try=" + URL_CN_HTTP + " result=ok");
      return new Download(URL_CN_HTTP, text);
    } catch (Exception error) {
      AppLog.i(
          "IptvOrg",
          "try=" + URL_CN_HTTP + " result=fail reason=" + error.getClass().getSimpleName());
    }
    try {
      String text = httpGet(URL_ZHO_HTTP);
      AppLog.i("IptvOrg", "try=" + URL_ZHO_HTTP + " result=ok");
      return new Download(URL_ZHO_HTTP, text);
    } catch (Exception error) {
      AppLog.i(
          "IptvOrg",
          "try=" + URL_ZHO_HTTP + " result=fail reason=" + error.getClass().getSimpleName());
      error.addSuppressed(first);
      throw error;
    }
  }

  private static boolean isSslRelated(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof javax.net.ssl.SSLHandshakeException
          || t instanceof java.security.cert.CertificateException) return true;
    }
    return false;
  }

  private static String httpGet(String address) throws IOException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(address).openConnection();
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", "WhaleFall/1.9.150");
      int code = connection.getResponseCode();
      if (code != 200) throw new IOException("HTTP " + code);
      try (InputStream in = connection.getInputStream()) {
        return readStream(in);
      }
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static String readStream(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    return out.toString("UTF-8");
  }

  private static String readText(File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      return readStream(in);
    }
  }

  private static void writeText(File file, String text) throws IOException {
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  // ---------------- M3U 解析（手写，无新依赖） ----------------

  static List<TvLive.Channel> parse(String text) {
    List<TvLive.Channel> out = new ArrayList<>();
    if (text == null || text.isEmpty()) return out;
    String[] lines = text.split("\\r?\\n");
    String pendingName = null;
    String pendingGroup = "";
    for (String raw : lines) {
      String line = raw == null ? "" : raw.trim();
      if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;
      if (line.startsWith("#EXTINF")) {
        pendingName = attr(line, "tvg-name");
        pendingGroup = attr(line, "group-title");
        if (pendingName == null || pendingName.trim().isEmpty()) {
          int comma = line.lastIndexOf(',');
          pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "未命名频道";
        }
        continue;
      }
      if (line.startsWith("#")) continue;
      if (pendingName == null) continue;
      String name = pendingName.trim();
      if (name.isEmpty() || line.isEmpty()) {
        pendingName = null;
        continue;
      }
      String category = category(pendingGroup, name);
      String display = category.isEmpty() ? name : category + " · " + name;
      out.add(new TvLive.Channel(display, line, TvLive.GROUP_IPTV));
      pendingName = null;
      pendingGroup = "";
      if (out.size() >= 3000) break;
    }
    return out;
  }

  private static String attr(String line, String key) {
    String token = key + "=\"";
    int start = line.indexOf(token);
    if (start < 0) return null;
    start += token.length();
    int end = line.indexOf('"', start);
    return end > start ? line.substring(start, end) : null;
  }

  /** group-title → 央视 / 卫视 / 地方 / 其他。 */
  private static String category(String group, String name) {
    String g = (group == null ? "" : group).toLowerCase();
    String n = name == null ? "" : name.toLowerCase();
    if (g.contains("cctv") || g.contains("央视") || n.contains("cctv")) return "央视";
    if (g.contains("卫视") || n.contains("卫视")) return "卫视";
    if (g.contains("news")
        || g.contains("sports")
        || g.contains("movie")
        || g.contains("music")
        || g.contains("kids")
        || g.contains("documentary")
        || g.contains("education")
        || g.contains("entertainment")
        || g.contains("general")) return "地方";
    return "其他";
  }

  private static String toJson(List<TvLive.Channel> channels) {
    JSONArray array = new JSONArray();
    for (TvLive.Channel channel : channels) {
      try {
        JSONObject item = new JSONObject();
        item.put("name", channel.name);
        item.put("url", channel.url);
        array.put(item);
      } catch (Throwable ignored) {
      }
    }
    return array.toString();
  }

  private static List<TvLive.Channel> parseJson(String text) {
    List<TvLive.Channel> out = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(text);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) continue;
        String name = item.optString("name");
        String url = item.optString("url");
        if (name.isEmpty() || url.isEmpty()) continue;
        out.add(new TvLive.Channel(name, url, TvLive.GROUP_IPTV));
      }
    } catch (Throwable ignored) {
    }
    return out;
  }
}
