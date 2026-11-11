package com.whalefall.movie;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;

/**
 * 1.8.3 全 App 通用「圆形帧动画」加载组件（素材 assets/loading/frame_01..07.webp）。
 *
 * <p>三档尺寸：SMALL 96dp（简洁模式：只有帧动画+文案，无光晕/浮动）、
 * MEDIUM 140dp、LARGE 168dp（光晕呼吸 2.4s + 浮动 ±4dp/3.2s）。
 * 帧 280ms/帧、140ms 交叉淡入；文案可自定义（默认「正在加载…」）；150ms 淡入/淡出；
 * 安全模式/减少动画时只显示静态首帧。
 */
final class WhaleLoadingView extends FrameLayout {
 static final int SMALL = 96;
 static final int MEDIUM = 140;
 static final int LARGE = 168;

 private static final long FRAME_MS = 280L;
 private static final long CROSS_MS = 140L;
 private static final long GLOW_MS = 2400L;
 private static final long FLOAT_MS = 3200L;
 private static final long FADE_MS = 150L;

 private static Bitmap[] frames;
 private static boolean framesLoading;

 private final MainActivity a;
 private final int targetDp;
 private final boolean simplified;
 private LinearLayout column;
 private FrameLayout glowBox;
 private Glow glow;
 private ImageView frameA, frameB;
 private TextView text;
 private int frameIndex;
 private boolean showingA = true;
 private boolean running;
 private boolean reduced;
 private String label = "正在加载…";
 private float baseOffsetY;
 private ValueAnimator crossAnimator, glowAnimator, floatAnimator, fadeAnimator;

 private final Runnable ticker =
 new Runnable() {
 @Override
 public void run() {
 if (!running) return;
 if (!isShown()) {
 // 页面不可见/被移除：停止 280ms 周期任务，避免后台继续重绘
 running = false;
 a.ui.removeCallbacks(this);
 return;
 }
 // 1.8.5：安全模式/减少动画开启后立即退化为静态帧并停 ticker
 if (SafeMode.enabled(a) || reduced) {
 java.util.List<Bitmap> cache =
 frames == null ? null : java.util.Arrays.asList(frames);
 if (cache != null && !cache.isEmpty() && frameA != null) {
 frameA.setImageBitmap(cache.get(0));
 frameA.setAlpha(1f);
 frameB.setAlpha(0f);
 }
 running = false;
 a.ui.removeCallbacks(this);
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 return;
 }
 crossFade();
 a.ui.postDelayed(this, FRAME_MS);
 }
 };

 WhaleLoadingView(MainActivity a, int sizeDp, boolean simplified) {
 super(a);
 this.a = a;
 this.targetDp = sizeDp;
 this.simplified = simplified;
 setTag("whale-loading");
 setFocusable(false);
 setClickable(false);
 build();
 }

 private void build() {
 int avatar = a.dp(targetDp);
 int glowSize = a.dp(Math.round(targetDp * 1.31f));
 column = a.vertical();
 column.setGravity(Gravity.CENTER_HORIZONTAL);
 glowBox = new FrameLayout(a);
 if (!simplified) {
 glow = new Glow(a);
 glowBox.addView(glow, new FrameLayout.LayoutParams(-1, -1));
 }
 FrameLayout frameBox = new FrameLayout(a);
 frameA = new ImageView(a);
 frameA.setScaleType(ImageView.ScaleType.FIT_CENTER);
 frameB = new ImageView(a);
 frameB.setScaleType(ImageView.ScaleType.FIT_CENTER);
 frameB.setAlpha(0f);
 frameBox.addView(frameA, new FrameLayout.LayoutParams(-1, -1));
 frameBox.addView(frameB, new FrameLayout.LayoutParams(-1, -1));
 glowBox.addView(
 frameBox, new FrameLayout.LayoutParams(avatar, avatar, Gravity.CENTER));
 column.addView(
 glowBox,
 new LinearLayout.LayoutParams(
 simplified ? avatar : glowSize, simplified ? avatar : glowSize));
 text = a.text(label, simplified ? 12 : 13, UiTokens.TEXT_TERTIARY);
 text.setGravity(Gravity.CENTER);
 text.setMaxLines(1);
 LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(-2, -2);
 textLp.topMargin = a.dp(8);
 column.addView(text, textLp);
 addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 }

