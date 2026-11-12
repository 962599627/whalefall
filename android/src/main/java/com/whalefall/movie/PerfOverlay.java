package com.whalefall.movie;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.util.ArrayDeque;

/**
 * 1.9.222：App 内性能观测浮层（FPS/掉帧/PSS/页面/壁纸状态），不依赖 ADB。
 *
 * <p>开关存 prefs；浮层挂 DecorView 右上角，页面切换不消失；每 10 秒写一条 I/Perf 日志；
 * 可导出最近 300 行到 Download 或 App 目录。
 */
final class PerfOverlay {
  private PerfOverlay() {}

  static final String PREF = "perf_overlay_enabled";
  private static final int MAX_LINES = 300;

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final ArrayDeque<String> LINES = new ArrayDeque<>();
  private static FrameLayout panel;
  private static TextView label;
  private static MainActivity host;
  private static boolean running;
  private static long frames;
  private static long dropped;
  private static long lastSecond;
  private static long lastLogAt;
  private static int fps;
  private static int dropPerSec;

  private static long lastFrameNanos;

  private static final Choreographer.FrameCallback FRAME =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!running) return;
          frames++;
          if (lastFrameNanos > 0) {
            long deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
            if (deltaMs > 25) dropped += Math.max(1, deltaMs / 17 - 1);
          }
          lastFrameNanos = frameTimeNanos;
          long now = android.os.SystemClock.elapsedRealtime();
          if (lastSecond == 0) lastSecond = now;
          if (now - lastSecond >= 1000) {
            fps = (int) frames;
            dropPerSec = (int) dropped;
            frames = 0;
            dropped = 0;
            lastSecond = now;
            updateText();
            if (now - lastLogAt >= 10000) {
              lastLogAt = now;
              writeLog("tick");
            }
          }
          Choreographer.getInstance().postFrameCallback(this);
        }
      };

  static boolean enabled(MainActivity a) {
    return a.prefs.getBoolean(PREF, false);
  }

  static void set(MainActivity a, boolean on) {
    a.prefs.edit().putBoolean(PREF, on).apply();
    if (on) {
      attach(a);
      AppLog.i("Perf", "overlay enabled=true");
    } else {
      detach();
      AppLog.i("Perf", "overlay enabled=false");
    }
  }

  static void attachIfEnabled(MainActivity a) {
    if (a != null && enabled(a) && panel == null) attach(a);
  }

  private static void attach(MainActivity a) {
    host = a;
    if (panel != null && panel.isAttachedToWindow()) {
      panel.bringToFront();
      return;
    }
    try {
      panel = new FrameLayout(a);
      panel.setTag("perf-overlay");
      LinearLayout box = a.vertical();
      box.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
      box.setBackgroundColor(0x99000000);
      label = a.text("perf", 10, 0xffffb3c8);
      label.setIncludeFontPadding(false);
      box.addView(label);
      panel.addView(box, new FrameLayout.LayoutParams(-2, -2));
      panel.setOnClickListener(v -> detach());
      FrameLayout.LayoutParams lp =
          new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
      lp.topMargin = a.dp(8);
      lp.rightMargin = a.dp(8);
      View decor = a.getWindow().peekDecorView();
      if (decor instanceof ViewGroup) {
        ((ViewGroup) decor).addView(panel, lp);
      } else if (a.root != null) {
        a.root.addView(panel, lp);
      }
      running = true;
      frames = 0;
      dropped = 0;
      lastSecond = 0;
      lastLogAt = 0;
      Choreographer.getInstance().postFrameCallback(FRAME);
      writeLog("attach");
    } catch (Throwable error) {
      AppLog.w("Perf", "overlay attach failed", error);
    }
  }

  private static void detach() {
    running = false;
    try {
      Choreographer.getInstance().removeFrameCallback(FRAME);
    } catch (Throwable ignored) {
    }
    try {
      if (panel != null && panel.getParent() instanceof ViewGroup)
        ((ViewGroup) panel.getParent()).removeView(panel);
    } catch (Throwable ignored) {
    }
    panel = null;
    label = null;
  }

  private static void updateText() {
    if (label == null) return;
    label.setText(
        "FPS "
            + fps
            + " · drop/s "
            + dropPerSec
            + "\n"
            + "PSS "
            + pssMb()
            + "MB · "
            + pageTag()
            + "\nwallpaper "
            + wallpaperState());
  }

  private static int pssMb() {
    try {
      android.app.ActivityManager am =
          (android.app.ActivityManager)
              host.getSystemService(android.content.Context.ACTIVITY_SERVICE);
      android.os.Debug.MemoryInfo[] info =
          am.getProcessMemoryInfo(new int[] {android.os.Process.myPid()});
      return info == null || info.length == 0 ? -1 : info[0].getTotalPss() / 1024;
    } catch (Throwable ignored) {
      return -1;
    }
  }

  private static String pageTag() {
    try {
      if (host.body != null && host.body.getTag() != null)
        return String.valueOf(host.body.getTag());
      if (host.root != null && host.root.getTag() != null)
        return String.valueOf(host.root.getTag());
    } catch (Throwable ignored) {
    }
    return "?";
  }

  private static String wallpaperState() {
    try {
      if (host.prefs.getBoolean(Prefs.WALLPAPER_VIDEO, false)
          && VideoWallpaper.file(host).isFile()) {
        if (host.player != null) return "video paused(player)";
        return "video";
      }
    } catch (Throwable ignored) {
    }
    return "static";
  }

  private static void writeLog(String reason) {
    String line =
        "page="
            + pageTag()
            + " fps="
            + fps
            + " dropped="
            + dropPerSec
            + " pss="
            + pssMb()
            + "MB wallpaper="
            + wallpaperState()
            + " reason="
            + reason;
    synchronized (LINES) {
      LINES.addLast(line);
      while (LINES.size() > MAX_LINES) LINES.removeFirst();
    }
    AppLog.i("Perf", line);
  }

  static void export(final MainActivity a) {
    MAIN.post(
        () -> {
          try {
            String stamp =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String name = "perf-" + stamp + ".txt";
            StringBuilder text = new StringBuilder();
            synchronized (LINES) {
              for (String line : LINES) text.append(line).append('\n');
            }
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
            java.io.File outFile = new java.io.File(dir, name);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
              out.write(text.toString().getBytes("UTF-8"));
            }
            AppLog.i("Perf", "export path=" + outFile.getAbsolutePath());
            a.toast("性能日志已导出：" + outFile.getAbsolutePath());
          } catch (Throwable error) {
            AppLog.w("Perf", "export failed", error);
            a.toast("导出失败：" + error.getClass().getSimpleName());
          }
        });
  }

  static int lines() {
    synchronized (LINES) {
      return LINES.size();
    }
  }
}
