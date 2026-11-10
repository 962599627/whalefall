package com.whalefall.movie;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quark/UC 原生网盘文件列表（按 OpenList quark_uc 移植）：列文件 + 视频取直链播放。 */
final class QuarkUcFiles {
    private QuarkUcFiles() {}

    static void show(final MainActivity a, final QuarkUcApi.Provider p) {
        a.toast("正在读取" + p.name + "网盘文件…");
        final String cookie = QuarkUcApi.cookie(a, p);
        a.worker.execute(
                () -> {
                    List<QuarkUcApi.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        files = QuarkUcApi.list(p, cookie, "0");
                        AppLog.i("QuarkUc", "list provider=" + p.key + " items=" + files.size());
                    } catch (Throwable failure) {
                        error = failure.getMessage();
                        AppLog.i("QuarkUc", "list provider=" + p.key + " error=" + error);
                    }
                    final List<QuarkUcApi.FileItem> result = files;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (!failure.isEmpty()) a.notice("读取文件失败：" + failure);
                                else filesDialog(a, p, result);
                            });
                });
    }

    private static void filesDialog(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final List<QuarkUcApi.FileItem> files) {
        LinearLayout box = a.vertical();
        TextView head =
                a.text(
                        "根目录 " + files.size() + " 项 · 容量 "
                                + QuarkUcApi.formatSize(QuarkUcApi.used(a, p))
                                + " / "
                                + QuarkUcApi.formatSize(QuarkUcApi.total(a, p)),
                        12,
                        MainActivity.MUTED);
        box.addView(head);
        a.gap(box, 6);
        if (files.isEmpty()) box.addView(a.text("网盘根目录为空。", 14, MainActivity.MUTED));
        for (QuarkUcApi.FileItem file : files) {
            if (file.folder) {
                TextView row = a.text("📁  " + file.name, 14, UiTokens.TEXT_SECONDARY);
                row.setPadding(0, a.dp(6), 0, a.dp(6));
                box.addView(row);
                continue;
            }
            TextView meta =
                    a.text(
                            "📄  " + file.name,
                            14,
                            file.video ? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_SECONDARY);
            meta.setPadding(0, a.dp(6), 0, 0);
            box.addView(meta);
            TextView size = a.text(QuarkUcApi.formatSize(file.size), 11, MainActivity.MUTED);
            size.setPadding(0, 0, 0, a.dp(6));
            box.addView(size);
            if (file.video) {
                Button play =
                        a.button(
                                "▶ 播放",
                                "quarkuc-play:" + file.fid,
                                () -> play(a, p, file));
                play.setTextSize(12);
                LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(-1, a.dp(40));
                playLp.bottomMargin = a.dp(8);
                box.addView(play, playLp);
            }
        }
        new AppDialog.Builder(a)
                .setTitle("我的" + p.name + "网盘")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** OpenList：POST /file/download 取直链，带 Cookie/Referer/UA 交给现有播放器。 */
    static void play(
            final MainActivity a, final QuarkUcApi.Provider p, final QuarkUcApi.FileItem file) {
        a.toast("正在获取播放地址…");
        final String cookie = QuarkUcApi.cookie(a, p);
        a.worker.execute(
                () -> {
                    try {
                        String url = QuarkUcApi.downloadUrl(p, cookie, file.fid);
                        AppLog.i(
                                "QuarkUc",
                                "download provider=" + p.key + " fid=" + file.fid + " url=true");
                        final Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("Cookie", cookie);
                        headers.put("Referer", p.referer);
                        headers.put("User-Agent", p.ua);
                        a.ui.post(() -> startPlayer(a, p, file, url, headers));
                    } catch (Throwable error) {
                        AppLog.i("QuarkUc", "download provider=" + p.key + " error=" + error.getMessage());
                        a.ui.post(() -> a.notice("获取播放地址失败：" + error.getMessage()));
                    }
                });
    }

    private static void startPlayer(
            MainActivity a,
            QuarkUcApi.Provider p,
            QuarkUcApi.FileItem file,
            String url,
            Map<String, String> headers) {
        Cms.Site site = new Cms.Site();
        site.key = "open_" + p.key;
        site.name = p.name + "网盘";
        site.api = p.api;
        site.requiresParser = false;
        site.searchable = false;
        site.headers.putAll(headers);
        Cms.Video video = new Cms.Video();
        video.id = file.fid;
        video.title = file.name;
        video.poster = "";
        Cms.Line line = new Cms.Line();
        line.name = p.name + "网盘";
        Cms.Episode episode = new Cms.Episode();
        episode.name = file.name;
        episode.url = url;
        episode.headers.putAll(headers);
        line.episodes.add(episode);
        video.lines.add(line);
        a.resolvePlayer(site, video, line, episode);
    }
}
