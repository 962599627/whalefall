package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Dedicated mobile and tablet video detail screen designed for touch interaction, horizontal
 * scrollable line/group bars, expandable synopsis card, and touch-friendly episode grid.
 */
final class MobileDetailScreen {
    private MobileDetailScreen() {}

    static void bindTouchFeedback(View v) {
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setOnTouchListener(
                (view, event) -> {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        view.setAlpha(0.72f);
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        view.setAlpha(1.0f);
                    }
                    return false;
                });
    }

    static void show(MainActivity a, Cms.Site s, Cms.Video listing, Cms.Video ready) {
        if (a.redirectListing(s, listing)) return;

        a.clearPlaybackLoading();
        a.cancelArtwork();
        a.cancelPageRequests();
        if (a.current != null) a.current.home = false;
        a.stopWeb();
        a.stopMagnet();
        a.releasePlayer();
        a.generation++;

        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(0xff0f1015);
        a.setContentView(a.root);

        final ImmersivePoster ambientCover = new ImmersivePoster(a, "detail-ambient-cover");
        a.root.addView(ambientCover.backdrop, new FrameLayout.LayoutParams(-1, -1));
        if (listing.poster != null && !listing.poster.isEmpty()) {
            ambientCover.load(listing.poster);
        }

        LinearLayout page = a.vertical();
        a.root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Mobile Top AppBar
        LinearLayout appBar = a.row();
        appBar.setPadding(a.dp(12), a.dp(10), a.dp(14), a.dp(10));
        appBar.setBackgroundColor(0xff15161e);

        TextView backBtn = a.text("‹ 返回", 15, MainActivity.WHITE);
        backBtn.setTag("detail-back");
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(a.dp(12), a.dp(6), a.dp(14), a.dp(6));
        backBtn.setBackground(a.bg(0xff232530, 18));
        bindTouchFeedback(backBtn);
        backBtn.setOnClickListener(v -> a.onBackPressed());
        appBar.addView(backBtn, new LinearLayout.LayoutParams(-2, a.dp(36)));

        TextView topTitle = a.text(listing.title, 16, MainActivity.WHITE);
        topTitle.setTypeface(null, Typeface.BOLD);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams topTitleLp = new LinearLayout.LayoutParams(0, -2, 1);
        topTitleLp.setMargins(a.dp(10), 0, a.dp(10), 0);
        appBar.addView(topTitle, topTitleLp);

        final TextView favTopBtn = a.text("☆ 收藏", 13, MainActivity.WHITE);
        favTopBtn.setTag("detail-favorite");
        favTopBtn.setGravity(Gravity.CENTER);
        favTopBtn.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        bindTouchFeedback(favTopBtn);
        appBar.addView(favTopBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));

        page.addView(appBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(a);
        scrollView.setTag("detail-scroll");
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        a.scroll = scrollView;
        page.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = a.vertical();
        content.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(36));
        scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));

        // Hero Row: Poster on left + Info & Play Button on right
        LinearLayout heroCard = a.row();
        heroCard.setTag("detail-hero");
        heroCard.setGravity(Gravity.TOP);
        heroCard.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
        heroCard.setBackground(a.bg(0xff181922, 18));
        content.addView(heroCard, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout posterWrap = new FrameLayout(a);
        posterWrap.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[] {0xff2d4464, 0xff151c28}));
        GradientDrawable posterMask = a.bg(0xff232530, 12);
        posterWrap.setBackground(posterMask);
        posterWrap.setClipToOutline(true);
        ImageView posterImg = new ImageView(a);
        posterImg.setTag("detail-cover");
        posterImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterWrap.addView(posterImg, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(108), a.dp(154));
        posterLp.rightMargin = a.dp(14);
        heroCard.addView(posterWrap, posterLp);
        if (listing.poster != null && !listing.poster.isEmpty()) {
            a.poster(listing.poster, posterImg, true);
        }

        LinearLayout rightCol = a.vertical();
        heroCard.addView(rightCol, new LinearLayout.LayoutParams(0, -2, 1));

        TextView titleView = a.text(listing.title, 19, MainActivity.WHITE);
        titleView.setTag("detail-title");
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        rightCol.addView(titleView);

        TextView sourceTag = a.text("片源：" + s.name, 12, 0xff8ab4f8);
        sourceTag.setPadding(0, a.dp(6), 0, 0);
        rightCol.addView(sourceTag);

        TextView metaView = a.text("", 12, MainActivity.MUTED);
        metaView.setPadding(0, a.dp(6), 0, 0);
        metaView.setMaxLines(2);
        rightCol.addView(metaView);

        TextView actorView = a.text("", 12, 0xffb4b6c2);
        actorView.setPadding(0, a.dp(5), 0, 0);
        actorView.setMaxLines(2);
        actorView.setEllipsize(TextUtils.TruncateAt.END);
        rightCol.addView(actorView);

        LinearLayout playBtnWrap = a.vertical();
        playBtnWrap.setPadding(0, a.dp(12), 0, 0);
        rightCol.addView(playBtnWrap, new LinearLayout.LayoutParams(-1, -2));

        TextView loadingState = a.text("正在加载剧集列表…", 14, MainActivity.MUTED);
        loadingState.setTag("detail-loading");
        loadingState.setPadding(a.dp(4), a.dp(18), a.dp(4), a.dp(10));
        content.addView(loadingState);

        LinearLayout detailsBody = a.vertical();
        content.addView(detailsBody, new LinearLayout.LayoutParams(-1, -2));

        Runnable syncFavStyle =
                () -> {
                    boolean faved =
                            Favorites.contains(
                                    a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, listing.id);
                    favTopBtn.setText(faved ? "★ 已收藏" : "☆ 收藏");
                    favTopBtn.setTextColor(faved ? 0xffffd166 : MainActivity.WHITE);
                    favTopBtn.setBackground(a.bg(faved ? 0xff332b1a : 0xff232530, 18));
                };
        syncFavStyle.run();

        MainActivity.Done<Cms.Video> present =
                v -> {
                    if (v.poster == null || v.poster.isEmpty()) v.poster = listing.poster;
                    if (v.title == null || v.title.isEmpty()) v.title = listing.title;
                    titleView.setText(v.title);
                    topTitle.setText(v.title);
                    if (!Objects.equals(v.poster, listing.poster)
                            && v.poster != null
                            && !v.poster.isEmpty()) {
                        a.poster(v.poster, posterImg, true);
                        ambientCover.load(v.poster);
                    }
                    content.removeView(loadingState);
                    detailsBody.removeAllViews();
                    playBtnWrap.removeAllViews();

                    String metaText =
                            ((v.year == null ? "" : v.year)
                                            + "  "
                                            + (v.area == null ? "" : v.area)
                                            + "  "
                                            + (v.remarks == null ? "" : v.remarks))
                                    .trim();
                    metaView.setText(metaText.isEmpty() ? "高清点播" : metaText);
                    if (v.actor != null && !v.actor.isEmpty()) {
                        actorView.setText("主演：" + v.actor);
                        actorView.setVisibility(View.VISIBLE);
                    } else {
                        actorView.setVisibility(View.GONE);
                    }

                    favTopBtn.setOnClickListener(
                            view -> {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.FAVORITES,
                                                Favorites.toggle(
                                                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                        s.key,
                                                        v))
                                        .apply();
                                boolean faved =
                                        Favorites.contains(
                                                a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                s.key,
                                                v.id);
                                favTopBtn.setText(faved ? "★ 已收藏" : "☆ 收藏");
                                favTopBtn.setTextColor(faved ? 0xffffd166 : MainActivity.WHITE);
                                favTopBtn.setBackground(a.bg(faved ? 0xff332b1a : 0xff232530, 18));
                            });

                    // Expandable Synopsis Card
                    if (v.description != null && !v.description.trim().isEmpty()) {
                        LinearLayout descCard = a.vertical();
                        descCard.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
                        descCard.setBackground(a.bg(0xff181922, 14));
                        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
                        descLp.topMargin = a.dp(12);
                        detailsBody.addView(descCard, descLp);

                        final TextView descText = a.text(v.description.trim(), 13, 0xffd1d5db);
                        descText.setLineSpacing(a.dp(4), 1f);
                        descText.setMaxLines(3);
                        descText.setEllipsize(TextUtils.TruncateAt.END);
                        descCard.addView(descText);

                        final TextView expandHint = a.text("点击展开完整简介 ▾", 11, 0xff8ab4f8);
                        expandHint.setPadding(0, a.dp(6), 0, 0);
                        descCard.addView(expandHint);

                        final boolean[] expanded = {false};
                        descCard.setOnClickListener(
                                view -> {
                                    expanded[0] = !expanded[0];
                                    descText.setMaxLines(expanded[0] ? 100 : 3);
                                    expandHint.setText(expanded[0] ? "收起简介 ▴" : "点击展开完整简介 ▾");
                                });
                    }

                    v.lines.removeIf(line -> line.episodes.isEmpty());
                    if (v.lines.isEmpty()) {
                        TextView empty = a.text("暂无可识别的播放线路。", 14, MainActivity.MUTED);
                        empty.setPadding(a.dp(4), a.dp(20), a.dp(4), a.dp(20));
                        detailsBody.addView(empty);
                        return;
                    }

                    WatchHistory.Entry bookmark = null;
                    for (WatchHistory.Entry entry : a.watchHistory()) {
                        if (entry.site.equals(s.key) && entry.id.equals(v.id)) {
                            bookmark = entry;
                            break;
                        }
                    }
                    final WatchHistory.Entry remembered = bookmark;

                    Button playBtn = new Button(a);
                    playBtn.setTag("detail-play");
                    playBtn.setAllCaps(false);
                    playBtn.setTextSize(14);
                    playBtn.setTypeface(null, Typeface.BOLD);
                    playBtn.setTextColor(Color.WHITE);
                    playBtn.setBackground(a.bg(0xff3b82f6, 20));
                    playBtn.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
                    bindTouchFeedback(playBtn);
                    playBtn.setText(remembered == null ? "▶ 立即播放" : "▶ 续播 " + remembered.episode);
                    playBtn.setOnClickListener(
                            view -> {
                                if (remembered != null) a.playBookmark(s, v, remembered);
                                else
                                    a.resolvePlayer(
                                            s, v, v.lines.get(0), v.lines.get(0).episodes.get(0));
                            });
                    playBtnWrap.addView(playBtn, new LinearLayout.LayoutParams(-1, a.dp(40)));

                    int initLine = 0, initPage = 0;
                    if (remembered != null) {
                        for (int i = 0; i < v.lines.size(); i++) {
                            if (v.lines.get(i).name.equals(remembered.line)) {
                                initLine = i;
                                for (int n = 0; n < v.lines.get(i).episodes.size(); n++) {
                                    if (v.lines
                                            .get(i)
                                            .episodes
                                            .get(n)
                                            .name
                                            .equals(remembered.episode)) {
                                        boolean desc =
                                                a.prefs.getBoolean(
                                                        Prefs.EPISODES_DESCENDING, false);
                                        initPage =
                                                (desc ? v.lines.get(i).episodes.size() - 1 - n : n)
                                                        / 50;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }

                    LinearLayout episodePanel = a.vertical();
                    episodePanel.setTag("detail-episodes");
                    LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-1, -2);
                    epLp.topMargin = a.dp(14);
                    detailsBody.addView(episodePanel, epLp);

                    renderMobileEpisodes(a, s, v, initLine, initPage, episodePanel, remembered);
                };

        if (ready == null) a.loadDetail(s, listing.id, present);
        else {
            try {
                present.accept(ready);
            } catch (Exception e) {
                AppLog.w("MobileDetailScreen", "present detail failed", e);
            }
        }
    }

    static void renderMobileEpisodes(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            int lineIndex,
            int page,
            LinearLayout panel,
            WatchHistory.Entry remembered) {
        if (a.current != null) {
            a.current.selection.select(
                    video.lines.get(lineIndex).name,
                    page,
                    video.lines.get(lineIndex).episodes.size());
        }
        panel.removeAllViews();
        panel.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(16));
        panel.setBackground(a.bg(0xff181922, 16));

        Cms.Line line = video.lines.get(lineIndex);
        final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);

        // 1. Horizontal Scrollable Line Selector
        TextView lineHeader = a.text("播放线路 (" + video.lines.size() + ")", 14, MainActivity.WHITE);
        lineHeader.setTypeface(null, Typeface.BOLD);
        panel.addView(lineHeader);
        a.gap(panel, 8);

        HorizontalScrollView lineScroll = new HorizontalScrollView(a);
        lineScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout lineRow = a.row();
        lineScroll.addView(lineRow);
        panel.addView(lineScroll, new LinearLayout.LayoutParams(-1, -2));

        for (int n = 0; n < video.lines.size(); n++) {
            final int idx = n;
            boolean active = n == lineIndex;
            TextView chip = a.text(video.lines.get(n).name, 13, active ? Color.WHITE : 0xffa1a1aa);
            chip.setTag("detail-line:" + n);
            chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            chip.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
            chip.setBackground(a.bg(active ? 0xff3b82f6 : 0xff252734, 16));
            bindTouchFeedback(chip);
            chip.setOnClickListener(
                    v -> renderMobileEpisodes(a, site, video, idx, 0, panel, remembered));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.rightMargin = a.dp(8);
            lineRow.addView(chip, chipLp);
        }

        a.gap(panel, 14);

        // 2. Episode Count & Sort Toggle Row
        LinearLayout headerRow = a.row();
        TextView epTitle = a.text("选集 · 共 " + line.episodes.size() + " 集", 14, MainActivity.WHITE);
        epTitle.setTypeface(null, Typeface.BOLD);
        headerRow.addView(epTitle, new LinearLayout.LayoutParams(0, -2, 1));

        // Phone / tablet only: offline download of episodes from the current line.
        TextView downloadBtn = a.text("⬇ 下载", 12, 0xff8ab4f8);
        downloadBtn.setTag("episodes-download");
        downloadBtn.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
        downloadBtn.setBackground(a.bg(0xff252734, 14));
        bindTouchFeedback(downloadBtn);
        String pickFrom = "";
        if (remembered != null && line.name.equals(remembered.line)) {
            pickFrom = remembered.episode;
        }
        final String currentName = pickFrom;
        downloadBtn.setOnClickListener(
                v -> MobileDownloads.pick(a, site, video, line, currentName));
        LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(-2, -2);
        downloadLp.rightMargin = a.dp(8);
        headerRow.addView(downloadBtn, downloadLp);

        TextView sortBtn = a.text(descending ? "↓ 倒序排列" : "↑ 正序排列", 12, 0xff8ab4f8);
        sortBtn.setTag("episodes-order");
        sortBtn.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
        sortBtn.setBackground(a.bg(0xff252734, 14));
        bindTouchFeedback(sortBtn);
        sortBtn.setOnClickListener(
                v -> {
                    a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, !descending).apply();
                    renderMobileEpisodes(a, site, video, lineIndex, 0, panel, remembered);
                });
        headerRow.addView(sortBtn);
        panel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        // 3. Horizontal Scrollable Group Tabs (when > 50 episodes)
        int total = line.episodes.size();
        int groupCount = (total + 49) / 50;
        if (groupCount > 1) {
            a.gap(panel, 10);
            HorizontalScrollView groupScroll = new HorizontalScrollView(a);
            groupScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout groupRow = a.row();
            groupScroll.addView(groupRow);
            for (int g = 0; g < groupCount; g++) {
                final int targetGroup = g;
                boolean selectedGroup = g == page;
                int gStart = g * 50 + 1;
                int gEnd = Math.min(total, (g + 1) * 50);
                String label =
                        descending
                                ? (total - g * 50) + "-" + Math.max(1, total - gEnd + 1)
                                : gStart + "-" + gEnd;
                TextView tab = a.text(label, 12, selectedGroup ? Color.WHITE : MainActivity.MUTED);
                tab.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
                tab.setBackground(a.bg(selectedGroup ? 0xff3b6291 : 0xff232530, 14));
                bindTouchFeedback(tab);
                tab.setOnClickListener(
                        v ->
                                renderMobileEpisodes(
                                        a, site, video, lineIndex, targetGroup, panel, remembered));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = a.dp(8);
                groupRow.addView(tab, lp);
            }
            panel.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));
        }

        a.gap(panel, 12);

        // 4. Touch-optimized Episode Grid
        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int cols = widthDp >= 640 ? 5 : 3;
        int start = page * 50;
        int end = Math.min(start + 50, total);
        LinearLayout grid = a.vertical();
        panel.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout currentRow = null;
        for (int i = start; i < end; i++) {
            if ((i - start) % cols == 0) {
                currentRow = a.row();
                grid.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            int originalIdx = descending ? (total - 1 - i) : i;
            Cms.Episode ep = line.episodes.get(originalIdx);
            boolean isCurrentBookmark =
                    remembered != null
                            && line.name.equals(remembered.line)
                            && ep.name.equals(remembered.episode);

            TextView epBtn = a.text(ep.name, 13, isCurrentBookmark ? Color.WHITE : 0xffe4e4e7);
            epBtn.setTag("episode:" + line.name + ":" + originalIdx);
            epBtn.setGravity(Gravity.CENTER);
            epBtn.setSingleLine(true);
            epBtn.setEllipsize(TextUtils.TruncateAt.END);
            epBtn.setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10));
            epBtn.setBackground(a.bg(isCurrentBookmark ? 0xff3b82f6 : 0xff252734, 10));
            bindTouchFeedback(epBtn);
            epBtn.setOnClickListener(
                    v -> {
                        a.resumeAt = 0;
                        a.resolvePlayer(site, video, line, ep);
                    });
            epBtn.setOnLongClickListener(
                    v -> {
                        List<Cms.Episode> one = Collections.singletonList(ep);
                        MobileDownloads.enqueue(a, site, video, line, one);
                        return true;
                    });
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, a.dp(42), 1);
            cellLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            currentRow.addView(epBtn, cellLp);
        }
        if (currentRow != null) {
            int rem = (end - start) % cols;
            if (rem > 0) {
                for (int i = rem; i < cols; i++) {
                    LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                    blankLp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
                    currentRow.addView(new View(a), blankLp);
                }
            }
        }
    }
}
