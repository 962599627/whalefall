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
        final String shareFidToken;

        FileItem(String fid, String name, long size, boolean folder) {
            this(fid, name, size, 0L, folder, "");
        }

        FileItem(String fid, String name, long size, long updated, boolean folder) {
            this(fid, name, size, updated, folder, "");
        }

        FileItem(
                String fid,
                String name,
                long size,
                long updated,
                boolean folder,
                String shareFidToken) {
            this.fid = fid == null ? "" : fid;
            this.name = name == null ? "" : name;
            this.size = size;
            this.updated = updated;
            this.folder = folder;
            this.video = !folder && isVideoName(this.name);
            this.shareFidToken = shareFidToken == null ? "" : shareFidToken;
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
                                    !item.optBoolean("file", true),
                                    item.optString(
                                            "share_fid_token",
                                            item.optString("shareFidToken", ""))));
                    try {
                        AppLog.i(
                                "QuarkList",
                                "item name="
                                        + item.optString("file_name", "")
                                        + " fid="
                                        + item.optString("fid", "")
                                        + " shareFidToken="
                                        + (item.optString("share_fid_token", "").isEmpty() ? "no" : "yes"));
                    } catch (Throwable ignored) {
                    }
                }
            JSONObject metadata = json.optJSONObject("metadata");
            if (metadata != null) total = metadata.optInt("_total", total);
            if (array == null || array.length() == 0 || page * size >= total) break;
            page++;
            if (out.size() >= 300) break;
        }
        return out;
    }

    /** 1.9.4：刷新 Cookie（OpenList 同款：从 Set-Cookie 取 __puus/__pus 合并回 Cookie）。 */
    static String refreshCookie(MainActivity a, Provider p, String cookie) {
        if (cookie == null || cookie.isEmpty()) return cookie == null ? "" : cookie;
        String merged = cookie;
        boolean puus = false;
        String[] endpoints = {
            p.api + "/config?pr=" + p.pr + "&fr=pc",
            "https://pan.quark.cn/account/info?lw=scan",
            "https://drive.uc.cn/1/clouddrive/config?pr=UCBrowser&fr=pc"
        };
        for (String endpoint : endpoints) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Cookie", merged);
                connection.setRequestProperty("Referer", p.referer);
                connection.setRequestProperty("User-Agent", UA_CHROME);
                connection.setRequestProperty("Accept", "application/json, text/plain, */*");
                int status = connection.getResponseCode();
                String newCookie = merged;
                boolean found = false;
                try {
                    java.util.List<String> raw = new java.util.ArrayList<>();
                    for (java.util.Map.Entry<String, java.util.List<String>> entry :
                            connection.getHeaderFields().entrySet()) {
                        if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()))
                            raw.addAll(entry.getValue());
                    }
                    for (String value : raw) {
                        if (value == null) continue;
                        for (String part : value.split(";")) {
                            String pair = part.trim();
                            int eq = pair.indexOf('=');
                            if (eq <= 0) continue;
                            String name = pair.substring(0, eq);
                            if (!name.startsWith("__puus")
                                    && !name.startsWith("__pus")
                                    && !name.equals("kps")
                                    && !name.equals("_uc_s")
                                    && !name.equals("__kp")
                                    && !name.equals("__uid")) continue;
                            newCookie = mergeCookie(newCookie, name, pair.substring(eq + 1));
                            found = true;
                            if (name.startsWith("__puus")) puus = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                merged = newCookie;
                AppLog.i(
                        "QuarkGet",
                        "refresh status="
                                + status
                                + " puus="
                                + (puus ? "yes" : "no")
                                + " changed="
                                + found
                                + " host="
                                + android.net.Uri.parse(endpoint).getHost());
                if (found) break;
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (!merged.equals(cookie)) {
            // 只覆盖 App 内保存的 Cookie；源插件 Cookie 仅本次会话使用，不写回 App 存储
            String stored = cookie(a, p);
            if (stored != null && stored.equals(cookie)) {
                try {
                    saveCookie(a, p, merged);
                } catch (Throwable ignored) {
                }
            }
        }
        return merged;
    }

    static String mergeCookie(String cookie, String name, String value) {
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String part : (cookie == null ? "" : cookie).split(";")) {
            String pair = part.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                if (out.length() > 0) out.append("; ");
                out.append(name).append('=').append(value);
                replaced = true;
            } else {
                if (out.length() > 0) out.append("; ");
                out.append(pair);
            }
        }
        if (!replaced) {
            if (out.length() > 0) out.append("; ");
            out.append(name).append('=').append(value);
        }
        return out.toString();
    }

    static final String UA_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String UA_OKHTTP = "okhttp/3.15";
    static final String UA_QUARK_APP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0 Safari/537.36";

    /** 1.9.5：夸克/UC PC 下载接口 + __puus 刷新；400 时尝试 body/接口变体。 */
    static String downloadUrl(MainActivity a, Provider p, String cookie, String fid) throws Exception {
        return downloadUrl(a, p, cookie, fid, "");
    }

    static String downloadUrl(
            MainActivity a, Provider p, String cookie, String fid, String shareFidToken)
            throws Exception {
        String fresh = cookie;
        if (a != null) fresh = refreshCookie(a, p, cookie);
        String[] endpoints =
                "quark".equals(p.key)
                        ? new String[] {
                            "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc",
                            "https://drive.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc"
                        }
                        : new String[] {
                            "https://pc-api.uc.cn/1/clouddrive/file/download?pr=UCBrowser&fr=pc",
                            "https://drive.uc.cn/1/clouddrive/file/download?pr=UCBrowser&fr=pc"
                        };
        java.util.List<String> bodies = new java.util.ArrayList<>();
        bodies.add(new JSONObject().put("fids", new org.json.JSONArray().put(fid)).toString());
        bodies.add(
                new JSONObject()
                        .put("fids", new org.json.JSONArray().put(fid))
                        .put("type", 1)
                        .toString());
        if (shareFidToken != null && !shareFidToken.isEmpty()) {
            bodies.add(
                    new JSONObject()
                            .put("fids", new org.json.JSONArray().put(fid))
                            .put("share_fid_token", shareFidToken)
                            .toString());
        }
        Exception last = null;
        for (String endpoint : endpoints) {
            for (String body : bodies) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(endpoint).openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Cookie", fresh == null ? "" : fresh);
                    connection.setRequestProperty(
                            "Content-Type", "application/json;charset=UTF-8");
                    connection.setRequestProperty("Accept", "application/json, text/plain, */*");
                    connection.setRequestProperty("User-Agent", UA_CHROME);
                    connection.setRequestProperty("Referer", p.referer);
                    java.io.OutputStream out = connection.getOutputStream();
                    out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.close();
                    int status = connection.getResponseCode();
                    String text =
                            read(
                                    status >= 200 && status < 400
                                            ? connection.getInputStream()
                                            : connection.getErrorStream());
                    String masked = text == null ? "" : text;
                    if (masked.length() > 500) masked = masked.substring(0, 500);
                    masked = masked.replaceAll("\"(download_url|token|__puus)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"<redacted>\"");
                    AppLog.i(
                            "QuarkGet",
                            "download-api provider="
                                    + p.key
                                    + " fid="
                                    + fid
                                    + " api="
                                    + android.net.Uri.parse(endpoint).getHost()
                                    + " status="
                                    + status
                                    + " body="
                                    + masked);
                    if (status == 403 || status == 412) {
                        last = new IOException("HTTP " + status + "（夸克风控）");
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        last =
                                new IOException(
                                        "HTTP "
                                                + status
                                                + (status == 400
                                                        ? "：请求被拒（可尝试重新扫码）"
                                                        : ""));
                        continue;
                    }
                    JSONObject json = new JSONObject(text);
                    org.json.JSONArray data = json.optJSONArray("data");
                    if (data == null || data.length() == 0) {
                        last = new IOException("没有下载地址");
                        continue;
                    }
                    String url = data.optJSONObject(0).optString("download_url", "");
                    if (url.isEmpty()) {
                        last = new IOException("download_url 为空");
                        continue;
                    }
                    return url;
                } catch (Throwable error) {
                    last = new IOException(error.getClass().getSimpleName());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }
        throw last == null ? new IOException("没有下载地址") : last;
    }

    /** 1.9.9：转码播放接口（m3u8），drive-pc / drive 双端点兜底，日志含 status+body。 */
    static String playProjectUrl(MainActivity a, Provider p, String cookie, String fid)
            throws Exception {
        String fresh = a == null ? cookie : refreshCookie(a, p, cookie);
        String[] endpoints =
                "quark".equals(p.key)
                        ? new String[] {
                            "https://drive-pc.quark.cn/1/clouddrive/file/v2/play/project?pr=ucpro&fr=pc",
                            "https://drive.quark.cn/1/clouddrive/file/v2/play/project?pr=ucpro&fr=pc"
                        }
                        : new String[] {
                            "https://pc-api.uc.cn/1/clouddrive/file/v2/play/project?pr=UCBrowser&fr=pc",
                            "https://drive.uc.cn/1/clouddrive/file/v2/play/project?pr=UCBrowser&fr=pc"
                        };
        JSONObject body =
                new JSONObject()
                        .put("fid", fid)
                        .put("resolutions", "low,normal,high,super,2k,4k")
                        .put("supports", "fmp4_av,m3u8,dolby_vision");
        Exception last = null;
        for (String endpoint : endpoints) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Cookie", fresh == null ? "" : fresh);
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json, text/plain, */*");
                connection.setRequestProperty("User-Agent", UA_CHROME);
                connection.setRequestProperty("Referer", p.referer);
                java.io.OutputStream out = connection.getOutputStream();
                out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.close();
                int status = connection.getResponseCode();
                String text =
                        read(
                                status >= 200 && status < 400
                                        ? connection.getInputStream()
                                        : connection.getErrorStream());
                String masked = text == null ? "" : text;
                if (masked.length() > 300) masked = masked.substring(0, 300);
                masked =
                        masked.replaceAll(
                                "\"(url|token|__puus|download_url)\"\\s*:\\s*\"[^\"]*\"",
                                "\"$1\":\"<redacted>\"");
                AppLog.i(
                        "QuarkPlay",
                        "project provider="
                                + p.key
                                + " fid="
                                + fid
                                + " api="
                                + android.net.Uri.parse(endpoint).getHost()
                                + " status="
                                + status
                                + " body="
                                + masked);
                if (status < 200 || status >= 300) {
                    last = new IOException("HTTP " + status);
                    continue;
                }
                JSONObject json = new JSONObject(text);
                JSONObject data = json.optJSONObject("data");
                org.json.JSONArray list = data == null ? null : data.optJSONArray("video_list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null) continue;
                        JSONObject info = item.optJSONObject("video_info");
                        String url = info == null ? "" : info.optString("url", "");
                        if (!url.isEmpty()) return url;
                    }
                }
                last = new IOException("转码列表为空");
            } catch (Throwable error) {
                last = new IOException(friendly(error));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IOException("转码不可用") : last;
    }

    /** 1.9.9：最近 Quark 相关日志（复制给开发者；token 已脱敏）。 */
    static String recentQuarkLog(int limit) {
        java.util.List<String> entries = AppLog.entries();
        java.util.List<String> picked = new java.util.ArrayList<>();
        for (String line : entries) {
            if (line.contains("/QuarkGet") || line.contains("/QuarkPlay")) picked.add(line);
        }
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, picked.size() - Math.max(1, limit));
        for (int i = start; i < picked.size(); i++) {
            String clean =
                    picked.get(i)
                            .replaceAll(
                                    "\"(url|token|__puus|download_url|quark_st)\"\\s*:\\s*\"[^\"]*\"",
                                    "\"$1\":\"<redacted>\"");
            out.append(clean).append('\n');
        }
        return out.toString();
    }

    static String[] uaVariants(Provider p) {
        return new String[] {UA_CHROME, UA_OKHTTP, p.ua == null ? UA_QUARK_APP : p.ua};
    }

    /** 1.9.3/1.9.4：刷新 Cookie → 下载接口 → 跟随 302；403/412 时刷新后重试、再换 UA。 */
    static String resolveUrl(MainActivity a, Provider p, String cookie, String fid) throws Exception {
        return resolveUrl(a, p, cookie, fid, "");
    }

    static String resolveUrl(
            MainActivity a, Provider p, String cookie, String fid, String shareFidToken)
            throws Exception {
        String fresh = a == null ? cookie : refreshCookie(a, p, cookie);
        String url = downloadUrl(a, p, fresh, fid, shareFidToken);
        if (a != null) fresh = refreshCookie(a, p, fresh); // file/download 也可能下发 __puus
        Exception last = null;
        for (int round = 0; round < 2; round++) {
            boolean afterRefresh = round == 1;
            if (afterRefresh) {
                if (a == null) break;
                fresh = refreshCookie(a, p, fresh);
                try {
                    url = downloadUrl(a, p, fresh, fid, shareFidToken);
                } catch (Throwable ignored) {
                }
            }
            String[] uas = uaVariants(p);
            for (String ua : uas) {
                for (int extra = 0; extra < 2; extra++) {
                    String current = url;
                    try {
                        for (int hop = 0; hop < 6; hop++) {
                            HttpURLConnection connection =
                                    (HttpURLConnection) new URL(current).openConnection();
                            try {
                                connection.setInstanceFollowRedirects(false);
                                connection.setConnectTimeout(10000);
                                connection.setReadTimeout(12000);
                                connection.setRequestProperty("Cookie", fresh == null ? "" : fresh);
                                connection.setRequestProperty("Referer", p.referer);
                                connection.setRequestProperty("User-Agent", ua);
                                connection.setRequestProperty("Accept", "*/*");
                                connection.setRequestProperty("Range", "bytes=0-0");
                                if (extra == 1)
                                    connection.setRequestProperty("x-requested-with", "XMLHttpRequest");
                                int code = connection.getResponseCode();
                                String location = connection.getHeaderField("Location");
                                String host = "";
                                try {
                                    host = android.net.Uri.parse(current).getHost();
                                } catch (Throwable ignored) {
                                }
                                AppLog.i(
                                        "QuarkGet",
                                        "dl status="
                                                + code
                                                + " ua="
                                                + uaTag(ua)
                                                + " afterRefresh="
                                                + afterRefresh
                                                + " urlHost="
                                                + host);
                                if (code == 403 || code == 412) {
                                    last =
                                            new IOException(
                                                    "HTTP "
                                                            + code
                                                            + (afterRefresh
                                                                    ? "（登录状态过期，请重新扫码）"
                                                                    : "（夸克直链风控）"));
                                    break;
                                }
                                if (code >= 300
                                        && code < 400
                                        && location != null
                                        && !location.isEmpty()) {
                                    current =
                                            location.startsWith("http")
                                                    ? location
                                                    : new URL(new URL(current), location).toString();
                                    continue;
                                }
                                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                                return current;
                            } finally {
                                connection.disconnect();
                            }
                        }
                    } catch (Throwable error) {
                        last = new IOException(friendly(error));
                    }
                }
            }
        }
        throw last == null ? new IOException("直链不可用") : last;
    }

    static String uaTag(String ua) {
        if (ua == null) return "none";
        if (ua.contains("okhttp")) return "okhttp";
        if (ua.contains("Chrome/120")) return "chrome";
        return "quark-app";
    }

    private static String friendly(Throwable error) {
        return error == null
                ? "未知错误"
                : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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
