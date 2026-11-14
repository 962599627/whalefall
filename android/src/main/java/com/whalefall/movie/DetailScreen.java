package com.whalefall.movie;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immersive TV & portrait video detail screen.
 *
 * <p>Redesigned with a mobile-portrait-inspired full-bleed hero poster stage (backed by 16:9 HD
 * widescreen stills or unstretched height-fitted vertical posters over a dynamic ambient color
 * field) and a full-width multi-line episode grid where every episode displays its complete title
 * without a separate focused-title bar.
 */
final class DetailScreen {
 private static final Pattern SCORE_PATTERN = Pattern.compile("(?<!\\d)([1-9]\\.[0-9])(?!\\d)");

 private DetailScreen() {}

 static void show(MainActivity a, Cms.Site s, Cms.Video listing, Cms.Video ready) {
 // 1.4.1：TV 详情页改为平板式 52/48 两栏（左海报+线路、右信息+操作+选集）。
 TvDetailLayout.show(a, s, listing, ready);
 }

 static void bindFavoriteAction(MainActivity a, Cms.Site s, Cms.Video v, Button favorite) {
 boolean isFav = Favorites.contains(a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v.id);
 favorite.setText(isFav ? " 已收藏" : " 加入收藏");
 styleGlassButton(a, favorite, favorite.hasFocus(), isFav);
 favorite.setOnClickListener(
 view -> {
 a.prefs
 .edit()
 .putString(
 Prefs.FAVORITES,
 Favorites.toggle(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v))
 .apply();
 boolean nowFav =
 Favorites.contains(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v.id);
 favorite.setText(nowFav ? " 已收藏" : " 加入收藏");
 styleGlassButton(a, favorite, favorite.hasFocus(), nowFav);
 });
 favorite.setOnFocusChangeListener(
 (view, focus) -> {
 boolean nowFav =
 Favorites.contains(
 a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, v.id);
 styleGlassButton(a, favorite, focus, nowFav);
 });
 }

 static Button glassNavButton(
 MainActivity a, String label, String tag, final boolean activeAccent, Runnable action) {
 final Button b = a.button(label, tag, action);
 b.setSingleLine(true);
 b.setEllipsize(TextUtils.TruncateAt.END);
 b.setMinWidth(0);
 b.setMinimumWidth(0);
 b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
 b.setMinHeight(a.dp(36));
 b.setMinimumHeight(a.dp(36));
 b.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
 b.setClipToOutline(false);
 b.setOutlineProvider(null);
 styleGlassButton(a, b, false, activeAccent);
 b.setOnFocusChangeListener((view, focus) -> styleGlassButton(a, b, focus, activeAccent));
 b.addOnLayoutChangeListener(
 (v, l, t, r, bot, ol, ot, or, ob) -> {
 int h = bot - t;
 if (h > 0 && b.getBackground() instanceof GradientDrawable) {
 ((GradientDrawable) b.getBackground()).setCornerRadius(h / 2f);
 }
 });
 return b;
 }

 static void styleGlassButton(MainActivity a, Button b, boolean focused, boolean activeAccent) {
 FocusMotion.unclipAncestors(b);
 int pl = b.getPaddingLeft() > 0 ? b.getPaddingLeft() : a.dp(16);
 int pt = b.getPaddingTop() > 0 ? b.getPaddingTop() : a.dp(6);
 int pr = b.getPaddingRight() > 0 ? b.getPaddingRight() : a.dp(16);
 int pb = b.getPaddingBottom() > 0 ? b.getPaddingBottom() : a.dp(6);
 GradientDrawable d = new GradientDrawable();
 d.setShape(GradientDrawable.RECTANGLE);
 int h = b.getHeight() > 0 ? b.getHeight() : b.getMinimumHeight();
 d.setCornerRadius(h > 0 ? h / 2f : a.dp(19));
 if (focused) {
 d.setColor(MainActivity.WHITE);
 b.setTextColor(MainActivity.DARK);
 b.animate().scaleX(1.04f).scaleY(1.04f).setDuration(130).start();
 } else {
 // 1.9.322：未聚焦跟随卡片底色，强调态用 ACCENT_SOFT 细描边（不再写死深蓝灰）
 d.setColor((0xF2 << 24) | (Skin.cardFill & 0xffffff));
 d.setStroke(
 Math.max(1, a.dp(1)), activeAccent ? Skin.accentSoft() : Skin.CARD_STROKE);
 b.setTextColor(Skin.TEXT_PRIMARY_T);
 b.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
 }
 b.setBackground(d);
 b.setPadding(pl, pt, pr, pb);
 }

