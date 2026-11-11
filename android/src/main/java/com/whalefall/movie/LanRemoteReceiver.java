package com.whalefall.movie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 1.9.30：手机遥控常驻通知的「停止」入口。 */
public final class LanRemoteReceiver extends BroadcastReceiver {
 static final String ACTION_STOP = "com.whalefall.movie.LAN_REMOTE_STOP";

 @Override
 public void onReceive(Context context, Intent intent) {
 if (intent == null || !ACTION_STOP.equals(intent.getAction())) return;
 AppLog.i("LanRemote", "keepalive stop reason=notification");
 RemoteInputScreen.stop("notification");
 RemoteInputScreen.cancelNotification(context);
 }
}
