package com.whalefall.movie;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.9.53：真正的左上角小窗（不再全屏 video 背景 + scrim）。
 *
 * <p>进入小窗时把 {@code playbackLayer} 缩到左上角固定尺寸，详情页完整显示在小窗下方/背后；
 * 小窗内叠加 播放/暂停、关闭、放大 三个控件；点小窗任意处回全屏。
 */
final class MiniDetails {
 final MainActivity a;
 final FrameLayout page;
 final View hit;
 final PlaybackControls controls;
 /** 1.9.226：首次测量的封面矩形（hide 后兜底）。 */
 final int[] cachedCover = new int[4];

 MiniDetails(MainActivity host) {
 a = host;
 controls = a.playbackControls;
 boolean tv = a.tvMiniMode();
 int width;
 int height;
 if (tv) {
 // 1.9.239：TV 固定 480×270px（16:9）
 width = 480;
 height = 270;
 } else {
 width = Math.round(a.windowWidth() * 0.45f);
 height = Math.round(width * 9f / 16f);
 }
 final int margin = tv ? 32 : a.dp(16);
 final int radius = a.dp(12);

 // 视频层缩成左上角小窗
 FrameLayout.LayoutParams videoLp =
 new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
 videoLp.leftMargin = margin;
 videoLp.topMargin = margin;
 UiCompat.setClipToOutline(a.playbackLayer, true);
 android.graphics.drawable.GradientDrawable frame =
 new android.graphics.drawable.GradientDrawable();
 frame.setColor(0x00000000);
 frame.setCornerRadius(radius);
 // 1.9.213：去掉白色细边，仅聚焦时由粉框绘制
 a.playbackLayer.setBackground(frame);
 a.playbackLayer.setOutlineProvider(
 new android.view.ViewOutlineProvider() {
 @Override
 public void getOutline(View view, android.graphics.Outline outline) {
 outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
 }
 });
 a.playbackLayer.setElevation(a.dp(8));
 a.playbackLayer.setLayoutParams(videoLp);
 AppLog.i(
 "Mini",
 "enter reason=player-back size=" + width + "x" + height + " at=" + margin + "," + margin);

 // 小窗控制层（与视频同尺寸同位置，透明背景）
 page = new FrameLayout(a);
 page.setTag("mini-details");
 page.setClipChildren(false);
 page.setClipToPadding(false);
 page.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 FrameLayout.LayoutParams pageLp =
 new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
 pageLp.leftMargin = margin;
 pageLp.topMargin = margin;
 page.setElevation(a.dp(10));
 page.setOnClickListener(v -> a.expandPlayback());

 LinearLayout bar = a.row();
 bar.setGravity(Gravity.CENTER_VERTICAL);
 bar.setPadding(a.dp(8), 0, a.dp(8), 0);
 GradientDrawable barBg = new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x00000000, 0xcc000000});
 bar.setBackground(barBg);
 final TextView[] playHolder = new TextView[1];
 playHolder[0] =
 miniButton(
 a.player != null && a.playing ? "暂停" : "播放",
 "mini-play",
 () -> {
 a.togglePlayback();
 if (playHolder[0] != null)
 playHolder[0].setText(a.playing ? "暂停" : "播放");
 AppLog.i(
 "Mini",
 "playstate="
 + (a.playing ? "playing" : "paused")
 + " focus=mini-play");
 });
 TextView playPause = playHolder[0];
 TextView expand = miniButton("全屏", "mini-expand", () -> a.expandPlayback());
 TextView stop = miniButton("关闭", "mini-stop", () -> {
 AppLog.i("Mini", "exit stop");
 PlaybackController.releasePlayer(a);
 a.ui.post(() -> {
 View play = a.root == null ? null : a.root.findViewWithTag("detail-play");
 if (play != null) play.requestFocus();
 });
 });
 bar.addView(playPause, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 bar.addView(expand, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 bar.addView(stop, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 FrameLayout.LayoutParams barLp =
 new FrameLayout.LayoutParams(-1, a.dp(24), Gravity.BOTTOM);
 barLp.bottomMargin = a.dp(8);
 barLp.leftMargin = a.dp(8);
 barLp.rightMargin = a.dp(8);
 page.addView(bar, barLp);

 // 小窗内一个不可见可聚焦载体：OK 回全屏，下方向进入详情控件
 hit = new View(a);
 hit.setFocusable(true);
 hit.setFocusableInTouchMode(true);
 hit.setTag("mini-video");
 // 1.9.212：焦点框改由 MiniDetails 粉框绘制，不再用通用白环
 // TvNav.focusRing(a, hit, 12);
 hit.setOnClickListener(
 v -> {
 // 1.9.96：小窗视频区 OK/点击 = 直接放大全屏
 AppLog.i("MiniFocus", "ok=expand");
 a.expandPlayback();
 });
 FrameLayout.LayoutParams hitLp = new FrameLayout.LayoutParams(-1, -1);
 page.addView(hit, hitLp);
 // 1.9.96：控制条必须叠在 mini-video 之上，否则按钮被整块 hit 盖住点不到
 bar.bringToFront();
 hit.setOnFocusChangeListener(
 (v, focused) -> {
 FocusMotion.style(
 a, page, focused, 0x00000000, 0x66ffffff, 12, 0, 0, false, 1f);
 // 1.9.212：小窗粉框（10%粉底 + 2dp ACCENT_SOFT，120ms/100ms）
 try {
 GradientDrawable box = new GradientDrawable();
 box.setCornerRadius(a.dp(12));
 box.setColor(0x1AFF8FB1);
 box.setStroke(Math.max(1, a.dp(2)), 0xFFFFB3C8);
 GradientDrawable clear = new GradientDrawable();
 clear.setCornerRadius(a.dp(12));
 clear.setColor(0x00000000);
 android.graphics.drawable.TransitionDrawable td =
 new android.graphics.drawable.TransitionDrawable(
 new android.graphics.drawable.Drawable[] {
 focused ? clear : box, focused ? box : clear
 });
 td.setCrossFadeEnabled(true);
 page.setBackground(td);
 td.startTransition(a.reduced ? 0 : (focused ? 120 : 100));
 AppLog.i(
 "MiniFocus",
 "frame=" + (focused ? "show" : "hide") + " focused=" + focused);
 } catch (Throwable ignored) {
 }
 if (focused) AppLog.i("MiniFocus", "enter from=detail dir=focus");
 });

 int layerIdx = a.root.indexOfChild(a.playbackLayer);
 a.root.addView(page, layerIdx >= 0 ? layerIdx + 1 : 0, pageLp);
 // 1.9.56：保证视频层在详情海报之上，再叠加控制层
 a.playbackLayer.bringToFront();
 page.bringToFront();
 // 1.9.211：只对齐详情页左侧 hero 图片区域（沿用原小窗布局/按钮样式）
 final int miniW = width;
 final int miniH = height;
 final int miniMargin = margin;
 a.ui.post(
 () -> {
 if (!a.miniMode) return;
 a.playbackLayer.setLayoutParams(videoLp);
 page.setLayoutParams(pageLp);
 PlaybackController.hideDetailHero(a, true);
 a.playbackLayer.bringToFront();
 page.bringToFront();
 AppLog.i(
 "Mini",
 "enter size="
 + miniW
 + "x"
 + miniH
 + " at="
 + miniMargin
 + ","
 + miniMargin);
 });
 // 1.9.211：等详情 hero 布局完成后对齐到 hero rect
 a.ui.postDelayed(() -> applyHeroRect(0), 120);
 a.ui.post(
 () -> {
 if (!a.miniMode) return;
 if (controls != null) controls.resize(controls.videoWidth, controls.videoHeight);
 View output = PlaybackController.findTagged(a.playbackLayer, "video-output");
 android.view.Surface surface =
 output instanceof VideoOutput
 ? ((VideoOutput) output).currentSurface()
 : null;
 if (a.player != null && surface != null) a.player.setSurface(surface);
 AppLog.i(
 "Mini",
 "z=video|poster surface="
 + (surface != null ? "reused" : "waiting")
 + " player="
 + (a.player != null));
 playPause.requestFocus();
 View detailPlay = a.root.findViewWithTag("detail-play");
 if (detailPlay != null) {
 if (detailPlay.getId() == View.NO_ID)
 detailPlay.setId(View.generateViewId());
 playPause.setNextFocusDownId(detailPlay.getId());
 playPause.setNextFocusUpId(detailPlay.getId());
 hit.setNextFocusDownId(detailPlay.getId());
 hit.setNextFocusUpId(detailPlay.getId());
 // 1.9.95：详情按钮区 UP 回到小窗暂停键，形成 mini 焦点区闭环
 detailPlay.setNextFocusUpId(playPause.getId());
 }
 // 三个小窗按钮显式左右/上下导航
 playPause.setNextFocusRightId(expand.getId());
 expand.setNextFocusLeftId(playPause.getId());
 expand.setNextFocusRightId(stop.getId());
 stop.setNextFocusLeftId(expand.getId());
 if (detailPlay != null) {
 expand.setNextFocusDownId(detailPlay.getId());
 stop.setNextFocusDownId(detailPlay.getId());
 expand.setNextFocusUpId(detailPlay.getId());
 stop.setNextFocusUpId(detailPlay.getId());
 }
 AppLog.i(
 "Mini",
 "playstate="
 + (a.playing ? "playing" : "paused")
 + " focus=mini-play");
 // 1.9.115：重建后确保 mini-video 仍可聚焦，并重挂详情焦点链
 if (!hit.isFocusable()) {
 hit.setFocusable(true);
 hit.setFocusableInTouchMode(true);
 AppLog.i("MiniFocus", "lost focusable=false restored=true");
 }
 a.ui.postDelayed(() -> TvDetailLayout.wireDetailFocusChain(a), 300);
 });
 // 图标跟随真实播放状态（暂停/播放切换后同步）
 final Runnable tick =
 new Runnable() {
 @Override
 public void run() {
 if (!a.miniMode) return;
 playPause.setText(a.playing ? "暂停" : "播放");
 a.ui.postDelayed(this, 500);
 }
 };
 a.ui.postDelayed(tick, 500);
 // 详情异步加载可能重新显示 hero 海报：延迟再隐藏一次，保证小窗始终是视频
 a.ui.postDelayed(
 () -> {
 if (a.miniMode) PlaybackController.hideDetailHero(a, true);
 },
 600);
 }

 /** 1.9.234：横竖屏切换时重算矩形。 */
 void refreshRect() {
 cachedCover[0] = cachedCover[1] = cachedCover[2] = cachedCover[3] = 0;
 applyHeroRect(0);
 }

 private void applyHeroRect(int attempt) {
 if (!a.miniMode || a.root == null) return;
 // 1.9.248：按分辨率判电视/盒子（横屏且长边≤1920），不再依赖触摸能力
 android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
 int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
 int shortEdge = Math.min(dm.widthPixels, dm.heightPixels);
 boolean landscape = dm.widthPixels > dm.heightPixels;
 boolean tvSmall = landscape && longEdge <= 1920;
 // 1.9.249：取消电视固定小窗，三端统一跟随海报
 boolean tvMini = false;
 AppLog.i(
 "Mini",
 "tvSmall="
 + tvSmall
 + " screen="
 + dm.widthPixels
 + "x"
 + dm.heightPixels
 + " long="
 + longEdge
 + " short="
 + shortEdge
 + " device="
 + a.deviceType
 + " size=480x270");
 if (tvMini) {
 // 1.9.248：电视/盒子固定 480×270px、left/top 32px
 int w = 480, h = 270, x = 32, y = 32;
 FrameLayout.LayoutParams vlp =
 new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
 vlp.leftMargin = x;
 vlp.topMargin = y;
 a.playbackLayer.setLayoutParams(vlp);
 FrameLayout.LayoutParams plp =
 new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
 plp.leftMargin = x;
 plp.topMargin = y;
 page.setLayoutParams(plp);
 if (a.player != null) {
 try {
 a.player.setScaleMode(true);
 } catch (Throwable ignored) {
 }
 }
 PlaybackController.hideDetailHero(a, true);
 a.playbackLayer.bringToFront();
 page.bringToFront();
 AppLog.i(
 "Mini",
 "tvSmall="
 + tvSmall
 + " size=480x270 at=32,32 device="
 + a.deviceType
 + " res="
 + a.resClass);
 // 1.9.248：布局后再强制校验尺寸
 a.ui.postDelayed(
 () -> {
 if (!a.miniMode || !tvMini || a.playbackLayer == null) return;
 int lw = a.playbackLayer.getWidth(), lh = a.playbackLayer.getHeight();
 if (lw > 600 || lh > 400) {
 FrameLayout.LayoutParams fp =
 new FrameLayout.LayoutParams(480, 270, Gravity.TOP | Gravity.LEFT);
 fp.leftMargin = 32;
 fp.topMargin = 32;
 a.playbackLayer.setLayoutParams(fp);
 FrameLayout.LayoutParams pp =
 new FrameLayout.LayoutParams(480, 270, Gravity.TOP | Gravity.LEFT);
 pp.leftMargin = 32;
 pp.topMargin = 32;
 page.setLayoutParams(pp);
 a.playbackLayer.bringToFront();
 page.bringToFront();
 AppLog.i("Mini", "force-resize from=" + lw + "x" + lh + " to=480x270");
 }
 },
 400);
 return;
 }
 {
 // 1.9.243：手机/平板按海报公式硬实现（不再 clamp/dump）
 int sw = a.windowWidth();
 boolean portrait = a.windowHeight() > sw;
 int w = portrait ? (sw - a.dp(32)) : Math.round(sw * 0.45f);
 int h = Math.round(w * 9f / 16f);
 int x = a.dp(16), y = a.dp(16);
 int posterW = 0, posterH = 0, cx = 0, cy = 0, cw = 0, ch = 0;
 String source = "formula";
 View poster = a.root.findViewWithTag("detail-cover");
 if (poster == null) poster = a.root.findViewWithTag("detail-poster");
 if (poster != null && poster.getWidth() > 0 && poster.getHeight() > 0) {
 posterW = poster.getWidth();
 posterH = poster.getHeight();
 int[] pv = new int[2];
 View anchor =
 a.playbackLayer.getParent() instanceof android.view.ViewGroup
 ? (View) a.playbackLayer.getParent()
 : a.root;
 int[] av = new int[2];
 poster.getLocationInWindow(pv);
 anchor.getLocationInWindow(av);
 x = Math.max(0, pv[0] - av[0]);
 y = Math.max(0, pv[1] - av[1]);
 w = posterW;
 h = posterH;
 cw = posterW;
 ch = posterH;
 cx = x;
 cy = y;
 source = "poster";
 }
 boolean clamped = false;
 AppLog.i(
 "MiniMobile",
 "formula w="
 + w
 + " h="
 + h
 + " posterW="
 + posterW
 + " posterH="
 + posterH
 + " match="
 + (posterW == w && posterH == h)
 + " source="
 + source);
 if (w > 0 && h > 0) {
 cachedCover[0] = x;
 cachedCover[1] = y;
 cachedCover[2] = w;
 cachedCover[3] = h;
 }
 FrameLayout.LayoutParams vlp =
 new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
 vlp.leftMargin = x;
 vlp.topMargin = y;
 a.playbackLayer.setLayoutParams(vlp);
 FrameLayout.LayoutParams plp =
 new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
 plp.leftMargin = x;
 plp.topMargin = y;
 page.setLayoutParams(plp);
 if (a.player != null) {
 try {
 a.player.setScaleMode(true);
 } catch (Throwable ignored) {
 }
 }
 AppLog.i(
 "MiniMobile",
 "poster="
 + cx
 + ","
 + cy
 + ","
 + (cx + cw)
 + ","
 + (cy + ch)
 + " rect="
 + x
 + ","
 + y
 + ","
 + (x + w)
 + ","
 + (y + h)
 + " visible="
 + (w > 0 && h > 0)
 + " playing="
 + a.playing
 + " source="
 + source);
 AppLog.i(
 "MiniMobile",
 "poster="
 + (cx - x)
 + ","
 + (cy - y)
 + ","
 + (cx - x + cw)
 + ","
 + (cy - y + ch)
 + " rect="
 + x
 + ","
 + y
 + ","
 + (x + w)
 + ","
 + (y + h)
 + " match="
 + (w == cw && h == ch)
 + " source="
 + source
 + " clamp="
 + clamped
 + " tv="
 + a.isTvDevice());
 PlaybackController.hideDetailHero(a, true);
 a.playbackLayer.bringToFront();
 page.bringToFront();
 // 1.9.234：布局后若尺寸仍为 0/不可见，再校正一次
 final int pass = attempt;
 a.ui.postDelayed(
 () -> {
 if (!a.miniMode) return;
 if (a.playbackLayer.getWidth() <= 0 || a.playbackLayer.getHeight() <= 0) {
 if (pass < 4) applyHeroRect(pass + 1);
 }
 },
 350);
 if (attempt < 2) a.ui.postDelayed(() -> applyHeroRect(attempt + 1), 300);
 return;
 }
 }

 private TextView miniButton(String label, String tag, Runnable action) {
 TextView b = a.text(label, a.isTvDevice() ? 11 : 12, 0xffffffff);
 b.setTag(tag);
 b.setGravity(Gravity.CENTER);
 b.setIncludeFontPadding(false);
 b.setFocusable(true);
 b.setFocusableInTouchMode(true);
 TvNav.markRing(b);
 if (b.getId() == View.NO_ID) b.setId(View.generateViewId());
 b.setOnFocusChangeListener(
 (v, focused) -> {
 if (focused) {
 GradientDrawable ring = new GradientDrawable();
 ring.setColor(0x99000000);
 ring.setCornerRadius(a.dp(8));
 ring.setStroke(a.dp(2), 0xffffffff);
 v.setBackground(ring);
 AppLog.i(
 "MiniFocus",
 "zone=mini focus="
 + ("mini-play".equals(tag)
 ? "pause"
 : ("mini-expand".equals(tag) ? "full" : "close")));
 } else {
 v.setBackground(null);
 }
 });
 b.setOnClickListener(
 v -> {
 if (v.hasFocus()) action.run();
 else v.requestFocus();
 });
 b.setOnKeyListener(
 (v, key, event) -> {
 if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
 AppLog.i(
 "TvKey",
 "page=mini code="
 + android.view.KeyEvent.keyCodeToString(key)
 + " focus="
 + tag
 + " action=nav");
 }
 if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
 && key == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
 action.run();
 return true;
 }
 return false;
 });
 return b;
 }

 void remove() {
 if (page.getParent() != null) ((FrameLayout) page.getParent()).removeView(page);
 if (hit != null && hit.getParent() == a.playbackLayer)
 a.playbackLayer.removeView(hit);
 }
}
