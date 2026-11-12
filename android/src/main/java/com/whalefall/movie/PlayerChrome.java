package com.whalefall.movie;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.InputStream;

/**
 * 播放器悬浮 UI：顶部标题/清晰度 + 中央播放簇（-10s / 播放 / +10s）+ 底部控制区
 * （大号时钟 + 预计结束、三段式进度条、10 格图标行）+ 右缘折叠钮。
 *
 * <p>视觉遵循 docs/WHALEFALL_DESIGN_SYSTEM.md：内容优先场景只用纯黑遮罩 + 白色图标 +
 * 单点樱花粉；图标全部是 vector drawable（{@link UiIcon}），不再手绘 Canvas。
 * 播放逻辑仍由 {@link PlaybackControls} 与 {@link PlaybackController} 驱动。
 */
final class PlayerChrome {
 final MainActivity a;
 final FrameLayout overlay;
 final View topShade, bottomShade;
 final LinearLayout heading;
 final LinearLayout topRight;
 final LinearLayout bottomPanel;
 final LinearLayout clockBlock;
 /** 进度槽：真正的三段布局 [时间][DefaultTimeBar][时间]，由 PlaybackControls 放入 timeline。 */
 final FrameLayout progress;
 final LinearLayout dock;
 final LinearLayout centerCluster;
 /** 1.7.9 加载层：圆形帧动画 + 双层光晕 + 浮动 + 动态文字（播放器内嵌）。 */
 final LoadingLayer loadingLayer;
 final LinearLayout topActions;
 TextView episodeLabel, titleView, seekHint, resolution;
 final TextView timeCurrent, timeTotal, clockBig, clockEnd, gesturePill;
 final ImageButton backBtn, moreBtn, foldBtn, state, seekBack, seekFwd;
 // 1.9.166（R2/R3）：中央三键自绘图标 + 专用焦点渲染器
 PlayerCenterIconDrawable seekBackIcon, seekFwdIcon, stateIcon;
 final TextView seekBubble;
 /** 弹层锚点（不可见）：位于底部控制区上方中央，供 PlayerBubble 向上展开。 */
 View menuAnchor;
 boolean loading = true;
 boolean lastLoadingShown;
 boolean progressOnlyMode = false;
 boolean bottomCollapsed = false;
 final Rect windowInsets = new Rect();
 boolean layoutPending;
 boolean wideLayout;

