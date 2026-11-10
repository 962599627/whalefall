package com.whalefall.movie;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.24「云盘专区 · 本地」：只显示 App 本地凭据与本地操作（登录/退出/查看文件），
 * 不显示源插件登录态、不带源动作卡（源动作只在源侧入口）。
 */
final class CloudZoneScreen {
    private CloudZoneScreen() {}

    private static final String[] DRIVES = {
        NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
    };

    static void show(final MainActivity a) {
        a.renderPage("云盘专区 · 本地", "LOCAL CLOUD");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body != null) a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        renderInto(a, a.body, () -> show(a));
    }

    static void renderInto(
            final MainActivity a, final LinearLayout body, final Runnable onChanged) {
        body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        AppLog.i("Wallpaper", "page=CloudZone bg=transparent");
        AppLog.i("CloudZone", "renderInto inline local-only");
        TextView note = a.text("本页是 App 本地直连登录，不经过源插件", 11.5f, MainActivity.MUTED);
        note.setPadding(a.dp(16), 0, a.dp(16), a.dp(8));
        body.addView(note);

        float density = a.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(a.windowWidth() / density);
        int cols = widthDp < 600 ? 2 : widthDp < 900 ? 3 : (a.isTvDevice() || !a.isPortrait() ? 5 : 4);
        int gap = a.dp(12);
        int available = a.windowWidth() - a.dp(32);
        int cardW = Math.min((available - gap * (cols - 1)) / cols, a.dp(150));
        int cardH = Math.max(a.dp(178), Math.round(cardW * 1.22f));
        java.util.List<Runnable> click = new java.util.ArrayList<>();
        java.util.List<Integer> icons = new java.util.ArrayList<>();
        java.util.List<Integer> tints = new java.util.ArrayList<>();
        java.util.List<String> titles = new java.util.ArrayList<>();
        java.util.List<String> states = new java.util.ArrayList<>();

        icons.add(UiIcon.PLAY_CIRCLE);
        tints.add(UiTokens.ACCENT);
        titles.add("登入自己网盘");
        states.add("App 内四盘二维码登录");
        click.add(
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("App 内扫码登录（本地）")
                                .setItems(
                                        new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                        (d, index) ->
                                                NetdiskQrLogin.show(
                                                        a,
                                                        DRIVES[index],
                                                        () -> a.ui.postDelayed(onChanged, 200)))
                                .setNegativeButton("取消", null)
                                .show());

        for (final String drive : DRIVES) {
            boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
            String state =
                    logged
                            ? "App 内已登录" + NetdiskQrLogin.capacityText(a, drive)
                            : NetdiskQrLogin.hasCredential(a, drive) ? "App 内登录未通过验证" : "App 内未登录";
            AppLog.i("CloudZone", "drive=" + drive + " state=" + (logged ? "logged" : "absent"));
            icons.add(UiIcon.CLOUD);
            tints.add(
                    NetdiskQrLogin.QUARK.equals(drive)
                            ? 0xFF5B8CFF
                            : NetdiskQrLogin.UC.equals(drive)
                                    ? 0xFFFF8A3D
                                    : NetdiskQrLogin.BAIDU.equals(drive) ? 0xFF3D7EFF : 0xFF8A6BFF);
            titles.add("退出 App 内" + NetdiskQrLogin.name(drive) + "登录");
            states.add(state);
            click.add(
                    () -> {
                        if (!NetdiskQrLogin.hasCredential(a, drive)) {
                            a.toast("App 内未登录" + NetdiskQrLogin.name(drive));
                            return;
                        }
                        new AppDialog.Builder(a)
                                .setTitle("退出 App 内" + NetdiskQrLogin.name(drive) + "登录？")
                                .setMessage("只清除 App 本地凭据与 WebView Cookie，不影响源插件登录。")
                                .setPositiveButton(
                                        "退出",
                                        (d, i) -> {
                                            NetdiskQrLogin.logout(a, drive);
                                            CloudConfigScreen.clearWebCookies(drive);
                                            a.toast(
                                                    "已退出 App 内"
                                                            + NetdiskQrLogin.name(drive)
                                                            + "登录");
                                            AppLog.i("CloudZone", "logout drive=" + drive);
                                            if (onChanged != null) onChanged.run();
                                        })
                                .setNegativeButton("取消", null)
                                .show();
                    });
        }

        LinearLayout grid = a.vertical();
        int index = 0;
        while (index < titles.size()) {
            LinearLayout row = a.row();
            for (int c = 0; c < cols && index < titles.size(); c++, index++) {
                final int cardIndex = index;
                LinearLayout card = a.vertical();
                card.setGravity(Gravity.CENTER);
                card.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(8));
                card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
                card.setFocusable(true);
                MobileDetailScreen.bindTouchFeedback(card);
                card.setOnClickListener(v -> click.get(cardIndex).run());
                ImageView icon = new ImageView(a);
                icon.setImageResource(UiIcon.res(icons.get(cardIndex)));
                icon.setColorFilter(tints.get(cardIndex), PorterDuff.Mode.SRC_IN);
                card.addView(icon, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
                TextView title = a.text(titles.get(cardIndex), 12, MainActivity.WHITE);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setGravity(Gravity.CENTER);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
                titleLp.topMargin = a.dp(6);
                card.addView(title, titleLp);
                TextView state = a.text(states.get(cardIndex), 9.5f, MainActivity.MUTED);
                state.setGravity(Gravity.CENTER);
                state.setMaxLines(2);
                state.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(state, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) lp.leftMargin = gap;
                row.addView(card, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (grid.getChildCount() > 0) rowLp.topMargin = gap;
            grid.addView(row, rowLp);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.leftMargin = a.dp(16);
        gridLp.rightMargin = a.dp(16);
        body.addView(grid, gridLp);

        boolean quark = NetdiskQrLogin.hasCredential(a, NetdiskQrLogin.QUARK);
        boolean uc = NetdiskQrLogin.hasCredential(a, NetdiskQrLogin.UC);
        if (quark || uc) {
            TextView head = a.text("本地网盘文件", 14, UiTokens.ACCENT_SOFT);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
            body.addView(head);
            if (quark) body.addView(fileRow(a, QuarkUcApi.QUARK));
            if (uc) body.addView(fileRow(a, QuarkUcApi.UC));
        }
    }

    private static LinearLayout fileRow(MainActivity a, final QuarkUcApi.Provider provider) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView name = a.text("查看" + provider.name + "网盘文件", 13, MainActivity.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = a.text("打开", 12, 0xFFFFB3C8);
        go.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        row.addView(go);
        row.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                v -> {
                    AppLog.i("CloudZone", "open files provider=" + provider.key);
                    QuarkUcFiles.show(a, provider);
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = a.dp(16);
        lp.rightMargin = a.dp(16);
        lp.bottomMargin = a.dp(8);
        row.setLayoutParams(lp);
        return row;
    }
}
