package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * 通用「网盘扫码登录」弹窗：深色外壳 + 白底二维码（只保留二维码，隐藏网页其余部分），
 * 登录成功后通过 Cookie 名或页面 JS token 捕获登录态，回调给各原生网盘模块。
 *
 * <p>可配置桌面 UA、二维码注入延迟/重试次数/连续命中次数、「扫码登录」tab 点击。
 */
final class PanDriveLogin {
    interface Success {
        void onValue(String cookieOrToken);
    }

    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 登录弹窗配置：每个网盘按自己的页面特点填。 */
    static final class Config {
        String title = "扫码登录";
        String hint = "请使用对应网盘 App 扫码登录";
        String loginUrl = "";
        String[] cookieNames = new String[0];
        String tokenScript;
        String userAgent;
        long injectDelayMs;
        int maxAttempts = 7;
        int requiredHits = 1;
        boolean clickScanTab;
        /** 百度等先自动点「去登录/立即登录」再找二维码。 */
        boolean clickLoginEntry;
    }

    private PanDriveLogin() {}

    /** 点击「去登录/立即登录/扫码登录」入口（若页面有），再找二维码。 */
    private static final String CLICK_LOGIN_JS =
            "(function(){try{var texts=['去登录','立即登录','登录','扫码登录','二维码登录'];"
                    + "var els=document.querySelectorAll('button,a,div,span,li,p');"
                    + "for(var t=0;t<texts.length;t++){for(var i=0;i<els.length;i++){"
                    + "var el=els[i];var s=(el.textContent||'').trim();"
                    + "if(s===texts[t]&&el.offsetParent!==null){el.click();return 'clicked';}}}"
                    + "}catch(e){}return 'no';})()";

    /** 点击「扫码登录/二维码」tab（若页面有），再找二维码。 */
    private static final String CLICK_SCAN_JS =
            "(function(){try{var els=document.querySelectorAll('button,a,div,span,li');"
                    + "for(var i=0;i<els.length;i++){var el=els[i];var t=(el.textContent||'').trim();"
                    + "if((t==='扫码登录'||t==='二维码登录'||t==='扫码'||t==='二维码')&&el.offsetParent!==null){"
                    + "el.click();return 'clicked';}}}catch(e){}return 'no';})()";

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

