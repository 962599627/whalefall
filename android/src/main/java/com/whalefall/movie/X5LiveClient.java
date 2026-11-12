package com.whalefall.movie;

import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

/** 1.9.149：腾讯 X5 的 WebViewClient，负责加载完成/错误回调（与系统 WebView 行为对齐）。 */
final class X5LiveClient extends WebViewClient {
  private final LiveWebKernel.Page page;

  X5LiveClient(LiveWebKernel.Page page) {
    this.page = page;
  }

  @Override
  public void onPageFinished(WebView view, String url) {
    if (page != null) page.markLoaded();
  }

  @Override
  public void onReceivedError(
      WebView view, int errorCode, String description, String failingUrl) {
    AppLog.i("LiveKernel", "x5 error code=" + errorCode + " desc=" + description);
    if (page != null) page.onX5Error(description);
  }
}
