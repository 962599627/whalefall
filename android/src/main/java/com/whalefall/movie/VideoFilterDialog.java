package com.whalefall.movie;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

/**
 * 首页视频筛选：从底部滑出的玻璃面板，按来源维度（类型 / 地区 / 年份…）分排展示。
 *
 * <p>视觉与「电视台直播」页统一：深海蓝悬浮玻璃面板（圆角 24dp）、底部拖拽手柄、
 * 胶囊 chip、樱花粉选中态；字号层级固定为 title 18 / 行标签 11 / chip 12.5 / 提示 11。
 * 点一下即时筛选并保持面板打开，「重置」清空，「完成」或点空白处关闭。
 */
final class VideoFilterDialog {
    private VideoFilterDialog() {}

    /** 切换 tab / 重建页面时关掉残留的筛选面板。 */
    static void dismiss(MainActivity a) {
        if (a.root == null) return;
        for (int i = a.root.getChildCount() - 1; i >= 0; i--) {
            View child = a.root.getChildAt(i);
            if ("filter-sheet".equals(child.getTag())) a.root.removeViewAt(i);
        }
    }

    static void show(
            MainActivity a,
            List<VideoFilters.Definition> definitions,
            Map<String, String> selections,
            Runnable changed) {
        show(a, definitions, selections, changed, 0);
    }

    /**
     * @param bottomMarginDp 面板距屏幕底部的距离：默认 0 = 贴底 bottom sheet（顶角 24dp、底边直角，
     *     展开时盖住底部 Dock）；需要浮空卡片时传 90 左右。
     */
    static void show(
            MainActivity a,
            List<VideoFilters.Definition> definitions,
            Map<String, String> selections,
            Runnable changed,
            int bottomMarginDp) {
        if (a.root == null) return;

        final FrameLayout overlay = new FrameLayout(a);
        overlay.setTag("filter-sheet");
        overlay.setBackgroundColor(0x7305070c);
        overlay.setClickable(true);

        final LinearLayout sheet = a.vertical();
        sheet.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(16));
        sheet.setBackground(topSheetBg(a, 0xf00b1220, 0x33ffffff, 24f));
        sheet.setElevation(a.dp(18));
        sheet.setClickable(true);

        final Runnable[] closeRef = new Runnable[1];
        overlay.setOnClickListener(v -> closeRef[0].run());

        View handle = new View(a);
        handle.setBackground(a.bg(0x59ffffff, 999));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = a.dp(10);
        sheet.addView(handle, handleLp);

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        int picked = 0;
        for (String v : selections.values())
            if (v != null && !v.isEmpty()) picked += v.split(",").length;
        TextView title = a.text(picked == 0 ? "筛选" : "筛选 · 已选 " + picked + " 项", 18, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        final TextView reset = TvUiKit.pill(a, "重置", 12, false);
        final TextView done = TvUiKit.pill(a, "完成", 12, true);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, a.dp(34));
        resetLp.rightMargin = a.dp(8);
        titleRow.addView(reset, resetLp);
        titleRow.addView(done, new LinearLayout.LayoutParams(-2, a.dp(34)));
        sheet.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        final TextView hint = a.text("", 11, 0xff8d9cb6);
        hint.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = a.dp(4);
        sheet.addView(hint, hintLp);

        final ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout rows = a.vertical();
        rows.setPadding(0, 0, 0, a.dp(4));
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = a.dp(6);
        sheet.addView(scroll, scrollLp);