 static Button heroPlayButton(MainActivity a, String label, String tag, Runnable action) {
 final Button b = a.button(label, tag, action);
 b.setSingleLine(true);
 b.setEllipsize(TextUtils.TruncateAt.END);
 b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
 b.setTypeface(null, Typeface.BOLD);
 b.setMinWidth(a.dp(240));
 b.setMinimumWidth(a.dp(240));
 b.setMinHeight(a.dp(46));
 b.setMinimumHeight(a.dp(46));
 b.setPadding(a.dp(32), a.dp(9), a.dp(32), a.dp(9));
 b.setClipToOutline(false);
 b.setOutlineProvider(null);
 styleHeroPlayButton(a, b, false);
 b.setOnFocusChangeListener((v, focus) -> styleHeroPlayButton(a, b, focus));
 b.addOnLayoutChangeListener(
 (v, l, t, r, bot, ol, ot, or, ob) -> {
 int h = bot - t;
 if (h > 0 && b.getBackground() instanceof GradientDrawable) {
 ((GradientDrawable) b.getBackground()).setCornerRadius(h / 2f);
 }
 });
 return b;
 }

 static void styleHeroPlayButton(MainActivity a, Button b, boolean focused) {
 FocusMotion.unclipAncestors(b);
 GradientDrawable d = new GradientDrawable();
 d.setShape(GradientDrawable.RECTANGLE);
 int h = b.getHeight() > 0 ? b.getHeight() : b.getMinimumHeight();
 d.setCornerRadius(h > 0 ? h / 2f : a.dp(23));
 if (focused) {
 d.setColor(MainActivity.WHITE);
 b.setTextColor(0xff070d18);
 b.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140).start();
 } else {
 // 1.9.322：未聚焦深色卡片底（跟随外观设置的卡片底色），去掉写死的蓝色玻璃
 d.setColor((0xF2 << 24) | (Skin.cardFill & 0xffffff));
 d.setStroke(Math.max(1, a.dp(1)), Skin.CARD_STROKE);
 b.setTextColor(Skin.TEXT_PRIMARY_T);
 b.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
 }
 b.setBackground(d);
 b.setPadding(a.dp(32), a.dp(9), a.dp(32), a.dp(9));
 }

 static List<String> collectMetaPills(Cms.Video v) {
 List<String> pills = new ArrayList<>();
 if (v.year != null && !v.year.trim().isEmpty()) pills.add(v.year.trim());
 if (v.area != null && !v.area.trim().isEmpty()) pills.add(v.area.trim());
 if (v.tag != null && !v.tag.trim().isEmpty()) {
 String[] parts = v.tag.split("[/,，、\\s]+");
 int added = 0;
 for (String p : parts) {
 String clean = p.trim();
 if (!clean.isEmpty() && !pills.contains(clean)) {
 pills.add(clean);
 if (++added >= 3) break;
 }
 }
 }
 if (v.remarks != null && !v.remarks.trim().isEmpty()) {
 pills.add(v.remarks.trim());
 } else if (!v.lines.isEmpty() && !v.lines.get(0).episodes.isEmpty()) {
 pills.add("共 " + v.lines.get(0).episodes.size() + " 集");
 }
 return pills;
 }

 static TextView makeGlassMetaPill(MainActivity a, String text) {
 TextView tv = a.text(text, 12, 0xf0ffffff);
 tv.setSingleLine(true);
 tv.setPadding(a.dp(11), a.dp(4), a.dp(11), a.dp(4));
 GradientDrawable bg = new GradientDrawable();
 bg.setCornerRadius(a.dp(8));
 bg.setColor(0x52181c26);
 bg.setStroke(Math.max(1, a.dp(1)), 0x33ffffff);
 tv.setBackground(bg);
 return tv;
 }

 static LinearLayout buildRatingAndCastRow(MainActivity a, Cms.Site s, Cms.Video v) {
 LinearLayout row = a.row();
 row.setGravity(Gravity.CENTER_VERTICAL);

 TextView tmdb = a.text("TMDB", 10, 0xff0c1626);
 tmdb.setTypeface(null, Typeface.BOLD);
 tmdb.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
 GradientDrawable tmdbBg = new GradientDrawable();
 tmdbBg.setCornerRadius(a.dp(4));
 tmdbBg.setColor(0xfff5c518);
 tmdb.setBackground(tmdbBg);
 row.addView(tmdb);

 String scoreText = extractScore(v.remarks);
 TextView score = a.text(scoreText, 14, 0xffffd54f);
 score.setTypeface(null, Typeface.BOLD);
 score.setPadding(a.dp(6), 0, a.dp(10), 0);
 row.addView(score);

 TextView dou = a.text("豆", 10, MainActivity.WHITE);
 dou.setTypeface(null, Typeface.BOLD);
 dou.setPadding(a.dp(5), a.dp(2), a.dp(5), a.dp(2));
 GradientDrawable douBg = new GradientDrawable();
 douBg.setCornerRadius(a.dp(4));
 douBg.setColor(0xff00b51d);
 dou.setBackground(douBg);
 row.addView(dou);

 if (s != null && s.name != null && !s.name.isEmpty()) {
 TextView siteLabel = a.text(" · " + s.name, 13, 0xd0ebebf5);
 row.addView(siteLabel);
 }

 if (v.actor != null && !v.actor.trim().isEmpty()) {
 String cast = v.actor.trim().replaceAll("\\s+", " / ");
 if (cast.length() > 28) cast = cast.substring(0, 28) + "…";
 TextView actorTv = a.text(" 主演: " + cast, 13, 0xc8d8d8e2);
 actorTv.setSingleLine(true);
 actorTv.setEllipsize(TextUtils.TruncateAt.END);
 row.addView(actorTv);
 }
 return row;
 }

 static String extractScore(String remarks) {
 if (remarks != null) {
 Matcher m = SCORE_PATTERN.matcher(remarks);
 if (m.find()) return m.group(1);
 }
 return "8.6";
 }

 static String buildFullSynopsisMessage(Cms.Video v) {
 StringBuilder sb = new StringBuilder();
 if (v.director != null && !v.director.trim().isEmpty()) {
 sb.append("导演：").append(v.director.trim()).append("\n");
 }
 if (v.actor != null && !v.actor.trim().isEmpty()) {
 sb.append("主演：").append(v.actor.trim()).append("\n");
 }
 if (sb.length() > 0) sb.append("\n");
 sb.append(v.description == null ? "" : v.description.trim());
 return sb.toString();
 }

 static void renderEpisodes(
 MainActivity a,
 Cms.Site site,
 Cms.Video video,
 int lineIndex,
 int page,
 LinearLayout panel,
 String focusTag) {
 if (a.current != null)
 a.current.selection.select(
 video.lines.get(lineIndex).name,
 page,
 video.lines.get(lineIndex).episodes.size());
 panel.removeAllViews();
 Cms.Line line = video.lines.get(lineIndex);
 final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);

 // 1. Inline Playback Line Switcher Section
 LinearLayout lineHeader = a.row();
 lineHeader.setGravity(Gravity.CENTER_VERTICAL);
 lineHeader.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(6));
 TextView lineTitle = a.text("播放线路", 16, MainActivity.WHITE);
 lineTitle.setTypeface(null, Typeface.BOLD);
 lineHeader.addView(lineTitle);
 TextView lineSub = a.text(" " + video.lines.size() + " 条可用线路", 12, MainActivity.MUTED);
 lineHeader.addView(lineSub, new LinearLayout.LayoutParams(0, -2, 1f));
 Button prefLineBtn =
 a.navButton(
 "默认播放线路", "line-preferred", () -> SourcePreferences.chooseLine(a, video));
 lineHeader.addView(prefLineBtn);
 panel.addView(lineHeader, new LinearLayout.LayoutParams(-1, -2));

 CategoryFlow sources = new CategoryFlow(a, () -> {});
 sources.setTag("detail-line-buttons");
 sources.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(12));
 panel.addView(sources, new LinearLayout.LayoutParams(-1, -2));
 for (int n = 0; n < video.lines.size(); n++) {
 final int index = n;
 final boolean selected = n == lineIndex;
 final Button choice =
 a.button(
 video.lines.get(n).name,
 "detail-line:" + n,
 () -> {
 renderEpisodes(a, site, video, index, 0, panel, null);
 View play = a.root.findViewWithTag("detail-play");
 if (play instanceof Button) {
 ((Button) play).setText("► 开始播放");
 play.setOnClickListener(
 v -> {
 a.resumeAt = 0;
 a.resolvePlayer(
 site,
 video,
 video.lines.get(index),
 video.lines.get(index).episodes.get(0));
 });
 }
 View next = panel.findViewWithTag("detail-line:" + index);
 if (next != null) next.requestFocus();
 });
 choice.setSelected(selected);
 choice.setSingleLine(true);
 choice.setEllipsize(TextUtils.TruncateAt.END);
 choice.setMinWidth(0);
 choice.setMinimumWidth(0);
 choice.setTextSize(13);
 choice.setMinHeight(a.dp(36));
 choice.setMinimumHeight(a.dp(36));
 choice.setBackground(MobileDetailScreen.chipBg(a, 18f, selected));
 choice.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
 choice.setOnFocusChangeListener(
 (view, focus) -> {
 FocusMotion.style(
 a,
 choice,
 focus,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 18,
 Skin.TEXT_PRIMARY_T,
 MainActivity.DARK,
 false,
 1.03f);
 choice.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
 });
 sources.addView(choice);
 }

 // 2. Episode Section Header + Sort + Pagination Controls
 LinearLayout orderRow = a.row();
 orderRow.setGravity(Gravity.CENTER_VERTICAL);
 orderRow.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8));

 TextView epHeader = a.text("选集列表", 17, MainActivity.WHITE);
 epHeader.setTypeface(null, Typeface.BOLD);
 orderRow.addView(epHeader);

 TextView count =
 a.text(" 共 " + line.episodes.size() + " 集 · 完整剧集名称直显", 13, MainActivity.MUTED);
 orderRow.addView(count, new LinearLayout.LayoutParams(0, -2, 1));

 Button order =
 a.navButton(
 descending ? "↓ 倒序" : "↑ 正序",
 "episodes-order",
 () -> {
 boolean next = !descending;
 a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, next).apply();
 renderEpisodes(a, site, video, lineIndex, 0, panel, "episodes-order");
 });
 order.setSelected(descending);
 order.setContentDescription("选集排序：" + (descending ? "倒序，点击改为正序" : "正序，点击改为倒序"));
 orderRow.addView(order);
 panel.addView(orderRow, new LinearLayout.LayoutParams(-1, -2));

 int start = page * 50, end = Math.min(start + 50, line.episodes.size());

 // Range pills bar when there are > 50 episodes
 if (line.episodes.size() > 50) {
 LinearLayout pages = a.row();
 pages.setGravity(Gravity.CENTER_VERTICAL);
 pages.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(10));
 if (page > 0)
 a.addButton(
 pages,
 a.navButton(
 "上一组",
 "episodes-previous",
 () ->
 renderEpisodes(
 a, site, video, lineIndex, page - 1, panel, null)));
 if (end < line.episodes.size())
 a.addButton(
 pages,
 a.navButton(
 "下一组",
 "episodes-next",
 () -> {
 renderEpisodes(
 a, site, video, lineIndex, page + 1, panel, null);
 View first =
 panel.findViewWithTag(
 "episode:"
 + line.name
 + ":"
 + (descending
 ? line.episodes.size() - 1 - end
 : end));
 if (first != null) first.requestFocus();
 }));
 a.addButton(
 pages,
 a.navButton(
 "全部选集 (" + line.episodes.size() + ")",
 "episodes-all",
 () -> {
 String[] labels = new String[line.episodes.size()];
 for (int n = 0; n < labels.length; n++)
 labels[n] =
 line.episodes.get(
 descending
 ? line.episodes.size() - 1 - n
 : n)
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
 ? line.episodes
 .size()
 - 1
 - n
 : n)))
 .setNegativeButton("取消", null)
 .show();
 }));
 TextView rangeHint =
 a.text(" 当前显示第 " + (start + 1) + " - " + end + " 项", 12, MainActivity.MUTED);
 pages.addView(rangeHint);
 panel.addView(pages, new LinearLayout.LayoutParams(-1, -2));
 }

 // 3. Full-Title Multi-Line Episode Grid (No separate focused-title bar needed!)
 final int columns = computeColumnsPerRow(line, start, end, descending);
 Object batchToken = new Object();
 panel.setTag(batchToken);
 LinearLayout cells = a.vertical();
 cells.setClipChildren(false);
 cells.setClipToPadding(false);
 panel.addView(cells, new LinearLayout.LayoutParams(-1, -2));
 appendEpisodeCells(
 a,
 site,
 video,
 line,
 start,
 end,
 columns,
 cells,
 panel,
 batchToken,
 a.generation,
 descending,
 focusTag);
 if ("episodes-order".equals(focusTag)) order.requestFocus();
 }

 /**
 * Chooses the optimal number of columns per row on TV so every episode's full name fits
 * comfortably without truncation.
 */
 private static int computeColumnsPerRow(Cms.Line line, int start, int end, boolean descending) {
 int maxLen = 0;
 for (int i = start; i < end; i++) {
 int idx = descending ? line.episodes.size() - 1 - i : i;
 String name = line.episodes.get(idx).name;
 if (name != null && name.length() > maxLen) {
 maxLen = name.length();
 }
 }
 if (maxLen >= 24) return 3;
 if (maxLen >= 13) return 4;
 return 5;
 }

 static void appendEpisodeCells(
 MainActivity a,
 Cms.Site site,
 Cms.Video video,
 Cms.Line line,
 int start,
 int end,
 int columns,
 LinearLayout cells,
 LinearLayout panel,
 Object token,
 int ticket,
 boolean descending,
 String focusTag) {
 if (ticket != a.generation || panel.getTag() != token) return;
 int batchSize = columns * 4;
 int stop = Math.min(end, start + batchSize);
 LinearLayout row = null;
 final List<LinearLayout> createdRows = new ArrayList<>();
 for (int i = start; i < stop; i++) {
 if ((i - start) % columns == 0) {
 row = a.row();
 row.setGravity(Gravity.TOP);
 row.setClipChildren(false);
 row.setClipToPadding(false);
 cells.addView(row, new LinearLayout.LayoutParams(-1, -2));
 createdRows.add(row);
 }
 final int originalIndex = descending ? line.episodes.size() - 1 - i : i;
 final Cms.Episode episode = line.episodes.get(originalIndex);
 final Button button =
 a.button(
 episode.name,
 "episode:" + line.name + ":" + originalIndex,
 () -> {
 a.resumeAt = 0;
 a.resolvePlayer(site, video, line, episode);
 });
 button.setContentDescription(episode.name);
 // 1.9.402：选集格子无条件挂焦点环（之前只在 TV 分支绘制，手机/平板遥控看不到选中效果）
 try {
 TvNav.focusRing(a, button, 12);
 } catch (Throwable ignored) {
 }
 // Display the COMPLETE episode name directly inside the button (multi-line, no
 // ellipsis)
 button.setSingleLine(false);
 button.setMaxLines(6);
 button.setEllipsize(null);
 button.setIncludeFontPadding(false);
 button.setTextSize(TypedValue.COMPLEX_UNIT_SP, columns <= 3 ? 13.5f : 14f);
 button.setLineSpacing(a.dp(2), 1.06f);
 button.setGravity(
 episode.name != null && episode.name.length() > 12
 ? (Gravity.START | Gravity.CENTER_VERTICAL)
 : Gravity.CENTER);
 button.setMinHeight(a.dp(54));
 button.setMinimumHeight(a.dp(54));
 button.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
 button.setBackground(MobileDetailScreen.chipBg(a, 12f, false));
 button.setOnFocusChangeListener(
 (view, focus) -> {
 FocusMotion.style(
 a,
 button,
 focus,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 12,
 Skin.TEXT_PRIMARY_T,
 MainActivity.DARK,
 false,
 1.022f);
 if (focus && a.current != null) {
 a.current.episodeFocus = originalIndex;
 a.current.episodeFocusLine = line.name;
 }
 });
 LinearLayout.LayoutParams cell =
 new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
 cell.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(6));
 row.addView(button, cell);
 if (focusTag != null && focusTag.equals(button.getTag())) button.requestFocus();
 }
 if (row != null) {
 int rem = (stop - start) % columns;
 if (rem > 0) {
 for (int n = rem; n < columns; n++) {
 View spacer = new View(a);
 LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
 sp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(6));
 row.addView(spacer, sp);
 }
 }
 }
 // Equalize button heights within each row so multi-line titles align cleanly
 for (final LinearLayout createdRow : createdRows) {
 createdRow.post(
 () -> {
 int maxH = a.dp(54);
 for (int c = 0; c < createdRow.getChildCount(); c++) {
 View ch = createdRow.getChildAt(c);
 if (ch instanceof Button && ch.getHeight() > maxH) {
 maxH = ch.getHeight();
 }
 }
 for (int c = 0; c < createdRow.getChildCount(); c++) {
 View ch = createdRow.getChildAt(c);
 if (ch instanceof Button && ch.getHeight() < maxH) {
 ((Button) ch).setMinHeight(maxH);
 ((Button) ch).setMinimumHeight(maxH);
 }
 }
 });
 }
 if (stop < end)
 cells.postOnAnimation(
 () ->
 appendEpisodeCells(
 a,
 site,
 video,
 line,
 stop,
 end,
 columns,
 cells,
 panel,
 token,
 ticket,
 descending,
 focusTag));
 else if (a.current != null
 && a.current.focus != null
 && a.current.focus.startsWith("episode:")) {
 View focus = a.getCurrentFocus();
 Object tag = focus == null ? null : focus.getTag();
 if (focus == null || "detail-play".equals(tag) || "detail-back".equals(tag)) {
 View saved = panel.findViewWithTag(a.current.focus);
 if (saved != null) saved.requestFocus();
 }
 }
 }

 static void populateTvRelatedSeasons(
 final MainActivity a,
 final Cms.Site site,
 final Cms.Video currentVideo,
 final LinearLayout seasonPanel) {
 final MobileDetailScreen.SeasonMeta curMeta =
 MobileDetailScreen.parseSeasonMeta(currentVideo.title);
 if (!curMeta.explicit) return;
 final String baseKey =
 curMeta.baseTitle == null
 ? ""
 : curMeta.baseTitle
 .toLowerCase()
 .replaceAll("[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]", "")
 .trim();
 if (baseKey.isEmpty()) return;
 final int ticket = a.generation;
 seasonPanel.postDelayed(
 () -> {
 if (a.isFinishing() || a.isDestroyed() || ticket != a.generation) return;
 a.submitPage(
 () -> {
 try {
 List<Cms.Video> found =
 Cms.listing(
 SourceController.engine(a, site)
 .search(curMeta.baseTitle, 1))
 .videos;
 if (found == null || found.isEmpty()) return;
 final Map<Integer, Cms.Video> bySeason = new TreeMap<>();
 final Map<Integer, String> labels = new TreeMap<>();
 bySeason.put(curMeta.seasonNumber, currentVideo);
 labels.put(curMeta.seasonNumber, curMeta.seasonLabel);
 for (Cms.Video cand : found) {
 if (cand == null || cand.title == null) continue;
 MobileDetailScreen.SeasonMeta candMeta =
 MobileDetailScreen.parseSeasonMeta(cand.title);
 String candKey =
 candMeta.baseTitle
 .toLowerCase()
 .replaceAll(
 "[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]",
 "")
 .trim();
 if (!baseKey.equals(candKey)) continue;
 if (!curMeta.explicit
 && !candMeta.explicit
 && !Objects.equals(cand.id, currentVideo.id)) {
 continue;
 }
 if (!bySeason.containsKey(candMeta.seasonNumber)) {
 bySeason.put(candMeta.seasonNumber, cand);
 labels.put(candMeta.seasonNumber, candMeta.seasonLabel);
 }
 }
 if (bySeason.size() < 2) return;
 final List<Integer> seasonNums =
 new ArrayList<>(bySeason.keySet());
 a.ui.post(
 () -> {
 if (a.isFinishing()
 || a.isDestroyed()
 || ticket != a.generation) return;
 seasonPanel.removeAllViews();
 a.gap(seasonPanel, 10);
 CategoryFlow flow = new CategoryFlow(a, () -> {});
 flow.setTag("detail-season-buttons");
 flow.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
 seasonPanel.addView(
 flow,
 new LinearLayout.LayoutParams(-1, -2));
 for (final Integer sNum : seasonNums) {
 final Cms.Video targetVid = bySeason.get(sNum);
 String sLabel = labels.get(sNum);
 boolean active =
 Objects.equals(
 targetVid.id,
 currentVideo.id)
 || sNum == curMeta.seasonNumber;
 Button btn =
 a.button(
 sLabel,
 "detail-season:" + sNum,
 () -> {
 if (!Objects.equals(
 targetVid.id,
 currentVideo.id)) {
 show(
 a, site,
 targetVid,
 null);
 }
 });
 btn.setSingleLine(true);
 btn.setMinWidth(0);
 btn.setMinimumWidth(0);
 btn.setMinHeight(a.dp(36));
 btn.setMinimumHeight(a.dp(36));
 btn.setPadding(
 a.dp(14), a.dp(6), a.dp(14), a.dp(6));
 // 1.9.322：季 chip 统一卡片底（未选中/选中只差 ACCENT_SOFT 描边）
 btn.setBackground(MobileDetailScreen.chipBg(a, 10f, active));
 btn.setPadding(
 a.dp(14), a.dp(6), a.dp(14),
 a.dp(6));
 btn.setOnFocusChangeListener(
 (v, f) -> {
 FocusMotion.style(
 a,
 btn,
 f,
 (0xE6 << 24) | (Skin.cardFill & 0xffffff),
 MainActivity.WHITE,
 10,
 Skin.TEXT_PRIMARY_T,
 MainActivity.DARK,
 true,
 1.02f);
 btn.setPadding(
 a.dp(14), a.dp(6),
 a.dp(14), a.dp(6));
 });
 flow.addView(btn);
 }
 });
 } catch (Exception ignored) {
 }
 });
 },
 450);
 }
}
