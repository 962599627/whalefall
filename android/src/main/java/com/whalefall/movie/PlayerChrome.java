package com.whalefall.movie;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/**
 * 播放器悬浮 UI：顶部标题/返回、右上角图标组、右侧工具 Dock 外层、中央播放区、底部进度与时间。
 *
 * <p>播放器是内容优先场景，走克制的深色方案：顶部/底部只用**纯黑渐变**，右侧 Dock 用接近
 * 不透明的深色卡片，所有按钮都是「深色底 + 白色图标」，只有激活态用樱花粉。不使用大面积
 * 液态玻璃，避免和暖色画面混成脏色。图标全部由 {@link PlayerUiKit} 纯 Canvas 绘制。
 */
final class PlayerChrome {
    final MainActivity a;
    final FrameLayout overlay;
    final View topShade, bottomShade;
    final LinearLayout dock, progress, heading;
    final LinearLayout loadingPill;
    final LinearLayout centerRow;
    final LinearLayout topActions;
    final TextView loadingText, episodeLabel, titleView, seekHint;
    final Button fsBtn;
    final PlayerUiKit.PlayPauseButton state;
    final TextView resolution;
    final TextView seekBubble;
    final PlayerUiKit.IconView dockFold;
    /** 弹层锚点：Dock 左侧 12dp、屏幕 72% 高处，避免弹层被视频/Dock 遮挡。 */
    View menuAnchor;
    boolean loading = true;
    boolean progressOnlyMode = false;
    final Rect windowInsets = new Rect();
    boolean layoutPending;

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

