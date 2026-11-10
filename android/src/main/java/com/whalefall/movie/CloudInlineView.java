package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.14 云盘配置原地卡片网格（对齐友商图三）：深色玻璃方卡、上半大图标、下半标题+状态。
 * 真实功能：四盘二维码登录 / 真实清除登录 / 源动作排序与推送（失败如实提示，不造假数据）。
 */
final class CloudInlineView {
    private CloudInlineView() {}

    private static final int QUARK_TINT = 0xFF5B8CFF;
    private static final int UC_TINT = 0xFFFF8A3D;
    private static final int BAIDU_TINT = 0xFF3D7EFF;
    private static final int ALI_TINT = 0xFF8A6BFF;

    static void build(final MainActivity a, final LinearLayout parent, final Runnable onChanged) {
        parent.removeAllViews();
        TextView title = a.text("云盘配置", 17, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(2));
        parent.addView(title);
        TextView sub = a.text("登录/清除原生网盘，或调用源动作（真实操作）", 11.5f, MainActivity.MUTED);
        sub.setPadding(a.dp(16), 0, a.dp(16), a.dp(10));
        parent.addView(sub);

        float density = a.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(a.windowWidth() / density);
        int cols;
        if (widthDp < 600) cols = 2;
        else if (widthDp < 900) cols = 3;
        else if (a.isTvDevice() || !a.isPortrait()) cols = 5;
        else cols = 4;
        int gap = a.dp(12);
        int available = a.windowWidth() - a.dp(32);
        int cardW = Math.min((available - gap * (cols - 1)) / cols, a.dp(150));
        int cardH = Math.round(cardW * 1.15f);

        java.util.List<Card> cards = new java.util.ArrayList<>();
        cards.add(
                new Card(
                        UiIcon.PLAY_CIRCLE,
                        UiTokens.ACCENT,
                        "登入自己网盘",
                        "四盘二维码登录",
                        () ->
                                new AppDialog.Builder(a)
                                        .setTitle("选择要登录的网盘")
                                        .setItems(
                                                new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                                (d, index) ->
                                                        NetdiskQrLogin.show(
                                                                a,
                                                                driveAt(index),
                                                                () ->
                                                                        a.ui.postDelayed(
                                                                                onChanged, 200)))
                                        .setNegativeButton("取消", null)
                                        .show()));
        cards.add(clearCard(a, NetdiskQrLogin.QUARK, "清除夸克 Cookie", QUARK_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.UC, "清除优汐 Cookie", UC_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.BAIDU, "清除嘟嘟 Cookie", BAIDU_TINT, onChanged));
        cards.add(clearCard(a, NetdiskQrLogin.ALI, "清除阿狸 token", ALI_TINT, onChanged));
        cards.add(sourceCard(a, UiIcon.LAYERS, 0xFFFFB3C8, "自定网盘排序", "源动作 panSortShow", "panSortShow", onChanged));
        cards.add(sourceCard(a, UiIcon.SWAP, 0xFFFFB3C8, "网盘线路前后排序", "源动作 panSourceSortShow", "panSourceSortShow", onChanged));
        cards.add(sourceCard(a, UiIcon.TV, 0xFFFF8FB1, "手动推送网盘授权", "源动作 pushCkShow", "pushCkShow", onChanged));

        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < cards.size()) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.LEFT);
            for (int c = 0; c < cols && index < cards.size(); c++, index++) {
                LinearLayout cardView = cardView(a, cards.get(index), cardW, cardH);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) lp.leftMargin = gap;
                row.addView(cardView, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (grid.getChildCount() > 0) rowLp.topMargin = gap;
            grid.addView(row, rowLp);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.leftMargin = a.dp(16);
        gridLp.rightMargin = a.dp(16);
        parent.addView(grid, gridLp);
        AppLog.i("CloudConfig", "grid cards=" + cards.size() + " cols=" + cols + " cardW=" + cardW);
    }

    private static String driveAt(int index) {
        switch (index) {
            case 0: return NetdiskQrLogin.QUARK;
            case 1: return NetdiskQrLogin.UC;
            case 2: return NetdiskQrLogin.BAIDU;
            default: return NetdiskQrLogin.ALI;
        }
    }

    private static Card clearCard(
            MainActivity a, final String drive, String title, int tint, final Runnable onChanged) {
        boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
        String state =
                logged
                        ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                        : NetdiskQrLogin.hasCredential(a, drive) ? "未通过验证" : "未登录";
        return new Card(
                UiIcon.CLOUD,
                tint,
                title,
                state,
                () -> {
                    if (!NetdiskQrLogin.hasCredential(a, drive)) {
                        a.toast("该盘当前未登录");
                        return;
                    }
                    NetdiskQrLogin.logout(a, drive);
                    CloudConfigScreen.clearWebCookies(drive);
                    a.toast("已清除" + NetdiskQrLogin.name(drive) + "登录");
                    AppLog.i("CloudConfig", "cleared drive=" + drive);
                    if (onChanged != null) onChanged.run();
                });
    }

    private static Card sourceCard(
            final MainActivity a,
            int icon,
            int tint,
            String title,
            final String action,
            final String subtitle,
            final Runnable onChanged) {
        return new Card(
                icon,
                tint,
                title,
                subtitle,
                () -> {
                    AppLog.i("CloudConfig", "sourceAction=" + action);
                    a.toast("正在请求源：" + title);
                    final boolean[] done = {false};
                    new SettingsScreen(a)
                            .sourceAccountAction(
                                    action,
                                    () -> {
                                        done[0] = true;
                                        AppLog.i("CloudConfig", "sourceAction done=" + action);
                                        if (onChanged != null) onChanged.run();
                                    });
                    // 源无响应/不支持：2.5s 内没有回调则如实提示
                    a.ui.postDelayed(
                            () -> {
                                if (done[0]) return;
                                AppLog.i("CloudConfig", "sourceAction timeout action=" + action);
                                a.toast("当前源不支持该操作");
                            },
                            2500);
                });
    }

    private static LinearLayout cardView(
            MainActivity a, final Card card, int cardW, int cardH) {
        LinearLayout view = a.vertical();
        view.setGravity(Gravity.CENTER);
        view.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(8));
        view.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        view.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(view);
        view.setOnClickListener(v -> card.click.run());
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(card.icon));
        icon.setColorFilter(card.tint, PorterDuff.Mode.SRC_IN);
        view.addView(icon, new LinearLayout.LayoutParams(a.dp(46), a.dp(46)));
        TextView title = a.text(card.title, 12.5f, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = a.dp(8);
        view.addView(title, titleLp);
        TextView sub = a.text(card.subtitle, 10.5f, MainActivity.MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(3);
        view.addView(sub, subLp);
        return view;
    }

    private static final class Card {
        final int icon;
        final int tint;
        final String title;
        final String subtitle;
        final Runnable click;

        Card(int icon, int tint, String title, String subtitle, Runnable click) {
            this.icon = icon;
            this.tint = tint;
            this.title = title;
            this.subtitle = subtitle;
            this.click = click;
        }
    }

    static void showError(MainActivity a, LinearLayout parent, Throwable error) {
        showError(a, parent, error, null);
    }

    static void showError(
            MainActivity a, final LinearLayout parent, Throwable error, final Runnable retryAction) {
        parent.removeAllViews();
        TextView fail =
                a.text(
                        "云盘配置加载失败：" + (error == null ? "" : a.message(error)),
                        13,
                        0xffffb4ad);
        fail.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(8));
        parent.addView(fail);
        TextView retry = a.text("重试", 13, 0xffffb3c8);
        retry.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        retry.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(retry);
        retry.setOnClickListener(
                v -> {
                    if (retryAction != null) retryAction.run();
                    else parent.setVisibility(View.GONE);
                });
        parent.addView(retry);
    }
}
