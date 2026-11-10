package com.whalefall.movie;

import java.util.List;
import org.json.JSONObject;

/** UC 网盘原生直连（Phase 4；接口域名 pc-api.uc.cn，独立于配置源插件）。 */
final class UcDrive {
    static final String LOGIN_URL = "https://drive.uc.cn/";
    /** 候选 API：host + pr 参数；首个可用组合会写入 prefs 复用。 */
    static final String[][] CANDIDATES = {
        {"https://pc-api.uc.cn", "UCBrowser"},
        {"https://pc-api.uc.cn", "ucpro"},
        {"https://drive.uc.cn", "ucpro"},
    };

    private UcDrive() {}

    static boolean hasCookie(MainActivity a) {
        return a != null && a.prefs.getString(Prefs.UC_COOKIE, "").contains("=");
    }

    /** 必须通过 member API 验证才算已登录（避免保存 Cookie 即显示已登录）。 */
    static boolean isLoggedIn(MainActivity a) {
        return hasCookie(a) && a.prefs.getBoolean(Prefs.UC_VERIFIED, false);
    }

    static String cookie(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.UC_COOKIE, "");
    }

    static String cachedAccount(MainActivity a) {
        return isLoggedIn(a) ? a.prefs.getString(Prefs.UC_ACCOUNT, "") : "";
    }

    static String apiBase(MainActivity a) {
        String base = a.prefs.getString(Prefs.UC_API_BASE, "");
        return base.isEmpty() ? CANDIDATES[0][0] : base;
    }

    static String apiPr(MainActivity a) {
        String pr = a.prefs.getString(Prefs.UC_API_PR, "");
        return pr.isEmpty() ? CANDIDATES[0][1] : pr;
    }

    static void deviceLogout(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.UC_COOKIE)
                .remove(Prefs.UC_VERIFIED)
                .remove(Prefs.UC_ACCOUNT)
                .remove(Prefs.UC_TOTAL)
                .remove(Prefs.UC_USED)
                .apply();
        AppLog.i("UcDrive", "logout cleared cookie=false verified=false");
    }

    static void showPanel(final MainActivity a, final Runnable onChanged) {
        if (!hasCookie(a)) {
            login(a, onChanged);
            return;
        }
        verifyAsync(
                a,
                status -> {
                    if (!status.logged) {
                        new AppDialog.Builder(a)
                                .setTitle("UC 登录验证失败")
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

    static void login(final MainActivity a, final Runnable onChanged) {
        PanDriveLogin.Config config = new PanDriveLogin.Config();
        config.title = "UC 扫码登录";
        config.hint = "用 UC 浏览器 / UC 网盘 App 扫码登录；成功后 App 会调 UC API 验证真实账号。";
        config.loginUrl = LOGIN_URL;
        config.cookieNames = new String[] {"__puus", "kps", "_uc_s"};
        config.userAgent = PanDriveLogin.DESKTOP_UA;
        config.injectDelayMs = 1500;
        PanDriveLogin.show(
                a,
                config,
                value -> {
                    a.prefs.edit()
                            .putString(Prefs.UC_COOKIE, value)
                            .putBoolean(Prefs.UC_VERIFIED, false)
                            .apply();
                    verifyAsync(
                            a,
                            status -> {
                                if (status.logged)
                                    a.toast(
                                            "UC 登录成功"
                                                    + (status.account.isEmpty()
                                                            ? ""
                                                            : "：" + status.account));
                                else a.notice("登录信息已保存，但验证失败：" + status.error);
                                if (onChanged != null) onChanged.run();
                            });
                });
    }

    /** 依次尝试候选 host/pr，成功后记住可用组合并标记 verified。 */
    static void verifyAsync(final MainActivity a, final QuarkDrive.Callback<QuarkDrive.Status> cb) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    QuarkDrive.Status status = null;
                    String lastError = "没有保存的 Cookie";
                    if (!cookie.isEmpty()) {
                        for (String[] candidate : CANDIDATES) {
                            String base = candidate[0];
                            String pr = candidate[1];
                            try {
                                String body =
                                        DriveApi.get(
                                                base
                                                        + "/1/clouddrive/member?pr="
                                                        + pr
                                                        + "&fr=pc&uc_param_str=",
                                                DriveApi.headers(
                                                        "Cookie", cookie,
                                                        "Referer", LOGIN_URL,
                                                        "User-Agent", PanDriveLogin.DESKTOP_UA));
                                QuarkDrive.Status parsed = QuarkDrive.parseMember(body);
                                AppLog.i(
                                        "UcDrive",
                                        "verify host="
                                                + base
                                                + " pr="
                                                + pr
                                                + " result="
                                                + (parsed.logged ? "ok" : "api-fail")
                                                + (parsed.logged ? "" : " error=" + parsed.error));
                                if (parsed.logged) {
                                    status = parsed;
                                    a.prefs.edit()
                                            .putString(Prefs.UC_API_BASE, base)
                                            .putString(Prefs.UC_API_PR, pr)
                                            .putBoolean(Prefs.UC_VERIFIED, true)
                                            .putString(Prefs.UC_ACCOUNT, parsed.account)
                                            .putLong(Prefs.UC_TOTAL, parsed.total)
                                            .putLong(Prefs.UC_USED, parsed.used)
                                            .apply();
                                    break;
                                }
                                lastError = parsed.error;
                            } catch (Throwable error) {
                                lastError = DriveApi.friendly(error);
                                AppLog.i(
                                        "UcDrive",
                                        "verify host=" + base + " pr=" + pr + " error=" + lastError);
                            }
                        }
                    }
                    if (status == null) {
                        a.prefs.edit().putBoolean(Prefs.UC_VERIFIED, false).apply();
                        status = new QuarkDrive.Status(false, "", 0, 0, lastError);
                    }
                    final QuarkDrive.Status result = status;
                    a.ui.post(() -> cb.ready(result));
                });
    }

    static void menu(final MainActivity a, final QuarkDrive.Status status, final Runnable onChanged) {
        StringBuilder message = new StringBuilder("真实登录态：已登录（UC API 验证通过）");
        if (status.total > 0)
            message.append("\n容量：")
                    .append(QuarkDrive.formatSize(status.used))
                    .append(" / ")
                    .append(QuarkDrive.formatSize(status.total));
        new AppDialog.Builder(a)
                .setTitle("我的 UC 网盘" + (status.account.isEmpty() ? "" : " · " + status.account))
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
                .setTitle("退出 UC 登录？")
                .setMessage("只清除本 App 保存的 UC Cookie 与账号缓存，不影响 UC App 与配置源插件。")
                .setPositiveButton(
                        "退出登录",
                        (d, i) -> {
                            deviceLogout(a);
                            try {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                                android.webkit.CookieManager.getInstance().flush();
                            } catch (Throwable ignored) {
                            }
                            a.toast("已退出 UC 登录");
                            if (onChanged != null) onChanged.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    static void listFiles(final MainActivity a) {
        a.toast("正在读取 UC 网盘文件…");
        final String cookie = cookie(a);
        final String base = apiBase(a);
        final String pr = apiPr(a);
        a.worker.execute(
                () -> {
                    List<QuarkDrive.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        String body =
                                DriveApi.get(
                                        base
                                                + "/1/clouddrive/file/sort?pr="
                                                + pr
                                                + "&fr=pc&uc_param_str="
                                                + "&pdir_fid=0&_page=1&_size=50&_fetch_total=1"
                                                + "&_sort=file_type:asc,updated_at:desc",
                                        DriveApi.headers(
                                                "Cookie", cookie,
                                                "Referer", LOGIN_URL,
                                                "User-Agent", PanDriveLogin.DESKTOP_UA));
                        files = QuarkDrive.parseFiles(body);
                        AppLog.i("UcDrive", "list host=" + base + " pr=" + pr + " items=" + files.size());
                    } catch (Throwable failure) {
                        error = DriveApi.friendly(failure);
                        AppLog.i("UcDrive", "list host=" + base + " pr=" + pr + " error=" + error);
                    }
                    final List<QuarkDrive.FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (!failure.isEmpty()) a.notice("读取 UC 文件失败：" + failure);
                                else
                                    PanDriveUi.showFiles(
                                            a,
                                            "我的 UC 网盘",
                                            "根目录前 50 项（原生直连 · " + base + "）",
                                            result);
                            });
                });
    }
}
