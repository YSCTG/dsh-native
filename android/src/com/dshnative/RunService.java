package com.dshnative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 跑任务时的前台服务。
 *
 * 为什么需要：手机随时会把后台进程回收，一轮长任务（多次工具调用）跑到一半被杀，
 * 会话里就留下半条记录。前台服务 + 常驻通知能把进程钉住，锁屏也继续跑。
 *
 * 类型用 specialUse：dataSync 在 Android 15 上有时长上限，不适合 agent。
 */
public class RunService extends Service {

    private static final String CHANNEL = "dshnative_run";
    private static final int NOTIF_ID = 0x4453;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, RunService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
            // 系统拒绝（例如后台启动限制）时不要影响正常流程
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, RunService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            CHANNEL, "运行中", NotificationManager.IMPORTANCE_LOW));
                }
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            // 计时器 + 不可清除：「正在进行中」比一句静止的文案更像"还活着"，
            // 也让系统把我们归到前台服务而不是可回收的后台进程。
            b.setContentTitle("DSH Native · 正在执行")
                    .setContentText("长任务在跑，锁屏不会中断")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(Notification.CATEGORY_SERVICE);
            startForeground(NOTIF_ID, b.build());
        } catch (Exception ignored) {}
        return START_STICKY;   // 被系统回收后尽量自己回来
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
