package com.whalefall.movie;

import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** UC 网盘原生直连（Phase 4，与夸克同源接口结构；独立于配置源插件）。 */
final class UcDrive {
    static final String API = "https://drive-h.uc.cn";
    static final String LOGIN_URL = "https://drive.uc.cn/";

    private UcDrive() {}

    static boolean hasCookie(MainActivity a) {
        return a != null && a.prefs.getString(Prefs.UC_COOKIE, "").contains("=");
    }

    static String cookie(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.UC_COOKIE, "");
    }

    static String cachedAccount(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.UC_ACCOUNT, "");
    }

    static void deviceLogout(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.UC_COOKIE)
                .remove(Prefs.UC_ACCOUNT)
                .remove(Prefs.UC_TOTAL)
                .remove(Prefs.UC_USED)
                .apply();
        AppLog.i("UcDrive", "logout cleared cookie=false account=true");
    }

    static void showPanel(final MainActivity a, final Runnable onChanged) {
        if (!hasCookie(a)) {
            PanDriveLogin.show(
                    a,
                    "UC 扫码登录",
                    "用 UC 浏览器 / UC 网盘 App 扫码登录，成功后 App 会验证真实账号。",
                    LOGIN_URL,
                    new String[] {"__puus", "kps", "_uc_s"},
                    null,
                    value -> {
                        a.prefs.edit().putString(Prefs.UC_COOKIE, value).apply();
                        verifyAsync(
                                a,
                                status -> {
                                    if (status.logged) {
                                        a.toast(
                                                "UC 登录成功"
                                                        + (status.account.isEmpty()
                                                                ? ""
                                                                : "：" + status.account));
                                    } else {
                                        a.notice("UC 登录验证失败：" + status.error);
                                    }
                                    if (onChanged != null) onChanged.run();
                                });
                    });
            return;
        }
        verifyAsync(
                a,
                status -> {
                    if (!status.logged) {
                        new AppDialog.Builder(a)
                                .setTitle("UC 登录已失效")
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

    static void verifyAsync(final MainActivity a, final QuarkDrive.Callback<QuarkDrive.Status> cb) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    QuarkDrive.Status status;
                    try {
                        if (cookie.isEmpty()) status = new QuarkDrive.Status(false, "", 0, 0, "没有保存的 Cookie");
                        else {
                            String body =
                                    DriveApi.get(
                                            API
                                                    + "/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=",
                                            DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                            status = QuarkDrive.parseMember(body);
                            if (status.logged)
                                a.prefs.edit()
                                        .putString(Prefs.UC_ACCOUNT, status.account)
                                        .putLong(Prefs.UC_TOTAL, status.total)
                                        .putLong(Prefs.UC_USED, status.used)
                                        .apply();
                        }
                    } catch (Throwable error) {
                        status = new QuarkDrive.Status(false, "", 0, 0, DriveApi.friendly(error));
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
        a.worker.execute(
                () -> {
                    List<QuarkDrive.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        String body =
                                DriveApi.get(
                                        API
                                                + "/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str="
                                                + "&pdir_fid=0&_page=1&_size=50&_fetch_total=1"
                                                + "&_sort=file_type:asc,updated_at:desc",
                                        DriveApi.headers("Cookie", cookie, "Referer", LOGIN_URL));
                        files = QuarkDrive.parseFiles(body);
                    } catch (Throwable failure) {
                        error = DriveApi.friendly(failure);
                        AppLog.i("UcDrive", "list failed " + error);
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
                                            "根目录前 50 项（原生直连）",
                                            result);
                            });
                });
    }
}
