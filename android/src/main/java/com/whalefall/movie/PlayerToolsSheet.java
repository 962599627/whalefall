package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 播放器「更多」底部工具面板：MD3 bottom sheet 形态，3 列「图标 + 标签」网格，
 * 每格 ≥72dp（触控 ≥48dp），全部条目都映射到既有播放回调，不新增无能力按钮。
 *
 * <p>替代原来的锚定气泡列表：图标行只保留 10 个高频工具，低频项（解码、跳片头尾、
 * 循环、重播、全屏、播放信息、返回详情等）收进这里，和主流播放器一致。
 */
final class PlayerToolsSheet {
    private PlayerToolsSheet() {}

    static final class Tool {
        final int icon;
        final String label;
        final boolean active;
        final Runnable action;

        Tool(int icon, String label, Runnable action) {
            this(icon, label, false, action);
        }

        Tool(int icon, String label, boolean active, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.active = active;
            this.action = action;
        }
    }

    static Dialog show(MainActivity a, String title, List<Tool> tools, Runnable dismissed) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(a);
        root.setTag("player-tools-sheet");

        LinearLayout card = a.vertical();
        GradientDrawable sheet = new GradientDrawable();
        float[] corners = new float[8];
        corners[0] = corners[1] = a.dp(UiTokens.RADIUS_24);
        corners[2] = corners[3] = a.dp(UiTokens.RADIUS_24);
        sheet.setColor(UiTokens.PLAYER_SHEET);
        sheet.setCornerRadii(corners);
        sheet.setStroke(Math.max(1, a.dp(1)), UiTokens.STROKE_SOFT);
        card.setBackground(sheet);
        // 卡片自身吃掉点击，避免点在卡片空白处触发「点遮罩关闭」
        card.setClickable(true);
        UiCompat.setElevation(card, a.dp(16));

