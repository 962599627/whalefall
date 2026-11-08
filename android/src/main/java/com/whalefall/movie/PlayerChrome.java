package com.whalefall.movie;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    final LinearLayout loadingPill;
    final LinearLayout topActions;
    TextView loadingText, episodeLabel, titleView, seekHint, resolution;
    final TextView timeCurrent, timeTotal, clockBig, clockEnd, gesturePill;
    final ImageButton backBtn, moreBtn, foldBtn, state, seekBack, seekFwd;
    final TextView seekBubble;
    /** 弹层锚点（不可见）：位于底部控制区上方中央，供 PlayerBubble 向上展开。 */
    View menuAnchor;
    boolean loading = true;
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
                        epName + (line.isEmpty() ? "" : "  ·  " + line),
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
        moreBtn =
                UiIcon.circleButton(
                        a,
                        UiIcon.MORE,
                        "更多工具",
                        48,
                        24,
                        UiTokens.PLAYER_BTN_FILL,
                        UiTokens.PLAYER_BTN_STROKE,
                        () -> {
                            if (a.playbackControls != null) {
                                a.playbackControls.menuAnchor = menuAnchor;
                                a.playbackControls.more();
                            }
                        });
        topRight.addView(moreBtn, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        overlay.addView(
                topRight, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
        topActions = topRight;

        // ---------------- 中央播放簇：-10s / 播放暂停 / +10s ----------------
        centerCluster = a.row();
        centerCluster.setGravity(Gravity.CENTER_VERTICAL);
        seekBack =
                UiIcon.circleButton(
                        a,
                        UiIcon.REW,
                        "快退 10 秒",
                        48,
                        24,
                        UiTokens.PLAYER_CHIP,
                        UiTokens.STROKE_SOFT,
                        () -> {
                            if (a.playbackControls != null) a.playbackControls.nudge(-10000);
                        });
        seekFwd =
                UiIcon.circleButton(
                        a,
                        UiIcon.FF,
                        "快进 10 秒",
                        48,
                        24,
                        UiTokens.PLAYER_CHIP,
                        UiTokens.STROKE_SOFT,
                        () -> {
                            if (a.playbackControls != null) a.playbackControls.nudge(10000);
                        });
        state =
                UiIcon.circleButton(
                        a,
                        UiIcon.PLAY,
                        "播放",
                        72,
                        40,
                        UiTokens.PLAYER_BTN_STRONG,
                        UiTokens.PLAYER_BTN_STROKE,
                        () -> {
                            a.togglePlayback();
                            a.showControls(true);
                        });
        state.setTag("player-state");
        UiCompat.setElevation(state, a.dp(4));
        centerCluster.addView(seekBack, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        LinearLayout.LayoutParams stateLp =
                new LinearLayout.LayoutParams(a.dp(72), a.dp(72));
        stateLp.leftMargin = a.dp(28);
        stateLp.rightMargin = a.dp(28);
        centerCluster.addView(state, stateLp);
        centerCluster.addView(seekFwd, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        overlay.addView(centerCluster, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // 直播没有可 seek 的时长，中央只留播放/暂停
        if (live) {
            seekBack.setVisibility(View.GONE);
            seekFwd.setVisibility(View.GONE);
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

        // ---------------- 加载胶囊 ----------------
        loadingPill = a.row();
        loadingPill.setTag("player-loading");
        loadingPill.setGravity(Gravity.CENTER_VERTICAL);
        loadingPill.setPadding(a.dp(20), a.dp(13), a.dp(20), a.dp(13));
        loadingPill.setBackground(
                UiTokens.round(
                        a, UiTokens.RADIUS_20, 0xe60b1220, UiTokens.STROKE_SOFT));
        UiCompat.setElevation(loadingPill, a.dp(10));
        ProgressBar spinner = new ProgressBar(a);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(UiTokens.ACCENT));
        loadingPill.addView(spinner, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
        String epTitle =
                episode != null && episode.name != null && !episode.name.isEmpty()
                        ? " " + episode.name
                        : "";
        loadingText = a.text("正在加载" + epTitle + "…", UiTokens.TYPE_BODY_SM, UiTokens.TEXT_PRIMARY);
        loadingText.setPadding(a.dp(12), 0, 0, 0);
        loadingPill.addView(loadingText);
        a.playbackLayer.addView(
                loadingPill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

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
                Math.max(a.dp(6), topMargin - a.dp(4)),
                side,
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
        update();
    }

    void switchEpisode(Cms.Episode episode, Cms.Line line) {
        String lineName = line == null || line.name == null ? "" : line.name;
        String epName = episode == null || episode.name == null ? "" : episode.name;
        episodeLabel.setText(epName + (lineName.isEmpty() ? "" : "  ·  " + lineName));
        resolution.setText("");
        String statusMsg = "正在加载" + (epName.isEmpty() ? "" : " " + epName) + "…";
        if (a.playStatus != null) a.playStatus.setText(statusMsg);
        loadingText.setText(statusMsg);
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
        loadingPill.setVisibility(showSpinner ? View.VISIBLE : View.GONE);

        // 中央播放钮：图标 + 描述跟随播放状态；未就绪时降透明度
        UiIcon.setIcon(state, a.playing ? UiIcon.PAUSE : UiIcon.PLAY);
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
}
