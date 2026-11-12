package com.whalefall.movie;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 1.9.168：IPTV 二级频道选择器（自绘面板，不用 AppDialog）。
 *
 * <p>720×420（540p 安全区收缩），左分类 240 / 右频道 480，行 48dp；
 * 选中/收藏/当前台标记，空态与加载态叠层。
 */
final class IptvPickerPanel {
  private IptvPickerPanel() {}

  private static FrameLayout root;
  private static MainActivity host;

  static boolean isOpen() {
    return root != null;
  }

  static boolean handleBack() {
    if (root == null) return false;
    close();
    return true;
  }

  static void close() {
    if (root != null && root.getParent() instanceof ViewGroup)
      ((ViewGroup) root.getParent()).removeView(root);
    root = null;
    host = null;
  }

  static void show(final MainActivity a, final String currentName) {
    close();
    final TvLive.Store store = new TvLive.Store(a);
    final java.util.List<TvLive.Channel> all = store.group(TvLive.GROUP_IPTV);
    if (all.isEmpty()) {
      a.toast("暂无开源 IPTV 频道");
      return;
    }
    host = a;
    root = new FrameLayout(a);
    root.setTag("iptv-picker");
    root.setBackgroundColor(0x99000000);
    root.setClickable(true);
    FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(-1, -1);
    a.root.addView(root, rootLp);

    int safeH = a.windowHeight();
    final int panelW = Math.min(a.dp(720), Math.max(a.dp(480), a.windowWidth() - a.dp(48)));
    final int panelH = Math.min(a.dp(420), safeH - a.dp(24));
    final FrameLayout panel = new FrameLayout(a);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xE6070b14);
    bg.setCornerRadius(a.dp(20));
    bg.setStroke(Math.max(1, a.dp(1)), 0x33ffffff);
    panel.setBackground(bg);
    FrameLayout.LayoutParams panelLp =
        new FrameLayout.LayoutParams(panelW, panelH, Gravity.CENTER);
    root.addView(panel, panelLp);

    LinearLayout column = a.vertical();
    panel.addView(column, new FrameLayout.LayoutParams(-1, -1));

    // Header 48
    LinearLayout header = a.row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(a.dp(16), 0, a.dp(8), 0);
    TextView title = a.text("选择频道", 17, 0xfff5f7fc);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView closeBtn = a.text("×", 20, 0xffffb3c8);
    closeBtn.setGravity(Gravity.CENTER);
    closeBtn.setFocusable(true);
    closeBtn.setFocusableInTouchMode(true);
    closeBtn.setClickable(true);
    ExtraPages.tvFocus(a, closeBtn, 999);
    closeBtn.setOnClickListener(v -> close());
    header.addView(closeBtn, new LinearLayout.LayoutParams(a.dp(32), a.dp(32)));
    column.addView(header, new LinearLayout.LayoutParams(-1, a.dp(48)));

    LinearLayout body = a.row();
    body.setPadding(0, 0, 0, 0);
    column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

    // 左分类 240
    LinearLayout left = a.vertical();
    left.setPadding(0, a.dp(4), a.dp(6), a.dp(4));
    body.addView(left, new LinearLayout.LayoutParams(a.dp(240), -1));

    // 右频道 FrameLayout（列表 + 空态/加载叠层）
    FrameLayout rightFrame = new FrameLayout(a);
    body.addView(rightFrame, new LinearLayout.LayoutParams(0, -1, 1f));
    final LinearLayout rightList = a.vertical();
    ScrollView rightScroll = new ScrollView(a);
    rightScroll.setVerticalScrollBarEnabled(false);
    rightScroll.addView(rightList);
    rightFrame.addView(rightScroll, new FrameLayout.LayoutParams(-1, -1));
    final TextView overlay = a.text("", 14, 0xffc9d6e4);
    overlay.setGravity(Gravity.CENTER);
    overlay.setBackgroundColor(0xCC070b14);
    overlay.setVisibility(View.GONE);
    overlay.setClickable(true);
    overlay.setFocusable(true);
    rightFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

    // Footer 32
    TextView footer = a.text("左右切换分类 · 上下选台 · OK 播放 · 长按收藏", 11, 0x8899aabb);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    footer.setPadding(a.dp(16), 0, a.dp(16), 0);
    column.addView(footer, new LinearLayout.LayoutParams(-1, a.dp(32)));

    final String[] groups = {"全部", "央视", "卫视", "地方", "其他"};
    String initial = "央视";
    int dot = currentName == null ? -1 : currentName.indexOf(" · ");
    if (dot > 0) initial = currentName.substring(0, dot);
    final String[] selectedGroup = {initial};
    final int[] generation = {0};
    final View[] currentRow = {null};
    final LinearLayout[] cats = {left};

    final Runnable[] renderList = new Runnable[1];
    renderList[0] =
        () -> {
          final int myGen = ++generation[0];
          rightList.removeAllViews();
          currentRow[0] = null;
          java.util.List<TvLive.Channel> filtered = new java.util.ArrayList<>();
          for (TvLive.Channel c : all) {
            if ("全部".equals(selectedGroup[0]) || c.name.startsWith(selectedGroup[0] + " · "))
              filtered.add(c);
          }
          overlay.setVisibility(View.VISIBLE);
          overlay.setText("加载中…");
          AppLog.i(
              "IptvOrg",
              "picker loading=true group=" + selectedGroup[0] + " gen=" + myGen);
          a.ui.postDelayed(
              () -> {
                if (myGen != generation[0] || !isOpen()) return;
                rightList.removeAllViews();
                if (filtered.isEmpty()) {
                  overlay.setText("该分类暂无频道");
                  overlay.setVisibility(View.VISIBLE);
                  AppLog.i("IptvOrg", "picker empty=true group=" + selectedGroup[0]);
                  return;
                }
                overlay.setVisibility(View.GONE);
                for (final TvLive.Channel channel : filtered) {
                  rightList.addView(buildRow(a, store, all, channel, currentName, currentRow), rowLp(a));
                }
                if (currentRow[0] != null)
                  a.ui.postDelayed(
                      () -> {
                        if (currentRow[0] != null && isOpen()) currentRow[0].requestFocus();
                      },
                      80);
                else if (rightList.getChildCount() > 0)
                  a.ui.postDelayed(
                      () -> {
                        if (isOpen() && rightList.getChildCount() > 0)
                          rightList.getChildAt(0).requestFocus();
                      },
                      80);
              },
              900);
        };

