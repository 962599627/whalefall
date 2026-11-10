package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * Quark/UC 登录：优先 App 内 WebView 桌面页扫码，捕获 Cookie（__puus/_uc_s/kps）；
 * 若页面被防爬/无二维码，提供「粘贴 Cookie」兜底（用户可从浏览器/官方 App 复制）。
 * Cookie 只存本机 prefs，不写日志。
 */
final class PanCookieLogin {
    private PanCookieLogin() {}

    static void show(final MainActivity a, final QuarkUcApi.Provider p, final Runnable onChanged) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = a.vertical();
        page.setBackgroundColor(MainActivity.DARK);
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(8));
        TextView title = a.text("登录" + p.name + "网盘", UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(
                a.button("关闭", "pan-cookie-close", dialog::dismiss),
                new LinearLayout.LayoutParams(-2, a.dp(40)));
        page.addView(header);
        TextView hint =
                a.text(
                        "用" + p.name + " App 扫码登录；若页面被限制，可点下方「粘贴 Cookie」"
                                + "（从浏览器或官方 App 获取）。Cookie 只保存在本机。",
                        UiTokens.TYPE_MICRO,
                        UiTokens.TEXT_TERTIARY);
        hint.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        page.addView(hint);
        LinearLayout actions = a.row();
        actions.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        actions.addView(
                a.button(
                        "粘贴 Cookie",
                        "pan-cookie-paste",
                        () -> {
                            dialog.dismiss();
                            pasteCookie(a, p, onChanged);
                        }),
                new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        page.addView(actions);

        final WebView web = new WebView(a);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setUserAgentString(PanCookieLogin.DESKTOP_UA);
        web.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        String scheme = Uri.parse(url).getScheme();
                        return !"https".equals(scheme) && !"http".equals(scheme);
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
        web.loadUrl(p.loginUrl);

        final boolean[] active = {true};
        final Runnable[] poll = new Runnable[1];
        poll[0] =
                () -> {
                    if (!active[0] || !dialog.isShowing()) return;
                    String cookie = readCookie(p.cookieNames);
                    if (!cookie.isEmpty()) {
                        active[0] = false;
                        dialog.dismiss();
                        saveAndVerify(a, p, cookie, onChanged);
                        return;
                    }
                    a.ui.postDelayed(poll[0], 1500);
                };
        a.ui.postDelayed(poll[0], 1500);
        AppLog.i("QuarkUc", "login open provider=" + p.key + " url=" + p.loginUrl + " desktopUA=true");
    }

    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static String readCookie(String[] names) {
        try {
            CookieManager manager = CookieManager.getInstance();
            String[] urls = {"https://pan.quark.cn/", "https://drive.uc.cn/", "https://pc-api.uc.cn/"};
            for (String url : urls) {
                String cookie = manager.getCookie(url);
                if (cookie == null || cookie.trim().isEmpty()) continue;
                String lower = cookie.toLowerCase(Locale.ROOT);
                for (String name : names)
                    if (lower.contains(name.toLowerCase(Locale.ROOT) + "=")) return cookie;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 粘贴 Cookie 兜底：单行输入框，校验含登录 Cookie 名后保存并验证。 */
    static void pasteCookie(
            final MainActivity a, final QuarkUcApi.Provider p, final Runnable onChanged) {
        final EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("__puus=...; 或 _uc_s=...; kps=...;");
        input.setHintTextColor(MainActivity.MUTED);
        input.setTextColor(MainActivity.WHITE);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AppDialog.Builder(a)
                .setTitle("粘贴 " + p.name + " Cookie")
                .setMessage("在浏览器登录同名网盘后，从开发者工具/扩展复制 Cookie 字符串粘贴到这里。")
                .setView(input)
                .setPositiveButton(
                        "保存并验证",
                        (d, i) -> {
                            String cookie = input.getText().toString().trim();
                            String lower = cookie.toLowerCase(Locale.ROOT);
                            boolean ok = false;
                            for (String name : p.cookieNames)
                                if (lower.contains(name.toLowerCase(Locale.ROOT) + "=")) ok = true;
                            if (!ok) {
                                StringBuilder names = new StringBuilder();
                                for (String name : p.cookieNames) {
                                    if (names.length() > 0) names.append('/');
                                    names.append(name);
                                }
                                a.toast("Cookie 里需要包含 " + names);
                                return;
                            }
                            saveAndVerify(a, p, cookie, onChanged);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    static void saveAndVerify(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            String cookie,
            final Runnable onChanged) {
        QuarkUcApi.saveCookie(a, p, cookie);
        a.toast("已保存登录信息，正在验证…");
        QuarkUcApi.verifyAsync(
                a,
                p,
                status -> {
                    if (status.logged)
                        a.toast(
                                p.name
                                        + "登录成功"
                                        + (status.total > 0
                                                ? "（容量 "
                                                        + QuarkUcApi.formatSize(status.used)
                                                        + " / "
                                                        + QuarkUcApi.formatSize(status.total)
                                                        + "）"
                                                : ""));
                    else a.notice("登录信息已保存，但验证失败：" + status.error);
                    if (onChanged != null) onChanged.run();
                });
    }
}
