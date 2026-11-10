package com.whalefall.movie;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 四网盘官方二维码登录（移植自上游开源实现 flutter/src/core/netdisk_service.dart，
 * 不逆向插件）：
 * <ul>
 *   <li>quark：uop.quark.cn token → 二维码 → service_ticket → pan.quark.cn/account/info set-cookie</li>
 *   <li>uc：api.open.uc.cn token → 二维码 → service_ticket → uc_st/token</li>
 *   <li>baidu：passport getqrcode sign → 二维码 → unicast channel_v.v → bduss</li>
 *   <li>ali：passport.aliyundrive.com generate → codeContent → query → bizExt</li>
 * </ul>
 * 二维码本地 zxing 渲染；状态：等待扫码/已扫码/成功/过期；支持刷新、粘贴 Cookie 兜底。
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

    // ---------------- 本机登录态（非 quark/uc） ----------------

    static String baiduCookie(MainActivity a) {
        return a.prefs.getString("baidu_open_cookie", "");
    }

    static boolean baiduLoggedIn(MainActivity a) {
        return baiduCookie(a).contains("BDUSS=")
                && a.prefs.getBoolean("baidu_open_verified", false);
    }

    static long baiduTotal(MainActivity a) {
        return a.prefs.getLong("baidu_open_total", 0);
    }

    static long baiduUsed(MainActivity a) {
        return a.prefs.getLong("baidu_open_used", 0);
    }

    static String aliToken(MainActivity a) {
        return a.prefs.getString("ali_open_token", "");
    }

    static boolean aliLoggedIn(MainActivity a) {
        return !aliToken(a).isEmpty()
                && a.prefs.getBoolean("ali_open_verified", false);
    }

    static long aliTotal(MainActivity a) {
        return a.prefs.getLong("ali_open_total", 0);
    }

    static long aliUsed(MainActivity a) {
        return a.prefs.getLong("ali_open_used", 0);
    }

    static boolean isLoggedIn(MainActivity a, String driveKey) {
        switch (driveKey) {
            case QUARK: return QuarkUcApi.isLoggedIn(a, QuarkUcApi.QUARK);
            case UC: return QuarkUcApi.isLoggedIn(a, QuarkUcApi.UC);
            case BAIDU: return baiduLoggedIn(a);
            case ALI: return aliLoggedIn(a);
            default: return false;
        }
    }

    static boolean hasCredential(MainActivity a, String driveKey) {
        switch (driveKey) {
            case QUARK: return QuarkUcApi.hasCookie(a, QuarkUcApi.QUARK);
            case UC: return QuarkUcApi.hasCookie(a, QuarkUcApi.UC);
            case BAIDU: return baiduCookie(a).contains("=");
            case ALI: return !aliToken(a).isEmpty();
            default: return false;
        }
    }

    static String capacityText(MainActivity a, String driveKey) {
        long total;
        long used;
        switch (driveKey) {
            case QUARK:
                total = QuarkUcApi.total(a, QuarkUcApi.QUARK);
                used = QuarkUcApi.used(a, QuarkUcApi.QUARK);
                break;
            case UC:
                total = QuarkUcApi.total(a, QuarkUcApi.UC);
                used = QuarkUcApi.used(a, QuarkUcApi.UC);
                break;
            case BAIDU:
                total = baiduTotal(a);
                used = baiduUsed(a);
                break;
            default:
                total = aliTotal(a);
                used = aliUsed(a);
                break;
        }
        if (total <= 0) return "";
        return " · 容量 "
                + QuarkUcApi.formatSize(used)
                + " / "
                + QuarkUcApi.formatSize(total);
    }

    static void logout(MainActivity a, String driveKey) {        switch (driveKey) {
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
        AppLog.i("NetdiskQr", "logout drive=" + driveKey);
    }

    // ---------------- 弹窗 ----------------

    static final class Session {
        final String driveKey;

        Session(String driveKey) {
            this.driveKey = driveKey;
        }

        String token = "";
        String extraT = "";
        String qrContent = "";
        String message = "";
        int state; // 0 waiting 1 scanned 2 confirmed 3 expired
        boolean closed;
        boolean refreshing;
    }

    static void show(final MainActivity a, final String driveKey, final Runnable onChanged) {
        final Session session = new Session(driveKey);
        final android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(16));
        card.setBackground(UiTokens.round(a, UiTokens.RADIUS_24, 0xf2141c2a, UiTokens.STROKE));
        UiCompat.setElevation(card, a.dp(18));
        card.setClickable(true);
        TextView title = a.text(name(driveKey) + "扫码登录", UiTokens.TYPE_TITLE_LG, UiTokens.TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView hint = a.text("用对应网盘 App 扫码登录", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = a.dp(6);
        card.addView(hint, hintLp);
        FrameLayout qrBox = new FrameLayout(a);
        qrBox.setBackground(a.bg(0xffffffff, 18));
        final ImageView qrView = new ImageView(a);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        qrBox.addView(qrView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams qrLp =
                new FrameLayout.LayoutParams(a.dp(250), a.dp(250), Gravity.CENTER_HORIZONTAL);
        qrLp.topMargin = a.dp(14);
        card.addView(qrBox, qrLp);
        final TextView status = a.text("正在获取二维码…", 13, MainActivity.MUTED);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = a.dp(8);
        card.addView(status, statusLp);
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = a.dp(12);
        card.addView(row, rowLp);
        Button paste = a.button("粘贴 Cookie", "netdisk-paste", () -> {
            dialog.dismiss();
            pasteCookie(a, driveKey, onChanged);
        });
        paste.setTextSize(12);
        row.addView(paste, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        Button refresh = a.button("刷新二维码", "netdisk-refresh", () -> {
            dialog.dismiss();
            show(a, driveKey, onChanged);
        });
        refresh.setTextSize(12);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
        refreshLp.leftMargin = a.dp(6);
        row.addView(refresh, refreshLp);
        Button close = a.button("关闭", "netdisk-close", dialog::dismiss);
        close.setTextSize(12);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
        closeLp.leftMargin = a.dp(6);
        row.addView(close, closeLp);

        int width = Math.min(a.dp(420), a.windowWidth() - a.dp(36));
        FrameLayout root = new FrameLayout(a);
        root.addView(
                card, new FrameLayout.LayoutParams(Math.max(a.dp(280), width), -2, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> session.closed = true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(Math.max(a.dp(280), width), -2);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        refresh.requestFocus();

        a.worker.execute(
                () -> {
                    try {
                        generate(session);
                        if (session.token.isEmpty())
                            throw new java.io.IOException("二维码生成失败（token 为空）");
                        Bitmap bitmap = PanQr.render(session.qrContent, 640);
                        AppLog.i(
                                "NetdiskQr",
                                "generate drive="
                                        + driveKey
                                        + " qrContent="
                                        + !session.qrContent.isEmpty()
                                        + " token="
                                        + !session.token.isEmpty());
                        a.ui.post(() -> {
                            if (bitmap != null) qrView.setImageBitmap(bitmap);
                            status.setText("等待扫码…");
                        });
                        long deadline = System.currentTimeMillis() + 180000;
                        while (System.currentTimeMillis() < deadline && !session.closed) {
                            Thread.sleep(1800);
                            poll(session);
                            AppLog.i(
                                    "NetdiskQr",
                                    "poll drive=" + driveKey + " state=" + session.state);
                            if (session.state == 2) {
                                saveCredential(a, driveKey, session);
                                a.ui.post(() -> {
                                    dialog.dismiss();
                                    a.toast(name(driveKey) + "扫码成功，正在验证…");
                                    validate(a, driveKey, onChanged);
                                });
                                return;
                            }
                            if (session.state == 3) {
                                a.ui.post(() -> status.setText(session.message));
                                return;
                            }
                            if (session.state == 1)
                                a.ui.post(() -> status.setText(session.message));
                        }
                        a.ui.post(() -> status.setText("二维码已过期，请点「刷新二维码」"));
                    } catch (Throwable error) {
                        AppLog.i("NetdiskQr", "generate drive=" + driveKey + " error=" + friendly(error));
                        a.ui.post(
                                () -> {
                                    status.setText("获取二维码失败：" + friendly(error));
                                });
                    }
                });
    }

    // ---------------- 二维码会话（照抄 netdisk_service.dart） ----------------

    static void generate(Session s) throws Exception {
        long ts = System.currentTimeMillis();
        JSONObject map;
        switch (s.driveKey) {
            case QUARK:
                map =
                        json(
                                get(
                                        "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
                                                + "?client_id=532&v=1.2&request_id="
                                                + ts,
                                        ""));
                s.token = path(map, "data", "members", "token");
                s.qrContent =
                        "https://su.quark.cn/4_eMHBJ?token="
                                + s.token
                                + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str="
                                + "S%3Acustom%7CC%3Atitlebar_hover_2";
                break;
            case UC:
                map =
                        json(
                                get(
                                        "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin"
                                                + "?client_id=381&v=1.2&request_id="
                                                + ts,
                                        ""));
                s.token = path(map, "data", "members", "token");
                s.qrContent =
                        "https://su.uc.cn/1_n0ZCv?uc_param_str="
                                + "dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt"
                                + "&token=" + s.token + "&client_id=381&uc_biz_str="
                                + "S%3Acustom%7CC%3Atitlebar_fix";
                break;
            case BAIDU:
                map =
                        json(
                                get(
                                        "https://passport.baidu.com/v2/api/getqrcode"
                                                + "?lp=pc&qrloginfrom=pc&tt=" + ts,
                                        ""));
                s.token = map.optString("sign", "");
                s.qrContent =
                        "https://wappass.baidu.com/wp/?qrlogin=&t="
                                + (ts / 1000)
                                + "&error=0&sign="
                                + s.token
                                + "&cmd=qf";
                break;
            case ALI:
                map =
                        json(
                                get(
                                        "https://passport.aliyundrive.com/newlogin/qrcode/generate.do"
                                                + "?appName=aliyun_drive&fromSite=52&appEntrance=web"
                                                + "&_csrf_token=",
                                        ""));
                s.qrContent = path(map, "content", "data", "codeContent");
                s.token = path(map, "content", "data", "ck");
                s.extraT = path(map, "content", "data", "t");
                break;
            default:
                break;
        }
    }

    static void poll(Session s) throws Exception {
        long ts = System.currentTimeMillis();
        switch (s.driveKey) {
            case QUARK:
            case UC: {
                boolean quark = QUARK.equals(s.driveKey);
                String base =
                        quark
                                ? "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
                                        + "?client_id=532&v=1.2&token="
                                : "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken"
                                        + "?client_id=381&v=1.2&token=";
                JSONObject map = json(get(base + s.token + "&request_id=" + ts, ""));
                int status = map.optInt("status", 0);
                if (status == 2000000) {
                    String st = path(map, "data", "members", "service_ticket");
                    s.token = st; // 暂存 service_ticket 用于保存
                    s.state = 2;
                    s.message = "登录成功";
                } else if (status == 50004002) {
                    s.state = 3;
                    s.message = "二维码已过期，请刷新";
                } else {
                    s.state = 0;
                    s.message = "等待扫码并确认…";
                }
                break;
            }
            case BAIDU: {
                String body =
                        get(
                                        "https://passport.baidu.com/channel/unicast?channel_id="
                                                + s.token
                                                + "&tpl=netdisk&apiver=v3&tt="
                                                + ts,
                                        "")
                                .trim();
                if (body.startsWith("(") && body.endsWith(")"))
                    body = body.substring(1, body.length() - 1);
                JSONObject map = json(body);
                if (map.optInt("errno", -1) == 0 && map.has("channel_v")) {
                    JSONObject cv = json(map.optString("channel_v", "{}"));
                    int status = cv.optInt("status", -1);
                    if (status == 1) {
                        s.state = 1;
                        s.message = "已扫码，请在手机百度网盘确认";
                    } else if (status == 0 && cv.has("v")) {
                        s.token = cv.optString("v", "");
                        s.state = 2;
                        s.message = "登录成功";
                    }
                } else {
                    s.state = 0;
                    s.message = "等待扫码并确认…";
                }
                break;
            }
            case ALI: {
                String body =
                        postForm(
                                "https://passport.aliyundrive.com/newlogin/qrcode/query.do"
                                        + "?appName=aliyun_drive&fromSite=52",
                                "ck="
                                        + java.net.URLEncoder.encode(s.token, "UTF-8")
                                        + "&t="
                                        + java.net.URLEncoder.encode(s.extraT, "UTF-8"));
                JSONObject map = json(body);
                JSONObject data = map.optJSONObject("content");
                data = data == null ? null : data.optJSONObject("data");
                String status = data == null ? "" : data.optString("qrCodeStatus", "");
                if ("CONFIRMED".equals(status)) {
                    s.token = data.optString("bizExt", s.token);
                    s.state = 2;
                    s.message = "登录成功";
                } else if ("SCANED".equals(status)) {
                    s.state = 1;
                    s.message = "已扫码，请在手机阿里云盘确认";
                } else if ("EXPIRED".equals(status)) {
                    s.state = 3;
                    s.message = "二维码已过期，请刷新";
                } else {
                    s.state = 0;
                    s.message = "等待扫码并确认…";
                }
                break;
            }
            default:
                break;
        }
    }

    static void saveCredential(MainActivity a, String driveKey, Session s) {
        switch (driveKey) {
            case QUARK: {
                String cookie = "quark_st=" + s.token + "; token=" + s.token;
                try {
                    String[] result =
                            getCapture(
                                    "https://pan.quark.cn/account/info?st="
                                            + java.net.URLEncoder.encode(s.token, "UTF-8")
                                            + "&lw=scan");
                    if (result[1] != null && !result[1].isEmpty()) cookie = result[1];
                } catch (Throwable ignored) {
                }
                QuarkUcApi.saveCookie(a, QuarkUcApi.QUARK, cookie);
                break;
            }
            case UC:
                QuarkUcApi.saveCookie(a, QuarkUcApi.UC, "uc_st=" + s.token + "; token=" + s.token);
                break;
            case BAIDU:
                a.prefs.edit()
                        .putString("baidu_open_cookie", "BDUSS=" + s.token)
                        .putBoolean("baidu_open_verified", false)
                        .apply();
                break;
            case ALI:
                a.prefs.edit()
                        .putString("ali_open_token", s.token)
                        .putBoolean("ali_open_verified", false)
                        .apply();
                break;
            default:
                break;
        }
        AppLog.i("NetdiskQr", "credential saved drive=" + driveKey);
    }

    // ---------------- 验证（各盘官方 API） ----------------

    static void validate(final MainActivity a, final String driveKey, final Runnable onChanged) {
        switch (driveKey) {
            case QUARK:
                QuarkUcApi.verifyAsync(
                        a,
                        QuarkUcApi.QUARK,
                        status -> {
                            toastValidate(a, "夸克", status.logged, status.error);
                            if (onChanged != null) onChanged.run();
                        });
                break;
            case UC:
                QuarkUcApi.verifyAsync(
                        a,
                        QuarkUcApi.UC,
                        status -> {
                            toastValidate(a, "UC", status.logged, status.error);
                            if (onChanged != null) onChanged.run();
                        });
                break;
            case BAIDU:
                a.worker.execute(
                        () -> {
                            String cookie = baiduCookie(a);
                            try {
                                JSONObject info =
                                        json(
                                                get(
                                                        "https://pan.baidu.com/api/user/getinfo"
                                                                + "?channel=chunlei&clienttype=0&web=1",
                                                        cookie));
                                int errno = info.optInt("errno", -1);
                                long total = 0, used = 0;
                                JSONObject quota =
                                        json(
                                                get(
                                                        "https://pan.baidu.com/api/quota"
                                                                + "?checkfree=1&checkexpire=1&channel=chunlei"
                                                                + "&clienttype=0&web=1",
                                                        cookie));
                                total = quota.optLong("total", 0);
                                used = quota.optLong("quota", quota.optLong("used", 0));
                                final long fTotal = total, fUsed = used;
                                boolean ok = errno == 0;
                                AppLog.i("NetdiskQr", "validate drive=baidu errno=" + errno);
                                a.ui.post(
                                        () -> {
                                            a.prefs.edit()
                                                    .putBoolean("baidu_open_verified", ok)
                                                    .putLong("baidu_open_total", fTotal)
                                                    .putLong("baidu_open_used", fUsed)
                                                    .apply();
                                            toastValidate(a, "百度", ok, errno == 0 ? "" : "errno=" + errno);
                                            if (onChanged != null) onChanged.run();
                                        });
                            } catch (Throwable error) {
                                AppLog.i("NetdiskQr", "validate drive=baidu error=" + friendly(error));
                                a.ui.post(
                                        () -> {
                                            a.prefs.edit().putBoolean("baidu_open_verified", false).apply();
                                            toastValidate(a, "百度", false, friendly(error));
                                            if (onChanged != null) onChanged.run();
                                        });
                            }
                        });
                break;
            case ALI:
                a.worker.execute(
                        () -> {
                            String token = aliAccessToken(aliToken(a));
                            try {
                                JSONObject json =
                                        json(
                                                getWithHeaders(
                                                        "https://api.alipan.com/adrive/v1.0/user/getDriveInfo",
                                                        "",
                                                        "Authorization",
                                                        "Bearer " + token,
                                                        "x-device-id",
                                                        "whalefall-open-1"));
                                long total = json.optLong("total_size", 0);
                                long used = json.optLong("used_size", 0);
                                final long fTotal = total, fUsed = used;
                                AppLog.i("NetdiskQr", "validate drive=ali ok=" + json.length());
                                a.ui.post(
                                        () -> {
                                            a.prefs.edit()
                                                    .putBoolean("ali_open_verified", true)
                                                    .putLong("ali_open_total", fTotal)
                                                    .putLong("ali_open_used", fUsed)
                                                    .apply();
                                            toastValidate(a, "阿里", true, "");
                                            if (onChanged != null) onChanged.run();
                                        });
                            } catch (Throwable error) {
                                AppLog.i("NetdiskQr", "validate drive=ali error=" + friendly(error));
                                a.ui.post(
                                        () -> {
                                            a.prefs.edit().putBoolean("ali_open_verified", false).apply();
                                            toastValidate(a, "阿里", false, friendly(error));
                                            if (onChanged != null) onChanged.run();
                                        });
                            }
                        });
                break;
            default:
                break;
        }
    }

    private static void toastValidate(MainActivity a, String name, boolean ok, String error) {
        if (ok) a.toast(name + "登录验证通过");
        else a.notice(name + "登录验证失败：" + error);
    }

    /** bizExt / ali_token 可能是 Base64(JSON)，取出 access_token。 */
    static String aliAccessToken(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("ali_token=")) text = text.substring("ali_token=".length());
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

    // ---------------- 面板入口 ----------------

    static void showPanel(final MainActivity a, final String driveKey, final Runnable onChanged) {
        if (!isLoggedIn(a, driveKey)) {
            show(a, driveKey, onChanged);
            return;
        }
        menu(a, driveKey, onChanged);
    }

    static void menu(final MainActivity a, final String driveKey, final Runnable onChanged) {
        String name = name(driveKey);
        StringBuilder message = new StringBuilder("真实登录态：已登录（" + name + " 官方 API 验证通过）");
        long total;
        long used;
        switch (driveKey) {
            case QUARK:
                total = QuarkUcApi.total(a, QuarkUcApi.QUARK);
                used = QuarkUcApi.used(a, QuarkUcApi.QUARK);
                break;
            case UC:
                total = QuarkUcApi.total(a, QuarkUcApi.UC);
                used = QuarkUcApi.used(a, QuarkUcApi.UC);
                break;
            case BAIDU:
                total = baiduTotal(a);
                used = baiduUsed(a);
                break;
            default:
                total = aliTotal(a);
                used = aliUsed(a);
                break;
        }
        if (total > 0)
            message.append("\n容量：")
                    .append(QuarkUcApi.formatSize(used))
                    .append(" / ")
                    .append(QuarkUcApi.formatSize(total));
        new AppDialog.Builder(a)
                .setTitle("我的" + name + "网盘")
                .setMessage(message.toString())
                .setItems(
                        new String[] {"查看我的文件", "重新验证登录状态", "退出登录"},
                        (d, index) -> {
                            if (index == 0) {
                                if (QUARK.equals(driveKey) || UC.equals(driveKey))
                                    QuarkUcFiles.show(
                                            a,
                                            QUARK.equals(driveKey)
                                                    ? QuarkUcApi.QUARK
                                                    : QuarkUcApi.UC);
                                else
                                    a.toast(name + "文件列表将在第二批接入");
                            } else if (index == 1) {
                                validate(a, driveKey, onChanged);
                            } else {
                                logout(a, driveKey);
                                a.toast("已退出" + name + "登录");
                                if (onChanged != null) onChanged.run();
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------- 粘贴 Cookie 兜底 ----------------

    static void pasteCookie(
            final MainActivity a, final String driveKey, final Runnable onChanged) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint(
                BAIDU.equals(driveKey)
                        ? "BDUSS=..."
                        : ALI.equals(driveKey)
                                ? "ali_token=... 或 bizExt 内容"
                                : "__puus=...; 或 uc_st=...; token=...");
        new AppDialog.Builder(a)
                .setTitle("粘贴 " + name(driveKey) + " 登录凭据")
                .setMessage("从浏览器/官方 App 复制的 Cookie（或 token），只保存在本机。")
                .setView(input)
                .setPositiveButton(
                        "保存并验证",
                        (d, i) -> {
                            String value = input.getText().toString().trim();
                            if (value.isEmpty()) {
                                a.toast("内容为空");
                                return;
                            }
                            switch (driveKey) {
                                case QUARK:
                                    QuarkUcApi.saveCookie(a, QuarkUcApi.QUARK, value);
                                    break;
                                case UC:
                                    QuarkUcApi.saveCookie(a, QuarkUcApi.UC, value);
                                    break;
                                case BAIDU:
                                    if (!value.toLowerCase(Locale.ROOT).contains("bduss="))
                                        value = "BDUSS=" + value;
                                    a.prefs.edit()
                                            .putString("baidu_open_cookie", value)
                                            .putBoolean("baidu_open_verified", false)
                                            .apply();
                                    break;
                                case ALI:
                                    a.prefs.edit()
                                            .putString("ali_open_token", value)
                                            .putBoolean("ali_open_verified", false)
                                            .apply();
                                    break;
                                default:
                                    break;
                            }
                            validate(a, driveKey, onChanged);
                        })
                .setNegativeButton("取消", null)
                .show();
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

    /** GET 并返回 {body, set-cookie}（夸克登录需要从 account/info 的 set-cookie 取凭据）。 */
    static String[] getCapture(String url) throws Exception {
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
            int status = connection.getResponseCode();
            InputStream input =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();
            String text = read(input);
            return new String[] {text, joinSetCookie(connection)};
        } finally {
            connection.disconnect();
        }
    }

    static String getWithHeaders(
            String url, String cookie, String k1, String v1, String k2, String v2)
            throws Exception {
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
            String joinedCookie = joinSetCookie(connection);
            if (!joinedCookie.isEmpty() && cookie != null && !cookie.isEmpty()) {
                try {
                    JSONObject holder = new JSONObject();
                    holder.put("cookie", cookie + "; " + joinedCookie);
                    // 由调用方处理 set-cookie；此处仅记录
                } catch (Throwable ignored) {
                }
            }
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status + " " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    static String joinSetCookie(HttpURLConnection connection) {
        try {
            List<String> values = new ArrayList<>();
            for (java.util.Map.Entry<String, List<String>> entry :
                    connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()))
                    values.addAll(entry.getValue());
            }
            StringBuilder out = new StringBuilder();
            for (String value : values) {
                if (value == null) continue;
                String pair = value.split(";", 2)[0];
                if (pair.contains("=")) {
                    if (out.length() > 0) out.append("; ");
                    out.append(pair);
                }
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "";
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
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status + " " + text);
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

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : "：" + message);
    }
}
