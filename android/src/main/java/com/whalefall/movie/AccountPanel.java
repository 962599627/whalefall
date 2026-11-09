package com.whalefall.movie;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「网盘与账号」面板：账号状态卡 + 源提供的功能列表 + 危险区（清除凭据）。
 *
 * <p>状态检测原则：<b>不伪造登录态</b>，三态严格区分（{@link PanLoginState}）：
 * <ul>
 *   <li>已登录（本地凭据）：本机 prefs 里能读到该网盘的 Cookie/Token，绿点；
 *   <li>源登录态可确认：WebView Cookie（插件/CatPaw 登录会落到系统 CookieManager）或
 *       源存储在 shared_prefs 里的严格特征串，绿点但不显示未读到的账号名；
 *   <li>由源管理 · 无法读取：以上都没有时如实标注，<b>不得显示成「未登录」</b>，橙/灰点。
 * </ul>
 * 登录/管理一律先打开我们自己的「网盘登录」玻璃面板（{@link PanLoginGuide}），
 * 只有用户在该面板里明确点「打开源登录界面 / 源账号管理」时才调用源提供的 LoginShow；
 * 接口返回后刷新本面板并重新检测。
 */
final class AccountPanel {
    private AccountPanel() {}

    private static final int STATE_EMPTY = 0;
    private static final int STATE_DISABLED = 1;
    private static final int STATE_UNKNOWN = 2;
    private static final int STATE_GUEST = 3;
    private static final int STATE_LOCAL = 4;
    /** 源可确认的登录信号（WebView Cookie / 源存储），与「本地凭据」「无法读取」区分。 */
    private static final int STATE_SOURCE = 5;
    /** 用户手动确认（「我已在源里登录」），非自动检测。 */
    private static final int STATE_MANUAL = 6;

    private static final int DOT_GREEN = 0xff2ad47a;
    private static final int DOT_GRAY = 0xff8d9cb6;
    private static final int DOT_ORANGE = 0xffffb454;
    private static final int DANGER = 0xffff6b81;

    static void show(MainActivity a, View anchor) {
        if (a == null) return;
        new Panel(a, anchor).show();
    }

    // ====================== 主面板 ======================

    private static final class Panel {
        final MainActivity a;
        final View anchor;
        final Cms.Site site;
        final boolean wide;
        Dialog dialog;
        TextView statusLabel;
        View statusDot;
        TextView detailLabel;
        TextView sourceLabel;
        FrameLayout actionSlot;
        LinearLayout panRows;
        int state = STATE_GUEST;
        List<Credential> credentials = new ArrayList<>();
        /** 每个网盘的三态检测结果（本地凭据 / 源可确认 / 无法读取）。 */
        java.util.Map<String, PanLoginState> panStates = new java.util.LinkedHashMap<>();
        String accountName = "";

        Panel(MainActivity a, View anchor) {
            this.a = a;
            this.anchor = anchor;
            this.site = findSite(a);
            float density = a.getResources().getDisplayMetrics().density;
            this.wide = a.windowWidth() / density >= 600f;
        }

        void show() {
            dialog = new Dialog(a);
            a.accountDialog = dialog;
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout root = new FrameLayout(a);
            root.setTag("account-panel-root");

            LinearLayout card = a.vertical();
            card.setTag("account-panel");
            card.setBackground(
                    UiTokens.round(
                            a, UiTokens.RADIUS_24, 0xf2141c2a, UiTokens.STROKE));
            UiCompat.setElevation(card, a.dp(18));
            card.setClipChildren(false);
            card.setClipToPadding(false);

            // ---- 头部 ----
            LinearLayout header = a.row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(a.dp(24), a.dp(20), a.dp(16), a.dp(4));
            LinearLayout titleCol = a.vertical();
            TextView title = a.text("网盘与账号", UiTokens.TYPE_TITLE_LG, UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            title.setIncludeFontPadding(false);
            titleCol.addView(title);
            TextView subtitle =
                    a.text(
                            "账号由当前配置源提供 · 登录状态以源返回为准",
                            UiTokens.TYPE_MICRO,
                            UiTokens.TEXT_TERTIARY);
            subtitle.setIncludeFontPadding(false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
            subLp.topMargin = a.dp(4);
            titleCol.addView(subtitle, subLp);
            header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
            View close =
                    UiIcon.button(a, UiIcon.CLOSE, "关闭账号面板", 48, 22, () -> dialog.dismiss());
            header.addView(close, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
            card.addView(header, new LinearLayout.LayoutParams(-1, -2));

            // ---- 内容（可滚动） ----
            ScrollView scroll = new ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            LinearLayout body = a.vertical();
            body.setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(24));
            body.setClipChildren(false);
            body.setClipToPadding(false);
            scroll.addView(body);
            card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

            if (site == null) {
                buildEmpty(body);
            } else {
                buildStatusCard(body);
                buildPanAccounts(body);
                buildFunctions(body);
                buildDanger(body);
                refresh();
            }

            int width =
                    Math.min(
                            a.dp(wide ? 560 : 520),
                            a.windowWidth() - a.dp(wide ? 48 : 28));
            width = Math.max(a.dp(280), width);
            root.addView(card, new FrameLayout.LayoutParams(-1, -2));
            dialog.setContentView(root);

            // 内容超高时限制滚动区高度，保证关闭按钮始终可见
            final ScrollView contentScroll = scroll;
            card.post(
                    () -> {
                        int maxHeight = a.windowHeight() - a.dp(32);
                        if (card.getHeight() <= maxHeight) return;
                        int headerHeight = card.getHeight() - contentScroll.getHeight();
                        contentScroll.setLayoutParams(
                                new LinearLayout.LayoutParams(
                                        -1, Math.max(a.dp(160), maxHeight - headerHeight)));
                    });

            dialog.setOnDismissListener(
                    d -> {
                        a.accountDialog = null;
                        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
                    });
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.55f;
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
            // TV 焦点：优先落在状态卡右侧按钮，其次功能行 / 空状态导入按钮
            card.post(
                    () -> {
                        View first =
                                actionSlot != null && actionSlot.getChildCount() > 0
                                        ? actionSlot.getChildAt(0)
                                        : card.findViewWithTag("account-function");
                        if (first == null) first = card.findViewWithTag("account-import");
                        if (first != null) first.requestFocus();
                    });
        }

        // ---------------- 状态卡 ----------------

        void buildStatusCard(LinearLayout body) {
            LinearLayout card = a.row();
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setTag("account-status-card");
            card.setPadding(a.dp(16), a.dp(14), a.dp(14), a.dp(14));
            card.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_18, 0x1affffff, 0x26ffffff));

            FrameLayout badge = new FrameLayout(a);
            int brand = panBrand(site);
            badge.setBackground(UiTokens.round(a, UiTokens.RADIUS_14, alpha(brand, 0x2e)));
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
            icon.setColorFilter(brand, android.graphics.PorterDuff.Mode.SRC_IN);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            badge.addView(
                    icon,
                    new FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER));
            card.addView(badge, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));

