package com.whalefall.movie;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded asynchronous poster/artwork loader, memory cache trimming, and background atmosphere
 * rendering extracted from {@link MainActivity}.
 */
final class ImageLoader {
 private static final java.util.Map<String, java.util.List<ImageView>> IN_FLIGHT =
 new java.util.HashMap<>();
 /** 1.9.147（1GB 内存优化）：壁纸按最大边 1280 降采样 + RGB_565，并跨页面缓存。 */
 private static final int WALLPAPER_MAX_EDGE = 1280;
 private static final android.util.LruCache<String, Bitmap> WALLPAPER_CACHE =
 new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
 @Override
 protected int sizeOf(String key, Bitmap value) {
 return value == null ? 0 : value.getByteCount();
 }
 };

 private ImageLoader() {}

 static void cancel(MainActivity a) {
 synchronized (IN_FLIGHT) {
 IN_FLIGHT.clear();
 }
 for (Future<?> request : a.artworkRequests) request.cancel(true);
 a.artworkRequests.clear();
 ((ThreadPoolExecutor) a.artwork).purge();
 }

 static void trimMemory(MainActivity a, int level) {
 if (a.images == null) return;
 if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level >= 15) {
 a.images.evictAll();
 } else if (level >= 10) {
 UiCompat.trimCache(a.images, a.images.maxSize() / 2);
 }
 }

 static void poster(MainActivity a, String address, ImageView image, boolean detail) {
 image.setTag(address);
 if (!Cms.isHttp(address) && !address.startsWith("catpaw://")) return;
 final int ticket = a.generation;
 int initialW = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
 int initialH = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
 boolean initialCrop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
 String initialKey =
 ImageDecodePolicy.cacheKey(address, initialW, initialH, initialCrop, detail);
 Bitmap immediate = a.images.get(initialKey);
 if (immediate != null && !immediate.isRecycled()) {
 image.setImageBitmap(immediate);
 com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
 return;
 }
 Bitmap preview = a.images.get(address);
 if (preview != null && !preview.isRecycled()) {
 image.setImageBitmap(preview);
 com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
 if (!detail || (preview.getWidth() >= initialW && preview.getHeight() >= initialH)) {
 return;
 }
 }
 startPosterLoad(a, address, image, detail, ticket);
 }

 private static void startPosterLoad(
 MainActivity a, String address, ImageView image, boolean detail, final int ticket) {
 if (ticket != a.generation || !address.equals(image.getTag())) return;
 final int width = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
 final int height = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
 final boolean crop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
 final String key = ImageDecodePolicy.cacheKey(address, width, height, crop, detail);
 Bitmap cached = a.images.get(key);
 if (cached != null && !cached.isRecycled()) {
 image.setImageBitmap(cached);
 com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
 return;
 }
 final java.util.List<ImageView> owned = new java.util.ArrayList<>(2);
 synchronized (IN_FLIGHT) {
 java.util.List<ImageView> waiters = IN_FLIGHT.get(key);
 if (waiters != null) {
 if (!waiters.contains(image)) waiters.add(image);
 return;
 }
 owned.add(image);
 IN_FLIGHT.put(key, owned);
 }
 Future<?> request =
 RequestScope.submit(
 a.artwork,
 () -> {
 if (ticket != a.generation) {
 synchronized (IN_FLIGHT) {
 if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
 }
 return;
 }
 HttpURLConnection connection = null;
 try {
 Cms.ImageRequest source = Cms.imageRequest(address);
 String resolvedUrl = Cms.normalizeHttp(source.url);
 if (resolvedUrl.startsWith("catpaw://")) {
 resolvedUrl =
 new com.pilipala.catpaw.CatPawHost(a)
 .resolve(resolvedUrl);
 }
 String targetUrl = upgradePosterUrl(resolvedUrl, detail);
 byte[] bytes =
 fetchPosterBytesWithFallback(
 a, source, resolvedUrl, targetUrl, detail);
 if (bytes == null || bytes.length == 0) return;
 BitmapFactory.Options options = new BitmapFactory.Options();
 options.inJustDecodeBounds = true;
 BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
 if (options.outWidth <= 0 || options.outHeight <= 0) return;
 options.inSampleSize =
 ImageDecodePolicy.sample(
 options.outWidth,
 options.outHeight,
 width,
 height,
 crop,
 detail);
 options.inJustDecodeBounds = false;
 RequestScope.current().check();
 Bitmap bitmap =
 BitmapFactory.decodeByteArray(
 bytes, 0, bytes.length, options);
 if (bitmap != null) {
 if (ticket != a.generation
 || Thread.currentThread().isInterrupted()) {
 bitmap.recycle();
 return;
 }
 a.images.put(key, bitmap);
 Bitmap existingPreview = a.images.get(address);
 if (existingPreview == null
 || existingPreview.isRecycled()
 || bitmap.getWidth() >= existingPreview.getWidth()) {
 a.images.put(address, bitmap);
 }
 final java.util.List<ImageView> targets;
 synchronized (IN_FLIGHT) {
 java.util.List<ImageView> removed =
 IN_FLIGHT.get(key) == owned
 ? IN_FLIGHT.remove(key)
 : null;
 targets =
 removed == null
 ? java.util.Collections.singletonList(image)
 : new java.util.ArrayList<>(removed);
 }
 a.ui.post(
 () -> {
 if (ticket != a.generation || a.isFinishing())
 return;
 for (ImageView target : targets) {
 if (target != null
 && address.equals(target.getTag())) {
 target.setImageBitmap(bitmap);
 com.pilipala.glass.GlassworkSurface
 .invalidateBackdrop(target);
 }
 }
 });
 }
 } catch (Exception ignored) {
 } finally {
 synchronized (IN_FLIGHT) {
 if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
 }
 if (connection != null) {
 RequestScope.detach(connection);
 connection.disconnect();
 }
 }
 });
 for (java.util.Iterator<Future<?>> it = a.artworkRequests.iterator(); it.hasNext(); ) {
 if (it.next().isDone()) it.remove();
 }
 a.artworkRequests.add(request);
 }

 private static File diskCacheFile(MainActivity a, String url) {
 try {
 File dir = new File(a.getCacheDir(), "posters_v2");
 if (!dir.exists()) dir.mkdirs();
 java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
 byte[] d = md.digest(url.getBytes("UTF-8"));
 StringBuilder sb = new StringBuilder(32);
 for (int i = 0; i < 16; i++) {
 sb.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 255));
 }
 return new File(dir, sb.toString() + ".img");
 } catch (Exception e) {
 return null;
 }
 }

 private static byte[] readDiskCachedPoster(MainActivity a, String url) {
 try {
 File f = diskCacheFile(a, url);
 if (f == null || !f.isFile()) return null;
 long len = f.length();
 if (len <= 128 || len > 8 * 1024 * 1024) return null;
 if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000L) {
 return null;
 }
 byte[] buf = new byte[(int) len];
 try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
 int off = 0;
 while (off < buf.length) {
 int r = in.read(buf, off, buf.length - off);
 if (r < 0) break;
 off += r;
 }
 if (off != buf.length) return null;
 }
 return buf;
 } catch (Exception e) {
 return null;
 }
 }

 private static void writeDiskCachedPoster(MainActivity a, String url, byte[] bytes) {
 try {
 File f = diskCacheFile(a, url);
 if (f == null) return;
 try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
 out.write(bytes);
 }
 } catch (Exception ignored) {
 }
 }

 private static byte[] fetchPosterBytesWithFallback(
 MainActivity a,
 Cms.ImageRequest source,
 String resolvedUrl,
 String targetUrl,
 boolean detail)
 throws Exception {
 String[] candidates =
 detail && !targetUrl.equals(resolvedUrl)
 ? new String[] {
 targetUrl, upgradePosterUrl(resolvedUrl, false), resolvedUrl
 }
 : new String[] {targetUrl, resolvedUrl};
 Exception lastError = null;
 java.util.HashSet<String> tried = new java.util.HashSet<>();
 for (String candidate : candidates) {
 if (candidate == null || candidate.isEmpty() || !tried.add(candidate)) continue;
 byte[] cached = readDiskCachedPoster(a, candidate);
 if (cached != null) return cached;
 HttpURLConnection connection = null;
 try {
 connection = (HttpURLConnection) new URL(candidate).openConnection();
 for (Map.Entry<String, String> header : source.headers.entrySet())
 connection.setRequestProperty(header.getKey(), header.getValue());
 String fragment = new URL(candidate).getRef();
 if (fragment != null && fragment.startsWith("mm-emby=")) {
 connection.setInstanceFollowRedirects(false);
 com.pilipala.emby.EmbyLibrary library =
 com.pilipala.emby.EmbyAccounts.open(
 a, "emby://account/" + fragment.substring(8));
 for (Map.Entry<String, String> h : library.imageHeaders(candidate).entrySet())
 connection.setRequestProperty(h.getKey(), h.getValue());
 }
 applyArtworkHeaders(connection, candidate, source.headers);
 connection.setConnectTimeout(4500);
 connection.setReadTimeout(5500);
 if (!source.headers.containsKey("User-Agent"))
 connection.setRequestProperty("User-Agent", Cms.USER_AGENT);
 RequestScope.attach(connection);
 try (InputStream in = connection.getInputStream()) {
 ByteArrayOutputStream out = new ByteArrayOutputStream();
 byte[] buffer = new byte[8192];
 int n;
 while ((n = in.read(buffer)) != -1) {
 RequestScope.current().check();
 if (out.size() + n > (detail ? 8 : 3) * 1024 * 1024)
 throw new IOException("Poster too large");
 out.write(buffer, 0, n);
 }
 byte[] bytes = out.toByteArray();
 if (bytes.length > 128) {
 writeDiskCachedPoster(a, candidate, bytes);
 return bytes;
 }
 }
 } catch (Exception e) {
 lastError = e;
 } finally {
 if (connection != null) connection.disconnect();
 }
 }
 if (lastError != null) throw lastError;
 return null;
 }

 static String upgradePosterUrl(String rawUrl, boolean detail) {
 if (rawUrl == null || rawUrl.isEmpty()) return "";
 String url = rawUrl;
 if (url.contains("doubanio.com")) {
 url =
 url.replace("img9.doubanio.com", "img3.doubanio.com")
 .replace("img1.doubanio.com", "img3.doubanio.com")
 .replace("img2.doubanio.com", "img3.doubanio.com");
 if (detail) {
 url =
 url.replace("/s_ratio_poster/", "/l_ratio_poster/")
 .replace("/m_ratio_poster/", "/l_ratio_poster/")
 .replace("/view/photo/spic/", "/view/photo/lpic/")
 .replace("/view/photo/mpic/", "/view/photo/lpic/")
 .replace("/view/photo/s/", "/view/photo/l/")
 .replace("/view/photo/m/", "/view/photo/l/");
 } else {
 url =
 url.replace("/s_ratio_poster/", "/m_ratio_poster/")
 .replace("/view/photo/spic/", "/view/photo/mpic/");
 }
 }
 if (url.contains("image.tmdb.org/t/p/")) {
 if (detail) {
 url =
 url.replace("/t/p/w185/", "/t/p/w780/")
 .replace("/t/p/w300/", "/t/p/w780/")
 .replace("/t/p/w342/", "/t/p/w780/")
 .replace("/t/p/w500/", "/t/p/w780/");
 } else {
 url =
 url.replace("/t/p/w185/", "/t/p/w500/")
 .replace("/t/p/w300/", "/t/p/w500/")
 .replace("/t/p/w342/", "/t/p/w500/");
 }
 }
 if (detail) {
 if (url.contains("lain.bgm.tv") || url.contains("bgm.tv/pic/cover/")) {
 url =
 url.replace("/pic/cover/c/", "/pic/cover/l/")
 .replace("/pic/cover/m/", "/pic/cover/l/")
 .replace("/pic/cover/s/", "/pic/cover/l/")
 .replace("/r/400/", "/r/800/");
 }
 if (url.contains("hdslb.com") || url.contains("biliimg.com")) {
 int at = url.indexOf('@');
 if (at > 0) url = url.substring(0, at);
 }
 if (url.contains("bcebos.com")) {
 int q = url.indexOf('?');
 if (q > 0) url = url.substring(0, q);
 }
 }
 return url;
 }

 static void applyArtworkHeaders(
 HttpURLConnection connection, String url, Map<String, String> existing) {
 if (existing != null && existing.containsKey("Referer")) return;
 String lower = url.toLowerCase();
 if (lower.contains("doubanio.com") || lower.contains("douban.com")) {
 connection.setRequestProperty("Referer", "https://movie.douban.com/");
 } else if (lower.contains("hdslb.com")
 || lower.contains("biliimg.com")
 || lower.contains("bilibili.com")) {
 connection.setRequestProperty("Referer", "https://www.bilibili.com/");
 } else if (lower.contains("bcebos.com") || lower.contains("baidu.com")) {
 connection.setRequestProperty("Referer", "https://baike.baidu.com/");
 } else if (lower.contains("bgm.tv") || lower.contains("bangumi.tv")) {
 connection.setRequestProperty("Referer", "https://bgm.tv/");
 } else if (lower.contains("bing.net") || lower.contains("bing.com")) {
 connection.setRequestProperty("Referer", "https://cn.bing.com/");
 } else if (lower.contains("qpic.cn") || lower.contains("gtimg.cn")) {
 connection.setRequestProperty("Referer", "https://v.qq.com/");
 } else if (lower.contains("sinaimg.cn")) {
 connection.setRequestProperty("Referer", "https://weibo.com/");
 }
 }

 static View createAtmosphere(MainActivity a) {
 return new Atmosphere(a);
 }

 static final class Atmosphere extends View {
 final MainActivity a;
 final Paint paint = new Paint(3);
 Bitmap wallpaper;
 Bitmap fallbackWallpaper;
 boolean decodeLogged;
 boolean usingBuiltin;
 boolean drawLogged;
 boolean released;
 /** 1.9.171：整屏壁纸缓存，避免每帧重算。 */
 Bitmap frameCache;
 /** 1.9.166：同一进程内复用内置鲸鱼娘降采样结果。 */
 static Bitmap cachedDefault;
 /** 1.9.180：缓存键带目标最大边，避免小图全局复用发黑。 */
 static int cachedDefaultEdge;

 Atmosphere(Context c) {
 super(c);
 a = (MainActivity) c;
 // 1.9.171：缓存层走硬件层，静止时不需要持续重绘
 setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
 if (!a.prefs.getBoolean("force_builtin_wallpaper", false)
 && a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) wallpaper = loadWallpaper();
 // 1.9.112：自定义壁纸关闭/缺失时永远用内置默认壁纸兜底
 if (wallpaper == null) {
 fallbackWallpaper = loadDefaultWallpaper();
 usingBuiltin = fallbackWallpaper != null;
 }
 }

 /** 1.9.148：壁纸最大边；1.9.180：TV 1920/低内存1080，手机 1280/低内存1080。 */
 private static int wallpaperMaxEdge(Context c) {
 try {
 android.app.ActivityManager am =
 (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
 boolean low = am != null && am.isLowRamDevice();
 boolean tv = c instanceof MainActivity && ((MainActivity) c).isTvDevice();
 if (low) return 1080;
 return tv ? 1920 : 1280;
 } catch (Throwable ignored) {
 }
 return WALLPAPER_MAX_EDGE;
 }

 private Bitmap loadDefaultWallpaper() {
 try {
 // 1.9.126：旧机 4K 默认图直接解码会原生崩溃，按屏幕降采样
 int maxEdge = wallpaperMaxEdge(a);
 if (cachedDefault != null
 && !cachedDefault.isRecycled()
 && cachedDefaultEdge == maxEdge) {
 AppLog.i(
 "Wallpaper",
 "default bitmap="
 + cachedDefault.getWidth()
 + "x"
 + cachedDefault.getHeight()
 + " asked="
 + maxEdge
 + " cache=true");
 return cachedDefault;
 }
 if (cachedDefault != null && !cachedDefault.isRecycled()) {
 cachedDefault.recycle();
 cachedDefault = null;
 }
 android.graphics.BitmapFactory.Options bounds =
 new android.graphics.BitmapFactory.Options();
 bounds.inJustDecodeBounds = true;
 try (java.io.InputStream probe = a.getAssets().open("branding/wallpaper-default.jpg")) {
 BitmapFactory.decodeStream(probe, null, bounds);
 }
 int sample = 1;
 if (bounds.outWidth > 0 && bounds.outHeight > 0) {
 int edge = Math.max(bounds.outWidth, bounds.outHeight);
 while (edge / sample > maxEdge) sample *= 2;
 }
 android.graphics.BitmapFactory.Options opts =
 new android.graphics.BitmapFactory.Options();
 opts.inSampleSize = sample;
 opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
 Bitmap bitmap;
 try (java.io.InputStream in = a.getAssets().open("branding/wallpaper-default.jpg")) {
 bitmap = BitmapFactory.decodeStream(in, null, opts);
 }
 AppLog.i(
 "Wallpaper",
 "default bitmap="
 + (bitmap == null ? "null" : bitmap.getWidth() + "x" + bitmap.getHeight())
 + " asked="
 + maxEdge
 + " sample="
 + sample
 + " cache=true");
 cachedDefault = bitmap;
 cachedDefaultEdge = maxEdge;
 return bitmap;
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "default wallpaper load failed", error);
 return null;
 }
 }

 /** 读取用户静态壁纸；文件缺失/解码失败写一条日志，不静默退纯色。 */
 private Bitmap loadWallpaper() {
 File file = new File(a.getFilesDir(), "wallpaper.jpg");
 if (!file.isFile()) {
 if (!decodeLogged) {
 decodeLogged = true;
 AppLog.w("Wallpaper", "static wallpaper file missing: " + file, null);
 }
 return null;
 }
 // 1.9.126：自定义静态壁纸同样降采样到屏幕尺寸内
 Bitmap bitmap = null;
 try {
 android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
 bounds.inJustDecodeBounds = true;
 BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
 int sample = 1;
 if (bounds.outWidth > 0 && bounds.outHeight > 0) {
 int edge = Math.max(bounds.outWidth, bounds.outHeight);
 while (edge / sample > wallpaperMaxEdge(a)) sample *= 2;
 }
 android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
 opts.inSampleSize = sample;
 opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
 bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "static wallpaper decode failed", error);
 }
 if (bitmap == null && !decodeLogged) {
 decodeLogged = true;
 AppLog.w("Wallpaper", "static wallpaper decode failed: " + file, null);
 }
 return bitmap;
 }

 @Override
 protected void onDraw(Canvas canvas) {
 canvas.drawColor(MainActivity.DARK);
 if (wallpaper == null
 && !a.prefs.getBoolean("force_builtin_wallpaper", false)
 && a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false))
 wallpaper = loadWallpaper();
 if (wallpaper == null) {
 wallpaper = fallbackWallpaper;
 usingBuiltin = wallpaper != null;
 }
 if (wallpaper != null) {
 try {
 // 1.9.171：首次绘制生成整屏缓存，之后只贴一次 bitmap，避免每帧重算缩放+渐变
 if (frameCache == null
 || frameCache.getWidth() != Math.max(1, getWidth())
 || frameCache.getHeight() != Math.max(1, getHeight())
 || frameCache.isRecycled()) {
 frameCache =
 Bitmap.createBitmap(
 Math.max(1, getWidth()), Math.max(1, getHeight()),
 android.graphics.Bitmap.Config.ARGB_8888);
 android.graphics.Canvas cacheCanvas = new android.graphics.Canvas(frameCache);
 float scale =
 Math.max(
 (float) getWidth() / wallpaper.getWidth(),
 (float) getHeight() / wallpaper.getHeight());
 float w = wallpaper.getWidth() * scale, h = wallpaper.getHeight() * scale;
 cacheCanvas.drawBitmap(
 wallpaper,
 null,
 new RectF(
 (getWidth() - w) / 2,
 (getHeight() - h) / 2,
 (getWidth() + w) / 2,
 (getHeight() + h) / 2),
 paint);
 int dim = Prefs.clampWallpaperDim(a.prefs.getInt(Prefs.WALLPAPER_DIM, 40));
 if (usingBuiltin) dim = Math.min(dim, 30);
 cacheCanvas.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0));
 AppLog.i("Wallpaper", "draw=cached size=" + getWidth() + "x" + getHeight());
 }
 canvas.drawBitmap(frameCache, 0, 0, null);
 if (!drawLogged) {
 drawLogged = true;
 AppLog.i(
 "Wallpaper",
 "draw source=" + (usingBuiltin ? "builtin-default" : "custom") + " cached=true");
 }
 } catch (Throwable error) {
 // 1.9.126：绘制异常退化为纯色底，绝不原生崩溃
 AppLog.w("Wallpaper", "draw failed; solid fallback", error);
 wallpaper = null;
 fallbackWallpaper = null;
 canvas.drawColor(MainActivity.DARK);
 }
 }
 }

 @Override
 protected void onAttachedToWindow() {
 super.onAttachedToWindow();
 // 1.9.166：复用 View 重新 attach 时若 bitmap 为空则补载
 if (!released && wallpaper == null && fallbackWallpaper == null) {
 if (!a.prefs.getBoolean("force_builtin_wallpaper", false)
 && a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) wallpaper = loadWallpaper();
 if (wallpaper == null) {
 fallbackWallpaper = loadDefaultWallpaper();
 usingBuiltin = fallbackWallpaper != null;
 }
 AppLog.i("Wallpaper", "atmosphere attach reload=true");
 } else {
 AppLog.i("Wallpaper", "atmosphere attach reload=false");
 }
 }

 @Override
 protected void onDetachedFromWindow() {
 // 1.9.166：detach 只保留 bitmap；明确 release 时才回收
 AppLog.i("Wallpaper", "atmosphere detach keep=true released=" + released);
 if (released) recycleBitmaps();
 super.onDetachedFromWindow();
 }

 /** 1.9.166：明确销毁（Activity destroy）时才回收。 */
 void release() {
 released = true;
 recycleBitmaps();
 AppLog.i("Wallpaper", "atmosphere release recycled=true");
 }

 private void recycleBitmaps() {
 try {
 if (frameCache != null && !frameCache.isRecycled()) frameCache.recycle();
 frameCache = null;
 if (wallpaper != null
 && !wallpaper.isRecycled()
 && wallpaper != cachedDefault) wallpaper.recycle();
 wallpaper = null;
 if (fallbackWallpaper != null
 && !fallbackWallpaper.isRecycled()
 && fallbackWallpaper != cachedDefault) fallbackWallpaper.recycle();
 fallbackWallpaper = null;
 if (cachedDefault != null && !cachedDefault.isRecycled()) cachedDefault.recycle();
 cachedDefault = null;
 } catch (Throwable ignored) {
 }
 }
 }
}
