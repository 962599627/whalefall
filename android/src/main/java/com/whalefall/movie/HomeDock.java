package com.whalefall.movie;

import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Home-only, fixed right rail. Content keeps its own scroll and return focus. */
final class HomeDock {
    final MainActivity a;
    final LinearLayout rail;
    final TextView hint;
    final View[] items = new View[7];
    final String[] labels = {"首页", "搜索", "直播", "电视", "收藏", "网盘", "设置"};
    final String[] keys = {
        "nav-home", "nav-search", "nav-live", "nav-tv", "nav-favorites", "nav-accounts", "nav-settings"
    };

    final boolean bottomMode;
    final float navScale;
    final int crossDp, mainDp, cellDp, hintHeightDp;

    HomeDock(MainActivity host) {
        a = host;
        bottomMode = a.useBottomDock();
        navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        cellDp = Math.round(38 * navScale);
        crossDp = Math.round(50 * navScale);
        // 7 项实占：7×38 + 7×8（上下间距）+ 2×8（padding）= 338dp
        // 140% navScale 时约 473dp < 960×540 的安全高 504dp
        mainDp = Math.round(338 * navScale);
        hintHeightDp = Math.round(28 * navScale);
        rail = bottomMode ? a.row() : a.vertical();
        rail.setTag("home-dock");
        rail.setGravity(Gravity.CENTER);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(
                a.dp(Math.round((bottomMode ? 10 : 6) * navScale)),
                a.dp(Math.round((bottomMode ? 6 : 8) * navScale)),
                a.dp(Math.round((bottomMode ? 10 : 6) * navScale)),
                a.dp(Math.round((bottomMode ? 6 : 8) * navScale)));
        a.glass(rail, Math.round(25 * navScale));
        rail.setElevation(a.dp(8));
        FrameLayout.LayoutParams size =
                bottomMode
                        ? new FrameLayout.LayoutParams(
                                a.dp(mainDp),
                                a.dp(crossDp),
                                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                        : new FrameLayout.LayoutParams(
                                a.dp(crossDp),
                                a.dp(mainDp),
                                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (bottomMode) size.bottomMargin = a.dp(14);
        else size.rightMargin = a.dp(20);
        a.root.addView(rail, size);
        hint = a.text("", 12f * navScale, MainActivity.WHITE);
        hint.setTag("home-dock-label");
        hint.setGravity(Gravity.CENTER);
        hint.setIncludeFontPadding(false);
        hint.setPadding(a.dp(Math.round(12 * navScale)), 0, a.dp(Math.round(16 * navScale)), 0);
        hint.setBackground(new LabelBubble());
        hint.setElevation(rail.getElevation() + a.dp(1));
        hint.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams tip =
                new FrameLayout.LayoutParams(-2, a.dp(hintHeightDp), Gravity.RIGHT | Gravity.TOP);
        tip.rightMargin = a.dp(crossDp + 28);
        a.root.addView(hint, tip);
        Runnable[] actions = {
            () -> {
                if (a.scroll != null) a.smoothHomeScroll(0);
                View target = a.root.findViewWithTag("home-history:0");
                if (target == null) target = a.root.findViewWithTag("home-tab:0");
                if (target == null) target = a.root.findViewWithTag("primary");
                if (target != null) target.requestFocus();
            },
            a::globalSearchDialog,
            () -> a.open(new MainActivity.Screen(a::liveSources), true),
            () -> a.open(new MainActivity.Screen(() -> TvLive.show(a)), true),
            () -> a.open(new MainActivity.Screen(() -> new FavoritesScreen(a).show()), true),
            () -> a.openSourceAccountPanel(),
            () -> a.open(new MainActivity.Screen(a::settings), true)
        };
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            Icon icon = new Icon(i);
            items[i] = icon;
            icon.setTag(keys[i]);
            icon.setId(View.generateViewId());
            icon.setContentDescription(labels[i]);
            icon.setFocusable(true);
            icon.setFocusableInTouchMode(true);
            icon.setClickable(true);
            icon.setSelected(i == 0);
            a.pressFeedback(icon);
            int cellGap = a.dp(Math.max(3, Math.round(4 * navScale)));
            LinearLayout.LayoutParams cell =
                    new LinearLayout.LayoutParams(a.dp(cellDp), a.dp(cellDp));
            if (bottomMode) {
                cell.leftMargin = cellGap;
                cell.rightMargin = cellGap;
            } else {
                cell.topMargin = cellGap;
                cell.bottomMargin = cellGap;
            }
            rail.addView(icon, cell);
            icon.setOnClickListener(v -> actions[index].run());
            icon.setOnFocusChangeListener(
                    (v, focused) -> {
                        icon.invalidate();
                        FocusMotion.style(
                                a,
                                icon,
                                focused,
                                Color.TRANSPARENT,
                                MainActivity.WHITE,
                                // Half of the cell or more: a true circle, not a squircle.
                                Math.max(24, (cellDp + 2) / 2),
                                MainActivity.WHITE,
                                MainActivity.DARK,
                                false,
                                1.045f);
                        if (focused && !bottomMode) showLabel(index);
                        else {
                            hint.animate().cancel();
                            hint.animate()
                                    .alpha(0)
                                    .setDuration(a.reduced ? 0 : 100)
                                    .withEndAction(
                                            () -> {
                                                if (!contains(a.getCurrentFocus()))
                                                    hint.setVisibility(View.INVISIBLE);
                                            })
                                    .start();
                        }
                    });
        }
        for (int i = 0; i < items.length; i++) {
            if (bottomMode) {
                items[i].setNextFocusLeftId(items[Math.max(0, i - 1)].getId());
                items[i].setNextFocusRightId(items[Math.min(6, i + 1)].getId());
            } else {
                items[i].setNextFocusUpId(items[Math.max(0, i - 1)].getId());
                items[i].setNextFocusDownId(items[Math.min(6, i + 1)].getId());
                items[i].setNextFocusRightId(items[i].getId());
            }
        }
        a.root.getViewTreeObserver()
                .addOnGlobalFocusChangeListener(
                        (oldFocus, newFocus) -> {
                            if (contains(newFocus)
                                    && oldFocus != null
                                    && !contains(oldFocus)
                                    && oldFocus.getTag() instanceof String
                                    && a.current != null)
                                a.current.homeReturnFocus = (String) oldFocus.getTag();
                        });
    }

    boolean contains(View view) {
        for (View item : items) if (item == view) return true;
        return false;
    }

    void showLabel(int index) {
        hint.animate().cancel();
        hint.setText(labels[index]);
        rail.post(
                () -> {
                    if (!items[index].hasFocus()) return;
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) hint.getLayoutParams();
                    if (bottomMode) {
                        hint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                        p.gravity = Gravity.LEFT | Gravity.TOP;
                        p.rightMargin = 0;
                        int center =
                                rail.getLeft()
                                        + items[index].getLeft()
                                        + items[index].getWidth() / 2;
                        p.leftMargin =
                                Math.max(
                                        a.dp(6),
                                        Math.min(
                                                center - hint.getMeasuredWidth() / 2,
                                                a.root.getWidth()
                                                        - hint.getMeasuredWidth()
                                                        - a.dp(6)));
                        p.topMargin = Math.max(a.dp(4), rail.getTop() - a.dp(36));
                    } else
                        p.topMargin =
                                rail.getTop()
                                        + items[index].getTop()
                                        + (items[index].getHeight() - a.dp(hintHeightDp)) / 2;
                    hint.setLayoutParams(p);
                    hint.setAlpha(1);
                    hint.setTranslationX(0);
                    hint.setVisibility(View.VISIBLE);
                    if (!a.reduced) {
                        hint.setAlpha(0);
                        hint.setTranslationX(a.dp(4));
                        hint.animate().alpha(1).translationX(0).setDuration(140).start();
                    }
                });
    }

