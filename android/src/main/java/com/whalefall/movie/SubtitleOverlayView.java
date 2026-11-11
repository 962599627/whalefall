package com.whalefall.movie;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;
import java.util.Collections;
import java.util.List;

/** Wrapper around Media3 SubtitleView so shared playback controls do not depend on Media3 classes. */
final class SubtitleOverlayView extends FrameLayout {
 static final String[] STYLE_NAMES = {
 "纯白黑边·透明底", "暖黄黑边·透明底", "天青黑边·透明底", "翠绿黑边·透明底", "珊瑚粉黑边·透明底", "纯白黑边·半透底"
 };
 static final String[] STYLE_LABELS = STYLE_NAMES;
 static final int[] STYLE_COLORS = {
 Color.WHITE, 0xFFFFE066, 0xFF8BE9FD, 0xFF69F0AE, 0xFFFF9EBB, Color.WHITE
 };

 static String bgStyleLabel(int preset) {
 return styleName(preset);
 }

 private static final String PREVIEW_LINE = "字幕实时预览 · ▲▼ 调高低位置 · ◀▶ 调字体大小 · 确认键 切换字体颜色";

 final androidx.media3.ui.SubtitleView delegate;
 private List<Cue> currentCues = Collections.emptyList();
 private boolean previewActive;

 SubtitleOverlayView(Context context) {
 super(context);
 delegate = new androidx.media3.ui.SubtitleView(context);
 delegate.setApplyEmbeddedStyles(false);
 delegate.setApplyEmbeddedFontSizes(false);
 addView(delegate, new FrameLayout.LayoutParams(-1, -1));
 applyStylePreset(0);
 }

 static String styleName(int preset) {
 int idx = Prefs.clampSubtitleStyle(preset);
 return STYLE_NAMES[idx];
 }

 void setFractionalTextSize(float fractionOfHeight) {
 delegate.setApplyEmbeddedFontSizes(false);
 delegate.setFractionalTextSize(fractionOfHeight);
 }

 void setBottomPaddingFraction(float fractionOfHeight) {
 delegate.setBottomPaddingFraction(fractionOfHeight);
 }

 void setUserDefaultStyle() {
 applyStylePreset(0);
 }

 void applyStylePreset(int preset) {
 int idx = Prefs.clampSubtitleStyle(preset);
 delegate.setApplyEmbeddedStyles(false);
 Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
 CaptionStyleCompat style;
 switch (idx) {
 case 1:
 // Warm cinema yellow text + crisp black outline, transparent background
 style =
 new CaptionStyleCompat(
 0xFFFFE066,
 Color.TRANSPARENT,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xF0000000,
 bold);
 break;
 case 2:
 // Soft cyan text + crisp black outline, transparent background
 style =
 new CaptionStyleCompat(
 0xFF8BE9FD,
 Color.TRANSPARENT,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xF0000000,
 bold);
 break;
 case 3:
 // Mint green text + crisp black outline, transparent background
 style =
 new CaptionStyleCompat(
 0xFF69F0AE,
 Color.TRANSPARENT,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xF0000000,
 bold);
 break;
 case 4:
 // Coral pink text + crisp black outline, transparent background
 style =
 new CaptionStyleCompat(
 0xFFFF9EBB,
 Color.TRANSPARENT,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xF0000000,
 bold);
 break;
 case 5:
 // White text + subtle 48% translucent dark background
 style =
 new CaptionStyleCompat(
 Color.WHITE,
 0x7A000000,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xAA000000,
 bold);
 break;
 case 0:
 default:
 // Default: crisp white text + black outline stroke, 100% transparent background
 style =
 new CaptionStyleCompat(
 Color.WHITE,
 Color.TRANSPARENT,
 Color.TRANSPARENT,
 CaptionStyleCompat.EDGE_TYPE_OUTLINE,
 0xF0000000,
 bold);
 break;
 }
 delegate.setStyle(style);
 }

 void setPreviewActive(boolean active) {
 this.previewActive = active;
 refreshCues();
 }

 boolean isPreviewActive() {
 return previewActive;
 }

 @Override
 public void setPadding(int left, int top, int right, int bottom) {
 if (delegate != null) {
 delegate.setPadding(left, top, right, bottom);
 } else {
 super.setPadding(left, top, right, bottom);
 }
 }

 void setCues(List<Cue> cues) {
 this.currentCues = cues != null ? cues : Collections.emptyList();
 refreshCues();
 }

 private void refreshCues() {
 if (!currentCues.isEmpty()) {
 delegate.setCues(currentCues);
 } else if (previewActive) {
 Cue sample = new Cue.Builder().setText(PREVIEW_LINE).build();
 delegate.setCues(Collections.singletonList(sample));
 } else {
 delegate.setCues(Collections.emptyList());
 }
 }
}
