package com.whalefall.movie;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized preference keys, default values, and backup/restore range validation. Pure Java so
 * offline unit checks can verify every setting constraint without Android framework dependencies.
 */
final class Prefs {
    static final String FILE = "movie";

    // User-facing appearance & playback preferences included in backups.
    static final String UI_SCALE_PERCENT = "ui_scale_percent";
    static final String NAV_SCALE_PERCENT = "nav_scale_percent";
    static final String STARTUP_ANIMATION = "startup_animation";
    static final String HOME_AMBIENT = "home_ambient";
    static final String HOME_COLUMNS = "home_columns";
    static final String HOME_ROWS = "home_rows";
    static final String HOME_SECTIONS = "home_sections";
    static final String WALLPAPER_ENABLED = "wallpaper_enabled";
    static final String WALLPAPER_DIM = "wallpaper_dim";
    /** 1.9.24：字体样式（Android 内置 Typeface 名）。 */
    static final String FONT_STYLE = "font_style";
    static final String WALLPAPER_VIDEO = "wallpaper_video";
    static final String WALLPAPER_SOUND = "wallpaper_sound";
    static final String TV_ASPECT = "tv_aspect";
    static final String TV_FILL = "tv_fill";
    static final String HOME_SHOW_HISTORY = "home_show_history";
    static final String HOME_SHOW_HERO = "home_show_hero";
    static final String HOME_SHOW_TONIGHT = "home_show_tonight";
    static final String SCREENSAVER_ENABLED = "screensaver_enabled";
    static final String SCREENSAVER_TIMEOUT_MIN = "screensaver_timeout_min";
    static final String SCREENSAVER_CLOCK_STYLE = "screensaver_clock_style";
    static final String REDUCED = "reduced";
    static final String REDUCED_TRANSPARENCY = "reduced_transparency";
    static final String PLAYER_ENGINE = "player_engine";
    static final String VIDEO_OUTPUT = "video_output";
    static final String DECODER_MODE = "decoder_mode";
    static final String SOFTWARE_AUDIO = "software_audio";
    static final String PREFER_AAC = "prefer_aac";
    static final String ASPECT = "aspect";
    static final String BUFFER_SECONDS = "buffer_seconds";
    static final String PLAY_SPEED = "play_speed";
    static final String AUTO_NEXT = "auto_next";
    static final String REPEAT_MODE = "repeat_mode";
    static final String SKIP_INTRO = "skip_intro";
    static final String SKIP_OUTRO = "skip_outro";
    static final String SUBTITLE_SIZE = "subtitle_size";
    static final String SUBTITLE_SIZE_SP = SUBTITLE_SIZE;
    static final String SUBTITLE_BOTTOM = "subtitle_bottom";
    static final String SUBTITLE_STYLE = "subtitle_style";
    static final String SUBTITLE_BG_STYLE = SUBTITLE_STYLE;
    static final String SKIP_INTRO_SECONDS = SKIP_INTRO;
    static final String SKIP_OUTRO_SECONDS = SKIP_OUTRO;
    static final String TUNNELED_PLAYBACK = "tunneled_playback";
    static final String DANMAKU_DEFAULT = "danmaku_default";
    static final String PICTURE_EFFECT = "picture_effect";
    static final String AUDIO_EFFECT = "audio_effect";
    static final String SEARCH_THREADS = "search_threads";
    static final String SEARCH_TIMEOUT = "search_timeout";
    static final String EPISODES_DESCENDING = "episodes_descending";

    // Runtime state & user data keys (excluded from settings-only reset/restore).
    static final String ORIGIN = "origin";
    /** 1.8.7 配置地址历史（JSON 数组，最多 10 条，最新在前）。 */
    static final String CONFIG_HISTORY = "config_history";
    static final String LIVE_URL = "live_url";
    static final String LIVE_NAME = "live_name";
    static final String SELECTED_SITE = "selected_site";
    static final String PREFERRED_SITE = "preferred_site_identity";
    static final String PREFERRED_LINE = "preferred_line";
    static final String LOADING_SITE = "loading_site";
    static final String EXPERIMENTAL_CSP = "experimental_csp";
    static final String FAVORITES = "favorites";
    static final String WATCH_HISTORY = "watch_history";
    static final String GLOBAL_SEARCH_HISTORY = "global_search_history";
    static final String PHONETIC_TITLES = "phonetic_titles";
    /** 字体颜色自定义：TEXT_COLOR_CUSTOM 开启时 TEXT_COLOR（ARGB）生效。 */
    static final String TEXT_COLOR_CUSTOM = "text_color_custom";
    static final String TEXT_COLOR = "text_color";
    // 1.7.0：原生网盘登录已下线（回退源插件流程）；旧版本残留的 *_native_* 键由
    // AccountPanel.purgeLegacyNativePrefs 在打开网盘面板时清理。

