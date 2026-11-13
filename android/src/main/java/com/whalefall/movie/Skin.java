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
 // 1.9.302「暖夜梅 + 樱花粉」Token
 static final int BACKDROP_TOP = 0xff1a0f16;
 static final int BACKDROP_MID = 0xff120b10;
 static final int BACKDROP_LOW = 0xff0c070c;
 static final int CARD_BASE = 0xff3a1f2b;
 static final int CARD_STROKE = 0x33ffb3c8;
 static final int TEXT_PRIMARY_T = 0xffffe3ec;
 static final int TEXT_SECONDARY_T = 0xffc9aeb8;
 static final int TEXT_TERTIARY_T = 0xff9b7d88;
 static int ACCENT_STRONG = 0xffff8fb1;
 static final int ACCENT_DEFAULT = 0xffff8fb1;
 static int ACCENT_MID = 0xffffa9c4;
 static int ACCENT_SOFT_T = 0xffffb3c8;
 static final int PINK_ON_FILL_TEXT = 0xff2a1018;
 static final int FOCUS_RING = 0xffffb3c8;
 static final int SCRIM_LIGHT_WALLPAPER = 0x990c070c;
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
 static int cardFill = 0x331e3a5f;
 /** 1.9.310：卡片选中态颜色（默认原粉色）。 */
 static int cardAccent = 0xffff8fb1;
 static int glassAlphaPct = 20;

 /**
  * 1.9.320：卡片调色盘预设色板 token。
  *
  * <p>Dialog 内禁止写死色值：色板的「底色」沿用樱夜/CARD_BASE 与墨夜/雾梅/咖啡三套历史预设，
  * 「选中色」沿用樱花粉 + 珊瑚 + 杏桃三套历史预设；其余色块由这些 token 与
  * TEXT_PRIMARY_T / TEXT_SECONDARY_T / PINK_ON_FILL_TEXT / BLUE 派生。
  */
 static final int PALETTE_BASE_INK = 0xff140b10;
 static final int PALETTE_BASE_PLUM = 0xff4a3440;
 static final int PALETTE_BASE_COFFEE = 0xff4b3326;
 static final int PALETTE_ACCENT_CORAL = 0xffff8a7a;
 static final int PALETTE_ACCENT_APRICOT = 0xffffb37c;
 static final int PALETTE_ACCENT_MINT = 0xff5fd3a6;

 /**
  * 1.9.325：8 套卡片主题预设（含旧 4 套），每套自带推荐字色与风格说明。
  *
  * <p>字色均满足 WCAG AA（≥4.5:1）；运行期仍用 {@link #inkOn(int)} 按实际合成底色复核。
  */
 static final String[] PRESET_KEYS = {
 "sakura-night", "ink-night", "plum-mist", "coffee",
 "celadon", "night-blue", "violet", "stone"
 };
 static final String[] PRESET_NAMES = {
 "樱夜", "墨夜", "雾梅", "咖啡", "青瓷", "夜蓝", "紫夜", "灰岩"
 };
 static final String[] PRESET_TAGS = {
 "暖夜梅 · 樱花粉底", "深海黑 · 极简", "灰紫梅 · 柔和", "暖棕咖啡 · 复古",
 "青瓷 · 冷调灰绿", "夜蓝 · 冷调深海", "紫夜 · 低饱和紫", "灰岩 · 中性石墨"
 };
 static final int[] PRESET_COLORS = {
 0xff3a1f2b, 0xff140b10, 0xff4a3440, 0xff4b3326,
 0xff2e3b36, 0xff1e2a3a, 0xff2e2233, 0xff3a3a3e
 };
 static final int[] PRESET_INKS = {
 0xffffe3ec, 0xffe9dde2, 0xffffe9f0, 0xfff6e7da,
 0xffddede4, 0xffdce6f5, 0xffebdef2, 0xffececf0
 };
 /** 1.9.327：8 套主题的「选中色」三元组之一（底/选中色/文字色）。 */
 static final int[] PRESET_ACCENTS = {
 0xffff8fb1, 0xffff8fb1, 0xffffb3c8, 0xffffb37c,
 0xff7fd8b8, 0xff8fb8ff, 0xffc9a6ff, 0xffffb3c8
 };

 static int presetAccent(String key) {
 return PRESET_ACCENTS[presetIndex(key)];
 }

 static int presetIndex(String key) {
 if (key == null) return 0;
 for (int i = 0; i < PRESET_KEYS.length; i++) if (PRESET_KEYS[i].equals(key)) return i;
 return 0;
 }

 static int presetColor(String key) {
 return PRESET_COLORS[presetIndex(key)] & 0xffffff;
 }

 static int presetInk(String key) {
 return PRESET_INKS[presetIndex(key)];
 }

 static String presetName(String key) {
 return PRESET_NAMES[presetIndex(key)];
 }

 static String presetTag(String key) {
 return PRESET_TAGS[presetIndex(key)];
 }

 static void applyGlassPrefs(String color, int alphaPct) {
 int base;
 if ("black".equals(color)) base = 0x000000;
 else if ("white".equals(color)) base = 0xffffff;
 else if ("accent".equals(color)) base = 0x2ee6a0;
 else base = 0x1e3a5f;
 glassAlphaPct = Math.max(0, Math.min(100, alphaPct));
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

 static android.graphics.drawable.GradientDrawable card(float radiusPx, float strokePx) {
 android.graphics.drawable.GradientDrawable d =
 new android.graphics.drawable.GradientDrawable();
 d.setColor(cardFill);
 d.setCornerRadius(radiusPx);
 d.setStroke(Math.max(1, Math.round(strokePx)), (0x55 << 24) | 0xffffff);
 return d;
 }

 /** 1.9.322：卡片底 RGB（不含用户透明度），供面板/按钮不透明底使用。 */
 static int cardBase() {
 return cardFill & 0xffffff;
 }

 /**
  * 1.9.322：实心卡片面板（筛选面板、浮层、按钮底）。
  *
  * <p>用「用户卡片底色 + 0xF2 不透明度」，既跟随外观设置里的底色，又不会被深色壁纸透成
  * 旧版深蓝/紫色；描边统一用 CARD_STROKE。
  */
 static android.graphics.drawable.GradientDrawable panel(float radiusPx, float strokePx) {
 android.graphics.drawable.GradientDrawable d =
 new android.graphics.drawable.GradientDrawable();
 d.setColor((0xF2 << 24) | (cardFill & 0xffffff));
 d.setCornerRadius(radiusPx);
 d.setStroke(Math.max(1, Math.round(strokePx)), CARD_STROKE);
 return d;
 }

 /** 1.9.322：底部弹层背景（只圆顶角）。 */
 static android.graphics.drawable.GradientDrawable topPanel(float radiusPx) {
 android.graphics.drawable.GradientDrawable d =
 new android.graphics.drawable.GradientDrawable();
 float r = radiusPx;
 d.setColor((0xF2 << 24) | (cardFill & 0xffffff));
 d.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
 d.setStroke(Math.max(1, 1), CARD_STROKE);
 return d;
 }

 /** 1.9.322：选中态浅强调底（α≈0x2E ≤ 0x33）+ ACCENT_SOFT 描边，粉色只做小面积强调。 */
 static android.graphics.drawable.GradientDrawable accentSoftFill(
 float radiusPx, float strokePx) {
 android.graphics.drawable.GradientDrawable d =
 new android.graphics.drawable.GradientDrawable();
 d.setColor((0x2E << 24) | (ACCENT_STRONG & 0xffffff));
 d.setCornerRadius(radiusPx);
 if (strokePx > 0) {
 d.setStroke(Math.max(1, Math.round(strokePx)), accentSoft());
 }
 return d;
 }

 /** 1.9.322：强调色（补足 alpha，规避 migrateAccent/lighten 返回 RGB-only 的透明色）。 */
 static int accentStrong() {
 return 0xff000000 | (ACCENT_STRONG & 0xffffff);
 }

 static int accentMid() {
 return 0xff000000 | (ACCENT_MID & 0xffffff);
 }

 static int accentSoft() {
 return 0xff000000 | (ACCENT_SOFT_T & 0xffffff);
 }

 // ------------------------------------------------------------------
 // 1.9.325：卡片底自动搭配字色（WCAG 对比度 ≥4.5）
 // ------------------------------------------------------------------

 /** 默认主题（樱夜 #3A1F2B）配套字色。 */
 static final int CARD_TEXT_SAKURA = 0xffffe3ec;
 /** 墨夜配套设施字色。 */
 static final int CARD_TEXT_INK = 0xffffe3ec;
 /** 雾梅配套设施字色。 */
 static final int CARD_TEXT_PLUM = 0xffffe3ec;
 /** 咖啡配套设施字色。 */
 static final int CARD_TEXT_COFFEE = 0xffffe3ec;

 static int presetCardText(String preset) {
 return presetInk(preset);
 }

 /** WCAG 相对亮度（0~1）。 */
 static double luminance(int rgb) {
 double r = linear((rgb >> 16) & 0xff);
 double g = linear((rgb >> 8) & 0xff);
 double b = linear(rgb & 0xff);
 return .2126 * r + .7152 * g + .0722 * b;
 }

 private static double linear(int channel) {
 double s = channel / 255.0;
 return s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
 }

 /** WCAG 对比度。 */
 static double contrastRatio(int fg, int bg) {
 double l1 = luminance(fg & 0xffffff), l2 = luminance(bg & 0xffffff);
 double light = Math.max(l1, l2), dark = Math.min(l1, l2);
 return (light + .05) / (dark + .05);
 }

 private static int mix(int a, int b, float t) {
 float k = Math.max(0f, Math.min(1f, t));
 int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
 int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
 int r = Math.round(ar + (br - ar) * k);
 int g = Math.round(ag + (bg - ag) * k);
 int bl = Math.round(ab + (bb - ab) * k);
 return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (bl & 0xff);
 }

 /**
  * 1.9.325：按底色亮度返回可读字色：深底用 TEXT_PRIMARY_T，浅底用 PINK_ON_FILL_TEXT；
  * 两者对比度都 <4.5:1 时朝相反方向混色，直到达到 4.5:1。
  */
 static int inkOn(int bgColor) {
 int bg = bgColor & 0xffffff;
 int lightInk = TEXT_PRIMARY_T & 0xffffff;
 int darkInk = PINK_ON_FILL_TEXT & 0xffffff;
 double lightRatio = contrastRatio(lightInk, bg);
 double darkRatio = contrastRatio(darkInk, bg);
 boolean useLight = lightRatio >= darkRatio;
 int ink = useLight ? lightInk : darkInk;
 int other = useLight ? darkInk : lightInk;
 double best = Math.max(lightRatio, darkRatio);
 if (best < 4.5) {
 for (int i = 1; i <= 12; i++) {
 int candidate = mix(ink, other, i * .08f);
 if (contrastRatio(candidate, bg) >= 4.5) {
 ink = candidate;
 best = contrastRatio(candidate, bg);
 break;
 }
 }
 }
 return 0xff000000 | ink;
 }

 /** 用户卡片底在深色背景上的等效可见色（把用户透明度合成到 BACKDROP_TOP）。 */
 static int cardSurface() {
 int alpha = (cardFill >>> 24) & 0xff;
 return mix(BACKDROP_TOP & 0xffffff, cardFill & 0xffffff, alpha / 255f);
 }

 private static int lastInkBg = -1, lastInk = -1;

 /** 1.9.325：当前卡片底配套字色（带一次日志，避免每帧刷屏）。 */
 static int cardInk() {
 int bg = cardSurface();
 int ink = inkOn(bg);
 if (bg != lastInkBg || ink != lastInk) {
 lastInkBg = bg;
 lastInk = ink;
 AppLog.i(
 "Theme",
 "ink bg=#"
 + String.format(java.util.Locale.ROOT, "%06X", bg)
 + "→ink=#"
 + String.format(java.util.Locale.ROOT, "%06X", ink & 0xffffff)
 + " ratio="
 + String.format(java.util.Locale.ROOT, "%.1f", contrastRatio(ink, bg)));
 }
 return ink;
 }

 /** 1.9.325：字色的弱化版本（副标题用，保持同色系）。 */
 static int dimInk(int ink, int alphaByte) {
 return ((alphaByte & 0xff) << 24) | (ink & 0xffffff);
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
