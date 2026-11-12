package com.whalefall.movie;

import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.19 网页直播：全屏 WebView 打开电视台网页（如 sztv），支持媒体自动播放与全屏视频。
 * 1.9.147：内核（自动 / 腾讯 X5 / 系统）交给 {@link LiveWebKernel} 选择。
 */
final class WebLiveScreen {
  private WebLiveScreen() {}

  private static LiveWebKernel.Page page;

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
          if (page != null) page.reload();
        });
    LinearLayout.LayoutParams reloadLp = new LinearLayout.LayoutParams(-2, -2);
    reloadLp.leftMargin = a.dp(8);
    actions.addView(reload, reloadLp);
    a.body.addView(actions);

    page =
        LiveWebKernel.create(
            a,
            description -> AppLog.i("WebLive", "error desc=" + description));
    a.body.addView(
        page.view,
        new LinearLayout.LayoutParams(
            -1, Math.max(a.dp(320), a.windowHeight() - a.dp(140))));
    page.loadUrl(url);
  }

  static boolean handleBack(MainActivity a) {
    if (page != null && page.exitFullscreen()) return true;
    destroy();
    return false;
  }

  static void destroy() {
    if (page != null) {
      page.exitFullscreen();
      page.stopLoading();
      page.destroy();
      page = null;
    }
  }
}
