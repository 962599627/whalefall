package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 电视台直播：学习 WebViewTvLive 的思路，把官方电视台直播网页内置进 App。
 *
 * <p>频道列表内置央视各频道，支持自定义频道与 JSON 导入；播放时用全屏 WebView 加载官方页面，
 * 自动注入脚本点击播放，支持数字键换台、上下滑调亮/音量、返回键退出。
 */
final class TvLive {
 private TvLive() {}

 static final String CUSTOM_FILE = "tv_channels.json";
 static final String HIDDEN = "tv_hidden_channels";
 static final String URL_OVERRIDES = "tv_url_overrides";
 static final String FAVORITES = "tv_favorites";
 static final String LAST = "tv_last";

 /** 一个频道：名称、官方直播页地址、分组。 */
 static final class Channel {
 final String name;
 final String url;
 final String group;

 Channel(String name, String url, String group) {
 this.name = name;
 this.url = url;
 this.group = group;
 }
 }

 static final String GROUP_CCTV = "央视频道";
 static final String GROUP_SAT = "卫视频道";
 static final String GROUP_MINE = "我的收藏";
 /** 1.9.19：网页直播分组（内置+自定义 weblive_sources）。 */
 static final String GROUP_WEB = "网页直播";
 /** 1.9.150：开源 IPTV（iptv-org M3U，原生 ExoPlayer）。 */
 static final String GROUP_IPTV = "开源 IPTV";

 static final Object[][] WEBLIVE = {
 {"深视 4K 超高清", "https://www.sztv.com.cn/dianshi.shtml?id=24725"},
 {"深圳卫视", "https://www.sztv.com.cn/dianshi.shtml?id=7867"},
 {"都市频道", "https://www.sztv.com.cn/dianshi.shtml?id=7868"},
 {"电视剧频道", "https://www.sztv.com.cn/dianshi.shtml?id=7880"},
 {"少儿频道", "https://www.sztv.com.cn/dianshi.shtml?id=7881"},
 {"移动电视", "https://www.sztv.com.cn/dianshi.shtml?id=7869"},
 {"宜和购物频道", "https://www.sztv.com.cn/dianshi.shtml?id=7878"},
 {"国际频道", "https://www.sztv.com.cn/dianshi.shtml?id=7944"},
 };

 static final Object[][] CCTV = {
 {"CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"},
 {"CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"},
 {"CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"},
 {"CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"},
 {"CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"},
 {"CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"},
 {"CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"},
 {"CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"},
 {"CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"},
 {"CCTV-9 纪录", "https://tv.cctv.com/live/cctv9/"},
 {"CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"},
 {"CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"},
 {"CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"},
 {"CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"},
 {"CCTV-14 少儿", "https://tv.cctv.com/live/cctv14/"},
 {"CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"},
 {"CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"},
 {"CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"},
 {"CCTV-4K 超高清", "https://tv.cctv.com/live/cctv4k/"},
 {"CCTV-8K 超高清", "https://tv.cctv.com/live/cctv8k/"},
 };

 static final Object[][] SAT = {
 {"湖南卫视", "https://live.mgtv.com/"},
 {"东方卫视", "https://live.kankanews.com/"},
 {"江苏卫视", "https://live.jstv.com/"},
 {"山东卫视", "https://v.iqilu.com/live/sdtv/"},
 {"山东齐鲁", "https://v.iqilu.com/live/qlpd/"},
 {"山东公共", "https://v.iqilu.com/live/ggpd/"},
 {"山东农科", "https://v.iqilu.com/live/nkpd/"},
 {"江西卫视", "https://www.jxntv.cn/live/"},
 {"福建新闻", "http://live.fjtv.net/zhpd/"},
 {"广西卫视", "https://live.gxtv.cn/"},
 {"云南都市", "https://www.yntv.cn/mobile/live.html?pid=yunnandushi"},
 {"云南公共", "https://www.yntv.cn/mobile/live.html?pid=yunnangonggong"},
 {"云南国际", "https://www.yntv.cn/mobile/live.html?pid=yunnanguoji"},
 {"贵州卫视", "https://www.gzstv.com/"},
 {"四川卫视", "https://www.sctv.com/live/list"},
 {"重庆卫视", "https://www.cqnews.net/"},
 {"湖北卫视", "http://www.hbtv.com.cn/"},
 {"黑龙江卫视", "https://www.hljtv.com/live"},
 {"山西卫视", "https://www.sxrtv.com/live/"},
 {"新疆卫视", "https://www.xjtvs.com.cn/"},
 {"海南卫视", "https://www.hnntv.cn/live.html"},
 {"深圳卫视", "https://www.sztv.com.cn/live"},
 {"广东卫视", "https://www.gdtv.cn/tvChannelDetail/43"},
 {"安徽卫视", "https://www.ahtv.cn/live"},
 {"河北卫视", "https://www.hebtv.com/"},
 {"宁夏卫视", "https://www.nxtv.com.cn/"},
 {"甘肃卫视", "https://www.gstv.com.cn/"},
 {"内蒙古卫视", "https://www.nmtv.cn/live"},
 {"TVB 翡翠台", "https://www.tvb.com/"},
 {"北京卫视", "https://www.brtn.cn/live"},
 {"卡酷少儿", "https://www.brtn.cn/live?channel=kaku"},
 {"浙江卫视", "https://www.zjstv.com/"},
 {"天津卫视", "https://www.tjyun.com/"},
 {"辽宁卫视", "https://www.lntv.cn/"},
 {"吉林卫视", "https://www.jlntv.cn/live"},
 {"河南卫视", "https://www.hntv.tv/live/index"},
 {"陕西卫视", "http://live.snrtv.com/star"},
 {"青海卫视", "https://www.qhbtv.com/"},
 {"西藏卫视", "https://www.xzstv.com/"},
 {"兵团卫视", "https://www.btstv.com/"},
 {"东南卫视", "https://live.fjtv.net/"},
 {"厦门卫视", "https://www.xmtv.cn/"},
 {"金鹰卡通", "https://www.mgtv.com/"},
 {"优漫卡通", "https://www.jstv.com/"},
 {"嘉佳卡通", "https://www.gdtv.cn/"},
 {"南方卫视", "https://www.gdtv.cn/"},
 {"中国教育1套", "http://www.centv.cn/"},
 };

 static final class Store {
 final List<Channel> builtin = new ArrayList<>();
 final List<Channel> custom = new ArrayList<>();
 /** 1.9.150：开源 IPTV 频道（缓存/下载后注入，不落 tv_channels.json）。 */
 final List<Channel> iptv = new ArrayList<>();
 final Set<String> favorites = new LinkedHashSet<>();

 Store(MainActivity a) {
 Set<String> hidden = readHidden(a);
 Map<String, String> overrides = readUrlOverrides(a);
 for (Object[] row : CCTV)
 if (!hidden.contains((String) row[0]))
 builtin.add(
 new Channel(
 (String) row[0],
 (overrides.containsKey((String) row[0]) ? overrides.get((String) row[0]) : (String) row[1]),
 GROUP_CCTV));
 for (Object[] row : SAT)
 if (!hidden.contains((String) row[0]))
 builtin.add(
 new Channel(
 (String) row[0],
 (overrides.containsKey((String) row[0]) ? overrides.get((String) row[0]) : (String) row[1]),
 GROUP_SAT));
 for (Object[] row : WEBLIVE)
 if (!hidden.contains((String) row[0]))
 builtin.add(
 new Channel(
 (String) row[0],
 (overrides.containsKey((String) row[0]) ? overrides.get((String) row[0]) : (String) row[1]),
 GROUP_WEB));
 builtin.addAll(readWebLive(a));
 custom.addAll(readCustom(a));
 // 1.9.150：恢复本地缓存的开源 IPTV 频道（离线可用）
 if (!hidden.contains(GROUP_IPTV))
 for (Channel channel : IptvOrg.cached(a))
 if (!hidden.contains(channel.name)) iptv.add(channel);
 favorites.addAll(readFavorites(a));
 }

 /** 1.9.150：下载完成后替换开源 IPTV 频道并重绘。 */
 void setIptv(List<Channel> channels) {
 iptv.clear();
 if (channels != null) iptv.addAll(channels);
 }

 /** 全部频道（内置 + 自定义 + 开源 IPTV），顺序稳定，不做收藏置顶。 */
 List<Channel> every() {
 List<Channel> list = new ArrayList<>(builtin);
 list.addAll(custom);
 list.addAll(iptv);
 return list;
 }

 /** 旧接口：收藏频道在前，播放器/兼容调用仍在用。 */
 List<Channel> all() {
 List<Channel> list = every();
 List<Channel> fav = new ArrayList<>();
 for (Channel c : list) if (favorites.contains(c.name)) fav.add(c);
 if (!fav.isEmpty()) {
 List<Channel> out = new ArrayList<>(fav);
 out.addAll(list);
 return out;
 }
 return list;
 }

 List<Channel> group(String group) {
 List<Channel> source = every();
 if (GROUP_MINE.equals(group)) {
 List<Channel> fav = new ArrayList<>();
 for (Channel c : source) if (favorites.contains(c.name)) fav.add(c);
 return fav;
 }
 List<Channel> list = new ArrayList<>();
 for (Channel c : source) if (group.equals(c.group)) list.add(c);
 return list;
 }
 Channel byName(String name) {
 for (Channel c : every()) if (c.name.equals(name)) return c;
 return null;
 }
 }

 // ======================= 数据持久化 =======================

 // ======================= 页面 =======================

 /** 电视台直播页的交互状态：分组 / 搜索 / 当前高亮频道 / 重渲染入口。 */
 static final class PageState {
 final MainActivity a;
 final Store store;
 final boolean wide;
 final int sidePad;
 final int railWidth;
 final int previewWidth;
 FrameLayout contentSlot;
 TextView subTitle;
 EditText search;
 TextView clearBtn;
 String group = GROUP_CCTV;
 String query = "";
 String selected;
 int listScrollY;
 Runnable render;

 PageState(
 MainActivity a,
 Store store,
 boolean wide,
 int sidePad,
 int railWidth,
 int previewWidth) {
 this.a = a;
 this.store = store;
 this.wide = wide;
 this.sidePad = sidePad;
 this.railWidth = railWidth;
 this.previewWidth = previewWidth;
 }

 void refresh() {
 if (render != null) render.run();
 }

 /** 切换分组：清空搜索、重置高亮，然后整页重绘。 */
 void pickGroup(String g) {
 group = g;
 selected = null;
 query = "";
 if (search != null) search.setText("");
 refresh();
 // 1.9.150：首次进入「开源 IPTV」且无缓存时自动下载
 if (GROUP_IPTV.equals(g) && store.group(g).isEmpty() && !iptvFetching)
 refreshIptv(this, false);
 }

 /** 实时搜索：频道名 / 去分隔符全名 / 拼音首字母都能命中。 */
 void setSearch(String raw) {
 String next = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
 if (clearBtn != null) {
 clearBtn.setVisibility(next.isEmpty() ? View.INVISIBLE : View.VISIBLE);
 }
 if (next.equals(query)) return;
 query = next;
 selected = null;
 refresh();
 }
 }

 /** TV 版入口（1.4.0）：把电视台直播作为独立页面打开，不依赖手机版 MobileShell。 */
 static void show(MainActivity a) {
 a.renderPage("", "", false);
 a.root.addView(screen(a), new FrameLayout.LayoutParams(-1, -1));
 }

 static View screen(MainActivity a) {
 if (a.formFactor == TvLayoutPolicy.FORM_PHONE) {
 try {
 a.setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
 } catch (Throwable ignored) {
 }
 } else if (a.formFactor == TvLayoutPolicy.FORM_TABLET) {
 try {
 a.setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
 } catch (Throwable ignored) {
 }
 }
 final Store store = new Store(a);
 final boolean tv = a.isTvDevice();
 final float density = a.getResources().getDisplayMetrics().density;
 final int windowDp = Math.round(a.windowWidth() / density);
 // 平板横向：左侧分类栏 + 中间编号列表 + 右侧频道信息三段式；手机/竖屏退化为单列列表
 // 手机竖屏一律单列（即使旋转时序导致 windowWidth 暂时残留横屏值也不出三栏）；
 // 平板/TV 仍按宽度判断，保持原有横屏三段式。
 final boolean wide =
 windowDp >= 720
 && (a.formFactor != TvLayoutPolicy.FORM_PHONE || !a.isPortrait());
 // TV 遥控 overscan 安全边：横向 ≥24dp、纵向 ≥18dp；手机沿用原值
 final int sidePad = a.dp(tv ? 24 : (wide ? 20 : 16));
 final PageState page =
 new PageState(
 a,
 store,
 wide,
 sidePad,
 // TV 设计值：左栏 176 + 16 + 列表 + 16 + 预览 300；手机/平板沿用原值
 a.dp(tv ? 176 : (wide ? 150 : 0)),
 a.dp(tv ? 300 : (wide ? 252 : 0)));

 FrameLayout root = new FrameLayout(a);
 // 不铺不透明 backdrop：让 MainActivity 安装的应用壁纸透出来（与文件管理/详情页一致）。
 // 子面板自带 glass 底，文字对比度沿用现有 token。

 LinearLayout shell = a.vertical();
 root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

 // ---------------- 吸顶头部：标题 / 说明 / 实时搜索 ----------------
 LinearLayout header = a.vertical();
 header.setPadding(
 sidePad,
 tv ? a.dp(18) : MobileDetailScreen.getStatusBarHeight(a) + a.dp(6),
 sidePad,
 0);
 shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

 LinearLayout titleRow = a.row();
 titleRow.setGravity(Gravity.CENTER_VERTICAL);
 TextView title = a.text("电视台直播", wide ? 27 : 24, MainActivity.WHITE);
 title.setTypeface(null, Typeface.BOLD);
 title.setIncludeFontPadding(false);
 titleRow.addView(title);

 if (wide) {
 LinearLayout livePill = a.row();
 livePill.setGravity(Gravity.CENTER_VERTICAL);
 livePill.setPadding(a.dp(9), 0, a.dp(11), 0);
 livePill.setBackground(MobileDetailScreen.glassBg(a, 0x1fff8fb1, 0x55ffb3c8, 999));
 livePill.addView(
 TvUiKit.dot(a, 0xffff6f96, 6),
 new LinearLayout.LayoutParams(a.dp(6), a.dp(6)));
 TextView liveTxt = a.text("直播中", 11, 0xffffb3c8);
 liveTxt.setTypeface(null, Typeface.BOLD);
 liveTxt.setIncludeFontPadding(false);
 LinearLayout.LayoutParams liveTxtLp = new LinearLayout.LayoutParams(-2, -2);
 liveTxtLp.leftMargin = a.dp(5);
 livePill.addView(liveTxt, liveTxtLp);
 LinearLayout.LayoutParams livePillLp = new LinearLayout.LayoutParams(-2, a.dp(26));
 livePillLp.leftMargin = a.dp(10);
 titleRow.addView(livePill, livePillLp);
 }

 View titleSpacer = new View(a);
 titleRow.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

 // 手机窄屏：标题行只放标题，三个操作 pill 单独一行，避免被屏幕右缘裁切
 LinearLayout actionsRow = null;
 if (!wide) {
 actionsRow = a.row();
 actionsRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
 TextView addBtn = TvUiKit.pill(a, "＋ 自定义", 12, false);
 ExtraPages.tvFocus(a, addBtn, 999);
 addBtn.setOnClickListener(
 v -> {
 if (a.isTvDevice()) ExtraPages.degrade(a, true);
 else addChannelDialog(a, store, () -> page.refresh());
 });
 actionsRow.addView(addBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));
 TextView webBtn = TvUiKit.pill(a, "＋ 网页直播", 12, false);
 ExtraPages.tvFocus(a, webBtn, 999);
 webBtn.setOnClickListener(
 v -> {
 if (a.isTvDevice()) ExtraPages.degrade(a, true);
 else addWebLiveDialog(a, () -> page.refresh());
 });
 LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 webLp.leftMargin = a.dp(8);
 actionsRow.addView(webBtn, webLp);
 TextView importBtn = TvUiKit.pill(a, "导入列表", 12, false);
 ExtraPages.tvFocus(a, importBtn, 999);
 LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 importLp.leftMargin = a.dp(8);
 importBtn.setOnClickListener(
 v -> {
 if (a.isTvDevice()) ExtraPages.degrade(a, true);
 else importDialog(a, store);
 });
 actionsRow.addView(importBtn, importLp);
 if (!a.isTvDevice()) {
 TextView manageBtn = TvUiKit.pill(a, "管理", 12, false);
 ExtraPages.tvFocus(a, manageBtn, 999);
 LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 manageLp.leftMargin = a.dp(8);
 manageBtn.setOnClickListener(v -> ChannelManagerScreen.show(a));
 actionsRow.addView(manageBtn, manageLp);
 }
 }
 header.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
 if (actionsRow != null) {
 LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
 actionsLp.topMargin = a.dp(8);
 header.addView(actionsRow, actionsLp);
 }

