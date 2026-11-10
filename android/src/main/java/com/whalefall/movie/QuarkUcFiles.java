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
        // 1.9.1：网盘浏览统一到文件管理器页面（旧裸列表弹窗废弃）
        FileManagerScreen.showNetdisk(a, p.key);
    }

    /** 1.9.1：保留旧签名兼容，内部统一走文件管理器页面。 */
    static void show(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String cookieOverride,
            final boolean fromSource) {
        FileManagerScreen.showNetdisk(a, p.key);
    }

    /** 1.9.1：保留旧签名兼容（浏览统一到文件管理器页面）。 */
    static void show(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final String primaryCookie,
            final boolean primaryFromSource,
            final String altCookie,
            final boolean altFromSource) {
        FileManagerScreen.showNetdisk(a, p.key);
    }

    private static boolean isAuthError(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.contains("HTTP 401")
                || message.contains("HTTP 403")
                || message.contains("code=401")
                || message.contains("code=403");
    }

    private static String friendly(Throwable error) {
        return error == null
                ? "未知错误"
                : error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "：" + error.getMessage());
    }

    private static void dialog(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final java.util.List<QuarkUcApi.FileItem> files,
            final String cookie,
            final boolean fromSource) {
        new Browser(a, p, cookie, fromSource).open(files);
    }

    /** 1.8.30：目录浏览（进入文件夹 / 返回上级 / 视频播放）。 */
    private static final class Browser {
        final MainActivity a;
        final QuarkUcApi.Provider p;
        final String cookie;
        final boolean fromSource;
        final android.app.Dialog dlg;
        final java.util.ArrayDeque<String[]> history = new java.util.ArrayDeque<>();
        LinearLayout listBox;
        TextView crumb, status, backBtn;
        String fid = "0";
        String path = "根目录";

        Browser(MainActivity a, QuarkUcApi.Provider p, String cookie, boolean fromSource) {
            this.a = a;
            this.p = p;
            this.cookie = cookie;
            this.fromSource = fromSource;
            dlg = new android.app.Dialog(a);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(12));
            card.setBackground(UiTokens.round(a, UiTokens.RADIUS_20, 0xf2141c2a, UiTokens.STROKE));
            TextView title =
                    a.text(
                            "我的"
                                    + p.name
                                    + "网盘 · "
                                    + (fromSource ? "源插件登录（只读）" : "App 内登录"),
                            15,
                            MainActivity.WHITE);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(title);
            crumb = a.text("根目录", 11.5f, 0xffffb3c8);
            crumb.setPadding(0, a.dp(4), 0, a.dp(6));
            card.addView(crumb);
            status = a.text("", 11, MainActivity.MUTED);
            card.addView(status);
            android.widget.ScrollView scroll = new android.widget.ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            listBox = a.vertical();
            listBox.setPadding(0, a.dp(6), 0, a.dp(6));
            scroll.addView(listBox, new android.widget.ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollLp =
                    new LinearLayout.LayoutParams(-1, a.dp(420));
            card.addView(scroll, scrollLp);
            LinearLayout buttons = a.row();
            buttons.setGravity(Gravity.CENTER);
            backBtn = a.text("返回上级", 12, MainActivity.WHITE);
            backBtn.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            backBtn.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            backBtn.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(v -> back());
            buttons.addView(backBtn, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
            TextView close = a.text("关闭", 12, MainActivity.WHITE);
            close.setGravity(Gravity.CENTER);
            close.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            close.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            close.setFocusable(true);
            MobileDetailScreen.bindTouchFeedback(close);
            close.setOnClickListener(v -> dlg.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
            closeLp.leftMargin = a.dp(8);
            buttons.addView(close, closeLp);
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
            buttonsLp.topMargin = a.dp(8);
            card.addView(buttons, buttonsLp);
            android.widget.FrameLayout root = new android.widget.FrameLayout(a);
            root.addView(
                    card,
                    new android.widget.FrameLayout.LayoutParams(
                            Math.min(a.dp(520), a.windowWidth() - a.dp(32)),
                            -2,
                            Gravity.CENTER));
            dlg.setContentView(root);
        }

        void open(java.util.List<QuarkUcApi.FileItem> initial) {
            dlg.show();
            fill(initial);
        }

        void load() {
            status.setText("加载中…");
            listBox.removeAllViews();
            final String loadFid = fid;
            final String loadPath = path;
            a.worker.execute(
                    () -> {
                        java.util.List<QuarkUcApi.FileItem> items = new java.util.ArrayList<>();
                        String error = "";
                        try {
                            items = QuarkUcApi.list(p, cookie, loadFid);
                            AppLog.i(
                                    "QuarkUc",
                                    "browse provider="
                                            + p.key
                                            + " fid="
                                            + loadFid
                                            + " items="
                                            + items.size()
                                            + " path="
                                            + loadPath);
                        } catch (Throwable failure) {
                            error = failure.getClass().getSimpleName();
                            AppLog.i("QuarkUc", "browse error=" + error);
                        }
                        final java.util.List<QuarkUcApi.FileItem> result = items;
                        final String failure = error;
                        a.ui.post(
                                () -> {
                                    if (dlg.isShowing()) {
                                        if (!failure.isEmpty()) {
                                            status.setText("加载失败：" + failure + "（点此重试）");
                                            status.setFocusable(true);
                                            MobileDetailScreen.bindTouchFeedback(status);
                                            status.setOnClickListener(v -> load());
                                        } else fill(result);
                                    }
                                });
                    });
        }

        void fill(java.util.List<QuarkUcApi.FileItem> files) {
            listBox.removeAllViews();
            crumb.setText(path + " · " + (files == null ? 0 : files.size()) + " 项");
            backBtn.setAlpha(history.isEmpty() ? 0.45f : 1f);
            backBtn.setFocusable(!history.isEmpty());
            status.setText(
                    history.isEmpty()
                            ? ""
                            : "已进入子目录，可点「返回上级」");
            if (files == null || files.isEmpty()) {
                TextView empty = a.text("该目录为空。", 13, MainActivity.MUTED);
                empty.setPadding(0, a.dp(12), 0, a.dp(12));
                listBox.addView(empty);
                return;
            }
            for (final QuarkUcApi.FileItem file : files) {
                if (file.folder) {
                    TextView folder = a.text("📁  " + file.name, 14, 0xffe6edf9);
                    folder.setPadding(a.dp(1), a.dp(8), a.dp(1), a.dp(8));
                    folder.setFocusable(true);
                    MobileDetailScreen.bindTouchFeedback(folder);
                    folder.setOnClickListener(
                            v -> {
                                history.push(new String[] {fid, path});
                                fid = file.fid;
                                path = path + " / " + file.name;
                                load();
                            });
                    listBox.addView(folder);
                    continue;
                }
                TextView name =
                        a.text(
                                "📄  " + file.name,
                                13.5f,
                                file.video ? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_SECONDARY);
                name.setPadding(0, a.dp(6), 0, 0);
                listBox.addView(name);
                String meta = QuarkUcApi.formatSize(file.size);
                if (file.updated > 0) {
                    try {
                        long ms = file.updated > 1000000000000L ? file.updated : file.updated * 1000L;
                        meta +=
                                " · "
                                        + new java.text.SimpleDateFormat(
                                                        "yyyy-MM-dd", java.util.Locale.getDefault())
                                                .format(new java.util.Date(ms));
                    } catch (Throwable ignored) {
                    }
                }
                TextView sub = a.text(meta, 11, MainActivity.MUTED);
                sub.setPadding(0, 0, 0, a.dp(4));
                listBox.addView(sub);
                if (file.video) {
                    Button play =
                            a.button(
                                    "▶ 播放",
                                    "quarkuc-play:" + file.fid,
                                    () -> play(a, p, file, cookie, fromSource));
                    play.setTextSize(12);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(38));
                    lp.bottomMargin = a.dp(8);
                    listBox.addView(play, lp);
                }
            }
        }

        void back() {
            if (history.isEmpty()) return;
            String[] prev = history.pop();
            fid = prev[0];
            path = prev[1];
            load();
        }
    }

    static void play(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final QuarkUcApi.FileItem file,
            final String cookie) {
        play(a, p, file, cookie, false);
    }

    /** 1.8.31：播放/下载统一交给 FileManagerCore（本地/直链）。 */
    static void play(
            final MainActivity a,
            final QuarkUcApi.Provider p,
            final QuarkUcApi.FileItem file,
            final String cookie,
            final boolean fromSource) {
        FileManagerCore.playRemote(a, p, cookie, file, fromSource);
    }

    static String download(
            MainActivity a,
            QuarkUcApi.Provider p,
            String cookie,
            QuarkUcApi.FileItem file,
            FileManagerCore.Progress progress,
            FileManagerCore.Done done,
            FileManagerCore.Failure failure) {
        return FileManagerCore.download(a, p, cookie, file, progress, done, failure);
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
