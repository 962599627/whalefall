package com.whalefall.movie;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 1.9.147：网页直播 / 网页播放的 WebView 内核选择（自动 → 腾讯 X5 → 系统）。
 *
 * <p>X5（TBS）通过反射加载：tbssdk 是可选的 {@code libs/tbssdk-*.jar}，未打包/初始化失败时
 * 自动回退系统 WebView 并 toast 提示。页面只持有 {@link Page} 包装，不关心具体内核。
 */
final class LiveWebKernel {
  static final String AUTO = "auto", X5 = "x5", SYSTEM = "system";
  private static final String TAG = "LiveKernel";
  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

  private LiveWebKernel() {}

  /** 页面加载失败回调（系统内核可收到；X5 基本 client 不保证）。 */
  interface ErrorSink {
    void onLoadError(String description);
  }

  static String mode(MainActivity a) {
    String value = a == null ? AUTO : a.prefs.getString(Prefs.LIVE_WEB_KERNEL, AUTO);
    if (X5.equals(value) || SYSTEM.equals(value)) return value;
    return AUTO;
  }

  static String label(String mode) {
    if (X5.equals(mode)) return "腾讯 X5";
    if (SYSTEM.equals(mode)) return "系统 WebView";
    return "自动";
  }

  /** 自动 → 腾讯 X5 → 系统 → 自动。 */
  static String cycle(String mode) {
    if (X5.equals(mode)) return SYSTEM;
    if (SYSTEM.equals(mode)) return AUTO;
    return X5;
  }

  /** 1.9.150 构建修复：X5 SDK 未集成（tbssdk jar 仅 export 接口），一律系统 WebView。 */
  static Page create(MainActivity a, ErrorSink sink) {
    String mode = mode(a);
    if (X5.equals(mode) || AUTO.equals(mode)) {
      AppLog.i(TAG, "mode=" + mode + " used=system reason=x5-sdk-missing");
      if (X5.equals(mode)) a.toast("X5 SDK 未集成，使用系统 WebView");
    } else {
      AppLog.i(TAG, "mode=" + mode + " used=system reason=mode-system");
    }
    try {
      return createSystem(a, sink);
    } catch (Throwable error) {
      AppLog.w(TAG, "system webview create failed", error);
      a.toast("网页组件不可用，请重启应用");
      android.widget.TextView fallback = new android.widget.TextView(a);
      fallback.setText("网页组件加载失败\n请返回后重试");
      fallback.setGravity(android.view.Gravity.CENTER);
      fallback.setTextColor(0xffffffff);
      Page page = new Page(fallback, false, sink);
      page.loadFailedStatic = true;
      AppLog.i(TAG, "mode=" + mode + " used=system reason=system-create-failed");
      return page;
    }
  }

  private static void log(String mode, boolean x5) {
    AppLog.i(TAG, "mode=" + mode + " used=" + (x5 ? "x5" : "system"));
  }

  /** 系统 WebView（现状行为：JS/DOM/媒体自动播放 + 错误回调 + 全屏视频）。 */
  private static Page createSystem(final MainActivity a, final ErrorSink sink) {
    final WebView view = new WebView(a);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    try {
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    } catch (Throwable ignored) {
    }
    settings.setUserAgentString(UA);
    final Page page = new Page(view, false, sink);
    view.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView wv, String url) {
            page.markLoaded();
          }

