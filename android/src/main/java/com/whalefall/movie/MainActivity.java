package com.whalefall.movie;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import androidx.recyclerview.widget.RecyclerView;

/** Independent first native build. No original APK code or artwork is embedded. */
public final class MainActivity extends Activity {
 static final int WHITE = Skin.INK, MUTED = Skin.INK_DIM, DARK = Skin.BG;
 static final int PINK = Skin.PINK;
 VideoWallpaper videoWallpaper;
 /** 1.9.180：本次失败仅回退静态，切页后自动重试动态壁纸。 */
 boolean videoWallpaperRetryPending;
 AdminConsole adminConsole;
 View screensaverOverlay;
 final Runnable screensaverTask = this::showScreensaver;
 static final long SCREENSAVER_DELAY_MS = 5 * 60 * 1000L;
 final Handler ui = new Handler(Looper.getMainLooper());
 final ExecutorService worker = Executors.newFixedThreadPool(4);
 final ExecutorService details = Executors.newFixedThreadPool(4);
 final DetailCache detailCache = new DetailCache();
 final SingleFlight<String> detailFlights = new SingleFlight<>();
 final List<Future<?>> pageRequests = new ArrayList<>();
 final ExecutorService artwork = Executors.newFixedThreadPool(8);
 final List<Future<?>> artworkRequests = new ArrayList<>();
 final ExecutorService phoneticWork = Executors.newSingleThreadExecutor();
 final LinkedHashSet<String> pendingTitles = new LinkedHashSet<>();
 final Runnable learnPendingTitles = this::learnPendingTitles;

 void learnPendingTitles() {
 SourceController.learnPendingTitles(this);
 }

 UpdateScreen updateScreen;
 final Runnable automaticUpdate =
 new Runnable() {
 public void run() {
 if (!foreground) return;
 if (current != null && current.home && appDialog == null) {
 UpdateManager updates = UpdateManager.get(MainActivity.this);
 updates.automatic();
 if (updates.consumeNotice()) toast("发现新版，可在设置 → 软件更新查看");
 }
 ui.postDelayed(this, 60000);
 }
 };
 PlaybackLoading playbackLoading;
 /** 1.9.89：解析超时/失败后的重试动作。 */
 Runnable playbackRetry;
 /** 1.9.120：seek 节流（最多 400ms 一次）。 */
 long lastSeekAt;
 int pendingSeek = -1;
 Runnable seekTask;
 View playbackLoadingFocus;
 int playbackResolveSerial;
 GlobalSearchScreen searchScreen;
 Dialog sourceDialog, accountDialog;
 MagnetStream magnetStream;
 static volatile PhoneticSearchIndex sharedPhonetic;
 volatile PhoneticSearchIndex phonetic;
 final CountDownLatch phoneticReady = new CountDownLatch(1);
 ConfigStore configStore;
 PhoneConfigDialog phoneConfig;
 LiveSession liveSession;
 final Runnable tuneLiveNumber = this::commitLiveNumber;
 LocalProxy localProxy;
 WebResolver webResolver;
 TvLive.Player tvPlayer;
 PlaybackControls playbackControls;
 Cms.Line activeLine;
 Cms.Episode activeEpisode;
 int resumeAt;
 WatchHistory.Entry playingBookmark;
 long lastBookmarkSave;
 final ArrayDeque<Screen> back = new ArrayDeque<>();
 final LruCache<String, Bitmap> images =
 // 1.9.148：海报缓存按 maxMemory/8 动态计算，低内存设备 24–32MB
 new LruCache<String, Bitmap>(imageCacheBytes()) {
 protected int sizeOf(String k, Bitmap b) {
 return b.getByteCount();
 }
 };

 /** 1.9.148：内存受限设备的海报缓存上限。 */
 static int imageCacheBytes() {
 long max = Runtime.getRuntime().maxMemory();
 long cap = Math.min(64L * 1024 * 1024, max / 8);
 if (max <= 256L * 1024 * 1024) cap = Math.min(cap, 20L * 1024 * 1024);
 return (int) Math.max(16L * 1024 * 1024, cap);
 }
 final HomeKeyTracker homeKeys = new HomeKeyTracker();
 Screen shortcutScreen;
 StartupIntro startupIntro;
 boolean introSkipKey;
 HomeAmbient homeAmbient;
 HomeDock homeDock;
 FrameLayout root;
 LinearLayout body;
 /** 当前页滚动容器：普通页面是 ScrollView/TvScrollView，首页是虚拟化的 RecyclerView。 */
 View scroll;
 Screen current;
 Cms.Config config;
 android.content.SharedPreferences prefs;
 boolean privateMode, switchingPrivacy;

 @Override
 public File getFilesDir() {
 File base = super.getFilesDir();
 if (!privateMode) return base;
 File scoped = new File(base, "private-space");
 scoped.mkdirs();
 return scoped;
 }

 @Override
 public File getCacheDir() {
 File base = super.getCacheDir();
 if (!privateMode) return base;
 File scoped = new File(base, "private-space");
 scoped.mkdirs();
 return scoped;
 }

 boolean reduced, prepared, playing, foreground;
 int generation;
 static final int MAX_HOME_SNAPSHOTS = 8;
 final Map<String, SourceEngine> engines = new HashMap<>();
 final SingleFlight<SourceEngine> engineInitializations = new SingleFlight<>();
 final Map<String, HomeScreen.Snapshot> homeData =
 Collections.synchronizedMap(
 new LinkedHashMap<String, HomeScreen.Snapshot>(16, 0.75f, true) {
 @Override
 protected boolean removeEldestEntry(
 Map.Entry<String, HomeScreen.Snapshot> eldest) {
 return size() > MAX_HOME_SNAPSHOTS;
 }
 });
 AppDialog appDialog;
 FrameLayout playbackLayer;
 MiniWindow miniDetails;

 /**
 * 1.9.386：缩小窗/放大过渡保护期。过渡期间 current 可能还是旧页面，
 * 不能让「非详情页就强制释放」把刚接管的播放器释放掉（真机日志：小窗刚建就 release）。
 */
 boolean miniTransitionGuard;

 /** 1.9.387：叠加层模式下被隐藏的页面子 View（保持 attached，只改可见性）。 */
 java.util.ArrayList<View> playerOverlayHidden;
 int playerLayerBaseW, playerLayerBaseH;
 /** 1.9.389：叠加层是否挂在 root 上（显式状态，不依赖 findViewWithTag）。 */
 boolean playerOverlayAttached;
 /** 1.9.389：当前是否处于叠加层小窗态（决定播放层用缩放还是 LayoutParams）。 */
 boolean overlayMiniActive;

 /**
 * 1.9.393：幂等的 UI 状态收敛。任何返回/释放/异常路径都可安全调用，
 * 保证不会停在「黑屏、页面被隐藏、mini/expanded 状态悬挂」的状态。
 */
 /** 1.9.403：移动端控件可遥控聚焦化（三端都要能用遥控，触摸不受影响）。 */
 void focusableForRemote(View v) {
 focusableForRemote(v, 12);
 }

 void focusableForRemote(View v, int radiusDp) {
 if (v == null) return;
 try {
 v.setFocusable(true);
 v.setFocusableInTouchMode(true);
 TvNav.focusRing(this, v, radiusDp);
 } catch (Throwable ignored) {
 }
 }

