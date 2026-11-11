package com.whalefall.movie;

import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playback resolution (direct, plugin/JS player, web resolver, magnet stream), player page
 * lifecycle, mini-player transitions, remote seek gesture, control overlay auto-hide, and watch
 * history bookmark persistence extracted from {@link MainActivity}.
 */
final class PlaybackController {
 private PlaybackController() {}

 /** 播放页布局诊断日志只打一次（用户反馈「播放页卡片化」时看 设置→运行日志）。 */
 private static boolean layoutLogged;
 /** 上次播放页 root 尺寸：只有真实变化才重排 chrome/重算 surface（避免持续布局干扰触摸）。 */
 private static int lastPlayerLayoutW = -1;
 private static int lastPlayerLayoutH = -1;

 static String formatTime(int ms) {
 int sec = Math.max(0, ms / 1000);
 return sec >= 3600
 ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
 : String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
 }

 static void stopMagnet(MainActivity a) {
 if (a.magnetStream != null) {
 a.magnetStream.close();
 a.magnetStream = null;
 }
 }

 static void stopWeb(MainActivity a) {
 if (a.webResolver != null) {
 a.webResolver.stop();
 a.webResolver = null;
 }
 }

 static void resolveWebPage(
 MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String prefix) {
 String address = (prefix == null ? "" : prefix) + ep.url;
 if (!Cms.isHttp(address)) {
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 failInPlaceSwitch(a, "解析页面地址不受支持。");
 return;
 }
 final int ticket = a.generation;
 final int serial = a.playbackResolveSerial;
 try {
 a.webResolver =
 new WebResolver(
 a,
 address,
 ep.headers,
 new WebResolver.Listener() {
 public void media(String url, Map<String, String> headers) {
 if (ticket != a.generation || serial != a.playbackResolveSerial)
 return;
 a.webResolver = null;
 clearPlaybackLoading(a);
 Cms.Episode resolved = new Cms.Episode();
 resolved.name = ep.name;
 resolved.url = url;
 resolved.headers.putAll(headers);
 resolved.media = ep.media;
 openPlayer(a, s, v, resolved);
 }

 public void failed(String reason) {
 if (ticket == a.generation
 && serial == a.playbackResolveSerial) {
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 stopWeb(a);
 failInPlaceSwitch(a, reason);
 }
 }
 });
 a.playbackLoading.addView(a.webResolver.view(), new FrameLayout.LayoutParams(1, 1));
 } catch (Exception error) {
 AppLog.w("PlaybackController", "WebResolver init failed", error);
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 failInPlaceSwitch(a, "这台设备无法打开网页播放组件。");
 }
 }

 static void failInPlaceSwitch(MainActivity a, String message) {
 if (a.playerChrome != null) a.playerChrome.setLoading(false);
 final PlaybackLoading loading = a.playbackLoading;
 if (loading != null) {
 // 加载失败/超时：停止动画并显示「加载失败 + 重试」
 final Cms.Site site = a.playbackControls == null ? null : a.playbackControls.site;
 final Cms.Video video = a.playbackControls == null ? null : a.playbackControls.video;
 final Cms.Line line = a.activeLine;
 final Cms.Episode ep = a.activeEpisode;
 AppLog.i("PlaybackController", "loading failed: " + message);
 loading.showError(
 message,
 () -> {
 clearPlaybackLoading(a);
 if (site != null && video != null && ep != null)
 a.resolvePlayer(site, video, line, ep);
 });
 return;
 }
 if (a.playerChrome != null && a.playerChrome.isLoadingVisible()) {
 final Cms.Site site = a.playbackControls == null ? null : a.playbackControls.site;
 final Cms.Video video = a.playbackControls == null ? null : a.playbackControls.video;
 final Cms.Line line = a.activeLine;
 final Cms.Episode ep = a.activeEpisode;
 AppLog.i("PlaybackController", "chrome loading failed: " + message);
 a.playerChrome.showLoadingError(
 message,
 () -> {
 if (site != null && video != null && ep != null)
 a.resolvePlayer(site, video, line, ep);
 });
 return;
 }
 if (a.current != null && a.current.player) {
 if (a.playStatus != null) a.playStatus.setText(message);
 showControls(a, true);
 }
 a.toast(message);
 }

 static WatchHistory.Entry lookupSavedBookmark(
 MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
 if (a == null
 || s == null
 || s.key == null
 || "live".equals(s.key)
 || v == null
 || v.id == null
 || ep == null) {
 return null;
 }
 int epIdx = line != null ? line.episodes.indexOf(ep) : -1;
 for (WatchHistory.Entry entry : a.watchHistory()) {
 if (s.key.equals(entry.site) && v.id.equals(entry.id)) {
 boolean sameName = ep.name != null && ep.name.equals(entry.episode);
 boolean sameIndex =
 line != null
 && line.name != null
 && line.name.equals(entry.line)
 && epIdx >= 0
 && epIdx == entry.index;
 if (sameName || sameIndex) {
 return entry;
 }
 break;
 }
 }
 // 1.8.0 跨源续播：换线路/换源后按「影片名+集名（或集序号）」匹配最近进度
 String title = v.title == null ? "" : v.title.trim();
 String epName = ep.name == null ? "" : ep.name.trim();
 if (!title.isEmpty()) {
 WatchHistory.Entry best = null;
 for (WatchHistory.Entry entry : a.watchHistory()) {
 if (entry.title == null || !title.equals(entry.title.trim())) continue;
 boolean sameEp =
 (!epName.isEmpty() && epName.equals(entry.episode))
 || (epIdx >= 0 && epIdx == entry.index);
 if (!sameEp) continue;
 if (best == null || entry.updated > best.updated) best = entry;
 }
 if (best != null) {
 AppLog.i(
 "PlaybackController",
 "cross-source resume title=" + title + " ep=" + epName);
 return best;
 }
 }
 return null;
 }

 private static Cms.Site findPushSite(MainActivity a) {
 if (a == null || a.config == null) return null;
 for (Cms.Site site : a.config.sites) {
 if (site.api != null
 && site.api.toLowerCase(Locale.ROOT).contains("push")
 && a.available(site)) {
 return site;
 }
 }
 return null;
 }

 private static boolean looksLikeNetdiskLine(Cms.Site s, Cms.Line line, String url) {
 String combined =
 ((s != null && s.name != null ? s.name : "")
 + " "
 + (line != null && line.name != null ? line.name : "")
 + " "
 + (url != null ? url : ""))
 .toLowerCase(Locale.ROOT);
 return combined.contains("原画")
 || combined.contains("原畫")
 || combined.contains("网盘")
 || combined.contains("百度")
 || combined.contains("摆渡")
 || combined.contains("夸克")
 || combined.contains("夸父")
 || combined.contains("阿里")
 || combined.contains("天翼")
 || combined.contains("移动")
 || combined.contains("115")
 || combined.contains("123")
 || combined.contains("uc")
 || combined.contains("pan")
 || combined.contains("quark")
 || combined.contains("baidu");
 }

 private static Cms.Episode findMatchingEpisode(
 Cms.Line targetLine, Cms.Line fromLine, Cms.Episode ep) {
 if (targetLine == null || targetLine.episodes.isEmpty()) return null;
 if (ep != null && ep.name != null) {
 for (Cms.Episode candidate : targetLine.episodes) {
 if (ep.name.equalsIgnoreCase(candidate.name)) {
 return candidate;
 }
 }
 }
 int idx = fromLine != null && ep != null ? fromLine.episodes.indexOf(ep) : 0;
 if (idx >= 0 && idx < targetLine.episodes.size()) {
 return targetLine.episodes.get(idx);
 }
 return targetLine.episodes.get(0);
 }