    for (final String g : groups) {
      LinearLayout item = a.row();
      item.setGravity(Gravity.CENTER_VERTICAL);
      item.setPadding(a.dp(12), 0, a.dp(8), 0);
      item.setFocusable(true);
      item.setFocusableInTouchMode(true);
      item.setClickable(true);
      ExtraPages.tvFocus(a, item, 10);
      item.setTag("iptv-picker-cat:" + g);
      TextView label = a.text(g, 15, g.equals(selectedGroup[0]) ? 0xffffb3c8 : 0xffd6e1f0);
      item.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
      item.setOnClickListener(
          v -> {
            selectedGroup[0] = g;
            refreshCats(cats[0], groups, selectedGroup[0], null);
            renderList[0].run();
            AppLog.i("IptvOrg", "picker level=1 group=" + g + " gen=" + generation[0]);
          });
      left.addView(item, new LinearLayout.LayoutParams(-1, a.dp(44)));
    }

    renderList[0].run();
    AppLog.i("IptvOrg", "picker open group=" + selectedGroup[0] + " gen=" + generation[0]);
  }

  private static void noop() {}

  private static void refreshCats(
      LinearLayout left, String[] groups, String selected, Runnable unused) {
    for (int i = 0; i < left.getChildCount(); i++) {
      View item = left.getChildAt(i);
      if (!(item instanceof LinearLayout)) continue;
      LinearLayout row = (LinearLayout) item;
      TextView label = row.getChildCount() > 0 ? (TextView) row.getChildAt(0) : null;
      if (label == null) continue;
      boolean on = groups[i].equals(selected);
      label.setTextColor(on ? 0xffffb3c8 : 0xffd6e1f0);
      if (on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFF8FB1);
        bg.setCornerRadius(10);
        row.setBackground(bg);
      } else {
        row.setBackground(null);
      }
    }
  }

  private static LinearLayout.LayoutParams rowLp(MainActivity a) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(48));
    lp.bottomMargin = a.dp(2);
    return lp;
  }

  private static View buildRow(
      final MainActivity a,
      final TvLive.Store store,
      final java.util.List<TvLive.Channel> all,
      final TvLive.Channel channel,
      final String currentName,
      final View[] currentRow) {
    LinearLayout row = a.row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(a.dp(8), 0, a.dp(8), 0);
    row.setFocusable(true);
    row.setFocusableInTouchMode(true);
    row.setClickable(true);
    ExtraPages.tvFocus(a, row, 10);
    row.setTag("iptv-picker-row:" + channel.name);

    TextView num = a.text("", 12, 0xff8d9cb6);
    num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    num.setGravity(Gravity.CENTER);
    num.setWidth(a.dp(30));
    row.addView(num);

    if (channel.name.equals(currentName)) {
      View bar = new View(a);
      bar.setBackgroundColor(0xFFFF8FB1);
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(a.dp(3), a.dp(24));
      barLp.rightMargin = a.dp(6);
      row.addView(bar, barLp);
      num.setText("▶");
      num.setTextColor(0xFFFFB3C8);
    }

    TextView name = a.text(channel.name, 14, 0xffe8eff9);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

    boolean fav = store.favorites.contains(channel.name);
    TextView heart = a.text(fav ? "♥" : "♡", 16, fav ? 0xFFFF8FB1 : 0x66c9d6ea);
    heart.setGravity(Gravity.CENTER);
    heart.setPadding(a.dp(6), 0, a.dp(6), 0);
    heart.setOnClickListener(
        v -> {
          boolean now = TvLive.toggleFavorite(a, store, channel);
          heart.setText(now ? "♥" : "♡");
          heart.setTextColor(now ? 0xFFFF8FB1 : 0x66c9d6ea);
          AppLog.i("IptvOrg", "favorite channel=" + channel.name + " on=" + now);
        });
    row.addView(heart, new LinearLayout.LayoutParams(a.dp(28), a.dp(28)));

    if (channel.name.equals(currentName)) currentRow[0] = row;
    row.setOnClickListener(
        v -> {
          java.util.List<String> urls = new java.util.ArrayList<>();
          for (TvLive.Channel c : all)
            if (c.name.equals(channel.name) && !urls.contains(c.url)) urls.add(c.url);
          if (urls.isEmpty()) urls.add(channel.url);
          AppLog.i("IptvOrg", "picker select=" + channel.name + " urls=" + urls.size());
          close();
          NativeLiveScreen.show(a, channel.name, urls);
        });
    row.setOnLongClickListener(
        v -> {
          boolean now = TvLive.toggleFavorite(a, store, channel);
          heart.setText(now ? "♥" : "♡");
          AppLog.i("IptvOrg", "favorite channel=" + channel.name + " on=" + now);
          return true;
        });
    return row;
  }
}
