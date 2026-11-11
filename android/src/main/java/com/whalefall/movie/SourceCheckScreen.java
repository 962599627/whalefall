package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.*;

/**
 * 1.9.26 片源体检 + 失效源一键清理：轻量请求每个 site（engine.home），显示状态/耗时，
 * 失效源可禁用（持久化 disabled_sources，配置加载时通过 available() 跳过）。
 */
final class SourceCheckScreen {
 private SourceCheckScreen() {}

 static final String DISABLED_KEY = "disabled_sources";
 /** 1.9.27：防重入 + 可取消线程池。 */
 private static volatile boolean running;
 private static volatile java.util.concurrent.ExecutorService activeExecutor;
 private static final java.util.concurrent.atomic.AtomicInteger RUN_ID =
 new java.util.concurrent.atomic.AtomicInteger();
 /** 1.9.27 设计评审：批量操作需完成态与原地刷新。 */
 private static volatile boolean completed;
 private static TextView bulkDisableView;
 private static LinearLayout currentList;
 private static List<Result> currentOrder;
 private static MainActivity currentHost;

 static int countStatus(List<Result> order, String status) {
 int count = 0;
 for (Result result : order) if (status.equals(result.status)) count++;
 return count;
 }

 static int countDisabled(Set<String> disabled) {
 return disabled == null ? 0 : disabled.size();
 }

 private static void updateBulk(MainActivity a, List<Result> order) {
 if (bulkDisableView == null) return;
 int fail = countStatus(order, "失效");
 int disabled = countDisabled(disabled(a));
 bulkDisableView.setText("一键禁用失效 (" + fail + ")");
 bulkDisableView.setAlpha(completed && fail > 0 ? 1f : 0.55f);
 bulkDisableView.setFocusable(completed && fail > 0);
 if (restoreAllView != null) {
 restoreAllView.setText("全部恢复 (" + disabled + ")");
 restoreAllView.setAlpha(disabled > 0 ? 1f : 0.55f);
 restoreAllView.setFocusable(disabled > 0);
 }
 }

 private static TextView restoreAllView;

 static void refreshList(MainActivity a) {
 if (currentList != null && currentOrder != null)
 render(a, currentList, currentOrder);
 updateBulk(a, currentOrder == null ? java.util.Collections.emptyList() : currentOrder);
 }

 static final class Result {
 final Cms.Site site;
 String status = "等待中";
 long ms;
 String error = "";

 Result(Cms.Site site) {
 this.site = site;
 }
 }

 static Set<String> disabled(MainActivity a) {
 Set<String> out = new LinkedHashSet<>();
 try {
 for (String key : a.prefs.getString(DISABLED_KEY, "").split(","))
 if (!key.trim().isEmpty()) out.add(key.trim());
 } catch (Throwable ignored) {
 }
 return out;
 }

 static boolean isDisabled(MainActivity a, Cms.Site site) {
 return site != null && disabled(a).contains(site.key);
 }

 static void setDisabled(MainActivity a, Cms.Site site, boolean disabled) {
 Set<String> set = disabled(a);
 if (disabled) set.add(site.key);
 else set.remove(site.key);
 StringBuilder builder = new StringBuilder();
 for (String key : set) {
 if (builder.length() > 0) builder.append(',');
 builder.append(key);
 }
 a.prefs.edit().putString(DISABLED_KEY, builder.toString()).apply();
 AppLog.i("SourceCheck", "disabled site=" + site.key + " value=" + disabled);
 }