    static final String[] KEYS = {
        UI_SCALE_PERCENT,
        NAV_SCALE_PERCENT,
        STARTUP_ANIMATION,
        HOME_AMBIENT,
        HOME_COLUMNS,
        HOME_ROWS,
        HOME_SECTIONS,
        WALLPAPER_ENABLED,
        WALLPAPER_DIM,
        WALLPAPER_VIDEO,
        WALLPAPER_SOUND,
        TV_ASPECT,
        TV_FILL,
        HOME_SHOW_HISTORY,
        HOME_SHOW_HERO,
        HOME_SHOW_TONIGHT,
        SCREENSAVER_ENABLED,
        SCREENSAVER_TIMEOUT_MIN,
        SCREENSAVER_CLOCK_STYLE,
        REDUCED,
        REDUCED_TRANSPARENCY,
        PLAYER_ENGINE,
        VIDEO_OUTPUT,
        DECODER_MODE,
        SOFTWARE_AUDIO,
        PREFER_AAC,
        ASPECT,
        BUFFER_SECONDS,
        PLAY_SPEED,
        AUTO_NEXT,
        REPEAT_MODE,
        SKIP_INTRO,
        SKIP_OUTRO,
        SUBTITLE_SIZE,
        SUBTITLE_BOTTOM,
        DANMAKU_DEFAULT,
        PICTURE_EFFECT,
        AUDIO_EFFECT,
        SEARCH_THREADS,
        SEARCH_TIMEOUT,
        EPISODES_DESCENDING,
        TEXT_COLOR_CUSTOM,
        TEXT_COLOR
    };

