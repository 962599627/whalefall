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

    /** 1.9.66：手机被控端检测到遥控连接后，进入遥控焦点模式（手机也显示焦点）。 */
    static void enableRemoteFocus(MainActivity a, String page) {
        if (a == null || a.root == null) return;
        int fixed = ensureFocusable(a, a.root, 12, 0);
        View first =
                ensureFocus(
                        a,
                        page,
                        "detail-play",
                        "detail-line:0",
                        "primary",
                        "home-tab:0",
                        "nav-home");
        AppLog.i(
                "Remote",
                "focusMode=on page="
                        + page
                        + " focus="
                        + (first == null ? "null" : String.valueOf(first.getTag()))
                        + " fixed="
                        + fixed);
    }

    /** 遥控断开：退出强制焦点（保留 focusable，不影响触摸）。 */
    static void disableRemoteFocus(MainActivity a) {
        if (a == null) return;
        AppLog.i("Remote", "focusMode=off page=remote");
    }

    /** 1.9.67：几何方向搜索兜底：手机布局没有显式 nextFocus 时按坐标找最近目标。 */
    static View spatialMove(View root, View current, int direction) {
        if (root == null || current == null) return null;
        int[] cur = new int[2];
        current.getLocationOnScreen(cur);
        float curX = cur[0] + current.getWidth() / 2f;
        float curY = cur[1] + current.getHeight() / 2f;
        ArrayList<View> all = new ArrayList<>();
        collectMovable(root, all, current);
        View best = null;
        double bestScore = Double.MAX_VALUE;
        for (View v : all) {
            if (v == current || !v.isShown() || !v.isFocusable()) continue;
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            float x = pos[0] + v.getWidth() / 2f;
            float y = pos[1] + v.getHeight() / 2f;
            float dx = x - curX;
            float dy = y - curY;
            float primary;
            float secondary;
            if (direction == View.FOCUS_RIGHT) {
                if (dx <= 1f) continue;
                primary = dx;
                secondary = Math.abs(dy);
            } else if (direction == View.FOCUS_LEFT) {
                if (dx >= -1f) continue;
                primary = -dx;
                secondary = Math.abs(dy);
            } else if (direction == View.FOCUS_DOWN) {
                if (dy <= 1f) continue;
                primary = dy;
                secondary = Math.abs(dx);
            } else {
                if (dy >= -1f) continue;
                primary = -dy;
                secondary = Math.abs(dx);
            }
            // 主轴距离优先，次轴偏移加权惩罚（避免选到斜对角很远的项）
            double score = primary + secondary * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) {
            best.requestFocus();
            AppLog.i(
                    "TvKey",
                    "spatial dir="
                            + directionName(direction)
                            + " from="
                            + String.valueOf(current.getTag())
                            + " to="
                            + String.valueOf(best.getTag())
                            + " dist="
                            + Math.round(bestScore));
        }
        return best;
    }

    private static String directionName(int direction) {
        if (direction == View.FOCUS_LEFT) return "left";
        if (direction == View.FOCUS_RIGHT) return "right";
        if (direction == View.FOCUS_UP) return "up";
        return "down";
    }

    private static void collectMovable(View view, List<View> out, View exclude) {
        if (view == null || out.size() > 600) return;
        if (view != exclude
                && view.isShown()
                && view.isFocusable()
                && (view.isClickable() || view.isFocusable())
                && !(view instanceof ViewGroup && !view.isClickable())) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectMovable(group.getChildAt(i), out, exclude);
        }
    }

    /** 注入方向键失败时滚动当前可滚动容器一屏。 */
    static boolean scrollPage(View root, int direction) {
        if (root == null) return false;
        View scrollable = findScrollable(root);
        if (!(scrollable instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) scrollable;
        int dy = group.getHeight() > 0 ? group.getHeight() * 2 / 3 : 400;
        if (direction == View.FOCUS_UP) dy = -dy;
        if (scrollable instanceof androidx.recyclerview.widget.RecyclerView) {
            ((androidx.recyclerview.widget.RecyclerView) scrollable).scrollBy(0, dy);
            return true;
        }
        if (scrollable instanceof android.widget.ScrollView) {
            ((android.widget.ScrollView) scrollable).scrollBy(0, dy);
            return true;
        }
        return false;
    }

    private static View findScrollable(View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView) return view;
        if (view instanceof android.widget.ScrollView) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findScrollable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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
