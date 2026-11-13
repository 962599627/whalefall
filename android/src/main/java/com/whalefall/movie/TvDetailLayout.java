package com.whalefall.movie;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * TV 详情页（1.4.1）：平板版 52/48 两栏结构 + TV 遥控焦点/BACK/降级。
 *
 * <p>只被 {@link DetailScreen#show} 调用；复用 DetailScreen 的按钮/胶囊/线路/选集视觉组件，
 * 不共享 {@link MobileDetailScreen} 整页布局，手机/平板行为零变化。
 */
final class TvDetailLayout {
 private TvDetailLayout() {}

 static void show(
 final MainActivity a,
 final Cms.Site s,
 final Cms.Video listing,
 Cms.Video ready) {
 if (a.redirectListing(s, listing)) return;
 a.renderPage("", "", false);
 a.body.setPadding(0, 0, 0, 0);
 // 去掉 renderPage 的整页滚动：左栏固定（海报+线路），右栏独立滚动
 if (a.scroll != null && a.scroll.getParent() instanceof ViewGroup)
 ((ViewGroup) a.scroll.getParent()).removeView(a.scroll);

 final int sidePad = a.dp(24);
 final int topRowH = a.dp(44);

 // ---------------- 顶部：返回 / 收藏（48dp 行，44dp 按钮） ----------------
 LinearLayout topRow = a.row();
 topRow.setGravity(Gravity.CENTER_VERTICAL);
 FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, a.dp(48), Gravity.TOP);
 topLp.setMargins(sidePad, a.dp(18), sidePad, 0);
 a.root.addView(topRow, topLp);
 final Button backBtn =
 DetailScreen.glassNavButton(a, "‹ 返回", "detail-back", false, a::onBackPressed);
 topRow.addView(backBtn, new LinearLayout.LayoutParams(-2, topRowH));
 View topSpacer = new View(a);
 topRow.addView(topSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
 final boolean initialFav =
 Favorites.contains(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, listing.id);
 final Button favTop =
 DetailScreen.glassNavButton(
 a,
 initialFav ? " 已收藏" : " 加入收藏",
 "detail-favorite",
 initialFav,
 () -> {});
 topRow.addView(favTop, new LinearLayout.LayoutParams(-2, topRowH));
 // 数据到达前也能点收藏（到达后由 buildActions 重新绑定真实视频）
 bindFavoriteAct(a, s, listing, favTop);

 // ---------------- 内容区：52% 左栏 + 20 gap + 48% 右栏 ----------------
 FrameLayout content = new FrameLayout(a);
 FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(-1, -1);
 contentLp.setMargins(sidePad, a.dp(18 + 48 + 12), sidePad, a.dp(18));
 a.root.addView(content, contentLp);
 final int contentW = Math.max(0, a.windowWidth() - sidePad * 2);
 final int gap = a.dp(20);
 final int leftW = Math.max(a.dp(280), Math.round((contentW - gap) * 0.52f));
 final int rightW = Math.max(a.dp(240), contentW - gap - leftW);

 LinearLayout row = a.row();
 row.setGravity(Gravity.TOP);
 content.addView(row, new FrameLayout.LayoutParams(-1, -1));
 final LinearLayout leftCol = a.vertical();
 row.addView(leftCol, new LinearLayout.LayoutParams(leftW, -1));
 View midGap = new View(a);
 row.addView(midGap, new LinearLayout.LayoutParams(gap, 1));

 final LinearLayout rightCol = a.vertical();
 final TvScrollView rightScroll = new TvScrollView(a);
 rightScroll.setTag("detail-scroll");
 rightScroll.setFillViewport(true);
 rightScroll.setClipChildren(false);
 rightScroll.setClipToPadding(false);
 rightScroll.setVerticalScrollBarEnabled(false);
 rightCol.setPadding(0, 0, 0, a.dp(16));
 rightScroll.addView(rightCol, new ScrollView.LayoutParams(-1, -2));
 rightScroll.setFocusable(false);
 rightScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
 row.addView(rightScroll, new LinearLayout.LayoutParams(rightW, -1));
 a.scroll = rightScroll;
 a.body = rightCol;

 // ---------------- 左栏：16:9 预览卡 + 片源·线路 ----------------
 int previewH = Math.round(leftW * 9f / 16f);
 leftCol.addView(
 buildPreview(a, listing, previewH), new LinearLayout.LayoutParams(-1, previewH));
 View previewGap = new View(a);
 leftCol.addView(previewGap, new LinearLayout.LayoutParams(1, a.dp(16)));
 final LinearLayout lineList = a.vertical();
 lineList.setTag("detail-line-list");
 ScrollView lineScroll = new ScrollView(a);
 lineScroll.setVerticalScrollBarEnabled(false);
 lineScroll.setClipToPadding(false);
 // 1.9.52：滚动容器不吃焦点，交给子项
 lineScroll.setFocusable(false);
 lineScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
 lineList.setFocusable(false);
 lineScroll.addView(lineList, new ScrollView.LayoutParams(-1, -2));
 // 1.9.308：片源·线路区块用用户卡片颜色
 lineScroll.setBackground(a.cardBg(14));
 leftCol.addView(lineScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

 // ---------------- 右栏骨架 ----------------
 final TextView title = a.text(listing.title, 28, MainActivity.WHITE);
 title.setTag("detail-title");
 title.setTypeface(null, android.graphics.Typeface.BOLD);
 title.setMaxLines(2);
 title.setEllipsize(TextUtils.TruncateAt.END);
 title.setLineSpacing(a.dp(2), 1f);
 title.setShadowLayer(a.dp(10), 0, a.dp(3), 0x99000000);
 rightCol.addView(title, new LinearLayout.LayoutParams(-1, -2));

 final LinearLayout pillsRow = a.row();
 pillsRow.setGravity(Gravity.START);
 LinearLayout.LayoutParams pillsLp = new LinearLayout.LayoutParams(-1, -2);
 pillsLp.topMargin = a.dp(12);
 rightCol.addView(pillsRow, pillsLp);

 final TextView actorTv = a.text(actorLine(listing.actor), 13, 0xffb9c7dd);
 actorTv.setTag("detail-actors");
 actorTv.setMaxLines(2);
 actorTv.setEllipsize(TextUtils.TruncateAt.END);
 actorTv.setVisibility(actorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 LinearLayout.LayoutParams actorLp = new LinearLayout.LayoutParams(-1, -2);
 actorLp.topMargin = a.dp(10);
 rightCol.addView(actorTv, actorLp);

 final TextView directorTv = a.text(directorLine(listing.director), 13, 0xffb9c7dd);
 directorTv.setTag("detail-director");
 directorTv.setMaxLines(2);
 directorTv.setEllipsize(TextUtils.TruncateAt.END);
 directorTv.setVisibility(directorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 LinearLayout.LayoutParams directorLp = new LinearLayout.LayoutParams(-1, -2);
 directorLp.topMargin = a.dp(4);
 rightCol.addView(directorTv, directorLp);

 // 简介：默认 4 行折叠 + 「展开完整简介 / 收起」44dp 可聚焦按钮
 final LinearLayout synopsisWrap = a.vertical();
 LinearLayout.LayoutParams synopsisLp = new LinearLayout.LayoutParams(-1, -2);
 synopsisLp.topMargin = a.dp(10);
 rightCol.addView(synopsisWrap, synopsisLp);
 final LinearLayout synopsisText = a.vertical();
 synopsisWrap.addView(synopsisText, new LinearLayout.LayoutParams(-1, -2));

 // 操作区 2×2（倒序 / 简介 / 收藏 / 复制降级）、主播放按钮
 final LinearLayout actionsWrap = a.vertical();
 LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
 actionsLp.topMargin = a.dp(12);
 rightCol.addView(actionsWrap, actionsLp);
 final LinearLayout playWrap = a.vertical();
 LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(-1, -2);
 playLp.topMargin = a.dp(10);
 rightCol.addView(playWrap, playLp);
 final TextView bookmarkTv = a.text("", 13, Skin.TEXT_TERTIARY_T);
 bookmarkTv.setTag("detail-history");
 LinearLayout.LayoutParams bookmarkLp = new LinearLayout.LayoutParams(-1, -2);
 bookmarkLp.topMargin = a.dp(6);
 rightCol.addView(bookmarkTv, bookmarkLp);

 final LinearLayout seasonsWrap = a.vertical();
 seasonsWrap.setTag("detail-seasons");
 LinearLayout.LayoutParams seasonsLp = new LinearLayout.LayoutParams(-1, -2);
 seasonsLp.topMargin = a.dp(6);
 rightCol.addView(seasonsWrap, seasonsLp);
 final LinearLayout episodesWrap = a.vertical();
 episodesWrap.setTag("detail-episodes");
 LinearLayout.LayoutParams episodesLp = new LinearLayout.LayoutParams(-1, -2);
 episodesLp.topMargin = a.dp(8);
 rightCol.addView(episodesWrap, episodesLp);

 // 加载中：焦点先落「返回」，避免无焦点空档；1.8.3 用圆形帧动画
 final FrameLayout stateWrap = new FrameLayout(a);
 stateWrap.setTag("detail-loading");
 final WhaleLoadingView tvWhale = new WhaleLoadingView(a, WhaleLoadingView.SMALL, true);
 tvWhale.setText("正在读取详情与剧集…");
 tvWhale.start();
 stateWrap.addView(
 tvWhale, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
 final TextView state = a.text("正在读取详情与剧集…", 12, MainActivity.MUTED);
 state.setGravity(Gravity.CENTER);
 state.setPadding(0, a.dp(4), 0, 0);
 FrameLayout.LayoutParams tvStateLp =
 new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
 stateWrap.addView(state, tvStateLp);
 rightCol.addView(stateWrap, new LinearLayout.LayoutParams(-1, a.dp(150)));
 // 1.9.60：首焦点必须是「播放·首集」，禁止先落「返回」
 a.ui.post(
 () -> {
 View current = a.getCurrentFocus();
 if (current != null && current.isAttachedToWindow() && current.isShown())
 return;
 focusFirstDetail(a, "post");
 });
 a.ui.postDelayed(
 () -> {
 int fixed = ensureFocusables(a, a.root, 0);
 fixed += TvNav.ensureFocusable(a, a.root, 12, 0);
 View current = a.getCurrentFocus();
 if (current == null || !current.isShown() || !current.isAttachedToWindow()) {
 focusFirstDetail(a, "fixed=" + fixed);
 } else {
 AppLog.i(
 "TvFocus",
 "page=detail focus="
 + (current.getTag() == null
 ? "null"
 : String.valueOf(current.getTag()))
 + " fixed="
 + fixed);
 }
 },
 450);
 a.ui.postDelayed(
 () -> {
 View current = a.getCurrentFocus();
 if (current == null || !current.isShown() || !current.isAttachedToWindow())
 focusFirstDetail(a, "delayed");
 },
 1400);

 // ---------------- 数据填充 ----------------
 final int[] lineIndex = {Math.max(0, SourcePreferences.lineIndex(a, listing))};
 final int[] page = {0};
 final Runnable[] rebuildEpisodes = new Runnable[1];

 MainActivity.Done<Cms.Video> present =
 v -> {
 if (v.poster == null || v.poster.isEmpty()) v.poster = listing.poster;
 if (v.title == null || v.title.isEmpty()) v.title = listing.title;
 title.setText(v.title);
 actorTv.setText(actorLine(v.actor));
 actorTv.setVisibility(
 actorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);
 directorTv.setText(directorLine(v.director));
 directorTv.setVisibility(
 directorTv.getText().length() == 0 ? View.GONE : View.VISIBLE);

 populatePills(a, pillsRow, v);
 populateSynopsis(a, synopsisText, synopsisWrap, v);

 for (java.util.Iterator<Cms.Line> it = v.lines.iterator(); it.hasNext(); )
 if (it.next().episodes.isEmpty()) it.remove();
 if (v.lines.isEmpty()) {
 state.setVisibility(View.VISIBLE);
 state.setText("这部影片没有返回可识别的播放线路。");
 buildActions(a, actionsWrap, s, v, null, null, null, page);
 backBtn.requestFocus();
 return;
 }
 state.setVisibility(View.GONE);
 stateWrap.setVisibility(View.GONE);
 tvWhale.stop();

 WatchHistory.Entry bookmark = null;
 for (WatchHistory.Entry entry : a.watchHistory())
 if (entry.site.equals(s.key) && entry.id.equals(v.id)) {
 bookmark = entry;
 break;
 }
 final WatchHistory.Entry remembered = bookmark;
 if (remembered != null) {
 bookmarkTv.setText("上次观看 · " + a.historyLabel(remembered));
 bookmarkTv.setVisibility(View.VISIBLE);
 } else {
 bookmarkTv.setVisibility(View.GONE);
 }
 if (a.current != null && a.current.selection.selected)
 for (int i = 0; i < v.lines.size(); i++)
 if (v.lines.get(i).name.equals(a.current.selection.line)) {
 lineIndex[0] = i;
 page[0] =
 EpisodeSelection.boundPage(
 a.current.selection.page,
 v.lines.get(i).episodes.size());
 break;
 }
 if (lineIndex[0] < 0 || lineIndex[0] >= v.lines.size()) lineIndex[0] = 0;

 final String[] rememberedEpisode = {
 remembered != null ? remembered.episode : null
 };
 rebuildEpisodes[0] =
 () ->
 renderEpisodes(
 a,
 s,
 v,
 lineIndex[0],
 page[0],
 episodesWrap,
 rememberedEpisode[0]);

 // 左栏线路 + 右栏选集
 bindLineList(
 a, s, v, lineList, lineIndex, page, rebuildEpisodes);
 rebuildEpisodes[0].run();

 // 主播放按钮（首焦点）。续播语义（R3）：有书签优先续播（书签自带线路）；
 // 无书签才使用当前左栏选中线路。
 String playLabel;
 if (remembered == null) playLabel = "播放 · 首集";
 else playLabel = "播放 · " + remembered.episode + " · 续播";
 final Button play =
 DetailScreen.heroPlayButton(
 a,
 playLabel,
 "detail-play",
 () -> {
 if (remembered != null) a.playBookmark(s, v, remembered);
 else {
 int idx =
 Math.max(
 0,
 Math.min(
 lineIndex[0],
 v.lines.size() - 1));
 Cms.Line line = v.lines.get(idx);
 a.resumeAt = 0;
 a.resolvePlayer(
 s, v, line, line.episodes.get(0));
 }
 });
 play.setMinWidth(a.dp(240));
 play.setMinimumWidth(a.dp(240));
 play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
 playWrap.removeAllViews();
 playWrap.addView(play, new LinearLayout.LayoutParams(-1, a.dp(52)));
 // 1.9.60：播放 → 下到线路第一项、右到收藏，保证方向键可达
 if (play.getId() == View.NO_ID) play.setId(View.generateViewId());
 View lineFirst = findByTagPrefix(a.root, "detail-line:");
 if (lineFirst != null) {
 if (lineFirst.getId() == View.NO_ID)
 lineFirst.setId(View.generateViewId());
 play.setNextFocusDownId(lineFirst.getId());
 lineFirst.setNextFocusUpId(play.getId());
 }
 View favoriteTop = a.root.findViewWithTag("detail-favorite");
 if (favoriteTop != null) {
 if (favoriteTop.getId() == View.NO_ID)
 favoriteTop.setId(View.generateViewId());
 play.setNextFocusRightId(favoriteTop.getId());
 favoriteTop.setNextFocusLeftId(play.getId());
 }

 buildActions(
 a, actionsWrap, s, v, remembered, play, rebuildEpisodes[0], page);
 DetailScreen.populateTvRelatedSeasons(a, s, v, seasonsWrap);
 // 1.9.62：内容就绪后建立完整显式 nextFocus 链（不依赖几何默认）
 wireDetailFocusChain(a);

 // 焦点恢复：优先离开前 tag（线路/选集/操作/播放），否则主播放
 View saved = null;
 if (a.current != null && a.current.focus != null)
 saved = a.root.findViewWithTag(a.current.focus);
 if (saved == null && a.current != null && a.current.episodeFocus >= 0)
 for (int i = 0; i < v.lines.size(); i++)
 if (v.lines.get(i).name.equals(a.current.episodeFocusLine)) {
 saved =
 a.root.findViewWithTag(
 "episode:"
 + v.lines.get(i).name
 + ":"
 + a.current.episodeFocus);
 break;
 }
 if (saved != null && saved.isFocusable() && saved.isShown()) {
 saved.requestFocus();
 } else {
 // R4：不要抢用户已经移走的焦点；仅当前焦点为空/未 attach/仍是自动焦点时才落主播放
 View focus = a.getCurrentFocus();
 boolean canTake =
 focus == null || !focus.isAttachedToWindow() || focus == a.autoFocus;
 if (canTake && a.current != null) {
 // 1.9.118：首焦点落在 hero 视频/海报区；无 hero 时回退播放按钮
 View heroFocus = a.root.findViewWithTag("detail-hero");
 if (heroFocus != null && heroFocus.isShown() && heroFocus.isFocusable()) {
 a.current.focus = "detail-hero";
 heroFocus.requestFocus();
 a.autoFocus = heroFocus;
 AppLog.i("DetailFocus", "first=hero");
 } else {
 a.current.focus = "detail-play";
 play.requestFocus();
 a.autoFocus = play;
 AppLog.i("DetailFocus", "first=play");
 }
 }
 }
 };

 if (ready != null) {
 try {
 present.accept(ready);
 } catch (Exception error) {
 AppLog.w("TvDetailLayout", "present ready detail failed", error);
 state.setText("详情数据无法显示，请返回后重试。");
 tvWhale.stop();
 }
 } else if (!a.reduced && hasPlayableLines(listing)) {
 try {
 present.accept(listing);
 } catch (Exception error) {
 AppLog.w("TvDetailLayout", "present listing detail failed", error);
 }
 if (listing.description == null || listing.description.trim().isEmpty())
 a.loadDetail(s, listing.id, present);
 } else {
 a.loadDetail(s, listing.id, present);
 }
 }

 private static boolean hasPlayableLines(Cms.Video v) {
 if (v == null || v.lines == null || v.lines.isEmpty()) return false;
 for (Cms.Line line : v.lines)
 if (line != null && line.episodes != null && !line.episodes.isEmpty()) return true;
 return false;
 }

 /** 16:9 预览卡：封面 + 底部渐变 + 中央 72dp 粉圆「按 OK 播放」浮层（独立可聚焦）。 */
 private static View buildPreview(MainActivity a, Cms.Video listing, int height) {
 FrameLayout card = new FrameLayout(a);
 card.setTag("detail-hero");
 // 1.9.118：hero 焦点与 tag 分离：容器仍用 detail-hero 供小窗测矩形
 card.setFocusable(true);
 card.setFocusableInTouchMode(true);
 TvNav.focusRing(a, card, 20);
 card.setContentDescription("播放当前视频");
 card.setOnClickListener(
 v -> {
 AppLog.i("DetailFocus", "hero ok=play");
 View pb = a.root.findViewWithTag("detail-play");
 if (pb != null) pb.performClick();
 });
 GradientDrawable bg = new GradientDrawable();
 bg.setColor(0xff0d1a2b);
 bg.setCornerRadius(a.dp(20));
 bg.setStroke(Math.max(1, a.dp(0.8f)), 0x22ffffff);
 card.setBackground(bg);
 card.setClipToOutline(true);
 ImageView poster = new ImageView(a);
 poster.setTag("detail-cover");
 poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
 card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
 if (listing.poster != null && !listing.poster.isEmpty())
 a.poster(listing.poster, poster, true);

 View scrim = new View(a);
 scrim.setBackground(
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0x00000000, 0x44000000, 0xcc050b14}));
 card.addView(scrim, new FrameLayout.LayoutParams(-1, a.dp(150), Gravity.BOTTOM));

 TextView hint = a.text("按 OK 播放", 13, 0xd9ffffff);
 hint.setShadowLayer(a.dp(6), 0, a.dp(2), 0xcc000000);
 FrameLayout.LayoutParams hintLp =
 new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
 hintLp.setMargins(a.dp(16), 0, 0, a.dp(12));
 card.addView(hint, hintLp);

 TextView bigPlay = a.text("播放", 16, Skin.TEXT_PRIMARY_T);
 bigPlay.setTag("detail-play-big");
 bigPlay.setTypeface(null, android.graphics.Typeface.BOLD);
 bigPlay.setGravity(Gravity.CENTER);
 // 1.9.322：大块粉色渐变 → 中性卡片底 + ACCENT_SOFT 细描边
 GradientDrawable fill = new GradientDrawable();
 fill.setColor((0xE6 << 24) | (Skin.cardFill & 0xffffff));
 fill.setCornerRadius(a.dp(999));
 fill.setStroke(Math.max(1, a.dp(1.2f)), Skin.accentSoft());
 bigPlay.setBackground(fill);
 bigPlay.setElevation(a.dp(8));
 bigPlay.setFocusable(true);
 bigPlay.setClickable(true);
 bigPlay.setOnClickListener(
 v -> {
 View play = a.root.findViewWithTag("detail-play");
 if (play != null) play.performClick();
 });
 bigPlay.setOnFocusChangeListener(
 (v, focused) -> {
 FocusMotion.style(
 a,
 v,
 focused,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 999,
 Skin.TEXT_PRIMARY_T,
 MainActivity.DARK,
 true,
 1.02f);
 });
 card.addView(bigPlay, new FrameLayout.LayoutParams(a.dp(72), a.dp(72), Gravity.CENTER));
 return card;
 }

 /** 左栏「片源 · 线路」列表：44dp 行、选中粉底、行 tag=detail-line:n。 */
 private static void bindLineList(
 final MainActivity a,
 final Cms.Site site,
 final Cms.Video video,
 final LinearLayout lineList,
 final int[] lineIndex,
 final int[] page,
 final Runnable[] rebuildEpisodes) {
 lineList.removeAllViews();
 TextView head = a.text("片源 · 线路", 15, MainActivity.WHITE);
 head.setTypeface(null, android.graphics.Typeface.BOLD);
 head.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(6));
 LinearLayout headRow = a.row();
 headRow.setGravity(Gravity.CENTER_VERTICAL);
 headRow.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
 TextView siteName = a.text(site.name, 13, Skin.TEXT_TERTIARY_T);
 siteName.setSingleLine(true);
 siteName.setEllipsize(TextUtils.TruncateAt.END);
 headRow.addView(siteName);
 lineList.addView(headRow, new LinearLayout.LayoutParams(-1, a.dp(30)));
 for (int i = 0; i < video.lines.size(); i++) {
 final int index = i;
 final boolean selected = i == lineIndex[0];
 final Button line =
 a.button(
 video.lines.get(i).name + " " + video.lines.get(i).episodes.size() + " 集",
 "detail-line:" + i,
 () -> {
 lineIndex[0] = index;
 page[0] = 0;
 bindLineList(
 a, site, video, lineList, lineIndex, page, rebuildEpisodes);
 rebuildEpisodes[0].run();
 View next = lineList.findViewWithTag("detail-line:" + index);
 if (next != null) next.requestFocus();
 });
 line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
 line.setSingleLine(true);
 line.setEllipsize(TextUtils.TruncateAt.END);
 line.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
 line.setMinWidth(0);
 line.setMinimumWidth(0);
 line.setMinHeight(a.dp(44));
 line.setMinimumHeight(a.dp(44));
 line.setPadding(a.dp(14), 0, a.dp(14), 0);
 line.setBackground(MobileDetailScreen.chipBg(a, 12f, selected));
 line.setTextColor(a.cardInk());
 line.setOnFocusChangeListener(
 (v, focus) ->
 FocusMotion.style(
 a,
 v,
 focus,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 12,
 selected ? Skin.TEXT_PRIMARY_T : Skin.TEXT_TERTIARY_T,
 MainActivity.DARK,
 false,
 1.02f));
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(44));
 lp.bottomMargin = a.dp(6);
 lineList.addView(line, lp);
 }
 }

 /** 右栏选集：2 列 × 52dp、单行省略、编号+集名、当前集粉底；>50 集分页。 */
 private static void renderEpisodes(
 final MainActivity a,
 final Cms.Site site,
 final Cms.Video video,
 final int lineIndex,
 final int page,
 final LinearLayout wrap,
 final String highlightEpisode) {
 wrap.removeAllViews();
 Cms.Line line = video.lines.get(lineIndex);
 if (a.current != null)
 a.current.selection.select(line.name, page, line.episodes.size());
 final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);

 LinearLayout header = a.row();
 header.setGravity(Gravity.CENTER_VERTICAL);
 TextView head = a.text("选集 · 共 " + line.episodes.size() + " 集", 17, MainActivity.WHITE);
 head.setTypeface(null, android.graphics.Typeface.BOLD);
 header.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
 Button all =
 a.navButton(
 "全部剧集 (" + line.episodes.size() + ")",
 "episodes-all",
 () -> {
 String[] labels = new String[line.episodes.size()];
 for (int n = 0; n < labels.length; n++)
 labels[n] =
 line.episodes.get(descending ? labels.length - 1 - n : n)
 .name;
 new AppDialog.Builder(a)
 .setTitle(line.name)
 .setItems(
 labels,
 (dialog, n) ->
 a.resolvePlayer(
 site,
 video,
 line,
 line.episodes.get(
 descending
 ? labels.length - 1 - n
 : n)))
 .setNegativeButton("取消", null)
 .show();
 });
 all.setMinHeight(a.dp(44));
 header.addView(all, new LinearLayout.LayoutParams(-2, a.dp(44)));
 wrap.addView(header, new LinearLayout.LayoutParams(-1, a.dp(44)));

 int start = page * 50, end = Math.min(start + 50, line.episodes.size());
 if (line.episodes.size() > 50) {
 LinearLayout pages = a.row();
 pages.setGravity(Gravity.CENTER_VERTICAL);
 if (page > 0)
 pages.addView(
 a.navButton(
 "上一组",
 "episodes-previous",
 () -> {
 renderEpisodes(
 a,
 site,
 video,
 lineIndex,
 page - 1,
 wrap,
 highlightEpisode);
 View first = wrap.findViewWithTag("episodes-previous");
 if (first != null) first.requestFocus();
 }),
 new LinearLayout.LayoutParams(-2, a.dp(44)));
 TextView range = a.text(" " + (start + 1) + " - " + end + " / " + line.episodes.size() + " ", 13, Skin.TEXT_TERTIARY_T);
 pages.addView(range);
 if (end < line.episodes.size())
 pages.addView(
 a.navButton(
 "下一组",
 "episodes-next",
 () -> {
 renderEpisodes(
 a,
 site,
 video,
 lineIndex,
 page + 1,
 wrap,
 highlightEpisode);
 View first = wrap.findViewWithTag("episodes-next");
 if (first != null) first.requestFocus();
 }),
 new LinearLayout.LayoutParams(-2, a.dp(44)));
 wrap.addView(pages, new LinearLayout.LayoutParams(-1, a.dp(44)));
 }

 LinearLayout grid = a.vertical();
 wrap.addView(grid, new LinearLayout.LayoutParams(-1, -2));
 for (int i = start; i < end; i += 2) {
 LinearLayout cellRow = a.row();
 cellRow.setGravity(Gravity.TOP);
 grid.addView(cellRow, new LinearLayout.LayoutParams(-1, -2));
 for (int c = 0; c < 2; c++) {
 int i2 = i + c;
 LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1f);
 cellLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 if (i2 >= end) {
 cellRow.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
 continue;
 }
 final int originalIndex = descending ? line.episodes.size() - 1 - i2 : i2;
 final Cms.Episode episode = line.episodes.get(originalIndex);
 final String[] episodeName = {episode.name};
 final Button cell =
 a.button(
 formattedEpisode(originalIndex, episode.name),
 "episode:" + line.name + ":" + originalIndex,
 () -> {
 a.resumeAt = 0;
 a.resolvePlayer(site, video, line, episode);
 });
 cell.setContentDescription(episode.name);
 cell.setSingleLine(true);
 cell.setEllipsize(TextUtils.TruncateAt.END);
 cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
 cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
 cell.setMinWidth(0);
 cell.setMinimumWidth(0);
 cell.setMinHeight(a.dp(52));
 cell.setMinimumHeight(a.dp(52));
 cell.setPadding(a.dp(12), 0, a.dp(12), 0);
 final boolean current =
 highlightEpisode != null && highlightEpisode.equals(episode.name);
 // 1.9.322：当前集=卡片底 + ACCENT_SOFT 描边（统一走 chipBg 工厂），不再整块粉
 cell.setBackground(MobileDetailScreen.chipBg(a, 14f, current));
 cell.setTextColor(a.cardInk());
 cell.setOnFocusChangeListener(
 (v, focused) -> {
 if (focused && a.current != null) {
 a.current.episodeFocus = originalIndex;
 a.current.episodeFocusLine = line.name;
 if (episodeName[0] != null && episodeName[0].length() > 8)
 v.setSelected(true);
 }
 FocusMotion.style(
 a,
 v,
 focused,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 14,
 Skin.TEXT_PRIMARY_T,
 MainActivity.DARK,
 false,
 1.02f);
 });
 cellRow.addView(cell, cellLp);
 }
 }
 }

 private static String formattedEpisode(int originalIndex, String name) {
 String num = String.format(java.util.Locale.US, "%02d", originalIndex + 1);
 if (name == null || name.trim().isEmpty()) return num;
 return num + " " + name;
 }

 /** 右栏操作区 2×2：倒序 / 简介（由调用方接管）/ 收藏 / 复制（TV 降级）。 */
 private static void buildActions(
 final MainActivity a,
 LinearLayout actionsWrap,
 final Cms.Site s,
 final Cms.Video video,
 final WatchHistory.Entry remembered,
 final Button play,
 final Runnable rebuildEpisodes,
 final int[] page) {
 actionsWrap.removeAllViews();
 if (video == null) return;
 final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
 Button order =
 actionButton(
 a,
 descending ? "排序 倒序 · 开" : "排序 倒序 · 关",
 "detail-act-order",
 () -> {
 boolean next = !a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
 a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, next).apply();
 if (page != null) page[0] = 0;
 if (rebuildEpisodes != null) rebuildEpisodes.run();
 View again = a.root.findViewWithTag("detail-act-order");
 if (again != null) again.requestFocus();
 });
 Button copy =
 actionButton(a, " 复制", "detail-act-copy", () -> ExtraPages.degrade(a, false));
 Button synopsis =
 actionButton(
 a,
 " 简介",
 "detail-act-synopsis",
 () -> {
 View t = a.root.findViewWithTag("synopsis-toggle");
 if (t != null) t.performClick();
 });
 Button favAct = actionButton(a, " 收藏", "detail-act-favorite", () -> {});
 LinearLayout row1 = a.row();
 row1.setGravity(Gravity.TOP);
 row1.addView(order, gridCell(a, true));
 row1.addView(synopsis, gridCell(a, false));
 LinearLayout row2 = a.row();
 row2.setGravity(Gravity.TOP);
 row2.addView(favAct, gridCell(a, true));
 row2.addView(copy, gridCell(a, false));
 actionsWrap.addView(row1, new LinearLayout.LayoutParams(-1, -2));
 actionsWrap.addView(row2, new LinearLayout.LayoutParams(-1, -2));
 bindFavoriteAct(a, s, video, favAct);
 }

 private static LinearLayout.LayoutParams gridCell(MainActivity a, boolean left) {
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(48), 1f);
 lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 return lp;
 }

 private static Button actionButton(
 MainActivity a, String label, String tag, Runnable action) {
 final Button b = a.button(label, tag, action);
 b.setSingleLine(true);
 b.setEllipsize(TextUtils.TruncateAt.END);
 b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
 b.setMinWidth(0);
 b.setMinimumWidth(0);
 b.setMinHeight(a.dp(48));
 b.setMinimumHeight(a.dp(48));
 b.setPadding(a.dp(12), 0, a.dp(12), 0);
 b.setBackground(Skin.card(a.dp(12), a.dp(1)));
 b.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 v,
 focused,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 12,
 0xffe6edf9,
 MainActivity.DARK,
 false,
 1.03f));
 return b;
 }

 /** 顶部 与操作区「收藏」同步。 */
 private static void bindFavoriteAct(
 final MainActivity a,
 final Cms.Site s,
 final Cms.Video v,
 final Button top) {
 final Button act = findTag(a, "detail-act-favorite");
 final Runnable[] sync = new Runnable[1];
 sync[0] =
 () -> {
 boolean fav =
 Favorites.contains(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v.id);
 if (act != null) {
 act.setText(fav ? " 已收藏" : " 收藏");
 }
 top.setText(fav ? " 已收藏" : " 加入收藏");
 DetailScreen.styleGlassButton(a, top, top.hasFocus(), fav);
 };
 top.setOnClickListener(
 view -> {
 a.prefs
 .edit()
 .putString(
 Prefs.FAVORITES,
 Favorites.toggle(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v))
 .apply();
 sync[0].run();
 });
 if (act != null)
 act.setOnClickListener(
 view -> {
 a.prefs
 .edit()
 .putString(
 Prefs.FAVORITES,
 Favorites.toggle(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v))
 .apply();
 sync[0].run();
 });
 sync[0].run();
 }

 private static Button findTag(MainActivity a, String tag) {
 View v = a.root == null ? null : a.root.findViewWithTag(tag);
 return v instanceof Button ? (Button) v : null;
 }

 private static void populatePills(MainActivity a, LinearLayout pillsRow, Cms.Video v) {
 pillsRow.removeAllViews();
 List<String> pills = DetailScreen.collectMetaPills(v);
 int max = Math.min(4, pills.size());
 for (int i = 0; i < max; i++) {
 TextView pill = DetailScreen.makeGlassMetaPill(a, pills.get(i));
 pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 if (i > 0) lp.leftMargin = a.dp(8);
 pillsRow.addView(pill, lp);
 }
 }

 private static void populateSynopsis(
 MainActivity a, LinearLayout textWrap, LinearLayout wrap, Cms.Video v) {
 textWrap.removeAllViews();
 if (v.description == null || v.description.trim().isEmpty()) return;
 final boolean[] expanded = {false};
 final TextView synopsis = a.text(v.description, 14, Skin.TEXT_SECONDARY_T);
 synopsis.setLineSpacing(a.dp(2), 1.45f);
 synopsis.setMaxLines(4);
 synopsis.setEllipsize(TextUtils.TruncateAt.END);
 textWrap.addView(synopsis, new LinearLayout.LayoutParams(-1, -2));
 final Button toggle =
 DetailScreen.glassNavButton(
 a, "展开完整简介 ›", "synopsis-toggle", false, () -> {});
 toggle.setTag("synopsis-toggle");
 toggle.setOnClickListener(
 x -> {
 expanded[0] = !expanded[0];
 synopsis.setMaxLines(expanded[0] ? Integer.MAX_VALUE : 4);
 synopsis.setEllipsize(expanded[0] ? null : TextUtils.TruncateAt.END);
 toggle.setText(expanded[0] ? "收起简介 ‹" : "展开完整简介 ›");
 DetailScreen.styleGlassButton(a, toggle, toggle.hasFocus(), false);
 });
 LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-2, a.dp(44));
 toggleLp.topMargin = a.dp(4);
 wrap.addView(toggle, toggleLp);
 }

 static String actorLine(String actor) {
 return actor == null || actor.trim().isEmpty() ? "" : "演员：" + actor.trim();
 }

 static String directorLine(String director) {
 return director == null || director.trim().isEmpty() ? "" : "导演：" + director.trim();
 }

 /** 1.9.62：详情页完整显式 nextFocus 链（播放/收藏/返回/线路/选集/简介/复制/全部剧集）。 */
 static void wireDetailFocusChain(MainActivity a) {
 if (a == null || a.root == null) return;
 View back = a.root.findViewWithTag("detail-back");
 View fav = a.root.findViewWithTag("detail-favorite");
 View play = a.root.findViewWithTag("detail-play");
 if (play == null) play = a.root.findViewWithTag("detail-play-big");
 View heroFocus = a.root.findViewWithTag("detail-hero");
 java.util.List<View> lines = collectByPrefix(a.root, "detail-line:", 64);
 java.util.List<View> episodes = collectByPrefix(a.root, "episode:", 512);
 View synopsis = a.root.findViewWithTag("synopsis-toggle");
 View copy = a.root.findViewWithTag("detail-act-copy");
 View allEpisodes = a.root.findViewWithTag("episodes-all");
 java.util.List<View> chain = new java.util.ArrayList<>();
 for (View v : new View[] {heroFocus, back, fav, play, synopsis, copy, allEpisodes})
 if (v != null) chain.add(v);
 chain.addAll(lines);
 chain.addAll(episodes);
 for (View v : chain) if (v.getId() == View.NO_ID) v.setId(View.generateViewId());

 if (play != null) {
 if (fav != null) play.setNextFocusUpId(fav.getId());
 if (!lines.isEmpty()) {
 play.setNextFocusDownId(lines.get(0).getId());
 play.setNextFocusLeftId(lines.get(0).getId());
 }
 if (fav != null) play.setNextFocusRightId(fav.getId());
 }
 // 1.9.118：hero 视频/海报区 DOWN→播放按钮，RIGHT→收藏；播放/返回 UP→hero
 if (heroFocus != null) {
 if (play != null) {
 heroFocus.setNextFocusDownId(play.getId());
 play.setNextFocusUpId(heroFocus.getId());
 }
 if (fav != null) heroFocus.setNextFocusRightId(fav.getId());
 if (back != null) heroFocus.setNextFocusUpId(back.getId());
 if (back != null) back.setNextFocusDownId(heroFocus.getId());
 if (!lines.isEmpty()) lines.get(0).setNextFocusUpId(heroFocus.getId());
 }
 if (fav != null) {
 if (back != null) fav.setNextFocusLeftId(back.getId());
 if (play != null) fav.setNextFocusDownId(play.getId());
 View right = synopsis != null ? synopsis : copy;
 if (right != null) fav.setNextFocusRightId(right.getId());
 }
 if (back != null) {
 if (fav != null) back.setNextFocusRightId(fav.getId());
 if (play != null) back.setNextFocusDownId(play.getId());
 }
 for (int i = 0; i < lines.size(); i++) {
 View line = lines.get(i);
 if (play != null) line.setNextFocusUpId(play.getId());
 if (i + 1 < lines.size()) line.setNextFocusDownId(lines.get(i + 1).getId());
 else if (!episodes.isEmpty()) line.setNextFocusDownId(episodes.get(0).getId());
 if (!episodes.isEmpty()) line.setNextFocusRightId(episodes.get(0).getId());
 if (i == 0 && back != null) line.setNextFocusUpId(play == null ? back.getId() : play.getId());
 }
 if (!lines.isEmpty() && !episodes.isEmpty()) {
 episodes.get(0).setNextFocusUpId(lines.get(lines.size() - 1).getId());
 }
 for (int i = 0; i < episodes.size(); i++) {
 View ep = episodes.get(i);
 if (i - 1 >= 0) ep.setNextFocusLeftId(episodes.get(i - 1).getId());
 if (i + 1 < episodes.size()) ep.setNextFocusRightId(episodes.get(i + 1).getId());
 // 2 列选集：上下按行移动
 if (i - 2 >= 0) ep.setNextFocusUpId(episodes.get(i - 2).getId());
 if (i + 2 < episodes.size()) ep.setNextFocusDownId(episodes.get(i + 2).getId());
 else if (allEpisodes != null) ep.setNextFocusDownId(allEpisodes.getId());
 }
 if (synopsis != null) {
 if (copy != null) synopsis.setNextFocusDownId(copy.getId());
 else if (play != null) synopsis.setNextFocusDownId(play.getId());
 if (fav != null) synopsis.setNextFocusLeftId(fav.getId());
 }
 if (copy != null) {
 if (allEpisodes != null) copy.setNextFocusDownId(allEpisodes.getId());
 if (synopsis != null) copy.setNextFocusUpId(synopsis.getId());
 }
 if (allEpisodes != null) {
 if (copy != null) allEpisodes.setNextFocusUpId(copy.getId());
 else if (synopsis != null) allEpisodes.setNextFocusUpId(synopsis.getId());
 }
 // 1.9.96：小窗显示时把 mini 控制区纳入详情焦点链，保证上下左右都不卡死
 View miniPlay = a.root.findViewWithTag("mini-play");
 View miniExpand = a.root.findViewWithTag("mini-expand");
 View miniStop = a.root.findViewWithTag("mini-stop");
 View miniVideo = a.root.findViewWithTag("mini-video");
 if (miniVideo != null) {
 if (miniVideo.getId() == View.NO_ID) miniVideo.setId(View.generateViewId());
 if (fav != null) miniVideo.setNextFocusRightId(fav.getId());
 if (play != null) miniVideo.setNextFocusDownId(play.getId());
 if (back != null) miniVideo.setNextFocusUpId(back.getId());
 if (back != null) back.setNextFocusDownId(miniVideo.getId());
 if (fav != null) fav.setNextFocusLeftId(miniVideo.getId());
 // 1.9.115：详情各分区都能重新进入小窗
 if (play != null) {
 play.setNextFocusLeftId(miniVideo.getId());
 play.setNextFocusUpId(miniVideo.getId());
 }
 if (synopsis != null) synopsis.setNextFocusLeftId(miniVideo.getId());
 if (copy != null) copy.setNextFocusLeftId(miniVideo.getId());
 if (!lines.isEmpty()) lines.get(0).setNextFocusUpId(miniVideo.getId());
 if (!episodes.isEmpty()) episodes.get(0).setNextFocusUpId(miniVideo.getId());
 if (allEpisodes != null) allEpisodes.setNextFocusUpId(miniVideo.getId());
 AppLog.i("MiniFocus", "root focus=mini-video chain=true");
 }
 if (miniPlay != null) {
 if (miniPlay.getId() == View.NO_ID) miniPlay.setId(View.generateViewId());
 if (miniExpand != null && miniExpand.getId() == View.NO_ID)
 miniExpand.setId(View.generateViewId());
 if (miniStop != null && miniStop.getId() == View.NO_ID)
 miniStop.setId(View.generateViewId());
 if (back != null) miniPlay.setNextFocusUpId(back.getId());
 if (play != null) miniPlay.setNextFocusDownId(play.getId());
 if (miniExpand != null) miniPlay.setNextFocusRightId(miniExpand.getId());
 if (miniExpand != null) {
 miniExpand.setNextFocusLeftId(miniPlay.getId());
 if (miniStop != null) miniExpand.setNextFocusRightId(miniStop.getId());
 if (back != null) miniExpand.setNextFocusUpId(back.getId());
 if (play != null) miniExpand.setNextFocusDownId(play.getId());
 }
 if (miniStop != null) {
 miniStop.setNextFocusLeftId(
 miniExpand != null ? miniExpand.getId() : miniPlay.getId());
 if (back != null) miniStop.setNextFocusUpId(back.getId());
 if (play != null) miniStop.setNextFocusDownId(play.getId());
 }
 if (play != null) play.setNextFocusUpId(miniPlay.getId());
 }
 AppLog.i("DetailFocus", "chain mini=" + (miniPlay != null) + " episodes=" + episodes.size());
 AppLog.i(
 "TvFocus",
 "chain play="
 + (play != null)
 + " lines="
 + lines.size()
 + " episodes="
 + episodes.size()
 + " fav="
 + (fav != null)
 + " back="
 + (back != null));
 // 1.9.99：线路/选集/全部剧集统一补焦点环，并在获得焦点时滚动到可见
 java.util.List<View> rowItems = new java.util.ArrayList<>();
 rowItems.addAll(lines);
 rowItems.addAll(episodes);
 if (allEpisodes != null) rowItems.add(allEpisodes);
 for (final View item : rowItems) {
 TvNav.focusRing(a, item, 12);
 final View.OnFocusChangeListener base = item.getOnFocusChangeListener();
 item.setOnFocusChangeListener(
 (vv, focused) -> {
 if (base != null) base.onFocusChange(vv, focused);
 if (focused && a.root != null)
 vv.requestRectangleOnScreen(
 new android.graphics.Rect(
 0, -a.dp(24), vv.getWidth(), vv.getHeight() + a.dp(48)),
 true);
 });
 }
 for (final View v : chain) {
 v.setOnKeyListener(
 (view, keyCode, event) -> {
 if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
 int direction = View.FOCUS_DOWN;
 if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)
 direction = View.FOCUS_UP;
 else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
 direction = View.FOCUS_LEFT;
 else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
 direction = View.FOCUS_RIGHT;
 else if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
 View next = view.focusSearch(direction);
 AppLog.i(
 "DetailFocus",
 "from="
 + String.valueOf(view.getTag())
 + " dir="
 + (direction == View.FOCUS_UP
 ? "up"
 : direction == View.FOCUS_DOWN
 ? "down"
 : direction == View.FOCUS_LEFT ? "left" : "right")
 + " to="
 + (next == null || next == view
 ? "none"
 : String.valueOf(next.getTag())));
 return false;
 });
 }
 }

 private static java.util.List<View> collectByPrefix(View view, String prefix, int limit) {
 java.util.List<View> out = new java.util.ArrayList<>();
 collectByPrefix(view, prefix, limit, out);
 return out;
 }

 private static void collectByPrefix(
 View view, String prefix, int limit, java.util.List<View> out) {
 if (view == null || out.size() >= limit) return;
 if (view.getTag() instanceof String
 && ((String) view.getTag()).startsWith(prefix)
 && view.isFocusable()
 && view.isShown()) out.add(view);
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount() && out.size() < limit; i++)
 collectByPrefix(group.getChildAt(i), prefix, limit, out);
 }
 }

 /** 1.9.60/1.9.62：首焦点顺序：播放 → 线路第一项 → 返回 → 收藏（收藏绝不优先）。 */
 static View firstDetailFocus(MainActivity a) {
 if (a == null || a.root == null) return null;
 // 1.9.118：首焦点=左上视频/海报区
 View hero = a.root.findViewWithTag("detail-hero");
 if (hero != null && hero.isShown() && hero.isFocusable()) return hero;
 View play = a.root.findViewWithTag("detail-play");
 if (play != null && play.isShown()) return play;
 View line = findByTagPrefix(a.root, "detail-line:");
 if (line != null) return line;
 View back = a.root.findViewWithTag("detail-back");
 if (back != null && back.isShown()) return back;
 View fav = a.root.findViewWithTag("detail-favorite");
 if (fav != null && fav.isShown()) return fav;
 return null;
 }

 private static View findByTagPrefix(View view, String prefix) {
 if (view == null) return null;
 if (view.getTag() instanceof String
 && ((String) view.getTag()).startsWith(prefix)
 && view.isShown()
 && view.isFocusable()) return view;
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++) {
 View found = findByTagPrefix(group.getChildAt(i), prefix);
 if (found != null) return found;
 }
 }
 return null;
 }

 private static void focusFirstDetail(MainActivity a, String reason) {
 View first = firstDetailFocus(a);
 if (first == null) {
 AppLog.i("TvFocus", "page=detail first=none reason=" + reason);
 return;
 }
 first.requestFocus();
 a.autoFocus = first;
 AppLog.i(
 "TvFocus",
 "page=detail first="
 + String.valueOf(first.getTag())
 + " reason="
 + reason);
 }

 /** 1.9.52/1.9.60：兜底把详情页可交互控件补成可聚焦，并加可见焦点描边。 */
 static int ensureFocusables(MainActivity a, View view, int fixed) {
 if (view == null) return fixed;
 if (view.isClickable()
 && !view.isFocusable()
 && !(view instanceof ScrollView)
 && !(view instanceof android.widget.HorizontalScrollView)) {
 view.setFocusable(true);
 view.setFocusableInTouchMode(true);
 view.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 v,
 focused,
 0x0016283f,
 0x66ffffff,
 12,
 0xffffffff,
 0xff070d18,
 true,
 1.03f));
 fixed++;
 }
 if (view instanceof ViewGroup) {
 ViewGroup group = (ViewGroup) view;
 for (int i = 0; i < group.getChildCount(); i++)
 fixed = ensureFocusables(a, group.getChildAt(i), fixed);
 }
 return fixed;
 }
}
