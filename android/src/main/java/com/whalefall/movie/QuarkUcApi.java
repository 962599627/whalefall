package com.whalefall.movie;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** 夸克/UC 原生 API（OpenList quark_uc 移植；仅保留状态验证与容量）。 */
final class QuarkUcApi {
    static final class Provider {
        final String key, name, api, pr, ua, referer, loginUrl;
        final String[] cookieNames;

        Provider(
                String key,
                String name,
                String api,
                String pr,
                String ua,
                String referer,
                String loginUrl,
                String[] cookieNames) {
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
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0 Safari/537.36",
                    "https://pan.quark.cn",
                    "https://pan.quark.cn/",
                    new String[] {"__puus", "__pus"});
    static final Provider UC =
            new Provider(
                    "uc",
                    "UC",
                    "https://pc-api.uc.cn/1/clouddrive",
                    "UCBrowser",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0 Safari/537.36",
                    "https://drive.uc.cn",
                    "https://drive.uc.cn/",
                    new String[] {"__puus", "kps", "_uc_s"});

    private QuarkUcApi() {}

    static Provider of(String key) {
        return "uc".equals(key) ? UC : QUARK;
    }

    static String cookie(MainActivity a, Provider p) {
        return a.prefs.getString("quark_uc_cookie_" + p.key, "");
    }

    static boolean hasCookie(MainActivity a, Provider p) {
        return cookie(a, p).contains("=");
    }

    static boolean isLoggedIn(MainActivity a, Provider p) {
        return hasCookie(a, p) && a.prefs.getBoolean("quark_uc_verified_" + p.key, false);
    }

    /** 1.8.26：仅代表该盘 App 内凭据是否已通过 API 验证（过期/401 时为 false）。 */
    static boolean verified(MainActivity a, Provider p) {
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
        AppLog.i("QuarkUc", "logout provider=" + p.key);
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

    interface Callback {
        void ready(Status status);
    }

    /** 异步验证（worker→UI），成功后写 verified/容量。 */
    static void verify(final MainActivity a, final Provider p, final Callback callback) {
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
                        AppLog.i("QuarkUc", "verify provider=" + p.key + " result=ok");
                    } catch (Throwable error) {
                        status = new Status(false, 0, 0, error.getMessage());
                        a.prefs.edit().putBoolean("quark_uc_verified_" + p.key, false).apply();
                        AppLog.i("QuarkUc", "verify provider=" + p.key + " error=" + status.error);
                    }
                    final Status result = status;
                    a.ui.post(() -> callback.ready(result));
                });
    }

    static final class FileItem {
        final String fid, name;
        final long size;
        final long updated;
        final boolean folder;
        final boolean video;

        FileItem(String fid, String name, long size, boolean folder) {
            this(fid, name, size, 0L, folder);
        }

        FileItem(String fid, String name, long size, long updated, boolean folder) {
            this.fid = fid == null ? "" : fid;
            this.name = name == null ? "" : name;
            this.size = size;
            this.updated = updated;
            this.folder = folder;
            this.video = !folder && isVideoName(this.name);
        }
    }

    static boolean isVideoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".avi")
                || lower.endsWith(".ts")
                || lower.endsWith(".m3u8")
                || lower.endsWith(".flv");
    }

    static java.util.List<FileItem> list(Provider p, String cookie, String dirFid) throws Exception {
        java.util.List<FileItem> out = new java.util.ArrayList<>();
        int page = 1;
        int size = 100;
        int total = Integer.MAX_VALUE;
        while (page <= 20) {
            JSONObject json =
                    request(
                            p,
                            "/file/sort",
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
            org.json.JSONArray array = data == null ? null : data.optJSONArray("list");
            if (array != null)
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    out.add(
                            new FileItem(
                                    item.optString("fid", ""),
                                    item.optString("file_name", ""),
                                    item.optLong("size", 0),
                                    item.optLong(
                                            "updated_at",
                                            item.optLong("l_updated_at", 0)),
                                    !item.optBoolean("file", true)));
                }
            JSONObject metadata = json.optJSONObject("metadata");
            if (metadata != null) total = metadata.optInt("_total", total);
            if (array == null || array.length() == 0 || page * size >= total) break;
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
                        null,
                        new JSONObject().put("fids", new org.json.JSONArray().put(fid)).toString(),
                        cookie);
        org.json.JSONArray data = json.optJSONArray("data");
        if (data == null || data.length() == 0) throw new IOException("没有下载地址");
        String url = data.optJSONObject(0).optString("download_url", "");
        if (url.isEmpty()) throw new IOException("download_url 为空");
        return url;
    }

    /** 1.9.1：跟随 302/重定向拿最终直链（播放器需要最终 URL；日志脱敏只打 host）。 */
    static String resolveUrl(Provider p, String cookie, String fid) throws Exception {
        String url = downloadUrl(p, cookie, fid);
        String host = "";
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Throwable ignored) {
        }
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Cookie", cookie == null ? "" : cookie);
                connection.setRequestProperty("Referer", p.referer);
                connection.setRequestProperty("User-Agent", p.ua);
                connection.setRequestProperty("Range", "bytes=0-0");
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                AppLog.i(
                        "QuarkPlay",
                        "url=" + host + " code=" + code + " redirect=" + (location != null));
                if (code >= 300 && code < 400 && location != null && !location.isEmpty()) {
                    url = location.startsWith("http") ? location : new URL(new URL(url), location).toString();
                    try {
                        host = android.net.Uri.parse(url).getHost();
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                if (code != 200 && code != 206)
                    throw new IOException("HTTP " + code);
                return url;
            } finally {
                connection.disconnect();
            }
        }
        AppLog.i("QuarkPlay", "redirect limit url=" + host);
        return url;
    }

    static Status member(Provider p, String cookie) throws Exception {        JSONObject json =
                request(
                        p,
                        "/member",
                        mapOf("fetch_subscribe", "false", "_ch", "home", "fetch_identity", "false"),
                        null,
                        cookie);
        JSONObject data = json.optJSONObject("data");
        return new Status(
                true,
                data == null ? 0 : data.optLong("total_capacity", 0),
                data == null ? 0 : data.optLong("use_capacity", 0),
                "");
    }

    static JSONObject request(
            Provider p,
            String pathname,
            Map<String, String> query,
            String body,
            String cookie)
            throws Exception {
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
            connection.setRequestMethod(body == null ? "GET" : "POST");
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
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            JSONObject json = new JSONObject(text);
            if (json.optInt("status", 200) >= 400 || json.optInt("code", 0) != 0)
                throw new IOException("code=" + json.optInt("code", 0));
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
                if (out.size() > 1024 * 1024) throw new IOException("响应过大");
            }
            return out.toString("UTF-8");
        }
    }

    static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
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
}