 void restoreUiState(String reason) {
 try {
 if (playerOverlayAttached) {
 if (playbackLayer != null) {
 playbackLayer.setAlpha(1f);
 playbackLayer.setVisibility(View.VISIBLE);
 }
 if (playerOverlayHidden != null) {
 for (View v : playerOverlayHidden) if (v != null) v.setVisibility(View.VISIBLE);
 }
 return;
 }
 if (playbackLayer != null) {
 playbackLayer.setVisibility(View.GONE);
 playbackLayer.setAlpha(1f);
 }
 if (root != null) {
 for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.VISIBLE);
 root.requestLayout();
 root.requestFocus();
 }
 miniMode = false;
 expandedOverDetail = false;
 overlayMiniActive = false;
 AppLog.i("Playback", "restoreUiState reason=" + reason);
 } catch (Throwable ignored) {
 }
 }
 boolean miniMode;
 /** 1.9.53：从小窗放大回全屏、但底下仍是详情页时使用（BACK 再缩回小窗）。 */
 boolean expandedOverDetail;
 /** 1.9.65：回前台重连与网络变化监听。 */
 long backgroundedAt;
 android.net.ConnectivityManager.NetworkCallback networkCallback;
 /** 1.9.66：手机作为被控端时进入遥控焦点模式。 */
 boolean remoteFocusMode;
 /** 1.9.97：Android 6 运行时存储权限。 */
 static final int REQ_STORAGE = 1001;
 Runnable storagePending;

 boolean hasStoragePermission() {
 if (android.os.Build.VERSION.SDK_INT < 23) return true;
 try {
 return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
 == android.content.pm.PackageManager.PERMISSION_GRANTED
 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
 == android.content.pm.PackageManager.PERMISSION_GRANTED;
 } catch (Throwable ignored) {
 return false;
 }
 }

 void requestStoragePermission(Runnable after) {
 if (hasStoragePermission()) {
 AppLog.i("Perm", "storage granted=true cached");
 if (after != null) after.run();
 return;
 }
 storagePending = after;
 try {
 requestPermissions(
 new String[] {
 android.Manifest.permission.READ_EXTERNAL_STORAGE,
 android.Manifest.permission.WRITE_EXTERNAL_STORAGE
 },
 REQ_STORAGE);
 } catch (Throwable error) {
 AppLog.w("Perm", "request storage failed", error);
 showStoragePermissionDialog(after);
 }
 }

 void showStoragePermissionDialog(final Runnable retry) {
 new AppDialog.Builder(this)
 .setTitle("未授予存储权限")
 .setMessage(
 "需要「存储」权限才能读取本地视频/图片、选择壁纸和保存崩溃日志。请在系统弹窗选择允许，或到系统设置手动授权。")
 .setNegativeButton(
 "去设置",
 (d, w) -> {
 try {
 startActivity(
 new android.content.Intent(
 android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
 android.net.Uri.parse("package:" + getPackageName())));
 } catch (Throwable ignored) {
 }
 })
 .setPositiveButton("重新授权", (d, w) -> requestStoragePermission(retry))
 .setPositiveDefaultFocus()
 .show();
 }

 @Override
 public void onRequestPermissionsResult(
 int requestCode, String[] permissions, int[] grantResults) {
 super.onRequestPermissionsResult(requestCode, permissions, grantResults);
 if (requestCode != REQ_STORAGE) return;
 boolean granted = grantResults.length > 0;
 for (int result : grantResults)
 if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) granted = false;
 AppLog.i("Perm", "storage granted=" + granted);
 if (granted) {
 toast("已授予存储权限");
 if (storagePending != null) {
 Runnable next = storagePending;
 storagePending = null;
 next.run();
 }
 } else {
 showStoragePermissionDialog(storagePending);
 }
 }
 /** 1.9.84：物理遥控按键即进入焦点模式（不依赖 LEANBACK 上报）。 */
 boolean keyFocusMode;
 /** 1.9.128：播放器长按 OK 进入进度拖动模式。 */
 boolean scrubMode;
 /** 1.9.102：播放器自动隐藏计时用的最后一次输入。 */
 String lastPlayerKey = "none";

 void enterRemoteFocus(String page) {
 try {
 // 1.9.75：只有遥控服务正在工作且 10s 内有客户端请求才进入焦点模式
 if (!LanRemoteServer.clientActiveWithin(10_000L)) {
 AppLog.i("Remote", "focusMode=skip reason=no-recent-client page=" + page);
 return;
 }
 remoteFocusMode = true;
 TvNav.setTouchFocusMode(this, true);
 TvNav.enableRemoteFocus(this, page);
 if (playbackControls != null) playbackControls.refreshFocusMode();
 scheduleFocusAudit();
 } catch (Throwable error) {
 AppLog.w("Remote", "enterRemoteFocus failed", error);
 }
 }

 /** 1.9.95：手机遥控音量直接走系统 AudioManager（注入 KEYCODE 不会改音量）。 */
 int adjustRemoteVolume(String action) {
 try {
 android.media.AudioManager am =
 (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
 if (am == null) return -1;
 int dir;
 if ("vol+".equals(action)) dir = android.media.AudioManager.ADJUST_RAISE;
 else if ("mute".equals(action)) dir = android.media.AudioManager.ADJUST_TOGGLE_MUTE;
 else dir = android.media.AudioManager.ADJUST_LOWER;
 am.adjustStreamVolume(
 android.media.AudioManager.STREAM_MUSIC,
 dir,
 android.media.AudioManager.FLAG_SHOW_UI);
 int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
 int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
 AppLog.i("LanRemote", "volume=" + action + " level=" + cur + "/" + max);
 return Math.round(cur * 100f / max);
 } catch (Throwable error) {
 AppLog.w("LanRemote", "volume adjust failed", error);
 return -1;
 }
 }

 /** 1.9.95：遥控亮度用窗口 screenBrightness（电视上等价系统亮度，无需写 Settings 权限）。 */
 int adjustRemoteBrightness(int delta) {
 try {
 android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
 float current = lp.screenBrightness;
 if (current < 0f) current = 0.6f;
 current = Math.max(0.05f, Math.min(1f, current + delta * 0.1f));
 lp.screenBrightness = current;
 getWindow().setAttributes(lp);
 AppLog.i("LanRemote", "brightness=" + Math.round(current * 100) + "%");
 return Math.round(current * 100);
 } catch (Throwable error) {
 AppLog.w("LanRemote", "brightness adjust failed", error);
 return -1;
 }
 }

 int remoteVolumePercent() { try {
 android.media.AudioManager am =
 (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
 if (am == null) return -1;
 int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
 int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
 return Math.round(cur * 100f / max);
 } catch (Throwable ignored) {
 return -1;
 }
 }

 void exitRemoteFocus() { try {
 if (!remoteFocusMode) return;
 remoteFocusMode = false;
 TvNav.disableRemoteFocus(this);
 TvNav.setTouchFocusMode(this, false);
 ui.removeCallbacks(focusAuditTask);
 } catch (Throwable error) {
 AppLog.w("Remote", "exitRemoteFocus failed", error);
 }
 }

 /** 1.9.74：TV/被控端每 1s（低端/Reduced 2s）扫描可见树，补齐焦点。 */
 final Runnable focusAuditTask =
 new Runnable() {
 @Override
 public void run() {
 if (!(isTvDevice() || remoteFocusMode) || isFinishing()) return;
 // 1.9.75：遥控 10s 无请求立即退出焦点模式，避免历史状态误开
 if (remoteFocusMode && !LanRemoteServer.clientActiveWithin(10_000L)) {
 AppLog.i("Remote", "focusMode=off reason=idle");
 exitRemoteFocus();
 return;
 }
 TvNav.periodicAudit(
 MainActivity.this,
 body == null ? "page" : String.valueOf(body.getTag()));
 ui.postDelayed(this, auditIntervalMs());
 }
 };

 int auditIntervalMs() {
 return (reduced || android.os.Build.VERSION.SDK_INT < 26) ? 2000 : 1000;
 }

 void scheduleFocusAudit() {
 ui.removeCallbacks(focusAuditTask);
 // 1.9.83 热修：默认关闭周期审计，仅 debug_focus_audit=true 时开启
 if (!prefs.getBoolean("debug_focus_audit", false)) return;
 if (isTvDevice() || remoteFocusMode) ui.postDelayed(focusAuditTask, auditIntervalMs());
 }
 boolean changingMiniEpisode;
 FrameLayout playerOverlay;
 boolean controlsVisible = true;
 Runnable hideControls;
 PlayerChrome playerChrome;
 MoviePlayer player;
 /** 1.9.22 后台播放状态。 */
 boolean bgPlayback;
 /** 1.9.147：控制条隐藏时左右键直接 ±10 秒的提示气泡（独立于控制条，不唤出控制条）。 */
 TextView playerSeekHint;
 Runnable playerSeekHintHide;

 android.media.AudioManager bgAudioManager;
 android.media.AudioManager.OnAudioFocusChangeListener bgFocusListener;

 private String bgTitle() {
 try {
 if (activeEpisode != null && activeEpisode.name != null) return activeEpisode.name;
 } catch (Throwable ignored) {
 }
 return "正在播放";
 }

 private boolean acquireAudioFocus() {
 try {
 if (bgAudioManager == null)
 bgAudioManager =
 (android.media.AudioManager)
 getSystemService(android.content.Context.AUDIO_SERVICE);
 if (bgFocusListener == null) {
 bgFocusListener =
 change -> {
 AppLog.i("BgPlay", "focus=" + change);
 if (change == android.media.AudioManager.AUDIOFOCUS_LOSS
 || change
 == android.media.AudioManager
 .AUDIOFOCUS_LOSS_TRANSIENT) {
 if (player != null && player.isPlaying()) {
 player.pause();
 playing = false;
 BgPlay.show(this, false, bgTitle());
 }
 } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
 if (bgPlayback && player != null) {
 player.start();
 playing = true;
 BgPlay.show(this, true, bgTitle());
 }
 }
 };
 }
 int result =
 bgAudioManager.requestAudioFocus(
 bgFocusListener,
 android.media.AudioManager.STREAM_MUSIC,
 android.media.AudioManager.AUDIOFOCUS_GAIN);
 return result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
 } catch (Throwable ignored) {
 return false;
 }
 }

 private void releaseAudioFocus() {
 try {
 if (bgAudioManager != null && bgFocusListener != null)
 bgAudioManager.abandonAudioFocus(bgFocusListener);
 } catch (Throwable ignored) {
 }
 }

 void startBackgroundPlayback() {
 if (player == null) return;
 bgPlayback = true;
 acquireAudioFocus();
 BgPlay.show(this, playing, bgTitle());
 AppLog.i("BgPlay", "start focus=" + (bgAudioManager != null));
 }

 void toggleBackgroundPlayback() {
 if (player == null) return;
 try {
 if (player.isPlaying()) {
 player.pause();
 playing = false;
 AppLog.i("BgPlay", "toggle=pause");
 } else {
 player.start();
 playing = true;
 AppLog.i("BgPlay", "toggle=play");
 }
 } catch (Throwable ignored) {
 }
 BgPlay.show(this, playing, bgTitle());
 }

 void stopBackgroundPlayback() {
 bgPlayback = false;
 releaseAudioFocus();
 BgPlay.cancel(this);
 try {
 if (player != null) {
 player.pause();
 playing = false;
 }
 } catch (Throwable ignored) {
 }
 savePlaybackHistory(true);
 AppLog.i("BgPlay", "stop");
 }
 com.pilipala.emby.EmbyLibrary.Reporter embyReporter;
 String configOrigin = "";
 TextView playStatus, playClock;
 final SeekGesture seekGesture = new SeekGesture();
 String seekStatus = "";
 final Runnable seekPulse =
 new Runnable() {
 public void run() {
 if (!seekGesture.active) return;
 if (player == null
 || !prepared
 || current == null
 || !current.player
 || miniMode
 || !foreground) {
 finishSeek(false);
 return;
 }
 seekGesture.advance(SystemClock.uptimeMillis());
 previewSeek();
 scheduleHide();
 ui.postDelayed(this, 100);
 }
 };

 static final class Screen {
 MobileShell mobileHome;
 Runnable render;
 String focus, homeReturnFocus;
 int scroll;
 /** 首页 RecyclerView 的滚动锚点（item position + 顶部偏移）；-1 表示无。 */
 int scrollPos = -1;
 int scrollOffset;
 int episodeFocus = -1;
 String episodeFocusLine = "";
 final EpisodeSelection selection = new EpisodeSelection();
 boolean player, home;
 /** 1.9.167：仅详情页允许小窗保留播放。 */
 boolean detail;
 HomeScreen.State homeState;
 HomeScreen retainedHome;

 /** True while a listing redirect (folder / action / cross-search) has not drawn a page. */
 boolean redirect;
 /** 1.9.30：该 Screen 入栈时的页面 body tag，用于精确判断能否 pop。 */
 String pushedTag;
 /** 1.9.30：pop 回该页面时的恢复渲染（保留目录/列表状态）。 */
 Runnable resume;

 Screen(Runnable r) {
 render = r;
 }
 }

 /** Foreground activity that source extensions may need for dialogs; cleared on destroy. */
 static volatile MainActivity activeActivity;

 /** 1.9.22：云盘相关功能总开关（默认关闭）。 */
 static boolean cloudEnabled(MainActivity a) {
 return a != null && a.prefs.getBoolean("cloud_features_enabled", false);
 }

 /**
 * Extension background threads must never take the whole app down. Main-thread errors and
 * crashes without extension frames still reach the system handler unchanged.
 */
 static boolean isPluginBackgroundCrash(Thread thread, Throwable error, Thread mainThread) {
 return ListingRoute.isPluginBackgroundCrash(thread, error, mainThread);
 }

 private static volatile boolean crashGuardInstalled;

 static void installPluginCrashGuard() {
 if (crashGuardInstalled) return;
 crashGuardInstalled = true;
 final Thread.UncaughtExceptionHandler previous =
 Thread.getDefaultUncaughtExceptionHandler();
 final Thread main = android.os.Looper.getMainLooper().getThread();
 Thread.setDefaultUncaughtExceptionHandler(
 (thread, error) -> {
 boolean bgPlugin =
 isPluginBackgroundCrash(thread, error, main)
 || (thread != null
 && thread != main
 && thread.getContextClassLoader()
 instanceof dalvik.system.DexClassLoader);
 if (bgPlugin) {
 AppLog.w(
 "PluginGuard",
 "extension thread failed: " + thread.getName(),
 error);
 MainActivity activity = activeActivity;
 if (activity != null)
 activity.ui.post(
 () -> {
 if (!activity.isFinishing())
 activity.toast("来源扩展出错，已拦截，应用继续运行");
 });
 return;
 }
 if (thread == main && ListingRoute.hasPluginFrame(error)) {
 AppLog.w("PluginGuard", "extension main-thread callback failed", error);
 MainActivity activity = activeActivity;
 if (activity != null && activity.prefs != null) {
 activity.prefs
 .edit()
 .putString(Prefs.LOADING_SITE, "")
 .putString(Prefs.SELECTED_SITE, "")
 .commit();
 activity.ui.post(
 () -> {
 if (!activity.isFinishing())
 activity.toast("片源扩展异常已拦截，请切换其他片源");
 });
 }
 if (previous != null) previous.uncaughtException(thread, error);
 return;
 }
 MainActivity activity = activeActivity;
 if (thread == main) writeCrashLog(thread, error);
 if (activity != null && activity.prefs != null) {
 String currentSite = activity.prefs.getString(Prefs.SELECTED_SITE, "");
 if (currentSite != null && !currentSite.isEmpty()) {
 activity.prefs
 .edit()
 .putString(Prefs.LOADING_SITE, currentSite)
 .commit();
 }
 }
 if (previous != null) previous.uncaughtException(thread, error);
 });
 }

 /**
 * 主线程崩溃前把堆栈 + 最近 AppLog 落盘（files/crash-last.txt），
 * 下次启动仍可读，便于真机排查「点播放闪退」这类没有 logcat 的问题。
 */
 /** 用内置 Markdown 阅读器打开「片源与直播配置教程」。 */
 void openSourceGuide() {
 try {
 String rel = PushFiles.installAsset(this, "guide/source-and-live.md", "教程/片源与直播配置教程.md");
 MarkdownScreen.show(this, rel);
 } catch (Throwable e) {
 toast("教程打开失败：" + e.getMessage());
 }
 }

 /** 预览当前片源配置：把原始 JSON/脚本写到文件后用 Markdown 阅读器打开。 */
 void previewCurrentConfig() {
 try {
 if (config == null || config.raw == null || config.raw.trim().isEmpty()) {
 toast("当前还没有片源配置，先导入一个配置");
 return;
 }
 String rel = PushFiles.writeText(this, "配置/当前片源配置.txt", config.raw);
 MarkdownScreen.show(this, rel);
 } catch (Throwable e) {
 toast("预览失败：" + e.getMessage());
 }
 }

 /** 恢复为内置默认壁纸（鲸落主题图）：写回 wallpaper.jpg 并启用静态壁纸。 */
 void restoreDefaultWallpaper() {
 try {
 java.io.File target = new java.io.File(getFilesDir(), "wallpaper.jpg");
 java.io.InputStream in = getAssets().open("branding/wallpaper-default.jpg");
 java.io.FileOutputStream out = new java.io.FileOutputStream(target);
 byte[] buf = new byte[65536];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 in.close();
 out.close();
 prefs.edit()
 .putBoolean(Prefs.WALLPAPER_ENABLED, true)
 .putBoolean(Prefs.WALLPAPER_VIDEO, false)
 .apply();
 toast("已恢复默认背景");
 refresh();
 } catch (Throwable e) {
 toast("恢复默认背景失败：" + e.getMessage());
 }
 }

 /** 首次启动安装内置默认静态壁纸（鲸落主题图），之后用户可自行更换。 */
 private void installDefaultWallpaper() {
 try {
 final String flag = "wallpaper_default_v3";
 if (prefs.getBoolean(flag, false)) return;
 java.io.File target = new java.io.File(getFilesDir(), "wallpaper.jpg");
 java.io.InputStream in = getAssets().open("branding/wallpaper-default.jpg");
 java.io.FileOutputStream out = new java.io.FileOutputStream(target);
 byte[] buf = new byte[65536];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 in.close();
 out.close();
 boolean wrote = true;
 android.content.SharedPreferences.Editor editor =
 prefs.edit().putBoolean(flag, true);
 if (wrote && !prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false))
 editor.putBoolean(Prefs.WALLPAPER_ENABLED, true);
 editor.apply();
 } catch (Throwable ignored) {
 }
 }

 /** 设置内查看上次崩溃栈：可复制、可清空，并另存一份到内置文件管理。 */
 void showCrashLog() {
 String text = null;
 try {
 java.io.File src = new java.io.File(getFilesDir(), "crash-last.txt");
 if (src.isFile()) {
 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
 try (java.io.InputStream in = new java.io.FileInputStream(src)) {
 byte[] buf = new byte[8192];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 }
 text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
 }
 } catch (Throwable ignored) {
 }
 final String content = text;
 TextView view = new TextView(this);
 view.setTypeface(typeface());
 view.setTextSize(11f);
 view.setTypeface(android.graphics.Typeface.MONOSPACE);
 view.setTextColor(userTextColor(0xffd5e1f2));
 view.setLineSpacing(0f, 1.15f);
 view.setPadding(dp(12), dp(12), dp(12), dp(12));
 view.setBackground(MobileDetailScreen.glassBg(this, 0x66000000, 0x33ffffff, 14));
 view.setText(
 content == null || content.trim().isEmpty()
 ? "暂无崩溃记录。\n\nApp 正常运行时不会产生此文件；发生崩溃后会自动保存最近一次堆栈。"
 : content);
 view.setMaxLines(24);
 view.setVerticalScrollBarEnabled(true);
 view.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
 android.widget.ScrollView scroll = new android.widget.ScrollView(this);
 scroll.setVerticalScrollBarEnabled(false);
 scroll.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -2));
 android.widget.FrameLayout host = new android.widget.FrameLayout(this);
 int pad = dp(4);
 host.setPadding(pad, pad, pad, 0);
 host.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, dp(320)));

 AppDialog.Builder builder =
 new AppDialog.Builder(this)
 .setTitle("崩溃记录")
 .setView(host)
 .setNegativeButton("关闭", null);
 if (content != null && !content.trim().isEmpty()) {
 builder.setNeutralButton(
 "复制",
 (d, w) -> {
 android.content.ClipboardManager cm =
 (android.content.ClipboardManager)
 getSystemService(CLIPBOARD_SERVICE);
 cm.setPrimaryClip(android.content.ClipData.newPlainText("崩溃记录", content));
 toast("崩溃记录已复制");
 });
 builder.setPositiveButton(
 "清空记录",
 (d, w) -> {
 try {
 java.io.File src = new java.io.File(getFilesDir(), "crash-last.txt");
 if (src.exists()) //noinspection ResultOfMethodCallIgnored
 src.delete();
 java.io.File pushed =
 new java.io.File(getFilesDir(), "pushed/崩溃日志/上次崩溃.txt");
 if (pushed.exists()) //noinspection ResultOfMethodCallIgnored
 pushed.delete();
 } catch (Throwable ignored) {
 }
 toast("崩溃记录已清空");
 });
 }
 builder.show();
 }

 /** 把上次崩溃栈复制到应用外部目录，便于直接取出排查。 */
 private void exportCrashLog() {
 try {
 java.io.File src = new java.io.File(getFilesDir(), "crash-last.txt");
 if (!src.isFile()) return;
 java.io.File dir = getExternalFilesDir(null);
 if (dir == null) return;
 java.io.File dst = new java.io.File(dir, "crash-last.txt");
 if (dst.isFile() && dst.lastModified() >= src.lastModified()) return;
 java.io.FileInputStream in = new java.io.FileInputStream(src);
 java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
 byte[] buf = new byte[8192];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 in.close();
 out.close();
 } catch (Throwable ignored) {
 }
 }

 private static void writeCrashLog(Thread thread, Throwable error) {
 try {
 MainActivity activity = activeActivity;
 if (activity == null) return;
 java.io.File dir = activity.getFilesDir();
 if (dir == null) return;
 StringBuilder sb = new StringBuilder(1024);
 sb.append("time=")
 .append(
 android.text.format.DateFormat.format(
 "yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()))
 .append('\n')
 .append("thread=")
 .append(thread == null ? "?" : thread.getName())
 .append('\n');
 java.io.StringWriter writer = new java.io.StringWriter();
 error.printStackTrace(new java.io.PrintWriter(writer));
 sb.append(writer).append("\n--- recent app log ---\n").append(AppLog.dump());
 // 1.9.100：内部崩溃日志轮转，最多保留 crash-1..3 + crash-last
 int crashedRemoved = rotateCrashLogs(dir);
 AppLog.i("CrashLog", "rotate keep=3 removed=" + crashedRemoved);
 java.io.FileOutputStream out =
 new java.io.FileOutputStream(new java.io.File(dir, "crash-last.txt"));
 try {
 out.write(sb.toString().getBytes("UTF-8"));
 } finally {
 out.close();
 }
 try {
 java.io.FileOutputStream copy =
 new java.io.FileOutputStream(new java.io.File(dir, "crash-1.txt"));
 try {
 copy.write(sb.toString().getBytes("UTF-8"));
 } finally {
 copy.close();
 }
 } catch (Throwable ignored) {
 }
 // 1.9.84：同时写外部存储，用户可直接拷出 /sdcard/Download/DSH-Go/鲸落/crash-last.txt
 writeExternalCrashLog(sb.toString());
 } catch (Throwable ignored) {
 }
 }

 private static void writeExternalCrashLog(String text) {
 try {
 java.io.File external = android.os.Environment.getExternalStorageDirectory();
 if (external == null) return;
 java.io.File[] dirs = {
 new java.io.File(external, "Download/DSH-Go/鲸落"),
 new java.io.File(external, "Download/WhaleFall"),
 new java.io.File(external, "Download")
 };
 for (java.io.File dir : dirs) {
 try {
 if (!dir.isDirectory() && !dir.mkdirs()) continue;
 // 1.9.100：外部只保留最新 1 份，先删除旧 crash 文件
 java.io.File[] old = dir.listFiles();
 if (old != null)
 for (java.io.File f : old) {
 String n = f.getName();
 if (n.startsWith("crash-") || n.equals("crash-last.txt")) f.delete();
 }
 java.io.FileOutputStream out =
 new java.io.FileOutputStream(new java.io.File(dir, "crash-last.txt"));
 try {
 out.write(text.getBytes("UTF-8"));
 } finally {
 out.close();
 }
 return;
 } catch (Throwable ignored) {
 }
 }
 } catch (Throwable ignored) {
 }
 }

 /** 1.9.100：crash-last → crash-1 → crash-2 → crash-3，删除最旧。 */
 private static int rotateCrashLogs(java.io.File dir) {
 int removed = 0;
 try {
 java.io.File c1 = new java.io.File(dir, "crash-1.txt");
 java.io.File c2 = new java.io.File(dir, "crash-2.txt");
 java.io.File c3 = new java.io.File(dir, "crash-3.txt");
 java.io.File last = new java.io.File(dir, "crash-last.txt");
 if (c3.exists() && !c3.delete()) removed++;
 if (c2.exists()) c2.renameTo(c3);
 if (c1.exists()) c1.renameTo(c2);
 if (last.exists()) last.renameTo(c1);
 } catch (Throwable ignored) {
 }
 return removed;
 }

 /** 1.9.100：启动时清理 7 天前的崩溃日志（内部 + 外部）。 */
 void cleanupOldCrashLogs() {
 long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
 int removed = cleanCrashDir(getFilesDir(), cutoff);
 try {
 java.io.File external = android.os.Environment.getExternalStorageDirectory();
 if (external != null)
 removed += cleanCrashDir(new java.io.File(external, "Download/DSH-Go/鲸落"), cutoff);
 } catch (Throwable ignored) {
 }
 if (removed > 0) AppLog.i("CrashLog", "cleanup old=7d removed=" + removed);
 }

 private static int cleanCrashDir(java.io.File dir, long cutoff) {
 int removed = 0;
 try {
 java.io.File[] files = dir == null ? null : dir.listFiles();
 if (files == null) return 0;
 for (java.io.File f : files) {
 String name = f.getName();
 if (!name.startsWith("crash-") || !name.endsWith(".txt")) continue;
 if (f.lastModified() < cutoff && f.delete()) removed++;
 }
 } catch (Throwable ignored) {
 }
 return removed;
 }

 /** Opens a list card without leaving an invisible redirect page in the back stack. */
 void openListing(Cms.Site s, Cms.Video v) {
 if (v == null) return;
 // 1.9.328：进入详情前先关掉筛选浮层，避免残留 overlay 盖住预览/小窗
 VideoFilterDialog.dismiss(this);
 if (v.action != null && !v.action.isEmpty()) {
 sourceAction(s, v.action);
 return;
 }
 if ("folder".equalsIgnoreCase(v.tag)) {
 open(new Screen(() -> videos(s, v.id, v.title, 1, null)), true);
 return;
 }
 if (v.id != null && v.id.startsWith("msearch:")) {
 crossSearch(Cms.searchTitle(v));
 return;
 }
 Screen detailScreen = new Screen(() -> detail(s, v));
 detailScreen.detail = true;
 open(detailScreen, true);
 }

 /**
 * Called by a detail redirect that will not draw its own page. Restores the page underneath
 * so Back never re-runs the redirect forever.
 */
 void dropRedirect() {
 if (current != null && current.redirect && !back.isEmpty()) current = back.pop();
 }

 static boolean isRedirectListing(Cms.Video v) {
 return v != null && ListingRoute.isRedirect(v.tag, v.action, v.id);
 }

 /**
 * Detail pages reached for a folder, action or cross-search card hand over to the real page.
 * The invisible detail screen is removed first, so Back returns to the list instead of
 * re-opening the folder in a loop.
 */
 boolean redirectListing(Cms.Site s, Cms.Video v) {
 if (!isRedirectListing(v)) return false;
 if (current != null) current.redirect = true;
 if (back.isEmpty() && "folder".equalsIgnoreCase(v.tag)) {
 open(new Screen(() -> videos(s, v.id, v.title, 1, null)), false);
 return true;
 }
 dropRedirect();
 openListing(s, v);
 return true;
 }

 interface Work<T> {
 T run() throws Exception;
 }

 interface Done<T> {
 void accept(T value) throws Exception;
 }

 static int[] viewportHint;
 boolean layoutReady, layoutIntro;
 int usableWidth, usableHeight;
 int formFactor = TvLayoutPolicy.FORM_TV;
 /** 1.9.244：设备类型与分辨率档位（全局响应式基线）。 */
 String deviceType = "phone";
 String resClass = "1K";
 int deviceLongEdge;
 boolean deviceTouch, deviceLandscape;
 /** 1.9.178：最小宽度（dp），响应式断点用。 */
 int smallestWidthDp;

 static int detectFormFactor(Context base, int widthPixels, int heightPixels, int densityDpi) {
 int smallestDp =
 densityDpi > 0
 ? Math.round(Math.min(widthPixels, heightPixels) * 160f / densityDpi)
 : 360;
 if (viewportHint == null) {
 try {
 android.content.pm.ApplicationInfo appInfo =
 base.getPackageManager()
 .getApplicationInfo(
 base.getPackageName(),
 android.content.pm.PackageManager.GET_META_DATA);
 if (appInfo != null && appInfo.metaData != null) {
 String edition = appInfo.metaData.getString("com.whalefall.movie.EDITION");
 if ("mobile".equals(edition)) {
 return smallestDp >= 600
 ? TvLayoutPolicy.FORM_TABLET
 : TvLayoutPolicy.FORM_PHONE;
 }
 if ("tv".equals(edition)) {
 return TvLayoutPolicy.FORM_TV;
 }
 }
 } catch (Exception ignored) {
 }
 }
 boolean tvMode = false;
 try {
 UiModeManager uiMode = (UiModeManager) base.getSystemService(Context.UI_MODE_SERVICE);
 tvMode =
 uiMode != null
 && uiMode.getCurrentModeType()
 == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
 } catch (Exception ignored) {
 }
 boolean hasTouch = false;
 try {
 hasTouch =
 base.getPackageManager()
 .hasSystemFeature(
 android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
 } catch (Exception ignored) {
 }
 return TvLayoutPolicy.formFactor(tvMode, hasTouch, smallestDp);
 }

 boolean isTvDevice() {
 return formFactor == TvLayoutPolicy.FORM_TV;
 }

 boolean isPortrait() {
 return TvLayoutPolicy.isPortrait(windowWidth(), windowHeight());
 }

 boolean useBottomDock() {
 return TvLayoutPolicy.useBottomDock(windowWidth(), windowHeight(), formFactor);
 }

 void applyMobileSystemBars(boolean fullscreenPlayer) {
 if (isTvDevice()) return;
 try {
 Window w = getWindow();
 if (w == null) return;
 WindowManager.LayoutParams lp = w.getAttributes();
 try {
 java.lang.reflect.Field f = lp.getClass().getField("layoutInDisplayCutoutMode");
 // 1 == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
 if (f.getInt(lp) != 1) {
 f.setInt(lp, 1);
 w.setAttributes(lp);
 }
 } catch (Throwable ignored) {
 }
 if (fullscreenPlayer) {
 w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
 w.getDecorView().setSystemUiVisibility(5894);
 } else {
 w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
 w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
 w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
 w.getDecorView()
 .setSystemUiVisibility(
 View.SYSTEM_UI_FLAG_LAYOUT_STABLE
 | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
 w.setStatusBarColor(Color.TRANSPARENT);
 w.setNavigationBarColor(0xff060a08);
 }
 } catch (Throwable ignored) {
 }
 }

 void togglePlayerOrientation() {
 if (isTvDevice()) return;
 // 1.9.178：手机播放器固定横屏，禁止切竖屏
 if (formFactor == TvLayoutPolicy.FORM_PHONE) {
 AppLog.i("Orient", "form=phone page=player set=landscape reason=phone-fixed");
 return;
 }
 if (isPortrait()) {
 setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
 } else {
 setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
 }
 if (playerChrome != null) playerChrome.relayout();
 }

 int windowWidth() {
 return usableWidth > 0 ? usableWidth : getResources().getDisplayMetrics().widthPixels;
 }

 int windowHeight() {
 return usableHeight > 0 ? usableHeight : getResources().getDisplayMetrics().heightPixels;
 }

 @Override
 protected void attachBaseContext(Context base) {
 android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
 ((WindowManager) base.getSystemService(Context.WINDOW_SERVICE))
 .getDefaultDisplay()
 .getMetrics(screen);
 int detectedForm =
 detectFormFactor(base, screen.widthPixels, screen.heightPixels, screen.densityDpi);
 int percent = base.getSharedPreferences(Prefs.FILE, 0).getInt(Prefs.UI_SCALE_PERCENT, 100);
 int[] hint = viewportHint;
 if (hint != null && hint[0] == screen.widthPixels && hint[1] == screen.heightPixels) {
 screen.widthPixels = hint[2];
 screen.heightPixels = hint[3];
 }
 android.content.res.Configuration layout =
 new android.content.res.Configuration(base.getResources().getConfiguration());
 layout.densityDpi =
 TvLayoutPolicy.deviceDensityDpi(
 screen.widthPixels, screen.heightPixels, percent, detectedForm);
 float density = layout.densityDpi / 160f;
 if (detectedForm == TvLayoutPolicy.FORM_TV) {
 layout.screenWidthDp =
 Math.round(Math.max(screen.widthPixels, screen.heightPixels) / density);
 layout.screenHeightDp =
 Math.round(Math.min(screen.widthPixels, screen.heightPixels) / density);
 } else {
 layout.screenWidthDp = Math.round(screen.widthPixels / density);
 layout.screenHeightDp = Math.round(screen.heightPixels / density);
 }
 layout.smallestScreenWidthDp = Math.min(layout.screenWidthDp, layout.screenHeightDp);
 super.attachBaseContext(base.createConfigurationContext(layout));
 }

 /** 1.9.174：一次性迁移旧 system 内核（此前自动回落/测试写入）到 exo。 */
 void migratePlayerKernel() {
 try {
 int code = 0;
 try {
 code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
 } catch (Throwable ignored) {
 }
 int last = prefs.getInt("last_version", 0);
 if (last < 19174 && "system".equals(prefs.getString(Prefs.PLAYER_KERNEL, "exo"))) {
 prefs.edit().putString(Prefs.PLAYER_KERNEL, "exo").apply();
 AppLog.i("Player", "kernel=migrate system->exo last=" + last + " code=" + code);
 }
 // 1.9.175：历史被强制写入的「软件解码」迁移回自动（硬解优先）
 if (last < 19175 && prefs.getInt(Prefs.DECODER_MODE, 0) == 2) {
 prefs.edit().putInt(Prefs.DECODER_MODE, 0).apply();
 AppLog.i("Player", "decode=migrate sw->auto last=" + last);
 }
 prefs.edit().putInt("last_version", code).apply();
 } catch (Throwable error) {
 AppLog.w("Player", "kernel migrate failed", error);
 }
 }

 /** 1.9.144：腾讯 X5(TBS) 内核初始化，老电视网页直播/网页播放兼容；失败自动回退系统 WebView。 */
 void initX5Kernel() {
 try {
 Class<?> qbs = Class.forName("com.tencent.smtt.sdk.QbSdk");
 Class<?> cb = Class.forName("com.tencent.smtt.sdk.QbSdk$PreInitCallback");
 Object callback =
 java.lang.reflect.Proxy.newProxyInstance(
 cb.getClassLoader(),
 new Class<?>[] {cb},
 (proxy, method, args) -> {
 AppLog.i("X5", "init callback=" + method.getName());
 return null;
 });
 qbs.getMethod("initX5Environment", android.content.Context.class, cb)
 .invoke(null, getApplicationContext(), callback);
 AppLog.i("X5", "init requested jar=44286");
 } catch (Throwable error) {
 AppLog.w("X5", "init failed fallback=system-webview", error);
 }
 }

 @Override
 public void onCreate(Bundle b) {
 super.onCreate(b);
 privateMode = PrivacySpace.active;
 if (privateMode) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
 activeActivity = this;
 initX5Kernel();
 installPluginCrashGuard();
 SafeMode.installCrashHandler(getApplication());
 SafeMode.onLaunch(getApplication());
 android.util.DisplayMetrics rawScreen = new android.util.DisplayMetrics();
 getWindowManager().getDefaultDisplay().getMetrics(rawScreen);
 formFactor =
 detectFormFactor(
 this, rawScreen.widthPixels, rawScreen.heightPixels, rawScreen.densityDpi);
 smallestWidthDp =
 getResources().getConfiguration().smallestScreenWidthDp;
 classifyDevice();
 setRequestedOrientation(
 isTvDevice()
 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
 : formFactor == TvLayoutPolicy.FORM_PHONE
 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
 : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
 configStore = new ConfigStore(getFilesDir());
 if (isTvDevice()) {
 getWindow().getDecorView().setSystemUiVisibility(5894);
 } else {
 applyMobileSystemBars(false);
 }
 prefs = getSharedPreferences(privateMode ? "movie-private" : Prefs.FILE, MODE_PRIVATE);
 // 1.9.327：全新安装默认主题=樱夜（底 #3A1F2B / 选中 #FF8FB1 / 文字 #FFE3EC）；老用户保留原设置
 if (!prefs.contains("theme_key") && !prefs.contains("glass_color")) {
  applyTheme("celadon", prefs);
 }
 // 1.9.301：启动即恢复全局色调（此前只在保存/onResume 时设置）
 applyGlassTint();
 migratePlayerKernel();
 reduced = prefs.getBoolean(Prefs.REDUCED, false);
 // 1.9.89：Android<8 强制 ExoPlayer，修正旧设备保存的系统播放器设置
 if (android.os.Build.VERSION.SDK_INT < 26
 && prefs.getInt(Prefs.PLAYER_ENGINE, 0) == 1) {
 prefs.edit().putInt(Prefs.PLAYER_ENGINE, 0).apply();
 ui.post(() -> toast("当前系统版本较低，已自动切换为内置播放器"));
 }
 configOrigin = prefs.getString("origin", "");
 ConfigHistory.backfill(this);
 try {
 ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
 if (am != null) images.resize(ImageDecodePolicy.cacheBytes(am.getMemoryClass()));
 } catch (Exception ignored) {
 }
 try {
 File f = new File(getFilesDir(), "config.json");
 if (f.exists())
 try (InputStream i = new FileInputStream(f)) {
 config = Cms.parseConfig(Cms.read(i, Cms.MAX_JSON), configOrigin);
 SourcePicker.applyEdits(this, config, configOrigin);
 SourcePreferences.restoreDefault(this);
 }
 } catch (Exception e) {
 AppLog.w("MainActivity", "load saved config failed", e);
 config = null;
 }
 if (!reduced && config != null) {
 final String savedKey = prefs.getString(Prefs.SELECTED_SITE, "");
 final String crashedKey = prefs.getString(Prefs.LOADING_SITE, "");
 if (savedKey != null
 && !savedKey.isEmpty()
 && (crashedKey == null || crashedKey.isEmpty())) {
 for (final Cms.Site s : config.sites) {
 if (s.key.equals(savedKey) && available(s)) {
 worker.execute(
 () -> {
 try {
 engine(s);
 } catch (Throwable ignored) {
 }
 });
 break;
 }
 }
 }
 }
 // Phone / tablet only: restore the offline download queue (never started on TV).
 if (!isTvDevice()) DownloadManager.get(this);
 if (sharedPhonetic != null) {
 phonetic = sharedPhonetic;
 phoneticReady.countDown();
 } else {
 worker.execute(
 () -> {
 try {
 PhoneticSearchIndex index =
 new PhoneticSearchIndex(
 getAssets().open("search/pinyin-characters.tsv"),
 getAssets().open("search/pinyin-phrases.dict.yaml"),
 getAssets().open("search/title-phrases.txt"));
 try {
 org.json.JSONArray old =
 new org.json.JSONArray(
 prefs.getString(Prefs.PHONETIC_TITLES, "[]"));
 for (int i = 0; i < old.length(); i++)
 index.learn(old.optString(i));
 org.json.JSONArray history =
 new org.json.JSONArray(
 prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
 for (int i = history.length() - 1; i >= 0; i--)
 index.prefer(history.optString(i));
 } catch (Throwable ignored) {
 }
 sharedPhonetic = index;
 phonetic = index;
 } catch (Throwable ignored) {
 } finally {
 phoneticReady.countDown();
 }
 });
 }
 layoutIntro = b == null || b.getBoolean("layout_initial_launch", false);
 exportCrashLog();
 installDefaultWallpaper();
 if (b != null) {
 MobileShell.pendingRestoreTab = b.getInt("whale_tab", -1);
 MobileShell.pendingHomeScroll = b.getInt("whale_home_scroll", -1);
 MobileShell.pendingHomeAnchorPos = b.getInt("whale_home_pos", -1);
 MobileShell.pendingHomeAnchorOffset = b.getInt("whale_home_off", 0);
 String keyword = b.getString("whale_search", "");
 MobileShell.pendingRestoreKeyword = keyword == null ? "" : keyword;
 }
 root = new FrameLayout(this);
 root.setBackground(Skin.backdrop(this));
 setContentView(root);
 root.addOnLayoutChangeListener(
 new View.OnLayoutChangeListener() {
 public void onLayoutChange(
 View view,
 int l,
 int t,
 int r,
 int bottom,
 int ol,
 int ot,
 int or,
 int ob) {
 int width = r - l, height = bottom - t;
 if (width <= 0 || height <= 0) return;
 view.removeOnLayoutChangeListener(this);
 usableWidth = width;
 usableHeight = height;
 int expected =
 TvLayoutPolicy.deviceDensityDpi(
 width,
 height,
 prefs.getInt(Prefs.UI_SCALE_PERCENT, 100),
 formFactor);
 if (viewportHint == null
 && expected != getResources().getDisplayMetrics().densityDpi) {
 android.util.DisplayMetrics display = new android.util.DisplayMetrics();
 getWindowManager().getDefaultDisplay().getMetrics(display);
 viewportHint =
 new int[] {
 display.widthPixels, display.heightPixels, width, height
 };
 recreate();
 return;
 }
 ui.post(
 () -> {
 if (isFinishing() || isDestroyed()) return;
 String crashedSite = prefs.getString(Prefs.LOADING_SITE, "");
 boolean recoveredFromCrash =
 crashedSite != null && !crashedSite.isEmpty();
 if (recoveredFromCrash) {
 prefs.edit()
 .putString(Prefs.LOADING_SITE, "")
 .putString(Prefs.SELECTED_SITE, "")
 .commit();
 }
 open(new Screen(() -> home()), false);
 layoutReady = true;
 if (recoveredFromCrash) {
 toast("上次加载该片源时异常退出，已自动重置片源选择");
 } else if (layoutIntro && isTvDevice()) {
 StartupIntro.show(MainActivity.this);
 }
 });
 }
 });
 }

 @Override
 public void onConfigurationChanged(android.content.res.Configuration newConfig) {
 super.onConfigurationChanged(newConfig);
 // 1.9.234：横竖屏切换后重算小窗矩形
 if (miniMode && miniDetails != null) {
 miniDetails.refreshRect();
 AppLog.i("Mini", "rect refresh config-change portrait=" + (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT));
 }
 if (!layoutReady || root == null) return;
 // 旋转/分屏时 root 在 config 回调里可能还是旧尺寸（横屏 2560 残留 → usableWidth 错误 →
 // 电视页按 2560px 重建成三栏）。改为等「真正完成新布局」的全局布局回调再读取尺寸。
 final FrameLayout watched = root;
 final ViewTreeObserver.OnGlobalLayoutListener[] holder =
 new ViewTreeObserver.OnGlobalLayoutListener[1];
 holder[0] =
 new ViewTreeObserver.OnGlobalLayoutListener() {
 @Override
 public void onGlobalLayout() {
 int w = watched.getWidth(), h = watched.getHeight();
 if (w <= 0 || h <= 0) return;
 // 布局仍是旧尺寸：等下一次布局，不在这里更新 usable
 if (w == usableWidth && h == usableHeight) return;
 ViewTreeObserver observer = watched.getViewTreeObserver();
 if (observer.isAlive()) observer.removeOnGlobalLayoutListener(holder[0]);
 watched.post(() -> applyWindowSize(w, h));
 }
 };
 ViewTreeObserver observer = watched.getViewTreeObserver();
 if (observer.isAlive()) observer.addOnGlobalLayoutListener(holder[0]);
 // 兜底移除监听，避免极端情况下不触发布局造成观察者泄漏
 ui.postDelayed(
 () -> {
 ViewTreeObserver current = watched.getViewTreeObserver();
 if (current.isAlive()) current.removeOnGlobalLayoutListener(holder[0]);
 },
 2000);
 }

 /** 新布局完成后的尺寸更新与页面处理（旋转/分屏共用）。 */
 private void applyWindowSize(int w, int h) {
 if (isFinishing() || isDestroyed() || root == null) return;
 if (w <= 0 || h <= 0) return;
 if (w == usableWidth && h == usableHeight) return;
 usableWidth = w;
 usableHeight = h;
 if (current != null && current.player) {
 if (playerChrome != null) playerChrome.relayout();
 if (playbackControls != null)
 playbackControls.resize(playbackControls.videoWidth, playbackControls.videoHeight);
 } else if (tvPlayer != null) {
 // 直播网页播放器是挂在 root 上的全屏 overlay，不属于 current.player：
 // 旋转时只 relayout，不能 refresh 整页，否则 overlay 会被新 root 丢掉。
 tvPlayer.relayout();
 } else if (current != null) {
 refresh();
 }
 }

 private static final int REQ_PICK_FILE = 7101;

 /** 系统文件管理器选文件，写入内置文件管理（视频/文本/图片/APK）。 */
 void pickAndUploadFile() {
 try {
 android.content.Intent intent =
 new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
 intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
 intent.setType("*/*");
 startActivityForResult(intent, REQ_PICK_FILE);
 } catch (Exception e) {
 toast("无法打开文件管理器：" + e.getMessage());
 }
 }

 private void handlePickedFile(int resultCode, android.content.Intent data) {
 if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
 final android.net.Uri uri = data.getData();
 new Thread(
 () -> {
 String name = "";
 try (android.database.Cursor cursor =
 getContentResolver()
 .query(
 uri,
 new String[] {
 android.provider.OpenableColumns.DISPLAY_NAME
 },
 null,
 null,
 null)) {
 if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
 } catch (Exception ignored) {
 }
 if (name == null || name.trim().isEmpty()) name = "upload.bin";
 final String finalName = PushFiles.sanitize(name);
 try {
 java.io.InputStream in = getContentResolver().openInputStream(uri);
 if (in == null) throw new java.io.IOException("无法读取文件");
 PushFiles.saveStream(this, finalName, in);
 ui.post(
 () -> {
 toast("已上传：" + finalName);
 FilesScreen.show(this);
 });
 } catch (Exception e) {
 ui.post(() -> toast("上传失败：" + e.getMessage()));
 }
 })
 .start();
 }

 @Override
 protected void onSaveInstanceState(Bundle out) {
 out.putBoolean("layout_initial_launch", !layoutReady && layoutIntro);
 if (!isTvDevice()) {
 out.putInt("whale_tab", MobileShell.activeTab);
 if (scroll != null && current != null && current.home) {
 if (scroll instanceof RecyclerView) {
 int[] anchor = MobileShell.captureListAnchor((RecyclerView) scroll);
 out.putInt("whale_home_scroll", 0);
 out.putInt("whale_home_pos", anchor[0]);
 out.putInt("whale_home_off", anchor[1]);
 } else {
 out.putInt("whale_home_scroll", scroll.getScrollY());
 }
 }
 out.putString("whale_search", MobileShell.cachedSearchKeyword);
 }
 super.onSaveInstanceState(out);
 }

 int dp(float x) {
 return Math.round(x * getResources().getDisplayMetrics().density);
 }

 /** 1.9.325：卡片底色 8 套预设（樱夜/墨夜/雾梅/咖啡/青瓷/夜蓝/紫夜/灰岩）。 */
 static int cardPresetBase(String key) {
 return cardPresetBase(key, null);
 }

 static int cardPresetBase(String key, android.content.SharedPreferences prefs) {
  if ("custom".equals(key))
   return prefs == null ? 0x3a1f2b : prefs.getInt("glass_custom_color", 0x3a1f2b) & 0xffffff;
  return Skin.presetColor(key);
 }

 /** 1.9.325：当前卡片主题的中文名（设置页/调色盘用）。 */
 static String cardPresetLabel(String key) {
  return "custom".equals(key) ? "自定义" : Skin.presetName(key);
 }

 /** 设置页循环下一套主题（8 档）。 */
 static String nextCardPreset(String key) {
  if (key == null || "custom".equals(key)) return Skin.PRESET_KEYS[0];
  int idx = Skin.presetIndex(key);
  return Skin.PRESET_KEYS[(idx + 1) % Skin.PRESET_KEYS.length];
 }

 /** 1.9.327：当前主题 key（优先 theme_key，其次 glass_color 迁移值）。 */
 static String currentThemeKey(android.content.SharedPreferences p) {
  String key = p.getString("theme_key", "");
  for (String k : Skin.PRESET_KEYS) if (k.equals(key)) return key;
  return migrateCardPreset(p);
 }

 /** 1.9.327：主题切换 = 一次写入「底 / 选中色 / 文字色」三元组 + 重置微调覆盖。 */
 static void applyTheme(String key, android.content.SharedPreferences p) {
  int idx = Skin.presetIndex(key);
  String theme = Skin.PRESET_KEYS[idx];
  p.edit()
   .putString("theme_key", theme)
   .putString("glass_color", theme)
   .putInt("card_accent_color", 0xff000000 | (Skin.PRESET_ACCENTS[idx] & 0xffffff))
   .putBoolean("card_accent_custom", true)
   .putInt(Prefs.TEXT_COLOR, 0xff000000 | (Skin.PRESET_INKS[idx] & 0xffffff))
   .putBoolean(Prefs.TEXT_COLOR_CUSTOM, true)
   .commit();
  AppLog.i(
   "Theme",
   "apply theme="
    + theme
    + " base=#"
    + String.format(java.util.Locale.ROOT, "%06X", Skin.PRESET_COLORS[idx] & 0xffffff)
    + " accent=#"
    + String.format(java.util.Locale.ROOT, "%06X", Skin.PRESET_ACCENTS[idx] & 0xffffff)
    + " ink=#"
    + String.format(java.util.Locale.ROOT, "%06X", Skin.PRESET_INKS[idx] & 0xffffff));
 }

 /** 1.9.327：主题中文名（设置卡片用）。 */
 static String themeLabel(android.content.SharedPreferences p) {
  return Skin.presetName(currentThemeKey(p));
 }

 /** 1.9.327：主题风格说明。 */
 static String themeTag(android.content.SharedPreferences p) {
  return Skin.presetTag(currentThemeKey(p));
 }

 /** 旧 glass_color / 任意 HEX → 最近预设（8 套）。 */
 static String migrateCardPreset(android.content.SharedPreferences p) {
  String raw = p.getString("glass_color", "celadon");
  if ("custom".equals(raw)) return raw;
  for (String key : Skin.PRESET_KEYS) if (key.equals(raw)) return raw;
  int old = 0x1e3a5f;
  if ("black".equals(raw)) old = 0x140b10;
  else if ("white".equals(raw)) old = 0x9a8f96;
  else if ("blue".equals(raw) || "accent".equals(raw)) old = 0x1e3a5f;
  else {
   try {
    old = Integer.parseInt(raw.replace("#", ""), 16) & 0xffffff;
   } catch (Throwable ignored) {
   }
  }
  int best = 0, bestDist = Integer.MAX_VALUE;
  for (int i = 0; i < Skin.PRESET_COLORS.length; i++) {
   int preset = Skin.PRESET_COLORS[i] & 0xffffff;
   int dr = ((old >> 16) & 0xff) - ((preset >> 16) & 0xff);
   int dg = ((old >> 8) & 0xff) - ((preset >> 8) & 0xff);
   int db = (old & 0xff) - (preset & 0xff);
   int d = dr * dr + dg * dg + db * db;
   if (d < bestDist) {
    bestDist = d;
    best = i;
   }
  }
  p.edit().putString("glass_color", Skin.PRESET_KEYS[best]).apply();
  return Skin.PRESET_KEYS[best];
 }

 /** 选中色 3 套预设：樱花粉/珊瑚/杏桃 + 最近值迁移。 */
 static int migrateAccent(int raw) {
  int value = raw & 0xffffff;
  int[] presets = {0xff8fb1, 0xff8a7a, 0xffb37c};
  int best = presets[0], bestDist = Integer.MAX_VALUE;
  for (int preset : presets) {
  int dr = ((value >> 16) & 0xff) - ((preset >> 16) & 0xff);
  int dg = ((value >> 8) & 0xff) - ((preset >> 8) & 0xff);
  int db = (value & 0xff) - (preset & 0xff);
  int d = dr * dr + dg * dg + db * db;
  if (d < bestDist) {
  bestDist = d;
  best = preset;
  }
  }
  return 0xff000000 | (best & 0xffffff);
 }

 static int lighten(int rgb, float amount) {
  int r = Math.min(255, Math.round(((rgb >> 16) & 0xff) + (255 - ((rgb >> 16) & 0xff)) * amount));
  int g = Math.min(255, Math.round(((rgb >> 8) & 0xff) + (255 - ((rgb >> 8) & 0xff)) * amount));
  int b = Math.min(255, Math.round((rgb & 0xff) + (255 - (rgb & 0xff)) * amount));
  return (r << 16) | (g << 8) | b;
 }

 /** 1.9.300：把外观设置同步到玻璃面板与普通卡片。 */
 void applyGlassTint() {
 try {
  String gc = migrateCardPreset(prefs);
  int base = cardPresetBase(gc, prefs);
  int alphaPct = Math.max(0, Math.min(100, prefs.getInt("glass_alpha", 40)));
  // 1.9.319：自定义选中色（card_accent_custom=true）不做预设吸附
 int accent =
 prefs.getBoolean("card_accent_custom", false)
 ? prefs.getInt("card_accent_color", 0xffff8fb1)
 : migrateAccent(prefs.getInt("card_accent_color", 0xffff8fb1));
  prefs.edit()
  .putString("glass_color", gc)
  .putInt("card_accent_color", accent)
  .apply();
  int accentOpaque = 0xff000000 | (accent & 0xffffff);
  Skin.ACCENT_STRONG = accentOpaque;
  Skin.ACCENT_MID = 0xff000000 | (lighten(accentOpaque, 0.15f) & 0xffffff);
  Skin.ACCENT_SOFT_T = 0xff000000 | (lighten(accentOpaque, 0.30f) & 0xffffff);
  int alpha = (int) (alphaPct * 2.55f);
  Skin.cardFill = (alpha << 24) | base;
  Skin.cardAccent = accent;
  Skin.applyGlassPrefs(gc, alphaPct);
  com.pilipala.glass.GlassworkSurface.setGlobalTint(Skin.cardFill);
  AppLog.i(
  "Theme",
  "tokens=warm-night-sakura card=#"
  + Integer.toHexString(Skin.CARD_BASE)
  + " cardBase=#"
  + Integer.toHexString(base)
  + " preset="
  + gc
  + " accent=#"
  + Integer.toHexString(accent)
  + " text=#"
  + Integer.toHexString(Skin.TEXT_PRIMARY_T)
  + " alpha="
  + alphaPct
  + " cardFill=#"
  + Integer.toHexString(Skin.cardFill));
 } catch (Throwable ignored) {
 }
 }

 /**
  * 1.9.325：画在用户卡片底上的文字色。
  *
  * <p>用户自定义字体色优先；与卡片底对比 <3:1 时自动回退到可读字色（`Skin.inkOn`）。
  */
 int cardInk() {
 int bg = Skin.cardSurface();
 if (prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false)) {
  int user = 0xff000000 | (prefs.getInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR) & 0xffffff);
  double ratio = Skin.contrastRatio(user, bg);
  if (ratio >= 3.0) return user;
  AppLog.i(
   "Theme",
   String.format(
    java.util.Locale.ROOT,
    "ink custom=#%06X ratio=%.1f fallback",
    user & 0xffffff,
    ratio));
 }
 return Skin.cardInk();
 }

 /** 卡片底上的次要文字（同色系、降低不透明度）。 */
 int cardInkDim() {
 return Skin.dimInk(cardInk(), 0xb8);
 }

 /** 1.9.302：卡片底色 = CARD 预设 + 用户透明度；描边用 CARD_STROKE。 */
 GradientDrawable cardBg(float radiusDp) { String gc = migrateCardPreset(prefs);
 int base = cardPresetBase(gc, prefs);
 int alpha = (int) (Math.max(0, Math.min(100, prefs.getInt("glass_alpha", 40))) * 2.55f);
 GradientDrawable d = new GradientDrawable();
 d.setColor((alpha << 24) | base);
 d.setCornerRadius(dp(radiusDp));
 d.setStroke(Math.max(1, dp(0.7f)), Skin.CARD_STROKE);
 return d;
 }

 /** 1.9.300：RGB + 透明度调色台。 */
 void showCustomCardColorDialog() {
 try {
 int cur = prefs.getInt("glass_custom_color", 0x1e3a5f) & 0xffffff;
 final int[] rgb = {(cur >> 16) & 0xff, (cur >> 8) & 0xff, cur & 0xff};
 final int[] alphaPct = {Math.max(0, Math.min(100, prefs.getInt("glass_alpha", 50)))};
 LinearLayout panel = vertical();
 final View preview = new View(this);
 preview.setBackground(bg((alphaPct[0] * 255 / 100) << 24 | cur, 12));
 panel.addView(preview, new LinearLayout.LayoutParams(-1, dp(56)));
 String[] names = {"红 R", "绿 G", "蓝 B", "透明度"};
 for (int i = 0; i < 4; i++) {
 final int idx = i;
 final TextView label =
 text(names[i] + "：" + (i < 3 ? rgb[i] : alphaPct[0] + "%"), 13, Color.WHITE);
 label.setPadding(0, dp(10), 0, dp(2));
 panel.addView(label);
 android.widget.SeekBar bar = new android.widget.SeekBar(this);
 bar.setMax(i < 3 ? 255 : 90);
 bar.setProgress(i < 3 ? rgb[i] : alphaPct[0]);
 bar.setOnSeekBarChangeListener(
 new android.widget.SeekBar.OnSeekBarChangeListener() {
 public void onProgressChanged(android.widget.SeekBar s, int value, boolean fromUser) {
 if (idx < 3) rgb[idx] = value;
 else alphaPct[0] = value;
 label.setText(names[idx] + "：" + (idx < 3 ? String.valueOf(value) : value + "%"));
 int c = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
 preview.setBackground(bg((alphaPct[0] * 255 / 100) << 24 | c, 12));
 }
 public void onStartTrackingTouch(android.widget.SeekBar s) {}
 public void onStopTrackingTouch(android.widget.SeekBar s) {}
 });
 panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
 }
 android.widget.ScrollView sc = new android.widget.ScrollView(this);
 sc.addView(panel);
 new android.app.AlertDialog.Builder(this)
 .setTitle("调色台")
 .setView(sc)
 .setPositiveButton(
 "应用",
 (d, which) -> {
 int c = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
 prefs
 .edit()
 .putInt("glass_custom_color", c)
 .putString("glass_color", "custom")
 .putInt("glass_alpha", alphaPct[0])
 .apply();
 applyGlassTint();
 toast("已应用卡片颜色");
 refresh();
 })
 .setNegativeButton("取消", null)
 .show();
 } catch (Throwable ignored) {
 }
 }

 void glass(View view, float radius) {
 com.pilipala.glass.GlassworkSurface.attach(
 view, root, dp(radius), () -> prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false));
 }

 GradientDrawable bg(int color, float radius) {
 GradientDrawable d = new GradientDrawable();
 d.setColor(color);
 d.setCornerRadius(dp(radius));
 return d;
 }

 /** 1.9.23：统一主题文字色入口（等同 userTextColor，供各页面调用）。 */
 int themeColor(int color) {
 return userTextColor(color);
 }

 private android.graphics.Typeface cachedTypeface;
 private String cachedTypefaceName;

 /** 1.9.24：当前字体样式（Android 内置 Typeface）。 */
 android.graphics.Typeface typeface() {
 String name = prefs.getString(Prefs.FONT_STYLE, "sans-serif");
 if (name == null || name.isEmpty()) name = "sans-serif";
 if (cachedTypeface != null && name.equals(cachedTypefaceName)) return cachedTypeface;
 android.graphics.Typeface typeface;
 try {
 typeface = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL);
 } catch (Throwable error) {
 typeface = android.graphics.Typeface.DEFAULT;
 }
 if (typeface == null) typeface = android.graphics.Typeface.DEFAULT;
 cachedTypeface = typeface;
 cachedTypefaceName = name;
 AppLog.i("Font", "applied name=" + name + " fallback=" + (typeface == android.graphics.Typeface.DEFAULT));
 return typeface;
 }

 /** 1.9.24：字体样式名（设置项用）。 */
 String fontStyleName() {
 return prefs.getString(Prefs.FONT_STYLE, "sans-serif");
 }

 /** 1.9.24：主题字体的 TextView，粗体设置自动保留自定义字体样式。 */
 final class ThemedTextView extends TextView {
 ThemedTextView(android.content.Context context) {
 super(context);
 }

 @Override
 public void setTypeface(android.graphics.Typeface tf, int style) {
 android.graphics.Typeface base = tf == null ? typeface() : tf;
 super.setTypeface(base, style);
 }
 }

 /** 1.9.32：默认文字色改为亮绿 #4CFF80；自定义开启时用 TEXT_COLOR，灰白文字按 alpha 映射，强调色保持。 */
 static int lastTextColorLog;

 int userTextColor(int color) {
 boolean custom = prefs.getBoolean(Prefs.TEXT_COLOR_CUSTOM, false);
 int rgb =
 (custom ? prefs.getInt(Prefs.TEXT_COLOR, Prefs.DEFAULT_TEXT_COLOR) : Prefs.DEFAULT_TEXT_COLOR)
 & 0xffffff;
 int effectiveLog = custom ? rgb : (Prefs.DEFAULT_TEXT_COLOR & 0xffffff);
 if (lastTextColorLog != effectiveLog) {
 lastTextColorLog = effectiveLog;
 if (custom)
 AppLog.i(
 "Theme",
 String.format(java.util.Locale.ROOT, "userTextColor=#%06X", rgb));
 else AppLog.i("Theme", "defaultTextColor=#4CFF80");
 }
 if (color == WHITE) return 0xff000000 | rgb;
 if (color == MUTED) return 0xb8000000 | rgb; // 1.9.32：次要文字 72%
 if (color == UiTokens.TEXT_TERTIARY) return 0xa8000000 | rgb; // 弱文字 66%
 // 1.9.25：HSV 判定偏蓝灰/灰白文字（S<=0.42 且 V>=0.35）
 int alpha = (color >>> 24) & 0xff;
 float[] hsv = new float[3];
 android.graphics.Color.colorToHSV(color & 0x00ffffff, hsv);
 boolean grayText = hsv[1] <= 0.42f && hsv[2] >= 0.35f;
 if (grayText) {
 // 1.9.32：主 100% / 次要 72%（0xB8）/ 弱 66%（0xA8）
 int outAlpha = alpha >= 0xF0 ? 0xff : alpha >= 0x80 ? 0xb8 : 0xa8;
 return (outAlpha << 24) | rgb;
 }
 // 高饱和强调色（粉/红/绿/蓝）与成功/危险/警告状态色保持原样，不染绿
 return color;
 }

 TextView text(String s, float size, int color) {
 TextView t = new ThemedTextView(this);
 t.setText(s);
 t.setTextColor(userTextColor(color));
 t.setTextSize(size);
 t.setTypeface(typeface());
 t.setFontFeatureSettings("kern");
 return t;
 }

 LinearLayout vertical() {
 LinearLayout v = new LinearLayout(this);
 v.setOrientation(LinearLayout.VERTICAL);
 return v;
 }

 LinearLayout row() {
 LinearLayout v = new LinearLayout(this);
 v.setOrientation(LinearLayout.HORIZONTAL);
 v.setGravity(Gravity.CENTER_VERTICAL);
 return v;
 }

 void gap(LinearLayout p, int n) {
 View v = new View(this);
 p.addView(v, new LinearLayout.LayoutParams(1, dp(n)));
 }

 void paragraph(String s) {
 TextView t = text(s, 16, MUTED);
 t.setLineSpacing(dp(5), 1);
 body.addView(t);
 gap(body, 14);
 }

 Button button(String label, String key, Runnable action) {
 Button b = new Button(this);
 b.setText(label);
 b.setTextSize(15);
 b.setTextColor(userTextColor(WHITE));
 b.setAllCaps(false);
 b.setGravity(Gravity.CENTER);
 b.setIncludeFontPadding(false);
 b.setStateListAnimator(null);
 b.setOutlineProvider(null);
 b.setMinHeight(dp(48));
 b.setMinimumHeight(dp(48));
 b.setPadding(dp(20), dp(9), dp(20), dp(9));
 b.setBackground(bg(0xff172742, 12));
 b.setTag(key);
 b.setFocusable(true);
 b.setFocusableInTouchMode(isTvDevice());
 b.setOnClickListener(v -> action.run());
 pressFeedback(b);
 b.setOnFocusChangeListener(
 (v, f) ->
 FocusMotion.style(
 this, b, f, 0xff172742, WHITE, 12, WHITE, DARK, false, 1.025f));
 return b;
 }

 void pressFeedback(View view) {
 view.setOnTouchListener(
 (v, event) -> {
 if (!isTvDevice()) {
 if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
 v.setAlpha(0.75f);
 } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
 || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
 v.setAlpha(1.0f);
 }
 return false;
 }
 if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
 v.requestFocus();
 FocusMotion.press(this, v, true);
 } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
 || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
 FocusMotion.press(this, v, false);
 return false;
 });
 view.setOnKeyListener(
 (v, key, event) -> {
 FocusMotion.key(this, v, key, event);
 return false;
 });
 }

 Button navButton(String label, String key, Runnable action) {
 Button b = button(label, key, action);
 b.setTextSize(13.5f);
 b.setSingleLine(true);
 b.setEllipsize(android.text.TextUtils.TruncateAt.END);
 b.setMinWidth(0);
 b.setMinimumWidth(0);
 b.setPadding(dp(14), dp(6), dp(14), dp(6));
 b.setMinHeight(dp(38));
 b.setMinimumHeight(dp(38));
 GradientDrawable pill = cardBg(19);
 pill.setStroke(Math.max(1, dp(1)), Skin.CARD_STROKE);
 b.setBackground(pill);
 // 1.9.322：胶囊底色跟随外观设置的卡片底色，文字 TEXT_PRIMARY_T
 b.setTextColor(Skin.TEXT_PRIMARY_T);
 b.setOnFocusChangeListener(
 (v, f) ->
 FocusMotion.style(
 this,
 b,
 f,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 WHITE,
 19,
 Skin.TEXT_PRIMARY_T,
 DARK,
 false,
 1.02f));
 return b;
 }

 void chooseOrImport() {
 if (config == null) importDialog();
 else chooseSources();
 }

 void addButton(LinearLayout p, Button b) {
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.setMargins(dp(4), dp(5), dp(10), dp(8));
 p.addView(b, lp);
 }

 void renderPage(String title, String eyebrow) {
 renderPage(title, eyebrow, false);
 }

 /** 1.9.177：手机版按当前页面强制方向（平板/电视不干预）。 */
 void applyPageOrientation(String reason) {
 try {
 if (isTvDevice() || formFactor != TvLayoutPolicy.FORM_PHONE) return;
 // 1.9.178：多窗口/画中画不强制方向
 try {
 if (isInMultiWindowMode() || isInPictureInPictureMode()) {
 AppLog.i("Orient", "skip=true form=phone reason=multiwindow");
 return;
 }
 } catch (Throwable ignored) {
 }
 String tag = body == null ? "" : String.valueOf(body.getTag());
 boolean playerPage =
 (current != null && current.player)
 || "native-live".equals(tag)
 || "web-live".equals(tag)
 || "web-player".equals(tag)
 || (root != null && "player-surface".equals(root.getTag()));
 // 1.9.272：小窗放大全屏期间强制横屏（播放器，而不是选集页）
 if (expandedOverDetail && player != null) {
 if (getRequestedOrientation()
 != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
 setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
 AppLog.i("Orient", "form=phone page=player set=landscape reason=expanded-guard");
 }
 return;
 }
 int target =
 playerPage
 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
 : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
 if (getRequestedOrientation() == target) return;
 setRequestedOrientation(target);
 AppLog.i(
 "Orient",
 "form=phone page="
 + (playerPage ? "player" : ("detail".equals(tag) ? "detail" : "page"))
 + " set="
 + (playerPage ? "landscape" : "portrait")
 + " reason="
 + reason
 + " tag="
 + tag);
 } catch (Throwable error) {
 AppLog.w("Orient", "apply failed reason=" + reason, error);
 }
 }

 void preparePage(boolean navigation) {
 // 1.9.180：切页后允许动态壁纸重试
 if (videoWallpaperRetryPending) {
 videoWallpaperRetryPending = false;
 AppLog.i("Wallpaper", "video retry reason=page-change");
 }
 NativeLiveScreen.close(this);
 // 1.9.106：直播浮层是页面级 WebView，任何页面重建都必须先关掉，否则会在后台继续出声
 releaseLiveOnPageChange("page");
 clearPlaybackLoading();
 cancelArtwork();
 cancelPageRequests();
 // 1.9.181：页面切换释放上一页约一半图片缓存
 ImageLoader.onPageChange(this);
 if (current != null) current.home = navigation;
 if (searchScreen != null) {
 searchScreen.close();
 searchScreen = null;
 }
 stopWeb();
 stopMagnet();
 // 1.9.167：只有详情页小窗/全屏浮层重建时保留播放器（其它页面一律释放）
 boolean keepPlayback =
 (miniMode || expandedOverDetail)
 && (miniTransitionGuard || (current != null && current.detail));
 AppLog.i(
 "Player",
 "policy target="
 + (current == null
 ? "null"
 : (current.detail ? "detail" : current.player ? "player" : current.home ? "home" : "other"))
 + " keep="
 + keepPlayback
 + " mini="
 + miniMode);
 if (!keepPlayback) {
 // 1.9.106：页面重建统一释放（手机与电视一致）
 releasePlaybackOnPageChange("page", false);
 generation++;
 }
 // 1.9.179：播放页暂停动态壁纸，其它页恢复
 if (videoWallpaper != null) {
 // 1.9.235：仅全屏播放页释放壁纸；详情小窗保留动态壁纸
 boolean fullscreenPlayer = (current != null && current.player);
 if (fullscreenPlayer) {
 videoWallpaper.stopAndReleaseDecoder("player-start");
 AppLog.i(
 "Wallpaper",
 "pause page=player reason=fullscreen mini=" + miniMode + " tv=" + isTvDevice());
 } else {
 videoWallpaper.ensureDecoder("page");
 if (current != null && current.detail && miniMode)
 AppLog.i("Wallpaper", "state=playing page=detail mini=true");
 else AppLog.i("Wallpaper", "mobile resume reason=player-stop tv=" + isTvDevice());
 }
 }
 if (!isTvDevice()) {
 applyMobileSystemBars(false);
 }
 // 1.9.177：页面渲染完成后再决定方向（避免在 Activity 级写死横屏）
 ui.post(() -> applyPageOrientation("page"));
 }

 static final String BACKDROP_TAG = "whale-backdrop";
 static final String SAFE_BACKDROP_TAG = "safe-mode-backdrop";
 /** 1.8.25：延迟补铺 token，只有最后一次生效，避免多次开关重复重建。 */
 int safeRestoreToken;
 /** 用户壁纸层（静态 Images/Atmosphere）专用 tag：与 base 渐变分开，便于幂等维护。 */
 static final String WALLPAPER_TAG = "whale-wallpaper-image";
 /** applyWallpaper 的日志签名：只在状态变化时写一条，避免刷屏。 */
 private String lastWallpaperLog;

 /** 幂等：确保 base 渐变 + 用户壁纸层（静态图或动态视频）都存在且唯一；缺则补、多则删。 */
 /** 1.9.112：启动/进首页时清理历史遗留的不兼容动态壁纸（4K/HEVC/高帧率）。 */
 void purgeUnsupportedVideoWallpaper() {
 try {
 // 1.9.113：仅旧系统清理 4K/HEVC；SDK>=26 保持 1.9.82 行为，不删合法 4K
 if (android.os.Build.VERSION.SDK_INT >= 26) return;
 final File video = VideoWallpaper.file(this);
 if (!video.isFile()) return;
 String reason = SettingsScreen.videoWallpaperRejectReason(this, video);
 AppLog.i(
 "Wallpaper",
 "video validate at=startup accepted=" + (reason == null) + " size=" + video.length());
 if (reason == null) return;
 video.delete();
 prefs.edit()
 .putBoolean(Prefs.WALLPAPER_VIDEO, false)
 .putBoolean(Prefs.WALLPAPER_ENABLED, true)
 .apply();
 AppLog.i("Wallpaper", "video purge reason=unsupported detail=" + reason);
 toast("已移除不兼容的 4K 动态壁纸");
 ui.post(
 () -> {
 if (root != null) applyWallpaper(root);
 });
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "video purge skipped", error);
 }
 }

 /** 1.9.108：动态壁纸失败自动回退默认/静态壁纸，避免整屏黑。 */
 void videoWallpaperFailed(String reason) {
 try {
 // 1.9.180：本次失败只回退静态，不永久关闭动态壁纸
 videoWallpaperRetryPending = true;
 toast("动态壁纸本次播放失败，已临时显示静态壁纸");
 AppLog.i(
 "Wallpaper",
 "video fallback reason="
 + reason
 + " videoEnabled kept=true retry=true mobile="
 + (!isTvDevice())
 + " source=builtin-default");
 if (!isTvDevice())
 AppLog.i("Wallpaper", "mobile fallback=static reason=" + reason);
 ui.post(
 () -> {
 if (root != null) applyWallpaper(root);
 });
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "fallback failed", error);
 }
 }

 /** 1.9.232：确保静态壁纸板（鲸鱼娘）可见。 */
 void ensureStaticPlate(String reason) {
 try {
 if (root == null) return;
 if (root.findViewWithTag(WALLPAPER_TAG) != null) return;
 View plate = ImageLoader.createAtmosphere(this);
 plate.setTag(WALLPAPER_TAG);
 root.addView(plate, 0, new FrameLayout.LayoutParams(-1, -1));
 AppLog.i("Wallpaper", "static plate shown reason=" + reason);
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "static plate add failed", error);
 }
 }

 /** 1.9.232：视频恢复后隐藏静态板，避免叠两层。 */
 void hideStaticPlate(String reason) {
 try {
 if (root == null) return;
 boolean removed = false;
 for (int i = root.getChildCount() - 1; i >= 0; i--)
 if (WALLPAPER_TAG.equals(root.getChildAt(i).getTag())) {
 root.removeViewAt(i);
 removed = true;
 }
 if (removed) AppLog.i("Wallpaper", "static plate hidden reason=" + reason);
 } catch (Throwable ignored) {
 }
 }

 /** 1.9.244：是否无触摸屏（TV 盒子最可靠判据）。 */
 boolean noTouchscreen() {
 try {
 return !getPackageManager()
 .hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
 } catch (Throwable ignored) {
 return false;
 }
 }

 /** 1.9.244：按分辨率 + 触摸能力判定设备类型，启动日志一次。 */
 void classifyDevice() {
 try {
 android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
 int w = dm.widthPixels, h = dm.heightPixels;
 deviceLongEdge = Math.max(w, h);
 int shortEdge = Math.min(w, h);
 deviceLandscape = w > h;
 deviceTouch =
 getPackageManager()
 .hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
 resClass = deviceLongEdge <= 1920 ? "1K" : deviceLongEdge <= 2880 ? "2K" : "4K";
 if (!deviceTouch) deviceType = "tv";
 else if (deviceLandscape) deviceType = "tablet";
 else deviceType = "phone";
 AppLog.i(
 "Device",
 "type="
 + deviceType
 + " res="
 + resClass
 + " screen="
 + w
 + "x"
 + h
 + " touch="
 + deviceTouch
 + " landscape="
 + deviceLandscape
 + " short="
 + shortEdge);
 } catch (Throwable error) {
 AppLog.w("Device", "classify failed", error);
 }
 }

 /** 1.9.248：按分辨率判电视/盒子（横屏且长边≤1920），不依赖触摸能力。 */
 boolean tvMiniMode() {
 try {
 if (isTvDevice()) return true;
 android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
 int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
 return dm.widthPixels > dm.heightPixels && longEdge <= 1920;
 } catch (Throwable ignored) {
 return false;
 }
 }

 String tvMiniReason() {
 if (isTvDevice()) return "tv";
 if (tvMiniMode()) return "no-touch-1080p";
 if (remoteFocusMode) return "remote";
 if (keyFocusMode) return "key";
 return "none";
 }

 void applyWallpaper(android.widget.FrameLayout target) {
 if (target == null) return;
 if (SafeMode.enabled(this)) {
 // 1.8.5/1.8.24：安全模式下清掉已有壁纸层（含 wallpaper-video），并补深色安全底
 clearWallpaperLayers(target, false);
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (SAFE_BACKDROP_TAG.equals(target.getChildAt(i).getTag()))
 target.removeViewAt(i);
 applySafeBackdrop(target);
 AppLog.i("SafeMode", "apply wallpaper cleared root=" + System.identityHashCode(target));
 return;
 }
 // 播放页是纯黑视频页（root 由 PlaybackController 创建并打 player-surface tag）：
 // 绝不铺壁纸，否则壁纸层会插到 playbackLayer 之上盖住视频。
 if ("player-surface".equals(target.getTag())) return;
 final boolean staticEnabled = prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false);
 // 1.9.219：新系统 TV/手机走独立 VideoWallpaper；老 TV(SDK<26) 走 Atmosphere 内嵌
 final boolean tvNewRoute =
 isTvDevice() && android.os.Build.VERSION.SDK_INT >= 26;
 final boolean videoEnabled =
 (!isTvDevice() || tvNewRoute)
 && videoWallpaperEnabled()
 && !videoWallpaperRetryPending;

 // 1. base 渐变：没有就补（动态壁纸时不铺不透明底，否则会盖住 SurfaceView）
 boolean hasBase = false;
 for (int i = 0; i < target.getChildCount(); i++)
 if (BACKDROP_TAG.equals(target.getChildAt(i).getTag())) {
 hasBase = true;
 break;
 }
 if (videoEnabled) {
 // 1.9.183：动态壁纸模式移除不透明 base，并让根容器透明
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (BACKDROP_TAG.equals(target.getChildAt(i).getTag())) target.removeViewAt(i);
 target.setBackground(null);
 } else if (!hasBase) {
 View base = new View(this);
 base.setTag(BACKDROP_TAG);
 base.setBackground(Skin.backdrop(this));
 base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
 target.addView(base, 0, new FrameLayout.LayoutParams(-1, -1));
 }

 // 2. 静态壁纸层：动态壁纸优先时全部移除；静态模式保留且唯一
 // 1.9.112：ENABLED 只控制自定义；关闭/失败时仍绘制内置默认壁纸兜底
 int staticCount = 0;
 for (int i = target.getChildCount() - 1; i >= 0; i--) {
 View child = target.getChildAt(i);
 if (!WALLPAPER_TAG.equals(child.getTag())) continue;
 if (videoEnabled) {
 target.removeViewAt(i);
 continue;
 }
 staticCount++;
 if (staticCount > 1) target.removeViewAt(i);
 }
 if (!videoEnabled && staticCount == 0) {
 java.io.File staticFile = new java.io.File(getFilesDir(), "wallpaper.jpg");
 if (!staticFile.isFile())
 AppLog.w("Wallpaper", "static wallpaper missing; use default asset: " + staticFile, null);
 View atmosphere = ImageLoader.createAtmosphere(this);
 atmosphere.setTag(WALLPAPER_TAG);
 target.addView(
 atmosphere,
 Math.min(1, target.getChildCount()),
 new FrameLayout.LayoutParams(-1, -1));
 staticCount = 1;
 }

 // 1.9.217：手机/平板动态壁纸——Atmosphere 内部承载视频，按 prefs 判断
 boolean wantAtomVideo =
 isTvDevice()
 && android.os.Build.VERSION.SDK_INT < 26
 && prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
 && VideoWallpaper.file(this).isFile();
 if (wantAtomVideo && target.findViewWithTag("atom-video") == null) {
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (WALLPAPER_TAG.equals(target.getChildAt(i).getTag())) target.removeViewAt(i);
 staticCount = 0;
 AppLog.i(
 "Wallpaper",
 "video enabled=true source=atmosphere mobile=" + (!isTvDevice()));
 }
 // 3. 动态壁纸：存在但挂在旧 root 时搬过来；否则释放并新建
 // 1.9.105：动态层必须视觉上位于静态壁纸之上（上面第 2 步在 videoEnabled 时已移除静态层，
 // 这里再兜底清一次，避免索引错位时视频被静态图盖住）
 if (videoEnabled) {
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (WALLPAPER_TAG.equals(target.getChildAt(i).getTag())) target.removeViewAt(i);
 if (videoWallpaper == null || videoWallpaper.isReleased()) {
 releaseWallpaper();
 try {
 videoWallpaper = new VideoWallpaper(this);
 videoWallpaper.attachTo(target);
 videoWallpaper.verifyVisible("create");
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "video create failed; fallback default", error);
 releaseWallpaper();
 videoWallpaper = null;
 prefs.edit()
 .putBoolean(Prefs.WALLPAPER_VIDEO, false)
 .putBoolean(Prefs.WALLPAPER_ENABLED, true)
 .putBoolean("force_builtin_wallpaper", true)
 .apply();
 }
 } else if (videoWallpaper.getParent() != target) {
 videoWallpaper.attachTo(target);
 } else if (videoWallpaper.isFailed()) {
 // 1.9.180：同一 root 下失败后下次页面渲染重试
 videoWallpaper.retry();
 }
 } else {
 if (videoWallpaper != null
 && videoWallpaper.getParent() instanceof android.view.ViewGroup)
 ((android.view.ViewGroup) videoWallpaper.getParent()).removeView(videoWallpaper);
 releaseWallpaper();
 }

 // 1.9.219：壁纸路线日志
 String route =
 isTvDevice()
 ? (tvNewRoute ? "tv-new" : "tv-old")
 : "mobile";
 boolean routeValid =
 videoEnabled
 ? videoWallpaper != null && videoWallpaper.getParent() == target
 : target.findViewWithTag(WALLPAPER_TAG) != null;
 AppLog.i(
 "Wallpaper",
 "route="
 + route
 + " engine="
 + (android.os.Build.VERSION.SDK_INT >= 26 ? "exo" : "mediaplayer")
 + " sdk="
 + android.os.Build.VERSION.SDK_INT
 + " valid="
 + routeValid);
 // 1.9.235：仅全屏播放页新建壁纸层时立即释放；详情小窗保留壁纸
 if (videoEnabled && videoWallpaper != null) {
 boolean fullscreen = (current != null && current.player);
 if (fullscreen) {
 videoWallpaper.stopAndReleaseDecoder("player-start");
 AppLog.i(
 "Wallpaper",
 "pause page=player reason=fullscreen mini=" + miniMode + " source=create");
 } else if (current != null && current.detail && miniMode) {
 videoWallpaper.ensureDecoder("mini");
 AppLog.i("Wallpaper", "state=playing page=detail mini=true source=create");
 }
 }
 // 1.9.202：所有页面统一背景板日志
 AppLog.i(
 "Wallpaper",
 "apply page="
 + (target.getTag() == null ? target.getClass().getSimpleName() : target.getTag())
 + " plate=static"
 + " wallpaperLayer="
 + (target.findViewWithTag(WALLPAPER_TAG) != null));
 // 4. 一次性诊断（仅状态变化时）
 final String signature =
 System.identityHashCode(target)
 + "/"
 + staticEnabled
 + "/"
 + videoEnabled
 + "/"
 + (videoWallpaper != null && videoWallpaper.getParent() == target)
 + "/"
 + staticCount;
 if (!signature.equals(lastWallpaperLog)) {
 lastWallpaperLog = signature;
 AppLog.i(
 "Wallpaper",
 "apply source="
 + (videoEnabled
 ? "custom video"
 : (new java.io.File(getFilesDir(), "wallpaper.jpg").isFile()
 ? "custom static"
 : "default"))
 + " root="
 + System.identityHashCode(target)
 + " staticEnabled="
 + staticEnabled
 + " videoEnabled="
 + videoEnabled
 + " videoAttached="
 + (videoWallpaper != null && videoWallpaper.getParent() == target)
 + " atmosphere="
 + staticCount);
 }
 }

 /** 统一给页面根容器铺背景：base 渐变 + 用户壁纸（静态/动态）都交给幂等的 applyWallpaper。 */
 void installBackdrop(android.widget.FrameLayout target) {
 applyWallpaper(target);
 }

 /** 返回已有页面（MobileShell.resume 复用 root）时同样幂等补齐壁纸层。 */
 void ensureBackdrop(android.widget.FrameLayout target) {
 applyWallpaper(target);
 }

 /** 1.8.21：安全模式下的深色渐变底（不是纯黑），避免黑屏感。 */
 void applySafeBackdrop(android.widget.FrameLayout target) {
 if (target == null) return;
 for (int i = 0; i < target.getChildCount(); i++)
 if (SAFE_BACKDROP_TAG.equals(target.getChildAt(i).getTag())) return;
 View view = new View(this);
 view.setTag(SAFE_BACKDROP_TAG);
 view.setBackground(
 new android.graphics.drawable.GradientDrawable(
 android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0xFF0B1220, 0xFF141C2A, 0xFF0B1220}));
 view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
 target.addView(view, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
 }
 void applySafeModeVisual() {
 final FrameLayout target = root;
 if (target == null) {
 AppLog.i("SafeMode", "visual restore pending root=null");
 ui.post(
 () -> {
 if (root != null) applySafeModeVisual();
 });
 return;
 }
 if (SafeMode.enabled(this)) {
 // 1.8.24：清壁纸层时把 VideoWallpaper 的 wallpaper-video 孤儿层一起摘掉
 clearWallpaperLayers(target, false);
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (SAFE_BACKDROP_TAG.equals(target.getChildAt(i).getTag()))
 target.removeViewAt(i);
 applySafeBackdrop(target);
 AppLog.i("SafeMode", "visual applied root=" + System.identityHashCode(target));
 return;
 }
 // 1.8.21/1.8.24：关闭安全模式 → 统一清层（含 wallpaper-video）→ 重建 → 150/400/800 补铺
 lastWallpaperLog = "";
 final int restoreToken = ++safeRestoreToken;
 clearWallpaperLayers(target, true);
 for (int i = target.getChildCount() - 1; i >= 0; i--)
 if (SAFE_BACKDROP_TAG.equals(target.getChildAt(i).getTag()))
 target.removeViewAt(i);
 applyWallpaper(target);
 ui.post(
 () -> {
 if (root != null) applyWallpaper(root);
 });
 ui.postDelayed(
 () -> {
 if (restoreToken != safeRestoreToken) return;
 if (root != null && !SafeMode.enabled(this)) {
 applyWallpaper(root);
 AppLog.i(
 "SafeMode",
 "restore retry phase=150 root="
 + System.identityHashCode(root)
 + " wallpaper="
 + ((root.findViewWithTag(BACKDROP_TAG) != null)
 ? "ok"
 : "black")
 + " videoAttached="
 + (videoWallpaper != null && videoWallpaper.getParent() == root));
 }
 },
 150);
 ui.postDelayed(
 () -> {
 if (restoreToken != safeRestoreToken) return;
 if (root != null && !SafeMode.enabled(this)) {
 applyWallpaper(root);
 AppLog.i(
 "SafeMode",
 "restore retry phase=400 root="
 + System.identityHashCode(root)
 + " wallpaper="
 + ((root.findViewWithTag(BACKDROP_TAG) != null)
 ? "ok"
 : "black")
 + " videoAttached="
 + (videoWallpaper != null && videoWallpaper.getParent() == root));
 }
 },
 400);
 ui.postDelayed(
 () -> {
 if (restoreToken != safeRestoreToken) return;
 if (root != null && !SafeMode.enabled(this)) {
 applyWallpaper(root);
 AppLog.i(
 "SafeMode",
 "restore retry phase=800 root="
 + System.identityHashCode(root)
 + " wallpaper="
 + ((root.findViewWithTag(BACKDROP_TAG) != null)
 ? "ok"
 : "black")
 + " videoAttached="
 + (videoWallpaper != null && videoWallpaper.getParent() == root));
 }
 },
 800);
 boolean staticOn = prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false);
 boolean videoOn = videoWallpaperEnabled();
 AppLog.i(
 "SafeMode",
 "restore root="
 + System.identityHashCode(target)
 + " static="
 + staticOn
 + " video="
 + videoOn
 + " wallpaper="
 + ((target.findViewWithTag(BACKDROP_TAG) != null
 || target.findViewWithTag(WALLPAPER_TAG) != null)
 ? "ok"
 : "black"));
 }

 void renderPage(String title, String eyebrow, boolean navigation) {
 lastRootBackAt = 0; // 1.9.31：任何页面渲染都重置「再按一次退出」窗口
 VideoFilterDialog.dismiss(this);
 preparePage(navigation);
 root = new FrameLayout(this);
 root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 root.setClipChildren(false);
 root.setClipToPadding(false);
 installBackdrop(root);
 homeAmbient = null;
 if (navigation
 && prefs.getBoolean(Prefs.HOME_AMBIENT, true)
 && !prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
 && !videoWallpaperEnabled()) {
 homeAmbient = new HomeAmbient(this);
 root.addView(homeAmbient, new FrameLayout.LayoutParams(-1, -1));
 }
 if (navigation) root.addView(homeGradient(), new FrameLayout.LayoutParams(-1, -1));
 TvScrollView tvScroll = new TvScrollView(this);
 scroll = tvScroll;
 tvScroll.setClipChildren(false);
 tvScroll.setClipToPadding(false);
 tvScroll.setFillViewport(true);
 body = vertical();
 body.setClipChildren(false);
 body.setClipToPadding(false);
 if (windowWidth() < dp(600) && !isTvDevice()) {
 body.setPadding(dp(18), dp(18), dp(18), dp(36));
 } else {
 body.setPadding(dp(46), dp(26), dp(46), dp(44));
 }
 tvScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
 root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
 setContentView(root);
 homeDock = null;
 if (navigation) {
 if (useBottomDock()) {
 body.setPadding(dp(18), dp(16), dp(18), dp(86));
 } else {
 body.setPadding(dp(34), dp(18), dp(100), dp(36));
 }
 LinearLayout brandRow = row();
 brandRow.setTag("home-brand");
 ImageView brandMark = new ImageView(this);
 brandMark.setImageResource(R.drawable.logo_mark);
 LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(dp(30), dp(30));
 brandSize.rightMargin = dp(10);
 brandRow.addView(brandMark, brandSize);
 TextView brand = text("鲸落 · WHALEFALL", 14, WHITE);
 brand.setLetterSpacing(.10f);
 brand.setTypeface(null, Typeface.BOLD);
 brandRow.addView(brand);
 body.addView(brandRow);
 gap(body, title.isEmpty() ? 14 : 22);
 homeDock = new HomeDock(this);
 }
 if (!title.isEmpty()) {
 TextView eye = text(eyebrow, 12, 0xffffc2d4);
 eye.setLetterSpacing(.15f);
 body.addView(eye);
 gap(body, 10);
 TextView heading = text(title, 36, WHITE);
 heading.setTypeface(null, Typeface.BOLD);
 body.addView(heading);
 gap(body, 16);
 }
 if (!reduced) {
 body.setTranslationY(dp(4));
 body.animate()
 .translationY(0)
 .setDuration(140)
 .setInterpolator(new DecelerateInterpolator())
 .start();
 }
 }

 /**
 * Soft home backdrop: a cool light from the top, deepening toward the bottom, with gentle
 * side shading. Sits above the poster colors or wallpaper and below all content.
 */
 boolean videoWallpaperEnabled() {
 // 1.9.231：默认保留源分辨率（4K 也直接播）；转码仅作为可选入口
 return !reduced
 && prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
 && VideoWallpaper.file(this).isFile();
 }

 void releaseWallpaper() {
 if (videoWallpaper != null) {
 videoWallpaper.release();
 try {
 android.view.ViewParent parent = videoWallpaper.getParent();
 if (parent instanceof android.view.ViewGroup)
 ((android.view.ViewGroup) parent).removeView(videoWallpaper);
 } catch (Throwable ignored) {
 }
 videoWallpaper = null;
 }
 }

 /** 1.8.24：统一清理壁纸类层（含 VideoWallpaper 的 wallpaper-video 孤儿层）。 */
 void clearWallpaperLayers(android.widget.FrameLayout target, boolean includeSafe) {
 if (target == null) return;
 for (int i = target.getChildCount() - 1; i >= 0; i--) {
 View child = target.getChildAt(i);
 Object tag = child.getTag();
 if (WALLPAPER_TAG.equals(tag)
 || BACKDROP_TAG.equals(tag)
 || (includeSafe && SAFE_BACKDROP_TAG.equals(tag))
 || "wallpaper-video".equals(tag)) target.removeViewAt(i);
 }
 releaseWallpaper();
 // 只处理“target 之外的 a.root”上的孤儿视频壁纸；target==root 时上面循环已覆盖
 if (root != null && root != target) {
 for (int i = root.getChildCount() - 1; i >= 0; i--) {
 View child = root.getChildAt(i);
 if ("wallpaper-video".equals(child.getTag())) root.removeViewAt(i);
 }
 }
 }

 View homeGradient() {
 GradientDrawable vertical =
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x3a3b5a80, 0x0d1a2233, 0x00101013, 0x8c06101f, 0xe6060c18});
 GradientDrawable sides =
 new GradientDrawable(
 GradientDrawable.Orientation.LEFT_RIGHT,
 new int[] {0x47000000, 0x00000000, 0x00000000, 0x38000000});
 View scrim = new View(this);
 scrim.setTag("home-gradient");
 scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
 scrim.setBackground(
 new android.graphics.drawable.LayerDrawable(
 new android.graphics.drawable.Drawable[] {vertical, sides, new Skin.Glow()}));
 return scrim;
 }

 boolean available(Cms.Site s) {
 // 1.9.26：体检中被禁用的源在加载/搜索时跳过
 if (SourceCheckScreen.isDisabled(this, s)) return false;
 return SourceController.available(this, s);
 }

 int engineEpoch;

 SourceEngine engine(Cms.Site s) throws Exception {
 return SourceController.engine(this, s);
 }

 synchronized void ensureProxy(Cms.Config snapshot) throws IOException {
 SourceController.ensureProxy(this, snapshot);
 }

 void closeEngines() {
 SourceController.closeEngines(this);
 }

 void open(Screen s, boolean history) {
 // 1.9.328：所有页面切换先清掉筛选浮层，防止残留 overlay 盖住新页面（含详情/小窗）
 VideoFilterDialog.dismiss(this);
 // 1.9.106：所有页面切换统一先释放播放/直播（目标页是播放页时由 playerPage 重建播放器）
 releasePlaybackOnPageChange("open", s != null && s.player);
 capture();
 if (history && current != null) back.push(current);
 current = s;
 s.render.run();
 lastRootBackAt = 0; // 1.9.31：进入新页面时重置「再按一次退出」计时
 s.pushedTag = body == null ? null : String.valueOf(body.getTag());
 if (root != null && !"player-surface".equals(root.getTag())) ensureBackdrop(root);
 restore(s);
 }

 void capture() {
 if (current != null) {
 View f = getCurrentFocus();
 if (f != null && f.getTag() instanceof String) current.focus = (String) f.getTag();
 if (scroll instanceof RecyclerView) {
 int[] anchor = MobileShell.captureListAnchor((RecyclerView) scroll);
 current.scroll = 0;
 current.scrollPos = anchor[0];
 current.scrollOffset = anchor[1];
 } else if (scroll != null) {
 current.scroll = scroll.getScrollY();
 current.scrollPos = -1;
 }
 }
 }

 /** Last focus placed by the app rather than the user; late content may replace only this. */
 View autoFocus;

 void restore(Screen s) {
 ui.post(
 () -> {
 if (current != s) return;
 if (scroll instanceof RecyclerView)
 MobileShell.restoreListAnchor(
 (RecyclerView) scroll, s.scrollPos, s.scrollOffset);
 else if (scroll != null) scroll.scrollTo(0, s.scroll);
 if (!isTvDevice()) return;
 View target = s.focus == null ? null : root.findViewWithTag(s.focus);
 if (target != null) {
 target.requestFocus();
 autoFocus = target;
 } else {
 View first = root.findViewWithTag("primary");
 if (first == null) first = root.findViewWithTag("detail-play");
 if (first == null && s.home) {
 first = root.findViewWithTag("home-tab:0");
 if (first == null) first = root.findViewWithTag("source-switcher");
 }
 if (first == null) first = root.findViewWithTag("nav-home");
 if (first == null) {
 ArrayList<View> available = new ArrayList<>();
 body.addFocusables(available, View.FOCUS_FORWARD);
 if (!available.isEmpty()) first = available.get(0);
 }
 if (first != null) first.requestFocus();
 autoFocus = first;
 }
 });
 if (isTvDevice()) {
 ui.post(
 () -> {
 View focus = getCurrentFocus();
 if (focus == null || !focus.isShown() || !focus.isAttachedToWindow())
 TvNav.ensureFocus(
 this,
 "restore",
 "detail-play",
 "home-video:0",
 "home-tonight",
 "primary",
 "home-tab:0",
 "source-switcher",
 "nav-home");
 });
 }
 if (remoteFocusMode)
 ui.post(() -> TvNav.enableRemoteFocus(this, "remote-page"));
 if (isTvDevice() || remoteFocusMode)
 ui.postDelayed(
 () -> {
 String page = body == null ? "page" : String.valueOf(body.getTag());
 TvNav.audit(this, page);
 UiAudit.audit(this, page);
 },
 300);
 }

 void refresh() {
 // 1.9.276：小窗放大全屏（横屏）期间禁止重建详情页，否则播放层丢失只剩选集页
 if (expandedOverDetail && player != null && playbackLayer != null) {
 try {
 if (playbackLayer.getParent() != root && root != null) root.addView(playbackLayer);
 playbackLayer.setVisibility(View.VISIBLE);
 playbackLayer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
 playbackLayer.bringToFront();
 AppLog.i("Player", "expand keep-layer skip-refresh");
 return;
 } catch (Throwable ignored) {
 }
 }

 capture();
 Screen s = current;
 s.render.run();
 if (root != null && !"player-surface".equals(root.getTag())) ensureBackdrop(root);
 restore(s);
 }

 void renderReturn(Screen screen) {
 // 1.9.177：返回后按目标页恢复方向
 ui.post(() -> applyPageOrientation("return"));
 if (!isTvDevice() && screen.mobileHome != null && screen.mobileHome.resume()) return;
 if (isTvDevice() && screen.retainedHome != null && screen.retainedHome.resume()) return;
 if (screen.resume != null) {
 screen.resume.run();
 if (root != null && !"player-surface".equals(root.getTag())) ensureBackdrop(root);
 return;
 }
 screen.render.run();
 if (root != null && !"player-surface".equals(root.getTag())) ensureBackdrop(root);
 }

 @Override
 public void onBackPressed() {
 restoreUiState("back");
 // 1.9.168：IPTV 选择器自绘面板优先关闭
 if (IptvPickerPanel.handleBack()) {
 AppLog.i("IptvOrg", "picker close source=back");
 return;
 }
 String ownerTag = body == null ? "null" : String.valueOf(body.getTag());
 AppLog.i(
 "Back",
 "page="
 + ownerTag
 + " stack="
 + back.size()
 + " owner="
 + (body == null
 ? (root == null ? "null" : root.getClass().getName())
 : body.getClass().getName()));
 // 1.9.87：原生直播页 BACK 释放 ExoPlayer 并回上一级
 // 1.9.106：走统一入口（popBack 内部释放，日志 I/Player release reason=back page=…）
 if ("native-live".equals(ownerTag)) {
 popBack();
 return;
 }
 // 1.9.82：App 内置文件页在最前处理，避免与 file-manager/播放器分支互串
 if ("app-files".equals(ownerTag)) {
 popBack();
 return;
 }
 AppLog.i(
 "Back",
 "page="
 + (body == null ? "null" : String.valueOf(body.getTag()))
 + " stack="
 + back.size()
 + " home="
 + (current != null && current.home)
 + " player="
 + (current != null && current.player));
 // 1.9.103：页面级 BACK 不被小窗焦点吞掉，直接回上一级（全屏 expanded 另处理）
 if (miniMode && miniDetails != null) {
 AppLog.i("Back", "page=detail mini=true action=pop-page");
 }
 // 1.9.106：直播浮层（手机直播页 / 电视直播页）BACK 一律先关闭并释放，不再要求 current.player；
 // 1.9.103 之前只在播放页判断，手机直播 tab 会漏关，WebView 留在后台继续出声
 if (tvPlayer != null) {
 boolean shown = tvPlayer.overlay != null && tvPlayer.overlay.isAttachedToWindow();
 AppLog.i("Player", "release reason=back page=" + ownerTag + " live=1");
 tvPlayer.close();
 AppLog.i("Back", "branch=tvPlayer shown=" + shown);
 if (shown) return;
 }
 // 1.9.53：从小窗放大后，BACK 重新缩回左上角小窗（详情页仍在下方）
 if (expandedOverDetail) {
 AppLog.i("Back", "branch=expanded");
 PlaybackController.minimizePlayback(this);
 return;
 }
 // 1.9.59/1.9.106：从小窗详情页 BACK 回首页/列表时停止并释放播放（手机与电视一致）
 if (miniMode && current != null && !current.player) {
 AppLog.i("Back", "branch=mini");
 releasePlaybackOnPageChange("back", false);
 }
 // 1.9.33：只在首页筛选层展开时优先收起（L3 → L2），避免误拦播放页/二级页/其它 tab 的 BACK
 if (!isTvDevice()
 && current != null
 && current.home
 && !current.player
 && MobileShell.activeTab == 0
 && MobileShell.activeShell != null
 && MobileShell.activeShell.backFilterUi()) return;
 if (isTvDevice()
 && current != null
 && current.home
 && !current.player
 && current.retainedHome != null
 && current.retainedHome.backFilterUi()) {
 AppLog.i("Back", "branch=filter");
 return;
 }
 String tag = body == null ? null : String.valueOf(body.getTag());
 // 1.9.30：先给页面自己的 handleBack 机会（退全屏/子目录/多选），再按入栈记录 pop
 if ("file-manager".equals(tag) && FileManagerScreen.handleBack()) {
 AppLog.i("Back", "branch=handle target=file-manager");
 return;
 }
 if ("web-live".equals(tag) && WebLiveScreen.handleBack(this)) {
 AppLog.i("Back", "branch=handle target=web-live");
 return;
 }
 if ("web-player".equals(tag) && WebPlayerScreen.handleBack(this)) {
 AppLog.i("Back", "branch=handle target=web-player");
 return;
 }
 if (!back.isEmpty()
 && current != null
 && !current.home
 && !current.player) {
 // 1.9.80：入栈页面统一 BACK=pop 回上一级（不再依赖 body tag 匹配）
 AppLog.i("Back", "branch=pop");
 // 1.9.106：离开页面（可能是详情页/直播相关页）统一释放，避免残留后台播放
 releasePlaybackOnPageChange("back", false);
 logBackAction("pop");
 capture();
 current = back.pop();
 renderReturn(current);
 restore(current);
 return;
 }
 logBackAction("custom");
 if (body != null && "source-check".equals(body.getTag())) {
 if (isTvDevice()) {
 current = new MainActivity.Screen(this::home);
 current.home = true;
 renderReturn(current);
 } else {
 MobileShell.show(this);
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(4);
 }
 return;
 }
 // 1.9.30：手机遥控页退出不再关闭服务（常驻通知停止/进程销毁才关闭）
 if (body != null && "lan-remote".equals(body.getTag())) {
 if (isTvDevice()) {
 current = new MainActivity.Screen(this::home);
 current.home = true;
 renderReturn(current);
 } else {
 MobileShell.show(this);
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(4);
 }
 return;
 }
 // 1.9.19：网页直播 BACK（先退全屏，再回直播 tab）
 if (body != null && "web-live".equals(body.getTag())) {
 if (WebLiveScreen.handleBack(this)) return;
 MobileShell.show(this);
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(2);
 return;
 }
 // 1.9.17：网页播放（先退全屏再离开）
 if (body != null && "web-player".equals(body.getTag())) {
 if (WebPlayerScreen.handleBack(this)) return;
 FileManagerScreen.resume(this);
 return;
 }
 // 1.9.11：网盘诊断返回文件管理器
 if (body != null && "netdisk-diag".equals(body.getTag())) {
 FileManagerScreen.resume(this);
 return;
 }
 // 1.9.2：下载中心返回文件管理器
 if (body != null && "download-center".equals(body.getTag())) {
 FileManagerScreen.resume(this);
 return;
 }
 // 1.9.1：图片/文本查看器返回文件管理器
 if (body != null
 && ("image-viewer".equals(body.getTag()) || "text-viewer".equals(body.getTag()))) {
 FileManagerScreen.resume(this);
 return;
 }
 // 1.9.0：文件管理器先交给页面处理（多选→上级），再离开页面
 if ((current == null || !current.player)
 && body != null
 && "file-manager".equals(body.getTag())) {
 // 1.9.30：先处理子目录/多选，再按入栈记录返回上一级（栈 pop 已在方法开头处理）
 if (FileManagerScreen.handleBack()) return;
 if (isTvDevice()) {
 current = new MainActivity.Screen(this::home);
 current.home = true;
 renderReturn(current);
 } else {
 FileManagerScreen.exitToSource(this);
 }
 return;
 }
 // 1.8.25/1.8.28：云盘相关独立页不直接退出；TV 回 TV 首页，手机回片库 tab
 if (body != null
 && ("netdisk-sheet".equals(body.getTag())
 || "source-cloud-config".equals(body.getTag()))) {
 if (isTvDevice()) {
 current = new MainActivity.Screen(this::home);
 current.home = true;
 renderReturn(current);
 } else {
 MobileShell.show(this);
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(3);
 }
 return;
 }
 if (MobileShell.exitLibrarySelection()) return;
 if (playbackLoading != null) {
 changingMiniEpisode = false;
 clearPlaybackLoading();
 cancelPageRequests();
 stopWeb();
 if (current != null && current.player && !prepared) {
 if (playerChrome != null) playerChrome.setLoading(false);
 if (playStatus != null) playStatus.setText("已取消加载");
 showControls(true);
 }
 return;
 }
 if (current != null && current.player) {
 if (!miniMode && playbackControls != null && controlsVisible) {
 // 第一次 BACK 只收起播放控件，不算离开播放页
 showControls(false);
 return;
 }
 // 1.9.374：只有「返回目标确实是详情页」才缩小窗；否则走正常释放，避免回首页还在后台播放
 // 1.9.375：只有返回栈顶就是详情页才允许缩小窗（current.detail 在播放页也可能是 true，导致小窗落到首页）
 boolean canMini =
 !back.isEmpty() && back.peek() != null && back.peek().detail;
 if (!miniMode && !isLive() && canMini) {
 // 1.9.360：TV 第二次 BACK → 回详情并缩左侧小窗；不得先释放播放器
 AppLog.i(
 "Back",
 "page=player target="
 + (back.isEmpty() ? "home" : "detail")
 + " stack="
 + back.size()
 + " tv=true action=mini");
 minimizePlayback();
 return;
 }
 if (!miniMode && playbackControls != null) {
 AppLog.i(
 "Back",
 "page=player target=" + (back.isEmpty() ? "home" : "detail") + " stack=" + back.size());
 // 1.9.214：手机/平板与电视一致——点播 BACK 回详情并缩小窗
 if (!isLive()) {
 AppLog.i("Back", "page=player mobile=true action=mini");
 minimizePlayback();
 return;
 }
 // 直播 BACK 仍直接停止播放并回上一级
 AppLog.i("Back", "page=player tv=false action=release live=1");
 releasePlaybackOnPageChange("back", false);
 if (!back.isEmpty()) {
 capture();
 current = back.pop();
 renderReturn(current);
 if (current != null) current.focus = "detail-play";
 restore(current);
 } else {
 current = new Screen(this::home);
 current.home = true;
 renderReturn(current);
 }
 return;
 }
 if (miniMode) {
 // 1.9.378：小窗态离开详情（回上一级/首页）必须先退出小窗状态再释放播放器。
 // 否则 1.9.360 的「小窗过渡期禁止释放」会把 release 拦掉，导致回首页后台还在播放。
 if (miniDetails != null) {
 try {
 miniDetails.remove();
 } catch (Throwable ignored) {
 }
 miniDetails = null;
 }
 miniMode = false;
 expandedOverDetail = false;
 releasePlaybackOnPageChange("back", false);
 // 页面切换不释放壁纸：由 applyWallpaper 在返回的页面 root 上幂等迁移/补齐
 if (!back.isEmpty()) back.pop();
 if (!back.isEmpty()) {
 current = back.pop();
 renderReturn(current);
 restore(current);
 } else {
 current = new Screen(this::home);
 renderReturn(current);
 }
 return;
 }
 // 兜底：播放页但没有播放控件（异常/半初始化状态）也先释放，再走普通返回
 releasePlaybackOnPageChange("back", false);
 }
 if (current != null && current.home) {
 if (MobileShell.activeTab == 6) {
 if (MobileShell.activeShell != null) MobileShell.activeShell.switchTab(0);
 return;
 }
 if (!isTvDevice() && MobileShell.activeTab == 4 && MobileShell.settingsSubPage != 0) {
 MobileShell.settingsSubPage = 0;
 MobileShell.show(this);
 return;
 }
 if (!isTvDevice() && !back.isEmpty()) {
 capture();
 current = back.pop();
 renderReturn(current);
 restore(current);
 return;
 }
 if (!isTvDevice() && MobileShell.activeTab != 0) {
 MobileShell.activeTab = 0;
 MobileShell.show(this);
 return;
 }
 // 1.9.31：根页面返回需 2 秒内再按一次才退出；首次只提示，不释放播放/壁纸
 if (!rootExitGuard()) return;
 releasePlaybackOnPageChange("back", false);
 releaseWallpaper();
 finish();
 return;
 }
 if (!back.isEmpty()) {
 // 1.9.106：入栈页面 pop 前统一释放播放/直播
 releasePlaybackOnPageChange("back", false);
 logBackAction("pop");
 capture();
 current = back.pop();
 renderReturn(current);
 restore(current);
 } else {
 logBackAction("finish");
 // 1.9.31：首次返回只提示，确认退出前才释放资源
 if (!rootExitGuard()) return;
 releasePlaybackOnPageChange("back", false);
 releaseWallpaper();
 finish();
 }
 }

 /** 1.9.31：首页/根页面「再按一次退出」保护（手势返回同样生效）。 */
 long lastRootBackAt;

 boolean rootExitGuard() {
 long now = System.currentTimeMillis();
 if (lastRootBackAt > 0 && now - lastRootBackAt <= 2000) {
 AppLog.i("Back", "root exitGuard=exit");
 lastRootBackAt = 0;
 return true;
 }
 lastRootBackAt = now;
 AppLog.i("Back", "root exitGuard=first");
 toast("再按一次退出应用");
 return false;
 }

 static boolean isPushedPage(String tag) {
 if (tag == null) return false;
 switch (tag) {
 case "source-check":
 case "lan-remote":
 case "web-live":
 case "web-player":
 case "netdisk-diag":
 case "download-center":
 case "image-viewer":
 case "text-viewer":
 case "netdisk-sheet":
 case "source-cloud-config":
 return true;
 default:
 return false;
 }
 }

 /** 1.9.81：显式 pop 回上一级（文件管理等自定义返回按钮调用，避免 onBackPressed 被早退分支吞）。 */
 void popBack() {
 // 1.9.106：显式返回也是页面切换入口，统一释放播放/直播
 releasePlaybackOnPageChange("back", false);
 if (!back.isEmpty()) {
 AppLog.i(
 "Back",
 "from="
 + (body == null ? "null" : String.valueOf(body.getTag()))
 + " action=popBack target="
 + back.peek().pushedTag);
 capture();
 current = back.pop();
 renderReturn(current);
 restore(current);
 return;
 }
 AppLog.i("Back", "action=popBack-home");
 current = new Screen(this::home);
 current.home = true;
 renderReturn(current);
 }

 void logBackAction(String action) {
 String from = body == null ? "null" : String.valueOf(body.getTag());
 String target = back.isEmpty() ? (current != null && current.home ? "home" : "none") : back.peek().pushedTag;
 AppLog.i(
 "Back",
 "from="
 + from
 + " action="
 + action
 + " target="
 + target
 + " stack="
 + back.size());
 }

 void minimizePlayback() {
 PlaybackController.minimizePlayback(this);
 }

 void expandPlayback() {
 PlaybackController.expandPlayback(this);
 }

 void toast(String message) {
 try {
 android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
 } catch (Throwable ignored) {
 }
 }

 void cancelPageRequests() {
 for (Future<?> request : pageRequests) request.cancel(true);
 pageRequests.clear();
 ((ThreadPoolExecutor) worker).purge();
 ((ThreadPoolExecutor) details).purge();
 }

 Future<?> submitPage(Runnable task) {
 Future<?> request = RequestScope.submit(worker, task);
 for (int i = pageRequests.size() - 1; i >= 0; i--) {
 if (pageRequests.get(i).isDone()) pageRequests.remove(i);
 }
 pageRequests.add(request);
 return request;
 }

 void loadDetail(Cms.Site site, String id, Done<Cms.Video> done) {
 SourceController.loadDetail(this, site, id, done);
 }

 <T> void load(String label, Work<T> task, Done<T> done) {
 SourceController.load(this, label, task, done);
 }

 String message(Throwable e) {
 return SourceController.message(e);
 }

 void notice(String s) {
 new AppDialog.Builder(this).setMessage(s).setPositiveButton("知道了", null).show();
 }

 void learnTitles(List<Cms.Video> videos) {
 SourceController.learnTitles(this, videos);
 }

 void sourceAction(Cms.Site site, String action) {
 sourceAction(site, action, null);
 }

 /**
 * 来源 action 统一入口：登录类动作先进入我们自己的网盘登录引导，
 * 避免任何菜单/旧入口直接弹出配置源的白底登录面板。
 */
 void sourceAction(Cms.Site site, String action, Runnable finished) {
 // 1.8.1：登录/账号一律直达源插件面板，不再经过我方引导层
 if ("LoginShow".equals(action)) {
 new SettingsScreen(this).sourceAccountAction(action, finished);
 return;
 }
 SourceController.sourceAction(this, site, action, finished);
 }

 /**
 * 首页「网盘」胶囊 / 设置「网盘与账号」直连源插件账号面板（唯一一层），
 * 不再经过我们的 AccountPanel / PanLoginGuide 中间层。
 */
 void openSourceAccountPanel() {
 AppLog.i("AccountEntrance", "direct source=LoginShow");
 new SettingsScreen(this).sourceAccountAction("LoginShow", null);
 }

 void pickDanmaku() {
 SourceController.pickDocument(this, 43, "这台电视没有文件选择器。");
 }

 void pickSubtitle() {
 SourceController.pickDocument(this, 42, "这台电视没有文件选择器，可安装文件管理器后再导入字幕。");
 }

 void home() {
 if (current != null) current.home = true;
 if (!isTvDevice()) {
 MobileShell.show(this);
 return;
 }
 Cms.Site selected = null;
 if (config != null)
 for (Cms.Site s : config.sites)
 if (s.key.equals(prefs.getString(Prefs.SELECTED_SITE, "")) && available(s))
 selected = s;
 if (selected == null && config != null) {
 // 1.9.28：当前源被禁用/不可用时回退到第一个可用源（与 MobileShell 口径一致）
 for (Cms.Site s : config.sites)
 if (available(s)) {
 selected = s;
 prefs.edit()
 .putString(Prefs.SELECTED_SITE, s.key)
 .putString(Prefs.LOADING_SITE, s.key)
 .apply();
 toast("当前片源已不可用，已回退到「" + s.name + "」");
 break;
 }
 }
 if (selected != null) {
 contentHome(selected);
 return;
 }
 renderPage("把今晚，留给好故事。", "YOUR OWN CINEMA", true);
 paragraph("属于你的电视播放器。导入自己合法获取的配置，用遥控器浏览和播放。");
 LinearLayout hero = row();
 addButton(
 hero,
 button(config == null ? "＋ 导入我的配置" : "＋ 更新配置", "primary", () -> importDialog()));
 if (config != null) addButton(hero, button("浏览片库", "primary", () -> chooseSources()));
 addButton(
 hero,
 navButton("观看历史", "history-open", () -> open(new Screen(this::historyPage), true)));
 body.addView(hero);
 gap(body, 30);
 if (config == null) {
 paragraph("本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。请自行合法获取内容与配置，并确保拥有相应使用权。");
 return;
 }
 TextView t = text("我的来源", 22, WHITE);
 body.addView(t);
 gap(body, 10);
 int supported = 0;
 int disabledCount = 0;
 for (Cms.Site s : config.sites) {
 if (SourceCheckScreen.isDisabled(this, s)) disabledCount++;
 else if (available(s)) supported++;
 }
 paragraph(
 "已保存 "
 + config.sites.size()
 + " 个来源（"
 + disabledCount
 + " 个已禁用）。选择一个，开始浏览。还未支持的来源会保留在列表中。");
 }

 void contentHome(Cms.Site site) {
 if (!isTvDevice()) {
 MobileShell.show(this);
 return;
 }
 new HomeScreen(this, site).show();
 }

 void chooseSources() {
 SourcePicker.show(this);
 }

 void importDialog() {
 if (!isTvDevice()) {
 MobileShell.showMobileImportDialog(this);
 return;
 }
 if (phoneConfig != null) phoneConfig.close();
 phoneConfig = new PhoneConfigDialog(this);
 phoneConfig.show();
 }

 void addressImportDialog() {
 SourceController.addressImportDialog(this);
 }

 void pickConfig() {
 SourceController.pickDocument(this, 41, "这台电视没有系统文件选择器。请安装文件管理器，或使用配置地址导入。");
 }

 @Override
 protected void onActivityResult(int request, int result, Intent data) {
 super.onActivityResult(request, result, data);
 if (request == REQ_PICK_FILE) {
 handlePickedFile(result, data);
 return;
 }
 SourceController.onActivityResult(this, request, result, data);
 }

 void saveConfig(Cms.Config c, String origin) throws Exception {
 SourceController.saveConfig(this, c, origin);
 }

 void categories(Cms.Site s) {
 CatalogScreen.categories(this, s);
 }

 void searchDialog(Cms.Site s) {
 globalSearchDialog();
 }

 void videos(Cms.Site s, String cat, String title, int page, String search) {
 CatalogScreen.videos(this, s, cat, title, page, search);
 }

 void cancelArtwork() {
 ImageLoader.cancel(this);
 }

 void poster(String address, ImageView image) {
 poster(address, image, false);
 }

 void poster(String address, ImageView image, boolean detail) {
 ImageLoader.poster(this, address, image, detail);
 }

 void detail(Cms.Site s, Cms.Video listing) {
 detail(s, listing, null);
 }

 void detail(Cms.Site s, Cms.Video listing, Cms.Video ready) {
 // 1.9.90：进入详情即预热 CSP WebView，减少旧设备首次解析等待
 CspEngine.prewarm(this);
 if (!isTvDevice()) {
 MobileDetailScreen.show(this, s, listing, ready);
 return;
 }
 DetailScreen.show(this, s, listing, ready);
 }

 void renderEpisodes(
 Cms.Site site, Cms.Video video, int lineIndex, int page, LinearLayout panel) {
 DetailScreen.renderEpisodes(this, site, video, lineIndex, page, panel, null);
 }

 void liveSources() {
 if (!isTvDevice()) {
 MobileShell.activeTab = 2;
 open(new Screen(this::home), false);
 return;
 }
 LiveController.liveSources(this);
 }

 void playLive(
 LiveCatalog.Source source, LiveCatalog.Channel channel, LiveCatalog.Stream stream) {
 LiveController.playLive(this, source, channel, stream);
 }

 boolean isLive() {
 return LiveController.isLive(this);
 }

 boolean liveStep(int direction) {
 return LiveController.liveStep(this, direction);
 }

 void chooseLiveChannel() {
 LiveController.chooseLiveChannel(this);
 }

 void chooseLiveStream() {
 LiveController.chooseLiveStream(this);
 }

 void clearLiveNumber() {
 LiveController.clearLiveNumber(this);
 }

 void commitLiveNumber() {
 LiveController.commitLiveNumber(this);
 }

 boolean liveKey(KeyEvent event) {
 return LiveController.liveKey(this, event);
 }

 void globalSearchDialog() {
 openSearch("");
 }

 void openSearch(String initial) {
 if (config == null) {
 importDialog();
 return;
 }
 if (!isTvDevice()) {
 MobileShell.openSearchTab(this, initial);
 return;
 }
 GlobalSearchScreen screen = new GlobalSearchScreen(this, initial);
 open(
 new Screen(
 () -> {
 screen.show();
 searchScreen = screen;
 }),
 true);
 }

 void crossSearch(String keyword) {
 openSearch(keyword);
 }

 void stopMagnet() {
 PlaybackController.stopMagnet(this);
 }

 void stopWeb() {
 PlaybackController.stopWeb(this);
 }

 void resolvePlayer(Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
 // 1.9.328：起播前关掉筛选浮层，避免浮层跟随到播放/返回小窗
 VideoFilterDialog.dismiss(this);
 PlaybackController.resolvePlayer(this, s, v, line, ep);
 }

 void clearPlaybackLoading() {
 PlaybackController.clearPlaybackLoading(this);
 }

 void openPlayer(Cms.Site s, Cms.Video v, Cms.Episode e) {
 PlaybackController.openPlayer(this, s, v, e);
 }

 void playerPage(Cms.Site s, Cms.Video v, Cms.Episode ep) {
 PlaybackController.playerPage(this, s, v, ep);
 }

 void changeEpisode(
 Cms.Site site, Cms.Video video, Cms.Line line, Cms.Episode episode, int position) {
 PlaybackController.changeEpisode(this, site, video, line, episode, position);
 }

 void leavePlayback() {
 PlaybackController.leavePlayback(this);
 }

 void showControls(boolean visible) {
 PlaybackController.showControls(this, visible);
 }

 void scheduleHide() {
 PlaybackController.scheduleHide(this);
 }

 String time(int ms) {
 return PlaybackController.formatTime(ms);
 }

 void togglePlayback() {
 PlaybackController.togglePlayback(this);
 }

 void beginSeek(int direction) {
 PlaybackController.beginSeek(this, direction);
 }

 void previewSeek() {
 PlaybackController.previewSeek(this);
 }

 void finishSeek(boolean commit) {
 PlaybackController.finishSeek(this, commit);
 }

 /** 1.9.167：清空后首页历史区显示「暂无观看记录」空态。 */
 boolean historyJustCleared;

 /** 1.9.122：统一清理观看历史 + 直播上次记录 + 首页历史缓存，并立即重绘。 */
 int clearAllHistory() {
 int removed = 0;
 try {
 removed = watchHistory().size();
 } catch (Throwable ignored) {
 }
 if (prefs.contains(TvLive.LAST)) removed++;
 prefs.edit()
 .remove(Prefs.WATCH_HISTORY)
 .remove(TvLive.LAST)
 .apply();
 AppLog.i("History", "clear scope=home+live removed=" + removed);
 toast("已清空观看历史与直播记录");
 // 1.9.167：作废首页历史缓存并立即重绘（含空态）
 historyJustCleared = true;
 try {
 if (current != null) {
 current.retainedHome = null;
 current.mobileHome = null;
 }
 if (back != null)
 for (Screen s : back) {
 s.retainedHome = null;
 s.mobileHome = null;
 }
 if (current != null && current.home) renderReturn(current);
 else refresh();
 ui.postDelayed(
 () -> {
 View all = root == null ? null : root.findViewWithTag("home-history-all");
 if (all != null && all.isShown()) all.requestFocus();
 AppLog.i("History", "clear cards=0 focus=home-history-all");
 },
 200);
 } catch (Throwable ignored) {
 }
 return removed;
 }

 java.util.List<WatchHistory.Entry> watchHistory() {
 return privateMode
 ? java.util.Collections.emptyList()
 : WatchHistory.read(prefs.getString(Prefs.WATCH_HISTORY, "[]"));
 }

 void savePlaybackHistory(boolean force) {
 PlaybackController.savePlaybackHistory(this, force);
 }

 String historyLabel(WatchHistory.Entry e) {
 return CatalogScreen.historyLabel(e);
 }

 void resumeHistory(WatchHistory.Entry e) {
 CatalogScreen.resumeHistory(this, e);
 }

 void playBookmark(Cms.Site site, Cms.Video video, WatchHistory.Entry bookmark) {
 CatalogScreen.playBookmark(this, site, video, bookmark);
 }

 void historyPage() {
 CatalogScreen.historyPage(this);
 }

 void releasePlayer() {
 PlaybackController.releasePlayer(this);
 }

 // ---------------- 1.9.106：页面切换统一释放播放 ----------------

 /** 当前页面 tag（日志用；页面自绘 body 时可能为空，退化为类型名）。 */
 String pageTagForLog() {
 if (current != null && current.player) return "player";
 if (tvPlayer != null) return "live";
 if (body != null && body.getTag() != null) return String.valueOf(body.getTag());
 if (current == null) return "null";
 return current.home ? "home" : "page";
 }

 /** 直播浮层（手机直播页 / 电视直播页）与原生 HLS 直播：离开页面必须关闭，否则 WebView 会继续出声。 */
 void releaseLiveOnPageChange(String reason) {
 if (tvPlayer != null) {
 AppLog.i(
 "Player",
 "release reason=" + reason + " page=" + pageTagForLog() + " live=1");
 try {
 tvPlayer.close();
 } catch (Throwable error) {
 AppLog.w("Player", "live close failed", error);
 }
 tvPlayer = null;
 }
 NativeLiveScreen.close(this);
 }

 /**
 * 1.9.106：所有页面切换（open / onBackPressed / switchTab / MobileShell 返回）统一走这里释放。
 *
 * @param targetIsPlayer 目标页本身是播放页时不释放视频播放器（playerPage 会自行 release 并重建），
 * 但直播浮层仍然关闭，避免两路声音并存。
 */
 void releasePlaybackOnPageChange(String reason, boolean targetIsPlayer) {
 // 1.9.367：释放来源日志
 AppLog.i("Playback", "releaseOnPageChange reason=" + reason + " mini=" + miniMode);

 // 1.9.396：过渡保护期只拦「页面渲染」造成的误释放；用户按 BACK / 换 tab 时必须真释放，
 // 否则快速连点返回会出现「已回首页但小窗/声音还在」。
 if (miniTransitionGuard && ("page".equals(reason) || "open".equals(reason))) {
 AppLog.i("Playback", "release skipped reason=mini-transition from=" + reason);
 return;
 }
 // 1.9.360：小窗过渡期间禁止释放播放器（TV BACK 回详情会先走页面释放）
 // 1.9.383：只有「目标页是详情页」才允许拦截；回首页/切 tab/换片等一律真释放，
 // 否则小窗与后台播放会残留到其它页面（真机日志：blocked reason=mini from=page）。
 if (miniMode && current != null && !current.player) {
 if (current.detail) {
 AppLog.i("Playback", "release blocked reason=mini from=" + reason);
 return;
 }
 AppLog.i("Playback", "mini release forced target=non-detail from=" + reason);
 miniMode = false;
 expandedOverDetail = false;
 if (miniDetails != null) {
 try {
 miniDetails.remove();
 } catch (Throwable ignored) {
 }
 miniDetails = null;
 }
 }
 releaseLiveOnPageChange(reason);
 if (targetIsPlayer) return;
 boolean active =
 player != null
 || playbackControls != null
 || miniMode
 || expandedOverDetail
 || bgPlayback
 || (playbackLayer != null && playbackLayer.getVisibility() == View.VISIBLE);
 if (!active) return;
 // 手机播放页会锁横屏并进沉浸式：离开播放页时一并恢复竖屏/系统栏，避免返回页残留全屏横屏
 if (!isTvDevice()) {
 applyMobileSystemBars(false);
 try {
 if (isInMultiWindowMode() || isInPictureInPictureMode()) {
 AppLog.i("Orient", "skip=true reason=release multiwindow");
 } else if (formFactor == TvLayoutPolicy.FORM_PHONE) {
 setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
 AppLog.i("Orient", "form=phone page=page set=portrait reason=release");
 } else {
 setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
 }
 } catch (Throwable ignored) {
 }
 }
 AppLog.i("Player", "release reason=" + reason + " page=" + pageTagForLog());
 PlaybackController.releasePlayer(this);
 }

 void smoothHomeScroll(int y) {
 if (scroll == null) return;
 if (scroll instanceof TvScrollView) ((TvScrollView) scroll).moveTo(y);
 else if (scroll instanceof RecyclerView) {
 RecyclerView list = (RecyclerView) scroll;
 if (y <= 0) {
 if (reduced) list.scrollToPosition(0);
 else list.smoothScrollToPosition(0);
 } else if (reduced) {
 list.scrollBy(0, y);
 } else {
 list.smoothScrollBy(0, y);
 }
 } else if (reduced) scroll.scrollTo(0, y);
 else ((ScrollView) scroll).smoothScrollTo(0, y);
 }

 void jumpHomeTop() {
 if (current == null || !current.home || scroll == null) return;
 View target = root.findViewWithTag("home-history:0");
 if (target == null) target = root.findViewWithTag("home-tab:0");
 if (target == null) target = root.findViewWithTag("source-switcher");
 if (target == null) target = root.findViewWithTag("primary");
 if (target != null) target.requestFocus();
 else if (scroll instanceof RecyclerView)
 // 首页虚拟化后头部可能尚未绑定：滚回顶部、item 重新绑定后再补一次焦点
 ui.postDelayed(this::focusHomeTopAfterScroll, 320);
 current.scroll = 0;
 current.scrollPos = 0;
 current.scrollOffset = 0;
 smoothHomeScroll(0);
 }

 private void focusHomeTopAfterScroll() {
 if (isFinishing()) return;
 if (current == null || !current.home) return;
 View target = root.findViewWithTag("home-history:0");
 if (target == null) target = root.findViewWithTag("home-tab:0");
 if (target == null) target = root.findViewWithTag("source-switcher");
 if (target == null) target = root.findViewWithTag("primary");
 if (target != null) {
 target.requestFocus();
 autoFocus = target;
 }
 }

 @Override
 public boolean dispatchTouchEvent(android.view.MotionEvent event) {
 noteInteraction();
 if (keyFocusMode
 && !remoteFocusMode
 && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
 keyFocusMode = false;
 try {
 TvNav.setTouchFocusMode(this, false);
 } catch (Throwable ignored) {
 }
 AppLog.i("Remote", "keyFocus=off reason=touch");
 }
 boolean handled = super.dispatchTouchEvent(event);
 // 1.9.79：遥控焦点模式下触摸不退出遥控；手指抬起后恢复焦点与 touch-mode
 if (remoteFocusMode
 && (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
 || event.getActionMasked()
 == android.view.MotionEvent.ACTION_CANCEL)) {
 ui.postDelayed(
 () -> {
 try {
 if (!remoteFocusMode) return;
 TvNav.setTouchFocusMode(this, true);
 View modal = ModalStack.topRoot();
 View focus;
 if (modal != null) {
 ModalStack.focusFirst(this, modal);
 focus = modal.findFocus();
 } else {
 focus =
 TvNav.ensureFocus(
 this,
 "touch",
 "detail-play",
 "home-video:0",
 "home-tonight",
 "primary",
 "nav-home");
 }
 AppLog.i(
 "Remote",
 "touch=restore-focus focus="
 + (focus == null
 ? "null"
 : String.valueOf(focus.getTag())));
 } catch (Throwable error) {
 AppLog.w("Remote", "touch restore failed", error);
 }
 },
 50);
 }
 return handled;
 }

 /** 1.9.127：深度优先找第一个可聚焦且可见的子控件。 */
 private static View findFirstFocusable(View view) {
 if (view == null) return null;
 if (view.isShown() && view.isFocusable() && view.getVisibility() == View.VISIBLE)
 return view;
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++) {
 View found = findFirstFocusable(group.getChildAt(i));
 if (found != null) return found;
 }
 }
 return null;
 }

 // ---------------- 1.9.147：控制条隐藏时左右键直接快退/快进 ----------------

 /**
 * 控制条隐藏时按左/右键：直接相对 seek ±10 秒，不再「先唤出控制条再聚焦进度条」。
 * 长按 repeat>0 只更新提示气泡、不重复 seek（避免频繁 seek 触发解码器风暴/卡顿）。
 */
 /** 1.9.167：每 5 分钟打印内存/缓冲观测日志。 */
 Runnable memoryLogTask;

 void startMemoryLog() {
 if (memoryLogTask != null) return;
 memoryLogTask =
 () -> {
 logMemory();
 ui.postDelayed(memoryLogTask, 5 * 60_000L);
 };
 ui.postDelayed(memoryLogTask, 5 * 60_000L);
 AppLog.i("Memory", "observe interval=5min start=true");
 startIdleClean();
 }

 /** 1.9.245：挂机清理（每5分钟检查）。 */
 Runnable idleCleanTask;
 long lastInteractionAt, idleWallpaperAt;
 boolean idleWallpaperReleased;

 void startIdleClean() {
 if (idleCleanTask != null) return;
 idleCleanTask =
 () -> {
 runIdleClean();
 ui.postDelayed(idleCleanTask, 5 * 60_000L);
 };
 ui.postDelayed(idleCleanTask, 5 * 60_000L);
 AppLog.i("Memory", "idle-clean schedule=5min enabled=" + prefs.getBoolean("idle_clean_enabled", true));
 }

 void noteInteraction() {
 lastInteractionAt = android.os.SystemClock.elapsedRealtime();
 if (idleWallpaperReleased
 && lastInteractionAt - idleWallpaperAt <= 30000
 && videoWallpaper != null) {
 idleWallpaperReleased = false;
 videoWallpaper.ensureDecoder("idle-interaction");
 AppLog.i("Wallpaper", "decoder recreated reason=idle-interaction");
 }
 }

 void runIdleClean() {
 try {
 boolean idle =
 player == null
 && !miniMode
 && (current == null || !current.player);
 if (!idle) return;
 int before = pssMbValue();
 ImageLoader.clearForBackground(this);
 boolean enabled = prefs.getBoolean("idle_clean_enabled", true);
 boolean gc = false;
 String wp = "kept";
 if (enabled) {
 System.gc();
 gc = true;
 long now = android.os.SystemClock.elapsedRealtime();
 if (now - lastInteractionAt >= 10 * 60_000L && videoWallpaper != null && !videoWallpaper.isReleased()) {
 videoWallpaper.stopAndReleaseDecoder("idle");
 idleWallpaperReleased = true;
 idleWallpaperAt = now;
 wp = "released";
 }
 }
 int after = pssMbValue();
 AppLog.i(
 "Memory",
 "idle-clean pss-before="
 + before
 + "MB after="
 + after
 + "MB freed="
 + Math.max(0, before - after)
 + "MB gc="
 + gc
 + " wallpaper="
 + wp);
 } catch (Throwable error) {
 AppLog.w("Memory", "idle-clean failed", error);
 }
 }

 int pssMbValue() {
 try {
 android.app.ActivityManager am =
 (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
 android.os.Debug.MemoryInfo[] info =
 am.getProcessMemoryInfo(new int[] {android.os.Process.myPid()});
 return info == null || info.length == 0 ? -1 : info[0].getTotalPss() / 1024;
 } catch (Throwable ignored) {
 return -1;
 }
 }

 void logMemory() {
 try {
 int pssMb = -1;
 android.app.ActivityManager am =
 (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
 if (am != null) {
 android.os.Debug.MemoryInfo[] info =
 am.getProcessMemoryInfo(new int[] {android.os.Process.myPid()});
 if (info != null && info.length > 0) pssMb = info[0].getTotalPss() / 1024;
 }
 boolean lowRam = am != null && am.isLowRamDevice();
 int cacheMb = imageCacheBytes() / (1024 * 1024);
 long javaMb =
 (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
 / (1024 * 1024);
 long nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
 int bufferSec = lowRam ? 20 : 15;
 int wideMb = Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024 ? 6 : 12;
 AppLog.i(
 "Memory",
 "cache="
 + cacheMb
 + "MB buffer="
 + bufferSec
 + "s wide="
 + wideMb
 + "MB pss="
 + (pssMb < 0 ? "?" : pssMb + "MB")
 + " lowRam="
 + lowRam
 + " java="
 + javaMb
 + "MB native="
 + nativeMb
 + "MB");
 } catch (Throwable error) {
 AppLog.w("Memory", "observe failed", error);
 }
 }

 boolean handleHiddenSeekKey(KeyEvent event) {
 int code = event.getKeyCode();
 if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
 if (current == null || !current.player || controlsVisible || isLive()) return false;
 // 1.9.148：小窗、未就绪/无时长时放行，去唤起控制条处理
 if (miniMode) return false;
 if (player == null || !prepared) return false;
 int duration = 0;
 try {
 duration = Math.max(0, player.getDuration());
 } catch (Throwable ignored) {
 }
 if (duration <= 0) return false;
 boolean forward = code == KeyEvent.KEYCODE_DPAD_RIGHT;
 int delta = forward ? 10000 : -10000;
 int base = 0;
 try {
 base = Math.max(0, player.getCurrentPosition());
 } catch (Throwable ignored) {
 }
 int target = Math.max(0, Math.min(duration, base + delta));
 int action = event.getAction();
 if (action == KeyEvent.ACTION_UP) return true;
 if (action != KeyEvent.ACTION_DOWN) return false;
 if (event.getRepeatCount() == 0) {
 PlaybackController.seekRelative(this, delta);
 AppLog.i(
 "Player",
 "key=" + (forward ? "right" : "left") + " hidden=true action=seek-relative");
 showPlayerSeekHint(forward, false, target, duration);
 } else {
 AppLog.i(
 "Player",
 "key="
 + (forward ? "right" : "left")
 + " hidden=true repeat="
 + event.getRepeatCount()
 + " action=hint-only");
 showPlayerSeekHint(forward, true, target, duration);
 }
 return true;
 }

 /** 隐藏控制条时的独立提示气泡（不依赖控制条，控制条隐藏也能看到）。 */
 void showPlayerSeekHint(boolean forward, boolean holding) {
 showPlayerSeekHint(forward, holding, -1, 0);
 }

 /** 1.9.148：提示气泡显示 seek 后的目标位置。 */
 void showPlayerSeekHint(boolean forward, boolean holding, int target, int duration) {
 if (playbackLayer == null) return;
 try {
 if (playerSeekHint == null) {
 playerSeekHint = text("", 13, 0xffffffff);
 playerSeekHint.setGravity(Gravity.CENTER);
 playerSeekHint.setPadding(dp(14), dp(8), dp(14), dp(8));
 playerSeekHint.setBackground(
 UiTokens.round(this, UiTokens.RADIUS_16, 0xb3000000, UiTokens.STROKE_SOFT));
 playerSeekHint.setShadowLayer(dp(6), 0, dp(2), 0xcc000000);
 FrameLayout.LayoutParams lp =
 new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
 lp.bottomMargin = dp(96);
 playbackLayer.addView(playerSeekHint, lp);
 }
 String base = forward ? "快进 10 秒" : "快退 10 秒";
 if (duration <= 0) duration = player == null ? 0 : Math.max(0, player.getDuration());
 int position =
 target >= 0
 ? target
 : (player == null ? 0 : Math.max(0, player.getCurrentPosition()));
 if (duration > 0) {
 base +=
 " · "
 + PlaybackController.formatTime(position)
 + " / "
 + PlaybackController.formatTime(duration);
 }
 if (holding) base += " · 按住不重复跳转";
 playerSeekHint.setText(base);
 playerSeekHint.setVisibility(View.VISIBLE);
 playerSeekHint.bringToFront();
 if (playerSeekHintHide != null) ui.removeCallbacks(playerSeekHintHide);
 playerSeekHintHide =
 () -> {
 if (playerSeekHint != null) playerSeekHint.setVisibility(View.GONE);
 };
 ui.postDelayed(playerSeekHintHide, 1200);
 } catch (Throwable ignored) {
 }
 }

 @Override
 public boolean dispatchKeyEvent(KeyEvent event) {
 noteInteraction();
 // 1.9.359：TV 小窗存在时，方向键从详情区回到小窗中央播放按钮（焦点兜底）
 try {
 if (miniMode
 && playbackLayer != null
 && event.getAction() == KeyEvent.ACTION_DOWN
 && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
 || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)) {
 View f = getCurrentFocus();
 boolean insideMini =
 f != null && isDescendantOf(playbackLayer, f);
 // 1.9.401：只在「真的没有焦点」时兜底。原先无条件把 ↑↓ 抢到小窗中央键，
 // 导致小窗态在选集页上纵向导航完全失效（第一次进选集页正常、返回小窗后失效）。
 if (!insideMini && f == null) {
 View cp = playbackLayer.findViewWithTag("mini-center-play");
 if (cp != null) {
 cp.requestFocus();
 AppLog.i("Mini", "focus fallback dead-focus -> mini");
 return true;
 }
 }
 }
 } catch (Throwable ignored) {
 }
 // 1.9.84：物理遥控/键盘方向键按下即进入焦点模式（isTvDevice 可能为 false）
 int pressed = event.getKeyCode();
 // 1.9.147：控制条隐藏时左右键直接快退/快进 10 秒（不唤出控制条、无需先聚焦进度条）
 if (handleHiddenSeekKey(event)) return true;
 // 1.9.102：播放器控制条任何按键输入都重置自动隐藏计时
 if (event.getAction() == KeyEvent.ACTION_DOWN
 && current != null
 && current.player
 && controlsVisible) {
 lastPlayerKey = KeyEvent.keyCodeToString(pressed);
 PlaybackController.scheduleHide(this);
 }
 boolean focusKey =
 pressed == KeyEvent.KEYCODE_DPAD_UP
 || pressed == KeyEvent.KEYCODE_DPAD_DOWN
 || pressed == KeyEvent.KEYCODE_DPAD_LEFT
 || pressed == KeyEvent.KEYCODE_DPAD_RIGHT
 || pressed == KeyEvent.KEYCODE_DPAD_CENTER
 || pressed == KeyEvent.KEYCODE_ENTER;
 // 1.9.126：任意方向/OK 按键先把隐藏的控制条唤出，避免被 seek/长按分支吞掉
 if (event.getAction() == KeyEvent.ACTION_DOWN
 && focusKey
 && current != null
 && current.player
 && !controlsVisible) {
 AppLog.i(
 "PlayerChrome",
 "show reason=key hiddenBefore=true key=" + KeyEvent.keyCodeToString(pressed));
 PlaybackController.showControls(this, true);
 }
 // 1.9.127：全局无焦点兜底，避免页面刷新后「无焦点死锁」
 if (focusKey
 && event.getAction() == KeyEvent.ACTION_DOWN
 && !(current != null && current.player)) {
 View cur = getCurrentFocus();
 if (cur == null || !cur.isShown()) {
 View first = findFirstFocusable(root);
 if (first != null) {
 first.requestFocus();
 AppLog.i(
 "Focus",
 "ensure reason=null-focus key=" + KeyEvent.keyCodeToString(pressed));
 return true;
 }
 }
 }
 if (focusKey && event.getAction() == KeyEvent.ACTION_DOWN && !keyFocusMode) { keyFocusMode = true;
 // 1.9.148：按键模式晚于 dock 构建时，补挂底部按钮焦点链
 if (playbackControls != null) playbackControls.refreshFocusMode();
 AppLog.i(
 "Remote",
 "keyFocus=on key="
 + KeyEvent.keyCodeToString(pressed)
 + " tv="
 + isTvDevice());
 try {
 TvNav.setTouchFocusMode(this, true);
 TvNav.ensureFocus(
 this,
 "key",
 "home-video:0",
 "detail-play",
 "primary",
 "nav-home");
 } catch (Throwable error) {
 AppLog.w("Remote", "keyFocus enter failed", error);
 }
 }
 if (introSkipKey && event.getAction() == KeyEvent.ACTION_UP) {
 introSkipKey = false;
 return true;
 }
 if (startupIntro != null) {
 int key = event.getKeyCode();
 if (event.getAction() == KeyEvent.ACTION_DOWN
 && (key == KeyEvent.KEYCODE_DPAD_CENTER
 || key == KeyEvent.KEYCODE_ENTER
 || key == KeyEvent.KEYCODE_BACK)) {
 introSkipKey = true;
 startupIntro.finish(true);
 }
 return true;
 }

 if (shortcutScreen != current) {
 homeKeys.reset();
 shortcutScreen = current;
 }
 if (current != null && current.home) {
 int key = event.getKeyCode();
 if (key == KeyEvent.KEYCODE_MENU) {
 homeKeys.reset();
 if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) chooseSources();
 return true;
 }
 if (event.getAction() == KeyEvent.ACTION_DOWN) {
 if (key == KeyEvent.KEYCODE_DPAD_UP) {
 if (homeKeys.up(event.getEventTime(), event.getRepeatCount())) {
 jumpHomeTop();
 return true;
 }
 } else homeKeys.reset();
 }
 }
 if (liveKey(event)) return true;
 // 1.9.54：TV 二级页（详情/播放除外）按键诊断 + 无焦点时强制落焦点，避免 DPAD/OK 无效
 if ((isTvDevice() || keyFocusMode || remoteFocusMode)
 && current != null
 && !current.home
 && !current.player) {
 int key = event.getKeyCode();
 View focus = getCurrentFocus();
 if (event.getAction() == KeyEvent.ACTION_DOWN) {
 AppLog.i(
 "TvKey",
 "page="
 + (body == null ? "detail" : String.valueOf(body.getTag()))
 + " code="
 + KeyEvent.keyCodeToString(key)
 + " action="
 + event.getAction()
 + " focus="
 + (focus == null
 ? "null"
 : String.valueOf(focus.getTag()))
 + " device="
 + event.getDeviceId()
 + " source=0x"
 + Integer.toHexString(event.getSource()));
 boolean nav =
 key == KeyEvent.KEYCODE_DPAD_UP
 || key == KeyEvent.KEYCODE_DPAD_DOWN
 || key == KeyEvent.KEYCODE_DPAD_LEFT
 || key == KeyEvent.KEYCODE_DPAD_RIGHT
 || key == KeyEvent.KEYCODE_DPAD_CENTER
 || key == KeyEvent.KEYCODE_ENTER;
 if (nav && (focus == null || !focus.isAttachedToWindow() || !focus.isShown())) {
 View first =
 TvNav.ensureFocus(
 this,
 body == null ? "detail" : String.valueOf(body.getTag()),
 "detail-play",
 "detail-line:0",
 "detail-back",
 "detail-favorite",
 "primary",
 "nav-home");
 if (first != null) {
 AppLog.i(
 "TvKey",
 "page=detail focus-fixed tag="
 + String.valueOf(first.getTag()));
 return true;
 }
 }
 }
 }
 View feedbackFocus = getCurrentFocus();
 if (feedbackFocus != null) FocusMotion.key(this, feedbackFocus, event.getKeyCode(), event);
 if (current != null && current.home && homeDock != null && homeDock.key(event)) return true;
 if (current != null && current.home && HomeScreen.navigationKey(this, event)) return true;
 if (PlaybackController.dispatchPlaybackKey(this, event)) return true;
 return super.dispatchKeyEvent(event);
 }

 @Override
 public void onWindowFocusChanged(boolean focus) {
 super.onWindowFocusChanged(focus);
 if (!focus) finishSeek(false);
 }

 @Override
 public void onTrimMemory(int level) {
 super.onTrimMemory(level);
 long before = ImageLoader.cacheBytes(this);
 ImageLoader.trimMemory(this, level);
 // 1.9.181：后台/内存不足清空图片与壁纸缓存
 if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
 || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
 ImageLoader.clearForBackground(this);
 if (images != null) images.evictAll();
 }
 long after = ImageLoader.cacheBytes(this);
 int freedMb = (int) Math.max(0, (before - after) / (1024 * 1024));
 AppLog.i(
 "Memory",
 "trim level="
 + level
 + " freed="
 + freedMb
 + "MB pss="
 + pssMb()
 + "MB");
 }

 /** 1.9.344：遍历当前视图树暂停/恢复 WebView 计时器（CSP 引擎常驻耗内存/CPU）。 */
 private void pauseWebViews(boolean pause) {
 try {
 if (root == null) return;
 java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
 stack.push(root);
 while (!stack.isEmpty()) {
 View v = stack.pop();
 if (v instanceof android.webkit.WebView) {
 if (pause) ((android.webkit.WebView) v).pauseTimers();
 else ((android.webkit.WebView) v).resumeTimers();
 }
 if (v instanceof android.view.ViewGroup) {
 android.view.ViewGroup g = (android.view.ViewGroup) v;
 for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
 }
 }
 } catch (Throwable ignored) {
 }
 }

 /** 1.9.359：判断 view 是否在 parent 子树内。 */
 private static boolean isDescendantOf(View parent, View child) {
 View v = child;
 while (v != null) {
 if (v == parent) return true;
 v = v.getParent() instanceof View ? (View) v.getParent() : null;
 }
 return false;
 }

 private int pssMb() {
 try {
 android.app.ActivityManager am =
 (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
 if (am == null) return -1;
 android.os.Debug.MemoryInfo[] info =
 am.getProcessMemoryInfo(new int[] {android.os.Process.myPid()});
 return info == null || info.length == 0 ? -1 : info[0].getTotalPss() / 1024;
 } catch (Throwable ignored) {
 return -1;
 }
 }

 @Override
 public void onLowMemory() {
 super.onLowMemory();
 if (images != null) images.evictAll();
 ImageLoader.clearForBackground(this);
 AppLog.i("Memory", "lowmemory caches cleared pss=" + pssMb() + "MB");
 }

 /** 5 分钟无操作后进入动态壁纸屏保；触摸任意位置退出。 */
 void scheduleScreensaver() {
 ui.removeCallbacks(screensaverTask);
 if (!prefs.getBoolean(Prefs.SCREENSAVER_ENABLED, false)) return;
 if (current != null && current.player) return;
 int minutes = Math.max(1, Math.min(60, prefs.getInt(Prefs.SCREENSAVER_TIMEOUT_MIN, 5)));
 ui.postDelayed(screensaverTask, minutes * 60 * 1000L);
 }

 void showScreensaver() {
 if (screensaverOverlay != null
 || isFinishing()
 || !foreground
 || current == null
 || current.player
 || appDialog != null) return;
 FrameLayout overlay = new FrameLayout(this);
 overlay.setBackgroundColor(0xff000000);
 if (videoWallpaperEnabled() && VideoWallpaper.file(this).isFile()) {
 VideoWallpaper wallpaper = new VideoWallpaper(this);
 overlay.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
 } else if (prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
 overlay.addView(
 ImageLoader.createAtmosphere(this), new FrameLayout.LayoutParams(-1, -1));
 }
 View dim = new View(this);
 dim.setBackgroundColor(0x66000000);
 overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

 LinearLayout column = vertical();
 column.setGravity(android.view.Gravity.CENTER);
 int clockStyle = Math.max(0, Math.min(2, prefs.getInt(Prefs.SCREENSAVER_CLOCK_STYLE, 0)));
 float clockSize = clockStyle == 1 ? 44 : clockStyle == 2 ? 96 : 64;
 TextView clock = text(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()), clockSize, 0xfff5f7fc);
 clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
 clock.setShadowLayer(dp(18), 0, dp(4), 0xcc000000);
 column.addView(clock);
 String dateText =
 new java.text.SimpleDateFormat("M 月 d 日 EEEE", java.util.Locale.CHINA)
 .format(new java.util.Date());
 TextView date = text(dateText, clockStyle == 2 ? 20 : 15, 0xccf5f7fc);
 date.setShadowLayer(dp(8), 0, dp(2), 0xcc000000);
 column.addView(date);
 TextView brand = text("鲸落 · 沉入故事深处", 12, 0x99ffc7d6);
 LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(-2, -2);
 brandLp.topMargin = dp(18);
 column.addView(brand, brandLp);
 overlay.addView(column, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));

 TextView hint = text("轻触屏幕继续", 12, 0x88f5f7fc);
 hint.setGravity(android.view.Gravity.CENTER);
 FrameLayout.LayoutParams hintLp =
 new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM);
 hintLp.bottomMargin = dp(48);
 overlay.addView(hint, hintLp);

 overlay.setClickable(true);
 overlay.setOnClickListener(v -> dismissScreensaver());
 overlay.setOnTouchListener(
 (v, event) -> {
 if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
 dismissScreensaver();
 return true;
 }
 return true;
 });
 screensaverOverlay = overlay;
 root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
 }

 void dismissScreensaver() {
 if (screensaverOverlay != null && root != null) {
 root.removeView(screensaverOverlay);
 }
 if (screensaverOverlay instanceof FrameLayout) {
 FrameLayout overlay = (FrameLayout) screensaverOverlay;
 for (int i = 0; i < overlay.getChildCount(); i++)
 if (overlay.getChildAt(i) instanceof VideoWallpaper)
 ((VideoWallpaper) overlay.getChildAt(i)).release();
 }
 screensaverOverlay = null;
 scheduleScreensaver();
 }

 @Override
 public void onUserInteraction() {
 super.onUserInteraction();
 if (screensaverOverlay != null) return;
 scheduleScreensaver();
 }

 @Override
 protected void onStart() {
 super.onStart();
 activeActivity = this;
 foreground = true;
 UpdateManager.get(this)
 .listen(
 () -> {
 if (updateScreen != null) updateScreen.refresh();
 if (foreground
 && current != null
 && current.home
 && appDialog == null
 && UpdateManager.get(this).consumeNotice())
 toast("发现新版，可在设置 → 软件更新查看");
 });
 ui.removeCallbacks(automaticUpdate);
 ui.postDelayed(automaticUpdate, 4000);
 scheduleScreensaver();
 }

 @Override
 protected void onPause() {
 // 1.9.344：切后台暂停 WebView 计时器（CSP 引擎常驻占内存/CPU）
 pauseWebViews(true);
 ui.removeCallbacks(focusAuditTask);
 ui.removeCallbacks(screensaverTask);
 savePlaybackHistory(true);
 if (playerChrome != null) playerChrome.pauseLoadingAnimation();
 super.onPause();
 }

 @Override
 protected void onResume() {
 pauseWebViews(false);
 AdFilter.sync(this);
 // 1.9.301：每次回前台恢复全局色调（修复进程重启后 GLOBAL_TINT=0）
 applyGlassTint();
 super.onResume();
 if (playerChrome != null) playerChrome.resumeLoadingAnimation();
 watchNetwork();
 long backgroundFor =
 backgroundedAt <= 0 ? 0 : android.os.SystemClock.elapsedRealtime() - backgroundedAt;
 backgroundedAt = 0;
 if (backgroundFor > 5 * 60_000L)
 AppLog.i("Player", "lifecycle resume backgroundMs=" + backgroundFor + " reconnect=true");
 // 1.9.65：切后台回来网络连接可能已被系统断开，用同一 URL 自动重连
 ui.postDelayed(() -> PlaybackController.retryAfterResume(this), 700);
 scheduleFocusAudit();
 ui.post(() -> applyPageOrientation("resume"));
 // 1.9.222：恢复性能浮层
 PerfOverlay.attachIfEnabled(this);
 // 1.9.223：测试模式日志初始化
 TestModeLog.configure(this);
 if (videoWallpaper != null) {
 boolean playerActive =
 (current != null && (current.player || (current.detail && miniMode)));
 if (playerActive) {
 // 1.9.224：播放器活跃时切回前台也不恢复壁纸
 AppLog.i("Wallpaper", "mobile resume skipped reason=player-active mini=" + miniMode);
 } else {
 videoWallpaper.ensureDecoder("on-resume");
 }
 }
 ui.postDelayed(this::cleanupOldCrashLogs, 1500);
 ui.postDelayed(this::purgeUnsupportedVideoWallpaper, 1200);
 startMemoryLog();
 if (bgPlayback) {
 bgPlayback = false;
 // 1.9.53：回到前台继续播放，不释放音频焦点/不暂停，仅撤掉常驻通知
 BgPlay.cancel(this);
 AppLog.i(
 "Player",
 "lifecycle resume activity player="
 + (player != null)
 + " mini="
 + miniMode);
 }
 }

 /** 1.9.65：WiFi/流量切换时，若播放器有错误立即同 URL 重试。 */
 void watchNetwork() {
 if (android.os.Build.VERSION.SDK_INT < 24 || networkCallback != null) return;
 try {
 final android.net.ConnectivityManager cm =
 (android.net.ConnectivityManager)
 getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
 if (cm == null) return;
 networkCallback =
 new android.net.ConnectivityManager.NetworkCallback() {
 @Override
 public void onAvailable(android.net.Network network) {
 AppLog.i("Player", "network change state=available reconnect=try");
 ui.postDelayed(
 () -> PlaybackController.retryAfterResume(MainActivity.this),
 600);
 }

 @Override
 public void onLost(android.net.Network network) {
 AppLog.i("Player", "network change state=lost");
 }
 };
 cm.registerDefaultNetworkCallback(networkCallback);
 } catch (Throwable error) {
 AppLog.w("Player", "network callback unavailable", error);
 }
 }

 @Override
 protected void onStop() {
 backgroundedAt = android.os.SystemClock.elapsedRealtime();
 PrivacySpace.cancelPrompt(this);
 finishSeek(false);
 if (playbackLoading != null) {
 clearPlaybackLoading();
 cancelPageRequests();
 stopWeb();
 }
 UpdateManager.get(this).listen(null);
 ui.removeCallbacks(automaticUpdate);
 if (phoneConfig != null) {
 PhoneConfigDialog session = phoneConfig;
 session.close();
 if (session.dialog != null) session.dialog.dismiss();
 }
 clearLiveNumber();
 if (startupIntro != null) startupIntro.cancel();
 introSkipKey = false;
 savePlaybackHistory(true);
 foreground = false;
 // 1.9.179：切后台暂停动态壁纸
 if (videoWallpaper != null) videoWallpaper.pause();
 super.onStop();
 boolean background =
 prefs.getBoolean("bg_play", true)
 && player != null
 && (current != null
 && (current.player || miniMode || expandedOverDetail));
 AppLog.i(
 "Player",
 "lifecycle pause activity player="
 + (player != null)
 + " mini="
 + miniMode
 + " background="
 + background);
 if (player != null) {
 try {
 player.pause();
 playing = false;
 if (embyReporter != null) embyReporter.update(player.getCurrentPosition(), true);
 if (playStatus != null) playStatus.setText("已暂停 · 按确认继续");
 } catch (Exception ignored) {
 }
 }
 // 1.9.179：切后台/主页键暂停并释放音频焦点，不自动续播
 releaseAudioFocus();
 AppLog.i(
 "Player",
 "background=stop player=" + (player != null) + " mini=" + miniMode + " bg=" + background);
 }

 @Override
 protected void onDestroy() {
 if (networkCallback != null) {
 try {
 android.net.ConnectivityManager cm =
 (android.net.ConnectivityManager)
 getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
 if (cm != null) cm.unregisterNetworkCallback(networkCallback);
 } catch (Throwable ignored) {
 }
 networkCallback = null;
 }
 RemoteInputScreen.stop("destroy");
 BgPlay.cancel(this);
 releaseAudioFocus();
 AppLog.i("BgPlay", "stop reason=destroy");
 if (privateMode && isFinishing() && !switchingPrivacy) PrivacySpace.active = false;
 if (activeActivity == this) activeActivity = null;
 clearPlaybackLoading();
 if (phoneConfig != null) phoneConfig.close();
 if (startupIntro != null) startupIntro.cancel();
 if (searchScreen != null) searchScreen.close();
 stopWeb();
 stopMagnet();
 generation++;
 // 1.9.106：销毁时统一释放（含直播浮层 WebView），避免退出后仍有声音
 releasePlaybackOnPageChange("destroy", false);
 releaseWallpaper();
 closeEngines();
 cancelArtwork();
 cancelPageRequests();
 details.shutdownNow();
 worker.shutdownNow();
 artwork.shutdownNow();
 phoneticWork.shutdownNow();
 ui.removeCallbacksAndMessages(null);
 images.evictAll();
 super.onDestroy();
 }

 void settings() {
 new SettingsScreen(this).show();
 }
}
