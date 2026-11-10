package com.whalefall.movie;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown / 文本阅读器：管理后台上传的 .md / .txt 在 App 内阅读。
 *
 * <p>轻量渲染：标题、粗体、行内代码、代码块、列表、引用、分隔线；不引入第三方解析库。
 */
final class MarkdownScreen {
    private MarkdownScreen() {}

    static void show(MainActivity a, String fileName) {
        a.open(new MainActivity.Screen(() -> render(a, fileName)), true);
    }

    private static void render(MainActivity a, String fileName) {
        String text;
        try {
            text = PushFiles.readText(a, fileName);
        } catch (Exception e) {
            a.toast("读取失败：" + e.getMessage());
            return;
        }
        a.clearPlaybackLoading();
        a.cancelPageRequests();
        a.generation++;
        a.applyMobileSystemBars(false);

        a.root = new android.widget.FrameLayout(a);
        a.installBackdrop(a.root);
        a.setContentView(a.root);

        LinearLayout column = a.vertical();
        column.setPadding(a.dp(20), getStatusBar(a) + a.dp(12), a.dp(20), a.dp(32));
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        a.root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        a.scroll = scroll;
        a.body = column;

        // 顶部返回 + 标题
        LinearLayout nav = a.row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = a.text("‹", 24, MainActivity.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, a.dp(2));
        back.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(back);
        back.setOnClickListener(v -> a.onBackPressed());
        nav.addView(back, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
        TextView title = a.text(baseName(fileName), 19, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = a.dp(12);
        nav.addView(title, titleLp);
        TextView time = a.text(PushFiles.sizeLabel(text.length()), 11.5f, 0xff8d9cb6);
        nav.addView(time);
        // 1.8.6：文本查看页加复制/分享；TV 无剪贴板时显示文件位置
        if (!a.isTvDevice()) {
            TextView copy = a.text("复制", 12, MainActivity.WHITE);
            copy.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            copy.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            MobileDetailScreen.bindTouchFeedback(copy);
            final String content = text;
            copy.setOnClickListener(
                    v -> {
                        try {
                            android.content.ClipboardManager manager =
                                    (android.content.ClipboardManager)
                                            a.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE);
                            if (manager != null)
                                manager.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                                baseName(fileName), content));
                            a.toast("已复制");
                        } catch (Throwable error) {
                            a.toast("复制失败：" + error.getMessage());
                        }
                    });
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
            copyLp.leftMargin = a.dp(8);
            nav.addView(copy, copyLp);

            TextView share = a.text("分享", 12, MainActivity.WHITE);
            share.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            share.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
            MobileDetailScreen.bindTouchFeedback(share);
            share.setOnClickListener(
                    v -> {
                        try {
                            android.content.Intent intent =
                                    new android.content.Intent(
                                            android.content.Intent.ACTION_SEND);
                            intent.setType("text/plain");
                            intent.putExtra(android.content.Intent.EXTRA_TEXT, content);
                            a.startActivity(
                                    android.content.Intent.createChooser(intent, "分享文本"));
                        } catch (Throwable error) {
                            a.toast("分享失败：" + error.getMessage());
                        }
                    });
            LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, -2);
            shareLp.leftMargin = a.dp(6);
            nav.addView(share, shareLp);
        } else {
            TextView saved = a.text("文件位置", 12, 0xffffb3c8);
            saved.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            MobileDetailScreen.bindTouchFeedback(saved);
            saved.setOnClickListener(v -> a.toast("已保存在文件管理：" + fileName));
            LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(-2, -2);
            savedLp.leftMargin = a.dp(8);
            nav.addView(saved, savedLp);
        }
        column.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        a.gap(column, 14);

        render(a, column, text);
    }

    private static int getStatusBar(MainActivity a) {
        return MobileDetailScreen.getStatusBarHeight(a);
    }

    private static String baseName(String name) {
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    static void render(MainActivity a, LinearLayout column, String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        List<String> listBuffer = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    addCodeBlock(a, column, code.toString());
                    code.setLength(0);
                    inCode = false;
                } else {
                    flushList(a, column, listBuffer);
                    inCode = true;
                }
                i++;
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                i++;
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushList(a, column, listBuffer);
                a.gap(column, 8);
                i++;
                continue;
            }
            // 表格：当前行含 | 且下一行是分隔行
            if (trimmed.contains("|")
                    && i + 1 < lines.length
                    && lines[i + 1].trim().matches("^\\|?\\s*:?-{2,}.*")) {
                flushList(a, column, listBuffer);
                int end = i;
                while (end < lines.length && lines[end].contains("|")) end++;
                addTable(a, column, lines, i, end);
                i = end;
                continue;
            }
            // 图片
            java.util.regex.Matcher image =
                    java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)[^)]*\\)")
                            .matcher(trimmed);
            if (image.find()) {
                flushList(a, column, listBuffer);
                addImage(a, column, image.group(1).trim());
                i++;
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                listBuffer.add("• " + trimmed.substring(2));
                i++;
                continue;
            }
            if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                listBuffer.add(trimmed);
                i++;
                continue;
            }
            flushList(a, column, listBuffer);
            if (trimmed.startsWith(">")) {
                addQuote(a, column, trimmed.substring(1).trim());
                i++;
                continue;
            }
            if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                android.view.View divider = new android.view.View(a);
                divider.setBackgroundColor(0x33ffffff);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
                lp.topMargin = a.dp(8);
                lp.bottomMargin = a.dp(8);
                column.addView(divider, lp);
                i++;
                continue;
            }
            int heading = 0;
            while (heading < trimmed.length() && trimmed.charAt(heading) == '#') heading++;
            if (heading > 0 && heading <= 6 && trimmed.length() > heading && trimmed.charAt(heading) == ' ') {
                TextView h = a.text(trimmed.substring(heading + 1), headingSizes[heading - 1], MainActivity.WHITE);
                h.setTypeface(null, Typeface.BOLD);
                h.setPadding(0, a.dp(heading <= 2 ? 10 : 6), 0, a.dp(4));
                column.addView(h);
                i++;
                continue;
            }
            TextView p = a.text("", 14.5f, 0xffe6edf9);
            p.setLineSpacing(a.dp(5), 1.1f);
            inline(p, trimmed);
            column.addView(p, new LinearLayout.LayoutParams(-1, -2));
            i++;
        }
        if (inCode && code.length() > 0) addCodeBlock(a, column, code.toString());
        flushList(a, column, listBuffer);
    }

    private static void addImage(MainActivity a, LinearLayout column, String url) {
        android.widget.ImageView image = new android.widget.ImageView(a);
        image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setMinimumHeight(a.dp(80));
        image.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 12));
        image.setContentDescription("Markdown 图片");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(6);
        lp.bottomMargin = a.dp(6);
        column.addView(image, lp);
        if (url.startsWith("http")) a.poster(url, image);
    }

    private static void addTable(
            MainActivity a, LinearLayout column, String[] lines, int start, int end) {
        List<String[]> rows = new ArrayList<>();
        boolean first = true;
        for (int i = start; i < end; i++) {
            String line = lines[i].trim();
            if (first) {
                first = false;
                continue;
            }
            if (line.matches("^\\|?\\s*:?-{2,}.*")) continue;
            String[] cells = line.replaceAll("^\\||\\|$", "").split("\\|");
            for (int c = 0; c < cells.length; c++) cells[c] = cells[c].trim();
            rows.add(cells);
        }
        if (rows.isEmpty()) return;
        LinearLayout table = a.vertical();
        table.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 12));
        table.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            LinearLayout row = a.row();
            for (int c = 0; c < cells.length; c++) {
                TextView cell = a.text(cells[c], 12.5f, r == 0 ? MainActivity.WHITE : 0xffdbe4f3);
                if (r == 0) cell.setTypeface(null, Typeface.BOLD);
                cell.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
                if (r > 0) cell.setBackgroundColor(0x0affffff);
                row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            table.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(6);
        lp.bottomMargin = a.dp(6);
        column.addView(table, lp);
    }

    private static final float[] headingSizes = {26, 22, 19, 17, 15.5f, 14.5f};

    private static void flushList(MainActivity a, LinearLayout column, List<String> list) {
        if (list.isEmpty()) return;
        for (String item : list) {
            TextView row = a.text("", 14.5f, 0xffe6edf9);
            row.setLineSpacing(a.dp(4), 1.1f);
            inline(row, item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = a.dp(6);
            column.addView(row, lp);
        }
        list.clear();
        a.gap(column, 6);
    }

    private static void addQuote(MainActivity a, LinearLayout column, String text) {
        LinearLayout box = a.row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        android.view.View bar = new android.view.View(a);
        bar.setBackgroundColor(0xffff8fb1);
        box.addView(bar, new LinearLayout.LayoutParams(a.dp(3), -1));
        TextView t = a.text("", 14, 0xffb9c7dd);
        t.setLineSpacing(a.dp(4), 1.1f);
        t.setPadding(a.dp(12), a.dp(6), 0, a.dp(6));
        inline(t, text);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(4);
        lp.bottomMargin = a.dp(4);
        column.addView(box, lp);
    }

    private static void addCodeBlock(MainActivity a, LinearLayout column, String code) {
        TextView block = a.text(code.trim(), 12.5f, 0xffd7e3f5);
        block.setTypeface(Typeface.MONOSPACE);
        block.setLineSpacing(a.dp(3), 1f);
        block.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        block.setBackground(MobileDetailScreen.glassBg(a, 0xcc0d1626, 0x26ffffff, 12));
        block.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(6);
        lp.bottomMargin = a.dp(6);
        column.addView(block, lp);
    }

    /** 行内样式：**粗体**、`代码`。 */
    private static void inline(TextView target, String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 2, end);
                    sb.setSpan(
                            new StyleSpan(Typeface.BOLD),
                            start,
                            sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 1, end);
                    sb.setSpan(
                            new TypefaceSpan("monospace"),
                            start,
                            sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        target.setText(sb);
    }
}
