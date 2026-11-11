package com.whalefall.movie;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * App 内置文件（APP FILES）：展示管理后台 / FTP 上传到应用内的文件，支持新建文件夹、重命名、删除。
 *
 * <p>Markdown / TXT 用内置阅读器打开；视频用内置播放器播放；其它文件可以复制路径。
 */
final class FilesScreen {
 private FilesScreen() {}

 static void show(MainActivity a) {
 show(a, "");
 }

 static void show(MainActivity a, String relDir) {
 a.open(new MainActivity.Screen(() -> render(a, relDir)), true);
 }

 private static void render(MainActivity a, String relDir) {
 a.clearPlaybackLoading();
 a.cancelPageRequests();
 a.generation++;
 a.applyMobileSystemBars(false);

 a.root = new FrameLayout(a);
 a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.installBackdrop(a.root);
 a.setContentView(a.root);

 LinearLayout column = a.vertical();
 // TV 遥控 overscan 安全边：横向 ≥24dp、纵向 ≥18dp；手机沿用原值
 column.setPadding(
 a.dp(a.isTvDevice() ? 24 : 20),
 a.isTvDevice()
 ? a.dp(18)
 : MobileDetailScreen.getStatusBarHeight(a) + a.dp(12),
 a.dp(a.isTvDevice() ? 24 : 20),
 a.dp(a.isTvDevice() ? 18 : 32));
 ScrollView scroll = new ScrollView(a);
 scroll.setVerticalScrollBarEnabled(false);
 scroll.setFillViewport(true);
 scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
 a.root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
 a.scroll = scroll;
 a.body = column;
 a.body.setTag("app-files");

 final String path = PushFiles.sanitizePath(relDir);

 // 1.9.105：英文小标固定为 APP FILES，和「文件管理器」(FILE MANAGER) 区分
 TextView eyebrow = a.text("APP FILES", 12, 0xffffc2d4);
 eyebrow.setLetterSpacing(.15f);
 column.addView(eyebrow);
 a.gap(column, 8);

 LinearLayout nav = a.row();
 nav.setGravity(Gravity.CENTER_VERTICAL);
 TextView back = a.text("‹", 24, MainActivity.WHITE);
 back.setGravity(Gravity.CENTER);
 back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(back);
 ExtraPages.tvFocus(a, back, 999);
 back.setOnClickListener(
 v -> {
 AppLog.i(
 "Back",
 "app-files path="
 + path
 + " target="
 + (a.back.isEmpty()
 ? "home"
 : String.valueOf(a.back.peek().pushedTag)));
 // 1.9.82：直接显式 popBack，不再经过 onBackPressed（避免与 FileManager 分支互串）
 a.popBack();
 });
 nav.addView(back, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
 // 1.9.105：本页是 App 内置文件（教程/配置/后台上传），标题与「文件管理」区分
 TextView title =
 a.text(path.isEmpty() ? "App 内置文件" : PushFiles.baseName(path), 20, MainActivity.WHITE);
 title.setTypeface(null, Typeface.BOLD);
 title.setSingleLine(true);
 title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
 LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
 titleLp.leftMargin = a.dp(12);
 nav.addView(title, titleLp);
 TextView uploadBtn = a.text("上传文件", 12, 0xffffb3c8);
 uploadBtn.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
 uploadBtn.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(uploadBtn);
 ExtraPages.tvFocus(a, uploadBtn, 999);
 if (a.isTvDevice()) uploadBtn.setVisibility(View.GONE); // R5：TV 隐藏触屏小按钮
 uploadBtn.setOnClickListener(
 v -> {
 if (a.isTvDevice()) {
 // 遥控端统一降级：不打开系统文件选择器
 ExtraPages.degrade(a, true);
 return;
 }
 a.pickAndUploadFile();
 });
 LinearLayout.LayoutParams uploadLp = new LinearLayout.LayoutParams(-2, -2);
 uploadLp.rightMargin = a.dp(8);
 nav.addView(uploadBtn, uploadLp);

 TextView adminBtn = a.text("管理员后台", 12, 0xffffb3c8);
 adminBtn.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
 adminBtn.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(adminBtn);
 ExtraPages.tvFocus(a, adminBtn, 999);
 adminBtn.setOnClickListener(v -> AdminPanel.show(a));
 LinearLayout.LayoutParams adminLp = new LinearLayout.LayoutParams(-2, -2);
 adminLp.rightMargin = a.dp(8);
 nav.addView(adminBtn, adminLp);

 TextView mkdir = a.text("＋ 文件夹", 12.5f, 0xffffb3c8);
 mkdir.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
 mkdir.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(mkdir);
 ExtraPages.tvFocus(a, mkdir, 999);
 if (a.isTvDevice()) mkdir.setVisibility(View.GONE); // R5：TV 隐藏「新建文件夹」（键盘操作，走降级口径）
 mkdir.setOnClickListener(v -> createFolder(a, path));
 nav.addView(mkdir);
 column.addView(nav, new LinearLayout.LayoutParams(-1, -2));
 a.gap(column, 6);
 if (path.isEmpty()) {
 // 1.9.105：页面副标题，说明这里放的是什么文件
 TextView subtitle = a.text("教程 / 配置 / 后台上传的文件", 12, 0xff9fb0c9);
 column.addView(subtitle);
 }
 a.gap(column, 14);
 if (path.isEmpty()) {
 // 1.9.10：与新的「文件管理器」区分说明
 LinearLayout tip = a.row();
 tip.setGravity(Gravity.CENTER_VERTICAL);
 tip.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
 tip.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 16));
 TextView tipText =
 a.text(
 "这里是 App 内置配置/教程文件；本地与网盘文件请用「文件管理器」",
 11.5f,
 0xff9fb0c9);
 tipText.setLineSpacing(a.dp(2), 1.05f);
 tip.addView(tipText, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView openMgr = a.text("打开文件管理器", 12, 0xffffb3c8);
 openMgr.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
 openMgr.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(openMgr);
 ExtraPages.tvFocus(a, openMgr, 999);
 openMgr.setOnClickListener(v -> FileManagerScreen.show(a));
 LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-2, -2);
 openLp.leftMargin = a.dp(8);
 tip.addView(openMgr, openLp);
 column.addView(tip, new LinearLayout.LayoutParams(-1, -2));
 a.gap(column, 10);
 }