 LinearLayout subRow = a.row();
 subRow.setGravity(Gravity.CENTER_VERTICAL);
 subRow.setPadding(0, a.dp(6), 0, 0);
 if (!wide) {
 subRow.addView(
 TvUiKit.dot(a, 0xffff6f96, 5),
 new LinearLayout.LayoutParams(a.dp(5), a.dp(5)));
 }
 TextView subTitle = a.text("", 11.5f, 0xffa9b7d0);
 subTitle.setSingleLine(true);
 subTitle.setEllipsize(TextUtils.TruncateAt.END);
 LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(0, -2, 1f);
 if (!wide) subLp.leftMargin = a.dp(6);
 subRow.addView(subTitle, subLp);
 header.addView(subRow, new LinearLayout.LayoutParams(-1, -2));
 page.subTitle = subTitle;

 // 搜索：实时过滤频道名 / 拼音首字母
 final EditText input = new EditText(a);
 input.setBackground(null);
 input.setSingleLine(true);
 input.setHint("搜索频道或拼音首字母，如 hnws、cctv5");
 input.setHintTextColor(a.themeColor(0xff7f8fa9));
 input.setTextColor(a.userTextColor(0xfff5f7fc));
 input.setTextSize(13.5f);
 input.setIncludeFontPadding(false);
 input.setInputType(InputType.TYPE_CLASS_TEXT);
 input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
 input.setPadding(a.dp(8), 0, a.dp(8), 0);
 page.search = input;

 LinearLayout searchRow = a.row();
 searchRow.setGravity(Gravity.CENTER_VERTICAL);
 searchRow.setPadding(0, a.dp(8), 0, a.dp(2));

 LinearLayout searchBox = a.row();
 searchBox.setGravity(Gravity.CENTER_VERTICAL);
 searchBox.setPadding(a.dp(10), 0, a.dp(6), 0);
 searchBox.setBackground(MobileDetailScreen.glassBg(a, 0x17ffffff, 0x2effffff, 16));
 searchBox.setClickable(true);
 searchBox.addView(
 new TvUiKit.SearchGlyph(a, 0xff8d9cb6),
 new LinearLayout.LayoutParams(a.dp(16), a.dp(16)));
 searchBox.addView(input, new LinearLayout.LayoutParams(0, a.dp(42), 1f));

 final TextView clearBtn = a.text("×", 15, 0x99e6edf9);
 clearBtn.setGravity(Gravity.CENTER);
 clearBtn.setVisibility(View.INVISIBLE);
 clearBtn.setOnClickListener(
 v -> {
 input.setText("");
 hideSoftKeyboard(a, input);
 });
 searchBox.addView(clearBtn, new LinearLayout.LayoutParams(a.dp(28), a.dp(28)));
 page.clearBtn = clearBtn;

 searchRow.addView(
 searchBox,
 wide
 ? new LinearLayout.LayoutParams(a.dp(520), a.dp(42))
 : new LinearLayout.LayoutParams(-1, a.dp(42)));
 if (wide) {
 View searchSpacer = new View(a);
 searchRow.addView(searchSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
 TextView searchHint = a.text("点频道直接播放 · 长按收藏", 11, 0xff6f7f99);
 searchRow.addView(searchHint);
 }
 header.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
 if (tv) {
 // 设计规范：TV 隐藏搜索框（遥控无输入，首字母键盘二期）
 searchRow.setVisibility(View.GONE);
 input.setFocusable(false);
 }

 searchBox.setOnClickListener(
 v -> {
 input.requestFocus();
 showSoftKeyboard(a, input);
 });
 input.setOnEditorActionListener(
 (v, actionId, event) -> {
 hideSoftKeyboard(a, input);
 return true;
 });
 input.addTextChangedListener(
 new TextWatcher() {
 @Override
 public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

 @Override
 public void onTextChanged(CharSequence s, int start, int before, int count) {}

 @Override
 public void afterTextChanged(Editable s) {
 page.setSearch(s.toString());
 }
 });

 // ---------------- 内容区（整块重绘） ----------------
 final FrameLayout contentSlot = new FrameLayout(a);
 shell.addView(contentSlot, new LinearLayout.LayoutParams(-1, 0, 1f));
 page.contentSlot = contentSlot;

 page.render = () -> renderTvPage(page);
 page.refresh();
 return root;
 }

 /** 1.9.150：下载/刷新开源 IPTV（clear=true 时先清缓存）。 */
 static void refreshIptv(final PageState page, final boolean clear) {
 if (page == null || iptvFetching) return;
 iptvFetching = true;
 page.a.toast(clear ? "正在刷新开源 IPTV…" : "正在获取开源 IPTV…");
 IptvOrg.Callback callback =
 (channels, fromCache, error) -> {
 iptvFetching = false;
 if (channels == null || channels.isEmpty()) {
 page.a.toast(error == null ? "开源 IPTV 为空" : "下载失败，请检查网络");
 return;
 }
 page.store.setIptv(channels);
 page.pickGroup(GROUP_IPTV);
 if (fromCache && error != null) {
 page.a.toast("网络不可用，已使用内置频道");
 } else {
 page.a.toast(
 (fromCache ? "离线缓存：" : "已更新：") + channels.size() + " 个频道");
 }
 };
 if (clear) IptvOrg.refresh(page.a, callback);
 else IptvOrg.fetch(page.a, callback);
 }

 static volatile boolean iptvFetching;

 /** 重绘内容区：三段式（平板横向）或单列（手机竖屏）。 */
 static void renderTvPage(PageState page) {
 final MainActivity a = page.a;
 final Store store = page.store;
 final boolean wide = page.wide;
 final int sidePad = page.sidePad;

 final String q = page.query;
 final boolean searching = !q.isEmpty();
 final List<Channel> list =
 searching ? searchChannels(store, q) : store.group(page.group);
 if (page.selected == null || findChannel(list, page.selected) == null) {
 page.selected = list.isEmpty() ? null : list.get(0).name;
 }
 final Channel chosen = findChannel(list, page.selected);

 final int cctvCount = store.group(GROUP_CCTV).size();
 final int satCount = store.group(GROUP_SAT).size();
 final int webCount = store.group(GROUP_WEB).size();
 final int iptvCount = store.group(GROUP_IPTV).size();
 final int favCount = store.favorites.size();
 final int total = store.every().size();
 if (page.subTitle != null) {
 if (wide) {
 page.subTitle.setText(
 total
 + " 个频道 · 央视 "
 + cctvCount
 + " · 卫视 "
 + satCount
 + " · 官方网页直播，数字键换台，上下滑调亮度 / 音量");
 } else {
 page.subTitle.setText("共 " + total + " 个频道 · 官方网页直播，轻点频道直接播放");
 }
 }
 if (page.clearBtn != null) {
 page.clearBtn.setVisibility(searching ? View.VISIBLE : View.INVISIBLE);
 }

 page.contentSlot.removeAllViews();

 if (!wide) {
 LinearLayout body = a.vertical();
 FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(-1, -1);
 // 底部给手机悬浮 Dock 留出空间；TV 页无手机 Dock，只留安全边
 bodyLp.setMargins(
 sidePad,
 a.dp(a.isTvDevice() ? 18 : 4),
 sidePad,
 a.dp(a.isTvDevice() ? 18 : 96));
 page.contentSlot.addView(body, bodyLp);

 HorizontalScrollView chipScroll = new HorizontalScrollView(a);
 chipScroll.setHorizontalScrollBarEnabled(false);
 chipScroll.setClipToPadding(false);
 LinearLayout chipRow = a.row();
 chipScroll.addView(chipRow);

 String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_IPTV, GROUP_WEB, GROUP_MINE};
 int[] counts = {cctvCount, satCount, iptvCount, webCount, favCount};
 for (int i = 0; i < gs.length; i++) {
 final String g = gs[i];
 boolean on = !searching && g.equals(page.group);
 LinearLayout chip = a.row();
 chip.setGravity(Gravity.CENTER_VERTICAL);
 chip.setPadding(a.dp(13), 0, a.dp(13), 0);
 chip.setBackground(
 on
 ? TvUiKit.accentFill(a, 999)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(chip);
 if (a.isTvDevice()) chip.setTag("tv-focus-cat:" + g);
 ExtraPages.tvFocus(a, chip, 999);
 TextView nm = a.text(gs[i], 12.5f, on ? 0xff31141f : 0xffd6e1f0);
 nm.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
 chip.addView(nm);
 TextView ct =
 a.text(String.valueOf(counts[i]), 10.5f, on ? 0xa631141f : 0xff8d9cb6);
 LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(-2, -2);
 ctLp.leftMargin = a.dp(6);
 chip.addView(ct, ctLp);
 chip.setOnClickListener(v -> page.pickGroup(g));
 LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, a.dp(38));
 chipLp.rightMargin = a.dp(8);
 chipRow.addView(chip, chipLp);
 }
 // 1.9.150：刷新开源 IPTV
 LinearLayout refreshChip = a.row();
 refreshChip.setGravity(Gravity.CENTER);
 refreshChip.setPadding(a.dp(13), 0, a.dp(13), 0);
 refreshChip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(refreshChip);
 ExtraPages.tvFocus(a, refreshChip, 999);
 refreshChip.setTag("tv-focus-live-iptv");
 refreshChip.addView(a.text("刷新开源", 12.5f, 0xffc9d6ea));
 refreshChip.setOnClickListener(v -> refreshIptv(page, true));
 LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-2, a.dp(38));
 chipRow.addView(refreshChip, refreshLp);
 body.addView(chipScroll, new LinearLayout.LayoutParams(-1, a.dp(42)));

