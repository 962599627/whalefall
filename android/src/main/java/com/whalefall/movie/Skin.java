package com.whalefall.movie;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * 鲸落主题「樱花粉 × 蓝白玻璃」调色板与背景绘制。
 *
 * <p>深海底色 + 蓝白玻璃面板 + 樱花粉强调色；所有页面共用同一套常量，方便以后调整。
 */
final class Skin {
 static final int BG = 0xff0b1220;
 static final int BG_DEEP = 0xff070d18;
 static final int INK = 0xfff5f7fc;
 static final int INK_DIM = 0xffa9b7d0;
 static final int INK_MUTED = 0xff7f8fa9;
 static final int PINK = 0xffff8fb1;
 static final int PINK_DEEP = 0xffe96c96;
 static final int PINK_SOFT = 0x33ffb3c8;
 static final int BLUE = 0xff8fc5ff;
 static final int GLASS = 0x33ffffff;

 /** 1.9.292：用户可配置的玻璃卡片底色/透明度（由 MainActivity 在 onResume 注入）。 */
 static int glassFill = GLASS;
 static int glassAlphaPct = 20;

 static void applyGlassPrefs(String color, int alphaPct) {
 int base;
 if ("black".equals(color)) base = 0x000000;
 else if ("white".equals(color)) base = 0xffffff;
 else if ("accent".equals(color)) base = 0x2ee6a0;
 else base = 0x1e3a5f;
 glassAlphaPct = Math.max(0, Math.min(90, alphaPct));
 glassFill = ((int) (glassAlphaPct * 2.55f) << 24) | base;
 }
 static final int GLASS_STRONG = 0x4dffffff;
 static final int GLASS_STROKE = 0x4dffffff;

 private Skin() {}

 /** 应用背景：深海蓝竖向渐变 + 右上樱花粉光晕 + 左下青蓝光。 */
 static Drawable backdrop(Context c) {
 GradientDrawable vertical =
 new GradientDrawable(
 GradientDrawable.Orientation.TOP_BOTTOM,
 new int[] {0xff0e1b31, 0xff0b1220, 0xff070d18});
 LayerDrawable layers = new LayerDrawable(new Drawable[] {vertical, new Glow()});
 return layers;
 }

 /** 半透明玻璃面板：白色蒙版 + 细描边，叠在深蓝底上就是蓝白玻璃。 */
 static Drawable glass(float radius, int fill, int stroke) {
 GradientDrawable d = new GradientDrawable();
 d.setShape(GradientDrawable.RECTANGLE);
 d.setCornerRadius(radius);
 d.setColor(fill);
 d.setStroke(1, stroke);
 return d;
 }

 static Drawable glass(float radius) {
 return glass(radius, glassFill, GLASS_STROKE);
 }

 /** 樱花粉光晕：纯代码绘制，无图片资源。 */
 static final class Glow extends Drawable {
 final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

 @Override
 public void draw(Canvas canvas) {
 Rect b = getBounds();
 float w = Math.max(1, b.width()), h = Math.max(1, b.height());
 paint.setShader(
 new RadialGradient(
 w * .82f,
 h * .05f,
 Math.max(w, h) * .55f,
 new int[] {0x5aff9dbb, 0x28ff9dbb, 0x00ff9dbb},
 new float[] {0f, .45f, 1f},
 Shader.TileMode.CLAMP));
 canvas.drawRect(b, paint);
 paint.setShader(
 new RadialGradient(
 w * .08f,
 h * .95f,
 Math.max(w, h) * .5f,
 new int[] {0x338fc5ff, 0x008fc5ff},
 new float[] {0f, 1f},
 Shader.TileMode.CLAMP));
 canvas.drawRect(b, paint);
 paint.setShader(null);
 }

 @Override
 public void setAlpha(int alpha) {}

 @Override
 public void setColorFilter(ColorFilter colorFilter) {}

 @Override
 public int getOpacity() {
 return PixelFormat.TRANSLUCENT;
 }
 }
}
