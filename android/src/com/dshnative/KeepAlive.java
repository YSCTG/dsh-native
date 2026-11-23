package com.dshnative;

import android.content.Context;
import android.os.PowerManager;

/**
 * 后台保活：跑任务期间抓住一把 WakeLock，并把"电池优化白名单"当成用户可见的状态。
 *
 * 说明白能做什么、不能做什么：
 *  - 能做的：CPU 在锁屏后不休眠，前台服务 + 通知把进程优先级抬高；
 *  - 不能做的：厂商的"自启动/后台限制"是系统策略，App 没有权限改，
 *    只能引导用户去系统设置里放行。
 *
 * 所以这里的定位是"尽量别被杀 + 被杀了说实话"。
 */
public final class KeepAlive {

    private static PowerManager.WakeLock lock;
    private static int depth;

    private KeepAlive() {}

    /** 有任务在跑时持有；嵌套调用安全。 */
    public static synchronized void acquire(Context c) {
        try {
            depth++;
            if (lock != null && lock.isHeld()) return;
            PowerManager pm = (PowerManager) c.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshnative:run");
            lock.setReferenceCounted(false);
            lock.acquire(11 * 60 * 1000L);   // 和看门狗同一个量级，超时自动放
        } catch (Throwable ignored) {}
    }

    /** 任务结束时释放；引用计数归零才真正放。 */
    public static synchronized void release() {
        try {
            depth = Math.max(0, depth - 1);
            if (depth > 0) return;
            if (lock != null && lock.isHeld()) lock.release();
            lock = null;
        } catch (Throwable ignored) {}
    }

    /** 是否已在"电池优化"白名单里（没有这条系统 API 时按下限处理）。 */
    public static boolean ignoringBatteryOptimizations(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    /** 后台保活当前是否生效（有锁 or 已进白名单）。 */
    public static boolean active(Context c) {
        boolean held;
        synchronized (KeepAlive.class) {
            held = lock != null && lock.isHeld();
        }
        return held || ignoringBatteryOptimizations(c);
    }
}
