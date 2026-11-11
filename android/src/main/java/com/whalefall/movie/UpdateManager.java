// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Separate network queue; installs only an official same-signed newer APK after explicit user action. */
final class UpdateManager {
    static final class Release extends UpdateRelease {
        Release(JSONObject o, String channel, boolean mobile) throws Exception {
            super(o, channel, mobile);
        }
    }

    private static UpdateManager instance;

    static synchronized UpdateManager get(Context c) {
        if (instance == null) instance = new UpdateManager(c.getApplicationContext());
        return instance;
    }

    final Context context;
    final SharedPreferences prefs;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    volatile Release available;
    /** available.variants 里当前选中的包体下标；无 variants 或自动模式时为 0。 */
    volatile int variantIndex;
    volatile String message = "尚未检查更新";
    volatile boolean busy;
    volatile int percent = -1;
    private volatile boolean cancel;
    private volatile HttpURLConnection activeConnection;
    private Runnable listener;
    private long noticedCode;

    boolean cancelled() {
        return cancel;
    }

    void resetCancellation() {
        cancel = false;
    }

    interface Connector {
        HttpURLConnection open(String address, long offset) throws Exception;
    }

    final Connector connector;

    private UpdateManager(Context c) {
        this(c, c.getSharedPreferences("updates", 0), UpdateManager::open);
    }

    UpdateManager(Context c, SharedPreferences preferences, Connector connection) {
        context = c;
        prefs = preferences;
        connector = connection;
        int code = installedCode(c), old = prefs.getInt("installed_code", code);
        if (code > old) {
            prefs.edit()
                    .putString("result", "已升级至 " + installedVersion(context) + "。" + bridgeNote())
                    .remove("download_sha")
                    .remove("pending_code")
                    .apply();
            apk().delete();
        } else if (prefs.contains("pending_code"))
            prefs.edit()
                    .putString("result", "上次安装未完成，当前版本仍可使用。可重新选择安装。")
                    .remove("pending_code")
                    .apply();
        prefs.edit().putInt("installed_code", code).apply();
        try {
            available =
                    new Release(
                            new JSONObject(prefs.getString("metadata", "")),
                            channel(),
                            mobileEdition());
        } catch (Exception ignored) {
        }
        if (available != null
                && (available.code <= code || available.minSdk > Build.VERSION.SDK_INT))
            available = null;
        // 恢复上次选择的包体，保证「安装已下载版本」在重启后仍指向同一文件
        if (available != null && available.variants.length > 0) {
            String kind = prefs.getString("download_kind", "");
            for (int i = 0; i < available.variants.length; i++)
                if (available.variants[i].kind.equals(kind)) {
                    variantIndex = i;
                    break;
                }
        }
        message = prefs.getString("result", "尚未检查更新");
    }

    static int installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean mobileEdition() {
        return mobileEdition(context);
    }