 static void resolvePlayer(
 MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
 if (a == null || s == null || v == null) return;
 if (ep == null) {
 a.toast("暂无可播放的剧集");
 return;
 }
 if (ep.url == null) ep.url = "";
 if (a.miniMode) {
 a.changingMiniEpisode = true;
 showEpisodeDetails(a, s, v);
 }
 a.activeLine = line;
 a.activeEpisode = ep;
 AppLog.i(
 "Playback",
 "resolve start source=" + s.name + " engine=" + (s.api == null ? "unknown" : s.api));
 // 1.9.90：所有解析入口都注册重试动作
 a.playbackRetry = () -> resolvePlayer(a, s, v, line, ep);
 if (a.resumeAt == 0) {
 WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, line, ep);
 if (saved != null) {
 a.resumeAt = saved.resume();
 }
 }
 if (s.supported()) {
 a.playbackRetry = () -> resolvePlayer(a, s, v, line, ep);
 AppLog.i(
 "Playback",
 "resolve start source="
 + s.name
 + " engine="
 + (s.api == null
 ? "unknown"
 : (s.api.startsWith("csp_")
 ? "csp"
 : (s.api.contains("type3")
 ? "type3"
 : (s.api.contains("json")
 ? "json"
 : s.api)))));
 if (!s.requiresParser || Cms.isDirectStreamUrl(ep.url)) {
 openPlayer(a, s, v, ep);
 return;
 }
 resolveCmsWithParser(a, s, v, ep, s.playUrl);
 return;
 }
 if (a.playbackLoading != null) return;
 showPlaybackLoading(a);
 final int serial = a.playbackResolveSerial, ticket = a.generation;
 Future<?> request =
 RequestScope.submit(
 a.details,
 () -> {
 try {
 Cms.Site targetSite = s;
 Cms.Line chosenLine = line;
 Cms.Episode chosenEp = ep;
 String targetFlag = line != null ? line.name : "";
 String targetId = ep.url != null ? ep.url : "";
 if (targetId.regionMatches(true, 0, "push://", 0, 7)) {
 Cms.Site pushSite = findPushSite(a);
 if (pushSite != null) {
 targetSite = pushSite;
 String pushTarget = targetId.substring(7);
 Cms.Listing pushList =
 Cms.listing(a.engine(pushSite).detail(pushTarget));
 if (!pushList.videos.isEmpty()
 && !pushList.videos.get(0).lines.isEmpty()
 && !pushList.videos
 .get(0)
 .lines
 .get(0)
 .episodes
 .isEmpty()) {
 Cms.Line pLine = pushList.videos.get(0).lines.get(0);
 targetFlag = pLine.name;
 targetId = pLine.episodes.get(0).url;
 }
 }
 }
 JSONObject result = null;
 Cms.Episode resolvedCandidate = null;
 Exception primaryError = null;
 try {
 result =
 new JSONObject(
 a.engine(targetSite)
 .player(targetFlag, targetId));
 resolvedCandidate =
 MediaOptions.episode(targetSite, chosenEp, result);
 if (resolvedCandidate.url.regionMatches(
 true, 0, "push://", 0, 7)) {
 Cms.Site pushSite = findPushSite(a);
 if (pushSite != null) {
 String pushTarget = resolvedCandidate.url.substring(7);
 Cms.Listing pushList =
 Cms.listing(
 a.engine(pushSite).detail(pushTarget));
 if (!pushList.videos.isEmpty()
 && !pushList.videos.get(0).lines.isEmpty()
 && !pushList.videos
 .get(0)
 .lines
 .get(0)
 .episodes
 .isEmpty()) {
 Cms.Line pLine =
 pushList.videos.get(0).lines.get(0);
 JSONObject pushRes =
 new JSONObject(
 a.engine(pushSite)
 .player(
 pLine.name,
 pLine.episodes.get(
 0)
 .url));
 resolvedCandidate =
 MediaOptions.episode(
 pushSite, chosenEp, pushRes);
 result = pushRes;
 }
 }
 }
 } catch (Exception e) {
 primaryError = e;
 }

 boolean primaryPlayable =
 resolvedCandidate != null
 && resolvedCandidate.url != null
 && !resolvedCandidate.url.trim().isEmpty()
 && (Cms.isMedia(resolvedCandidate.url.trim())
 || resolvedCandidate
 .url
 .trim()
 .startsWith("magnet:?")
 || (result != null
 && (result.optInt("parse", 0) != 0
 || result.optInt("jx", 0)
 != 0)));
 if (!primaryPlayable
 && v != null
 && v.lines != null
 && v.lines.size() > 1
 && !looksLikeNetdiskLine(s, line, targetId)) {
 for (Cms.Line altLine : v.lines) {
 if (altLine == null
 || altLine == line
 || altLine.episodes.isEmpty()) {
 continue;
 }
 Cms.Episode altEp = findMatchingEpisode(altLine, line, ep);
 if (altEp == null || altEp.url == null) continue;
 if (looksLikeNetdiskLine(s, altLine, altEp.url)) continue;
 try {
 JSONObject altRes =
 new JSONObject(
 a.engine(s)
 .player(
 altLine.name,
 altEp.url));
 Cms.Episode altResolved =
 MediaOptions.episode(s, altEp, altRes);
 String altUrl =
 altResolved.url == null
 ? ""
 : altResolved.url.trim();
 if (!altUrl.isEmpty()
 && (Cms.isMedia(altUrl)
 || altUrl.startsWith("magnet:?")
 || altRes.optInt("parse", 0) != 0
 || altRes.optInt("jx", 0) != 0)) {
 result = altRes;
 resolvedCandidate = altResolved;
 chosenLine = altLine;
 chosenEp = altEp;
 primaryError = null;
 break;
 }
 } catch (Exception ignored) {
 }
 }
 }
 if (primaryError != null && resolvedCandidate == null) {
 throw primaryError;
 }
 final JSONObject finalResult =
 result != null ? result : new JSONObject();
 final Cms.Episode resolved =
 resolvedCandidate != null
 ? resolvedCandidate
 : MediaOptions.episode(
 targetSite, chosenEp, finalResult);
 final Cms.Line finalLine = chosenLine;
 final Cms.Episode finalEp = chosenEp;
 final boolean switchedLine =
 chosenLine != line && chosenLine != null;
 a.ui.post(
 () -> {
 if (serial != a.playbackResolveSerial
 || ticket != a.generation
 || a.isFinishing()) {
 JSONObject unused =
 resolved.media.optJSONObject("embySession");
 if (unused != null
 && s.api.startsWith(
 com.pilipala.emby.EmbyLibrary
 .PREFIX))
 try {
 com.pilipala.emby.EmbyAccounts.open(
 a, s.api)
 .track(unused)
 .stop(0);
 } catch (Exception ignored) {
 }
 return;
 }
 try {
 if (switchedLine) {
 a.activeLine = finalLine;
 a.activeEpisode = finalEp;
 a.toast("当前线路响应异常，已自动切换至 " + finalLine.name);
 }
 String url =
 resolved.url == null
 ? ""
 : resolved.url.trim();
 if (url.isEmpty() && finalEp.url != null) {
 String fallbackEpUrl =
 LocalProxy.rewrite(finalEp.url.trim());
 if (Cms.isMedia(fallbackEpUrl)
 || fallbackEpUrl.startsWith(
 "magnet:?")) {
 url = fallbackEpUrl;
 resolved.url = fallbackEpUrl;
 }
 }
 if (finalResult.optInt("parse", 0) != 0
 || finalResult.optInt("jx", 0) != 0) {
 String playPrefix =
 finalResult.optString(
 "playUrl", s.playUrl);
 if ((playPrefix == null || playPrefix.isEmpty())
 && a.config != null
 && !a.config.parses.isEmpty()) {
 clearPlaybackLoading(a);
 resolveCmsWithParser(a, s, v, resolved, "");
 return;
 }
 resolveWebPage(a, s, v, resolved, playPrefix);
 return;
 }
 clearPlaybackLoading(a);
 if (url.isEmpty()) {
 a.changingMiniEpisode = false;
 String spiderMsg =
 finalResult
 .optString(
 "msg",
 finalResult.optString(
 "message",
 finalResult
 .optString(
 "tip",
 "")))
 .trim();
 if (!spiderMsg.isEmpty()) {
 failInPlaceSwitch(a, spiderMsg);
 } else if (looksLikeNetdiskLine(
 s, finalLine, finalEp.url)) {
 failInPlaceSwitch(
 a,
 "该网盘线路未返回播放地址，请先在「网盘与账号」扫码登录对应网盘，或点击右上角 切换 切换其他线路");
 } else {
 failInPlaceSwitch(
 a, "当前线路未返回有效播放地址，请点击右上角 切换 切换其他线路");
 }
 return;
 }
 if (!Cms.isMedia(url)
 && !url.startsWith("magnet:?")) {
 a.changingMiniEpisode = false;
 if (looksLikeNetdiskLine(s, finalLine, url)) {
 failInPlaceSwitch(
 a,
 "该网盘线路需先在「网盘与账号」登录授权，或点击右上角 切换 切换其他线路");
 } else {
 failInPlaceSwitch(
 a, "来源返回的播放协议暂不支持，请切换其他线路");
 }
 return;
 }
 openPlayer(a, s, v, resolved);
 } catch (Exception error) {
 boolean initFailure = isPluginInitFailure(error);
 AppLog.i(
 "Playback",
 "resolve fail reason="
 + (initFailure ? "init CCE" : error.getClass().getSimpleName())
 + " elapsed=inline");
 AppLog.w(
 "PlaybackController",
 "parse player response failed",
 error);
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 if (initFailure)
 showResolveFail(a, "片源插件初始化失败：" + a.message(error), null);
 else failInPlaceSwitch(a, "播放信息无法读取，请重试");
 }
 });
 } catch (Exception error) {
 AppLog.w("PlaybackController", "engine.player failed", error);
 boolean initFailure = isPluginInitFailure(error);
 AppLog.i(
 "Playback",
 "resolve fail reason="
 + (initFailure ? "init CCE" : error.getClass().getSimpleName())
 + " elapsed=engine");
 a.ui.post(
 () -> {
 if (serial != a.playbackResolveSerial
 || ticket != a.generation) return;
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 if (initFailure)
 showResolveFail(a, "片源插件初始化失败：" + a.message(error), null);
 else failInPlaceSwitch(a, a.message(error));
 });
 }
 });
 a.pageRequests.add(request);
 }

 static void resolveCmsWithParser(
 MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String preferredPrefix) {
 if (a.playbackLoading != null) return;
 a.playbackRetry = () -> resolveCmsWithParser(a, s, v, ep, preferredPrefix);
 showPlaybackLoading(a);
 final int serial = a.playbackResolveSerial, ticket = a.generation;
 Future<?> request =
 RequestScope.submit(
 a.details,
 () -> {
 List<Cms.Parse> candidates = new java.util.ArrayList<>();
 if (preferredPrefix != null && Cms.isHttp(preferredPrefix)) {
 Cms.Parse direct = new Cms.Parse();
 direct.name = s.name;
 direct.url = preferredPrefix;
 direct.type = 1;
 direct.headers.putAll(s.headers);
 candidates.add(direct);
 }
 if (a.config != null && a.config.parses != null) {
 for (Cms.Parse p : a.config.parses) {
 if (p != null && Cms.isHttp(p.url)) candidates.add(p);
 }
 }
 String webFallbackPrefix =
 preferredPrefix != null ? preferredPrefix : "";
 for (int i = 0; i < Math.min(4, candidates.size()); i++) {
 if (Thread.currentThread().isInterrupted()) return;
 Cms.Parse p = candidates.get(i);
 if (p.type == 0 && webFallbackPrefix.isEmpty()) {
 webFallbackPrefix = p.url;
 }
 if (p.type == 1 || p.type == 2 || p.type == 3 || i == 0) {
 try {
 String targetUrl =
 p.url + java.net.URLEncoder.encode(ep.url, "UTF-8");
 String resp = Cms.fetch(targetUrl, p.headers);
 if (resp != null && resp.trim().startsWith("{")) {
 JSONObject jo = new JSONObject(resp.trim());
 String mediaUrl = jo.optString("url", "").trim();
 if (Cms.isMedia(mediaUrl)) {
 final Cms.Episode resolved =
 MediaOptions.episode(s, ep, jo);
 a.ui.post(
 () -> {
 if (serial != a.playbackResolveSerial
 || ticket != a.generation
 || a.isFinishing()) return;
 clearPlaybackLoading(a);
 openPlayer(a, s, v, resolved);
 });
 return;
 }
 }
 } catch (Exception ignored) {
 }
 }
 }
 final String finalPrefix = webFallbackPrefix;
 a.ui.post(
 () -> {
 if (serial != a.playbackResolveSerial
 || ticket != a.generation
 || a.isFinishing()) return;
 if (Cms.isHttp(finalPrefix + ep.url)) {
 resolveWebPage(a, s, v, ep, finalPrefix);
 } else if (Cms.isMedia(ep.url)) {
 clearPlaybackLoading(a);
 openPlayer(a, s, v, ep);
 } else {
 a.changingMiniEpisode = false;
 clearPlaybackLoading(a);
 failInPlaceSwitch(a, "此剧集需要外部解析，未找到可用解析接口");
 }
 });
 });
 a.pageRequests.add(request);
 }

 static void showPlaybackLoading(MainActivity a) {
 a.playbackLoadingFocus = a.getCurrentFocus();
 a.playbackLoading = new PlaybackLoading(a);
 a.root.addView(a.playbackLoading, new FrameLayout.LayoutParams(-1, -1));
 if (a.playerChrome != null) a.playerChrome.update();
 a.playbackLoading.requestFocus();
 // 1.9.89：15s 内未完成解析则隐藏 loading 并提示，绝不永久卡加载
 final long started = android.os.SystemClock.uptimeMillis();
 a.ui.postDelayed(
 () -> {
 if (a.playbackLoading == null) return;
 long elapsed = android.os.SystemClock.uptimeMillis() - started;
 AppLog.i("Playback", "resolve fail reason=timeout elapsed=" + elapsed);
 Runnable retry = a.playbackRetry;
 clearPlaybackLoading(a);
 showResolveFail(a, "解析超时（30 秒），旧设备首次解析可能较慢。", retry);
 },
 30000);
 }

 /** 1.9.89：解析失败统一提示（隐藏 loading + 重试按钮，可换线路）。 */
 /** 1.9.91：识别插件/CSP 初始化失败（ClassCastException / 主题 Context 等），立即提示不等超时。 */
 static boolean isPluginInitFailure(Throwable error) {
 for (Throwable t = error; t != null; t = t.getCause()) {
 if (t instanceof ClassCastException) return true;
 String m = t.getMessage();
 if (m != null
 && (m.contains("初始化")
 || m.contains("Init.init")
 || m.contains("ContextThemeWrapper")
 || m.contains("Application"))) return true;
 }
 return false;
 }

 static void showResolveFail(MainActivity a, String reason, Runnable retry) {
 if (a == null || a.isFinishing()) return;
 AppDialog.Builder builder =
 new AppDialog.Builder(a)
 .setTitle("解析失败")
 .setMessage(reason + "\n\n可点「重试」，或返回后用右上角「切换」换线路。")
 .setNegativeButton("取消", null);
 if (retry != null) builder.setPositiveButton("重试", (d, w) -> retry.run());
 builder.setPositiveDefaultFocus().show();
 }

 static void showResolveFail(
 MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep, String reason) {
 showResolveFail(
 a,
 reason,
 () -> resolvePlayer(a, s, v, line, ep));
 }

 static void clearPlaybackLoading(MainActivity a) {
 a.playbackResolveSerial++;
 a.playbackRetry = null;
 if (a.playbackLoading != null) {
 if (a.playbackLoading.getParent() instanceof ViewGroup)
 ((ViewGroup) a.playbackLoading.getParent()).removeView(a.playbackLoading);
 a.playbackLoading = null;
 if (a.playerChrome != null) a.playerChrome.update();
 if (a.playbackLoadingFocus != null && UiCompat.isAttached(a.playbackLoadingFocus))
 a.playbackLoadingFocus.requestFocus();
 a.playbackLoadingFocus = null;
 }
 }

 static void openPlayer(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode e) {
 if (a == null || s == null || v == null || e == null) {
 if (a != null) a.toast("该片源数据不完整，无法播放");
 return;
 }
 if (e.url == null) e.url = "";
 if (e.media == null) e.media = new JSONObject();
 if (e.url.startsWith("magnet:?")) {
 magnetPlayer(a, s, v, e);
 return;
 }
 if (!Cms.isMedia(e.url)) {
 if (a.playerChrome != null) a.playerChrome.setLoading(false);
 a.notice("这条剧集需要解析或使用了特殊协议，基础版尚未支持。");
 return;
 }
 MainActivity.Screen screen =
 new MainActivity.Screen(
 () -> {
 // 播放页构造涉及大量站点数据，任何异常都降级成提示，不闪退
 try {
 playerPage(a, s, v, e);
 } catch (Throwable error) {
 AppLog.w("Playback", "playerPage failed", error);
 if (a.playerChrome != null) a.playerChrome.setLoading(false);
 clearPlaybackLoading(a);
 a.toast("该片源暂不可播放，已返回");
 try {
 releasePlayer(a);
 if (!a.back.isEmpty()) {
 a.current = a.back.pop();
 a.renderReturn(a.current);
 a.restore(a.current);
 }
 } catch (Throwable ignored) {
 }
 }
 });
 screen.player = true;
 a.open(screen, a.current == null || !a.current.player);
 }

 static void magnetPlayer(MainActivity a, Cms.Site site, Cms.Video video, Cms.Episode episode) {
 a.open(
 new MainActivity.Screen(
 () -> {
 a.renderPage(video.title, "MAGNET PLAYBACK");
 TextView state = a.text("正在获取磁力文件列表…", 17, MainActivity.MUTED);
 a.body.addView(state);
 final int ticket = a.generation;
 try {
 a.magnetStream =
 MagnetStream.start(
 a,
 episode.url,
 new MagnetStream.Listener() {
 public void progress(String message) {
 if (ticket == a.generation)
 state.setText(message);
 }

 public void files(
 List<MagnetStream.MediaFile> files,
 int suggested,
 MagnetStream.Selection selection) {
 if (ticket != a.generation) return;
 String[] names = new String[files.size()];
 for (int i = 0; i < names.length; i++)
 names[i] = files.get(i).name;
 new AppDialog.Builder(a)
 .setTitle("选择要播放的视频")
 .setSingleChoiceItems(
 names,
 suggested,
 (dialog, index) -> {
 dialog.dismiss();
 selection.select(index);
 })
 .setNegativeButton(
 "取消",
 (dialog, index) ->
 a.onBackPressed())
 .setOnCancelListener(
 dialog -> a.onBackPressed())
 .show();
 }

 public void ready(
 String url, String mime, String name) {
 if (ticket != a.generation) return;
 Cms.Episode resolved = new Cms.Episode();
 resolved.name = name;
 resolved.url = url;
 resolved.headers.putAll(episode.headers);
 try {
 resolved.media =
 new JSONObject(
 episode.media
 .toString())
 .put("mimeType", mime);
 } catch (Exception ignored) {
 }
 if (!a.back.isEmpty())
 a.current = a.back.pop();
 openPlayer(a, site, video, resolved);
 }

 public void error(String message) {
 if (ticket == a.generation) {
 state.setText(message);
 a.addButton(
 a.body,
 a.button(
 "重试",
 "primary",
 a::refresh));
 }
 }
 });
 } catch (Exception error) {
 AppLog.w("PlaybackController", "MagnetStream start failed", error);
 state.setText("磁力播放初始化失败：" + error.getClass().getSimpleName());
 }
 }),
 true);
 }

 static void changeEpisode(
 MainActivity a,
 Cms.Site site,
 Cms.Video video,
 Cms.Line line,
 Cms.Episode episode,
 int position) {
 if (a == null || video == null || episode == null) return;
 if (episode.url == null) episode.url = "";
 a.changingMiniEpisode = a.miniMode;
 a.resumeAt = position;
 if (a.miniMode) {
 showEpisodeDetails(a, site, video);
 } else {
 rememberEpisodeOnBackStack(a, line, episode);
 if (a.current != null && a.current.player) {
 prepareInPlaceSwitch(a, line, episode);
 }
 }
 resolvePlayer(a, site, video, line, episode);
 }

 static void rememberEpisodeOnBackStack(MainActivity a, Cms.Line line, Cms.Episode episode) {
 if (a.back.isEmpty() || line == null || episode == null) return;
 MainActivity.Screen prev = a.back.peek();
 if (prev == null) return;
 int epIdx = line.episodes.indexOf(episode);
 if (epIdx >= 0) {
 boolean desc = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
 int page = EpisodeSelection.pageFor(epIdx, line.episodes.size(), desc);
 prev.selection.select(line.name, page, line.episodes.size());
 prev.focus = "episode:" + line.name + ":" + epIdx;
 prev.episodeFocus = epIdx;
 prev.episodeFocusLine = line.name;
 }
 }

 static void prepareInPlaceSwitch(MainActivity a, Cms.Line line, Cms.Episode episode) {
 finishSeek(a, false);
 savePlaybackHistory(a, true);
 stopWeb(a);
 stopMagnet(a);
 if (a.player != null) {
 try {
 a.player.release();
 } catch (Exception error) {
 AppLog.w("PlaybackController", "player.release failed", error);
 }
 a.player = null;
 }
 a.prepared = false;
 a.playing = false;
 if (a.playerChrome != null) {
 a.playerChrome.switchEpisode(episode, line);
 }
 }

 static void showEpisodeDetails(MainActivity a, Cms.Site site, Cms.Video video) {
 releasePlayer(a);
 if (!a.isTvDevice()) {
 a.applyMobileSystemBars(false);
 a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
 }
 if (a.current != null && a.current.player)
 a.current =
 a.back.isEmpty()
 ? new MainActivity.Screen(() -> a.detail(site, video))
 : a.back.pop();
 if (a.current != null) {
 a.detail(site, video, video);
 a.restore(a.current);
 }
 }

 static void leavePlayback(MainActivity a) {
 if (!a.isTvDevice()) {
 a.applyMobileSystemBars(false);
 a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
 // 1.9.62：手机返回详情与 TV 一致：进入左上角小窗，而不是释放播放器回海报
 if (!a.isLive() && !a.miniMode && a.playbackControls != null && a.player != null) {
 minimizePlayback(a);
 return;
 }
 }
 if (!a.back.isEmpty()) {
 releasePlayer(a);
 a.current = a.back.pop();
 a.renderReturn(a.current);
 // 1.9.52：返回详情后焦点回到「播放·首集」（或原线路）
 if (a.current != null) {
 a.current.focus = "detail-play";
 a.restore(a.current);
 } else a.restore(a.current);
 } else a.onBackPressed();
 }

 static void minimizePlayback(MainActivity a) {
 if (a.playbackControls == null || a.miniMode) return;
 boolean overDetail = a.expandedOverDetail;
 a.expandedOverDetail = false;
 a.miniMode = true;
 showControls(a, false);
 if (a.playbackControls.subtitle != null)
 a.playbackControls.subtitle.setVisibility(View.GONE);
 if (a.playbackControls.danmaku != null) a.playbackControls.danmaku.setVisibility(View.GONE);
 // 1.9.53：从全屏播放返回时先渲染详情页；从小窗放大后再次缩小则详情已在下方
 if (!overDetail && !a.back.isEmpty()) {
 a.current = a.back.pop();
 a.renderReturn(a.current);
 }
 View layer = a.playbackLayer;
 if (layer != null && a.root != null && layer.getParent() != a.root) {
 if (layer.getParent() instanceof ViewGroup)
 ((ViewGroup) layer.getParent()).removeView(layer);
 a.root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
 }
 if (layer != null) layer.bringToFront();
 // 1.9.62：手机小窗确保详情回到顶部，hero 区域可见用于视频覆盖
 if (a.scroll != null) a.scroll.scrollTo(0, 0);
 AppLog.i(
 "Mini",
 "enter mobile="
 + (!a.isTvDevice())
 + " mini="
 + a.miniMode
 + " player="
 + (a.player != null));
 // 1.9.95：先恢复详情页，再建小窗，避免详情页 restore 抢走小窗焦点
 if (!overDetail && a.current != null) a.restore(a.current);
 // 1.9.58：由 MiniDetails 先测 hero 矩形再隐藏，保证小窗与海报区域一致
 a.miniDetails = new MiniDetails(a);
 }

 static void expandPlayback(MainActivity a) {
 if (!a.miniMode) return;
 a.miniMode = false;
 a.expandedOverDetail = true;
 if (a.miniDetails != null) {
 a.miniDetails.remove();
 a.miniDetails = null;
 }
 int position = a.player == null ? 0 : a.player.getCurrentPosition();
 UiCompat.setClipToOutline(a.playbackLayer, false);
 a.playbackLayer.setElevation(0);
 a.playbackLayer.setOutlineProvider(null);
 a.playbackLayer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.playbackLayer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
 // 1.9.57：恢复详情 hero 海报/播放按钮（全屏播放时详情在背后）
 hideDetailHero(a, false);
 // 1.9.53：只恢复布局并复用现有 Surface/player，不重新请求地址、不 seek
 View output = findTagged(a.playbackLayer, "video-output");
 android.view.Surface surface =
 output instanceof VideoOutput ? ((VideoOutput) output).currentSurface() : null;
 if (a.player != null && surface != null) {
 a.player.setSurface(surface);
 try {
 a.player.seekTo(position);
 } catch (Throwable ignored) {
 }
 }
 if (a.playerChrome != null) a.playerChrome.setLoading(false);
 AppLog.i(
 "Player",
 "expand fullscreen player="
 + (a.player != null ? "kept" : "missing")
 + " surface="
 + (surface != null ? "reused" : "none")
 + " position="
 + position);
 if (a.playbackControls != null) {
 a.playbackControls.subtitle.setVisibility(View.VISIBLE);
 a.playbackControls.danmaku.setVisibility(View.VISIBLE);
 }
 a.playbackLayer.post(
 () -> {
 if (a.playbackControls != null) {
 a.playbackControls.resize(
 a.playbackControls.videoWidth, a.playbackControls.videoHeight);
 showControls(a, true);
 if (a.playbackControls.timeline != null)
 a.playbackControls.timeline.requestFocus();
 }
 });
 }

 /** 1.9.56：小窗时隐藏详情页 hero 海报/播放按钮层，避免死图 + 播放按钮盖住视频。 */
 static void hideDetailHero(MainActivity a, boolean hide) {
 if (a.root == null) return;
 int changed = 0;
 // 1.9.58：整个 hero 容器（海报 + 渐变 + 播放按钮/提示）一起隐藏/恢复
 View hero = a.root.findViewWithTag("detail-hero");
 if (hero != null) {
 // INVISIBLE 保留 hero 占位，避免详情排版上移被小窗盖住
 hero.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
 changed++;
 }
 String[] tags = {"detail-cover", "detail-play-big", "detail-play"};
 for (String tag : tags) {
 View view = a.root.findViewWithTag(tag);
 if (view != null) {
 view.setVisibility(hide ? View.GONE : View.VISIBLE);
 changed++;
 }
 }
 AppLog.i(
 "Mini",
 "z=video|poster hero="
 + (hide ? "hidden" : "restored")
 + " layers="
 + changed
 + " poster="
 + (hide ? "hidden" : "shown")
 + " surface="
 + (a.player != null ? "kept" : "none"));
 }

 static View findTagged(View view, String tag) { if (view == null) return null;
 if (tag.equals(view.getTag())) return view;
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++) {
 View found = findTagged(group.getChildAt(i), tag);
 if (found != null) return found;
 }
 }
 return null;
 }

 /** 1.9.22：播放失败/卡顿时自动换线路（最多 2 次，记住用户偏好）。 */
 static boolean tryAutoSource(
 final MainActivity a,
 final Cms.Site s,
 final Cms.Video v,
 final Cms.Episode ep,
 String reason) {
 if (!a.prefs.getBoolean("auto_switch_source", true)) return false;
 if (v == null || v.lines == null || v.lines.size() < 2 || ep == null) return false;
 int attempts = 0;
 try {
 attempts = ep.media == null ? 0 : ep.media.optInt("_autoSourceAttempts", 0);
 } catch (Throwable ignored) {
 }
 if (attempts >= 2) return false;
 Cms.Line current = a.activeLine;
 int idx = current == null ? -1 : v.lines.indexOf(current);
 for (int step = 1; step < v.lines.size(); step++) {
 Cms.Line alt = v.lines.get((idx + step + 1 + v.lines.size()) % v.lines.size());
 if (alt == null || alt == current || alt.episodes.isEmpty()) continue;
 Cms.Episode altEp = findMatchingEpisode(alt, current, ep);
 if (altEp == null || altEp.url == null || altEp.url.trim().isEmpty()) continue;
 int next = attempts + 1;
 try {
 if (ep.media == null) ep.media = new JSONObject();
 ep.media.put("_autoSourceAttempts", next);
 if (altEp.media == null) altEp.media = new JSONObject();
 altEp.media.put("_autoSourceAttempts", next);
 } catch (Throwable ignored) {
 }
 AppLog.i(
 "AutoSource",
 "fallback from="
 + (current == null ? "" : current.name)
 + " to="
 + alt.name
 + " attempt="
 + next
 + " result=try reason="
 + reason);
 a.prefs.edit().putString("auto_source_last_line", alt.name).apply();
 String message = "播放失败，正在自动换源（" + next + "/2）";
 a.playStatus.setText(message);
 a.toast(message);
 final Cms.Episode target = altEp;
 a.activeLine = alt;
 a.activeEpisode = altEp;
 a.resumeAt = a.player == null ? 0 : a.player.getCurrentPosition();
 final int ticket = a.generation;
 a.ui.post(
 () -> {
 if (ticket == a.generation) playerPage(a, s, v, target);
 });
 return true;
 }
 return false;
 }

 /** 1.9.22：播放 5 秒无进度视为卡死，尝试自动换源。 */
 private static void scheduleStallWatchdog(
 final MainActivity a,
 final Cms.Site s,
 final Cms.Video v,
 final Cms.Episode ep,
 final int ticket) {
 final int[] lastPos = {-1};
 final int[] stuck = {0};
 final Runnable[] tick = new Runnable[1];
 tick[0] =
 () -> {
 if (ticket != a.generation || a.player == null || !a.prepared) return;
 int pos;
 try {
 pos = a.player.getCurrentPosition();
 } catch (Throwable error) {
 return;
 }
 if (pos == lastPos[0] && a.playing) stuck[0]++;
 else stuck[0] = 0;
 lastPos[0] = pos;
 if (stuck[0] >= 5) {
 stuck[0] = 0;
 AppLog.i("AutoSource", "stall 5s pos=" + pos + " try fallback");
 if (tryAutoSource(a, s, v, ep, "stall")) return;
 }
 a.ui.postDelayed(tick[0], 1000);
 };
 a.ui.postDelayed(tick[0], 1500);
 }

 static void playerPage(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep) {
 if (a == null || s == null || v == null || ep == null) return;
 if (ep.url == null) ep.url = "";
 if (ep.media == null) ep.media = new JSONObject();
 ep.url = LocalProxy.rewrite(ep.url);
 try {
 JSONArray subtitles = ep.media.optJSONArray("subtitles");
 if (subtitles != null)
 for (int i = 0; i < subtitles.length(); i++) {
 JSONObject sub = subtitles.optJSONObject(i);
 if (sub != null) sub.put("url", LocalProxy.rewrite(sub.optString("url")));
 }
 } catch (Exception ignored) {
 }
 JSONObject embySession = ep.media.optJSONObject("embySession");
 com.pilipala.emby.EmbyLibrary.Reporter retained = a.embyReporter;
 if (retained != null && retained.matches(embySession)) a.embyReporter = null;
 else retained = null;
 releasePlayer(a);
 a.embyReporter = retained;
 a.playingBookmark = null;
 if (s.api != null
 && s.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)
 && embySession != null) {
 try {
 if (a.embyReporter == null)
 a.embyReporter =
 com.pilipala.emby.EmbyAccounts.open(a, s.api).track(embySession);
 } catch (Exception ignored) {
 // Check-in setup must not prevent an already resolved video from playing.
 }
 }
 if (s.key != null
 && !s.key.equals("live")
 && v.id != null
 && a.activeLine != null
 && a.activeEpisode != null) {
 WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, a.activeLine, a.activeEpisode);
 int initialResume =
 a.resumeAt > 0
 ? a.resumeAt
 : (a.resumeAt < 0 ? 0 : (saved != null ? saved.resume() : 0));
 a.playingBookmark = new WatchHistory.Entry();
 a.playingBookmark.site = s.key;
 a.playingBookmark.id = v.id;
 a.playingBookmark.title = v.title;
 a.playingBookmark.poster = v.poster;
 a.playingBookmark.line = a.activeLine.name;
 a.playingBookmark.episode = a.activeEpisode.name;
 a.playingBookmark.index = a.activeLine.episodes.indexOf(a.activeEpisode);
 a.playingBookmark.position = Math.max(0, initialResume);
 a.playingBookmark.duration = saved != null ? Math.max(0, saved.duration) : 0;
 a.playingBookmark.updated = System.currentTimeMillis();
 try {
 a.prefs
 .edit()
 .putString(
 Prefs.WATCH_HISTORY,
 WatchHistory.upsert(
 a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
 a.playingBookmark))
 .commit();
 a.lastBookmarkSave = a.playingBookmark.updated;
 } catch (Exception ignored) {
 }
 }
 a.generation++;
 final int ticket = a.generation;
 a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 if (!a.isTvDevice()) {
 a.applyMobileSystemBars(true);
 a.setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
 }
 a.root = new FrameLayout(a);
 a.root.setBackgroundColor(Color.BLACK);
 a.root.setFocusable(true);
 a.root.setFocusableInTouchMode(true);
 a.root.setTag("player-surface");
 a.scroll = null;
 lastPlayerLayoutW = -1;
 lastPlayerLayoutH = -1;
 VideoOutput surface = new VideoOutput(a, a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0) == 1);
 a.playbackLayer = new FrameLayout(a);
 a.playbackLayer.setTag("playback-layer");
 a.root.addView(a.playbackLayer, new FrameLayout.LayoutParams(-1, -1));
 a.playbackLayer.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
 a.playerChrome = new PlayerChrome(a, v, ep, "live".equals(s.key));
 // 1.7.10：进入播放页即按 loading 状态点亮加载动画（此前从未调用 setLoading(true)）
 a.playerChrome.setLoading(!a.prepared && !a.miniMode);
 a.playerChrome.update();
 AppLog.i(
 "PlayerChrome",
 "loading init prepared="
 + a.prepared
 + " mini="
 + a.miniMode
 + " overlay="
 + (a.playbackLoading != null));
 if (!a.isTvDevice()) {
 final PlayerChrome chrome = a.playerChrome;
 a.root.setOnApplyWindowInsetsListener(
 (view, insets) -> {
 // 全屏播放页：chrome 只避让屏幕切孔，不再避让状态栏/导航栏/手势区。
 // 内缩由 PlayerChrome 自身的 16/24dp 设计边距负责，保证控件贴住屏幕边缘。
 int left = 0, top = 0, right = 0, bottom = 0;
 if (android.os.Build.VERSION.SDK_INT >= 30) {
 android.graphics.Insets cutout =
 insets.getInsets(
 android.view.WindowInsets.Type.displayCutout());
 left = cutout.left;
 top = cutout.top;
 right = cutout.right;
 bottom = cutout.bottom;
 } else if (android.os.Build.VERSION.SDK_INT >= 28
 && insets.getDisplayCutout() != null) {
 android.view.DisplayCutout cutout = insets.getDisplayCutout();
 left = cutout.getSafeInsetLeft();
 top = cutout.getSafeInsetTop();
 right = cutout.getSafeInsetRight();
 bottom = cutout.getSafeInsetBottom();
 }
 chrome.safeInsets(left, top, right, bottom);
 return insets;
 });
 a.root.requestApplyInsets();
 }
 a.playbackControls =
 new PlaybackControls(
 a, a.playerChrome.dock, a.playerChrome.progress, surface, s, v, ep);
 TouchPlayerGesture.attach(a, surface);
 final PlaybackControls controls = a.playbackControls;
 // 1.9.99：控制条内焦点变化时重新计时（离开控制条 3s 后才隐藏）
 a.root.getViewTreeObserver()
 .addOnGlobalFocusChangeListener(
 (oldFocus, newFocus) -> {
 if (a.controlsVisible) scheduleHide(a);
 });
 a.root.addOnLayoutChangeListener(
 (view, l, t, r, b, ol, ot, or, ob) -> {
 int w = r - l, h = b - t;
 if (w <= 0 || h <= 0) return;
 boolean sizeChanged = w != lastPlayerLayoutW || h != lastPlayerLayoutH;
 lastPlayerLayoutW = w;
 lastPlayerLayoutH = h;
 a.usableWidth = w;
 a.usableHeight = h;
 // 只在真实尺寸变化时 post（首次 -1 必触发），避免 relayout 自身引发连续布局
 if (!sizeChanged) return;
 view.post(
 () -> {
 if (ticket != a.generation || a.isFinishing()) return;
 if (a.playerChrome != null) a.playerChrome.relayout();
 if (a.playbackControls != null) {
 a.playbackControls.resize(
 a.playbackControls.videoWidth,
 a.playbackControls.videoHeight);
 }
 });
 });
 a.setContentView(a.root);
 if (!a.isTvDevice()) a.root.requestApplyInsets();
 // 一次性诊断：首帧布局后记录 layer/surface/chrome 尺寸与安全区（播放页「卡片化」排查用）
 logPlaybackLayout(a, ticket, 0);
 a.ui.postDelayed(() -> TvNav.audit(a, "player"), 600);
 a.ui.postDelayed(() -> UiAudit.audit(a, "player"), 600);
 surface.listen(
 new VideoOutput.Listener() {
 Surface renderSurface;
 int surfaceRevision;

 public void available(Surface value) {
 renderSurface = value;
 final int revision = ++surfaceRevision;
 AppLog.i(
 "Player",
 "lifecycle resume surface="
 + (revision > 1 ? "recreated" : "reused")
 + " revision="
 + revision
 + " player="
 + (a.player != null));
 if (ticket != a.generation) return;
 if (a.player != null) {
 a.player.setSurface(value);
 return;
 }
 a.worker.execute(
 () -> {
 MoviePlayer candidate = null;
 try {
 Map<String, String> mediaHeaders =
 Cms.inheritedHeaders(s.api, ep.url, s.headers);
 Cms.mergeHeaders(mediaHeaders, ep.headers);
 candidate =
 a.prefs.getInt(Prefs.PLAYER_ENGINE, 0) == 1
 ? new SystemPlayer(a)
 : ep.media.optBoolean("_softwareFallback")
 ? new MoviePlayer(
 a,
 ep.media.optInt(
 "_fallbackMode", 2))
 : new MoviePlayer(a);
 candidate.configureDefaultAudio();
 candidate.setDataSource(
 a,
 Uri.parse(
 Cms.isHttp(ep.url)
 ? Cms.normalizeHttp(ep.url)
 : ep.url),
 mediaHeaders);
 candidate.setOptions(ep.media);
 final MoviePlayer ready = candidate;
 a.ui.post(
 () -> {
 try {
 if (ticket != a.generation
 || revision != surfaceRevision
 || a.isFinishing()
 || renderSurface == null
 || !renderSurface.isValid()) {
 ready.release();
 return;
 }
 a.player = ready;
 ready.setSurface(renderSurface);
 ready.bindSubtitles(
 controls.subtitle,
 text -> {
 if (ticket == a.generation) {
 controls.lastSubtitle =
 String.valueOf(text);
 }
 });
 ready.setOnPreparedListener(
 mp -> {
 if (ticket != a.generation) return;
 a.prepared = true;
 if (a.playerChrome != null)
 a.playerChrome.setLoading(
 false);
 // 1.8.4：prepared 后再补一次，防止早于 chrome 创建/首帧回调丢失
 a.ui.postDelayed(
 () -> {
 if (ticket
 == a
 .generation
 && a.prepared
 && a.playerChrome
 != null) {
 a.playerChrome
 .setLoading(
 false);
 AppLog.i(
 "PlayerChrome",
 "loading hide requested reason=prepared-delayed");
 }
 },
 300);
 a.playing = a.foreground;
 if (a.foreground) mp.start();
 else mp.pause();
 controls.prepared();
 savePlaybackHistoryOnPrepared(a);
 scheduleStallWatchdog(
 a, s, v, ep, ticket);
 a.playStatus.setText(
 a.foreground
 ? ("live"
 .equals(
 s.key)
 ? (a
 .isTvDevice()
 ? "正在直播 · 上下换台 · 菜单键工具条"
 : "正在直播 · 单击显示选台工具栏")
 : (a
 .isTvDevice()
 ? "正在播放 · 确认暂停 · 上下键显示工具条"
 : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量"))
 : (a.isTvDevice()
 ? "已暂停 · 按确认继续"
 : "已暂停 · 双击继续"));
 clock(a, ticket);
 if (a.changingMiniEpisode) {
 a.changingMiniEpisode = false;
 minimizePlayback(a);
 } else if (a.foreground) {
 if ("live".equals(s.key)) {
 showControls(a, false);
 a.toast(
 "频道 "
 + (a.liveSession
 == null
 ? ""
 : (a.liveSession
 .index
 + 1)
 + " · ")
 + v.title);
 } else scheduleHide(a);
 }
 });
 ready.setOnVideoSizeChangedListener(
 (mp, w, h) -> {
 if (ticket == a.generation)
 controls.resize(w, h);
 });
 ready.setOnCompletionListener(
 mp -> {
 if (ticket != a.generation) return;
 a.playing = false;
 stopEmbySession(a);
 a.playStatus.setText("播放结束");
 controls.completed();
 if (QueueStore.playNext(a))
 a.toast("继续播放推送队列");
 });
 ready.setOnErrorListener(
 (mp, what, extra) -> {
 if (ticket != a.generation)
 return true;
 JSONArray qualities =
 ep.media.optJSONArray(
 "qualities");
 int qIdx =
 ep.media.optInt(
 "_qualityIdx", 0);
 if (qualities != null
 && qIdx + 1
 < qualities
 .length()) {
 JSONObject nextQ =
 qualities.optJSONObject(
 qIdx + 1);
 String nextUrl =
 nextQ != null
 ? nextQ
 .optString(
 "url",
 "")
 : "";
 if (!nextUrl.isEmpty()) {
 try {
 ep.media.put(
 "_qualityIdx",
 qIdx + 1);
 } catch (
 Exception ignored) {
 }
 ep.url =
 LocalProxy.rewrite(
 nextUrl);
 String qName =
 nextQ.optString(
 "name",
 "备用线路");
 a.playStatus.setText(
 "正在切换至 " + qName
 + "…");
 a.ui.post(
 () -> {
 if (ticket
 == a.generation)
 playerPage(
 a,
 s,
 v,
 ep);
 });
 return true;
 }
 }
 if (PlaybackFailure.decoderFallback(
 what)
 && a.prefs.getInt(
 Prefs
 .PLAYER_ENGINE,
 0)
 == 0
 && !ep.media.optBoolean(
 "_softwareFallback")) {
 AppLog.i(
 "Player",
 "decode fallback software code="
 + what
 + " detail="
 + mp.getLastError());
 try {
 ep.media.put(
 "_softwareFallback",
 true);
 ep.media.put(
 "_fallbackMode",
 mp
 .softFallbackMode());
 } catch (Exception ignored) {
 }
 a.resumeAt =
 mp.getCurrentPosition();
 a.playStatus.setText(
 "正在尝试内置软解码…");
 a.ui.post(
 () -> {
 if (ticket
 == a.generation)
 playerPage(
 a, s, v,
 ep);
 });
 return true;
 }
 if (tryAutoSource(
 a, s, v, ep, "error"))
 return true;
 stopEmbySession(a);
 a.prepared = false;
 a.playing = false;
 if (a.playerChrome != null)
 a.playerChrome.setLoading(
 false);
 a.playStatus.setText(
 mp.getFailureMessage(what)
 + "（"
 + what
 + "）");
 showControls(a, true);
 return true;
 });
 a.playStatus.setText("正在加载播放…");
 ready.prepareAsync();
 } catch (Throwable error) {
 AppLog.w(
 "Playback",
 "player wiring failed",
 error);
 try {
 ready.release();
 } catch (Throwable ignored) {
 }
 failInPlaceSwitch(
 a, "播放器初始化异常，已跳过该片源");
 }
 });
 } catch (Exception ex) {
 AppLog.w("PlaybackController", "player init failed", ex);
 if (candidate != null) candidate.release();
 a.ui.post(
 () -> {
 if (ticket == a.generation) {
 if (a.playerChrome != null)
 a.playerChrome.setLoading(false);
 a.playStatus.setText(a.message(ex));
 showControls(a, true);
 }
 });
 }
 });
 }

 public void destroyed() {
 surfaceRevision++;
 AppLog.i(
 "Player",
 "lifecycle pause surface=detached revision=" + surfaceRevision);
 if (ticket == a.generation && a.player != null) a.player.setSurface(null);
 renderSurface = null;
 }
 });
 a.playbackControls.timeline.requestFocus();
 if ("live".equals(s.key)) showControls(a, false);
 }

 static void showControls(MainActivity a, boolean visible) {
 showControls(a, visible, false);
 }

 static void showControls(MainActivity a, boolean visible, boolean progressOnly) {
 if (!visible) finishSeek(a, false);
 if (a.miniMode && visible) return;
 boolean changed = a.controlsVisible != visible;
 a.controlsVisible = visible;
 if (a.playerChrome != null) {
 a.playerChrome.setProgressOnlyMode(visible && progressOnly && a.isTvDevice());
 }
 if (a.playbackControls != null) a.playbackControls.visible(visible);
 if (a.playerChrome != null) a.playerChrome.update();
 if (a.playerOverlay != null) {
 a.playerOverlay.animate().cancel();
 if (visible) {
 a.playerOverlay.setAlpha(1f);
 a.playerOverlay.setTranslationY(0f);
 a.playerOverlay.setVisibility(View.VISIBLE);
 if (changed && !a.reduced && !a.isTvDevice()) {
 a.playerOverlay.setAlpha(0f);
 a.playerOverlay.animate().alpha(1f).setDuration(150).start();
 }
 } else {
 if (a.reduced || a.isTvDevice()) {
 a.playerOverlay.setAlpha(0f);
 a.playerOverlay.setVisibility(View.GONE);
 } else {
 long hideDur = 140;
 UiCompat.startWithEndAction(
 a.playerOverlay.animate().alpha(0f).setDuration(hideDur),
 a.playerOverlay,
 hideDur,
 () -> {
 if (!a.controlsVisible && a.playerOverlay != null)
 a.playerOverlay.setVisibility(View.GONE);
 });
 }
 a.root.requestFocus();
 }
 }
 if (visible) {
 if (changed && a.playbackControls != null && a.isTvDevice()) {
 // 1.9.54：控制条默认焦点放「下一集」而不是进度条，方向键才能在按钮间移动
 View first = a.playerChrome == null ? null : a.playerChrome.state;
 if (first == null) first = a.playbackControls.dockFirst;
 if (first == null) first = a.playbackControls.buttons.get("next-episode");
 if (first == null) first = a.playbackControls.timeline;
 if (first != null) {
 first.requestFocus();
 AppLog.i(
 "PlayerFocus",
 "default="
 + (first == a.playbackControls.timeline
 ? "timeline"
 : ("play-pause".equals(String.valueOf(first.getTag()))
 ? "play-pause"
 : String.valueOf(first.getTag()))));
 }
 AppLog.i(
 "TvKey",
 "page=player controls=visible focus="
 + (first == null ? "null" : String.valueOf(first.getTag()))
 + " action=show");
 }
 scheduleHide(a);
 } else if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
 }

 static boolean focusInControls(MainActivity a) {
 View focus = a.getCurrentFocus();
 for (View v = focus; v != null; ) {
 if (a.playerChrome != null
 && (v == a.playerChrome.overlay || v == a.playerChrome.state)) return true;
 if (a.playbackControls != null
 && (v == a.playbackControls.dockOverlay || v == a.playbackControls.timeline))
 return true;
 if (!(v.getParent() instanceof View)) break;
 v = (View) v.getParent();
 }
 return false;
 }

 static void scheduleHide(MainActivity a) {
 if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
 // 1.9.99：焦点在控制条内时暂停自动隐藏；离开控制条后才重新 3s 计时
 if (a.controlsVisible && focusInControls(a)) {
 AppLog.i("PlayerChrome", "autohide paused=focus");
 return;
 }
 AppLog.i("PlayerChrome", "autohide resumed idle=3000");
 a.hideControls =
 () -> {
 if (!a.controlsVisible) return;
 if (focusInControls(a)) {
 AppLog.i("PlayerChrome", "autohide paused=focus");
 return;
 }
 if (a.prepared
 && a.playing
 && (a.playbackControls == null || !a.playbackControls.dialogOpen)) {
 // 隐藏前把焦点交回视频区，避免控件已隐藏仍持焦
 View focus = a.getCurrentFocus();
 if (focus != null && focusInControls(a)) a.root.requestFocus();
 AppLog.i("PlayerChrome", "autohide hidden=true");
 showControls(a, false);
 }
 };
 a.ui.postDelayed(a.hideControls, 3000);
 }

 static void clock(MainActivity a, int ticket) {
 if (ticket != a.generation || a.player == null || !a.prepared) return;
 savePlaybackHistory(a, false);
 try {
 if (a.embyReporter != null)
 a.embyReporter.update(a.player.getCurrentPosition(), !a.player.isPlaying());
 if (a.seekGesture.active) previewSeek(a);
 else if (a.playbackControls == null || !a.playbackControls.isUserSeeking()) {
 int rawPos = a.player.getCurrentPosition();
 int displayPos =
 a.playbackControls != null
 ? a.playbackControls.displayPosition(rawPos)
 : rawPos;
 int dur = a.player.getDuration();
 if (dur <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
 dur = a.playingBookmark.duration;
 }
 a.playClock.setText(
 formatTime(displayPos)
 + " / "
 + (dur > 0 ? formatTime(dur) : (a.isLive() ? "直播" : "--:--")));
 if (a.playbackControls != null) a.playbackControls.tick();
 }
 if (a.playerChrome != null) a.playerChrome.update();
 } catch (Exception ignored) {
 }
 a.ui.postDelayed(() -> clock(a, ticket), 1000);
 }

 static void togglePlayback(MainActivity a) {
 if (a.player == null || !a.prepared) return;
 if (a.embyReporter == null
 && a.playbackControls != null
 && a.playbackControls.site.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)) {
 a.resumeAt = -1;
 resolvePlayer(
 a,
 a.playbackControls.site,
 a.playbackControls.video,
 a.activeLine,
 a.activeEpisode);
 return;
 }
 try {
 if (a.player.isPlaying()) {
 a.player.pause();
 a.playing = false;
 savePlaybackHistory(a, true);
 a.playStatus.setText(a.isTvDevice() ? "已暂停 · 按确认继续" : "已暂停 · 双击继续");
 } else {
 if (a.player.getDuration() > 0
 && a.player.getCurrentPosition() >= a.player.getDuration() - 300)
 a.player.seekTo(0);
 a.player.start();
 a.playing = true;
 a.playStatus.setText(
 a.isTvDevice()
 ? "正在播放 · 确认暂停 · 上下键显示工具条"
 : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量");
 }
 if (a.playerChrome != null) a.playerChrome.update();
 if (a.embyReporter != null)
 a.embyReporter.update(a.player.getCurrentPosition(), !a.playing);
 } catch (Exception error) {
 AppLog.w("PlaybackController", "togglePlayback failed", error);
 }
 }

 static void beginSeek(MainActivity a, int direction) {
 if (a.player == null || !a.prepared || a.player.getDuration() <= 0) return;
 int position = a.seekGesture.active ? a.seekGesture.target : a.player.getCurrentPosition();
 if (!a.seekGesture.active) a.seekStatus = a.playStatus.getText().toString();
 a.seekGesture.begin(
 direction, position, a.player.getDuration(), SystemClock.uptimeMillis());
 showControls(a, true, a.isTvDevice());
 if (a.playbackControls != null && a.isTvDevice()) {
 a.playbackControls.timeline.requestFocus();
 }
 previewSeek(a);
 a.ui.removeCallbacks(a.seekPulse);
 a.ui.postDelayed(a.seekPulse, 100);
 }

 static void previewSeek(MainActivity a) {
 if (!a.seekGesture.active || a.playbackControls == null) return;
 a.playbackControls.setTimelineProgress(a.seekGesture.target);
 a.playClock.setText(
 formatTime(a.seekGesture.target) + " / " + formatTime(a.seekGesture.duration));
 long now = SystemClock.uptimeMillis();
 int seekSpeed = a.seekGesture.speed(now);
 boolean accel = a.seekGesture.accelerated(now);
 String msg =
 (a.seekGesture.direction > 0 ? "快进" : "快退")
 + (seekSpeed > 0
 ? (accel ? " · 加速 " : " · ") + seekSpeed + " 秒/秒"
 : " · 15 秒")
 + " · 松开跳转";
 if (a.playStatus != null) a.playStatus.setText(msg);
 if (a.playerChrome != null && a.playerChrome.seekHint != null) {
 a.playerChrome.seekHint.setText(msg);
 }
 }

 static void finishSeek(MainActivity a, boolean commit) {
 if (!a.seekGesture.active) return;
 a.seekGesture.advance(SystemClock.uptimeMillis());
 int target = a.seekGesture.target;
 a.seekGesture.cancel();
 a.ui.removeCallbacks(a.seekPulse);
 if (commit && a.player != null && a.prepared) {
 a.player.seekTo(target);
 if (a.playingBookmark != null) {
 a.playingBookmark.position = Math.max(0, target);
 }
 savePlaybackHistory(a, true);
 }
 if (a.playStatus != null) a.playStatus.setText(a.seekStatus);
 if (a.playerChrome != null && a.playerChrome.seekHint != null) {
 a.playerChrome.seekHint.setText("");
 }
 if (a.player != null && a.prepared && a.playbackControls != null) {
 int position = commit ? target : a.player.getCurrentPosition();
 a.playbackControls.setTimelineProgress(position);
 a.playClock.setText(formatTime(position) + " / " + formatTime(a.player.getDuration()));
 }
 if (commit) scheduleHide(a);
 }

 static void savePlaybackHistoryOnPrepared(MainActivity a) {
 if (a.privateMode) return;
 if (a.player == null || !a.prepared || a.playingBookmark == null) return;
 try {
 int rawPos = a.player.getCurrentPosition();
 int displayPos =
 a.playbackControls != null
 ? a.playbackControls.displayPosition(rawPos)
 : rawPos;
 if (a.playbackControls != null
 && a.playbackControls.pendingResumeMs > 0
 && displayPos < 2000) {
 displayPos = a.playbackControls.pendingResumeMs;
 }
 if (displayPos >= 1000 || a.playingBookmark.position <= 0) {
 a.playingBookmark.position = Math.max(0, displayPos);
 }
 int dur = a.player.getDuration();
 if (dur > 0) {
 a.playingBookmark.duration = dur;
 }
 a.playingBookmark.updated = System.currentTimeMillis();
 a.prefs
 .edit()
 .putString(
 Prefs.WATCH_HISTORY,
 WatchHistory.upsert(
 a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
 a.playingBookmark))
 .commit();
 a.lastBookmarkSave = a.playingBookmark.updated;
 } catch (Exception error) {
 AppLog.w("PlaybackController", "savePlaybackHistoryOnPrepared failed", error);
 }
 }

 static void savePlaybackHistory(MainActivity a, boolean force) {
 if (a.privateMode) return;
 if (a.player == null || !a.prepared || a.playingBookmark == null) return;
 long now = System.currentTimeMillis();
 if (!force && now - a.lastBookmarkSave < 2000) return;
 try {
 int rawPos = a.player.getCurrentPosition();
 int position =
 a.playbackControls != null
 ? a.playbackControls.displayPosition(rawPos)
 : rawPos;
 if (a.playbackControls != null
 && a.playbackControls.pendingResumeMs > 0
 && position < 2000) {
 position = a.playbackControls.pendingResumeMs;
 }
 if (position < 1000 && a.playingBookmark.position == 0) return;
 if (position < 1000 && a.playingBookmark.position >= 1000) {
 position = a.playingBookmark.position;
 }
 a.playingBookmark.position = Math.max(0, position);
 int dur = a.player.getDuration();
 if (dur > 0) {
 a.playingBookmark.duration = dur;
 }
 a.playingBookmark.updated = now;
 a.prefs
 .edit()
 .putString(
 Prefs.WATCH_HISTORY,
 WatchHistory.upsert(
 a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
 a.playingBookmark))
 .commit();
 a.lastBookmarkSave = now;
 } catch (Exception error) {
 AppLog.w("PlaybackController", "savePlaybackHistory failed", error);
 }
 }

 static void stopEmbySession(MainActivity a) {
 if (a.embyReporter == null) return;
 int position = 0;
 try {
 if (a.player != null && a.prepared) position = a.player.getCurrentPosition();
 } catch (Exception ignored) {
 }
 a.embyReporter.stop(position);
 a.embyReporter = null;
 }

 /** 播放页首帧布局诊断：layer/surface/overlay 尺寸与 chrome 安全区，只写一次运行日志。 */
 private static void logPlaybackLayout(
 final MainActivity a, final int ticket, final int attempt) {
 if (layoutLogged || ticket != a.generation || a.isFinishing()) return;
 if (a.playbackLayer == null
 || a.playbackLayer.getWidth() <= 0
 || a.playbackLayer.getHeight() <= 0) {
 if (attempt < 10)
 a.ui.postDelayed(() -> logPlaybackLayout(a, ticket, attempt + 1), 50);
 return;
 }
 layoutLogged = true;
 View video = a.playbackLayer.findViewWithTag("video-output");
 ViewGroup.LayoutParams vlp = video == null ? null : video.getLayoutParams();
 int sw =
 vlp instanceof FrameLayout.LayoutParams
 ? ((FrameLayout.LayoutParams) vlp).width
 : -1;
 int sh =
 vlp instanceof FrameLayout.LayoutParams
 ? ((FrameLayout.LayoutParams) vlp).height
 : -1;
 int ow = 0, oh = 0;
 String safe = "?";
 if (a.playerChrome != null && a.playerChrome.overlay != null) {
 ow = a.playerChrome.overlay.getWidth();
 oh = a.playerChrome.overlay.getHeight();
 android.graphics.Rect r = a.playerChrome.windowInsets;
 safe = r.left + "," + r.top + "," + r.right + "," + r.bottom;
 }
 AppLog.i(
 "Player",
 "layout layer="
 + a.playbackLayer.getWidth()
 + "x"
 + a.playbackLayer.getHeight()
 + " surface="
 + sw
 + "x"
 + sh
 + " actual="
 + (video == null ? "?" : video.getWidth() + "x" + video.getHeight())
 + " overlay="
 + ow
 + "x"
 + oh
 + " safe=["
 + safe
 + "]"
 + " fill="
 + a.prefs.getInt(Prefs.ASPECT, 4));
 }

 static void releasePlayer(MainActivity a) {
 finishSeek(a, false);
 a.clearLiveNumber();
 savePlaybackHistory(a, true);
 stopEmbySession(a);
 // 1.9.59：释放播放器同时取消后台通知/音频焦点，避免离开详情后仍有声音
 try {
 a.bgPlayback = false;
 a.stopBackgroundPlayback();
 } catch (Throwable ignored) {
 }
 // 1.9.52：清理 MiniDetails/播放层残留，避免返回详情后出现巨幅贴图/黑罩/「返回全屏播放」
 int layers = 0;
 if (a.miniDetails != null) {
 try {
 a.miniDetails.remove();
 } catch (Throwable ignored) {
 }
 a.miniDetails = null;
 layers++;
 }
 a.miniMode = false;
 a.expandedOverDetail = false;
 if (a.playbackLayer != null) {
 a.playbackLayer.setVisibility(View.GONE);
 layers++;
 }
 if (a.playbackControls != null) a.playbackControls.close();
 a.playbackControls = null;
 a.prepared = false;
 a.playing = false;
 if (a.player != null) {
 try {
 a.player.release();
 } catch (Exception error) {
 AppLog.w("PlaybackController", "player.release failed", error);
 }
 a.player = null;
 layers++;
 }
 a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 AppLog.i("Detail", "resume player=cleared layers=" + layers);
 a.ui.postDelayed(() -> UiAudit.audit(a, "player-exit"), 300);
 }

 /** 1.9.65：回前台/网络变化后用同一 URL 自动重连（保留进度，最多 2 次）。 */
 static void retryAfterResume(MainActivity a) {
 retryPlayer(a, "resume", 0);
 }

 private static void retryPlayer(final MainActivity a, final String reason, final int attempt) {
 if (a == null
 || a.player == null
 || a.playbackControls == null
 || a.miniDetails == null && a.current == null) return;
 if (!a.player.hasPlaybackError()) {
 if (attempt == 0)
 AppLog.i("Player", "retry skip reason=" + reason + " result=no-error");
 return;
 }
 if (attempt >= 2) {
 AppLog.i(
 "Player",
 "retry attempt=" + (attempt + 1) + " url=same result=fail reason=" + reason);
 if (a.playerChrome != null)
 a.playerChrome.showLoadingError(
 "媒体连接失败，请检查网络后重试",
 () -> {
 if (a.playbackControls != null) a.playbackControls.reload(true);
 });
 return;
 }
 final int position = a.player.getCurrentPosition();
 AppLog.i(
 "Player",
 "retry attempt="
 + (attempt + 1)
 + " url=same pos="
 + position
 + " reason="
 + reason);
 try {
 a.player.prepareAsync();
 } catch (Throwable error) {
 AppLog.w("Player", "retry prepare failed", error);
 if (a.playerChrome != null) a.playerChrome.setLoading(true);
 a.ui.postDelayed(() -> retryPlayer(a, reason, attempt + 1), 3000);
 return;
 }
 a.ui.postDelayed(
 () -> {
 if (a.player == null) return;
 if (a.player.hasPlaybackError()) {
 AppLog.i(
 "Player",
 "retry attempt="
 + (attempt + 1)
 + " url=same pos="
 + position
 + " result=fail");
 long delay = attempt == 0 ? 1000 : 3000;
 a.ui.postDelayed(() -> retryPlayer(a, reason, attempt + 1), delay);
 return;
 }
 a.player.clearPlaybackError();
 try {
 if (position > 0) a.player.seekTo(position);
 } catch (Throwable ignored) {
 }
 if (a.playerChrome != null) {
 a.playerChrome.setLoading(false);
 a.playerChrome.hideLoadingError();
 }
 if (a.playbackControls != null && a.playbackControls.timeline != null)
 a.playbackControls.timeline.requestFocus();
 AppLog.i(
 "Player",
 "retry attempt="
 + (attempt + 1)
 + " url=same pos="
 + position
 + " result=ok");
 },
 1500);
 }

 static boolean dispatchPlaybackKey(MainActivity a, KeyEvent event) {
 if (a.current == null
 || !a.current.player
 || a.miniMode
 || (a.playbackControls != null && a.playbackControls.dialogOpen)) return false;
 int key = event.getKeyCode();
 boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
 View focus = a.getCurrentFocus();
 boolean horizontal =
 key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
 if (a.seekGesture.active && !horizontal && down)
 finishSeek(a, key != KeyEvent.KEYCODE_BACK);
 boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
 // 1.9.92：进度条 OK = 切换「可拖动/可跳转」提示，左右为快退/快进
 if (confirm
 && a.playbackControls != null
 && focus == a.playbackControls.timeline) {
 if (down && event.getRepeatCount() == 0) {
 a.toast("进度条：左右快退/快进（长按加速），上下切换区域");
 AppLog.i("PlayerFocus", "zone=progress action=confirm mode=seek");
 }
 return true;
 }
 boolean navKey =
 key == KeyEvent.KEYCODE_DPAD_LEFT
 || key == KeyEvent.KEYCODE_DPAD_RIGHT
 || key == KeyEvent.KEYCODE_DPAD_UP
 || key == KeyEvent.KEYCODE_DPAD_DOWN;
 // 1.9.54：控制条可见时方向键优先做焦点导航（不再被左右快进抢走）
 if (a.controlsVisible && navKey && !a.seekGesture.active) {
 View timeline = a.playbackControls == null ? null : a.playbackControls.timeline;
 boolean onControl = focus != null && focus != a.root && focus != timeline;
 if (onControl) {
 AppLog.i(
 "TvKey",
 "page=player controls=visible focus="
 + (focus.getTag() == null ? "null" : focus.getTag())
 + " action=nav");
 return false;
 }
 if (focus == null || focus == a.root) {
 View target =
 a.playbackControls == null
 ? null
 : a.playbackControls.buttons.get("next-episode");
 if (target != null) {
 target.requestFocus();
 AppLog.i(
 "TvKey",
 "page=player controls=visible focus=next-episode action=nav");
 return true;
 }
 }
 }
 if (confirm
 && (!a.controlsVisible
 || focus == a.root
 || a.playbackControls != null && focus == a.playbackControls.timeline)) {
 if (down && event.getRepeatCount() == 0) {
 togglePlayback(a);
 showControls(a, true);
 AppLog.i("TvKey", "page=player controls=toggle action=confirm");
 }
 return true;
 }
 if ((!a.controlsVisible || (a.playerChrome != null && a.playerChrome.progressOnlyMode))
 && (key == KeyEvent.KEYCODE_DPAD_UP
 || key == KeyEvent.KEYCODE_DPAD_DOWN
 || key == KeyEvent.KEYCODE_MENU)) {
 if (down) {
 showControls(a, true, false);
 if (a.playbackControls != null)
 a.playbackControls.buttons.get("next-episode").requestFocus();
 AppLog.i("TvKey", "page=player controls=hidden focus=null action=show");
 }
 return true;
 }
 if (a.controlsVisible && key == KeyEvent.KEYCODE_MENU) {
 if (down && event.getRepeatCount() == 0) {
 if (a.playbackControls != null
 && focus == a.playbackControls.timeline
 && a.playbackControls.buttons.get("next-episode") != null) {
 a.playbackControls.buttons.get("next-episode").requestFocus();
 } else {
 showControls(a, false);
 }
 }
 return true;
 }
 if (horizontal
 && !a.isLive()
 && (a.seekGesture.active
 || !a.controlsVisible
 || focus == a.root
 || a.playbackControls != null && focus == a.playbackControls.timeline)) {
 int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
 if (down) {
 if (event.getRepeatCount() > 0 && a.seekGesture.active) {
 // 1.9.61：按住方向键连续拖动，系统重复键只加速不抖动
 a.seekGesture.advance(android.os.SystemClock.uptimeMillis());
 previewSeek(a);
 AppLog.i(
 "TvKey",
 "page=player focus="
 + (focus == null ? "null" : String.valueOf(focus.getTag()))
 + " action=longseek step="
 + a.seekGesture.stepMs);
 return true;
 }
 if (!a.seekGesture.active && event.getRepeatCount() == 0
 || a.seekGesture.active
 && a.seekGesture.direction != direction
 && event.getRepeatCount() == 0) beginSeek(a, direction);
 } else if (event.getAction() == KeyEvent.ACTION_UP
 && a.seekGesture.active
 && a.seekGesture.direction == direction) finishSeek(a, !event.isCanceled());
 AppLog.i(
 "TvKey",
 "page=player controls="
 + (a.controlsVisible ? "visible" : "hidden")
 + " focus="
 + (focus == null ? "null" : String.valueOf(focus.getTag()))
 + " action=seek");
 return true;
 }
 if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
 || key == KeyEvent.KEYCODE_MEDIA_PLAY
 || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
 if (down && event.getRepeatCount() == 0) {
 if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
 || (key == KeyEvent.KEYCODE_MEDIA_PLAY && !a.playing)
 || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && a.playing)) togglePlayback(a);
 showControls(a, true);
 }
 return true;
 }
 if (a.controlsVisible && down) scheduleHide(a);
 return false;
 }
}
