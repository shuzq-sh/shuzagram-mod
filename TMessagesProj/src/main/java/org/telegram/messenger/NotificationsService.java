/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        // ShuzaGram Mod: without push services the connection has to stay alive,
        // which modern Android only allows for a foreground service.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                android.app.NotificationChannel channel = new android.app.NotificationChannel("shuzamod_keepalive", LocaleController.getString(R.string.ShuzaModKeepAliveChannel), android.app.NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
                android.content.Intent open = new android.content.Intent(this, org.telegram.ui.LaunchActivity.class);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.Notification n = new android.app.Notification.Builder(this, "shuzamod_keepalive")
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.getString(R.string.ShuzaModKeepAliveTitle))
                    .setContentText(LocaleController.getString(R.string.ShuzaModKeepAliveText))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startForeground(2026, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(2026, n);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
