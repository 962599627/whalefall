package com.whalefall.movie;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.9.73：运行时 UI 状态自检（状态不变量）。
 *
 * <p>每次页面渲染 / 导航切换后检查：导航恰好 1 个选中且与当前 tab 一致、焦点唯一可见、
 * 同名单例层不重复、MiniDetails/播放层不残留；可自动修复的立即修复并记录。
 */
final class UiAudit {
 private UiAudit() {}

 /** 返回报告（同时打日志）。action=fix 表示已自动修复。 */
 static String audit(MainActivity a, String page) {
 try {
 return auditInner(a, page);
 } catch (Throwable error) {
 AppLog.w("UiAudit", "audit skipped page=" + page, error);
 return "";
 }
 }

 private static String auditInner(MainActivity a, String page) {
 if (a == null) return "";
 List<String> issues = new ArrayList<>();
 boolean fixed = false;

 // 1) 导航恰好一个选中且等于当前 tab
 String expected = expectedNavKey(a);
 List<View> navItems = new ArrayList<>();
 collect(a.root, navItems, "nav-", 64);
 int selected = 0;
 List<String> selectedTags = new ArrayList<>();
 List<View> selectedViews = new ArrayList<>();
 for (View v : navItems) {
 if (v.isSelected()) {
 selected++;
 selectedTags.add(String.valueOf(v.getTag()));
 selectedViews.add(v);
 }
 }
 if (navItems.isEmpty()) {
 // 页面没有导航栏（二级页）时该不变量不适用
 } else if (selected != 1
 || (expected != null
 && selected == 1
 && !expected.equals(String.valueOf(selectedViews.get(0).getTag())))) {
 issues.add("nav-single-selected got=" + selected + " tabs=" + selectedTags);
 if (expected != null) {
 for (View v : navItems) v.setSelected(expected.equals(String.valueOf(v.getTag())));
 HomeDock.selectedKey = expected;
 fixed = true;
 }
 }

 // 2) 焦点唯一且可见
 View focus = a.getCurrentFocus();
 if (focus == null) issues.add("focus-none");
 else if (!focus.isShown() || !focus.isAttachedToWindow()) issues.add("focus-invisible");
 else issues.add("");

 // 3) 同名单例层不重复
 String[] singletons = {
 "mini-details",
 "player-loading",
 "tv-loading-overlay",
 "player-tools-sheet",
 "player-chrome",
 "filter-panel"
 };
 for (String tag : singletons) {
 int count = countTag(a.root, tag);
 if (count > 1) issues.add("duplicate:" + tag + "=" + count);
 }

 // 4) 播放器/小窗残留
 if (!a.miniMode && countTag(a.root, "mini-details") > 0)
 issues.add("residual:mini-details");
 if (a.player == null && a.playbackLayer != null
 && a.playbackLayer.isShown())
 issues.add("residual:playbackLayer");
 if (a.appDialog != null && !a.appDialog.isShowing()) issues.add("stale:appDialog");

 List<String> bad = new ArrayList<>();
 // 5) 焦点样式泄漏：非焦点控件出现焦点 overlay / 同时多个焦点环
 int[] leaks = new int[2];
 auditFocusLeaks(a, a.root, leaks);
 if (leaks[0] > 0) issues.add("focus-leak count=" + leaks[0]);
 if (leaks[1] > 1) issues.add("focus-ring count=" + leaks[1]);
 for (String line : issues) if (!line.isEmpty()) bad.add(line);
 String report =
 "page="
 + page
 + " navSelected="
 + selected
 + " expected="
 + expected
 + " focus="
 + (focus == null || !(focus.getTag() instanceof String)
 ? (focus == null ? "null" : "untagged")
 : String.valueOf(focus.getTag()))
 + " issues="
 + bad
 + (fixed ? " action=fix" : " action=log");
 if (bad.isEmpty()) {
 AppLog.i("UiAudit", report);
 } else {
 for (String line : bad)
 AppLog.i("UiAudit", "rule=" + line + " page=" + page + (fixed ? " action=fix" : " action=log"));
 }
 return report;
 }

 private static String expectedNavKey(MainActivity a) {
 // TV：静态选中键（由 HomeDock 点击/返回首页维护）；手机：按 activeTab 映射
 if (a.isTvDevice()) return HomeDock.selectedKey;
 switch (MobileShell.activeTab) {
 case 1:
 case 0:
 return "nav-home";
 case 2:
 return "nav-live";
 case 3:
 return "nav-tv";
 default:
 return HomeDock.selectedKey;
 }
 }

 private static void collect(View view, List<View> out, String prefix, int limit) {
 if (view == null || out.size() >= limit) return;
 if (view.getTag() instanceof String
 && ((String) view.getTag()).startsWith(prefix)
 && "nav-".equals(prefix)) {
 out.add(view);
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 collect(group.getChildAt(i), out, prefix, limit);
 }
 }

 private static void auditFocusLeaks(MainActivity a, View view, int[] stats) {
 if (view == null) return;
 boolean overlay = TvNav.hasFocusOverlay(view);
 if (overlay && view.hasFocus()) stats[1]++;
 if (!view.hasFocus() && overlay) {
 AppLog.i(
 "UiAudit",
 "rule=focus-leak view="
 + String.valueOf(view.getTag())
 + " action=fix");
 TvNav.clearFocusOverlay(view);
 stats[0]++;
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 auditFocusLeaks(a, group.getChildAt(i), stats);
 }
 }

 private static int countTag(View view, String tag) {
 if (view == null) return 0;
 int count = tag.equals(String.valueOf(view.getTag())) ? 1 : 0;
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 count += countTag(group.getChildAt(i), tag);
 }
 return count;
 }
}
