package com.whalefall.movie;

import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.8.31 文件管理器核心能力（先做数据/下载，UI 等设计规格）：
 * - 网盘文件直链下载到 App 下载目录（流式、进度、取消、失败重试入口）
 * - 本地目录扫描（文件夹/视频/其它，大小、修改时间）+ 删除/重命名/移动
 * - 本地文件播放 / 网盘视频优先直链播放
 * 日志统一 I/FileMgr。
 */
final class FileManagerCore {
    private FileManagerCore() {}

    interface Progress {
        void onProgress(int percent, long bytes, long total);
    }

    interface Done {
        void onDone(File file);
    }

    interface Failure {
        void onError(String message);
    }

    private static final ConcurrentHashMap<String, Task> TASKS = new ConcurrentHashMap<>();

    static File downloadDir(MainActivity a) {
        // 1.8.31：公共 Download/鲸落/，系统文件管理器可见
        File base =
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, "鲸落");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static boolean hasDownloadPermission(MainActivity a) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return a.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static void requestDownloadPermission(MainActivity a, int requestCode) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            a.requestPermissions(
                    new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    requestCode);
            AppLog.i("FileMgr", "request storage permission code=" + requestCode);
        } catch (Throwable ignored) {
        }
    }

    /** 发起下载；返回 taskId（可用于 cancel/pause/resume）。全部回调在 UI 线程。 */
    static String download(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookie,
            final QuarkUcApi.FileItem item,
            final Progress progress,
            final Done done,
            final Failure failure) {
        return download(a, p, cookie, item, progress, done, failure, null, false);
    }

    static String download(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookie,
            final QuarkUcApi.FileItem item,
            final Progress progress,
            final Done done,
            final Failure failure,
            final File targetDir) {
        return download(a, p, cookie, item, progress, done, failure, targetDir, false);
    }

    /**
     * 1.9.0：先写同目录 `name.part`，成功后再原子替换目标；失败/取消只删 .part，原文件不动。
     * overwrite=true 时目标同名覆盖（仍走 .part 替换，不会先删原文件）。
     */
    static String download(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookie,
            final QuarkUcApi.FileItem item,
            final Progress progress,
            final Done done,
            final Failure failure,
            final File targetDir,
            final boolean overwrite) {
        final String taskId = p.key + ":" + item.fid + ":" + System.currentTimeMillis();
        if (!hasDownloadPermission(a)) {
            AppLog.i("FileMgr", "download provider=" + p.key + " fid=" + item.fid + " state=no-permission");
            requestDownloadPermission(a, 8821);
            if (failure != null)
                a.ui.post(() -> failure.onError("需要存储权限：请在系统设置中授予「存储」权限后重试"));
            return taskId;
        }
        File dir = targetDir != null ? targetDir : downloadDir(a);
        if (!dir.exists()) dir.mkdirs();
        File target =
                overwrite
                        ? new File(dir, safeName(item.name))
                        : uniqueTarget(dir, safeName(item.name));
        File part = new File(target.getParentFile(), target.getName() + ".part");
        Task task =
                new Task(
                        taskId,
                        a,
                        p,
                        cookie,
                        item,
                        progress,
                        done,
                        failure,
                        target,
                        part,
                        overwrite);
        TASKS.put(taskId, task);
        AppLog.i(
                "FileMgr",
                "download provider=" + p.key + " fid=" + item.fid + " state=start target=" + target.getName());
        start(task);
        return taskId;
    }

    private static final class Task {
        static final int RUNNING = 0, PAUSING = 1, PAUSED = 2, FINISHED = 3;
        final String id;
        final MainActivity a;
        final QuarkUcApi.Provider p;
        final String cookie;
        final QuarkUcApi.FileItem item;
        final Progress progress;
        final Done done;
        final Failure failure;
        final File target;
        final File part;
        final boolean overwrite;
        final int notifyId;
        volatile int state = RUNNING;
        volatile boolean resumeAfterPause;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile long written;

        Task(
                String id,
                MainActivity a,
                QuarkUcApi.Provider p,
                String cookie,
                QuarkUcApi.FileItem item,
                Progress progress,
                Done done,
                Failure failure,
                File target,
                File part,
                boolean overwrite) {
            this.id = id;
            this.a = a;
            this.p = p;
            this.cookie = cookie;
            this.item = item;
            this.progress = progress;
            this.done = done;
            this.failure = failure;
            this.target = target;
            this.part = part;
            this.overwrite = overwrite;
            this.notifyId = id.hashCode();
        }
    }

    private static void start(final Task task) {
        if (task.state != Task.RUNNING) return;
        task.a.worker.execute(() -> run(task));
    }

    /** 暂停状态机：旧 run 退出后进入 PAUSED；若期间已点继续则延后自动续传。 */
    private static void enterPaused(final Task task) {
        task.state = Task.PAUSED;
        AppLog.i(
                "FileMgr",
                "download provider=" + task.p.key + " fid=" + task.item.fid + " bytes=" + task.written + " state=paused");
        if (task.resumeAfterPause) {
            task.resumeAfterPause = false;
            task.a.ui.postDelayed(
                    () -> {
                        if (task.state == Task.PAUSED && !task.cancelled) {
                            task.state = Task.RUNNING;
                            start(task);
                        }
                    },
                    50);
        }
    }

    private static void run(final Task task) {
        final MainActivity a = task.a;
        FileOutputStream out = null;
        InputStream in = null;
        HttpURLConnection connection = null;
        try {
            String direct = QuarkUcApi.downloadUrl(task.p, task.cookie, task.item.fid);
            connection = (HttpURLConnection) new URL(direct).openConnection();
            task.connection = connection;
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Cookie", task.cookie == null ? "" : task.cookie);
            connection.setRequestProperty("Referer", task.p.referer);
            connection.setRequestProperty("User-Agent", task.p.ua);
            // 断点续传：已有 .part 时带 Range
            long existing = task.part.isFile() ? task.part.length() : 0;
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            int status = connection.getResponseCode();
            boolean append = existing > 0 && status == 206;
            if (status == 200 && existing > 0) {
                // 服务端不支持 Range：从头覆盖 .part
                AppLog.i("FileMgr", "resume provider=" + task.p.key + " fid=" + task.item.fid + " range=unsupported");
                append = false;
                existing = 0;
            } else if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status);
            } else if (append) {
                AppLog.i("FileMgr", "resume provider=" + task.p.key + " fid=" + task.item.fid + " range=206 offset=" + existing);
            }
            task.written = existing;
            int contentLength = connection.getContentLength();
            long total = contentLength > 0 ? existing + contentLength : -1;
            in = connection.getInputStream();
            out = new FileOutputStream(task.part, append);
            byte[] buffer = new byte[64 * 1024];
            long lastReport = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (task.cancelled) throw new IOException("已取消");
                if (task.state == Task.PAUSING || task.state == Task.PAUSED) {
                    try {
                        out.flush();
                    } catch (Throwable ignored) {
                    }
                    enterPaused(task);
                    return;
                }
                out.write(buffer, 0, n);
                task.written += n;
                long now = System.currentTimeMillis();
                if (task.progress != null && now - lastReport > 400) {
                    lastReport = now;
                    final int percent =
                            total > 0 ? (int) Math.min(100, task.written * 100 / total) : -1;
                    final long bytes = task.written;
                    final long totalBytes = total;
                    a.ui.post(
                            () -> {
                                task.progress.onProgress(percent, bytes, totalBytes);
                                notifyProgress(a, task.notifyId, task.item.name, percent);
                            });
                }
            }
            out.flush();
            out.close();
            out = null;
            // 原子替换：成功后才接触目标文件
            File target = task.target;
            if (target.exists()) {
                if (!task.overwrite) target = uniqueTarget(target.getParentFile(), target.getName());
                else target.delete();
            }
            if (!task.part.renameTo(target)) throw new IOException("写入目标文件失败");
            task.state = Task.FINISHED;
            TASKS.remove(task.id);
            long bytes = task.written;
            AppLog.i(
                    "FileMgr",
                    "download provider=" + task.p.key + " fid=" + task.item.fid + " bytes=" + bytes + " state=done path=" + target.getAbsolutePath());
            scanFile(a, target);
            notifyDone(a, task.notifyId, task.item.name);
            if (task.progress != null)
                a.ui.post(() -> task.progress.onProgress(100, bytes, bytes));
            if (task.done != null) {
                final File result = target;
                a.ui.post(
                        () -> {
                            a.toast("已下载到 " + relativePath(result));
                            task.done.onDone(result);
                        });
            }
        } catch (Throwable error) {
            if (!task.cancelled
                    && (task.state == Task.PAUSING || task.state == Task.PAUSED)) {
                enterPaused(task);
                return;
            }
            task.state = Task.FINISHED;
            String message = task.cancelled ? "已取消下载" : "下载失败：" + friendly(error);
            TASKS.remove(task.id);
            try {
                if (task.part.exists()) task.part.delete();
            } catch (Throwable ignored) {
            }
            AppLog.i(
                    "FileMgr",
                    "download provider=" + task.p.key + " fid=" + task.item.fid + " bytes=" + task.written + " state=" + (task.cancelled ? "cancel" : "fail") + " error=" + message);
            if (!task.cancelled) notifyFail(a, task.notifyId, task.item.name, message);
            if (task.failure != null) {
                final String text = message;
                a.ui.post(() -> task.failure.onError(text));
            }
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
            if (connection != null) connection.disconnect();
            task.connection = null;
        }
    }

    static void pause(String taskId) {
        Task task = TASKS.get(taskId);
        if (task == null || task.state != Task.RUNNING) return;
        task.state = Task.PAUSING;
        HttpURLConnection connection = task.connection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
        }
        AppLog.i("FileMgr", "download pause task=" + taskId + " part=" + task.part.length() + " state=pausing");
    }

    static void resume(String taskId) {
        Task task = TASKS.get(taskId);
        if (task == null) return;
        if (task.state == Task.PAUSED) {
            task.state = Task.RUNNING;
            AppLog.i(
                    "FileMgr",
                    "download resume task=" + taskId + " offset=" + (task.part.isFile() ? task.part.length() : 0));
            start(task);
        } else if (task.state == Task.PAUSING) {
            // 旧 run 尚未退出：等它 finally 进入 PAUSED 后再续传，避免双写 .part
            task.resumeAfterPause = true;
            AppLog.i("FileMgr", "download resume deferred task=" + taskId);
        }
    }

    static void cancel(String taskId) {
        Task task = TASKS.get(taskId);
        if (task != null) {
            task.cancelled = true;
            task.state = Task.FINISHED;
            HttpURLConnection connection = task.connection;
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
            try {
                if (task.part.exists()) task.part.delete();
            } catch (Throwable ignored) {
            }
            TASKS.remove(taskId);
        }
        AppLog.i("FileMgr", "download cancel task=" + taskId);
    }

    static String relativePath(File file) {
        String path = file.getAbsolutePath();
        int at = path.indexOf("/Download/");
        return at >= 0 ? "Download/" + path.substring(at + "/Download/".length()) : path;
    }

    // ---------------- 本地文件数据层 ----------------

    static final class LocalEntry {
        final File file;
        final String name;
        final boolean folder;
        final boolean video;
        final long size;
        final long modified;

        LocalEntry(File file) {
            this.file = file;
            this.name = file.getName();
            this.folder = file.isDirectory();
            this.video = !folder && QuarkUcApi.isVideoName(name);
            this.size = folder ? 0 : file.length();
            this.modified = file.lastModified();
        }
    }

    /** 扫描本地目录（文件夹在前，按名称排序；不递归）。 */
    static List<LocalEntry> scan(File dir) {
        List<LocalEntry> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        List<LocalEntry> folders = new ArrayList<>();
        for (File file : files) {
            LocalEntry entry = new LocalEntry(file);
            if (entry.folder) folders.add(entry);
            else out.add(entry);
        }
        java.util.Collections.sort(
                folders, (x, y) -> x.name.compareToIgnoreCase(y.name));
        java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        folders.addAll(out);
        AppLog.i("FileMgr", "scan dir=" + dir.getAbsolutePath() + " items=" + folders.size());
        return folders;
    }

    /** 设备相册/媒体库视频（MediaStore）。 */
    static List<LocalEntry> scanDeviceVideos(MainActivity a) {
        List<LocalEntry> out = new ArrayList<>();
        try {
            android.database.Cursor cursor =
                    a.getContentResolver()
                            .query(
                                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                    new String[] {
                                        android.provider.MediaStore.Video.Media.DATA,
                                        android.provider.MediaStore.Video.Media.SIZE,
                                        android.provider.MediaStore.Video.Media.DATE_MODIFIED
                                    },
                                    null,
                                    null,
                                    android.provider.MediaStore.Video.Media.DATE_MODIFIED + " DESC");
            if (cursor != null) {
                while (cursor.moveToNext() && out.size() < 500) {
                    String path = cursor.getString(0);
                    if (path == null) continue;
                    File file = new File(path);
                    if (file.isFile()) out.add(new LocalEntry(file));
                }
                cursor.close();
            }
        } catch (Throwable error) {
            AppLog.i("FileMgr", "scanDeviceVideos error=" + error.getClass().getSimpleName());
        }
        AppLog.i("FileMgr", "scanDeviceVideos items=" + out.size());
        return out;
    }

    // ---------------- 文件操作（真实 File API） ----------------

    static boolean delete(File file) {
        boolean ok = file != null && file.delete();
        AppLog.i(
                "FileMgr",
                "delete path=" + (file == null ? "null" : file.getName()) + " ok=" + ok);
        return ok;
    }

    static boolean rename(File file, String newName) {
        if (file == null || newName == null) return false;
        String name = safeName(newName.trim());
        if (name.isEmpty()) return false;
        File target = new File(file.getParentFile(), name);
        if (target.exists()) return false;
        boolean ok = file.renameTo(target);
        AppLog.i(
                "FileMgr",
                "rename from=" + file.getName() + " to=" + name + " ok=" + ok);
        return ok;
    }

    static boolean move(File file, File targetDir) {
        if (file == null || targetDir == null || !targetDir.isDirectory()) return false;
        File target = uniqueTarget(targetDir, file.getName());
        boolean ok = file.renameTo(target);
        if (!ok) {
            // 跨存储卷：流式复制后删除源文件
            ok = copy(file, target) && file.delete();
        }
        AppLog.i("FileMgr", "move name=" + file.getName() + " ok=" + ok);
        return ok;
    }

    private static boolean copy(File source, File target) {
        try (InputStream in = new java.io.FileInputStream(source);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    // ---------------- 播放 ----------------

    /** 本地文件直接交给现有播放器（复用 PushFiles.playUrl 的本地播放路径）。 */
    static void playLocal(MainActivity a, File file) {
        if (file == null || !file.isFile()) {
            a.toast("文件不存在");
            return;
        }
        AppLog.i("FileMgr", "play local=" + file.getName());
        try {
            PushFiles.playUrl(a, "file://" + file.getAbsolutePath(), file.getName());
        } catch (Throwable error) {
            AppLog.i("FileMgr", "play local error=" + friendly(error));
            a.toast("播放失败：" + friendly(error));
        }
    }

    /** 网盘视频：优先 http 直链（带 Cookie/Referer/UA）。 */
    static void playRemote(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookie,
            final QuarkUcApi.FileItem item) {
        boolean fromSource = cookie != null && cookie.contains("quark_st");
        playRemote(a, p, cookie, item, fromSource);
    }

    static void playRemote(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookie,
            final QuarkUcApi.FileItem item,
            final boolean fromSource) {
        a.toast("正在获取播放地址…");
        a.worker.execute(
                () -> {
                    try {
                        String url = QuarkUcApi.downloadUrl(p, cookie, item.fid);
                        final Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("Cookie", cookie == null ? "" : cookie);
                        headers.put("Referer", p.referer);
                        headers.put("User-Agent", p.ua);
                        AppLog.i("FileMgr", "play remote provider=" + p.key + " fid=" + item.fid + " url=ok");
                        a.ui.post(
                                () ->
                                        syntheticPlay(
                                                a,
                                                "open_" + p.key,
                                                p.name + "网盘（" + (fromSource ? "源插件" : "App 内") + "）",
                                                url,
                                                item.name,
                                                headers));
                    } catch (Throwable error) {
                        AppLog.i("FileMgr", "play remote error=" + friendly(error));
                        a.ui.post(() -> a.notice("获取播放地址失败：" + friendly(error)));
                    }
                });
    }

    private static void syntheticPlay(
            MainActivity a,
            String key,
            String siteName,
            String url,
            String title,
            Map<String, String> headers) {
        Cms.Site site = new Cms.Site();
        site.key = key;
        site.name = siteName;
        site.api = "";
        site.requiresParser = false;
        site.searchable = false;
        site.headers.putAll(headers);
        Cms.Video video = new Cms.Video();
        video.id = key + ":" + title;
        video.title = title;
        video.poster = "";
        Cms.Line line = new Cms.Line();
        line.name = siteName;
        Cms.Episode episode = new Cms.Episode();
        episode.name = title;
        episode.url = url;
        episode.headers.putAll(headers);
        line.episodes.add(episode);
        video.lines.add(line);
        a.resolvePlayer(site, video, line, episode);
    }

    // ---------------- 工具 ----------------

    static String safeName(String name) {
        if (name == null) return "download";
        String out = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (out.isEmpty()) out = "download";
        if (out.length() > 120) {
            // 1.8.31：超长截断时保留扩展名
            String ext = "";
            int dot = out.lastIndexOf('.');
            if (dot > 0 && out.length() - dot <= 12) ext = out.substring(dot);
            String base = dot > 0 ? out.substring(0, dot) : out;
            out = base.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return out;
    }

    static File uniqueTarget(File dir, String name) {
        File target = new File(dir, safeName(name));
        if (!target.exists()) return target;
        String base = target.getName();
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            ext = base.substring(dot);
            base = base.substring(0, dot);
        }
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return target;
    }

    private static String friendly(Throwable error) {
        return error == null
                ? "未知错误"
                : error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "：" + error.getMessage());
    }

    // ---------------- 通知栏 / 媒体库 ----------------

    private static final String CHANNEL = "whalefall_download";

    private static void notifyProgress(
            MainActivity a, int notifyId, String name, int percent) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            a.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel channel =
                        new android.app.NotificationChannel(
                                CHANNEL, "下载", android.app.NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
            android.app.Notification.Builder builder =
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? new android.app.Notification.Builder(a, CHANNEL)
                            : new android.app.Notification.Builder(a);
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("正在下载")
                    .setContentText(name)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(100, Math.max(0, percent), percent < 0);
            nm.notify(notifyId, builder.build());
        } catch (Throwable ignored) {
        }
    }

    private static void notifyDone(MainActivity a, int notifyId, String name) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            a.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                        new android.app.NotificationChannel(
                                CHANNEL, "下载", android.app.NotificationManager.IMPORTANCE_LOW));
            }
            android.app.Notification.Builder builder =
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? new android.app.Notification.Builder(a, CHANNEL)
                            : new android.app.Notification.Builder(a);
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载完成")
                    .setContentText("Download/鲸落/" + name)
                    .setAutoCancel(true);
            nm.notify(notifyId, builder.build());
        } catch (Throwable ignored) {
        }
    }

    private static void notifyFail(MainActivity a, int notifyId, String name, String message) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            a.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                        new android.app.NotificationChannel(
                                CHANNEL, "下载", android.app.NotificationManager.IMPORTANCE_LOW));
            }
            android.app.Notification.Builder builder =
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? new android.app.Notification.Builder(a, CHANNEL)
                            : new android.app.Notification.Builder(a);
            builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("下载失败")
                    .setContentText(name + "：" + message)
                    .setAutoCancel(true);
            nm.notify(notifyId + 1, builder.build());
        } catch (Throwable ignored) {
        }
    }

    static void scanFile(MainActivity a, File file) {
        try {
            android.media.MediaScannerConnection.scanFile(
                    a, new String[] {file.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {
        }
    }

    static String formatDate(long millis) {
        if (millis <= 0) return "";
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(millis));
        } catch (Throwable ignored) {
            return "";
        }
    }
}
