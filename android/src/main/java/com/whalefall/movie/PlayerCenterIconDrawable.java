package com.whalefall.movie;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 1.9.166（R2+R3）：播放器中央三键自绘图标 + 专用焦点渲染器。
 *
 * <p>几何/焦点框由外部 View 尺寸决定（侧键 44、中心 64）；本类只画图标并让 icon 1.0→1.04 缩放，
 * 焦点框固定不缩放。颜色仅 PLAYER_ICON / 10% 粉 / ACCENT_SOFT。
 */
final class PlayerCenterIconDrawable extends Drawable
    implements PlayerFocusRenderer.Fraction {
  static final int REW = 0, STATE = 1, FF = 2, MORE = 3;

  private final MainActivity a;
  private final int kind;
  private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint focusFill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint focusStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private boolean playing;
  private float focus;
  private boolean pressed;

  void setPressedState(boolean value) {
    pressed = value;
    invalidateSelf();
  }

  PlayerCenterIconDrawable(MainActivity a, int kind) {
    this.a = a;
    this.kind = kind;
    icon.setColor(0xFFF2F6FF);
    icon.setStyle(Paint.Style.FILL);
    stroke.setColor(0xFFF2F6FF);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(a.dp(2.5f));
    stroke.setStrokeCap(Paint.Cap.ROUND);
    stroke.setStrokeJoin(Paint.Join.ROUND);
    focusFill.setColor(0x1AFF8FB1);
    focusFill.setStyle(Paint.Style.FILL);
    focusStroke.setColor(0xFFFFB3C8);
    focusStroke.setStyle(Paint.Style.STROKE);
    focusStroke.setStrokeWidth(a.dp(2f));
  }

  void setPlaying(boolean value) {
    if (playing == value) return;
    playing = value;
    invalidateSelf();
  }

  void setFocusFraction(float value) {
    focus = Math.max(0f, Math.min(1f, value));
    invalidateSelf();
  }

  @Override
  public void set(float value) {
    setFocusFraction(value);
  }

  float focusFraction() {
    return focus;
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    if (b.width() <= 0 || b.height() <= 0) return;
    // 焦点框固定（side=44/center=64 即 bounds）不缩放
    if (focus > 0f) {
      float radius = a.dp(kind == STATE || kind == MORE ? 20f : 14f);
      RectF box = new RectF(b.left, b.top, b.right, b.bottom);
      int alpha = Math.round(0x1A * focus);
      focusFill.setColor((alpha << 24) | 0x00FF8FB1);
      focusStroke.setAlpha(Math.round(255 * focus));
      canvas.drawRoundRect(box, radius, radius, focusFill);
      canvas.drawRoundRect(box, radius, radius, focusStroke);
    }
    float scale = 1f + 0.04f * focus;
    canvas.save();
    canvas.scale(scale, scale, b.exactCenterX(), b.exactCenterY());
    if (kind == STATE) drawPlayPause(canvas, b);
    else if (kind == MORE) drawMore(canvas, b);
    else drawSeek(canvas, b, kind == REW);
    canvas.restore();
    if (pressed) {
      Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
      press.setColor(0x26FF8FB1);
      canvas.drawRoundRect(
          new RectF(b.left, b.top, b.right, b.bottom), a.dp(20f), a.dp(20f), press);
    }
  }

  private void drawMore(Canvas canvas, Rect b) {
    Paint dot = new Paint(icon);
    dot.setStyle(Paint.Style.FILL);
    dot.setColor(0xFFF2F6FF);
    float r = a.dp(2f);
    float cx = b.exactCenterX(), cy = b.exactCenterY();
    canvas.drawCircle(cx - a.dp(9f), cy, r, dot);
    canvas.drawCircle(cx, cy, r, dot);
    canvas.drawCircle(cx + a.dp(9f), cy, r, dot);
  }

  private void drawSeek(Canvas canvas, Rect b, boolean back) {
    // 双三角 20×18 + 中线 2.5×14
    float w = a.dp(20f), h = a.dp(18f);
    float left = b.exactCenterX() - w / 2f;
    float top = b.exactCenterY() - h / 2f;
    float triW = a.dp(11f);
    for (int i = 0; i < 2; i++) {
      float x = left + i * a.dp(9f);
      path.reset();
      if (back) {
        path.moveTo(x + triW, top);
        path.lineTo(x, top + h / 2f);
        path.lineTo(x + triW, top + h);
      } else {
        path.moveTo(x, top);
        path.lineTo(x + triW, top + h / 2f);
        path.lineTo(x, top + h);
      }
      path.close();
      canvas.drawPath(path, stroke);
    }
    stroke.setAlpha(focus > 0f ? 255 : 140); // 中线 55% alpha；聚焦 100%
    float cx = b.exactCenterX();
    canvas.drawLine(cx, b.exactCenterY() - a.dp(7f), cx, b.exactCenterY() + a.dp(7f), stroke);
    stroke.setAlpha(255);
  }

  private void drawPlayPause(Canvas canvas, Rect b) {
    if (playing) {
      // 暂停：两条 6×20dp 圆角竖条，间距 6
      float barW = a.dp(6f), barH = a.dp(20f), gap = a.dp(6f);
      float cx = b.exactCenterX(), cy = b.exactCenterY();
      float total = barW * 2 + gap;
      float left = cx - total / 2f;
      RectF first = new RectF(left, cy - barH / 2f, left + barW, cy + barH / 2f);
      RectF second =
          new RectF(
              left + barW + gap, cy - barH / 2f, left + barW + gap + barW, cy + barH / 2f);
      float r = a.dp(3f);
      canvas.drawRoundRect(first, r, r, icon);
      canvas.drawRoundRect(second, r, r, icon);
    } else {
      // 播放：实心圆角三角 22×26（非正方形），2.5dp 同色圆头 join
      float w = a.dp(22f), h = a.dp(26f);
      float cx = b.exactCenterX(), cy = b.exactCenterY();
      float left = cx - w / 2f + a.dp(1f);
      float top = cy - h / 2f;
      path.reset();
      path.moveTo(left, top);
      path.lineTo(left + w, cy);
      path.lineTo(left, top + h);
      path.close();
      Paint triangle = new Paint(icon);
      triangle.setStyle(Paint.Style.FILL_AND_STROKE);
      triangle.setStrokeWidth(a.dp(2.5f));
      triangle.setStrokeJoin(Paint.Join.ROUND);
      triangle.setStrokeCap(Paint.Cap.ROUND);
      triangle.setColor(0xFFF2F6FF);
      canvas.drawPath(path, triangle);
    }
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
