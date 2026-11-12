package com.whalefall.movie;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.WeakHashMap;

/**
 * 1.9.168：播放器/IPTV 共用焦点渲染器。
 *
 * <p>统一：10% #1AFF8FB1 底、2dp ACCENT_SOFT 描边、进 120ms / 出 100ms Decelerate；
 * 容器不缩放；内容缩放（1.04）由调用方决定。支持两种形态：
 * 背景+前景环（普通行/底部按钮）与自绘 drawable（中央三键/More）。
 */
final class PlayerFocusRenderer {
  private PlayerFocusRenderer() {}

  interface Fraction {
    void set(float value);
  }

  private static final WeakHashMap<View, android.graphics.drawable.Drawable> ORIGINAL =
      new WeakHashMap<>();

  /** 背景+前景环形态（底部文字按钮 / IPTV 行）。 */
  static void background(final View view, boolean focused, final int radiusDp) {
    if (view == null) return;
    MainActivity a = view.getContext() instanceof MainActivity ? (MainActivity) view.getContext() : null;
    if (a == null || android.os.Build.VERSION.SDK_INT < 23) return;
    boolean mode = a.isTvDevice() || a.keyFocusMode || a.remoteFocusMode;
    if (!mode) return;
    GradientDrawable box = box(a, radiusDp);
    GradientDrawable clear = clear(a, radiusDp);
    if (focused) {
      if (!ORIGINAL.containsKey(view)) ORIGINAL.put(view, view.getBackground());
      TransitionDrawable td =
          new TransitionDrawable(new android.graphics.drawable.Drawable[] {clear, box});
      td.setCrossFadeEnabled(true);
      view.setBackground(td);
      td.startTransition(a.reduced ? 0 : 120);
      AppLog.i("PlayerFocus", "style=common mode=background view=" + view.getTag());
    } else {
      final TransitionDrawable td =
          new TransitionDrawable(new android.graphics.drawable.Drawable[] {box, clear});
      view.setBackground(td);
      td.startTransition(a.reduced ? 0 : 100);
      a.ui.postDelayed(
          () -> {
            if (!view.hasFocus()) view.setBackground(ORIGINAL.get(view));
          },
          110);
    }
  }

  /** 自绘 drawable 形态：只给一个焦点比例回调（中央三键/More）。 */
  static void animated(final MainActivity a, final Fraction fraction, boolean focused) {
    if (a == null || fraction == null) return;
    float from = 0f;
    if (fraction instanceof PlayerCenterIconDrawable)
      from = ((PlayerCenterIconDrawable) fraction).focusFraction();
    android.animation.ValueAnimator animator =
        android.animation.ValueAnimator.ofFloat(from, focused ? 1f : 0f);
    animator.setDuration(a.reduced ? 0 : (focused ? 120 : 100));
    if (focused && !a.reduced) animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(
        animation -> fraction.set(((Number) animation.getAnimatedValue()).floatValue()));
    animator.start();
    AppLog.i("PlayerFocus", "style=common mode=drawable focused=" + focused);
  }

  private static GradientDrawable box(MainActivity a, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setCornerRadius(a.dp(Math.max(0, radiusDp)));
    d.setColor(0x1AFF8FB1);
    d.setStroke(Math.max(1, a.dp(2)), 0xFFFFB3C8);
    return d;
  }

  private static GradientDrawable clear(MainActivity a, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setCornerRadius(a.dp(Math.max(0, radiusDp)));
    d.setColor(0x00000000);
    return d;
  }
}
