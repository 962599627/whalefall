package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.8「云盘配置」：友商风格的 5 张真实卡片（不再读 ext URL / 不展示 HTML 原文）：
 * 登入自己网盘（四盘二维码）+ 清除夸克/UC/百度/阿里登录（真实清 prefs + WebView Cookie）。
 * 源动作排序/推送卡因无法验证真实可用，已删除。
 */
final class CloudConfigScreen {
    private CloudConfigScreen() {}

    private static final String[] DRIVES = {
        NetdiskQrLogin.QUARK, NetdiskQrLogin.UC, NetdiskQrLogin.BAIDU, NetdiskQrLogin.ALI
    };

    static void show(final MainActivity a) {
        a.renderPage("云盘配置", "CLOUD DRIVE");
        renderInto(a, a.body, () -> show(a));
    }

    /** 1.8.9：原地渲染到指定容器（首页分类 chips 下方），不跳新页面。 */
    static void renderInto(
            final MainActivity a, final LinearLayout parent, final Runnable onChanged) {
        AppLog.i("CloudConfig", "cards=5 sourcePanel=true inline=true");
        int widthDp = Math.round(a.windowWidth() / a.getResources().getDisplayMetrics().density);
        int cols = widthDp < 360 ? 2 : widthDp < 600 ? 2 : widthDp < 900 ? 3 : 4;
        LinearLayout grid = a.vertical();
        LinearLayout row = a.row();
        row.addView(loginCard(a, onChanged), new LinearLayout.LayoutParams(0, a.dp(104), 1f));
        int inRow = 1;
        for (String drive : DRIVES) {
            if (inRow >= cols) {
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.topMargin = a.dp(10);
                grid.addView(row, rowLp);
                row = a.row();
                inRow = 0;
            }
            row.addView(
                    clearCard(a, drive, onChanged),
                    new LinearLayout.LayoutParams(0, a.dp(104), 1f));
            inRow++;
        }
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(-1, -2);
        lastLp.topMargin = a.dp(10);
        grid.addView(row, lastLp);
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        TextView open = a.text("打开源插件账号面板", 13, 0xffffb3c8);
        open.setGravity(Gravity.CENTER);
        open.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        open.setBackground(MobileDetailScreen.glassBg(a, 0x24ff8fb1, 0x1affffff, 999));
        open.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(open);
        open.setOnClickListener(
                v -> new SettingsScreen(a).sourceAccountAction("LoginShow", null));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, a.dp(48));
        openLp.topMargin = a.dp(16);
        parent.addView(open, openLp);
    }

    private static View loginCard(final MainActivity a, final Runnable onChanged) {
        return card(
                a,
                UiIcon.CLOUD,
                "登入自己网盘",
                "选择网盘后扫码登录",
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("选择要登录的网盘")
                                .setItems(
                                        new String[] {"夸克网盘", "UC 网盘", "百度网盘", "阿里云盘"},
                                        (d, index) ->
                                                NetdiskQrLogin.show(
                                                        a,
                                                        DRIVES[index],
                                                        () -> a.ui.postDelayed(onChanged, 200)))
                                .setNegativeButton("取消", null)
                                .show());
    }

    private static View clearCard(
            MainActivity a, final String drive, final Runnable onChanged) {
        final boolean logged = NetdiskQrLogin.isLoggedIn(a, drive);
        String status =
                logged
                        ? "已登录" + NetdiskQrLogin.capacityText(a, drive)
                        : NetdiskQrLogin.hasCredential(a, drive) ? "登录信息未通过验证" : "未登录";
        return card(
                a,
                logged ? UiIcon.INFO : UiIcon.REFRESH,
                "清除" + NetdiskQrLogin.name(drive) + "登录",
                status,
                () -> clear(a, drive, onChanged));
    }

    private static void clear(final MainActivity a, final String drive, final Runnable onChanged) {
        if (!NetdiskQrLogin.hasCredential(a, drive)) {
            a.toast("该盘当前未登录");
            return;
        }
        NetdiskQrLogin.logout(a, drive);
        clearWebCookies(drive);
        a.toast("已清除" + NetdiskQrLogin.name(drive) + "登录");
        AppLog.i("CloudConfig", "cleared drive=" + drive);
        if (onChanged != null) onChanged.run();
    }

    /** 同步清 WebView CookieManager 对应域，避免网页登录态残留。 */
    private static void clearWebCookies(String drive) {
        try {
            android.webkit.CookieManager manager = android.webkit.CookieManager.getInstance();
            String[] urls;
            switch (drive) {
                case NetdiskQrLogin.QUARK:
                    urls = new String[] {"https://pan.quark.cn/", "https://quark.cn/"};
                    break;
                case NetdiskQrLogin.UC:
                    urls = new String[] {"https://drive.uc.cn/", "https://pc-api.uc.cn/"};
                    break;
                case NetdiskQrLogin.BAIDU:
                    urls = new String[] {"https://pan.baidu.com/", "https://passport.baidu.com/"};
                    break;
                default:
                    urls = new String[] {"https://www.alipan.com/", "https://passport.aliyundrive.com/"};
                    break;
            }
            for (String url : urls) {
                String cookie = manager.getCookie(url);
                if (cookie == null || cookie.isEmpty()) continue;
                for (String part : cookie.split(";")) {
                    String name = part.split("=", 2)[0].trim();
                    if (!name.isEmpty()) manager.setCookie(url, name + "=; Max-Age=0; path=/");
                }
            }
            manager.flush();
        } catch (Throwable error) {
            AppLog.w("CloudConfig", "clear web cookies failed", error);
        }
    }

    private static View card(
            MainActivity a, int icon, String title, String subtitle, Runnable click) {
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER);
        card.setPadding(a.dp(10), a.dp(12), a.dp(10), a.dp(10));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
        card.setFocusable(true);
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(v -> click.run());
        ImageView iconView = new ImageView(a);
        iconView.setImageResource(UiIcon.res(icon));
        iconView.setColorFilter(UiTokens.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        card.addView(iconView, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        TextView titleView = a.text(title, 13, MainActivity.WHITE);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = a.dp(8);
        card.addView(titleView, titleLp);
        TextView sub = a.text(subtitle, 10.5f, MainActivity.MUTED);
        sub.setGravity(Gravity.CENTER);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(3);
        card.addView(sub, subLp);
        return card;
    }
}
