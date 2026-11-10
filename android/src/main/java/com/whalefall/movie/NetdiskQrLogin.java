package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * 四网盘官方二维码登录（移植自上游 netdisk_service.dart）：
 * quark/uc（passport token→service_ticket）、baidu（sign→channel_v.v）、ali（codeContent→bizExt）。
 * 本地 zxing 渲染二维码 + 状态文字 + 刷新/关闭；成功后保存凭据并走各盘 API 验证。
 */
final class NetdiskQrLogin {
    static final String QUARK = "quark";
    static final String UC = "uc";
    static final String BAIDU = "baidu";
    static final String ALI = "ali";

    private NetdiskQrLogin() {}

    static String name(String key) {
        switch (key) {
            case QUARK: return "夸克";
            case UC: return "UC";
            case BAIDU: return "百度";
            case ALI: return "阿里";
            default: return key;
        }
    }

    static boolean isLoggedIn(MainActivity a, String key) {
        switch (key) {
            case QUARK: return QuarkUcApi.isLoggedIn(a, QuarkUcApi.QUARK);
            case UC: return QuarkUcApi.isLoggedIn(a, QuarkUcApi.UC);
            case BAIDU:
                return a.prefs.getString("baidu_open_cookie", "").contains("BDUSS=")
                        && a.prefs.getBoolean("baidu_open_verified", false);
            case ALI:
                return !a.prefs.getString("ali_open_token", "").isEmpty()
                        && a.prefs.getBoolean("ali_open_verified", false);
            default: return false;
        }
    }

    static boolean hasCredential(MainActivity a, String key) {
        switch (key) {
            case QUARK: return QuarkUcApi.hasCookie(a, QuarkUcApi.QUARK);
            case UC: return QuarkUcApi.hasCookie(a, QuarkUcApi.UC);
            case BAIDU: return a.prefs.getString("baidu_open_cookie", "").contains("=");
            case ALI: return !a.prefs.getString("ali_open_token", "").isEmpty();
            default: return false;
        }
    }

    static void logout(MainActivity a, String key) {
        switch (key) {
            case QUARK: QuarkUcApi.logout(a, QuarkUcApi.QUARK); break;
            case UC: QuarkUcApi.logout(a, QuarkUcApi.UC); break;
            case BAIDU:
                a.prefs.edit()
                        .remove("baidu_open_cookie")
                        .remove("baidu_open_verified")
                        .remove("baidu_open_total")
                        .remove("baidu_open_used")
                        .apply();
                break;
            case ALI:
                a.prefs.edit()
                        .remove("ali_open_token")
                        .remove("ali_open_verified")
                        .remove("ali_open_total")
                        .remove("ali_open_used")
                        .apply();
                break;
            default: break;
        }
        AppLog.i("NetdiskQr", "logout drive=" + key);
    }