    static void show(final MainActivity a, final Config config, final Success success) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = a.vertical();
        page.setBackgroundColor(MainActivity.DARK);
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(16), a.dp(12), a.dp(12), a.dp(8));
        TextView heading = a.text(config.title, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(
                a.button("关闭", "pan-login-close", dialog::dismiss),
                new LinearLayout.LayoutParams(-2, a.dp(40)));
        page.addView(header);
        TextView tip = a.text(config.hint, UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
        tip.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        page.addView(tip);

        final WebView web = new WebView(a);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        if (config.userAgent != null && !config.userAgent.isEmpty())
            web.getSettings().setUserAgentString(config.userAgent);
        final int[] qrHits = {0};
        final boolean[] qrFound = {false};
        final boolean[] qrWarned = {false};
        final boolean[] scanClicked = {false};
        // 遮罩：二维码注入成功前一直盖住网页，避免先闪原页面
        final FrameLayout webWrap = new FrameLayout(a);
        webWrap.addView(web, new FrameLayout.LayoutParams(-1, -1));
        final FrameLayout mask = new FrameLayout(a);
        mask.setBackgroundColor(0xff0b1220);
        LinearLayout maskCol = a.vertical();
        maskCol.setGravity(Gravity.CENTER_HORIZONTAL);
        maskCol.setPadding(a.dp(24), 0, a.dp(24), 0);
        ProgressBar spinner = new ProgressBar(a);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
        maskCol.addView(spinner, new LinearLayout.LayoutParams(a.dp(34), a.dp(34)));
        TextView maskTitle =
                a.text("请使用对应网盘 App 扫码登录", 16, UiTokens.TEXT_PRIMARY);
        maskTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams maskTitleLp = new LinearLayout.LayoutParams(-2, -2);
        maskTitleLp.topMargin = a.dp(16);
        maskCol.addView(maskTitle, maskTitleLp);
        TextView maskHint = a.text("二维码加载中…", 12, UiTokens.TEXT_TERTIARY);
        maskHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams maskHintLp = new LinearLayout.LayoutParams(-2, -2);
        maskHintLp.topMargin = a.dp(6);
        maskCol.addView(maskHint, maskHintLp);
        mask.addView(
                maskCol,
                new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        webWrap.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        final Runnable hideMask = () -> mask.setVisibility(View.GONE);
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
                        qrHits[0] = 0;
                        qrFound[0] = false;
                        qrWarned[0] = false;
                        scanClicked[0] = false;
                        a.ui.postDelayed(
                                () ->
                                        injectQr(
                                                view,
                                                a,
                                                config,
                                                0,
                                                qrHits,
                                                qrFound,
                                                qrWarned,
                                                scanClicked,
                                                hideMask),
                                Math.max(0, config.injectDelayMs));
                    }
                });
        page.addView(webWrap, new LinearLayout.LayoutParams(-1, 0, 1f));
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
        AppLog.i(
                "PanLoginWeb",
                "open title="
                        + config.title
                        + " url="
                        + config.loginUrl
                        + " desktopUA="
                        + (config.userAgent != null && config.userAgent.contains("Windows"))
                        + " delay="
                        + config.injectDelayMs
                        + " cookieNames="
                        + java.util.Arrays.toString(config.cookieNames)
                        + " tokenScript="
                        + (config.tokenScript != null));
        web.loadUrl(config.loginUrl);

        final boolean[] active = {true};
        final boolean[] busy = {false};
        final Runnable[] poll = new Runnable[1];
        final Runnable schedule = () -> a.ui.postDelayed(poll[0], 1500);
        poll[0] =
                () -> {
                    if (!active[0] || !dialog.isShowing() || busy[0]) return;
                    String cookie = readCookies(config.cookieNames);
                    if (!cookie.isEmpty()) {
                        finish(a, dialog, active, success, cookie);
                        return;
                    }
                    if (config.tokenScript != null) {
                        busy[0] = true;
                        try {
                            web.evaluateJavascript(
                                    config.tokenScript,
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

    static String readCookies(String[] names) {
        if (names == null || names.length == 0) return "";
        try {
            CookieManager manager = CookieManager.getInstance();
            String[] urls = {
                "https://pan.quark.cn/",
                "https://drive.uc.cn/",
                "https://pc-api.uc.cn/",
                "https://pan.baidu.com/",
                "https://www.alipan.com/"
            };
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
            final Config config,
            final int attempt,
            final int[] hits,
            final boolean[] found,
            final boolean[] warned,
            final boolean[] scanClicked,
            final Runnable hideMask) {
        if (web == null || found[0]) return;
        if (attempt > Math.max(1, config.maxAttempts)) {
            if (!warned[0]) {
                warned[0] = true;
                if (hideMask != null) hideMask.run();
                a.toast("未自动找到二维码，请在页面内完成登录");
            }
            return;
        }
        final String script;
        if (config.clickLoginEntry && !scanClicked[0]) {
            script = CLICK_LOGIN_JS + ";" + QR_JS;
        } else if (config.clickScanTab && !scanClicked[0]) {
            script = CLICK_SCAN_JS + ";" + QR_JS;
        } else {
            script = QR_JS;
        }
        try {
            web.evaluateJavascript(
                    script,
                    value -> {
                        boolean ok = value != null && value.contains("true");
                        if ((config.clickLoginEntry || config.clickScanTab)
                                && !scanClicked[0]
                                && value != null)
                            scanClicked[0] = value.contains("clicked");
                        if (ok) {
                            hits[0]++;
                            // 阿里等页面需要连续多次命中（等二维码稳定渲染）才简化
                            if (hits[0] >= Math.max(1, config.requiredHits)) {
                                found[0] = true;
                                if (hideMask != null) hideMask.run();
                            } else
                                a.ui.postDelayed(
                                        () ->
                                                injectQr(
                                                        web,
                                                        a,
                                                        config,
                                                        attempt + 1,
                                                        hits,
                                                        found,
                                                        warned,
                                                        scanClicked,
                                                        hideMask),
                                        600);
                        } else
                            a.ui.postDelayed(
                                    () ->
                                            injectQr(
                                                    web,
                                                    a,
                                                    config,
                                                    attempt + 1,
                                                    hits,
                                                    found,
                                                    warned,
                                                    scanClicked,
                                                    hideMask),
                                    800);
                    });
        } catch (Throwable error) {
            a.ui.postDelayed(
                    () ->
                            injectQr(
                                    web,
                                    a,
                                    config,
                                    attempt + 1,
                                    hits,
                                    found,
                                    warned,
                                    scanClicked,
                                    hideMask),
                    900);
        }
    }
}
