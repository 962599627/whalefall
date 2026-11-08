package com.whalefall.movie;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 内置文件管理：展示管理后台 / FTP 上传到应用内的文件。
 *
 * <p>Markdown / TXT 用内置阅读器打开；视频用内置播放器播放；其它文件可以复制路径或删除。
 */
final class FilesScreen {
    private FilesScreen() {}

    static void show(MainActivity a) {
        a.clearPlaybackLoading();
        a.cancelPageRequests();
        a.generation++;
        a.applyMobileSystemBars(false);

        a.root = new FrameLayout(a);
        a.installBackdrop(a.root);
        a.setContentView(a.root);

        LinearLayout column = a.vertical();
        column.setPadding(a.dp(20), MobileDetailScreen.getStatusBarHeight(a) + a.dp(12), a.dp(20), a.dp(32));
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        a.root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        a.scroll = scroll;
        a.body = column;

        LinearLayout nav = a.row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = a.text("‹", 24, MainActivity.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> a.onBackPressed());
        nav.addView(back, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
        TextView title = a.text("文件管理", 20, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = a.dp(12);
        nav.addView(title, titleLp);
        TextView refresh = a.text("刷新", 12.5f, 0xffffb3c8);
        refresh.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        refresh.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(refresh);
        refresh.setOnClickListener(v -> show(a));
        nav.addView(refresh);
        column.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        a.gap(column, 14);

        // 上传入口说明
        LinearLayout hint = a.vertical();
        hint.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        hint.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 18));
        TextView h1 = a.text("怎么把文件放进 App？", 14, MainActivity.WHITE);
        h1.setTypeface(null, Typeface.BOLD);
        hint.addView(h1);
        String ftp = MiniFtpServer.RUNNING == null ? "管理后台 → 文件 上传" : "FTP：ftp://" + AdminConsole.lanIp() + ":2121/";
        TextView h2 = a.text(
                "设置 → 备份、更新与诊断 → 管理员后台（网页）打开后：\n· " + ftp + "\n· 或网页后台「文件」页直接上传",
                11.5f,
                0xff9fb0c9);
        h2.setLineSpacing(a.dp(3), 1.05f);
        LinearLayout.LayoutParams h2Lp = new LinearLayout.LayoutParams(-1, -2);
        h2Lp.topMargin = a.dp(6);
        hint.addView(h2, h2Lp);
        column.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        a.gap(column, 12);

        List<File> files = PushFiles.list(a);
        if (files.isEmpty()) {
            LinearLayout empty = a.vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(a.dp(16), a.dp(34), a.dp(16), a.dp(34));
            empty.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 18));
            TextView ico = a.text("📁", 34, 0xffffb3c8);
            ico.setGravity(Gravity.CENTER);
            empty.addView(ico);
            TextView t = a.text("还没有文件", 15, MainActivity.WHITE);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            empty.addView(t);
            TextView d = a.text("通过管理后台上传后，这里会出现文件", 12, 0xff8d9cb6);
            d.setGravity(Gravity.CENTER);
            empty.addView(d);
            column.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (final File f : files) {
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(12), a.dp(12), a.dp(12));
            row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 16));
            MobileDetailScreen.bindTouchFeedback(row);

            String name = f.getName();
            TextView type = a.text(fileBadge(name), 18, 0xffffffff);
            type.setTypeface(null, Typeface.BOLD);
            type.setGravity(Gravity.CENTER);
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(MobileDetailScreen.glassBg(
                    a,
                    PushFiles.isMarkdown(name) ? 0xffff8fb1 : PushFiles.isVideo(name) ? 0xff6a8cff : 0xcc2b3a52,
                    0x33ffffff,
                    12));
            badge.addView(type, new FrameLayout.LayoutParams(-1, -1));
            row.addView(badge, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));

            LinearLayout meta = a.vertical();
            TextView n = a.text(name, 14, MainActivity.WHITE);
            n.setTypeface(null, Typeface.BOLD);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            meta.addView(n);
            String date =
                    new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(f.lastModified()));
            TextView s = a.text(PushFiles.sizeLabel(f.length()) + " · " + date, 11, 0xff8d9cb6);
            s.setPadding(0, a.dp(3), 0, 0);
            meta.addView(s);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, -2, 1f);
            metaLp.leftMargin = a.dp(12);
            row.addView(meta, metaLp);

            TextView del = a.text("删除", 11.5f, 0xffff9ab5);
            del.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            del.setBackground(MobileDetailScreen.glassBg(a, 0x26ff8fb1, 0x4dffb3c8, 999));
            final MainActivity host = a;
            del.setOnClickListener(
                    v -> new AppDialog.Builder(host)
                            .setTitle("删除文件")
                            .setMessage("确定删除「" + name + "」？此操作不可恢复。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton(
                                    "删除",
                                    (d, w) -> {
                                        PushFiles.delete(host, name);
                                        host.toast("已删除 " + name);
                                        show(host);
                                    })
                            .show());
            row.addView(del);

            final String target = name;
            row.setOnClickListener(
                    v -> {
                        if (PushFiles.isMarkdown(target)) {
                            MarkdownScreen.show(a, target);
                        } else if (PushFiles.isVideo(target)) {
                            PushFiles.playUrl(a, "file://" + f.getAbsolutePath(), target);
                        } else {
                            a.toast("路径：" + f.getAbsolutePath());
                        }
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            column.addView(row, lp);
        }
    }

    private static String fileBadge(String name) {
        if (PushFiles.isMarkdown(name)) return "MD";
        if (PushFiles.isVideo(name)) return "▶";
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "?";
        return ext.length() > 2 ? ext.substring(0, 2) : ext;
    }
}
