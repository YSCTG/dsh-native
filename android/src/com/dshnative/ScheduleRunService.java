package com.dshnative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 执行一条定时任务。
 *
 * 无人值守的三条硬规矩：
 *  1. 所有需要人工确认的工具一律**拒绝**（Headless.confirm 直接返回 false）——
 *     没人看着的时候，宁可不做，也不要假装有人点过"执行"。
 *  2. 跑在前台服务里，进程才不会被掐。
 *  3. 结果写进一个新会话 + 发通知；下次到点由 Schedule.arm 重新挂上。
 */
public class ScheduleRunService extends Service {

    private static final String CH = "dshnative_sched";
    private static final int NOTIF_ID = 0x4453;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("定时任务运行中…", ""));
        final String taskId = intent == null ? null : intent.getStringExtra("task");
        if (taskId == null) { stopSelf(); return START_NOT_STICKY; }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runTask(taskId); }
        });
        t.setDaemon(true);
        t.start();
        return START_NOT_STICKY;
    }

    private void runTask(String taskId) {
        Schedule.Task task = Schedule.byId(this, taskId);
        if (task == null || !task.enabled) { finish("任务已删除或已停用", ""); return; }

        SharedPreferences p = getSharedPreferences("dshnative", MODE_PRIVATE);
        String key = Secret.get(p);
        if (key == null || key.isEmpty()) { finish("没有 API key，跳过", task.name); return; }

        String model = p.getString("model", "deepseek-flash");
        String baseUrl = p.getString("base_url", "https://api.deepseek.com");
        Mode mode = Mode.byId(p.getString("mode", "standard"));

        Session s = Session.create(this);
        s.title = "定时 · " + task.name;
        Session.touch(this, s);

        Agent agent = new Agent(this, key, model, baseUrl, mode, s, Shell.hasRoot(), new Headless());
        agent.run(task.prompt);

        Schedule.markRun(this, taskId);
        Schedule.arm(this);

        String last = lastAssistant(s.id);
        finish(last.isEmpty() ? "已执行（没有文字输出）" : last, task.name);
    }

    private String lastAssistant(String sessionId) {
        JSONArray msgs = Session.load(this, sessionId);
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if (!"assistant".equals(m.optString("role"))) continue;
            String c = m.optString("content", "");
            if (!c.isEmpty()) return c.length() > 300 ? c.substring(0, 300) + "…" : c;
        }
        return "";
    }

    private void finish(String text, String title) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(
                    title.isEmpty() ? "定时任务" : "定时 · " + title, text));
        } catch (Exception ignored) {}
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification(String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CH, "定时任务", NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** 无人值守：不弹窗、不确认、不审计成"人批准的"。 */
    private static final class Headless implements Agent.Listener {
        @Override public void onAssistant(String delta) {}
        @Override public void onReasoning(String delta) {}
        @Override public void onTool(String name, String detail) {}
        @Override public void onToolDone(String name, long ms) {}
        @Override public void onStatus(String status) {}
        @Override public void onError(String message) {}
        @Override public void onBusy(boolean busy) {}
        @Override public void onRender(String html, String url) {}
        @Override public boolean confirm(String title, String body) {
            return false;   // 没人看着 → 不做需要确认的事
        }
    }
}
