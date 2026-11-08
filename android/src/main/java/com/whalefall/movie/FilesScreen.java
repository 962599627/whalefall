package com.whalefall.movie;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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
 * 内置文件管理：展示管理后台 / FTP 上传到应用内的文件，支持新建文件夹、重命名、删除。
 *
 * <p>Markdown / TXT 用内置阅读器打开；视频用内置播放器播放；其它文件可以复制路径。
 */
final class FilesScreen {
    private FilesScreen() {}

    static void show(MainActivity a) {
        show(a, "");
    }

    static void show(MainActivity a, String relDir) {
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

        final String path = PushFiles.sanitizePath(relDir);

        LinearLayout nav = a.row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = a.text("‹", 24, MainActivity.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(
                v -> {
                    if (path.isEmpty()) a.onBackPressed();
                    else show(a, PushFiles.parentOf(path));
                });
        nav.addView(back, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
        TextView title = a.text(path.isEmpty() ? "文件管理" : PushFiles.baseName(path), 20, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = a.dp(12);
        nav.addView(title, titleLp);
        TextView mkdir = a.text("＋ 文件夹", 12.5f, 0xffffb3c8);
        mkdir.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
        mkdir.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(mkdir);
        mkdir.setOnClickListener(v -> createFolder(a, path));
        nav.addView(mkdir);
        column.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        a.gap(column, 14);

        if (path.isEmpty()) {
            LinearLayout hint = a.vertical();
            hint.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
            hint.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 18));
            TextView h1 = a.text("怎么把文件放进 App？", 14, MainActivity.WHITE);
            h1.setTypeface(null, Typeface.BOLD);
            hint.addView(h1);
            String ftp =
                    MiniFtpServer.RUNNING == null
                            ? "管理后台 → 文件 上传"
                            : "FTP：ftp://" + AdminConsole.lanIp() + ":2121/";
            TextView h2 = a.text(
                    "设置 → 备份、更新与诊断 → 管理员后台（网页）打开后：\n· "
                            + ftp
                            + "\n· 或网页后台「文件」页直接上传",
                    11.5f,
                    0xff9fb0c9);
            h2.setLineSpacing(a.dp(3), 1.05f);
            LinearLayout.LayoutParams h2Lp = new LinearLayout.LayoutParams(-1, -2);
            h2Lp.topMargin = a.dp(6);
            hint.addView(h2, h2Lp);
            column.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            a.gap(column, 12);
        }

        List<File> files = PushFiles.list(a, path);
        if (files.isEmpty()) {
            LinearLayout empty = a.vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(a.dp(16), a.dp(34), a.dp(16), a.dp(34));
            empty.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 18));
            TextView ico = a.text("📁", 34, 0xffffb3c8);
            ico.setGravity(Gravity.CENTER);
            empty.addView(ico);
            TextView t = a.text(path.isEmpty() ? "还没有文件" : "这个文件夹是空的", 15, MainActivity.WHITE);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            empty.addView(t);
            TextView d = a.text("通过管理后台 / FTP 上传后，这里会出现文件", 12, 0xff8d9cb6);
            d.setGravity(Gravity.CENTER);
            empty.addView(d);
            column.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (final File f : files) {
            final String rel = PushFiles.join(path, f.getName());
            LinearLayout row = a.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(14), a.dp(12), a.dp(12), a.dp(12));
            row.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x26ffffff, 16));
            MobileDetailScreen.bindTouchFeedback(row);

            String name = f.getName();
            boolean dir = f.isDirectory();
            TextView type = a.text(dir ? "📁" : fileBadge(name), dir ? 18 : 16, 0xffffffff);
            type.setTypeface(null, Typeface.BOLD);
            type.setGravity(Gravity.CENTER);
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(MobileDetailScreen.glassBg(
                    a,
                    dir
                            ? 0xcc3a2b52
                            : PushFiles.isMarkdown(name)
                                    ? 0xffff8fb1
                                    : PushFiles.isVideo(name) ? 0xff6a8cff : 0xcc2b3a52,
                    0x33ffffff,
                    12));
            badge.addView(type, new FrameLayout.LayoutParams(-1, -1));
            row.addView(badge, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));

            LinearLayout meta = a.vertical();
            TextView n = a.text((dir ? "📁 " : "") + name, 14, MainActivity.WHITE);
            n.setTypeface(null, Typeface.BOLD);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            meta.addView(n);
            String date =
                    new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(f.lastModified()));
            TextView s =
                    a.text(
                            dir
                                    ? (f.listFiles() == null ? 0 : f.listFiles().length) + " 项"
                                    : PushFiles.sizeLabel(f.length()) + " · " + date,
                            11,
                            0xff8d9cb6);
            s.setPadding(0, a.dp(3), 0, 0);
            meta.addView(s);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, -2, 1f);
            metaLp.leftMargin = a.dp(12);
            row.addView(meta, metaLp);

            TextView more = a.text("⋯", 16, 0xffc9d4e6);
            more.setGravity(Gravity.CENTER);
            more.setPadding(a.dp(10), 0, a.dp(10), 0);
            more.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            more.setOnClickListener(v -> fileMenu(a, rel, path));
            row.addView(more, new LinearLayout.LayoutParams(a.dp(38), a.dp(30)));

            final String target = rel;
            final File targetFile = f;
            row.setOnClickListener(
                    v -> {
                        if (targetFile.isDirectory()) {
                            show(a, target);
                        } else if (PushFiles.isMarkdown(target)) {
                            MarkdownScreen.show(a, target);
                        } else if (PushFiles.isImage(target)) {
                            showImage(a, targetFile, target);
                        } else if (PushFiles.isApk(target)) {
                            installApk(a, targetFile);
                        } else if (PushFiles.isVideo(target)) {
                            PushFiles.playUrl(a, "file://" + targetFile.getAbsolutePath(), target);
                        } else {
                            a.toast("路径：" + targetFile.getAbsolutePath());
                        }
                    });
            row.setOnLongClickListener(
                    v -> {
                        fileMenu(a, rel, path);
                        return true;
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            column.addView(row, lp);
        }
    }

    private static void fileMenu(MainActivity a, String rel, String dir) {
        final String[] labels = {"重命名", "删除"};
        new AppDialog.Builder(a)
                .setTitle(PushFiles.baseName(rel))
                .setItems(
                        labels,
                        (d, index) -> {
                            if (index == 0) rename(a, rel, dir);
                            else
                                new AppDialog.Builder(a)
                                        .setTitle("删除")
                                        .setMessage("确定删除「" + PushFiles.baseName(rel) + "」？不可恢复。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton(
                                                "删除",
                                                (dd, w) -> {
                                                    PushFiles.delete(a, rel);
                                                    a.toast("已删除");
                                                    show(a, dir);
                                                })
                                        .show();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void rename(MainActivity a, String rel, String dir) {
        EditText input = new EditText(a);
        input.setText(PushFiles.baseName(rel));
        input.setSelectAllOnFocus(true);
        new AppDialog.Builder(a)
                .setTitle("重命名")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (d, w) -> {
                            try {
                                PushFiles.rename(a, rel, input.getText().toString());
                                a.toast("已重命名");
                                show(a, dir);
                            } catch (Exception e) {
                                a.toast("重命名失败：" + e.getMessage());
                            }
                        })
                .show();
    }

    private static void createFolder(MainActivity a, String dir) {
        EditText input = new EditText(a);
        input.setHint("文件夹名称");
        new AppDialog.Builder(a)
                .setTitle("新建文件夹")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "创建",
                        (d, w) -> {
                            try {
                                String name = input.getText().toString().trim();
                                if (name.isEmpty()) throw new IllegalArgumentException("名称不能为空");
                                PushFiles.mkdirs(a, PushFiles.join(dir, name));
                                a.toast("已创建文件夹");
                                show(a, dir);
                            } catch (Exception e) {
                                a.toast("创建失败：" + e.getMessage());
                            }
                        })
                .show();
    }

    /** 图片预览：全屏暗底 + 自适应图片，点任意处返回。 */
    private static void showImage(MainActivity a, File file, String rel) {
        FrameLayout overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0xee000000);
        overlay.setClickable(true);
        android.widget.ImageView image = new android.widget.ImageView(a);
        image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        a.poster("file://" + file.getAbsolutePath(), image);
        overlay.addView(image, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        TextView hint = a.text(PushFiles.baseName(rel) + " · 点任意处返回", 12, 0xccffffff);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp =
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hintLp.bottomMargin = a.dp(32);
        overlay.addView(hint, hintLp);
        overlay.setOnClickListener(v -> a.root.removeView(overlay));
        a.root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    /** 安装上传的 APK：复制到更新缓存目录，用现有 provider 交给系统安装器。 */
    private static void installApk(MainActivity a, File file) {
        try {
            File cacheDir = new File(a.getCacheDir(), "updates");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new java.io.IOException("cache");
            File target = new File(cacheDir, "release.apk");
            try (java.io.InputStream in = new java.io.FileInputStream(file);
                    java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            android.content.Intent intent =
                    new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(UpdateApkProvider.URI, "application/vnd.android.package-archive");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(intent);
        } catch (Exception e) {
            a.toast("安装失败：" + e.getMessage());
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
