package com.whalefall.movie;

import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.19 网页直播：全屏 WebView 打开电视台网页（如 sztv），支持媒体自动播放与全屏视频。
 */
final class WebLiveScreen {
    private WebLiveScreen() {}

    private static WebView webView;
    private static View customView;
    private static WebChromeClient.CustomViewCallback customCallback;

    static void show(final MainActivity a, final String title, final String url) {
        AppLog.i("WebLive", "open title=" + title + " url=" + url);
        a.renderPage(title == null ? "网页直播" : title, "WEB LIVE");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.BLACK);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.BLACK);
        a.body.setTag("web-live");
        LinearLayout actions = a.row();
        actions.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(4));
        TextView back = a.text("‹ 直播列表", 12, UiTokens.TEXT_PRIMARY);
        back.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(
                v -> {
                    destroy();
                    MobileShell.show(a);
                    if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(2);
                });
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

        WebView view = new WebView(a);
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
        view.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public void onReceivedError(
                            WebView wv, int code, String desc, String failingUrl) {
                        AppLog.i("WebLive", "error code=" + code + " desc=" + desc);
                    }
                });
        view.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public void onShowCustomView(View v, CustomViewCallback callback) {
                        customView = v;
                        customCallback = callback;
                        if (a.root != null)
                            a.root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                    }

                    @Override
                    public void onHideCustomView() {
                        hideFullscreen(a);
                    }
                });
        a.body.addView(
                view,
                new LinearLayout.LayoutParams(
                        -1, Math.max(a.dp(320), a.windowHeight() - a.dp(140))));
        view.loadUrl(url);
    }

    static boolean handleBack(MainActivity a) {
        if (customView != null) {
            hideFullscreen(a);
            return true;
        }
        destroy();
        return false;
    }

    private static void hideFullscreen(MainActivity a) {
        if (customView != null && a.root != null) a.root.removeView(customView);
        customView = null;
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
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
