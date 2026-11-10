package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.List;

/**
 * 1.9.2 下载中心：全局任务（进行中/暂停/失败）+ 完成历史（prefs 持久化）。
 */
final class DownloadCenterScreen {
    private DownloadCenterScreen() {}

    static void show(final MainActivity a) {
        a.renderPage("下载中心", "DOWNLOADS");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.body.setTag("download-center");
        a.body.setPadding(a.dp(16), a.dp(16) + statusBar(a), a.dp(16), a.dp(24));
        LinearLayout actions = a.row();
        TextView back = a.text("‹ 文件管理", 12, UiTokens.TEXT_PRIMARY);
        back.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> FileManagerScreen.show(a));
        actions.addView(back);
        TextView copyLogs = a.text("复制日志", 12, UiTokens.ACCENT);
        copyLogs.setPadding(a.dp(10), a.dp(7), a.dp(10), a.dp(7));
        copyLogs.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(copyLogs);
        copyLogs.setOnClickListener(v -> FileManagerScreen.copyQuarkLogs(a));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
        copyLp.leftMargin = a.dp(8);
        actions.addView(copyLogs, copyLp);
        a.body.addView(actions);
        final LinearLayout container = a.vertical();
        a.body.addView(container);
        final Runnable[] poll = new Runnable[1];
        poll[0] =
                () -> {
                    if (a.body == null || !"download-center".equals(a.body.getTag())) return;
                    render(a, container);
                    a.ui.postDelayed(poll[0], 700);
                };
        poll[0].run();
        AppLog.i("DownCenter", "page=DownloadCenter");
    }

    private static void render(MainActivity a, LinearLayout container) {
        container.removeAllViews();
        List<FileManagerCore.Info> tasks = FileManagerCore.tasks();
        TextView running = a.text("进行中/暂停/失败（" + tasks.size() + "）", 14, UiTokens.ACCENT_SOFT);
        running.setPadding(0, a.dp(10), 0, a.dp(6));
        container.addView(running);
        if (tasks.isEmpty()) {
            TextView empty = a.text("暂无进行中的下载任务", 13, UiTokens.TEXT_TERTIARY);
            empty.setPadding(0, a.dp(6), 0, a.dp(6));
            container.addView(empty);
        }
        for (FileManagerCore.Info info : tasks) addTaskRow(a, container, info);
        List<FileManagerCore.Info> history = FileManagerCore.history(a);
        TextView done = a.text("已完成（" + history.size() + "）", 14, UiTokens.SUCCESS);
        done.setPadding(0, a.dp(14), 0, a.dp(6));
        container.addView(done);
        if (history.isEmpty()) {
            TextView empty = a.text("还没有完成记录", 13, UiTokens.TEXT_TERTIARY);
            empty.setPadding(0, a.dp(6), 0, a.dp(6));
            container.addView(empty);
        }
        for (FileManagerCore.Info info : history) addHistoryRow(a, container, info);
        AppLog.i("DownCenter", "tasks=" + tasks.size() + " done=" + history.size());
    }

    private static boolean isAccountLimit(String error) {
        return error != null
                && (error.contains("23018") || error.contains("账号限制") || error.contains("非会员"));
    }

    private static void addTaskRow(
            MainActivity a, LinearLayout container, final FileManagerCore.Info info) {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        TextView name = a.text(info.name, 13, UiTokens.TEXT_PRIMARY);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name);
        String stateText;
        switch (info.state) {
            case 0:
                stateText = "下载中";
                break;
            case 1:
                stateText = "暂停中…";
                break;
            case 2:
                stateText = "已暂停";
                break;
            case 4:
                stateText = "失败：" + (info.error == null ? "" : info.error);
                break;
            default:
                stateText = "已完成";
                break;
        }
        TextView meta =
                a.text(
                        info.provider
                                + (info.fromSource ? "（源插件）" : "（App 内）")
                                + " · "
                                + stateText
                                + " · "
                                + QuarkUcApi.formatSize(info.bytes)
                                + (info.total > 0 ? " / " + QuarkUcApi.formatSize(info.total) : "")
                                + (info.speed > 0 ? " · " + QuarkUcApi.formatSize(info.speed) + "/s" : ""),
                        11,
                        info.state == 4 ? 0xffff8fa3 : UiTokens.TEXT_TERTIARY);
        meta.setPadding(0, a.dp(3), 0, 0);
        card.addView(meta);
        if (info.state == 0 || info.state == 1 || info.state == 2) {
            ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(
                    info.total > 0 ? (int) Math.min(100, info.bytes * 100 / info.total) : 0);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiTokens.ACCENT));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, a.dp(3));
            barLp.topMargin = a.dp(6);
            card.addView(bar, barLp);
        }
        LinearLayout actions = a.row();
        actions.setPadding(0, a.dp(8), 0, 0);
        if (info.state == 0) actions.addView(action(a, "暂停", () -> FileManagerCore.pause(info.id)));
        if (info.state == 1 || info.state == 2)
            actions.addView(action(a, "继续", () -> FileManagerCore.resume(info.id)));
        if (info.state == 4 && !isAccountLimit(info.error))
            actions.addView(action(a, "重试", () -> FileManagerCore.retry(info.id)));
        if (info.state != 4)
            actions.addView(action(a, "取消", () -> FileManagerCore.cancel(info.id)));
        else if (isAccountLimit(info.error))
            actions.addView(
                    action(
                            a,
                            "账号限制",
                            () ->
                                    new AppDialog.Builder(a)
                                            .setTitle("无法重试")
                                            .setMessage(
                                                    "夸克非会员下载限制（code 23018）：该文件超过账号允许的下载大小，"
                                                            + "开通会员或改在源里观看。")
                                            .setPositiveButton("关闭", (d, i) -> {})
                                            .setNeutralButton(
                                                    "复制日志",
                                                    (d, i) -> FileManagerScreen.copyQuarkLogs(a))
                                            .show()));
        card.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(8);
        container.addView(card, lp);
    }

    private static void addHistoryRow(
            MainActivity a, LinearLayout container, final FileManagerCore.Info info) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9));
        card.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0xb3121d2e, 14));
        LinearLayout text = a.vertical();
        TextView name = a.text(info.name, 12.5f, UiTokens.TEXT_PRIMARY);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        TextView sub =
                a.text(
                        info.provider
                                + " · "
                                + QuarkUcApi.formatSize(info.bytes)
                                + " · "
                                + FileManagerCore.formatDate(info.time),
                        10.5f,
                        UiTokens.TEXT_TERTIARY);
        text.addView(sub);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView open = a.text("打开", 12, UiTokens.ACCENT);
        open.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
        MobileDetailScreen.bindTouchFeedback(open);
        open.setOnClickListener(v -> FileManagerCore.playLocal(a, new java.io.File(info.path)));
        card.addView(open);
        TextView remove = a.text("删除记录", 11, 0xffff8fa3);
        remove.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
        MobileDetailScreen.bindTouchFeedback(remove);
        remove.setOnClickListener(
                v -> {
                    FileManagerCore.removeHistory(a, info.path);
                    DownloadCenterScreen.show(a);
                });
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(-2, -2);
        removeLp.leftMargin = a.dp(6);
        card.addView(remove, removeLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(6);
        container.addView(card, lp);
    }

    private static TextView action(MainActivity a, String label, Runnable run) {
        TextView view = a.text(label, 12, UiTokens.TEXT_PRIMARY);
        view.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        view.setBackground(MobileDetailScreen.glassBg(a, 0x24ffffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(view);
        view.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(6);
        view.setLayoutParams(lp);
        return view;
    }

    private static int statusBar(MainActivity a) {
        try {
            int id =
                    a.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return a.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return a.dp(24);
    }
}