 LinearLayout mid = buildMiddle(page, list, chosen);
 LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(-1, 0, 1f);
 midLp.topMargin = a.dp(8);
 body.addView(mid, midLp);
 } else {
 LinearLayout bodyRow = a.row();
 bodyRow.setGravity(Gravity.TOP);
 FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(-1, -1);
 bodyLp.setMargins(
 sidePad,
 a.dp(a.isTvDevice() ? 18 : 8),
 sidePad,
 a.dp(a.isTvDevice() ? 18 : 20));
 page.contentSlot.addView(bodyRow, bodyLp);

 bodyRow.addView(
 buildCategoryRail(page, cctvCount, satCount, webCount, favCount, iptvCount),
 new LinearLayout.LayoutParams(page.railWidth, -1));
 View gap1 = new View(a);
 bodyRow.addView(gap1, new LinearLayout.LayoutParams(a.dp(12), 1));

 bodyRow.addView(
 buildMiddle(page, list, chosen), new LinearLayout.LayoutParams(0, -1, 1f));
 View gap2 = new View(a);
 bodyRow.addView(gap2, new LinearLayout.LayoutParams(a.dp(12), 1));

 bodyRow.addView(
 buildPreview(page, chosen),
 new LinearLayout.LayoutParams(page.previewWidth, -1));
 }
 wireTvFocus(page);
 focusFirstTvItem(page);
 }

 /** 1.9.69：电视台直播页显式焦点链：分类 ↔ 频道列表 ↔ 右侧面板。 */
 static void wireTvFocus(final PageState page) {
 final MainActivity a = page.a;
 if (!a.isTvDevice() || page.contentSlot == null) return;
 View slot = page.contentSlot;
 View search = slot.findViewWithTag("tv-focus-search");
 java.util.List<View> cats = new java.util.ArrayList<>();
 java.util.List<View> channels = new java.util.ArrayList<>();
 collectByPrefix(slot, "tv-focus-cat:", cats, 32);
 collectByPrefix(slot, "tv-focus-channel:", channels, 256);
 View fav = slot.findViewWithTag("tv-focus-live-fav");
 View watch = slot.findViewWithTag("tv-focus-live-watch");
 View add = slot.findViewWithTag("tv-focus-live-add");
 View imp = slot.findViewWithTag("tv-focus-live-import");
 View manage = slot.findViewWithTag("tv-focus-live-manage");
 for (View v : cats)
 if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
 for (View v : channels)
 if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
 for (int i = 0; i < cats.size(); i++) {
 View cat = cats.get(i);
 if (i > 0) cat.setNextFocusUpId(cats.get(i - 1).getId());
 if (i + 1 < cats.size()) cat.setNextFocusDownId(cats.get(i + 1).getId());
 if (!channels.isEmpty()) cat.setNextFocusRightId(channels.get(0).getId());
 }
 for (int i = 0; i < channels.size(); i++) {
 View ch = channels.get(i);
 if (i > 0) ch.setNextFocusUpId(channels.get(i - 1).getId());
 if (i + 1 < channels.size()) ch.setNextFocusDownId(channels.get(i + 1).getId());
 if (!cats.isEmpty()) ch.setNextFocusLeftId(cats.get(0).getId());
 View right = watch != null ? watch : fav;
 if (right != null) ch.setNextFocusRightId(right.getId());
 }
 if (fav != null && watch != null) {
 fav.setNextFocusDownId(watch.getId());
 watch.setNextFocusUpId(fav.getId());
 if (!channels.isEmpty()) {
 fav.setNextFocusLeftId(channels.get(0).getId());
 watch.setNextFocusLeftId(channels.get(0).getId());
 }
 }
 if (!channels.isEmpty()) {
 View last = channels.get(channels.size() - 1);
 if (watch != null) last.setNextFocusDownId(watch.getId());
 else if (fav != null) last.setNextFocusDownId(fav.getId());
 }
 if (search != null) {
 if (!channels.isEmpty()) search.setNextFocusDownId(channels.get(0).getId());
 if (!cats.isEmpty()) search.setNextFocusLeftId(cats.get(0).getId());
 }
 AppLog.i(
 "TvNav",
 "page=live focus=channel cats="
 + cats.size()
 + " channels="
 + channels.size()
 + " panel="
 + (fav != null || watch != null));
 }

 private static void collectByPrefix(View view, String prefix, java.util.List<View> out, int limit) {
 if (view == null || out.size() >= limit) return;
 if (view.getTag() instanceof String
 && ((String) view.getTag()).startsWith(prefix)
 && view.isFocusable()) out.add(view);
 if (view instanceof android.view.ViewGroup) {
 android.view.ViewGroup group = (android.view.ViewGroup) view;
 for (int i = 0; i < group.getChildCount() && out.size() < limit; i++)
 collectByPrefix(group.getChildAt(i), prefix, out, limit);
 }
 }

 /** 递归按 tag 前缀找第一个（焦点兜底用）。 */
 static View findByPrefix(View view, String prefix) {
 if (view == null) return null;
 if (view.getTag() instanceof String
 && ((String) view.getTag()).startsWith(prefix)
 && view.isFocusable()
 && view.isShown()) return view;
 if (view instanceof android.view.ViewGroup) {
 android.view.ViewGroup group = (android.view.ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++) {
 View found = findByPrefix(group.getChildAt(i), prefix);
 if (found != null) return found;
 }
 }
 return null;
 }

 /** TV 端：整块重绘后把焦点还给「当前分组」或频道首项，避免刷新/退出播放后焦点丢失。 */
 static void focusFirstTvItem(final PageState page) {
 final MainActivity a = page.a;
 if (!a.isTvDevice() || page.contentSlot == null) return;
 final FrameLayout slot = page.contentSlot;
 slot.post(
 new Runnable() {
 @Override
 public void run() {
 if (page.contentSlot != slot || a.isFinishing()) return;
 View focused = a.getCurrentFocus();
 // 用户已经把焦点放在仍在页面上的控件（如搜索框）时不要抢
 if (focused != null && focused.isAttachedToWindow()) return;
 focusSelectedOrFirst(page, slot, true);
 }
 });
 }

 /**
 * TV 焦点归位：优先回到正在播放的频道行（page.selected，播放返回后仍停留原频道），
 * 其次当前分组胶囊 / 列表首项；rows 由 RecyclerView 按需绑定，未绑定时补一次重试。
 */
 static void focusSelectedOrFirst(final PageState page, final View slot, boolean retry) {
 final MainActivity a = page.a;
 if (page.contentSlot != slot) return;
 View target = null;
 if (page.selected != null && !page.selected.isEmpty())
 target = slot.findViewWithTag("tv-focus-channel:" + page.selected);
 // 1.9.69：默认焦点必须是频道列表第一项（不是右侧导航/分类）
 if (target == null) target = findByPrefix(slot, "tv-focus-channel:");
 if (target == null && page.wide)
 target = slot.findViewWithTag("tv-focus-cat:" + page.group);
 if (target == null) target = firstFocusable(slot);
 if (target != null) {
 target.requestFocus();
 a.autoFocus = target;
 return;
 }
 if (retry) slot.postDelayed(() -> focusSelectedOrFirst(page, slot, false), 160);
 }

 /** 递归找第一个可聚焦且可见的子视图（TV 定首焦点用）。 */
 static View firstFocusable(View view) {
 if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()) return null;
 if (view.isFocusable()) return view;
 if (!(view instanceof android.view.ViewGroup)) return null;
 android.view.ViewGroup group = (android.view.ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++) {
 View found = firstFocusable(group.getChildAt(i));
 if (found != null) return found;
 }
 return null;
 }

 /** 左侧分组栏：名称 + 数量，选中项用樱花粉渐变块。 */
 static LinearLayout buildCategoryRail(
 PageState page, int cctvCount, int satCount, int webCount, int favCount, int iptvCount) {
 final MainActivity a = page.a;
 LinearLayout rail = a.vertical();
 rail.setPadding(a.dp(9), a.dp(12), a.dp(9), a.dp(12));
 rail.setBackground(MobileDetailScreen.glassBg(a, 0x14ffffff, 0x24ffffff, 22));

 TextView cap = a.text("频道分类", 10.5f, 0xff8d9cb6);
 cap.setLetterSpacing(0.1f);
 cap.setPadding(a.dp(7), 0, 0, a.dp(8));
 rail.addView(cap, new LinearLayout.LayoutParams(-1, -2));

 String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_IPTV, GROUP_WEB, GROUP_MINE};
 int[] counts = {cctvCount, satCount, iptvCount, webCount, favCount};
 for (int i = 0; i < gs.length; i++) {
 final String g = gs[i];
 boolean on = page.query.isEmpty() && g.equals(page.group);
 LinearLayout item = a.row();
 item.setGravity(Gravity.CENTER_VERTICAL);
 item.setPadding(a.dp(10), 0, a.dp(10), 0);
 // 1.9.78：选中态轻量化：浅底 + 左侧 3dp ACCENT 竖条，不再整块亮粉
 if (on) {
 GradientDrawable selectedBg = new GradientDrawable();
 selectedBg.setColor(0x1AFF8FB1);
 selectedBg.setCornerRadius(a.dp(10));
 item.setBackground(selectedBg);
 }
 MobileDetailScreen.bindTouchFeedback(item);
 item.setClickable(true);
 item.setTag("tv-focus-cat:" + g);
 ExtraPages.tvFocus(a, item, 14);
 if (on) {
 View bar = new View(a);
 bar.setBackgroundColor(0xFFFF8FB1);
 LinearLayout.LayoutParams barLp =
 new LinearLayout.LayoutParams(a.dp(3), a.dp(28));
 barLp.rightMargin = a.dp(8);
 item.addView(bar, barLp);
 }

 TextView name = a.text(gs[i], 14, on ? 0xFFFFB3C8 : 0xffd6e1f0);
 name.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
 item.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

 TextView count = a.text(String.valueOf(counts[i]), 12, on ? 0xFFFFB3C8 : 0xff8d9cb6);
 count.setTypeface(null, Typeface.BOLD);
 item.addView(count);

 item.setOnClickListener(v -> page.pickGroup(g));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(a.isTvDevice() ? 56 : 46));
 if (i > 0) lp.topMargin = a.dp(6);
 rail.addView(item, lp);
 }

 View fill = new View(a);
 rail.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));

 View divider = new View(a);
 divider.setBackgroundColor(0x1affffff);
 LinearLayout.LayoutParams divLp =
 new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
 divLp.topMargin = a.dp(10);
 divLp.bottomMargin = a.dp(6);
 rail.addView(divider, divLp);

 // 底部两个并排胶囊：＋ 添加 / 导入
 LinearLayout actions = a.row();
 LinearLayout addPill = a.row();
 addPill.setGravity(Gravity.CENTER);
 addPill.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x40ffb3c8, 999));
 MobileDetailScreen.bindTouchFeedback(addPill);
 ExtraPages.tvFocus(a, addPill, 999);
 addPill.setTag("tv-focus-live-add");
 addPill.addView(
 new TvUiKit.PlusGlyph(a, 0xffffb3c8),
 new LinearLayout.LayoutParams(a.dp(11), a.dp(11)));
 TextView addTxt = a.text("添加", 11, 0xffffb3c8);
 LinearLayout.LayoutParams addTxtLp = new LinearLayout.LayoutParams(-2, -2);
 addTxtLp.leftMargin = a.dp(4);
 addPill.addView(addTxt, addTxtLp);
 addPill.setOnClickListener(
 v -> {
 if (a.isTvDevice()) ExtraPages.degrade(a, true);
 else addChannelDialog(a, page.store, () -> page.refresh());
 });
 LinearLayout.LayoutParams addPillLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
 addPillLp.rightMargin = a.dp(6);
 actions.addView(addPill, addPillLp);

 LinearLayout impPill = a.row();
 impPill.setGravity(Gravity.CENTER);
 impPill.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(impPill);
 ExtraPages.tvFocus(a, impPill, 999);
 impPill.setTag("tv-focus-live-import");
 impPill.addView(
 new TvUiKit.ImportGlyph(a, 0xffc9d6ea),
 new LinearLayout.LayoutParams(a.dp(11), a.dp(11)));
 TextView impTxt = a.text("导入", 11, 0xffc9d6ea);
 LinearLayout.LayoutParams impTxtLp = new LinearLayout.LayoutParams(-2, -2);
 impTxtLp.leftMargin = a.dp(4);
 impPill.addView(impTxt, impTxtLp);
 impPill.setOnClickListener(
 v -> {
 if (a.isTvDevice()) ExtraPages.degrade(a, true);
 else importDialog(a, page.store);
 });
 LinearLayout.LayoutParams impPillLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
 impPillLp.rightMargin = a.dp(6);
 actions.addView(impPill, impPillLp);

 // 1.9.150：刷新开源 IPTV（清缓存重新下载）
 LinearLayout iptvPill = a.row();
 iptvPill.setGravity(Gravity.CENTER);
 iptvPill.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(iptvPill);
 ExtraPages.tvFocus(a, iptvPill, 999);
 iptvPill.setTag("tv-focus-live-iptv");
 TextView iptvTxt = a.text("开源", 11, 0xffc9d6ea);
 iptvPill.addView(iptvTxt);
 iptvPill.setOnClickListener(v -> refreshIptv(page, true));
 LinearLayout.LayoutParams iptvPillLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
 iptvPillLp.rightMargin = a.dp(6);
 actions.addView(iptvPill, iptvPillLp);

 // M2：TV 暂不提供「管理」（ChannelManagerScreen 尚未做 TV 焦点/输入适配，下轮单独处理）
 if (!a.isTvDevice()) {
 LinearLayout managePill = a.row();
 managePill.setGravity(Gravity.CENTER);
 managePill.setBackground(
 MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(managePill);
 ExtraPages.tvFocus(a, managePill, 999);
 managePill.setTag("tv-focus-live-manage");
 TextView manageTxt = a.text("管理", 11, 0xffc9d6ea);
 managePill.addView(manageTxt);
 managePill.setOnClickListener(
 v -> {
 ChannelManagerScreen.show(a);
 });
 actions.addView(managePill, new LinearLayout.LayoutParams(0, a.dp(34), 1f));
 }
 rail.addView(actions, new LinearLayout.LayoutParams(-1, a.dp(34)));

 TextView tip = a.text("长按频道可收藏", 9.5f, 0xff6f7f99);
 tip.setPadding(0, a.dp(7), 0, 0);
 rail.addView(tip, new LinearLayout.LayoutParams(-1, -2));
 return rail;
 }

 /** 中间编号频道列表（平板）或单列列表（手机）。 */
 static LinearLayout buildMiddle(PageState page, List<Channel> list, Channel chosen) {
 final MainActivity a = page.a;
 final boolean wide = page.wide;
 LinearLayout mid = a.vertical();
 mid.setBackground(MobileDetailScreen.glassBg(a, 0x10ffffff, 0x1fffffff, 22));

 LinearLayout head = a.row();
 head.setGravity(Gravity.CENTER_VERTICAL);
 head.setPadding(a.dp(14), a.dp(9), a.dp(14), a.dp(6));
 TextView h = a.text(page.query.isEmpty() ? page.group : "搜索结果", wide ? 15.5f : 15, 0xfff5f7fc);
 h.setTypeface(null, Typeface.BOLD);
 head.addView(h);
 TextView count = a.text(list.size() + " 个", 10.5f, 0xffffb3c8);
 count.setGravity(Gravity.CENTER);
 count.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
 count.setBackground(MobileDetailScreen.glassBg(a, 0x1fff8fb1, 0x33ffb3c8, 999));
 LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
 countLp.leftMargin = a.dp(8);
 head.addView(count, countLp);

 View gap = new View(a);
 head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
 TextView hint =
 a.text(page.query.isEmpty() ? "长按收藏" : "× 清空搜索", wide ? 10.5f : 11, 0xff7f8fa9);
 hint.setOnClickListener(
 v -> {
 if (page.query.isEmpty()) page.a.toast("长按频道即可收藏");
 else page.search.setText("");
 });
 head.addView(hint);
 mid.addView(head, new LinearLayout.LayoutParams(-1, -2));

 if (list.isEmpty()) {
 LinearLayout emptyBox = a.vertical();
 emptyBox.setPadding(a.dp(5), 0, a.dp(5), a.dp(10));
 final boolean iptvEmpty = GROUP_IPTV.equals(page.group) && page.query.isEmpty();
 emptyBox.addView(
 emptyState(
 a,
 !page.query.isEmpty(),
 iptvEmpty
 ? "开源 IPTV 暂无频道"
 : (page.query.isEmpty() ? "收藏夹还是空的" : "没有找到频道"),
 iptvEmpty
 ? "可能尚未下载或网络不可用，点下方按钮重新下载"
 : (page.query.isEmpty()
 ? "长按任意频道，或点列表右侧的 ，就能收藏到这里"
 : "换个关键词试试，频道名或拼音首字母都可以，例如 hnws、cctv5"),
 iptvEmpty
 ? "刷新开源 IPTV"
 : (page.query.isEmpty() ? "去央视频道看看" : "清空搜索"),
 () -> {
 if (iptvEmpty) refreshIptv(page, true);
 else if (page.query.isEmpty()) page.pickGroup(GROUP_CCTV);
 else page.search.setText("");
 }),
 new LinearLayout.LayoutParams(-1, -2));
 mid.addView(emptyBox, new LinearLayout.LayoutParams(-1, 0, 1f));
 return mid;
 }

 final int rowH = a.dp(wide ? (a.isTvDevice() ? 64 : 40) : 56);
 final RecyclerView recycler = new RecyclerView(a);
 final LinearLayoutManager layout = new LinearLayoutManager(a);
 recycler.setLayoutManager(layout);
 recycler.setClipToPadding(false);
 recycler.setPadding(a.dp(5), 0, a.dp(5), a.dp(10));
 recycler.setVerticalScrollBarEnabled(false);
 recycler.setItemAnimator(null);
 // 1.9.69：列表容器不抢焦点，子频道行可聚焦
 recycler.setFocusable(false);
 recycler.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
 recycler.setAdapter(
 new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
 @Override
 public RecyclerView.ViewHolder onCreateViewHolder(
 android.view.ViewGroup parent, int viewType) {
 FrameLayout cell = new FrameLayout(a);
 cell.setLayoutParams(
 new RecyclerView.LayoutParams(
 RecyclerView.LayoutParams.MATCH_PARENT, rowH));
 return new RecyclerView.ViewHolder(cell) {};
 }

 @Override
 public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
 FrameLayout cell = (FrameLayout) holder.itemView;
 cell.removeAllViews();
 final Channel ch = list.get(position);
 final String openGroup = page.query.isEmpty() ? page.group : ch.group;
 final boolean active = chosen != null && ch.name.equals(chosen.name);
 final boolean fav = page.store.favorites.contains(ch.name);
 cell.addView(
 channelRow(
 page,
 ch,
 position + 1,
 active,
 fav,
 () -> {
 page.selected = ch.name;
 open(
 page.a,
 page.store,
 openGroup,
 ch,
 () -> page.refresh());
 },
 () -> {
 boolean now = toggleFavorite(page.a, page.store, ch);
 page.a.toast(
 (now ? "已收藏 " : "已取消收藏 ") + ch.name);
 page.refresh();
 }),
 new FrameLayout.LayoutParams(-1, rowH));
 }

 @Override
 public int getItemCount() {
 return list.size();
 }
 });
 // 1.9.74：数据变化立即审计；滚动停止也审计
 RecyclerView.Adapter<?> liveAdapter = recycler.getAdapter();
 if (liveAdapter != null)
 liveAdapter.registerAdapterDataObserver(
 new RecyclerView.AdapterDataObserver() {
 @Override
 public void onChanged() {
 a.ui.postDelayed(() -> TvNav.audit(a, "live-data"), 80);
 }

 @Override
 public void onItemRangeInserted(int start, int count) {
 a.ui.postDelayed(() -> TvNav.audit(a, "live-data"), 80);
 }
 });
 recycler.addOnScrollListener(
 new RecyclerView.OnScrollListener() {
 @Override
 public void onScrolled(RecyclerView rv, int dx, int dy) {
 page.listScrollY = rv.computeVerticalScrollOffset();
 }
 });
 mid.addView(recycler, new LinearLayout.LayoutParams(-1, 0, 1f));

 final int savedIndex = page.listScrollY > 0 ? page.listScrollY / Math.max(1, rowH) : 0;
 int topPosition = savedIndex;
 if (page.listScrollY <= 0 && page.query.isEmpty()) {
 String lastWatched = a.prefs.getString(LAST, "");
 if (!lastWatched.isEmpty()) {
 String lastName = lastWatched.split("\n", 2)[0];
 for (int i = 0; i < list.size(); i++)
 if (list.get(i).name.equals(lastName)) {
 topPosition = Math.max(0, i - 1);
 break;
 }
 }
 }
 final int scrollTo = topPosition;
 recycler.post(() -> layout.scrollToPositionWithOffset(scrollTo, 0));
 return mid;
 }

 /** 一行频道：编号 + 台标色块 + 名称 + 直播状态/收藏。 */
 static LinearLayout channelRow(
 PageState page,
 Channel ch,
 int number,
 boolean active,
 boolean fav,
 Runnable onOpen,
 Runnable onFav) {
 final MainActivity a = page.a;
 final boolean wide = page.wide;
 final TvUiKit.Brand brand = TvUiKit.brand(ch.name);

 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(a.dp(6), 0, a.dp(8), 0);
 // 1.9.78：正在播放不再整行粉底，只用小红点标记
 MobileDetailScreen.bindTouchFeedback(row);
 // TV/被控端：频道行可遥控聚焦并有焦点态
 row.setFocusable(true);
 row.setFocusableInTouchMode(true);
 row.setClickable(true);
 row.setTag("tv-focus-channel:" + ch.name);
 TvNav.focusRing(a, row, 14);
 row.setOnClickListener(v -> onOpen.run());
 row.setOnLongClickListener(
 v -> {
 onFav.run();
 return true;
 });

 TextView num =
 a.text(
 String.format(Locale.US, "%02d", number),
 wide ? 13.5f : 13,
 active ? 0xffffd4df : 0xff6f7f99);
 num.setTypeface(null, Typeface.BOLD);
 num.setGravity(Gravity.CENTER);
 row.addView(num, new LinearLayout.LayoutParams(a.dp(30), -2));

 FrameLayout tile = new FrameLayout(a);
 int tileSize = a.dp(26);
 tile.setBackground(TvUiKit.brandBlock(a, brand, 8));
 TextView mk =
 a.text(brand.mark, brand.mark.length() >= 3 ? 8 : 10.5f, 0xfff7fbff);
 mk.setTypeface(null, Typeface.BOLD);
 mk.setGravity(Gravity.CENTER);
 tile.addView(mk, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 row.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

 String lastWatched = a.prefs.getString(LAST, "");
 if (lastWatched.startsWith(ch.name + "\n")) {
 TextView last = a.text("上次", 9, 0xff31141f);
 last.setGravity(Gravity.CENTER);
 last.setPadding(a.dp(7), 0, a.dp(7), 0);
 last.setBackground(a.bg(0xffffc7d6, 999));
 LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(-2, a.dp(18));
 lastLp.rightMargin = a.dp(6);
 row.addView(last, lastLp);
 }
 TextView name = a.text(ch.name, wide ? 15.5f : 15, active ? 0xffffe3ea : 0xffe8eff9);
 name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
 name.setSingleLine(true);
 name.setEllipsize(TextUtils.TruncateAt.END);
 LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
 nameLp.leftMargin = a.dp(9);
 row.addView(name, nameLp);

 if (active) {
 // 当前台只留一个樱花粉小圆点，避免和编号 / 名称抢横向空间
 View liveDot = TvUiKit.dot(a, 0xffff8fb1, 6);
 LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(a.dp(6), a.dp(6));
 dotLp.leftMargin = a.dp(6);
 row.addView(liveDot, dotLp);
 }

 TextView heart = a.text(fav ? "" : "", 13, fav ? 0xffff8fb1 : 0x66c9d6ea);
 heart.setGravity(Gravity.CENTER);
 heart.setOnClickListener(v -> onFav.run());
 MobileDetailScreen.bindTouchFeedback(heart);
 LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(a.dp(24), a.dp(24));
 heartLp.leftMargin = a.dp(4);
 row.addView(heart, heartLp);
 return row;
 }

 /** 右侧频道信息 / 预览面板。 */
 static LinearLayout buildPreview(PageState page, Channel chosen) {
 final MainActivity a = page.a;
 LinearLayout panel = a.vertical();
 panel.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(14));
 panel.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 24));

 if (chosen == null) {
 panel.addView(
 emptyState(
 a,
 !page.query.isEmpty(),
 page.query.isEmpty() ? "收藏夹还是空的" : "没有找到频道",
 "在左侧切换分组，或换个关键词再试",
 page.query.isEmpty() ? "去央视频道看看" : "清空搜索",
 () -> {
 if (page.query.isEmpty()) page.pickGroup(GROUP_CCTV);
 else page.search.setText("");
 }),
 new LinearLayout.LayoutParams(-1, 0, 1f));
 return panel;
 }

 final TvUiKit.Brand brand = TvUiKit.brand(chosen.name);
 final Channel sel = chosen;

 // 16:9 台标卡：横排台标字 + 右上角直播状态，代替视频预览占位
 FrameLayout hero = new FrameLayout(a);
 hero.setBackground(TvUiKit.brandBlock(a, brand, 18));
 LinearLayout logoRow = a.row();
 logoRow.setGravity(Gravity.CENTER_VERTICAL);
 if ("卫视".equals(brand.wordmark)) {
 TextView prov = a.text(brand.mark, 34, 0xfff7fbff);
 prov.setTypeface(null, Typeface.BOLD);
 prov.setIncludeFontPadding(false);
 logoRow.addView(prov);
 TextView ws = a.text("卫视", 14, 0xc9ffffff);
 ws.setTypeface(null, Typeface.BOLD);
 ws.setIncludeFontPadding(false);
 LinearLayout.LayoutParams wsLp = new LinearLayout.LayoutParams(-2, -2);
 wsLp.leftMargin = a.dp(7);
 logoRow.addView(ws, wsLp);
 } else if (brand.mark.length() <= 2) {
 TextView word = a.text(brand.wordmark, 21, 0xe6ffffff);
 word.setTypeface(null, Typeface.BOLD);
 word.setIncludeFontPadding(false);
 word.setLetterSpacing(0.14f);
 logoRow.addView(word);
 View sep = new View(a);
 sep.setBackgroundColor(0x59ffffff);
 LinearLayout.LayoutParams sepLp =
 new LinearLayout.LayoutParams(Math.max(1, a.dp(1)), a.dp(34));
 sepLp.leftMargin = a.dp(12);
 sepLp.rightMargin = a.dp(12);
 logoRow.addView(sep, sepLp);
 TextView num = a.text(brand.mark, 34, 0xffffffff);
 num.setTypeface(null, Typeface.BOLD);
 num.setIncludeFontPadding(false);
 logoRow.addView(num);
 } else {
 TextView word = a.text(brand.mark, 24, 0xfff7fbff);
 word.setTypeface(null, Typeface.BOLD);
 word.setIncludeFontPadding(false);
 logoRow.addView(word);
 }
 hero.addView(logoRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

 LinearLayout badge = a.row();
 badge.setGravity(Gravity.CENTER_VERTICAL);
 badge.setPadding(a.dp(10), a.dp(5), a.dp(11), a.dp(5));
 badge.setBackground(MobileDetailScreen.glassBg(a, 0x66060b14, 0x33ffffff, 999));
 badge.addView(
 TvUiKit.dot(a, 0xffff6f96, 6), new LinearLayout.LayoutParams(a.dp(6), a.dp(6)));
 TextView bt = a.text("直播中", 10, 0xffffd4df);
 LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-2, -2);
 btLp.leftMargin = a.dp(5);
 badge.addView(bt, btLp);
 FrameLayout.LayoutParams badgeLp =
 new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
 badgeLp.topMargin = a.dp(10);
 badgeLp.rightMargin = a.dp(10);
 hero.addView(badge, badgeLp);
 panel.addView(hero, new LinearLayout.LayoutParams(-1, a.dp(96)));

 TextView name = a.text(chosen.name, 19, 0xfff7fbff);
 name.setTypeface(null, Typeface.BOLD);
 name.setMaxLines(2);
 name.setEllipsize(TextUtils.TruncateAt.END);
 LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
 nameLp.topMargin = a.dp(12);
 panel.addView(name, nameLp);

 TextView meta =
 a.text(
 (page.query.isEmpty() ? page.group : chosen.group)
 + (page.query.isEmpty() ? "" : " · 搜索结果"),
 11.5f,
 0xffffb3c8);
 LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
 metaLp.topMargin = a.dp(5);
 panel.addView(meta, metaLp);

 TextView desc =
 a.text(
 "官方网页直播 · 首次加载稍慢\n数字键换台，上下滑调亮度 / 音量",
 11.5f,
 0xff9aa9c0);
 desc.setLineSpacing(a.dp(4), 1f);
 LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
 descLp.topMargin = a.dp(10);
 panel.addView(desc, descLp);

 // 频道信息：分类 / 编号 / 播放方式，填满面板中段留白
 View infoDivider = new View(a);
 infoDivider.setBackgroundColor(0x1affffff);
 LinearLayout.LayoutParams infoDivLp =
 new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
 infoDivLp.topMargin = a.dp(12);
 infoDivLp.bottomMargin = a.dp(3);
 panel.addView(infoDivider, infoDivLp);

 final int totalInList =
 page.query.isEmpty()
 ? page.store.group(page.group).size()
 : searchChannels(page.store, page.query).size();
 panel.addView(
 infoRow(
 a,
 "分类",
 page.query.isEmpty() ? page.group : chosen.group + " · 搜索"),
 new LinearLayout.LayoutParams(-1, -2));
 panel.addView(
 infoRow(
 a,
 "编号",
 String.format(
 Locale.US,
 "%02d / %02d",
 numberInList(page, chosen),
 totalInList)),
 new LinearLayout.LayoutParams(-1, -2));
 View fill = new View(a);
 panel.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));

 boolean fav = page.store.favorites.contains(chosen.name);
 TextView favBtn =
 a.text(fav ? " 已收藏" : " 收藏频道", 12.5f, fav ? 0xffff8fb1 : 0xffe6edf9);
 favBtn.setGravity(Gravity.CENTER);
 favBtn.setSingleLine(true);
 favBtn.setBackground(
 MobileDetailScreen.glassBg(
 a, fav ? 0x2bff8fb1 : 0x1affffff, fav ? 0x66ffb3c8 : 0x33ffffff, 999));
 MobileDetailScreen.bindTouchFeedback(favBtn);
 ExtraPages.tvFocus(a, favBtn, 999);
 favBtn.setTag("tv-focus-live-fav");
 favBtn.setOnClickListener(
 v -> {
 boolean now = toggleFavorite(page.a, page.store, sel);
 page.a.toast((now ? "已收藏 " : "已取消收藏 ") + sel.name);
 page.refresh();
 });
 LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-1, a.dp(a.isTvDevice() ? 48 : 36));
 favLp.bottomMargin = a.dp(6);
 panel.addView(favBtn, favLp);

 TextView watch = TvUiKit.pill(a, "观看直播", 14, true);
 ExtraPages.tvFocus(a, watch, 999);
 watch.setTag("tv-focus-live-watch");
 final String openGroup = page.query.isEmpty() ? page.group : sel.group;
 watch.setOnClickListener(
 v -> {
 page.selected = sel.name;
 open(page.a, page.store, openGroup, sel, () -> page.refresh());
 });
 LinearLayout.LayoutParams watchLp = new LinearLayout.LayoutParams(-1, a.dp(a.isTvDevice() ? 52 : 42));
 watchLp.bottomMargin = a.dp(4);
 panel.addView(watch, watchLp);
 return panel;
 }

 /** 信息面板的一行：左标签 + 右值。 */
 static LinearLayout infoRow(MainActivity a, String label, String value) {
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);
 row.setPadding(0, a.dp(5), 0, a.dp(5));
 TextView l = a.text(label, 10.5f, 0xff7f8fa9);
 row.addView(l);
 View gap = new View(a);
 row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
 TextView v = a.text(value, 11.5f, 0xffe6edf9);
 v.setSingleLine(true);
 v.setEllipsize(TextUtils.TruncateAt.END);
 row.addView(v);
 return row;
 }

 static int numberInList(PageState page, Channel ch) {
 List<Channel> list =
 page.query.isEmpty()
 ? page.store.group(page.group)
 : searchChannels(page.store, page.query);
 for (int i = 0; i < list.size(); i++) {
 if (list.get(i).name.equals(ch.name)) return i + 1;
 }
 return 1;
 }

 /** 空状态：圆形玻璃徽标 + 标题 + 说明 + 可选主按钮。 */
 static View emptyState(
 MainActivity a,
 boolean searchGlyph,
 String title,
 String body,
 String actionLabel,
 Runnable action) {
 LinearLayout box = a.vertical();
 box.setGravity(Gravity.CENTER_HORIZONTAL);
 box.setPadding(a.dp(14), a.dp(34), a.dp(14), a.dp(28));

 FrameLayout badge = new FrameLayout(a);
 GradientDrawable circle = new GradientDrawable();
 circle.setShape(GradientDrawable.OVAL);
 circle.setColor(0x1fff8fb1);
 circle.setStroke(Math.max(1, a.dp(1)), 0x40ffb3c8);
 badge.setBackground(circle);
 if (searchGlyph) {
 badge.addView(
 new TvUiKit.SearchGlyph(a, 0xffff8fb1),
 new FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER));
 } else {
 TextView glyph = a.text("", 24, 0xffff8fb1);
 glyph.setGravity(Gravity.CENTER);
 badge.addView(glyph, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 }
 box.addView(badge, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));

 TextView t = a.text(title, 14.5f, 0xfff5f7fc);
 t.setTypeface(null, Typeface.BOLD);
 t.setGravity(Gravity.CENTER);
 LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
 tLp.topMargin = a.dp(14);
 box.addView(t, tLp);

 TextView b = a.text(body, 11.5f, 0xff8d9cb6);
 b.setGravity(Gravity.CENTER);
 b.setLineSpacing(a.dp(3), 1f);
 LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, -2);
 bLp.topMargin = a.dp(6);
 box.addView(b, bLp);

 if (actionLabel != null && action != null) {
 TextView btn = TvUiKit.pill(a, actionLabel, 12, true);
 btn.setOnClickListener(v -> action.run());
 LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 btnLp.topMargin = a.dp(14);
 btnLp.gravity = Gravity.CENTER_HORIZONTAL;
 box.addView(btn, btnLp);
 }
 return box;
 }

 // ---------------- 搜索 / 键盘辅助 ----------------

 static List<Channel> searchChannels(Store store, String q) {
 List<Channel> out = new ArrayList<>();
 if (q == null || q.isEmpty()) return out;
 String compactQ = compact(q);
 for (Channel c : store.every()) {
 if (matchesChannel(c, q, compactQ)) out.add(c);
 }
 return out;
 }

 static boolean matchesChannel(Channel c, String q, String compactQ) {
 String name = c.name == null ? "" : c.name.toLowerCase(Locale.US);
 if (name.contains(q)) return true;
 if (compact(name).contains(compactQ)) return true;
 String initials = compact(TvUiKit.brand(c.name).initials);
 return !initials.isEmpty() && initials.contains(compactQ);
 }

 /** 去掉空格 / 连字符等分隔符，让「cctv1」也能命中「CCTV-1」。 */
 static String compact(String s) {
 if (s == null) return "";
 return s.toLowerCase(Locale.US)
 .replace("-", "")
 .replace(" ", "")
 .replace("·", "")
 .replace("+", "plus");
 }

 static Channel findChannel(List<Channel> list, String name) {
 if (name == null) return null;
 for (Channel c : list) {
 if (c.name.equals(name)) return c;
 }
 return null;
 }

 static void hideSoftKeyboard(MainActivity a, View v) {
 try {
 InputMethodManager imm =
 (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
 if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
 } catch (Throwable ignored) {
 }
 }

 static void showSoftKeyboard(MainActivity a, View v) {
 try {
 InputMethodManager imm =
 (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
 if (imm != null && v != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
 } catch (Throwable ignored) {
 }
 }

 static void refresh(MainActivity a) {
 MobileShell.show(a);
 }

 static int statusBar(MainActivity a) {
 int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
 return id > 0 ? a.getResources().getDimensionPixelSize(id) : a.dp(24);
 }

 static TextView pill(MainActivity a, String label, int color) {
 TextView v = a.text(label, 12, MainActivity.WHITE);
 v.setGravity(Gravity.CENTER);
 v.setPadding(a.dp(12), 0, a.dp(12), 0);
 v.setBackground(a.bg(color, 999));
 MobileDetailScreen.bindTouchFeedback(v);
 return v;
 }

 // ======================= 自定义 / 导入 =======================

 static void addChannelDialog(MainActivity a, Store store, Runnable done) {
 LinearLayout box = a.vertical();
 box.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 android.widget.EditText name = new android.widget.EditText(a);
 name.setHint("频道名称，如 东方卫视");
 name.setSingleLine(true);
 android.widget.EditText url = new android.widget.EditText(a);
 url.setHint("官方直播页地址 https://…");
 url.setSingleLine(true);
 box.addView(name);
 box.addView(url);
 new AppDialog.Builder(a)
 .setTitle("添加自定义频道")
 .setView(box)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "保存",
 (d, w) -> {
 String n = name.getText().toString().trim();
 String u = url.getText().toString().trim();
 if (n.isEmpty() || !Cms.isHttp(u)) {
 a.toast("请填写名称和 http(s) 地址");
 return;
 }
 store.custom.add(new Channel(n, u, GROUP_SAT));
 saveCustom(a, store.custom);
 done.run();
 })
 .show();
 }

 static void importDialog(MainActivity a, Store store) {
 android.widget.EditText input = new android.widget.EditText(a);
 input.setHint("粘贴 JSON：{\"channels\":[{\"name\":\"频道\",\"url\":\"https://…\"}]}");
 input.setMinLines(4);
 input.setGravity(Gravity.TOP | Gravity.START);
 new AppDialog.Builder(a)
 .setTitle("导入频道列表")
 .setView(input)
 .setNegativeButton("取消", null)
 .setPositiveButton(
 "导入",
 (d, w) -> {
 try {
 String text = input.getText().toString().trim();
 int added = 0;
 if (text.startsWith("{")) {
 JSONObject json = new JSONObject(text);
 JSONArray arr = json.optJSONArray("channels");
 if (arr == null) throw new IllegalArgumentException("缺少 channels");
 for (int i = 0; i < arr.length(); i++) {
 JSONObject o = arr.getJSONObject(i);
 String n = o.optString("name").trim();
 String u = o.optString("url").trim();
 if (!n.isEmpty() && Cms.isHttp(u)) {
 store.custom.add(new Channel(n, u, GROUP_SAT));
 added++;
 }
 }
 } else {
 // M3U / TXT 列表：#EXTINF 后面跟播放地址，或「频道名,地址」
 String pendingName = null;
 for (String raw : text.split("\r?\n")) {
 String line = raw.trim();
 if (line.isEmpty()) continue;
 if (line.startsWith("#EXTINF")) {
 int comma = line.lastIndexOf(',');
 pendingName =
 comma >= 0
 ? line.substring(comma + 1).trim()
 : "自定义频道";
 continue;
 }
 if (line.startsWith("#")) continue;
 if (Cms.isHttp(line)) {
 String name = pendingName != null ? pendingName : line;
 store.custom.add(new Channel(name, line, GROUP_SAT));
 added++;
 pendingName = null;
 continue;
 }
 int comma = line.indexOf(',');
 if (comma > 0) {
 String name = line.substring(0, comma).trim();
 String url = line.substring(comma + 1).trim();
 if (!name.isEmpty() && Cms.isHttp(url)) {
 store.custom.add(new Channel(name, url, GROUP_SAT));
 added++;
 }
 }
 }
 }
 saveCustom(a, store.custom);
 a.toast("已导入 " + added + " 个频道");
 refresh(a);
 } catch (Exception e) {
 a.toast("导入失败：" + e.getMessage());
 }
 })
 .show();
 }

 static void saveCustom(MainActivity a, List<Channel> channels) {
 try {
 JSONObject json = new JSONObject();
 JSONArray arr = new JSONArray();
 for (Channel c : channels)
 arr.put(new JSONObject().put("name", c.name).put("url", c.url));
 json.put("channels", arr);
 try (FileOutputStream out =
 new FileOutputStream(new File(a.getFilesDir(), CUSTOM_FILE))) {
 out.write(json.toString().getBytes(StandardCharsets.UTF_8));
 }
 } catch (Exception e) {
 AppLog.w("TvLive", "save channels failed", e);
 }
 }

 static List<Channel> readWebLive(MainActivity a) {
 List<Channel> list = new ArrayList<>();
 try {
 String raw = a.prefs.getString("weblive_sources", "[]");
 org.json.JSONArray array = new org.json.JSONArray(raw);
 for (int i = 0; i < array.length(); i++) {
 org.json.JSONObject item = array.optJSONObject(i);
 if (item == null) continue;
 String name = item.optString("name", "").trim();
 String url = item.optString("url", "").trim();
 if (!name.isEmpty() && !url.isEmpty())
 list.add(new Channel(name, url, GROUP_WEB));
 }
 } catch (Throwable ignored) {
 }
 return list;
 }

 static void addWebLive(MainActivity a, String name, String url) {
 try {
 org.json.JSONArray array =
 new org.json.JSONArray(a.prefs.getString("weblive_sources", "[]"));
 org.json.JSONArray next = new org.json.JSONArray();
 org.json.JSONObject item = new org.json.JSONObject();
 item.put("name", name);
 item.put("url", url);
 next.put(item);
 for (int i = 0; i < array.length(); i++) next.put(array.opt(i));
 a.prefs.edit().putString("weblive_sources", next.toString()).apply();
 AppLog.i("WebLive", "add name=" + name + " total=" + next.length());
 } catch (Throwable ignored) {
 }
 }

 static void removeWebLive(MainActivity a, String name) {
 try {
 org.json.JSONArray array =
 new org.json.JSONArray(a.prefs.getString("weblive_sources", "[]"));
 org.json.JSONArray next = new org.json.JSONArray();
 for (int i = 0; i < array.length(); i++) {
 org.json.JSONObject item = array.optJSONObject(i);
 if (item != null && name.equals(item.optString("name", ""))) continue;
 next.put(array.opt(i));
 }
 a.prefs.edit().putString("weblive_sources", next.toString()).apply();
 } catch (Throwable ignored) {
 }
 }

 static void addWebLiveDialog(final MainActivity a, final Runnable done) {
 EditText nameInput = new EditText(a);
 nameInput.setHint("名称，如 深圳卫视");
 nameInput.setTextColor(a.userTextColor(0xfff5f7fc));
 nameInput.setHintTextColor(a.themeColor(0xff7f8fa9));
 EditText urlInput = new EditText(a);
 urlInput.setHint("https://… 网页地址");
 urlInput.setTextColor(a.userTextColor(0xfff5f7fc));
 urlInput.setHintTextColor(a.themeColor(0xff7f8fa9));
 LinearLayout box = a.vertical();
 box.addView(nameInput);
 box.addView(urlInput);
 new AppDialog.Builder(a)
 .setTitle("添加网页直播")
 .setView(box)
 .setPositiveButton(
 "保存",
 (d, i) -> {
 String name = nameInput.getText().toString().trim();
 String url = urlInput.getText().toString().trim();
 if (name.isEmpty() || !url.startsWith("http")) {
 a.toast("请填写名称和 http(s) 地址");
 return;
 }
 addWebLive(a, name, url);
 a.toast("已添加");
 if (done != null) done.run();
 })
 .setNegativeButton("取消", null)
 .show();
 }

 static List<Channel> readCustom(MainActivity a) {
 List<Channel> list = new ArrayList<>();
 File f = new File(a.getFilesDir(), CUSTOM_FILE);
 if (!f.isFile()) return list;
 try (InputStream in = new java.io.FileInputStream(f)) {
 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
 byte[] buf = new byte[4096];
 int n;
 while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
 JSONObject json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
 JSONArray arr = json.optJSONArray("channels");
 if (arr != null)
 for (int i = 0; i < arr.length(); i++) {
 JSONObject o = arr.getJSONObject(i);
 list.add(new Channel(o.optString("name"), o.optString("url"), GROUP_SAT));
 }
 } catch (Exception ignored) {
 }
 return list;
 }

 /** 内置频道地址覆盖（用户手动改成别的直播地址）。 */
 static Map<String, String> readUrlOverrides(MainActivity a) {
 Map<String, String> map = new java.util.LinkedHashMap<>();
 try {
 JSONObject json = new JSONObject(a.prefs.getString(URL_OVERRIDES, "{}"));
 java.util.Iterator<String> it = json.keys();
 while (it.hasNext()) {
 String key = it.next();
 map.put(key, json.optString(key));
 }
 } catch (Exception ignored) {
 }
 return map;
 }

 static void saveUrlOverrides(MainActivity a, Map<String, String> map) {
 try {
 JSONObject json = new JSONObject();
 for (Map.Entry<String, String> e : map.entrySet()) json.put(e.getKey(), e.getValue());
 a.prefs.edit().putString(URL_OVERRIDES, json.toString()).apply();
 } catch (Exception ignored) {
 }
 }

 static String defaultUrl(String name) {
 for (Object[] row : CCTV) if (row[0].equals(name)) return (String) row[1];
 for (Object[] row : SAT) if (row[0].equals(name)) return (String) row[1];
 return "";
 }

 /** 被隐藏的内置频道（名字集合）。 */
 static Set<String> readHidden(MainActivity a) {
 Set<String> set = new LinkedHashSet<>();
 for (String s : a.prefs.getString(HIDDEN, "").split("\n"))
 if (!s.isEmpty()) set.add(s);
 return set;
 }

 static void saveHidden(MainActivity a, Set<String> hidden) {
 StringBuilder sb = new StringBuilder();
 for (String s : hidden) sb.append(s).append('\n');
 a.prefs.edit().putString(HIDDEN, sb.toString()).apply();
 }

 static boolean isBuiltin(String name) {
 for (Object[] row : CCTV) if (row[0].equals(name)) return true;
 for (Object[] row : SAT) if (row[0].equals(name)) return true;
 return false;
 }

 /** 切换收藏状态：先改内存（store），再按原格式落盘，返回是否已收藏。 */
 static boolean toggleFavorite(MainActivity a, Store store, Channel c) {
 boolean fav;
 if (store.favorites.remove(c.name)) {
 fav = false;
 } else {
 store.favorites.add(c.name);
 fav = true;
 }
 StringBuilder sb = new StringBuilder();
 for (String s : store.favorites) sb.append(s).append('\n');
 a.prefs.edit().putString(FAVORITES, sb.toString()).apply();
 return fav;
 }

 static Set<String> readFavorites(MainActivity a) {
 Set<String> set = new LinkedHashSet<>();
 for (String s : a.prefs.getString(FAVORITES, "").split("\n"))
 if (!s.isEmpty()) set.add(s);
 return set;
 }

 // ======================= 播放器 =======================

 static void open(MainActivity a, Store store, String group, Channel channel) {
 open(a, store, group, channel, null);
 }

 static void open(
 MainActivity a, Store store, String group, Channel channel, Runnable onClosed) {
 a.prefs.edit().putString(LAST, channel.name + "\n" + channel.url).apply();
 // 1.9.150：开源 IPTV 用内置 ExoPlayer 原生播放；同名频道互为备用源
 if (GROUP_IPTV.equals(group)) {
 java.util.List<String> urls = new java.util.ArrayList<>();
 for (Channel c : store.every())
 if (GROUP_IPTV.equals(c.group) && c.name.equals(channel.name) && !urls.contains(c.url))
 urls.add(c.url);
 if (urls.isEmpty()) urls.add(channel.url);
 final java.util.List<String> playlist = urls;
 AppLog.i(
 "IptvOrg",
 "open channel=" + channel.name + " url=" + channel.url + " fallbacks=" + urls.size());
 a.open(
 new MainActivity.Screen(
 () -> NativeLiveScreen.show(a, channel.name, playlist)),
 true);
 if (onClosed != null) onClosed.run();
 return;
 }
 // 1.9.87：网页直播分组在旧 TV 上优先原生 HLS；无原生流则明确提示，不打开假页面
 if (GROUP_WEB.equals(group)) {
 if (webLiveRestricted(a)) {
 String nativeUrl = nativeWebLiveUrl(channel);
 if (nativeUrl != null) {
 AppLog.i(
 "TvLive",
 "weblive native=yes channel=" + channel.name + " url=" + nativeUrl);
 a.open(
 new MainActivity.Screen(
 () -> NativeLiveScreen.show(a, channel.name, nativeUrl)),
 true);
 if (onClosed != null) onClosed.run();
 return;
 }
 AppLog.i("TvLive", "weblive native=no channel=" + channel.name);
 showWebLiveUnsupported(a, channel.name);
 if (onClosed != null) onClosed.run();
 return;
 }
 WebLiveScreen.show(a, channel.name, channel.url);
 if (onClosed != null) onClosed.run();
 return;
 }
 Player player = new Player(a, store, group, channel);
 player.onClosed = onClosed;
 player.show();
 }

 /** 旧 TV / 老 WebView 才限制网页 HLS；新系统继续网页播放。 */
 static boolean webLiveRestricted(MainActivity a) {
 if (a == null || !a.isTvDevice()) return false;
 if (Build.VERSION.SDK_INT < 26) return true;
 try {
 String ua = android.webkit.WebSettings.getDefaultUserAgent(a);
 java.util.regex.Matcher m =
 java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua == null ? "" : ua);
 if (m.find()) return Integer.parseInt(m.group(1)) < 70;
 } catch (Throwable ignored) {
 }
 return false;
 }

 /** 已知可原生播放的深圳频道 m3u8；其它深圳频道返回 null。 */
 static String nativeWebLiveUrl(Channel channel) {
 // 1.9.88：原公开流已失效（404），旧系统统一提示，不再进黑屏
 return null;
 }

 private static void showWebLiveUnsupported(MainActivity a, String name) {
 new AppDialog.Builder(a)
 .setTitle("网页直播暂不支持")
 .setMessage(
 "该频道为官方网页直播，当前电视系统 WebView 不支持 HLS 播放。"
 + "\n\n请使用上方央视 / 卫视直播源，或换用其它频道。")
 .setPositiveButton("知道了", null)
 .show();
 }

 static final class Player {
 /** chrome 无操作 3 秒后自动淡出，点屏幕再唤出。 */
 static final long CHROME_HIDE_MS = 3000L;

 final MainActivity a;
 final Store store;
 List<Channel> list;
 String group;
 String playingName;
 int index;
 FrameLayout overlay;
 int previousOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
 TvWebView web;
 FrameLayout chrome;
 LinearLayout topBar;
 TextView title;
 TextView brandMiniMark;
 FrameLayout brandMini;
 LinearLayout infoPanel;
 TextView infoSize;
 TextView infoTime;
 TextView infoFav;
 LinearLayout bottomPanel;
 LinearLayout groupStrip;
 LinearLayout channelStrip;
 HorizontalScrollView channelScroll;
 View currentChip;
 LinearLayout loadingCard;
 TextView loadingTitle;
 TextView loadingSub;
 WhaleLoadingView loadingWhale;
 boolean panelOpen;
 boolean chromeShown = true;
 int chromeGen;
 int loadingGen;
 /** 注入脚本是否已启动（单例循环，避免多次进度/页面回调叠加出多条 self-post 循环）。 */
 boolean injectStarted;
 Runnable onClosed;
 View fullView;
 WebChromeClient.CustomViewCallback fullCallback;
 final Handler handler = new Handler(Looper.getMainLooper());
 final Runnable hideChromeRunnable = () -> fadeOutChrome();
 final Runnable clockTick =
 new Runnable() {
 @Override
 public void run() {
 // 窄屏没有信息面板，直接不排下一轮
 if (infoTime == null) return;
 infoTime.setText(
 android.text.format.DateFormat.format(
 "HH:mm", System.currentTimeMillis())
 .toString());
 handler.postDelayed(this, 20000);
 }
 };
 final Runnable inject =
 new Runnable() {
 @Override
 public void run() {
 if (!injectStarted || web == null) return;
 web.evaluateJavascript(INJECT_JS + fitJs(), null);
 // 探针：页面内 video 真的在播就收掉加载浮层（不依赖 onPageFinished）
 web.evaluateJavascript(
 PLAYING_JS,
 value -> {
 if ("true".equals(value)) hideLoading();
 });
 handler.postDelayed(this, 1800);
 }
 };

 /** 注入脚本单例启动：已有循环在跑就不再叠加。 */
 void startInject() {
 if (injectStarted) return;
 injectStarted = true;
 handler.post(inject);
 }

 Player(MainActivity a, Store store, String group, Channel channel) {
 this.a = a;
 this.store = store;
 this.group = group;
 this.list = store.group(group);
 if (this.list.isEmpty()) {
 this.group = GROUP_CCTV;
 this.list = store.group(GROUP_CCTV);
 }
 this.playingName = channel.name;
 this.index = Math.max(0, indexOf(this.list, channel.name));
 }

 static int indexOf(List<Channel> list, String name) {
 for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
 return 0;
 }

 void show() {
 overlay = new FrameLayout(a);
 overlay.setBackgroundColor(0xff05070c);
 overlay.setFocusable(true);
 overlay.setFocusableInTouchMode(true);

 web = new TvWebView(a);
 WebSettings s = web.getSettings();
 s.setJavaScriptEnabled(true);
 s.setDomStorageEnabled(true);
 s.setMediaPlaybackRequiresUserGesture(false);
 s.setUseWideViewPort(true);
 s.setLoadWithOverviewMode(true);
 s.setUserAgentString(UA);
 if (Build.VERSION.SDK_INT >= 21)
 s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
 web.setWebChromeClient(
 new WebChromeClient() {
 @Override
 public void onProgressChanged(WebView v, int progress) {
 if (loadingSub != null && progress > 0 && progress < 100) {
 loadingSub.setText("正在加载官方页面 " + progress + "%");
 }
 if (progress >= 100) hideLoading();
 if (progress > 80) startInject();
 }

 @Override
 public void onShowCustomView(View view, CustomViewCallback callback) {
 if (fullView != null) callback.onCustomViewHidden();
 fullView = view;
 fullCallback = callback;
 overlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
 if (chrome != null) {
 handler.removeCallbacks(hideChromeRunnable);
 chrome.animate().cancel();
 chrome.setVisibility(View.GONE);
 chromeShown = false;
 }
 // 网页原生全屏 = 已经在播，收掉加载浮层
 hideLoading();
 }

 @Override
 public void onHideCustomView() {
 if (fullView != null && fullView.getParent() instanceof FrameLayout)
 ((FrameLayout) fullView.getParent()).removeView(fullView);
 fullView = null;
 if (fullCallback != null) {
 fullCallback.onCustomViewHidden();
 fullCallback = null;
 }
 showChrome();
 }
 });
 web.setWebViewClient(
 new WebViewClient() {
 @Override
 public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
 injectStarted = false;
 showLoading(playingName);
 }

 @Override
 public void onPageFinished(WebView v, String url) {
 hideLoading();
 if (infoSize != null && web != null) {
 infoSize.setText(web.getWidth() + " × " + web.getHeight());
 }
 startInject();
 handler.postDelayed(Player.this::pressFullscreenKey, 1800);
 handler.postDelayed(Player.this::pressFullscreenKey, 4500);
 }
 });
 overlay.addView(web, new FrameLayout.LayoutParams(-1, -1));

 // chrome 容器：顶栏 + 右侧信息面板 + 底部频道条，随 3 秒计时整体淡出
 boolean widePlayer =
 a.windowWidth() / a.getResources().getDisplayMetrics().density >= 840f;

 loadingCard = buildLoadingCard();
 overlay.addView(
 loadingCard, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

 chrome = new FrameLayout(a);
 topBar = buildTopBar();
 FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
 topLp.setMargins(
 a.dp(10), MobileDetailScreen.getStatusBarHeight(a) + a.dp(8), a.dp(10), 0);
 chrome.addView(topBar, topLp);

 if (widePlayer) {
 infoPanel = buildInfoPanel();
 // 右侧信息面板放进 ScrollView：横屏高度不足时可滚动，底部「收藏频道」不再被裁掉；
 // 上下按安全边 + 导航条 inset 留白（不小于 16dp）。
 ScrollView infoScroll = new ScrollView(a);
 infoScroll.setVerticalScrollBarEnabled(false);
 infoScroll.setClipToPadding(false);
 infoScroll.addView(infoPanel, new ScrollView.LayoutParams(-1, -2));
 FrameLayout.LayoutParams infoLp =
 new FrameLayout.LayoutParams(
 a.dp(172), -1, Gravity.TOP | Gravity.END);
 infoLp.setMargins(
 0,
 MobileDetailScreen.getStatusBarHeight(a) + a.dp(56),
 a.dp(10),
 navigationBarHeight() + a.dp(16));
 chrome.addView(infoScroll, infoLp);
 }

 bottomPanel = buildBottomPanel();
 FrameLayout.LayoutParams panelLp =
 new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
 panelLp.setMargins(a.dp(10), 0, a.dp(10), a.dp(16));
 chrome.addView(bottomPanel, panelLp);
 bottomPanel.setVisibility(View.GONE);

 overlay.addView(chrome, new FrameLayout.LayoutParams(-1, -1));

 // 画面尺寸跟随 WebView 实际像素尺寸
 web.addOnLayoutChangeListener(
 (v, l, t, r, b, ol, ot, orr, ob) -> {
 if (infoSize != null && web != null) {
 infoSize.setText(web.getWidth() + " × " + web.getHeight());
 }
 });

 overlay.setOnKeyListener(
 (v, code, event) -> {
 if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
 int digit = code - KeyEvent.KEYCODE_0;
 if (digit >= 0 && digit <= 9 && !list.isEmpty()) {
 int target = digit == 0 ? 9 : digit - 1;
 if (target < list.size()) {
 tune(target);
 return true;
 }
 }
 return false;
 });

 a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
 MobileShell.setDockVisible(false);
 // 先登记 tvPlayer 再请求转屏：保证 onConfigurationChanged 按「直播播放中」处理（只 relayout）
 a.tvPlayer = this;
 if (a.formFactor == TvLayoutPolicy.FORM_PHONE) {
 previousOrientation = a.getRequestedOrientation();
 a.setRequestedOrientation(
 android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
 }
 overlay.requestFocus();
 load();
 handler.postDelayed(this::startInject, 2500);
 showChrome();
 handler.post(clockTick);
 }

 /** 旋转/尺寸变化：直播 overlay 跟随新窗口，不重建页面（由 onConfigurationChanged 调用）。 */
 void relayout() {
 if (overlay != null) overlay.requestLayout();
 if (web != null) {
 web.requestLayout();
 if (infoSize != null) infoSize.setText(web.getWidth() + " × " + web.getHeight());
 }
 }

 void tune(int idx) {
 if (idx < 0 || idx >= list.size()) return;
 index = idx;
 playingName = list.get(idx).name;
 refreshTopChrome();
 rebuildChannelChips();
 load();
 showLoading(playingName);
 if (panelOpen) {
 panelOpen = false;
 if (bottomPanel != null) bottomPanel.setVisibility(View.GONE);
 }
 showChrome();
 }

 /** 向网页发送 F 键，触发 CCTV / 芒果等网页播放器的全屏（参考 WebViewTvLive）。 */
 /** 按设置生成画面比例/填充模式的 JS（object-fit + aspect-ratio）。 */
 String fitJs() {
 int aspect = a.prefs.getInt(Prefs.TV_ASPECT, 0);
 int fill = a.prefs.getInt(Prefs.TV_FILL, 0);
 String fit = fill == 1 ? "cover" : fill == 2 ? "fill" : "contain";
 String ratio = aspect == 1 ? "16/9" : aspect == 2 ? "4/3" : "";
 return ";(function(){var v=document.querySelector('video');if(!v)return;"
 + "v.style.objectFit='"
 + fit
 + "';"
 + (ratio.isEmpty()
 ? "v.style.aspectRatio='';"
 : "v.style.aspectRatio='" + ratio + "';")
 + "})();";
 }

 void pressFullscreenKey() {
 if (web == null) return;
 try {
 long now = android.os.SystemClock.uptimeMillis();
 web.dispatchKeyEvent(
 new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0));
 web.dispatchKeyEvent(
 new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0));
 } catch (Throwable ignored) {
 }
 }

 void load() {
 if (list.isEmpty()) return;
 injectStarted = false;
 handler.removeCallbacks(inject);
 String url = list.get(index).url;
 web.loadUrl(url);
 handler.postDelayed(this::startInject, 2500);
 }

 // ---------------- chrome（顶栏 / 信息面板 / 底部频道条） ----------------

 LinearLayout buildTopBar() {
 LinearLayout top = a.row();
 top.setGravity(Gravity.CENTER_VERTICAL);
 top.setPadding(a.dp(8), a.dp(7), a.dp(8), a.dp(7));
 top.setBackground(MobileDetailScreen.glassBg(a, 0x99000000, 0x40ffffff, 20));
 top.setClickable(true);
 top.setOnClickListener(v -> toggleChrome());

 TextView back = a.text("‹", 24, Color.WHITE);
 back.setGravity(Gravity.CENTER);
 back.setPadding(0, 0, a.dp(2), a.dp(2));
 GradientDrawable backBg = new GradientDrawable();
 backBg.setShape(GradientDrawable.OVAL);
 backBg.setColor(0x33ffffff);
 backBg.setStroke(Math.max(1, a.dp(0.8f)), 0x4dffffff);
 back.setBackground(backBg);
 MobileDetailScreen.bindTouchFeedback(back);
 back.setOnClickListener(v -> close());
 top.addView(back, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));

 TvUiKit.Brand brand = TvUiKit.brand(playingName);
 brandMini = new FrameLayout(a);
 brandMini.setBackground(TvUiKit.brandBlock(a, brand, 11));
 brandMiniMark = a.text(brand.mark, brand.mark.length() >= 3 ? 10.5f : 15, 0xfff7fbff);
 brandMiniMark.setTypeface(null, Typeface.BOLD);
 brandMiniMark.setGravity(Gravity.CENTER);
 brandMiniMark.setIncludeFontPadding(false);
 brandMini.addView(
 brandMiniMark, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
 miniLp.leftMargin = a.dp(9);
 top.addView(brandMini, miniLp);

 LinearLayout titleCol = a.vertical();
 titleCol.setPadding(a.dp(10), 0, a.dp(8), 0);
 title = a.text(playingName, 14.5f, Color.WHITE);
 title.setTypeface(null, Typeface.BOLD);
 title.setSingleLine(true);
 title.setEllipsize(TextUtils.TruncateAt.END);
 titleCol.addView(title, new LinearLayout.LayoutParams(-1, -2));

 LinearLayout liveRow = a.row();
 liveRow.setGravity(Gravity.CENTER_VERTICAL);
 liveRow.addView(
 TvUiKit.dot(a, 0xffff6f96, 5),
 new LinearLayout.LayoutParams(a.dp(5), a.dp(5)));
 TextView live = a.text("直播中", 10, 0xffffb3c8);
 live.setTypeface(null, Typeface.BOLD);
 LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(-2, -2);
 liveLp.leftMargin = a.dp(4);
 liveRow.addView(live, liveLp);
 liveRow.addView(a.text(" · 官方网页直播", 10, 0x8ca9b7d0));
 titleCol.addView(liveRow, new LinearLayout.LayoutParams(-1, -2));
 top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

 TextView listBtn = TvUiKit.pill(a, "频道", 11.5f, false);
 listBtn.setOnClickListener(v -> toggleChannelPanel());
 LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-2, a.dp(32));
 listLp.rightMargin = a.dp(6);
 top.addView(listBtn, listLp);

 TextView refreshBtn = TvUiKit.pill(a, "刷新", 11.5f, false);
 refreshBtn.setOnClickListener(
 v -> {
 load();
 showChrome();
 });
 LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-2, a.dp(32));
 refreshLp.rightMargin = a.dp(6);
 top.addView(refreshBtn, refreshLp);

 TextView fullBtn = TvUiKit.pill(a, "全屏", 11.5f, false);
 fullBtn.setOnClickListener(
 v -> {
 pressFullscreenKey();
 showChrome();
 a.toast("已向网页发送全屏请求");
 });
 top.addView(fullBtn, new LinearLayout.LayoutParams(-2, a.dp(32)));
 return top;
 }

 /** 系统导航条高度（手势导航可能为 0），用于右侧信息面板的底部安全 inset。 */
 int navigationBarHeight() {
 try {
 int resId =
 a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
 if (resId > 0) return a.getResources().getDimensionPixelSize(resId);
 } catch (Throwable ignored) {
 }
 return a.dp(24);
 }

 LinearLayout buildInfoPanel() {
 LinearLayout panel = a.vertical();
 panel.setPadding(a.dp(13), a.dp(13), a.dp(13), a.dp(12));
 panel.setBackground(MobileDetailScreen.glassBg(a, 0xf20b1220, 0x40ffffff, 20));
 panel.setClickable(true);
 panel.setOnClickListener(v -> showChrome());

 panel.addView(label("视频源"));
 LinearLayout sources = a.row();
 for (int i = 1; i <= 3; i++) {
 final int n = i;
 TextView item = a.text(String.valueOf(i), 14, n == 1 ? 0xffffb3c8 : 0xff9aa9c0);
 item.setTypeface(null, Typeface.BOLD);
 item.setGravity(Gravity.CENTER);
 item.setOnClickListener(
 v -> a.toast(n == 1 ? "当前使用官方网页直播源" : "该频道暂无备用官方源"));
 sources.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 }
 panel.addView(sources, new LinearLayout.LayoutParams(-1, -2));

 panel.addView(label("画面比例"));
 LinearLayout ratios = a.row();
 final String[] ratioNames = {"自适应", "16:9", "4:3"};
 final int[] ratioValues = {0, 1, 2};
 final TextView[] ratioViews = new TextView[ratioNames.length];
 for (int i = 0; i < ratioNames.length; i++) {
 final int value = ratioValues[i];
 boolean on = a.prefs.getInt(Prefs.TV_ASPECT, 0) == value;
 TextView item = a.text(ratioNames[i], 12, on ? 0xffffb3c8 : 0xff9aa9c0);
 item.setTypeface(null, Typeface.BOLD);
 item.setGravity(Gravity.CENTER);
 ratioViews[i] = item;
 item.setOnClickListener(
 v -> {
 a.prefs.edit().putInt(Prefs.TV_ASPECT, value).apply();
 for (int k = 0; k < ratioViews.length; k++)
 ratioViews[k].setTextColor(
 k == value ? 0xffffb3c8 : a.userTextColor(0xff9aa9c0));
 if (web != null) web.evaluateJavascript(fitJs(), null);
 });
 ratios.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 }
 panel.addView(ratios, new LinearLayout.LayoutParams(-1, -2));

 panel.addView(label("填充模式"));
 LinearLayout fills = a.row();
 final String[] fillNames = {"适应", "裁剪", "拉伸"};
 final TextView[] fillViews = new TextView[fillNames.length];
 for (int i = 0; i < fillNames.length; i++) {
 final int value = i;
 boolean on = a.prefs.getInt(Prefs.TV_FILL, 0) == value;
 TextView item = a.text(fillNames[i], 12, on ? 0xffffb3c8 : 0xff9aa9c0);
 item.setTypeface(null, Typeface.BOLD);
 item.setGravity(Gravity.CENTER);
 fillViews[i] = item;
 item.setOnClickListener(
 v -> {
 a.prefs.edit().putInt(Prefs.TV_FILL, value).apply();
 for (int k = 0; k < fillViews.length; k++)
 fillViews[k].setTextColor(
 k == value ? 0xffffb3c8 : a.userTextColor(0xff9aa9c0));
 if (web != null) web.evaluateJavascript(fitJs(), null);
 });
 fills.addView(item, new LinearLayout.LayoutParams(0, a.dp(24), 1f));
 }
 panel.addView(fills, new LinearLayout.LayoutParams(-1, -2));

 panel.addView(label("画面尺寸"));
 infoSize = a.text("--", 12, 0xffe6edf9);
 panel.addView(infoSize, new LinearLayout.LayoutParams(-1, -2));

 View divider = new View(a);
 divider.setBackgroundColor(0x1affffff);
 LinearLayout.LayoutParams divLp =
 new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f)));
 divLp.topMargin = a.dp(10);
 divLp.bottomMargin = a.dp(8);
 panel.addView(divider, divLp);

 TextView fav = a.text(" 收藏频道", 11.5f, 0xffe6edf9);
 fav.setPadding(a.dp(2), 0, 0, 0);
 fav.setOnClickListener(
 v -> {
 if (list.isEmpty()) return;
 Channel c = list.get(Math.max(0, Math.min(index, list.size() - 1)));
 boolean now = toggleFavorite(a, store, c);
 fav.setText(now ? " 已收藏" : " 收藏频道");
 fav.setTextColor(now ? 0xffff8fb1 : a.userTextColor(0xffe6edf9));
 a.toast((now ? "已收藏 " : "已取消收藏 ") + c.name);
 refreshTopChrome();
 });
 infoFav = fav;
 panel.addView(fav, new LinearLayout.LayoutParams(-1, a.dp(30)));

 infoTime = a.text("", 11, 0xff9aa9c0);
 panel.addView(infoTime, new LinearLayout.LayoutParams(-1, -2));
 return panel;
 }

 LinearLayout buildBottomPanel() {
 LinearLayout panel = a.vertical();
 panel.setPadding(a.dp(10), a.dp(9), a.dp(10), a.dp(10));
 panel.setBackground(MobileDetailScreen.glassBg(a, 0xf20b1220, 0x40ffffff, 22));
 panel.setClickable(true);
 panel.setOnClickListener(v -> showChrome());

 LinearLayout head = a.row();
 head.setGravity(Gravity.CENTER_VERTICAL);
 groupStrip = a.row();
 head.addView(groupStrip, new LinearLayout.LayoutParams(0, -2, 1f));
 head.addView(a.text("数字键 1–9 换台", 10, 0x99a9b7d0));
 TextView collapse = TvUiKit.pill(a, "收起", 10.5f, false);
 collapse.setOnClickListener(v -> toggleChannelPanel());
 LinearLayout.LayoutParams collapseLp = new LinearLayout.LayoutParams(-2, a.dp(28));
 collapseLp.leftMargin = a.dp(8);
 head.addView(collapse, collapseLp);
 panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

 channelScroll = new HorizontalScrollView(a);
 channelScroll.setHorizontalScrollBarEnabled(false);
 channelStrip = a.row();
 channelScroll.addView(channelStrip);
 LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-1, -2);
 csLp.topMargin = a.dp(8);
 panel.addView(channelScroll, csLp);

 rebuildGroupChips();
 rebuildChannelChips();
 return panel;
 }

 LinearLayout buildLoadingCard() {
 LinearLayout card = a.vertical();
 card.setGravity(Gravity.CENTER_HORIZONTAL);
 card.setPadding(a.dp(24), a.dp(18), a.dp(24), a.dp(18));
 card.setBackground(MobileDetailScreen.glassBg(a, 0xf20b1220, 0x40ffffff, 22));
 card.setElevation(a.dp(8));
 // 1.8.3：直播连接卡片改用通用圆形帧动画（小尺寸简洁模式）
 loadingWhale = new WhaleLoadingView(a, WhaleLoadingView.SMALL, true);
 loadingWhale.setText("正在连接…");
 card.addView(loadingWhale, new LinearLayout.LayoutParams(-2, -2));

 loadingTitle = a.text("正在连接 " + playingName, 13, 0xfff5f7fc);
 loadingTitle.setTypeface(null, Typeface.BOLD);
 loadingTitle.setGravity(Gravity.CENTER);
 LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(-2, -2);
 ltLp.topMargin = a.dp(12);
 card.addView(loadingTitle, ltLp);

 loadingSub = a.text("官方网页直播 · 首次加载稍慢", 10.5f, 0xff9aa9c0);
 loadingSub.setGravity(Gravity.CENTER);
 LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-2, -2);
 lsLp.topMargin = a.dp(5);
 card.addView(loadingSub, lsLp);
 return card;
 }

 TextView label(String text) {
 TextView t = a.text(text, 10, 0xff8d9cb6);
 t.setLetterSpacing(0.08f);
 t.setPadding(0, a.dp(10), 0, a.dp(3));
 return t;
 }

 void rebuildGroupChips() {
 if (groupStrip == null) return;
 groupStrip.removeAllViews();
 String[] gs = {GROUP_CCTV, GROUP_SAT, GROUP_WEB, GROUP_MINE};
 String[] shortNames = {"央视", "卫视", "网页", "收藏"};
 for (int i = 0; i < gs.length; i++) {
 final String g = gs[i];
 boolean on = g.equals(group);
 TextView chip = a.text(shortNames[i], 11, on ? 0xff31141f : 0xffc9d6ea);
 chip.setGravity(Gravity.CENTER);
 chip.setPadding(a.dp(10), 0, a.dp(10), 0);
 if (on) {
 chip.setTypeface(null, Typeface.BOLD);
 chip.setBackground(TvUiKit.accentFill(a, 999));
 } else {
 chip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 }
 chip.setOnClickListener(v -> switchGroup(g));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(28));
 lp.rightMargin = a.dp(6);
 groupStrip.addView(chip, lp);
 }
 }

 void rebuildChannelChips() {
 if (channelStrip == null) return;
 channelStrip.removeAllViews();
 currentChip = null;
 for (int i = 0; i < list.size(); i++) {
 final int idx = i;
 Channel c = list.get(i);
 boolean on = i == index;
 TvUiKit.Brand brand = TvUiKit.brand(c.name);

 LinearLayout chip = a.row();
 chip.setGravity(Gravity.CENTER_VERTICAL);
 chip.setPadding(a.dp(6), a.dp(4), a.dp(12), a.dp(4));
 chip.setBackground(
 on
 ? TvUiKit.accentFill(a, 999)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x2effffff, 999));
 MobileDetailScreen.bindTouchFeedback(chip);

 FrameLayout tile = new FrameLayout(a);
 tile.setBackground(TvUiKit.brandBlock(a, brand, 7));
 TextView mk = a.text(brand.mark, brand.mark.length() >= 3 ? 7 : 9, 0xfff7fbff);
 mk.setTypeface(null, Typeface.BOLD);
 mk.setGravity(Gravity.CENTER);
 tile.addView(mk, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 LinearLayout.LayoutParams tileLp =
 new LinearLayout.LayoutParams(a.dp(22), a.dp(22));
 tileLp.rightMargin = a.dp(6);
 chip.addView(tile, tileLp);

 TextView name = a.text(c.name, 11.5f, on ? 0xff31141f : 0xffe6edf9);
 name.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
 name.setSingleLine(true);
 name.setEllipsize(TextUtils.TruncateAt.END);
 name.setMaxWidth(a.dp(120));
 chip.addView(name);

 chip.setOnClickListener(v -> tune(idx));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
 lp.rightMargin = a.dp(7);
 channelStrip.addView(chip, lp);
 if (on) currentChip = chip;
 }
 }

 void switchGroup(String g) {
 List<Channel> next = store.group(g);
 if (next.isEmpty()) {
 a.toast("还没有收藏频道");
 return;
 }
 group = g;
 list = next;
 boolean stillHere = indexOf(list, playingName) > 0 || list.get(0).name.equals(playingName);
 if (stillHere) {
 index = indexOf(list, playingName);
 rebuildGroupChips();
 rebuildChannelChips();
 } else {
 // 当前台不在新分组：切到该分组第一个频道，避免顶栏 / 列表 / 网页三者不一致
 index = 0;
 playingName = list.get(0).name;
 refreshTopChrome();
 rebuildGroupChips();
 rebuildChannelChips();
 load();
 showLoading(playingName);
 }
 scrollToCurrentChip();
 showChrome();
 }

 void scrollToCurrentChip() {
 if (channelScroll == null || currentChip == null) return;
 final View target = currentChip;
 channelScroll.post(
 () -> {
 int x = target.getLeft() - a.dp(30);
 channelScroll.smoothScrollTo(Math.max(0, x), 0);
 });
 }

 void refreshTopChrome() {
 TvUiKit.Brand brand = TvUiKit.brand(playingName);
 if (title != null) title.setText(playingName);
 if (brandMini != null) brandMini.setBackground(TvUiKit.brandBlock(a, brand, 10));
 if (brandMiniMark != null) {
 brandMiniMark.setText(brand.mark);
 brandMiniMark.setTextSize(brand.mark.length() >= 3 ? 10.5f : 15);
 }
 if (infoFav != null && !list.isEmpty()) {
 Channel cur = list.get(Math.max(0, Math.min(index, list.size() - 1)));
 boolean on = store.favorites.contains(cur.name);
 infoFav.setText(on ? " 已收藏" : " 收藏频道");
 infoFav.setTextColor(on ? 0xffff8fb1 : a.userTextColor(0xffe6edf9));
 }
 }

 void toggleChannelPanel() {
 if (bottomPanel == null) return;
 panelOpen = !panelOpen;
 if (panelOpen) {
 rebuildGroupChips();
 rebuildChannelChips();
 bottomPanel.setVisibility(View.VISIBLE);
 bottomPanel.setAlpha(0f);
 bottomPanel.animate().alpha(1f).setDuration(180).start();
 scrollToCurrentChip();
 } else {
 bottomPanel.animate().cancel();
 bottomPanel.setAlpha(1f);
 bottomPanel.setVisibility(View.GONE);
 }
 showChrome();
 }

 void showChrome() {
 if (chrome == null || overlay == null) return;
 final boolean fromHidden = !chromeShown;
 chromeShown = true;
 ++chromeGen;
 chrome.animate().cancel();
 chrome.setVisibility(View.VISIBLE);
 chrome.setAlpha(1f);
 if (infoPanel != null) {
 infoPanel.animate().cancel();
 infoPanel.setTranslationX(0f);
 infoPanel.setAlpha(1f);
 }
 handler.removeCallbacks(hideChromeRunnable);
 if (!panelOpen) handler.postDelayed(hideChromeRunnable, CHROME_HIDE_MS);
 }

 void fadeOutChrome() {
 if (chrome == null || overlay == null) return;
 chromeShown = false;
 ++chromeGen;
 chrome.animate().cancel();
 chrome.setAlpha(1f);
 chrome.setVisibility(View.INVISIBLE);
 }

 long lastChromeToggleAt;

 void toggleChrome() {
 long now = android.os.SystemClock.uptimeMillis();
 if (now - lastChromeToggleAt < 350) return;
 lastChromeToggleAt = now;
 if (chromeShown) {
 handler.removeCallbacks(hideChromeRunnable);
 fadeOutChrome();
 } else {
 showChrome();
 }
 }

 void showLoading(String name) {
 if (loadingCard == null) return;
 ++loadingGen;
 loadingCard.animate().cancel();
 loadingTitle.setText("正在连接 " + name);
 loadingSub.setText("官方网页直播 · 首次加载稍慢");
 if (loadingWhale != null) {
 loadingWhale.setText("正在连接…");
 loadingWhale.start();
 }
 loadingCard.setAlpha(1f);
 loadingCard.setVisibility(View.VISIBLE);
 // 进度可能长期停在 <80%（直播长连接/iframe）：兜底 1.2s 后也把播放探针跑起来
 handler.postDelayed(this::startInject, 1200);
 }

 void hideLoading() {
 if (loadingCard == null || loadingCard.getVisibility() != View.VISIBLE) return;
 if (loadingWhale != null) loadingWhale.stop();
 final int gen = ++loadingGen;
 loadingCard.animate().cancel();
 loadingCard.animate()
 .alpha(0f)
 .setDuration(220)
 .withEndAction(
 () -> {
 if (gen == loadingGen && loadingCard != null) {
 loadingCard.setVisibility(View.GONE);
 loadingCard.setAlpha(1f);
 }
 })
 .start();
 }

 boolean back() {
 if (overlay == null) return false;
 if (web != null && web.canGoBack()) {
 web.goBack();
 return true;
 }
 close();
 return true;
 }

 void close() {
 handler.removeCallbacksAndMessages(null);
 injectStarted = false;
 if (chrome != null) chrome.animate().cancel();
 if (loadingCard != null) loadingCard.animate().cancel();
 if (overlay != null && overlay.getParent() instanceof FrameLayout)
 ((FrameLayout) overlay.getParent()).removeView(overlay);
 if (web != null) {
 web.loadUrl("about:blank");
 web.destroy();
 web = null;
 }
 overlay = null;
 // 先摘掉 tvPlayer 再恢复方向：横屏→竖屏的 onConfigurationChanged 会走 refresh()，
 // 在最终方向/尺寸上重新构建一次 shell（避免残留横屏的侧栏 + 三段式布局）。
 if (a.tvPlayer == this) a.tvPlayer = null;
 try {
 a.setRequestedOrientation(
 a.formFactor == TvLayoutPolicy.FORM_PHONE
 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
 : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
 } catch (Throwable ignored) {
 }
 MobileShell.setDockVisible(true);
 // 关闭后通知列表页刷新高亮（page::refresh）
 Runnable callback = onClosed;
 onClosed = null;
 if (callback != null) callback.run();
 }

 int volume() {
 android.media.AudioManager am =
 (android.media.AudioManager)
 a.getSystemService(Context.AUDIO_SERVICE);
 int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
 int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
 return max <= 0 ? 0 : Math.round(cur * 100f / max);
 }

 void setVolume(int percent) {
 android.media.AudioManager am =
 (android.media.AudioManager)
 a.getSystemService(Context.AUDIO_SERVICE);
 int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
 am.setStreamVolume(
 android.media.AudioManager.STREAM_MUSIC,
 Math.round(max * percent / 100f),
 0);
 }

 /** 自定义 WebView：普通点击交给网页；明显的上下滑才拦截成亮度/音量手势。 */
 final class TvWebView extends WebView {
 private float startX, startY;
 private int startBrightness, startVolume;
 private boolean vertical;
 private long downTime;

 TvWebView(Context c) {
 super(c);
 }

 @Override
 public boolean dispatchTouchEvent(MotionEvent e) {
 switch (e.getActionMasked()) {
 case MotionEvent.ACTION_DOWN:
 startX = e.getX();
 startY = e.getY();
 downTime = e.getEventTime();
 vertical = false;
 WindowManager.LayoutParams lp = a.getWindow().getAttributes();
 startBrightness =
 lp.screenBrightness < 0
 ? 128
 : Math.round(lp.screenBrightness * 255);
 startVolume = volume();
 break;
 case MotionEvent.ACTION_MOVE:
 float dx = Math.abs(e.getX() - startX);
 float dy = Math.abs(e.getY() - startY);
 if (dy > a.dp(30) && dy > dx * 1.5f) vertical = true;
 if (vertical) {
 int delta =
 Math.round((startY - e.getY()) / a.dp(360) * 255);
 if (startX < getWidth() / 2f) {
 int level = Math.max(10, Math.min(255, startBrightness + delta));
 WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
 attrs.screenBrightness = level / 255f;
 a.getWindow().setAttributes(attrs);
 } else {
 setVolume(Math.max(0, Math.min(100, startVolume + delta * 100 / 255)));
 }
 return true;
 }
 break;
 case MotionEvent.ACTION_UP:
 case MotionEvent.ACTION_CANCEL:
 if (vertical) {
 vertical = false;
 return true;
 }
 // 轻点（非滑动）唤出 / 收起 chrome；不拦截网页点击
 if (e.getActionMasked() == MotionEvent.ACTION_UP && fullView == null) {
 float tapDx = Math.abs(e.getX() - startX);
 float tapDy = Math.abs(e.getY() - startY);
 long tapMs = e.getEventTime() - downTime;
 if (tapDx < a.dp(12) && tapDy < a.dp(12) && tapMs >= 0 && tapMs < 320) {
 toggleChrome();
 }
 }
 break;
 default:
 break;
 }
 return super.dispatchTouchEvent(e);
 }
 }

 final class SwipeGesture implements View.OnTouchListener {
 float startX, startY;
 int startBrightness, startVolume;
 boolean dragging;

 @Override
 public boolean onTouch(View v, MotionEvent e) {
 switch (e.getActionMasked()) {
 case MotionEvent.ACTION_DOWN:
 startX = e.getX();
 startY = e.getY();
 dragging = false;
 WindowManager.LayoutParams lp = a.getWindow().getAttributes();
 startBrightness =
 lp.screenBrightness < 0
 ? 128
 : Math.round(lp.screenBrightness * 255);
 startVolume = volume();
 return true;
 case MotionEvent.ACTION_MOVE:
 float dy = startY - e.getY();
 if (Math.abs(dy) < a.dp(24)) return true;
 dragging = true;
 int delta = Math.round(dy / a.dp(300) * 255);
 if (startX < overlay.getWidth() / 2f) {
 int level = Math.max(10, Math.min(255, startBrightness + delta));
 WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
 attrs.screenBrightness = level / 255f;
 a.getWindow().setAttributes(attrs);
 } else {
 setVolume(Math.max(0, Math.min(100, startVolume + delta * 100 / 255)));
 }
 return true;
 case MotionEvent.ACTION_UP:
 return dragging;
 default:
 return false;
 }
 }
 }
 }

 static final String UA =
 "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

 static final String INJECT_JS =
 "(function(){"
 + "try{"
 + "var pump=function(){"
 + "var sels=['.vjs-big-play-button','.dplayer-play-icon','.xgplayer-start','.prism-player .play-btn','.play-button','.live-play'];"
 + "for(var i=0;i<sels.length;i++){var el=document.querySelector(sels[i]);if(el){try{el.click();}catch(e){}}}"
 + "var vs=document.querySelectorAll('video');"
 + "for(var j=0;j<vs.length;j++){var v=vs[j];try{v.muted=false;v.play();}catch(e){}}"
 + "};"
 + "pump();"
 // 真·全屏：把 video 的父容器固定铺满，隐藏其它所有兄弟节点（页面导航/EPG 等）
 + "var v=document.querySelector('video');"
 + "if(v&&v.parentElement){"
 + "var root=v.parentElement;"
 // 若父容器太小（纯 video 包装），再上提一层，尽量包住控制条
 + "if(root.clientWidth<200||root.clientHeight<150){if(root.parentElement&&root.parentElement!==document.body)root=root.parentElement;}"
 + "var node=root;"
 + "while(node&&node.parentElement){var par=node.parentElement;"
 + "for(var k=0;k<par.children.length;k++){var sib=par.children[k];if(sib!==node){sib.style.display='none';}}"
 + "if(par===document.body)break;node=par;}"
 + "root.style.position='fixed';root.style.top='0';root.style.left='0';"
 + "root.style.width='100vw';root.style.height='100vh';root.style.zIndex='2147483000';"
 + "root.style.background='#000';root.style.overflow='hidden';"
 + "v.style.width='100%';v.style.height='100%';v.style.objectFit='contain';"
 + "}"
 + "document.documentElement.style.margin='0';document.body.style.margin='0';"
 + "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';"
 + "}catch(e){}"
 + "})();";

 /** 页面内是否真的有 video 在播（画面已出但 onPageFinished 迟迟不来时用来收掉加载浮层）。 */
 static final String PLAYING_JS =
 "(function(){try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var v=vs[i];if(v&&!v.paused&&v.readyState>=2&&v.currentTime>0)return true;}return false;}catch(e){return false;}})()";
}
