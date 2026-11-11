// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import java.net.URI;

/** Public update metadata policy, also tested without Android. */
final class UpdatePolicy {
    static final long MAX_APK = 128L * 1024 * 1024;
    // 二次开发者的自有更新源；留空即关闭应用内更新检查。
    // FEED 为 JSON 目录前缀（如 https://example.com/updates/），MIRROR 为安装包镜像前缀。
    // 更新检查以 GitHub 最新发布版为准：清单与安装包都放在 Release 资产里，
    // releases/latest/download/<文件名> 永远指向最新一版，不需要每次改代码。
    static final String FEED = "https://github.com/962599627/whalefall/releases/latest/download/";
    /** 下载/清单镜像前缀（按可用性排序，最后是直连 GitHub）。 */
    static final String JSDELIVR =
            "https://cdn.jsdelivr.net/gh/962599627/whalefall@whalefall-rebrand/updates/";
    static final String GH_RAW =
            "https://raw.githubusercontent.com/962599627/whalefall/whalefall-rebrand/updates/";
    static final String GH_RAW_PROXY =
            "https://ghproxy.net/https://raw.githubusercontent.com/962599627/whalefall/whalefall-rebrand/updates/";
    static final String[] MIRRORS = {
        "https://gh-proxy.org/https://github.com/962599627/whalefall/releases/latest/download/",
        "https://gh-proxy.com/https://github.com/962599627/whalefall/releases/latest/download/",
        "https://ghproxy.net/https://github.com/962599627/whalefall/releases/latest/download/",
        JSDELIVR,
        GH_RAW_PROXY,
        GH_RAW,
        FEED
    };
    static final String MIRROR = MIRRORS[0];

    /** 1.9.51：清单检查顺序：镜像 → GitHub raw → jsDelivr → ghproxy → Release。 */
    static final String[] FEED_SOURCES = {
        "https://gh-proxy.org/https://github.com/962599627/whalefall/releases/latest/download/",
        "https://gh-proxy.com/https://github.com/962599627/whalefall/releases/latest/download/",
        FEED,
        GH_RAW,
        JSDELIVR,
        GH_RAW_PROXY
    };

    /** 允许的更新源主机；留空表示接受任意 HTTPS 主机（自建源最省事）。 */
    static final String[] ALLOWED_HOSTS = {};

    static boolean configured() {
        return !FEED.isEmpty() || !MIRROR.isEmpty();
    }

    static String[] feeds(String channel) {
        if (!"stable".equals(channel) && !"preview".equals(channel))
            throw new IllegalArgumentException("channel");
        return new String[] {MIRROR + "updates/" + channel + ".json", FEED + channel + ".json"};
    }

    static String platform(boolean mobile) {
        return mobile ? "android-mobile" : "android-tv";
    }

    static boolean mobileEdition(String edition) {
        if ("mobile".equals(edition)) return true;
        if ("tv".equals(edition)) return false;
        throw new IllegalArgumentException("Unknown application edition");
    }

    static String[] feeds(String channel, boolean mobile) {
        if (!"stable".equals(channel) && !"preview".equals(channel))
            throw new IllegalArgumentException("channel");
        // Release 资产名不能带 "/"，清单直接命名为 android-mobile.json 等
        String name = platform(mobile) + ("preview".equals(channel) ? "-preview" : "");
        String[] out = new String[FEED_SOURCES.length];
        for (int i = 0; i < FEED_SOURCES.length; i++) out[i] = FEED_SOURCES[i] + name + ".json";
        return out;
    }

    static boolean editionUrl(String url, String version, boolean mobile) {
        if (!apkUrl(url)) return false;
        String file = url.substring(url.lastIndexOf('/') + 1);
        String platform = platform(mobile);
        String named = "WhaleFall-" + version + "-" + platform + ".apk";
        String tightened = "WhaleFall-" + version + "-android-" + platform + ".apk";
        String legacy = "WhaleFall-" + version + (mobile ? "-mobile.apk" : "-preview.apk");
        return file.equals(named) || file.equals(tightened) || file.equals(legacy);
    }

    /** 同一 release 的可选包体种类：手机显示 mobile/arm64/r8，电视只显示 tv。 */
    static boolean variantKindAllowed(String kind, boolean mobile) {
        if (kind == null) return false;
        if (mobile) return "mobile".equals(kind) || "arm64".equals(kind) || "r8".equals(kind);
        return "tv".equals(kind);
    }

    static String variantLabel(String kind, boolean mobile) {
        if ("arm64".equals(kind)) return "arm64 精简版";
        if ("r8".equals(kind)) return "R8 精简版";
        if ("tv".equals(kind)) return "TV 版";
        return mobile ? "标准版（双 ABI）" : "TV 版";
    }

    /** 校验变体 APK 文件名（固定 edition 前缀，避免错版 feed 绕过 edition / 跨 ABI 混淆）。 */
    static boolean variantUrl(String url, String version, String kind, boolean mobile) {
        if (!apkUrl(url) || !variantKindAllowed(kind, mobile)) return false;
        String file = url.substring(url.lastIndexOf('/') + 1);
        if ("mobile".equals(kind)) return editionUrl(url, version, true);
        if ("tv".equals(kind)) return editionUrl(url, version, false);
        // 手机精简包必须是 WhaleFall-<version>-android-mobile-... 开头
        String mobilePrefix = "WhaleFall-" + version + "-android-mobile-";
        if (!file.startsWith(mobilePrefix) || !file.endsWith(".apk")) return false;
        if ("arm64".equals(kind)) return file.contains("-arm64") && !file.contains("-r8");
        if ("r8".equals(kind)) return file.contains("-r8") && file.contains("-arm64");
        return false;
    }

