package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 网盘登录引导（原生暗色，不依赖源内弹窗）。
 *
 * <p>4 个网盘（我的夸父/优汐/嘟嘟/阿狸）先在我们自己的界面里列出状态与步骤；
 * 只有用户明确点「打开源登录界面」时才调用源提供的 LoginShow —— 那个界面由配置源绘制，
 * 样式不受本 App 控制，所以前面一定先给出提示（方案 B 的心理预期）。
 */
final class PanLoginGuide {
    private PanLoginGuide() {}

    static final class Drive {
        final String key;
        final String alias;
        final String provider;
        final String hint;
        final String linePrefix;
        final int color;

        Drive(String key, String alias, String provider, String hint, String linePrefix, int color) {
            this.key = key;
            this.alias = alias;
            this.provider = provider;
            this.hint = hint;
            this.linePrefix = linePrefix;
            this.color = color;
        }
    }

    static final Drive[] DRIVES = {
        new Drive("quark", "我的夸父", "夸克网盘", "请使用手机「夸克 App」扫码登录", "夸父智1", 0xff3d8bff),
        new Drive("uc", "我的优汐", "UC网盘", "请使用手机「UC浏览器 App」扫码登录", "优汐智1", 0xffff8a3d),
        new Drive("baidu", "我的嘟嘟", "百度网盘", "请使用手机「百度网盘 App」扫码登录", "嘟嘟无限2", 0xff4d7cff),
        new Drive("ali", "我的阿狸", "阿里云盘", "请使用手机「阿里云盘 App」扫码登录", "阿狸原画", 0xffff8f6b)
    };

    static Drive byKey(String key) {
        if (key != null)
            for (Drive drive : DRIVES) if (drive.key.equals(key)) return drive;
        return DRIVES[0];
    }

    /** 打开引导面板；driveKey 为空时先显示 4 个网盘的选择列表。 */
    static void show(MainActivity a, Cms.Site site, String driveKey, Runnable onChanged) {
        new Guide(a, site, driveKey == null ? "" : driveKey, onChanged).show();
    }