          @Override
          public void onReceivedError(
              WebView wv, int code, String desc, String failingUrl) {
            AppLog.i("WebView", "error code=" + code + " desc=" + desc);
            if (sink != null) sink.onLoadError(desc);
          }
        });
    view.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onShowCustomView(View v, CustomViewCallback callback) {
            page.enterFullscreen(a, v, callback);
          }

          @Override
          public void onHideCustomView() {
            page.exitFullscreen();
          }
        });
    return page;
  }

  /** 注入网页播放的网盘 Cookie（1.9.150：X5 未集成，仅系统 CookieManager）。 */
  static void setCookies(String base, String cookie, boolean x5) {
    if (cookie == null || cookie.isEmpty()) return;
    try {
      CookieManager cookies = CookieManager.getInstance();
      cookies.setAcceptCookie(true);
      for (String pair : cookie.split(";")) {
        String value = pair.trim();
        if (value.isEmpty() || !value.contains("=")) continue;
        cookies.setCookie(base, value + "; path=/");
      }
      cookies.flush();
    } catch (Throwable error) {
      AppLog.w(TAG, "setCookies failed", error);
    }
  }

  /** 页面持有的 WebView 包装：统一 loadUrl/reload/destroy/全屏处理。 */
  static final class Page {
    final View view;
    final boolean x5;
    java.lang.reflect.Method x5LoadUrl, x5Reload, x5StopLoading, x5Destroy;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    // 1.9.148：加载超时兜底与错误提示
    private ErrorSink sink;
    private boolean loaded;
    private Runnable loadTimeout;
    boolean loadFailedStatic;

    Page(View view, boolean x5) {
      this(view, x5, null);
    }

    Page(View view, boolean x5, ErrorSink sink) {
      this.view = view;
      this.x5 = x5;
      this.sink = sink;
    }

    void markLoaded() {
      loaded = true;
      if (loadTimeout != null) {
        view.removeCallbacks(loadTimeout);
        loadTimeout = null;
      }
    }

    private void reportLoadError(String description) {
      if (loaded) return;
      loaded = true;
      if (loadTimeout != null) {
        view.removeCallbacks(loadTimeout);
        loadTimeout = null;
      }
      AppLog.i(TAG, "load error x5=" + x5 + " desc=" + description);
      if (sink != null) sink.onLoadError(description);
      else if (view.getContext() instanceof MainActivity)
        ((MainActivity) view.getContext()).toast("网页加载失败");
    }

    void loadUrl(String url) {
      try {
        if (x5) {
          if (x5LoadUrl != null) x5LoadUrl.invoke(view, url);
        } else if (view instanceof WebView) {
          ((WebView) view).loadUrl(url);
        } else {
          return;
        }
        loaded = false;
        if (loadTimeout != null) view.removeCallbacks(loadTimeout);
        loadTimeout = () -> reportLoadError("timeout");
        view.postDelayed(loadTimeout, 12000);
      } catch (Throwable error) {
        AppLog.w(TAG, "loadUrl failed x5=" + x5, error);
        reportLoadError(error.getClass().getSimpleName());
      }
    }

    void reload() {
      try {
        if (x5) {
          if (x5Reload != null) x5Reload.invoke(view);
        } else {
          ((WebView) view).reload();
        }
      } catch (Throwable ignored) {
      }
    }

    void stopLoading() {
      try {
        if (x5) {
          if (x5StopLoading != null) x5StopLoading.invoke(view);
        } else {
          ((WebView) view).stopLoading();
        }
      } catch (Throwable ignored) {
      }
    }

    void destroy() {
      try {
        if (x5) {
          if (x5Destroy != null) x5Destroy.invoke(view);
        } else {
          ((WebView) view).destroy();
        }
      } catch (Throwable ignored) {
      }
    }

    boolean fullscreen() {
      return fullscreenView != null;
    }

    void enterFullscreen(
        MainActivity a, View v, WebChromeClient.CustomViewCallback callback) {
      fullscreenView = v;
      fullscreenCallback = callback;
      if (a != null && a.root != null) {
        a.root.addView(v, new FrameLayout.LayoutParams(-1, -1));
      }
    }

    /** @return 是否真的退出了全屏。 */
    boolean exitFullscreen() {
      if (fullscreenView == null) return false;
      if (fullscreenView.getParent() instanceof ViewGroup) {
        ((ViewGroup) fullscreenView.getParent()).removeView(fullscreenView);
      }
      fullscreenView = null;
      if (fullscreenCallback != null) {
        try {
          fullscreenCallback.onCustomViewHidden();
        } catch (Throwable ignored) {
        }
        fullscreenCallback = null;
      }
      return true;
    }
  }
}
