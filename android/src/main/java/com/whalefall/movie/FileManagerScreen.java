package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.9.0 内置文件管理器（按 docs/DESIGN_FILE_MANAGER_1.9.0.md 实现核心 UI）：
 * 顶部存储概览 + 本地/网盘分段 + 面包屑/排序 + 响应式网格 + 多选操作栏 + 下载任务条。
 * 本地操作接 FileManagerCore；网盘浏览复用 QuarkUcApi/QuarkUcFiles 的凭据选择与回退。
 */
final class FileManagerScreen {
 private static FileManagerScreen current;

 private final MainActivity a;
 private int tab; // 0 本地 1 网盘
 private File localDir;
 /** 1.9.105：本地根目录（/sdcard），在该层聚合 Download/鲸落/Movies/DCIM。 */
 private File localRootDir;
 /** 1.9.105：本地目录前进栈：BACK/上级按栈回退，聚合根进入的目录也能回到聚合根。 */
 private final java.util.ArrayDeque<File> dirStack = new java.util.ArrayDeque<>();
 private LinearLayout breadcrumbRow, grid, content;
 private TextView pathLabel, emptyView, selectionCount;
 private LinearLayout selectionBar, downloadStrip;
 private final List<Object> items = new ArrayList<>();
 private boolean selecting;
 private final java.util.Set<File> selFiles = new java.util.HashSet<>();
 private final java.util.Set<String> selFids = new java.util.HashSet<>();
 private int sort; // 0 名称 1 时间 2 大小
 /** 1.9.13：0全部 1文件夹 2视频 3图片 4文本·文档 5其他。 */
 private int filter;
 private LinearLayout filterChips;
 private LinearLayout actionChips;
 private String providerKey = NetdiskQrLogin.QUARK;
 private String netdiskCookie = "", netdiskPath = "根目录", netdiskFid = "0";
 private boolean netdiskFromSource;
 private String activeTaskId;
 private boolean stripPaused;
 private long lastPauseToggle;
 private TextView downCenterBtn;
 private LinearLayout globalStrip;
 private TextView globalText;
 private TextView stripPause;
 private LinearLayout stripProgressWrap;

 private static int lastSource = 0; // 0=设置 1=云盘专区

 static void show(MainActivity a) {
 showFrom(a, lastSource);
 }

 static void showFrom(MainActivity a, int source) {
 // 1.9.97：无存储权限时不显示空列表，先申请/引导
 if (!a.hasStoragePermission()) {
 AppLog.i("Perm", "storage granted=false target=file-manager");
 a.requestStoragePermission(() -> showFrom(a, source));
 return;
 }
 lastSource = source;
 FileManagerScreen screen = new FileManagerScreen(a);
 screen.open();
 current = screen;
 }

 /** 1.9.30：pop 回文件管理器时保留当前目录/筛选状态。 */
 static void resume(MainActivity a) {
 FileManagerScreen screen = current;
 if (screen != null && screen.a == a) screen.open();
 else show(a);
 }

 /** 1.9.10：BACK 回到来源（设置或云盘专区）。 */
 static void exitToSource(MainActivity a) {
 MobileShell.show(a);
 if (MobileShell.activeShell == null) return;
 MobileShell.activeShell.switchTab(lastSource == 1 ? 6 : 4);
 }

 static boolean handleBack() {
 FileManagerScreen screen = current;
 if (screen == null) return false;
 return screen.back();
 }

 private FileManagerScreen(MainActivity a) {
 this.a = a;
 // 1.9.104：根目录聚合本地存储，不再只看 App 私有目录
 // 1.9.105：本地根 = /sdcard，聚合 Download / Download/DSH-Go/鲸落 / Movies / DCIM
 this.localDir = localRoot(a);
 this.localRootDir = this.localDir;
 AppLog.i("FileManager", "scan root=" + this.localDir.getAbsolutePath());
 }

 /** 1.9.105：本地根 = 外部存储根 /sdcard（下钻聚合四个来源目录）；不可用时回退 App 下载目录。 */
 static java.io.File localRoot(MainActivity a) {
 try {
 java.io.File external = android.os.Environment.getExternalStorageDirectory();
 if (external != null && external.isDirectory()) return external;
 } catch (Throwable ignored) {
 }
 return FileManagerCore.downloadDir(a);
 }

 /** 1.9.105：根目录聚合来源（/sdcard/Download、Download/DSH-Go/鲸落、Movies、DCIM）。 */
 private static java.util.List<java.io.File> localSources(MainActivity a) {
 java.util.List<java.io.File> out = new java.util.ArrayList<>();
 try {
 java.io.File external = android.os.Environment.getExternalStorageDirectory();
 if (external != null) {
 out.add(new java.io.File(external, "Download"));
 out.add(new java.io.File(external, "Download/DSH-Go/鲸落"));
 out.add(new java.io.File(external, "Movies"));
 out.add(new java.io.File(external, "DCIM"));
 }
 } catch (Throwable ignored) {
 }
 if (out.isEmpty()) out.add(FileManagerCore.downloadDir(a));
 return out;
 }

 /** 1.9.105：当前是否停在本地聚合根。 */
 private boolean aggregateRoot() {
 return localDir != null && localDir.equals(localRootDir);
 }

 /** 1.9.105：进入子目录前记录当前目录，BACK/上级按栈回退。 */
 private void pushDir() {
 if (localDir != null) dirStack.push(localDir);
 }

 /** 1.9.105：文件所属来源目录名（取最长匹配，鲸落优先于 Download）。 */
 private String localSourceName(java.io.File file) {
 if (file == null) return "";
 String path = file.getAbsolutePath();
 String best = "";
 int bestLen = -1;
 for (java.io.File dir : localSources(a)) {
 String base = dir.getAbsolutePath();
 if ((path.equals(base) || path.startsWith(base + java.io.File.separator))
 && base.length() > bestLen) {
 bestLen = base.length();
 best = dir.getName();
 }
 }
 return best;
 }

 /** 1.9.105：本地路径（聚合根 → 本地文件；子目录 → 本地文件 / 来源 / 子路径）。 */
 private String localPathLabel() {
 if (aggregateRoot()) return "本地文件";
 String path = localDir.getAbsolutePath();
 String best = "";
 String bestName = "";
 for (java.io.File dir : localSources(a)) {
 String base = dir.getAbsolutePath();
 if ((path.equals(base) || path.startsWith(base + java.io.File.separator))
 && base.length() > best.length()) {
 best = base;
 bestName = dir.getName();
 }
 }
 if (best.isEmpty()) return "本地文件 / " + localDir.getName();
 String rest = path.substring(best.length());
 if (rest.startsWith("/")) rest = rest.substring(1);
 return "本地文件 / " + bestName + (rest.isEmpty() ? "" : " / " + rest.replace("/", " / "));
 }

 private void open() {
 // 1.9.105：标题区自绘（英文小标 FILE MANAGER + 标题 + 副标题 + 存储空间）：
 // 顶栏两行，renderPage 只负责页面骨架，不再由它出标题
 a.renderPage("", "");
 if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.body == null) return;
 a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 a.body.setTag("file-manager");
 // 1.9.2：自行控制左右留白，避免 renderPage 默认 padding 与网格 margin 叠加导致超宽/贴边
 a.body.setPadding(0, statusBarHeightSafe() + a.dp(8), 0, a.dp(24));
 selectionBar = null;
 downloadStrip = null;
 stripProgressWrap = null;
 providerBar = null;
 content = null;
 AppLog.i(
 "FileMgr",
 "page=FileManager tab=" + (tab == 0 ? "local" : "netdisk") + " items=0");
 buildHeader();
 if (!MainActivity.cloudEnabled(a)) tab = 0;
 // 1.9.105：第一行（标题+存储）紧跟第二行（chips + 排序/新建），分段放在其下
 buildTopBar();
 buildSegment();
 if (tab == 1) buildProviderChips();
 buildToolbar();
 content = a.vertical();
 a.body.addView(content);
 globalStrip = a.row();
 globalStrip.setGravity(Gravity.CENTER_VERTICAL);
 globalStrip.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 globalStrip.setBackground(MobileDetailScreen.glassBg(a, 0xee0d1a2b, 0x33ffffff, 14));
 globalText = a.text("", 11, UiTokens.TEXT_SECONDARY);
 globalStrip.addView(globalText, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView centerGo = a.text("查看", 12, UiTokens.ACCENT);
 centerGo.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
 MobileDetailScreen.bindTouchFeedback(centerGo);
 centerGo.setOnClickListener(v -> DownloadCenterScreen.show(a));
 globalStrip.addView(centerGo);
 LinearLayout.LayoutParams gslp = new LinearLayout.LayoutParams(-1, -2);
 gslp.leftMargin = a.dp(24);
 gslp.rightMargin = a.dp(24);
 gslp.bottomMargin = a.dp(8);
 a.body.addView(globalStrip, gslp);
 // 放到内容区上方
 int contentIndex = a.body.indexOfChild(content);
 a.body.removeView(globalStrip);
 a.body.addView(globalStrip, Math.max(0, contentIndex));
 startDownloadPoll();
 refresh();
 tvFocusFirst();
 }

 // ---------------- 顶部：标题 + 存储空间（第一行） ----------------

