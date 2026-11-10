package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 夸克网盘原生直连（Phase 1，完全独立于配置源插件，不读插件存储）。
 *
 * <p>流程：App 内 WebView 打开 pan.quark.cn → 用户登录（夸克页面自行处理）→ 捕获
 * 系统 CookieManager 里的 {@code __puus}/{@code __pus}/{@code kps} → 存到本 App prefs；
 * 再用该 Cookie 调夸克官方 API 验证真实登录态（昵称/容量），并列出根目录文件。
 *
 * <p>约束：所有网络在 worker 线程；cookie 只存本机 prefs，不写日志；失败时提示回夸克页面登录。
 */
final class QuarkDrive {
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String API = "https://drive-h.quark.cn";
    static final String LOGIN_URL = "https://pan.quark.cn/";
    static final String[] LOGIN_COOKIE_NAMES = {"__puus", "__pus", "kps"};
    /** 只有这些 Cookie 才算「登录成功」（__pus 可能是匿名辅助，不作为捕获条件）。 */
    static final String[] CAPTURE_COOKIE_NAMES = {"__puus", "kps"};

    interface Callback<T> {
        void ready(T value);
    }

    static final class Status {
        final boolean logged;
        final String account;
        final long total, used;
        final String error;

        Status(boolean logged, String account, long total, long used, String error) {
            this.logged = logged;
            this.account = account == null ? "" : account;
            this.total = total;
            this.used = used;
            this.error = error == null ? "" : error;
        }
    }

    static final class FileItem {
        final String name, fid;
        final long size;
        final boolean folder, playable;

        FileItem(String name, String fid, long size, boolean folder) {
            this.name = name == null ? "" : name;
            this.fid = fid == null ? "" : fid;
            this.size = size;
            this.folder = folder;
            this.playable = !folder && isVideoName(this.name);
        }
    }

    private QuarkDrive() {}

