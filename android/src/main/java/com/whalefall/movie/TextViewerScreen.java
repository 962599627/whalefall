package com.whalefall.movie;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/**
 * 1.9.1 文本/Markdown 查看器：本地 txt/md/json/log 等；玻璃底、可滚动可选复制。
 */
final class TextViewerScreen {
    private TextViewerScreen() {}

    private static final int MAX_BYTES = 1024 * 1024;

    static void show(MainActivity a, File file) {
        if (file == null || !file.isFile()) {
            a.toast("文件不存在");
            return;
        }
        String text;
        try {
            text = read(file);
        } catch (Throwable error) {
            a.toast("读取失败：" + error.getMessage());
            return;
        }
        showText(a, file.getName(), text);
    }

    static void showText(final MainActivity a, final String titleText, final String text) {
        a.renderPage("查看文本", "TEXT");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        a.body.setTag("text-viewer");
        AppLog.i("TextViewer", "open title=" + titleText + " chars=" + (text == null ? 0 : text.length()));
        TextView title = a.text(titleText, 15, UiTokens.TEXT_PRIMARY);
        title.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(6));
        a.body.addView(title);
        LinearLayout actions = a.row();
        actions.setPadding(a.dp(16), 0, a.dp(16), a.dp(6));
        TextView copy = a.text("复制全文", 12, UiTokens.ACCENT);
        copy.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        copy.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(copy);
        copy.setOnClickListener(
                v -> {
                    android.content.ClipboardManager manager =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(
                            android.content.ClipData.newPlainText(titleText, text == null ? "" : text));
                    a.toast("已复制全文");
                });
        actions.addView(copy);
        TextView back = a.text("返回文件管理", 12, UiTokens.TEXT_PRIMARY);
        back.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> FileManagerScreen.resume(a));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-2, -2);
        backLp.leftMargin = a.dp(8);
        actions.addView(back, backLp);
        a.body.addView(actions);
        ScrollView scroll = new ScrollView(a);
        scroll.setBackground(MobileDetailScreen.glassBg(a, 0xcc16283f, 0x33ffffff, 14));
        LinearLayout column = a.vertical();
        column.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(16));
        if (titleText.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            MarkdownScreen.render(a, column, text == null ? "" : text);
        } else {
            TextView content = a.text(text == null ? "" : text, 13, UiTokens.TEXT_PRIMARY);
            content.setTextIsSelectable(true);
            content.setLineSpacing(a.dp(3), 1f);
            column.addView(content);
        }
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        a.body.addView(scroll, new LinearLayout.LayoutParams(-1, a.dp(560)));
    }

    private static String read(File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), MAX_BYTES)];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n == -1) break;
                read += n;
            }
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static boolean isTextName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".json")
                || lower.endsWith(".log")
                || lower.endsWith(".xml")
                || lower.endsWith(".ini")
                || lower.endsWith(".conf")
                || lower.endsWith(".m3u")
                || lower.endsWith(".srt")
                || lower.endsWith(".ass")
                || lower.endsWith(".js");
    }

    static boolean isImageName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp");
    }
}