 void setText(String value) {
 label = value == null ? "" : value;
 if (text != null) text.setText(label);
 }

 String getText() {
 return label;
 }

 void setReduced(boolean value) {
 reduced = value;
 if (running) restartAnimation();
 }

 void start() {
 if (running) return;
 running = true;
 setVisibility(View.VISIBLE);
 fadeTo(1f);
 ensureFrames(a);
 Bitmap[] cache = frames;
 if (cache == null || cache.length == 0) return;
 frameA.setImageBitmap(cache[frameIndex]);
 frameA.setAlpha(1f);
 frameB.setAlpha(0f);
 if (SafeMode.enabled(a) || reduced || simplified) {
 glowBox.setScaleX(1f);
 glowBox.setScaleY(1f);
 if (glow != null) glow.setBreath(0.5f);
 if (simplified || SafeMode.enabled(a) || reduced) return;
 }
 a.ui.removeCallbacks(ticker);
 a.ui.postDelayed(ticker, FRAME_MS);
 if (!simplified) {
 startGlow();
 startFloat();
 }
 }

 void stop() {
 running = false;
 a.ui.removeCallbacks(ticker);
 if (crossAnimator != null) crossAnimator.cancel();
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 if (getVisibility() != View.VISIBLE) return;
 fadeTo(0f);
 a.ui.postDelayed(
 () -> {
 if (!running) setVisibility(View.GONE);
 },
 FADE_MS);
 }

 void release() {
 running = false;
 a.ui.removeCallbacks(ticker);
 if (crossAnimator != null) crossAnimator.cancel();
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 if (fadeAnimator != null) fadeAnimator.cancel();
 if (frameA != null) frameA.setImageBitmap(null);
 if (frameB != null) frameB.setImageBitmap(null);
 }

 private void restartAnimation() {
 a.ui.removeCallbacks(ticker);
 if (crossAnimator != null) crossAnimator.cancel();
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 running = false;
 if (getVisibility() == View.VISIBLE) start();
 }

 private void crossFade() {
 Bitmap[] cache = frames;
 if (cache == null || cache.length == 0) return;
 final ImageView next = showingA ? frameB : frameA;
 final ImageView old = showingA ? frameA : frameB;
 frameIndex = (frameIndex + 1) % cache.length;
 next.setImageBitmap(cache[frameIndex]);
 next.setAlpha(0f);
 next.bringToFront();
 if (crossAnimator != null) crossAnimator.cancel();
 crossAnimator = ValueAnimator.ofFloat(0f, 1f);
 crossAnimator.setDuration(CROSS_MS);
 crossAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 crossAnimator.addUpdateListener(v -> next.setAlpha((float) v.getAnimatedValue()));
 crossAnimator.start();
 old.setAlpha(1f);
 showingA = !showingA;
 }

 private void startGlow() {
 if (glowAnimator != null) glowAnimator.cancel();
 glowAnimator = ValueAnimator.ofFloat(0f, 1f);
 glowAnimator.setDuration(GLOW_MS);
 glowAnimator.setRepeatCount(ValueAnimator.INFINITE);
 glowAnimator.setRepeatMode(ValueAnimator.REVERSE);
 glowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 glowAnimator.addUpdateListener(
 v -> {
 float t = (float) v.getAnimatedValue();
 if (glow != null) glow.setBreath(t);
 float scale = 0.96f + 0.08f * t;
 glowBox.setScaleX(scale);
 glowBox.setScaleY(scale);
 });
 glowAnimator.start();
 }