            LinearLayout col = a.vertical();
            col.setPadding(a.dp(12), 0, a.dp(8), 0);
            LinearLayout statusRow = a.row();
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            statusDot = new View(a);
            statusRow.addView(statusDot, new LinearLayout.LayoutParams(a.dp(8), a.dp(8)));
            statusLabel = a.text("", 13.5f, UiTokens.TEXT_PRIMARY);
            statusLabel.setTypeface(null, Typeface.BOLD);
            statusLabel.setIncludeFontPadding(false);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
            statusLp.leftMargin = a.dp(7);
            statusRow.addView(statusLabel, statusLp);
            col.addView(statusRow);
            detailLabel = a.text("", UiTokens.TYPE_CAPTION, UiTokens.TEXT_SECONDARY);
            detailLabel.setMaxLines(2);
            detailLabel.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
            detailLp.topMargin = a.dp(4);
            col.addView(detailLabel, detailLp);
            sourceLabel = a.text("", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            sourceLabel.setSingleLine(true);
            sourceLabel.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, -2);
            sourceLp.topMargin = a.dp(3);
            col.addView(sourceLabel, sourceLp);
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            actionSlot = new FrameLayout(a);
            card.addView(actionSlot, new LinearLayout.LayoutParams(-2, -2));
            body.addView(card, new LinearLayout.LayoutParams(-1, -2));

