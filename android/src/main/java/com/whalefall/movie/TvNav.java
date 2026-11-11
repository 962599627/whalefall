package com.whalefall.movie;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.9.63：TV 遥控统一焦点工具（借鉴友商遥控器模型）。
 *
 * <p>页面渲染完成后调用 {@link #ensureFocus} 保证始终有可见焦点；网格用
 * {@link #wireGrid} 建立显式 nextFocus；{@link #focusRing} 统一焦点描边。
 */
final class TvNav {
    private TvNav() {}

    /** 页面渲染后确保焦点：优先 preferred tags，否则第一个可聚焦控件。 */
    static View ensureFocus(MainActivity a, String page, String... preferredTags) {
        if (a == null || a.root == null) return null;
        View current = a.getCurrentFocus();
        if (current != null && current.isAttachedToWindow() && current.isShown()) return current;
        View first = null;
        for (String tag : preferredTags) {
            first = a.root.findViewWithTag(tag);
            if (first != null && first.isShown() && first.isFocusable()) break;
            first = null;
        }
        if (first == null) {
            ArrayList<View> available = new ArrayList<>();
            a.root.addFocusables(available, View.FOCUS_FORWARD);
            for (View view : available) {
                if (view != a.root && view.isShown()) {
                    first = view;
                    break;
                }
            }
        }
        if (first != null) {
            first.requestFocus();
            a.autoFocus = first;
            AppLog.i(
                    "TvNav",
                    "page="
                            + page
                            + " focus="
                            + String.valueOf(first.getTag())
                            + " reason=ensure");
        } else {
            AppLog.i("TvNav", "page=" + page + " focus=null reason=no-focusable");
        }
        return first;
    }

    /** 网格/列表显式 nextFocus：左右逐列、上下逐行，末行向下到 closeView（无则回第一行）。 */
    static void wireGrid(List<View> cells, int columns, View closeView) {
        if (cells == null || cells.isEmpty()) return;
        columns = Math.max(1, columns);
        for (View cell : cells)
            if (cell != null && cell.getId() == View.NO_ID) cell.setId(View.generateViewId());
        if (closeView != null && closeView.getId() == View.NO_ID)
            closeView.setId(View.generateViewId());
        for (int i = 0; i < cells.size(); i++) {
            View cell = cells.get(i);
            if (cell == null) continue;
            int column = i % columns;
            int row = i / columns;
            int lastRow = (cells.size() - 1) / columns;
            if (column > 0) cell.setNextFocusLeftId(cells.get(i - 1).getId());
            if (column + 1 < columns && i + 1 < cells.size())
                cell.setNextFocusRightId(cells.get(i + 1).getId());
            if (i - columns >= 0) cell.setNextFocusUpId(cells.get(i - columns).getId());
            if (i + columns < cells.size()) cell.setNextFocusDownId(cells.get(i + columns).getId());
            else if (closeView != null) cell.setNextFocusDownId(closeView.getId());
            else cell.setNextFocusDownId(cells.get(0).getId());
            if (row == 0) cell.setNextFocusUpId(cells.get(0).getId());
            if (row == lastRow && closeView != null) cell.setNextFocusDownId(closeView.getId());
        }
        if (closeView != null && !cells.isEmpty())
            closeView.setNextFocusUpId(cells.get(cells.size() - 1).getId());
    }

    /** 统一焦点描边：2dp 白描边 + 1.03× 放大；失焦恢复透明。 */
    static void focusRing(final MainActivity a, final View view, final int radiusDp) {
        if (a == null || view == null) return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
        view.setOnFocusChangeListener(
                (v, focused) -> {
                    android.graphics.drawable.GradientDrawable ring =
                            new android.graphics.drawable.GradientDrawable();
                    ring.setColor(focused ? 0x33ffffff : 0x00000000);
                    ring.setCornerRadius(a.dp(radiusDp));
                    if (focused) ring.setStroke(a.dp(2), 0xffffffff);
                    v.setBackground(ring);
                    v.animate()
                            .scaleX(focused ? 1.03f : 1f)
                            .scaleY(focused ? 1.03f : 1f)
                            .setDuration(UiTokens.MOTION_FAST)
                            .start();
                });
    }

    /** 递归补齐可交互控件的可聚焦与焦点态。 */
    static int ensureFocusable(MainActivity a, View view, int radiusDp, int fixed) {
        if (view == null) return fixed;
        if (view.isClickable()
                && !view.isFocusable()
                && !(view instanceof android.widget.ScrollView)
                && !(view instanceof android.widget.HorizontalScrollView)) {
            focusRing(a, view, radiusDp);
            fixed++;
        } else if (view.isFocusable() && !view.isClickable() && view instanceof ViewGroup) {
            view.setFocusable(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (view.isFocusable() && group.getChildCount() > 0)
                group.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            for (int i = 0; i < group.getChildCount(); i++)
                fixed = ensureFocusable(a, group.getChildAt(i), radiusDp, fixed);
        }
        return fixed;
    }
}