        final Runnable[] renderRows = new Runnable[1];
        final Runnable render =
                new Runnable() {
                    @Override
                    public void run() {
                        rows.removeAllViews();
                        for (int i = 0; i < definitions.size(); i++) {
                            final VideoFilters.Definition def = definitions.get(i);
                            LinearLayout row = a.row();
                            row.setGravity(Gravity.CENTER_VERTICAL);

                            TextView label = a.text(def.label, 11, 0xff8d9cb6);
                            label.setSingleLine(true);
                            label.setLetterSpacing(0.06f);
                            label.setPadding(0, a.dp(6), a.dp(10), a.dp(6));
                            row.addView(label, new LinearLayout.LayoutParams(a.dp(62), -2));

                            HorizontalScrollView hs = new HorizontalScrollView(a);
                            hs.setHorizontalScrollBarEnabled(false);
                            LinearLayout chips = a.row();
                            chips.setGravity(Gravity.CENTER_VERTICAL);
                            hs.addView(chips);
                            for (VideoFilters.Choice choice : def.choices) {
                                final String value = choice.value;
                                boolean on =
                                        value.isEmpty()
                                                ? !selections.containsKey(def.dimension)
                                                : value.equals(selections.get(def.dimension));
                                TextView chip = TvUiKit.pill(a, choice.label, 12.5f, on);
                                chip.setOnClickListener(
                                        v -> {
                                            if (value.isEmpty()) {
                                                selections.remove(def.dimension);
                                            } else {
                                                selections.put(def.dimension, value);
                                            }
                                            renderRows[0].run();
                                            changed.run();
                                        });
                                LinearLayout.LayoutParams lp =
                                        new LinearLayout.LayoutParams(-2, a.dp(34));
                                lp.rightMargin = a.dp(7);
                                chips.addView(chip, lp);
                            }
                            row.addView(hs, new LinearLayout.LayoutParams(0, -2, 1));
                            LinearLayout.LayoutParams rowLp =
                                    new LinearLayout.LayoutParams(-1, -2);
                            if (i > 0) rowLp.topMargin = a.dp(6);
                            rows.addView(row, rowLp);
                        }
                        int picked = 0;
                        for (String v : selections.values())
                            if (v != null && !v.isEmpty()) picked += v.split(",").length;
                        title.setText(picked == 0 ? "筛选" : "筛选 · 已选 " + picked + " 项");
                        hint.setText(
                                picked == 0
                                        ? "点选即生效 · 可多选"
                                        : "可继续叠加条件 · 点「全部」清空该项");
                        reset.setAlpha(selections.isEmpty() ? 0.45f : 1f);
                    }
                };
        renderRows[0] = render;

        reset.setOnClickListener(
                v -> {
                    selections.clear();
                    renderRows[0].run();
                    changed.run();
                });
        done.setOnClickListener(v -> closeRef[0].run());

        closeRef[0] =
                () -> {
                    overlay.animate().cancel();
                    sheet.animate().cancel();
                    sheet.animate().translationY(a.dp(56)).setDuration(180).start();
                    overlay.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(
                                    () -> {
                                        if (overlay.getParent() instanceof FrameLayout) {
                                            ((FrameLayout) overlay.getParent())
                                                    .removeView(overlay);
                                        }
                                    })
                            .start();
                };

        // 贴底 bottom sheet：平板限制最大宽度并居中，手机全宽；顶角 24dp、底边直角
        float sheetDensity = a.getResources().getDisplayMetrics().density;
        int sheetWindowDp = Math.round(a.windowWidth() / sheetDensity);
        FrameLayout.LayoutParams sheetLp =
                new FrameLayout.LayoutParams(
                        sheetWindowDp >= 720 ? a.dp(760) : -1,
                        Math.min(a.windowHeight() * 58 / 100, a.dp(430)),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetLp.leftMargin = 0;
        sheetLp.rightMargin = 0;
        sheetLp.bottomMargin = a.dp(bottomMarginDp);
        overlay.addView(sheet, sheetLp);
        a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        render.run();

        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
        sheet.setTranslationY(a.dp(56));
        sheet.animate().translationY(0).setDuration(240).start();
    }

    /** 贴底 bottom sheet 背景：只圆顶角，底边直角，和屏幕底部贴合。 */
    private static GradientDrawable topSheetBg(
            MainActivity a, int fill, int stroke, float radiusDp) {
        float r = a.dp(radiusDp);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        // 顺序：左上、右上、右下、左下（每角 rx/ry）
        gd.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        gd.setStroke(Math.max(1, a.dp(0.8f)), stroke);
        return gd;
    }
}
