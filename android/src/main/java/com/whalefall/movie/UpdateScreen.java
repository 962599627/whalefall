// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Uses the app's TV controls; only installation and its permission belong to Android. */
final class UpdateScreen {
 static final int INSTALL = 1401;
 final MainActivity a;
 final UpdateManager updates;
 TextView status;
 LinearLayout variantBox;
 TextView variantTitle;
 /** 1.9.179：检测到新版只弹选择包体窗口，选中后才下载。 */
 String promptedVersion = "";
 boolean chooseDialogOpen;

 UpdateScreen(MainActivity host) {
 a = host;
 updates = UpdateManager.get(a);
 }

 void show() {
 AppLog.i("Update", "page=enter");
 try {
 SettingsScreen settings = new SettingsScreen(a);
 settings.page("软件更新", "镜像优先，连接失败时尝试 GitHub。安装前会核对版本、文件完整性与签名。");
 // 1.9.107：黑屏兜底：页面必须有深色底，不能依赖透明 root
 if (a.root != null) a.root.setBackgroundColor(0xff070b14);
 if (a.scroll != null) a.scroll.setBackgroundColor(0xff070b14);
 if (a.body != null) a.body.setTag("update");
 a.updateScreen = this;
 // 已存在的旧检查结果不自动弹窗，仅本次新检测到才弹
 if (updates.available != null) promptedVersion = updates.available.version;
 status = a.text("正在加载更新信息…", 16, MainActivity.MUTED);
 status.setTag("update-status");
 status.setLineSpacing(a.dp(4), 1);
 a.body.addView(status);
 a.gap(a.body, 18);
 variantTitle = a.text("选择下载包体", 13, MainActivity.MUTED);
 variantTitle.setVisibility(View.GONE);
 a.body.addView(variantTitle);
 variantBox = a.vertical();
 a.body.addView(variantBox);
 settings.link("检查更新", "当前使用正式版更新渠道", "update-check", () -> updates.check(true));
 settings.link(
 "下载新版",
 "下载后仍需你确认安装",
 "update-download",
 () -> {
 if (updates.available == null) a.toast("请先检查更新");
 else updates.download();
 });
 settings.link("安装已下载版本", "覆盖升级保留配置、收藏和观看记录", "update-install", this::install);
 settings.link(
 "更新内容",
 "查看新版说明",
 "update-notes",
 () ->
 a.notice(
 updates.available == null
 ? "尚未发现更高版本。"
 : updates.available.version
 + "\n\n"
 + updates.available.notes));
 settings.link("更新方式", "自动检查提醒、自动下载或仅手动", "update-mode", this::mode);
 settings.link(
 "取消下载或检查",
 "取消不会影响已安装版本",
 "update-cancel",
 () -> {
 if (updates.busy) updates.cancel();
 else a.toast("当前没有进行中的任务");
 });
 refresh();
 } catch (Throwable error) {
 AppLog.w(
 "Update",
 "fail reason=" + error.getClass().getSimpleName() + ": " + error.getMessage(),
 error);
 showFallback(error);
 }
 }

 /** 1.9.107：更新页异常时显示深色底 + 原因，避免纯黑。 */
 private void showFallback(Throwable error) {
 try {
 a.renderPage("软件更新", "");
 if (a.root != null) a.root.setBackgroundColor(0xff070b14);
 if (a.body != null) {
 a.body.setTag("update");
 a.body.addView(a.text("软件更新", 26, MainActivity.WHITE));
 a.gap(a.body, 10);
 a.body.addView(
 a.text(
 "更新页加载失败："
 + error.getClass().getSimpleName()
 + (error.getMessage() == null ? "" : " - " + error.getMessage())
 + "\n请检查网络后重试。",
 14,
 MainActivity.MUTED));
 }
 AppLog.i("Update", "page=fallback shown=true");
 } catch (Throwable ignored) {
 }
 }

