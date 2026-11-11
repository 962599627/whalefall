package com.whalefall.movie;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 「管理后台上传」与「内置文件管理」共用的文件区：应用私有目录 pushed/。
 *
 * <p>支持子目录（文件管理里可以新建文件夹），路径以相对 pushed/ 的 "/" 分隔路径表示。
 */
final class PushFiles {
 static final long MAX_BYTES = 200L * 1024 * 1024;

 private PushFiles() {}

 static File root(MainActivity a) {
 File dir = new File(a.getFilesDir(), "pushed");
 if (!dir.isDirectory()) dir.mkdirs();
 return dir;
 }

 /** 兼容旧调用：根目录。 */
 static File dir(MainActivity a) {
 return root(a);
 }

 /** 清理文件名（单段）。 */
 static String sanitize(String name) {
 return sanitizeSegment(name);
 }

 private static String sanitizeSegment(String name) {
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
 if (clean.equals(".") || clean.equals("..")) clean = "";
 return clean;
 }

 /** 清理相对路径（允许 / 分隔，禁止 .. 与绝对路径）。 */
 static String sanitizePath(String path) {
 if (path == null) return "";
 StringBuilder out = new StringBuilder();
 for (String part : path.replace('\\', '/').split("/")) {
 String seg = sanitizeSegment(part);
 if (seg.isEmpty()) continue;
 if (out.length() > 0) out.append('/');
 out.append(seg);
 }
 return out.toString();
 }

 static File resolve(MainActivity a, String relPath) {
 String clean = sanitizePath(relPath);
 return clean.isEmpty() ? root(a) : new File(root(a), clean);
 }

 /** 兼容旧调用（根目录下文件）。 */
 /** 把内置 assets 文本复制到内置文件目录（用于教程/示例预览）。 */
 static String installAsset(MainActivity a, String assetName, String target) throws Exception {
 File f = file(a, target);
 File parent = f.getParentFile();
 if (parent != null && !parent.isDirectory() && !parent.mkdirs())
 throw new java.io.IOException("无法创建目录");
 try (InputStream in = a.getAssets().open(assetName);
 OutputStream out = new FileOutputStream(f)) {
 byte[] buf = new byte[8192];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 }
 return target;
 }

 /** 写入一段文本到内置文件目录。 */
 static String writeText(MainActivity a, String target, String text) throws Exception {
 File f = file(a, target);
 File parent = f.getParentFile();
 if (parent != null && !parent.isDirectory() && !parent.mkdirs())
 throw new java.io.IOException("无法创建目录");
 try (java.io.Writer writer =
 new java.io.OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
 writer.write(text);
 }
 return target;
 }

 /** 从系统文件选择器流式保存，避免大文件整块进内存。 */
 static String saveStream(MainActivity a, String rel, InputStream in) throws Exception {
 if (!allowedUpload(rel)) throw new IllegalArgumentException("仅支持视频 / 文本 / 图片 / APK");
 File target = file(a, rel);
 File parent = target.getParentFile();
 if (parent != null && !parent.isDirectory() && !parent.mkdirs())
 throw new java.io.IOException("无法创建目录");
 long total = 0;
 try (InputStream source = in; OutputStream out = new FileOutputStream(target)) {
 byte[] buf = new byte[65536];
 int n;
 while ((n = source.read(buf)) > 0) {
 total += n;
 if (total > MAX_BYTES) throw new java.io.IOException("文件超过 200MB 上限");
 out.write(buf, 0, n);
 }
 }
 return rel;
 }

 static File file(MainActivity a, String name) {
 return resolve(a, name);
 }

 static List<File> list(MainActivity a, String relDir) {
 File dir = resolve(a, relDir);
 File[] files = dir.listFiles();
 List<File> out = new ArrayList<>();
 if (files != null) for (File f : files) if (!f.getName().startsWith(".")) out.add(f);
 Collections.sort(
 out,
 (x, y) -> {
 if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
 return x.getName().compareToIgnoreCase(y.getName());
 });
 return out;
 }

 static List<File> list(MainActivity a) {
 return list(a, "");
 }

 static void mkdirs(MainActivity a, String relDir) throws IOException {
 File dir = resolve(a, relDir);
 if (dir.isDirectory()) return;
 if (!dir.mkdirs()) throw new IOException("创建文件夹失败");
 }

 static void rename(MainActivity a, String relPath, String newName) throws IOException {
 File from = resolve(a, relPath);
 if (!from.exists()) throw new IOException("文件不存在");
 String parent = parentOf(relPath);
 String clean = sanitizePath(parent.isEmpty() ? newName : parent + "/" + newName);
 File to = resolve(a, clean);
 if (to.exists()) throw new IOException("同名文件已存在");
 if (!from.renameTo(to)) throw new IOException("重命名失败");
 }

 static void delete(MainActivity a, String relPath) {
 deleteRecursive(resolve(a, relPath));
 }

 private static void deleteRecursive(File f) {
 if (f == null || !f.exists()) return;
 if (f.isDirectory()) {
 File[] kids = f.listFiles();
 if (kids != null) for (File kid : kids) deleteRecursive(kid);
 }
 f.delete();
 }

 static File save(MainActivity a, String relPath, byte[] bytes) throws IOException {
 if (bytes == null) throw new IOException("没有文件内容");
 if (bytes.length > MAX_BYTES) throw new IOException("文件超过 60MB 限制");
 File target = resolve(a, relPath);
 File parent = target.getParentFile();
 if (parent != null && !parent.isDirectory() && !parent.mkdirs())
 throw new IOException("无法创建目录");
 try (FileOutputStream out = new FileOutputStream(target)) {
 out.write(bytes);
 }
 return target;
 }

 static String readText(MainActivity a, String relPath) throws IOException {
 File f = resolve(a, relPath);
 if (!f.isFile()) throw new IOException("文件不存在");
 if (f.length() > 2L * 1024 * 1024) throw new IOException("文本文件过大");
 try (InputStream in = new FileInputStream(f)) {
 ByteArrayOutputStream out = new ByteArrayOutputStream();
 byte[] buf = new byte[8192];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 return new String(out.toByteArray(), StandardCharsets.UTF_8);
 }
 }

 static final String[] ALLOWED_EXT = {
 ".md", ".markdown", ".txt", ".log", ".json",
 ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".flv",
 ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
 ".apk"
 };

 static boolean isImage(String name) {
 String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
 for (String ext : new String[] {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"})
 if (n.endsWith(ext)) return true;
 return false;
 }

 static boolean isApk(String name) {
 String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
 return n.endsWith(".apk");
 }

 static boolean isText(String name) {
 return isMarkdown(name);
 }

 /** 上传类型校验：视频 / 文本 / 图片 / APK。 */
 static boolean allowedUpload(String name) {
 String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
 for (String ext : ALLOWED_EXT) if (n.endsWith(ext)) return true;
 return false;
 }

 static boolean isMarkdown(String name) {
 String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
 return n.endsWith(".md")
 || n.endsWith(".markdown")
 || n.endsWith(".txt")
 || n.endsWith(".log")
 || n.endsWith(".json");
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

 static String join(String dir, String name) {
 return dir == null || dir.isEmpty() ? name : dir + "/" + name;
 }

 static String parentOf(String relPath) {
 String clean = sanitizePath(relPath);
 int slash = clean.lastIndexOf('/');
 return slash < 0 ? "" : clean.substring(0, slash);
 }

 static String baseName(String relPath) {
 String clean = sanitizePath(relPath);
 int slash = clean.lastIndexOf('/');
 return slash < 0 ? clean : clean.substring(slash + 1);
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
