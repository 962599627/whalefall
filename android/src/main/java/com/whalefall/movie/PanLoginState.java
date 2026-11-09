package com.whalefall.movie;

import android.content.SharedPreferences;
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
 *   <li>本地凭据：{@link AccountPanel#scanCredentials} 扫到的来源写入 prefs 的 Cookie/Token；
 *   <li>WebView 登录 Cookie：{@link CookieManager} 里出现该网盘已知的<b>登录 Cookie 名</b>
 *       （夸克 {@code __puus}、百度 {@code BDUSS}、UC {@code __puus}/{@code _uc_s}、
 *       阿里 {@code refresh_token}/{@code ALIYUN}）；仅有普通站点 Cookie（如匿名 {@code __pus}）
 *       不算登录，降级为无法确认；
 *   <li>源存储登录凭据：只扫 {@code js-source*} / 含 source|csp|plugin|auth|login 的 prefs，
 *       且只认凭据型 token（{@code kps=}/{@code bduss}/{@code uc_st}/{@code stoken=}/{@code refresh_token}），
 *       或 {@code cookie} 键且值里同时含该网盘域名；<b>裸域名单独命中不算</b>。
 * </ol>
 * 都没有时返回 {@link #STATE_UNKNOWN}（「由源管理 · 无法读取」），不会显示成「未登录」。
 */
final class PanLoginState {
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
                return new String[] {"refresh_token"};
            default:
                return new String[0];
        }
    }

    private static final class CookieProbe {
        /** 命中已知登录 Cookie 名时非空，例如 "WebView 登录 Cookie · pan.quark.cn"。 */
        String login = "";
        /** 站点有 Cookie 但不是登录 cookie 时非空，仅用于说明「无法确认」。 */
        String site = "";
    }

    private static CookieProbe cookieEvidence(String drive) {
        CookieProbe probe = new CookieProbe();
        String[] names = loginCookieNames(drive);
        String[] sites = domains(drive);
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String domain : sites) {
                String cookie = manager.getCookie("https://" + domain);
                if (cookie == null || cookie.trim().isEmpty()) continue;
                if (cookie.trim().length() >= 8) probe.site = domain;
                if (names.length == 0) continue;
                for (String part : cookie.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    String name = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String value = part.substring(eq + 1).trim();
                    if (value.isEmpty()) continue;
                    for (String loginName : names) {
                        if (name.equals(loginName)) {
                            probe.login = "WebView 登录 Cookie · " + domain;
                            return probe;
                        }
                    }
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "WebView cookie probe failed", error);
        }
        return probe;
    }

    /** 只扫来源自己的存储文件，且只认凭据型 token；返回证据位置（不含值）。 */
    static List<String> storageEvidence(MainActivity a, String drive) {
        List<String> hits = new ArrayList<>();
        String[] tokens = credentialTokens(drive);
        String[] sites = domains(drive);
        if (tokens.length == 0) return hits;
        try {
            File dir = null;
            if (a.getApplicationInfo() != null && a.getApplicationInfo().dataDir != null)
                dir = new File(a.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return hits;
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml")) continue;
                name = name.substring(0, name.length() - 4);
                if (!sourcePrefsFile(name)) continue;
                try {
                    SharedPreferences prefs =
                            a.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        Object value = entry.getValue();
                        if (!(value instanceof String)) continue;
                        String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                        String text = ((String) value).toLowerCase(Locale.ROOT);
                        for (String token : tokens) {
                            if (text.contains(token)) {
                                hits.add(name + " · " + entry.getKey() + " · token:" + token);
                                break;
                            }
                        }
                        if (key.contains("cookie")) {
                            for (String site : sites) {
                                if (text.contains(site)) {
                                    hits.add(name + " · " + entry.getKey() + " · cookie@" + site);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("PanLogin", "source storage probe failed", error);
        }
        return hits;
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

    static PanLoginState detect(
            MainActivity a,
            List<AccountPanel.Credential> credentials,
            String drive,
            String accountName) {
        if (AccountPanel.driveHasLocalCredential(credentials, drive)) {
            return new PanLoginState(
                    drive,
                    STATE_LOCAL,
                    "已登录（本地凭据）",
                    accountName,
                    "local credentials");
        }
        CookieProbe cookie = cookieEvidence(drive);
        if (!cookie.login.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_SOURCE,
                    cookie.login,
                    "",
                    cookie.login);
        }
        List<String> hits = storageEvidence(a, drive);
        if (!hits.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_SOURCE,
                    "源存储有登录凭据（账号名不可读）",
                    "",
                    hits.get(0));
        }
        if (!cookie.site.isEmpty()) {
            return new PanLoginState(
                    drive,
                    STATE_UNKNOWN,
                    "有站点 Cookie，但无法确认登录（由源管理）",
                    "",
                    "unknown: site cookie only @" + cookie.site);
        }
        return new PanLoginState(
                drive,
                STATE_UNKNOWN,
                "由源管理 · 无法读取",
                "",
                "unknown: no login cookie / credential token");
    }

    static Map<String, PanLoginState> detectAll(MainActivity a, Cms.Site site) {
        Map<String, PanLoginState> out = new LinkedHashMap<>();
        List<AccountPanel.Credential> credentials =
                site == null ? new ArrayList<>() : AccountPanel.scanCredentials(a, site);
        String accountName = AccountPanel.findAccountName(credentials);
        int local = 0, source = 0, unknown = 0;
        for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
            PanLoginState state = detect(a, credentials, drive.key, accountName);
            out.put(drive.key, state);
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
                            + state.evidence
                            + (state.state == STATE_UNKNOWN ? " (no strong login evidence)" : ""));
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
                        + unknown);
        return out;
    }
}