        // 拖拽把手
        View handle = new View(a);
        handle.setBackground(UiTokens.round(a, UiTokens.RADIUS_6, 0x40ffffff));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(a.dp(36), a.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = a.dp(UiTokens.SP_10);
        card.addView(handle, handleLp);

        // 标题行：标题 + 说明 + 关闭
        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(a.dp(UiTokens.SP_20), a.dp(UiTokens.SP_12), a.dp(UiTokens.SP_12), 0);
        LinearLayout titleCol = a.vertical();
        TextView heading = a.text(title, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setIncludeFontPadding(false);
        titleCol.addView(heading);
        TextView note =
                a.text("全部播放功能 · 低频项收纳在这里", UiTokens.TYPE_CAPTION, UiTokens.TEXT_TERTIARY);
        note.setIncludeFontPadding(false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-2, -2);
        noteLp.topMargin = a.dp(3);
        titleCol.addView(note, noteLp);
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.ImageButton close =
                UiIcon.button(a, UiIcon.CLOSE, "关闭工具面板", 48, 22, dialog::dismiss);
        close.setTag("tools-close");
        if (a.isTvDevice()) {
            close.setFocusable(true);
            close.setFocusableInTouchMode(true);
            close.setOnFocusChangeListener(
                    (v, focused) -> {
                        GradientDrawable ring = new GradientDrawable();
                        ring.setColor(focused ? 0x33ffffff : 0x00000000);
                        ring.setCornerRadius(a.dp(24));
                        if (focused) ring.setStroke(a.dp(2), 0xffffffff);
                        v.setBackground(ring);
                    });
        }
        titleRow.addView(close, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        card.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // 1.9.61：TV 四列网格显式方向导航，默认焦点=第一格
        final java.util.List<View> cells = new java.util.ArrayList<>();

        // 自适应列数：宽面板 4 列（20 项正好 5 行全展示），窄屏 3 列（内部滚动）
        int width =
                Math.min(
                        a.dp(620),
                        (a.root == null ? a.windowWidth() : a.root.getWidth())
                                - a.dp(UiTokens.SP_16));
        width = Math.max(a.dp(240), width);
        final int columns = width >= a.dp(460) ? 4 : 3;
        final int cellHeightDp = 68;
        final int rowHeightDp = 72;
        LinearLayout grid = a.vertical();
        grid.setPadding(a.dp(UiTokens.SP_12), a.dp(UiTokens.SP_8), a.dp(UiTokens.SP_12), 0);
        int rows = (tools.size() + columns - 1) / columns;
        for (int i = 0; i < tools.size(); i += columns) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int column = 0; column < columns; column++) {
                int index = i + column;
                if (index < tools.size()) {
                    final Tool tool = tools.get(index);
                    final View cell =
                            UiIcon.toolCell(
                                    a,
                                    tool.icon,
                                    tool.label,
                                    tool.active,
                                    () -> {
                                        dialog.dismiss();
                                        // 让上一个弹层先完成 dismiss，再打开下一个（选集/换源等）
                                        a.ui.postDelayed(
                                                () -> {
                                                    if (tool.action != null) tool.action.run();
                                                },
                                                90);
                                    });
                    LinearLayout.LayoutParams cellLp =
                            new LinearLayout.LayoutParams(0, a.dp(cellHeightDp), 1f);
                    cellLp.setMargins(a.dp(3), a.dp(2), a.dp(3), a.dp(2));
                    row.addView(cell, cellLp);
                    cell.setTag("tool:" + tool.label);
                    cell.setOnKeyListener(
                            (v, keyCode, event) -> {
                                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                                    boolean confirm =
                                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                                                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
                                    AppLog.i(
                                            "TvKey",
                                            "page=tools focus="
                                                    + v.getTag()
                                                    + " action="
                                                    + (confirm ? "confirm" : "nav"));
                                }
                                return false;
                            });
                    cells.add(cell);
                } else {
                    LinearLayout.LayoutParams emptyLp =
                            new LinearLayout.LayoutParams(0, a.dp(cellHeightDp), 1f);
                    emptyLp.setMargins(a.dp(3), a.dp(2), a.dp(3), a.dp(2));
                    row.addView(new View(a), emptyLp);
                }
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, a.dp(rowHeightDp)));
        }
        // 显式方向导航：右/左逐列，下/上逐行；行尾到下一行首、行首到上一行尾
        if (a.isTvDevice()) {
            if (close.getId() == View.NO_ID) close.setId(View.generateViewId());
            for (int i = 0; i < cells.size(); i++) {
                View cell = cells.get(i);
                if (cell.getId() == View.NO_ID) cell.setId(View.generateViewId());
            }
            for (int i = 0; i < cells.size(); i++) {
                View cell = cells.get(i);
                int column = i % columns;
                if (column > 0 && i - 1 >= 0) cell.setNextFocusLeftId(cells.get(i - 1).getId());
                if (column < columns - 1 && i + 1 < cells.size())
                    cell.setNextFocusRightId(cells.get(i + 1).getId());
                if (i - columns >= 0) cell.setNextFocusUpId(cells.get(i - columns).getId());
                if (i + columns < cells.size())
                    cell.setNextFocusDownId(cells.get(i + columns).getId());
                else if (i + columns >= cells.size())
                    cell.setNextFocusDownId(close.getId());
            }
            // 关闭键：从最后一格下行可达，向上回到最后一格
            if (!cells.isEmpty())
                close.setNextFocusUpId(cells.get(cells.size() - 1).getId());
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(grid);
        // 面板最大高度 = 屏幕 76%，超出时网格区域内部滚动
        int screenHeight = a.root == null ? a.windowHeight() : a.root.getHeight();
        int maxSheet = Math.min(a.dp(640), Math.round(screenHeight * 0.76f));
        int headerEstimate = a.dp(96);
        int gridPx = rows * a.dp(rowHeightDp) + a.dp(8);
        int available = Math.max(a.dp(150), maxSheet - headerEstimate);
        int scrollHeight = gridPx > available ? available : -2;
        card.addView(scroll, new LinearLayout.LayoutParams(-1, scrollHeight));
        card.setPadding(0, 0, 0, a.dp(UiTokens.SP_16));

        FrameLayout.LayoutParams cardFrameLp =
                new FrameLayout.LayoutParams(
                        width, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(card, cardFrameLp);

        dialog.setContentView(root);
        dialog.setOnDismissListener(
                d -> {
                    if (dismissed != null) dismissed.run();
                });
        // 点遮罩空白处关闭（窗口铺满全屏，卡片贴底）
        root.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        // 1.9.61：默认焦点=第一格（上一集）
        if (!cells.isEmpty()) {
            final View firstCell = cells.get(0);
            a.ui.post(
                    () -> {
                        if (!firstCell.isAttachedToWindow()) return;
                        firstCell.requestFocus();
                        AppLog.i(
                                "TvKey",
                                "page=tools focus="
                                        + firstCell.getTag()
                                        + " action=nav first=true");
                    });
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.58f;
            window.setAttributes(attrs);
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        if (!a.reduced) {
            root.setTranslationY(a.dp(28));
            root.setAlpha(0f);
            root.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(UiTokens.MOTION_SLOW)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        return dialog;
    }
}
