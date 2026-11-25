package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把编好的 APK 交给系统安装器 / 分享出去。
 *
 * 为什么要单独一个类：Android 7 起不能把 file:// 交给别的应用，必须用 content://
 * 加临时授权（我们自己的 {@link FileProviderX}）。而"安装未知应用"权限没给时，
 * 起安装器会静默失败——所以这里先检查，没给就带用户去授权页。
 */
public final class Apks {

    private Apks() {}

    /** 找到一个可安装的 APK 文件（优先 *-signed.apk）。 */
    public static File pick(File appDir) {
        File[] fs = appDir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".apk")) continue;
            if (f.getName().endsWith("-signed.apk")) return f;
            if (best == null) best = f;
        }
        return best;
    }

    /** 列出工作区里所有编好的 APK（apps/<名字>/ 下的产物）。 */
    public static List<File> all(Context c) {
        List<File> out = new ArrayList<>();
        try {
            File apps = new File(Workspaces.currentDir(c), "apps");
            File[] dirs = apps.listFiles();
            if (dirs == null) return out;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                File apk = pick(d);
                if (apk != null && apk.isFile()) out.add(apk);
            }
        } catch (Throwable ignored) {}
        return out;
    }


    /** 人类可读的大小（列表里用）。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    /** 项目名（apps/<名字>/ 里的 <名字>）。 */
    public static String projectName(File apk) {
        File d = apk == null ? null : apk.getParentFile();
        return d == null ? "" : d.getName();
    }

    /** APK 自己的包名与版本；读不出来返回 null。 */
    public static String[] packageOf(android.content.Context c, File apk) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null) return null;
            return new String[]{pi.packageName, pi.versionName == null ? "?" : pi.versionName};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个 APK 对应的包是否已装在机器上；装了返回版本名，否则 null。 */
    public static String installedVersion(android.content.Context c, File apk) {
        String[] pa = packageOf(c, apk);
        if (pa == null) return null;
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(pa[0], 0);
            String v = pi.versionName == null ? "" : pi.versionName.trim();
            return v.isEmpty() ? "" : v;      // 空字符串 = 装了但没版本号，交给调用方显示"已安装"
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删除整个项目目录（APK 所在的 apps/<名字>/）。返回删除的文件数。 */
    public static int deleteProject(File apk) {
        File dir = apk == null ? null : apk.getParentFile();
        if (dir == null) return 0;
        return deleteTree(dir);
    }

    private static int deleteTree(File f) {
        if (f == null || !f.exists()) return 0;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += deleteTree(k);
        return f.delete() ? n + 1 : n;
    }

    /** 打开系统安装器。返回 null 表示已发起，否则返回要提示的文字。 */
    public static String install(Activity c, File apk) {
        if (apk == null || !apk.isFile()) return "找不到 APK 文件";
        if (!Updater.canInstall(c)) {
            Updater.openInstallPermission(c);
            return "需要先允许「安装未知应用」，已打开授权页；允许后再点一次";
        }
        try {
            Uri uri = FileProviderX.uriFor(c, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Throwable t) {
            return "起不了安装器：" + t.getMessage();
        }
    }

    /** 分享 APK（存到网盘、发给别人、或用别的安装器打开）。 */
    public static String share(Context c, File apk) {
        try {
            Uri uri = FileProviderX.uriFor(c, apk);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(Intent.createChooser(i, "分享 " + apk.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        } catch (Throwable t) {
            return "分享失败：" + t.getMessage();
        }
    }
}
