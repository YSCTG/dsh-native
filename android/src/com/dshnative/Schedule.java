package com.dshnative;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时自动化。
 *
 * 用 AlarmManager 的 setAndAllowWhileIdle（**非精确**闹钟）：不需要 SCHEDULE_EXACT_ALARM 权限，
 * 代价是系统可能推迟几分钟。对"每天早上跑一次"这类任务够用；要精确到秒得上精确闹钟权限。
 *
 * 到点后由 ScheduleReceiver 拉起 ScheduleRunService，在后台跑一轮 agent。
 * 无人值守时的安全约束：**所有需要确认的工具一律拒绝**，见 ScheduleRunService。
 */
public final class Schedule {

    public static final class Task {
        public String id = "";
        public String name = "";
        public String prompt = "";
        public int intervalMin = 60;
        public boolean enabled = true;
        public long lastRun = 0L;
    }

    private static final String PREF = "schedules";
    private static final int REQ = 0x445311;

    private Schedule() {}

    public static List<Task> list(Context c) {
        List<Task> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(PREF, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.optString("id");
                t.name = o.optString("name");
                t.prompt = o.optString("prompt");
                t.intervalMin = o.optInt("intervalMin", 60);
                t.enabled = o.optBoolean("enabled", true);
                t.lastRun = o.optLong("lastRun", 0);
                out.add(t);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context c, List<Task> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Task t : all) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("name", t.name);
                o.put("prompt", t.prompt);
                o.put("intervalMin", t.intervalMin);
                o.put("enabled", t.enabled);
                o.put("lastRun", t.lastRun);
                arr.put(o);
            }
            prefs(c).edit().putString(PREF, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static Task add(Context c, String name, String prompt, int intervalMin) {
        Task t = new Task();
        t.id = "t" + Long.toString(System.currentTimeMillis(), 36);
        t.name = name.trim();
        t.prompt = prompt.trim();
        t.intervalMin = Math.max(1, intervalMin);
        t.enabled = true;
        List<Task> all = list(c);
        all.add(t);
        save(c, all);
        arm(c);
        return t;
    }

    public static void remove(Context c, String id) {
        List<Task> all = list(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        save(c, all);
        arm(c);
    }

    public static void setEnabled(Context c, String id, boolean on) {
        List<Task> all = list(c);
        for (Task t : all) if (t.id.equals(id)) t.enabled = on;
        save(c, all);
        arm(c);
    }

    public static void markRun(Context c, String id) {
        List<Task> all = list(c);
        for (Task t : all) if (t.id.equals(id)) t.lastRun = System.currentTimeMillis();
        save(c, all);
    }

    public static Task byId(Context c, String id) {
        for (Task t : list(c)) if (t.id.equals(id)) return t;
        return null;
    }

    /** 按最早到期的一个任务设一个闹钟；没有启用任务就取消。 */
    public static void arm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pending(c, null);
            am.cancel(pi);

            Task next = null;
            long when = Long.MAX_VALUE;
            long now = System.currentTimeMillis();
            for (Task t : list(c)) {
                if (!t.enabled) continue;
                long at = (t.lastRun == 0 ? now : t.lastRun) + t.intervalMin * 60000L;
                if (at < when) { when = at; next = t; }
            }
            if (next == null) return;
            if (when < now + 5000) when = now + 5000;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c, next.id));
        } catch (Exception ignored) {}
    }

    private static PendingIntent pending(Context c, String taskId) {
        Intent i = new Intent(c, ScheduleReceiver.class);
        if (taskId != null) i.putExtra("task", taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }

    private static SharedPreferences prefs(Context c) {
        return Prefs.get(c);
    }
}
