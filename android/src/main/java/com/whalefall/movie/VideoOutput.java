package com.whalefall.movie;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.*;
import android.widget.FrameLayout;

/** SurfaceView preserves the decoder's HDR output; TextureView remains an explicit fallback. */
final class VideoOutput extends FrameLayout
 implements com.pilipala.glass.GlassworkSurface.FrameSource {
 interface Listener {
 void available(Surface surface);

 void destroyed();
 }

 private Listener listener;
 private Surface current;
 private SurfaceTexture boundTexture;
 private boolean holdSurface;
 private final TextureView texture;
 private SurfaceView surfaceView;
 private Bitmap glassFrame;
 private boolean framePending, advancing;
 private long frameAt;
 private int frameGeneration;
 private final java.util.ArrayList<Runnable> frameWaiters = new java.util.ArrayList<>();
 private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

 VideoOutput(Context context, boolean compatible) {
 super(context);
 setTag("video-output");
 setFocusable(false);
 if (compatible || Build.VERSION.SDK_INT < 24) {
 texture = new TextureView(context);
 addView(texture, new LayoutParams(-1, -1));
 texture.setSurfaceTextureListener(
 new TextureView.SurfaceTextureListener() {
 public void onSurfaceTextureAvailable(SurfaceTexture value, int w, int h) {
 // 1.9.378：旧安卓小窗期保留同一个 SurfaceTexture，重挂时不重绑 Surface
 AppLog.i(
 "Mini",
 "output available st="
 + System.identityHashCode(value)
 + " w=" + w + " h=" + h
 + " held=" + holdSurface
 + " sameBound=" + (boundTexture == value)
 + " curValid=" + (current != null && current.isValid()));
 if (holdSurface && boundTexture == value && current != null && current.isValid()) {
 AppLog.i("Mini", "output reattach held=surface");
 return;
 }
 boundTexture = value;
 Surface fresh = new Surface(value);
 AppLog.i("Mini", "output surface fresh valid=" + fresh.isValid() + " " + fresh);
 ready(fresh);
 }

 public void onSurfaceTextureSizeChanged(
 SurfaceTexture value, int w, int h) {}

 public boolean onSurfaceTextureDestroyed(SurfaceTexture value) {
 // 1.9.378：旧安卓小窗期 detach（返回详情换 root）必须保留 SurfaceTexture；
 // 返回 false 表示由 App 继续持有，重挂时复用同一个，避免 setSurface 重绑崩溃。
 AppLog.i("Mini", "output destroyed st=" + System.identityHashCode(value) + " held=" + holdSurface + " cur=" + current);
 if (holdSurface) {
 AppLog.i("Mini", "output detach held=surface");
 return false;
 }
 boundTexture = null;
 gone();
 return true;
 }

 public void onSurfaceTextureUpdated(SurfaceTexture value) {}
 });
 } else {
 texture = null;
 SurfaceView surface = new SurfaceView(context);
 surfaceView = surface;
 addView(surface, new LayoutParams(-1, -1));
 surface.getHolder()
 .addCallback(
 new SurfaceHolder.Callback() {
 public void surfaceCreated(SurfaceHolder holder) {
 ready(holder.getSurface());
 }

 public void surfaceChanged(
 SurfaceHolder holder, int format, int w, int h) {}

 public void surfaceDestroyed(SurfaceHolder holder) {
 if (holdSurface) return;
 gone();
 }
 });
 }
 }

 void listen(Listener value) {
 listener = value;
 if (current != null && current.isValid()) listener.available(current);
 }

 /** 1.9.53：小窗→全屏时复用当前 Surface，避免重新起播/断流。 */
 Surface currentSurface() {
 return current != null && current.isValid() ? current : null;
 }

 /**
 * 1.9.378：旧安卓小窗期保留输出 Surface：detach 时既不 release SurfaceTexture、也不回调
 * {@link Listener#destroyed()}，重挂时复用同一个 SurfaceTexture。
 *
 * <p>只允许在小窗/放大过渡期打开；{@code PlaybackController.releasePlayer} 必须关闭，
 * 否则播放器销毁后仍留着 SurfaceTexture 引用。
 */
 void holdSurface(boolean value) {
 if (holdSurface == value) return;
 holdSurface = value;
 AppLog.i("Mini", "output hold=" + value + " sdk=" + Build.VERSION.SDK_INT);
 }

 private void ready(Surface value) {
 current = value;
 if (listener != null) listener.available(value);
 }

 private void gone() {
 boundTexture = null;
 frameGeneration++;
 glassFrame = null;
 framePending = false;
 finishGlassFrame();
 Surface old = current;
 current = null;
 if (listener != null) listener.destroyed();
 if (texture != null && old != null) old.release();
 }

 boolean supportsPicture() {
 return texture != null;
 }

 String outputName() {
 return texture == null ? "SurfaceView · 保真输出" : "TextureView · 兼容输出";
 }

 void picture(int preset) {
 if (texture != null) PlaybackEffects.picture(texture, preset);
 }

 void setFrameAdvancing(boolean value) {
 if (advancing == value) return;
 advancing = value;
 com.pilipala.glass.GlassworkSurface.invalidateBackdrop(this);
 }

 public boolean glassFrameAdvancing() {
 return advancing && current != null;
 }

 /** All visible controls share one bounded readback per 100 ms. */
 public void prepareGlassFrame(Runnable ready) {
 long now = android.os.SystemClock.uptimeMillis();
 if (current == null
 || !current.isValid()
 || getWidth() <= 0
 || getHeight() <= 0
 || (frameAt > 0 && now - frameAt < 100)) {
 ready.run();
 return;
 }
 frameWaiters.add(ready);
 if (framePending) return;
 framePending = true;
 final int token = frameGeneration;
 double scale =
 Math.min(
 1,
 Math.min(
 320.0 / Math.max(getWidth(), getHeight()),
 Math.sqrt(57600.0 / ((double) getWidth() * getHeight()))));
 final Bitmap frame =
 Bitmap.createBitmap(
 Math.max(1, (int) (getWidth() * scale)),
 Math.max(1, (int) (getHeight() * scale)),
 Bitmap.Config.ARGB_8888);
 if (texture != null) {
 try {
 glassFrame = texture.getBitmap(frame);
 frameAt = now;
 } catch (RuntimeException unavailable) {
 frame.recycle();
 glassFrame = null;
 }
 frameAt = now;
 framePending = false;
 finishGlassFrame();
 } else if (Build.VERSION.SDK_INT >= 24 && surfaceView != null) {
 try {
 PixelCopy.request(
 surfaceView,
 frame,
 result -> {
 if (token != frameGeneration) {
 frame.recycle();
 return;
 }
 glassFrame = result == PixelCopy.SUCCESS ? frame : null;
 if (glassFrame == null) frame.recycle();
 frameAt = android.os.SystemClock.uptimeMillis();
 framePending = false;
 finishGlassFrame();
 },
 new Handler(Looper.getMainLooper()));
 } catch (RuntimeException unavailable) {
 frame.recycle();
 glassFrame = null;
 frameAt = now;
 framePending = false;
 finishGlassFrame();
 }
 } else {
 frame.recycle();
 framePending = false;
 finishGlassFrame();
 }
 }

 private void finishGlassFrame() {
 java.util.ArrayList<Runnable> callbacks = new java.util.ArrayList<>(frameWaiters);
 frameWaiters.clear();
 for (Runnable callback : callbacks) callback.run();
 }

 @Override
 public void draw(Canvas canvas) {
 if (com.pilipala.glass.GlassworkSurface.isCapturingBackdrop()) {
 if (glassFrame != null && !glassFrame.isRecycled())
 canvas.drawBitmap(
 glassFrame, null, new Rect(0, 0, getWidth(), getHeight()), glassPaint);
 else canvas.drawColor(com.pilipala.glass.GlassworkMaterial.DARK_TINT);
 return;
 }
 super.draw(canvas);
 }
}
