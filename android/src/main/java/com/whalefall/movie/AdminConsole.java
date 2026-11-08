package com.whalefall.movie;

import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/**
 * 「管理员后台」本地网页服务：把配置、收藏、历史、电视台频道和常用设置暴露成一个
 * 局域网后台，网页前端是内置的 Vue 单页（assets/admin），不需要外网。
 *
 * <p>只监听本机局域网；打开「管理员后台」时启动，关闭时停止。
 */
final class AdminConsole implements Closeable {
    /** 每次打开后台生成的一次性访问令牌：URL 里带 ?t=…，无令牌一律 403。 */
    final String token;
    private final MainActivity a;
    private final ServerSocket socket;
    private volatile boolean closed;
    private final ThreadPoolExecutor pool =
            new ThreadPoolExecutor(
                    0,
                    4,
                    15,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "whalefall-admin");
                        t.setDaemon(true);
                        return t;
                    });

    AdminConsole(MainActivity host) throws IOException {
        a = host;
        byte[] seed = new byte[24];
        new java.security.SecureRandom().nextBytes(seed);
        StringBuilder sb = new StringBuilder();
        for (byte b : seed) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        token = sb.toString();
        socket = new ServerSocket();
        socket.bind(new InetSocketAddress("0.0.0.0", 0));
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket client = socket.accept();
                                    try {
                                        pool.execute(() -> serve(client));
                                    } catch (RejectedExecutionException full) {
                                        client.close();
                                    }
                                } catch (IOException error) {
                                    if (!closed) close();
                                }
                        },
                        "whalefall-admin-listener");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    static String lanIp() {
        try {
            return PhoneConfigDialog.lanAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    String url() {
        return "http://" + lanIp() + ":" + port() + "/?t=" + token;
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ---------------- HTTP ----------------

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(12000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int c, state = 0;
            while ((c = in.read()) != -1) {
                head.write(c);
                if (c == '\n') {
                    state++;
                    if (state >= 2) break;
                } else if (c != '\r') state = 0;
                if (head.size() > 16384) break;
            }
            String[] lines = head.toString("UTF-8").split("\r?\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) return;
            String method = requestLine[0];
            String target = requestLine[1];
            String path = target.split("\\?", 2)[0];
            final String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
            final String hostHeader = header(lines, "host");
            final String originHeader = header(lines, "origin");
            int length = 0;
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length"))
                    try {
                        length = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
            }
            String body = "";
            if (length > 0 && length < 2 * 1024 * 1024) {
                byte[] buf = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(buf, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            final java.net.InetAddress remote = s.getInetAddress();
            boolean localPeer =
                    remote != null
                            && (remote.isLoopbackAddress() || remote.isSiteLocalAddress());
            if (!localPeer) {
                send(s.getOutputStream(), "text/plain; charset=utf-8", "403 only LAN allowed");
                return;
            }
            if (!closed) route(s.getOutputStream(), method, path, query, originHeader, body);
        } catch (Exception ignored) {
        }
    }

    private void route(
            OutputStream out, String method, String path, String query, String origin, String body)
            throws IOException {
        if (!tokenMatches(query, origin)) {
            send(out, "text/plain; charset=utf-8", "403 invalid token");
            return;
        }
        if (path.equals("/") || path.equals("/index.html")) {
            String html = new String(asset("admin/index.html"), StandardCharsets.UTF_8);
            html = html.replace("__ADMIN_TOKEN__", token);
            send(out, "text/html; charset=utf-8", html);
            return;
        }
        if (path.equals("/vue.global.prod.js") || path.equals("/assets/vue.global.prod.js")) {
            send(out, "application/javascript; charset=utf-8", asset("admin/vue.global.prod.js"));
            return;
        }
        if (path.startsWith("/api/files/text")) {
            String name = "";
            int q = path.indexOf("name=");
            if (q >= 0) name = URLDecoder.decode(path.substring(q + 5), "UTF-8");
            try {
                send(out, "text/plain; charset=utf-8", PushFiles.readText(a, name));
            } catch (Exception e) {
                send(out, "text/plain; charset=utf-8", "读取失败：" + e.getMessage());
            }
            return;
        }
        if (path.startsWith("/api/")) {
            try {
                JSONObject payload = body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                JSONObject result = api(path, payload);
                send(out, "application/json; charset=utf-8", result.toString());
            } catch (Exception e) {
                JSONObject error = new JSONObject();
                try {
                    error.put("ok", false);
                    error.put("error", String.valueOf(e.getMessage()));
                } catch (JSONException ignored) {
                }
                send(out, "application/json; charset=utf-8", error.toString());
            }
            return;
        }
        send(out, "text/plain; charset=utf-8", "not found");
    }

    private boolean tokenMatches(String query, String origin) {
        if (origin != null && !origin.isEmpty()) {
            String expectedPort = ":" + port();
            if (!origin.endsWith(expectedPort)) return false;
        }
        String supplied = null;
        if (query != null && !query.isEmpty())
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? part : part.substring(0, eq);
                if (key.equals("t")) {
                    supplied = eq < 0 ? "" : part.substring(eq + 1);
                    break;
                }
            }
        return supplied != null && supplied.equals(token);
    }

    private static String header(String[] lines, String name) {
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name))
                return line.substring(colon + 1).trim();
        }
        return "";
    }

    private JSONObject api(String path, JSONObject body) throws Exception {
        switch (path) {
            case "/api/state":
                return state();
            case "/api/settings":
                settings(body.getString("key"), body.opt("value"));
                break;
            case "/api/site/select":
                String key = body.getString("key");
                a.prefs.edit().putString(Prefs.SELECTED_SITE, key).apply();
                refreshUi();
                break;
            case "/api/config":
                importConfig(body.getString("text"));
                break;
            case "/api/favorites/remove":
                removeFavorite(body.getString("site"), body.getString("id"));
                break;
            case "/api/history/remove":
                a.prefs
                        .edit()
                        .putString(
                                Prefs.WATCH_HISTORY,
                                WatchHistory.remove(
                                        a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                        body.getString("site") + "\n" + body.getString("id")))
                        .apply();
                refreshUi();
                break;
            case "/api/tv/add":
                addChannel(body.getString("name"), body.getString("url"));
                break;
            case "/api/tv/remove":
                removeChannel(body.getString("name"), body.getString("url"));
                break;
            case "/api/files":
                return filesState();
            case "/api/files/upload":
                uploadFile(body.getString("name"), body.getString("base64"));
                break;
            case "/api/files/delete":
                PushFiles.delete(a, body.getString("name"));
                break;
            case "/api/play":
                PushFiles.playUrl(a, body.getString("url"), body.optString("title", ""));
                break;
            case "/api/queue":
                return queueState();
            case "/api/queue/add":
                QueueStore.add(a, body.getString("url"), body.optString("title", ""));
                refreshUi();
                break;
            case "/api/queue/remove":
                QueueStore.remove(a, body.getString("url"));
                break;
            case "/api/queue/clear":
                QueueStore.clear(a);
                break;
            case "/api/queue/next":
                a.ui.post(() -> QueueStore.playNext(a));
                break;
            default:
                throw new IllegalArgumentException("未知接口");
        }
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        ok.put("state", state());
        return ok;
    }

    // ---------------- 数据 ----------------

    private JSONObject state() throws Exception {
        JSONObject s = new JSONObject();
        s.put("ok", true);
        s.put("version", versionName());
        s.put("package", a.getPackageName());
        s.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        s.put("ip", lanIp());
        s.put("port", port());

        JSONArray sites = new JSONArray();
        String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        for (Cms.Site site : a.config.sites) {
            JSONObject o = new JSONObject();
            o.put("key", site.key);
            o.put("name", site.name);
            o.put("api", site.api);
            sites.put(o);
        }
        s.put("sites", sites);
        s.put("selectedSite", selected);
        s.put("origin", a.configOrigin == null ? "" : a.configOrigin);

        JSONArray favorites = new JSONArray();
        for (Favorites.Entry e :
                Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"))) {
            JSONObject o = new JSONObject();
            o.put("site", e.site);
            o.put("id", e.id);
            o.put("title", e.title);
            o.put("poster", e.poster == null ? "" : e.poster);
            favorites.put(o);
        }
        s.put("favorites", favorites);

        JSONArray history = new JSONArray();
        for (WatchHistory.Entry e :
                WatchHistory.read(a.prefs.getString(Prefs.WATCH_HISTORY, "[]"))) {
            JSONObject o = new JSONObject();
            o.put("site", e.site);
            o.put("id", e.id);
            o.put("title", e.title);
            o.put("episode", e.episode == null ? "" : e.episode);
            o.put("position", e.position);
            o.put("duration", e.duration);
            o.put("updated", e.updated);
            history.put(o);
        }
        s.put("history", history);

        JSONObject tv = new JSONObject();
        tv.put("total", TvLive.CCTV.length + TvLive.SAT.length + TvLive.readCustom(a).size());
        tv.put("builtinSat", TvLive.SAT.length);
        JSONArray custom = new JSONArray();
        for (TvLive.Channel ch : TvLive.readCustom(a)) {
            JSONObject o = new JSONObject();
            o.put("name", ch.name);
            o.put("url", ch.url);
            custom.put(o);
        }
        tv.put("custom", custom);
        s.put("tv", tv);

        JSONObject prefs = new JSONObject();
        prefs.put("wallpaper_sound", a.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false));
        prefs.put("wallpaper_enabled", a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false));
        prefs.put("reduced", a.prefs.getBoolean(Prefs.REDUCED, false));
        prefs.put("autoplay", a.prefs.getBoolean(Prefs.AUTO_NEXT, true));
        prefs.put("tv_aspect", a.prefs.getInt(Prefs.TV_ASPECT, 0));
        prefs.put("tv_fill", a.prefs.getInt(Prefs.TV_FILL, 0));
        s.put("prefs", prefs);
        return s;
    }

    private void settings(String key, Object raw) {
        switch (key) {
            case "wallpaper_sound":
                a.prefs.edit().putBoolean(Prefs.WALLPAPER_SOUND, bool(raw)).apply();
                break;
            case "wallpaper_enabled":
                a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, bool(raw)).apply();
                break;
            case "reduced":
                a.prefs.edit().putBoolean(Prefs.REDUCED, bool(raw)).apply();
                break;
            case "autoplay":
                a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, bool(raw)).apply();
                break;
            case "tv_aspect":
                a.prefs.edit().putInt(Prefs.TV_ASPECT, clamp(raw, 0, 2)).apply();
                break;
            case "tv_fill":
                a.prefs.edit().putInt(Prefs.TV_FILL, clamp(raw, 0, 2)).apply();
                break;
            default:
                throw new IllegalArgumentException("不支持的设置项：" + key);
        }
        refreshUi();
    }

    private void importConfig(String text) throws Exception {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("配置为空");
        String raw;
        String origin;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            origin = trimmed;
            raw = SourceController.fetchImport(a, trimmed);
        } else {
            origin = "管理员后台";
            raw = trimmed;
        }
        final Cms.Config config = Cms.parseConfig(Cms.read(new ByteArrayInputStream(
                raw.getBytes(StandardCharsets.UTF_8)), Cms.MAX_JSON), origin);
        final String finalOrigin = origin;
        a.ui.post(
                () -> {
                    try {
                        a.saveConfig(config, finalOrigin);
                    } catch (Exception e) {
                        a.toast("导入失败：" + e.getMessage());
                    }
                });
    }

    private void removeFavorite(String site, String id) {
        String raw = a.prefs.getString(Prefs.FAVORITES, "[]");
        if (!Favorites.contains(raw, site, id)) return;
        Favorites.Entry found = null;
        for (Favorites.Entry e : Favorites.read(raw))
            if (e.site.equals(site) && e.id.equals(id)) found = e;
        if (found == null) return;
        a.prefs.edit().putString(Prefs.FAVORITES, Favorites.toggle(raw, site, found.video())).apply();
        refreshUi();
    }

    private void addChannel(String name, String url) {
        if (!Cms.isHttp(url)) throw new IllegalArgumentException("地址必须是 http/https");
        List<TvLive.Channel> custom = new ArrayList<>(TvLive.readCustom(a));
        custom.add(new TvLive.Channel(name, url, TvLive.GROUP_SAT));
        TvLive.saveCustom(a, custom);
        refreshUi();
    }

    private void removeChannel(String name, String url) {
        List<TvLive.Channel> custom = new ArrayList<>(TvLive.readCustom(a));
        for (int i = custom.size() - 1; i >= 0; i--) { TvLive.Channel c = custom.get(i); if (c.name.equals(name) && c.url.equals(url)) custom.remove(i); }
        TvLive.saveCustom(a, custom);
        refreshUi();
    }

    private void refreshUi() {
        a.ui.post(
                () -> {
                    try {
                        a.refresh();
                    } catch (Throwable ignored) {
                    }
                });
    }

    /** 文件列表（管理后台上传区）。 */
    private JSONObject filesState() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        JSONArray files = new JSONArray();
        for (File f : PushFiles.list(a)) {
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("size", f.length());
            o.put("sizeLabel", PushFiles.sizeLabel(f.length()));
            o.put("time", f.lastModified());
            o.put("markdown", PushFiles.isMarkdown(f.getName()));
            o.put("video", PushFiles.isVideo(f.getName()));
            files.put(o);
        }
        out.put("files", files);
        return out;
    }

    private void uploadFile(String name, String base64) throws Exception {
        if (base64 == null || base64.isEmpty())
            throw new IllegalArgumentException("文件内容为空");
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma > 0) base64 = base64.substring(comma + 1);
        byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        PushFiles.save(a, name, bytes);
        refreshUi();
    }

    private JSONObject queueState() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        JSONArray arr = new JSONArray();
        for (QueueStore.Item item : QueueStore.list(a)) {
            JSONObject o = new JSONObject();
            o.put("url", item.url);
            o.put("title", item.title);
            arr.put(o);
        }
        out.put("queue", arr);
        return out;
    }

    private String versionName() {
        try {
            PackageInfo info = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean bool(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int clamp(Object raw, int min, int max) {
        int v = raw instanceof Number ? ((Number) raw).intValue() : 0;
        return Math.max(min, Math.min(max, v));
    }

    // ---------------- 静态资源 ----------------

    private byte[] asset(String name) throws IOException {
        try (InputStream in = a.getAssets().open(name)) {
            return Cms.read(in, 2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void send(OutputStream out, String type, String body) throws IOException {
        send(out, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, String type, byte[] body) throws IOException {
        String head =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: "
                        + type
                        + "\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Content-Length: "
                        + body.length
                        + "\r\n"
                        + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
