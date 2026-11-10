package com.whalefall.movie;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** 1.8.18 App 本地网盘文件列表（夸克/UC）：列根目录 + 视频取直链播放。 */
final class QuarkUcFiles {
    private QuarkUcFiles() {}

    static void show(final MainActivity a, final QuarkUcApi.Provider p) {
        show(a, p, QuarkUcApi.cookie(a, p), false);
    }

    /** 1.8.25：支持外部 Cookie（优先 App 本地，否则源插件 spUtils 解码）。 */
    static void show(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookieOverride,
            final boolean fromSource) {
        final int pageGen = a.generation;
        a.toast("正在读取" + p.name + "网盘文件…");
        final String cookie = cookieOverride == null ? "" : cookieOverride;
        a.worker.execute(
                () -> {
                    java.util.List<QuarkUcApi.FileItem> files = new java.util.ArrayList<>();
                    String error = "";
                    try {
                        files = QuarkUcApi.list(p, cookie, "0");
                        AppLog.i(
                                "QuarkUc",
                                "list provider="
                                        + p.key
                                        + " items="
                                        + files.size()
                                        + " sourceCookie="
                                        + fromSource);
                    } catch (Throwable failure) {
                        error = failure.getMessage();
                        AppLog.i("QuarkUc", "list provider=" + p.key + " error=" + error);
                    }
                    final java.util.List<QuarkUcApi.FileItem> result = files;
                    final String failure = error;
                    final int gen = pageGen;
                    a.ui.post(
                            () -> {
                                if (a.isFinishing() || a.isDestroyed() || gen != a.generation) {
                                    AppLog.i("QuarkUc", "list callback skipped gen=" + gen);
                                    return;
                                }
                                if (!failure.isEmpty()) {
                                    a.notice("读取文件失败：" + failure);
                                    return;
                                }
                                dialog(a, p, result, cookie, fromSource);
                            });
                });
    }

    private static void dialog(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final java.util.List<QuarkUcApi.FileItem> files,
            final String cookie,
            final boolean fromSource) {
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
        if (files.isEmpty()) {
            TextView empty = a.text("网盘根目录为空。", 14, MainActivity.MUTED);
            empty.setPadding(0, a.dp(16), 0, a.dp(16));
            box.addView(empty);
        }
        for (QuarkUcApi.FileItem file : files) {
            if (file.folder) {
                TextView folder = a.text("📁  " + file.name, 14, UiTokens.TEXT_SECONDARY);
                folder.setPadding(0, a.dp(6), 0, a.dp(6));
                box.addView(folder);
                continue;
            }
            TextView name =
                    a.text(
                            "📄  " + file.name,
                            14,
                            file.video ? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_SECONDARY);
            name.setPadding(0, a.dp(6), 0, 0);
            box.addView(name);
            TextView size = a.text(QuarkUcApi.formatSize(file.size), 11, MainActivity.MUTED);
            size.setPadding(0, 0, 0, a.dp(6));
            box.addView(size);
            if (file.video) {
                Button play =
                        a.button(
                                "▶ 播放", "quarkuc-play:" + file.fid, () -> play(a, p, file, cookie));
                play.setTextSize(12);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(40));
                lp.bottomMargin = a.dp(8);
                box.addView(play, lp);
            }
        }
        new AppDialog.Builder(a)
                .setTitle(
                        "我的"
                                + p.name
                                + "网盘 · "
                                + (fromSource ? "源插件登录（只读）" : "App 内登录"))
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    static void play(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final QuarkUcApi.FileItem file,
            final String cookie) {
        a.toast("正在获取播放地址…");
        a.worker.execute(
                () -> {
                    try {
                        String url = QuarkUcApi.downloadUrl(p, cookie, file.fid);
                        AppLog.i("QuarkUc", "download provider=" + p.key + " url=true");
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
        site.key = "local_" + p.key;
        site.name = p.name + "网盘（App 内）";
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
