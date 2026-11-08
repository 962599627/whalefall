package com.whalefall.movie;

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
 * 首页视频筛选：从底部滑出的玻璃面板，按来源维度（类型/地区/年份…）分排展示。
 *
 * <p>点一下即时筛选并保持面板打开，选中态是樱花粉胶囊；「重置」清空，「完成」关闭。
 * 与参考设计（TVBox 风格底部筛选栏）一致，不再是「点两次分类才出现」的两级弹窗。
 */
final class VideoFilterDialog {
    private VideoFilterDialog() {}

    static void show(
            MainActivity a,
            List<VideoFilters.Definition> definitions,
            Map<String, String> selections,
            Runnable changed) {
        if (a.root == null) return;
        final FrameLayout overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> ((FrameLayout) overlay.getParent()).removeView(overlay));

        final LinearLayout sheet = a.vertical();
        sheet.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(16));
        sheet.setBackground(MobileDetailScreen.glassBg(a, 0xe60b1220, 0x44ffffff, a.dp(24)));
        sheet.setClickable(true);

        View handle = new View(a);
        handle.setBackground(a.bg(0x66ffffff, 999));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = a.dp(10);
        sheet.addView(handle, handleLp);

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = a.text("筛选", 18, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        final ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout rows = a.vertical();
        rows.setPadding(0, a.dp(8), 0, 0);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));

        final Runnable[] renderRows = new Runnable[1];
        final Runnable render = new Runnable() {
            @Override
            public void run() {
                rows.removeAllViews();
                for (VideoFilters.Definition def : definitions) {
                    LinearLayout row = a.row();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView label = a.text(def.label, 12.5f, 0xffa9b7d0);
                    label.setSingleLine(true);
                    label.setPadding(0, a.dp(6), a.dp(10), a.dp(6));
                    row.addView(label, new LinearLayout.LayoutParams(a.dp(54), -2));

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
                        TextView chip = pill(a, choice.label, on);
                        chip.setOnClickListener(
                                v -> {
                                    if (value.isEmpty()) selections.remove(def.dimension);
                                    else selections.put(def.dimension, value);
                                    renderRows[0].run();
                                    changed.run();
                                });
                        LinearLayout.LayoutParams lp =
                                new LinearLayout.LayoutParams(-2, a.dp(30));
                        lp.rightMargin = a.dp(6);
                        chips.addView(chip, lp);
                    }
                    row.addView(hs, new LinearLayout.LayoutParams(0, -2, 1));
                    rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        };
        renderRows[0] = render;
        render.run();

        TextView reset = pill(a, "重置", false);
        reset.setOnClickListener(
                v -> {
                    selections.clear();
                    renderRows[0].run();
                    changed.run();
                });
        TextView done = pill(a, "完成", true);
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-2, a.dp(30));
        doneLp.leftMargin = a.dp(8);
        done.setOnClickListener(v -> ((FrameLayout) overlay.getParent()).removeView(overlay));
        titleRow.addView(reset, new LinearLayout.LayoutParams(-2, a.dp(30)));
        titleRow.addView(done, doneLp);
        sheet.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams sheetLp =
                new FrameLayout.LayoutParams(
                        -1, Math.min(a.windowHeight() * 58 / 100, a.dp(420)), Gravity.BOTTOM);
        sheetLp.leftMargin = a.dp(8);
        sheetLp.rightMargin = a.dp(8);
        sheetLp.bottomMargin = a.dp(96);
        overlay.addView(sheet, sheetLp);
        a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(150).start();
        sheet.setTranslationY(a.dp(48));
        sheet.animate().translationY(0).setDuration(220).start();
    }

    private static TextView pill(MainActivity a, String label, boolean on) {
        TextView v = a.text(label, 12, on ? 0xff31141f : 0xffe6edf9);
        v.setGravity(Gravity.CENTER);
        v.setPadding(a.dp(14), 0, a.dp(14), 0);
        v.setBackground(a.bg(on ? 0xffffc7d6 : 0x38ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(v);
        return v;
    }
}