    static void validateVariant(
            int code,
            String version,
            int minSdk,
            long size,
            String sha,
            String url,
            String channel,
            boolean mobile,
            String kind) {
        if (code <= 0
                || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")
                || version.length() > 48
                || minSdk < 23
                || minSdk > 100
                || size < 0
                || size > MAX_APK
                || sha == null
                || !sha.matches("[a-f0-9]{64}")
                || !variantUrl(url, version, kind, mobile)
                || (!"stable".equals(channel) && !"preview".equals(channel)))
            throw new IllegalArgumentException("Invalid update variant");
    }

    static String[] variantDownloads(
            String version, boolean mobile, String primary, String github, String kind) {
        if (!variantUrl(primary, version, kind, mobile))
            throw new IllegalArgumentException("APK variant");
        String file = primary.substring(primary.lastIndexOf('/') + 1);
        java.util.List<String> urls = new java.util.ArrayList<>();
        urls.add(primary);
        for (String mirror : MIRRORS) {
            String candidate = mirror + file;
            if (!urls.contains(candidate)) urls.add(candidate);
        }
        if (github != null && !github.isEmpty()) {
            if (!variantUrl(github, version, kind, mobile) || !github.endsWith("/" + file))
                throw new IllegalArgumentException("Invalid release download URL");
            if (!urls.contains(github)) urls.add(github);
        }
        return urls.toArray(new String[0]);
    }

    /** Keep the release's actual tag; never switch editions when retrying a download. */
    static String[] downloads(String version, boolean mobile, String primary, String github) {
        if (!editionUrl(primary, version, mobile))
            throw new IllegalArgumentException("APK edition");
        String file = primary.substring(primary.lastIndexOf('/') + 1);
        java.util.List<String> urls = new java.util.ArrayList<>();
        urls.add(primary);
        for (String mirror : MIRRORS) {
            String candidate = mirror + file;
            if (!urls.contains(candidate)) urls.add(candidate);
        }
        if (github != null && !github.isEmpty()) {
            if (!editionUrl(github, version, mobile) || !github.endsWith("/" + file))
                throw new IllegalArgumentException("Invalid release download URL");
            if (!urls.contains(github)) urls.add(github);
        }
        return urls.toArray(new String[0]);
    }

    static String[] downloads(String version) {
        return downloads(version, false);
    }

    static String[] downloads(String version, boolean mobile) {
        if (version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")
                || version.length() > 48) throw new IllegalArgumentException("version");
        String file = "WhaleFall-" + version + "-android-" + platform(mobile) + ".apk";
        if (MIRROR.isEmpty()) throw new IllegalArgumentException("No update mirror configured");
        return new String[] {MIRROR + file};
    }

    static boolean apkUrl(String value) {
        try {
            URI u = new URI(value);
            if (!"https".equals(u.getScheme())
                    || u.getUserInfo() != null
                    || u.getPort() != -1
                    || u.getQuery() != null
                    || u.getFragment() != null
                    || u.getHost() == null
                    || !u.getPath().endsWith(".apk")
                    || u.getPath().contains("..")
                    || !u.getRawPath().equals(u.getPath())) return false;
            if (ALLOWED_HOSTS.length == 0) return true;
            for (String host : ALLOWED_HOSTS) if (host.equals(u.getHost())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean transferUrl(String value) {
        try {
            URI u = new URI(value);
            if (!"https".equals(u.getScheme())
                    || u.getUserInfo() != null
                    || u.getPort() != -1
                    || u.getHost() == null) return false;
            if (ALLOWED_HOSTS.length == 0) return true;
            for (String host : ALLOWED_HOSTS) if (host.equals(u.getHost())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static void validate(
            int code,
            String version,
            int minSdk,
            long size,
            String sha,
            String url,
            boolean preview,
            String channel) {
        validate(code, version, minSdk, size, sha, url, preview, channel, false);
    }

    static void validate(
            int code,
            String version,
            int minSdk,
            long size,
            String sha,
            String url,
            boolean preview,
            String channel,
            boolean mobile) {
        if (code <= 0
                || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")
                || version.length() > 48
                || minSdk < 23
                || minSdk > 100
                || size < 0
                || size > MAX_APK
                || sha == null
                || !sha.matches("[a-f0-9]{64}")
                || !apkUrl(url)
                || !editionUrl(url, version, mobile)
                || (!"stable".equals(channel) && !"preview".equals(channel))
                || ("stable".equals(channel) && preview))
            throw new IllegalArgumentException("Invalid update metadata");
    }

    static boolean newer(int offered, int installed) {
        return offered > installed;
    }

    static long resumeOffset(long requested, int status, String range, long size)
            throws java.io.IOException {
        if (requested < 0 || requested >= size) throw new java.io.IOException("invalid offset");
        if (status == 200) return 0;
        if (status != 206 || requested == 0 || range == null)
            throw new java.io.IOException("invalid range response");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);
        try {
            if (!m.matches()
                    || Long.parseLong(m.group(1)) != requested
                    || Long.parseLong(m.group(2)) != size - 1
                    || Long.parseLong(m.group(3)) != size)
                throw new java.io.IOException("range mismatch");
        } catch (NumberFormatException e) {
            throw new java.io.IOException("range number", e);
        }
        return requested;
    }
}