 static void show(final MainActivity a) {
 if (a.config == null || a.config.sites.isEmpty()) {
 a.toast("请先导入配置");
 return;
 }
 a.renderPage("片源体检", "SOURCE CHECK");
 if (a.body == null) return;
 a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.body.setTag("source-check");
 a.body.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(24));
 final LinearLayout list = a.vertical();
 final Map<String, Result> results = new LinkedHashMap<>();
 final List<Result> order = new ArrayList<>();
 for (Cms.Site site : a.config.sites) {
 Result result = new Result(site);
 results.put(site.key, result);
 order.add(result);
 }
 final boolean[] cancelled = {false};
 completed = false;
 currentHost = a;
 currentOrder = order;
 currentList = list;
 final LinearLayout actions = a.row();
 actions.setGravity(Gravity.CENTER_VERTICAL);
 TextView start = a.text("开始体检", a.isTvDevice() ? 14f : 12, 0xff0b1220);
 start.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 start.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
 start.setFocusable(true);
 start.setOnClickListener(v -> runCheck(a, order, list, cancelled));
 actions.addView(start);
 TextView cancel = a.text("取消", a.isTvDevice() ? 14f : 12, UiTokens.TEXT_PRIMARY);
 cancel.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 cancel.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 cancel.setFocusable(true);
 cancel.setOnClickListener(
 v -> {
 cancelled[0] = true;
 java.util.concurrent.ExecutorService pool = activeExecutor;
 if (pool != null) pool.shutdownNow();
 activeExecutor = null;
 running = false;
 AppLog.i("SourceCheck", "cancel run");
 a.toast("已取消体检");
 });
 LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, -2);
 cancelLp.leftMargin = a.dp(8);
 actions.addView(cancel, cancelLp);
 final TextView disableAll =
 a.text("一键禁用失效 (0)", a.isTvDevice() ? 14f : 12, 0xffff8fa3);
 disableAll.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 disableAll.setBackground(MobileDetailScreen.glassBg(a, 0x24ff6b81, 0x66ff6b81, 999));
 disableAll.setFocusable(false);
 disableAll.setAlpha(0.55f);
 bulkDisableView = disableAll;
 disableAll.setOnClickListener(
 v -> {
 if (!completed) {
 a.toast("请先完成体检");
 return;
 }
 int count = countStatus(order, "失效");
 if (count <= 0) {
 a.toast("没有可禁用的失效源");
 return;
 }
 new AppDialog.Builder(a)
 .setTitle("禁用 " + count + " 个失效源？")
 .setMessage("可随时在列表中恢复。")
 .setPositiveButton(
 "禁用",
 (d, i) -> {
 for (Result result : order)
 if ("失效".equals(result.status))
 setDisabled(a, result.site, true);
 a.toast("已禁用 " + count + " 个失效源");
 refreshList(a);
 })
 .setNegativeButton("取消", null)
 .setNegativeDefaultFocus()
 .show();
 });
 LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
 allLp.leftMargin = a.dp(8);
 actions.addView(disableAll, allLp);
 final TextView restoreAll =
 a.text("全部恢复 (0)", a.isTvDevice() ? 14f : 12, UiTokens.ACCENT_SOFT);
 restoreAll.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 restoreAll.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 restoreAll.setFocusable(false);
 restoreAll.setAlpha(0.55f);
 restoreAllView = restoreAll;
 restoreAll.setOnClickListener(
 v -> {
 Set<String> disabled = disabled(a);
 if (disabled.isEmpty()) {
 a.toast("没有已禁用的源");
 return;
 }
 new AppDialog.Builder(a)
 .setTitle("恢复全部禁用源？")
 .setMessage("将恢复 " + disabled.size() + " 个源。")
 .setPositiveButton(
 "恢复",
 (d, i) -> {
 a.prefs.edit().remove(DISABLED_KEY).apply();
 a.toast("已全部恢复");
 refreshList(a);
 })
 .setNegativeButton("取消", null)
 .setNegativeDefaultFocus()
 .show();
 });
 LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(-2, -2);
 restoreLp.leftMargin = a.dp(8);
 actions.addView(restoreAll, restoreLp);
 a.body.addView(actions);
 updateBulk(a, order);

 ScrollView scroll = new ScrollView(a);
 scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
 a.body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
 render(a, list, order);
 AppLog.i("SourceCheck", "page sites=" + order.size());
 }

 private static void render(MainActivity a, LinearLayout list, List<Result> order) {
 list.removeAllViews();
 boolean tv = a.isTvDevice();
 for (final Result result : order) {
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 row.setMinimumHeight(a.dp(tv ? 64 : 56));
 row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
 LinearLayout info = a.vertical();
 TextView name = a.text(result.site.name, tv ? 17f : 15f, UiTokens.TEXT_PRIMARY);
 info.addView(name);
 int color = 0xff8d9cb6;
 if ("正常".equals(result.status)) color = 0xff2ad47a;
 else if ("慢".equals(result.status)) color = 0xffffb454;
 else if ("失效".equals(result.status)) color = 0xffff6b81;
 String detail = result.status;
 if (result.ms > 0) detail += " · " + result.ms + "ms";
 if (!result.error.isEmpty()) detail += " · " + result.error;
 final String detailText = detail;
 TextView status = a.text(detail, tv ? 13f : 12f, color);
 status.setSingleLine(true);
 status.setEllipsize(android.text.TextUtils.TruncateAt.END);
 status.setOnClickListener(
 v ->
 new AppDialog.Builder(a)
 .setTitle(result.site.name)
 .setMessage(detailText + (result.error.isEmpty() ? "" : "\n错误：" + result.error))
 .setPositiveButton("关闭", (d, i) -> {})
 .show());
 info.addView(status);
 row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
 final boolean disabled = isDisabled(a, result.site);
 TextView action =
 a.text(
 disabled ? "恢复" : "禁用",
 tv ? 15f : 12f,
 disabled ? UiTokens.ACCENT_SOFT : 0xffff6b81);
 action.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
 action.setMinimumHeight(a.dp(tv ? 48 : 44));
 action.setGravity(Gravity.CENTER);
 action.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 action.setFocusable(true);
 action.setOnClickListener(
 v -> {
 setDisabled(a, result.site, !disabled);
 a.toast(!disabled ? "已禁用：" + result.site.name : "已恢复：" + result.site.name);
 // 1.9.27：只更新列表，保留体检结果
 render(a, list, order);
 updateBulk(a, order);
 });
 row.addView(action);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.bottomMargin = a.dp(6);
 list.addView(row, lp);
 }
 }

 private static void runCheck(
 final MainActivity a,
 final List<Result> order,
 final LinearLayout list,
 final boolean[] cancelled) {
 if (running) {
 a.toast("体检进行中，请等待完成或先取消");
 return;
 }
 running = true;
 completed = false;
 updateBulk(a, order);
 final int runId = RUN_ID.incrementAndGet();
 cancelled[0] = false;
 AppLog.i("SourceCheck", "run id=" + runId + " sites=" + order.size());
 final java.util.concurrent.ExecutorService executor =
 java.util.concurrent.Executors.newFixedThreadPool(4);
 activeExecutor = executor;
 final java.util.concurrent.atomic.AtomicInteger remaining =
 new java.util.concurrent.atomic.AtomicInteger(order.size());
 for (final Result result : order) {
 executor.execute(
 () -> {
 try {
 if (cancelled[0]) return;
 long start = System.currentTimeMillis();
 String status;
 String error = "";
 try {
 // 1.9.27：每站 8 秒超时，避免插件阻塞拖住线程池
 java.util.concurrent.FutureTask<String> task =
 new java.util.concurrent.FutureTask<>(
 () -> a.engine(result.site).home());
 Thread worker = new Thread(task, "source-check-probe");
 worker.setDaemon(true);
 worker.start();
 try {
 task.get(8, java.util.concurrent.TimeUnit.SECONDS);
 } catch (java.util.concurrent.TimeoutException timeout) {
 // 1.9.28 P2：超时后取消，打断探测线程继续跑 home()
 task.cancel(true);
 throw timeout;
 }
 long ms = System.currentTimeMillis() - start;
 result.ms = ms;
 status = ms < 1500 ? "正常" : ms < 4000 ? "慢" : "失效";
 } catch (java.util.concurrent.TimeoutException timeout) {
 result.ms = System.currentTimeMillis() - start;
 status = "失效";
 error = "超时 8s";
 } catch (Throwable failure) {
 result.ms = System.currentTimeMillis() - start;
 status = "失效";
 error = failure.getClass().getSimpleName();
 }
 result.status = status;
 result.error = error;
 AppLog.i(
 "SourceCheck",
 "id="
 + runId
 + " site="
 + result.site.key
 + " status="
 + ("正常".equals(status)
 ? "ok"
 : "慢".equals(status) ? "slow" : "fail")
 + " ms="
 + result.ms
 + (error.isEmpty() ? "" : " error=" + error));
 a.ui.post(
 () -> {
 if (a.body == null
 || !"source-check".equals(a.body.getTag()))
 return;
 render(a, list, order);
 });
 } finally {
 if (remaining.decrementAndGet() == 0) {
 executor.shutdown();
 // 1.9.28 P2：只有当前 run 才清状态，避免旧 run 清掉新 run
 if (runId == RUN_ID.get()) {
 completed = true;
 activeExecutor = null;
 running = false;
 AppLog.i("SourceCheck", "done id=" + runId);
 a.ui.post(() -> updateBulk(a, order));
 }
 }
 }
 });
 }
 }
}
