package com.whalefall.movie;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iPhone / Apple TV+ / Infuse 1:1 portrait & tablet immersive video detail screen:
 * - True full-bleed poster from physical screen top (top: 0) down to 77% of screen height
 * - Dynamic ambient color extraction from poster artwork with progressive lower frosted-glass blend
 * - Floating Liquid Glass top navigation bar (left circular back button, right independent Source
 * selector capsule + Download/Favorite dual capsule)
 * - Centered hero typography, metadata pills, TMDB / Douban badges, expandable synopsis, and
 * full-width white stadium Play button
 * - Independent Season selector button ("第 X 季 "), horizontal circular episode number strip, and
 * horizontal 16:9 landscape episode preview cards.
 */
final class MobileDetailScreen {
 private MobileDetailScreen() {}

 static void bindTouchFeedback(View v) {
 v.setFocusable(false);
 v.setFocusableInTouchMode(false);
 v.setOnTouchListener(
 (view, event) -> {
 int action = event.getActionMasked();
 if (action == MotionEvent.ACTION_DOWN) {
 view.setAlpha(0.72f);
 } else if (action == MotionEvent.ACTION_UP
 || action == MotionEvent.ACTION_CANCEL) {
 view.setAlpha(1.0f);
 }
 return false;
 });
 }

 static GradientDrawable glassBg(
 MainActivity a, int fillColor, int strokeColor, float radiusDp) {
 // 1.9.299：统一使用外观设置里的卡片颜色/透明度（替换模式）
 return Skin.card(a.dp(radiusDp), a.dp(0.8f));
 }

 /**
  * 1.9.322：线路 chip / 集数格 / 季 chip 的统一 drawable 工厂。
  *
  * <p>未选中 = 用户卡片底；选中 = 同一卡片底 + 1.5dp ACCENT_SOFT 描边（不换实心色、不用蓝色描边）。
  */
 static GradientDrawable chipBg(MainActivity a, float radiusDp, boolean selected) {
 GradientDrawable d = a.cardBg(radiusDp);
 if (selected) d.setStroke(Math.max(1, a.dp(1.5f)), Skin.accentSoft());
 return d;
 }

 private static GradientDrawable glassBgLegacy(
 MainActivity a, int fillColor, int strokeColor, float radiusDp) {
 GradientDrawable gd = new GradientDrawable();
 gd.setColor(fillColor);
 gd.setCornerRadius(a.dp(radiusDp));
 gd.setStroke(Math.max(1, a.dp(0.8f)), strokeColor);
 return gd;
 }

 static int getStatusBarHeight(MainActivity a) {
 int resId = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
 if (resId > 0) {
 int px = a.getResources().getDimensionPixelSize(resId);
 if (px > 0) return px;
 }
 return a.dp(28);
 }

 static int defaultAmbientForTitle(String title) {
 int[] palette = {
 0xff16314a, // Deep forest green (e.g. 谷雨)
 0xff182638, // Deep midnight blue
 0xff2a1d33, // Deep plum
 0xff2a2f4a, // Deep warm umber
 0xff163048, // Deep teal
 0xff291c24 // Deep burgundy
 };
 int h = title == null ? 0 : Math.abs(title.hashCode());
 return palette[h % palette.length];
 }

 static int extractAmbientColor(Bitmap bmp, int fallback) {
 if (bmp == null || bmp.isRecycled() || bmp.getWidth() < 4 || bmp.getHeight() < 4) {
 return fallback;
 }
 int w = bmp.getWidth();
 int h = bmp.getHeight();
 long rSum = 0, gSum = 0, bSum = 0, weightSum = 0;
 int startY = (int) (h * 0.35f);
 int stepX = Math.max(1, w / 24);
 int stepY = Math.max(1, (h - startY) / 24);
 float[] hsv = new float[3];
 for (int y = startY; y < h; y += stepY) {
 for (int x = 0; x < w; x += stepX) {
 int c = bmp.getPixel(x, y);
 int alpha = Color.alpha(c);
 if (alpha < 128) continue;
 Color.colorToHSV(c, hsv);
 // Weight saturated mid-tone pixels higher so dull black/white bars don't dominate
 long weight = 1 + (long) (hsv[1] * 8f);
 if (hsv[2] > 0.12f && hsv[2] < 0.92f) weight += 3;
 rSum += Color.red(c) * weight;
 gSum += Color.green(c) * weight;
 bSum += Color.blue(c) * weight;
 weightSum += weight;
 }
 }
 if (weightSum <= 0) return fallback;
 int r = (int) (rSum / weightSum);
 int g = (int) (gSum / weightSum);
 int b = (int) (bSum / weightSum);
 Color.RGBToHSV(r, g, b, hsv);
 // Clamp saturation & value to produce a rich, dark Apple TV+ cinema canvas
 if (hsv[1] < 0.22f && hsv[1] > 0.04f) {
 hsv[1] = 0.28f;
 } else {
 hsv[1] = Math.min(0.65f, hsv[1] * 1.15f);
 }
 hsv[2] = Math.max(0.18f, Math.min(0.28f, hsv[2] * 0.42f));
 return Color.HSVToColor(hsv);
 }

 static final class EpisodeDisplay {
 final String cleanTitle;
 final String specBadge;

 EpisodeDisplay(String cleanTitle, String specBadge) {
 this.cleanTitle = cleanTitle;
 this.specBadge = specBadge;
 }
 }

