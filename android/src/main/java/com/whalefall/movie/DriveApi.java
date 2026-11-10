package com.whalefall.movie;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** 原生网盘直连的通用 HTTP 工具（worker 线程调用；Cookie/UA/Referer 头、超时、错误透出）。 */
final class DriveApi {
    static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private DriveApi() {}

    static String get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, headers, null);
    }

    static String post(String url, Map<String, String> headers, String body) throws Exception {
        return request("POST", url, headers, body, "application/json; charset=UTF-8");
    }

    /** 表单 POST（阿里 passport 等要求 x-www-form-urlencoded）。 */
    static String postForm(String url, Map<String, String> headers, String form) throws Exception {
        return request(
                "POST",
                url,
                headers,
                form == null ? "" : form,
                "application/x-www-form-urlencoded; charset=UTF-8");
    }

    private static String request(
            String method, String url, Map<String, String> headers, String body, String contentType)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (headers != null)
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null)
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
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
            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status + " " + text);
            return text;
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

    static String friendly(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : "：" + message);
    }

    /** Java 8 兼容的小 Map（Android 无 Map.of）。 */
    static Map<String, String> headers(String... pairs) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
            if (pairs[i] != null && pairs[i + 1] != null) map.put(pairs[i], pairs[i + 1]);
        return map;
    }
}
