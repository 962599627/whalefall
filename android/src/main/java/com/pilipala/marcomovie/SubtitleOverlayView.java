package com.pilipala.marcomovie;

import android.content.Context;
import android.widget.FrameLayout;
import androidx.media3.common.text.Cue;
import java.util.List;

/** Wrapper around Media3 SubtitleView so shared playback controls do not depend on Media3 classes. */
final class SubtitleOverlayView extends FrameLayout {
    final androidx.media3.ui.SubtitleView delegate;

    SubtitleOverlayView(Context context) {
        super(context);
        delegate = new androidx.media3.ui.SubtitleView(context);
        addView(delegate, new FrameLayout.LayoutParams(-1, -1));
    }

    void setFractionalTextSize(float fractionOfHeight) {
        delegate.setFractionalTextSize(fractionOfHeight);
    }

    void setUserDefaultStyle() {
        delegate.setUserDefaultStyle();
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
        delegate.setCues(cues);
    }
}
