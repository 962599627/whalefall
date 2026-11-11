package com.whalefall.movie;

import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;

/** Remote-friendly settings. Each row changes a setting consumed by the app. */
final class SettingsScreen {
 final MainActivity a;

 SettingsScreen(MainActivity activity) {
 a = activity;
 }

 private static int styleIndex(String name) {
 for (int i = 0; i < FontStyleDialog.NAMES.length; i++)
 if (FontStyleDialog.NAMES[i].equals(name)) return i;
 return 0;
 }

 void page(String title, String note) {
 a.renderPage("", "");
 if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 if (a.body != null) a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
 AppLog.i("Wallpaper", "page=SettingsScreen bg=transparent");
 a.body.addView(a.text(title, 28, MainActivity.WHITE));
 a.gap(a.body, 8);
 if (!note.isEmpty()) a.paragraph(note);
 }

 void link(String title, String detail, String key, Runnable action) {
 LinearLayout row = a.row();
 row.setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12));
 LinearLayout copy = a.vertical();
 copy.addView(a.text(title, 17, MainActivity.WHITE));
 TextView caption = a.text(detail, 12, MainActivity.MUTED);
 caption.setPadding(0, a.dp(4), 0, 0);
 if (!detail.isEmpty()) copy.addView(caption);
 row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
 row.addView(a.text("›", 23, MainActivity.MUTED));
 row.setTag(key);
 row.setId(View.generateViewId());
 row.setFocusable(true);
 row.setFocusableInTouchMode(true);
 row.setClickable(true);
 row.setBackground(a.bg(0xff16243c, 12));
 row.setOnFocusChangeListener(
 (v, f) -> {
 FocusMotion.style(
 a,
 row,
 f,
 0xff16243c,
 0xff334564,
 12,
 MainActivity.WHITE,
 MainActivity.WHITE,
 true,
 1.008f);
 });
 a.pressFeedback(row);
 row.setOnClickListener(v -> action.run());
 LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
 size.bottomMargin = a.dp(8);
 a.body.addView(row, size);
 }

 void open(Runnable page) {
 a.open(new MainActivity.Screen(page), true);
 }

 void show() {
 page("设置", "");
 // 1.8.11 分组：数据源 / 播放器 / 备份与诊断 / 关于（对齐友商层级）
 section("数据源与首页");
 link(
 "搜索与数据",
 "配置地址与历史、来源扩展、直播源、网盘账号面板",
 "settings-data",
 () -> open(this::data));
 link(
 "首页与壁纸",
 "首页区块、推荐数量、壁纸与启动动画",
 "settings-appearance",
 () -> open(this::appearance));
 section("播放器");
 link(
 "播放与字幕",
 "播放器内核、解码、画面比例、片头片尾与字幕",
 "settings-playback",
 () -> open(this::playback));
 section("备份与诊断");
 link(
 "软件更新",
 "检查新版本、下载与安装",
 "settings-updates",
 () -> open(() -> new UpdateScreen(a).show()));
 // 1.4.0：「本机与工具」分组只给 TV（R2：手机设置页保持原 4 项，手机零变化）。
 // 放设置页而不是 Dock：7 项 Dock 已排满，再加会挤爆且打乱遥控焦点顺序。
 if (a.isTvDevice()) {
 section("本机与工具");
 link(
 "文件管理器（本地 / 网盘 / 下载）",
 "浏览本地/网盘、播放与下载；支持多选与下载中心",
 "settings-file-manager",
 () -> {
 MainActivity.Screen s =
 new MainActivity.Screen(
 () -> FileManagerScreen.showFrom(a, 0));
 s.resume = () -> FileManagerScreen.resume(a);
 a.open(s, true);
 });
 link(
 "App 内置文件（教程 / 配置 / 上传）",
 "仅 App 内置配置/教程与管理员后台上传文件",
 "settings-files",
 // 1.9.82：FilesScreen.show 内部已 a.open；这里不能再包一层 open，否则双入栈导致 BACK 循环
 () -> {
 AppLog.i("Back", "open owner=FilesScreen target=app-files");
 FilesScreen.show(a);
 });
 link(
 "片源体检 / 失效源清理",
 "逐个检测片源可用性与耗时，可禁用/恢复失效源",
 "settings-source-check",
 () -> open(() -> SourceCheckScreen.show(a)));
 link(
 "手机遥控 / 扫码输入",
 "手机浏览器扫码当遥控器和输入法，无需安装 App",
 "settings-lan-remote",
 () -> open(() -> RemoteInputScreen.show(a)));
 link(
 "管理员后台（手机/电脑）",
 "扫码或用浏览器打开，用手机/电脑管理文件与设置",
 "settings-admin",
 () -> open(() -> AdminPanel.show(a)));
 link(
 "查看崩溃日志",
 "上次异常退出堆栈；可另存到文件管理",
 "settings-crash-log",
 () -> open(() -> extra(EXTRA_CRASH)));
 link(
 "存储空间与缓存清理",
 "查看占用并清理临时缓存 / 插件缓存 / 日志",
 "settings-storage",
 () -> open(() -> extra(EXTRA_STORAGE)));
 link(
 "观影统计",
 "本周观看部数 / 时长 / 最近记录",
 "settings-stats",
 () -> open(() -> extra(EXTRA_STATS)));
 link(
 "遥控器按键说明",
 "遥控器 / 键盘 / 手机手势对照",
 "settings-keymap",
 () -> open(() -> extra(EXTRA_KEYMAP)));
 link(
 "关于鲸落",
 "版本、开源许可与源码地址",
 "settings-about",
 () -> open(() -> extra(EXTRA_ABOUT)));
 }
 }

 static final int EXTRA_ABOUT = 0;
 static final int EXTRA_STATS = 1;
 static final int EXTRA_KEYMAP = 2;
 static final int EXTRA_STORAGE = 3;
 static final int EXTRA_CRASH = 4;

 /** 复用手机构建代码（ExtraPages），TV 不复制页面；标题沿用 TV 设置页 28sp。 */
 void extra(int which) {
 switch (which) {
 case EXTRA_ABOUT:
 page("关于鲸落", "");
 if (a.body != null) a.body.setTag("settings-extra");
 ExtraPages.about(a, a.body);
 break;
 case EXTRA_STATS:
 page("观影统计", "");
 if (a.body != null) a.body.setTag("settings-extra");
 ExtraPages.stats(a, a.body);
 break;
 case EXTRA_KEYMAP:
 page("遥控器按键说明", "");
 if (a.body != null) a.body.setTag("settings-extra");
 ExtraPages.keymap(a, a.body);
 break;
 case EXTRA_STORAGE:
 page("存储空间与缓存清理", "");
 if (a.body != null) a.body.setTag("settings-extra");
 ExtraPages.storage(a, a.body);
 break;
 case EXTRA_CRASH:
 page("崩溃日志", "");
 if (a.body != null) a.body.setTag("settings-extra");
 ExtraPages.crashLog(a, a.body);
 break;
 default:
 page("设置", "");
 }
 }

 /** 分组标题（TV 13sp / 手机 12.5sp）。 */
 void section(String title) {
 TextView h = a.text(title, a.isTvDevice() ? 13 : 12.5f, 0xff5ac8fa);
 h.setTypeface(null, Typeface.BOLD);
 h.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(6));
 a.body.addView(h);
 }

 void decoders() {
 page("解码组件", "组件随应用安装，无需另外下载解码包。");
 a.paragraph(DecoderSupport.description());
 }

 void accountAction(String action) {
 accountAction(action, null);
 }

 /**
 * 与 accountAction(action) 相同，额外在源返回后回调 finished（账号面板刷新状态用）。
 *
 * <p>「LoginShow」一律先进入我们自己的「网盘登录」引导面板，绝不直接弹配置源自带的
 * 白底登录面板；只有用户在引导里明确点「打开源登录界面/源账号管理」后，才由
 * {@link #sourceAccountAction} 直达源面板。
 */
 void accountAction(String action, Runnable finished) {
 if (a.config != null)
 for (Cms.Site site : a.config.sites)
 if (site.api != null && site.api.contains("MyDrive")) {
 if (!a.available(site)) {
 a.notice("请先在“搜索与数据”中启用来源扩展。");
 if (finished != null) finished.run();
 return;
 }
 if ("LoginShow".equals(action)) {
 // 1.8.1：直达源面板，不再经过我方引导层
 sourceAccountAction(action, finished);
 return;
 }
 a.sourceAction(site, action, finished);
 return;
 }
 a.notice("当前配置没有提供网盘账号入口，请先导入支持网盘的配置源。");
 if (finished != null) finished.run();
 }

 /** 只供 PanLoginGuide 的明确动作使用：直接执行源 action，不再二次进入引导。 */
 void sourceAccountAction(String action, Runnable finished) {
 if (a.config != null)
 for (Cms.Site site : a.config.sites)
 if (site.api != null && site.api.contains("MyDrive")) {
 if (!a.available(site)) {
 a.notice("请先在“搜索与数据”中启用来源扩展。");
 if (finished != null) finished.run();
 return;
 }
 // 绕过 MainActivity.sourceAction 的登录拦截：这是用户在引导里明确点的动作
 SourceController.sourceAction(a, site, action, finished);
 return;
 }
 a.notice("当前配置没有提供网盘账号入口，请先导入支持网盘的配置源。");
 if (finished != null) finished.run();
 }

 void appearance() {
 page("首页与壁纸", "界面按屏幕分辨率自动适配，海报保持 2:3 比例。");
 link(
 "界面大小",
 TvLayoutPolicy.percent(a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100))
 + "% · 按钮、文字与图标一起调整",
 "setting-ui_scale_percent",
 () ->
 new AppDialog.Builder(a)
 .setTitle("界面大小")
 .setMessage("标准会自动适配屏幕分辨率。观看距离较远时可放大；选择后界面重新载入。")
 .setSingleChoiceItems(
 new String[] {"标准 · 自动适配", "放大 · 110%", "更大 · 120%"},
 a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 120
 ? 2
 : a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 110
 ? 1
 : 0,
 (d, n) -> {
 a.prefs
 .edit()
 .putInt(
 Prefs.UI_SCALE_PERCENT,
 new int[] {100, 110, 120}[n])
 .commit();
 a.recreate();
 })
 .setNegativeButton("返回", null)
 .show());
 ints(
 "导航栏字体与按钮大小",
 Prefs.NAV_SCALE_PERCENT,
 new String[] {"紧凑 · 85%", "标准 · 100%", "偏大 · 115%", "大号 · 130%", "特大 · 140%"},
 new int[] {85, 100, 115, 130, 140},
 100);
 ints(
 "每行海报",
 Prefs.HOME_COLUMNS,
 new String[] {"最多 5 张 · 舒展", "最多 6 张", "最多 7 张", "最多 8 张"},
 new int[] {5, 6, 7, 8},
 5);
 link(
 "字体颜色",
 TextColorDialog.summary(a),
 "settings-text-color",
 () -> TextColorDialog.show(a));
 link(
 "字体样式",
 FontStyleDialog.LABELS[styleIndex(a.fontStyleName())],
 "settings-font-style",
 () -> FontStyleDialog.show(a));
 link(
 "首页壁纸",
 a.prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
 ? "视频动态壁纸"
 : a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
 ? "自定义壁纸"
 : a.prefs.getBoolean(Prefs.HOME_AMBIENT, true) ? "海报配色柔光" : "纯深色",
 "settings-wallpaper",
 () -> open(this::wallpaper));
 toggle(
 "减少透明效果",
 Prefs.REDUCED_TRANSPARENCY,
 false,
 () -> com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root));
 toggle("开屏动画", Prefs.STARTUP_ANIMATION, true, null);
 toggle("海报配色柔光", Prefs.HOME_AMBIENT, true, null);
 toggle(
 "减少动态效果",
 Prefs.REDUCED,
 false,
 () -> a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false));
 }

 void wallpaper() {
 // 1.9.112：进入壁纸设置先自检，残留 4K/HEVC 文件自动回退
 a.ui.postDelayed(a::purgeUnsupportedVideoWallpaper, 300);
 page(
 "首页壁纸",
 "壁纸只影响应用背景，不会更改电视桌面。图片以居中裁切显示；视频壁纸循环静音播放，耗电更高。");
 link(
 "选择本地图片",
 "JPG、PNG、WebP",
 "wallpaper-file",
 () -> {
                    if (a.isTvDevice()) pickWallpaperBuiltin(false);
                    else pick(44, "image/*", false);
                });
 link(
 "选择视频壁纸",
 "MP4 / WebM / MKV，循环静音，仅首页背景播放",
 "wallpaper-video-file",
 () -> {
                    if (a.isTvDevice()) pickWallpaperBuiltin(true);
                    else pick(47, "video/*", false);
                });
 if (VideoWallpaper.file(a).isFile())
 link(
 "壁纸声音",
 a.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false)
 ? "有声（播放壁纸自带声音）"
 : "静音（推荐）",
 "wallpaper-sound",
 () -> {
 boolean on = !a.prefs.getBoolean(Prefs.WALLPAPER_SOUND, false);
 a.prefs.edit().putBoolean(Prefs.WALLPAPER_SOUND, on).apply();
 a.refresh();
 a.toast(on ? "壁纸声音：开" : "壁纸声音：静音");
 });
 if (VideoWallpaper.file(a).isFile())
 link(
 a.prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false) ? "关闭视频壁纸" : "启用视频壁纸",
 "动态壁纸持续解码，耗电高于静态图片",
 "wallpaper-video-toggle",
 () -> {
 boolean on = !a.prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false);
 a.prefs.edit().putBoolean(Prefs.WALLPAPER_VIDEO, on).apply();
 a.refresh();
 });
 link("从图片地址导入", "输入你自己的 HTTP / HTTPS 图片地址", "wallpaper-url", this::wallpaperUrl);
 ints(
 "背景压暗",
 Prefs.WALLPAPER_DIM,
 new String[] {"轻 · 40%", "适中 · 60%", "深 · 80%"},
 new int[] {40, 60, 80},
 60);
 link(
 "恢复默认背景",
 "恢复为鲸落内置壁纸（会覆盖当前自定义图片）",
 "wallpaper-reset",
 () -> a.restoreDefaultWallpaper());
 if (new File(a.getFilesDir(), "wallpaper.jpg").exists())
 link(
 "启用已保存的壁纸",
 "使用上次导入的图片",
 "wallpaper-enable",
 () -> {
 a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
 a.refresh();
 });
 }

 void playback() {
 page("播放与字幕", "作为之后播放的默认设置；播放界面中仍可随时调整。");
 ints(
 "电视台画面比例",
 Prefs.TV_ASPECT,
 new String[] {"自适应", "16:9", "4:3"},
 new int[] {0, 1, 2},
 0);
 ints(
 "电视台填充模式",
 Prefs.TV_FILL,
 new String[] {"适应（完整画面）", "裁剪（铺满）", "拉伸"},
 new int[] {0, 1, 2},
 0);
 ints(
 "画面输出",
 Prefs.VIDEO_OUTPUT,
 new String[] {"保真输出 · HDR 与流畅度优先", "兼容输出 · 支持应用内调色"},
 new int[] {0, 1},
 0);
 // 1.9.89：Android<8 隐藏「系统播放器」选项，强制 Media3
 if (android.os.Build.VERSION.SDK_INT >= 26)
 ints("播放器", Prefs.PLAYER_ENGINE, new String[] {"Media3", "系统播放器"}, new int[] {0, 1}, 0);
 else {
 if (a.prefs.getInt(Prefs.PLAYER_ENGINE, 0) != 0)
 a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, 0).apply();
 a.paragraph("当前 Android 版本低于 8.0，仅支持内置 Media3 播放器（系统播放器在旧电视上可能崩溃）。");
 }
 ints(
 "解码方式",
 Prefs.DECODER_MODE,
 new String[] {"自动", "硬件解码", "软件解码优先"},
 new int[] {0, 1, 2},
 0);
 toggle("优先使用兼容音频解码", Prefs.SOFTWARE_AUDIO, false, null);
 toggle("优先选择 AAC 音轨", Prefs.PREFER_AAC, false, null);
 link("解码组件", "查看本机已加载的音频与视频扩展", "settings-decoders", () -> open(this::decoders));
 ints(
 "画面比例",
 Prefs.ASPECT,
 new String[] {"适应", "铺满", "16:9", "4:3", "裁切填充"},
 new int[] {0, 1, 2, 3, 4},
 0);
 ints(
 "网络缓冲",
 Prefs.BUFFER_SECONDS,
 new String[] {"15 秒 · 节省内存", "30 秒 · 均衡", "50 秒 · 网络波动"},
 new int[] {15, 30, 50},
 30);
 link(
 "默认速度",
 a.prefs.getFloat(Prefs.PLAY_SPEED, 1) + "×",
 "setting-play_speed",
 () ->
 new AppDialog.Builder(a)
 .setTitle("播放速度")
 .setItems(
 new String[] {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"},
 (d, i) -> {
 a.prefs
 .edit()
 .putFloat(
 Prefs.PLAY_SPEED,
 new float[] {
 .5f, .75f, 1, 1.25f, 1.5f, 2
 }
 [i])
 .apply();
 a.refresh();
 })
 .show());
 toggle("自动播放下一集", Prefs.AUTO_NEXT, true, null);
 ints("循环", Prefs.REPEAT_MODE, new String[] {"关闭", "单集循环", "列表循环"}, new int[] {0, 1, 2}, 0);
 int skipIntroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_INTRO, 0));
 link(
 "跳过片头（精准拉取节点）",
 skipIntroSec <= 0
 ? "关闭（点击可手动拉取秒数）"
 : PlaybackController.formatTime(skipIntroSec * 1000)
 + " · "
 + skipIntroSec
 + " 秒",
 "setting-" + Prefs.SKIP_INTRO,
 () -> PlaybackControls.showSkipAdjuster(a, true, 0, 0, a::refresh));
 int skipOutroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_OUTRO, 0));
 link(
 "跳过片尾（精准拉取节点）",
 skipOutroSec <= 0
 ? "关闭（点击可手动拉取秒数）"
 : PlaybackController.formatTime(skipOutroSec * 1000)
 + " · "
 + skipOutroSec
 + " 秒",
 "setting-" + Prefs.SKIP_OUTRO,
 () -> PlaybackControls.showSkipAdjuster(a, false, 0, 0, a::refresh));
 int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
 int subBottom = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
 int subStyle = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
 link(
 "字幕大小与位置（方向键直调）",
 "字号 "
 + subSize
 + " · 底部高度 "
 + subBottom
 + "dp · "
 + SubtitleOverlayView.STYLE_LABELS[subStyle],
 "setting-" + Prefs.SUBTITLE_SIZE,
 () -> PlaybackControls.showSubtitleAdjuster(a, null, a::refresh));
 ints(
 "字幕样式",
 Prefs.SUBTITLE_STYLE,
 SubtitleOverlayView.STYLE_LABELS,
 new int[] {0, 1, 2, 3, 4, 5},
 0);
 toggle("自动加载来源弹幕", Prefs.DANMAKU_DEFAULT, true, null);
 ints(
 "画面色彩",
 Prefs.PICTURE_EFFECT,
 new String[] {"原始", "明亮", "影院", "柔和"},
 new int[] {0, 1, 2, 3},
 0);
 ints(
 "音效",
 Prefs.AUDIO_EFFECT,
 new String[] {"原声", "影院", "人声清晰", "柔和低音"},
 new int[] {0, 1, 2, 3},
 0);
 a.paragraph(
 "兼容音频解码仅切换音频，不强制视频软解码；AAC 优先仅在媒体包含可用 AAC 音轨时生效，不会转换文件。以上两项用于 Media3。音效取决于电视提供的音频接口。系统播放器支持的字幕和网络格式少于 Media3；播放器中的字幕按钮可导入外部字幕。");
 }

 void data() {
 page("搜索与数据", "搜索默认识别首拼和全拼，并检索配置中全部可搜索来源。");
 ints(
 "同时搜索的来源数",
 Prefs.SEARCH_THREADS,
 new String[] {"2 个 · 轻量", "4 个 · 均衡", "6 个 · 快速"},
 new int[] {2, 4, 6},
 6);
 ints(
 "单个来源等待时间",
 Prefs.SEARCH_TIMEOUT,
 new String[] {"10 秒", "20 秒", "30 秒"},
 new int[] {10, 20, 30},
 20);
 link(
 "导入或更新配置源",
 a.isTvDevice()
 ? "手机扫码、遥控输入或本地包 (.json/.js/.py/.zip)"
 : "剪贴板粘贴、网址输入或本地包 (.json/.js/.py/.zip)",
 "settings-import",
 a::importDialog);
 link(
 "导入或管理电视直播源",
 "支持 M3U、TXT (#genre#)、JSON 及本地直播文件",
 "settings-live-import",
 () -> SourceController.showLiveImportDialog(a));
 if (a.config != null && a.config.depots.length() > 1) {
 link(
 "切换影视仓线路",
 "当前订阅包含 " + a.config.depots.length() + " 个子仓库，点击切换",
 "settings-depots",
 () -> SourcePicker.showWarehouses(a));
 }
 toggle("启用来源扩展", Prefs.EXPERIMENTAL_CSP, true, a::closeEngines);
 toggle(
 "聚合搜索（跨源合并）",
 "search_aggregate_enabled",
 true,
 () ->
 AppLog.i(
 "SearchAgg",
 "aggregate="
 + (a.prefs.getBoolean("search_aggregate_enabled", true)
 ? "on"
 : "off")));
 link(
 "安全模式",
 SafeMode.enabled(a) ? "开启（壁纸与加载动效已禁用）" : "关闭",
 "settings-safe-mode",
 () -> {
 boolean next = !SafeMode.enabled(a);
 SafeMode.set(a, next);
 a.applySafeModeVisual();
 a.toast(next ? "已开启安全模式（壁纸与加载动效已禁用）" : "已关闭安全模式（已恢复）");
 a.refresh();
 });
 link(
 "网盘与账号",
 "直接打开配置源自带的账号面板",
 "settings-account",
 () -> {
 AppLog.i("AccountEntrance", "direct source=LoginShow");
 sourceAccountAction("LoginShow", null);
 });
 // 1.8.1 安全模式：Switch 手动开关（开=立即禁用壁纸/加载动效，持久化）
 LinearLayout safeRow = a.row();
 safeRow.setGravity(Gravity.CENTER_VERTICAL);
 safeRow.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(10));
 LinearLayout safeText = a.vertical();
 TextView safeTitle = a.text("安全模式", 15, MainActivity.WHITE);
 safeTitle.setTypeface(null, Typeface.BOLD);
 safeText.addView(safeTitle);
 TextView safeDetail =
 a.text("开启后禁用壁纸与加载动效，减少崩溃/卡顿", 12, MainActivity.MUTED);
 safeDetail.setPadding(0, a.dp(3), 0, 0);
 safeText.addView(safeDetail);
 safeRow.addView(safeText, new LinearLayout.LayoutParams(0, -2, 1f));
 android.widget.Switch safeSwitch = new android.widget.Switch(a);
 safeSwitch.setChecked(SafeMode.enabled(a));
 safeSwitch.setOnCheckedChangeListener(
 (v, checked) -> {
 SafeMode.set(a, checked);
 a.applySafeModeVisual();
 a.toast(checked ? "已开启安全模式（壁纸与加载动效已禁用）" : "已关闭安全模式（已恢复）");
 a.refresh();
 // 1.8.2：refresh 会重建当前页，下一帧再确保壁纸层补回
 a.ui.postDelayed(a::applySafeModeVisual, 120);
 a.ui.postDelayed(a::applySafeModeVisual, 400);
 });
 safeRow.addView(safeSwitch, new LinearLayout.LayoutParams(-2, a.dp(44)));
 a.body.addView(safeRow, new LinearLayout.LayoutParams(-1, -2));
 link(
 "备份配置与偏好",
 "导出配置与偏好，请妥善保管；不含插件单独保存的登录状态",
 "settings-backup",
 () -> pick(45, "application/json", true));
 link(
 "还原配置与偏好",
 "从鲸落备份文件还原",
 "settings-restore",
 () -> pick(46, "application/json", false));
 link("观看历史", "查看、继续播放或删除本机观看记录", "settings-history", () -> open(a::historyPage));
 link(
 "清除搜索记录",
 "清除最近搜索，保留网盘登录",
 "settings-clear-history",
 () ->
 confirm(
 "清除搜索记录？",
 () -> {
 a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
 a.refresh();
 }));
 link(
 "重新加载首页图片与片单",
 "清除内存与磁盘中的首页缓存",
 "settings-clear-images",
 () -> {
 a.images.evictAll();
 a.homeData.clear();
 try {
 File snapDir = new File(a.getCacheDir(), "home_snap_v1");
 File[] files = snapDir.listFiles();
 if (files != null) for (File f : files) f.delete();
 } catch (Throwable ignored) {
 }
 a.toast("已清除首页缓存");
 });
 link("运行日志", "查看本机最近异常与诊断记录", "settings-logs", this::diagnostics);
 link(
 "UI 自检",
 "检查导航选中唯一、焦点唯一、无重复/残留浮层",
 "settings-ui-audit",
 () -> {
 String report =
 UiAudit.audit(
 a,
 a.body == null ? "settings" : String.valueOf(a.body.getTag()));
 String focusReport =
 "最近一次焦点审计：\n"
 + TvNav.lastReport
 + "\nmissing 明细："
 + TvNav.lastMissing;
 new AppDialog.Builder(a)
 .setTitle("UI 自检")
 .setMessage(report + "\n\n" + focusReport)
 .setPositiveButton(
 "复制",
 (d, w) ->
 ExtraPages.copyText(
 a, report + "\n" + focusReport, "UI 自检"))
 .setNegativeButton("关闭", null)
 .show();
 });
 link(
 "焦点审计",
 "扫描当前页按钮焦点/动效是否齐全，并输出报告",
 "settings-focus-audit",
 () -> {
 // 1.9.83：手动自检时才开启自动补齐
 TvNav.setAutoFix(true);
 String report;
 try {
 report =
 TvNav.audit(
 a,
 a.body == null ? "settings" : String.valueOf(a.body.getTag()));
 } finally {
 TvNav.setAutoFix(false);
 }
 new AppDialog.Builder(a)
 .setTitle("焦点审计")
 .setMessage(report)
 .setPositiveButton(
 "复制",
 (d, w) -> ExtraPages.copyText(a, report, "焦点审计"))
 .setNegativeButton("关闭", null)
 .show();
 });
 link(
 "恢复播放与外观默认设置",
 "保留来源配置和网盘登录",
 "settings-reset",
 () ->
 confirm(
 "恢复默认设置？",
 () -> {
 android.content.SharedPreferences.Editor edit = a.prefs.edit();
 for (String key : KEYS) edit.remove(key);
 edit.commit();
 a.reduced = false;
 a.recreate();
 }));
 String appVersionName = "";
 try {
 android.content.pm.PackageInfo info =
 a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
 if (info.versionName != null) appVersionName = info.versionName;
 } catch (Throwable ignored) {
 }
 if (appVersionName.isEmpty()) appVersionName = "开发版";
 a.paragraph(
 "鲸落 "
 + appVersionName
 + " · 基于 Marco Movie 0.5.3（作者 Pilipala，GPL-3.0-only）二次开发 · 本产品仅为工具，不含任何影视资源；内容与配置请自行合法获取。播放能力取决于服务授权和设备支持。");
 }

 void confirm(String title, Runnable action) {
 new AppDialog.Builder(a)
 .setTitle(title)
 .setNegativeButton("取消", null)
 .setPositiveButton("确定", (d, i) -> action.run())
 .show();
 }

 void diagnostics() {
 final String text = AppLog.dump();
 final boolean hasText = !text.isEmpty();
 AppDialog.Builder builder =
 new AppDialog.Builder(a)
 .setTitle("运行日志")
 .setMessage(hasText ? text : "当前运行正常，暂无异常或告警记录。");
 if (hasText) {
 // 1.9.55：所有平台都可复制运行日志
 builder.setNeutralButton(
 "复制",
 (d, i) -> ExtraPages.copyText(a, text, "运行日志"));
 builder.setPositiveButton(
 "另存到文件管理",
 (d, i) -> {
 try {
 String stamp =
 new java.text.SimpleDateFormat(
 "yyyyMMdd-HHmm", java.util.Locale.CHINA)
 .format(new java.util.Date());
 String rel =
 PushFiles.writeText(
 a, "诊断/运行日志-" + stamp + ".txt", text);
 a.toast("已保存到文件管理：" + rel);
 } catch (Throwable e) {
 a.notice("另存失败：" + a.message(e));
 }
 });
 }
 builder.setNegativeButton(
 "清空记录",
 (d, i) ->
 ExtraPages.confirmDanger(
 a,
 "清空运行日志？",
 "会删除本机最近异常与诊断记录，不可恢复。",
 "清空",
 () -> {
 AppLog.clear();
 a.toast("已清空运行日志");
 }))
 .setPositiveButton("关闭", null);
 AppDialog dialog = builder.show();
 ExtraPages.focusDialog(a, dialog, "dialog-action:-1");
 }

 void toggle(String title, String key, boolean defaultValue, Runnable after) {
 link(
 title,
 a.prefs.getBoolean(key, defaultValue) ? "开启" : "关闭",
 "setting-" + key,
 () -> {
 Runnable change =
 () -> {
 a.prefs
 .edit()
 .putBoolean(key, !a.prefs.getBoolean(key, defaultValue))
 .apply();
 if (after != null) after.run();
 a.refresh();
 };
 if (key.equals(Prefs.EXPERIMENTAL_CSP) && !a.prefs.getBoolean(key, false))
 confirm("运行配置源提供的扩展？请仅使用你信任的配置。", change);
 else change.run();
 });
 }

 void ints(String title, String key, String[] labels, int[] values, int fallback) {
 int current = a.prefs.getInt(key, fallback), selected = 0;
 for (int i = 0; i < values.length; i++) if (current == values[i]) selected = i;
 final int choice = selected;
 link(
 title,
 labels[selected],
 "setting-" + key,
 () ->
 new AppDialog.Builder(a)
 .setTitle(title)
 .setSingleChoiceItems(
 labels,
 choice,
 (dialog, index) -> {
 a.prefs.edit().putInt(key, values[index]).apply();
 dialog.dismiss();
 a.refresh();
 })
 .setNegativeButton("取消", null)
 .show());
 }

 void pick(int request, String mime, boolean create) {
 if (a.privateMode && (request == 45 || request == 46 || request == 47)) {
 a.toast("请先退出隐私空间，再导出或恢复公开备份");
 return;
 }
 try {
 Intent intent =
 new Intent(
 create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
 intent.addCategory(Intent.CATEGORY_OPENABLE);
 intent.setType(mime);
 if (create) intent.putExtra(Intent.EXTRA_TITLE, "WhaleFall-backup.json");
 a.startActivityForResult(intent, request);
 } catch (Exception error) {
 a.notice("电视没有文件选择器。壁纸也可以通过图片地址导入；文件导入导出需要安装文件管理器。");
 }
 }

 void wallpaperUrl() {
 AddressInput input = new AddressInput(a, "https://…/wallpaper.jpg", "");
 new AppDialog.Builder(a)
 .setTitle("壁纸图片地址")
 .setView(input)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "导入",
 (d, i) -> {
 String address = input.value();
 if (!Cms.isHttp(address)) {
 a.notice("请输入 HTTP 或 HTTPS 图片地址。");
 return;
 }
 a.worker.execute(
 () -> {
 try {
 java.net.HttpURLConnection connection =
 (java.net.HttpURLConnection)
 new java.net.URL(
 Cms.normalizeHttp(
 address))
 .openConnection();
 connection.setConnectTimeout(15000);
 connection.setReadTimeout(15000);
 try (InputStream stream = connection.getInputStream()) {
 saveWallpaper(a, stream);
 } finally {
 connection.disconnect();
 }
 a.ui.post(
 () -> {
 a.refresh();
 a.toast("壁纸已更新");
 });
 } catch (Exception error) {
 a.ui.post(() -> a.notice("图片无法读取，请检查图片地址和格式。"));
 }
 });
 })
 .show();
 }

 static void saveVideoWallpaper(MainActivity a, InputStream stream) throws Exception {
 File target = VideoWallpaper.file(a);
 File partial = new File(target.getPath() + ".part");
 long limit = 512L * 1024 * 1024;
 long copied = 0;
 try (java.io.OutputStream output = new java.io.FileOutputStream(partial)) {
 byte[] chunk = new byte[256 * 1024];
 int length;
 while ((length = stream.read(chunk)) != -1) {
 copied += length;
 if (copied > limit) throw new java.io.IOException("Video wallpaper too large");
 output.write(chunk, 0, length);
 }
 }
 if (copied == 0) {
 partial.delete();
 throw new java.io.IOException("Empty video wallpaper");
 }
 if (!partial.renameTo(target)) {
 partial.delete();
 throw new java.io.IOException("Cannot store video wallpaper");
 }
 a.prefs.edit()
 .putBoolean(Prefs.WALLPAPER_VIDEO, true)
 .putBoolean(Prefs.WALLPAPER_ENABLED, false)
 .apply();
 String reason = videoWallpaperRejectReason(a, target);
 if (reason != null) {
 target.delete();
 a.prefs.edit()
 .putBoolean(Prefs.WALLPAPER_VIDEO, false)
 .apply();
 throw new java.io.IOException(reason);
 }
 }

 /** 1.9.108：动态壁纸严格限制 H.264 + ≤1080p + ≤30fps，避免旧 TV 黑屏。 */
 static String videoWallpaperRejectReason(MainActivity a, File target) {
 // 1.9.113：新系统（SDK>=26）行为回到 1.9.82：允许 ExoPlayer + 4K/HEVC，不做严格规格限制
 if (android.os.Build.VERSION.SDK_INT >= 26) {
 String decoderProblem = FileManagerScreen.videoCodecProblem(target);
 AppLog.i(
 "Compat",
 "sdk="
 + android.os.Build.VERSION.SDK_INT
 + " feature=wallpaper engine=exo gating=relaxed decoder="
 + (decoderProblem.isEmpty() ? "ok" : decoderProblem));
 return decoderProblem.isEmpty() ? null : decoderProblem;
 }
 AppLog.i(
 "Compat",
 "sdk="
 + android.os.Build.VERSION.SDK_INT
 + " feature=wallpaper engine=mediaplayer gating=strict");
 String mime = "";
 int width = 0, height = 0;
 float fps = 0f;
 try {
 android.media.MediaExtractor extractor = new android.media.MediaExtractor();
 extractor.setDataSource(target.getAbsolutePath());
 for (int i = 0; i < extractor.getTrackCount(); i++) {
 android.media.MediaFormat format = extractor.getTrackFormat(i);
 String m = format.getString(android.media.MediaFormat.KEY_MIME);
 if (m == null || !m.startsWith("video/")) continue;
 mime = m;
 width = format.getInteger(android.media.MediaFormat.KEY_WIDTH);
 height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT);
 try {
 if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
 fps = format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE);
 } catch (Throwable ignored) {
 }
 break;
 }
 extractor.release();
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "video probe failed", error);
 return "无法读取视频信息";
 }
 boolean h264 =
 mime.contains("avc") || mime.contains("h264") || mime.contains("video/avc");
 // 1.9.111：竖屏也支持：短边≤1080 且 长边≤1920
 int shortSide = Math.min(width, height);
 int longSide = Math.max(width, height);
 boolean tooLarge = shortSide > 1080 || longSide > 1920;
 boolean tooFast = fps > 30f;
 boolean ok = h264 && !tooLarge && !tooFast;
 AppLog.i(
 "Wallpaper",
 "video validate mime="
 + mime
 + " width="
 + width
 + " height="
 + height
 + " fps="
 + fps
 + " accepted="
 + ok);
 if (!h264) return "动态壁纸仅支持 H.264 视频（当前 " + mime + "）";
 if (tooLarge) return "动态壁纸仅支持 ≤1080p（长边≤1920，当前 " + width + "×" + height + "）";
 if (tooFast) return "动态壁纸仅支持 ≤30fps（当前 " + Math.round(fps) + "fps）";
 return null;
 }

 /** 1.9.107：动态壁纸分辨率/帧率提示（旧 TV 4K 解码易卡顿）。 */
 static void warnVideoWallpaperSpec(MainActivity a, File target) {
 try {
 android.media.MediaMetadataRetriever retriever =
 new android.media.MediaMetadataRetriever();
 retriever.setDataSource(target.getAbsolutePath());
 int width =
 Integer.parseInt(
 retriever.extractMetadata(
 android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
 int height =
 Integer.parseInt(
 retriever.extractMetadata(
 android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
 String fpsText =
 retriever.extractMetadata(
 android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
 float fps = 0f;
 try {
 fps = fpsText == null ? 0f : Float.parseFloat(fpsText);
 } catch (Throwable ignored) {
 }
 retriever.release();
 boolean tooLarge = width > 1920 || height > 1080;
 boolean tooFast = fps > 30f;
 AppLog.i(
 "Wallpaper",
 "video width="
 + width
 + " height="
 + height
 + " fps="
 + fps
 + " warn="
 + (tooLarge ? "4k" : (tooFast ? "fps" : "none")));
 if (tooLarge || tooFast) {
 a.ui.post(
 () ->
 a.toast(
 "该视频为 "
 + (tooLarge ? "4K" : "高帧率")
 + "，旧设备可能卡顿，建议使用 1080p/30fps 视频"));
 }
 } catch (Throwable error) {
 AppLog.w("Wallpaper", "video spec probe failed", error);
 }
 }

 /** 1.9.94：TV 无系统文件选择器时，用内置文件管理器选壁纸文件。 */
 void pickWallpaperBuiltin(final boolean video) {
 AppLog.i(
 "Wallpaper",
 "picker=builtin type=" + (video ? "video" : "image") + " path=file-manager");
 FileManagerScreen.pick(
 a,
 video,
 file ->
 a.worker.execute(
 () -> {
 try (InputStream input = new java.io.FileInputStream(file)) {
 if (video) saveVideoWallpaper(a, input);
 else saveWallpaper(a, input);
 AppLog.i(
 "Wallpaper",
 "picker=builtin type="
 + (video ? "video" : "image")
 + " path="
 + file.getName());
 a.ui.post(
 () -> {
 a.toast(video ? "视频壁纸已更新" : "壁纸已更新");
 a.refresh();
 });
 } catch (Exception error) {
 AppLog.w("Wallpaper", "builtin pick apply failed", error);
 a.ui.post(() -> a.notice("壁纸设置失败：" + a.message(error)));
 }
 }));
 }

 static void saveWallpaper(MainActivity a, InputStream stream) throws Exception { ByteArrayOutputStream bytes = new ByteArrayOutputStream();
 byte[] chunk = new byte[8192];
 int length;
 while ((length = stream.read(chunk)) != -1) {
 if (bytes.size() + length > 16 * 1024 * 1024) throw new IOException("Image too large");
 bytes.write(chunk, 0, length);
 }
 byte[] data = bytes.toByteArray();
 BitmapFactory.Options options = new BitmapFactory.Options();
 options.inJustDecodeBounds = true;
 BitmapFactory.decodeByteArray(data, 0, data.length, options);
 if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
 options.inSampleSize = 1;
 while (options.outWidth / options.inSampleSize > 1920
 || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
 options.inJustDecodeBounds = false;
 Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
 if (image == null) throw new IOException("Invalid image");
 File temporary = new File(a.getFilesDir(), "wallpaper.tmp");
 try {
 try (OutputStream output = new FileOutputStream(temporary)) {
 if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output))
 throw new IOException("Encoding failed");
 }
 if (!temporary.renameTo(new File(a.getFilesDir(), "wallpaper.jpg")))
 throw new IOException("Unable to save wallpaper");
 } finally {
 image.recycle();
 temporary.delete();
 }
 a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
 }

 static final String[] KEYS = Prefs.KEYS;

 static void result(MainActivity a, int request, Uri uri) {
 if (a.privateMode && (request == 45 || request == 46)) return;
 a.worker.execute(
 () -> {
 try {
 if (request == 44) {
 try (InputStream input = a.getContentResolver().openInputStream(uri)) {
 saveWallpaper(a, input);
 }
 } else if (request == 47) {
 try (InputStream input = a.getContentResolver().openInputStream(uri)) {
 saveVideoWallpaper(a, input);
 }
 } else if (request == 45) {
 org.json.JSONObject backup =
 new org.json.JSONObject()
 .put("format", "MarcoMovie-1")
 .put("schema", "marco-movie-backup")
 .put("version", 1)
 .put("origin", a.configOrigin)
 .put(
 "config",
 a.config == null
 ? org.json.JSONObject.NULL
 : new org.json.JSONObject(
 a.config.raw));
 org.json.JSONObject settings = new org.json.JSONObject();
 for (String key : KEYS)
 if (a.prefs.contains(key))
 settings.put(key, a.prefs.getAll().get(key));
 for (String dataKey :
 new String[] {
 Prefs.SELECTED_SITE,
 Prefs.WATCH_HISTORY,
 Prefs.FAVORITES,
 Prefs.GLOBAL_SEARCH_HISTORY
 }) {
 if (a.prefs.contains(dataKey)) {
 settings.put(dataKey, a.prefs.getString(dataKey, ""));
 }
 }
 backup.put("settings", settings);
 try (OutputStream output =
 a.getContentResolver().openOutputStream(uri, "wt")) {
 output.write(backup.toString(2).getBytes("UTF-8"));
 }
 } else if (request == 46) {
 org.json.JSONObject backup;
 try (InputStream input = a.getContentResolver().openInputStream(uri)) {
 backup = new org.json.JSONObject(Cms.read(input, Cms.MAX_JSON));
 }
 if (!Prefs.isBackupObject(
 backup.optString("format", null),
 backup.optString("schema", null),
 backup.optInt("version", 0)))
 throw new IOException("Invalid backup");
 final String origin = backup.optString("origin", "");
 final String configJson;
 if (!backup.has("config") || backup.isNull("config")) {
 configJson = null;
 } else {
 configJson = Prefs.extractBackupConfigJson(backup.opt("config"));
 if (configJson == null)
 throw new IOException("Invalid backup config");
 }
 final Cms.Config config =
 configJson == null ? null : Cms.parseConfig(configJson, origin);
 final org.json.JSONObject settings = backup.optJSONObject("settings");
 if (settings != null) {
 for (String key :
 new String[] {
 Prefs.WATCH_HISTORY,
 Prefs.FAVORITES,
 Prefs.GLOBAL_SEARCH_HISTORY
 }) {
 if (!settings.has(key)) continue;
 org.json.JSONArray rows =
 new org.json.JSONArray(
 String.valueOf(settings.get(key)));
 for (int i = 0; i < rows.length(); i++) {
 if (Prefs.GLOBAL_SEARCH_HISTORY.equals(key)) {
 if (!(rows.get(i) instanceof String))
 throw new IOException("Invalid search history");
 } else {
 org.json.JSONObject item = rows.getJSONObject(i);
 if (item.optString("id").isEmpty()
 || item.optString("site").isEmpty())
 throw new IOException("Invalid bookmark");
 }
 }
 }
 }
 a.ui.post(
 () ->
 new AppDialog.Builder(a)
 .setTitle("还原备份？")
 .setMessage("将替换本机来源配置与外观、播放偏好。网盘账号保持不变。")
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "还原",
 (d, i) -> {
 try {
 int oldScale =
 a.prefs.getInt(
 Prefs
 .UI_SCALE_PERCENT,
 100);
 restoreSettings(a, settings);
 if (config != null)
 a.saveConfig(
 config, origin);
 else a.refresh();
 if (oldScale
 != a.prefs.getInt(
 Prefs
 .UI_SCALE_PERCENT,
 100))
 a.recreate();
 } catch (Exception error) {
 AppLog.w(
 "Settings",
 "restoreSettings failed",
 error);
 a.notice("备份无法还原。");
 }
 })
 .show());
 return;
 }
 a.ui.post(
 () -> {
 a.refresh();
 a.toast(request == 45 ? "备份已保存" : "壁纸已更新");
 });
 } catch (Exception error) {
 AppLog.w("Settings", "file result " + request + " failed", error);
 a.ui.post(() -> a.notice("文件无法处理。请检查文件格式与可用存储空间。"));
 }
 });
 }

 static void restoreSettings(MainActivity a, org.json.JSONObject settings) {
 if (settings == null) return;
 android.content.SharedPreferences.Editor edit = a.prefs.edit();
 for (String key : KEYS) {
 Object clean = Prefs.sanitizeSetting(key, settings.opt(key));
 if (clean instanceof Boolean) edit.putBoolean(key, (Boolean) clean);
 else if (clean instanceof Float) edit.putFloat(key, (Float) clean);
 else if (clean instanceof Integer) edit.putInt(key, (Integer) clean);
 }
 for (String dataKey :
 new String[] {
 Prefs.SELECTED_SITE,
 Prefs.WATCH_HISTORY,
 Prefs.FAVORITES,
 Prefs.GLOBAL_SEARCH_HISTORY
 }) {
 if (settings.has(dataKey)) {
 String cleanData = Prefs.sanitizeUserData(dataKey, settings.opt(dataKey));
 if (cleanData != null) edit.putString(dataKey, cleanData);
 }
 }
 edit.apply();
 a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
 }
}
