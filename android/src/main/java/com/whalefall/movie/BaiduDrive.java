package com.whalefall.movie;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 百度网盘原生直连（Phase 4；桌面 UA + BDUSS/STOKEN + bdstoken）。 */
final class BaiduDrive {
    static final String API = "https://pan.baidu.com";
    static final String PASSPORT = "https://passport.baidu.com";
    static final String LOGIN_URL = "https://pan.baidu.com/";
    /** 桌面 UA 下 passport 登录页通常直接给二维码（同源可注入）。 */
    static final String LOGIN_PAGE =
            "https://passport.baidu.com/v2/?login&u=https%3A%2F%2Fpan.baidu.com%2F";

    private BaiduDrive() {}

    static boolean hasCookie(MainActivity a) {
        String cookie = a == null ? "" : a.prefs.getString(Prefs.BAIDU_COOKIE, "");
        return cookie.contains("BDUSS=") || cookie.contains("STOKEN=");
    }

    /** 必须通过 getinfo API 验证才算已登录。 */
    static boolean isLoggedIn(MainActivity a) {
        return hasCookie(a) && a.prefs.getBoolean(Prefs.BAIDU_VERIFIED, false);
    }

    static String cookie(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.BAIDU_COOKIE, "");
    }

    static String cachedAccount(MainActivity a) {
        return isLoggedIn(a) ? a.prefs.getString(Prefs.BAIDU_ACCOUNT, "") : "";
    }

    static String stoken(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.BAIDU_STOKEN, "");
    }

    static void deviceLogout(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.BAIDU_COOKIE)
                .remove(Prefs.BAIDU_VERIFIED)
                .remove(Prefs.BAIDU_STOKEN)
                .remove(Prefs.BAIDU_ACCOUNT)
                .remove(Prefs.BAIDU_TOTAL)
                .remove(Prefs.BAIDU_USED)
                .apply();
        AppLog.i("BaiduDrive", "logout cleared cookie=false verified=false");
    }

    static void showPanel(final MainActivity a, final Runnable onChanged) {
        if (!hasCookie(a)) {
            startQrLogin(a, onChanged);
            return;
        }
        verifyAsync(
                a,
                status -> {
                    if (!status.logged) {
                        new AppDialog.Builder(a)
                                .setTitle("百度登录验证失败")
                                .setMessage(
                                        "登录信息已保存，但验证失败："
                                                + status.error
                                                + "\n\n可重试验证，或重新扫码登录。")
                                .setPositiveButton(
                                        "重试验证",
                                        (d, i) -> {
                                            PanLoginState.invalidate();
                                            showPanel(a, onChanged);
                                        })
                                .setNeutralButton("重新登录", (d, i) -> login(a, onChanged))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    menu(a, status, onChanged);
                });
    }

    /**
     * 官方二维码登录 API（优先，插件同款）：
     * getqrcode 取 imgurl/channel_id/gid → 显示二维码 → unicast 轮询拿 BDUSS/STOKEN → Cookie 后走 verify。
     * 失败时降级为网页扫码（login）。
     */
    static void startQrLogin(final MainActivity a, final Runnable onChanged) {
        final QrLoginDialog ui = new QrLoginDialog(a, "百度网盘扫码登录", "用百度网盘 App 扫码", null);
        ui.setRefresh(
                () -> {
                    ui.dismiss();
                    startQrLogin(a, onChanged);
                });
        a.worker.execute(
                () -> {
                    try {
                        String gid = java.util.UUID.randomUUID().toString().toUpperCase();
                        String generate =
                                PASSPORT
                                        + "/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid="
                                        + gid
                                        + "&callback=cb";
                        String body =
                                DriveApi.get(
                                        generate,
                                        DriveApi.headers(
                                                "Referer", "https://passport.baidu.com/",
                                                "User-Agent", PanDriveLogin.DESKTOP_UA));
                        JSONObject json = new JSONObject(PanQr.stripJsonp(body));
                        String imgurl = json.optString("imgurl", "");
                        String channel = json.optString("channel_id", "");
                        AppLog.i(
                                "BaiduQr",
                                "generate imgurl="
                                        + !imgurl.isEmpty()
                                        + " channel="
                                        + !channel.isEmpty()
                                        + " errno="
                                        + json.optInt("errno", 0));
                        if (imgurl.isEmpty() || channel.isEmpty())
                            throw new java.io.IOException("getqrcode 缺少 imgurl/channel_id");
                        a.ui.post(() -> ui.showImageUrl(imgurl));
                        long deadline = System.currentTimeMillis() + 120000;
                        while (System.currentTimeMillis() < deadline && !ui.closed) {
                            Thread.sleep(2000);
                            String poll =
                                    PASSPORT
                                            + "/channel/unicast?channel_id="
                                            + channel
                                            + "&tpl=netdisk&gid="
                                            + gid
                                            + "&callback=cb";
                            String pollBody =
                                    DriveApi.get(
                                            poll,
                                            DriveApi.headers(
                                                    "Referer", "https://passport.baidu.com/",
                                                    "User-Agent", PanDriveLogin.DESKTOP_UA));
                            JSONObject pollJson = new JSONObject(PanQr.stripJsonp(pollBody));
                            String v = pollJson.optString("v", "");
                            AppLog.i(
                                    "BaiduQr",
                                    "poll errno="
                                            + pollJson.optInt("errno", -1)
                                            + " hasV="
                                            + !v.isEmpty());
                            if (v.isEmpty()) continue;
                            android.net.Uri data = android.net.Uri.parse("?" + v);
                            String bduss = data.getQueryParameter("BDUSS");
                            String stoken = data.getQueryParameter("STOKEN");
                            if (bduss == null || bduss.isEmpty()) {
                                AppLog.i("BaiduQr", "poll v missing BDUSS");
                                continue;
                            }
                            String cookie =
                                    "BDUSS=" + bduss + (stoken == null ? "" : "; STOKEN=" + stoken);
                            a.prefs.edit()
                                    .putString(Prefs.BAIDU_COOKIE, cookie)
                                    .putBoolean(Prefs.BAIDU_VERIFIED, false)
                                    .apply();
                            try {
                                android.webkit.CookieManager manager =
                                        android.webkit.CookieManager.getInstance();
                                manager.setCookie(
                                        "https://pan.baidu.com",
                                        "BDUSS=" + bduss + "; domain=.baidu.com; path=/");
                                manager.flush();
                            } catch (Throwable ignored) {
                            }
                            fetchBdstokenBlocking(a, cookie);
                            verifyAsync(
                                    a,
                                    status -> {
                                        ui.dismiss();
                                        if (status.logged)
                                            a.toast(
                                                    "百度登录成功"
                                                            + (status.account.isEmpty()
                                                                    ? ""
                                                                    : "：" + status.account));
                                        else
                                            a.notice(
                                                    "二维码已确认，但验证失败："
                                                            + status.error);
                                        if (onChanged != null) onChanged.run();
                                    });
                            return;
                        }
                        ui.setStatus("二维码已过期，请点「刷新二维码」");
                    } catch (Throwable error) {
                        AppLog.i("BaiduQr", "generate error=" + DriveApi.friendly(error));
                        a.ui.post(
                                () -> {
                                    ui.dismiss();
                                    new AppDialog.Builder(a)
                                            .setTitle("获取百度二维码失败")
                                            .setMessage(
                                                    DriveApi.friendly(error)
                                                            + "\n\n可改用网页扫码登录。")
                                            .setPositiveButton(
                                                    "用网页登录", (d, i) -> login(a, onChanged))
                                            .setNegativeButton("取消", null)
                                            .show();
                                });
                    }
                });
    }

    static void login(final MainActivity a, final Runnable onChanged) {
        PanDriveLogin.Config config = new PanDriveLogin.Config();
        config.title = "百度网盘扫码登录";
        config.hint = "用百度网盘 App 扫码登录；成功后 App 会调百度 API 验证真实账号。";
        config.loginUrl = LOGIN_PAGE;
        config.cookieNames = new String[] {"BDUSS", "STOKEN"};
        config.userAgent = PanDriveLogin.DESKTOP_UA;
        config.injectDelayMs = 2000;
        config.maxAttempts = 20;
        config.clickLoginEntry = true;
        PanDriveLogin.show(
                a,
                config,
                value -> {
                    a.prefs.edit()
                            .putString(Prefs.BAIDU_COOKIE, value)
                            .putBoolean(Prefs.BAIDU_VERIFIED, false)
                            .apply();
                    fetchBdstokenAsync(
                            a,
                            () ->
                                    verifyAsync(
                                            a,
                                            status -> {
                                                if (status.logged)
                                                    a.toast(
                                                            "百度登录成功"
                                                                    + (status.account.isEmpty()
                                                                            ? ""
                                                                            : "："
                                                                                    + status.account));
                                                else
                                                    a.notice(
                                                            "登录信息已保存，但验证失败："
                                                                    + status.error);
                                                if (onChanged != null) onChanged.run();
                                            }));
                });
    }

    static void fetchBdstokenAsync(final MainActivity a, final Runnable done) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    fetchBdstokenBlocking(a, cookie);
                    a.ui.post(done);
                });
    }

    static String fetchBdstokenBlocking(MainActivity a, String cookie) {
        try {
            String url =
                    API
                            + "/api/gettemplatevariable?fields=%5B%22bdstoken%22%5D"
                            + "&channel=chunlei&clienttype=0&web=1";
            String body =
                    DriveApi.get(
                            url,
                            DriveApi.headers(
                                    "Cookie", cookie,
                                    "Referer", LOGIN_URL,
                                    "User-Agent", PanDriveLogin.DESKTOP_UA));
            JSONObject json = new JSONObject(body);
            JSONObject result = json.optJSONObject("result");
            String token =
                    result == null
                            ? json.optString("bdstoken", "")
                            : result.optString("bdstoken", "");
            if (!token.isEmpty()) a.prefs.edit().putString(Prefs.BAIDU_STOKEN, token).apply();
            AppLog.i("BaiduDrive", "bdstoken url=gettemplatevariable ok=" + !token.isEmpty());
            return token;
        } catch (Throwable error) {
            AppLog.i(
                    "BaiduDrive",
                    "bdstoken url=gettemplatevariable error=" + DriveApi.friendly(error));
            return "";
        }
    }

    static String accountFrom(JSONObject data, JSONObject root) {
        String[] keys = {
            "username", "user_name", "display_name", "baidu_name", "netdisk_name", "name",
            "nickname"
        };
        if (data != null)
            for (String key : keys) {
                String value = data.optString(key, "");
                if (!value.isEmpty()) return value;
            }
        if (root != null)
            for (String key : keys) {
                String value = root.optString(key, "");
                if (!value.isEmpty()) return value;
            }
        long uk = data == null ? 0 : data.optLong("uk", 0);
        if (uk <= 0 && root != null) uk = root.optLong("uk", 0);
        return uk > 0 ? "百度用户" + uk : "";
    }

    static void verifyAsync(final MainActivity a, final QuarkDrive.Callback<QuarkDrive.Status> cb) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    QuarkDrive.Status status;
                    String error = "";
                    try {
                        // 1) 先取 bdstoken（所有接口都带）
                        String bdstoken = stoken(a);
                        if (bdstoken.isEmpty()) bdstoken = fetchBdstokenBlocking(a, cookie);
                        AppLog.i("BaiduDrive", "verify bdstoken=" + !bdstoken.isEmpty());
                        final String[][] candidates = {
                            {
                                "/api/user/getinfo?channel=chunlei&clienttype=0&web=1&app_id=250528&bdstoken=",
                                "getinfo-full"
                            },
                            {
                                "/api/user/getinfo?web=1&app_id=250528&bdstoken=",
                                "getinfo-min"
                            },
                            {
                                "/rest/2.0/xpan/nas?method=uinfo&web=1&app_id=250528&bdstoken=",
                                "xpan-uinfo"
                            },
                        };
                        String account = "";
                        for (String[] candidate : candidates) {
                            String url = API + candidate[0] + bdstoken;
                            try {
                                String body =
                                        DriveApi.get(
                                                url,
                                                DriveApi.headers(
                                                        "Cookie", cookie,
                                                        "Referer", LOGIN_URL,
                                                        "User-Agent", PanDriveLogin.DESKTOP_UA));
                                JSONObject json = new JSONObject(body);
                                int errno = json.optInt("errno", -1);
                                String msg = json.optString("errmsg", json.optString("msg", ""));
                                AppLog.i(
                                        "BaiduDrive",
                                        "verify try="
                                                + candidate[1]
                                                + " result=errno="
                                                + errno
                                                + (msg.isEmpty() ? "" : " msg=" + msg));
                                if (errno != 0) {
                                    error = "errno=" + errno + (msg.isEmpty() ? "" : " " + msg);
                                    continue;
                                }
                                account = accountFrom(json.optJSONObject("data"), json);
                                if (!account.isEmpty()) break;
                            } catch (Throwable failure) {
                                error = DriveApi.friendly(failure);
                                AppLog.i(
                                        "BaiduDrive",
                                        "verify try=" + candidate[1] + " error=" + error);
                            }
                        }
                        if (account.isEmpty() && error.isEmpty())
                            error = "getinfo 返回 eno=0 但未取到账号名";
                        if (!account.isEmpty()) {
                            long total = 0, used = 0;
                            String[][] quotaCandidates = {
                                {
                                    "/api/quota?checkfree=1&checkexpire=1&channel=chunlei&clienttype=0&web=1&app_id=250528&bdstoken=",
                                    "quota-full"
                                },
                                {
                                    "/api/quota?checkfree=1&checkexpire=1&web=1&app_id=250528&bdstoken=",
                                    "quota-min"
                                },
                            };
                            for (String[] quotaCandidate : quotaCandidates) {
                                try {
                                    String quotaBody =
                                            DriveApi.get(
                                                    API + quotaCandidate[0] + bdstoken,
                                                    DriveApi.headers(
                                                            "Cookie", cookie,
                                                            "Referer", LOGIN_URL,
                                                            "User-Agent", PanDriveLogin.DESKTOP_UA));
                                    JSONObject quota = new JSONObject(quotaBody);
                                    int errno = quota.optInt("errno", 0);
                                    AppLog.i(
                                            "BaiduDrive",
                                            "quota try="
                                                    + quotaCandidate[1]
                                                    + " result=errno="
                                                    + errno);
                                    if (errno != 0) continue;
                                    total = quota.optLong("total", 0);
                                    used = quota.optLong("quota", quota.optLong("used", 0));
                                    break;
                                } catch (Throwable failure) {
                                    AppLog.i(
                                            "BaiduDrive",
                                            "quota try="
                                                    + quotaCandidate[1]
                                                    + " error="
                                                    + DriveApi.friendly(failure));
                                }
                            }
                            status = new QuarkDrive.Status(true, account, total, used, "");
                            a.prefs.edit()
                                    .putBoolean(Prefs.BAIDU_VERIFIED, true)
                                    .putString(Prefs.BAIDU_ACCOUNT, account)
                                    .putLong(Prefs.BAIDU_TOTAL, total)
                                    .putLong(Prefs.BAIDU_USED, used)
                                    .apply();
                            AppLog.i(
                                    "BaiduDrive",
                                    "verify result=ok account="
                                            + account
                                            + " quota="
                                            + (total > 0));
                        } else {
                            status = new QuarkDrive.Status(false, "", 0, 0, error);
                            a.prefs.edit().putBoolean(Prefs.BAIDU_VERIFIED, false).apply();
                            AppLog.i("BaiduDrive", "verify failed " + status.error);
                        }
                    } catch (Throwable errorThrown) {
                        status =
                                new QuarkDrive.Status(
                                        false, "", 0, 0, DriveApi.friendly(errorThrown));
                        a.prefs.edit().putBoolean(Prefs.BAIDU_VERIFIED, false).apply();
                        AppLog.i("BaiduDrive", "verify failed " + status.error);
                    }
                    final QuarkDrive.Status result = status;
                    a.ui.post(() -> cb.ready(result));
                });
    }

    static void menu(final MainActivity a, final QuarkDrive.Status status, final Runnable onChanged) {
        StringBuilder message = new StringBuilder("真实登录态：已登录（百度 API 验证通过）");
        if (status.total > 0)
            message.append("\n容量：")
                    .append(QuarkDrive.formatSize(status.used))
                    .append(" / ")
                    .append(QuarkDrive.formatSize(status.total));
        new AppDialog.Builder(a)
                .setTitle("我的百度网盘" + (status.account.isEmpty() ? "" : " · " + status.account))
                .setMessage(message.toString())
                .setItems(
                        new String[] {"查看我的文件", "重新验证登录状态", "退出登录"},
                        (d, index) -> {
                            if (index == 0) listFiles(a);
                            else if (index == 1)
                                verifyAsync(
                                        a,
                                        next -> {
                                            a.toast(next.logged ? "验证通过" : "验证失败：" + next.error);
                                            if (onChanged != null) onChanged.run();
                                        });
                            else logout(a, onChanged);
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    static void logout(final MainActivity a, final Runnable onChanged) {
        new AppDialog.Builder(a)
                .setTitle("退出百度登录？")
                .setMessage("只清除本 App 保存的百度 Cookie 与账号缓存，不影响百度 App 与配置源插件。")
                .setPositiveButton(
                        "退出登录",
                        (d, i) -> {
                            deviceLogout(a);
                            try {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                                android.webkit.CookieManager.getInstance().flush();
                            } catch (Throwable ignored) {
                            }
                            a.toast("已退出百度登录");
                            if (onChanged != null) onChanged.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    static void listFiles(final MainActivity a) {
        a.toast("正在读取百度网盘文件…");
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    List<QuarkDrive.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        String bdstoken = stoken(a);
                        if (bdstoken.isEmpty()) bdstoken = fetchBdstokenBlocking(a, cookie);
                        String url =
                                API
                                        + "/api/list?dir=%2F&start=0&limit=50&order=time&desc=1"
                                        + "&web=1&app_id=250528&bdstoken="
                                        + bdstoken;
                        String body =
                                DriveApi.get(
                                        url,
                                        DriveApi.headers(
                                                "Cookie", cookie,
                                                "Referer", LOGIN_URL,
                                                "User-Agent", PanDriveLogin.DESKTOP_UA));
                        files = parseList(body);
                        AppLog.i("BaiduDrive", "list host=pan.baidu.com items=" + files.size());
                    } catch (Throwable failure) {
                        error = DriveApi.friendly(failure);
                        AppLog.i("BaiduDrive", "list host=pan.baidu.com error=" + error);
                    }
                    final List<QuarkDrive.FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (!failure.isEmpty()) a.notice("读取百度文件失败：" + failure);
                                else
                                    PanDriveUi.showFiles(
                                            a,
                                            "我的百度网盘",
                                            "根目录前 50 项（原生直连 · 桌面 UA）",
                                            result);
                            });
                });
    }

    static List<QuarkDrive.FileItem> parseList(String body) throws Exception {
        List<QuarkDrive.FileItem> out = new java.util.ArrayList<>();
        JSONObject json = new JSONObject(body);
        int errno = json.optInt("errno", 0);
        if (errno != 0) throw new java.io.IOException("百度返回错误 errno=" + errno);
        JSONArray list = json.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("server_filename", item.optString("path", ""));
            long size = item.optLong("size", 0);
            String fsId = String.valueOf(item.optLong("fs_id", 0));
            boolean folder = item.optInt("isdir", 0) == 1;
            out.add(new QuarkDrive.FileItem(name, fsId, size, folder));
        }
        return out;
    }
}
