package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * 通用「网盘扫码登录」弹窗：深色外壳 + 白底二维码（只保留二维码，隐藏网页其余部分），
 * 登录成功后通过 Cookie 名或页面 JS token 捕获登录态，回调给各原生网盘模块。
 */
final class PanDriveLogin {
    interface Success {
        void onValue(String cookieOrToken);
    }

    private PanDriveLogin() {}

    /** 只保留登录二维码的注入脚本（与 QuarkDrive 相同策略）。 */
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
                    + "var tip=document.createElement('div');tip.textContent='请使用对应网盘 App 扫码登录';"
                    + "tip.style.cssText='margin-top:16px;font:15px sans-serif;color:#333;';"
                    + "wrap.appendChild(tip);document.body.appendChild(wrap);return 'true';"
                    + "}catch(e){return 'false';}})()";

    /**
     * @param cookieNames 登录成功的 Cookie 名（任一出现即视为登录）
     * @param tokenScript 可选的 JS 表达式（如 localStorage.getItem('token')），返回 JSON 字符串；
     *                    非空时每次轮询执行，值非空即捕获
     */
    static void show(
            final MainActivity a,
            final String title,
            final String hint,
            final String loginUrl,
            final String[] cookieNames,
            final String tokenScript,
            final Success success) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = a.vertical();
        page.setBackgroundColor(MainActivity.DARK);
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(8));
        TextView heading = a.text(title, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(
                a.button("关闭", "pan-login-close", dialog::dismiss),
                new LinearLayout.LayoutParams(-2, a.dp(40)));
        page.addView(header);
        TextView tip = a.text(hint, UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
        tip.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        page.addView(tip);

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
        web.loadUrl(loginUrl);

        final boolean[] active = {true};
        final boolean[] busy = {false};
        final Runnable[] poll = new Runnable[1];
        final Runnable schedule = () -> a.ui.postDelayed(poll[0], 1500);
        poll[0] =
                () -> {
                    if (!active[0] || !dialog.isShowing() || busy[0]) return;
                    String cookie = readCookies(cookieNames);
                    if (!cookie.isEmpty()) {
                        finish(a, dialog, active, success, cookie);
                        return;
                    }
                    if (tokenScript != null) {
                        busy[0] = true;
                        try {
                            web.evaluateJavascript(
                                    tokenScript,
                                    value -> {
                                        busy[0] = false;
                                        String token = unfence(value);
                                        if (!token.isEmpty()) {
                                            finish(a, dialog, active, success, token);
                                        } else if (active[0]) schedule.run();
                                    });
                            return;
                        } catch (Throwable ignored) {
                            busy[0] = false;
                        }
                    }
                    schedule.run();
                };
        a.ui.postDelayed(poll[0], 1500);
    }

    private static void finish(
            MainActivity a, Dialog dialog, boolean[] active, Success success, String value) {
        active[0] = false;
        dialog.dismiss();
        a.toast("已捕获登录信息，正在验证…");
        success.onValue(value);
    }

    /** 读系统 CookieManager：返回含指定登录 Cookie 名的完整 Cookie 串，否则空。 */
    static String readCookies(String[] names) {
        try {
            CookieManager manager = CookieManager.getInstance();
            String[] urls = {"https://pan.quark.cn/", "https://drive.uc.cn/", "https://pan.baidu.com/", "https://www.alipan.com/"};
            for (String url : urls) {
                String cookie = manager.getCookie(url);
                if (cookie == null || cookie.trim().isEmpty()) continue;
                String lower = cookie.toLowerCase(Locale.ROOT);
                for (String name : names) if (lower.contains(name.toLowerCase(Locale.ROOT) + "=")) return cookie;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** evaluateJavascript 返回值是带引号的 JSON 字符串，去掉引号/转义。 */
    static String unfence(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.isEmpty() || "null".equals(text) || "undefined".equals(text)) return "";
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
            text = text.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return text.trim();
    }

    private static void injectQr(
            final WebView web,
            final MainActivity a,
            final int attempt,
            final boolean[] found,
            final boolean[] warned) {
        if (web == null || found[0]) return;
        if (attempt > 6) {
            if (!warned[0]) {
                warned[0] = true;
                a.toast("未找到二维码容器，请在网盘页面完成登录");
            }
            return;
        }
        try {
            web.evaluateJavascript(
                    QR_JS,
                    value -> {
                        boolean ok = value != null && value.contains("true");
                        if (ok) found[0] = true;
                        else a.ui.postDelayed(() -> injectQr(web, a, attempt + 1, found, warned), 700);
                    });
        } catch (Throwable error) {
            a.ui.postDelayed(() -> injectQr(web, a, attempt + 1, found, warned), 800);
        }
    }
}
