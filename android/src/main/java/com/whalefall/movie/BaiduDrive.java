package com.whalefall.movie;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 百度网盘原生直连（Phase 4，独立于配置源插件；Cookie: BDUSS/STOKEN + bdstoken）。 */
final class BaiduDrive {
    static final String API = "https://pan.baidu.com";
    static final String LOGIN_URL = "https://pan.baidu.com/";

    private BaiduDrive() {}

    static boolean hasCookie(MainActivity a) {
        String cookie = a == null ? "" : a.prefs.getString(Prefs.BAIDU_COOKIE, "");
        return cookie.contains("BDUSS=") || cookie.contains("STOKEN=");
    }

    static String cookie(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.BAIDU_COOKIE, "");
    }

    static String cachedAccount(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.BAIDU_ACCOUNT, "");
    }

    static String stoken(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.BAIDU_STOKEN, "");
    }

    static void deviceLogout(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.BAIDU_COOKIE)
                .remove(Prefs.BAIDU_STOKEN)
                .remove(Prefs.BAIDU_ACCOUNT)
                .remove(Prefs.BAIDU_TOTAL)
                .remove(Prefs.BAIDU_USED)
                .apply();
        AppLog.i("BaiduDrive", "logout cleared cookie=false account=true");
    }

    static void showPanel(final MainActivity a, final Runnable onChanged) {
        if (!hasCookie(a)) {
            PanDriveLogin.show(
                    a,
                    "百度网盘扫码登录",
                    "用百度网盘 App 扫码登录；成功后 App 会验证真实账号与容量。",
                    LOGIN_URL,
                    new String[] {"BDUSS", "STOKEN"},
                    null,
                    value -> {
                        a.prefs.edit().putString(Prefs.BAIDU_COOKIE, value).apply();
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
                                                                "百度登录验证失败：" + status.error);
                                                    if (onChanged != null) onChanged.run();
                                                }));
                    });
            return;
        }
        verifyAsync(
                a,
                status -> {
                    if (!status.logged) {
                        new AppDialog.Builder(a)
                                .setTitle("百度登录已失效")
                                .setMessage("验证失败：" + status.error + "\n\n请重新扫码登录。")
                                .setPositiveButton(
                                        "重新登录",
                                        (d, i) -> {
                                            PanLoginState.invalidate();
                                            showPanel(a, onChanged);
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    menu(a, status, onChanged);
                });
    }

    /** 取 bdstoken（列表接口需要）；失败不阻断登录状态，列表时再报错。 */
    static void fetchBdstokenAsync(final MainActivity a, final Runnable done) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    try {
                        String body =
                                DriveApi.get(
                                        API
                                                + "/api/gettemplatevariable?fields=%5B%22bdstoken%22%5D&channel=chunlei&clienttype=0&web=1",
                                        DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                        JSONObject json = new JSONObject(body);
                        JSONObject result = json.optJSONObject("result");
                        String token = result == null ? json.optString("bdstoken", "") : result.optString("bdstoken", "");
                        if (!token.isEmpty()) a.prefs.edit().putString(Prefs.BAIDU_STOKEN, token).apply();
                        AppLog.i("BaiduDrive", "bdstoken fetched=" + !token.isEmpty());
                    } catch (Throwable error) {
                        AppLog.i("BaiduDrive", "bdstoken failed " + DriveApi.friendly(error));
                    } finally {
                        a.ui.post(done);
                    }
                });
    }

    static void verifyAsync(final MainActivity a, final QuarkDrive.Callback<QuarkDrive.Status> cb) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    QuarkDrive.Status status;
                    try {
                        String infoBody =
                                DriveApi.get(
                                        API
                                                + "/api/user/getinfo?channel=chunlei&clienttype=0&web=1",
                                        DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                        JSONObject info = new JSONObject(infoBody);
                        if (info.optInt("errno", -1) != 0)
                            throw new java.io.IOException(
                                    "getinfo errno=" + info.optInt("errno", -1));
                        JSONObject data = info.optJSONObject("data");
                        String account =
                                data == null
                                        ? ""
                                        : data.optString(
                                                "username",
                                                data.optString("user_name", data.optString("display_name", "")));
                        long total = 0, used = 0;
                        try {
                            String quotaBody =
                                    DriveApi.get(
                                            API
                                                    + "/api/quota?checkfree=1&checkexpire=1&channel=chunlei&clienttype=0&web=1",
                                            DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                            JSONObject quota = new JSONObject(quotaBody);
                            total = quota.optLong("total", 0);
                            used = quota.optLong("quota", quota.optLong("used", 0));
                        } catch (Throwable ignored) {
                        }
                        status = new QuarkDrive.Status(true, account, total, used, "");
                        a.prefs.edit()
                                .putString(Prefs.BAIDU_ACCOUNT, account)
                                .putLong(Prefs.BAIDU_TOTAL, total)
                                .putLong(Prefs.BAIDU_USED, used)
                                .apply();
                    } catch (Throwable error) {
                        status = new QuarkDrive.Status(false, "", 0, 0, DriveApi.friendly(error));
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
        final String bdstoken = stoken(a);
        a.worker.execute(
                () -> {
                    List<QuarkDrive.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        String body =
                                DriveApi.get(
                                        API
                                                + "/api/list?dir=%2F&start=0&limit=50&order=time&desc=1&web=1&bdstoken="
                                                + bdstoken,
                                        DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                        files = parseList(body);
                    } catch (Throwable failure) {
                        error = DriveApi.friendly(failure);
                        AppLog.i("BaiduDrive", "list failed " + error);
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
                                            "根目录前 50 项（原生直连）",
                                            result);
                            });
                });
    }

    static List<QuarkDrive.FileItem> parseList(String body) throws Exception {
        List<QuarkDrive.FileItem> out = new java.util.ArrayList<>();
        JSONObject json = new JSONObject(body);
        if (json.optInt("errno", 0) != 0)
            throw new java.io.IOException("百度返回错误 errno=" + json.optInt("errno", -1));
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