    /**
     * 打开源内面板前的统一提示：先说明「接下来是配置源提供的界面」，
     * 用户确认后再执行 action，避免突然弹出白底系统样式的弹窗。
     */
    static void confirmSourcePanel(
            MainActivity a, String title, String message, Runnable confirmed) {
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(a);
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(18));
        card.setBackground(UiTokens.round(a, UiTokens.RADIUS_20, 0xf2141c2a, UiTokens.STROKE));
        UiCompat.setElevation(card, a.dp(18));
        card.setClickable(true);

        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(a);
        icon.setImageResource(UiIcon.res(UiIcon.INFO));
        icon.setColorFilter(UiTokens.ACCENT_SOFT, android.graphics.PorterDuff.Mode.SRC_IN);
        titleRow.addView(icon, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
        TextView heading = a.text(title, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
        heading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-2, -2);
        headingLp.leftMargin = a.dp(9);
        titleRow.addView(heading, headingLp);
        card.addView(titleRow);

        TextView body = a.text(message, UiTokens.TYPE_BODY_SM, UiTokens.TEXT_SECONDARY);
        body.setLineSpacing(a.dp(4), 1.35f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.topMargin = a.dp(10);
        card.addView(body, bodyLp);

        LinearLayout buttons = a.row();
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = a.dp(18);
        card.addView(buttons, buttonsLp);
        Button cancel = glassButton(a, "取消", dialog::dismiss);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(a.dp(92), a.dp(46));
        cancelLp.rightMargin = a.dp(10);
        buttons.addView(cancel, cancelLp);
        Button open =
                accentButton(
                        a,
                        "继续打开",
                        () -> {
                            dialog.dismiss();
                            if (confirmed != null) confirmed.run();
                        });
        buttons.addView(open, new LinearLayout.LayoutParams(a.dp(120), a.dp(46)));

        int width = Math.min(a.dp(460), a.windowWidth() - a.dp(40));
        root.addView(
                card,
                new FrameLayout.LayoutParams(
                        Math.max(a.dp(260), width), -2, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.6f;
            window.setAttributes(attrs);
            window.setLayout(Math.max(a.dp(260), width), -2);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        open.requestFocus();
    }

    // ====================== 引导面板 ======================

    private static final class Guide {
        final MainActivity a;
        final Cms.Site site;
        final String initialDrive;
        final Runnable onChanged;
        final boolean wide;
        Dialog dialog;
        LinearLayout card;
        ScrollView scroll;
        LinearLayout body;
        String activeDrive = "";

        Guide(MainActivity a, Cms.Site site, String drive, Runnable onChanged) {
            this.a = a;
            this.site = site;
            this.initialDrive = drive;
            this.onChanged = onChanged;
            float density = a.getResources().getDisplayMetrics().density;
            this.wide = a.windowWidth() / density >= 600f;
        }

        void show() {
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(a);
            card = a.vertical();
            card.setTag("pan-login-guide");
            card.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_24, 0xf2141c2a, UiTokens.STROKE));
            UiCompat.setElevation(card, a.dp(18));
            card.setClipChildren(false);
            card.setClickable(true);

            LinearLayout header = a.row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(a.dp(24), a.dp(20), a.dp(16), a.dp(4));
            LinearLayout titleCol = a.vertical();
            TextView title = a.text("网盘登录", UiTokens.TYPE_TITLE_LG, UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            title.setIncludeFontPadding(false);
            titleCol.addView(title);
            TextView subtitle =
                    a.text(
                            "账号由配置源管理 · 登录界面由源提供",
                            UiTokens.TYPE_MICRO,
                            UiTokens.TEXT_TERTIARY);
            subtitle.setIncludeFontPadding(false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
            subLp.topMargin = a.dp(4);
            titleCol.addView(subtitle, subLp);
            header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
            View close =
                    UiIcon.button(a, UiIcon.CLOSE, "关闭登录引导", 48, 22, () -> dialog.dismiss());
            header.addView(close, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
            card.addView(header, new LinearLayout.LayoutParams(-1, -2));

            scroll = new ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            body = a.vertical();
            body.setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(24));
            scroll.addView(body);
            card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

            activeDrive = initialDrive;
            render();

            int width =
                    Math.min(a.dp(wide ? 540 : 500), a.windowWidth() - a.dp(wide ? 48 : 28));
            width = Math.max(a.dp(280), width);
            root.addView(card, new FrameLayout.LayoutParams(-1, -2));
            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> {});
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.56f;
                window.setAttributes(attrs);
                window.setLayout(width, -2);
                window.setGravity(Gravity.CENTER);
                window.getDecorView().setSystemUiVisibility(5894);
            }
            if (!a.reduced) {
                card.setAlpha(0f);
                card.setScaleX(0.96f);
                card.setScaleY(0.96f);
                card.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(UiTokens.MOTION_BASE)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        }

        void render() {
            body.removeAllViews();
            List<AccountPanel.Credential> credentials =
                    site == null
                            ? new java.util.ArrayList<>()
                            : AccountPanel.scanCredentials(a, site);
            if (activeDrive.isEmpty()) {
                renderChooser(credentials);
            } else {
                renderSteps(credentials, activeDrive);
            }
        }

        void renderChooser(List<AccountPanel.Credential> credentials) {
            TextView intro =
                    a.text(
                            "选择要登录/管理的网盘。4 个网盘共用配置源提供的登录凭据。",
                            UiTokens.TYPE_BODY_SM,
                            UiTokens.TEXT_SECONDARY);
            intro.setLineSpacing(a.dp(4), 1.35f);
            body.addView(intro);
            for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
                boolean logged =
                        AccountPanel.driveHasLocalCredential(credentials, drive.key);
                body.addView(
                        driveRow(drive, logged, () -> {
                            activeDrive = drive.key;
                            render();
                        }),
                        rowLp());
            }
            body.addView(
                    banner(
                            "提示：登录界面由配置源提供，样式不受本 App 控制；"
                                    + "若源内显示白底页面，属正常现象。"),
                    bannerLp());
        }

        void renderSteps(List<AccountPanel.Credential> credentials, String driveKey) {
            PanLoginGuide.Drive drive = PanLoginGuide.byKey(driveKey);
            boolean logged = AccountPanel.driveHasLocalCredential(credentials, drive.key);

            LinearLayout head = a.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_14, AccountPanel.alpha(drive.color, 0x2e)));
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
            icon.setColorFilter(drive.color, android.graphics.PorterDuff.Mode.SRC_IN);
            badge.addView(
                    icon,
                    new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER));
            head.addView(badge, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
            LinearLayout titleCol = a.vertical();
            titleCol.setPadding(a.dp(10), 0, 0, 0);
            TextView title =
                    a.text(
                            "登录「" + drive.alias + "」",
                            UiTokens.TYPE_TITLE,
                            UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            titleCol.addView(title);
            TextView sub =
                    a.text(drive.provider + " · 线路 " + drive.linePrefix, UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            sub.setPadding(0, a.dp(2), 0, 0);
            titleCol.addView(sub);
            head.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
            body.addView(head);

            LinearLayout status = a.row();
            status.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(a);
            dot.setBackground(UiTokens.circle(logged ? 0xff2ad47a : 0xffffb454));
            status.addView(dot, new LinearLayout.LayoutParams(a.dp(8), a.dp(8)));
            TextView statusText =
                    a.text(
                            logged ? "已登录（本地凭据）" : "未登录 · 状态由源提供",
                            11.5f,
                            logged ? 0xff2ad47a : 0xffffb454);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
            stLp.leftMargin = a.dp(7);
            status.addView(statusText, stLp);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
            statusLp.topMargin = a.dp(14);
            body.addView(status, statusLp);

            TextView stepsTitle =
                    a.text("登录步骤", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            stepsTitle.setPadding(0, a.dp(18), 0, a.dp(6));
            body.addView(stepsTitle);
            String[] steps = {
                "点击下方「打开源登录界面」，进入配置源提供的登录页。",
                "在源登录页里选择「" + drive.alias + "」并扫码（或按源内提示操作）。",
                "手机 " + drive.hint.replace("请使用手机", "").replace("扫码登录", "") + " 扫码并确认。",
                "完成后返回本面板，点「我已登录，刷新状态」。"
            };
            for (int i = 0; i < steps.length; i++) {
                LinearLayout step = a.row();
                step.setGravity(Gravity.TOP);
                TextView index = a.text("" + (i + 1), 10.5f, 0xff0b1220);
                index.setTypeface(null, Typeface.BOLD);
                index.setGravity(Gravity.CENTER);
                index.setBackground(UiTokens.circle(UiTokens.ACCENT_SOFT));
                LinearLayout.LayoutParams indexLp =
                        new LinearLayout.LayoutParams(a.dp(18), a.dp(18));
                indexLp.rightMargin = a.dp(10);
                step.addView(index, indexLp);
                TextView text = a.text(steps[i], UiTokens.TYPE_BODY_SM, UiTokens.TEXT_SECONDARY);
                text.setLineSpacing(a.dp(3), 1.3f);
                step.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
                LinearLayout.LayoutParams stepLp = new LinearLayout.LayoutParams(-1, -2);
                stepLp.bottomMargin = a.dp(8);
                body.addView(step, stepLp);
            }

            body.addView(
                    banner(
                            "接下来若打开源登录界面，将显示配置源自带的页面（白底/红色按钮等样式由源决定），"
                                    + "完成登录后回到本面板即可。"),
                    bannerLp());
            if (logged) {
                Button refresh = accentButton(a, "我已登录，刷新状态", () -> {
                    dialog.dismiss();
                    if (onChanged != null) onChanged.run();
                    a.toast("已按本地凭据刷新");
                });
                body.addView(refresh, actionLp());
            } else {
                Button open =
                        accentButton(
                                a,
                                "打开源登录界面",
                                () -> {
                                    dialog.dismiss();
                                    if (onChanged != null) {
                                        new SettingsScreen(a).accountAction("LoginShow", onChanged);
                                    }
                                    a.toast("已打开源登录界面 · 完成后返回本页刷新");
                                });
                body.addView(open, actionLp());
                Button other =
                        glassButton(a, "我已在其他设备登录", () -> {
                            dialog.dismiss();
                            if (onChanged != null) onChanged.run();
                            a.toast("登录态由源保存；若源内已登录，刷新后本页会显示已登录");
                        });
                body.addView(other, actionLp());
            }
            Button back = glassButton(a, "返回网盘列表", () -> {
                activeDrive = "";
                render();
            });
            body.addView(back, actionLp());
        }

        View driveRow(PanLoginGuide.Drive drive, boolean logged, Runnable click) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10));
            row.setMinimumHeight(a.dp(62));
            row.setBackground(UiTokens.round(a, UiTokens.RADIUS_14, 0x14ffffff));
            row.setFocusable(true);
            row.setClickable(true);
            MobileDetailScreen.bindTouchFeedback(row);
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_12, AccountPanel.alpha(drive.color, 0x2e)));
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
            icon.setColorFilter(drive.color, android.graphics.PorterDuff.Mode.SRC_IN);
            badge.addView(
                    icon,
                    new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER));
            row.addView(badge, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));
            LinearLayout col = a.vertical();
            col.setPadding(a.dp(10), 0, a.dp(8), 0);
            TextView title = a.text(drive.alias, UiTokens.TYPE_BODY_SM + 1, UiTokens.TEXT_PRIMARY);
            title.setIncludeFontPadding(false);
            col.addView(title);
            TextView sub =
                    a.text(
                            drive.provider + " · " + (logged ? "已登录（本地凭据）" : "未登录 · 由源管理"),
                            UiTokens.TYPE_MICRO,
                            logged ? 0xff2ad47a : UiTokens.TEXT_TERTIARY);
            sub.setIncludeFontPadding(false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = a.dp(2);
            col.addView(sub, subLp);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView manage = a.text(logged ? "管理" : "登录", 11.5f, UiTokens.ACCENT_SOFT);
            manage.setTypeface(null, Typeface.BOLD);
            manage.setGravity(Gravity.CENTER);
            manage.setPadding(a.dp(12), 0, a.dp(12), 0);
            manage.setBackground(UiTokens.round(a, 999, 0x26ff8fb1, 0x4dffb3c8));
            row.addView(manage, new LinearLayout.LayoutParams(-2, a.dp(32)));
            row.setOnClickListener(v -> click.run());
            row.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    row,
                                    focused,
                                    0x14ffffff,
                                    0x33ffb3c8,
                                    UiTokens.RADIUS_14,
                                    UiTokens.TEXT_PRIMARY,
                                    UiTokens.TEXT_PRIMARY,
                                    true,
                                    1.02f));
            return row;
        }

        LinearLayout.LayoutParams rowLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            return lp;
        }

        LinearLayout.LayoutParams actionLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(46));
            lp.topMargin = a.dp(8);
            return lp;
        }

        LinearLayout.LayoutParams bannerLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = a.dp(12);
            return lp;
        }

        LinearLayout banner(String text) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            row.setBackground(UiTokens.round(a, UiTokens.RADIUS_12, 0x1affb454, 0x33ffb454));
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(UiIcon.INFO));
            icon.setColorFilter(0xffffb454, android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(icon, new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));
            TextView body = a.text(text, 10.5f, 0xffe6d5b8);
            body.setLineSpacing(a.dp(3), 1.3f);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(0, -2, 1f);
            bodyLp.leftMargin = a.dp(9);
            row.addView(body, bodyLp);
            return row;
        }
    }

    // ====================== 按钮 ======================

    static Button accentButton(MainActivity a, String label, Runnable action) {
        Button b = a.button(label, "pan-guide-accent", action);
        b.setTextSize(UiTokens.TYPE_BODY_SM);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(0xff0b1220);
        b.setBackground(UiTokens.round(a, 20, UiTokens.ACCENT));
        b.setMinHeight(a.dp(46));
        b.setMinimumHeight(a.dp(46));
        b.setPadding(a.dp(18), 0, a.dp(18), 0);
        b.setOnFocusChangeListener(
                (v, focused) ->
                        FocusMotion.style(
                                a,
                                b,
                                focused,
                                UiTokens.ACCENT,
                                0xffffd0de,
                                20,
                                0xff0b1220,
                                0xff31141f,
                                true,
                                1.04f));
        return b;
    }

    static Button glassButton(MainActivity a, String label, Runnable action) {
        Button b = a.button(label, "pan-guide-glass", action);
        b.setTextSize(UiTokens.TYPE_BODY_SM);
        b.setTextColor(UiTokens.TEXT_PRIMARY);
        b.setBackground(UiTokens.round(a, 20, 0x1affffff, 0x33ffffff));
        b.setMinHeight(a.dp(46));
        b.setMinimumHeight(a.dp(46));
        b.setPadding(a.dp(18), 0, a.dp(18), 0);
        b.setOnFocusChangeListener(
                (v, focused) ->
                        FocusMotion.style(
                                a,
                                b,
                                focused,
                                0x1affffff,
                                0x40ffb3c8,
                                20,
                                UiTokens.TEXT_PRIMARY,
                                UiTokens.TEXT_PRIMARY,
                                true,
                                1.03f));
        return b;
    }
}
