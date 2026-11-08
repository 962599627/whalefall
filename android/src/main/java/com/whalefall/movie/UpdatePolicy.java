// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import java.net.URI;

/** Public update metadata policy, also tested without Android. */
final class UpdatePolicy {
    static final long MAX_APK = 128L * 1024 * 1024;
    // 二次开发者的自有更新源；留空即关闭应用内更新检查。
    // FEED 为 JSON 目录前缀（如 https://example.com/updates/），MIRROR 为安装包镜像前缀。
    static final String FEED = "https://cdn.jsdelivr.net/gh/962599627/whalefall@main/updates/";
    static final String MIRROR = "https://raw.githubusercontent.com/962599627/whalefall/main/";

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
        String[] legacy = feeds(channel);
        String name = platform(mobile) + ("preview".equals(channel) ? "-preview" : "");
        return new String[] {
            MIRROR + "updates/" + name + ".json", FEED + name + ".json", legacy[0], legacy[1]
        };
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

    /** Keep the release's actual tag; never switch editions when retrying a download. */
    static String[] downloads(String version, boolean mobile, String primary, String github) {
        if (!editionUrl(primary, version, mobile))
            throw new IllegalArgumentException("APK edition");
        String file = primary.substring(primary.lastIndexOf('/') + 1);
        String mirror = MIRROR.isEmpty() ? primary : MIRROR + file;
        if (github != null && !github.isEmpty()) {
            if (!editionUrl(github, version, mobile) || !github.endsWith("/" + file))
                throw new IllegalArgumentException("Invalid release download URL");
            if (!github.equals(primary) && !mirror.equals(github))
                return new String[] {primary, mirror, github};
        }
        return primary.equals(mirror) ? new String[] {primary} : new String[] {primary, mirror};
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
                || size < 1
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
