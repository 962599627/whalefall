package com.whalefall.movie;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 阿里云盘原生直连（Phase 4，登录态在 localStorage.token，独立于插件）。 */
final class AliDrive {
    static final String API = "https://api.alipan.com";
    static final String LOGIN_URL = "https://www.alipan.com/";
    static final String DEVICE_ID = "whalefall-pan-0001";
    static final String TOKEN_JS = "(function(){try{return localStorage.getItem('token')||'';}catch(e){return '';}})()";

    private AliDrive() {}

    static boolean hasToken(MainActivity a) {
        return a != null && !a.prefs.getString(Prefs.ALI_TOKEN, "").trim().isEmpty();
    }

    static String rawToken(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.ALI_TOKEN, "");
    }

    static String accessToken(MainActivity a) {
        String raw = rawToken(a).trim();
        if (!raw.startsWith("{")) return raw;
        try {
            JSONObject json = new JSONObject(raw);
            String token = json.optString("access_token", json.optString("accessToken", ""));
            if (!token.isEmpty()) return token;
        } catch (Throwable ignored) {
        }
        return "";
    }

    static String refreshToken(MainActivity a) {
        String raw = rawToken(a).trim();
        if (!raw.startsWith("{")) return "";
        try {
            return new JSONObject(raw).optString("refresh_token", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String cachedAccount(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.ALI_ACCOUNT, "");
    }

    static String cachedDriveId(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.ALI_DRIVE_ID, "root");
    }

    static void deviceLogout(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.ALI_TOKEN)
                .remove(Prefs.ALI_ACCOUNT)
                .remove(Prefs.ALI_DRIVE_ID)
                .remove(Prefs.ALI_TOTAL)
                .remove(Prefs.ALI_USED)
                .apply();
        AppLog.i("AliDrive", "logout cleared token=false account=true");
    }

    static void showPanel(final MainActivity a, final Runnable onChanged) {
        if (!hasToken(a)) {
            PanDriveLogin.show(
                    a,
                    "阿里云盘扫码登录",
                    "用阿里云盘 App 扫码登录；成功后 App 会验证真实账号与容量。",
                    LOGIN_URL,
                    new String[] {},
                    TOKEN_JS,
                    value -> {
                        a.prefs.edit().putString(Prefs.ALI_TOKEN, value).apply();
                        verifyAsync(
                                a,
                                status -> {
                                    if (status.logged)
                                        a.toast(
                                                "阿里登录成功"
                                                        + (status.account.isEmpty()
                                                                ? ""
                                                                : "：" + status.account));
                                    else
                                        a.notice("阿里登录验证失败：" + status.error);
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
                                .setTitle("阿里登录已失效")
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
        final String token = accessToken(a);
        a.worker.execute(
                () -> {
                    QuarkDrive.Status status;
                    try {
                        if (token.isEmpty()) throw new java.io.IOException("没有保存的 access_token");
                        String body =
                                DriveApi.get(
                                        API + "/adrive/v1.0/user/getDriveInfo",
                                        authHeaders(token));
                        JSONObject json = new JSONObject(body);
                        String account =
                                json.optString(
                                        "nick_name",
                                        json.optString(
                                                "user_name", json.optString("name", "")));
                        String driveId =
                                json.optString(
                                        "resource_drive_id",
                                        json.optString("default_drive_id", ""));
                        long total = json.optLong("total_size", 0);
                        long used = json.optLong("used_size", 0);
                        if (driveId.isEmpty()) throw new java.io.IOException("getDriveInfo 缺少 drive_id");
                        status = new QuarkDrive.Status(true, account, total, used, "");
                        a.prefs.edit()
                                .putString(Prefs.ALI_ACCOUNT, account)
                                .putString(Prefs.ALI_DRIVE_ID, driveId)
                                .putLong(Prefs.ALI_TOTAL, total)
                                .putLong(Prefs.ALI_USED, used)
                                .apply();
                    } catch (Throwable error) {
                        status = new QuarkDrive.Status(false, "", 0, 0, DriveApi.friendly(error));
                        AppLog.i("AliDrive", "verify failed " + status.error);
                    }
                    final QuarkDrive.Status result = status;
                    a.ui.post(() -> cb.ready(result));
                });
    }

    static java.util.Map<String, String> authHeaders(String token) {
        return DriveApi.headers(
                "Authorization", "Bearer " + token,
                "x-device-id", DEVICE_ID,
                "Referer", LOGIN_URL);
    }

    static void menu(final MainActivity a, final QuarkDrive.Status status, final Runnable onChanged) {
        StringBuilder message = new StringBuilder("真实登录态：已登录（阿里 API 验证通过）");
        if (status.total > 0)
            message.append("\n容量：")
                    .append(QuarkDrive.formatSize(status.used))
                    .append(" / ")
                    .append(QuarkDrive.formatSize(status.total));
        new AppDialog.Builder(a)
                .setTitle("我的阿里云盘" + (status.account.isEmpty() ? "" : " · " + status.account))
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
                .setTitle("退出阿里云盘登录？")
                .setMessage("只清除本 App 保存的阿里 token 与账号缓存，不影响阿里云盘 App 与配置源插件。")
                .setPositiveButton(
                        "退出登录",
                        (d, i) -> {
                            deviceLogout(a);
                            a.toast("已退出阿里云盘登录");
                            if (onChanged != null) onChanged.run();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    static void listFiles(final MainActivity a) {
        a.toast("正在读取阿里云盘文件…");
        final String token = accessToken(a);
        final String driveId = cachedDriveId(a);
        a.worker.execute(
                () -> {
                    List<QuarkDrive.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        if (token.isEmpty()) throw new java.io.IOException("没有保存的 access_token");
                        JSONObject body =
                                new JSONObject()
                                        .put("drive_id", driveId)
                                        .put("parent_file_id", "root")
                                        .put("limit", 50)
                                        .put("marker", "")
                                        .put("order_by", "updated_at desc")
                                        .put("fields", "*");
                        String response =
                                DriveApi.post(
                                        API + "/adrive/v1.0/file/list",
                                        authHeaders(token),
                                        body.toString());
                        files = parseFiles(response);
                    } catch (Throwable failure) {
                        error = DriveApi.friendly(failure);
                        AppLog.i("AliDrive", "list failed " + error);
                    }
                    final List<QuarkDrive.FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (!failure.isEmpty()) a.notice("读取阿里文件失败：" + failure);
                                else
                                    PanDriveUi.showFiles(
                                            a,
                                            "我的阿里云盘",
                                            "根目录前 50 项（原生直连）",
                                            result);
                            });
                });
    }

    static List<QuarkDrive.FileItem> parseFiles(String body) throws Exception {
        List<QuarkDrive.FileItem> out = new java.util.ArrayList<>();
        JSONObject json = new JSONObject(body);
        JSONArray items = json.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "");
            String fileId = item.optString("file_id", "");
            long size = item.optLong("size", 0);
            boolean folder = "folder".equals(item.optString("type", "")) || item.optBoolean("folder", false);
            out.add(new QuarkDrive.FileItem(name, fileId, size, folder));
        }
        return out;
    }
}
