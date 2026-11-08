package com.whalefall.movie;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/**
 * 播放器悬浮 UI：顶部标题/分辨率、右上码率占位、底部专业控制区（进度条 + 纯图标行 +
 * 大号时钟 + 预计结束时间）、右缘折叠箭头。
 *
 * <p>播放器是内容优先场景：全黑半透明渐变保证可读，白色图标 + 单点樱花粉，不用彩色玻璃。
 * 所有图标由 {@link PlayerUiKit} 纯 Canvas 绘制；播放逻辑仍由 {@link PlaybackControls}
 * 与 {@link PlaybackController} 驱动。
 */
final class PlayerChrome {
    final MainActivity a;
    final FrameLayout overlay;
    final View topShade, bottomShade;
    final LinearLayout heading;
    final LinearLayout topRight;
    final LinearLayout bottomPanel;
    final LinearLayout clockBlock;
    final FrameLayout progress;
    final LinearLayout dock;
    final LinearLayout loadingPill;
    final LinearLayout topActions;
    TextView loadingText, episodeLabel, titleView, seekHint, resolution;
    final TextView timeCurrent, timeTotal, clockBig, clockEnd, bitrate;
    final PlayerUiKit.IconView moreBtn, foldBtn;
    final PlayerUiKit.PlayPauseButton state;
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
                        new int[] {0x00000000, 0x99000000}));
        overlay.addView(bottomShade, new FrameLayout.LayoutParams(-1, a.dp(200), Gravity.BOTTOM));

        // 外部逻辑（PlaybackController.clock）每秒写 a.playClock，这里保留一个隐藏占位避免 NPE
        a.playClock = a.text("", 1, 0);
        a.playClock.setVisibility(View.GONE);
        overlay.addView(a.playClock, new FrameLayout.LayoutParams(1, 1));

        // ---------------- 顶部：返回 + 标题/分辨率 + 码率/更多 ----------------
        heading = a.vertical();
        heading.setTag("player-heading");
        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        PlayerUiKit.IconView back =
                new PlayerUiKit.IconView(a, PlayerUiKit.ICON_BACK, 0xfff2f6ff, a.dp(18));
        back.setTag("player-back");
        back.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x40ffffff, Math.max(1, a.dp(1))));
        back.setOnClickListener(v -> a.leavePlayback());
        MobileDetailScreen.bindTouchFeedback(back);
        titleRow.addView(back, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));

        LinearLayout titleCol = a.vertical();
        titleCol.setPadding(a.dp(10), 0, a.dp(8), 0);
        titleView = a.text(video.title, 17, MainActivity.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleView.getPaint().setFakeBoldText(true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(a.dp(8), 0, a.dp(2), 0xcc000000);
        titleView.setIncludeFontPadding(false);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(3), 0, 0);
        String line = a.activeLine == null ? "" : a.activeLine.name;
        String epName = episode == null || episode.name == null ? "" : episode.name;
        episodeLabel = a.text(epName + (line.isEmpty() ? "" : "  ·  " + line), 11, 0xffc7d2e3);
        episodeLabel.setTag("player-episode");
        episodeLabel.setSingleLine(true);
        episodeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        episodeLabel.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        subRow.addView(episodeLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        resolution = a.text("--", 11, 0xffc7d2e3);
        resolution.setTag("player-resolution");
        resolution.setSingleLine(true);
        resolution.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(-2, -2);
        resLp.leftMargin = a.dp(8);
        subRow.addView(resolution, resLp);
        titleCol.addView(subRow, new LinearLayout.LayoutParams(-1, -2));

        a.playStatus = a.text("", 10.5f, 0xff8d9cb6);
        a.playStatus.setSingleLine(true);
        a.playStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.playStatus.setPadding(0, a.dp(2), 0, 0);
        a.playStatus.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        titleCol.addView(a.playStatus, new LinearLayout.LayoutParams(-1, -2));
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        heading.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(heading, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        topRight = a.row();
        topRight.setGravity(Gravity.CENTER_VERTICAL);
        moreBtn = new PlayerUiKit.IconView(a, PlayerUiKit.ICON_MORE, 0xfff2f6ff, a.dp(17));
        moreBtn.setBackground(
                PlayerUiKit.circleBg(0x66000000, 0x40ffffff, Math.max(1, a.dp(1))));
        moreBtn.setOnClickListener(
                v -> {
                    if (a.playbackControls != null) {
                        a.playbackControls.menuAnchor = menuAnchor;
                        a.playbackControls.more();
                    }
                });
        MobileDetailScreen.bindTouchFeedback(moreBtn);
        topRight.addView(moreBtn, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));

        bitrate = a.text("— Mbps", 11, 0xff8d9cb6);
        bitrate.setSingleLine(true);
        bitrate.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        LinearLayout.LayoutParams bitrateLp = new LinearLayout.LayoutParams(-2, -2);
        bitrateLp.leftMargin = a.dp(10);
        topRight.addView(bitrate, bitrateLp);
        overlay.addView(
                topRight, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));

        topActions = topRight;
        moreBtn.setContentDescription("更多");

        // ---------------- 底部控制区：时钟 + 进度 + 纯图标行 ----------------
        bottomPanel = a.vertical();
        bottomPanel.setTag("player-bottom-panel");

        clockBlock = a.vertical();
        clockBlock.setGravity(Gravity.END);
        clockBig = a.text("00:00:00", 22, MainActivity.WHITE);
        clockBig.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        clockBig.setIncludeFontPadding(false);
        clockBig.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
        clockBig.setGravity(Gravity.END);
        clockBlock.addView(clockBig, new LinearLayout.LayoutParams(-1, -2));
        clockEnd = a.text("影片结束 --:--:--", 11, 0xffa9b7d0);
        clockEnd.setSingleLine(true);
        clockEnd.setGravity(Gravity.END);
        clockEnd.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        LinearLayout.LayoutParams clockEndLp = new LinearLayout.LayoutParams(-1, -2);
        clockEndLp.topMargin = a.dp(2);
        clockBlock.addView(clockEnd, clockEndLp);
        LinearLayout.LayoutParams clockBlockLp = new LinearLayout.LayoutParams(-1, -2);
        clockBlockLp.bottomMargin = a.dp(8);
        bottomPanel.addView(clockBlock, clockBlockLp);

        progress = new FrameLayout(a);
        progress.setTag("player-progress");
        timeCurrent = timeLabel("00:00", Gravity.START | Gravity.CENTER_VERTICAL);
        timeTotal = timeLabel("00:00", Gravity.END | Gravity.CENTER_VERTICAL);
        progress.addView(
                timeCurrent,
                new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        progress.addView(
                timeTotal,
                new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        bottomPanel.addView(progress, new LinearLayout.LayoutParams(-1, a.dp(32)));

        dock = a.row();
        dock.setTag("player-dock");
        dock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(-1, a.dp(64));
        dockLp.topMargin = a.dp(6);
        bottomPanel.addView(dock, dockLp);

        overlay.addView(bottomPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // 右缘折叠箭头（垂直居中）
        foldBtn = new PlayerUiKit.IconView(a, PlayerUiKit.ICON_CHEVRON_DOWN, 0xfff2f6ff, a.dp(16));
        foldBtn.setBackground(
                PlayerUiKit.circleBg(0x99000000, 0x33ffffff, Math.max(1, a.dp(1))));
        foldBtn.setOnClickListener(v -> toggleBottomPanel());
        MobileDetailScreen.bindTouchFeedback(foldBtn);
        overlay.addView(foldBtn, new FrameLayout.LayoutParams(a.dp(30), a.dp(30)));

        // 拖拽时间气泡：白底黑字
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

        // 中央播放/暂停钮由 PlaybackControls 放进底部图标行
        state = new PlayerUiKit.PlayPauseButton(a);
        state.setTag("player-state");
        state.setFocusable(false);
        state.setVisibility(View.GONE);
        state.setOnClickListener(
                v -> {
                    a.togglePlayback();
                    a.showControls(true);
                });

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

    private TextView timeLabel(String text, int gravity) {
        TextView tv = a.text(text, 13, MainActivity.WHITE);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setIncludeFontPadding(false);
        tv.setGravity(gravity);
        tv.setShadowLayer(a.dp(4), 0, a.dp(1), 0xcc000000);
        return tv;
    }

    /** 收起 / 展开底部控制区（右缘箭头）。 */
    void toggleBottomPanel() {
        bottomCollapsed = !bottomCollapsed;
        applyBottomCollapsed();
        a.showControls(true);
    }

    private void applyBottomCollapsed() {
        if (bottomPanel == null) return;
        bottomPanel.setVisibility(bottomCollapsed ? View.GONE : View.VISIBLE);
        if (foldBtn != null) {
            foldBtn.setIcon(
                    bottomCollapsed
                            ? PlayerUiKit.ICON_CHEVRON_UP
                            : PlayerUiKit.ICON_CHEVRON_DOWN);
        }
        a.controlsVisible = true;
        hideSeekBubble();
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
        int topMargin = Math.max(0, Math.min(a.dp(14), height / 12));

        if (titleView != null) titleView.setTextSize(wideLayout ? 17 : 16);

        place(heading, -1, -2, Gravity.TOP, side, topMargin, side + a.dp(150), 0);
        place(
                topRight,
                -2,
                -2,
                Gravity.TOP | Gravity.RIGHT,
                0,
                Math.max(a.dp(6), topMargin - a.dp(2)),
                side,
                0);

        if (wideLayout) {
            place(bottomPanel, -1, -2, Gravity.BOTTOM, side, 0, side, a.dp(22));
        } else {
            place(bottomPanel, -1, -2, Gravity.BOTTOM, side, 0, side, a.dp(16));
        }
        if (clockBlock != null) {
            clockBlock.setVisibility(wideLayout ? View.VISIBLE : View.GONE);
        }
        foldBtn.setVisibility(progressOnlyMode ? View.GONE : View.VISIBLE);
        int foldSize = a.dp(30);
        place(
                foldBtn,
                foldSize,
                foldSize,
                Gravity.TOP | Gravity.LEFT,
                Math.max(0, width - foldSize - Math.min(a.dp(10), side)),
                Math.max(0, (height - foldSize) / 2),
                0,
                0);

        // 进度条左右留出时间文本位置
        if (a.playbackControls != null && a.playbackControls.timeline != null) {
            View timeline = a.playbackControls.timeline;
            FrameLayout.LayoutParams lp;
            if (timeline.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                lp = (FrameLayout.LayoutParams) timeline.getLayoutParams();
            } else {
                lp = new FrameLayout.LayoutParams(-1, a.dp(32), Gravity.CENTER_VERTICAL);
                timeline.setLayoutParams(lp);
            }
            int timeW = a.dp(wideLayout ? 64 : 56);
            if (lp.width != -1
                    || lp.height != a.dp(32)
                    || lp.leftMargin != timeW
                    || lp.rightMargin != timeW
                    || lp.gravity != Gravity.CENTER_VERTICAL) {
                lp.width = -1;
                lp.height = a.dp(32);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.leftMargin = timeW;
                lp.rightMargin = timeW;
                timeline.setLayoutParams(lp);
            }
        }

        int anchorX = Math.max(0, width / 2);
        int anchorY = Math.max(0, height - dp160());
        place(menuAnchor, 1, 1, Gravity.TOP | Gravity.LEFT, anchorX, anchorY, 0, 0);

        applyBottomCollapsed();
        if (seekBubbleVisible()) hideSeekBubble();

        if (a.playbackControls != null) {
            a.playbackControls.relayout(wideLayout);
        }
    }

    private int dp160() {
        return a.dp(wideLayout ? 180 : 140);
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
            resolution.setText(width > 0 && height > 0 ? width + " × " + height : "--");
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
        resolution.setText("--");
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
        dock.setVisibility(otherVis);
        if (clockBlock != null) clockBlock.setVisibility(otherVis);
        foldBtn.setVisibility(otherVis);
        seekHint.setVisibility(progressOnly ? View.VISIBLE : View.GONE);
        hideSeekBubble();
        update();
        if (a.playbackControls != null) a.playbackControls.visible(a.controlsVisible);
    }

    void update() {
        if (a.prepared) loading = false;
        if (a.player != null && a.prepared) resolution.setText(a.player.videoResolution());
        boolean showSpinner = loading && !a.prepared && a.playbackLoading == null && !a.miniMode;
        loadingPill.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
        int stateVis = progressOnlyMode ? View.INVISIBLE : View.VISIBLE;
        state.setVisibility(stateVis);
        state.setContentDescription(
                !a.prepared ? "正在准备" : a.playing ? "正在播放，确认暂停" : "已暂停，确认继续");
        state.invalidate();

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
                    position = a.playbackControls.timeline.getProgress();
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
