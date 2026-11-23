package com.dshnative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 闹钟到点：把真正的执行交给前台服务（后台直接跑会被系统掐掉）。 */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String taskId = intent == null ? null : intent.getStringExtra("task");
        if (taskId == null) return;
        try {
            Intent svc = new Intent(c, ScheduleRunService.class);
            svc.putExtra("task", taskId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(svc);
            } else {
                c.startService(svc);
            }
        } catch (Exception ignored) {}
    }
}
