package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 官方二维码登录弹窗：本地渲染/显示二维码 + 状态文字 + 刷新/关闭（不依赖网页）。 */
final class QrLoginDialog {
    private final MainActivity a;
    final Dialog dialog;
    private final ImageView qrView;
    private final TextView status;
    private Runnable onRefresh;
    volatile boolean closed;

    QrLoginDialog(final MainActivity host, String title, String hint, Runnable refresh) {
        this.a = host;
        this.onRefresh = refresh;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(16));
        card.setBackground(UiTokens.round(a, UiTokens.RADIUS_24, 0xf2141c2a, UiTokens.STROKE));
        UiCompat.setElevation(card, a.dp(18));
        card.setClickable(true);

        TextView heading = a.text(title, UiTokens.TYPE_TITLE_LG, UiTokens.TEXT_PRIMARY);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        TextView sub = a.text(hint, UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(6);
        card.addView(sub, subLp);

        FrameLayout qrBox = new FrameLayout(a);
        qrBox.setBackground(a.bg(0xffffffff, 18));
        qrView = new ImageView(a);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        qrBox.addView(qrView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams qrLp =
                new FrameLayout.LayoutParams(a.dp(260), a.dp(260), Gravity.CENTER_HORIZONTAL);
        qrLp.topMargin = a.dp(16);
        qrLp.bottomMargin = a.dp(12);
        card.addView(qrBox, qrLp);

        status = a.text("正在获取二维码…", 13, MainActivity.MUTED);
        status.setGravity(Gravity.CENTER);
        card.addView(status);

        LinearLayout buttons = a.row();
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.topMargin = a.dp(14);
        card.addView(buttons, btnLp);
        Button refreshBtn = a.button("刷新二维码", "qr-refresh", () -> {
            if (this.onRefresh != null) this.onRefresh.run();
        });
        refreshBtn.setTextSize(13);
        buttons.addView(refreshBtn, new LinearLayout.LayoutParams(0, a.dp(44), 1f));
        Button close = a.button("关闭", "qr-close", dialog::dismiss);
        close.setTextSize(13);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, a.dp(44), 1f);
        closeLp.leftMargin = a.dp(8);
        buttons.addView(close, closeLp);

        int width = Math.min(a.dp(420), a.windowWidth() - a.dp(36));
        FrameLayout root = new FrameLayout(a);
        root.addView(
                card,
                new FrameLayout.LayoutParams(Math.max(a.dp(280), width), -2, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> closed = true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(Math.max(a.dp(280), width), -2);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        refreshBtn.requestFocus();
    }

    void setRefresh(Runnable refresh) {
        onRefresh = refresh;
    }

    void showBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            setStatus("二维码为空");
            return;
        }
        qrView.setImageBitmap(bitmap);
        setStatus("请使用 App 扫描上方二维码");
    }

    void showImageUrl(String url) {
        if (url == null || url.isEmpty()) {
            setStatus("二维码为空");
            return;
        }
        ImageLoader.poster(a, url, qrView, false);
        setStatus("请使用 App 扫描上方二维码");
    }

    void setStatus(String text) {
        a.ui.post(
                () -> {
                    if (!closed) status.setText(text == null ? "" : text);
                });
    }

    void fail(String message) {
        setStatus(message == null ? "获取二维码失败" : message);
    }

    void dismiss() {
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }
}
