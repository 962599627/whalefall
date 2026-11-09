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
            new LruCache<String, Bitmap>(10 * 1024 * 1024) {
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };
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
    MiniDetails miniDetails;
    boolean miniMode;
    boolean changingMiniEpisode;
    FrameLayout playerOverlay;
    boolean controlsVisible = true;
    Runnable hideControls;
    PlayerChrome playerChrome;
    MoviePlayer player;
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
        HomeScreen.State homeState;
        HomeScreen retainedHome;

        /** True while a listing redirect (folder / action / cross-search) has not drawn a page. */
        boolean redirect;

        Screen(Runnable r) {
            render = r;
        }
    }

    /** Foreground activity that source extensions may need for dialogs; cleared on destroy. */
    static volatile MainActivity activeActivity;

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
        view.setTextSize(11f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextColor(0xffd5e1f2);
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
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new java.io.File(dir, "crash-last.txt"));
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Opens a list card without leaving an invisible redirect page in the back stack. */
    void openListing(Cms.Site s, Cms.Video v) {
        if (v == null) return;
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
        open(new Screen(() -> detail(s, v)), true);
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

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        privateMode = PrivacySpace.active;
        if (privateMode) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        activeActivity = this;
        installPluginCrashGuard();
        android.util.DisplayMetrics rawScreen = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(rawScreen);
        formFactor =
                detectFormFactor(
                        this, rawScreen.widthPixels, rawScreen.heightPixels, rawScreen.densityDpi);
        setRequestedOrientation(
                isTvDevice()
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
        configStore = new ConfigStore(getFilesDir());
        if (isTvDevice()) {
            getWindow().getDecorView().setSystemUiVisibility(5894);
        } else {
            applyMobileSystemBars(false);
        }
        prefs = getSharedPreferences(privateMode ? "movie-private" : Prefs.FILE, MODE_PRIVATE);
        reduced = prefs.getBoolean(Prefs.REDUCED, false);
        configOrigin = prefs.getString("origin", "");
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

    TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
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
        b.setTextColor(WHITE);
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
        GradientDrawable pill = bg(0xff1b2c49, 19);
        pill.setStroke(Math.max(1, dp(1)), 0x33ffffff);
        b.setBackground(pill);
        b.setTextColor(0xffe6edfb);
        b.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                this, b, f, 0xff1b2c49, WHITE, 19, 0xffe6edfb, DARK, false, 1.02f));
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

    void preparePage(boolean navigation) {
        clearPlaybackLoading();
        cancelArtwork();
        cancelPageRequests();
        if (current != null) current.home = navigation;
        if (searchScreen != null) {
            searchScreen.close();
            searchScreen = null;
        }
        stopWeb();
        stopMagnet();
        releasePlayer();
        if (!isTvDevice()) {
            applyMobileSystemBars(false);
        }
        generation++;
    }

    static final String BACKDROP_TAG = "whale-backdrop";
    /** 用户壁纸层（静态 Images/Atmosphere）专用 tag：与 base 渐变分开，便于幂等维护。 */
    static final String WALLPAPER_TAG = "whale-wallpaper-image";
    /** applyWallpaper 的日志签名：只在状态变化时写一条，避免刷屏。 */
    private String lastWallpaperLog;

    /** 幂等：确保 base 渐变 + 用户壁纸层（静态图或动态视频）都存在且唯一；缺则补、多则删。 */
    void applyWallpaper(android.widget.FrameLayout target) {
        if (target == null) return;
        final boolean staticEnabled = prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false);
        final boolean videoEnabled = videoWallpaperEnabled();

        // 1. base 渐变：没有就补
        boolean hasBase = false;
        for (int i = 0; i < target.getChildCount(); i++)
            if (BACKDROP_TAG.equals(target.getChildAt(i).getTag())) {
                hasBase = true;
                break;
            }
        if (!hasBase) {
            View base = new View(this);
            base.setTag(BACKDROP_TAG);
            base.setBackground(Skin.backdrop(this));
            base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(base, 0, new FrameLayout.LayoutParams(-1, -1));
        }

        // 2. 静态壁纸层：动态壁纸优先时全部移除；静态模式保留且唯一
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
        if (staticEnabled && !videoEnabled && staticCount == 0) {
            java.io.File staticFile = new java.io.File(getFilesDir(), "wallpaper.jpg");
            if (!staticFile.isFile())
                AppLog.w("Wallpaper", "static wallpaper file missing: " + staticFile, null);
            View atmosphere = ImageLoader.createAtmosphere(this);
            atmosphere.setTag(WALLPAPER_TAG);
            target.addView(
                    atmosphere,
                    Math.min(1, target.getChildCount()),
                    new FrameLayout.LayoutParams(-1, -1));
            staticCount = 1;
        }

        // 3. 动态壁纸：存在但挂在旧 root 时搬过来；否则释放并新建
        if (videoEnabled) {
            if (videoWallpaper == null || videoWallpaper.isReleased()) {
                releaseWallpaper();
                videoWallpaper = new VideoWallpaper(this);
                target.addView(
                        videoWallpaper,
                        Math.min(1, target.getChildCount()),
                        new FrameLayout.LayoutParams(-1, -1));
            } else if (videoWallpaper.getParent() != target) {
                videoWallpaper.attachTo(target);
            }
        } else {
            if (videoWallpaper != null
                    && videoWallpaper.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) videoWallpaper.getParent()).removeView(videoWallpaper);
            releaseWallpaper();
        }

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
                    "apply root="
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

    void renderPage(String title, String eyebrow, boolean navigation) {
        VideoFilterDialog.dismiss(this);
        preparePage(navigation);
        root = new FrameLayout(this);
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
        return !reduced
                && prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
                && VideoWallpaper.file(this).isFile();
    }

    void releaseWallpaper() {
        if (videoWallpaper != null) {
            videoWallpaper.release();
            videoWallpaper = null;
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
        capture();
        if (history && current != null) back.push(current);
        current = s;
        s.render.run();
        if (root != null) ensureBackdrop(root);
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
    }

    void refresh() {
        capture();
        Screen s = current;
        s.render.run();
        if (root != null) ensureBackdrop(root);
        restore(s);
    }

    void renderReturn(Screen screen) {
        if (!isTvDevice() && screen.mobileHome != null && screen.mobileHome.resume()) return;
        if (isTvDevice() && screen.retainedHome != null && screen.retainedHome.resume()) return;
        screen.render.run();
        if (root != null) ensureBackdrop(root);
    }

    @Override
    public void onBackPressed() {
        if (tvPlayer != null && tvPlayer.back()) return;
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
            if (!miniMode && playbackControls != null) {
                if (controlsVisible) showControls(false);
                else if (isLive() || !isTvDevice()) leavePlayback();
                else minimizePlayback();
                return;
            }
            if (miniMode) {
                releasePlayer();
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
        }
        if (current != null && current.home) {
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
            releasePlayer();
        releaseWallpaper();
            finish();
            return;
        }
        if (!back.isEmpty()) {
            capture();
            current = back.pop();
            renderReturn(current);
            restore(current);
        } else {
            releasePlayer();
        releaseWallpaper();
            finish();
        }
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
        SourceController.sourceAction(this, site, action);
    }

    void sourceAction(Cms.Site site, String action, Runnable finished) {
        SourceController.sourceAction(this, site, action, finished);
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
        for (Cms.Site s : config.sites) if (available(s)) supported++;
        paragraph("已保存 " + config.sites.size() + " 个来源。选择一个，开始浏览。还未支持的来源会保留在列表中。");
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
    public boolean dispatchKeyEvent(KeyEvent event) {
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
        ImageLoader.trimMemory(this, level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        images.evictAll();
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
        ui.removeCallbacks(screensaverTask);
        savePlaybackHistory(true);
        super.onPause();
    }

    @Override
    protected void onStop() {
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
        super.onStop();
        if (player != null)
            try {
                player.pause();
                playing = false;
                if (embyReporter != null) embyReporter.update(player.getCurrentPosition(), true);
                if (playStatus != null) playStatus.setText("已暂停 · 按确认继续");
            } catch (Exception ignored) {
            }
    }

    @Override
    protected void onDestroy() {
        if (privateMode && isFinishing() && !switchingPrivacy) PrivacySpace.active = false;
        if (activeActivity == this) activeActivity = null;
        clearPlaybackLoading();
        if (phoneConfig != null) phoneConfig.close();
        if (startupIntro != null) startupIntro.cancel();
        if (searchScreen != null) searchScreen.close();
        stopWeb();
        stopMagnet();
        generation++;
        releasePlayer();
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