    private static final Set<String> BOOLEAN_KEYS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    STARTUP_ANIMATION,
                                    HOME_AMBIENT,
                                    WALLPAPER_ENABLED,
                                    WALLPAPER_VIDEO,
                                    WALLPAPER_SOUND,
        TV_ASPECT,
        TV_FILL,
        HOME_SHOW_HISTORY,
        HOME_SHOW_HERO,
        HOME_SHOW_TONIGHT,
        SCREENSAVER_ENABLED,
        SCREENSAVER_TIMEOUT_MIN,
        SCREENSAVER_CLOCK_STYLE,
                                    REDUCED,
                                    REDUCED_TRANSPARENCY,
                                    AUTO_NEXT,
                                    DANMAKU_DEFAULT,
                                    SOFTWARE_AUDIO,
                                    PREFER_AAC,
                                    EPISODES_DESCENDING,
                                    TEXT_COLOR_CUSTOM)));

    private static final Map<String, int[]> INT_RANGES;

    static {
        Map<String, int[]> ranges = new HashMap<>();
        ranges.put(HOME_COLUMNS, new int[] {5, 8});
        ranges.put(NAV_SCALE_PERCENT, new int[] {85, 140});
        ranges.put(HOME_ROWS, new int[] {1, 4});
        ranges.put(HOME_SECTIONS, new int[] {0, 5});
        ranges.put(WALLPAPER_DIM, new int[] {20, 100});
        ranges.put(TV_ASPECT, new int[] {0, 2});
        ranges.put(TV_FILL, new int[] {0, 2});
        ranges.put(SCREENSAVER_TIMEOUT_MIN, new int[] {1, 60});
        ranges.put(SCREENSAVER_CLOCK_STYLE, new int[] {0, 2});
        ranges.put(PLAYER_ENGINE, new int[] {0, 1});
        ranges.put(VIDEO_OUTPUT, new int[] {0, 1});
        ranges.put(DECODER_MODE, new int[] {0, 2});
        ranges.put(REPEAT_MODE, new int[] {0, 2});
        ranges.put(ASPECT, new int[] {0, 4});
        ranges.put(BUFFER_SECONDS, new int[] {15, 50});
        ranges.put(SKIP_INTRO, new int[] {0, 180});
        ranges.put(SKIP_OUTRO, new int[] {0, 180});
        ranges.put(SUBTITLE_SIZE, new int[] {25, 65});
        ranges.put(SUBTITLE_BOTTOM, new int[] {0, 180});
        ranges.put(SUBTITLE_STYLE, new int[] {0, 5});
        ranges.put(PICTURE_EFFECT, new int[] {0, 3});
        ranges.put(AUDIO_EFFECT, new int[] {0, 3});
        ranges.put(SEARCH_THREADS, new int[] {2, 6});
        ranges.put(SEARCH_TIMEOUT, new int[] {10, 30});
        INT_RANGES = Collections.unmodifiableMap(ranges);
    }

    private Prefs() {}

    /**
     * Validates a candidate setting from a backup payload. Returns a sanitized {@link Boolean},
     * {@link Float}, or {@link Integer} ready to store, or {@code null} if the key is unknown or
     * the value is out of range.
     */
    static Object sanitizeSetting(String key, Object value) {
        if (key == null || value == null) return null;
        if (UI_SCALE_PERCENT.equals(key)) {
            return value instanceof Number
                    ? TvLayoutPolicy.percent(((Number) value).intValue())
                    : null;
        }
        if (PLAY_SPEED.equals(key)) {
            if (value instanceof Number) {
                float speed = ((Number) value).floatValue();
                if (speed >= .5f && speed <= 2f) return speed;
            }
            return null;
        }
        if (BOOLEAN_KEYS.contains(key)) {
            return value instanceof Boolean ? value : null;
        }
        if (TEXT_COLOR.equals(key)) {
            // 仅保留 RGB，alpha 统一按用户选择处理（0xffffffff 默认白）
            return value instanceof Number ? (0xff000000 | (((Number) value).intValue() & 0xffffff)) : null;
        }
        int[] range = INT_RANGES.get(key);
        if (range == null || !(value instanceof Number)) return null;
        int n = ((Number) value).intValue();
        return n >= range[0] && n <= range[1] ? n : null;
    }

    static int clampWallpaperDim(int dim) {
        // 1.8.24：下限 20%，避免亮壁纸 + dim=0 时白字不可读
        return Math.max(20, Math.min(100, dim));
    }

    static int clampSubtitleSize(int size) {
        return Math.max(25, Math.min(65, size));
    }

    static int clampSubtitleBottom(int dp) {
        return Math.max(0, Math.min(180, dp));
    }

    static int clampSubtitleStyle(int style) {
        return Math.max(0, Math.min(5, style));
    }

    static int clampNavScale(int percent) {
        return Math.max(85, Math.min(140, percent));
    }

    static int clampSubtitleBgStyle(int style) {
        return clampSubtitleStyle(style);
    }

    static int clampSkipSeconds(int seconds) {
        return Math.max(0, Math.min(180, seconds));
    }

    static int clampSearchTimeoutSeconds(int seconds) {
        return Math.max(10, Math.min(30, seconds));
    }

    static boolean isBackupObject(String format, String schema, int version) {
        return "MarcoMovie-1".equals(format)
                || ("marco-movie-backup".equals(schema) && (version == 0 || version == 1));
    }

    static String extractBackupConfigJson(Object raw) {
        if (raw == null) return null;
        String text = raw instanceof String ? ((String) raw).trim() : String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equals(text)) return null;
        if (text.startsWith("{") && text.endsWith("}")) return text;
        return null;
    }

    static String sanitizeUserData(String key, Object value) {
        if (key == null || value == null) return null;
        String text =
                value instanceof String ? ((String) value).trim() : String.valueOf(value).trim();
        if ("null".equals(text)) return null;
        if (SELECTED_SITE.equals(key)) {
            return text.length() <= 256 ? text : null;
        }
        if (WATCH_HISTORY.equals(key)
                || FAVORITES.equals(key)
                || GLOBAL_SEARCH_HISTORY.equals(key)) {
            if (text.isEmpty()) return "[]";
            if (text.startsWith("[") && text.endsWith("]")) return text;
            return null;
        }
        return null;
    }
}
