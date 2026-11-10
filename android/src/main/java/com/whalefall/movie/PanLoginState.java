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
        CookieDb cookieDb = cookieDbSnapshot(a);
        List<Hit> dbHits = databaseHits(a);
        Map<String, PanLoginState> states = new LinkedHashMap<>();
        int local = 0, source = 0, unknown = 0, manual = 0;
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
            PanLoginState state =
                    detect(
                            a,
                            credentials,
                            drive.key,
                            accountName,
                            prefHits,
                            fileHits,
                            cookies,
                            cookieDb,
                            dbHits);
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
                        + " sampled="
                        + filesSampleForLog
                        + " fileHits="
                        + fileHits.size()
                        + " dbHits="
                        + dbHits.size()
                        + " cookieDbRows="
                        + cookieDb.rows
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
            Map<String, String> cookies,
            CookieDb cookieDb,
            List<Hit> dbHits) {
        if (AccountPanel.driveHasLocalCredential(credentials, drive)) {
            return new PanLoginState(
                    drive, STATE_LOCAL, "已登录（本地凭据）", accountName, "local credentials");
        }
        String loginCookie = cookieLoginEvidence(cookies, drive);
        if (!loginCookie.isEmpty()) {
            return new PanLoginState(drive, STATE_SOURCE, loginCookie, "", loginCookie);
        }
        // WebView Cookies SQLite 库（CookieManager 读空时兜底）
        List<String> dbCookieEvidence = evidenceFor(cookieDb.loginHits, drive);
        if (!dbCookieEvidence.isEmpty()) {
            String where = dbCookieEvidence.get(0);
            return new PanLoginState(
                    drive,
                    STATE_SOURCE,
                    "源已登录（WebView Cookie 库 " + where.replace("cookie-db ", "") + "）",
                    "",
                    where);
        }
        List<String> prefs = evidenceFor(prefHits, drive);
        List<String> files = evidenceFor(fileHits, drive);
        List<String> sqlite = evidenceFor(dbHits, drive);
        List<String> all = new ArrayList<>();
        all.addAll(prefs);
        all.addAll(files);
        all.addAll(sqlite);
        if (!all.isEmpty()) {
            String joined = joinList(all);
            String first = all.get(0);
            String detail;
            if (first.startsWith("prefs:") && first.contains("base64")) {
                String where = first.substring("prefs:".length()).replace(" · base64", "");
                detail = "源已登录（源存储登录凭据 · " + where + "）";
            } else if (all.size() > 1) {
                detail = "源存储/私有文件/数据库命中多条凭据";
            } else {
                detail = "源存储/私有文件/数据库有登录凭据（账号名不可读）";
            }
            return new PanLoginState(drive, STATE_SOURCE, detail, "", joined);
        }
        if (manualLogin(a, drive)) {
            return new PanLoginState(
                    drive, STATE_MANUAL, "已登录（用户确认，非自动检测）", "", "manual confirm");
        }
        String siteCookie = cookieSiteEvidence(cookies, drive);
        List<String> anyDb = evidenceFor(cookieDb.anyHits, drive);
        if (!anyDb.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_UNKNOWN,
                    "Cookie 库有该网盘 Cookie，但无已知登录 Cookie（由源管理）",
                    "",
                    "unknown: cookie-db non-login cookie " + anyDb.get(0));
        }
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
                        String whereKey = name + " · " + entry.getKey();
                        List<String> drives = drivesForToken(raw);
                        boolean decodedHit = false;
                        if (drives.isEmpty() && looksBase64(raw)) {
                            // Base64 存储（如 spUtils.quark_ck）：解码后按强 token/cookie 形态判定
                            String decoded = decodeBase64(raw);
                            if (decoded != null) {
                                drives = decodedDrives(decoded);
                                decodedHit = !drives.isEmpty();
                            }
                        }
                        if (drives.isEmpty()) continue;
                        // 直读值仍要求凭据形态；Base64 命中已由解码内容保证
                        if (!decodedHit && !AccountPanel.credentialValue(raw)) continue;
                        for (String drive : drives)
                            hits.add(
                                    new Hit(
                                            drive,
                                            "prefs:"
                                                    + whereKey
                                                    + (decodedHit ? " · base64" : "")));
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "prefs scan failed", error);
        }
        return hits;
    }

    // ---------------- Base64 支持（spUtils.quark_ck 等） ----------------

    static boolean looksBase64(String value) {
        String v = compactBase64(value);
        if (v == null) return false;
        if (v.length() < 16 || v.length() > 65536) return false;
        if (!v.matches("[A-Za-z0-9+/=_-]+")) return false;
        int mod = v.length() % 4;
        return mod == 0 || mod == 2 || mod == 3;
    }

    /** 去掉空白/换行后再判 Base64（SharedPreferences 里可能带换行）。 */
    static String compactBase64(String value) {
        if (value == null) return null;
        String v = value.replaceAll("\\s+", "");
        return v.isEmpty() ? null : v;
    }

    /** 解码诊断结果：printable 百分比 + kind(cookie/json/text/binary) + cookie 前 6 字符。 */
    static final class DecodeReport {
        final String decoded;
        final int printable;
        final String kind;
        final String head;

        DecodeReport(String decoded, int printable, String kind, String head) {
            this.decoded = decoded;
            this.printable = printable;
            this.kind = kind;
            this.head = head;
        }
    }

    /** 纯诊断：即使解码是二进制也返回报告（kind=binary），用于判断是否加密。 */
    static DecodeReport inspectBase64(String value) {
        String compact = compactBase64(value);
        if (compact == null) return null;
        if (compact.length() < 16 || compact.length() > 65536) return null;
        if (!compact.matches("[A-Za-z0-9+/=_-]+")) return null;
        int mod = compact.length() % 4;
        if (!(mod == 0 || mod == 2 || mod == 3)) return null;
        String normalized = compact.replace('-', '+').replace('_', '/');
        if (mod == 2) normalized += "==";
        else if (mod == 3) normalized += "=";
        byte[] bytes;
        try {
            bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT);
        } catch (Throwable ignored) {
            return null;
        }
        if (bytes == null || bytes.length == 0 || bytes.length > 64 * 1024) return null;
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 32 && c < 127) printable++;
        }
        int percent = text.isEmpty() ? 0 : printable * 100 / text.length();
        String kind;
        if (percent < 70) {
            kind = "binary";
        } else {
            String lower = text.toLowerCase(Locale.ROOT);
            String trim = text.trim();
            if ((lower.contains("=") && lower.contains(";")) || lower.contains("kps=")) {
                kind = "cookie";
            } else if (trim.startsWith("{") || trim.startsWith("[")) {
                kind = "json";
            } else {
                kind = "text";
            }
        }
        String head = null;
        if ("cookie".equals(kind) && text.length() >= 6) head = text.substring(0, 6);
        return new DecodeReport(text, percent, kind, head);
    }

    /** 标准 + URL-safe + 去 padding 兜底解码；非可打印文本返回 null（检测路径用）。 */
    static String decodeBase64(String value) {
        DecodeReport report = inspectBase64(value);
        if (report == null || report.printable < 70) return null;
        return report.decoded;
    }

    /** 解码后的内容必须是 cookie 形态或强 token，设备 ID/纯状态文本不算登录。 */
    static List<String> decodedDrives(String decoded) {
        List<String> drives = new ArrayList<>();
        if (decoded == null) return drives;
        String lower = decoded.toLowerCase(Locale.ROOT);
        boolean cookieShape =
                (lower.contains("=") && lower.contains(";"))
                        || lower.contains("kps=")
                        || lower.contains("__puus")
                        || lower.contains("__pus");
        if (!cookieShape) return drives;
        drives.addAll(drivesForToken(lower));
        if (lower.contains("quark") && !drives.contains("quark")) drives.add("quark");
        if ((lower.contains("drive.uc.cn") || lower.contains("uc_st")) && !drives.contains("uc"))
            drives.add("uc");
        if (lower.contains("baidu") && !drives.contains("baidu")) drives.add("baidu");
        if ((lower.contains("aliyundrive") || lower.contains("alipan"))
                && !drives.contains("ali")) drives.add("ali");
        return drives;
    }

    // ---------------- 私有目录原始字节扫描（不跳过含 NUL 文件，DB 优先） ----------------

    private static int fileCountForLog;
    private static int prefsFileCountForLog;
    private static int filesSampleForLog;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final long SAMPLE_BYTES = 2L * 1024 * 1024;

    static List<Hit> fileHits(MainActivity a) {
        List<Hit> hits = new ArrayList<>();
        fileCountForLog = 0;
        filesSampleForLog = 0;
        try {
            List<DirRoot> roots = new ArrayList<>();
            File dataDir = new File(a.getApplicationInfo().dataDir);
            addRoot(roots, "databases", new File(dataDir, "databases")); // DB 优先
            addRoot(roots, "files", a.getFilesDir());
            try {
                addRoot(roots, "no_backup", a.getNoBackupFilesDir());
            } catch (Throwable ignored) {
            }
            addRoot(roots, "cache", a.getCacheDir());
            try {
                addRoot(roots, "external_files", a.getExternalFilesDir(null));
            } catch (Throwable ignored) {
            }
            addRoot(roots, "app_webview", new File(dataDir, "app_webview"));
            List<FileInfo> all = new ArrayList<>();
            for (DirRoot root : roots) collect(root.root, root.label + "/", all, new int[] {4000});
            // 扫描优先级：databases（小）→ files/（含 70MB 插件大文件，必须采到）→ 其余按 mtime
            List<FileInfo> ordered = new ArrayList<>();
            List<FileInfo> databases = new ArrayList<>();
            List<FileInfo> filesRoot = new ArrayList<>();
            List<FileInfo> others = new ArrayList<>();
            for (FileInfo info : all) {
                if (info.rel.startsWith("databases/")) databases.add(info);
                else if (info.rel.startsWith("files/")) filesRoot.add(info);
                else others.add(info);
            }
            java.util.Collections.sort(
                    databases, (left, right) -> Long.compare(right.mtime, left.mtime));
            java.util.Collections.sort(
                    filesRoot, (left, right) -> Long.compare(right.mtime, left.mtime));
            java.util.Collections.sort(
                    others, (left, right) -> Long.compare(right.mtime, left.mtime));
            ordered.addAll(databases);
            ordered.addAll(filesRoot);
            ordered.addAll(others);
            long readTotal = 0;
            int scanned = 0;
            for (FileInfo info : ordered) {
                if (scanned >= MAX_FILES || readTotal >= MAX_TOTAL_BYTES) break;
                if (info.size <= 0 || info.size > 512L * 1024 * 1024) continue;
                boolean bigFile = info.size > 8L * 1024 * 1024;
                // 只对 files/ 下的大文件做前后采样；其它目录的大文件仍跳过
                if (bigFile && !info.rel.startsWith("files/")) continue;
                if (binaryExtension(info.file.getName())) continue;
                if (lowValue(info.rel)) continue;
                byte[] data;
                boolean sampled = info.size > SAMPLE_BYTES;
                try {
                    data = readSample(info.file, info.size, SAMPLE_BYTES);
                } catch (Throwable ignored) {
                    continue;
                }
                if (data.length == 0) continue;
                scanned++;
                fileCountForLog++;
                if (sampled) filesSampleForLog++;
                readTotal += data.length;
                List<String> drives = rawTokenDrives(data);
                if (drives.isEmpty()) continue;
                for (String drive : drives) hits.add(new Hit(drive, "files:" + info.rel));
                if (hits.size() >= MAX_FILE_HITS) break;
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "private file scan failed", error);
        }
        return hits;
    }

    /** 大文件只读前/后各 2MB，避免 70MB 资源全读。 */
    private static byte[] readSample(File file, long size, long sample) throws java.io.IOException {
        if (size <= sample * 2) {
            byte[] out = new byte[(int) size];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < out.length) {
                    int n = in.read(out, read, out.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                return out;
            }
        }
        byte[] out = new byte[(int) (sample * 2)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < sample) {
                int n = in.read(out, read, (int) sample - read);
                if (n <= 0) break;
                read += n;
            }
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(size - sample);
            int read = 0;
            while (read < sample) {
                int n = raf.read(out, (int) sample + read, (int) sample - read);
                if (n <= 0) break;
                read += n;
            }
        }
        return out;
    }

    /** 原始字节里找强 token（大小写不敏感）；含 NUL 的 SQLite/DB 同样可命中。 */
    static List<String> rawTokenDrives(byte[] data) {
        List<String> drives = new ArrayList<>();
        if (containsToken(data, "kps=")
                || containsToken(data, "__puus")
                || containsToken(data, "__pus")) drives.add("quark");
        if (containsToken(data, "uc_st") || containsToken(data, "_uc_s")) drives.add("uc");
        if (containsToken(data, "bduss") || containsToken(data, "stoken=")) drives.add("baidu");
        if (containsToken(data, "refresh_token") || containsToken(data, "aliyundrive"))
            drives.add("ali");
        return drives;
    }

    private static boolean containsToken(byte[] data, String token) {
        byte[] needle = token.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int limit = data.length - needle.length;
        for (int i = 0; i <= limit; i++) {
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                byte value = data[i + j];
                if (value >= 'A' && value <= 'Z') value += 32;
                if (value != needle[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) return true;
        }
        return false;
    }

    /** 诊断用低价值文件过滤：崩溃报告/日志/锁/.webview。 */
    private static boolean lowValue(String rel) {
        if (rel == null) return true;
        String lower = rel.toLowerCase(Locale.ROOT);
        return lower.contains("crash reports")
                || lower.contains("crashpad")
                || lower.endsWith(".log")
                || lower.endsWith(".lock")
                || lower.contains(".webview")
                || lower.contains("/tmp/");
    }

    // ---------------- WebView Cookies SQLite 库 ----------------

    static final class CookieDb {
        final List<Hit> loginHits = new ArrayList<>();
        final List<Hit> anyHits = new ArrayList<>();
        /** 每行 host/name/expires（仅诊断日志用，不含 cookie 值）。 */
        final List<String> rowDetails = new ArrayList<>();
        int rows;
        boolean opened;
    }

    /** CookieManager 读空时，直接只读查询 app_webview/Default/Cookies。 */
    static CookieDb cookieDbSnapshot(MainActivity a) {
        CookieDb out = new CookieDb();
        File copy = null;
        android.database.sqlite.SQLiteDatabase sqlite = null;
        android.database.Cursor cursor = null;
        try {
            File dataDir = new File(a.getApplicationInfo().dataDir);
            File source = new File(dataDir, "app_webview/Default/Cookies");
            if (!source.isFile()) {
                AppLog.i("PanLogin", "cookie-db absent");
                return out;
            }
            copy = new File(a.getCacheDir(), "pan-cookie-probe.db");
            copyFile(source, copy);
            sqlite =
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            copy.getAbsolutePath(),
                            null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            cursor = sqlite.rawQuery("SELECT host_key,name FROM cookies", null);
            while (cursor.moveToNext()) {
                String host = cursor.getString(0);
                String name = cursor.getString(1);
                if (host == null || name == null) continue;
                host = host.toLowerCase(Locale.ROOT);
                name = name.toLowerCase(Locale.ROOT);
                out.rows++;
                out.rowDetails.add("#" + out.rows + " host=" + host + " name=" + name);
                for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
                    if (!hostMatchesDrive(host, drive.key)) continue;
                    Hit hit = new Hit(drive.key, "cookie-db host=" + host + " name=" + name);
                    if (isLoginCookieName(drive.key, name)) out.loginHits.add(hit);
                    else out.anyHits.add(hit);
                }
            }
            out.opened = true;
        } catch (Throwable error) {
            AppLog.w("PanLogin", "cookie-db read failed", error);
        } finally {
            if (cursor != null) try {
                cursor.close();
            } catch (Throwable ignored) {
            }
            if (sqlite != null) try {
                sqlite.close();
            } catch (Throwable ignored) {
            }
            if (copy != null) copy.delete();
        }
        return out;
    }

    private static void copyFile(File source, File target) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
                java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    static boolean hostMatchesDrive(String host, String drive) {
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        // 宽松匹配：历史数据里出现过 .quark.cn / quark.cn. / login.quark.cn 等变体
        switch (drive) {
            case "quark":
                return lower.contains("quark");
            case "uc":
                return lower.endsWith("uc.cn")
                        || lower.contains(".uc.")
                        || lower.contains("login.uc");
            case "baidu":
                return lower.contains("baidu");
            case "ali":
                return lower.contains("alipan") || lower.contains("aliyundrive");
            default:
                return false;
        }
    }

    static boolean isLoginCookieName(String drive, String name) {
        if (name == null) return false;
        for (String login : loginCookieNames(drive)) if (login.equals(name)) return true;
        return false;
    }

    // ---------------- databases/*.db 文本列扫描 ----------------

    static List<Hit> databaseHits(MainActivity a) {
        List<Hit> hits = new ArrayList<>();
        File copy = null;
        android.database.sqlite.SQLiteDatabase sqlite = null;
        try {
            File dir = new File(a.getApplicationInfo().dataDir, "databases");
            File[] dbs = dir.listFiles();
            if (dbs == null) return hits;
            int dbCount = 0;
            for (File db : dbs) {
                if (dbCount >= 8 || hits.size() >= MAX_FILE_HITS) break;
                String lower = db.getName().toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".db") || lower.endsWith(".sqlite"))) continue;
                if (db.length() <= 0 || db.length() > 2L * 1024 * 1024) continue;
                dbCount++;
                copy = new File(a.getCacheDir(), "pan-db-probe-" + dbCount + ".db");
                try {
                    copyFile(db, copy);
                } catch (Throwable ignored) {
                    continue;
                }
                android.database.Cursor tables = null;
                try {
                    sqlite =
                            android.database.sqlite.SQLiteDatabase.openDatabase(
                                    copy.getAbsolutePath(),
                                    null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    tables =
                            sqlite.rawQuery(
                                    "SELECT name FROM sqlite_master WHERE type='table'", null);
                    int tableCount = 0;
                    while (tables.moveToNext() && tableCount < 12 && hits.size() < MAX_FILE_HITS) {
                        String table = tables.getString(0);
                        if (table == null
                                || table.startsWith("sqlite_")
                                || table.startsWith("android_")) continue;
                        tableCount++;
                        android.database.Cursor rows = null;
                        try {
                            String escaped = table.replace("\"", "\"\"");
                            rows = sqlite.rawQuery("SELECT * FROM \"" + escaped + "\" LIMIT 20", null);
                            int columns = rows.getColumnCount();
                            while (rows.moveToNext() && hits.size() < MAX_FILE_HITS) {
                                for (int c = 0; c < columns; c++) {
                                    String value;
                                    try {
                                        value = rows.getString(c);
                                    } catch (Throwable ignored) {
                                        continue;
                                    }
                                    if (value == null || value.length() < 6) continue;
                                    String text = value.toLowerCase(Locale.ROOT);
                                    for (String drive : drivesForToken(text))
                                        hits.add(
                                                new Hit(
                                                        drive,
                                                        "db:"
                                                                + db.getName()
                                                                + "."
                                                                + table));
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            if (rows != null) try {
                                rows.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable error) {
                    AppLog.w("PanLogin", "db read failed: " + db.getName(), error);
                } finally {
                    if (tables != null) try {
                        tables.close();
                    } catch (Throwable ignored) {
                    }
                    if (sqlite != null) try {
                        sqlite.close();
                    } catch (Throwable ignored) {
                    }
                    sqlite = null;
                    copy.delete();
                    copy = null;
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "database scan failed", error);
        } finally {
            if (copy != null) copy.delete();
        }
        return hits;
    }

    // ---------------- Cookie（CookieManager） ----------------

    private static String[] loginCookieNames(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"__puus", "__pus", "kps"};
            case "uc":
                return new String[] {"__puus", "_uc_s", "uc_st", "__pus"};
            case "baidu":
                return new String[] {"bduss", "stoken"};
            case "ali":
                return new String[] {"refresh_token", "aliyun", "__pus"};
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
            // 1b) 每个 prefs 的键名 + 值长度/base64 特征（不打印值内容）
            try {
                for (String prefName : prefNames) {
                    android.content.SharedPreferences prefs =
                            a.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE);
                    Map<String, ?> values = prefs.getAll();
                    List<String> keys = new ArrayList<>(values.keySet());
                    List<String> shownKeys = new ArrayList<>();
                    for (int i = 0; i < keys.size() && i < 40; i++) shownKeys.add(keys.get(i));
                    AppLog.i(
                            "PanLogin",
                            "diagnose pref "
                                    + prefName
                                    + " keys=["
                                    + joinAll(shownKeys)
                                    + "] count="
                                    + keys.size());
                    int meta = 0;
                    for (Map.Entry<String, ?> entry : values.entrySet()) {
                        if (meta >= 12) break;
                        Object value = entry.getValue();
                        String text = value instanceof String ? (String) value : "";
                        boolean base64 =
                                text.length() >= 16
                                        && text.matches("[A-Za-z0-9+/=_-]+")
                                        && text.length() >= 24;
                        if (!base64 && text.length() < 24) continue;
                        AppLog.i(
                                "PanLogin",
                                "diagnose prefval "
                                        + prefName
                                        + " key="
                                        + entry.getKey()
                                        + " len="
                                        + text.length()
                                        + " b64="
                                        + (base64 ? "yes" : "no"));
                        meta++;
                    }
                    // Base64 解码诊断（每次都打；binary 也打，用于判断是否加密）
                    int decodeShown = 0;
                    for (Map.Entry<String, ?> entry : values.entrySet()) {
                        if (decodeShown >= 30) break;
                        Object value = entry.getValue();
                        if (!(value instanceof String)) continue;
                        DecodeReport report = inspectBase64((String) value);
                        if (report == null) continue;
                        decodeShown++;
                        List<String> drives =
                                "binary".equals(report.kind)
                                        ? new ArrayList<String>()
                                        : decodedDrives(report.decoded);
                        StringBuilder line =
                                new StringBuilder("diagnose decode ")
                                        .append(prefName)
                                        .append('.')
                                        .append(entry.getKey())
                                        .append(" b64=yes lenDecoded=")
                                        .append(report.decoded == null ? 0 : report.decoded.length())
                                        .append(" printable=")
                                        .append(report.printable)
                                        .append("% kind=")
                                        .append(report.kind);
                        if (report.head != null) line.append(" head=").append(report.head);
                        line.append(" drive=")
                                .append(joinAll(drives))
                                .append(" matched=")
                                .append(drives.isEmpty() ? "no" : "yes");
                        AppLog.i("PanLogin", line.toString());
                    }
                }
            } catch (Throwable ignored) {
            }
            // 预置/加密存储：值像 base64/长串但判不出网盘的记 opaque，不硬解
            try {
                int opaque = 0;
                for (String prefName : prefNames) {
                    if (opaque >= 8) break;
                    android.content.SharedPreferences prefs =
                            a.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE);
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        Object value = entry.getValue();
                        if (!(value instanceof String)) continue;
                        String text = ((String) value).trim();
                        if (text.length() < 24) continue;
                        if (!text.matches("[A-Za-z0-9+/=_-]+")) continue;
                        if (!drivesForToken(text.toLowerCase(Locale.ROOT)).isEmpty()) continue;
                        AppLog.i(
                                "PanLogin",
                                "diagnose prefs opaque "
                                        + prefName
                                        + " key="
                                        + entry.getKey()
                                        + " len="
                                        + text.length());
                        if (++opaque >= 8) break;
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2) 目录统计 + 每目录最近 5 个文件（跳过崩溃日志等噪音）
            List<DirRoot> roots = diagnosticRoots(a);
            List<FileInfo> all = new ArrayList<>();
            int[] budget = {20000};
            for (DirRoot root : roots) {
                List<FileInfo> dirFiles = new ArrayList<>();
                collect(root.root, root.label + "/", dirFiles, new int[] {Math.min(20000, budget[0])});
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
                List<FileInfo> dirRecent = new ArrayList<>();
                for (FileInfo info : dirFiles) if (!lowValue(info.rel)) dirRecent.add(info);
                java.util.Collections.sort(
                        dirRecent, (left, right) -> Long.compare(right.mtime, left.mtime));
                int dirShown = 0;
                for (FileInfo info : dirRecent) {
                    if (dirShown >= 5) break;
                    dirShown++;
                    AppLog.i(
                            "PanLogin",
                            "diagnose recent "
                                    + root.label
                                    + "#"
                                    + dirShown
                                    + " "
                                    + info.rel
                                    + " size="
                                    + info.size
                                    + " mtime="
                                    + timeText(info.mtime));
                }
                all.addAll(dirFiles);
                budget[0] -= dirFiles.size();
                if (budget[0] <= 0) break;
            }
            // 2b) files/ 下文件名单（大文件名字本身可能提示功能，如 csp.db/plugin.js/config.json）
            int filesShown = 0;
            for (FileInfo info : all) {
                if (!info.rel.startsWith("files/")) continue;
                if (filesShown >= 40) break;
                filesShown++;
                AppLog.i(
                        "PanLogin",
                        "diagnose files "
                                + info.rel
                                + " size="
                                + info.size);
            }

            // 3) databases 表清单（只表名）与 app_webview/Cookies
            try {
                File dbDir = new File(dataDir, "databases");
                File[] dbs = dbDir.listFiles();
                if (dbs != null)
                    for (File db : dbs) {
                        String lower = db.getName().toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".db")
                                || lower.endsWith(".sqlite")
                                || lower.contains("cookie"))
                            AppLog.i(
                                    "PanLogin",
                                    "diagnose db file="
                                            + db.getName()
                                            + " size="
                                            + db.length());
                        if (!(lower.endsWith(".db") || lower.endsWith(".sqlite"))) continue;
                        if (db.length() <= 0 || db.length() > 2L * 1024 * 1024) continue;
                        File dbCopy = new File(a.getCacheDir(), "pan-db-tables.db");
                        android.database.sqlite.SQLiteDatabase sqlite = null;
                        android.database.Cursor tables = null;
                        try {
                            copyFile(db, dbCopy);
                            sqlite =
                                    android.database.sqlite.SQLiteDatabase.openDatabase(
                                            dbCopy.getAbsolutePath(),
                                            null,
                                            android.database.sqlite.SQLiteDatabase
                                                    .OPEN_READONLY);
                            tables =
                                    sqlite.rawQuery(
                                            "SELECT name FROM sqlite_master WHERE type='table'",
                                            null);
                            List<String> names = new ArrayList<>();
                            while (tables.moveToNext() && names.size() < 40) {
                                String table = tables.getString(0);
                                if (table == null || table.startsWith("sqlite_")) continue;
                                names.add(table);
                            }
                            AppLog.i(
                                    "PanLogin",
                                    "diagnose db "
                                            + db.getName()
                                            + " tables=["
                                            + joinAll(names)
                                            + "]");
                        } catch (Throwable ignored) {
                        } finally {
                            if (tables != null) try {
                                tables.close();
                            } catch (Throwable ignored) {
                            }
                            if (sqlite != null) try {
                                sqlite.close();
                            } catch (Throwable ignored) {
                            }
                            dbCopy.delete();
                        }
                    }
            } catch (Throwable ignored) {
            }
            findCookieDb(new File(dataDir, "app_webview"), 0);

            // 4) WebView Cookie 库 + databases 文本列 + 原始字节扫描（含 NUL 不跳过）
            CookieDb cookieDb = cookieDbSnapshot(a);
            AppLog.i(
                    "PanLogin",
                    "diagnose cookie-db rows="
                            + cookieDb.rows
                            + " loginHits="
                            + cookieDb.loginHits.size()
                            + " anyHits="
                            + cookieDb.anyHits.size()
                            + " opened="
                            + cookieDb.opened);
            int cookieDbShown = 0;
            for (String row : cookieDb.rowDetails) {
                if (cookieDbShown++ >= 40) break;
                AppLog.i("PanLogin", "diagnose cookie-db row " + row);
            }
            for (Hit hit : cookieDb.loginHits) {
                if (cookieDbShown++ >= 50) break;
                AppLog.i("PanLogin", "diagnose cookie-db hit " + hit.drive + " " + hit.where);
            }
            List<Hit> dbHits = databaseHits(a);
            int dbShown = 0;
            for (Hit hit : dbHits) {
                if (dbShown++ >= 15) break;
                AppLog.i("PanLogin", "diagnose dbhit " + hit.drive + " " + hit.where);
            }
            List<Hit> rawHits = fileHits(a);
            int rawShown = 0;
            for (Hit hit : rawHits) {
                if (rawShown++ >= 30) break;
                AppLog.i("PanLogin", "diagnose hit " + hit.drive + " " + hit.where);
            }
            AppLog.i(
                    "PanLogin",
                    "diagnose bytescan files="
                            + fileCountForLog
                            + " sampled="
                            + filesSampleForLog
                            + " hits="
                            + rawHits.size()
                            + " dbHits="
                            + dbHits.size());

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
