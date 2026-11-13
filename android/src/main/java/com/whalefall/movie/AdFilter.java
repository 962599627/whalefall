package com.whalefall.movie;

import java.util.Locale;

/**
 * 1.9.288：广告/追踪请求过滤（默认开启，可在设置里关闭）。
 *
 * <p>只拦已知广告/统计域名，避免误伤片源接口；命中后打日志方便排查。
 */
final class AdFilter {
  private AdFilter() {}

  static final String PREF = "ad_filter_enabled";

  private static final String[] BLOCK = {
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google",
    "pagead2.",
    "ads.youtube.com",
    "amazon-adsystem.com",
    "adnxs.com",
    "rubiconproject.com",
    "pubmatic.com",
    "casalemedia.com",
    "openx.net",
    "scorecardresearch.com",
    "taboola.com",
    "outbrain.com",
    "criteo.",
    "moatads.com",
    "adcolony.com",
    "applovin.com",
    "unityads.unity3d.com",
    "track.adform.net",
    "analytics.126.net",
    "ad.360.cn",
    "cpro.baidu.com",
    "pos.baidu.com",
    "hm.baidu.com",
    "splash.ad",
    "advertisement.",
    "/ad/",
    "adpolicy",
    "vast.xml",
    "preroll",
  };

  private static volatile Boolean cached;

  /** Activity 创建/恢复时同步开关，供无 Context 的请求入口使用。 */
  static void sync(MainActivity a) {
    if (a == null || a.prefs == null) return;
    cached = a.prefs.getBoolean(PREF, true);
  }

  static boolean enabled(MainActivity a) {
    if (a != null && a.prefs != null) return a.prefs.getBoolean(PREF, true);
    Boolean c = cached;
    return c == null || c;
  }

  /** 命中返回拦截原因，未命中返回 null。 */
  static String reason(String url) {
    if (url == null || url.isEmpty()) return null;
    String u = url.toLowerCase(Locale.ROOT);
    if (u.startsWith("data:") || u.startsWith("blob:")) return null;
    for (String token : BLOCK) if (u.contains(token)) return token;
    return null;
  }

  static boolean shouldBlock(MainActivity a, String url) {
    if (!enabled(a)) return false;
    String why = reason(url);
    if (why != null) {
      AppLog.i("AdFilter", "block token=" + why + " url=" + url);
      return true;
    }
    return false;
  }
}
