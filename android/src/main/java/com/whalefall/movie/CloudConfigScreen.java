package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 1.8.4「云盘配置」标签页：友商风格的大图标操作页（只调用源动作，不重写源功能）。
 *
 * <p>操作全部走源插件动作：LoginShow / panSortShow / panSourceSortShow / pushCkShow；
 * 清除凭据项只弹说明（登录数据在源插件内，App 已无原生凭据）。
 */
final class CloudConfigScreen {
    private CloudConfigScreen() {}

    private static final class Action {
        final int icon;
        final String title;
        final String subtitle;
        final Runnable click;

        Action(int icon, String title, String subtitle, Runnable click) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.click = click;
        }
    }

    static void show(final MainActivity a) {
        a.renderPage("云盘配置", "CLOUD DRIVE");
        int count = 0;
        try {
            java.util.List<Action> actions = new java.util.ArrayList<>();
            actions.add(
                    new Action(
                            UiIcon.CLOUD,
                            "登入自己网盘",
                            "打开源插件账号面板扫码登录",
                            () -> sourceAction(a, "LoginShow")));
            actions.add(
                    new Action(
                            UiIcon.LAYERS,
                            "自定网盘排序",
                            "调整网盘显示顺序（源动作 panSortShow）",
                            () -> sourceAction(a, "panSortShow")));
            actions.add(
                    new Action(
                            UiIcon.SWAP,
                            "网盘线路前后排序",
                            "调整网盘线路优先级（源动作 panSourceSortShow）",
                            () -> sourceAction(a, "panSourceSortShow")));
            actions.add(
                    new Action(
                            UiIcon.REFRESH,
                            "手动推送网盘授权",
                            "打开源的推送授权面板（源动作 pushCkShow）",
                            () -> sourceAction(a, "pushCkShow")));
            String[] clearNames = {"夸克", "UC", "百度", "阿里"};
            for (String name : clearNames)
                actions.add(
                        new Action(
                                UiIcon.INFO,
                                "清除" + name + "凭据",
                                "登录数据在源插件内，需在源面板退出",
                                () ->
                                        new AppDialog.Builder(a)
                                                .setTitle("清除" + name + "凭据")
                                                .setMessage(
                                                        "登录/退出数据由源插件加密保存，App 无法直接清除。"
                                                                + "请在「网盘与账号（源面板）」中退出对应网盘。")
                                                .setPositiveButton(
                                                        "打开源面板",
                                                        (d, i) -> sourceAction(a, "LoginShow"))
                                                .setNegativeButton("知道了", null)
                                                .show()));

            int widthDp = Math.round(a.windowWidth() / a.getResources().getDisplayMetrics().density);
            int cols = widthDp < 360 ? 2 : widthDp < 600 ? 2 : widthDp < 900 ? 3 : 4;
            LinearLayout grid = a.vertical();
            for (int i = 0; i < actions.size(); i += cols) {
                LinearLayout row = a.row();
                for (int c = 0; c < cols && i + c < actions.size(); c++) {
                    row.addView(
                            card(a, actions.get(i + c)),
                            new LinearLayout.LayoutParams(0, a.dp(104), 1f));
                    count++;
                }
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rowLp.topMargin = a.dp(10);
                a.body.addView(row, rowLp);
            }
            AppLog.i("Catalog", "cloud-config items=" + count + " error=");
        } catch (Throwable error) {
            AppLog.i("Catalog", "cloud-config items=0 error=" + error.getClass().getSimpleName());
            a.paragraph("云盘配置页加载失败：" + a.message(error));
            a.addButton(a.body, a.button("重试", "cloud-config-retry", () -> show(a)));
        }
    }

    private static void sourceAction(MainActivity a, String action) {
        AppLog.i("Catalog", "cloud-config action=" + action);
        new SettingsScreen(a).sourceAccountAction(action, null);
    }

    private static View card(MainActivity a, final Action item) {
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER);
        card.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(10));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 16));
        card.setOnClickListener(v -> item.click.run());
        MobileDetailScreen.bindTouchFeedback(card);
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(item.icon));
        icon.setColorFilter(UiTokens.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        card.addView(icon, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        TextView title = a.text(item.title, 13, MainActivity.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = a.dp(8);
        card.addView(title, titleLp);
        TextView subtitle = a.text(item.subtitle, 10.5f, MainActivity.MUTED);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(3);
        card.addView(subtitle, subLp);
        return card;
    }
}