    private static boolean mobileEdition(Context context) {
        try {
            android.content.pm.ApplicationInfo info =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    context.getPackageName(), PackageManager.GET_META_DATA);
            return UpdatePolicy.mobileEdition(
                    info.metaData == null
                            ? null
                            : info.metaData.getString("com.whalefall.movie.EDITION"));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("无法识别安装版本", e);
        }
    }

    String channel() {
        return prefs.getString("channel", "stable");
    }

    String mode() {
        return prefs.getString("mode", "notify");
    }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    String bridgeNote() {
        return "配置、收藏与观看记录保留在本机。";
    }

    synchronized void listen(Runnable callback) {
        listener = callback;
    }

    private void changed() {
        main.post(
                () -> {
                    Runnable callback;
                    synchronized (this) {
                        callback = listener;
                    }
                    if (callback != null) callback.run();
                });
    }

    File folder() {
        File f = new File(context.getCacheDir(), "updates");
        f.mkdirs();
        return f;
    }

    File apk() {
        return new File(folder(), "release.apk");
    }

    /** 当前选中的包体下标（无 variants 时恒为 0，等同旧单包行为）。 */
    int selectedVariant() {
        Release r = available;
        if (r == null || r.variants.length == 0) return 0;
        int index = variantIndex;
        if (index < 0) return 0;
        return Math.min(index, r.variants.length - 1);
    }

    /** 手动选择包体；下载/检查进行中不允许切换。 */
    synchronized boolean selectVariant(int index) {
        if (busy) return false;
        Release r = available;
        if (r == null || index < 0 || index >= r.variants.length) return false;
        variantIndex = index;
        changed();
        return true;
    }

    boolean ready() {
        Release r = available;
        if (r == null || r.code <= installedCode(context)) return false;
        int index = selectedVariant();
        long size = r.targetSize(index);
        String sha = r.targetSha(index);
        return apk().isFile()
                && apk().length() == size
                && sha != null
                && sha.equals(prefs.getString("download_sha", ""));
    }

    boolean consumeNotice() {
        Release r = available;
        if (r == null
                || r.code <= installedCode(context)
                || noticedCode == r.code
                || "off".equals(mode())) return false;
        noticedCode = r.code;
        return true;
    }

    void setMode(String value) {
        if (!Arrays.asList("notify", "download", "off").contains(value)) return;
        prefs.edit().putString("mode", value).apply();
        if ("off".equals(value)) cancel = true;
        changed();
    }

    synchronized boolean setChannel(String value) {
        if (busy || !Arrays.asList("stable", "preview").contains(value)) return false;
        prefs.edit()
                .putString("channel", value)
                .remove("metadata")
                .remove("download_sha")
                .remove("download_kind")
                .remove("last_attempt")
                .apply();
        available = null;
        variantIndex = 0;
        apk().delete();
        new File(folder(), "release.part").delete();
        prefs.edit().remove("partial_sha").apply();
        noticedCode = 0;
        message = "渠道已切换，请检查更新";
        changed();
        return true;
    }

    void automatic() {
        if (!"off".equals(mode())
                && System.currentTimeMillis() - prefs.getLong("last_attempt", 0)
                        >= 6 * 60 * 60 * 1000L) check(false);
    }

    synchronized void check(boolean manual) {
        if (busy) return;
        busy = true;
        cancel = false;
        message = "正在检查更新…";
        percent = -1;
        changed();
        final String channel = channel();
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        worker.execute(
                () -> {
                    try {
                        if (!UpdatePolicy.configured()) {
                            message = "未配置更新源（自用构建），当前版本可正常使用";
                            return;
                        }
                        JSONObject data = readRelease(channel);
                        Release r = new Release(data, channel, mobileEdition());
                        if (cancel) throw new InterruptedIOException();
                        if (!UpdatePolicy.newer(r.code, installedCode(context))) {
                            clearOffer();
                            message = "当前渠道暂无更高版本（本机 " + installedVersion(context) + "）";
                        } else if (r.minSdk > Build.VERSION.SDK_INT) {
                            clearOffer();
                            message = "发现新版，但其系统版本要求不适合本机，已停止下载";
                        } else {
                            available = r;
                            variantIndex = 0;
                            message = "发现新版 " + r.version;
                            prefs.edit().putString("metadata", data.toString()).apply();
                            if ("download".equals(mode()) && !ready()) downloadFile(r);
                        }
                    } catch (InterruptedIOException e) {
                        message = cancel ? "已取消，当前版本不受影响" : "网络连接超时；若已有下载进度，再次下载可继续";
                    } catch (Exception e) {
                        String detail = e.getMessage();
                        message =
                                "检查或下载失败："
                                        + e.getClass().getSimpleName()
                                        + (detail == null || detail.trim().isEmpty()
                                                ? ""
                                                : "（" + detail + "）")
                                        + "。请检查网络后重试；现有版本仍可使用";
                        AppLog.w("UpdateManager", "Check failed", e);
                    } finally {
                        busy = false;
                        percent = -1;
                        changed();
                    }
                });
    }

    synchronized void download() {
        if (busy || available == null) return;
        Release r = available;
        if (r.minSdk > Build.VERSION.SDK_INT || r.code <= installedCode(context)) return;
        busy = true;
        cancel = false;
        worker.execute(
                () -> {
                    try {
                        downloadFile(r);
                    } catch (InterruptedIOException e) {
                        message = cancel ? "下载已取消" : "下载超时，已保留进度，可再次下载继续";
                    } catch (Exception e) {
                        message = "下载或校验失败，未安装。请重试或从官方发布页下载";
                        AppLog.w("UpdateManager", "Download failed", e);
                    } finally {
                        busy = false;
                        percent = -1;
                        changed();
                    }
                });
    }

    private void clearOffer() {
        available = null;
        variantIndex = 0;
        prefs.edit().remove("metadata").remove("download_sha").remove("download_kind").apply();
        apk().delete();
    }

    void cancel() {
        cancel = true;
        HttpURLConnection active = activeConnection;
        if (active != null) workerCancel(active);
        message = "正在取消…";
        changed();
    }

    private void workerCancel(HttpURLConnection active) {
        new Thread(active::disconnect, "WhaleFall-cancel-update").start();
    }

    private void downloadFile(Release r) throws Exception {
        final int selected = selectedVariant();
        final long size = r.targetSize(selected);
        final boolean unknownSize = size <= 0;
        final String sha = r.targetSha(selected);
        final String[] sources = r.targetDownloads(selected);
        if (!unknownSize && folder().getUsableSpace() < size + 16 * 1024 * 1024L)
            throw new IOException("space");
        File part = new File(folder(), "release.part");
        if (unknownSize
                || !sha.equals(prefs.getString("partial_sha", ""))
                || part.length() > size) {
            part.delete();
            prefs.edit().putString("partial_sha", sha).commit();
        }
        percent = unknownSize ? -1 : (int) (part.length() * 100 / size);
        boolean verified = false;
        try {
            Exception failure = null;
            boolean valid = false;
            for (int source = 0; source < sources.length && !valid; source++) {
                for (int attempt = 0;
                        attempt < 2 && (unknownSize || part.length() < size);
                        attempt++) {
                    if (cancel) throw new InterruptedIOException();
                    message = "正在下载 " + r.version + "（" + (source + 1) + "/" + sources.length + "）";
                    changed();
                    try {
                        transfer(part, sources[source], size);
                        failure = null;
                        AppLog.i(
                                "Update",
                                "try=" + sources[source] + " result=downloaded bytes=" + part.length());
                    } catch (IOException e) {
                        failure = e;
                        AppLog.i(
                                "Update",
                                "try=" + sources[source] + " result=fail error="
                                        + e.getClass().getSimpleName());
                        if (cancel) throw new InterruptedIOException();
                    }
                }
                if (unknownSize ? part.length() > 0 : part.length() == size) {
                    try {
                        verifyApk(context, part, r, selected);
                        valid = true;
                    } catch (Exception e) {
                        failure = e;
                        if (!part.delete()) throw new IOException("discard invalid APK", e);
                    }
                }
            }
            if (!valid) {
                if (failure != null) throw failure;
                throw new IOException("short download");
            }
            if (cancel) throw new InterruptedIOException();
            if (apk().exists() && !apk().delete()) throw new IOException("replace");
            if (!part.renameTo(apk())) throw new IOException("rename");
            prefs.edit()
                    .putString("download_sha", sha)
                    .putString("download_kind", r.targetKind(selected))
                    .remove("partial_sha")
                    .apply();
            message = "新版已下载并校验，选择“安装已下载版本”继续";
            verified = true;
        } finally {
            if (cancel) {
                part.delete();
                prefs.edit().remove("partial_sha").apply();
            }
            if (!verified && !cancel && part.exists()) message = "下载中断，已保留进度，可再次下载继续";
        }
    }

    private void transfer(File part, String address, long size) throws Exception {
        boolean unknownSize = size <= 0;
        long offset = part.length();
        HttpURLConnection conn = connector.open(address, unknownSize ? 0 : offset);
        activeConnection = conn;
        try {
            long start;
            if (unknownSize) {
                start = 0;
            } else {
                start =
                        UpdatePolicy.resumeOffset(
                                offset,
                                conn.getResponseCode(),
                                conn.getHeaderField("Content-Range"),
                                size);
            }
            long declared = conn.getContentLength();
            if (!unknownSize && declared > 0 && declared != size - start)
                throw new IOException("size");
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(part, start > 0)) {
                byte[] bytes = new byte[32768];
                long total = start;
                int n, last = -1;
                while ((n = in.read(bytes)) != -1) {
                    if (cancel) throw new InterruptedIOException();
                    total += n;
                    if (!unknownSize && total > size) throw new IOException("limit");
                    out.write(bytes, 0, n);
                    if (!unknownSize) {
                        int now = (int) (total * 100 / size);
                        if (now != last) {
                            last = now;
                            percent = now;
                            changed();
                        }
                    }
                }
                if (!unknownSize && total != size) throw new IOException("short download");
            }
        } finally {
            activeConnection = null;
            conn.disconnect();
        }
    }

    static void verifyApk(Context c, File file, Release r) throws Exception {
        verifyApk(c, file, r, 0);
    }

    static void verifyApk(Context c, File file, Release r, int variantIndex) throws Exception {
        long expectedSize = r.targetSize(variantIndex);
        if ((expectedSize > 0 && file.length() != expectedSize)
                || !sha(file).equals(r.targetSha(variantIndex)))
            throw new IOException("digest");
        PackageManager pm = c.getPackageManager();
        int flags =
                Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES
                        : PackageManager.GET_SIGNATURES;
        PackageInfo next =
                pm.getPackageArchiveInfo(
                        file.getAbsolutePath(), flags | PackageManager.GET_META_DATA);
        PackageInfo current = pm.getPackageInfo(c.getPackageName(), flags);
        if (next == null
                || !c.getPackageName().equals(next.packageName)
                || next.versionCode != r.code
                || next.versionCode <= current.versionCode
                || !r.version.equals(next.versionName)
                || next.applicationInfo == null
                || (Build.VERSION.SDK_INT >= 24
                        && (next.applicationInfo.minSdkVersion != r.minSdk
                                || next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)))
            throw new IOException("package identity or compatibility");
        boolean nextMobile =
                UpdatePolicy.mobileEdition(
                        next.applicationInfo.metaData == null
                                ? null
                                : next.applicationInfo.metaData.getString(
                                        "com.whalefall.movie.EDITION"));
        if (nextMobile != mobileEdition(c)) throw new IOException("安装包类型不匹配");
        Set<String> a = packageSignatures(current), b = packageSignatures(next);
        if (a.isEmpty() || !a.equals(b)) throw new IOException("signer");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> packageSignatures(PackageInfo info) {
        if (info == null) return Collections.emptySet();
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            android.content.pm.Signature[] certs =
                    info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
            Set<String> modern = signatures(certs);
            if (!modern.isEmpty()) return modern;
        }
        return signatures(info.signatures);
    }

    private static Set<String> signatures(android.content.pm.Signature[] signatures) {
        Set<String> result = new HashSet<>();
        if (signatures != null)
            for (android.content.pm.Signature s : signatures) result.add(s.toCharsString());
        return result;
    }

    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static HttpURLConnection open(String address) throws Exception {
        return open(address, 0, 12000, 45000);
    }

    private static HttpURLConnection open(String address, long offset) throws Exception {
        return open(address, offset, 12000, 45000);
    }

    private static HttpURLConnection open(
            String address, long offset, int connectMs, int readMs) throws Exception {
        for (int hops = 0; hops < 6; hops++) {
            if (!UpdatePolicy.transferUrl(address)) throw new IOException("untrusted host");
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            c.setRequestProperty("User-Agent", "WhaleFall-Updater");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setUseCaches(false);
            int status;
            try {
                status = c.getResponseCode();
            } catch (Exception e) {
                c.disconnect();
                throw e;
            }
            if (status == 200 || (offset > 0 && status == 206)) return c;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("redirect");
                address = new URI(address).resolve(next).toString();
            } else {
                c.disconnect();
                throw new IOException("HTTP " + status);
            }
        }
        throw new IOException("redirect limit");
    }

    private JSONObject readRelease(String channel) throws Exception {
        Exception failure = null;
        for (String address : UpdatePolicy.feeds(channel, mobileEdition())) {
            if (cancel) throw new InterruptedIOException();
            try {
                JSONObject data = new JSONObject(new String(readFeed(address), "UTF-8"));
                new Release(data, channel, mobileEdition());
                AppLog.i("Update", "try=" + address + " result=ok");
                return data;
            } catch (Exception e) {
                failure = e;
                AppLog.i(
                        "Update",
                        "try=" + address + " result=fail error=" + e.getClass().getSimpleName());
                if (cancel) throw new InterruptedIOException();
            }
        }
        throw failure == null ? new IOException("no update source") : failure;
    }

    private byte[] readFeed(String address) throws Exception {
        // 1.9.51：每个源 8s 超时，快速切换到下一个镜像/CDN
        HttpURLConnection c = open(address, 0, 8000, 8000);
        activeConnection = c;
        try (InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int n;
            while ((n = in.read(bytes)) != -1) {
                if (cancel) throw new InterruptedIOException();
                if (out.size() + n > 32768) throw new IOException("feed limit");
                out.write(bytes, 0, n);
            }
            return out.toByteArray();
        } finally {
            activeConnection = null;
            c.disconnect();
        }
    }
}
