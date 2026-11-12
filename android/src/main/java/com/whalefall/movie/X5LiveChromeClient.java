package com.whalefall.movie;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebView;

/** 1.9.149：腾讯 X5 的 WebChromeClient，补齐全屏视频与加载进度。 */
final class X5LiveChromeClient extends WebChromeClient {
  private final MainActivity activity;
  private View fullscreenView;
  private CustomViewCallback fullscreenCallback;

  X5LiveChromeClient(MainActivity activity) {
    this.activity = activity;
  }

  @Override
  public void onShowCustomView(View view, CustomViewCallback callback) {
    if (activity == null || activity.root == null) return;
    if (fullscreenView != null) {
      callback.onCustomViewHidden();
      return;
    }
    fullscreenView = view;
    fullscreenCallback = callback;
    activity.root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    AppLog.i("LiveKernel", "x5 fullscreen enter");
  }

  @Override
  public void onHideCustomView() {
    if (fullscreenView == null) return;
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
    AppLog.i("LiveKernel", "x5 fullscreen exit");
  }

  @Override
  public void onProgressChanged(WebView view, int newProgress) {
    AppLog.i("LiveKernel", "x5 progress=" + newProgress);
  }

  boolean isFullscreen() {
    return fullscreenView != null;
  }

  void exitFullscreen() {
    onHideCustomView();
  }
}