 void refresh() {
 if (status != null && status.isAttachedToWindow())
 status.setText(
 "当前版本 "
 + UpdateManager.installedVersion(a)
 + " · 正式版\n"
 + updates.message
 + (updates.percent >= 0 ? " · " + updates.percent + "%" : "")
 + "\n"
 + modeName(updates.mode()));
 rebuildVariants();
 maybeChooseBodyDialog();
 }

 /** 1.9.179：检测到新版后弹「选择安装包」窗口，不选不下载。 */
 void maybeChooseBodyDialog() {
 UpdateManager.Release r = updates.available;
 if (r == null || updates.busy || chooseDialogOpen) return;
 if (r.version.equals(promptedVersion)) return;
 promptedVersion = r.version;
 chooseDialogOpen = true;
 AppLog.i("Update", "check found=" + r.version + " dialog=choose-body");
 a.ui.post(() -> showChooseBodyDialog(r));
 }

 void showChooseBodyDialog(final UpdateManager.Release r) {
 String[] labels;
 int checked = updates.selectedVariant();
 if (r.variants.length == 0) {
 labels = new String[] {"当前设备推荐包 · " + sizeText(r.size)};
 checked = 0;
 } else {
 labels = new String[r.variants.length];
 for (int i = 0; i < r.variants.length; i++) {
 labels[i] = r.variants[i].label + " · " + sizeText(r.variants[i].size);
 }
 }
 String notes = r.notes == null ? "" : r.notes;
 if (notes.length() > 300) notes = notes.substring(0, 300) + "…";
 new AppDialog.Builder(a)
 .setTitle("发现新版本 " + r.version)
 .setMessage(
 (notes.isEmpty() ? "" : notes + "\n\n")
 + "请选择要下载的安装包（选择后才开始下载）：")
 .setSingleChoiceItems(
 labels,
 checked,
 (dialog, which) -> {
 chooseDialogOpen = false;
 if (r.variants.length > 0) updates.selectVariant(which);
 AppLog.i("Update", "download choose=" + which + " version=" + r.version);
 updates.download();
 })
 .setNegativeButton(
 "取消",
 (d, w) -> {
 chooseDialogOpen = false;
 AppLog.i("Update", "dialog cancel version=" + r.version);
 })
 .setOnCancelListener(
 d -> {
 chooseDialogOpen = false;
 AppLog.i("Update", "dialog cancel version=" + r.version);
 })
 .show();
 }

 private static String sizeText(long bytes) {
 if (bytes <= 0) return "大小未知";
 return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
 }

 /** 检查结果里有多包体时显示单选列表；自动模式仍用第一项（默认选中）。 */
 void rebuildVariants() {
 if (variantBox == null || variantTitle == null) return;
 variantBox.removeAllViews();
 UpdateManager.Release r = updates.available;
 boolean show = r != null && r.variants.length > 0;
 variantTitle.setVisibility(show ? View.VISIBLE : View.GONE);
 if (!show) return;
 final int selected = updates.selectedVariant();
 for (int i = 0; i < r.variants.length; i++) {
 final UpdateRelease.Variant variant = r.variants[i];
 final int index = i;
 LinearLayout row = a.row();
 row.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
 LinearLayout copy = a.vertical();
 TextView name =
 a.text(
 (i == selected ? "◉ " : "○ ") + variant.label,
 15,
 i == selected ? 0xff8ef0c0 : MainActivity.WHITE);
 copy.addView(name);
 TextView meta =
 a.text(formatSize(variant.size) + " · " + kindNote(variant.kind), 12,
 MainActivity.MUTED);
 meta.setPadding(0, a.dp(3), 0, 0);
 copy.addView(meta);
 row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
 row.setTag("update-variant:" + i);
 row.setId(View.generateViewId());
 row.setFocusable(true);
 row.setFocusableInTouchMode(true);
 row.setClickable(true);
 row.setBackground(a.bg(i == selected ? 0xff1c3a2e : 0xff16243c, 12));
 row.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 row,
 focused,
 0xff16243c,
 0xff334564,
 12,
 MainActivity.WHITE,
 MainActivity.WHITE,
 true,
 1.008f));
 a.pressFeedback(row);
 row.setOnClickListener(
 v -> {
 if (updates.selectVariant(index)) refresh();
 else a.toast("请等待当前任务完成");
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.bottomMargin = a.dp(6);
 variantBox.addView(row, lp);
 }
 }

