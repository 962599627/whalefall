package com.whalefall.movie;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;

/**
 * 1.9.223：测试模式日志——AppLog 全量写入内存环形缓冲（5000 行）与 files/test-log.txt
 * （2MB 自动滚动）。复制到剪贴板后自动清空。
 */
final class TestModeLog {
  private TestModeLog() {}

  static final String PREF = "test_mode_enabled";
  private static final int MAX_LINES = 5000;
  private static final long MAX_BYTES = 2L * 1024 * 1024;

  private static final Object LOCK = new Object();
  private static final ArrayDeque<String> LINES = new ArrayDeque<>();
  private static boolean enabled;
  private static File file;

  static void configure(MainActivity a) {
    try {
      enabled = a.prefs.getBoolean(PREF, false);
      file = new File(a.getFilesDir(), "test-log.txt");
      AppLog.i("TestMode", "configure enabled=" + enabled + " file=" + file.getAbsolutePath());
    } catch (Throwable error) {
      AppLog.w("TestMode", "configure failed", error);
    }
  }

  static boolean enabled() {
    return enabled;
  }

  static void set(MainActivity a, boolean on) {
    enabled = on;
    try {
      a.prefs.edit().putBoolean(PREF, on).apply();
    } catch (Throwable ignored) {
    }
    AppLog.i("TestMode", "enabled=" + on + " lines=" + lines());
  }

  static int lines() {
    synchronized (LOCK) {
      return LINES.size();
    }
  }

  /** AppLog 每次记录时调用。 */
  static void record(String line) {
    if (!enabled || line == null) return;
    synchronized (LOCK) {
      LINES.addLast(line);
      while (LINES.size() > MAX_LINES) LINES.removeFirst();
      appendFile(line);
    }
  }

  private static void appendFile(String line) {
    try {
      if (file == null) return;
      if (file.length() > MAX_BYTES) {
        File old = new File(file.getParentFile(), "test-log.old.txt");
        if (old.exists()) old.delete();
        file.renameTo(old);
      }
      try (FileOutputStream out = new FileOutputStream(file, true)) {
        out.write((line + "\n").getBytes("UTF-8"));
      }
    } catch (Throwable ignored) {
    }
  }

  static String dump() {
    synchronized (LOCK) {
      StringBuilder out = new StringBuilder(LINES.size() * 64);
      for (String line : LINES) out.append(line).append('\n');
      return out.toString();
    }
  }

  /** 复制到剪贴板并清空（内存+文件）。 */
  static int copyToClipboard(MainActivity a) {
    int count;
    String text;
    synchronized (LOCK) {
      count = LINES.size();
      text = dump();
      LINES.clear();
      try {
        if (file != null && file.exists()) file.delete();
      } catch (Throwable ignored) {
      }
    }
    try {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager)
              a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(android.content.ClipData.newPlainText("test-log", text));
      AppLog.i("TestMode", "copy lines=" + count + " cleared=true");
      a.ui.post(() -> a.toast("已复制 " + count + " 行测试日志"));
    } catch (Throwable error) {
      AppLog.w("TestMode", "copy failed", error);
    }
    return count;
  }

  static void clear() {
    synchronized (LOCK) {
      int count = LINES.size();
      LINES.clear();
      try {
        if (file != null && file.exists()) file.delete();
      } catch (Throwable ignored) {
      }
      AppLog.i("TestMode", "clear lines=" + count + " cleared=true");
    }
  }

  static void export(final MainActivity a) {
    a.ui.post(
        () -> {
          try {
            String stamp =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File dir = null;
            try {
              dir =
                  new java.io.File(
                      android.os.Environment.getExternalStorageDirectory(),
                      "Download/DSH-Go/鲸落");
              if (!dir.isDirectory()) dir.mkdirs();
              if (!dir.canWrite()) dir = null;
            } catch (Throwable ignored) {
            }
            if (dir == null) dir = a.getFilesDir();
            java.io.File outFile = new java.io.File(dir, "test-" + stamp + ".txt");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
              out.write(dump().getBytes("UTF-8"));
            }
            AppLog.i("TestMode", "export path=" + outFile.getAbsolutePath());
            a.toast("测试日志已导出：" + outFile.getAbsolutePath());
          } catch (Throwable error) {
            AppLog.w("TestMode", "export failed", error);
            a.toast("导出失败：" + error.getClass().getSimpleName());
          }
        });
  }
}
