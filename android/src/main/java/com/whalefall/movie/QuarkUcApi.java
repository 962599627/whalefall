package com.whalefall.movie;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Quark / UC 原生网盘（按 OpenList quark_uc 驱动移植，不逆向插件、不读插件存储）。
 *
 * <p>参考：third_party/reference/openlist/quark_uc/{meta,util,types}.go
 * <ul>
 *   <li>Quark: api=https://drive.quark.cn/1/clouddrive, pr=ucpro, referer=https://pan.quark.cn</li>
 *   <li>UC: api=https://pc-api.uc.cn/1/clouddrive, pr=UCBrowser, referer=https://drive.uc.cn</li>
 *   <li>列表 /file/sort；下载直链 POST /file/download；容量 GET /member</li>
 * </ul>
 */
final class QuarkUcApi {
    static final class Provider {
        final String key, name, api, pr, ua, referer, loginUrl;
        final String[] cookieNames;

        Provider(String key, String name, String api, String pr, String ua, String referer,
                String loginUrl, String[] cookieNames) {
            this.key = key;
            this.name = name;
            this.api = api;
            this.pr = pr;
            this.ua = ua;
            this.referer = referer;
            this.loginUrl = loginUrl;
            this.cookieNames = cookieNames;
        }
    }

    static final Provider QUARK =
            new Provider(
                    "quark",
                    "夸克",
                    "https://drive.quark.cn/1/clouddrive",
                    "ucpro",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
                            + "Safari/537.36 Channel/pckk_other_ch",
                    "https://pan.quark.cn",
                    "https://pan.quark.cn/",
                    new String[] {"__puus", "__pus"});
    static final Provider UC =
            new Provider(
                    "uc",
                    "UC",
                    "https://pc-api.uc.cn/1/clouddrive",
                    "UCBrowser",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
                            + "Safari/537.36 Channel/pckk_other_ch",
                    "https://drive.uc.cn",
                    "https://drive.uc.cn/",
                    new String[] {"__puus", "kps", "_uc_s"});

    private QuarkUcApi() {}

    static Provider of(String key) {
        return "uc".equals(key) ? UC : QUARK;
    }

    // ---------------- 本地状态（key: quark_uc_*） ----------------

    static String cookie(MainActivity a, Provider p) {
        return a.prefs.getString("quark_uc_cookie_" + p.key, "");
    }

    static boolean hasCookie(MainActivity a, Provider p) {
        return cookie(a, p).contains("=");
    }

    static boolean isLoggedIn(MainActivity a, Provider p) {
        return hasCookie(a, p) && a.prefs.getBoolean("quark_uc_verified_" + p.key, false);
    }

    static long total(MainActivity a, Provider p) {
        return a.prefs.getLong("quark_uc_total_" + p.key, 0);
    }

    static long used(MainActivity a, Provider p) {
        return a.prefs.getLong("quark_uc_used_" + p.key, 0);
    }

    static void saveCookie(MainActivity a, Provider p, String value) {
        a.prefs.edit()
                .putString("quark_uc_cookie_" + p.key, value)
                .putBoolean("quark_uc_verified_" + p.key, false)
                .apply();
    }

    static void logout(MainActivity a, Provider p) {
        a.prefs.edit()
                .remove("quark_uc_cookie_" + p.key)
                .remove("quark_uc_verified_" + p.key)
                .remove("quark_uc_total_" + p.key)
                .remove("quark_uc_used_" + p.key)
                .apply();
        AppLog.i("QuarkUc", "logout provider=" + p.key + " cookie=false verified=false");
    }

    static final class Status {
        final boolean logged;
        final long total, used;
        final String error;

        Status(boolean logged, long total, long used, String error) {
            this.logged = logged;
            this.total = total;
            this.used = used;
            this.error = error == null ? "" : error;
        }
    }

    static final class FileItem {
        final String fid, name;
        final long size;
        final boolean folder;
        final boolean video;

        FileItem(String fid, String name, long size, boolean folder) {
            this.fid = fid == null ? "" : fid;
            this.name = name == null ? "" : name;
            this.size = size;
            this.folder = folder;
            this.video = !folder && isVideoName(this.name);
        }
    }

