package com.whalefall.movie;

import android.os.SystemClock;
import android.webkit.CookieManager;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网盘登录状态检测（三态，只报可确认的事实，绝不猜测）。
 *
 * <p>判据（只有「登录凭据型」证据才算已登录，避免 false positive）：
 * <ol>
 *   <li>本地凭据：{@link AccountPanel#scanCredentials} 扫到、且满足
 *       {@link AccountPanel#credentialValue} 值形态与 {@link AccountPanel#credentialConfigKey} 键名；
 *   <li>WebView 登录 Cookie：出现该网盘已知<b>登录 Cookie 名</b>（夸克 {@code __puus}、
 *       百度 {@code BDUSS}、UC {@code __puus}/{@code _uc_s}、阿里 {@code refresh_token}/{@code ALIYUN}）；
 *       仅普通站点 Cookie（如匿名 {@code __pus}）不算登录；
 *   <li>源存储登录凭据：只扫 {@code js-source*} / 含 source|csp|plugin|auth|login 的 prefs，
 *       值必须像凭据，且含凭据型 token（{@code kps=}/{@code bduss}/{@code uc_st}/{@code stoken=}/
 *       {@code refresh_token}/{@code aliyundrive}/{@code alipan}）或 {@code cookie} 键且值含网盘域名；
 *       <b>裸域名/纯键名不算</b>。
 * </ol>
 * 检测结果有 60s 静态缓存（两个面板共享；显式刷新时 force 失效），扫描与 Cookie 探测在 worker 线程。
 */
final class PanLoginState {
    static final long CACHE_TTL_MS = 60_000L;
    static final int STATE_LOCAL = 0; // 本机凭据（可确认）
    static final int STATE_SOURCE = 1; // 源可确认：登录 Cookie / 源存储登录凭据
    static final int STATE_UNKNOWN = 2; // 无法读取（由源管理）

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

    /** 一次检测的完整结果：网盘三态 + 本地凭据/账号名（面板共用）。 */
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
    private static String cachedSite = "";
    private static boolean loading;
    private static String loadingSite = "";
    private static final List<Callback> waiters = new ArrayList<>();

    /** 显式刷新时调用：丢弃缓存，下一次检测强制重新扫描。 */
    static void invalidate() {
        synchronized (CACHE_LOCK) {
            cached = null;
            cachedAt = 0L;
            cachedSite = "";
        }
    }

    /**
     * 异步检测：命中 60s 缓存直接回 UI；否则 worker 扫描（同站点并发请求合并等待）。
     * {@code force=true} 跳过缓存（「刷新登录状态 / 刷新源状态」）。
     */
    static void detectAsync(
            final MainActivity a,
            final Cms.Site site,
            final boolean force,
            final Callback callback) {
        final String siteKey = site == null || site.key == null ? "" : site.key;
        synchronized (CACHE_LOCK) {
            if (!force
                    && cached != null
                    && siteKey.equals(cachedSite)
                    && SystemClock.uptimeMillis() - cachedAt < CACHE_TTL_MS) {
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

    /** 单次扫描（worker 线程）：本地凭据 + 一次 prefs 快照 + 每域名一次 Cookie。 */
    static Snapshot detectAll(MainActivity a, Cms.Site site) {
        List<AccountPanel.Credential> credentials =
                site == null ? new ArrayList<>() : AccountPanel.scanCredentials(a, site);
        String accountName = AccountPanel.findAccountName(credentials);
        Map<String, String> prefs = prefsSnapshot(a);
        Map<String, String> cookies = cookieSnapshot();
        Map<String, PanLoginState> states = new LinkedHashMap<>();
        int local = 0, source = 0, unknown = 0;
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
            PanLoginState state = detect(credentials, drive.key, accountName, prefs, cookies);
            states.put(drive.key, state);
            if (state.state == STATE_LOCAL) local++;
            else if (state.state == STATE_SOURCE) source++;
            else unknown++;
            AppLog.i(
                    "PanLogin",
                    "drive="
                            + drive.key
                            + " state="
                            + (state.state == STATE_LOCAL
                                    ? "local"
                                    : state.state == STATE_SOURCE ? "source" : "unknown")
                            + " evidence="
                            + state.evidence);
        }
        AppLog.i(
                "PanLogin",
                "detect site="
                        + (site == null ? "-" : site.key)
                        + " local="
                        + local
                        + " source="
                        + source
                        + " unknown="
                        + unknown
                        + " (unknown=no strong login evidence)");
        return new Snapshot(states, credentials, accountName);
    }

    static PanLoginState detect(
            List<AccountPanel.Credential> credentials,
            String drive,
            String accountName,
            Map<String, String> prefs,
            Map<String, String> cookies) {
        if (AccountPanel.driveHasLocalCredential(credentials, drive)) {
            return new PanLoginState(
                    drive, STATE_LOCAL, "已登录（本地凭据）", accountName, "local credentials");
        }
        String loginCookie = cookieLoginEvidence(cookies, drive);
        if (!loginCookie.isEmpty()) {
            return new PanLoginState(drive, STATE_SOURCE, loginCookie, "", loginCookie);
        }
        List<String> hits = storageEvidence(prefs, drive);
        if (!hits.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_SOURCE,
                    "源存储有登录凭据（账号名不可读）",
                    "",
                    hits.get(0));
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
                "unknown: no login cookie / credential token");
    }

    // ---------------- 凭据特征 ----------------

    /** 各网盘 WebView Cookie 里可确认「已登录」的 Cookie 名（小写比较）。 */
    private static String[] loginCookieNames(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"__puus"}; // __pus 仅匿名/辅助，不单独算登录
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

    /** 各网盘域名（仅用于「cookie 键 + 值含域名」的辅助判定，裸域名不单独算）。 */
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

    /** 各网盘「凭据型」token；裸域名不在此列，避免把配置里的域名误判成登录。 */
    private static String[] credentialTokens(String drive) {
        switch (drive) {
            case "quark":
                return new String[] {"kps="};
            case "uc":
                return new String[] {"uc_st"};
            case "baidu":
                return new String[] {"bduss", "stoken="};
            case "ali":
                return new String[] {"refresh_token", "aliyundrive", "alipan"};
            default:
                return new String[0];
        }
    }

    /** 只接受来源存储文件，避免把主设置/配置 JSON 当登录数据。 */
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

    /** 一次读取所有来源 prefs 文件（key = file\u0000key），只保留 String 值。 */
    static Map<String, String> prefsSnapshot(MainActivity a) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            File dir = null;
            if (a.getApplicationInfo() != null && a.getApplicationInfo().dataDir != null)
                dir = new File(a.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return out;
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml")) continue;
                name = name.substring(0, name.length() - 4);
                if (!sourcePrefsFile(name)) continue;
                try {
                    android.content.SharedPreferences prefs =
                            a.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof String)
                            out.put(name + "\u0000" + entry.getKey(), (String) value);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "source prefs snapshot failed", error);
        }
        return out;
    }

    /** 每域名只查一次 WebView Cookie。 */
    static Map<String, String> cookieSnapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
            for (String domain : domains(drive.key)) seen.add(domain);
        }
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
        for (String domain : domains(drive))
            if (cookies.get(domain) != null) return domain;
        return "";
    }

    /** 只扫来源 prefs 快照；值必须像凭据，token/域名必须出现在值里。 */
    static List<String> storageEvidence(Map<String, String> prefs, String drive) {
        List<String> hits = new ArrayList<>();
        String[] tokens = credentialTokens(drive);
        String[] sites = domains(drive);
        if (tokens.length == 0 || prefs == null) return hits;
        for (Map.Entry<String, String> entry : prefs.entrySet()) {
            String[] parts = entry.getKey().split("\u0000", 2);
            String name = parts.length > 0 ? parts[0] : "";
            String key = parts.length > 1 ? parts[1] : "";
            String raw = entry.getValue();
            if (!AccountPanel.credentialValue(raw)) continue;
            String text = raw.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (text.contains(token)) {
                    hits.add(name + " · " + key + " · token:" + token);
                    break;
                }
            }
            if (key.toLowerCase(Locale.ROOT).contains("cookie")) {
                for (String site : sites) {
                    if (text.contains(site)) {
                        hits.add(name + " · " + key + " · cookie@" + site);
                        break;
                    }
                }
            }
        }
        return hits;
    }
}
