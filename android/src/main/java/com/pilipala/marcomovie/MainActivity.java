package com.pilipala.marcomovie;

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

/** Independent first native build. No original APK code or artwork is embedded. */
public final class MainActivity extends Activity {
    static final int WHITE = 0xfff5f5f7, MUTED = 0xffa1a1aa, DARK = 0xff101013;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newFixedThreadPool(3);
    final ExecutorService details = Executors.newFixedThreadPool(2);
    final DetailCache detailCache = new DetailCache();
    final List<Future<?>> pageRequests = new ArrayList<>();
    final ExecutorService artwork = Executors.newFixedThreadPool(3);
    final List<Future<?>> artworkRequests = new ArrayList<>();
    final ExecutorService phoneticWork = Executors.newSingleThreadExecutor();
    final LinkedHashSet<String> pendingTitles = new LinkedHashSet<>();
    final Runnable learnPendingTitles = this::learnPendingTitles;

    void learnPendingTitles() {
        List<String> titles = new ArrayList<>(pendingTitles);
        pendingTitles.clear();
        final PhoneticSearchIndex index = phonetic;
        final android.content.SharedPreferences target = prefs;
        if (index == null || titles.isEmpty()) return;
        phoneticWork.execute(
                () -> {
                    LinkedHashSet<String> values = new LinkedHashSet<>();
                    try {
                        org.json.JSONArray previous =
                                new org.json.JSONArray(
                                        target.getString(Prefs.PHONETIC_TITLES, "[]"));
                        for (int i = 0; i < previous.length(); i++)
                            values.add(previous.optString(i));
                    } catch (Exception ignored) {
                    }
                    for (String title : titles) {
                        index.learn(title);
                        values.add(title);
                    }
                    while (values.size() > 400) values.remove(values.iterator().next());
                    target.edit()
                            .putString(
                                    Prefs.PHONETIC_TITLES,
                                    new org.json.JSONArray(values).toString())
                            .apply();
                });
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
    volatile PhoneticSearchIndex phonetic;
    final CountDownLatch phoneticReady = new CountDownLatch(1);
    ConfigStore configStore;
    PhoneConfigDialog phoneConfig;
    LiveSession liveSession;
    final Runnable tuneLiveNumber = this::commitLiveNumber;
    LocalProxy localProxy;
    WebResolver webResolver;
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
    ScrollView scroll;
    Screen current;
    Cms.Config config;
    android.content.SharedPreferences prefs;
    boolean reduced, prepared, playing, foreground;
    int generation;
    final Map<String, SourceEngine> engines = new HashMap<>();
    final SingleFlight<SourceEngine> engineInitializations = new SingleFlight<>();
    final Map<String, HomeScreen.Snapshot> homeData = new ConcurrentHashMap<>();
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
        Runnable render;
        String focus, homeReturnFocus;
        int scroll;
        int episodeFocus = -1;
        String episodeFocusLine = "";
        final EpisodeSelection selection = new EpisodeSelection();
        boolean player, home;
        HomeScreen.State homeState;

        Screen(Runnable r) {
            render = r;
        }
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
        int percent = base.getSharedPreferences(Prefs.FILE, 0).getInt(Prefs.UI_SCALE_PERCENT, 100);
        int[] hint = viewportHint;
        if (hint != null && hint[0] == screen.widthPixels && hint[1] == screen.heightPixels) {
            screen.widthPixels = hint[2];
            screen.heightPixels = hint[3];
        }
        android.content.res.Configuration layout =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        layout.densityDpi =
                TvLayoutPolicy.densityDpi(screen.widthPixels, screen.heightPixels, percent);
        float density = layout.densityDpi / 160f;
        layout.screenWidthDp =
                Math.round(Math.max(screen.widthPixels, screen.heightPixels) / density);
        layout.screenHeightDp =
                Math.round(Math.min(screen.widthPixels, screen.heightPixels) / density);
        layout.smallestScreenWidthDp = Math.min(layout.screenWidthDp, layout.screenHeightDp);
        super.attachBaseContext(base.createConfigurationContext(layout));
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        configStore = new ConfigStore(getFilesDir());
        getWindow().getDecorView().setSystemUiVisibility(5894);
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE);
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
                }
        } catch (Exception e) {
            AppLog.w("MainActivity", "load saved config failed", e);
            config = null;
        }
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
                            for (int i = 0; i < old.length(); i++) index.learn(old.optString(i));
                            org.json.JSONArray history =
                                    new org.json.JSONArray(
                                            prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                            for (int i = history.length() - 1; i >= 0; i--)
                                index.prefer(history.optString(i));
                        } catch (Exception ignored) {
                        }
                        phonetic = index;
                    } catch (Exception ignored) {
                    } finally {
                        phoneticReady.countDown();
                    }
                });
        layoutIntro = b == null || b.getBoolean("layout_initial_launch", false);
        root = new FrameLayout(this);
        root.setBackgroundColor(DARK);
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
                                TvLayoutPolicy.densityDpi(
                                        width, height, prefs.getInt(Prefs.UI_SCALE_PERCENT, 100));
                        if (expected != getResources().getDisplayMetrics().densityDpi) {
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
                                    open(new Screen(() -> home()), false);
                                    layoutReady = true;
                                    if (layoutIntro) StartupIntro.show(MainActivity.this);
                                });
                    }
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("layout_initial_launch", !layoutReady && layoutIntro);
        super.onSaveInstanceState(out);
    }

    int dp(float x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    TextView text(String s, int size, int color) {
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
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(20), dp(9), dp(20), dp(9));
        b.setBackground(bg(0xff242429, 12));
        b.setTag(key);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(v -> action.run());
        pressFeedback(b);
        b.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                this, b, f, 0xff242429, WHITE, 12, WHITE, DARK, false, 1.025f));
        return b;
    }

    void pressFeedback(View view) {
        view.setOnTouchListener(
                (v, event) -> {
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
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(5), dp(14), dp(5));
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setBackground(bg(Color.TRANSPARENT, 20));
        b.setTextColor(MUTED);
        b.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                this,
                                b,
                                f,
                                Color.TRANSPARENT,
                                WHITE,
                                20,
                                MUTED,
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

    void renderPage(String title, String eyebrow, boolean navigation) {
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
        generation++;
        root = new FrameLayout(this);
        root.setBackgroundColor(DARK);
        root.addView(ImageLoader.createAtmosphere(this), new FrameLayout.LayoutParams(-1, -1));
        homeAmbient = null;
        if (navigation
                && prefs.getBoolean(Prefs.HOME_AMBIENT, true)
                && !prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
            homeAmbient = new HomeAmbient(this);
            root.addView(homeAmbient, new FrameLayout.LayoutParams(-1, -1));
        }
        scroll = new TvScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        body = vertical();
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(46), dp(26), dp(46), dp(44));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        homeDock = null;
        if (navigation) {
            body.setPadding(dp(34), dp(18), dp(100), dp(36));
            LinearLayout brandRow = row();
            brandRow.setTag("home-brand");
            ImageView brandMark = new ImageView(this);
            brandMark.setImageResource(R.drawable.logo_mark);
            LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(dp(30), dp(30));
            brandSize.rightMargin = dp(10);
            brandRow.addView(brandMark, brandSize);
            TextView brand = text("MARCO MOVIE", 14, WHITE);
            brand.setLetterSpacing(.10f);
            brand.setTypeface(null, Typeface.BOLD);
            brandRow.addView(brand);
            body.addView(brandRow);
            gap(body, title.isEmpty() ? 14 : 22);
            homeDock = new HomeDock(this);
        }
        if (!title.isEmpty()) {
            TextView eye = text(eyebrow, 12, 0xffa9c9f3);
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

    boolean available(Cms.Site s) {
        return s.supported()
                || (prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                        && (CspEngine.eligible(s) || JsEngine.eligible(s))
                        && config != null
                        && !config.spider.isEmpty());
    }

    int engineEpoch;

    SourceEngine engine(Cms.Site s) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        final Cms.Config snapshot;
        final int epoch;
        synchronized (this) {
            SourceEngine existing = engines.get(s.key);
            if (existing != null) return existing;
            snapshot = config;
            epoch = engineEpoch;
        }
        return engineInitializations.get(
                epoch + ":" + s.key,
                () -> {
                    synchronized (this) {
                        if (epoch != engineEpoch) throw new IOException("配置已更改，请重新选择来源");
                        SourceEngine existing = engines.get(s.key);
                        if (existing != null) return existing;
                    }
                    SourceEngine raw;
                    if (s.supported()) raw = new JsonCmsEngine(s);
                    else if (prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                            && CspEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(snapshot);
                        raw = new CspEngine(getApplication(), snapshot, s);
                    } else if (prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                            && JsEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(snapshot);
                        raw = new JsEngine(getApplication(), snapshot, s);
                    } else throw new IOException("来源需要尚未启用的扩展引擎");
                    SourceEngine created = new QueuedSourceEngine(raw);
                    synchronized (this) {
                        if (epoch == engineEpoch) {
                            if (raw instanceof JsEngine) ((JsEngine) raw).activate();
                            engines.put(s.key, created);
                            return created;
                        }
                    }
                    created.close();
                    throw new IOException("配置已更改，请重新选择来源");
                });
    }

    synchronized void ensureProxy(Cms.Config snapshot) throws IOException {
        if (localProxy == null) localProxy = new LocalProxy(getApplication(), snapshot);
    }

    void closeEngines() {
        liveSession = null;
        detailCache.clear();
        homeData.clear();
        if (localProxy != null) {
            localProxy.close();
            localProxy = null;
        }
        final List<SourceEngine> old;
        synchronized (this) {
            engineEpoch++;
            old = new ArrayList<>(engines.values());
            engines.clear();
        }
        if (!old.isEmpty())
            new Thread(
                            () -> {
                                for (SourceEngine e : old) e.close();
                            },
                            "close-source-engines")
                    .start();
    }

    void open(Screen s, boolean history) {
        capture();
        if (history && current != null) back.push(current);
        current = s;
        s.render.run();
        restore(s);
    }

    void capture() {
        if (current != null) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) current.focus = (String) f.getTag();
            if (scroll != null) current.scroll = scroll.getScrollY();
        }
    }

    /** Last focus placed by the app rather than the user; late content may replace only this. */
    View autoFocus;

    void restore(Screen s) {
        ui.post(
                () -> {
                    if (current != s) return;
                    if (scroll != null) scroll.scrollTo(0, s.scroll);
                    View target = s.focus == null ? null : root.findViewWithTag(s.focus);
                    if (target != null) {
                        target.requestFocus();
                        autoFocus = target;
                    } else {
                        View first = root.findViewWithTag("primary");
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
        restore(s);
    }

    @Override
    public void onBackPressed() {
        if (playbackLoading != null) {
            changingMiniEpisode = false;
            clearPlaybackLoading();
            cancelPageRequests();
            stopWeb();
            return;
        }
        if (current != null && current.player) {
            if (!miniMode && playbackControls != null) {
                if (controlsVisible) showControls(false);
                else if (isLive()) leavePlayback();
                else minimizePlayback();
                return;
            }
            if (miniMode) {
                releasePlayer();
                if (!back.isEmpty()) back.pop();
                if (!back.isEmpty()) {
                    current = back.pop();
                    current.render.run();
                    restore(current);
                } else {
                    current = new Screen(this::home);
                    current.render.run();
                }
                return;
            }
        }
        if (current != null && current.home) {
            releasePlayer();
            finish();
            return;
        }
        if (!back.isEmpty()) {
            capture();
            current = back.pop();
            current.render.run();
            restore(current);
        } else {
            releasePlayer();
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
        final FrameLayout parent = root;
        if (parent == null) return;
        TextView tip = text(message, 14, WHITE);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(22), dp(12), dp(22), dp(12));
        tip.setBackground(bg(0xf535353c, 22));
        tip.setTag("app-toast");
        FrameLayout.LayoutParams size =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        size.bottomMargin = dp(24);
        parent.addView(tip, size);
        ui.postDelayed(() -> parent.removeView(tip), 2600);
    }

    void cancelPageRequests() {
        for (Future<?> request : pageRequests) request.cancel(true);
        pageRequests.clear();
        ((ThreadPoolExecutor) worker).purge();
        ((ThreadPoolExecutor) details).purge();
    }

    Future<?> submitPage(Runnable task) {
        Future<?> request = RequestScope.submit(worker, task);
        pageRequests.removeIf(Future::isDone);
        pageRequests.add(request);
        return request;
    }

    void loadDetail(Cms.Site site, String id, Done<Cms.Video> done) {
        final int ticket = generation, epoch = engineEpoch;
        final String key = epoch + ":" + site.key.length() + ":" + site.key + id;
        final DetailRequest timing = new DetailRequest();
        Future<?> request =
                RequestScope.submit(
                        details,
                        () -> {
                            try {
                                timing.dispatched = SystemClock.elapsedRealtime();
                                String raw = detailCache.get(key);
                                timing.cached = raw != null;
                                if (raw == null) {
                                    SourceEngine source = engine(site);
                                    timing.engineReady = SystemClock.elapsedRealtime();
                                    long[] phases = new long[2];
                                    raw = SourceGate.measured(phases, () -> source.detail(id));
                                    timing.gateWaitNanos = phases[0];
                                    timing.delegateNanos = phases[1];
                                    timing.returned = SystemClock.elapsedRealtime();
                                } else
                                    timing.engineReady =
                                            timing.returned = SystemClock.elapsedRealtime();
                                if (Thread.currentThread().isInterrupted()) return;
                                Cms.Listing listing = Cms.listing(raw);
                                if (listing.videos.isEmpty()) throw new IOException("来源没有返回影片详情");
                                timing.parsed = SystemClock.elapsedRealtime();
                                if (!timing.cached && epoch == engineEpoch)
                                    detailCache.put(key, raw);
                                Cms.Video video = listing.videos.get(0);
                                ui.post(
                                        () -> {
                                            if (isFinishing() || ticket != generation) return;
                                            try {
                                                done.accept(video);
                                                root.postOnAnimation(timing::ready);
                                            } catch (Exception error) {
                                                paragraph("详情数据无法显示，请返回后重试。");
                                            }
                                        });
                            } catch (Exception error) {
                                if (Thread.currentThread().isInterrupted()) return;
                                ui.post(
                                        () -> {
                                            if (isFinishing() || ticket != generation) return;
                                            View state = root.findViewWithTag("detail-loading");
                                            if (state instanceof TextView)
                                                ((TextView) state).setText(message(error));
                                            LinearLayout target =
                                                    state != null
                                                                    && state.getParent()
                                                                            instanceof LinearLayout
                                                            ? (LinearLayout) state.getParent()
                                                            : body;
                                            addButton(
                                                    target, button("重试", "primary", this::refresh));
                                        });
                            }
                        });
        pageRequests.removeIf(Future::isDone);
        pageRequests.add(request);
    }

    <T> void load(String label, Work<T> task, Done<T> done) {
        final int ticket = generation;
        TextView loading = text(label, 17, MUTED);
        body.addView(loading);
        submitPage(
                () -> {
                    try {
                        T value = task.run();
                        ui.post(
                                () -> {
                                    if (isFinishing() || ticket != generation) return;
                                    body.removeView(loading);
                                    try {
                                        done.accept(value);
                                    } catch (Exception e) {
                                        notice("数据格式不符合此版本支持的格式。");
                                    }
                                });
                    } catch (Exception e) {
                        ui.post(
                                () -> {
                                    if (isFinishing() || ticket != generation) return;
                                    body.removeView(loading);
                                    paragraph(message(e));
                                    addButton(body, button("重试", "primary", () -> refresh()));
                                });
                    }
                });
    }

    String message(Exception e) {
        String s = e.getMessage();
        if (e instanceof CspEngine.EngineException) return s;
        if (s != null
                && (s.startsWith("配置")
                        || s.startsWith("来源返回 HTTP")
                        || s.startsWith("来源没有")
                        || s.startsWith("来源重定向")
                        || s.startsWith("单页超过")
                        || s.startsWith("响应超过")
                        || s.startsWith("剧集返回")
                        || s.startsWith("媒体地址返回")
                        || s.startsWith("此剧集"))) return s;
        return "连接超时、来源不可达或数据格式不受支持。请检查配置与网络后重试。";
    }

    void notice(String s) {
        new AppDialog.Builder(this).setMessage(s).setPositiveButton("知道了", null).show();
    }

    void learnTitles(List<Cms.Video> videos) {
        if (phonetic == null || videos == null || videos.isEmpty()) return;
        for (Cms.Video video : videos)
            if (video.title != null && !video.title.isEmpty()) pendingTitles.add(video.title);
        while (pendingTitles.size() > 400) pendingTitles.remove(pendingTitles.iterator().next());
        ui.removeCallbacks(learnPendingTitles);
        ui.postDelayed(learnPendingTitles, 500);
    }

    void sourceAction(Cms.Site site, String action) {
        detailCache.clear();
        final int ticket = generation;
        worker.execute(
                () -> {
                    try {
                        String response = engine(site).action(action);
                        ui.post(
                                () -> {
                                    if (ticket != generation
                                            || isFinishing()
                                            || response == null
                                            || response.trim().isEmpty()) return;
                                    try {
                                        org.json.JSONObject result =
                                                new org.json.JSONObject(response);
                                        if (result.has("list")) {
                                            Cms.Listing list = Cms.listing(response);
                                            new AppDialog.Builder(this)
                                                    .setTitle(site.name)
                                                    .setItems(
                                                            list.videos.stream()
                                                                    .map(v -> v.title)
                                                                    .toArray(String[]::new),
                                                            (dialog, index) ->
                                                                    detail(
                                                                            site,
                                                                            list.videos.get(index)))
                                                    .setNegativeButton("关闭", null)
                                                    .show();
                                        } else
                                            notice(
                                                    result.optString(
                                                            "msg",
                                                            result.optString("message", response)));
                                    } catch (Exception ignored) {
                                        notice(response);
                                    }
                                });
                    } catch (Exception error) {
                        ui.post(
                                () -> {
                                    if (ticket == generation) notice(message(error));
                                });
                    }
                });
    }

    void pickDanmaku() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, 43);
        } catch (Exception error) {
            notice("这台电视没有文件选择器。");
        }
    }

    void pickSubtitle() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, 42);
        } catch (Exception error) {
            notice("这台电视没有文件选择器，可安装文件管理器后再导入字幕。");
        }
    }

    void home() {
        if (current != null) current.home = true;
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
        new HomeScreen(this, site).show();
    }

    void chooseSources() {
        SourcePicker.show(this);
    }

    void importDialog() {
        if (phoneConfig != null) phoneConfig.close();
        phoneConfig = new PhoneConfigDialog(this);
        phoneConfig.show();
    }

    void addressImportDialog() {
        AddressInput input =
                new AddressInput(this, "https://…/config.json", prefs.getString("origin", ""));
        AppDialog d =
                new AppDialog.Builder(this)
                        .setTitle("导入配置")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("选择本地文件", (x, y) -> pickConfig())
                        .setPositiveButton("导入地址", null)
                        .create();
        d.setOnShowListener(
                x ->
                        d.getButton(-1)
                                .setOnClickListener(
                                        view -> {
                                            String address = input.value();
                                            if (!Cms.isHttp(address)) {
                                                input.invalid("请输入 HTTP(S) 配置地址");
                                                return;
                                            }
                                            d.dismiss();
                                            open(
                                                    new Screen(
                                                            () -> {
                                                                renderPage(
                                                                        "正在导入",
                                                                        "IMPORT CONFIGURATION");
                                                                load(
                                                                        "连接配置并读取来源…",
                                                                        () -> {
                                                                            String raw =
                                                                                    Cms.fetch(
                                                                                            address,
                                                                                            null);
                                                                            return Cms.parseConfig(
                                                                                    raw, address);
                                                                        },
                                                                        c ->
                                                                                saveConfig(
                                                                                        c,
                                                                                        address));
                                                            }),
                                                    true);
                                        }));
        d.show();
    }

    void pickConfig() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 41);
        } catch (Exception e) {
            notice("这台电视没有系统文件选择器。请安装文件管理器，或使用配置地址导入。");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UpdateScreen.INSTALL) {
            UpdateScreen.result(this);
            return;
        }
        if (request >= 44 && request <= 46) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                SettingsScreen.result(this, request, data.getData());
            return;
        }
        if (request == 43 && result == RESULT_OK && data != null && data.getData() != null) {
            final PlaybackControls controls = playbackControls;
            if (controls != null) {
                Uri file = data.getData();
                worker.execute(
                        () -> {
                            try (InputStream in = getContentResolver().openInputStream(file)) {
                                String text = Cms.read(in, 8 * 1024 * 1024);
                                ui.post(
                                        () -> {
                                            if (controls == playbackControls) {
                                                controls.danmaku.enabled(true);
                                                controls.danmaku.loadText(
                                                        text,
                                                        new DanmakuView.Listener() {
                                                            public void loaded(int count) {
                                                                toast("已加载 " + count + " 条弹幕");
                                                            }

                                                            public void error(String message) {
                                                                notice(message);
                                                            }
                                                        });
                                            }
                                        });
                            } catch (Exception error) {
                                ui.post(() -> notice("弹幕文件无法读取。"));
                            }
                        });
            }
            return;
        }
        if (request == 42 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri subtitle = data.getData();
            if (player != null && prepared) {
                String name = "外挂字幕";
                try (android.database.Cursor cursor =
                        getContentResolver()
                                .query(
                                        subtitle,
                                        new String[] {
                                            android.provider.OpenableColumns.DISPLAY_NAME
                                        },
                                        null,
                                        null,
                                        null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                } catch (Exception ignored) {
                }
                try {
                    player.addExternalSubtitle(subtitle, name, MediaOptions.subtitleMime(name, ""));
                    toast("已导入字幕");
                } catch (Exception error) {
                    notice("字幕无法读取，请选择 SRT、VTT、ASS 或 TTML 文件。");
                }
            }
            return;
        }
        if (request == 41 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            open(
                    new Screen(
                            () -> {
                                renderPage("正在读取文件", "IMPORT CONFIGURATION");
                                load(
                                        "读取本地配置…",
                                        () -> {
                                            try (InputStream i =
                                                    getContentResolver().openInputStream(uri)) {
                                                return Cms.parseConfig(
                                                        Cms.read(i, Cms.MAX_JSON), null);
                                            }
                                        },
                                        c -> saveConfig(c, ""));
                            }),
                    true);
        }
    }

    void saveConfig(Cms.Config c, String origin) throws Exception {
        configStore.write(c.raw);
        closeEngines();
        config = c;
        configOrigin = origin;
        prefs.edit().putString("origin", origin).apply();
        back.clear();
        current = new Screen(() -> home());
        current.render.run();
        restore(current);
        toast("已导入 " + c.sites.size() + " 个来源");
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
        DetailScreen.show(this, s, listing, ready);
    }

    void renderEpisodes(
            Cms.Site site, Cms.Video video, int lineIndex, int page, LinearLayout panel) {
        DetailScreen.renderEpisodes(this, site, video, lineIndex, page, panel, null);
    }

    void liveSources() {
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
        return WatchHistory.read(prefs.getString(Prefs.WATCH_HISTORY, "[]"));
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
        if (scroll instanceof TvScrollView) ((TvScrollView) scroll).moveTo(y);
        else if (reduced) scroll.scrollTo(0, y);
        else scroll.smoothScrollTo(0, y);
    }

    void jumpHomeTop() {
        if (current == null || !current.home || scroll == null) return;
        View target = root.findViewWithTag("home-history:0");
        if (target == null) target = root.findViewWithTag("home-tab:0");
        if (target == null) target = root.findViewWithTag("source-switcher");
        if (target == null) target = root.findViewWithTag("primary");
        if (target != null) target.requestFocus();
        current.scroll = 0;
        smoothHomeScroll(0);
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

    @Override
    protected void onStart() {
        super.onStart();
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
    }

    @Override
    protected void onStop() {
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
                if (playStatus != null) playStatus.setText("已暂停 · 按确认继续");
            } catch (Exception ignored) {
            }
    }

    @Override
    protected void onDestroy() {
        clearPlaybackLoading();
        if (phoneConfig != null) phoneConfig.close();
        if (startupIntro != null) startupIntro.cancel();
        if (searchScreen != null) searchScreen.close();
        stopWeb();
        stopMagnet();
        generation++;
        releasePlayer();
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
