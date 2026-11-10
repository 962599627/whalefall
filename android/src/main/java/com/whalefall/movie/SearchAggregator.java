package com.whalefall.movie;

import java.util.Locale;

/** 1.8.1 #6 搜索跨源聚合：标题归一化（去空格/标点/季集后缀）后同名合并。 */
final class SearchAggregator {
    private SearchAggregator() {}

    static String normalize(String title) {
        if (title == null) return "";
        String text = title.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[\\s·・:：\\-—_.,，。!！?？()（）\\[\\]【】]", "");
        text = text.replaceAll("第\\d+[季集话話].*$", "");
        text = text.replaceAll("(season\\d+|s\\d+)$", "");
        return text;
    }
}