 /** 1.9.0：bindTouchFeedback 会关闭 focusable，必须先绑定再打开焦点。 */
 private void focusable(View view) {
 MobileDetailScreen.bindTouchFeedback(view);
 view.setFocusable(true);
 view.setFocusableInTouchMode(true);
 view.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 v,
 focused,
 0x00000000,
 0x33ffffff,
 16,
 UiTokens.TEXT_PRIMARY,
 UiTokens.TEXT_PRIMARY,
 focused,
 1.02f));
 }

 /**
 * 1.9.105：顶栏第一行 = 英文小标 APP 页眉（FILE MANAGER）+ 标题「文件管理」+ 副标题
 * 「本地存储 / 网盘 / 下载」，右侧 = /sdcard 的 StatFs 已用/可用 + 进度条。
 */
 private void buildHeader() {
 // TV overscan / 手机左右安全边：右侧 ≥24dp，避免存储数值溢出
 int side = a.dp(24);
 LinearLayout header = a.vertical();
 header.setPadding(side, 0, side, a.dp(2));
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 LinearLayout titleCol = a.vertical();
 TextView eye = a.text("FILE MANAGER", 11.5f, 0xffffc2d4);
 eye.setLetterSpacing(.15f);
 titleCol.addView(eye);
 TextView title = a.text("文件管理", 24, MainActivity.WHITE);
 title.setTypeface(null, android.graphics.Typeface.BOLD);
 titleCol.addView(title);
 TextView subtitle = a.text("本地存储 / 网盘 / 下载", 11.5f, UiTokens.TEXT_TERTIARY);
 subtitle.setPadding(0, a.dp(3), 0, 0);
 titleCol.addView(subtitle);
 row.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
 // 存储空间：/sdcard 的 StatFs 已用/可用（失败再回退 App 外部目录）
 long total = 0, available = 0;
 boolean ok = false;
 java.io.File statDir = android.os.Environment.getExternalStorageDirectory();
 for (int attempt = 0; attempt < 2 && !ok; attempt++) {
 if (attempt == 1) statDir = a.getExternalFilesDir(null);
 if (statDir == null) continue;
 try {
 android.os.StatFs stat = new android.os.StatFs(statDir.getAbsolutePath());
 total = stat.getTotalBytes();
 available = stat.getAvailableBytes();
 ok = total > 0;
 } catch (Throwable ignored) {
 total = 0;
 available = 0;
 }
 }
 long used = Math.max(0, total - available);
 LinearLayout storageCol = a.vertical();
 TextView label = a.text("存储空间", 10.5f, UiTokens.TEXT_TERTIARY);
 label.setGravity(Gravity.END);
 storageCol.addView(label);
 TextView value =
 a.text(
 ok
 ? "已用 " + QuarkUcApi.formatSize(used) + " · 可用 " + QuarkUcApi.formatSize(available)
 : "存储信息不可用",
 11.5f,
 UiTokens.TEXT_SECONDARY);
 value.setGravity(Gravity.END);
 value.setPadding(a.dp(10), a.dp(4), 0, 0);
 storageCol.addView(value);
 ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
 bar.setMax(100);
 bar.setProgress(total > 0 ? (int) (used * 100 / total) : 0);
 bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
 LinearLayout.LayoutParams barLp =
 new LinearLayout.LayoutParams(a.isTvDevice() ? a.dp(200) : a.dp(110), a.dp(3));
 barLp.topMargin = a.dp(6);
 storageCol.addView(bar, barLp);
 LinearLayout.LayoutParams storageLp = new LinearLayout.LayoutParams(-2, -2);
 storageLp.leftMargin = a.dp(12);
 row.addView(storageCol, storageLp);
 header.addView(row);
 a.body.addView(header);
 }

 /**
 * 1.9.105：顶栏第二行——左侧 HorizontalScrollView 筛选 chips（全部/文件夹/视频/图片/文本/其他
 * 下载中心/网盘诊断），右侧排序/新建固定不滚动；右边距 ≥24dp，TV 左右 ≥24dp。
 */
 private void buildTopBar() {
 final int side = a.dp(a.isTvDevice() ? 24 : 16);
 LinearLayout bar = a.row();
 bar.setGravity(Gravity.CENTER_VERTICAL);
 bar.setPadding(side, a.dp(8), a.dp(24), a.dp(6));
 android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(a);
 scroll.setHorizontalScrollBarEnabled(false);
 scroll.setClipToPadding(false);
 LinearLayout chipsRow = a.row();
 chipsRow.setGravity(Gravity.CENTER_VERTICAL);
 filterChips = a.row();
 filterChips.setGravity(Gravity.CENTER_VERTICAL);
 chipsRow.addView(filterChips);
 actionChips = a.row();
 actionChips.setGravity(Gravity.CENTER_VERTICAL);
 downCenterBtn = a.text("下载中心", 11.5f, UiTokens.ACCENT_SOFT);
 downCenterBtn.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 downCenterBtn.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(downCenterBtn);
 downCenterBtn.setOnClickListener(v -> DownloadCenterScreen.show(a));
 LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-2, -2);
 dcLp.leftMargin = a.dp(6);
 actionChips.addView(downCenterBtn, dcLp);
 TextView diagBtn = a.text("网盘诊断", 11.5f, UiTokens.TEXT_PRIMARY);
 diagBtn.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 diagBtn.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(diagBtn);
 diagBtn.setOnClickListener(v -> NetdiskDiagScreen.show(a));
 LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(-2, -2);
 diagLp.leftMargin = a.dp(6);
 actionChips.addView(diagBtn, diagLp);
 chipsRow.addView(actionChips);
 scroll.addView(chipsRow, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
 bar.addView(scroll, new LinearLayout.LayoutParams(0, -2, 1f));
 // 右侧固定：排序 + 新建（不随 chips 滚动）
 TextView sortChip = a.text("", 11.5f, UiTokens.ACCENT_SOFT);
 sortChip.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 sortChip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 sortChip.setFocusable(true);
 focusable(sortChip);
 final TextView sortRef = sortChip;
 sortChip.setOnClickListener(
 v -> {
 sort = (sort + 1) % 3;
 sortRef.setText("排序：" + sortLabel());
 refresh();
 });
 sortRef.setText("排序：" + sortLabel());
 LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(-2, -2);
 sortLp.leftMargin = a.dp(8);
 bar.addView(sortChip, sortLp);
 if (tab == 0) {
 TextView newDir = a.text("新建", 11.5f, UiTokens.ACCENT_SOFT);
 newDir.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 newDir.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 newDir.setFocusable(true);
 focusable(newDir);
 newDir.setOnClickListener(v -> createFolderDialog());
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.leftMargin = a.dp(6);
 bar.addView(newDir, lp);
 }
 a.body.addView(bar);
 updateFilterChips();
 }

 // ---------------- 分段 ----------------

 private void buildSegment() {
 LinearLayout row = a.row();
 row.setPadding(0, a.dp(10), 0, a.dp(6));
 int segmentCount = MainActivity.cloudEnabled(a) ? 2 : 1;
 for (int i = 0; i < segmentCount; i++) {
 final int mode = i;
 TextView chip =
 a.text(
 i == 0 ? "本地文件" : "网盘",
 13,
 tab == i ? 0xff0b1220 : UiTokens.TEXT_PRIMARY);
 chip.setGravity(Gravity.CENTER);
 chip.setPadding(a.dp(8), a.dp(9), a.dp(8), a.dp(9));
 chip.setBackground(
 tab == i
 ? UiTokens.round(a, 999, UiTokens.ACCENT)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 chip.setFocusable(true);
 focusable(chip);
 chip.setOnClickListener(
 v -> {
 if (tab == mode) return;
 exitSelection();
 tab = mode;
 rebuild();
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(44), 1f);
 if (i > 0) lp.leftMargin = a.dp(8);
 chip.setGravity(Gravity.CENTER);
 row.addView(chip, lp);
 }
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 int side = a.dp(a.isTvDevice() ? 24 : 16);
 lp.leftMargin = side;
 lp.rightMargin = side;
 a.body.addView(row, lp);
 }

 // ---------------- 工具栏：面包屑（排序/新建已移到顶栏第二行右侧） ----------------

 private void buildToolbar() {
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 // 1.9.104：TV overscan/手机左右安全边 ≥24dp
 int sidePad = a.dp(a.isTvDevice() ? 24 : 12);
 row.setPadding(sidePad, a.dp(4), sidePad, a.dp(4));
 pathLabel = a.text("", 11.5f, UiTokens.TEXT_TERTIARY);
 pathLabel.setSingleLine(true);
 pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.START);
 row.addView(pathLabel, new LinearLayout.LayoutParams(0, -2, 1f));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.leftMargin = a.dp(a.isTvDevice() ? 24 : 16);
 lp.rightMargin = a.dp(a.isTvDevice() ? 24 : 16);
 a.body.addView(row, lp);
 breadcrumbRow = a.row();
 a.body.addView(breadcrumbRow);
 }

 private String sortLabel() {
 return sort == 0 ? "名称 ↑" : sort == 1 ? "时间 ↓" : "大小 ↓";
 }

 private void rebuild() {
 a.body.removeAllViews();
 open();
 }

 // ---------------- 刷新内容 ----------------

 private static final String[] FILTER_NAMES = {
 "全部", "文件夹", "视频", "图片", "文本", "其他"
 };

 /** 1.9.94：内置文件选择模式（壁纸等），只允许选择图片或视频。 */
 interface PickCallback {
 void picked(java.io.File file);
 }

 static PickCallback pickCallback;
 static boolean pickVideoMode;

 static void pick(MainActivity a, boolean video, PickCallback callback) {
 pickCallback = callback;
 pickVideoMode = video;
 showFrom(a, 0);
 if (current != null) {
 current.filter = video ? 2 : 3;
 current.refresh();
 }
 }

 static void clearPick() {
 pickCallback = null;
 }

 private int[] filterCounts() {
 int[] counts = new int[6];
 synchronized (items) {
 for (Object item : items) {
 boolean folder;
 String name;
 if (item instanceof FileManagerCore.LocalEntry) {
 FileManagerCore.LocalEntry entry = (FileManagerCore.LocalEntry) item;
 folder = entry.folder;
 name = entry.name;
 } else if (item instanceof QuarkUcApi.FileItem) {
 QuarkUcApi.FileItem file = (QuarkUcApi.FileItem) item;
 folder = file.folder;
 name = file.name;
 } else continue;
 int type = folder ? 1 : fileType(name);
 counts[0]++;
 counts[type]++;
 }
 }
 return counts;
 }

 /** 扩展名分类：视频/图片/文本·文档/其他。 */
 static int fileType(String name) {
 if (name == null) return 5;
 String lower = name.toLowerCase(java.util.Locale.ROOT);
 int dot = lower.lastIndexOf('.');
 String ext = dot >= 0 ? lower.substring(dot + 1) : "";
 switch (ext) {
 case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv":
 case "ts": case "m4v": case "webm": case "rmvb": case "rm": case "3gp":
 case "m3u8": case "mpg": case "mpeg":
 return 2;
 case "jpg": case "jpeg": case "png": case "webp": case "gif": case "bmp":
 return 3;
 case "txt": case "md": case "json": case "log": case "xml": case "ini":
 case "conf": case "srt": case "ass": case "js": case "pdf": case "doc":
 case "docx": case "epub":
 return 4;
 default:
 return 5;
 }
 }

 private void updateFilterChips() {
 if (filterChips == null) return;
 filterChips.removeAllViews();
 int[] counts = filterCounts();
 for (int i = 0; i < FILTER_NAMES.length; i++) {
 final int index = i;
 String label = FILTER_NAMES[i] + (i == 0 ? "" : " " + counts[i]);
 TextView chip =
 a.text(label, 11.5f, filter == i ? 0xff0b1220 : UiTokens.TEXT_PRIMARY);
 chip.setGravity(Gravity.CENTER);
 chip.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
 chip.setBackground(
 filter == i
 ? UiTokens.round(a, 999, UiTokens.ACCENT)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 chip.setFocusable(true);
 focusable(chip);
 chip.setOnClickListener(
 v -> {
 filter = index;
 updateFilterChips();
 renderFiltered();
 AppLog.i(
 "FileMgr",
 "filter="
 + FILTER_NAMES[index]
 + " items="
 + (filterCounts()[index]));
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 if (i > 0) lp.leftMargin = a.dp(6);
 filterChips.addView(chip, lp);
 }
 }

 /** 1.9.13：本地/网盘都按已加载数据本地过滤，不重新请求。 */
 private void renderFiltered() {
 if (content == null) return;
 content.removeAllViews();
 cardMap.clear();
 if (tab == 0) {
 List<FileManagerCore.LocalEntry> visible = new ArrayList<>();
 synchronized (items) {
 for (Object item : items) {
 if (!(item instanceof FileManagerCore.LocalEntry)) continue;
 FileManagerCore.LocalEntry entry = (FileManagerCore.LocalEntry) item;
 if (filter == 0 || (filter == 1 ? entry.folder : (!entry.folder && fileType(entry.name) == filter)))
 visible.add(entry);
 }
 sortEntries(visible);
 }
 if (visible.isEmpty()) {
 showEmpty(
 "该类型下没有文件",
 "换一个类型，或清除筛选",
 "清除筛选",
 () -> {
 filter = 0;
 updateFilterChips();
 renderFiltered();
 });
 return;
 }
 buildGrid(visible.size(), index -> buildLocalCard(visible.get(index), index));
 } else {
 List<QuarkUcApi.FileItem> visible = new ArrayList<>();
 synchronized (items) {
 for (Object item : items) {
 if (!(item instanceof QuarkUcApi.FileItem)) continue;
 QuarkUcApi.FileItem file = (QuarkUcApi.FileItem) item;
 if (filter == 0 || (filter == 1 ? file.folder : (!file.folder && fileType(file.name) == filter)))
 visible.add(file);
 }
 }
 if (visible.isEmpty()) {
 showEmpty(
 "该类型下没有文件",
 "换一个类型，或清除筛选",
 "清除筛选",
 () -> {
 filter = 0;
 updateFilterChips();
 renderFiltered();
 });
 return;
 }
 buildGrid(visible.size(), index -> buildNetdiskCard(visible.get(index)));
 }
 }

 private void refresh() {
 if (content == null) return;
 if (selecting) exitSelection();
 cardMap.clear();
 content.removeAllViews();
 if (tab == 0) refreshLocal();
 else refreshNetdisk();
 updateBreadcrumb();
 updateSelectionBar();
 updateDownloadStrip();
 }

 private void updateBreadcrumb() {
 if (breadcrumbRow == null) return;
 breadcrumbRow.removeAllViews();
 String path;
 if (tab == 0) {
 path = localPathLabel();
 } else path = "网盘 / " + providerName() + " / " + netdiskPath;
 pathLabel.setText(path);
 breadcrumbRow.setPadding(a.dp(a.isTvDevice() ? 24 : 16), 0, a.dp(24), a.dp(4));
 // 1.9.105：聚合根下的来源目录也能回上一层（dirStack 优先，其次回聚合根）
 boolean canUp = tab == 0 && (!dirStack.isEmpty() || !aggregateRoot());
 if (canUp) {
 TextView up = a.text("‹ 上级", 12, UiTokens.ACCENT);
 up.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
 up.setFocusable(true);
 focusable(up);
 up.setOnClickListener(v -> goUp());
 breadcrumbRow.addView(up);
 }
 }

 private void refreshLocal() {
 items.clear();
 if (!aggregateRoot() && localDir.exists() && !localDir.canRead()) {
 showEmpty("读取目录失败", "请检查存储权限后重试", "重试", () -> refresh());
 return;
 }
 List<FileManagerCore.LocalEntry> entries = new ArrayList<>();
 if (aggregateRoot()) {
 // 1.9.105：根目录聚合 /sdcard 下的 Download、Download/DSH-Go/鲸落、Movies、DCIM
 // 只列存在目录里的条目，按绝对路径去重（鲸落在 Download 内，避免重复出现）
 java.util.Set<String> seen = new java.util.HashSet<>();
 for (java.io.File dir : localSources(a)) {
 if (dir == null || !dir.isDirectory()) continue;
 List<FileManagerCore.LocalEntry> part = FileManagerCore.scan(dir);
 for (FileManagerCore.LocalEntry entry : part) {
 if (seen.add(entry.file.getAbsolutePath())) entries.add(entry);
 }
 AppLog.i(
 "FileManager",
 "scan source=" + dir.getAbsolutePath() + " count=" + part.size());
 }
 } else {
 entries.addAll(FileManagerCore.scan(localDir));
 }
 int videoCount = 0;
 for (FileManagerCore.LocalEntry e : entries) if (e.video) videoCount++;
 AppLog.i(
 "FileManager",
 "scan root="
 + (aggregateRoot() ? localRootDir.getAbsolutePath() : localDir.getAbsolutePath())
 + " count="
 + entries.size()
 + " video="
 + videoCount);
 items.addAll(entries);
 sortEntries(entries);
 updateFilterChips();
 AppLog.i(
 "FileMgr",
 "page=FileManager tab=local items="
 + entries.size()
 + " dir="
 + (aggregateRoot() ? "local-root" : localDir.getName()));
 if (entries.isEmpty()) {
 showEmpty(
 "还没有文件",
 "Download / 鲸落 / Movies / DCIM 里都没有文件，可从管理后台上传或网盘下载",
 "刷新本地",
 () -> refresh());
 return;
 }
 renderFiltered();
 updateFilterChips();
 }

 private void sortEntries(List<FileManagerCore.LocalEntry> entries) {
 java.util.Collections.sort(
 entries,
 (x, y) -> {
 if (x.folder != y.folder) return x.folder ? -1 : 1;
 if (sort == 1) return Long.compare(y.modified, x.modified);
 if (sort == 2) return Long.compare(y.size, x.size);
 return x.name.compareToIgnoreCase(y.name);
 });
 }

 private void showLocalPanDisabled() {
 LinearLayout box = a.vertical();
 box.setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(16));
 box.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
 TextView title = a.text("本地网盘直连默认关闭", 15, MainActivity.WHITE);
 title.setTypeface(null, android.graphics.Typeface.BOLD);
 box.addView(title);
 TextView desc =
 a.text(
 "本地网盘直连（浏览器 Cookie 调夸克 API）可能触发风控/限流，默认关闭；"
 + "日常请用源插件观看。开启后仍需遵守全局冷却。",
 11.5f,
 UiTokens.TEXT_TERTIARY);
 desc.setPadding(0, a.dp(6), 0, a.dp(10));
 box.addView(desc);
 TextView enable = a.text("启用本地网盘直连", 12.5f, 0xff0b1220);
 enable.setGravity(Gravity.CENTER);
 enable.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
 enable.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
 enable.setFocusable(true);
 focusable(enable);
 enable.setOnClickListener(
 v -> {
 a.prefs.edit().putBoolean("local_pan_enabled", true).apply();
 AppLog.i("LocalPan", "enabled=true");
 refresh();
 });
 box.addView(enable, new LinearLayout.LayoutParams(-1, a.dp(44)));
 TextView panel = a.text("打开源面板登录", 12, 0xffffb3c8);
 panel.setGravity(Gravity.CENTER);
 panel.setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9));
 panel.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x33ffffff, 999));
 panel.setFocusable(true);
 focusable(panel);
 panel.setOnClickListener(
 v -> new SettingsScreen(a).sourceAccountAction("LoginShow", null));
 LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(-1, a.dp(44));
 panelLp.topMargin = a.dp(8);
 box.addView(panel, panelLp);
 content.addView(box, new LinearLayout.LayoutParams(-1, -2));
 }

 private void showPanCooldown(long remaining) {
 final LinearLayout box = a.vertical();
 box.setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(16));
 box.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
 TextView title = a.text("夸克接口冷却中", 15, 0xffffb454);
 title.setTypeface(null, android.graphics.Typeface.BOLD);
 box.addView(title);
 final TextView countdown =
 a.text("冷却中：剩余 " + formatRemaining(remaining), 13, UiTokens.TEXT_PRIMARY);
 countdown.setPadding(0, a.dp(6), 0, a.dp(10));
 box.addView(countdown);
 TextView desc =
 a.text(
 "连续失败/风控后暂停请求 10 分钟，期间不会向夸克发送任何 API 请求。",
 11.5f,
 UiTokens.TEXT_TERTIARY);
 desc.setPadding(0, 0, 0, a.dp(10));
 box.addView(desc);
 TextView retry = a.text("刷新", 12.5f, 0xff0b1220);
 retry.setGravity(Gravity.CENTER);
 retry.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
 retry.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
 retry.setFocusable(true);
 focusable(retry);
 retry.setOnClickListener(v -> refresh());
 box.addView(retry, new LinearLayout.LayoutParams(-1, a.dp(44)));
 content.addView(box, new LinearLayout.LayoutParams(-1, -2));
 // 每秒倒计时，结束后自动刷新
 final Runnable[] tick = new Runnable[1];
 tick[0] =
 () -> {
 if (a.body == null
 || !"file-manager".equals(a.body.getTag())
 || tab != 1) return;
 long left = QuarkUcApi.blockRemaining(a);
 if (left <= 0) {
 refresh();
 return;
 }
 countdown.setText("冷却中：剩余 " + formatRemaining(left));
 a.ui.postDelayed(tick[0], 1000);
 };
 a.ui.postDelayed(tick[0], 1000);
 }

 private static String formatRemaining(long millis) {
 long seconds = Math.max(0, millis / 1000);
 return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
 }

 private void refreshNetdisk() {
 if (!MainActivity.cloudEnabled(a)) {
 tab = 0;
 refreshLocal();
 return;
 }
 AppLog.i(
 "FileMgr",
 "page=FileManager tab=netdisk provider="
 + providerKey
 + " fid="
 + netdiskFid
 + " credential="
 + (netdiskCookie.isEmpty() ? "none" : netdiskFromSource ? "source" : "app"));
 // 1.9.20：本地网盘直连默认关闭 + 全局风控冷却
 if (!a.prefs.getBoolean("local_pan_enabled", false)) {
 showLocalPanDisabled();
 return;
 }
 AppLog.i("LocalPan", "enabled=true");
 long blocked = QuarkUcApi.blockRemaining(a);
 if (blocked > 0) {
 AppLog.i("LocalPan", "blocked remaining=" + blocked);
 showPanCooldown(blocked);
 return;
 }
 // 1.9.0：进入网盘分段时先按凭据优先级初始化 cookie（否则永远提示未登录）
 if (netdiskCookie.isEmpty()) {
 String[] picked = pickCookie(providerKey, null);
 if (picked != null && picked.length >= 2 && picked[0] != null && !picked[0].isEmpty()) {
 netdiskCookie = picked[0];
 netdiskFromSource = Boolean.parseBoolean(picked[1]);
 }
 }
 if (!NetdiskQrLogin.QUARK.equals(providerKey) && !NetdiskQrLogin.UC.equals(providerKey)) {
 showEmpty(
 "暂不支持浏览" + providerName(),
 providerName() + "当前仅支持登录/退出，夸克与 UC 可浏览、播放和下载",
 "去登录",
 () -> loginProvider(providerKey));
 return;
 }
 if (netdiskCookie.isEmpty()) {
 showEmpty(
 "未登录" + providerName(),
 "登录后浏览、播放与下载",
 "去登录",
 () -> loginProvider(providerKey));
 return;
 }
 // 1.9.0：chips 已在工具栏区常驻，不随内容区清空
 final TextView loading = a.text("正在读取" + providerName() + "文件…", 13, UiTokens.TEXT_TERTIARY);
 loading.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
 content.addView(loading);
 a.worker.execute(
 () -> {
 List<QuarkUcApi.FileItem> files = new ArrayList<>();
 String error = "";
 try {
 files = QuarkUcApi.list(
 QuarkUcApi.of(providerKey), netdiskCookie, netdiskFid);
 } catch (Throwable failure) {
 error = failure.getClass().getSimpleName();
 }
 final List<QuarkUcApi.FileItem> result = files;
 final String failure = error;
 a.ui.post(
 () -> {
 if (content == null || tab != 1) return;
 content.removeAllViews();
 if (!failure.isEmpty()) {
 showEmpty("读取网盘失败", failure, "重试", () -> refresh());
 return;
 }
 if (result.isEmpty()) {
 showEmpty("这个文件夹是空的", "换一个目录，或稍后刷新", "刷新", () -> refresh());
 return;
 }
 items.clear();
 items.addAll(result);
 AppLog.i(
 "FileMgr",
 "page=FileManager tab=netdisk items=" + result.size());
 renderFiltered();
 updateFilterChips();
 });
 });
 }

 private LinearLayout providerBar;

 private void buildProviderChips() {
 if (providerBar != null && providerBar.getParent() instanceof android.view.ViewGroup)
 ((android.view.ViewGroup) providerBar.getParent()).removeView(providerBar);
 final LinearLayout row = a.row();
 providerBar = row;
 row.setPadding(0, 0, 0, a.dp(6));
 String[] names = {"夸克", "UC", "百度", "阿里"};
 String[] keys = {NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI};
 for (int i = 0; i < keys.length; i++) {
 final String key = keys[i];
 String[] picked = pickCookie(key, null);
 boolean logged = !picked[0].isEmpty();
 boolean source = Boolean.parseBoolean(picked[1]);
 TextView chip =
 a.text(
 names[i] + (logged ? (source ? "·源" : "·App") : "·未登录"),
 11.5f,
 key.equals(providerKey) ? 0xff0b1220 : UiTokens.TEXT_PRIMARY);
 chip.setGravity(Gravity.CENTER);
 chip.setPadding(a.dp(9), a.dp(7), a.dp(9), a.dp(7));
 chip.setBackground(
 key.equals(providerKey)
 ? UiTokens.round(a, 999, UiTokens.ACCENT)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 chip.setFocusable(true);
 focusable(chip);
 chip.setOnClickListener(
 v -> {
 if (key.equals(providerKey)) return;
 String[] chosen = pickCookie(key, null);
 if (chosen[0].isEmpty()) {
 a.toast("未检测到" + names[indexOfKey(key)] + "登录凭据，请先登录");
 return;
 }
 providerKey = key;
 netdiskFid = "0";
 netdiskPath = "根目录";
 filter = 0;
 netdiskCookie = chosen[0];
 netdiskFromSource = Boolean.parseBoolean(chosen[1]);
 exitSelection();
 buildProviderChips();
 refresh();
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
 if (i > 0) lp.leftMargin = a.dp(6);
 row.addView(chip, lp);
 }
 // 1.9.0：四盘 chips 固定在工具栏区，不随内容区 clear
 if (content != null && content.getParent() == a.body) {
 int index = a.body.indexOfChild(content);
 a.body.addView(row, index >= 0 ? index : a.body.getChildCount());
 } else {
 a.body.addView(row);
 }
 }

 /** 复用云盘专区凭据策略：App 已验证 > 源插件 > App 未验证。 */
 private String[] pickCookie(String key, QuarkUcApi.Provider ignored) {
 QuarkUcApi.Provider provider = QuarkUcApi.of(key);
 String app = QuarkUcApi.cookie(a, provider);
 boolean verified = QuarkUcApi.verified(a, provider);
 String source = CloudZoneScreen.sourceCookie(a, provider);
 String cookie;
 boolean fromSource;
 if (!app.isEmpty() && verified) {
 cookie = app;
 fromSource = false;
 } else if (!source.isEmpty()) {
 cookie = source;
 fromSource = true;
 } else {
 cookie = app;
 fromSource = false;
 }
 AppLog.i(
 "FileMgr",
 "pick provider="
 + key
 + " credential="
 + (cookie.isEmpty() ? "none" : fromSource ? "source" : "app"));
 return new String[] {cookie, String.valueOf(fromSource)};
 }

 private int indexOfKey(String key) {
 switch (key) {
 case NetdiskQrLogin.QUARK:
 return 0;
 case NetdiskQrLogin.UC:
 return 1;
 case NetdiskQrLogin.BAIDU:
 return 2;
 default:
 return 3;
 }
 }

 private int currentProviderIndex() {
 return indexOfKey(providerKey);
 }

 private void loginProvider(String key) {
 NetdiskQrLogin.show(
 a,
 key,
 () -> {
 // 1.9.0：登录成功后用新 cookie 重载网盘分段
 String[] picked = pickCookie(key, null);
 if (!picked[0].isEmpty()) {
 netdiskCookie = picked[0];
 netdiskFromSource = Boolean.parseBoolean(picked[1]);
 providerKey = key;
 }
 if (tab == 1) buildProviderChips();
 a.ui.postDelayed(this::refresh, 200);
 });
 }

 private String providerName() {
 switch (providerKey) {
 case NetdiskQrLogin.QUARK:
 return "夸克";
 case NetdiskQrLogin.UC:
 return "UC";
 case NetdiskQrLogin.BAIDU:
 return "百度";
 default:
 return "阿里";
 }
 }

 // ---------------- 网格 ----------------

 private interface CardBuilder {
 View build(int index);
 }

 private int statusBarHeightSafe() {
 try {
 int id =
 a.getResources()
 .getIdentifier("status_bar_height", "dimen", "android");
 if (id > 0) return a.getResources().getDimensionPixelSize(id);
 } catch (Throwable ignored) {
 }
 return a.dp(24);
 }

 private int gridUsedAvail = -1;
 private boolean gridRecalcPosted;
 private int lastGridCount;
 private CardBuilder lastGridBuilder;
 private final java.util.Map<Object, MemoCard> cardMap = new java.util.HashMap<>();

 /** 1.9.6：卡片外壳 = 内容 + 右上角选中标记（覆盖层）。 */
 private final class MemoCard extends FrameLayout {
 final LinearLayout content;
 final TextView marker;
 final File file;
 final String fid;
 boolean selected;

 MemoCard(File file, String fid) {
 super(a);
 this.file = file;
 this.fid = fid;
 content = a.vertical();
 content.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
 addView(content, new FrameLayout.LayoutParams(-1, -1));
 marker = a.text("✓", 14, 0xff0b1220);
 marker.setGravity(Gravity.CENTER);
 marker.setVisibility(View.INVISIBLE);
 FrameLayout.LayoutParams mlp =
 new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.TOP | Gravity.END);
 mlp.topMargin = a.dp(6);
 mlp.rightMargin = a.dp(6);
 addView(marker, mlp);
 setFocusable(true);
 focusable(this);
 }

 void apply(boolean selecting, boolean selected) {
 this.selected = selected;
 marker.setVisibility(selecting ? View.VISIBLE : View.INVISIBLE);
 if (!selecting) {
 content.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
 setAlpha(1f);
 return;
 }
 marker.setText(selected ? "✓" : "");
 android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
 circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
 circle.setColor(selected ? UiTokens.ACCENT : 0x44000000);
 circle.setStroke(Math.max(1, a.dp(1.5f)), selected ? 0xffffffff : 0x66ffffff);
 marker.setBackground(circle);
 android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
 border.setColor(0xcc16283f);
 border.setCornerRadius(a.dp(16));
 border.setStroke(Math.max(1, a.dp(1.5f)), selected ? UiTokens.ACCENT : 0x33ffffff);
 content.setBackground(border);
 setAlpha(selected ? 1f : 0.92f);
 }
 }

 private void applySelectionVisuals() {
 for (MemoCard card : cardMap.values()) {
 boolean selected = card.file != null ? selFiles.contains(card.file) : selFids.contains(card.fid);
 card.apply(selecting, selected);
 }
 }

 private void registerCard(MemoCard card) {
 cardMap.put(card.file != null ? (Object) card.file : card.fid, card);
 card.apply(selecting, false);
 }

 private void buildGrid(int count, CardBuilder builder) {
 lastGridCount = count;
 lastGridBuilder = builder;
 int avail = content != null ? content.getWidth() : 0;
 if (avail <= 0 && a.root != null) avail = a.root.getWidth() - a.dp(32);
 if (avail <= 0) {
 // 首帧尚未布局：用窗口宽兜底，post 后再按实测宽重建
 avail = a.windowWidth();
 }
 gridUsedAvail = avail;
 float density = a.getResources().getDisplayMetrics().density;
 int widthDp = Math.round(avail / density);
 boolean tv = a.isTvDevice();
 int cols = tv ? 5 : widthDp < 420 ? 2 : widthDp < 600 ? 3 : 4;
 int margin = widthDp < 600 ? a.dp(16) : a.dp(24);
 int gap = tv ? a.dp(18) : widthDp < 420 ? a.dp(12) : widthDp < 600 ? a.dp(12) : a.dp(16);
 int inner = avail - margin * 2;
 if (!tv && cols < 2) cols = 2;
 while (cols > 2 && (inner - gap * (cols - 1)) / cols < a.dp(110)) cols--;
 if (!tv && cols < 2) cols = 2;
 int cardW = Math.max(a.dp(100), (inner - gap * (cols - 1)) / cols);
 int thumbH = Math.round(cardW * 0.625f);
 int cardH = thumbH + a.dp(90);
 int rows = (count + cols - 1) / cols;
 AppLog.i(
 "FileMgr",
 "grid avail="
 + avail
 + " cols="
 + cols
 + " rows="
 + rows
 + " cardH="
 + cardH
 + " margin="
 + margin);
 LinearLayout wrapper = a.vertical();
 wrapper.setPadding(margin, 0, margin, a.dp(20));
 for (int i = 0; i < count; i += cols) {
 LinearLayout row = a.row();
 for (int c = 0; c < cols; c++) {
 View cell;
 if (i + c < count) {
 cell = builder.build(i + c);
 cell.setTag("filemgr-card:" + (i + c));
 } else {
 cell = new View(a); // 行内占位，保证每行列宽一致
 }
 LinearLayout.LayoutParams lp =
 new LinearLayout.LayoutParams(0, cardH, 1f);
 if (c > 0) lp.leftMargin = gap;
 row.addView(cell, lp);
 }
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 if (wrapper.getChildCount() > 0) lp.topMargin = gap;
 wrapper.addView(row, lp);
 }
 content.addView(wrapper);
 // 布局后按真实 content 宽再算一次（仅首帧，避免死循环）
 if (!gridRecalcPosted && content != null) {
 gridRecalcPosted = true;
 content.post(
 () -> {
 if (content == null || lastGridBuilder == null) return;
 int measured = content.getWidth();
 if (measured > 0 && Math.abs(measured - gridUsedAvail) > a.dp(8)) {
 AppLog.i("FileMgr", "grid relayout measured=" + measured);
 content.removeAllViews();
 buildGrid(lastGridCount, lastGridBuilder);
 }
 gridRecalcPosted = false;
 });
 }
 }

 private int lastCardW;

 /** 1.9.2：按实测 content 宽计算列数与卡宽（与 buildGrid 一致）。 */
 private int cardWidth() {
 int avail = content != null ? content.getWidth() : 0;
 if (avail <= 0 && a.root != null) avail = a.root.getWidth() - a.dp(32);
 if (avail <= 0) avail = a.windowWidth();
 float density = a.getResources().getDisplayMetrics().density;
 int widthDp = Math.round(avail / density);
 boolean tv = a.isTvDevice();
 int cols = tv ? 5 : widthDp < 420 ? 2 : widthDp < 600 ? 3 : 4;
 int margin = widthDp < 600 ? a.dp(16) : a.dp(24);
 int gap = tv ? a.dp(18) : widthDp < 600 ? a.dp(12) : a.dp(16);
 int inner = avail - margin * 2;
 if (!tv && cols < 2) cols = 2;
 while (cols > 2 && (inner - gap * (cols - 1)) / cols < a.dp(110)) cols--;
 if (!tv && cols < 2) cols = 2;
 return Math.max(a.dp(100), (inner - gap * (cols - 1)) / cols);
 }

 private LinearLayout cardShell(int thumbH) {
 LinearLayout card = a.vertical();
 card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
 card.setFocusable(true);
 card.setClipToOutline(false);
 focusable(card);
 return card;
 }

 /** 1.9.6：本地图片/视频首帧缩略图（96px 采样，LruCache 缓存）。 */
 private static final class ThumbCache {
 static final android.util.LruCache<String, android.graphics.Bitmap> CACHE =
 new android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
 @Override
 protected int sizeOf(String key, android.graphics.Bitmap value) {
 return value.getByteCount();
 }
 };

 static android.graphics.Bitmap image(File file) {
 String key = "img:" + file.getAbsolutePath() + ":" + file.lastModified();
 android.graphics.Bitmap cached = CACHE.get(key);
 if (cached != null) return cached;
 try {
 android.graphics.BitmapFactory.Options bounds =
 new android.graphics.BitmapFactory.Options();
 bounds.inJustDecodeBounds = true;
 android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
 int sample = 1;
 while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192)
 sample *= 2;
 android.graphics.BitmapFactory.Options decode =
 new android.graphics.BitmapFactory.Options();
 decode.inSampleSize = sample;
 android.graphics.Bitmap bitmap =
 android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
 if (bitmap != null) CACHE.put(key, bitmap);
 return bitmap;
 } catch (Throwable ignored) {
 return null;
 }
 }

 static android.graphics.Bitmap video(File file) {
 String key = "vid:" + file.getAbsolutePath() + ":" + file.lastModified();
 android.graphics.Bitmap cached = CACHE.get(key);
 if (cached != null) return cached;
 try {
 android.media.MediaMetadataRetriever retriever =
 new android.media.MediaMetadataRetriever();
 retriever.setDataSource(file.getAbsolutePath());
 android.graphics.Bitmap frame =
 retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
 retriever.release();
 if (frame != null) {
 android.graphics.Bitmap scaled =
 android.graphics.Bitmap.createScaledBitmap(frame, 192, 120, true);
 CACHE.put(key, scaled);
 return scaled;
 }
 } catch (Throwable ignored) {
 }
 return null;
 }
 }

 private static boolean usableBitmap(android.graphics.Bitmap bitmap) {
 if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0)
 return false;
 try {
 int[][] points = {
 {bitmap.getWidth() / 2, bitmap.getHeight() / 2},
 {bitmap.getWidth() / 4, bitmap.getHeight() / 4},
 {bitmap.getWidth() * 3 / 4, bitmap.getHeight() * 3 / 4}
 };
 for (int[] point : points) {
 int pixel = bitmap.getPixel(point[0], point[1]);
 if (android.graphics.Color.alpha(pixel) > 8) return true;
 }
 } catch (Throwable ignored) {
 }
 return false;
 }

 private void addThumb(
 LinearLayout card, int thumbH, final FileManagerCore.LocalEntry entry, String name) {
 FrameLayout thumb = new FrameLayout(a);
 thumb.setBackground(
 MobileDetailScreen.glassBg(a, 0xdd0d1a2b, 0x33000000, 16));
 final TextView glyph = a.text(entry == null ? fileSymbol(name) : entry.folder ? "" : entry.video ? "视频" : fileSymbol(entry.name), 34, UiTokens.ACCENT_SOFT);
 glyph.setGravity(Gravity.CENTER);
 thumb.addView(glyph, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
 if (entry != null && !entry.folder && (entry.video || TextViewerScreen.isImageName(entry.name))) {
 final ImageView image = new ImageView(a);
 image.setScaleType(ImageView.ScaleType.CENTER_CROP);
 thumb.addView(image, new FrameLayout.LayoutParams(-1, -1));
 if (entry.video) {
 a.worker.execute(
 () -> {
 final android.graphics.Bitmap bitmap = ThumbCache.video(entry.file);
 if (usableBitmap(bitmap))
 a.ui.post(
 () -> {
 if (image.getParent() != null) {
 image.setImageBitmap(bitmap);
 glyph.setVisibility(View.GONE);
 }
 });
 });
 } else {
 android.graphics.Bitmap bitmap = ThumbCache.image(entry.file);
 if (usableBitmap(bitmap)) {
 image.setImageBitmap(bitmap);
 glyph.setVisibility(View.GONE);
 }
 }
 }
 card.addView(thumb, new LinearLayout.LayoutParams(-1, thumbH));
 }

 private void addTitleAndMeta(LinearLayout card, String title, String meta) {
 LinearLayout info = a.vertical();
 info.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
 TextView name = a.text(title, 12.5f, UiTokens.TEXT_PRIMARY);
 name.setMaxLines(2);
 name.setMinHeight(a.dp(30));
 name.setEllipsize(android.text.TextUtils.TruncateAt.END);
 name.setLineSpacing(a.dp(1), 1f);
 info.addView(name);
 TextView sub = a.text(meta, 10.5f, UiTokens.TEXT_TERTIARY);
 sub.setMaxLines(1);
 sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
 info.addView(sub);
 // 1.9.2：固定信息区高度（卡高 = 缩略区 + 90dp），不被内容撑开
 card.addView(info, new LinearLayout.LayoutParams(-1, a.dp(90)));
 }

 private View buildLocalCard(final FileManagerCore.LocalEntry entry, final int index) {
 MemoCard holder = new MemoCard(entry.file, null);
 LinearLayout card = holder.content;
 registerCard(holder);
 int cardW = cardWidth();
 int thumbH = Math.round(cardW * 0.625f);
 addThumb(card, thumbH, entry, "");
 String meta =
 entry.folder
 ? (countChildren(entry.file) + " 项")
 : QuarkUcApi.formatSize(entry.size);
 meta += " · " + FileManagerCore.formatDate(entry.modified);
 if (aggregateRoot()) {
 // 1.9.105：聚合根下显示来源目录名（Download / 鲸落 / Movies / DCIM）
 String source = localSourceName(entry.file);
 if (!source.isEmpty()) meta += " · 来自 " + source;
 }
 addTitleAndMeta(card, entry.name, meta);
 final MemoCard finalHolder = holder;
 holder.setOnClickListener(
 v -> {
 String nm = entry.name == null ? "" : entry.name;
 String ext =
 nm.contains(".") ? nm.substring(nm.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
 AppLog.i(
 "FileManager",
 "click name="
 + nm
 + " ext="
 + ext
 + " folder="
 + entry.folder
 + " video="
 + entry.video
 + " pick="
 + (pickCallback != null)
 + " selecting="
 + selecting);
 if (pickCallback != null && !entry.folder) {
 boolean ok = pickVideoMode ? entry.video : TextViewerScreen.isImageName(entry.name);
 if (!ok) {
 a.toast(pickVideoMode ? "请选择视频文件" : "请选择图片文件");
 return;
 }
 PickCallback callback = pickCallback;
 pickCallback = null;
 callback.picked(entry.file);
 a.popBack();
 return;
 }
 if (selecting) {
 toggleSelection(entry.file, null);
 applySelectionVisuals();
 } else if (entry.folder) {
 pushDir();
 localDir = entry.file;
 filter = 0;
 exitSelection();
 refresh();
 } else if (entry.video) FileManagerCore.playLocal(a, entry.file);
 else if ("apk".equals(ext)) {
 // 1.9.169：本地 APK 走多路安装回退（大小写/空格/中文名均按后缀判断）
 AppLog.i("FileManager", "apk install path=" + entry.file.getAbsolutePath());
 UpdateScreen.installApkWithFallback(a, entry.file, "file-manager");
 }
 else if (TextViewerScreen.isImageName(entry.name))
 ImageViewerScreen.show(a, entry.file);
 else if (TextViewerScreen.isTextName(entry.name))
 TextViewerScreen.show(a, entry.file);
 else a.toast("当前仅支持视频/图片/文本查看与下载");
 });
 holder.setOnLongClickListener(
 v -> {
 // 1.9.98：长按本地文件给出「设为壁纸/播放/删除」菜单（遥控 OK 长按也生效）
 if (!selecting && !entry.folder) {
 showLocalFileMenu(entry);
 return true;
 }
 if (!selecting) {
 selecting = true;
 selFiles.clear();
 selFids.clear();
 }
 toggleSelection(entry.file, null);
 applySelectionVisuals();
 updateSelectionBar();
 return true;
 });
 holder.setTag("filemgr-card:" + index);
 return finalHolder;
 }

 /** 1.9.98：本地文件长按菜单：视频→设为动态壁纸/播放；图片→设为壁纸/查看；含删除/重命名。 */
 private void showLocalFileMenu(final FileManagerCore.LocalEntry entry) {
 final boolean video = entry.video;
 final boolean image = TextViewerScreen.isImageName(entry.name);
 final String menuName = entry.name == null ? "" : entry.name;
 final String menuExt =
 menuName.contains(".")
 ? menuName.substring(menuName.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT)
 : "";
 final boolean apk = "apk".equals(menuExt);
 AppLog.i(
 "FileManager",
 "longpress name=" + menuName + " ext=" + menuExt + " apk=" + apk);
 java.util.List<String> labels = new java.util.ArrayList<>();
 if (video) labels.add("设为动态壁纸");
 if (image) labels.add("设为壁纸");
 if (apk) labels.add("安装");
 labels.add(video ? "播放" : "查看");
 labels.add("重命名");
 labels.add("删除");
 labels.add("取消");
 String[] items = labels.toArray(new String[0]);
 new AppDialog.Builder(a)
 .setTitle(entry.name)
 .setItems(
 items,
 (dialog, which) -> {
 String choice = items[which];
 if ("设为动态壁纸".equals(choice) || "设为壁纸".equals(choice)) {
 setWallpaperFromFile(a, entry.file, video);
 } else if ("播放".equals(choice)) {
 FileManagerCore.playLocal(a, entry.file);
 } else if ("查看".equals(choice)) {
 if (image) ImageViewerScreen.show(a, entry.file);
 else TextViewerScreen.show(a, entry.file);
 } else if ("安装".equals(choice)) {
 AppLog.i("FileManager", "apk install path=" + entry.file.getAbsolutePath());
 UpdateScreen.installApkWithFallback(a, entry.file, "file-manager-menu");
 } else if ("重命名".equals(choice)) {
 renameSingle(entry.file);
 } else if ("删除".equals(choice)) {
 new AppDialog.Builder(a)
 .setTitle("删除 " + entry.name + "？")
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "删除",
 (d, w) -> {
 a.toast(FileManagerCore.delete(entry.file) ? "已删除" : "删除失败（无权限）");
 refresh();
 })
 .show();
 }
 })
 .show();
 }

 private void renameSingle(final java.io.File file) {
 final android.widget.EditText input = new android.widget.EditText(a);
 input.setText(file.getName());
 input.setSelection(input.getText().length());
 new AppDialog.Builder(a)
 .setTitle("重命名")
 .setView(input)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "确定",
 (d, w) -> {
 boolean ok = FileManagerCore.rename(file, input.getText().toString());
 a.toast(ok ? "已重命名" : "重命名失败（可能重名或无权限）");
 refresh();
 })
 .show();
 }

 /** 1.9.98：文件直接设为壁纸（复制到 files/wallpaper.jpg|mp4 → 启用 → 立即应用）。 */
 static void setWallpaperFromFile(final MainActivity a, final java.io.File file, final boolean video) {
 a.worker.execute(
 () -> {
 try (java.io.InputStream input = new java.io.FileInputStream(file)) {
 if (video) {
 // 1.9.105：真实编码探测（MediaExtractor 读轨道 mime + MediaCodecList 找解码器）
 String problem = videoCodecProblem(file);
 AppLog.i(
 "Wallpaper",
 "set-from-file type=video path="
 + file.getName()
 + " supported="
 + problem.isEmpty()
 + " reason="
 + problem);
 if (!problem.isEmpty()) {
 a.ui.post(() -> a.notice("该视频不能设为动态壁纸：" + problem));
 return;
 }
 SettingsScreen.saveVideoWallpaper(a, input);
 a.prefs.edit()
 .putBoolean(Prefs.WALLPAPER_VIDEO, true)
 .putBoolean(Prefs.WALLPAPER_ENABLED, true)
 .apply();
 a.ui.post(
 () -> {
 a.toast("已设为动态壁纸");
 if (a.root != null) a.applyWallpaper(a.root);
 a.refresh();
 });
 } else {
 AppLog.i("Wallpaper", "set-from-file type=image path=" + file.getName() + " supported=true");
 SettingsScreen.saveWallpaper(a, input);
 a.ui.post(
 () -> {
 a.toast("已设为壁纸");
 if (a.root != null) a.applyWallpaper(a.root);
 a.refresh();
 });
 }
 } catch (Exception error) {
 AppLog.w("Wallpaper", "set-from-file failed", error);
 a.ui.post(() -> a.notice("设置壁纸失败：" + a.message(error)));
 }
 });
 }

 /**
 * 1.9.105 真实编码探测：MediaExtractor 读出视频轨 mime，再用 MediaCodecList 确认系统里
 * 存在该 mime 的解码器（HEVC / Dolby Vision 等无解码器则视为不可用）。
 *
 * @return 空串 = 可用；否则为不可用原因（给前端/弹窗显示）
 */
 static String videoCodecProblem(java.io.File file) {
 if (file == null || !file.isFile() || file.length() <= 0) return "视频文件不存在或为空";
 String mime = "";
 android.media.MediaExtractor extractor = new android.media.MediaExtractor();
 try {
 extractor.setDataSource(file.getAbsolutePath());
 for (int i = 0; i < extractor.getTrackCount(); i++) {
 android.media.MediaFormat format = extractor.getTrackFormat(i);
 String track = format.getString(android.media.MediaFormat.KEY_MIME);
 if (track != null && track.startsWith("video/")) {
 mime = track;
 break;
 }
 }
 } catch (Throwable error) {
 return "无法解析视频（" + error.getClass().getSimpleName() + "）";
 } finally {
 try {
 extractor.release();
 } catch (Throwable ignored) {
 }
 }
 if (mime.isEmpty()) return "未找到视频轨（格式不支持）";
 boolean hevcOrDolby =
 mime.contains("hevc")
 || mime.contains("h265")
 || mime.contains("dolby")
 || mime.contains("dvhe")
 || mime.contains("dvh1")
 || mime.contains("dvav")
 || mime.contains("dva1");
 boolean decoder = hasVideoDecoder(mime);
 AppLog.i(
 "Wallpaper",
 "codec mime=" + mime + " hevcOrDolby=" + hevcOrDolby + " decoder=" + decoder);
 if (!decoder) return "无可用解码器：" + mime;
 return "";
 }

 /** 1.9.105：壁纸/文件页通用的编码可用性判断。 */
 static boolean videoCodecSupported(java.io.File file) {
 return videoCodecProblem(file).isEmpty();
 }

 /** 用 MediaCodecList 查找 mime 对应的硬件/软件解码器。 */
 private static boolean hasVideoDecoder(String mime) {
 try {
 android.media.MediaCodecList list =
 new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
 for (android.media.MediaCodecInfo codec : list.getCodecInfos()) {
 if (codec == null || codec.isEncoder()) continue;
 String[] types = codec.getSupportedTypes();
 if (types == null) continue;
 for (String type : types) if (type != null && mime.equalsIgnoreCase(type)) return true;
 }
 } catch (Throwable ignored) {
 }
 return false;
 }

 private View buildNetdiskCard(final QuarkUcApi.FileItem item) {
 MemoCard holder = new MemoCard(null, item.fid);
 LinearLayout card = holder.content;
 registerCard(holder);
 int cardW = cardWidth();
 int thumbH = Math.round(cardW * 0.625f);
 addThumb(card, thumbH, null, item.name);
 String dateText = "网盘";
 if (item.updated > 0) {
 long ms = item.updated > 1000000000000L ? item.updated : item.updated * 1000L;
 dateText = FileManagerCore.formatDate(ms);
 }
 String meta =
 (item.folder ? "文件夹" : QuarkUcApi.formatSize(item.size))
 + " · "
 + dateText
 + " · "
 + (netdiskFromSource ? "源插件" : "App 内");
 addTitleAndMeta(card, item.name, meta);
 final MemoCard finalHolder = holder;
 holder.setOnClickListener(
 v -> {
 if (selecting) {
 toggleSelection(null, item.fid);
 applySelectionVisuals();
 } else if (item.folder) {
 netdiskFid = item.fid;
 netdiskPath = netdiskPath + " / " + item.name;
 filter = 0;
 exitSelection();
 refresh();
 } else if (item.video) {
 AppLog.i("FileMgr", "open netdisk video fid=" + item.fid);
 final QuarkUcApi.FileItem videoItem = item;
 new AppDialog.Builder(a)
 .setTitle("播放：" + item.name)
 .setItems(
 new String[] {
 "播放（转码 / 直链）",
 "用源插件缓存地址播放"
 },
 (d, which) -> {
 if (which == 0) {
 FileManagerCore.playRemote(
 a,
 QuarkUcApi.of(providerKey),
 netdiskCookie,
 videoItem,
 netdiskFromSource);
 } else {
 String[] cached =
 QuarkUcApi.cachedPluginUrl(
 a, videoItem.name);
 if (cached[0].isEmpty()) {
 a.toast("没有可用的源插件缓存地址");
 return;
 }
 if ("yes".equals(cached[1])) {
 FileManagerCore.playCachedUrl(
 a, cached[0], videoItem.name);
 } else {
 new AppDialog.Builder(a)
 .setTitle("缓存地址可能不匹配")
 .setMessage(
 "源插件缓存地址可能来自上一个视频，仍要尝试播放吗？")
 .setPositiveButton(
 "仍要播放",
 (dd, ii) ->
 FileManagerCore
 .playCachedUrl(
 a,
 cached[0],
 videoItem.name))
 .setNegativeButton("取消", null)
 .show();
 }
 }
 })
 .show();
 } else if (TextViewerScreen.isImageName(item.name)) {
 downloadForView(item, true);
 } else if (TextViewerScreen.isTextName(item.name)) {
 downloadForView(item, false);
 } else a.toast("当前仅支持视频/图片/文本查看与下载");
 });
 holder.setOnLongClickListener(
 v -> {
 if (!selecting) {
 selecting = true;
 selFiles.clear();
 selFids.clear();
 }
 toggleSelection(null, item.fid);
 applySelectionVisuals();
 updateSelectionBar();
 return true;
 });
 return finalHolder;
 }

 private String fileSymbol(String name) {
 String lower = name.toLowerCase(java.util.Locale.ROOT);
 if (lower.endsWith(".md")) return "MD";
 if (lower.endsWith(".pdf")) return "PDF";
 if (lower.endsWith(".zip") || lower.endsWith(".rar")) return "ZIP";
 if (lower.endsWith(".apk")) return "APK";
 if (lower.matches(".*\\.(png|jpg|jpeg|webp|gif)$")) return "IMG";
 return "FILE";
 }

 private int countChildren(File dir) {
 try {
 File[] files = dir.listFiles();
 return files == null ? 0 : files.length;
 } catch (Throwable ignored) {
 return 0;
 }
 }

 // ---------------- 多选 ----------------

 private void toggleSelection(File file, String fid, View ignored) {
 toggleSelection(file, fid);
 }

 private void toggleSelection(File file, String fid) {
 if (file != null) {
 if (!selFiles.remove(file)) selFiles.add(file);
 } else {
 if (!selFids.remove(fid)) selFids.add(fid);
 }
 applySelectionVisuals();
 updateSelectionBar();
 }

 /** 1.9.6：全选 / 取消全选。 */
 private void selectAllCards() {
 selFiles.clear();
 selFids.clear();
 synchronized (items) {
 for (Object item : items) {
 if (item instanceof FileManagerCore.LocalEntry)
 selFiles.add(((FileManagerCore.LocalEntry) item).file);
 else if (item instanceof QuarkUcApi.FileItem)
 selFids.add(((QuarkUcApi.FileItem) item).fid);
 }
 }
 applySelectionVisuals();
 updateSelectionBar();
 }

 private void clearSelectionOnly() {
 selFiles.clear();
 selFids.clear();
 applySelectionVisuals();
 updateSelectionBar();
 }

 private void updateSelectionBar() {
 if (selectionBar == null) {
 selectionBar = a.row();
 selectionBar.setGravity(Gravity.CENTER);
 selectionBar.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 selectionBar.setBackground(MobileDetailScreen.glassBg(a, 0xee0d1a2b, 0x33ffffff, 16));
 selectionCount = a.text("", 12, UiTokens.TEXT_PRIMARY);
 selectionCount.setPadding(0, 0, a.dp(8), 0);
 selectionBar.addView(selectionCount, new LinearLayout.LayoutParams(0, -2, 1f));
 a.body.addView(selectionBar);
 }
 if (!selecting) {
 selectionBar.setVisibility(View.GONE);
 return;
 }
 selectionBar.setVisibility(View.VISIBLE);
 selectionCount.setText(
 "已选 "
 + (selFiles.size() + selFids.size())
 + " 项"
 + (filter != 0 ? "（筛选外的选择已保留）" : ""));
 while (selectionBar.getChildCount() > 1) selectionBar.removeViewAt(1);
 selectionBar.addView(selAction("全选", () -> selectAllCards()));
 selectionBar.addView(selAction("取消全选", () -> clearSelectionOnly()));
 if (tab == 0) {
 selectionBar.addView(selAction("删除", () -> deleteSelected()));
 selectionBar.addView(selAction("重命名", () -> renameSelected()));
 selectionBar.addView(selAction("移动", () -> moveSelected()));
 } else {
 selectionBar.addView(selAction("下载", () -> downloadSelected()));
 }
 selectionBar.addView(selAction("退出", () -> exitSelection()));
 }

 private TextView selAction(String label, Runnable action) {
 TextView view = a.text(label, 12, UiTokens.TEXT_PRIMARY);
 view.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
 view.setBackground(MobileDetailScreen.glassBg(a, 0x24ffffff, 0x33ffffff, 999));
 view.setFocusable(true);
 focusable(view);
 view.setOnClickListener(v -> action.run());
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.leftMargin = a.dp(6);
 if (view.getParent() == null) {
 // 首次添加时父容器由调用方设置
 }
 view.setLayoutParams(lp);
 return view;
 }

 private void exitSelection() {
 selecting = false;
 selFiles.clear();
 selFids.clear();
 if (selectionBar != null) selectionBar.setVisibility(View.GONE);
 applySelectionVisuals();
 }

 private void deleteSelected() {
 new AppDialog.Builder(a)
 .setTitle("删除所选 " + selFiles.size() + " 项？")
 .setMessage("不可恢复。")
 .setPositiveButton(
 "删除",
 (d, i) -> {
 int deleted = 0;
 for (File file : selFiles) if (FileManagerCore.delete(file)) deleted++;
 a.toast("已删除 " + deleted + " 项");
 exitSelection();
 refresh();
 })
 .setNegativeButton("取消", null)
 .show();
 }

 private void renameSelected() {
 if (selFiles.size() != 1) {
 a.toast("重命名仅支持单选");
 return;
 }
 final File file = selFiles.iterator().next();
 EditText input = new EditText(a);
 input.setSingleLine(true);
 prepareInput(input);
 input.setText(file.getName());
 input.setTextColor(MainActivity.WHITE);
 input.setHintTextColor(a.themeColor(MainActivity.MUTED));
 new AppDialog.Builder(a)
 .setTitle("重命名")
 .setView(input)
 .setPositiveButton(
 "确定",
 (d, i) -> {
 boolean ok = FileManagerCore.rename(file, input.getText().toString());
 a.toast(ok ? "已重命名" : "重命名失败（可能重名或无权限）");
 if (ok) {
 exitSelection();
 refresh();
 }
 })
 .setNegativeButton("取消", null)
 .show();
 }

 private void moveSelected() {
 final EditText input = new EditText(a);
 input.setSingleLine(true);
 prepareInput(input);
 input.setText(
 aggregateRoot()
 ? FileManagerCore.downloadDir(a).getAbsolutePath()
 : localDir.getAbsolutePath());
 input.setTextColor(MainActivity.WHITE);
 input.setHintTextColor(a.themeColor(MainActivity.MUTED));
 new AppDialog.Builder(a)
 .setTitle("移动到目录（绝对路径）")
 .setView(input)
 .setPositiveButton(
 "移动",
 (d, i) -> {
 File target = new File(input.getText().toString().trim());
 if (!target.isDirectory()) {
 a.toast("目标目录不存在");
 return;
 }
 int moved = 0;
 for (File file : selFiles) if (FileManagerCore.move(file, target)) moved++;
 a.toast("已移动 " + moved + " 项");
 exitSelection();
 refresh();
 })
 .setNegativeButton("取消", null)
 .show();
 }

 /** 1.9.0：TV 上不弹系统软键盘（用遥控键盘/现有输入）。 */
 private void prepareInput(EditText input) {
 if (a.isTvDevice()) {
 try {
 input.setShowSoftInputOnFocus(false);
 } catch (Throwable ignored) {
 }
 }
 }

 private void createFolderDialog() {
 EditText input = new EditText(a);
 input.setSingleLine(true);
 prepareInput(input);
 input.setTextColor(MainActivity.WHITE);
 input.setHintTextColor(a.themeColor(MainActivity.MUTED));
 input.setHint("文件夹名");
 new AppDialog.Builder(a)
 .setTitle("新建文件夹")
 .setView(input)
 .setPositiveButton(
 "创建",
 (d, i) -> {
 String name = FileManagerCore.safeName(input.getText().toString().trim());
 // 1.9.105：聚合根（/sdcard）不可直接写，新建落到 Download/鲸落
 File target =
 new File(aggregateRoot() ? FileManagerCore.downloadDir(a) : localDir, name);
 a.toast(target.mkdirs() ? "已创建" : "创建失败，请检查存储空间");
 refresh();
 })
 .setNegativeButton("取消", null)
 .show();
 }

 // ---------------- 下载 ----------------

 private void downloadSelected() {
 if (selFids.isEmpty()) return;
 final List<QuarkUcApi.FileItem> selected = new ArrayList<>();
 for (Object item : items)
 if (item instanceof QuarkUcApi.FileItem
 && selFids.contains(((QuarkUcApi.FileItem) item).fid))
 selected.add((QuarkUcApi.FileItem) item);
 if (selected.isEmpty()) return;
 final EditText input = new EditText(a);
 input.setSingleLine(true);
 prepareInput(input);
 String remembered =
 a.prefs.getString("filemgr_target_" + providerKey, providerName());
 input.setText(remembered);
 input.setTextColor(MainActivity.WHITE);
 input.setHintTextColor(a.themeColor(MainActivity.MUTED));
 new AppDialog.Builder(a)
 .setTitle("下载到 Download/鲸落/<目录>")
 .setMessage("目标目录可修改，不存在会自动创建。")
 .setView(input)
 .setPositiveButton(
 "开始下载",
 (d, i) -> {
 String folderName = FileManagerCore.safeName(input.getText().toString().trim());
 a.prefs.edit().putString("filemgr_target_" + providerKey, folderName).apply();
 File dir = new File(FileManagerCore.downloadDir(a), folderName);
 if (!dir.exists()) dir.mkdirs();
 startDownloads(selected, dir);
 })
 .setNegativeButton("取消", null)
 .show();
 }

 private void startDownloads(List<QuarkUcApi.FileItem> files, File dir) {
 a.toast("已加入下载 " + files.size() + " 项");
 exitSelection();
 final QuarkUcApi.Provider provider = QuarkUcApi.of(providerKey);
 downloadNext(files, 0, dir, provider);
 }

 private void downloadNext(
 final List<QuarkUcApi.FileItem> files,
 final int index,
 final File dir,
 final QuarkUcApi.Provider provider) {
 if (index >= files.size()) return;
 final QuarkUcApi.FileItem item = files.get(index);
 // 1.9.0：同名文件三选（覆盖/自动重命名/取消），默认取消
 File expected = new File(dir, FileManagerCore.safeName(item.name));
 if (expected.exists()) {
 new AppDialog.Builder(a)
 .setTitle("已存在同名文件")
 .setMessage(item.name)
 .setItems(
 new String[] {"覆盖", "自动重命名", "取消"},
 (d, which) -> {
 if (which == 0) {
 // 1.9.0：覆盖走 .part 原子替换，不先删原文件
 beginDownload(item, dir, provider, files, index + 1, true);
 } else if (which == 1) {
 beginDownload(item, dir, provider, files, index + 1, false);
 } else {
 a.toast("已取消该文件下载");
 downloadNext(files, index + 1, dir, provider);
 }
 })
 .show();
 return;
 }
 beginDownload(item, dir, provider, files, index + 1, false);
 }

 private void beginDownload(
 final QuarkUcApi.FileItem item,
 final File dir,
 final QuarkUcApi.Provider provider,
 final List<QuarkUcApi.FileItem> queue,
 final int next,
 final boolean overwrite) {
 // FileManagerCore 下载目录固定为 Download/鲸落；目标子目录通过临时切换不现实，
 // 这里先保证链路可用：下载到鲸落后提示实际路径，再继续队列。
 a.toast("开始下载：" + item.name);
 activeTaskId =
 FileManagerCore.download(
 a,
 provider,
 netdiskCookie,
 item,
 (percent, bytes, total) -> {
 if (stripProgressWrap != null) {
 ProgressBar bar =
 (ProgressBar) stripProgressWrap.getChildAt(0);
 bar.setProgress(Math.max(0, percent));
 TextView text = (TextView) stripProgressWrap.getChildAt(1);
 text.setText(item.name + " · " + (percent < 0 ? "下载中" : percent + "%"));
 }
 },
 file -> {
 activeTaskId = null;
 stripPaused = false;
 removeDownloadStrip();
 downloadNext(queue, next, dir, provider);
 if (current == this) refresh();
 },
 message -> {
 activeTaskId = null;
 stripPaused = false;
 notifyDownloadFailure(message, providerKey);
 AppLog.i("FileMgr", "download failed task kept for DownloadCenter fid=" + item.fid);
 },
 dir,
 overwrite);
 }

 static void copyQuarkLogs(MainActivity a) {
 try {
 String text = QuarkUcApi.recentQuarkLog(30);
 android.content.ClipboardManager manager =
 (android.content.ClipboardManager)
 a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
 manager.setPrimaryClip(
 android.content.ClipData.newPlainText("QuarkLog", text == null ? "" : text));
 a.toast("已复制，可发给开发者");
 } catch (Throwable error) {
 a.toast("复制失败：" + error.getMessage());
 }
 }

 /** 1.9.11：统一失败弹窗（含「复制日志」「关闭」），文案只加一次「下载失败：」。 */
 static void showFailureDialog(MainActivity a, String message) {
 String text = message == null ? "" : message;
 if (!text.startsWith("下载失败")) text = "下载失败：" + text;
 new AppDialog.Builder(a)
 .setTitle("下载失败")
 .setMessage(text)
 .setNeutralButton("复制日志", (d, i) -> copyQuarkLogs(a))
 .setPositiveButton("关闭", (d, i) -> {})
 .show();
 }

 private void notifyDownloadFailure(String message, final String providerKey) {
 String text = message == null ? "" : message;
 if (!text.startsWith("下载失败")) text = "下载失败：" + text;
 final boolean auth = text.contains("登录状态过期") || text.contains("重新扫码");
 if (!auth) {
 showFailureDialog(a, text);
 return;
 }
 new AppDialog.Builder(a)
 .setTitle("登录状态过期")
 .setMessage("夸克/UC 登录已过期，请重新扫码登录后再试。")
 .setNeutralButton("复制日志", (d, i) -> copyQuarkLogs(a))
 .setPositiveButton(
 "去扫码",
 (d, i) ->
 NetdiskQrLogin.show(
 a, providerKey, () -> a.ui.postDelayed(this::refresh, 300)))
 .setNegativeButton("取消", null)
 .show();
 }

 private void downloadForView(final QuarkUcApi.FileItem item, final boolean image) {
 File dir = new File(FileManagerCore.downloadDir(a), ".viewer");
 if (!dir.exists()) dir.mkdirs();
 a.toast("正在下载以查看：" + item.name);
 FileManagerCore.download(
 a,
 QuarkUcApi.of(providerKey),
 netdiskCookie,
 item,
 null,
 file -> {
 if (image) ImageViewerScreen.show(a, file);
 else TextViewerScreen.show(a, file);
 },
 message -> notifyDownloadFailure(message, providerKey),
 dir,
 false);
 }

 static void showNetdisk(MainActivity a, String providerKey) {
 lastSource = 1;
 FileManagerScreen screen = new FileManagerScreen(a);
 screen.tab = 1;
 if (providerKey != null && !providerKey.isEmpty()) screen.providerKey = providerKey;
 screen.open();
 current = screen;
 }

 private void buildDownloadStrip(String name) {
 removeDownloadStrip();
 stripPaused = false;
 downloadStrip = a.row();
 downloadStrip.setGravity(Gravity.CENTER_VERTICAL);
 downloadStrip.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 downloadStrip.setBackground(MobileDetailScreen.glassBg(a, 0xee0d1a2b, 0x33ffffff, 14));
 LinearLayout col = a.vertical();
 stripProgressWrap = a.vertical();
 ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
 bar.setMax(100);
 bar.setProgress(0);
 bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
 bar.setLayoutParams(new LinearLayout.LayoutParams(-1, a.dp(2)));
 stripProgressWrap.addView(bar);
 TextView text = a.text("准备下载…", 11, UiTokens.TEXT_SECONDARY);
 stripProgressWrap.addView(text);
 col.addView(stripProgressWrap);
 downloadStrip.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
 stripPause = a.text("暂停", 12, UiTokens.TEXT_PRIMARY);
 stripPause.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
 stripPause.setFocusable(true);
 focusable(stripPause);
 stripPause.setOnClickListener(
 v -> {
 if (activeTaskId == null) return;
 long now = android.os.SystemClock.uptimeMillis();
 if (now - lastPauseToggle < 300) return; // 1.9.0：300ms 防抖
 lastPauseToggle = now;
 if (!stripPaused) {
 FileManagerCore.pause(activeTaskId);
 stripPaused = true;
 stripPause.setText("继续");
 if (stripProgressWrap != null) {
 TextView t = (TextView) stripProgressWrap.getChildAt(1);
 t.setText("已暂停 · " + name);
 }
 } else {
 FileManagerCore.resume(activeTaskId);
 stripPaused = false;
 stripPause.setText("暂停");
 }
 });
 downloadStrip.addView(stripPause);
 TextView cancel = a.text("取消", 12, 0xffef4444);
 cancel.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
 cancel.setFocusable(true);
 focusable(cancel);
 cancel.setOnClickListener(
 v -> {
 if (activeTaskId != null) FileManagerCore.cancel(activeTaskId);
 removeDownloadStrip();
 });
 downloadStrip.addView(cancel);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.leftMargin = a.dp(16);
 lp.rightMargin = a.dp(16);
 lp.topMargin = a.dp(6);
 a.body.addView(downloadStrip, lp);
 }

 private void buildFailedStrip(String name, String message, Runnable retry) {
 removeDownloadStrip();
 downloadStrip = a.row();
 downloadStrip.setGravity(Gravity.CENTER_VERTICAL);
 downloadStrip.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
 downloadStrip.setBackground(MobileDetailScreen.glassBg(a, 0xee2a1016, 0x33ff6b81, 14));
 TextView text =
 a.text(
 message != null && message.startsWith("下载失败")
 ? message
 : "下载失败：" + (message == null ? "" : message),
 11,
 0xffff8fa3);
 text.setMaxLines(2);
 text.setEllipsize(android.text.TextUtils.TruncateAt.END);
 downloadStrip.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView retryBtn = a.text("重试", 12, UiTokens.ACCENT);
 retryBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
 retryBtn.setFocusable(true);
 focusable(retryBtn);
 retryBtn.setOnClickListener(v -> retry.run());
 downloadStrip.addView(retryBtn);
 TextView close = a.text("关闭", 12, UiTokens.TEXT_TERTIARY);
 close.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
 close.setFocusable(true);
 focusable(close);
 close.setOnClickListener(v -> removeDownloadStrip());
 downloadStrip.addView(close);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
 lp.leftMargin = a.dp(16);
 lp.rightMargin = a.dp(16);
 lp.topMargin = a.dp(6);
 a.body.addView(downloadStrip, lp);
 }

 private void removeDownloadStrip() {
 if (downloadStrip != null && downloadStrip.getParent() instanceof android.view.ViewGroup) {
 ((android.view.ViewGroup) downloadStrip.getParent()).removeView(downloadStrip);
 }
 downloadStrip = null;
 stripProgressWrap = null;
 }

 private void startDownloadPoll() {
 final Runnable poll =
 new Runnable() {
 @Override
 public void run() {
 if (a.body == null || !"file-manager".equals(a.body.getTag())) return;
 updateDownloadUi();
 if (downCenterBtn != null) a.ui.postDelayed(this, 800);
 }
 };
 poll.run();
 }

 private void updateDownloadUi() {
 List<FileManagerCore.Info> tasks = FileManagerCore.tasks();
 int active = 0;
 for (FileManagerCore.Info info : tasks)
 if (info.state == 0 || info.state == 1 || info.state == 2) active++;
 if (downCenterBtn != null)
 downCenterBtn.setText(active > 0 ? "下载中心 · 下载中 " + active : "下载中心");
 if (globalStrip != null && globalText != null) {
 if (tasks.isEmpty()) {
 globalStrip.setVisibility(View.GONE);
 } else {
 globalStrip.setVisibility(View.VISIBLE);
 FileManagerCore.Info first = tasks.get(0);
 int percent =
 first.total > 0 ? (int) Math.min(100, first.bytes * 100 / first.total) : -1;
 String state =
 first.state == 2
 ? "已暂停"
 : first.state == 4
 ? "失败"
 : percent >= 0 ? percent + "%" : "下载中";
 globalText.setText(
 first.name
 + " · "
 + state
 + " · 共 "
 + tasks.size()
 + " 个任务");
 }
 }
 }

 private void updateDownloadStrip() {
 if (downloadStrip == null) return;
 downloadStrip.setVisibility(View.VISIBLE);
 }

 // ---------------- 状态 ----------------

 private void showEmpty(String title, String note, String action, Runnable run) {
 LinearLayout box = a.vertical();
 box.setGravity(Gravity.CENTER);
 box.setPadding(a.dp(16), a.dp(48), a.dp(16), a.dp(48));
 TextView glyph = a.text("🗂", 44, UiTokens.TEXT_TERTIARY);
 glyph.setGravity(Gravity.CENTER);
 box.addView(glyph);
 TextView heading = a.text(title, 17, UiTokens.TEXT_PRIMARY);
 heading.setGravity(Gravity.CENTER);
 heading.setPadding(0, a.dp(8), 0, 0);
 box.addView(heading);
 TextView sub = a.text(note, 13, UiTokens.TEXT_TERTIARY);
 sub.setGravity(Gravity.CENTER);
 sub.setPadding(0, a.dp(4), 0, a.dp(12));
 box.addView(sub);
 if (action != null && run != null) {
 TextView button = a.text(action, 13, 0xff0b1220);
 button.setGravity(Gravity.CENTER);
 button.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10));
 button.setBackground(UiTokens.round(a, 999, UiTokens.ACCENT));
 button.setFocusable(true);
 focusable(button);
 button.setOnClickListener(v -> run.run());
 box.addView(button, new LinearLayout.LayoutParams(-2, -2));
 a.ui.post(button::requestFocus);
 }
 content.addView(box);
 }

 // ---------------- BACK / TV ----------------

 private boolean back() {
 if (selecting) {
 exitSelection();
 return true;
 }
 if (tab == 0) {
 // 1.9.105：先按前进栈回退（聚合根进入的目录也能回到聚合根），再兜底回本地根
 if (!dirStack.isEmpty()) {
 localDir = dirStack.pop();
 filter = 0;
 refresh();
 return true;
 }
 if (!aggregateRoot()) {
 localDir = localRootDir;
 filter = 0;
 refresh();
 return true;
 }
 }
 if (tab == 1 && !"0".equals(netdiskFid)) {
 netdiskFid = "0";
 netdiskPath = "根目录";
 filter = 0;
 refresh();
 return true;
 }
 return false;
 }

 private void goUp() {
 // 1.9.105：上级目录优先走前进栈，避免从聚合来源目录跳进无关父目录
 if (!dirStack.isEmpty()) localDir = dirStack.pop();
 else if (!aggregateRoot()) localDir = localRootDir;
 filter = 0;
 refresh();
 }

 private void tvFocusFirst() {
 a.ui.post(
 () -> {
 View card = content.findViewWithTag("filemgr-card:0");
 if (card != null) card.requestFocus();
 });
 }
}
