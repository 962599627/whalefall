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
 /** 1.9.83：默认关闭自动补齐（只在设置→UI 自检手动开启），减少真机崩溃面。 */
 static volatile boolean AUTO_FIX = false;

 static void setAutoFix(boolean enabled) {
 AUTO_FIX = enabled;
 }

 static View ensureFocus(MainActivity a, String page, String... preferredTags) {
 try {
 return ensureFocusInner(a, page, preferredTags);
 } catch (Throwable error) {
 AppLog.w("TvNav", "ensureFocus failed page=" + page, error);
 return null;
 }
 }

 private static View ensureFocusInner(
 MainActivity a, String page, String... preferredTags) {
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

 /** 1.9.72 设计规范：按钮焦点=10% 粉底 + 2dp ACCENT_SOFT 内环；不缩放、不溢出。 */
 static void focusRing(final MainActivity a, final View view, final int radiusDp) {
 install(a, view, radiusDp, STYLE_BUTTON);
 }

 /** 设置条目/弹层行：10% 粉底 + 左侧 3dp ACCENT 竖条，不用整圈描边。 */
 static void rowFocus(final MainActivity a, final View view, final int radiusDp) {
 install(a, view, radiusDp, STYLE_ROW);
 }

 /** 按 tag 自动选择卡片/按钮/行样式（首页 outline 兼容入口）。 */
 static void tagFocus(MainActivity a, View view, boolean focused, int radiusDp) {
 String tag = view == null || !(view.getTag() instanceof String) ? "" : (String) view.getTag();
 int style =
 tag.startsWith("home-video:")
 || tag.startsWith("home-history:")
 || tag.startsWith("home-tonight")
 || tag.startsWith("card:")
 ? STYLE_CARD
 : STYLE_BUTTON;
 install(a, view, radiusDp, style);
 applyFocus(a, view, focused, style, radiusDp);
 }

 /** 卡片：2dp ACCENT_SOFT 内环 + 3dp ACCENT 底部高亮条，不缩放。 */
 static void cardFocus(final MainActivity a, final View view, boolean focused, int radiusDp) {
 install(a, view, radiusDp, STYLE_CARD);
 applyFocus(a, view, focused, STYLE_CARD, radiusDp);
 }

 static final int STYLE_BUTTON = 0, STYLE_ROW = 1, STYLE_CARD = 2;

 private static void install(
 final MainActivity a, final View view, final int radiusDp, final int style) {
 try {
 installInner(a, view, radiusDp, style);
 } catch (Throwable error) {
 AppLog.w("TvNav", "focus style skipped", error);
 }
 }

 private static void installInner(
 final MainActivity a, final View view, final int radiusDp, final int style) {
 if (a == null || view == null) return;
 markRing(view);
 view.setFocusable(true);
 // 1.9.76：触摸模式保持 false（单击即响应）；只有遥控焦点模式/TV 才 true
 view.setFocusableInTouchMode(a.remoteFocusMode || a.isTvDevice());
 if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
 if (view.getTag(BG_KEY) == null) view.setTag(BG_KEY, view.getBackground());
 if (view.getTag(TEXT_KEY) == null && view instanceof android.widget.TextView)
 view.setTag(TEXT_KEY, ((android.widget.TextView) view).getCurrentTextColor());
 view.setOnFocusChangeListener(
 (v, focused) -> applyFocus(a, v, focused, style, radiusDp));
 // 1.9.75：安装监听只补能力，不在未聚焦时绘制任何可见状态
 if (view.hasFocus()) applyFocus(a, view, true, style, radiusDp);
 }

 private static void applyFocus(
 MainActivity a, View view, boolean focused, int style, int radiusDp) {
 try {
 // 1.9.93：焦点移走时立即清除其它残留环，仅保留真正的当前焦点
 if (!focused && a != null) clearOthers(a, a.getCurrentFocus());
 applyFocusInner(a, view, focused, style, radiusDp);
 } catch (Throwable error) {
 AppLog.w("FocusDraw", "draw skipped " + view.getTag(), error);
 }
 }

 private static void applyFocusInner(
 MainActivity a, View view, boolean focused, int style, int radiusDp) {
 // 1.9.75：只有真实 hasFocus 才绘制焦点样式；失焦必须完全透明
 if (focused && !view.hasFocus()) {
 AppLog.i(
 "FocusDraw",
 "view="
 + String.valueOf(view.getTag())
 + " hasFocus=false draw=skip");
 focused = false;
 }
 Object stored = view.getTag(FG_KEY);
 final boolean canOverlay = android.os.Build.VERSION.SDK_INT >= 23;
 if (focused && !canOverlay) {
 // 1.9.84：API<23 无 foreground，用 background 内描边替代焦点环
 try {
 if (view.getTag(BG_KEY) == null) view.setTag(BG_KEY, view.getBackground());
 android.graphics.drawable.GradientDrawable ring =
 new android.graphics.drawable.GradientDrawable();
 ring.setColor(0x1AFF8FB1);
 ring.setCornerRadius(a.dp(Math.max(0, radiusDp)));
 ring.setStroke(a.dp(2), 0xFFFFB3C8);
 view.setBackground(ring);
 } catch (Throwable error) {
 AppLog.w("TvNav", "legacy focus ring skipped", error);
 }
 } else if (!focused && !canOverlay) {
 try {
 Object bg = view.getTag(BG_KEY);
 view.setBackground(
 bg instanceof android.graphics.drawable.Drawable
 ? (android.graphics.drawable.Drawable) bg
 : null);
 } catch (Throwable ignored) {
 }
 }
 if (focused && canOverlay) {
 // 1.9.93：同一时刻只允许一个焦点环
 clearOthers(a, view);
 ringOwner = new java.lang.ref.WeakReference<>(view);
 if (stored == null) {
 try {
 view.setTag(FG_KEY, view.getForeground());
 } catch (Throwable ignored) {
 }
 }
 android.graphics.drawable.Drawable d = new FocusDrawable(a, radiusDp, style);
 if (android.os.Build.VERSION.SDK_INT >= 23) {
 d.setAlpha(255);
 try {
 view.setForeground(d);
 } catch (Throwable error) {
 AppLog.w("TvNav", "setForeground failed", error);
 }
 }
 if (view instanceof android.widget.TextView) {
 android.widget.TextView text = (android.widget.TextView) view;
 text.setTextColor(
 style == STYLE_ROW || style == STYLE_BUTTON
 ? 0xffffd0de
 : text.getCurrentTextColor());
 }
 } else if (!focused) {
 // 无论之前有没有缓存，失焦都清掉焦点 overlay，避免“款式残留全亮”
 if (android.os.Build.VERSION.SDK_INT >= 23) {
 android.graphics.drawable.Drawable original =
 stored instanceof android.graphics.drawable.Drawable
 ? (android.graphics.drawable.Drawable) stored
 : null;
 try {
 view.setForeground(original);
 } catch (Throwable error) {
 AppLog.w("TvNav", "clearForeground failed", error);
 }
 }
 view.setTag(FG_KEY, null);
 if (view instanceof android.widget.TextView) {
 Object originalColor = view.getTag(TEXT_KEY);
 if (originalColor instanceof Integer)
 ((android.widget.TextView) view).setTextColor((Integer) originalColor);
 }
 AppLog.i(
 "FocusDraw",
 "view="
 + String.valueOf(view.getTag())
 + " hasFocus=false clear=true");
 }
 view.setScaleX(1f);
 view.setScaleY(1f);
 AppLog.i(
 "TvNav",
 "focus=" + String.valueOf(view.getTag()) + " style=ring+scale on=" + focused);
 }

 /** 清理非焦点控件上的焦点 overlay（UiAudit focus-leak 修复）。 */
 static void clearFocusOverlay(View view) {
 if (view == null) return;
 if (android.os.Build.VERSION.SDK_INT >= 23
 && view.getForeground() instanceof FocusDrawable) view.setForeground(null);
 view.setTag(FG_KEY, null);
 AppLog.i(
 "FocusDraw",
 "view=" + String.valueOf(view.getTag()) + " hasFocus=" + view.hasFocus() + " clear=true");
 }

 /** 判断控件是否残留焦点 overlay。 */
 static boolean hasFocusOverlay(View view) {
 return android.os.Build.VERSION.SDK_INT >= 23
 && view.getForeground() instanceof FocusDrawable;
 }

 /** 焦点环 overlay：内嵌式，画在内容之上；卡片额外画底部高亮条。 */
 private static final class FocusDrawable extends android.graphics.drawable.Drawable {
 private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
 private final int radiusPx, style;
 private final float inset, barInsetLR, barBottom;

 FocusDrawable(MainActivity a, int radiusDp, int style) {
 this.radiusPx = a.dp(Math.max(0, radiusDp));
 this.style = style;
 this.inset = a.dp(1);
 this.barInsetLR = a.dp(8);
 this.barBottom = a.dp(6);
 }

 @Override
 public void draw(android.graphics.Canvas canvas) {
 android.graphics.Rect b = getBounds();
 float r = Math.max(0, radiusPx);
 paint.setStyle(android.graphics.Paint.Style.FILL);
 if (style != STYLE_CARD) {
 paint.setColor(0x1AFF8FB1);
 canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, r, r, paint);
 }
 android.graphics.RectF ring =
 new android.graphics.RectF(
 b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
 if (style != STYLE_ROW) {
 paint.setStyle(android.graphics.Paint.Style.STROKE);
 paint.setStrokeWidth(inset * 2);
 paint.setColor(0xFFFFB3C8);
 canvas.drawRoundRect(ring, Math.max(0, r - inset), Math.max(0, r - inset), paint);
 }
 if (style == STYLE_ROW) {
 paint.setStyle(android.graphics.Paint.Style.FILL);
 paint.setColor(0xFFFF8FB1);
 canvas.drawRoundRect(
 b.left,
 b.top + inset * 6,
 b.left + inset * 3,
 b.bottom - inset * 6,
 inset * 1.5f,
 inset * 1.5f,
 paint);
 } else if (style == STYLE_CARD) {
 paint.setStyle(android.graphics.Paint.Style.FILL);
 paint.setColor(0xFFFF8FB1);
 canvas.drawRoundRect(
 b.left + barInsetLR,
 b.bottom - barBottom - inset * 3,
 b.right - barInsetLR,
 b.bottom - barBottom,
 inset * 1.5f,
 inset * 1.5f,
 paint);
 }
 }

 @Override
 public void setAlpha(int alpha) {
 paint.setAlpha(alpha);
 invalidateSelf();
 }

 @Override
 public void setColorFilter(android.graphics.ColorFilter filter) {
 paint.setColorFilter(filter);
 }

 @Override
 public int getOpacity() {
 return android.graphics.PixelFormat.TRANSLUCENT;
 }
 }

 private static final int BG_KEY = 0x7f0f9e01;
 private static final int TEXT_KEY = 0x7f0f9e02;
 private static final int RING_KEY = 0x7f0f9e03;
 private static final int FG_KEY = 0x7f0f9e04;
 /** 1.9.93：全局唯一焦点环持有者。 */
 private static java.lang.ref.WeakReference<View> ringOwner = new java.lang.ref.WeakReference<>(null);

 /** 1.9.93：清除除 keep 外所有控件的焦点 overlay（单焦点环硬保证）。 */
 static int clearOthers(MainActivity a, View keep) {
 if (a == null || a.root == null) return 0;
 int[] removed = new int[1];
 clearOthersWalk(a.root, keep, removed);
 if (removed[0] > 0)
 AppLog.i(
 "TvNav",
 "clear-others keep="
 + (keep == null ? "null" : String.valueOf(keep.getTag()))
 + " removed="
 + removed[0]);
 return removed[0];
 }

 private static void clearOthersWalk(MainActivity a, View view, View keep, int[] removed) {
 if (view == null) return;
 if (view != keep && hasFocusOverlay(view)) {
 clearFocusOverlay(view);
 removed[0]++;
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 clearOthersWalk(a, group.getChildAt(i), keep, removed);
 }
 }

 /** 标记该控件已有焦点动效（审计用）。 */
 static void markRing(View view) {
 if (view != null) view.setTag(RING_KEY, Boolean.TRUE);
 }

 /** 1.9.71：页面焦点审计；返回报告文本，并对缺失控件幂等补焦点。 */
 /** 1.9.74：最近一次审计报告（设置→UI 自检展示）。 */
 static volatile String lastReport = "";
 static volatile String lastMissing = "[]";

 static String audit(MainActivity a, String page) {
 return auditInternal(a, page, true);
 }

 private static String auditInternal(MainActivity a, String page, boolean log) {
 try {
 return auditInner(a, page, log);
 } catch (Throwable error) {
 AppLog.w("TvFocusAudit", "audit skipped page=" + page, error);
 return "";
 }
 }

 private static String auditInner(MainActivity a, String page, boolean log) {
 if (a == null || a.root == null) return "";
 int[] stats = new int[3];
 List<String> missing = new ArrayList<>();
 auditWalk(a, a.root, stats, missing);
 String report =
 "page="
 + page
 + " clickable="
 + stats[0]
 + " focusable="
 + stats[1]
 + " rings="
 + stats[2]
 + " missing="
 + missing;
 if (log) AppLog.i("TvFocusAudit", report);
 lastReport = report;
 lastMissing = missing.toString();
 return report;
 }

 private static void auditWalk(MainActivity a, View view, int[] stats, List<String> missing) {
 if (view == null || stats[0] > 2000) return;
 boolean clickable = view.isClickable() && view.isShown();
 if (clickable) {
 stats[0]++;
 if (view.isFocusable()) stats[1]++;
 boolean ring =
 Boolean.TRUE.equals(view.getTag(RING_KEY))
 || view.getOnFocusChangeListener() != null;
 if (ring) stats[2]++;
 else {
 String tag = view.getTag() instanceof String ? (String) view.getTag() : null;
 if (missing.size() < 24)
 missing.add(
 (tag != null ? tag : view.getClass().getSimpleName())
 + "@"
 + (view.getParent() instanceof View
 ? String.valueOf(
 ((View) view.getParent()).getTag())
 : "root"));
 // 1.9.83：默认只记录缺失，不自动补齐（手动 UI 自检才修）
 if (AUTO_FIX) {
 focusRing(a, view, 12);
 stats[1]++;
 stats[2]++;
 }
 }
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 auditWalk(a, group.getChildAt(i), stats, missing);
 }
 }

 /** 弹层窗口审计：Dialog 有独立装饰树。 */
 static String auditDialog(MainActivity a, String page, android.view.View decor) {
 if (decor == null) return "";
 int[] stats = new int[3];
 List<String> missing = new ArrayList<>();
 auditWalk(a, decor, stats, missing);
 String report =
 "page="
 + page
 + " clickable="
 + stats[0]
 + " focusable="
 + stats[1]
 + " rings="
 + stats[2]
 + " missing="
 + missing;
 AppLog.i("TvFocusAudit", report);
 lastReport = report;
 lastMissing = missing.toString();
 return report;
 }

 /** 1.9.74：轻量周期审计（只扫可见树；无缺失时每 10s 打一次心跳，避免刷屏）。 */
 private static long lastBeat;
 static String periodicAudit(MainActivity a, String page) {
 String report = auditInternal(a, page, false);
 if (!missingOf(report).equals("[]") || System.currentTimeMillis() - lastBeat > 10_000) {
 lastBeat = System.currentTimeMillis();
 AppLog.i("TvFocusAudit", report);
 }
 return report;
 }

 private static String missingOf(String report) {
 int at = report.indexOf("missing=");
 return at < 0 ? "[]" : report.substring(at + 8);
 }

 /** 1.9.66：手机被控端检测到遥控连接后，进入遥控焦点模式（手机也显示焦点）。 */
 static void enableRemoteFocus(MainActivity a, String page) {
 try {
 enableRemoteFocusInner(a, page);
 } catch (Throwable error) {
 AppLog.w("Remote", "enableRemoteFocus skipped", error);
 }
 }

 private static void enableRemoteFocusInner(MainActivity a, String page) {
 if (a == null || a.root == null) return;
 int fixed = ensureFocusable(a, a.root, 12, 0);
 // 手机被控端：所有 clickable 控件统一粉色焦点动效（含卡片/侧栏/chip）
 if (AUTO_FIX) applyRings(a, a.root);
 View first =
 ensureFocus(
 a,
 page,
 "detail-play",
 "detail-line:0",
 "home-video:0",
 "home-tonight",
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
 audit(a, page);
 }

 /** 遥控断开：退出强制焦点（保留 focusable，不影响触摸）。 */
 static void disableRemoteFocus(MainActivity a) {
 if (a == null) return;
 AppLog.i("Remote", "focusMode=off page=remote");
 }

 private static void applyRings(MainActivity a, View view) {
 if (view == null) return;
 if (view.isClickable() && view.isShown()) {
 view.setFocusable(true);
 view.setFocusableInTouchMode(a.remoteFocusMode || a.isTvDevice());
 String tag =
 view.getTag() instanceof String ? (String) view.getTag() : "";
 if (tag.startsWith("settings-") || tag.startsWith("dialog-option")) rowFocus(a, view, 10);
 else if (tag.startsWith("home-video:")
 || tag.startsWith("home-history:")
 || tag.startsWith("home-tonight")) cardFocus(a, view, view.hasFocus(), 12);
 else focusRing(a, view, 12);
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 applyRings(a, group.getChildAt(i));
 }
 }

 /** 1.9.67：几何方向搜索兜底：手机布局没有显式 nextFocus 时按坐标找最近目标。 */
 static View spatialMove(View root, View current, int direction) {
 try {
 return spatialMoveInner(root, current, direction);
 } catch (Throwable error) {
 AppLog.w("TvNav", "spatialMove failed", error);
 return null;
 }
 }

 private static View spatialMoveInner(View root, View current, int direction) {
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

 /** 1.9.76：批量切换 touch-mode 焦点（遥控模式=true，触摸模式=false）。 */
 static int setTouchFocusMode(MainActivity a, boolean enabled) {
 if (a == null || a.root == null) return 0;
 int[] count = new int[1];
 setTouchFocusWalk(a, a.root, enabled, count);
 AppLog.i(
 "TouchMode",
 "focusableInTouchMode=" + enabled + (enabled ? " enabled=" : " restored=") + count[0]);
 return count[0];
 }

 private static void setTouchFocusWalk(MainActivity a, View view, boolean enabled, int[] count) {
 if (view == null) return;
 try {
 if (view.isClickable() || view.isFocusable()) {
 view.setFocusableInTouchMode(enabled);
 if (!enabled) view.setFocusable(view.isClickable());
 count[0]++;
 }
 } catch (Throwable ignored) {
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 setTouchFocusWalk(a, group.getChildAt(i), enabled, count);
 }
 }

 /** 递归补齐可交互控件的可聚焦与焦点态。 */
 static int ensureFocusable(MainActivity a, View view, int radiusDp, int fixed) {
 try {
 return ensureFocusableInner(a, view, radiusDp, fixed);
 } catch (Throwable error) {
 AppLog.w("TvNav", "ensureFocusable skipped", error);
 return fixed;
 }
 }

 private static int ensureFocusableInner(MainActivity a, View view, int radiusDp, int fixed) {
 if (view == null) return fixed;
 if (view.isClickable()
 && !view.isFocusable()
 && !(view instanceof android.widget.ScrollView)
 && !(view instanceof android.widget.HorizontalScrollView)) {
 String tag =
 view.getTag() instanceof String ? (String) view.getTag() : "";
 if (tag.startsWith("settings-") || tag.startsWith("dialog-option"))
 rowFocus(a, view, 10);
 else focusRing(a, view, radiusDp);
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
