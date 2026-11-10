package com.whalefall.movie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 1.9.22 后台播放：通知栏媒体控制（播放/暂停、停止），配合 MainActivity.audio focus。
 */
final class BgPlay {
    static final String ACTION_TOGGLE = "com.whalefall.movie.BG_TOGGLE";
    static final String ACTION_STOP = "com.whalefall.movie.BG_STOP";
    static final int ID = 7310;
    static final String CHANNEL = "whalefall_playback";

    private BgPlay() {}

    static void show(MainActivity a, boolean playing, String title) {
        try {
            NotificationManager nm =
                    (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                        new NotificationChannel(
                                CHANNEL, "后台播放", NotificationManager.IMPORTANCE_LOW));
            }
            Intent toggle = new Intent(ACTION_TOGGLE).setPackage(a.getPackageName());
            Intent stop = new Intent(ACTION_STOP).setPackage(a.getPackageName());
            PendingIntent togglePi =
                    PendingIntent.getBroadcast(
                            a, 1, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stopPi =
                    PendingIntent.getBroadcast(
                            a, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent open = new Intent(a, MainActivity.class);
            PendingIntent openPi =
                    PendingIntent.getActivity(
                            a, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder =
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? new Notification.Builder(a, CHANNEL)
                            : new Notification.Builder(a);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title == null ? "正在播放" : title)
                    .setContentText(playing ? "后台播放中" : "已暂停")
                    .setContentIntent(openPi)
                    .setOnlyAlertOnce(true)
                    .setOngoing(playing)
                    .addAction(
                            playing
                                    ? android.R.drawable.ic_media_pause
                                    : android.R.drawable.ic_media_play,
                            playing ? "暂停" : "播放",
                            togglePi)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
            nm.notify(ID, builder.build());
        } catch (Throwable ignored) {
        }
    }

    static void cancel(MainActivity a) {
        try {
            ((NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);
        } catch (Throwable ignored) {
        }
    }

    static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            MainActivity a = MainActivity.activeActivity;
            if (a == null || a.isFinishing() || a.isDestroyed()) {
                // 1.9.27：无有效 Activity 时取消常驻假控制
                try {
                    ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                            .cancel(ID);
                } catch (Throwable ignored) {
                }
                AppLog.i("BgPlay", "receive ignored reason=no-activity");
                return;
            }
            if (intent == null || intent.getAction() == null) return;
            AppLog.i("BgPlay", "action=" + intent.getAction());
            if (ACTION_STOP.equals(intent.getAction())) {
                a.stopBackgroundPlayback();
                return;
            }
            a.toggleBackgroundPlayback();
        }
    }
}