 private void startFloat() {
 if (floatAnimator != null) floatAnimator.cancel();
 refreshBaseOffset();
 floatAnimator = ValueAnimator.ofFloat(0f, 1f);
 floatAnimator.setDuration(FLOAT_MS);
 floatAnimator.setRepeatCount(ValueAnimator.INFINITE);
 floatAnimator.setRepeatMode(ValueAnimator.REVERSE);
 floatAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 floatAnimator.addUpdateListener(
 v -> {
 float t = (float) v.getAnimatedValue();
 column.setTranslationY(baseOffsetY + (t * 2f - 1f) * a.dp(4));
 });
 floatAnimator.start();
 }

 private void refreshBaseOffset() {
 baseOffsetY = 0f;
 column.setTranslationY(0f);
 }

 private void fadeTo(float target) {
 if (fadeAnimator != null) fadeAnimator.cancel();
 fadeAnimator = ValueAnimator.ofFloat(getAlpha(), target);
 fadeAnimator.setDuration(FADE_MS);
 fadeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 fadeAnimator.addUpdateListener(v -> setAlpha((float) v.getAnimatedValue()));
 fadeAnimator.start();
 }

 @Override
 protected void onAttachedToWindow() {
 super.onAttachedToWindow();
 if (running) restartAnimation();
 }

 @Override
 protected void onDetachedFromWindow() {
 running = false;
 a.ui.removeCallbacks(ticker);
 release();
 super.onDetachedFromWindow();
 }

 @Override
 protected void onWindowVisibilityChanged(int visibility) {
 super.onWindowVisibilityChanged(visibility);
 if (visibility != View.VISIBLE) {
 a.ui.removeCallbacks(ticker);
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 if (crossAnimator != null) crossAnimator.cancel();
 } else if (running) {
 restartAnimation();
 }
 }

 private static synchronized void ensureFrames(MainActivity a) {
 if (frames != null || framesLoading) return;
 framesLoading = true;
 try {
 Bitmap[] cache = new Bitmap[7];
 for (int i = 0; i < cache.length; i++) {
 String name = String.format(java.util.Locale.ROOT, "loading/frame_%02d.webp", i + 1);
 try (InputStream in = a.getAssets().open(name)) {
 BitmapFactory.Options options = new BitmapFactory.Options();
 options.inPreferredConfig = Bitmap.Config.ARGB_8888;
 cache[i] = BitmapFactory.decodeStream(in, null, options);
 } catch (Throwable ignored) {
 }
 }
 boolean ok = true;
 for (Bitmap bitmap : cache) if (bitmap == null) ok = false;
 if (ok) frames = cache;
 AppLog.i("WhaleLoading", "frames loaded=" + (frames != null) + " count=" + cache.length);
 } finally {
 framesLoading = false;
 }
 }

 private static final class Glow extends View {
 final Paint blue = new Paint(Paint.ANTI_ALIAS_FLAG);
 final Paint pink = new Paint(Paint.ANTI_ALIAS_FLAG);
 float breath = 0.5f;

 Glow(MainActivity a) {
 super(a);
 }

 void setBreath(float value) {
 breath = Math.max(0f, Math.min(1f, value));
 invalidate();
 }

 @Override
 protected void onDraw(Canvas c) {
 super.onDraw(c);
 float cx = getWidth() / 2f;
 float cy = getHeight() / 2f;
 float radius = Math.min(getWidth(), getHeight()) / 2f;
 blue.setShader(
 new RadialGradient(
 cx,
 cy,
 radius,
 new int[] {0xFF8FA8FF, 0x008FA8FF},
 null,
 Shader.TileMode.CLAMP));
 blue.setAlpha((int) (255 * (0.35f + 0.35f * breath)));
 c.drawCircle(cx, cy, radius, blue);
 pink.setShader(
 new RadialGradient(
 cx,
 cy,
 radius * 1.12f,
 new int[] {0xFFFFB3C8, 0x00FFB3C8},
 null,
 Shader.TileMode.CLAMP));
 pink.setAlpha((int) (255 * (0.10f + 0.08f * breath)));
 c.drawCircle(cx, cy, radius * 1.12f, pink);
 }
 }
}