    static boolean isVideoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".ts")
                || lower.endsWith(".m3u8") || lower.endsWith(".flv") || lower.endsWith(".rmvb");
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824f);
        if (bytes >= 1024L * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576f);
        if (bytes >= 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024f);
        return bytes + " B";
    }

    // ---------------- API ----------------

    static JSONObject request(
            Provider p, String pathname, String method, Map<String, String> query,
            String body, String cookie) throws Exception {
        StringBuilder url = new StringBuilder(p.api).append(pathname);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pr", p.pr);
        params.put("fr", "pc");
        if (query != null) params.putAll(query);
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(first ? '?' : '&');
            first = false;
            url.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Cookie", cookie == null ? "" : cookie);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("Referer", p.referer);
            connection.setRequestProperty("User-Agent", p.ua);
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream input =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();
            String text = read(input);
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " " + text);
            JSONObject json = new JSONObject(text);
            int respStatus = json.optInt("status", 200);
            int code = json.optInt("code", 0);
            if (respStatus >= 400 || code != 0)
                throw new IOException(
                        "code=" + code + (json.has("message") ? " " + json.optString("message") : ""));
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("响应过大");
            }
            return out.toString("UTF-8");
        }
    }

    static Status member(Provider p, String cookie) throws Exception {
        JSONObject json =
                request(
                        p,
                        "/member",
                        "GET",
                        mapOf("fetch_subscribe", "false", "_ch", "home", "fetch_identity", "false"),
                        null,
                        cookie);
        JSONObject data = json.optJSONObject("data");
        long total = data == null ? 0 : data.optLong("total_capacity", 0);
        long used = data == null ? 0 : data.optLong("use_capacity", 0);
        return new Status(true, total, used, "");
    }

    static List<FileItem> list(Provider p, String cookie, String dirFid) throws Exception {
        List<FileItem> out = new ArrayList<>();
        int page = 1;
        int size = 100;
        int total = Integer.MAX_VALUE;
        while (page <= 20) {
            JSONObject json =
                    request(
                            p,
                            "/file/sort",
                            "GET",
                            mapOf(
                                    "pdir_fid", dirFid,
                                    "_page", String.valueOf(page),
                                    "_size", String.valueOf(size),
                                    "_fetch_total", "1",
                                    "fetch_all_file", "1",
                                    "fetch_risk_file_name", "1",
                                    "_sort", "file_type:asc,updated_at:desc"),
                            null,
                            cookie);
            JSONObject data = json.optJSONObject("data");
            JSONArray list = data == null ? null : data.optJSONArray("list");
            if (list != null)
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    boolean folder = !item.optBoolean("file", true);
                    out.add(
                            new FileItem(
                                    item.optString("fid", ""),
                                    item.optString("file_name", ""),
                                    item.optLong("size", 0),
                                    folder));
                }
            JSONObject metadata = json.optJSONObject("metadata");
            if (metadata != null) total = metadata.optInt("_total", total);
            if (list == null || list.length() == 0 || page * size >= total) break;
            page++;
            if (out.size() >= 300) break;
        }
        return out;
    }

    static String downloadUrl(Provider p, String cookie, String fid) throws Exception {
        JSONObject json =
                request(
                        p,
                        "/file/download",
                        "POST",
                        null,
                        new JSONObject().put("fids", new JSONArray().put(fid)).toString(),
                        cookie);
        JSONArray data = json.optJSONArray("data");
        if (data == null || data.length() == 0) throw new IOException("没有下载地址");
        String url = data.optJSONObject(0).optString("download_url", "");
        if (url.isEmpty()) throw new IOException("download_url 为空");
        return url;
    }

    static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    // ---------------- 面板/交互 ----------------

    static void showPanel(final MainActivity a, final Provider p, final Runnable onChanged) {
        if (!hasCookie(a, p)) {
            PanCookieLogin.show(a, p, onChanged);
            return;
        }
        verifyAsync(
                a,
                p,
                status -> {
                    if (!status.logged) {
                        new AppDialog.Builder(a)
                                .setTitle(p.name + "登录验证失败")
                                .setMessage("登录信息已保存，但验证失败：" + status.error + "\n\n可重试或重新登录。")
                                .setPositiveButton(
                                        "重新登录", (d, i) -> PanCookieLogin.show(a, p, onChanged))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    menu(a, p, status, onChanged);
                });
    }

    static void verifyAsync(
            final MainActivity a, final Provider p, final QuarkUcCallback callback) {
        final String cookie = cookie(a, p);
        a.worker.execute(
                () -> {
                    Status status;
                    try {
                        if (cookie.isEmpty()) throw new IOException("没有保存的 Cookie");
                        status = member(p, cookie);
                        a.prefs.edit()
                                .putBoolean("quark_uc_verified_" + p.key, true)
                                .putLong("quark_uc_total_" + p.key, status.total)
                                .putLong("quark_uc_used_" + p.key, status.used)
                                .apply();
                        AppLog.i(
                                "QuarkUc",
                                "verify provider="
                                        + p.key
                                        + " result=ok capacity="
                                        + (status.total > 0));
                    } catch (Throwable error) {
                        status = new Status(false, 0, 0, friendly(error));
                        a.prefs.edit().putBoolean("quark_uc_verified_" + p.key, false).apply();
                        AppLog.i("QuarkUc", "verify provider=" + p.key + " error=" + status.error);
                    }
                    final Status result = status;
                    a.ui.post(() -> callback.ready(result));
                });
    }

    interface QuarkUcCallback {
        void ready(Status status);
    }

    static void menu(
            final MainActivity a, final Provider p, final Status status, final Runnable onChanged) {
        StringBuilder message = new StringBuilder("真实登录态：已登录（" + p.name + " 原生 API 验证通过）");
        if (status.total > 0)
            message.append("\n容量：")
                    .append(formatSize(status.used))
                    .append(" / ")
                    .append(formatSize(status.total));
        new AppDialog.Builder(a)
                .setTitle("我的" + p.name + "网盘")
                .setMessage(message.toString())
                .setItems(
                        new String[] {"查看我的文件", "重新验证登录状态", "退出登录"},
                        (d, index) -> {
                            if (index == 0) QuarkUcFiles.show(a, p);
                            else if (index == 1)
                                verifyAsync(
                                        a,
                                        p,
                                        next -> {
                                            a.toast(next.logged ? "验证通过" : "验证失败：" + next.error);
                                            if (onChanged != null) onChanged.run();
                                        });
                            else logout(a, p, onChanged);
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    static void logout(final MainActivity a, final Provider p, final Runnable onChanged) {
        new AppDialog.Builder(a)
                .setTitle("退出" + p.name + "登录？")
                .setMessage("只清除本 App 保存的 Cookie 与容量缓存。")
                .setPositiveButton(
                        "退出登录",
                        (d, i) -> {
                            logout(a, p);
                            a.toast("已退出" + p.name + "登录");
                            if (onChanged != null) onChanged.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : "：" + message);
    }
}
