package com.whalefall.movie;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;

/**
 * 1.9.387：旧安卓（SDK&lt;24）小窗叠加层。
 *
 * <p>真机证据（MiBox4C / Android 6 / Amlogic 硬解）：
 *
 * <ol>
 *   <li>播放层被换父/换 root 会 detach 解码输出的 TextureView，重挂后拿到新的 SurfaceTexture，
 *       只出最后一帧（冻帧）。
 *   <li>解码输出 View 的布局尺寸一变（TextureView setDefaultBufferSize），这颗硬解会停帧，
 *       严重时报 {@code ERROR_CODE_DECODING_FAILED}，音频继续 → 「卡住但在播放」。
 * </ol>
 *
 * <p>因此播放页不再置换 root：在现有 root 上叠一个全屏黑底容器，播放层以<b>固定尺寸</b>挂进去；
 * 页面原有子 View 只设 INVISIBLE（保持 attached，不触发重建），小窗/全屏切换仅缩放播放层。
 */
final class PlayerOverlay {
 private static final String HOST_TAG = "player-overlay";

 private PlayerOverlay() {}

 static FrameLayout host(MainActivity a) {
 if (a == null || a.root == null) return null;
 return (FrameLayout) a.root.findViewWithTag(HOST_TAG);
 }

 static boolean active(MainActivity a) {
 return host(a) != null;
 }

 /** 进入播放页：复用现有 root，页面子 View 隐藏但保持 attached；播放层用固定尺寸。 */
 static boolean attach(MainActivity a, int width, int height) {
 if (a == null || a.root == null || active(a)) return false;
 FrameLayout container = new FrameLayout(a);
 container.setTag(HOST_TAG);
 container.setBackgroundColor(Color.BLACK);
 container.setClipChildren(false);
 container.setClipToPadding(false);
 ArrayList<View> hidden = new ArrayList<>();
 for (int i = 0; i < a.root.getChildCount(); i++) {
 View child = a.root.getChildAt(i);
 hidden.add(child);
 child.setVisibility(View.INVISIBLE);
 }
 a.playerOverlayHidden = hidden;
 a.root.addView(container, new FrameLayout.LayoutParams(-1, -1));
 a.playerOverlayAttached = true;
 a.playerLayerBaseW = width > 0 ? width : Math.max(1, a.windowWidth());
 a.playerLayerBaseH = height > 0 ? height : Math.max(1, a.windowHeight());
 a.playbackLayer = new FrameLayout(a);
 a.playbackLayer.setTag("playback-layer");
 container.addView(
 a.playbackLayer,
 new FrameLayout.LayoutParams(
 a.playerLayerBaseW, a.playerLayerBaseH, Gravity.CENTER));
 AppLog.i(
 "Mini",
 "overlay attach base=" + a.playerLayerBaseW + "x" + a.playerLayerBaseH);
 return true;
 }

 /** 页面（详情页/首页）重新可见：小窗状态需要它作为背景。 */
 static void showPage(MainActivity a) {
 if (a == null || a.playerOverlayHidden == null) return;
 for (View v : a.playerOverlayHidden) {
 if (v != null) v.setVisibility(View.VISIBLE);
 }
 // 1.9.390：叠加层拿走焦点后必须交回页面，否则 mCurrentFocus=null、遥控像失灵
 if (a.root != null) a.root.requestFocus();
 }

 /** 全屏播放：页面重新隐藏、播放层缩放复位。 */
 static void hidePage(MainActivity a) {
 if (a == null || a.playerOverlayHidden == null) return;
 for (View v : a.playerOverlayHidden) {
 if (v != null) v.setVisibility(View.INVISIBLE);
 }
 reset(a);
 }

 /** 只缩放播放层（不改父、不改尺寸），满足「解码输出尺寸全程不变」。 */
 static void scaleTo(MainActivity a, int x, int y, int w, int h) {
 View layer = a == null ? null : a.playbackLayer;
 if (layer == null) return;
 int baseW = Math.max(1, a.playerLayerBaseW);
 int baseH = Math.max(1, a.playerLayerBaseH);
 float sx = (float) w / baseW;
 float sy = (float) h / baseH;
 layer.setPivotX(0f);
 layer.setPivotY(0f);
 layer.setScaleX(sx);
 layer.setScaleY(sy);
 layer.setTranslationX(x);
 layer.setTranslationY(y);
 AppLog.i(
 "Mini",
 "overlay scale=" + sx + "x" + sy + " at=" + x + "," + y + " base=" + baseW + "x" + baseH);
 }

 static void reset(MainActivity a) {
 if (a == null) return;
 int baseW = Math.max(1, a.playerLayerBaseW);
 int baseH = Math.max(1, a.playerLayerBaseH);
 scaleTo(a, 0, 0, baseW, baseH);
 }

 /** 退出播放器：移除叠加层并恢复页面。播放器必须已经释放。 */
 static void detach(MainActivity a) {
 if (a == null || a.root == null) return;
 FrameLayout container = host(a);
 showPage(a);
 if (container != null && container.getParent() instanceof ViewGroup) {
 ((ViewGroup) container.getParent()).removeView(container);
 }
 a.playerOverlayHidden = null;
 a.playerOverlayAttached = false;
 a.overlayMiniActive = false;
 // 1.9.390：移除叠加层后把焦点交回页面，避免 mCurrentFocus=null
 if (a.root != null) a.root.requestFocus();
 AppLog.i("Mini", "overlay detach focus=" + (a.getCurrentFocus() != null));
 }
}
