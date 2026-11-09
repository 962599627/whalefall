package com.whalefall.movie;

import android.os.SystemClock;
import android.webkit.CookieManager;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网盘登录状态检测（三态 + 用户确认，只报可确认的事实，绝不猜测）。
 *
 * <p>自动判据（只有「登录凭据型」证据才算已登录）：
 * <ol>
 *   <li>本地凭据：{@link AccountPanel#scanCredentials} + {@link AccountPanel#driveHasLocalCredential}；
 *   <li>WebView 登录 Cookie：夸克 {@code __puus}、百度 {@code BDUSS}、UC {@code __puus}/{@code _uc_s}、
 *       阿里 {@code refresh_token}/{@code ALIYUN}；普通站点 Cookie 不算；
 *   <li>强凭据 token（全部 shared_prefs，除 movie/movie-private）：值满足
 *       {@link AccountPanel#credentialValue} 且含 {@code kps=}/{@code bduss}/{@code uc_st}/
 *       {@code refresh_token}/{@code stoken=}/{@code __puus=}/{@code __pus=}；
 *   <li>App 私有目录小文件（files/no_backup/databases/cache，≤512KB、最多 300 个）：
 *       用带值长度的 token 正则匹配（避免插件源码里的 token 名误命中）。
 * </ol>
 * 以上都没有时：若用户点过「我已在源里登录」→「已登录（用户确认）」；否则
 * {@link #STATE_UNKNOWN}（「由源管理 · 无法读取」），不会显示成「未登录」。
 *
 * <p>性能：一次扫描取 prefs/文件/Cookie 快照（4 盘共用）；结果 60s 静态缓存（两面板共享），
 * 显式刷新才 force；同站点并发合并；超过 1.5s 内的重复 force 复用上次结果防刷屏。
 */
final class PanLoginState {
    static final long CACHE_TTL_MS = 60_000L;
    static final long FORCE_BURST_GUARD_MS = 1_500L;
    static final int STATE_LOCAL = 0; // 本机凭据（可确认）
    static final int STATE_SOURCE = 1; // 源可确认：登录 Cookie / 强凭据 token / 私有文件
    static final int STATE_UNKNOWN = 2; // 无法读取（由源管理）
    static final int STATE_MANUAL = 3; // 用户手动确认（非自动检测）

    static final String MANUAL_KEY_PREFIX = "pan_manual_login_";
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_FILES = 300;
    private static final int MAX_FILE_HITS = 120;
    private static final int MAX_EVIDENCE = 2;
    private static final Set<String> SKIP_DIRS =
            new LinkedHashSet<>(
                    java.util.Arrays.asList(
                            "lib", "code_cache", "app_webview", "app_textures", "app_cache",
                            "cache", "no_backup_tmp", "oat", "tmp"));
    private static final String[] BINARY_EXTENSIONS = {
        ".so", ".apk", ".zip", ".jar", ".dex", ".mp4", ".mkv", ".webm", ".mp3", ".m4a",
        ".wav", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ttf", ".otf", ".woff", ".woff2"
    };

    final String drive;
    final int state;
    final String detail;
    final String account;
    final String evidence;

    private PanLoginState(String drive, int state, String detail, String account, String evidence) {
        this.drive = drive;
        this.state = state;
        this.detail = detail;
        this.account = account;
        this.evidence = evidence;
    }

    boolean known() {
        return state != STATE_UNKNOWN;
    }

    /** 一次检测的完整结果：网盘状态 + 本地凭据/账号名（面板共用）。 */
    static final class Snapshot {
        final Map<String, PanLoginState> states;
        final List<AccountPanel.Credential> credentials;
        final String accountName;

        Snapshot(
                Map<String, PanLoginState> states,
                List<AccountPanel.Credential> credentials,
                String accountName) {
            this.states = states;
            this.credentials = credentials;
            this.accountName = accountName;
        }
    }

    interface Callback {
        void ready(Snapshot snapshot);
    }

    // ---------------- 60s 短 TTL 缓存（两面板共享） ----------------

    private static final Object CACHE_LOCK = new Object();
    private static Snapshot cached;
    private static long cachedAt;
    private static long lastScanAt;
    private static String cachedSite = "";
    private static boolean loading;
    private static String loadingSite = "";
    private static final List<Callback> waiters = new ArrayList<>();

    static void invalidate() {
        synchronized (CACHE_LOCK) {
            cached = null;
            cachedAt = 0L;
            cachedSite = "";
        }
    }

    static void detectAsync(
            final MainActivity a,
            final Cms.Site site,
            final boolean force,
            final Callback callback) {
        final String siteKey = site == null || site.key == null ? "" : site.key;
        synchronized (CACHE_LOCK) {
            long now = SystemClock.uptimeMillis();
            boolean fresh = cached != null && siteKey.equals(cachedSite)
                    && now - cachedAt < CACHE_TTL_MS;
            // 非强制：60s 内复用；强制但距上次扫描 <1.5s：复用，避免同一动作连续重扫刷屏
            if (fresh && (!force || now - lastScanAt < FORCE_BURST_GUARD_MS)) {
                final Snapshot snapshot = cached;
                a.ui.post(() -> callback.ready(snapshot));
                return;
            }
            if (loading && siteKey.equals(loadingSite)) {
                waiters.add(callback);
                return;
            }
            loading = true;
            loadingSite = siteKey;
        }
        a.worker.execute(
                () -> {
                    Snapshot snapshot;
                    try {
                        snapshot = detectAll(a, site);
                    } catch (Throwable error) {
                        AppLog.w("PanLogin", "detect failed", error);
                        snapshot = new Snapshot(new LinkedHashMap<>(), new ArrayList<>(), "");
                    }
                    final Snapshot result = snapshot;
                    final List<Callback> pending;
                    synchronized (CACHE_LOCK) {
                        cached = result;
                        cachedAt = SystemClock.uptimeMillis();
                        lastScanAt = cachedAt;
                        cachedSite = siteKey;
                        loading = false;
                        loadingSite = "";
                        pending = new ArrayList<>(waiters);
                        waiters.clear();
                    }
                    a.ui.post(
                            () -> {
                                callback.ready(result);
                                for (Callback waiter : pending) waiter.ready(result);
                            });
                });
    }

    /** 单次扫描（worker 线程）：本地凭据 + 一次全 prefs/私有文件/Cookie 快照，4 盘共用。 */
    static Snapshot detectAll(MainActivity a, Cms.Site site) {
        List<AccountPanel.Credential> credentials =
                site == null ? new ArrayList<>() : AccountPanel.scanCredentials(a, site);
        String accountName = AccountPanel.findAccountName(credentials);
        List<Hit> prefHits = prefsHits(a);
        List<Hit> fileHits = fileHits(a);
        Map<String, String> cookies = cookieSnapshot();
        Map<String, PanLoginState> states = new LinkedHashMap<>();
        int local = 0, source = 0, unknown = 0, manual = 0;
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
            PanLoginState state = detect(a, credentials, drive.key, accountName, prefHits, fileHits, cookies);
            states.put(drive.key, state);
            if (state.state == STATE_LOCAL) local++;
            else if (state.state == STATE_SOURCE) source++;
            else if (state.state == STATE_MANUAL) manual++;
            else unknown++;
            AppLog.i(
                    "PanLogin",
                    "drive="
                            + drive.key
                            + " state="
                            + stateName(state.state)
                            + " evidence="
                            + state.evidence);
        }
        AppLog.i(
                "PanLogin",
                "scan prefs="
                        + prefsFileCountForLog
                        + " prefHits="
                        + prefHits.size()
                        + " files="
                        + fileCountForLog
                        + " fileHits="
                        + fileHits.size()
                        + " cookies=["
                        + join(cookies.keySet())
                        + "]");
        AppLog.i(
                "PanLogin",
                "detect site="
                        + (site == null ? "-" : site.key)
                        + " local="
                        + local
                        + " source="
                        + source
                        + " manual="
                        + manual
                        + " unknown="
                        + unknown
                        + " (unknown=no auto evidence)");
        return new Snapshot(states, credentials, accountName);
    }

    private static String stateName(int state) {
        return state == STATE_LOCAL
                ? "local"
                : state == STATE_SOURCE ? "source" : state == STATE_MANUAL ? "manual" : "unknown";
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    static PanLoginState detect(
            MainActivity a,
            List<AccountPanel.Credential> credentials,
            String drive,
            String accountName,
            List<Hit> prefHits,
            List<Hit> fileHits,
            Map<String, String> cookies) {
        if (AccountPanel.driveHasLocalCredential(credentials, drive)) {
            return new PanLoginState(
                    drive, STATE_LOCAL, "已登录（本地凭据）", accountName, "local credentials");
        }
        String loginCookie = cookieLoginEvidence(cookies, drive);
        if (!loginCookie.isEmpty()) {
            return new PanLoginState(drive, STATE_SOURCE, loginCookie, "", loginCookie);
        }
        List<String> prefs = evidenceFor(prefHits, drive);
        List<String> files = evidenceFor(fileHits, drive);
        List<String> all = new ArrayList<>();
        all.addAll(prefs);
        all.addAll(files);
        if (!all.isEmpty()) {
            String joined = joinList(all);
            return new PanLoginState(
                    drive,
                    STATE_SOURCE,
                    all.size() > 1 ? "源存储/私有文件命中多条凭据" : "源存储/私有文件有登录凭据（账号名不可读）",
                    "",
                    joined);
        }
        if (manualLogin(a, drive)) {
            return new PanLoginState(
                    drive, STATE_MANUAL, "已登录（用户确认，非自动检测）", "", "manual confirm");
        }
        String siteCookie = cookieSiteEvidence(cookies, drive);
        if (!siteCookie.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_UNKNOWN,
                    "有站点 Cookie，但无法确认登录（由源管理）",
                    "",
                    "unknown: site cookie only @" + siteCookie);
        }
        return new PanLoginState(
                drive,
                STATE_UNKNOWN,
                "由源管理 · 无法读取",
                "",
                "unknown: no login cookie / strong token in prefs or private files");
    }

    private static String joinList(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size() && i < MAX_EVIDENCE; i++) {
            if (out.length() > 0) out.append("；");
            out.append(values.get(i));
        }
        return out.toString();
    }

    /** 诊断日志用：完整拼接（最多 80 项，避免超长日志）。 */
    private static String joinAll(List<String> values) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(values.size(), 80);
        for (int i = 0; i < limit; i++) {
            if (out.length() > 0) out.append(',');
            out.append(values.get(i));
        }
        if (values.size() > limit) out.append(",…");
        return out.toString();
    }

    private static List<String> evidenceFor(List<Hit> hits, String drive) {
        List<String> out = new ArrayList<>();
        if (hits == null) return out;
        for (Hit hit : hits) {
            if (hit == null || !drive.equals(hit.drive)) continue;
            String where = hit.where;
            if (!out.contains(where)) out.add(where);
            if (out.size() >= MAX_EVIDENCE) break;
        }
        return out;
    }

    // ---------------- 手动确认 ----------------

    static boolean manualLogin(MainActivity a, String drive) {
        try {
            return a.prefs.getBoolean(MANUAL_KEY_PREFIX + drive, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void markManual(MainActivity a, String drive, boolean value) {
        a.prefs.edit().putBoolean(MANUAL_KEY_PREFIX + drive, value).apply();
        invalidate();
    }

    // ---------------- 强 token 判定 ----------------

    /** 网盘强凭据 token（值内命中才算；裸域名不在此列）。 */
    private static String[] strongTokens(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"kps=", "__puus=", "__pus="};
            case "uc":
                return new String[] {"uc_st", "_uc_s=", "drive.uc.cn"};
            case "baidu":
                return new String[] {"bduss", "stoken="};
            case "ali":
                return new String[] {"refresh_token", "aliyundrive", "alipan"};
            default:
                return new String[0];
        }
    }

    private static List<String> drivesForToken(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> drives = new ArrayList<>();
        if (lower.contains("kps=") || lower.contains("__puus=") || lower.contains("__pus="))
            drives.add("quark");
        if (lower.contains("uc_st") || lower.contains("_uc_s=") || lower.contains("drive.uc.cn"))
            drives.add("uc");
        if (lower.contains("bduss") || lower.contains("stoken=")) drives.add("baidu");
        if (lower.contains("refresh_token")
                || lower.contains("aliyundrive")
                || lower.contains("alipan")) drives.add("ali");
        return drives;
    }

    /** 私有文件里 token 还要求带值长度（避免插件源码/配置里的 token 名误命中）。 */
    private static final Pattern FILE_TOKEN =
            Pattern.compile(
                    "(?i)(kps|__puus|__pus|bduss|stoken|uc_st|_uc_s|refresh_token|aliyundrive|alipan)"
                            + "\\s*[=:]\\s*[A-Za-z0-9._%+\\-]{6,}");

    private static final class Hit {
        final String drive;
        final String where;

        Hit(String drive, String where) {
            this.drive = drive;
            this.where = where;
        }
    }

    // ---------------- prefs 全量扫描（除 movie 系列） ----------------

    static List<Hit> prefsHits(MainActivity a) {
        List<Hit> hits = new ArrayList<>();
        prefsFileCountForLog = 0;
        try {
            File dir = new File(a.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files == null) return hits;
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml")) continue;
                name = name.substring(0, name.length() - 4);
                if ("movie".equals(name) || "movie-private".equals(name)) continue;
                prefsFileCountForLog++;
                try {
                    android.content.SharedPreferences prefs =
                            a.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        Object value = entry.getValue();
                        if (!(value instanceof String)) continue;
                        String raw = (String) value;
                        if (!AccountPanel.credentialValue(raw)) continue;
                        for (String drive : drivesForToken(raw))
                            hits.add(new Hit(drive, "prefs:" + name + " · " + entry.getKey()));
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "prefs scan failed", error);
        }
        return hits;
    }

    // ---------------- 私有目录小文件扫描 ----------------

    private static int fileCountForLog;
    private static int prefsFileCountForLog;

    static List<Hit> fileHits(MainActivity a) {
        List<Hit> hits = new ArrayList<>();
        fileCountForLog = 0;
        try {
            List<File> roots = new ArrayList<>();
            roots.add(a.getFilesDir());
            try {
                File noBackup = a.getNoBackupFilesDir();
                if (noBackup != null) roots.add(noBackup);
            } catch (Throwable ignored) {
            }
            try {
                File db = a.getDatabasePath("noop");
                if (db != null && db.getParentFile() != null) roots.add(db.getParentFile());
            } catch (Throwable ignored) {
            }
            roots.add(a.getCacheDir());
            int[] budget = {MAX_FILES};
            for (File root : roots) {
                if (root == null || !root.isDirectory()) continue;
                walk(root, root, hits, budget);
                if (budget[0] <= 0 || hits.size() >= MAX_FILE_HITS) break;
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "private file scan failed", error);
        }
        return hits;
    }

    private static void walk(File dir, File base, List<Hit> hits, int[] budget) {
        if (dir == null || budget[0] <= 0 || hits.size() >= MAX_FILE_HITS) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (budget[0] <= 0 || hits.size() >= MAX_FILE_HITS) return;
            if (file.isDirectory()) {
                if (SKIP_DIRS.contains(file.getName().toLowerCase(Locale.ROOT))) continue;
                walk(file, base, hits, budget);
                continue;
            }
            if (file.length() <= 0 || file.length() > MAX_FILE_BYTES) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            boolean binary = false;
            for (String ext : BINARY_EXTENSIONS) if (lower.endsWith(ext)) binary = true;
            if (binary) continue;
            budget[0]--;
            fileCountForLog++;
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                String text = out.toString("UTF-8");
                Matcher matcher = FILE_TOKEN.matcher(text);
                while (matcher.find()) {
                    for (String drive : drivesForToken(matcher.group()))
                        hits.add(new Hit(drive, "files:" + relative(base, file)));
                    if (hits.size() >= MAX_FILE_HITS) break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 相对私有目录的展示路径（files/…、cache/…、databases/…）。 */
    private static String relative(File base, File file) {
        try {
            String path = file.getAbsolutePath();
            String basePath = base == null ? "" : base.getAbsolutePath();
            String label = base == null || base.getName().isEmpty() ? "files" : base.getName();
            if (!basePath.isEmpty() && path.startsWith(basePath + "/"))
                return label + "/" + path.substring(basePath.length() + 1);
            int index = path.lastIndexOf("/");
            return label + "/" + (index > 0 ? path.substring(index + 1) : file.getName());
        } catch (Throwable ignored) {
            return "files/…";
        }
    }

    // ---------------- Cookie ----------------

    private static String[] loginCookieNames(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"__puus"};
            case "uc":
                return new String[] {"__puus", "_uc_s"};
            case "baidu":
                return new String[] {"bduss"};
            case "ali":
                return new String[] {"refresh_token", "aliyun"};
            default:
                return new String[0];
        }
    }

    private static String[] domains(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"pan.quark.cn", "drive.quark.cn"};
            case "uc":
                return new String[] {"drive.uc.cn", "fast.uc.cn"};
            case "baidu":
                return new String[] {"pan.baidu.com", "yun.baidu.com"};
            case "ali":
                return new String[] {"alipan.com", "aliyundrive.com"};
            default:
                return new String[0];
        }
    }

    /** 只接受来源存储文件，避免把主设置/配置 JSON 当登录数据（本地凭据扫描用）。 */
    static boolean sourcePrefsFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if ("movie".equals(n) || "movie-private".equals(n)) return false;
        return n.startsWith("js-source")
                || n.contains("source")
                || n.contains("csp")
                || n.contains("plugin")
                || n.contains("auth")
                || n.contains("login");
    }

    static Map<String, String> cookieSnapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES)
            for (String domain : domains(drive.key)) seen.add(domain);
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String domain : seen) {
                try {
                    String cookie = manager.getCookie("https://" + domain);
                    if (cookie != null && !cookie.trim().isEmpty()) out.put(domain, cookie);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "WebView cookie probe failed", error);
        }
        return out;
    }

    private static String cookieLoginEvidence(Map<String, String> cookies, String drive) {
        String[] names = loginCookieNames(drive);
        if (names.length == 0 || cookies == null) return "";
        for (String domain : domains(drive)) {
            String cookie = cookies.get(domain);
            if (cookie == null) continue;
            for (String part : cookie.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                if (value.isEmpty()) continue;
                for (String loginName : names)
                    if (name.equals(loginName)) return "WebView 登录 Cookie · " + domain;
            }
        }
        return "";
    }

    private static String cookieSiteEvidence(Map<String, String> cookies, String drive) {
        if (cookies == null) return "";
        for (String domain : domains(drive)) if (cookies.get(domain) != null) return domain;
        return "";
    }

    // ---------------- 诊断 v2 ----------------

    private static final int DIAG_MAX_FILES = 800;
    private static final long DIAG_MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long DIAG_MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final String[] COOKIE_BLOB_KEYWORDS = {
        "quark", "kps", "puus", "bduss", "baidu", "uc_st", "_uc_s", "alipan", "aliyundrive",
        "refresh_token"
    };

    private static final class FileInfo {
        final File file;
        final String rel;
        final long size;
        final long mtime;

        FileInfo(File file, String rel, long size, long mtime) {
            this.file = file;
            this.rel = rel;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private static final class DirRoot {
        final String label;
        final File root;

        DirRoot(String label, File root) {
            this.label = label;
            this.root = root;
        }
    }

    private static List<DirRoot> diagnosticRoots(MainActivity a) {
        List<DirRoot> roots = new ArrayList<>();
        try {
            File dataDir = new File(a.getApplicationInfo().dataDir);
            addRoot(roots, "files", a.getFilesDir());
            try {
                addRoot(roots, "no_backup", a.getNoBackupFilesDir());
            } catch (Throwable ignored) {
            }
            addRoot(roots, "databases", new File(dataDir, "databases"));
            addRoot(roots, "cache", a.getCacheDir());
            try {
                addRoot(roots, "external_files", a.getExternalFilesDir(null));
            } catch (Throwable ignored) {
            }
            addRoot(roots, "app_webview", new File(dataDir, "app_webview"));
        } catch (Throwable error) {
            AppLog.w("PanLogin", "diagnose roots failed", error);
        }
        return roots;
    }

    private static void addRoot(List<DirRoot> roots, String label, File root) {
        if (root != null && root.isDirectory()) roots.add(new DirRoot(label, root));
    }

    private static void collect(File dir, String prefix, List<FileInfo> out, int[] budget) {
        if (dir == null || budget[0] <= 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (budget[0] <= 0) return;
            if (file.isDirectory()) {
                if (SKIP_DIRS.contains(file.getName().toLowerCase(Locale.ROOT))) continue;
                collect(file, prefix + file.getName() + "/", out, budget);
                continue;
            }
            budget[0]--;
            out.add(new FileInfo(file, prefix + file.getName(), file.length(), file.lastModified()));
        }
    }

    private static boolean binaryExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : BINARY_EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static boolean looksBinary(byte[] data, int length) {
        int limit = Math.min(length, 1024);
        for (int i = 0; i < limit; i++) if (data[i] == 0) return true;
        return false;
    }

    private static String timeText(long millis) {
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                    .format(new java.util.Date(millis));
        } catch (Throwable ignored) {
            return String.valueOf(millis);
        }
    }

    private static List<String> drivesForCookieBlob(String lower) {
        List<String> out = new ArrayList<>();
        if (lower.contains("quark") || lower.contains("kps") || lower.contains("puus"))
            out.add("quark");
        if (lower.contains("uc_st")
                || lower.contains("_uc_s")
                || lower.contains("uc.cn")
                || lower.contains("puus")) out.add("uc");
        if (lower.contains("baidu") || lower.contains("bduss")) out.add("baidu");
        if (lower.contains("alipan")
                || lower.contains("aliyundrive")
                || lower.contains("refresh_token")) out.add("ali");
        return out;
    }

    /** C：诊断 v2——目录清单/最近文件/Cookie 长度/扩大字节搜索，全部写日志（不含值）。 */
    static void diagnose(final MainActivity a, final Runnable done) {
        // 先在 UI 线程初始化 WebView，确保 CookieManager 提供者就绪后再读 Cookie
        a.ui.post(
                () -> {
                    try {
                        android.webkit.WebView web = new android.webkit.WebView(a);
                        web.destroy();
                    } catch (Throwable error) {
                        AppLog.w("PanLogin", "diagnose webview init failed", error);
                    }
                    Map<String, String> cookies = new LinkedHashMap<>();
                    Set<String> seenDomains = new LinkedHashSet<>();
                    for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES)
                        for (String domain : domains(drive.key)) seenDomains.add(domain);
                    try {
                        CookieManager manager = CookieManager.getInstance();
                        for (String domain : seenDomains) {
                            String cookie = manager.getCookie("https://" + domain);
                            int length = cookie == null ? 0 : cookie.length();
                            AppLog.i("PanLogin", "diagnose cookie dom=" + domain + " len=" + length);
                            if (cookie != null && !cookie.isEmpty()) cookies.put(domain, cookie);
                        }
                    } catch (Throwable error) {
                        AppLog.w("PanLogin", "diagnose cookie read failed", error);
                    }
                    a.worker.execute(() -> diagnoseScan(a, cookies, done));
                });
    }

    private static void diagnoseScan(MainActivity a, Map<String, String> cookies, Runnable done) {
        try {
            File dataDir = new File(a.getApplicationInfo().dataDir);
            // 1) prefs 文件名清单（不含 movie 系列）
            File[] prefFiles = new File(dataDir, "shared_prefs").listFiles();
            List<String> prefNames = new ArrayList<>();
            if (prefFiles != null)
                for (File file : prefFiles) {
                    String name = file.getName();
                    if (!name.endsWith(".xml")) continue;
                    name = name.substring(0, name.length() - 4);
                    if ("movie".equals(name) || "movie-private".equals(name)) continue;
                    prefNames.add(name);
                }
            AppLog.i("PanLogin", "diagnose prefs files=[" + joinAll(prefNames) + "] count=" + prefNames.size());

            // 2) 目录统计 + 最近文件
            List<DirRoot> roots = diagnosticRoots(a);
            List<FileInfo> all = new ArrayList<>();
            int[] budget = {20000};
            for (DirRoot root : roots) {
                int[] before = {budget[0]};
                List<FileInfo> dirFiles = new ArrayList<>();
                collect(root.root, root.label + "/", dirFiles, new int[] {Math.min(20000, before[0])});
                long bytes = 0;
                for (FileInfo info : dirFiles) bytes += Math.max(0, info.size);
                AppLog.i(
                        "PanLogin",
                        "diagnose dir "
                                + root.label
                                + " files="
                                + dirFiles.size()
                                + " bytes="
                                + bytes);
                all.addAll(dirFiles);
                budget[0] -= dirFiles.size();
                if (budget[0] <= 0) break;
            }
            List<FileInfo> recent = new ArrayList<>(all);
            java.util.Collections.sort(
                    recent,
                    (left, right) -> Long.compare(right.mtime, left.mtime));
            int shown = 0;
            for (FileInfo info : recent) {
                if (shown >= 15) break;
                shown++;
                AppLog.i(
                        "PanLogin",
                        "diagnose recent #"
                                + shown
                                + " "
                                + info.rel
                                + " size="
                                + info.size
                                + " mtime="
                                + timeText(info.mtime));
            }

            // 3) databases 与 app_webview/Cookies
            try {
                File dbDir = new File(dataDir, "databases");
                File[] dbs = dbDir.listFiles();
                if (dbs != null)
                    for (File db : dbs) {
                        String lower = db.getName().toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.contains("cookie"))
                            AppLog.i(
                                    "PanLogin",
                                    "diagnose db file="
                                            + db.getName()
                                            + " size="
                                            + db.length());
                    }
            } catch (Throwable ignored) {
            }
            findCookieDb(new File(dataDir, "app_webview"), 0);

            // 4) 扩大字节搜索：最多 800 文件 / 单文件 ≤8MB / 总读 ≤64MB，mtime 新→旧
            int scanned = 0, hits = 0;
            long readBytes = 0;
            for (FileInfo info : recent) {
                if (scanned >= DIAG_MAX_FILES || readBytes >= DIAG_MAX_TOTAL_BYTES) break;
                if (info.size <= 0 || info.size > DIAG_MAX_FILE_BYTES) continue;
                if (binaryExtension(info.file.getName())) continue;
                scanned++;
                byte[] data;
                try {
                    try (java.io.InputStream in = new java.io.FileInputStream(info.file)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        data = out.toByteArray();
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                readBytes += data.length;
                if (looksBinary(data, data.length)) continue;
                String text;
                try {
                    text = new String(data, "UTF-8");
                } catch (Throwable ignored) {
                    continue;
                }
                String lower = text.toLowerCase(Locale.ROOT);
                List<String> drives = drivesForToken(lower);
                boolean cookieBlob =
                        lower.indexOf('=') >= 0 && lower.indexOf(';') >= 0;
                if (cookieBlob) {
                    for (String drive : drivesForCookieBlob(lower))
                        if (!drives.contains(drive)) drives.add(drive);
                }
                if (drives.isEmpty()) continue;
                for (String drive : drives) {
                    hits++;
                    AppLog.i(
                            "PanLogin",
                            "diagnose hit "
                                    + drive
                                    + " files:"
                                    + info.rel
                                    + " size="
                                    + info.size
                                    + " mtime="
                                    + timeText(info.mtime));
                    if (hits >= 40) break;
                }
                if (hits >= 40) break;
            }
            AppLog.i(
                    "PanLogin",
                    "diagnose bytescan files="
                            + scanned
                            + " read="
                            + readBytes
                            + " hits="
                            + hits);

            int manual = 0;
            for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES)
                if (manualLogin(a, drive.key)) manual++;
            AppLog.i(
                    "PanLogin",
                    "diagnose done cookieDomains=["
                            + join(cookies.keySet())
                            + "] manual="
                            + manual);
        } catch (Throwable error) {
            AppLog.w("PanLogin", "diagnose failed", error);
        } finally {
            if (done != null) a.ui.post(done);
        }
    }

    /** app_webview 下的 Cookies DB（深度 ≤4），只记录路径与大小。 */
    private static void findCookieDb(File dir, int depth) {
        if (dir == null || depth > 4) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                findCookieDb(file, depth + 1);
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("cookies"))
                AppLog.i(
                        "PanLogin",
                        "diagnose appwebview cookie-db="
                                + file.getAbsolutePath().replace("/data/user/0/", "")
                                + " size="
                                + file.length());
        }
    }
}