 PlayerChrome(MainActivity host, Cms.Video video, Cms.Episode episode, boolean live) {
 a = host;
 overlay = new FrameLayout(a);
 overlay.setTag("player-chrome");
 overlay.setClipChildren(true);
 overlay.setClipToPadding(true);
 overlay.addOnLayoutChangeListener(
 (view, l, t, r, b, ol, ot, or, ob) -> {
 if (l != ol || t != ot || r != or || b != ob) scheduleLayout();
 });

 // ---------------- 纯黑渐变遮罩（保证可读，不用彩色） ----------------
 topShade = new View(a);
 topShade.setBackground(
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x99000000, 0x00000000}));
 overlay.addView(topShade, new FrameLayout.LayoutParams(-1, a.dp(120), Gravity.TOP));
 bottomShade = new View(a);
 bottomShade.setBackground(
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x00000000, UiTokens.PLAYER_SCRIM}));
 overlay.addView(bottomShade, new FrameLayout.LayoutParams(-1, a.dp(210), Gravity.BOTTOM));

 // 外部逻辑（PlaybackController.clock）每秒写 a.playClock，这里保留隐藏占位避免 NPE
 a.playClock = a.text("", 1, 0);
 a.playClock.setVisibility(View.GONE);
 overlay.addView(a.playClock, new FrameLayout.LayoutParams(1, 1));

 // ---------------- 顶部：返回 + 标题/清晰度 + 更多 ----------------
 heading = a.vertical();
 heading.setTag("player-heading");
 LinearLayout titleRow = a.row();
 titleRow.setGravity(Gravity.CENTER_VERTICAL);

 backBtn =
 UiIcon.circleButton(
 a,
 UiIcon.BACK,
 "返回",
 44,
 24,
 UiTokens.PLAYER_BTN_FILL,
 UiTokens.PLAYER_BTN_STROKE,
 a::leavePlayback);
 backBtn.setTag("player-back");
 styleTopFocus(backBtn, "top-back");
 titleRow.addView(backBtn, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));

 LinearLayout titleCol = a.vertical();
 titleCol.setPadding(a.dp(12), 0, a.dp(8), 0);
 titleView = a.text(video == null ? "" : video.title, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
 titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
 titleView.getPaint().setFakeBoldText(true);
 titleView.setSingleLine(true);
 titleView.setEllipsize(TextUtils.TruncateAt.END);
 titleView.setShadowLayer(a.dp(8), 0, a.dp(2), 0xcc000000);
 titleView.setIncludeFontPadding(false);
 titleCol.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

 LinearLayout subRow = a.row();
 subRow.setGravity(Gravity.CENTER_VERTICAL);
 subRow.setPadding(0, a.dp(4), 0, 0);
 String line = a.activeLine == null || a.activeLine.name == null ? "" : a.activeLine.name;
 String epName = episode == null || episode.name == null ? "" : episode.name;
 episodeLabel =
 a.text(
 epName + (line.isEmpty() ? "" : " · " + line),
 UiTokens.TYPE_CAPTION,
 UiTokens.TEXT_SECONDARY);
 episodeLabel.setTag("player-episode");
 episodeLabel.setSingleLine(true);
 episodeLabel.setEllipsize(TextUtils.TruncateAt.END);
 episodeLabel.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
 subRow.addView(episodeLabel, new LinearLayout.LayoutParams(0, -2, 1f));

 resolution = a.text("", UiTokens.TYPE_MICRO, 0xffe2ebf8);
 resolution.setTag("player-resolution");
 resolution.setSingleLine(true);
 resolution.setIncludeFontPadding(false);
 resolution.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
 resolution.setBackground(
 UiTokens.round(
 a,
 UiTokens.RADIUS_6,
 UiTokens.PLAYER_CHIP,
 UiTokens.STROKE_SOFT));
 LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(-2, -2);
 resLp.leftMargin = a.dp(8);
 subRow.addView(resolution, resLp);
 titleCol.addView(subRow, new LinearLayout.LayoutParams(-1, -2));

 a.playStatus = a.text("", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
 a.playStatus.setVisibility(View.GONE);
 titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
 heading.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
 overlay.addView(heading, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

 // 手势 / 状态提示：屏幕中下方小胶囊，随控制条出现与淡出
 gesturePill = a.text("", UiTokens.TYPE_CAPTION, Color.WHITE);
 gesturePill.setSingleLine(true);
 gesturePill.setEllipsize(TextUtils.TruncateAt.END);
 gesturePill.setGravity(Gravity.CENTER);
 gesturePill.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
 gesturePill.setBackground(
 UiTokens.round(
 a, UiTokens.RADIUS_16, 0xb3000000, UiTokens.STROKE_SOFT));
 gesturePill.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
 gesturePill.setVisibility(View.GONE);
 overlay.addView(
 gesturePill,
 new FrameLayout.LayoutParams(
 -2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
 // 手势 seek 反馈复用同一个胶囊（PlaybackController 直接写 seekHint）
 seekHint = gesturePill;

 topRight = a.row();
 topRight.setGravity(Gravity.CENTER_VERTICAL);
 // 1.9.168：More = 48 wrapper + 44×40 胶囊（PLAYER_CHIP + 1dp STROKE_SOFT，圆角20）
 FrameLayout moreWrap = new FrameLayout(a);
 moreWrap.setClickable(true);
 moreBtn = new ImageButton(a);
 GradientDrawable moreCapsule = new GradientDrawable();
 moreCapsule.setColor(UiTokens.PLAYER_CHIP);
 moreCapsule.setCornerRadius(a.dp(20));
 moreCapsule.setStroke(Math.max(1, a.dp(1)), UiTokens.STROKE_SOFT);
 moreBtn.setBackground(moreCapsule);
 PlayerCenterIconDrawable moreIcon = new PlayerCenterIconDrawable(a, PlayerCenterIconDrawable.MORE);
 moreBtn.setImageDrawable(moreIcon);
 moreBtn.setPadding(0, 0, 0, 0);
 moreBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
 moreBtn.setContentDescription("更多工具");
 moreBtn.setClickable(true);
 moreBtn.setOnClickListener(
 v -> {
 if (a.playbackControls != null) {
 a.playbackControls.menuAnchor = menuAnchor;
 a.playbackControls.more();
 }
 });
 moreBtn.setOnTouchListener(
 (v, event) -> {
 moreIcon.setPressedState(
 event.getAction() == android.view.MotionEvent.ACTION_DOWN);
 return false;
 });
 moreBtn.setOnKeyListener(
 (v, keyCode, event) -> {
 boolean center =
 keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
 if (center && event.getAction() == android.view.KeyEvent.ACTION_DOWN)
 moreIcon.setPressedState(true);
 if (center && event.getAction() == android.view.KeyEvent.ACTION_UP)
 moreIcon.setPressedState(false);
 return false;
 });
 bindCenterIconFocus(moreBtn, moreIcon, "more");
 final View.OnFocusChangeListener moreBase = moreBtn.getOnFocusChangeListener();
 moreBtn.setOnFocusChangeListener(
 (v, focused) -> {
 if (moreBase != null) moreBase.onFocusChange(v, focused);
 if (focused) AppLog.i("PlayerFocus", "more style=capsule");
 });
 moreWrap.setOnClickListener(v -> moreBtn.performClick());
 moreWrap.addView(
 moreBtn, new FrameLayout.LayoutParams(a.dp(44), a.dp(40), Gravity.CENTER));
 topRight.addView(moreWrap, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
 overlay.addView(
 topRight, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
 topActions = topRight;

 // ---------------- 中央播放簇：-10s / 播放暂停 / +10s ----------------
 // 1.9.163（第1步，仅容器视觉）：单玻璃胶囊，不改子项尺寸/图标/焦点
 centerCluster = a.row();
 centerCluster.setGravity(Gravity.CENTER_VERTICAL);
 centerCluster.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
 GradientDrawable centerCapsule = new GradientDrawable();
 centerCapsule.setColor(UiTokens.PLAYER_CHIP);
 centerCapsule.setCornerRadius(a.dp(40));
 centerCapsule.setStroke(Math.max(1, a.dp(1)), UiTokens.STROKE_SOFT);
 centerCluster.setBackground(centerCapsule);
 seekBack = centerIconButton(PlayerCenterIconDrawable.REW, "快退 10 秒", () -> {
 if (a.playbackControls != null) a.playbackControls.nudge(-10000);
 });
 seekFwd = centerIconButton(PlayerCenterIconDrawable.FF, "快进 10 秒", () -> {
 if (a.playbackControls != null) a.playbackControls.nudge(10000);
 });
 state = centerIconButton(PlayerCenterIconDrawable.STATE, "播放/暂停", () -> {
 a.togglePlayback();
 a.showControls(true);
 });
 state.setTag("play-pause");
 // 1.9.163/164：去掉中央键独立深色圆底与 elevation（统一由胶囊承载）
 state.setBackground(null);
 UiCompat.setElevation(state, 0);
 seekBackIcon = (PlayerCenterIconDrawable) seekBack.getDrawable();
 seekFwdIcon = (PlayerCenterIconDrawable) seekFwd.getDrawable();
 stateIcon = (PlayerCenterIconDrawable) state.getDrawable();
 bindCenterIconFocus(seekBack, seekBackIcon, "seek-back");
 bindCenterIconFocus(seekFwd, seekFwdIcon, "seek-fwd");
 bindCenterIconFocus(state, stateIcon, "state");
 state.setOnKeyListener(
 (v, keyCode, event) -> {
 if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
 if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
 AppLog.i("PlayerFocus", "ok=play-pause action=toggle");
 else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
 View next = v.focusSearch(View.FOCUS_DOWN);
 AppLog.i(
 "PlayerFocus",
 "down from=play-pause to="
 + (next == null
 ? "none"
 : String.valueOf(next.getTag())));
 } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
 View next = v.focusSearch(View.FOCUS_RIGHT);
 AppLog.i(
 "PlayerFocus",
 "right from=play-pause to="
 + (next == null
 ? "none"
 : String.valueOf(next.getTag())));
 }
 }
 return false;
 });
 // 1.9.164 R1b：侧键视觉 44dp / hit 48dp（48 容器内居中 44），state 64×64、margin 0
 FrameLayout backWrap = new FrameLayout(a);
 backWrap.setClickable(true);
 backWrap.setOnClickListener(v -> seekBack.performClick());
 backWrap.addView(
 seekBack, new FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.CENTER));
 FrameLayout fwdWrap = new FrameLayout(a);
 fwdWrap.setClickable(true);
 fwdWrap.setOnClickListener(v -> seekFwd.performClick());
 fwdWrap.addView(
 seekFwd, new FrameLayout.LayoutParams(a.dp(44), a.dp(44), Gravity.CENTER));
 LinearLayout.LayoutParams stateLp =
 new LinearLayout.LayoutParams(a.dp(64), a.dp(64));
 stateLp.leftMargin = a.dp(18);
 stateLp.rightMargin = a.dp(18);
 centerCluster.addView(backWrap, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
 centerCluster.addView(state, stateLp);
 centerCluster.addView(fwdWrap, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
 overlay.addView(centerCluster, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 centerCluster.post(
 () ->
 AppLog.i(
 "PlayerChrome",
 "center capsule=" + centerCluster.getWidth() + "x" + centerCluster.getHeight()));
 // 直播没有可 seek 的时长，中央只留播放/暂停；胶囊收为 80×80
 if (live) {
 backWrap.setVisibility(View.GONE);
 fwdWrap.setVisibility(View.GONE);
 stateLp.leftMargin = 0;
 stateLp.rightMargin = 0;
 state.setLayoutParams(stateLp);
 centerCluster.requestLayout();
 state.requestLayout();
 centerCluster.post(
 () ->
 AppLog.i(
 "PlayerChrome",
 "live capsule=" + centerCluster.getWidth() + "x" + centerCluster.getHeight()));
 }

 // ---------------- 底部控制区：时钟 + 三段进度 + 10 格图标行 ----------------
 bottomPanel = a.vertical();
 bottomPanel.setTag("player-bottom-panel");

 clockBlock = a.vertical();
 clockBlock.setGravity(Gravity.END);
 clockBlock.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(5));
 clockBlock.setBackground(
 UiTokens.round(
 a, UiTokens.RADIUS_16, UiTokens.PLAYER_CHIP, UiTokens.STROKE_SOFT));
 UiCompat.setElevation(clockBlock, a.dp(3));
 clockBig = a.text("00:00:00", UiTokens.TYPE_CLOCK, UiTokens.TEXT_PRIMARY);
 clockBig.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
 clockBig.setFontFeatureSettings("tnum");
 clockBig.setIncludeFontPadding(false);
 clockBig.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
 clockBig.setGravity(Gravity.END);
 clockBlock.addView(clockBig, new LinearLayout.LayoutParams(-1, -2));
 clockEnd = a.text("影片结束 --:--:--", UiTokens.TYPE_CAPTION, UiTokens.TEXT_SECONDARY);
 clockEnd.setSingleLine(true);
 clockEnd.setGravity(Gravity.END);
 clockEnd.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
 LinearLayout.LayoutParams clockEndLp = new LinearLayout.LayoutParams(-1, -2);
 clockEndLp.topMargin = a.dp(2);
 clockBlock.addView(clockEnd, clockEndLp);
 LinearLayout.LayoutParams clockBlockLp = new LinearLayout.LayoutParams(-2, -2);
 clockBlockLp.gravity = Gravity.END;
 clockBlockLp.bottomMargin = a.dp(UiTokens.SP_8);
 bottomPanel.addView(clockBlock, clockBlockLp);

 // 三段式进度：左时间 / 时间轴（weight 1）/ 右时间
 LinearLayout progressRow = a.row();
 progressRow.setGravity(Gravity.CENTER_VERTICAL);
 progressRow.setTag("player-progress-row");
 timeCurrent = timeLabel("00:00", Gravity.START);
 timeTotal = timeLabel("00:00", Gravity.END);
 progressRow.addView(timeCurrent, new LinearLayout.LayoutParams(-2, -2));
 progress = new FrameLayout(a);
 progress.setTag("player-progress");
 LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, a.dp(48), 1f);
 slotLp.leftMargin = a.dp(UiTokens.SP_10);
 slotLp.rightMargin = a.dp(UiTokens.SP_10);
 progressRow.addView(progress, slotLp);
 progressRow.addView(timeTotal, new LinearLayout.LayoutParams(-2, -2));
 bottomPanel.addView(progressRow, new LinearLayout.LayoutParams(-1, a.dp(48)));

 dock = a.row();
 dock.setTag("player-dock");
 dock.setGravity(Gravity.CENTER);
 LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(-1, a.dp(56));
 dockLp.topMargin = a.dp(2);
 bottomPanel.addView(dock, dockLp);

 overlay.addView(bottomPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

 // 右缘折叠钮：48dp 圆形触控目标，垂直居中
 foldBtn =
 UiIcon.circleButton(
 a,
 UiIcon.CHEVRON_RIGHT,
 "收起控制栏",
 48,
 24,
 UiTokens.PLAYER_CHIP,
 0,
 this::toggleBottomPanel);
 overlay.addView(foldBtn, new FrameLayout.LayoutParams(a.dp(48), a.dp(48)));

 // 拖拽时间气泡：白底黑字
 seekBubble = a.text("", 12, 0xff1a1a1a);
 seekBubble.setTypeface(null, Typeface.BOLD);
 seekBubble.setSingleLine(true);
 seekBubble.setIncludeFontPadding(false);
 seekBubble.setGravity(Gravity.CENTER);
 seekBubble.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
 seekBubble.setBackground(
 UiTokens.round(a, UiTokens.RADIUS_10, 0xf2ffffff, 0x33000000));
 seekBubble.setVisibility(View.GONE);
 overlay.addView(
 seekBubble,
 new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

 // 弹层锚点：底部控制区上方中央
 menuAnchor = new View(a);
 menuAnchor.setVisibility(View.INVISIBLE);
 menuAnchor.setFocusable(false);
 overlay.addView(
 menuAnchor, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));

 a.playbackLayer.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

 // ---------------- 加载层（1.7.9：圆形帧动画 + 光晕 + 浮动） ----------------
 loadingLayer = new LoadingLayer(a, episode);
 a.playbackLayer.addView(loadingLayer, new FrameLayout.LayoutParams(-1, -1));

 a.playerOverlay = overlay;
 a.controlsVisible = true;
 scheduleLayout();
 }

 private TextView timeLabel(String text, int gravity) {
 TextView tv = a.text(text, UiTokens.TYPE_BODY, UiTokens.TEXT_PRIMARY);
 tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
 tv.setFontFeatureSettings("tnum");
 tv.setIncludeFontPadding(false);
 tv.setGravity(gravity);
 tv.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
 tv.setBackground(UiTokens.round(a, UiTokens.RADIUS_6, 0x66000000));
 tv.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
 return tv;
 }

 /** 收起 / 展开底部控制区（右缘箭头）。 */
 void toggleBottomPanel() {
 bottomCollapsed = !bottomCollapsed;
 applyBottomCollapsed();
 scheduleLayout();
 a.showControls(true);
 }

 private void applyBottomCollapsed() {
 if (bottomPanel == null) return;
 dock.setVisibility(
 progressOnlyMode
 ? View.INVISIBLE
 : (bottomCollapsed ? View.GONE : View.VISIBLE));
 if (clockBlock != null) {
 clockBlock.setVisibility(
 progressOnlyMode
 ? View.INVISIBLE
 : (bottomCollapsed || !wideLayout ? View.GONE : View.VISIBLE));
 }
 UiIcon.setIcon(
 foldBtn, bottomCollapsed ? UiIcon.CHEVRON_LEFT : UiIcon.CHEVRON_RIGHT);
 foldBtn.setContentDescription(bottomCollapsed ? "展开控制栏" : "收起控制栏");
 a.controlsVisible = true;
 hideSeekBubble();
 }

 // ---------------- 时间气泡 ----------------

 void showSeekBubble(int positionMs, int durationMs) {
 if (seekBubble == null || a.playbackControls == null) return;
 final View timeline = a.playbackControls.timeline;
 if (timeline == null || timeline.getWidth() <= 0) return;
 seekBubble.setText(
 PlaybackController.formatTime(Math.max(0, positionMs))
 + " / "
 + PlaybackController.formatTime(Math.max(1, durationMs)));
 seekBubble.setVisibility(View.VISIBLE);
 seekBubble.measure(
 View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
 View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
 final int bubbleW = seekBubble.getMeasuredWidth();
 final int bubbleH = seekBubble.getMeasuredHeight();
 final float fraction =
 durationMs <= 0
 ? 0f
 : Math.max(0f, Math.min(1f, (float) positionMs / durationMs));
 seekBubble.post(
 () -> {
 if (overlay == null || seekBubble.getVisibility() != View.VISIBLE) return;
 int[] t = new int[2];
 timeline.getLocationInWindow(t);
 int[] o = new int[2];
 overlay.getLocationInWindow(o);
 int padL = timeline.getPaddingLeft();
 int padR = timeline.getPaddingRight();
 int trackW = Math.max(1, timeline.getWidth() - padL - padR);
 int x = t[0] - o[0] + padL + Math.round(fraction * trackW);
 int left =
 Math.max(
 a.dp(8),
 Math.min(
 overlay.getWidth() - bubbleW - a.dp(8),
 x - bubbleW / 2));
 int top = Math.max(a.dp(8), t[1] - o[1] - bubbleH - a.dp(10));
 FrameLayout.LayoutParams lp =
 (FrameLayout.LayoutParams) seekBubble.getLayoutParams();
 lp.gravity = Gravity.TOP | Gravity.LEFT;
 lp.leftMargin = left;
 lp.topMargin = top;
 seekBubble.setLayoutParams(lp);
 });
 }

 void hideSeekBubble() {
 if (seekBubble != null) seekBubble.setVisibility(View.GONE);
 }

 // ---------------- 布局 ----------------

 void scheduleLayout() {
 if (layoutPending) return;
 layoutPending = true;
 overlay.post(
 () -> {
 layoutPending = false;
 if (a.playerChrome == this) relayout();
 });
 }

 /** 1.9.61：TV 顶部返回/更多可聚焦 + 可见焦点描边 + 按键日志。 */
 private void styleTopFocus(final ImageButton button, final String tag) {
 button.setTag(tag);
 if (!a.isTvDevice()) return;
 button.setFocusable(true);
 button.setFocusableInTouchMode(true);
 // 1.9.68：顶部栏也走统一焦点动效
 TvNav.focusRing(a, button, 24);
 final View.OnFocusChangeListener baseRing = button.getOnFocusChangeListener();
 button.setOnFocusChangeListener(
 (v, focused) -> {
 if (baseRing != null) baseRing.onFocusChange(v, focused);
 if (focused)
 AppLog.i("PlayerFocus", "bg focus=" + tag + " zone=top");
 });
 button.setOnKeyListener(
 (v, keyCode, event) -> {
 if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
 && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == android.view.KeyEvent.KEYCODE_ENTER))
 AppLog.i(
 "TvKey",
 "page=player focus=" + tag + " action=confirm");
 return false;
 });
 }

 /** 1.9.166（R2）：中央键 ImageButton + 自绘图标（无背景/无 elevation，几何由外部 LP 决定）。 */
 private ImageButton centerIconButton(int kind, String desc, Runnable click) {
 ImageButton button = new ImageButton(a);
 button.setBackground(null);
 PlayerCenterIconDrawable icon = new PlayerCenterIconDrawable(a, kind);
 if (kind == PlayerCenterIconDrawable.STATE) icon.setPlaying(a.playing);
 button.setImageDrawable(icon);
 button.setPadding(0, 0, 0, 0);
 button.setScaleType(ImageView.ScaleType.FIT_CENTER);
 button.setContentDescription(desc);
 button.setClickable(true);
 button.setOnClickListener(v -> click.run());
 // R2 icon 日志
 String iconTag =
 kind == PlayerCenterIconDrawable.REW
 ? "rew"
 : kind == PlayerCenterIconDrawable.FF ? "ffw" : (a.playing ? "pause" : "play");
 AppLog.i("PlayerCenter", "icon=" + iconTag);
 return button;
 }

 /** 1.9.166（R3）：中央专用焦点渲染：框固定不缩放、icon 1.04、120/100ms；不用 TvNav。 */
 private void bindCenterIconFocus(
 final ImageButton button, final PlayerCenterIconDrawable icon, final String tag) {
 if (button == null || icon == null) return;
 boolean mode = a.isTvDevice() || a.keyFocusMode || a.remoteFocusMode;
 button.setFocusable(mode);
 button.setFocusableInTouchMode(mode);
 button.setClickable(true);
 button.setOnFocusChangeListener(
 (v, focused) -> {
 if (!(a.isTvDevice() || a.keyFocusMode || a.remoteFocusMode)) {
 icon.setFocusFraction(0f);
 return;
 }
 PlayerFocusRenderer.animated(a, icon, focused);
 if (focused) AppLog.i("PlayerFocus", "center focus=" + tag + " style=capsule");
 });
 }

 /** 1.9.166：进入按键/遥控焦点模式后重新绑定中央三键（专用渲染器）。 */
 void refreshCenterFocus() {
 boolean mode = a.isTvDevice() || a.keyFocusMode || a.remoteFocusMode;
 bindCenterIconFocus(seekBack, seekBackIcon, "seek-back");
 bindCenterIconFocus(seekFwd, seekFwdIcon, "seek-fwd");
 bindCenterIconFocus(state, stateIcon, "state");
 if (!mode) {
 if (seekBackIcon != null) seekBackIcon.setFocusFraction(0f);
 if (seekFwdIcon != null) seekFwdIcon.setFocusFraction(0f);
 if (stateIcon != null) stateIcon.setFocusFraction(0f);
 }
 AppLog.i("PlayerFocus", "refresh center mode=" + mode + " style=capsule");
 }

 void safeInsets(int left, int top, int right, int bottom) {
 windowInsets.set(left, top, right, bottom);
 relayout();
 }

 void relayout() {
 int measuredWidth = overlay.getWidth(), measuredHeight = overlay.getHeight();
 if (measuredWidth <= 0 || measuredHeight <= 0) return;
 Rect safe = new Rect(0, 0, measuredWidth, measuredHeight);
 Rect visible = new Rect();
 if (overlay.getLocalVisibleRect(visible)) safe.intersect(visible);
 if (!a.isTvDevice()) {
 int[] location = new int[2];
 overlay.getLocationInWindow(location);
 View decor = a.getWindow().getDecorView();
 safe.left = Math.max(safe.left, windowInsets.left - location[0]);
 safe.top = Math.max(safe.top, windowInsets.top - location[1]);
 safe.right = Math.min(safe.right, decor.getWidth() - windowInsets.right - location[0]);
 safe.bottom =
 Math.min(safe.bottom, decor.getHeight() - windowInsets.bottom - location[1]);
 }
 safe.left = Math.max(0, Math.min(measuredWidth, safe.left));
 safe.top = Math.max(0, Math.min(measuredHeight, safe.top));
 safe.right = Math.max(safe.left, Math.min(measuredWidth, safe.right));
 safe.bottom = Math.max(safe.top, Math.min(measuredHeight, safe.bottom));
 overlay.setPadding(
 safe.left, safe.top, measuredWidth - safe.right, measuredHeight - safe.bottom);
 int width = safe.width(), height = safe.height();
 int widthDp = Math.round(width / a.getResources().getDisplayMetrics().density);
 wideLayout = a.isTvDevice() || widthDp >= 600;
 int side = Math.max(0, Math.min(a.dp(wideLayout ? 24 : 16), width / 10));
 int topMargin = Math.max(0, Math.min(a.dp(wideLayout ? 14 : 10), height / 12));

 if (titleView != null) {
 titleView.setTextSize(wideLayout ? UiTokens.TYPE_TITLE : UiTokens.TYPE_TITLE_COMPACT);
 }

 place(heading, -1, -2, Gravity.TOP, side, topMargin, side + a.dp(120), 0);
 place(
 topRight,
 -2,
 -2,
 Gravity.TOP | Gravity.RIGHT,
 0,
 a.dp(8),
 a.dp(8),
 0);
 place(bottomPanel, -1, -2, Gravity.BOTTOM, side, 0, side, wideLayout ? a.dp(20) : a.dp(14));
 place(centerCluster, -2, -2, Gravity.CENTER, 0, 0, 0, 0);

 if (clockBlock != null) {
 clockBlock.setVisibility(
 progressOnlyMode
 ? View.INVISIBLE
 : (bottomCollapsed || !wideLayout ? View.GONE : View.VISIBLE));
 }
 dock.setVisibility(
 progressOnlyMode
 ? View.INVISIBLE
 : (bottomCollapsed ? View.GONE : View.VISIBLE));
 foldBtn.setVisibility(progressOnlyMode ? View.GONE : View.VISIBLE);
 int foldSize = a.dp(48);
 place(
 foldBtn,
 foldSize,
 foldSize,
 Gravity.TOP | Gravity.LEFT,
 Math.max(0, width - foldSize - a.dp(8)),
 Math.max(0, (height - foldSize) / 2),
 0,
 0);

 int pillBottom;
 if (progressOnlyMode) pillBottom = a.dp(72);
 else if (bottomCollapsed) pillBottom = a.dp(82);
 else pillBottom = a.dp(wideLayout ? 210 : 176);
 place(
 gesturePill,
 -2,
 -2,
 Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
 0,
 0,
 0,
 pillBottom);

 int anchorY = Math.max(0, height - (bottomCollapsed ? a.dp(120) : a.dp(wideLayout ? 230 : 190)));
 place(menuAnchor, 1, 1, Gravity.TOP | Gravity.LEFT, Math.max(0, width / 2), anchorY, 0, 0);

 applyBottomCollapsed();
 if (seekBubbleVisible()) hideSeekBubble();

 if (a.playbackControls != null) {
 a.playbackControls.relayout(wideLayout);
 // 安全区/尺寸变化后同步重算视频 surface：避免用旧窗口尺寸固定住一张「小卡」
 a.playbackControls.resize(
 a.playbackControls.videoWidth, a.playbackControls.videoHeight);
 }
 }

 private boolean seekBubbleVisible() {
 return seekBubble != null && seekBubble.getVisibility() == View.VISIBLE;
 }

 private void place(
 View view,
 int width,
 int height,
 int gravity,
 int left,
 int top,
 int right,
 int bottom) {
 if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
 FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
 if (p.width == width
 && p.height == height
 && p.gravity == gravity
 && p.leftMargin == left
 && p.topMargin == top
 && p.rightMargin == right
 && p.bottomMargin == bottom) return;
 p.width = width;
 p.height = height;
 p.gravity = gravity;
 p.setMargins(left, top, right, bottom);
 view.setLayoutParams(p);
 }

 void resolution(int width, int height) {
 if (a.player != null && a.prepared) {
 applyResolutionShort();
 } else {
 resolution.setText(width > 0 && height > 0 ? width + " × " + height : "");
 }
 }

 /** 第二行只保留清晰度档位（如「1080P」），完整编码信息留给「更多 → 播放信息」。 */
 private void applyResolutionShort() {
 String shortRes = shortResolution();
 resolution.setText(shortRes);
 }

 private String shortResolution() {
 if (a.player == null || !a.prepared) return "";
 String full = a.player.videoResolution();
 if (full == null || full.isEmpty() || full.startsWith("分辨率未知")) return "";
 if (full.startsWith("4K")
 || full.startsWith("2K")
 || full.startsWith("1080P")
 || full.startsWith("720P")) {
 int space = full.indexOf(' ');
 return space > 0 ? full.substring(0, space) : full;
 }
 return "";
 }

 void setLoading(boolean value) {
 loading = value;
 boolean show = value && !a.prepared && !a.miniMode && a.playbackLoading == null;
 if (!show) loadingLayer.hideNow("setLoading(" + value + ")");
 loadingLayer.setActive(show);
 if (show != lastLoadingShown) {
 lastLoadingShown = show;
 AppLog.i(
 "PlayerChrome",
 "loading set value="
 + value
 + " active="
 + show
 + " prepared="
 + a.prepared
 + " mini="
 + a.miniMode
 + " overlay="
 + (a.playbackLoading != null)
 + " layerVisible="
 + (loadingLayer.getVisibility() == View.VISIBLE));
 }
 update();
 }

 void pauseLoadingAnimation() {
 loadingLayer.pause();
 }

 void resumeLoadingAnimation() {
 loadingLayer.resume();
 }

 /** 预留：仅当拿到缓冲百分比（0..1）时才显示 2dp 细进度条。 */
 void setLoadingProgress(Float fraction) {
 loadingLayer.setProgress(fraction);
 }

 boolean isLoadingVisible() {
 return loadingLayer.isActiveVisible();
 }

 void showLoadingError(String message, Runnable retry) {
 loadingLayer.showError(message, retry);
 }

 /** 1.9.65：自动重连成功后收起错误层。 */
 void hideLoadingError() {
 loadingLayer.hideNow("retry ok");
 loadingLayer.setVisibility(View.GONE);
 }

 void switchEpisode(Cms.Episode episode, Cms.Line line) {
 String lineName = line == null || line.name == null ? "" : line.name;
 String epName = episode == null || episode.name == null ? "" : episode.name;
 episodeLabel.setText(epName + (lineName.isEmpty() ? "" : " · " + lineName));
 resolution.setText("");
 if (a.playStatus != null) a.playStatus.setText("正在加载");
 loadingLayer.setEpisode(epName);
 loading = true;
 update();
 }

 void setProgressOnlyMode(boolean progressOnly) {
 if (this.progressOnlyMode == progressOnly) return;
 this.progressOnlyMode = progressOnly;
 int otherVis = progressOnly ? View.INVISIBLE : View.VISIBLE;
 topShade.setVisibility(otherVis);
 heading.setVisibility(otherVis);
 topRight.setVisibility(otherVis);
 foldBtn.setVisibility(progressOnly ? View.GONE : View.VISIBLE);
 centerCluster.setVisibility(progressOnly ? View.INVISIBLE : View.VISIBLE);
 applyBottomCollapsed();
 hideSeekBubble();
 update();
 if (a.playbackControls != null) a.playbackControls.visible(a.controlsVisible);
 }

 void update() {
 if (a.prepared) loading = false;
 if (a.player != null && a.prepared) applyResolutionShort();
 boolean showSpinner = loading && !a.prepared && a.playbackLoading == null && !a.miniMode;
 if (!showSpinner) loadingLayer.hideNow("update");
 loadingLayer.setActive(showSpinner);
 if (showSpinner != lastLoadingShown) {
 lastLoadingShown = showSpinner;
 AppLog.i(
 "PlayerChrome",
 "loading update active="
 + showSpinner
 + " spinner="
 + showSpinner
 + " prepared="
 + a.prepared
 + " mini="
 + a.miniMode
 + " overlay="
 + (a.playbackLoading != null)
 + " layerVisible="
 + (loadingLayer.getVisibility() == View.VISIBLE));
 }

 // 中央播放钮：自绘图标跟随播放状态；未就绪时降透明度
 if (stateIcon != null) {
 stateIcon.setPlaying(a.playing);
 AppLog.i("PlayerCenter", "icon=" + (a.playing ? "pause" : "play"));
 } else UiIcon.setIcon(state, a.playing ? UiIcon.PAUSE : UiIcon.PLAY);
 state.setAlpha(!a.prepared ? 0.45f : 1f);
 state.setContentDescription(
 !a.prepared ? "正在准备" : a.playing ? "正在播放，点击暂停" : "已暂停，点击继续");
 state.setVisibility(progressOnlyMode ? View.INVISIBLE : View.VISIBLE);

 // 手势 / 状态提示（控制条出现时显示，随整体淡出一起消失）
 String hint = a.playStatus == null ? "" : a.playStatus.getText().toString().trim();
 if (hint.isEmpty() && a.playing && a.prepared) {
 hint = "双击暂停 · 左右滑快进 · 上下滑调亮度音量";
 }
 if (progressOnlyMode) hint = "";
 if (a.seekGesture.active) {
 // 手势 seek 中：显示 PlaybackController 写入的实时提示（快进 +N 秒 · 松开跳转）
 String seekMsg = a.playStatus == null ? "" : a.playStatus.getText().toString().trim();
 if (!seekMsg.isEmpty()) {
 gesturePill.setText(seekMsg);
 gesturePill.setVisibility(View.VISIBLE);
 }
 } else {
 gesturePill.setText(hint);
 gesturePill.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
 }

 int duration = 0;
 int position = 0;
 if (a.player != null && a.prepared) {
 duration = a.player.getDuration();
 if (duration <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
 duration = a.playingBookmark.duration;
 }
 position = a.player.getCurrentPosition();
 if (a.playbackControls != null) {
 if (a.playbackControls.isUserSeeking()) {
 position = a.playbackControls.timelineProgress();
 } else {
 position = a.playbackControls.displayPosition(position);
 }
 }
 }
 boolean hours = duration >= 3600 * 1000;
 timeCurrent.setText(fmtTime(position, hours));
 timeTotal.setText(
 duration > 0
 ? fmtTime(duration, hours)
 : (a.isLive() ? "直播" : "--:--"));
 long now = System.currentTimeMillis();
 clockBig.setText(DateFormatHour(now));
 if (duration > 0 && !a.isLive()) {
 long end = now + Math.max(0, duration - position);
 clockEnd.setText("影片结束 " + DateFormatHour(end));
 clockEnd.setVisibility(View.VISIBLE);
 } else {
 clockEnd.setText(a.isLive() ? "直播中" : "影片结束 --:--:--");
 }
 }

 private String fmtTime(int ms, boolean hours) {
 int sec = Math.max(0, ms / 1000);
 if (hours) {
 return String.format(
 java.util.Locale.ROOT,
 "%02d:%02d:%02d",
 sec / 3600,
 (sec / 60) % 60,
 sec % 60);
 }
 return String.format(java.util.Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
 }

 private String DateFormatHour(long millis) {
 return android.text.format.DateFormat.format("HH:mm:ss", millis).toString();
 }

 // ====================== 1.7.9 加载层 ======================

 /**
 * 播放器内嵌加载层（按 docs/DESIGN_LOADING_1.7.9.md）：
 * 圆形帧动画 280ms/帧 + 140ms 交叉淡入、双层光晕呼吸 2.4s、整体浮动 ±4dp/3.2s、
 * 「正在加载」+ 400ms 省略号（TV 无）、条件进度条、失败态、150ms 进出场。
 */
 static final class LoadingLayer extends FrameLayout {
 static final long FRAME_MS = 280L;
 static final long CROSS_MS = 140L;
 static final long GLOW_MS = 2400L;
 static final long FLOAT_MS = 3200L;
 static final long DOTS_MS = 400L;
 static final long FADE_MS = 150L;

 private static Bitmap[] frames;
 private static boolean framesLoading;

 final MainActivity a;
 final boolean tv;
 LinearLayout column;
 FrameLayout glowBox, frameBox;
 Glow glow;
 ImageView frameA, frameB;
 TextView status, episode, reason;
 LoadingBar bar;
 Button retry;

 int frameIndex;
 boolean showingA = true;
 boolean active;
 boolean paused;
 boolean failed;
 boolean lowPower;
 float baseOffsetY;
 int dots;

 ValueAnimator crossAnimator, glowAnimator, floatAnimator, fadeAnimator;

 final Runnable frameTicker =
 new Runnable() {
 @Override
 public void run() {
 if (!active || paused || !isShown()) return;
 crossFade();
 a.ui.postDelayed(this, FRAME_MS);
 }
 };
 Runnable hideTask;
 final Runnable dotsTicker =
 new Runnable() {
 @Override
 public void run() {
 if (!active || paused || tv || !isShown()) return;
 dots = (dots + 1) % 4;
 StringBuilder text = new StringBuilder("正在加载");
 for (int i = 0; i < dots; i++) text.append('.');
 status.setText(text.toString());
 a.ui.postDelayed(this, DOTS_MS);
 }
 };

 LoadingLayer(MainActivity host, Cms.Episode ep) {
 super(host);
 a = host;
 tv = host.isTvDevice();
 setTag("player-loading");
 setBackgroundColor(0x66000000);
 setClickable(true);
 setFocusable(false);
 setAlpha(0f);
 setVisibility(View.GONE);

 int avatar = a.dp(tv ? 260 : 168);
 int glowSize = a.dp(tv ? 340 : 220);
 column = a.vertical();
 column.setGravity(Gravity.CENTER_HORIZONTAL);

 glowBox = new FrameLayout(a);
 glow = new Glow(a);
 glowBox.addView(glow, new FrameLayout.LayoutParams(-1, -1));
 frameBox = new FrameLayout(a);
 frameA = frame(a, avatar);
 frameB = frame(a, avatar);
 frameB.setAlpha(0f);
 frameBox.addView(frameA, new FrameLayout.LayoutParams(-1, -1));
 frameBox.addView(frameB, new FrameLayout.LayoutParams(-1, -1));
 FrameLayout.LayoutParams frameLp =
 new FrameLayout.LayoutParams(avatar, avatar, Gravity.CENTER);
 glowBox.addView(frameBox, frameLp);
 column.addView(glowBox, new LinearLayout.LayoutParams(glowSize, glowSize));

 status = a.text(tv ? "正在加载" : "正在加载", tv ? 15 : 13, UiTokens.TEXT_PRIMARY);
 status.setGravity(Gravity.CENTER);
 status.setMinWidth(a.dp(tv ? 150 : 96));
 LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
 statusLp.topMargin = a.dp(tv ? 18 : 16);
 column.addView(status, statusLp);

 String epName = ep == null || ep.name == null ? "" : ep.name.trim();
 episode = a.text(epName, tv ? 13 : 11, UiTokens.TEXT_SECONDARY);
 episode.setGravity(Gravity.CENTER);
 episode.setMaxLines(1);
 episode.setEllipsize(TextUtils.TruncateAt.END);
 episode.setVisibility(epName.isEmpty() ? View.GONE : View.VISIBLE);
 LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-2, -2);
 epLp.topMargin = a.dp(tv ? 5 : 4);
 column.addView(episode, epLp);

 bar = new LoadingBar(a);
 bar.setVisibility(View.GONE);
 LinearLayout.LayoutParams barLp =
 new LinearLayout.LayoutParams(a.dp(tv ? 260 : 168), a.dp(2));
 barLp.topMargin = a.dp(8);
 column.addView(bar, barLp);

 reason = a.text("", tv ? 13 : 11, UiTokens.TEXT_SECONDARY);
 reason.setGravity(Gravity.CENTER);
 reason.setMaxLines(2);
 reason.setEllipsize(TextUtils.TruncateAt.END);
 reason.setVisibility(View.GONE);
 LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(-2, -2);
 reasonLp.topMargin = a.dp(6);
 reasonLp.leftMargin = a.dp(24);
 reasonLp.rightMargin = a.dp(24);
 column.addView(reason, reasonLp);

 retry =
 a.button(
 "重试", "player-loading-retry", () -> {
 if (retryAction != null) retryAction.run();
 });
 retry.setTextSize(tv ? 14 : 13);
 retry.setTextColor(UiTokens.TEXT_PRIMARY);
 retry.setBackground(UiTokens.round(a, 20, 0x1affffff, 0x33ffffff));
 retry.setVisibility(View.GONE);
 LinearLayout.LayoutParams retryLp =
 new LinearLayout.LayoutParams(
 a.dp(tv ? 160 : 120), a.dp(tv ? 48 : 44));
 retryLp.topMargin = a.dp(12);
 column.addView(retry, retryLp);

 addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 addOnAttachStateChangeListener(
 new View.OnAttachStateChangeListener() {
 @Override
 public void onViewAttachedToWindow(View v) {
 refreshBaseOffset();
 if (active) resume();
 }

 @Override
 public void onViewDetachedFromWindow(View v) {
 pause();
 frameA.setImageBitmap(null);
 frameB.setImageBitmap(null);
 }
 });
 }

 private static ImageView frame(MainActivity a, int size) {
 ImageView view = new ImageView(a);
 view.setScaleType(ImageView.ScaleType.FIT_CENTER);
 return view;
 }

 Runnable retryAction;

 // ---------------- 状态 ----------------

 void setEpisode(String epName) {
 String value = epName == null ? "" : epName.trim();
 episode.setText(value);
 episode.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
 }

 void setActive(boolean value) {
 if (active == value) return;
 active = value;
 AppLog.i(
 "PlayerChrome",
 "loading active="
 + value
 + " layerVisible="
 + (getVisibility() == View.VISIBLE)
 + " frames="
 + (frames != null && frames.length == 7));
 if (value) showLayer();
 else hideLayer();
 }

 boolean isActiveVisible() {
 return active && getVisibility() == View.VISIBLE;
 }

 void setProgress(Float fraction) {
 if (fraction == null || fraction < 0f || fraction > 1f) {
 bar.setVisibility(View.GONE);
 return;
 }
 bar.setProgress(fraction);
 bar.setVisibility(View.VISIBLE);
 }

 void showError(String message, Runnable retryRunnable) {
 failed = true;
 retryAction = retryRunnable;
 pause();
 frameBox.setAlpha(0.55f);
 status.setText("加载失败");
 reason.setText(message == null ? "" : message);
 reason.setVisibility(message == null || message.trim().isEmpty() ? View.GONE : View.VISIBLE);
 bar.setVisibility(View.GONE);
 retry.setVisibility(View.VISIBLE);
 retry.requestFocus();
 }

 private void showLayer() {
 failed = false;
 frameBox.setAlpha(1f);
 status.setText("正在加载");
 reason.setVisibility(View.GONE);
 retry.setVisibility(View.GONE);
 bar.setVisibility(View.GONE);
 setVisibility(View.VISIBLE);
 fadeTo(1f);
 start();
 }

 private void hideLayer() {
 hideNow("setActive(false)");
 }

 /** 1.8.4 修复：立即隐藏，绝不因 stopAnimationsNow 取消淡出任务而永久留在画面上。 */
 void hideNow(String reason) {
 boolean wasVisible = getVisibility() == View.VISIBLE;
 if (wasVisible || getAlpha() > 0f)
 AppLog.i(
 "PlayerChrome",
 "loading hide requested reason="
 + reason
 + " prepared="
 + a.prepared
 + " alpha="
 + getAlpha()
 + " visible="
 + wasVisible);
 stopAnimationsNow();
 if (fadeAnimator != null) {
 fadeAnimator.cancel();
 fadeAnimator = null;
 }
 setAlpha(0f);
 setVisibility(View.GONE);
 if (wasVisible) AppLog.i("PlayerChrome", "loading hidden reason=" + reason);
 }

 private void fadeTo(float target) {
 if (fadeAnimator != null) fadeAnimator.cancel();
 float from = getAlpha();
 fadeAnimator = ValueAnimator.ofFloat(from, target);
 fadeAnimator.setDuration(FADE_MS);
 fadeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 fadeAnimator.addUpdateListener(v -> setAlpha((float) v.getAnimatedValue()));
 fadeAnimator.start();
 }

 // ---------------- 动效 ----------------

 void pause() {
 paused = true;
 stopAnimationsNow();
 }

 /** 1.8.3：立即停止帧 ticker/光晕/浮动/淡入淡出，播放开始后不留 280ms 周期任务。 */
 void stopAnimationsNow() {
 a.ui.removeCallbacks(frameTicker);
 a.ui.removeCallbacks(dotsTicker);
 if (hideTask != null) {
 a.ui.removeCallbacks(hideTask);
 hideTask = null;
 }
 boolean glowOn = glowAnimator != null && glowAnimator.isRunning();
 boolean floatOn = floatAnimator != null && floatAnimator.isRunning();
 if (crossAnimator != null) crossAnimator.cancel();
 if (glowAnimator != null) glowAnimator.cancel();
 if (floatAnimator != null) floatAnimator.cancel();
 if (fadeAnimator != null) fadeAnimator.cancel();
 if (!glowOn && !floatOn && !active && !paused) return;
 AppLog.i(
 "PlayerChrome",
 "loading animator stop glow="
 + glowOn
 + " float="
 + floatOn
 + " ticker="
 + active
 + " paused="
 + paused);
 }

 void resume() {
 paused = false;
 if (!active || failed) return;
 start();
 refreshBaseOffset();
 }

 private void start() {
 paused = false;
 ensureFrames(a);
 if (frames == null || frames.length == 0) return;
 // 1.8.0 安全模式：不做帧切换/光晕/浮动/省略号，只显示静态帧，减少重绘
 if (SafeMode.enabled(a)) {
 frameA.setImageBitmap(frames[0]);
 frameA.setAlpha(1f);
 frameB.setAlpha(0f);
 glow.setBreath(0.5f);
 glowBox.setScaleX(1f);
 glowBox.setScaleY(1f);
 AppLog.i("SafeMode", "loading animation disabled (static frame)");
 return;
 }
 if (frameA.getDrawable() == null) {
 frameIndex = 0;
 frameA.setImageBitmap(frames[0]);
 frameA.setAlpha(1f);
 frameB.setAlpha(0f);
 showingA = true;
 }
 refreshBaseOffset();
 boolean reduced = false;
 try {
 reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
 } catch (Throwable ignored) {
 }
 lowPower = reduced || isLowRam(a);
 a.ui.removeCallbacks(frameTicker);
 a.ui.postDelayed(frameTicker, FRAME_MS);
 if (!tv && !lowPower) {
 a.ui.removeCallbacks(dotsTicker);
 a.ui.postDelayed(dotsTicker, DOTS_MS);
 }
 if (!lowPower) {
 startGlow();
 startFloat();
 } else {
 glow.setBreath(0.5f);
 glowBox.setScaleX(1f);
 glowBox.setScaleY(1f);
 AppLog.i("PlayerChrome", "loading low-power mode (frames only)");
 }
 }

 static boolean isLowRam(MainActivity a) {
 try {
 android.app.ActivityManager am =
 (android.app.ActivityManager)
 a.getSystemService(android.content.Context.ACTIVITY_SERVICE);
 return am != null && am.isLowRamDevice();
 } catch (Throwable ignored) {
 return false;
 }
 }

 private void crossFade() {
 Bitmap[] cache = frames;
 if (cache == null || cache.length == 0) return;
 frameIndex = (frameIndex + 1) % cache.length;
 if (lowPower) {
 frameA.setImageBitmap(cache[frameIndex]);
 frameA.setAlpha(1f);
 frameB.setAlpha(0f);
 showingA = true;
 return;
 }
 final ImageView next = showingA ? frameB : frameA;
 final ImageView old = showingA ? frameA : frameB;
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
 glow.setBreath(t);
 float scale = 0.96f + 0.08f * t;
 glowBox.setScaleX(scale);
 glowBox.setScaleY(scale);
 });
 glowAnimator.start();
 }

 private void startFloat() {
 if (floatAnimator != null) floatAnimator.cancel();
 floatAnimator = ValueAnimator.ofFloat(0f, 1f);
 floatAnimator.setDuration(FLOAT_MS);
 floatAnimator.setRepeatCount(ValueAnimator.INFINITE);
 floatAnimator.setRepeatMode(ValueAnimator.REVERSE);
 floatAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
 floatAnimator.addUpdateListener(
 v -> {
 float t = (float) v.getAnimatedValue();
 float offset = (t * 2f - 1f) * a.dp(4);
 column.setTranslationY(baseOffsetY + offset);
 });
 floatAnimator.start();
 }

 private void refreshBaseOffset() {
 int height = getHeight();
 if (height <= 0) return;
 baseOffsetY = -(height * (tv ? 0.03f : 0.04f));
 column.setTranslationY(baseOffsetY);
 }

 @Override
 protected void onSizeChanged(int w, int h, int oldw, int oldh) {
 super.onSizeChanged(w, h, oldw, oldh);
 refreshBaseOffset();
 }

 @Override
 protected void onWindowVisibilityChanged(int visibility) {
 super.onWindowVisibilityChanged(visibility);
 if (visibility == View.VISIBLE) {
 if (active) resume();
 } else {
 pause();
 }
 }

 static synchronized void ensureFrames(MainActivity a) {
 if (frames != null || framesLoading) return;
 framesLoading = true;
 try {
 Bitmap[] cache = new Bitmap[7];
 for (int i = 0; i < cache.length; i++) {
 String name =
 String.format(
 java.util.Locale.ROOT, "loading/frame_%02d.webp", i + 1);
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
 AppLog.i(
 "PlayerChrome",
 "loading frames loaded=" + (frames != null) + " count=" + cache.length);
 } finally {
 framesLoading = false;
 }
 }
 }

 /** 双层径向光晕：蓝紫 #8FA8FF + 粉 #FFB3C8，呼吸由 LoadingLayer 驱动。 */
 static final class Glow extends View {
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
 float blueAlpha = 0.35f + 0.35f * breath;
 float pinkAlpha = 0.10f + 0.08f * breath;
 blue.setShader(
 new RadialGradient(
 cx,
 cy,
 radius,
 new int[] {0xFF8FA8FF, 0x008FA8FF},
 null,
 Shader.TileMode.CLAMP));
 blue.setAlpha((int) (255 * blueAlpha));
 c.drawCircle(cx, cy, radius, blue);
 pink.setShader(
 new RadialGradient(
 cx,
 cy,
 radius * 1.12f,
 new int[] {0xFFFFB3C8, 0x00FFB3C8},
 null,
 Shader.TileMode.CLAMP));
 pink.setAlpha((int) (255 * pinkAlpha));
 c.drawCircle(cx, cy, radius * 1.12f, pink);
 }
 }

 /** 2dp 条件进度条：轨道 #33FFFFFF、已缓冲 #FF8FB1。 */
 static final class LoadingBar extends View {
 final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
 final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
 float progress;

 LoadingBar(MainActivity a) {
 super(a);
 track.setColor(0x33FFFFFF);
 fill.setColor(0xFFFF8FB1);
 }

 void setProgress(float value) {
 progress = Math.max(0f, Math.min(1f, value));
 invalidate();
 }

 @Override
 protected void onDraw(Canvas c) {
 super.onDraw(c);
 float h = getHeight();
 float r = h / 2f;
 c.drawRoundRect(new RectF(0, 0, getWidth(), h), r, r, track);
 if (progress > 0f)
 c.drawRoundRect(
 new RectF(0, 0, getWidth() * progress, h), r, r, fill);
 }
 }
}
