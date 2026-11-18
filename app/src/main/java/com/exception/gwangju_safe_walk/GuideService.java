package com.exception.gwangju_safe_walk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

// 안내 중에만 떠 있는 서비스. 이게 있어야 화면을 꺼도 위치가 계속 들어온다
public class GuideService extends Service {

    private static final String CHANNEL = "guide";
    private static final int NOTI_ID = 1001;
    private static final String EXTRA_TEXT = "text";

    public static void start(Context c, String text) {
        Intent i = new Intent(c, GuideService.class);
        i.putExtra(EXTRA_TEXT, text);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, GuideService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) text = "경로를 안내하고 있습니다";

        Notification n = build(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTI_ID, n);
        }
        // 앱을 밀어서 없애면 같이 죽어야 한다. 그래야 서버가 신호 두절로 본다
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build(String text) {
        makeChannel();

        Intent open = new Intent(this, RouteActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, flag);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("광주 안심길 안내 중")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_turn_straight)
                .setContentIntent(tap)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void makeChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "경로 안내",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("안내 중일 때만 표시됩니다");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
