package com.whalefall.movie;

import android.app.Application;
import android.content.Context;

/**
 * 安全模式 + 崩溃自恢复（1.8.0）：
 * 主线程/未捕获崩溃连续 2 次 → 启动进入安全模式：禁用壁纸、硬件层、播放加载动画等重绘动效；
 * 正常使用 10 分钟后崩溃计数清零；设置里可手动退出安全模式。
 */
final class SafeMode {
    static final String SAFE = "safe_mode";
    static final String STREAK = "crash_streak";
    static final String LAST = "crash_last";
    static final long NORMAL_CLEAR_MS = 10 * 60 * 1000L;

    private SafeMode() {}

    static boolean enabled(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences("movie", Context.MODE_PRIVATE)
                    .getBoolean(SAFE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int streak(Context context) {
        try {
            return context.getSharedPreferences("movie", Context.MODE_PRIVATE)
                    .getInt(STREAK, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 每次启动检查：连续崩溃 ≥2 次则进入安全模式，并安排正常使用 10 分钟后清零计数。 */
    static void onLaunch(final Application app) {
        try {
            int streak = streak(app);
            boolean safe = enabled(app);
            if (streak >= 2) {
                app.getSharedPreferences("movie", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(SAFE, true)
                        .apply();
                safe = true;
            }
            AppLog.i(
                    "SafeMode",
                    "launch enabled=" + safe + " streak=" + streak);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(
                            () -> {
                                try {
                                    app.getSharedPreferences("movie", Context.MODE_PRIVATE)
                                            .edit()
                                            .putInt(STREAK, 0)
                                            .apply();
                                    AppLog.i("SafeMode", "normal 10min, crash streak cleared");
                                } catch (Throwable ignored) {
                                }
                            },
                            NORMAL_CLEAR_MS);
        } catch (Throwable error) {
            AppLog.w("SafeMode", "onLaunch failed", error);
        }
    }

    /** 记录一次崩溃；连续 2 次则标记安全模式（下次启动生效）。 */
    static void recordCrash(Application app, Throwable error) {
        try {
            android.content.SharedPreferences prefs =
                    app.getSharedPreferences("movie", Context.MODE_PRIVATE);
            int streak = prefs.getInt(STREAK, 0) + 1;
            boolean safe = prefs.getBoolean(SAFE, false) || streak >= 2;
            prefs.edit()
                    .putInt(STREAK, streak)
                    .putLong(LAST, System.currentTimeMillis())
                    .putBoolean(SAFE, safe)
                    .apply();
            AppLog.i(
                    "SafeMode",
                    "crash recorded streak=" + streak + " safe=" + safe + " cause="
                            + (error == null ? "" : error.getClass().getSimpleName()));
        } catch (Throwable ignored) {
        }
    }

    static void exit(Context context) {
        try {
            context.getSharedPreferences("movie", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(SAFE, false)
                    .putInt(STREAK, 0)
                    .apply();
            AppLog.i("SafeMode", "exit requested");
        } catch (Throwable ignored) {
        }
    }

    /** 包装系统默认崩溃处理器：先记录计数，再交回原处理器（不吞崩溃）。 */
    static void installCrashHandler(final Application app) {
        if (handlerInstalled) return;
        handlerInstalled = true;
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    (thread, error) -> {
                        // 只统计主线程崩溃；插件后台线程崩溃不计入安全模式
                        if (thread == android.os.Looper.getMainLooper().getThread())
                            recordCrash(app, error);
                        if (previous != null) previous.uncaughtException(thread, error);
                    });
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean handlerInstalled;
}