    /** 手动配置 Cookie/Token：保存到本地并立即验证（对应 Dart「手动配置」）。 */
    static void saveManual(final MainActivity a, final String drive, String value,
            final Runnable onChanged) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            a.toast("请输入 Cookie 或 Token");
            return;
        }
        switch (drive) {
            case QUARK:
                QuarkUcApi.saveCookie(a, QuarkUcApi.QUARK, text);
                break;
            case UC:
                QuarkUcApi.saveCookie(a, QuarkUcApi.UC, text);
                break;
            case BAIDU:
                if (!text.toLowerCase(java.util.Locale.ROOT).contains("bduss="))
                    text = "BDUSS=" + text;
                a.prefs.edit()
                        .putString("baidu_open_cookie", text)
                        .putBoolean("baidu_open_verified", false)
                        .apply();
                break;
            default:
                a.prefs.edit()
                        .putString("ali_open_token", text)
                        .putBoolean("ali_open_verified", false)
                        .apply();
                break;
        }
        AppLog.i("NetdiskQr", "manual credential saved drive=" + drive);
        a.toast("已保存，正在验证…");
        validate(a, drive, onChanged);
    }

    static String capacityText(MainActivity a, String key) {        long total;
        long used;
        switch (key) {
            case QUARK:
                total = QuarkUcApi.total(a, QuarkUcApi.QUARK);
                used = QuarkUcApi.used(a, QuarkUcApi.QUARK);
                break;
            case UC:
                total = QuarkUcApi.total(a, QuarkUcApi.UC);
                used = QuarkUcApi.used(a, QuarkUcApi.UC);
                break;
            case BAIDU:
                total = a.prefs.getLong("baidu_open_total", 0);
                used = a.prefs.getLong("baidu_open_used", 0);
                break;
            default:
                total = a.prefs.getLong("ali_open_total", 0);
                used = a.prefs.getLong("ali_open_used", 0);
                break;
        }
        if (total <= 0) return "";
        return " · 容量 "
                + QuarkUcApi.formatSize(used)
                + " / "
                + QuarkUcApi.formatSize(total);
    }

    static void validate(final MainActivity a, final String key, final Runnable onChanged) {
        switch (key) {
            case QUARK:
                QuarkUcApi.verify(a, QuarkUcApi.QUARK, status -> done(a, onChanged, "夸克", status.logged, status.error));
                break;
            case UC:
                QuarkUcApi.verify(a, QuarkUcApi.UC, status -> done(a, onChanged, "UC", status.logged, status.error));
                break;
            case BAIDU:
                a.worker.execute(
                        () -> {
                            String cookie = a.prefs.getString("baidu_open_cookie", "");
                            try {
                                JSONObject info =
                                        json(get("https://pan.baidu.com/api/user/getinfo"
                                                + "?channel=chunlei&clienttype=0&web=1", cookie));
                                boolean ok = info.optInt("errno", -1) == 0;
                                a.prefs.edit().putBoolean("baidu_open_verified", ok).apply();
                                a.ui.post(() -> done(a, onChanged, "百度", ok, "errno=" + info.optInt("errno", -1)));
                            } catch (Throwable error) {
                                a.prefs.edit().putBoolean("baidu_open_verified", false).apply();
                                a.ui.post(() -> done(a, onChanged, "百度", false, error.getMessage()));
                            }
                        });
                break;
            case ALI:
                a.worker.execute(
                        () -> {
                            try {
                                String token = a.prefs.getString("ali_open_token", "");
                                JSONObject json =
                                        json(getWithHeaders(
                                                "https://api.alipan.com/adrive/v1.0/user/getDriveInfo",
                                                "",
                                                "Authorization",
                                                "Bearer " + aliAccessToken(token),
                                                "x-device-id",
                                                "whalefall-open-1"));
                                a.prefs.edit()
                                        .putBoolean("ali_open_verified", true)
                                        .putLong("ali_open_total", json.optLong("total_size", 0))
                                        .putLong("ali_open_used", json.optLong("used_size", 0))
                                        .apply();
                                a.ui.post(() -> done(a, onChanged, "阿里", true, ""));
                            } catch (Throwable error) {
                                a.prefs.edit().putBoolean("ali_open_verified", false).apply();
                                a.ui.post(() -> done(a, onChanged, "阿里", false, error.getMessage()));
                            }
                        });
                break;
            default: break;
        }
    }

    private static void done(
            MainActivity a, Runnable onChanged, String drive, boolean ok, String error) {
        if (ok) a.toast(drive + "登录验证通过");
        else a.notice(drive + "登录验证失败：" + (error == null ? "" : error));
        if (onChanged != null) onChanged.run();
    }

    // ---------------- 二维码登录弹窗 ----------------

    static void show(final MainActivity a, final String drive, final Runnable onChanged) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(14));
        card.setBackground(UiTokens.round(a, UiTokens.RADIUS_24, 0xf2141c2a, UiTokens.STROKE));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = a.text(name(drive) + "扫码登录", UiTokens.TYPE_TITLE_LG, UiTokens.TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        final ImageView qr = new ImageView(a);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout qrBox = new FrameLayout(a);
        qrBox.setBackground(a.bg(0xffffffff, 18));
        qrBox.addView(qr, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(a.dp(250), a.dp(250));
        qrLp.topMargin = a.dp(14);
        card.addView(qrBox, qrLp);
        final TextView status = a.text("正在获取二维码…", 12, UiTokens.TEXT_TERTIARY);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = a.dp(8);
        card.addView(status, statusLp);
        LinearLayout buttons = a.row();
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = a.dp(10);
        card.addView(buttons, buttonsLp);
        Button refresh = a.button("刷新", "netdisk-qr-refresh", () -> {
            dialog.dismiss();
            show(a, drive, onChanged);
        });
        refresh.setTextSize(13);
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        Button close = a.button("关闭", "netdisk-qr-close", dialog::dismiss);
        close.setTextSize(13);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
        closeLp.leftMargin = a.dp(8);
        buttons.addView(close, closeLp);

        int width = Math.min(a.dp(420), a.windowWidth() - a.dp(40));
        FrameLayout root = new FrameLayout(a);
        root.addView(card, new FrameLayout.LayoutParams(Math.max(a.dp(280), width), -2, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.max(a.dp(280), width), -2);
            window.setGravity(Gravity.CENTER);
        }
        refresh.requestFocus();

        final int pageGen = a.generation;
        final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        dialog.setOnDismissListener(d -> closed.set(true));
        a.worker.execute(
                () -> {
                    try {
                        QrSession session = generate(drive);
                        AppLog.i("NetdiskQr", "generate drive=" + drive + " token=" + !session.token.isEmpty());
                        if (session.token.isEmpty()) throw new java.io.IOException("二维码生成失败");
                        Bitmap bitmap = PanQr.render(session.content, 640);
                        a.ui.post(
                                () -> {
                                    if (!alive(a, pageGen, closed)) return;
                                    if (bitmap != null) qr.setImageBitmap(bitmap);
                                    status.setText("请使用" + name(drive) + " App 扫码");
                                });
                        long deadline = System.currentTimeMillis() + 180000;
                        while (System.currentTimeMillis() < deadline && alive(a, pageGen, closed)) {
                            Thread.sleep(1800);
                            int state = poll(drive, session);
                            AppLog.i("NetdiskQr", "poll drive=" + drive + " state=" + state);
                            if (state == 2) {
                                saveCredential(a, drive, session);
                                a.ui.post(
                                        () -> {
                                            if (!alive(a, pageGen, closed)) return;
                                            dialog.dismiss();
                                            a.toast(name(drive) + "扫码成功，正在验证…");
                                            validate(a, drive, onChanged);
                                        });
                                return;
                            }
                            if (state == 3) {
                                a.ui.post(
                                        () -> {
                                            if (!alive(a, pageGen, closed)) return;
                                            status.setText("二维码已过期，请刷新");
                                        });
                                return;
                            }
                        }
                        a.ui.post(
                                () -> {
                                    if (!alive(a, pageGen, closed)) return;
                                    status.setText("二维码已过期，请刷新");
                                });
                    } catch (Throwable error) {
                        AppLog.i("NetdiskQr", "generate drive=" + drive + " error=" + error.getClass().getSimpleName());
                        a.ui.post(
                                () -> {
                                    if (!alive(a, pageGen, closed)) return;
                                    status.setText("获取二维码失败：" + error.getMessage());
                                });
                    }
                });
    }

    /** 1.8.24：轮询生命周期守卫（弹窗关闭/App 结束/切页）。 */
    private static boolean alive(
            MainActivity a, int pageGen, java.util.concurrent.atomic.AtomicBoolean closed) {
        return !closed.get() && !a.isFinishing() && !a.isDestroyed() && pageGen == a.generation;
    }

    private static final class QrSession {
        String token = "", content = "", extra = "";
    }

    private static QrSession generate(String drive) throws Exception {
        QrSession s = new QrSession();
        long ts = System.currentTimeMillis();
        switch (drive) {
            case QUARK: {
                JSONObject map =
                        json(get("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
                                + "?client_id=532&v=1.2&request_id=" + ts, ""));
                s.token = path(map, "data", "members", "token");
                s.content =
                        "https://su.quark.cn/4_eMHBJ?token=" + s.token
                                + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str="
                                + "S%3Acustom%7CC%3Atitlebar_hover_2";
                break;
            }
            case UC: {
                JSONObject map =
                        json(get("https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin"
                                + "?client_id=381&v=1.2&request_id=" + ts, ""));
                s.token = path(map, "data", "members", "token");
                s.content =
                        "https://su.uc.cn/1_n0ZCv?uc_param_str="
                                + "dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"
                                + "&token=" + s.token + "&client_id=381&uc_biz_str="
                                + "S%3Acustom%7CC%3Atitlebar_fix";
                break;
            }
            case BAIDU: {
                JSONObject map =
                        json(get("https://passport.baidu.com/v2/api/getqrcode"
                                + "?lp=pc&qrloginfrom=pc&tt=" + ts, ""));
                s.token = map.optString("sign", "");
                s.content =
                        "https://wappass.baidu.com/wp/?qrlogin=&t=" + (ts / 1000)
                                + "&error=0&sign=" + s.token + "&cmd=qf";
                break;
            }
            case ALI: {
                JSONObject map =
                        json(get("https://passport.aliyundrive.com/newlogin/qrcode/generate.do"
                                + "?appName=aliyun_drive&fromSite=52&appEntrance=web&_csrf_token=", ""));
                s.content = path(map, "content", "data", "codeContent");
                s.token = path(map, "content", "data", "ck");
                s.extra = path(map, "content", "data", "t");
                break;
            }
            default: break;
        }
        return s;
    }

    private static int poll(String drive, QrSession s) throws Exception {
        switch (drive) {
            case QUARK:
            case UC: {
                String base =
                        QUARK.equals(drive)
                                ? "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
                                        + "?client_id=532&v=1.2&token="
                                : "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken"
                                        + "?client_id=381&v=1.2&token=";
                JSONObject map = json(get(base + s.token + "&request_id=" + System.currentTimeMillis(), ""));
                int status = map.optInt("status", 0);
                if (status == 2000000) {
                    s.token = path(map, "data", "members", "service_ticket");
                    return 2;
                }
                if (status == 50004002) return 3;
                return 0;
            }
            case BAIDU: {
                String body =
                        get("https://passport.baidu.com/channel/unicast?channel_id=" + s.token
                                + "&tpl=netdisk&apiver=v3&tt=" + System.currentTimeMillis(), "").trim();
                if (body.startsWith("(") && body.endsWith(")"))
                    body = body.substring(1, body.length() - 1);
                JSONObject map = json(body);
                if (map.optInt("errno", -1) == 0 && map.has("channel_v")) {
                    JSONObject cv = json(map.optString("channel_v", "{}"));
                    int status = cv.optInt("status", -1);
                    if (status == 1) return 1;
                    if (status == 0 && cv.has("v")) {
                        s.token = cv.optString("v", "");
                        return 2;
                    }
                }
                return 0;
            }
            case ALI: {
                String body =
                        postForm(
                                "https://passport.aliyundrive.com/newlogin/qrcode/query.do"
                                        + "?appName=aliyun_drive&fromSite=52",
                                "ck=" + java.net.URLEncoder.encode(s.token, "UTF-8")
                                        + "&t=" + java.net.URLEncoder.encode(s.extra, "UTF-8"));
                JSONObject map = json(body);
                JSONObject data = map.optJSONObject("content");
                data = data == null ? null : data.optJSONObject("data");
                String status = data == null ? "" : data.optString("qrCodeStatus", "");
                if ("CONFIRMED".equals(status)) {
                    s.token = data.optString("bizExt", s.token);
                    return 2;
                }
                if ("SCANED".equals(status)) return 1;
                if ("EXPIRED".equals(status)) return 3;
                return 0;
            }
            default: return 0;
        }
    }

    private static void saveCredential(MainActivity a, String drive, QrSession s) {
        switch (drive) {
            case QUARK:
                QuarkUcApi.saveCookie(a, QuarkUcApi.QUARK, "quark_st=" + s.token + "; token=" + s.token);
                break;
            case UC:
                QuarkUcApi.saveCookie(a, QuarkUcApi.UC, "uc_st=" + s.token + "; token=" + s.token);
                break;
            case BAIDU:
                a.prefs.edit().putString("baidu_open_cookie", "BDUSS=" + s.token)
                        .putBoolean("baidu_open_verified", false).apply();
                break;
            default:
                a.prefs.edit().putString("ali_open_token", s.token)
                        .putBoolean("ali_open_verified", false).apply();
                break;
        }
        AppLog.i("NetdiskQr", "credential saved drive=" + drive);
    }

    static String aliAccessToken(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        try {
            JSONObject json = new JSONObject(text);
            String access = json.optString("access_token", json.optString("accessToken", ""));
            if (!access.isEmpty()) return access;
        } catch (Throwable ignored) {
        }
        try {
            String decoded =
                    new String(
                            android.util.Base64.decode(
                                    text, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP),
                            java.nio.charset.StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(decoded);
            String access = json.optString("access_token", "");
            if (!access.isEmpty()) return access;
        } catch (Throwable ignored) {
        }
        return text;
    }

    // ---------------- HTTP 小工具 ----------------

    static JSONObject json(String body) throws Exception {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length() - 1);
        return new JSONObject(text);
    }

    static String path(JSONObject json, String... keys) {
        Object current = json;
        for (String key : keys) {
            if (!(current instanceof JSONObject)) return "";
            current = ((JSONObject) current).opt(key);
        }
        return current == null ? "" : String.valueOf(current);
    }

    static String get(String url, String cookie) throws Exception {
        return getWithHeaders(url, cookie, null, null, null, null);
    }

    static String getWithHeaders(
            String url, String cookie, String k1, String v1, String k2, String v2) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (cookie != null && !cookie.isEmpty())
                connection.setRequestProperty("Cookie", cookie);
            if (k1 != null) connection.setRequestProperty(k1, v1);
            if (k2 != null) connection.setRequestProperty(k2, v2);
            int status = connection.getResponseCode();
            InputStream input =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();
            String text = read(input);
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    static String postForm(String url, String form) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes = form.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream input =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();
            String text = read(input);
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
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
                if (out.size() > 4 * 1024 * 1024) throw new java.io.IOException("响应过大");
            }
            return out.toString("UTF-8");
        }
    }
}
