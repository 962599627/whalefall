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
  private static LinearLayout railRef;
  private static int selectedCatIndex;

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
    hostIptv = all;
    root = new FrameLayout(a);
    root.setTag("iptv-picker");
    root.setBackgroundColor(0x66000000);
    root.setClickable(true);
    FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(-1, -1);
    a.root.addView(root, rootLp);

    int safeH = a.windowHeight();
    final int panelW = Math.min(a.dp(720), Math.max(a.dp(480), a.windowWidth() - a.dp(48)));
    final int panelH = Math.min(a.dp(420), safeH - a.dp(24));
    final FrameLayout panel = new FrameLayout(a);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xE60B1220);
    bg.setCornerRadius(a.dp(20));
    bg.setStroke(Math.max(1, a.dp(1)), 0x33ffffff);
    panel.setBackground(bg);
    panel.setElevation(0);
    FrameLayout.LayoutParams panelLp =
        new FrameLayout.LayoutParams(panelW, panelH, Gravity.CENTER);
    root.addView(panel, panelLp);

    LinearLayout column = a.vertical();
    panel.addView(column, new FrameLayout.LayoutParams(-1, -1));

    // Header 48
    LinearLayout header = a.row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(a.dp(16), 0, a.dp(8), 0);
    TextView title = a.text("选择频道", 16, 0xfff5f7fc);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView closeBtn = a.text("×", 18, 0xfff5f7fc);
    closeBtn.setGravity(Gravity.CENTER);
    GradientDrawable closeBg = new GradientDrawable();
    closeBg.setShape(GradientDrawable.OVAL);
    closeBg.setColor(0x1AFFFFFF);
    closeBg.setStroke(Math.max(1, a.dp(1)), 0x1AFFFFFF);
    closeBtn.setBackground(closeBg);
    closeBtn.setFocusable(true);
    closeBtn.setFocusableInTouchMode(true);
    closeBtn.setClickable(true);
    closeBtn.setOnFocusChangeListener(
        (v, focused) -> PlayerFocusRenderer.background(v, focused, 16));
    closeBtn.setOnClickListener(v -> close());
    header.addView(closeBtn, new LinearLayout.LayoutParams(a.dp(32), a.dp(32)));
    column.addView(header, new LinearLayout.LayoutParams(-1, a.dp(48)));

    LinearLayout body = a.row();
    body.setPadding(0, 0, 0, 0);
    column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

    // 左分类 1/3（TV 240dp），右列表 2/3（TV 480dp），中间 1dp 分隔
    LinearLayout left = a.vertical();
    left.setBackgroundColor(0x08000000);
    left.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
    body.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

    View divider = new View(a);
    divider.setBackgroundColor(0x1AFFFFFF);
    body.addView(divider, new LinearLayout.LayoutParams(1, -1));

    // 右频道 FrameLayout（列表 + 空态/加载叠层）
    FrameLayout rightFrame = new FrameLayout(a);
    body.addView(rightFrame, new LinearLayout.LayoutParams(0, -1, 2f));
    final LinearLayout rightList = a.vertical();
    ScrollView rightScroll = new ScrollView(a);
    rightScroll.setVerticalScrollBarEnabled(false);
    rightScroll.addView(rightList);
    rightFrame.addView(rightScroll, new FrameLayout.LayoutParams(-1, -1));
    // 空态/加载态叠层（只覆盖右栏）
    final FrameLayout overlay = new FrameLayout(a);
    overlay.setBackgroundColor(0xE6070B14);
    overlay.setVisibility(View.GONE);
    overlay.setClickable(true);
    rightFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

    final LinearLayout emptyBox = a.vertical();
    emptyBox.setGravity(Gravity.CENTER);
    emptyBox.setVisibility(View.GONE);
    TextView emptyTitle = a.text("暂无频道", 14, 0xffa9b7d0);
    emptyTitle.setGravity(Gravity.CENTER);
    emptyBox.addView(emptyTitle);
    TextView emptyDesc = a.text("换个分类看看，或稍后重试", 11, 0xff8d9cb6);
    emptyDesc.setGravity(Gravity.CENTER);
    emptyDesc.setPadding(0, a.dp(4), 0, a.dp(8));
    emptyBox.addView(emptyDesc);
    final TextView retry = a.text("重试", 11, 0xFFFFB3C8);
    retry.setGravity(Gravity.CENTER);
    GradientDrawable retryBg = new GradientDrawable();
    retryBg.setColor(0x1AFFFFFF);
    retryBg.setCornerRadius(999);
    retryBg.setStroke(Math.max(1, a.dp(1)), 0x1AFFFFFF);
    retry.setBackground(retryBg);
    retry.setFocusable(true);
    retry.setFocusableInTouchMode(true);
    retry.setClickable(true);
    retry.setOnFocusChangeListener(
        (v, focused) -> PlayerFocusRenderer.background(v, focused, 999));
    emptyBox.addView(retry, new LinearLayout.LayoutParams(a.dp(88), a.dp(28)));
    overlay.addView(
        emptyBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    final LoadingRing ring = new LoadingRing(a);
    overlay.addView(ring, new FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER));
    final TextView loadingText = a.text("正在加载频道…", 11, 0xff8d9cb6);
    loadingText.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams loadingLp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
    loadingLp.topMargin = a.dp(36);
    overlay.addView(loadingText, loadingLp);

    // Footer 32
    TextView footer =
        a.text("←→ 切换分类   ↑↓ 选择频道   OK 播放并关闭   BACK 关闭", 11, 0xFF8D9CB6);
    footer.setGravity(Gravity.CENTER);
    footer.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
    column.addView(footer, new LinearLayout.LayoutParams(-1, a.dp(32)));

    final String[] groups = {"全部", "央视", "卫视", "地方", "其他"};
    String initial = "央视";
    int dot = currentName == null ? -1 : currentName.indexOf(" · ");
    if (dot > 0) initial = currentName.substring(0, dot);
    final String[] selectedGroup = {initial};
    selectedCatIndex = 0;
    for (int i = 0; i < groups.length; i++)
      if (groups[i].equals(initial)) {
        selectedCatIndex = i;
        break;
      }
    final int[] generation = {0};
    final View[] currentRow = {null};
    final LinearLayout[] cats = {left};
    railRef = left;

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
          emptyBox.setVisibility(View.GONE);
          loadingText.setVisibility(View.VISIBLE);
          ring.setVisibility(View.VISIBLE);
          AppLog.i(
              "IptvOrg",
              "picker loading=true group=" + selectedGroup[0] + " gen=" + myGen);
          a.ui.postDelayed(
              () -> {
                if (myGen != generation[0] || !isOpen()) return;
                rightList.removeAllViews();
                if (filtered.isEmpty()) {
                  overlay.setVisibility(View.VISIBLE);
                  ring.setVisibility(View.GONE);
                  loadingText.setVisibility(View.GONE);
                  emptyBox.setVisibility(View.VISIBLE);
                  AppLog.i("IptvOrg", "picker empty=true group=" + selectedGroup[0]);
                  return;
                }
                overlay.setVisibility(View.GONE);
                ring.setVisibility(View.GONE);
                emptyBox.setVisibility(View.GONE);
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
    retry.setOnClickListener(v -> renderList[0].run());

    for (int gi = 0; gi < groups.length; gi++) {
      final String g = groups[gi];
      final int catIndex = gi;
      LinearLayout item = a.row();
      item.setGravity(Gravity.CENTER_VERTICAL);
      item.setPadding(a.dp(10), 0, a.dp(10), 0);
      item.setFocusable(true);
      item.setFocusableInTouchMode(true);
      item.setClickable(true);
      item.setTag("iptv-picker-cat:" + g);
      View bar = new View(a);
      bar.setTag("cat-bar");
      bar.setBackgroundColor(0xFFFF8FB1);
      item.addView(bar, new LinearLayout.LayoutParams(a.dp(3), a.dp(24)));
      TextView label = a.text(g, 14, 0xffa9b7d0);
      label.setTag("cat-label");
      LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
      labelLp.leftMargin = a.dp(7);
      item.addView(label, labelLp);
      TextView count = a.text("", 11, 0xff8d9cb6);
      count.setTag("cat-count");
      item.addView(count);
      item.setOnFocusChangeListener(
          (v, focused) -> {
            if (focused) PlayerFocusRenderer.background(v, true, 10);
            else {
              refreshCats(cats[0], groups, selectedGroup[0], null);
              AppLog.i("PlayerFocus", "style=common mode=background view=iptv-cat");
            }
          });
      item.setOnKeyListener(
          (v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
              if (rightList.getChildCount() > 0) {
                rightList.getChildAt(0).requestFocus();
                AppLog.i("IptvOrg", "picker focus rail->list group=" + g);
              }
              return true;
            }
            return false;
          });
      item.setOnClickListener(
          v -> {
            selectedGroup[0] = g;
            selectedCatIndex = catIndex;
            refreshCats(cats[0], groups, selectedGroup[0], null);
            renderList[0].run();
            AppLog.i("IptvOrg", "picker level=1 group=" + g + " gen=" + generation[0]);
          });
      left.addView(item, new LinearLayout.LayoutParams(-1, a.dp(44)));
      LinearLayout.LayoutParams itemLp =
          (LinearLayout.LayoutParams) item.getLayoutParams();
      itemLp.bottomMargin = a.dp(4);
      item.setLayoutParams(itemLp);
    }
    refreshCats(left, groups, selectedGroup[0], null);

    renderList[0].run();
    AppLog.i("IptvOrg", "picker open group=" + selectedGroup[0] + " gen=" + generation[0]);
  }

  private static int groupCount(java.util.List<TvLive.Channel> all, String group) {
    if ("全部".equals(group)) return all.size();
    int n = 0;
    for (TvLive.Channel c : all) if (c.name.startsWith(group + " · ")) n++;
    return n;
  }

  private static void noop() {}

  private static void refreshCats(
      LinearLayout left, String[] groups, String selected, Runnable unused) {
    for (int i = 0; i < left.getChildCount() && i < groups.length; i++) {
      View item = left.getChildAt(i);
      if (!(item instanceof LinearLayout)) continue;
      LinearLayout row = (LinearLayout) item;
      boolean on = groups[i].equals(selected);
      TextView label = row.findViewWithTag("cat-label");
      TextView count = row.findViewWithTag("cat-count");
      View bar = row.findViewWithTag("cat-bar");
      if (label != null) {
        label.setTextColor(on ? 0xFFFFB3C8 : 0xFFA9B7D0);
        label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      }
      if (bar != null) bar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
      if (count != null) {
        count.setTextColor(on ? 0xFFFFB3C8 : 0xFF8D9CB6);
        count.setText(String.valueOf(groupCount(host == null ? java.util.Collections.emptyList() : hostIptv, groups[i])));
      }
      GradientDrawable bg = new GradientDrawable();
      bg.setCornerRadius(10);
      bg.setColor(on ? 0x1AFF8FB1 : 0x00000000);
      row.setBackground(bg);
    }
  }

  private static java.util.List<TvLive.Channel> hostIptv = new java.util.ArrayList<>();

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
    row.setContentDescription(channel.name);
    row.setTag("iptv-picker-row:" + channel.name);
    row.setOnFocusChangeListener(
        (v, focused) -> PlayerFocusRenderer.background(v, focused, 10));
    row.setOnKeyListener(
        (v, keyCode, event) -> {
          if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
              && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            View cat = railRef == null ? null : railRef.getChildAt(Math.max(0, selectedCatIndex));
            if (cat != null) {
              cat.requestFocus();
              AppLog.i("IptvOrg", "picker focus list->rail");
            }
            return true;
          }
          return false;
        });

    TextView num = a.text("", 11, 0xff8d9cb6);
    num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    num.setGravity(Gravity.CENTER);
    num.setWidth(a.dp(30));
    row.addView(num);

    if (channel.name.equals(currentName)) {
      View bar = new View(a);
      bar.setBackgroundColor(0xFFFF8FB1);
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(a.dp(3), a.dp(32));
      barLp.rightMargin = a.dp(6);
      row.addView(bar, barLp);
      num.setText("▶");
      num.setTextColor(0xFFFFB3C8);
    }

    TextView name = a.text(channel.name, 15, 0xfff5f7fc);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

    boolean fav = store.favorites.contains(channel.name);
    TextView heart = a.text(fav ? "♥" : "♡", 16, fav ? 0xFFFF8FB1 : 0xFF8D9CB6);
    heart.setGravity(Gravity.CENTER);
    heart.setFocusable(false);
    heart.setPadding(a.dp(6), 0, a.dp(6), 0);
    heart.setOnClickListener(
        v -> {
          boolean now = TvLive.toggleFavorite(a, store, channel);
          heart.setText(now ? "♥" : "♡");
          heart.setTextColor(now ? 0xFFFF8FB1 : 0xFF8D9CB6);
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

  /** 1.9.168：加载态 20dp 圆环（900ms 线性旋转；Reduced 静态）。 */
  static final class LoadingRing extends android.view.View {
    private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF oval = new android.graphics.RectF();
    private float rotation;
    private android.animation.ValueAnimator animator;

    LoadingRing(MainActivity a) {
      super(a);
      track.setStyle(android.graphics.Paint.Style.STROKE);
      track.setStrokeWidth(a.dp(3f));
      track.setColor(0x1AFFFFFF);
      arc.setStyle(android.graphics.Paint.Style.STROKE);
      arc.setStrokeWidth(a.dp(2f));
      arc.setColor(0xFFFFB3C8);
      arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      if (!a.reduced) {
        animator = android.animation.ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(900);
        animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(
            animation -> {
              rotation = ((Number) animation.getAnimatedValue()).floatValue();
              invalidate();
            });
        animator.start();
      }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
      float inset = Math.max(getWidth(), getHeight()) == 0 ? 0 : Math.min(getWidth(), getHeight()) / 2f;
      oval.set(
          getWidth() / 2f - inset + android.util.TypedValue.applyDimension(
              android.util.TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()),
          getHeight() / 2f - inset + android.util.TypedValue.applyDimension(
              android.util.TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()),
          getWidth() / 2f + inset - android.util.TypedValue.applyDimension(
              android.util.TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()),
          getHeight() / 2f + inset - android.util.TypedValue.applyDimension(
              android.util.TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()));
      canvas.drawOval(oval, track);
      canvas.save();
      canvas.rotate(rotation, getWidth() / 2f, getHeight() / 2f);
      canvas.drawArc(oval, -90f, 90f, false, arc);
      canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
      if (animator != null) animator.cancel();
      super.onDetachedFromWindow();
    }
  }
}