    static boolean isVideoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".avi")
                || lower.endsWith(".ts")
                || lower.endsWith(".m3u8")
                || lower.endsWith(".flv")
                || lower.endsWith(".rmvb");
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824f);
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        return bytes + " B";
    }

    // ---------------- 本地状态 ----------------

    static boolean hasCookie(MainActivity a) {
        return a != null && a.prefs.getString(Prefs.QUARK_COOKIE, "").contains("=");
    }

    static String cookie(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.QUARK_COOKIE, "");
    }

    static String cachedAccount(MainActivity a) {
        return a == null ? "" : a.prefs.getString(Prefs.QUARK_ACCOUNT, "");
    }

    static long cachedTotal(MainActivity a) {
        return a == null ? 0 : a.prefs.getLong(Prefs.QUARK_TOTAL, 0);
    }

    static long cachedUsed(MainActivity a) {
        return a == null ? 0 : a.prefs.getLong(Prefs.QUARK_USED, 0);
    }

    static Status cached(MainActivity a) {
        boolean logged = hasCookie(a);
        return new Status(logged, cachedAccount(a), cachedTotal(a), cachedUsed(a), "");
    }

    static void clear(MainActivity a) {
        a.prefs.edit()
                .remove(Prefs.QUARK_COOKIE)
                .remove(Prefs.QUARK_ACCOUNT)
                .remove(Prefs.QUARK_TOTAL)
                .remove(Prefs.QUARK_USED)
                .apply();
    }

    // ---------------- 登录（App 内 WebView） ----------------

    /**
     * 打开夸克登录页；登录成功后保存 Cookie 并验证真实登录态。
     * 不使用插件、不读写插件存储；失败时提示用户在夸克页面完成登录后再试。
     */
    /** 只保留登录二维码：隐藏页面头/导航/文案，白底居中放大二维码。 */
    private static final String QR_JS =
            "(function(){try{"
                    + "var cands=document.querySelectorAll('canvas,img,svg');var qr=null;"
                    + "for(var i=0;i<cands.length;i++){var el=cands[i];var r=el.getBoundingClientRect();"
                    + "if(r.width>=110&&r.height>=110){qr=el;break;}}"
                    + "if(!qr){var sels=['[class*=qrcode]','[id*=qrcode]','[class*=qr]','[id*=qr]'];"
                    + "for(var s=0;s<sels.length&&!qr;s++){var ns=document.querySelectorAll(sels[s]);"
                    + "for(var j=0;j<ns.length;j++){var b=ns[j].getBoundingClientRect();"
                    + "if(b.width>=90&&b.height>=90){qr=ns[j];break;}}}}"
                    + "if(!qr)return 'false';"
                    + "var old=qr;document.body.innerHTML='';"
                    + "document.body.style.background='#ffffff';document.documentElement.style.background='#ffffff';"
                    + "var wrap=document.createElement('div');"
                    + "wrap.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;display:flex;"
                    + "flex-direction:column;align-items:center;justify-content:center;background:#fff;';"
                    + "old.style.width='260px';old.style.height='260px';old.style.maxWidth='78vw';"
                    + "old.style.maxHeight='78vw';old.style.display='block';"
                    + "wrap.appendChild(old);"
                    + "var tip=document.createElement('div');tip.textContent='用夸克 App 扫码登录';"
                    + "tip.style.cssText='margin-top:16px;font:15px sans-serif;color:#333;';"
                    + "wrap.appendChild(tip);document.body.appendChild(wrap);return 'true';"
                    + "}catch(e){return 'false';}})()";

    /** 注入二维码精简逻辑；找不到容器时最多重试 7 次，然后降级原页面并提示。 */
    static void injectQr(
            final WebView web,
            final MainActivity a,
            final int attempt,
            final boolean[] found,
            final boolean[] warned) {
        if (web == null || found[0]) return;
        if (attempt > 6) {
            if (!warned[0]) {
                warned[0] = true;
                a.toast("未找到二维码容器，请在夸克页面完成登录");
            }
            return;
        }
        try {
            web.evaluateJavascript(
                    QR_JS,
                    value -> {
                        boolean ok = value != null && value.contains("true");
                        if (ok) found[0] = true;
                        else
                            a.ui.postDelayed(
                                    () -> injectQr(web, a, attempt + 1, found, warned), 700);
                    });
        } catch (Throwable error) {
            a.ui.postDelayed(() -> injectQr(web, a, attempt + 1, found, warned), 800);
        }
    }

    static void login(final MainActivity a, final Runnable onChanged) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = a.vertical();
        page.setBackgroundColor(MainActivity.DARK);
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(8));
        TextView title = a.text("夸克扫码登录", UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(
                a.button("关闭", "quark-login-close", dialog::dismiss),
                new LinearLayout.LayoutParams(-2, a.dp(40)));
        page.addView(header);
        TextView hint =
                a.text(
                        "用夸克 App 扫描下方二维码；登录成功后 App 会自动保存并验证账号。"
                                + "完全独立于配置源插件，不读取插件里的数据。",
                        UiTokens.TYPE_MICRO,
                        UiTokens.TEXT_TERTIARY);
        hint.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        page.addView(hint);

        final WebView web = new WebView(a);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        final boolean[] qrFound = {false};
        final boolean[] qrWarned = {false};
        web.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        String scheme = Uri.parse(url).getScheme();
                        return !"https".equals(scheme) && !"http".equals(scheme);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        // 只保留二维码（找不到时降级为原页面并提示）
                        qrFound[0] = false;
                        qrWarned[0] = false;
                        injectQr(view, a, 0, qrFound, qrWarned);
                    }
                });
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        dialog.setContentView(page);
        dialog.setOnDismissListener(
                d -> {
                    web.stopLoading();
                    web.destroy();
                });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1, -1);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        web.loadUrl(LOGIN_URL);

        final boolean[] active = {true};
        final Runnable[] poll = new Runnable[1];
        poll[0] =
                () -> {
                    if (!active[0] || !dialog.isShowing()) return;
                    String cookie = readQuarkCookie();
                    if (!cookie.isEmpty()) {
                        active[0] = false;
                        saveCookie(a, cookie);
                        dialog.dismiss();
                        a.toast("已捕获夸克登录信息，正在验证账号…");
                        verifyAsync(
                                a,
                                status -> {
                                    if (status.logged) {
                                        a.toast(
                                                "夸克登录成功"
                                                        + (status.account.isEmpty()
                                                                ? ""
                                                                : "：" + status.account));
                                    } else {
                                        a.notice(
                                                "已保存登录信息，但验证失败（"
                                                        + status.error
                                                        + "）。请重新打开夸克页面完成登录。");
                                    }
                                    if (onChanged != null) onChanged.run();
                                });
                        return;
                    }
                    a.ui.postDelayed(poll[0], 1500);
                };
        a.ui.postDelayed(poll[0], 1500);
    }

    /** 从系统 CookieManager 读夸克登录 Cookie（含 __puus/__pus/kps 才认）。 */
    static String readQuarkCookie() {
        try {
            CookieManager manager = CookieManager.getInstance();
            String best = "";
            for (String url : new String[] {LOGIN_URL, API + "/"}) {
                String cookie = manager.getCookie(url);
                if (cookie == null || cookie.trim().isEmpty()) continue;
                if (best.isEmpty()) best = cookie;
                String lower = cookie.toLowerCase(Locale.ROOT);
                for (String name : CAPTURE_COOKIE_NAMES) {
                    if (lower.contains(name + "=")) return cookie;
                }
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void saveCookie(MainActivity a, String cookie) {
        a.prefs.edit().putString(Prefs.QUARK_COOKIE, cookie == null ? "" : cookie).apply();
    }

    // ---------------- 真实状态验证（夸克 API） ----------------

    static void verifyAsync(final MainActivity a, final Callback<Status> callback) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    Status status;
                    try {
                        if (cookie.isEmpty()) {
                            status = new Status(false, "", 0, 0, "没有保存的 Cookie");
                        } else {
                            String body =
                                    get(
                                            API
                                                    + "/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=",
                                            cookie);
                            status = parseMember(body);
                            if (status.logged)
                                a.prefs.edit()
                                        .putString(Prefs.QUARK_ACCOUNT, status.account)
                                        .putLong(Prefs.QUARK_TOTAL, status.total)
                                        .putLong(Prefs.QUARK_USED, status.used)
                                        .apply();
                        }
                    } catch (Throwable error) {
                        status = new Status(false, "", 0, 0, friendly(error));
                    }
                    final Status result = status;
                    a.ui.post(() -> callback.ready(result));
                });
    }

    static Status parseMember(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        int status = json.optInt("status", 200);
        int code = json.optInt("code", 0);
        JSONObject data = json.optJSONObject("data");
        if (status != 200 || code != 0 || data == null)
            return new Status(
                    false,
                    "",
                    0,
                    0,
                    "HTTP " + status + " / code " + code + (json.has("message") ? "：" + json.optString("message") : ""));
        String account = data.optString("nickname", data.optString("nick_name", ""));
        if (account.isEmpty()) account = data.optString("account", "");
        long total = data.optLong("total_capacity", data.optLong("totalCapacity", 0));
        long used = data.optLong("use_capacity", data.optLong("useCapacity", 0));
        return new Status(true, account, total, used, "");
    }

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : "：" + message);
    }

    // ---------------- 文件列表 ----------------

    static void filesAsync(final MainActivity a, final Callback<List<FileItem>> callback) {
        final String cookie = cookie(a);
        a.worker.execute(
                () -> {
                    List<FileItem> files = new ArrayList<>();
                    String error = "";
                    try {
                        if (cookie.isEmpty()) throw new java.io.IOException("没有保存的 Cookie");
                        String body =
                                get(
                                        API
                                                + "/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str="
                                                + "&pdir_fid=0&_page=1&_size=50&_fetch_total=1"
                                                + "&_sort=file_type:asc,updated_at:desc",
                                        cookie);
                        files = parseFiles(body);
                    } catch (Throwable failure) {
                        error = friendly(failure);
                    }
                    final List<FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (!failure.isEmpty()) a.notice("读取夸克文件失败：" + failure);
                                else callback.ready(result);
                            });
                });
    }

    static List<FileItem> parseFiles(String body) throws Exception {
        List<FileItem> out = new ArrayList<>();
        JSONObject json = new JSONObject(body);
        if (json.optInt("status", 200) != 200 || json.optInt("code", 0) != 0)
            throw new java.io.IOException("夸克返回错误 code=" + json.optInt("code", -1));
        JSONObject data = json.optJSONObject("data");
        if (data == null) return out;
        JSONArray list = data.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("file_name", item.optString("name", ""));
            String fid = item.optString("fid", "");
            long size = item.optLong("size", 0);
            boolean folder = item.optInt("file_type", 1) == 0 || item.optBoolean("dir", false);
            out.add(new FileItem(name, fid, size, folder));
        }
        return out;
    }

    // ---------------- UI：管理面板 / 文件列表 ----------------

    /** 网盘行的原生入口：已登录 → 管理面板（验证/文件/退出）；未登录 → App 内登录。 */
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
                                .setTitle("夸克登录已失效")
                                .setMessage(
                                        "验证失败："
                                                + status.error
                                                + "\n\n请重新打开夸克页面完成登录。")
                                .setPositiveButton("重新登录", (d, i) -> login(a, onChanged))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    showMenu(a, status, onChanged);
                });
    }

    static void showMenu(final MainActivity a, final Status status, final Runnable onChanged) {
        String account = status.account.isEmpty() ? "夸克网盘" : status.account;
        StringBuilder message = new StringBuilder("真实登录态：已登录（夸克 API 验证通过）");
        if (status.total > 0)
            message.append("\n容量：")
                    .append(formatSize(status.used))
                    .append(" / ")
                    .append(formatSize(status.total));
        new AppDialog.Builder(a)
                .setTitle("我的夸克网盘 · " + account)
                .setMessage(message.toString())
                .setItems(
                        new String[] {"查看我的文件", "重新验证登录状态", "退出登录"},
                        (d, index) -> {
                            if (index == 0) listFiles(a, onChanged);
                            else if (index == 1)
                                verifyAsync(
                                        a,
                                        next -> {
                                            if (next.logged) {
                                                a.toast(
                                                        "验证通过"
                                                                + (next.account.isEmpty()
                                                                        ? ""
                                                                        : "：" + next.account));
                                                if (onChanged != null) onChanged.run();
                                            } else {
                                                a.notice("验证失败：" + next.error);
                                                if (onChanged != null) onChanged.run();
                                            }
                                        });
                            else logout(a, onChanged);
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    static void logout(final MainActivity a, final Runnable onChanged) {
        new AppDialog.Builder(a)
                .setTitle("退出夸克登录？")
                .setMessage("只清除本 App 保存的夸克 Cookie 与账号缓存，不影响夸克 App 与配置源插件。")
                .setPositiveButton(
                        "退出登录",
                        (d, i) ->
                                deviceLogout(
                                        a,
                                        () -> {
                                            a.toast("已退出夸克登录");
                                            if (onChanged != null) onChanged.run();
                                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 同步清除本机 prefs + 系统 WebView Cookie（异步 removeAllCookies 后 flush），并记日志。 */
    static void deviceLogout(final MainActivity a, final Runnable done) {
        clear(a);
        boolean webViewCleared = false;
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.removeAllCookies(value -> {});
            manager.removeSessionCookies(value -> {});
            manager.flush();
            webViewCleared = true;
        } catch (Throwable ignored) {
        }
        AppLog.i(
                "Quark",
                "logout cleared cookie="
                        + hasCookie(a)
                        + " account="
                        + cachedAccount(a).isEmpty()
                        + " webviewCookies="
                        + webViewCleared);
        if (done != null) a.ui.post(done);
    }

    static void clearWebViewCookies() {
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String name : LOGIN_COOKIE_NAMES) {
                manager.setCookie(LOGIN_URL, name + "=; Max-Age=0; Path=/");
                manager.setCookie(API + "/", name + "=; Max-Age=0; Path=/");
            }
            manager.flush();
        } catch (Throwable ignored) {
        }
    }

    static void listFiles(final MainActivity a, final Runnable onChanged) {
        a.toast("正在读取夸克网盘文件…");
        filesAsync(
                a,
                files -> {
                    LinearLayout box = a.vertical();
                    TextView capacity =
                            a.text(
                                    "容量："
                                            + formatSize(cachedUsed(a))
                                            + " / "
                                            + formatSize(cachedTotal(a))
                                            + " · 共 "
                                            + files.size()
                                            + " 项（根目录前 50 项）",
                                    12,
                                    MainActivity.MUTED);
                    box.addView(capacity);
                    a.gap(box, 6);
                    if (files.isEmpty()) {
                        box.addView(a.text("网盘根目录为空。", 14, MainActivity.MUTED));
                    }
                    for (FileItem file : files) {
                        if (file.folder) {
                            TextView row =
                                    a.text(
                                            "📁  " + file.name,
                                            14,
                                            UiTokens.TEXT_SECONDARY);
                            row.setPadding(0, a.dp(6), 0, a.dp(6));
                            box.addView(row);
                            continue;
                        }
                        TextView meta =
                                a.text(
                                        "📄  " + file.name,
                                        14,
                                        file.playable
                                                ? UiTokens.TEXT_PRIMARY
                                                : UiTokens.TEXT_SECONDARY);
                        meta.setPadding(0, a.dp(6), 0, 0);
                        box.addView(meta);
                        TextView size = a.text(formatSize(file.size), 11, MainActivity.MUTED);
                        size.setPadding(0, 0, 0, a.dp(6));
                        box.addView(size);
                        if (file.playable) {
                            Button play =
                                    a.button(
                                            "▶ 播放（Phase 2 接入解析）",
                                            "quark-play:" + file.fid,
                                            () ->
                                                    a.notice(
                                                            "播放/下载解析将在 Phase 2 接入（本阶段只验证登录与列表）。"));
                            play.setTextSize(12);
                            LinearLayout.LayoutParams playLp =
                                    new LinearLayout.LayoutParams(-1, a.dp(40));
                            playLp.bottomMargin = a.dp(8);
                            box.addView(play, playLp);
                        }
                    }
                    new AppDialog.Builder(a)
                            .setTitle("我的夸克网盘")
                            .setView(box)
                            .setNegativeButton("关闭", null)
                            .show();
                });
    }

    // ---------------- HTTP ----------------

    static String get(String url, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", LOGIN_URL);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (cookie != null && !cookie.isEmpty())
                connection.setRequestProperty("Cookie", cookie);
            int status = connection.getResponseCode();
            InputStream input =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();
            String body = read(input);
            if (status < 200 || status >= 300)
                throw new java.io.IOException("HTTP " + status + " " + body);
            return body;
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
