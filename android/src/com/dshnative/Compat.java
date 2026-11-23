package com.dshnative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 环境适配层。
 *
 * 手机上真正让人卡住的不是代码，是各家 ROM 的差异：
 *  - 无障碍服务在重装后会被系统解绑（华为/小米都会）
 *  - 旁加载应用在 Android 13+ 要额外开「受限设置」
 *  - 各家自启动/省电白名单藏在完全不同的 Activity 里
 *  - ADB 安装会弹两三层「风险提示」（华为尤其）
 *
 * 这里只做两件事：**探测状态** + **把用户送到正确的那个页面**。
 * 不去尝试绕过系统限制 —— 那既不可靠也不该做。
 */
public final class Compat {

    private Compat() {}

    public static String oem() {
        String b = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase();
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase();
        String s = b + " " + m;
        if (s.contains("huawei") || s.contains("honor")) return "huawei";
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) return "xiaomi";
        if (s.contains("oppo") || s.contains("realme") || s.contains("oneplus")) return "oppo";
        if (s.contains("vivo") || s.contains("iqoo")) return "vivo";
        if (s.contains("samsung")) return "samsung";
        return "other";
    }

    public static String oemLabel() {
        switch (oem()) {
            case "huawei": return "华为 / 荣耀";
            case "xiaomi": return "小米 / Redmi";
            case "oppo": return "OPPO / 一加 / realme";
            case "vivo": return "vivo / iQOO";
            case "samsung": return "三星";
            default: return "通用 Android";
        }
    }

    // ---------------- 状态探测 ----------------

    public static boolean a11yInSettings(Context c) {
        try {
            int on = Settings.Secure.getInt(c.getContentResolver(), "accessibility_enabled");
            if (on != 1) return false;
            String s = Settings.Secure.getString(
                    c.getContentResolver(), "enabled_accessibility_services");
            return s != null && s.contains(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean allFilesGranted(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return android.os.Environment.isExternalStorageManager();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean batteryIgnored(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean notificationsOn(Context c) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 跳到对应页面 ----------------

    private static boolean go(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 打开无障碍列表（那一页里找 DSH Native）。
     * 想直达单个服务的页面需要 ACTION_ACCESSIBILITY_DETAILS_SETTINGS，
     * 但那个常量不在公开 SDK 里，各家实现也不一致 —— 宁可用稳定的总列表，不要花活。
     */
    public static void openAccessibility(Context c) {
        go(c, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    public static void openAllFiles(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                if (go(c, i)) return;
            } catch (Exception ignored) {}
            if (go(c, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return;
        }
        openAppDetail(c);
    }

    public static void openAppDetail(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            go(c, i);
        } catch (Exception ignored) {}
    }

    public static void openBattery(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            if (go(c, i)) return;
        } catch (Exception ignored) {}
        go(c, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    public static void openNotifications(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
            if (go(c, i)) return;
        } catch (Exception ignored) {}
        openAppDetail(c);
    }

    /** 自启动 / 后台启动管理：各家 Activity 名字都不一样，只能逐个试，全失败就退回应用详情页。 */
    public static void openAutostart(Context c) {
        String[][] candidates;
        switch (oem()) {
            case "huawei":
                candidates = new String[][]{
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                };
                break;
            case "xiaomi":
                candidates = new String[][]{
                        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                };
                break;
            case "oppo":
                candidates = new String[][]{
                        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                };
                break;
            case "vivo":
                candidates = new String[][]{
                        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                };
                break;
            default:
                candidates = new String[][]{};
        }
        for (String[] cand : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(cand[0], cand[1]));
                if (go(c, i)) return;
            } catch (Exception ignored) {}
        }
        openAppDetail(c);
    }
}
