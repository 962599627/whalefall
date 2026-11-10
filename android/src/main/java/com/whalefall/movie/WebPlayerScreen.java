package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.17 网页播放兜底：把 App 保存的网盘 Cookie 注入 WebView，直接打开夸克/UC 网页版；
 * 网页版能播说明账号正常、只是浏览器 Cookie 调 API 受限。
 */
final class WebPlayerScreen {
    private WebPlayerScreen() {}

    private static WebView webView;
    private static View customView;
    private static WebChromeClient.CustomViewCallback customCallback;

    static void show(final MainActivity a, final String providerKey, final String cookie, final String fid) {
        boolean uc = NetdiskQrLogin.UC.equals(providerKey);
        String site = uc ? "UC" : "夸克";
        String base = uc ? "https://drive.uc.cn" : "https://pan.quark.cn";
        String url = base + "/list#/list/all/" + (fid == null || fid.isEmpty() ? "" : fid);
        String fallback = base + "/list#/list/all";
        AppLog.i("WebPlayer", "open url=" + base + "/list#/list/all/" + fid + " cookie=" + (cookie == null || cookie.isEmpty() ? "no" : "yes"));

        a.renderPage("网页播放（" + site + "）", "WEB PLAYER");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.BLACK);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.BLACK);
        a.body.setTag("web-player");

        TextView note =
                a.text(
                        "若网页版能播，说明账号正常，仅 API 受限；可直接在此观看。",
                        11.5f,
                        UiTokens.TEXT_SECONDARY);
        note.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
        a.body.addView(note);
        LinearLayout actions = a.row();
        actions.setPadding(a.dp(16), 0, a.dp(16), a.dp(4));
        TextView back = a.text("‹ 文件管理", 12, UiTokens.TEXT_PRIMARY);
        back.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> FileManagerScreen.show(a));
        actions.addView(back);
        TextView reload = a.text("重新加载", 12, UiTokens.ACCENT);
        reload.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        reload.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(reload);
        reload.setOnClickListener(
                v -> {
                    if (webView != null) webView.reload();
                });
        LinearLayout.LayoutParams reloadLp = new LinearLayout.LayoutParams(-2, -2);
        reloadLp.leftMargin = a.dp(8);
        actions.addView(reload, reloadLp);
        a.body.addView(actions);

        final WebView view = new WebView(a);
        webView = view;
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        try {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) {
        }
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (cookie != null && !cookie.isEmpty()) {
            for (String pair : cookie.split(";")) {
                String value = pair.trim();
                if (value.isEmpty() || !value.contains("=")) continue;
                cookies.setCookie(base, value + "; path=/");
            }
            cookies.flush();
        }
        final String first = url;
        final String second = fallback;
        final boolean[] retried = {false};
        view.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public void onReceivedError(
                            WebView wv,
                            int errorCode,
                            String description,
                            String failingUrl) {
                        AppLog.i("WebPlayer", "error code=" + errorCode + " retry=" + (!retried[0]));
                        if (!retried[0]) {
                            retried[0] = true;
                            wv.loadUrl(second);
                        } else {
                            a.toast("网页加载失败：" + description);
                        }
                    }
                });
        view.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public void onShowCustomView(View view, CustomViewCallback callback) {
                        customView = view;
                        customCallback = callback;
                        if (a.root != null) {
                            FrameLayout.LayoutParams lp =
                                    new FrameLayout.LayoutParams(-1, -1);
                            a.root.addView(view, lp);
                        }
                    }

                    @Override
                    public void onHideCustomView() {
                        if (customView != null && a.root != null) {
                            a.root.removeView(customView);
                            customView = null;
                        }
                        if (customCallback != null) {
                            customCallback.onCustomViewHidden();
                            customCallback = null;
                        }
                    }
                });
        a.body.addView(
                view,
                new LinearLayout.LayoutParams(
                        -1, Math.max(a.dp(320), a.windowHeight() - a.dp(200))));
        view.loadUrl(first);
    }

    /** BACK：先退全屏视频，再离开页面。 */
    static boolean handleBack(MainActivity a) {
        if (customView != null) {
            if (a.root != null) a.root.removeView(customView);
            customView = null;
            if (customCallback != null) {
                customCallback.onCustomViewHidden();
                customCallback = null;
            }
            return true;
        }
        destroy();
        return false;
    }

    static void destroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        customView = null;
        customCallback = null;
    }
}
