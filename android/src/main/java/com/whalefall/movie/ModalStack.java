package com.whalefall.movie;

import android.view.View;
import java.util.ArrayDeque;

/**
 * 1.9.77：统一模态栈。所有 Dialog/浮层 show 时 push、dismiss 时 pop；
 * 手机遥控注入键优先交给最上层模态，避免焦点落到背景页面。
 */
final class ModalStack {
    private ModalStack() {}

    static final class Entry {
        final String tag;
        final View root;
        final Runnable dismiss;

        Entry(String tag, View root, Runnable dismiss) {
            this.tag = tag;
            this.root = root;
            this.dismiss = dismiss;
        }
    }

    private static final ArrayDeque<Entry> stack = new ArrayDeque<>();

    static synchronized void push(String tag, View root, Runnable dismiss) {
        if (root == null) return;
        popByRoot(root);
        stack.push(new Entry(tag, root, dismiss));
        log("push", root);
    }

    static synchronized void pop(View root) {
        popByRoot(root);
        log("pop", topRoot());
    }

    static synchronized void popByRoot(View root) {
        if (root == null) return;
        // 1.9.85：避免 lambda 合成类（Android 6.0 上曾报 NoClassDefFoundError）
        java.util.Iterator<Entry> it = stack.iterator();
        while (it.hasNext()) {
            if (it.next().root == root) it.remove();
        }
    }

    static synchronized Entry top() {
        return stack.peek();
    }

    static synchronized View topRoot() {
        Entry entry = stack.peek();
        return entry == null ? null : entry.root;
    }

    static synchronized int size() {
        return stack.size();
    }

    private static void log(String action, View root) {
        Entry top = stack.peek();
        AppLog.i(
                "Modal",
                "stack="
                        + stack.size()
                        + " top="
                        + (top == null ? "none" : top.tag)
                        + " focus="
                        + (root == null || root.findFocus() == null
                                ? "none"
                                : String.valueOf(root.findFocus().getTag()))
                        + " action="
                        + action);
    }

    /** 立即把首焦点放进模态内部（第一个可聚焦项）。 */
    static void focusFirst(MainActivity a, View root) {
        if (root == null) return;
        View focus = root.findFocus();
        if (focus != null && focus.isShown()) return;
        java.util.ArrayList<View> available = new java.util.ArrayList<>();
        try {
            root.addFocusables(available, View.FOCUS_FORWARD);
        } catch (Throwable ignored) {
        }
        for (View view : available) {
            if (view.isShown()) {
                view.requestFocus();
                AppLog.i(
                        "Modal",
                        "stack="
                                + size()
                                + " top="
                                + (top() == null ? "none" : top().tag)
                                + " focus="
                                + String.valueOf(view.getTag()));
                return;
            }
        }
    }

    /**
     * 注入按键优先给最上层模态；返回 true 表示已消费。
     * 方向键只在该模态 root 内搜索/几何移动，不落到背景页。
     */
    static boolean handleKey(MainActivity a, int keyCode) {
        try {
            return handleKeyInner(a, keyCode);
        } catch (Throwable error) {
            AppLog.w("Modal", "handleKey failed", error);
            return false;
        }
    }

    private static boolean handleKeyInner(MainActivity a, int keyCode) {
        Entry top;
        synchronized (ModalStack.class) {
            top = stack.peek();
        }
        if (top == null || top.root == null) return false;
        View root = top.root;
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (top.dismiss != null) {
                try {
                    top.dismiss.run();
                } catch (Throwable ignored) {
                }
            }
            log("back", root);
            return true;
        }
        View focus = root.findFocus();
        if (focus == null || !focus.isShown()) {
            focusFirst(a, root);
            focus = root.findFocus();
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (focus != null && focus.isClickable()) {
                focus.performClick();
                log("confirm", root);
            }
            return true;
        }
        int direction = -1;
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) direction = View.FOCUS_UP;
        else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) direction = View.FOCUS_DOWN;
        else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) direction = View.FOCUS_LEFT;
        else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) direction = View.FOCUS_RIGHT;
        if (direction < 0) return false;
        View next = focus == null ? null : focus.focusSearch(direction);
        if ((next == null || next == focus) && focus != null)
            next = TvNav.spatialMove(root, focus, direction);
        if ((next == null || next == focus)) next = root.focusSearch(direction);
        if (next != null && next != focus) {
            next.requestFocus();
            log("nav", root);
        }
        return true;
    }
}