 static EpisodeDisplay parseEpisodeDisplay(String rawName, int epNumber) {
 String s = rawName == null ? "" : rawName.trim();
 int nl = s.indexOf('\n');
 if (nl >= 0) {
 s = s.substring(0, nl).trim();
 }
 s = s.replace('\r', ' ').replaceAll("\\s+", " ").trim();
 String badge = null;
 Matcher m = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)$").matcher(s);
 if (m.matches()) {
 badge = m.group(1).trim();
 s = m.group(2).trim();
 }
 s = s.replaceAll("(?i)\\.(mkv|mp4|ts|flv|avi|mov|m3u8)$", "").trim();
 if (s.length() > 12 && s.length() % 2 == 1) {
 int half = s.length() / 2;
 if (s.charAt(half) == ' '
 && s.substring(0, half).equalsIgnoreCase(s.substring(half + 1))) {
 s = s.substring(0, half).trim();
 }
 }
 if (s.isEmpty() || s.matches("(?i)^[Ee]?0*\\d+$")) {
 s = "E" + String.format(Locale.US, "%02d", epNumber);
 }
 return new EpisodeDisplay(s, badge);
 }

 /**
 * Full-screen portrait backdrop canvas that renders:
 * 1. Solid ambient color matching the poster's dominant mood
 * 2. Full-bleed poster from y=0 to 77% screen height with vertical DST_IN alpha feathering
 * 3. Lower 58%..100% softened/blurred overlap transition + top 28% breathable status-bar scrim
 * 4. Smooth scroll-driven blur & veil cross-fade when scrolling down to the episode list.
 */
 static final class PortraitBackdropView extends View {
 private final MainActivity a;
 private final int posterWindowHeight;
 private final int topScrimHeight;
 private int ambientColor;
 private Bitmap rawBitmap;
 private Bitmap blurredBitmap;
 private float scrollProgress = 0f;
 private int revision = 0;

 private final Paint bitmapPaint =
 new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
 private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Matrix drawMatrix = new Matrix();

 PortraitBackdropView(
 MainActivity activity,
 int posterWindowHeight,
 int topScrimHeight,
 int initAmbient) {
 super(activity);
 this.a = activity;
 this.posterWindowHeight = posterWindowHeight;
 this.topScrimHeight = topScrimHeight;
 this.ambientColor = initAmbient;
 maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
 }

 void setScrollOffset(int scrollY) {
 float maxScroll = Math.max(1f, a.dp(340));
 float next = Math.max(0f, Math.min(1f, scrollY / maxScroll));
 if (Math.abs(next - scrollProgress) > 0.008f) {
 scrollProgress = next;
 invalidate();
 }
 }

 void setPosterBitmap(Bitmap bmp, Runnable onAmbientChanged) {
 this.rawBitmap = bmp;
 final int token = ++revision;
 final int gen = a.generation;
 if (bmp == null) {
 blurredBitmap = null;
 invalidate();
 return;
 }
 a.artwork.execute(
 () -> {
 int extracted = extractAmbientColor(bmp, ambientColor);
 Bitmap small = Bitmap.createScaledBitmap(bmp, 72, 108, true);
 int[] pixels = new int[72 * 108];
 small.getPixels(pixels, 0, 72, 0, 0, 72, 108);
 if (small != bmp) small.recycle();
 for (int pass = 0; pass < 3; pass++) {
 int[] next = new int[pixels.length];
 for (int y = 0; y < 108; y++) {
 for (int x = 0; x < 72; x++) {
 int r = 0, g = 0, b = 0, count = 0;
 for (int dy = -3; dy <= 3; dy++) {
 for (int dx = -3; dx <= 3; dx++) {
 int c =
 pixels[
 Math.max(0, Math.min(107, y + dy)) * 72
 + Math.max(
 0,
 Math.min(71, x + dx))];
 r += Color.red(c);
 g += Color.green(c);
 b += Color.blue(c);
 count++;
 }
 }
 next[y * 72 + x] = Color.rgb(r / count, g / count, b / count);
 }
 }
 pixels = next;
 }
 final Bitmap soft =
 Bitmap.createBitmap(pixels, 72, 108, Bitmap.Config.ARGB_8888);
 a.ui.post(
 () -> {
 if (token != revision
 || gen != a.generation
 || a.isFinishing()) {
 soft.recycle();
 return;
 }
 ambientColor = extracted;
 blurredBitmap = soft;
 if (onAmbientChanged != null) onAmbientChanged.run();
 invalidate();
 });
 });
 invalidate();
 }

 int getAmbientColor() {
 return ambientColor;
 }

 private void configureCenterCropMatrix(Bitmap bmp, int targetW, int targetH) {
 drawMatrix.reset();
 float bw = bmp.getWidth();
 float bh = bmp.getHeight();
 if (bw <= 0 || bh <= 0 || targetW <= 0 || targetH <= 0) return;
 float scale = Math.max(targetW / bw, targetH / bh);
 float dx = (targetW - bw * scale) * 0.5f;
 // Bias slightly toward upper-center (-0.32 alignment like iPhone) so faces/titles shine
 float dy = (targetH - bh * scale) * 0.34f;
 drawMatrix.setScale(scale, scale);
 drawMatrix.postTranslate(dx, dy);
 }

 @Override
 protected void onDraw(Canvas canvas) {
 int w = getWidth();
 int h = getHeight();
 if (w <= 0 || h <= 0) return;

 // 1. Unified Solid Ambient Canvas
 canvas.drawColor(ambientColor);

 int winH = Math.min(h, posterWindowHeight);
 if (rawBitmap != null && !rawBitmap.isRecycled() && winH > 0) {
 int save = canvas.saveLayer(0, 0, w, winH, null);
 configureCenterCropMatrix(rawBitmap, w, winH);
 bitmapPaint.setAlpha(255);
 canvas.drawBitmap(rawBitmap, drawMatrix, bitmapPaint);

 // Scroll-driven frosted blur ONLY when scrolling down to the episode list
 if (blurredBitmap != null && !blurredBitmap.isRecycled()) {
 int scrollBlurAlpha = Math.round(scrollProgress * 225f);
 if (scrollBlurAlpha > 4) {
 RectF dst = new RectF(0, 0, w, winH);
 bitmapPaint.setAlpha(scrollBlurAlpha);
 canvas.drawBitmap(blurredBitmap, null, dst, bitmapPaint);
 }
 }

 // Intrinsic vertical alpha feathering (top 72% is 100% sharp, bottom feathers
 // smoothly)
 maskPaint.setShader(
 new LinearGradient(
 0,
 0,
 0,
 winH,
 new int[] {
 0xffffffff, 0xffffffff, 0xd9ffffff, 0x66ffffff, 0x00ffffff
 },
 new float[] {0f, 0.72f, 0.85f, 0.94f, 1f},
 Shader.TileMode.CLAMP));
 canvas.drawRect(0, 0, w, winH, maskPaint);
 maskPaint.setShader(null);
 canvas.restoreToCount(save);
 }

 // 2. Breathable 28% Top Status-Bar Scrim
 if (topScrimHeight > 0) {
 int cTop = withAlpha(ambientColor, 72);
 int cMid = withAlpha(ambientColor, 26);
 int cBot = withAlpha(ambientColor, 0);
 gradientPaint.setShader(
 new LinearGradient(
 0,
 0,
 0,
 topScrimHeight,
 new int[] {cTop, cMid, cBot},
 new float[] {0f, 0.55f, 1f},
 Shader.TileMode.CLAMP));
 canvas.drawRect(0, 0, w, topScrimHeight, gradientPaint);
 gradientPaint.setShader(null);
 }

 // 3. Post-Blur Bottom Lock Gradient in lower overlap & episode zone (zero bottom seam)
 int lockTop = Math.round(winH * 0.58f);
 gradientPaint.setShader(
 new LinearGradient(
 0,
 lockTop,
 0,
 h,
 new int[] {
 withAlpha(ambientColor, 0),
 withAlpha(ambientColor, 128),
 withAlpha(ambientColor, 232),
 ambientColor,
 ambientColor
 },
 new float[] {0f, 0.22f, 0.40f, 0.52f, 1f},
 Shader.TileMode.CLAMP));
 canvas.drawRect(0, lockTop, w, h, gradientPaint);
 gradientPaint.setShader(null);

 // 4. Scroll Veil
 if (scrollProgress > 0.01f) {
 int veilAlpha = Math.round(scrollProgress * 98f);
 canvas.drawColor(withAlpha(ambientColor, veilAlpha));
 }
 }

 private static int withAlpha(int rgb, int alpha) {
 return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00ffffff);
 }
 }

 /**
 * Hidden ImageView hook passed to `a.poster(url, proxyImage, true)` so whenever MainActivity's
 * artwork loader finishes decoding the poster Bitmap, it feeds `PortraitBackdropView`.
 */
 static final class PosterHookImageView extends ImageView {
 private final PortraitBackdropView target;
 private final Runnable onLoaded;

 PosterHookImageView(MainActivity a, PortraitBackdropView target, Runnable onLoaded) {
 super(a);
 this.target = target;
 this.onLoaded = onLoaded;
 setScaleType(ScaleType.CENTER_CROP);
 int targetW = Math.max(1080, a.windowWidth());
 int targetH = Math.max(1620, Math.round(targetW * 1.5f));
 setLayoutParams(new FrameLayout.LayoutParams(targetW, targetH));
 layout(0, 0, targetW, targetH);
 }

 @Override
 public void setImageBitmap(Bitmap bm) {
 super.setImageBitmap(bm);
 target.setPosterBitmap(bm, onLoaded);
 }
 }

 /**
 * 16:9 Episode Still ImageView that crops a subtle per-episode camera pan from the series
 * poster so each episode card in the horizontal strip has a rich cinematic 16:9 frame.
 */
 static final class EpisodeStillCropView extends ImageView {
 private final int epIndex;
 private final Matrix cropMatrix = new Matrix();

 EpisodeStillCropView(MainActivity a, int epIndex) {
 super(a);
 this.epIndex = epIndex;
 setScaleType(ScaleType.MATRIX);
 }

 @Override
 protected boolean setFrame(int l, int t, int r, int b) {
 boolean changed = super.setFrame(l, t, r, b);
 updateCropMatrix(r - l, b - t);
 return changed;
 }

 @Override
 public void setImageBitmap(Bitmap bm) {
 super.setImageBitmap(bm);
 updateCropMatrix(getWidth(), getHeight());
 }

 private void updateCropMatrix(int vw, int vh) {
 if (getDrawable() == null || vw <= 0 || vh <= 0) return;
 int dw = getDrawable().getIntrinsicWidth();
 int dh = getDrawable().getIntrinsicHeight();
 if (dw <= 0 || dh <= 0) return;
 cropMatrix.reset();
 float baseScale = Math.max((float) vw / dw, (float) vh / dh);
 float zoom = 1.04f + (epIndex % 4) * 0.03f;
 float scale = baseScale * zoom;
 float[] yAnchors = {0.22f, 0.36f, 0.50f, 0.30f, 0.44f};
 float[] xAnchors = {0.50f, 0.38f, 0.62f, 0.45f, 0.55f};
 float ax = xAnchors[epIndex % xAnchors.length];
 float ay = yAnchors[epIndex % yAnchors.length];
 float dx = (vw - dw * scale) * ax;
 float dy = (vh - dh * scale) * ay;
 cropMatrix.setScale(scale, scale);
 cropMatrix.postTranslate(dx, dy);
 setImageMatrix(cropMatrix);
 }
 }

 static void show(MainActivity a, Cms.Site s, Cms.Video listing, Cms.Video ready) {
 if (a.redirectListing(s, listing)) return;

 a.clearPlaybackLoading();
 a.cancelArtwork();
 a.cancelPageRequests();
 if (a.current != null) a.current.home = false;
 a.stopWeb();
 a.stopMagnet();
 // 1.9.57：小窗/全屏浮层返回详情时保留正在播放的 player，不重建
 boolean keepPlayback = a.miniMode || a.expandedOverDetail;
 if (!keepPlayback) a.releasePlayer();
 if (!keepPlayback) a.generation++;

 // Edge-to-edge transparent status bar so the preview reaches near the physical top
 a.applyMobileSystemBars(false);

 final float density = a.getResources().getDisplayMetrics().density;
 final int widthDp = Math.round(a.windowWidth() / density);
 // 平板横向：左右两栏（52% 预览 + 48% 资料）；手机/竖屏：上下堆叠单列
 final boolean wide = widthDp >= 600;
 final int topPad = getStatusBarHeight(a);
 final int sidePad = a.dp(wide ? UiTokens.SP_24 : UiTokens.SP_16);
 final int contentTop = topPad + a.dp(wide ? 60 : 56);

 a.root = new FrameLayout(a);
 a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.installBackdrop(a.root);
 a.setContentView(a.root);

 // ---------------- 顶部：返回 / 片源 / 下载 / 收藏 ----------------
 LinearLayout topNavRow = a.row();
 topNavRow.setGravity(Gravity.CENTER_VERTICAL);
 FrameLayout.LayoutParams topNavLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
 topNavLp.setMargins(sidePad, topPad + a.dp(8), sidePad, 0);
 a.root.addView(topNavRow, topNavLp);
 a.root.post(() -> { try { topNavRow.bringToFront(); } catch (Throwable ignored) {} });

 TextView backBtn = a.text("‹", 22, Color.WHITE);
 backBtn.setTag("detail-back");
 backBtn.setGravity(Gravity.CENTER);
 backBtn.setPadding(0, 0, a.dp(1), a.dp(2));
 a.glass(backBtn, 999);
 bindTouchFeedback(backBtn);
 backBtn.setOnClickListener(v -> a.onBackPressed());
 topNavRow.addView(backBtn, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));

 View topSpacer = new View(a);
 topNavRow.addView(topSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

 final TextView sourceTopBtn = a.text("切换 片源…", 12.5f, Color.WHITE);
 sourceTopBtn.setTag("detail-source-picker");
 sourceTopBtn.setTypeface(null, Typeface.BOLD);
 sourceTopBtn.setGravity(Gravity.CENTER);
 sourceTopBtn.setSingleLine(true);
 sourceTopBtn.setEllipsize(TextUtils.TruncateAt.END);
 sourceTopBtn.setMaxWidth(a.dp(156));
 sourceTopBtn.setPadding(a.dp(14), 0, a.dp(14), 0);
 a.glass(sourceTopBtn, 999);
 bindTouchFeedback(sourceTopBtn);
 sourceTopBtn.setOnClickListener(v -> a.crossSearch(Cms.searchTitle(listing)));
 LinearLayout.LayoutParams sourceTopLp = new LinearLayout.LayoutParams(-2, a.dp(38));
 sourceTopLp.rightMargin = a.dp(8);
 topNavRow.addView(sourceTopBtn, sourceTopLp);

 final TextView downloadTopBtn = a.text("⇩", 16, Color.WHITE);
 downloadTopBtn.setTag("episodes-download");
 downloadTopBtn.setGravity(Gravity.CENTER);
 a.glass(downloadTopBtn, 999);
 bindTouchFeedback(downloadTopBtn);
 downloadTopBtn.setOnClickListener(v -> a.toast("正在加载剧集列表，请稍候…"));
 LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
 downloadLp.rightMargin = a.dp(8);
 topNavRow.addView(downloadTopBtn, downloadLp);

 final TextView favTopBtn = a.text("♡", 17, Color.WHITE);
 favTopBtn.setTag("detail-favorite");
 favTopBtn.setGravity(Gravity.CENTER);
 a.glass(favTopBtn, 999);
 bindTouchFeedback(favTopBtn);
 topNavRow.addView(favTopBtn, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));

 final Runnable syncFavStyle =
 () -> {
 boolean faved =
 Favorites.contains(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, listing.id);
 favTopBtn.setText(faved ? "♥" : "♡");
 favTopBtn.setTextColor(faved ? Skin.ACCENT_STRONG : a.themeColor(Color.WHITE));
 View actFav = a.root == null ? null : a.root.findViewWithTag("detail-act-favorite");
 if (actFav instanceof TextView)
 ((TextView) actFav).setText(faved ? " 已收藏" : " 收藏");
 };
 syncFavStyle.run();

 // ---------------- 右侧/下方资料列（两种布局共用） ----------------
 final LinearLayout lowerBody = a.vertical();

 final TextView titleView =
 a.text(
 listing.title,
 wide ? UiTokens.TYPE_DISPLAY : UiTokens.TYPE_HEADING,
 MainActivity.WHITE);
 titleView.setTag("detail-title");
 titleView.setTypeface(null, Typeface.BOLD);
 titleView.setMaxLines(2);
 titleView.setEllipsize(TextUtils.TruncateAt.END);
 titleView.setLineSpacing(a.dp(2), 1f);
 titleView.setShadowLayer(a.dp(10), 0, a.dp(3), 0x99000000);
 lowerBody.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

 final LinearLayout pillsRow = a.row();
 pillsRow.setGravity(Gravity.START);
 LinearLayout.LayoutParams pillsRowLp = new LinearLayout.LayoutParams(-1, -2);
 pillsRowLp.topMargin = a.dp(UiTokens.SP_12);
 lowerBody.addView(pillsRow, pillsRowLp);

 final TextView actorTv = a.text(actorLine(listing.actor), 12.5f, 0xffb9c7dd);
 actorTv.setTag("detail-actor");
 actorTv.setTag("detail-actors");
 actorTv.setMaxLines(2);
 actorTv.setEllipsize(TextUtils.TruncateAt.END);
 actorTv.setLineSpacing(a.dp(6), 1f);
 actorTv.setVisibility(actorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 LinearLayout.LayoutParams actorLp = new LinearLayout.LayoutParams(-1, -2);
 actorLp.topMargin = a.dp(UiTokens.SP_12);
 lowerBody.addView(actorTv, actorLp);

 final TextView directorTv = a.text(directorLine(listing.director), 12.5f, 0xffb9c7dd);
 directorTv.setTag("detail-director");
 directorTv.setTag("detail-director");
 directorTv.setMaxLines(2);
 directorTv.setEllipsize(TextUtils.TruncateAt.END);
 directorTv.setLineSpacing(a.dp(6), 1f);
 directorTv.setVisibility(directorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 LinearLayout.LayoutParams directorLp = new LinearLayout.LayoutParams(-1, -2);
 directorLp.topMargin = a.dp(5);
 lowerBody.addView(directorTv, directorLp);

 final Runnable[] toggleSynopsis = new Runnable[1];

 final LinearLayout synopsisWrap = a.vertical();
 synopsisWrap.setTag("detail-synopsis-wrap");
 LinearLayout.LayoutParams synopsisLp = new LinearLayout.LayoutParams(-1, -2);
 synopsisLp.topMargin = a.dp(UiTokens.SP_10);
 lowerBody.addView(synopsisWrap, synopsisLp);

 // ---------------- 操作胶囊行（横向滚动） ----------------
 HorizontalScrollView actionScroll = new HorizontalScrollView(a);
 actionScroll.setHorizontalScrollBarEnabled(false);
 actionScroll.setClipToPadding(false);
 LinearLayout actionRow = a.row();
 actionScroll.addView(actionRow);
 LinearLayout.LayoutParams actionScrollLp = new LinearLayout.LayoutParams(-1, -2);
 actionScrollLp.topMargin = a.dp(UiTokens.SP_16);
 lowerBody.addView(actionScroll, actionScrollLp);

 LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 actLp.rightMargin = a.dp(8);
 actionRow.addView(
 actionPill(a, "⌕ 快搜", v -> a.crossSearch(Cms.searchTitle(listing))), actLp);

 TextView orderAct = actionPill(a, "排序 倒序", v -> {});
 orderAct.setTag("detail-act-order");
 LinearLayout.LayoutParams orderLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 orderLp.rightMargin = a.dp(8);
 actionRow.addView(orderAct, orderLp);

 TextView downloadAct =
 actionPill(a, "⤓ 下载", v -> downloadTopBtn.performClick());
 downloadAct.setTag("detail-act-download");
 LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 dlLp.rightMargin = a.dp(8);
 actionRow.addView(downloadAct, dlLp);

 TextView favAct = actionPill(a, " 收藏", v -> favTopBtn.performClick());
 favAct.setTag("detail-act-favorite");
 LinearLayout.LayoutParams favActLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 favActLp.rightMargin = a.dp(8);
 actionRow.addView(favAct, favActLp);

 TextView copyAct = actionPill(a, " 复制", v -> copyTitle(a, listing));
 copyAct.setTag("detail-act-copy");
 actionRow.addView(copyAct, new LinearLayout.LayoutParams(-2, a.dp(34)));
 // 操作行里的收藏胶囊与顶部 状态同步
 syncFavStyle.run();

 // ---------------- 主播放按钮（粉色胶囊，B 段填充内容） ----------------
 final LinearLayout playBtnWrap = a.vertical();
 LinearLayout.LayoutParams playWrapLp = new LinearLayout.LayoutParams(-1, -2);
 playWrapLp.topMargin = a.dp(UiTokens.SP_16);
 lowerBody.addView(playBtnWrap, playWrapLp);

 // 1.8.3：详情页资源加载改用圆形帧动画（简洁模式）
 final FrameLayout loadingState = new FrameLayout(a);
 loadingState.setTag("detail-loading");
 WhaleLoadingView detailWhale = new WhaleLoadingView(a, WhaleLoadingView.SMALL, true);
 detailWhale.setText("正在加载剧集资源…");
 detailWhale.start();
 loadingState.addView(
 detailWhale, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 TextView loadingHint = a.text("", 12, MainActivity.MUTED);
 loadingHint.setGravity(Gravity.CENTER);
 FrameLayout.LayoutParams hintLp =
 new FrameLayout.LayoutParams(
 -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
 loadingState.addView(loadingHint, hintLp);
 lowerBody.addView(
 loadingState,
 new LinearLayout.LayoutParams(-1, a.dp(150)));

 // ---------------- 片源 / 线路列表（平板在左栏下方，手机在资料列里） ----------------
 LinearLayout lineSection = a.vertical();
 lineSection.setTag("detail-lines");
 // 1.9.322：整块“片源 · 线路”区块统一用卡片底
 lineSection.setBackground(a.cardBg(14));
 lineSection.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
 LinearLayout lineHeader = a.row();
 lineHeader.setGravity(Gravity.CENTER_VERTICAL);
 LinearLayout.LayoutParams lineHeaderLp = new LinearLayout.LayoutParams(-1, -2);
 lineHeaderLp.topMargin = a.dp(UiTokens.SP_16);
 lineHeaderLp.bottomMargin = a.dp(6);
 TextView lineTitle = a.text("片源 · 线路", 13, MainActivity.WHITE);
 lineTitle.setTypeface(null, Typeface.BOLD);
 lineHeader.addView(lineTitle, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView lineSite = a.text(s.name, 11, Skin.TEXT_TERTIARY_T);
 lineSite.setSingleLine(true);
 lineSite.setEllipsize(TextUtils.TruncateAt.END);
 lineHeader.addView(lineSite);
 lineSection.addView(lineHeader, lineHeaderLp);

 final LinearLayout lineList = a.vertical();
 lineList.setTag("detail-line-list");
 lineSection.addView(lineList, new LinearLayout.LayoutParams(-1, -2));

 final LinearLayout detailsBody = a.vertical();
 LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(-1, -2);
 detailsLp.topMargin = a.dp(UiTokens.SP_16);

 // ---------------- 预览卡：封面 + 中央播放钮 + 底部渐变 ----------------
 int contentW = a.windowWidth() - sidePad * 2 - a.dp(wide ? 20 : 0);
 int leftW = wide ? Math.round(contentW * 0.52f) : contentW;
 int previewH = Math.round(leftW * 9f / 16f);
 previewH = Math.max(a.dp(wide ? 240 : 180), Math.min(a.dp(wide ? 430 : 260), previewH));

 FrameLayout previewCard = new FrameLayout(a);
 GradientDrawable previewBg = new GradientDrawable();
 // 1.9.322：跟随用户卡片底色，不再写死 0xff0d1a2b
 previewBg.setColor((0xE6 << 24) | (Skin.cardFill & 0xffffff));
 previewBg.setCornerRadius(a.dp(wide ? 20 : 18));
 previewBg.setStroke(Math.max(1, a.dp(0.8f)), Skin.CARD_STROKE);
 previewCard.setBackground(previewBg);
 previewCard.setClipToOutline(true);
 previewCard.setTag("detail-hero");

 final ImageView posterHook = new ImageView(a);
 posterHook.setTag("detail-cover");
 posterHook.setScaleType(ImageView.ScaleType.CENTER_CROP);
 previewCard.addView(posterHook, new FrameLayout.LayoutParams(-1, -1));
 if (listing.poster != null && !listing.poster.isEmpty()) {
 a.poster(listing.poster, posterHook, true);
 }

 View previewScrim = new View(a);
 previewScrim.setBackground(
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x00000000, 0x44000000, 0xcc050b14}));
 FrameLayout.LayoutParams previewScrimLp = new FrameLayout.LayoutParams(-1, a.dp(150), Gravity.BOTTOM);
 previewCard.addView(previewScrim, previewScrimLp);

 TextView bigPlayHint = a.text("点击播放", 12, 0xd9ffffff);
 bigPlayHint.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
 FrameLayout.LayoutParams bigHintLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
 bigHintLp.setMargins(a.dp(16), 0, 0, a.dp(14));
 previewCard.addView(bigPlayHint, bigHintLp);

 TextView bigPlay = a.text("播放", 16, Skin.TEXT_PRIMARY_T);
 bigPlay.setTag("detail-play-big");
 bigPlay.setTypeface(null, Typeface.BOLD);
 bigPlay.setGravity(Gravity.CENTER);
 // 1.9.322：大块粉底 → 中性卡片底 + 细强调描边，粉色只做小面积强调
 GradientDrawable bigFill = new GradientDrawable();
 bigFill.setColor((0xE6 << 24) | (Skin.cardFill & 0xffffff));
 bigFill.setCornerRadius(a.dp(999));
 bigFill.setStroke(Math.max(1, a.dp(1.2f)), Skin.accentSoft());
 bigPlay.setBackground(bigFill);
 bigPlay.setElevation(a.dp(8));
 bindTouchFeedback(bigPlay);
 bigPlay.setOnClickListener(
 v -> {
 View pb = a.root.findViewWithTag("detail-play");
 if (pb != null) pb.performClick();
 });
 previewCard.addView(
 bigPlay, new FrameLayout.LayoutParams(a.dp(72), a.dp(72), Gravity.CENTER));

 // ---------------- 组装两套布局 ----------------
 if (wide) {
 LinearLayout bodyRow = a.row();
 bodyRow.setGravity(Gravity.TOP);
 FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(-1, -1);
 bodyLp.setMargins(sidePad, contentTop, sidePad, a.dp(UiTokens.SP_24));
 a.root.addView(bodyRow, bodyLp);

 LinearLayout leftCol = a.vertical();
 bodyRow.addView(leftCol, new LinearLayout.LayoutParams(0, -1, 0.52f));
 leftCol.addView(previewCard, new LinearLayout.LayoutParams(-1, previewH));
 View colGap = new View(a);
 leftCol.addView(colGap, new LinearLayout.LayoutParams(1, a.dp(16)));
 ScrollView lineScroll = new ScrollView(a);
 lineScroll.setVerticalScrollBarEnabled(false);
 lineScroll.setClipToPadding(false);
 lineScroll.addView(lineSection, new ScrollView.LayoutParams(-1, -2));
 leftCol.addView(lineScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

 View rowGap = new View(a);
 bodyRow.addView(rowGap, new LinearLayout.LayoutParams(a.dp(20), 1));

 lowerBody.addView(detailsBody, detailsLp);
 final ScrollView rightScroll = new ScrollView(a);
 rightScroll.setTag("detail-scroll");
 rightScroll.setFillViewport(true);
 rightScroll.setVerticalScrollBarEnabled(false);
 rightScroll.setClipToPadding(false);
 lowerBody.setPadding(0, 0, 0, a.dp(30));
 rightScroll.addView(lowerBody, new ScrollView.LayoutParams(-1, -2));
 bodyRow.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, 0.48f));
 a.scroll = rightScroll;
 } else {
 final ScrollView scrollView = new ScrollView(a);
 scrollView.setTag("detail-scroll");
 scrollView.setFillViewport(true);
 scrollView.setVerticalScrollBarEnabled(false);
 scrollView.setClipToPadding(false);
 a.scroll = scrollView;
 a.root.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));

 LinearLayout mainCol = a.vertical();
 mainCol.setPadding(sidePad, contentTop, sidePad, a.dp(34));
 scrollView.addView(mainCol, new ScrollView.LayoutParams(-1, -2));
 mainCol.addView(previewCard, new LinearLayout.LayoutParams(-1, previewH));
 View colGap = new View(a);
 mainCol.addView(colGap, new LinearLayout.LayoutParams(1, a.dp(16)));
 lowerBody.addView(lineSection, new LinearLayout.LayoutParams(-1, -2));
 lowerBody.addView(detailsBody, detailsLp);
 mainCol.addView(lowerBody, new LinearLayout.LayoutParams(-1, -2));
 }

 // Populate initial metadata pills from listing
 populateHeroPills(a, pillsRow, listing, 0);

 MainActivity.Done<Cms.Video> present =
 v -> {
 if (v.poster == null || v.poster.isEmpty()) v.poster = listing.poster;
 if (v.title == null || v.title.isEmpty()) v.title = listing.title;
 String shownTitle = v.title;
 if (shownTitle == null || shownTitle.trim().isEmpty() || "未命名影片".equals(shownTitle)) {
 if (listing != null && listing.title != null && !listing.title.trim().isEmpty())
 shownTitle = listing.title.trim();
 if (shownTitle == null
 || shownTitle.trim().isEmpty()
 || "未命名影片".equals(shownTitle)) shownTitle = "未命名影片";
 AppLog.i("Detail", "title fallback=" + shownTitle + " listing=" + (listing == null ? "-" : listing.title));
 }
 // 1.9.335：兜底片名回写到 video，播放器左上角才能显示正确片名
 if (shownTitle != null && !shownTitle.trim().isEmpty()) v.title = shownTitle.trim();
 titleView.setText(shownTitle);
 actorTv.setText(actorLine(v.actor));
 actorTv.setVisibility(
 actorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 directorTv.setText(directorLine(v.director));
 directorTv.setVisibility(
 directorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 TextView bigHint = null;
 View bigHintView = a.root == null ? null : a.root.findViewWithTag("detail-play-big");
 if (bigHintView instanceof TextView) bigHint = (TextView) bigHintView;
 if (bigHint != null) bigHint.setContentDescription("播放 " + v.title);
 if (!Objects.equals(v.poster, listing.poster)
 && v.poster != null
 && !v.poster.isEmpty()) {
 a.poster(v.poster, posterHook, true);
 }
 lowerBody.removeView(loadingState);
 synopsisWrap.removeAllViews();
 playBtnWrap.removeAllViews();
 detailsBody.removeAllViews();

 for (java.util.Iterator<Cms.Line> it = v.lines.iterator(); it.hasNext(); ) {
 if (it.next().episodes.isEmpty()) it.remove();
 }
 int firstLineCount =
 v.lines.isEmpty()
 ? 0
 : v.lines
 .get(SourcePreferences.lineIndex(a, v))
 .episodes
 .size();
 populateHeroPills(a, pillsRow, v, firstLineCount);

 favTopBtn.setOnClickListener(
 view -> {
 // 1.9.284：用「列表条目」作为收藏身份（站点+listing.id），避免 video.id 为空导致无法取消/不同步
 Cms.Video favVideo = new Cms.Video();
 favVideo.id = listing.id;
 favVideo.title = listing.title;
 favVideo.poster = listing.poster;
 String before = a.prefs.getString(Prefs.FAVORITES, "[]");
 boolean wasFaved = Favorites.contains(before, s.key, listing.id);
 String after = Favorites.toggle(before, s.key, favVideo);
 a.prefs.edit().putString(Prefs.FAVORITES, after).apply();
 AppLog.i("Detail", "fav click id=" + listing.id + " before=" + wasFaved + " after=" + Favorites.contains(after, s.key, listing.id));
 boolean faved = !wasFaved;
 favTopBtn.setText(faved ? "♥" : "♡");
 favTopBtn.setTextColor(faved ? Skin.ACCENT_STRONG : a.themeColor(Color.WHITE));
 View actFav =
 a.root == null
 ? null
 : a.root.findViewWithTag("detail-act-favorite");
 if (actFav instanceof TextView)
 ((TextView) actFav)
 .setText(faved ? " 已收藏" : " 收藏");
 a.toast(faved ? "已加入收藏片库" : "已从片库移除");
 });

 // Centered Expandable Synopsis
 String descRaw = v.description == null ? "" : v.description.trim();
 String castRaw =
 (v.actor != null && !v.actor.trim().isEmpty())
 ? "演职员：" + v.actor.trim()
 : "";
 if (!descRaw.isEmpty() || !castRaw.isEmpty()) {
 final TextView descTv =
 a.text(descRaw.isEmpty() ? castRaw : descRaw, 13, Skin.TEXT_SECONDARY_T);
 descTv.setTag("detail-synopsis");
 descTv.setGravity(Gravity.START);
 descTv.setLineSpacing(a.dp(4), 1.45f);
 descTv.setMaxLines(4);
 descTv.setEllipsize(TextUtils.TruncateAt.END);
 synopsisWrap.addView(descTv, new LinearLayout.LayoutParams(-1, -2));

 final TextView castTv = a.text(castRaw, 11.5f, Skin.TEXT_TERTIARY_T);
 castTv.setGravity(Gravity.START);
 castTv.setPadding(0, a.dp(6), 0, 0);
 castTv.setMaxLines(3);
 castTv.setEllipsize(TextUtils.TruncateAt.END);
 castTv.setVisibility(View.GONE);
 if (!descRaw.isEmpty() && !castRaw.isEmpty()) {
 synopsisWrap.addView(castTv, new LinearLayout.LayoutParams(-1, -2));
 }

 final boolean[] expanded = {false};
 toggleSynopsis[0] =
 () -> {
 expanded[0] = !expanded[0];
 descTv.setMaxLines(expanded[0] ? 18 : 4);
 View actSyn =
 a.root == null
 ? null
 : a.root.findViewWithTag(
 "detail-act-synopsis");
 if (actSyn instanceof TextView)
 ((TextView) actSyn)
 .setText(expanded[0] ? " 收起" : " 简介");
 if (!descRaw.isEmpty() && !castRaw.isEmpty()) {
 castTv.setVisibility(
 expanded[0] ? View.VISIBLE : View.GONE);
 }
 };
 synopsisWrap.setOnClickListener(view -> toggleSynopsis[0].run());
 }

 if (v.lines.isEmpty()) {
 sourceTopBtn.setText("切换 " + s.name);
 TextView empty = a.text("暂未获取到可播放的剧集资源", 14, 0xb3ffffff);
 empty.setGravity(Gravity.CENTER);
 empty.setPadding(a.dp(4), a.dp(20), a.dp(4), a.dp(20));
 detailsBody.addView(empty, new LinearLayout.LayoutParams(-1, -2));
 return;
 }

 WatchHistory.Entry bookmark = null;
 for (WatchHistory.Entry entry : a.watchHistory()) {
 if (entry.site.equals(s.key) && entry.id.equals(v.id)) {
 bookmark = entry;
 break;
 }
 }
 // 1.8.0 跨源续播：当前源没有记录时按影片名+集名匹配其它源最近进度
 if (bookmark == null && v.title != null && !v.title.trim().isEmpty()) {
 String title = v.title.trim();
 for (WatchHistory.Entry entry : a.watchHistory()) {
 if (entry.title == null || !title.equals(entry.title.trim())) continue;
 if (bookmark == null || entry.updated > bookmark.updated) bookmark = entry;
 }
 }
 final WatchHistory.Entry remembered = bookmark;

 // Full-Width White Capsule Play Button (matches iPhone `播放 · 第 1 季 · E01`)
 SeasonMeta curSeason = parseSeasonMeta(v.title);
 EpisodeDisplay firstDisp =
 parseEpisodeDisplay(
 v.lines
 .get(SourcePreferences.lineIndex(a, v))
 .episodes
 .get(0)
 .name,
 1);
 String playLabel;
 if (remembered != null) {
 EpisodeDisplay remDisp = parseEpisodeDisplay(remembered.episode, 1);
 int resumePos = remembered.resume();
 playLabel =
 resumePos > 0
 ? "播放 · "
 + remDisp.cleanTitle
 + " · 继续上次位置 · "
 + PlaybackController.formatTime(resumePos)
 : "播放 · " + remDisp.cleanTitle;
 } else if (v.lines.get(SourcePreferences.lineIndex(a, v)).episodes.size() > 1) {
 playLabel =
 "播放 · " + curSeason.seasonLabel + " · " + firstDisp.cleanTitle;
 } else {
 playLabel = "播放 · " + firstDisp.cleanTitle;
 }

 Button playBtn = new Button(a);
 playBtn.setTag("detail-play");
 playBtn.setAllCaps(false);
 playBtn.setSingleLine(true);
 playBtn.setMaxLines(1);
 playBtn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
 playBtn.setGravity(Gravity.CENTER);
 playBtn.setIncludeFontPadding(false);
 playBtn.setTextSize(14);
 playBtn.setTypeface(null, Typeface.BOLD);
 playBtn.setTextColor(Skin.TEXT_PRIMARY_T);
 // 1.9.322：主播放按钮改中性卡片底 + ACCENT_SOFT 细描边（不再大块粉色渐变）
 GradientDrawable playFill = new GradientDrawable();
 playFill.setColor((0xE6 << 24) | (Skin.cardFill & 0xffffff));
 playFill.setCornerRadius(a.dp(999));
 playFill.setStroke(Math.max(1, a.dp(1.2f)), Skin.accentSoft());
 playBtn.setBackground(playFill);
 playBtn.setElevation(a.dp(4));
 playBtn.setPadding(a.dp(18), 0, a.dp(18), 0);
 bindTouchFeedback(playBtn);
 playBtn.setText(playLabel);
 playBtn.setOnClickListener(
 view -> {
 if (remembered != null) a.playBookmark(s, v, remembered);
 else
 a.resolvePlayer(
 s,
 v,
 v.lines.get(SourcePreferences.lineIndex(a, v)),
 v.lines
 .get(SourcePreferences.lineIndex(a, v))
 .episodes
 .get(0));
 });
 playBtnWrap.setGravity(Gravity.START);
 int playWidth = Math.min(a.dp(320), a.windowWidth() - a.dp(48));
 playBtnWrap.addView(
 playBtn, new LinearLayout.LayoutParams(playWidth, a.dp(46)));

 if (remembered != null) {
 EpisodeDisplay bmDisp = parseEpisodeDisplay(remembered.episode, 1);
 String bmSummary =
 remembered.position > 0
 ? "已观看 ["
 + bmDisp.cleanTitle
 + "] 至 "
 + PlaybackController.formatTime(remembered.position)
 + " · 线路："
 + remembered.line
 : "已观看 [" + bmDisp.cleanTitle + "] · 线路：" + remembered.line;
 TextView bmInfo = a.text(bmSummary, 11.5f, Skin.TEXT_TERTIARY_T);
 bmInfo.setSingleLine(true);
 bmInfo.setEllipsize(TextUtils.TruncateAt.MIDDLE);
 bmInfo.setGravity(Gravity.START);
 bmInfo.setPadding(0, a.dp(8), 0, 0);
 playBtnWrap.addView(bmInfo, new LinearLayout.LayoutParams(-1, -2));
 }

 int initLine = SourcePreferences.lineIndex(a, v), initPage = 0;
 if (remembered != null) {
 for (int i = 0; i < v.lines.size(); i++) {
 if (v.lines.get(i).name.equals(remembered.line)) {
 initLine = i;
 for (int n = 0; n < v.lines.get(i).episodes.size(); n++) {
 if (v.lines
 .get(i)
 .episodes
 .get(n)
 .name
 .equals(remembered.episode)) {
 boolean desc =
 a.prefs.getBoolean(
 Prefs.EPISODES_DESCENDING, false);
 initPage =
 (desc ? v.lines.get(i).episodes.size() - 1 - n : n)
 / 50;
 break;
 }
 }
 break;
 }
 }
 }

 LinearLayout episodePanel = a.vertical();
 episodePanel.setTag("detail-episodes");
 detailsBody.addView(episodePanel, new LinearLayout.LayoutParams(-1, -2));

 final Map<Integer, Cms.Video> discoveredSeasons = new TreeMap<>();
 final Map<Integer, String> discoveredLabels = new TreeMap<>();
 discoveredSeasons.put(curSeason.seasonNumber, v);
 discoveredLabels.put(curSeason.seasonNumber, curSeason.seasonLabel);

 // 片源 / 线路列表：选中项 2dp 樱花粉短线 + 白字，点击切换线路并重排剧集
 final int startLineIdx = initLine;
 final int[] selLine = {startLineIdx};
 final PlayerBubble.IntAction[] linePick = new PlayerBubble.IntAction[1];
 linePick[0] =
 idx -> {
 selLine[0] = idx;
 renderLineList(a, lineList, v, idx, linePick[0]);
 renderMobileEpisodes(
 a,
 s,
 v,
 idx,
 0,
 episodePanel,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 };
 renderLineList(a, lineList, v, startLineIdx, linePick[0]);
 View orderAction =
 a.root == null ? null : a.root.findViewWithTag("detail-act-order");
 if (orderAction != null) {
 orderAction.setOnClickListener(
 vw -> {
 boolean nowDescending =
 a.prefs.getBoolean(
 Prefs.EPISODES_DESCENDING, false);
 a.prefs
 .edit()
 .putBoolean(
 Prefs.EPISODES_DESCENDING, !nowDescending)
 .apply();
 renderMobileEpisodes(
 a,
 s,
 v,
 selLine[0],
 0,
 episodePanel,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 });
 }

 renderMobileEpisodes(
 a,
 s,
 v,
 selLine[0],
 initPage,
 episodePanel,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 populateRelatedSeasons(
 a,
 s,
 v,
 episodePanel,
 initLine,
 initPage,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 };

 if (ready == null) a.loadDetail(s, listing.id, present);
 else {
 try {
 present.accept(ready);
 } catch (Exception e) {
 AppLog.w("MobileDetailScreen", "present detail failed", e);
 }
 }
 }

 private static String actorLine(String actor) {
 if (actor == null) return "";
 String text = actor.trim();
 return text.isEmpty() ? "" : "演员：" + text;
 }

 private static String directorLine(String director) {
 if (director == null) return "";
 String text = director.trim();
 return text.isEmpty() ? "" : "导演：" + text;
 }

 /** 资料区的操作胶囊：玻璃底 + 胶囊圆角，与电视台页按钮同色系。 */
 private static TextView actionPill(
 MainActivity a, String label, View.OnClickListener click) {
 TextView t = a.text(label, 12, a.cardInk());
 t.setSingleLine(true);
 t.setGravity(Gravity.CENTER);
 t.setPadding(a.dp(14), 0, a.dp(14), 0);
 t.setBackground(glassBg(a, 0x14ffffff, 0x2effffff, 999));
 bindTouchFeedback(t);
 t.setOnClickListener(click);
 return t;
 }

 private static void copyTitle(MainActivity a, Cms.Video video) {
 try {
 StringBuilder text = new StringBuilder();
 if (video.title != null) text.append(video.title);
 if (video.year != null && !video.year.trim().isEmpty())
 text.append(" · ").append(video.year.trim());
 if (video.area != null && !video.area.trim().isEmpty())
 text.append(" · ").append(video.area.trim());
 if (video.genre != null && !video.genre.trim().isEmpty())
 text.append(" · ").append(video.genre.trim());
 android.content.ClipboardManager clipboard =
 (android.content.ClipboardManager)
 a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
 if (clipboard == null) return;
 clipboard.setPrimaryClip(
 android.content.ClipData.newPlainText("片名", text.toString()));
 a.toast("已复制片名与元信息");
 } catch (Throwable error) {
 a.toast("复制失败");
 }
 }

 /**
 * 片源 / 线路列表：选中项白字 + 2dp 樱花粉短线，未选中 #8D9CB6；点击切换线路并重排剧集。
 */
 private static void renderLineList(
 MainActivity a,
 LinearLayout list,
 Cms.Video video,
 int selected,
 PlayerBubble.IntAction onPick) {
 list.removeAllViews();
 if (video == null || video.lines == null) return;
 for (int i = 0; i < video.lines.size(); i++) {
 final int index = i;
 Cms.Line line = video.lines.get(i);
 boolean on = i == selected;
 LinearLayout row = a.vertical();
 row.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
 // 1.9.322：未选中=卡片底，选中=同底 + ACCENT_SOFT 描边
 row.setBackground(chipBg(a, 12f, on));
 bindTouchFeedback(row);

 LinearLayout top = a.row();
 top.setGravity(Gravity.CENTER_VERTICAL);
 TextView name = a.text(line.name, on ? 13.5f : 13, a.cardInk());
 name.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
 name.setSingleLine(true);
 name.setEllipsize(TextUtils.TruncateAt.END);
 top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView count =
 a.text(line.episodes == null ? "" : line.episodes.size() + " 集", 10.5f, 0xff6f7f99);
 top.addView(count);
 row.addView(top, new LinearLayout.LayoutParams(-1, -2));

 if (on) {
 View underline = new View(a);
 GradientDrawable lineBg = new GradientDrawable();
 lineBg.setColor(0xff000000 | (Skin.cardAccent & 0xffffff));
 lineBg.setCornerRadius(a.dp(1));
 underline.setBackground(lineBg);
 LinearLayout.LayoutParams underlineLp =
 new LinearLayout.LayoutParams(a.dp(28), a.dp(2));
 underlineLp.topMargin = a.dp(5);
 row.addView(underline, underlineLp);
 }
 if (onPick != null) {
 row.setOnClickListener(v -> onPick.accept(index));
 }
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.bottomMargin = a.dp(6);
 list.addView(row, lp);
 }
 }

 private static void populateHeroPills(
 MainActivity a, LinearLayout pillsRow, Cms.Video v, int epCount) {
 pillsRow.removeAllViews();
 List<String> tags = new ArrayList<>();
 if (v.year != null && !v.year.trim().isEmpty()) tags.add(v.year.trim());
 if (v.area != null && !v.area.trim().isEmpty()) tags.add(v.area.trim());
 if (v.tag != null && !v.tag.trim().isEmpty()) tags.add(v.tag.trim());
 if (v.remarks != null && !v.remarks.trim().isEmpty()) {
 tags.add(v.remarks.trim());
 } else if (epCount > 1) {
 tags.add("共" + epCount + "集");
 }
 int limit = Math.min(4, tags.size());
 for (int i = 0; i < limit; i++) {
 TextView pill = a.text(tags.get(i), 11.5f, 0xffd9e3f2);
 pill.setTypeface(null, Typeface.BOLD);
 pill.setSingleLine(true);
 pill.setPadding(a.dp(11), a.dp(3), a.dp(11), a.dp(3));
 a.glass(pill, 999);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 if (i > 0) lp.leftMargin = a.dp(8);
 pillsRow.addView(pill, lp);
 }
 // 1.9.342：简介按钮放在「共N集」右侧，点击隐藏/显示下方简介
 TextView syn = a.text(" 简介", 11.5f, 0xffd9e3f2);
 syn.setTypeface(null, Typeface.BOLD);
 syn.setPadding(a.dp(11), a.dp(3), a.dp(11), a.dp(3));
 a.glass(syn, 999);
 syn.setTag("detail-act-synopsis");
 syn.setOnClickListener(
 view -> {
 if (a.root == null) return;
 View wrap = a.root.findViewWithTag("detail-synopsis-wrap");
 View actor = a.root.findViewWithTag("detail-actor");
 View director = a.root.findViewWithTag("detail-director");
 boolean hide = wrap == null || wrap.getVisibility() == View.VISIBLE;
 int vis = hide ? View.GONE : View.VISIBLE;
 if (wrap != null) wrap.setVisibility(vis);
 if (actor != null) actor.setVisibility(vis);
 if (director != null) director.setVisibility(vis);
 syn.setText(hide ? " 展开简介" : " 收起简介");
 });
 LinearLayout.LayoutParams synLp = new LinearLayout.LayoutParams(-2, -2);
 synLp.leftMargin = a.dp(8);
 pillsRow.addView(syn, synLp);
 }

 static void renderMobileEpisodes(
 MainActivity a,
 Cms.Site site,
 Cms.Video video,
 int lineIndex,
 int page,
 LinearLayout panel,
 WatchHistory.Entry remembered,
 Map<Integer, Cms.Video> discoveredSeasons,
 Map<Integer, String> discoveredLabels) {
 if (a.current != null) {
 a.current.selection.select(
 video.lines.get(lineIndex).name,
 page,
 video.lines.get(lineIndex).episodes.size());
 }
 panel.removeAllViews();

 final Cms.Line line = video.lines.get(lineIndex);
 panel.addView(
 a.navButton(
 "默认播放线路", "line-preferred", () -> SourcePreferences.chooseLine(a, video)));
 final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
 final SeasonMeta curSeason = parseSeasonMeta(video.title);
 final int total = line.episodes.size();

 // Update Top-Right Floating Source Button, Play Button & Download Button
 if (a.root != null) {
 View playBtnView = a.root.findViewWithTag("detail-play");
 if (playBtnView instanceof Button && !line.episodes.isEmpty()) {
 Button playBtn = (Button) playBtnView;
 playBtn.setOnClickListener(
 v -> {
 if (remembered != null && line.name.equals(remembered.line)) {
 a.playBookmark(site, video, remembered);
 } else {
 a.resumeAt = 0;
 a.resolvePlayer(site, video, line, line.episodes.get(0));
 }
 });
 }
 View topSourceView = a.root.findViewWithTag("detail-source-picker");
 if (topSourceView instanceof TextView) {
 TextView sourceTopBtn = (TextView) topSourceView;
 sourceTopBtn.setText("切换 " + line.name + " ");
 sourceTopBtn.setOnClickListener(
 v -> {
 String[] names = new String[video.lines.size()];
 for (int i = 0; i < video.lines.size(); i++) {
 Cms.Line l = video.lines.get(i);
 names[i] =
 l.name
 + " (共 "
 + l.episodes.size()
 + " 集 · "
 + site.name
 + ")";
 }
 new AppDialog.Builder(a)
 .wrapChoices()
 .setTitle("切换播放源 (" + video.lines.size() + ")")
 .setSingleChoiceItems(
 names,
 lineIndex,
 (dialog, which) -> {
 dialog.dismiss();
 renderMobileEpisodes(
 a,
 site,
 video,
 which,
 0,
 panel,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 })
 .setNeutralButton(
 "🔍 跨片源搜索",
 (dialog, which) ->
 a.crossSearch(Cms.searchTitle(video)))
 .setNegativeButton("取消", null)
 .show();
 });
 }
 View topDownView = a.root.findViewWithTag("episodes-download");
 if (topDownView != null) {
 String pickFrom = "";
 if (remembered != null && line.name.equals(remembered.line)) {
 pickFrom = remembered.episode;
 }
 final String currentName = pickFrom;
 topDownView.setOnClickListener(
 v -> MobileDownloads.pick(a, site, video, line, currentName));
 }
 }

 // 1. Header Row:
 // Left: Independent Season Selector Button (`第 X 季 `)
 // Right: `[ ↑ 正序 ]` + `[ ⊞ 全部剧集 (N) ]`
 LinearLayout headerRow = a.row();
 headerRow.setGravity(Gravity.CENTER_VERTICAL);
 panel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

 LinearLayout seasonBtn = a.row();
 seasonBtn.setTag("detail-season-picker");
 seasonBtn.setGravity(Gravity.CENTER_VERTICAL);
 bindTouchFeedback(seasonBtn);

 TextView seasonTitle = a.text(curSeason.seasonLabel, 18, Color.WHITE);
 seasonTitle.setTypeface(null, Typeface.BOLD);
 seasonBtn.addView(seasonTitle);

 if (discoveredSeasons.size() > 1) {
 TextView countBadge = a.text("共" + discoveredSeasons.size() + "季", 10.5f, 0xf2ffffff);
 countBadge.setTypeface(null, Typeface.BOLD);
 countBadge.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
 countBadge.setBackground(a.bg(0x38ffffff, 999));
 LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-2, -2);
 cbLp.leftMargin = a.dp(6);
 seasonBtn.addView(countBadge, cbLp);
 }

 TextView seasonChevron = a.text(" ", 16, 0xccffffff);
 seasonChevron.setTypeface(null, Typeface.BOLD);
 seasonBtn.addView(seasonChevron);

 seasonBtn.setOnClickListener(
 v ->
 showSeasonPickerDialog(
 a, site, video, curSeason, discoveredSeasons, discoveredLabels));
 headerRow.addView(seasonBtn, new LinearLayout.LayoutParams(0, -2, 1f));

 // Sort Pill (`↑ 正序` / `↓ 倒序`)
 TextView sortBtn = a.text(descending ? "↓ 倒序" : "↑ 正序", 12, 0xe6ffffff);
 sortBtn.setTag("episodes-order");
 sortBtn.setTypeface(null, Typeface.BOLD);
 sortBtn.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
 sortBtn.setBackground(glassBg(a, 0x30ffffff, 0x30ffffff, 999));
 bindTouchFeedback(sortBtn);
 sortBtn.setOnClickListener(
 v -> {
 a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, !descending).apply();
 renderMobileEpisodes(
 a,
 site,
 video,
 lineIndex,
 0,
 panel,
 remembered,
 discoveredSeasons,
 discoveredLabels);
 });
 LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(-2, -2);
 sortLp.rightMargin = a.dp(8);
 headerRow.addView(sortBtn, sortLp);

 // All Episodes Grid Pill (`⊞ 全部剧集 (N)`)
 TextView allGridBtn = a.text("⊞ 全部剧集 (" + total + ")", 12, 0xe6ffffff);
 allGridBtn.setTag("episodes-all-grid");
 allGridBtn.setTypeface(null, Typeface.BOLD);
 allGridBtn.setPadding(a.dp(11), a.dp(5), a.dp(11), a.dp(5));
 allGridBtn.setBackground(glassBg(a, 0x30ffffff, 0x30ffffff, 999));
 bindTouchFeedback(allGridBtn);
 allGridBtn.setOnClickListener(
 v -> showAllEpisodesDialog(a, site, video, line, descending, remembered));
 headerRow.addView(allGridBtn);

 // 1b. Direct Multi-Season Selector Strip (visible whenever >= 2 seasons exist)
 LinearLayout seasonStripWrap = a.vertical();
 seasonStripWrap.setTag("detail-seasons");
 panel.addView(seasonStripWrap, new LinearLayout.LayoutParams(-1, -2));
 if (discoveredSeasons.size() > 1) {
 renderSeasonStrip(
 a,
 site,
 video,
 curSeason,
 seasonStripWrap,
 discoveredSeasons,
 discoveredLabels);
 }

 // Determine current active episode index
 int activeEpIdx = 0;
 if (remembered != null && line.name.equals(remembered.line)) {
 for (int i = 0; i < total; i++) {
 if (line.episodes.get(i).name.equals(remembered.episode)) {
 activeEpIdx = i;
 break;
 }
 }
 }

 int start = page * 50;
 int end = Math.min(start + 50, total);

 // 2. 紧凑剧集网格：每行 2 个 46dp 胶囊（编号 + 集名；当前集粉底白字）
 a.gap(panel, 10);
 final int epColumns = 2;
 LinearLayout gridRow = null;
 for (int i = start; i < end; i++) {
 final int posIdx = descending ? (total - 1 - i) : i;
 final Cms.Episode ep = line.episodes.get(posIdx);
 boolean isCurrent = posIdx == activeEpIdx;
 EpisodeDisplay disp = parseEpisodeDisplay(ep.name, posIdx + 1);

 int slot = i - start;
 if (slot % epColumns == 0) {
 gridRow = a.row();
 LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
 if (slot != 0) rowLp.topMargin = a.dp(8);
 panel.addView(gridRow, rowLp);
 }

 LinearLayout cell = a.row();
 cell.setTag("episode:" + line.name + ":" + posIdx);
 cell.setGravity(Gravity.CENTER_VERTICAL);
 cell.setPadding(a.dp(12), 0, a.dp(10), 0);
 GradientDrawable cellBg = new GradientDrawable();
 if (isCurrent) {
 // 1.9.322：当前集=卡片底 + ACCENT_SOFT 描边，不再整块粉
 cellBg = chipBg(a, 14f, true);
 } else {
 cellBg = chipBg(a, 14f, false);
 }
 cellBg.setCornerRadius(a.dp(14));
 cell.setBackground(cellBg);
 bindTouchFeedback(cell);
 a.focusableForRemote(cell, 14);
 cell.setOnClickListener(
 v -> {
 a.resumeAt = 0;
 a.resolvePlayer(site, video, line, ep);
 });
 cell.setOnLongClickListener(
 v -> {
 MobileDownloads.enqueue(
 a, site, video, line, Collections.singletonList(ep));
 return true;
 });

 TextView num =
 a.text(
 String.format(Locale.US, "%02d", posIdx + 1),
 11.5f,
 a.cardInk());
 num.setTypeface(null, Typeface.BOLD);
 num.setGravity(Gravity.CENTER);
 num.setMinWidth(a.dp(24));
 cell.addView(num, new LinearLayout.LayoutParams(-2, -2));

 TextView nameTv =
 a.text(disp.cleanTitle, 12.5f, a.cardInk());
 nameTv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
 nameTv.setSingleLine(true);
 nameTv.setEllipsize(TextUtils.TruncateAt.END);
 LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
 nameLp.leftMargin = a.dp(8);
 cell.addView(nameTv, nameLp);

 if (disp.specBadge != null && !disp.specBadge.isEmpty()) {
 TextView badge =
 a.text(
 disp.specBadge,
 9.5f,
 isCurrent ? Skin.TEXT_SECONDARY_T : 0xff7f8fa9);
 badge.setSingleLine(true);
 badge.setEllipsize(TextUtils.TruncateAt.END);
 LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
 badgeLp.leftMargin = a.dp(6);
 cell.addView(badge, badgeLp);
 }

 LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, a.dp(46), 1f);
 if (slot % epColumns != 0) cellLp.leftMargin = a.dp(8);
 gridRow.addView(cell, cellLp);

 if (i == end - 1 && (end - start) % epColumns != 0) {
 View filler = new View(a);
 LinearLayout.LayoutParams fillerLp = new LinearLayout.LayoutParams(0, 1, 1f);
 fillerLp.leftMargin = a.dp(8);
 gridRow.addView(filler, fillerLp);
 }
 }

 // 4. Range Capsules (`1 · 50`, `51 · 100` ...) when > 50 episodes
 int groupCount = (total + 49) / 50;
 if (groupCount > 1) {
 a.gap(panel, 12);
 HorizontalScrollView groupScroll = new HorizontalScrollView(a);
 groupScroll.setHorizontalScrollBarEnabled(false);
 LinearLayout groupRow = a.row();
 groupScroll.addView(groupRow);
 for (int g = 0; g < groupCount; g++) {
 final int targetGroup = g;
 boolean selectedGroup = g == page;
 int gStart = g * 50 + 1;
 int gEnd = Math.min(total, (g + 1) * 50);
 String label =
 descending
 ? (total - g * 50) + " · " + Math.max(1, total - gEnd + 1)
 : gStart + " · " + gEnd;
 TextView tab = a.text(label, 12.5f, Skin.TEXT_PRIMARY_T);
 tab.setTypeface(null, Typeface.BOLD);
 tab.setPadding(a.dp(15), a.dp(6), a.dp(15), a.dp(6));
 // 1.9.322：范围胶囊同样走卡片底/选中描边工厂
 tab.setBackground(chipBg(a, 999f, selectedGroup));
 bindTouchFeedback(tab);
 tab.setOnClickListener(
 v ->
 renderMobileEpisodes(
 a,
 site,
 video,
 lineIndex,
 targetGroup,
 panel,
 remembered,
 discoveredSeasons,
 discoveredLabels));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.rightMargin = a.dp(8);
 groupRow.addView(tab, lp);
 }
 panel.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));
 }
 }

 private static void showSeasonPickerDialog(
 MainActivity a,
 Cms.Site site,
 Cms.Video currentVideo,
 SeasonMeta curSeason,
 Map<Integer, Cms.Video> discoveredSeasons,
 Map<Integer, String> discoveredLabels) {
 final List<Integer> seasonNums = new ArrayList<>(discoveredSeasons.keySet());
 String[] items = new String[seasonNums.size()];
 int checkedIdx = 0;
 for (int i = 0; i < seasonNums.size(); i++) {
 int sNum = seasonNums.get(i);
 Cms.Video vid = discoveredSeasons.get(sNum);
 String sLabel = discoveredLabels.get(sNum);
 String rem =
 (vid != null && vid.remarks != null && !vid.remarks.trim().isEmpty())
 ? " · " + vid.remarks.trim()
 : "";
 items[i] = sLabel + " (" + (vid != null ? vid.title : currentVideo.title) + rem + ")";
 if ((vid != null && Objects.equals(vid.id, currentVideo.id))
 || sNum == curSeason.seasonNumber) {
 checkedIdx = i;
 }
 }
 String title = seasonNums.size() > 1 ? "选择季数（共 " + seasonNums.size() + " 季）" : "选择季数";
 new AlertDialog.Builder(a)
 .setTitle(title)
 .setSingleChoiceItems(
 items,
 checkedIdx,
 (dialog, which) -> {
 dialog.dismiss();
 int sNum = seasonNums.get(which);
 Cms.Video target = discoveredSeasons.get(sNum);
 if (target != null && !Objects.equals(target.id, currentVideo.id)) {
 show(a, site, target, null);
 }
 })
 .setNegativeButton("关闭", null)
 .show();
 }

 private static void showAllEpisodesDialog(
 MainActivity a,
 Cms.Site site,
 Cms.Video video,
 Cms.Line line,
 boolean descending,
 WatchHistory.Entry remembered) {
 int total = line.episodes.size();
 ScrollView sv = new ScrollView(a);
 sv.setVerticalScrollBarEnabled(false);
 LinearLayout grid = a.vertical();
 grid.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(16));
 sv.addView(grid, new ScrollView.LayoutParams(-1, -2));

 final AlertDialog dialog =
 new AlertDialog.Builder(a)
 .setTitle("全部剧集 · " + line.name + " (" + total + ")")
 .setView(sv)
 .setNegativeButton("关闭", null)
 .create();

 int cols = 3;
 LinearLayout currentRow = null;
 for (int i = 0; i < total; i++) {
 if (i % cols == 0) {
 currentRow = a.row();
 grid.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
 }
 final int posIdx = descending ? (total - 1 - i) : i;
 final Cms.Episode ep = line.episodes.get(posIdx);
 boolean isCurrent =
 remembered != null
 && line.name.equals(remembered.line)
 && ep.name.equals(remembered.episode);
 EpisodeDisplay disp = parseEpisodeDisplay(ep.name, posIdx + 1);
 TextView cell = a.text(disp.cleanTitle, 13, a.cardInk());
 cell.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
 cell.setGravity(Gravity.CENTER);
 cell.setSingleLine(true);
 cell.setEllipsize(TextUtils.TruncateAt.END);
 cell.setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10));
 // 1.9.322：全部剧集格同样走卡片底/选中描边工厂
 cell.setBackground(chipBg(a, 10f, isCurrent));
 bindTouchFeedback(cell);
 cell.setOnClickListener(
 v -> {
 dialog.dismiss();
 a.resumeAt = 0;
 a.resolvePlayer(site, video, line, ep);
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
 lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 currentRow.addView(cell, lp);
 }
 if (currentRow != null && total % cols != 0) {
 for (int pad = total % cols; pad < cols; pad++) {
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
 lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 currentRow.addView(new View(a), lp);
 }
 }
 dialog.show();
 }

 private static void renderSeasonStrip(
 MainActivity a,
 Cms.Site site,
 Cms.Video currentVideo,
 SeasonMeta curMeta,
 LinearLayout seasonStripWrap,
 Map<Integer, Cms.Video> bySeason,
 Map<Integer, String> labels) {
 seasonStripWrap.removeAllViews();
 a.gap(seasonStripWrap, 11);
 HorizontalScrollView hsv = new HorizontalScrollView(a);
 hsv.setHorizontalScrollBarEnabled(false);
 LinearLayout row = a.row();
 hsv.addView(row);
 seasonStripWrap.addView(hsv, new LinearLayout.LayoutParams(-1, -2));

 for (final Integer sNum : bySeason.keySet()) {
 final Cms.Video targetVid = bySeason.get(sNum);
 String sLabel = labels.get(sNum);
 boolean active =
 (targetVid != null && Objects.equals(targetVid.id, currentVideo.id))
 || sNum == curMeta.seasonNumber;
 TextView pill = a.text(sLabel, 12.5f, Skin.TEXT_PRIMARY_T);
 pill.setTag("detail-season:" + sNum);
 pill.setTypeface(null, Typeface.BOLD);
 pill.setPadding(a.dp(14), a.dp(5), a.dp(14), a.dp(5));
 // 1.9.322：季 chip 同一工厂：卡片底 + 选中 ACCENT_SOFT 描边
 pill.setBackground(chipBg(a, 999f, active));
 bindTouchFeedback(pill);
 pill.setOnClickListener(
 v -> {
 if (targetVid != null && !Objects.equals(targetVid.id, currentVideo.id)) {
 show(a, site, targetVid, null);
 }
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
 lp.rightMargin = a.dp(8);
 row.addView(pill, lp);
 }
 }

 static final class SeasonMeta {
 final String baseTitle;
 final int seasonNumber;
 final String seasonLabel;
 final boolean explicit;

 SeasonMeta(String baseTitle, int seasonNumber, String seasonLabel, boolean explicit) {
 this.baseTitle = baseTitle;
 this.seasonNumber = seasonNumber;
 this.seasonLabel = seasonLabel;
 this.explicit = explicit;
 }
 }

 static int parseChineseNumber(String raw) {
 if (raw == null) return 1;
 String s = raw.trim();
 if (s.isEmpty()) return 1;
 try {
 int d = Integer.parseInt(s);
 if (d > 0) return d;
 } catch (NumberFormatException ignored) {
 }
 String digits = "零一二三四五六七八九";
 if ("十".equals(s) || "拾".equals(s)) return 10;
 if (s.contains("十") || s.contains("拾")) {
 String[] parts = s.split("[十拾]", -1);
 int tens = parts[0].isEmpty() ? 1 : Math.max(1, digits.indexOf(parts[0].charAt(0)));
 int ones =
 (parts.length > 1 && !parts[1].isEmpty())
 ? Math.max(0, digits.indexOf(parts[1].charAt(0)))
 : 0;
 int val = tens * 10 + ones;
 return val > 0 ? val : 1;
 }
 int acc = 0;
 for (int i = 0; i < s.length(); i++) {
 char c = s.charAt(i);
 int idx = digits.indexOf(c);
 if (c == '两') idx = 2;
 if (idx >= 0) acc = acc * 10 + idx;
 }
 return acc > 0 ? acc : 1;
 }

 static SeasonMeta parseSeasonMeta(String rawTitle) {
 String t = rawTitle == null ? "" : rawTitle.trim();
 t =
 t.replaceAll(
 "(?i)\\s*[\\[【(（](?:4K|2160P|1080P|720P|HD|BD|国语|粤语|中字|双语|完结|全集)[^\\]】)）]*[\\]】)）]\\s*$",
 "")
 .trim();
 if (t.isEmpty() && rawTitle != null) t = rawTitle.trim();

 Matcher m1 =
 Pattern.compile(
 "^(.+?)\\s*(?:[（(\\[【·:：\\-—]\\s*)?第\\s*([一二三四五六七八九十百零两\\d]+)\\s*([季部辑篇])(?:\\s*[)）\\]】])?\\s*$")
 .matcher(t);
 if (m1.matches()) {
 String base = m1.group(1).trim();
 int num = parseChineseNumber(m1.group(2));
 String unit = m1.group(3);
 if (!base.isEmpty()) {
 return new SeasonMeta(base, num, "第 " + num + " " + unit, true);
 }
 }

 Matcher m2 =
 Pattern.compile(
 "(?i)^(.+?)\\s*(?:[（(\\[【·:：\\-—]\\s*)?(?:Season\\s*0*(\\d{1,2})|S0*(\\d{1,2}))(?:\\s*[)）\\]】])?\\s*$")
 .matcher(t);
 if (m2.matches()) {
 String base = m2.group(1).trim();
 String numStr = m2.group(2) != null ? m2.group(2) : m2.group(3);
 int num = parseChineseNumber(numStr);
 if (!base.isEmpty()) {
 return new SeasonMeta(base, num, "第 " + num + " 季", true);
 }
 }

 Matcher m3 =
 Pattern.compile(
 "^([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9·\\s]{1,20}?)\\s*(?:[（(\\[【·\\-]\\s*)?(20[123]\\d)\\s*[季期年]?(?:\\s*[)）\\]】])?\\s*$")
 .matcher(t);
 if (m3.matches()) {
 String base = m3.group(1).trim();
 int yr = parseChineseNumber(m3.group(2));
 if (!base.isEmpty()) {
 return new SeasonMeta(base, yr, yr + " 季", true);
 }
 }

 Matcher m4 = Pattern.compile("^([\\u4e00-\\u9fa5]{2,16})\\s*([1-9])\\s*$").matcher(t);
 if (m4.matches()) {
 String base = m4.group(1).trim();
 int num = parseChineseNumber(m4.group(2));
 if (!base.isEmpty()) {
 return new SeasonMeta(base, num, "第 " + num + " 季", true);
 }
 }

 return new SeasonMeta(t, 1, "第 1 季", false);
 }

 private static String normalizeBaseKey(String s) {
 if (s == null) return "";
 return s.toLowerCase().replaceAll("[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]", "").trim();
 }

 static void populateRelatedSeasons(
 final MainActivity a,
 final Cms.Site site,
 final Cms.Video currentVideo,
 final LinearLayout episodePanel,
 final int currentLineIdx,
 final int currentPageIdx,
 final WatchHistory.Entry remembered,
 final Map<Integer, Cms.Video> bySeason,
 final Map<Integer, String> labels) {
 final SeasonMeta curMeta = parseSeasonMeta(currentVideo.title);
 if (!curMeta.explicit) return;
 final String baseKey = normalizeBaseKey(curMeta.baseTitle);
 if (baseKey.isEmpty()) return;
 final int ticket = a.generation;
 a.submitPage(
 () -> {
 try {
 List<Cms.Video> found =
 Cms.listing(
 SourceController.engine(a, site)
 .search(curMeta.baseTitle, 1))
 .videos;
 if (found == null || found.isEmpty()) return;
 boolean added = false;
 for (Cms.Video cand : found) {
 if (cand == null || cand.title == null) continue;
 SeasonMeta candMeta = parseSeasonMeta(cand.title);
 if (!baseKey.equals(normalizeBaseKey(candMeta.baseTitle))) continue;
 if (!curMeta.explicit
 && !candMeta.explicit
 && !Objects.equals(cand.id, currentVideo.id)) {
 continue;
 }
 if (!bySeason.containsKey(candMeta.seasonNumber)) {
 bySeason.put(candMeta.seasonNumber, cand);
 labels.put(candMeta.seasonNumber, candMeta.seasonLabel);
 added = true;
 }
 }
 if (!added || bySeason.size() < 2) return;
 a.ui.post(
 () -> {
 if (a.isFinishing()
 || a.isDestroyed()
 || ticket != a.generation) return;
 renderMobileEpisodes(
 a,
 site,
 currentVideo,
 currentLineIdx,
 currentPageIdx,
 episodePanel,
 remembered,
 bySeason,
 labels);
 });
 } catch (Exception ignored) {
 }
 });
 }
}