 if (path.isEmpty()) {
 LinearLayout hint = a.vertical();
 hint.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
 hint.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 18));
 TextView h1 = a.text("怎么把文件放进 App？", 14, MainActivity.WHITE);
 h1.setTypeface(null, Typeface.BOLD);
 hint.addView(h1);
 String ftp =
 MiniFtpServer.RUNNING == null
 ? "管理后台 → 文件 上传"
 : "FTP：ftp://" + AdminConsole.lanIp() + ":2121/";
 TextView h2 = a.text(
 "设置 → 备份、更新与诊断 → 管理员后台（网页）打开后：\n· "
 + ftp
 + "\n· 或网页后台「文件」页直接上传",
 11.5f,
 0xff9fb0c9);
 h2.setLineSpacing(a.dp(3), 1.05f);
 LinearLayout.LayoutParams h2Lp = new LinearLayout.LayoutParams(-1, -2);
 h2Lp.topMargin = a.dp(6);
 hint.addView(h2, h2Lp);
 column.addView(hint, new LinearLayout.LayoutParams(-1, -2));
 a.gap(column, 12);
 }

 List<File> files = PushFiles.list(a, path);
 if (files.isEmpty()) {
 LinearLayout empty = a.vertical();
 empty.setGravity(Gravity.CENTER);
 empty.setPadding(a.dp(16), a.dp(34), a.dp(16), a.dp(34));
 empty.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 18));
 TextView ico = a.text("", 34, 0xffffb3c8);
 ico.setGravity(Gravity.CENTER);
 empty.addView(ico);
 TextView t = a.text(path.isEmpty() ? "还没有文件" : "这个文件夹是空的", 15, MainActivity.WHITE);
 t.setTypeface(null, Typeface.BOLD);
 t.setGravity(Gravity.CENTER);
 empty.addView(t);
 TextView d = a.text("通过管理后台 / FTP 上传后，这里会出现文件", 12, 0xff8d9cb6);
 d.setGravity(Gravity.CENTER);
 empty.addView(d);
 column.addView(empty, new LinearLayout.LayoutParams(-1, -2));
 // 空态：设计规定首焦点=「管理员后台」
 if (a.isTvDevice()) adminBtn.setTag("primary");
 tvFirstFocus(a, a.isTvDevice() ? adminBtn : back);
 return;
 }

 View firstTvTarget = null;
 for (final File f : files) {
 final String rel = PushFiles.join(path, f.getName());
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(a.dp(14), a.dp(12), a.dp(12), a.dp(12));
 row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 16));
 MobileDetailScreen.bindTouchFeedback(row);
 // TV：文件行可遥控聚焦；OK 打开、长按 OK 弹出重命名/删除菜单
 ExtraPages.tvFocus(a, row, 16);
 if (firstTvTarget == null) {
 firstTvTarget = row;
 // restore() 的 TV 首焦点会先找 tag "primary"：让首个文件行拿到它
 if (a.isTvDevice()) row.setTag("primary");
 }

 String name = f.getName();
 boolean dir = f.isDirectory();
 TextView type = a.text(dir ? "" : fileBadge(name), dir ? 18 : 16, 0xffffffff);
 type.setTypeface(null, Typeface.BOLD);
 type.setGravity(Gravity.CENTER);
 FrameLayout badge = new FrameLayout(a);
 badge.setBackground(MobileDetailScreen.glassBg(
 a,
 dir
 ? 0xcc3a2b52
 : PushFiles.isMarkdown(name)
 ? 0xffff8fb1
 : PushFiles.isVideo(name) ? 0xff6a8cff : 0xcc2b3a52,
 0x33ffffff,
 12));
 badge.addView(type, new FrameLayout.LayoutParams(-1, -1));
 row.addView(badge, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));

 LinearLayout meta = a.vertical();
 TextView n = a.text((dir ? " " : "") + name, 14, MainActivity.WHITE);
 n.setTypeface(null, Typeface.BOLD);
 n.setSingleLine(true);
 n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
 meta.addView(n);
 String date =
 new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(f.lastModified()));
 TextView s =
 a.text(
 dir
 ? (f.listFiles() == null ? 0 : f.listFiles().length) + " 项"
 : PushFiles.sizeLabel(f.length()) + " · " + date,
 11,
 0xff8d9cb6);
 s.setPadding(0, a.dp(3), 0, 0);
 meta.addView(s);
 LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, -2, 1f);
 metaLp.leftMargin = a.dp(12);
 row.addView(meta, metaLp);

 // R5：TV 隐藏行尾「…」触屏小按钮；同功能走长按 OK 菜单
 if (!a.isTvDevice()) {
 TextView more = a.text("…", 16, 0xffc9d4e6);
 more.setGravity(Gravity.CENTER);
 more.setPadding(a.dp(10), 0, a.dp(10), 0);
 more.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 more.setOnClickListener(v -> fileMenu(a, rel, path));
 row.addView(more, new LinearLayout.LayoutParams(a.dp(38), a.dp(30)));
 }

 final String target = rel;
 final File targetFile = f;
 row.setOnClickListener(
 v -> {
 if (targetFile.isDirectory()) {
 show(a, target);
 } else if (PushFiles.isMarkdown(target)) {
 MarkdownScreen.show(a, target);
 } else if (PushFiles.isImage(target)) {
 showImage(a, targetFile, target);
 } else if (PushFiles.isApk(target)) {
 installApk(a, targetFile);
 } else if (PushFiles.isVideo(target)) {
 PushFiles.playUrl(a, "file://" + targetFile.getAbsolutePath(), target);
 } else {
 a.toast("路径：" + targetFile.getAbsolutePath());
 }
 });
 row.setOnLongClickListener(
 v -> {
 // 1.9.104：App 内置文件长按图片/视频 → 壁纸菜单
 if (PushFiles.isImage(target) || PushFiles.isVideo(target)) {
 AppLog.i(
 "FileScreen",
 "longpress type=" + (PushFiles.isVideo(target) ? "video" : "image") + " action=wallpaper");
 new AppDialog.Builder(a)
 .setTitle(PushFiles.isVideo(target) ? "设为动态壁纸" : "设为壁纸")
 .setMessage("复制到 App 壁纸目录并立即启用，与文件管理器长按设壁纸一致。")
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "设为壁纸",
 (d, w) ->
 FileManagerScreen.setWallpaperFromFile(
 a, targetFile, PushFiles.isVideo(target)))
 .show();
 return true;
 }
 fileMenu(a, rel, path);
 return true;
 });

 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.bottomMargin = a.dp(8);
 column.addView(row, lp);
 }
 tvFirstFocus(a, firstTvTarget);
 }

 /** TV 端：页面渲染后给一个明确的初始焦点（不抢用户已有的焦点）。 */
 private static void tvFirstFocus(final MainActivity a, final View target) {
 if (!a.isTvDevice() || target == null) return;
 target.post(
 () -> {
 if (!a.isTvDevice() || a.isFinishing()) return;
 if (a.getCurrentFocus() == null) target.requestFocus();
 });
 }

 private static void fileMenu(MainActivity a, String rel, String dir) {
 final String[] labels = {"重命名", "删除"};
 new AppDialog.Builder(a)
 .setTitle(PushFiles.baseName(rel))
 .setItems(
 labels,
 (d, index) -> {
 if (index == 0) rename(a, rel, dir);
 else
 ExtraPages.confirmDanger(
 a,
 "删除",
 "确定删除「" + PushFiles.baseName(rel) + "」？不可恢复。",
 "删除",
 () -> {
 PushFiles.delete(a, rel);
 a.toast("已删除");
 show(a, dir);
 });
 })
 .setNegativeButton("取消", null)
 .show();
 }

 private static void rename(MainActivity a, String rel, String dir) {
 if (a.isTvDevice()) {
 ExtraPages.degrade(a, true);
 return;
 }
 EditText input = new EditText(a);
 input.setText(PushFiles.baseName(rel));
 input.setSelectAllOnFocus(true);
 new AppDialog.Builder(a)
 .setTitle("重命名")
 .setView(input)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "保存",
 (d, w) -> {
 try {
 PushFiles.rename(a, rel, input.getText().toString());
 a.toast("已重命名");
 show(a, dir);
 } catch (Exception e) {
 a.toast("重命名失败：" + e.getMessage());
 }
 })
 .show();
 }

 private static void createFolder(MainActivity a, String dir) {
 if (a.isTvDevice()) {
 ExtraPages.degrade(a, true);
 return;
 }
 EditText input = new EditText(a);
 input.setHint("文件夹名称");
 new AppDialog.Builder(a)
 .setTitle("新建文件夹")
 .setView(input)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "创建",
 (d, w) -> {
 try {
 String name = input.getText().toString().trim();
 if (name.isEmpty()) throw new IllegalArgumentException("名称不能为空");
 PushFiles.mkdirs(a, PushFiles.join(dir, name));
 a.toast("已创建文件夹");
 show(a, dir);
 } catch (Exception e) {
 a.toast("创建失败：" + e.getMessage());
 }
 })
 .show();
 }

 /** 图片预览：全屏暗底 + 自适应图片，点任意处返回。 */
 private static void showImage(MainActivity a, File file, String rel) {
 FrameLayout overlay = new FrameLayout(a);
 overlay.setBackgroundColor(0xee000000);
 overlay.setClickable(true);
 final android.widget.ImageView image = new android.widget.ImageView(a);
 image.setAdjustViewBounds(true);
 image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
 final String path = file.getAbsolutePath();
 new Thread(
 () -> {
 android.graphics.Bitmap bitmap =
 android.graphics.BitmapFactory.decodeFile(path);
 if (bitmap != null) a.ui.post(() -> image.setImageBitmap(bitmap));
 })
 .start();
 overlay.addView(image, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
 TextView hint = a.text(PushFiles.baseName(rel) + (a.isTvDevice() ? " · 按返回键关闭" : " · 双指缩放 · 点按返回"), 12, 0xccffffff);
 hint.setGravity(Gravity.CENTER);
 FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
 hintLp.bottomMargin = a.dp(32);
 overlay.addView(hint, hintLp);

 final android.graphics.Matrix matrix = new android.graphics.Matrix();
 final float[] scale = {1f};
 final float[] down = new float[2];
 final float[] last = new float[2];
 final android.view.ScaleGestureDetector scaler =
 new android.view.ScaleGestureDetector(
 a,
 new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
 @Override
 public boolean onScale(android.view.ScaleGestureDetector detector) {
 float factor = detector.getScaleFactor();
 float next = Math.max(1f, Math.min(6f, scale[0] * factor));
 factor = next / scale[0];
 scale[0] = next;
 matrix.postScale(
 factor, factor, detector.getFocusX(), detector.getFocusY());
 image.setImageMatrix(matrix);
 image.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
 return true;
 }
 });
 image.setOnTouchListener(
 new android.view.View.OnTouchListener() {
 @Override
 public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
 scaler.onTouchEvent(event);
 int action = event.getActionMasked();
 if (action == android.view.MotionEvent.ACTION_DOWN) {
 down[0] = event.getX();
 down[1] = event.getY();
 last[0] = down[0];
 last[1] = down[1];
 } else if (action == android.view.MotionEvent.ACTION_MOVE) {
 if (event.getPointerCount() == 1) {
 if (scale[0] > 1.02f) {
 matrix.postTranslate(
 event.getX() - last[0], event.getY() - last[1]);
 image.setImageMatrix(matrix);
 }
 last[0] = event.getX();
 last[1] = event.getY();
 }
 } else if (action == android.view.MotionEvent.ACTION_UP) {
 float dx = Math.abs(event.getX() - down[0]);
 float dy = Math.abs(event.getY() - down[1]);
 if (scale[0] <= 1.02f && dx < 24 && dy < 24)
 a.root.removeView(overlay);
 }
 return true;
 }
 });
 a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
 if (a.isTvDevice()) {
 // 遥控端：聚焦遮罩后按返回/OK 关闭，避免 BACK 直接退掉整个文件管理页
 overlay.setFocusable(true);
 overlay.setFocusableInTouchMode(true);
 overlay.requestFocus();
 overlay.setOnKeyListener(
 (v, keyCode, event) -> {
 if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
 if (keyCode == android.view.KeyEvent.KEYCODE_BACK
 || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
 a.root.removeView(overlay);
 return true;
 }
 return false;
 });
 }
 }

 /** 安装上传的 APK：复制到更新缓存目录，用现有 provider 交给系统安装器。 */
 private static void installApk(MainActivity a, File file) {
 try {
 File cacheDir = new File(a.getCacheDir(), "updates");
 if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new java.io.IOException("cache");
 File target = new File(cacheDir, "release.apk");
 try (java.io.InputStream in = new java.io.FileInputStream(file);
 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
 byte[] buf = new byte[65536];
 int n;
 while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
 }
 android.content.Intent intent =
 new android.content.Intent(android.content.Intent.ACTION_VIEW);
 intent.setDataAndType(UpdateApkProvider.URI, "application/vnd.android.package-archive");
 intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
 intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
 a.startActivity(intent);
 } catch (Exception e) {
 a.toast("安装失败：" + e.getMessage());
 }
 }

 private static String fileBadge(String name) {
 if (PushFiles.isMarkdown(name)) return "MD";
 if (PushFiles.isVideo(name)) return "视频";
 int dot = name.lastIndexOf('.');
 String ext = dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "?";
 return ext.length() > 2 ? ext.substring(0, 2) : ext;
 }
}
