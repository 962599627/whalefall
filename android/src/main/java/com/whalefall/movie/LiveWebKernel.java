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

  /** 按设置创建 WebView；X5 失败自动回退系统并提示。 */
  static Page create(MainActivity a, ErrorSink sink) {
    String mode = mode(a);
    boolean wantX5 = X5.equals(mode) || (AUTO.equals(mode) && canUseX5());
    if (wantX5) {
      try {
        Page page = createX5(a);
        if (page != null) {
          log(mode, true);
          return page;
        }
      } catch (Throwable error) {
        AppLog.w(TAG, "x5 create failed mode=" + mode, error);
      }
      a.toast("X5 内核不可用，已回退系统 WebView");
    }
    Page page = createSystem(a, sink);
    log(mode, false);
    return page;
  }

  private static void log(String mode, boolean x5) {
    AppLog.i(TAG, "mode=" + mode + " used=" + (x5 ? "x5" : "system"));
  }

  /** 反射 QbSdk.canUseX5()：类不存在/方法缺失/返回 false 都视为不可用。 */
  private static boolean canUseX5() {
    try {
      Class<?> qbs = Class.forName("com.tencent.smtt.sdk.QbSdk");
      Object result = qbs.getMethod("canUseX5").invoke(null);
      boolean usable = result instanceof Boolean && (Boolean) result;
      AppLog.i(TAG, "canUseX5=" + usable);
      return usable;
    } catch (Throwable error) {
      AppLog.i(TAG, "canUseX5=false reason=" + error.getClass().getSimpleName());
      return false;
    }
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
    final Page page = new Page(view, false);
    view.setWebViewClient(
        new WebViewClient() {
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

  /** 反射创建腾讯 X5 WebView；任何一步失败都抛异常由调用方回退。 */
  private static Page createX5(MainActivity a) throws Exception {
    Class<?> cls = Class.forName("com.tencent.smtt.sdk.WebView");
    Object webView = cls.getConstructor(android.content.Context.class).newInstance(a);
    Object settings = cls.getMethod("getSettings").invoke(webView);
    if (settings != null) {
      setBoolean(settings, "setJavaScriptEnabled", true);
      setBoolean(settings, "setDomStorageEnabled", true);
      setBoolean(settings, "setMediaPlaybackRequiresUserGesture", false);
      try {
        settings
            .getClass()
            .getMethod("setUserAgentString", String.class)
            .invoke(settings, UA);
      } catch (Throwable ignored) {
      }
    }
    // client 基本配置：使用 X5 默认 client（找不到就保持默认，不影响加载）
    try {
      Class<?> clientClass = Class.forName("com.tencent.smtt.sdk.WebViewClient");
      Object client = clientClass.getDeclaredConstructor().newInstance();
      cls.getMethod("setWebViewClient", clientClass).invoke(webView, client);
    } catch (Throwable ignored) {
    }
    try {
      Class<?> chromeClass = Class.forName("com.tencent.smtt.sdk.WebChromeClient");
      Object chrome = chromeClass.getDeclaredConstructor().newInstance();
      cls.getMethod("setWebChromeClient", chromeClass).invoke(webView, chrome);
    } catch (Throwable ignored) {
    }
    Page page = new Page((View) webView, true);
    page.x5LoadUrl = cls.getMethod("loadUrl", String.class);
    page.x5Reload = cls.getMethod("reload");
    page.x5StopLoading = cls.getMethod("stopLoading");
    page.x5Destroy = cls.getMethod("destroy");
    return page;
  }

  private static void setBoolean(Object target, String method, boolean value) {
    try {
      target.getClass().getMethod(method, boolean.class).invoke(target, value);
    } catch (Throwable ignored) {
    }
  }

  /** 注入网页播放的网盘 Cookie（系统/X5 各用各的 CookieManager）。 */
  static void setCookies(String base, String cookie, boolean x5) {
    if (cookie == null || cookie.isEmpty()) return;
    try {
      if (x5) {
        Class<?> cls = Class.forName("com.tencent.smtt.sdk.CookieManager");
        Object manager = cls.getMethod("getInstance").invoke(null);
        for (String pair : cookie.split(";")) {
          String value = pair.trim();
          if (value.isEmpty() || !value.contains("=")) continue;
          cls.getMethod("setCookie", String.class, String.class)
              .invoke(manager, base, value + "; path=/");
        }
        try {
          cls.getMethod("flush").invoke(manager);
        } catch (Throwable ignored) {
        }
        return;
      }
      CookieManager cookies = CookieManager.getInstance();
      cookies.setAcceptCookie(true);
      for (String pair : cookie.split(";")) {
        String value = pair.trim();
        if (value.isEmpty() || !value.contains("=")) continue;
        cookies.setCookie(base, value + "; path=/");
      }
      cookies.flush();
    } catch (Throwable error) {
      AppLog.w(TAG, "setCookies failed x5=" + x5, error);
    }
  }

  /** 页面持有的 WebView 包装：统一 loadUrl/reload/destroy/全屏处理。 */
  static final class Page {
    final View view;
    final boolean x5;
    java.lang.reflect.Method x5LoadUrl, x5Reload, x5StopLoading, x5Destroy;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    Page(View view, boolean x5) {
      this.view = view;
      this.x5 = x5;
    }

    void loadUrl(String url) {
      try {
        if (x5) {
          if (x5LoadUrl != null) x5LoadUrl.invoke(view, url);
        } else {
          ((WebView) view).loadUrl(url);
        }
      } catch (Throwable error) {
        AppLog.w(TAG, "loadUrl failed x5=" + x5, error);
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