 static String formatSize(long size) {
 if (size <= 0) return "大小未知";
 if (size >= 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f GB", size / 1073741824f);
 return String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576f);
 }

 static String kindNote(String kind) {
 if ("arm64".equals(kind)) return "单 ABI · 精简包";
 if ("r8".equals(kind)) return "R8 · 精简包";
 if ("tv".equals(kind)) return "电视版";
 return "双 ABI · 推荐";
 }

 static String modeName(String mode) {
 return "off".equals(mode) ? "仅手动检查" : "download".equals(mode) ? "自动下载后提醒" : "自动检查并提醒";
 }

 void mode() {
 String[] modes = {"notify", "download", "off"};
 new AppDialog.Builder(a)
 .setTitle("更新方式")
 .setMessage("自动检查至少间隔六小时，仅在应用首页运行时发起。播放过程中不弹出更新窗口；安装始终由你确认。")
 .setSingleChoiceItems(
 new String[] {"自动检查并提醒", "自动下载后提醒", "仅手动检查"},
 java.util.Arrays.asList(modes).indexOf(updates.mode()),
 (d, n) -> {
 updates.setMode(modes[n]);
 refresh();
 })
 .setNegativeButton("返回", null)
 .show();
 }

 void install() {
 if (updates.busy) {
 a.toast("请等待当前任务完成");
 return;
 }
 if (!updates.ready()) {
 a.toast("请先下载并校验新版");
 return;
 }
 if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
 new AppDialog.Builder(a)
 .setTitle("允许安装更新")
 .setMessage("电视需要你允许鲸落安装应用。打开系统授权后，返回本页再次选择安装。")
 .setPositiveButton(
 "打开授权页面",
 (d, n) -> {
 try {
 a.startActivity(
 new Intent(
 Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
 Uri.parse("package:" + a.getPackageName())));
 } catch (RuntimeException e) {
 a.notice("这台电视未提供授权页面，请使用电脑或 U 盘安装。");
 }
 })
 .setNegativeButton("稍后", null)
 .show();
 return;
 }
 new AppDialog.Builder(a)
 .setTitle("安装 " + updates.available.version + "？")
 .setMessage("将打开电视系统的安装确认页面。覆盖升级保留配置、收藏和观看记录。")
 .setPositiveButton("继续安装", (d, n) -> prepareInstall())
 .setNegativeButton("稍后", null)
 .show();
 }

 static Intent installIntent() {
 Intent intent =
 new Intent(Intent.ACTION_INSTALL_PACKAGE)
 .setDataAndType(
 UpdateApkProvider.URI, "application/vnd.android.package-archive")
 .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .putExtra(Intent.EXTRA_RETURN_RESULT, true);
 intent.setClipData(ClipData.newRawUri("鲸落 update", UpdateApkProvider.URI));
 return intent;
 }

 void prepareInstall() {
 final UpdateManager.Release release = updates.available;
 if (release == null || updates.busy) return;
 final int epoch = a.generation;
 updates.busy = true;
 updates.resetCancellation();
 updates.message = "正在核对安装包…";
 refresh();
 updates.worker.execute(
 () -> {
 try {
 UpdateManager.verifyApk(
 a, updates.apk(), release, updates.selectedVariant());
 a.ui.post(
 () -> {
 updates.busy = false;
 if (!a.foreground
 || a.isFinishing()
 || a.isDestroyed()
 || epoch != a.generation
 || updates.cancelled()
 || status == null
 || !status.isAttachedToWindow()) {
 updates.message = "校验完成，回到软件更新页后可继续安装";
 return;
 }
 try {
 updates.prefs
 .edit()
 .putInt("pending_code", release.code)
 .commit();
 a.startActivityForResult(installIntent(), INSTALL);
 updates.message = "请在系统安装页面确认或返回";
 } catch (RuntimeException error) {
 updates.prefs.edit().remove("pending_code").apply();
 updates.message = "无法打开安装器，请使用电脑或 U 盘安装";
 }
 refresh();
 });
 } catch (Exception error) {
 a.ui.post(
 () -> {
 updates.busy = false;
 updates.message = "安装包校验失败，请重新下载";
 refresh();
 });
 }
 });
 }

 /** 1.9.167：多路 APK 安装回退；全部失败时复制到 Download 供手动安装。 */
 static boolean installApkWithFallback(MainActivity a, java.io.File apk, String tag) {
 AppLog.i(
 "Update",
 "install enter tag="
 + tag
 + " path="
 + (apk == null ? "null" : apk.getAbsolutePath())
 + " exists="
 + (apk != null && apk.isFile())
 + " canRead="
 + (apk != null && apk.canRead()));
 if (apk == null || !apk.isFile()) {
 a.toast("安装包不存在或无法读取");
 return false;
 }
 try {
 java.io.File cache = new java.io.File(a.getCacheDir(), "updates");
 if (!cache.isDirectory() && !cache.mkdirs()) throw new java.io.IOException("cache");
 java.io.File release = new java.io.File(cache, "release.apk");
 try (java.io.InputStream in = new java.io.FileInputStream(apk);
 java.io.OutputStream out = new java.io.FileOutputStream(release)) {
 byte[] buf = new byte[65536];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 }
 // 1.9.176：系统安装器只认 file://；先准备一个可被安装器读取的绝对路径文件
 java.io.File staged = apk;
 try {
 java.io.File external = android.os.Environment.getExternalStorageDirectory();
 String sourcePath = apk.getAbsolutePath();
 if (external != null
 && !sourcePath.startsWith(external.getAbsolutePath())) {
 java.io.File dir = new java.io.File(external, "Download/DSH-Go/鲸落");
 if (!dir.isDirectory()) dir.mkdirs();
 java.io.File target = new java.io.File(dir, apk.getName());
 try (java.io.InputStream in2 = new java.io.FileInputStream(apk);
 java.io.OutputStream out2 = new java.io.FileOutputStream(target)) {
 byte[] buf = new byte[65536];
 int n;
 while ((n = in2.read(buf)) > 0) out2.write(buf, 0, n);
 }
 staged = target;
 AppLog.i("Update", "install stage copy=" + target.getAbsolutePath());
 }
 } catch (Throwable stageError) {
 AppLog.w("Update", "install stage failed", stageError);
 }
 final java.io.File installFile = staged;
 android.net.Uri fileUri = android.net.Uri.fromFile(installFile);
 final android.net.Uri contentUri = UpdateApkProvider.URI;
 AppLog.i(
 "Update",
 "install file="
 + installFile.getAbsolutePath()
 + " uri="
 + fileUri
 + " bytes="
 + installFile.length());
 android.net.Uri uri = fileUri;
 android.content.Intent install =
 new android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE, uri)
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
 .putExtra(android.content.Intent.EXTRA_RETURN_RESULT, true);
 AppLog.i("Update", "install step=launch_package tag=" + tag);
 if (launchInstall(a, install, "install_package")) return true;
 AppLog.i("Update", "install step=launch_view tag=" + tag);
 android.content.Intent view =
 new android.content.Intent(android.content.Intent.ACTION_VIEW)
 .setDataAndType(uri, "application/vnd.android.package-archive")
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 if (launchInstall(a, view, "view")) return true;
 String[] components = {
 "com.android.packageinstaller/.ui.PackageInstallerActivity",
 "com.android.packageinstaller/.PackageInstallerActivity",
 "com.miui.packageinstaller/.PackageInstallerActivity",
 "com.google.android.packageinstaller/.PackageInstallerActivity"
 };
 for (String component : components) {
 try {
 String[] parts = component.split("/");
 android.content.Intent explicit =
 new android.content.Intent(android.content.Intent.ACTION_VIEW)
 .setComponent(
 new android.content.ComponentName(
 parts[0],
 parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1]))
 .setDataAndType(uri, "application/vnd.android.package-archive")
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 if (launchInstall(a, explicit, "explicit:" + parts[0])) return true;
 } catch (Throwable error) {
 AppLog.i(
 "Update",
 "install try=explicit:"
 + component
 + " result=fail reason="
 + error.getClass().getSimpleName());
 }
 }
 // 1.9.176：SDK>=24 file:// 被拦时，再试 content provider（指向 cache/release.apk）
 if (android.os.Build.VERSION.SDK_INT >= 24) {
 AppLog.i("Update", "install step=content_fallback tag=" + tag);
 android.content.Intent installContent =
 new android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE, contentUri)
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 if (launchInstall(a, installContent, "install_package_content")) return true;
 android.content.Intent viewContent =
 new android.content.Intent(android.content.Intent.ACTION_VIEW)
 .setDataAndType(contentUri, "application/vnd.android.package-archive")
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 if (launchInstall(a, viewContent, "view_content")) return true;
 for (String component : components) {
 try {
 String[] parts = component.split("/");
 android.content.Intent explicit =
 new android.content.Intent(android.content.Intent.ACTION_VIEW)
 .setComponent(
 new android.content.ComponentName(
 parts[0],
 parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1]))
 .setDataAndType(contentUri, "application/vnd.android.package-archive")
 .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 if (launchInstall(a, explicit, "explicit_content:" + parts[0])) return true;
 } catch (Throwable ignored) {
 }
 }
 }
 // 全部失败：文件已在可访问路径，给出绝对路径与复制
 try {
 AppLog.i(
 "Update",
 "install step=dialog tag=" + tag + " path=" + installFile.getAbsolutePath());
 a.toast("无法调起安装器，请用文件管理器手动安装");
 final String shownPath = installFile.getAbsolutePath();
 new AppDialog.Builder(a)
 .setTitle("无法调起安装器")
 .setMessage("文件位置：\n" + shownPath + "\n\n请在文件管理器中打开安装。")
 .setNeutralButton(
 "复制路径",
 (d, w) -> {
 try {
 android.content.ClipboardManager cm =
 (android.content.ClipboardManager) a.getSystemService(
 android.content.Context.CLIPBOARD_SERVICE);
 cm.setPrimaryClip(android.content.ClipData.newPlainText("apk", shownPath));
 a.toast("路径已复制");
 } catch (Throwable ignored) {
 }
 })
 .setPositiveButton("知道了", null)
 .show();
 return false;
 } catch (Throwable copyError) {
 AppLog.w("Update", "install try=saved-copy result=fail", copyError);
 final String apkPath = apk.getAbsolutePath();
 AppLog.i("Update", "install step=dialog tag=" + tag + " path=" + apkPath + " copied=false");
 new AppDialog.Builder(a)
 .setTitle("无法调起安装器")
 .setMessage("安装包路径：\n" + apkPath + "\n\n请用文件管理器手动打开安装。")
 .setPositiveButton("知道了", null)
 .show();
 a.toast("安装失败，请用文件管理器手动安装");
 return false;
 }
 } catch (Throwable error) {
 AppLog.w("Update", "install fallback failed", error);
 a.toast("安装失败：" + error.getMessage());
 return false;
 }
 }

 private static boolean launchInstall(MainActivity a, android.content.Intent intent, String tag) {
 try {
 a.startActivity(intent);
 AppLog.i("Update", "install try=" + tag + " result=ok");
 return true;
 } catch (Throwable error) {
 AppLog.i(
 "Update",
 "install try="
 + tag
 + " result=fail reason="
 + error.getClass().getSimpleName()
 + (error.getMessage() == null ? "" : ":" + error.getMessage()));
 return false;
 }
 }

 static void result(MainActivity a) {
 UpdateManager updates = UpdateManager.get(a);
 updates.prefs.edit().remove("pending_code").apply();
 updates.message = "已返回应用；若取消安装，原版本继续保留";
 if (a.updateScreen != null) a.updateScreen.refresh();
 }
}