        // ---------------- 纯黑渐变遮罩（不掺蓝，避免和暖色画面混色） ----------------
        topShade = new View(a);
        topShade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xd9000000, 0xa6000000, 0x00000000}));
        overlay.addView(topShade, new FrameLayout.LayoutParams(-1, a.dp(150), Gravity.TOP));
        bottomShade = new View(a);
        bottomShade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x00000000, 0x8c000000, 0xd1000000}));
        overlay.addView(bottomShade, new FrameLayout.LayoutParams(-1, a.dp(160), Gravity.BOTTOM));

        // ---------------- 顶部：返回圆钮 + 两行标题 + 编码胶囊 ----------------
        heading = a.vertical();
        heading.setTag("player-heading");
        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        PlayerUiKit.IconView back =
                new PlayerUiKit.IconView(a, PlayerUiKit.ICON_BACK, 0xfff2f6ff, a.dp(20));
        back.setTag("player-back");
        back.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x4dffffff, Math.max(1, a.dp(1))));
        back.setOnClickListener(v -> a.leavePlayback());
        MobileDetailScreen.bindTouchFeedback(back);
        titleRow.addView(back, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));

        LinearLayout titleCol = a.vertical();
        titleCol.setPadding(a.dp(12), 0, a.dp(10), 0);
        titleView = a.text(video.title, 18, MainActivity.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleView.getPaint().setFakeBoldText(true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(a.dp(10), 0, a.dp(2), 0xe6000000);
        titleView.setIncludeFontPadding(false);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(4), 0, 0);
        String line = a.activeLine == null ? "" : a.activeLine.name;
        String epName = episode == null || episode.name == null ? "" : episode.name;
        episodeLabel = a.text(epName + (line.isEmpty() ? "" : "  ·  " + line), 11, 0xffd6dfec);
        episodeLabel.setTag("player-episode");
        episodeLabel.setSingleLine(true);
        episodeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        episodeLabel.setShadowLayer(a.dp(6), 0, a.dp(1), 0xcc000000);
        subRow.addView(episodeLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        resolution = a.text("分辨率读取中", 10, 0xffe2ebf8);
        resolution.setTag("player-resolution");
        resolution.setSingleLine(true);
        resolution.setEllipsize(android.text.TextUtils.TruncateAt.END);
        resolution.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
        resolution.setBackground(
                PlayerUiKit.capsuleBg(
                        0x99000000,
                        0x33ffffff,
                        Math.max(1, a.dp(0.7f)),
                        a.dp(999)));
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(-2, -2);
        resLp.leftMargin = a.dp(8);
        subRow.addView(resolution, resLp);
        titleCol.addView(subRow, new LinearLayout.LayoutParams(-1, -2));

        a.playStatus = a.text("正在加载所选剧集…", 11, 0xffa9b7d0);
        a.playStatus.setSingleLine(true);
        a.playStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.playStatus.setPadding(0, a.dp(4), 0, 0);
        a.playStatus.setShadowLayer(a.dp(6), 0, a.dp(1), 0xcc000000);
        titleCol.addView(a.playStatus, new LinearLayout.LayoutParams(-1, -2));
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        heading.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(heading, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---------------- 右上角悬浮图标组（全屏 / 更多） ----------------
        topActions = a.row();
        topActions.setGravity(Gravity.CENTER_VERTICAL);
        PlayerUiKit.IconView fsIcon =
                new PlayerUiKit.IconView(a, PlayerUiKit.ICON_FULLSCREEN, 0xfff2f6ff, a.dp(19));
        fsIcon.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x40ffffff, Math.max(1, a.dp(1))));
        fsIcon.setOnClickListener(v -> a.togglePlayerOrientation());
        MobileDetailScreen.bindTouchFeedback(fsIcon);
        topActions.addView(fsIcon, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));
        PlayerUiKit.IconView moreIcon =
                new PlayerUiKit.IconView(a, PlayerUiKit.ICON_MORE, 0xfff2f6ff, a.dp(19));
        moreIcon.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x40ffffff, Math.max(1, a.dp(1))));
        moreIcon.setOnClickListener(
                v -> {
                    if (a.playbackControls != null) {
                        a.playbackControls.menuAnchor = menuAnchor;
                        a.playbackControls.more();
                    }
                });
        MobileDetailScreen.bindTouchFeedback(moreIcon);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        moreLp.leftMargin = a.dp(8);
        topActions.addView(moreIcon, moreLp);
        overlay.addView(
                topActions, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));

        // ---------------- 底部：进度 + 时间 + 退出全屏 ----------------
        progress = a.vertical();
        progress.setTag("player-progress");
        LinearLayout clockRow = a.row();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        a.playClock = a.text("--:-- / --:--", 13, MainActivity.WHITE);
        a.playClock.setTypeface(null, Typeface.BOLD);
        a.playClock.setIncludeFontPadding(false);
        a.playClock.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        a.playClock.setShadowLayer(a.dp(6), 0, a.dp(1), 0xcc000000);
        clockRow.addView(a.playClock, new LinearLayout.LayoutParams(0, -2, 1f));

        seekHint = a.text("", 11.5f, 0xffffb3c8);
        seekHint.setTag("player-seek-hint");
        seekHint.setSingleLine(true);
        seekHint.setGravity(Gravity.CENTER);
        seekHint.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        seekHint.setBackground(
                PlayerUiKit.capsuleBg(
                        0x33000000, 0x4dffb3c8, Math.max(1, a.dp(0.7f)), a.dp(999)));
        seekHint.setVisibility(View.GONE);
        clockRow.addView(seekHint, new LinearLayout.LayoutParams(-2, -2));

        if (!a.isTvDevice()) {
            fsBtn = a.button("退出全屏", "player-fullscreen", a::togglePlayerOrientation);
            fsBtn.setTextSize(11.5f);
            fsBtn.setTextColor(0xffe8eff9);
            fsBtn.setAllCaps(false);
            fsBtn.setSingleLine(true);
            fsBtn.setMinWidth(0);
            fsBtn.setMinimumWidth(0);
            fsBtn.setMinHeight(0);
            fsBtn.setMinimumHeight(0);
            fsBtn.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            fsBtn.setBackground(
                    PlayerUiKit.capsuleBg(
                            0x99000000, 0x40ffffff, Math.max(1, a.dp(0.8f)), a.dp(999)));
            PlayerUiKit.setLeftIcon(fsBtn, PlayerUiKit.ICON_ORIENTATION, 0xffe8eff9, a.dp(15));
            fsBtn.setCompoundDrawablePadding(a.dp(5));
            LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(-2, a.dp(32));
            fsLp.leftMargin = a.dp(10);
            clockRow.addView(fsBtn, fsLp);
        } else {
            fsBtn = null;
        }
        progress.addView(clockRow, new LinearLayout.LayoutParams(-1, -2));
        progress.setVisibility(live ? View.GONE : View.VISIBLE);
        overlay.addView(progress, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // 拖拽时间气泡：白底黑字（亮画面/暗画面都清楚）
        seekBubble = a.text("", 12, 0xff1a1a1a);
        seekBubble.setTypeface(null, Typeface.BOLD);
        seekBubble.setSingleLine(true);
        seekBubble.setIncludeFontPadding(false);
        seekBubble.setGravity(Gravity.CENTER);
        seekBubble.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
        seekBubble.setBackground(
                PlayerUiKit.capsuleBg(
                        0xf2ffffff, 0x33000000, Math.max(1, a.dp(0.7f)), a.dp(10)));
        seekBubble.setVisibility(View.GONE);
        overlay.addView(
                seekBubble,
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        // ---------------- 右侧工具 Dock（接近不透明的深色卡片） ----------------
        dock = a.vertical();
        dock.setTag("player-dock");
        dock.setClipChildren(true);
        dock.setClipToPadding(true);
        dock.setBackground(
                PlayerUiKit.capsuleBg(
                        0xe60b1220, 0x26ffffff, Math.max(1, a.dp(1)), a.dp(18)));
        UiCompat.setElevation(dock, a.dp(10));
        overlay.addView(
                dock, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));

        // Dock 折叠钮：放在 Dock 卡片头部右上角（28dp 圆钮）
        dockFold =
                new PlayerUiKit.IconView(
                        a, PlayerUiKit.ICON_CHEVRON_RIGHT, 0xfff2f6ff, a.dp(14));
        dockFold.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x40ffffff, Math.max(1, a.dp(0.8f))));
        dockFold.setOnClickListener(
                v -> {
                    if (a.playbackControls != null) a.playbackControls.toggleDockCollapsed();
                });
        MobileDetailScreen.bindTouchFeedback(dockFold);
        dockFold.setVisibility(View.GONE);
        overlay.addView(
                dockFold,
                new FrameLayout.LayoutParams(a.dp(28), a.dp(28), Gravity.TOP | Gravity.LEFT));

        // 弹层锚点：Dock 左侧 12dp、72% 高度处（不可见，只用于 PlayerBubble 定位）
        menuAnchor = new View(a);
        menuAnchor.setVisibility(View.INVISIBLE);
        menuAnchor.setFocusable(false);
        overlay.addView(
                menuAnchor, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));

        // ---------------- 中央播放区：64dp 深色圆 + 15s 深色圆钮 ----------------
        state = new PlayerUiKit.PlayPauseButton(a);
        state.setTag("player-state");
        state.setFocusable(false);
        state.setVisibility(View.GONE);
        state.setOnClickListener(
                v -> {
                    a.togglePlayback();
                    a.showControls(true);
                });
        if (!a.isTvDevice()) {
            centerRow = a.row();
            centerRow.setGravity(Gravity.CENTER);
            View rewind = buildSkipCircle(-15000, false);
            View forward = buildSkipCircle(15000, true);
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(a.dp(56), a.dp(56));
            LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(a.dp(64), a.dp(64));
            centerLp.leftMargin = a.dp(26);
            centerLp.rightMargin = a.dp(26);
            centerRow.addView(rewind, sideLp);
            centerRow.addView(state, centerLp);
            centerRow.addView(forward, sideLp);
            overlay.addView(centerRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        } else {
            centerRow = null;
            overlay.addView(state, new FrameLayout.LayoutParams(a.dp(72), a.dp(72), Gravity.CENTER));
        }

        a.playbackLayer.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        // ---------------- 加载胶囊 ----------------
        loadingPill = a.row();
        loadingPill.setTag("player-loading");
        loadingPill.setGravity(Gravity.CENTER_VERTICAL);
        loadingPill.setPadding(a.dp(20), a.dp(13), a.dp(20), a.dp(13));
        loadingPill.setBackground(
                PlayerUiKit.capsuleBg(
                        0xe60b1220, 0x26ffffff, Math.max(1, a.dp(0.8f)), a.dp(20)));
        UiCompat.setElevation(loadingPill, a.dp(10));
        loadingPill.addView(
                new PlaybackLoading.Ring(a), new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        String epTitle =
                episode != null && episode.name != null && !episode.name.isEmpty()
                        ? " " + episode.name
                        : "";
        loadingText = a.text("正在加载" + epTitle + "…", 13, MainActivity.WHITE);
        loadingText.setPadding(a.dp(12), 0, 0, 0);
        loadingPill.addView(loadingText);
        a.playbackLayer.addView(
                loadingPill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        a.playerOverlay = overlay;
        a.controlsVisible = true;
        dock.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    if (b - t != ob - ot) scheduleLayout();
                });
        scheduleLayout();
    }

    /** 左右 15 秒圆钮：点击回跳/前跳并给一点按压反馈。 */
    View buildSkipCircle(int deltaMs, boolean forward) {
        final PlayerUiKit.SkipCircle btn = new PlayerUiKit.SkipCircle(a, forward);
        btn.setOnClickListener(
                v -> {
                    if (a.player != null && a.prepared) {
                        int dur = Math.max(1, a.player.getDuration());
                        int cur =
                                a.playbackControls != null
                                        ? a.playbackControls.displayPosition(
                                                a.player.getCurrentPosition())
                                        : a.player.getCurrentPosition();
                        int next = Math.max(0, Math.min(dur, cur + deltaMs));
                        if (a.playbackControls != null) a.playbackControls.recordUserSeek(next);
                        a.player.seekTo(next);
                        if (a.playClock != null && dur > 1) {
                            a.playClock.setText(
                                    PlaybackController.formatTime(next)
                                            + " / "
                                            + PlaybackController.formatTime(dur));
                        }
                    }
                    a.showControls(true);
                });
        MobileDetailScreen.bindTouchFeedback(btn);
        return btn;
    }

    // ---------------- 时间气泡 ----------------

    void showSeekBubble(int positionMs, int durationMs) {
        if (seekBubble == null || a.playbackControls == null) return;
        final SeekBar timeline = a.playbackControls.timeline;
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
                    int x = t[0] - o[0] + Math.round(fraction * timeline.getWidth());
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

    /** Dock 折叠状态变化后刷新折叠钮方向并重新布局。 */
    void onDockToggled() {
        boolean collapsed = a.playbackControls != null && a.playbackControls.dockCollapsed;
        if (dockFold != null) {
            dockFold.setIcon(
                    collapsed
                            ? PlayerUiKit.ICON_CHEVRON_LEFT
                            : PlayerUiKit.ICON_CHEVRON_RIGHT);
        }
        scheduleLayout();
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
        // A rotation request is not a measurement. OEM bars, multi-window and
        // embedded playback can all leave this layer smaller than the display.
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
        boolean portrait = measuredWidth < measuredHeight;
        boolean mobileLandscape = !a.isTvDevice() && !portrait;
        boolean compact = mobileLandscape && height < a.dp(420);

        if (titleView != null) {
            titleView.setTextSize(portrait || compact ? 17 : 18);
        }
        if (episodeLabel != null) {
            episodeLabel.setVisibility(compact && height < a.dp(320) ? View.GONE : View.VISIBLE);
        }
        if (a.playStatus != null) a.playStatus.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (fsBtn != null) {
            fsBtn.setText(portrait ? "全屏横屏" : "退出全屏");
        }

        int side = Math.max(0, Math.min(a.dp(portrait ? 18 : 24), width / 8));
        int topMargin = Math.max(0, Math.min(a.dp(compact ? 12 : 20), height / 8));
        int dockRight = Math.min(a.dp(10), side);
        int dockTop = topMargin + a.dp(52);

        int dockWidth = 0;
        int dockHeight = 0;
        if (!a.isTvDevice()) {
            if (mobileLandscape) {
                int wanted = a.playbackControls == null ? a.dp(150) : a.playbackControls.dockWidth();
                dockWidth = Math.max(0, Math.min(wanted, width - side * 2));
                dockHeight = Math.max(0, Math.min(a.dp(340), height - dockTop - a.dp(96)));
                dock.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
                place(
                        dock,
                        dockWidth,
                        dockHeight,
                        Gravity.TOP | Gravity.RIGHT,
                        0,
                        dockTop,
                        dockRight,
                        0);
            } else {
                int maxDock = a.dp(560);
                dockWidth = Math.max(0, Math.min(maxDock, width - side * 2));
                dockHeight = -2;
                dock.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
                place(
                        dock,
                        dockWidth,
                        -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                        0,
                        0,
                        0,
                        a.dp(14));
            }
        } else {
            int maxDock = a.dp(612);
            dockWidth = Math.max(0, Math.min(maxDock, width - side * 2));
            place(
                    dock,
                    dockWidth,
                    -2,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                    0,
                    0,
                    0,
                    a.dp(14));
        }

        // 进度条：铺在底部纯黑遮罩内，左右 24dp、距底 18dp
        int progressLeft = Math.max(0, Math.min(a.dp(portrait ? 18 : 24), width / 8));
        int progressRight = progressLeft;
        int trackWidth = Math.max(0, Math.min(a.dp(960), width - progressLeft - progressRight));
        int progressHeight = Math.max(progress.getMeasuredHeight(), a.dp(72));
        int progressBottom =
                mobileLandscape
                        ? a.dp(18)
                        : Math.max(a.dp(88), dock.getMeasuredHeight() + a.dp(26));
        progressBottom = Math.max(0, Math.min(progressBottom, height - progressHeight));
        place(
                progress,
                trackWidth,
                -2,
                Gravity.BOTTOM | Gravity.LEFT,
                progressLeft,
                0,
                progressRight,
                progressBottom);

        int headingRight = mobileLandscape || !a.isTvDevice() ? side + a.dp(96) : side;
        place(
                heading,
                -1,
                -2,
                Gravity.TOP,
                side,
                topMargin,
                Math.min(headingRight, width / 3),
                0);
        place(
                topActions,
                -2,
                -2,
                Gravity.TOP | Gravity.RIGHT,
                0,
                Math.max(a.dp(6), topMargin - a.dp(4)),
                side,
                0);

        // Dock 折叠钮：Dock 卡片头部右上角
        boolean showFold = mobileLandscape;
        dockFold.setVisibility(showFold ? View.VISIBLE : View.GONE);
        if (showFold) {
            boolean collapsed = a.playbackControls != null && a.playbackControls.dockCollapsed;
            dockFold.setIcon(
                    collapsed
                            ? PlayerUiKit.ICON_CHEVRON_LEFT
                            : PlayerUiKit.ICON_CHEVRON_RIGHT);
            int foldSize = a.dp(28);
            place(
                    dockFold,
                    foldSize,
                    foldSize,
                    Gravity.TOP | Gravity.LEFT,
                    Math.max(0, width - dockRight - a.dp(6) - foldSize),
                    dockTop + a.dp(6),
                    0,
                    0);
        }

        // 弹层锚点：Dock 左侧 12dp、72% 高度
        int anchorX = Math.max(0, width - dockRight - dockWidth - a.dp(12));
        int anchorY = Math.max(0, Math.round(height * 0.72f));
        place(menuAnchor, 1, 1, Gravity.TOP | Gravity.LEFT, anchorX, anchorY, 0, 0);

        if (seekBubbleVisible()) hideSeekBubble();

        if (a.playbackControls != null) {
            a.playbackControls.relayout(mobileLandscape);
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
            resolution.setText(a.player.videoResolution());
        } else {
            resolution.setText(width > 0 && height > 0 ? width + " × " + height : "分辨率未知");
        }
    }

    void setLoading(boolean value) {
        loading = value;
        update();
    }

    void switchEpisode(Cms.Episode episode, Cms.Line line) {
        String lineName = line == null || line.name == null ? "" : line.name;
        String epName = episode == null || episode.name == null ? "" : episode.name;
        episodeLabel.setText(epName + (lineName.isEmpty() ? "" : "  ·  " + lineName));
        resolution.setText("分辨率读取中");
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
        topActions.setVisibility(otherVis);
        dock.setVisibility(otherVis);
        dockFold.setVisibility(otherVis);
        seekHint.setVisibility(progressOnly ? View.VISIBLE : View.GONE);
        hideSeekBubble();
        update();
        if (a.playbackControls != null) {
            a.playbackControls.visible(a.controlsVisible);
        }
    }

    void update() {
        if (a.prepared) loading = false;
        if (a.player != null && a.prepared) resolution.setText(a.player.videoResolution());
        boolean showSpinner = loading && !a.prepared && a.playbackLoading == null && !a.miniMode;
        loadingPill.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
        int stateVis = showSpinner ? View.GONE : (progressOnlyMode ? View.INVISIBLE : View.VISIBLE);
        state.setVisibility(stateVis);
        if (centerRow != null) centerRow.setVisibility(stateVis);
        state.setContentDescription(
                !a.prepared ? "正在准备" : a.playing ? "正在播放，确认暂停" : "已暂停，确认继续");
        state.invalidate();
    }
}