            TextView hint =
                    a.text(
                            "本机只能读取本地凭据；登录界面与启用状态由配置源提供。",
                            UiTokens.TYPE_MICRO,
                            UiTokens.TEXT_TERTIARY);
            hint.setPadding(a.dp(2), a.dp(8), a.dp(2), 0);
            body.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }

        // ---------------- 4 个网盘账号 ----------------

        void buildPanAccounts(LinearLayout body) {
            TextView label = a.text("网盘账号 · 4 个网盘", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            label.setPadding(a.dp(2), a.dp(20), a.dp(2), a.dp(6));
            body.addView(label, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout warn = a.row();
            warn.setGravity(Gravity.CENTER_VERTICAL);
            warn.setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9));
            warn.setBackground(UiTokens.round(a, UiTokens.RADIUS_12, 0x1affb454, 0x33ffb454));
            ImageView info = new ImageView(a);
            info.setImageResource(UiIcon.res(UiIcon.INFO));
            info.setColorFilter(0xffffb454, android.graphics.PorterDuff.Mode.SRC_IN);
            warn.addView(info, new LinearLayout.LayoutParams(a.dp(16), a.dp(16)));
            TextView warnText =
                    a.text(
                            "登录/停用状态由配置源管理；本页展示本机可确认的状态并提供登录引导。",
                            UiTokens.TYPE_MICRO,
                            0xffe6d5b8);
            warnText.setLineSpacing(a.dp(3), 1.3f);
            LinearLayout.LayoutParams warnTextLp = new LinearLayout.LayoutParams(0, -2, 1f);
            warnTextLp.leftMargin = a.dp(8);
            warn.addView(warnText, warnTextLp);
            LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(-1, -2);
            warnLp.bottomMargin = a.dp(8);
            body.addView(warn, warnLp);

            panRows = a.vertical();
            body.addView(panRows, new LinearLayout.LayoutParams(-1, -2));
        }

        void rebuildPanRows() {
            if (panRows == null) return;
            panRows.removeAllViews();
            for (PanLoginGuide.Drive drive : PanLoginGuide.DRIVES) {
                PanLoginState pan = panStates.get(drive.key);
                boolean logged = pan != null && pan.known();
                String statusText = pan == null ? "由源管理 · 无法读取" : pan.detail;
                int statusColor =
                        pan == null || pan.state == PanLoginState.STATE_UNKNOWN
                                ? 0xffffb454
                                : 0xff2ad47a;
                LinearLayout row = a.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10));
                row.setMinimumHeight(a.dp(62));
                row.setBackground(UiTokens.round(a, UiTokens.RADIUS_14, 0x14ffffff));
                row.setFocusable(true);
                row.setFocusableInTouchMode(a.isTvDevice());
                row.setClickable(true);
                FrameLayout badge = new FrameLayout(a);
                badge.setBackground(
                        UiTokens.round(a, UiTokens.RADIUS_12, alpha(drive.color, 0x2e)));
                ImageView icon = new ImageView(a);
                icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
                icon.setColorFilter(drive.color, android.graphics.PorterDuff.Mode.SRC_IN);
                badge.addView(
                        icon,
                        new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER));
                row.addView(badge, new LinearLayout.LayoutParams(a.dp(38), a.dp(38)));
                LinearLayout col = a.vertical();
                col.setPadding(a.dp(10), 0, a.dp(8), 0);
                TextView name = a.text(drive.alias, UiTokens.TYPE_BODY_SM + 1, UiTokens.TEXT_PRIMARY);
                name.setIncludeFontPadding(false);
                col.addView(name);
                TextView sub =
                        a.text(
                                drive.provider + " · " + statusText,
                                UiTokens.TYPE_MICRO,
                                statusColor);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
                subLp.topMargin = a.dp(2);
                col.addView(sub, subLp);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView action = a.text(logged ? "管理" : "登录", 11.5f, UiTokens.ACCENT_SOFT);
                action.setTypeface(null, Typeface.BOLD);
                action.setGravity(Gravity.CENTER);
                action.setPadding(a.dp(12), 0, a.dp(12), 0);
                action.setBackground(UiTokens.round(a, 999, 0x26ff8fb1, 0x4dffb3c8));
                row.addView(action, new LinearLayout.LayoutParams(-2, a.dp(32)));
                MobileDetailScreen.bindTouchFeedback(row);
                row.setOnClickListener(
                        v -> PanLoginGuide.show(a, site, drive.key, () -> refresh(true)));
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
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = a.dp(8);
                panRows.addView(row, rowLp);
            }
        }

        // ---------------- 功能列表 ----------------

        void buildFunctions(LinearLayout body) {
            TextView label =
                    a.text("账号与网盘设置", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            label.setPadding(a.dp(2), a.dp(20), a.dp(2), a.dp(8));
            body.addView(label, new LinearLayout.LayoutParams(-1, -2));
            addFunction(body, UiIcon.LAYERS, "源凭据配置（Cookie / Token）", "由源提供的凭据配置面板；登录请用上方网盘登录引导", "pushCkShow");
            addFunction(
                    body,
                    UiIcon.TUNE,
                    "刷新登录状态",
                    "重新检测：本地凭据 / WebView Cookie / 源存储（无法读取时如实标注）",
                    "accountRefresh");
            addFunction(
                    body,
                    UiIcon.TUNE,
                    "诊断登录数据",
                    "扫描全部 prefs / 私有目录 / Cookie，把命中位置写入运行日志（不含值）",
                    "accountDiag");
            addFunction(body, UiIcon.TUNE, "网盘优先顺序", "调整网盘在播放线路中的优先级", "panSortShow");
            addFunction(body, UiIcon.FILTER, "网盘搜索顺序", "调整网盘在搜索结果中的顺序", "panSourceSortShow");
        }

        void addFunction(
                LinearLayout body, int iconKind, String title, String desc, String action) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag("account-function");
            row.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10));
            row.setMinimumHeight(a.dp(56));
            row.setBackground(UiTokens.round(a, UiTokens.RADIUS_14, 0x14ffffff));
            row.setFocusable(true);
            row.setFocusableInTouchMode(a.isTvDevice());
            row.setClickable(true);
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(iconKind));
            icon.setColorFilter(UiTokens.ACCENT_SOFT, android.graphics.PorterDuff.Mode.SRC_IN);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
            LinearLayout col = a.vertical();
            col.setPadding(a.dp(12), 0, a.dp(8), 0);
            TextView titleView = a.text(title, UiTokens.TYPE_BODY_SM + 1, UiTokens.TEXT_PRIMARY);
            titleView.setIncludeFontPadding(false);
            col.addView(titleView);
            TextView descView = a.text(desc, UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            descView.setIncludeFontPadding(false);
            descView.setSingleLine(true);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
            descLp.topMargin = a.dp(2);
            col.addView(descView, descLp);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView chevron =
                    UiIcon.view(a, UiIcon.CHEVRON_RIGHT, UiTokens.TEXT_TERTIARY, 18);
            row.addView(chevron, new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));
            MobileDetailScreen.bindTouchFeedback(row);
            row.setOnClickListener(
                    v -> {
                        if (action.startsWith("account")) runAction(action);
                        else confirmSourcePanel(action, title);
                    });
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            body.addView(row, lp);
        }

        /** 打开源内设置面板前先给提示（白底样式由源绘制，用户有心理预期）。 */
        void confirmSourcePanel(String action, String label) {
            PanLoginGuide.confirmSourcePanel(
                    a,
                    "打开「" + label + "」",
                    "该设置面板由配置源提供，界面样式不受本 App 控制（可能为白底系统样式）。"
                            + "继续后会直接进入源面板。",
                    () -> runAction(action));
        }

        // ---------------- 危险区 ----------------

        void buildDanger(LinearLayout body) {
            TextView label = a.text("危险操作", UiTokens.TYPE_MICRO, UiTokens.TEXT_TERTIARY);
            label.setPadding(a.dp(2), a.dp(20), a.dp(2), a.dp(8));
            body.addView(label, new LinearLayout.LayoutParams(-1, -2));
            Button clear =
                    a.button(
                            "清除账号与凭据",
                            "account-clear",
                            this::confirmClear);
            clear.setTextSize(UiTokens.TYPE_BODY_SM);
            clear.setTypeface(null, Typeface.BOLD);
            clear.setTextColor(DANGER);
            clear.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_14, 0x14ff6b81, 0x66ff6b81));
            clear.setMinHeight(a.dp(48));
            clear.setMinimumHeight(a.dp(48));
            clear.setPadding(a.dp(16), 0, a.dp(16), 0);
            clear.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    clear,
                                    focused,
                                    0x14ff6b81,
                                    0x33ff6b81,
                                    UiTokens.RADIUS_14,
                                    DANGER,
                                    0xff31141f,
                                    true,
                                    1.02f));
            body.addView(clear, new LinearLayout.LayoutParams(-1, a.dp(48)));
            TextView note =
                    a.text(
                            "仅清除本机保存的网盘登录数据（夸父/优汐/嘟嘟/阿狸），不影响其它设置。",
                            UiTokens.TYPE_MICRO,
                            UiTokens.TEXT_TERTIARY);
            note.setPadding(a.dp(2), a.dp(6), a.dp(2), 0);
            body.addView(note, new LinearLayout.LayoutParams(-1, -2));
        }

        void buildEmpty(LinearLayout body) {
            FrameLayout iconWrap = new FrameLayout(a);
            iconWrap.setBackground(UiTokens.circle(0x26ff8fb1));
            ImageView icon = new ImageView(a);
            icon.setImageResource(UiIcon.res(UiIcon.CLOUD));
            icon.setColorFilter(UiTokens.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
            iconWrap.addView(
                    icon,
                    new FrameLayout.LayoutParams(a.dp(28), a.dp(28), Gravity.CENTER));
            LinearLayout wrap = a.vertical();
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            wrap.addView(iconWrap, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
            TextView title = a.text("当前配置不支持网盘账号", UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.topMargin = a.dp(16);
            wrap.addView(title, titleLp);
            TextView desc =
                    a.text(
                            "导入包含 MyDrive 网盘源的配置后，可在此查看登录状态、配置凭据与清除账号。",
                            UiTokens.TYPE_BODY_SM,
                            UiTokens.TEXT_SECONDARY);
            desc.setGravity(Gravity.CENTER);
            desc.setLineSpacing(a.dp(4), 1.35f);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
            descLp.topMargin = a.dp(8);
            wrap.addView(desc, descLp);
            Button importBtn = accentButton("去导入配置", "account-import", () -> {
                dialog.dismiss();
                a.pickConfig();
            });
            importBtn.setTag("account-import");
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, a.dp(46));
            btnLp.topMargin = a.dp(20);
            wrap.addView(importBtn, btnLp);
            body.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        }

        // ---------------- 状态刷新 ----------------

        int refreshVersion;

        void refresh() {
            refresh(false);
        }

        /**
         * 异步刷新：检测在 worker 线程（扫描 prefs + Cookie），结果回 UI；60s 缓存由
         * {@link PanLoginState} 维护，{@code force=true}（显式刷新/从源面板返回）跳过缓存。
         */
        void refresh(boolean force) {
            if (site == null || statusLabel == null) return;
            final int version = ++refreshVersion;
            if (force) PanLoginState.invalidate();
            if (credentials.isEmpty()) {
                statusLabel.setText("正在检测登录状态…");
                detailLabel.setText("读取本地凭据 / WebView Cookie / 源存储…");
            }
            PanLoginState.detectAsync(
                    a,
                    site,
                    force,
                    snapshot -> {
                        if (version != refreshVersion) return;
                        if (dialog == null || !dialog.isShowing()) return;
                        applySnapshot(snapshot);
                    });
        }

        void applySnapshot(PanLoginState.Snapshot snapshot) {
            credentials = snapshot.credentials;
            accountName = snapshot.accountName;
            panStates = snapshot.states;
            int sourceKnown = 0, unknownDrives = 0, manualDrives = 0;
            for (PanLoginState pan : panStates.values()) {
                if (pan.state == PanLoginState.STATE_SOURCE) sourceKnown++;
                else if (pan.state == PanLoginState.STATE_MANUAL) manualDrives++;
                else if (pan.state == PanLoginState.STATE_UNKNOWN) unknownDrives++;
            }
            boolean available = a.available(site);
            if (!available) {
                state = STATE_DISABLED;
            } else if (!credentials.isEmpty()) {
                state = STATE_LOCAL;
            } else if (sourceKnown > 0) {
                state = STATE_SOURCE;
            } else if (manualDrives > 0) {
                state = STATE_MANUAL;
            } else if (engineStateUnreadable(site)) {
                state = STATE_UNKNOWN;
            } else {
                state = STATE_GUEST;
            }

            int dotColor;
            String label;
            String detail;
            switch (state) {
                case STATE_DISABLED:
                    dotColor = DOT_ORANGE;
                    label = "来源未启用";
                    detail = "请先在“设置 → 搜索与数据”启用来源扩展后再登录。";
                    break;
                case STATE_LOCAL:
                    dotColor = DOT_GREEN;
                    label = "已登录（本地凭据）";
                    detail =
                            accountName.isEmpty()
                                    ? "本地检测到 "
                                            + credentials.size()
                                            + " 项登录数据 · 凭据是否仍有效以源返回为准"
                                    : "账号：" + accountName + " · 凭据是否仍有效以源返回为准";
                    break;
                case STATE_SOURCE:
                    dotColor = DOT_GREEN;
                    label = "源登录态可确认";
                    detail =
                            "检测到 "
                                    + sourceKnown
                                    + " 个网盘的源登录信号（WebView Cookie / 源存储 / 私有文件）；"
                                    + "账号名不可读时请以源面板为准。";
                    break;
                case STATE_MANUAL:
                    dotColor = DOT_GREEN;
                    label = "已登录（用户确认）";
                    detail =
                            "本机无法自动读取源内登录态，" + manualDrives
                                    + " 个网盘由你手动标记为已登录；如需取消可在网盘登录引导里恢复。";
                    break;
                case STATE_UNKNOWN:
                    dotColor = DOT_ORANGE;
                    label = "由源管理 · 无法读取";
                    detail =
                            "本机未发现可读取的登录数据（插件可能加密存储/仅内存）→ "
                                    + "可到源面板确认，或在网盘登录引导里「手动标记为已登录」；"
                                    + "也可先点「诊断登录数据」把日志发我们定位。";
                    break;
                default:
                    dotColor = DOT_GRAY;
                    label = "无法确认 · 由源管理";
                    detail =
                            "本机未发现可读取的登录数据（不等于未登录）→ 可到源面板确认，"
                                    + "或在网盘登录引导里手动标记；也可先点「诊断登录数据」。";
                    break;
            }
            statusDot.setBackground(UiTokens.circle(dotColor));
            statusLabel.setText(label);
            detailLabel.setText(detail);
            String sourceName = site.name == null || site.name.trim().isEmpty() ? site.key : site.name;
            sourceLabel.setText(
                    "配置源 · "
                            + (sourceName == null ? "未知" : sourceName)
                            + (credentials.isEmpty() ? "" : " · 本地凭据 " + credentials.size() + " 项")
                            + (sourceKnown == 0
                                    ? ""
                                    : " · 源登录信号 " + sourceKnown + " 个")
                            + (manualDrives == 0
                                    ? ""
                                    : " · 用户确认 " + manualDrives + " 个")
                            + (unknownDrives == 0
                                    ? ""
                                    : " · 无法读取 " + unknownDrives + " 个"));

            actionSlot.removeAllViews();
            if (state == STATE_DISABLED) {
                Button enable = glassButton("去启用", "account-enable", () -> {
                    dialog.dismiss();
                    a.settings();
                });
                actionSlot.addView(enable, new FrameLayout.LayoutParams(-2, a.dp(44)));
            } else {
                String actionLabel =
                        state == STATE_LOCAL || state == STATE_SOURCE || state == STATE_MANUAL
                                ? "管理"
                                : "登录";
                Button login =
                        accentButton(
                                actionLabel,
                                "account-login",
                                () -> PanLoginGuide.show(a, site, "", () -> refresh(true)));
                actionSlot.addView(login, new FrameLayout.LayoutParams(-2, a.dp(44)));
            }
            rebuildPanRows();
            // 刷新后把 TV 焦点放回状态卡按钮，避免 dpad 失焦
            if (a.isTvDevice() && dialog != null && dialog.isShowing() && actionSlot.getChildCount() > 0) {
                View focused =
                        dialog.getWindow() == null
                                ? null
                                : dialog.getWindow().getDecorView().findFocus();
                if (focused == null || !focused.isAttachedToWindow())
                    actionSlot.getChildAt(0).requestFocus();
            }
        }

        // ---------------- 交互 ----------------

        void runAction(String action) {
            if (site == null) return;
            if ("accountRefresh".equals(action)) {
                PanLoginState.invalidate();
                refresh(true);
                a.toast("已重新检测：本地凭据 / WebView Cookie / 源存储");
                return;
            }
            if ("accountDiag".equals(action)) {
                a.toast("正在扫描登录数据，请稍候…");
                PanLoginState.diagnose(
                        a,
                        () -> {
                            a.toast("诊断已写入运行日志：设置 → 运行日志");
                            PanLoginState.invalidate();
                            refresh(true);
                        });
                return;
            }
            new SettingsScreen(a).accountAction(action, () -> refresh(true));
        }

        void confirmClear() {
            if (credentials.isEmpty()) {
                showNoticeDialog(
                        "没有可清除的凭据",
                        "本机没有检测到网盘登录凭据。若源内仍显示已登录，请在“登录”面板内退出。",
                        null);
                return;
            }
            StringBuilder message = new StringBuilder();
            message.append("将清除本机保存的 ")
                    .append(credentials.size())
                    .append(" 项网盘登录数据（Cookie / Token / 账号缓存）。\n\n")
                    .append("此操作不可恢复；其它设置不受影响。\n")
                    .append("若来源扩展没有提供退出登录接口，源端可能仍保留登录态。\n\n本机待清除项：");
            int shown = 0;
            for (Credential credential : credentials) {
                if (shown >= 3) {
                    message.append("\n… 另有 ").append(credentials.size() - shown).append(" 项");
                    break;
                }
                message.append("\n· ").append(credential.display());
                shown++;
            }
            showClearConfirm(message.toString());
        }

        void showClearConfirm(String message) {
            final Dialog confirm = new Dialog(a);
            confirm.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(a);
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(18));
            card.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_20, 0xf2141c2a, UiTokens.STROKE));
            UiCompat.setElevation(card, a.dp(18));
            TextView title = a.text("清除账号与凭据？", UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
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
            Button cancel = glassButton("取消", "account-clear-cancel", confirm::dismiss);
            LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(a.dp(96), a.dp(46));
            cancelLp.rightMargin = a.dp(10);
            buttons.addView(cancel, cancelLp);
            Button clear = dangerButton("清除", "account-clear-confirm", () -> {
                confirm.dismiss();
                performClear();
            });
            buttons.addView(clear, new LinearLayout.LayoutParams(a.dp(110), a.dp(46)));
            int width =
                    Math.min(
                            a.dp(wide ? 460 : 440),
                            a.windowWidth() - a.dp(wide ? 48 : 40));
            root.addView(
                    card,
                    new FrameLayout.LayoutParams(
                            Math.max(a.dp(260), width), -2, Gravity.CENTER));
            confirm.setContentView(root);
            confirm.show();
            Window window = confirm.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.62f;
                window.setAttributes(attrs);
                window.setLayout(
                        Math.max(a.dp(260), width), -2);
                window.setGravity(Gravity.CENTER);
                window.getDecorView().setSystemUiVisibility(5894);
            }
            cancel.requestFocus();
        }

        void showNoticeDialog(String titleText, String message, Runnable onOk) {
            final Dialog notice = new Dialog(a);
            notice.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(a);
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(18));
            card.setBackground(
                    UiTokens.round(a, UiTokens.RADIUS_20, 0xf2141c2a, UiTokens.STROKE));
            TextView title = a.text(titleText, UiTokens.TYPE_TITLE, UiTokens.TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
            TextView body = a.text(message, UiTokens.TYPE_BODY_SM, UiTokens.TEXT_SECONDARY);
            body.setLineSpacing(a.dp(4), 1.35f);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
            bodyLp.topMargin = a.dp(10);
            card.addView(body, bodyLp);
            Button ok =
                    accentButton(
                            "知道了",
                            "account-notice-ok",
                            () -> {
                                notice.dismiss();
                                if (onOk != null) onOk.run();
                            });
            LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(-1, a.dp(46));
            okLp.topMargin = a.dp(18);
            card.addView(ok, okLp);
            int width = Math.min(a.dp(440), a.windowWidth() - a.dp(40));
            root.addView(
                    card,
                    new FrameLayout.LayoutParams(
                            Math.max(a.dp(260), width), -2, Gravity.CENTER));
            notice.setContentView(root);
            notice.show();
            Window window = notice.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.58f;
                window.setAttributes(attrs);
                window.setLayout(Math.max(a.dp(260), width), -2);
                window.setGravity(Gravity.CENTER);
                window.getDecorView().setSystemUiVisibility(5894);
            }
            ok.requestFocus();
        }

        void performClear() {
            List<Credential> targets = credentials;
            Map<String, SharedPreferences> cache = new LinkedHashMap<>();
            int removed = 0;
            for (Credential credential : targets) {
                try {
                    SharedPreferences sp = cache.get(credential.file);
                    if (sp == null) {
                        sp = a.getSharedPreferences(credential.file, Context.MODE_PRIVATE);
                        cache.put(credential.file, sp);
                    }
                    if (sp.edit().remove(credential.key).commit()) removed++;
                } catch (Throwable error) {
                    AppLog.w("AccountPanel", "clear credential failed: " + credential.display(), error);
                }
            }
            AppLog.i(
                    "AccountPanel",
                    "cleared " + removed + "/" + targets.size() + " pan credential entries");
            // 丢弃缓存引擎，确保下一次访问重新读取（源端无删除接口时只能做到这一步）
            try {
                a.closeEngines();
            } catch (Throwable error) {
                AppLog.w("AccountPanel", "close engines after clear failed", error);
            }
            credentials = new ArrayList<>();
            accountName = "";
            if (dialog != null && dialog.isShowing()) refresh();
            a.toast(removed > 0 ? "已清除账号与凭据" : "未找到可清除的本机凭据");
        }

        // ---------------- 小部件 ----------------

        Button accentButton(String label, String tag, Runnable action) {
            Button b = a.button(label, tag, action);
            b.setTextSize(UiTokens.TYPE_BODY_SM);
            b.setTypeface(null, Typeface.BOLD);
            b.setTextColor(0xff0b1220);
            b.setBackground(UiTokens.round(a, 20, UiTokens.ACCENT));
            b.setMinHeight(a.dp(44));
            b.setMinimumHeight(a.dp(44));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(a.dp(18), 0, a.dp(18), 0);
            b.setElevation(a.dp(2));
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

        Button glassButton(String label, String tag, Runnable action) {
            Button b = a.button(label, tag, action);
            b.setTextSize(UiTokens.TYPE_BODY_SM);
            b.setTextColor(UiTokens.TEXT_PRIMARY);
            b.setBackground(UiTokens.round(a, 20, 0x1affffff, 0x33ffffff));
            b.setMinHeight(a.dp(44));
            b.setMinimumHeight(a.dp(44));
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

        Button dangerButton(String label, String tag, Runnable action) {
            Button b = a.button(label, tag, action);
            b.setTextSize(UiTokens.TYPE_BODY_SM);
            b.setTypeface(null, Typeface.BOLD);
            b.setTextColor(0xff0b1220);
            b.setBackground(UiTokens.round(a, 20, DANGER));
            b.setMinHeight(a.dp(46));
            b.setMinimumHeight(a.dp(46));
            b.setPadding(a.dp(18), 0, a.dp(18), 0);
            b.setOnFocusChangeListener(
                    (v, focused) ->
                            FocusMotion.style(
                                    a,
                                    b,
                                    focused,
                                    DANGER,
                                    0xffffb3bf,
                                    20,
                                    0xff0b1220,
                                    0xff31141f,
                                    true,
                                    1.03f));
            return b;
        }
    }

    // ====================== 状态检测 ======================

    static Cms.Site findSite(MainActivity a) {
        if (a == null || a.config == null || a.config.sites == null) return null;
        for (Cms.Site site : a.config.sites)
            if (site.api != null && site.api.contains("MyDrive")) return site;
        return null;
    }

    /** 引擎内部状态是否不可读（CSP 插件 / 第三方运行时）：此时不猜测登录态。 */
    static boolean engineStateUnreadable(Cms.Site site) {
        if (site == null || site.api == null) return true;
        String api = site.api;
        return api.startsWith("csp_")
                || api.startsWith("catpaw://")
                || api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)
                || !(JsEngine.eligible(site) || DrpyEngine.eligible(site) || site.supported());
    }

    static int panBrand(Cms.Site site) {
        String text =
                ((site.name == null ? "" : site.name)
                                + " "
                                + (site.api == null ? "" : site.api)
                                + " "
                                + (site.ext == null ? "" : site.ext.toString()))
                        .toLowerCase(Locale.ROOT);
        if (text.contains("阿里") || text.contains("aliyun") || text.contains("ali")) return 0xffff8a3d;
        if (text.contains("夸克") || text.contains("quark")) return 0xff8a7bff;
        if (text.contains("百度") || text.contains("baidu")) return 0xff4d7cff;
        if (text.contains("115")) return 0xff3fa9f5;
        if (text.contains("天翼") || text.contains("189") || text.contains("cloud.189")) return 0xff3fc1f5;
        if (text.contains("迅雷") || text.contains("xunlei")) return 0xff42a5f5;
        return 0xffff8fb1;
    }

    static int alpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 0xff) << 24);
    }

    // ====================== 凭据扫描 ======================

    static final class Credential {
        final String file;
        final String key;
        final String value;

        Credential(String file, String key, String value) {
            this.file = file;
            this.key = key;
            this.value = value;
        }

        String display() {
            String shortFile = file.startsWith("js-source-") ? "来源本地存储" : file;
            return shortFile + " · " + key;
        }
    }

    static List<Credential> scanCredentials(MainActivity a, Cms.Site site) {
        List<Credential> out = new ArrayList<>();
        try {
            File dir = null;
            if (a.getApplicationInfo() != null && a.getApplicationInfo().dataDir != null)
                dir = new File(a.getApplicationInfo().dataDir, "shared_prefs");
            if (dir == null || !dir.isDirectory()) {
                scanPrefs(a.prefs, prefsFileName(a), out);
                return out;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                scanPrefs(a.prefs, prefsFileName(a), out);
                return out;
            }
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml")) continue;
                name = name.substring(0, name.length() - 4);
                if (!includePrefsFile(name, site)) continue;
                try {
                    SharedPreferences sp = a.getSharedPreferences(name, Context.MODE_PRIVATE);
                    scanPrefs(sp, name, out);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            AppLog.w("AccountPanel", "scan credentials failed", error);
        }
        return out;
    }

    static String prefsFileName(MainActivity a) {
        return a.privateMode ? "movie-private" : Prefs.FILE;
    }

    static void scanPrefs(SharedPreferences prefs, String fileName, List<Credential> out) {
        if (prefs == null) return;
        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (!credentialKey(key)) continue;
                Object value = entry.getValue();
                out.add(
                        new Credential(
                                fileName,
                                key,
                                value instanceof String ? (String) value : ""));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只认 key 含 pan / drive / cookie / token / credential 的项，避免误删其它设置。 */
    static boolean credentialKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("pan")
                || k.contains("drive")
                || k.contains("cookie")
                || k.contains("token")
                || k.contains("credential")
                || k.contains("cred");
    }

    static boolean includePrefsFile(String name, Cms.Site site) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("movie") || n.equals("movie-private")) return true;
        String siteKey = site == null || site.key == null ? "" : site.key.toLowerCase(Locale.ROOT);
        String apiSuffix = "";
        if (site != null && site.api != null) {
            String api = site.api;
            if (api.startsWith("csp_")) apiSuffix = api.substring(4).toLowerCase(Locale.ROOT);
            else if (api.startsWith("catpaw://"))
                apiSuffix = api.substring(9).toLowerCase(Locale.ROOT);
        }
        // JS 来源只在它自己的存储文件里清理，避免误删其它来源的 token
        String jsStorage = jsStorageName(site);
        if (!jsStorage.isEmpty() && n.equals(jsStorage)) return true;
        if (!siteKey.isEmpty() && n.contains(siteKey)) return true;
        if (!apiSuffix.isEmpty() && n.contains(apiSuffix)) return true;
        // 插件宿主 / 网盘相关命名的 prefs 文件
        if (n.contains("pan")
                || n.contains("drive")
                || n.contains("cookie")
                || n.contains("credential")
                || n.contains("cred")) return true;
        if (n.startsWith("csp") || n.contains("guard")) return true;
        return false;
    }

    /** 与 JsEngine 的本地存储命名保持一致：js-source-<sha256(site.key)>。 */
    static String jsStorageName(Cms.Site site) {
        if (site == null || site.key == null || site.key.isEmpty()) return "";
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(site.key.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(11 + digest.length * 2);
            out.append("js-source-");
            for (byte value : digest)
                out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /** 凭据型键名：cookie/token/credential/auth/login/session；排除配置字段（alias/quality/name/title/desc）。 */
    static boolean credentialConfigKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("alias")
                || k.contains("quality")
                || k.contains("original")
                || k.contains("name")
                || k.contains("title")
                || k.contains("desc")) return false;
        return k.contains("cookie")
                || k.contains("token")
                || k.contains("credential")
                || k.contains("auth")
                || k.contains("login")
                || k.contains("session");
    }

    /**
     * 值是否像凭据（避免配置字面量误判）：
     * ① 形如 {@code name=value; ...} 的 cookie 串（含 = 与 ;）；或
     * ② 长度 ≥16、不含 {@code <} / {@code http} / 空白的 token。
     */
    static boolean credentialValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 8192) return false;
        if (v.contains("<")) return false;
        if (v.contains("=") && v.contains(";")) return true; // cookie 串
        if (v.length() < 16) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("http")) return false;
        for (int i = 0; i < v.length(); i++)
            if (Character.isWhitespace(v.charAt(i))) return false;
        return v.matches("[A-Za-z0-9._~+/=%-]+");
    }

    /** 从已扫描的凭据里判断某个网盘是否在本机留下登录数据（以值形态为准，不猜测源内启用状态）。 */
    static boolean driveHasLocalCredential(List<Credential> credentials, String driveKey) {
        if (credentials == null || driveKey == null) return false;
        for (Credential credential : credentials) {
            String key = credential.key == null ? "" : credential.key;
            String lowerKey = key.toLowerCase(Locale.ROOT);
            String file = credential.file == null ? "" : credential.file;
            String value = credential.value == null ? "" : credential.value;
            // ① 值必须像凭据；② 键名必须是凭据键，或 prefs 文件本身属于来源存储
            if (!credentialValue(value)) continue;
            // 只认凭据键：来源 prefs 里任意键 + `x=y;` 形态值不再算本地凭据（源侧凭据由 PanLoginState 的 STATE_SOURCE 单独判定）。
            if (!credentialConfigKey(key)) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            switch (driveKey) {
                case "quark":
                    if (lower.contains("kps=")
                            || lower.contains("pan.quark.cn")
                            || lowerKey.contains("quark")) return true;
                    break;
                case "uc":
                    if (lower.contains("uc_st")
                            || lower.contains("drive.uc.cn")
                            || lowerKey.contains("uc_")
                            || lowerKey.contains("ucst")) return true;
                    break;
                case "baidu":
                    if (lower.contains("bduss")
                            || lower.contains("stoken=")
                            || lowerKey.contains("bduss")
                            || lowerKey.contains("baidu")) return true;
                    break;
                case "ali":
                    // 不再用裸 ali（会命中 alias/quality/original）；只认明确的网盘/令牌特征
                    if (lower.contains("refresh_token")
                            || lower.contains("aliyundrive")
                            || lower.contains("alipan")
                            || lowerKey.contains("aliyun")
                            || lowerKey.contains("alipan")
                            || lowerKey.contains("refresh_token")) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /** 从凭据里找可展示的账号名（只接受 user/name/account 之类短值，不展示 token）。 */
    static String findAccountName(List<Credential> credentials) {
        for (Credential credential : credentials) {
            String key = credential.key.toLowerCase(Locale.ROOT);
            if (!(key.contains("user")
                    || key.contains("name")
                    || key.contains("account")
                    || key.contains("nick")
                    || key.contains("mobile")
                    || key.contains("uid"))) continue;
            String value = credential.value == null ? "" : credential.value.trim();
            if (value.isEmpty() || value.length() > 40) continue;
            if (value.startsWith("{") || value.startsWith("[")) continue;
            if (value.length() >= 20
                    && (value.matches("[0-9a-fA-F]+") || value.matches("[A-Za-z0-9+/=_-]+"))) {
                // 长随机串更可能是 token，不当账号名展示
                continue;
            }
            return value;
        }
        return "";
    }
}