    void rightEdge(View view) {
        if (view != null) view.setNextFocusRightId(items[0].getId());
    }

    boolean key(KeyEvent event) {
        if (!contains(a.getCurrentFocus()) || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_LEFT)
            return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            View target =
                    a.current == null || a.current.homeReturnFocus == null
                            ? null
                            : a.root.findViewWithTag(a.current.homeReturnFocus);
            if (target == null || !target.isShown())
                target = a.root.findViewWithTag("source-switcher");
            if (target == null) target = a.root.findViewWithTag("primary");
            if (target != null) target.requestFocus();
        }
        return true;
    }

    final class LabelBubble extends android.graphics.drawable.Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (bottomMode) {
                float edge = b.bottom - a.dp(4);
                paint.setColor(0xff303036);
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(b.left, b.top, b.right, edge), a.dp(9), a.dp(9), paint);
                Path point = new Path();
                point.moveTo(b.exactCenterX() - a.dp(4), edge - a.dp(1));
                point.lineTo(b.exactCenterX(), b.bottom);
                point.lineTo(b.exactCenterX() + a.dp(4), edge - a.dp(1));
                point.close();
                c.drawPath(point, paint);
                return;
            }
            float edge = b.right - a.dp(4), middle = b.exactCenterY();
            paint.setColor(0xff303036);
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(b.left, b.top, edge, b.bottom), a.dp(9), a.dp(9), paint);
            Path tip = new Path();
            tip.moveTo(edge - a.dp(1), middle - a.dp(4));
            tip.lineTo(b.right, middle);
            tip.lineTo(edge - a.dp(1), middle + a.dp(4));
            tip.close();
            c.drawPath(tip, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    final class Icon extends View {
        final int kind;
        private final android.graphics.drawable.Drawable glyph;
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int kind) {
            super(a);
            this.kind = kind;
            glyph = UiIcon.drawable(a, glyphKind(kind));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            FocusMotion.State motion = (FocusMotion.State) getTag(R.id.focus_motion);
            boolean light = hasFocus() && (motion == null || Color.alpha(motion.color) > 150);
            UiIcon.tintDrawable(glyph, light ? MainActivity.DARK : UiTokens.TEXT_SECONDARY);
            UiIcon.drawCentered(c, glyph, getWidth(), getHeight(), a.dp(21f * navScale));
            if (isSelected()) {
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(hasFocus() ? MainActivity.DARK : MainActivity.WHITE);
                c.drawCircle(a.dp(5), getHeight() / 2f, a.dp(1.5f), dotPaint);
            }
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }

    /** Dock 图标 → 统一 vector 图标（首页/搜索/直播/电视/收藏/网盘/设置）。 */
    static int glyphKind(int kind) {
        switch (kind) {
            case 0:
                return UiIcon.HOME;
            case 1:
                return UiIcon.SEARCH;
            case 2:
                return UiIcon.LIVE;
            case 3:
                return UiIcon.TV;
            case 4:
                return UiIcon.FAVORITE;
            case 5:
                return UiIcon.CLOUD;
            default:
                return UiIcon.TUNE;
        }
    }
}
