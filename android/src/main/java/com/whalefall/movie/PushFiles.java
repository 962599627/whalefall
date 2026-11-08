package com.whalefall.movie;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 「管理后台上传」与「内置文件管理」共用的文件区：应用私有目录 pushed/。
 *
 * <p>管理后台（网页 / FTP）上传的文件保存到这里；App 内文件管理读取同一目录。
 */
final class PushFiles {
    static final long MAX_BYTES = 60L * 1024 * 1024;

    private PushFiles() {}

    static File dir(MainActivity a) {
        File dir = new File(a.getFilesDir(), "pushed");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** 去掉路径分隔符与危险字符，只允许一层文件名。 */
    static String sanitize(String name) {
        if (name == null) return "";
        String base = new File(name).getName();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                    || c == '>' || c == '|' || c == 0)
                continue;
            out.append(c);
        }
        String clean = out.toString().trim();
        return clean.isEmpty() ? "未命名文件" : clean;
    }

    static File file(MainActivity a, String name) {
        return new File(dir(a), sanitize(name));
    }

    static List<File> list(MainActivity a) {
        File[] files = dir(a).listFiles();
        List<File> out = new ArrayList<>();
        if (files != null) for (File f : files) if (f.isFile()) out.add(f);
        Collections.sort(out, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return out;
    }

    static File save(MainActivity a, String name, byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("没有文件内容");
        if (bytes.length > MAX_BYTES) throw new IOException("文件超过 60MB 限制");
        File target = file(a, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return target;
    }

    static void delete(MainActivity a, String name) {
        File f = file(a, name);
        if (f.isFile()) f.delete();
    }

    static String readText(MainActivity a, String name) throws IOException {
        File f = file(a, name);
        if (!f.isFile()) throw new IOException("文件不存在");
        if (f.length() > 2L * 1024 * 1024) throw new IOException("文本文件过大");
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static boolean isMarkdown(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt");
    }

    static boolean isVideo(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String ext : new String[] {".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".flv"})
            if (n.endsWith(ext)) return true;
        return false;
    }

    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        return String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f));
    }

    /** 推送链接直接播放：构造一个临时来源，交给现有播放器。 */
    static void playUrl(MainActivity a, String url, String title) {
        final String target = url == null ? "" : url.trim();
        if (target.isEmpty()) return;
        a.ui.post(
                () -> {
                    Cms.Site site = new Cms.Site();
                    site.key = "push";
                    site.name = "网页推送";
                    site.api = "push";
                    Cms.Video video = new Cms.Video();
                    video.id = "push:" + Math.abs(target.hashCode());
                    video.title = title == null || title.trim().isEmpty() ? "网页推送播放" : title;
                    video.remarks = "推送";
                    Cms.Episode episode = new Cms.Episode();
                    episode.name = "推送播放";
                    episode.url = target;
                    try {
                        a.openPlayer(site, video, episode);
                    } catch (Throwable t) {
                        a.toast("推送播放失败：" + t.getMessage());
                    }
                });
    }
}
