package com.whalefall.movie;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** 原生网盘文件列表的通用玻璃弹窗（文件夹/文件、可播放视频给 Phase 2 占位入口）。 */
final class PanDriveUi {
    private PanDriveUi() {}

    static void showFiles(
            final MainActivity a,
            final String title,
            final String subtitle,
            final List<QuarkDrive.FileItem> files) {
        LinearLayout box = a.vertical();
        TextView head = a.text(subtitle, 12, MainActivity.MUTED);
        box.addView(head);
        a.gap(box, 6);
        if (files.isEmpty()) box.addView(a.text("网盘根目录为空。", 14, MainActivity.MUTED));
        for (QuarkDrive.FileItem file : files) {
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
                            file.playable ? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_SECONDARY);
            meta.setPadding(0, a.dp(6), 0, 0);
            box.addView(meta);
            TextView size = a.text(QuarkDrive.formatSize(file.size), 11, MainActivity.MUTED);
            size.setPadding(0, 0, 0, a.dp(6));
            box.addView(size);
            if (file.playable) {
                Button play =
                        a.button(
                                "▶ 播放（Phase 2 接入解析）",
                                "drive-play:" + file.fid,
                                () ->
                                        a.notice(
                                                "播放/下载解析将在 Phase 2 接入（本阶段只验证登录与列表）。"));
                play.setTextSize(12);
                LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(-1, a.dp(40));
                playLp.bottomMargin = a.dp(8);
                box.addView(play, playLp);
            }
        }
        new AppDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }
}
