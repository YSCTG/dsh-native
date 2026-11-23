package com.dshnative;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * 工作区 = 整个手机。
 *
 * 以前相对路径被关在 files/workspace 里，等于给 agent 造了个笼子；现在不造笼子了：
 *  - 绝对路径原样访问，/sdcard 下任何位置都能读写（有「所有文件访问」权限时）
 *  - 没有该权限时自动退回私有目录，功能不消失，只是范围变小
 */
public final class Ws {

    public static final String DIR_NAME = "DSHNative";

    private Ws() {}

    /** 首选位置：/sdcard/DSHNative */
    /** 有没有「所有文件访问」：没有就只能读写应用私有目录。 */
    public static boolean hasAllFiles(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return true;
            return android.os.Environment.isExternalStorageManager();
        } catch (Throwable e) {
            return false;
        }
    }

    public static File preferred(Context c) {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) return new File(ext, DIR_NAME);
        } catch (Exception ignored) {}
        return new File(c.getFilesDir(), DIR_NAME);
    }

    private static File legacy(Context c) {
        return new File(c.getFilesDir(), "workspace");
    }

    /** 真正可用的根：当前工作区优先，其次外部存储，最后私有目录。 */
    public static File active(Context c) {
        try {
            File cur = Workspaces.currentDir(c);
            if (cur != null && cur.isDirectory()) return cur;
        } catch (Throwable ignored) {}
        File p = preferred(c);
        if (p.isDirectory()) return p;
        if (p.mkdirs()) return p;
        //noinspection ResultOfMethodCallIgnored
        legacy(c).mkdirs();
        return legacy(c);
    }

    /** 把老版本的 files/workspace 里的文件搬过来（只做一次）。 */
    public static void migrate(Context c) {
        File old = legacy(c);
        if (!old.isDirectory()) return;
        File root = active(c);
        if (root.equals(old)) return;
        File marker = new File(c.getFilesDir(), ".migrated");
        if (marker.isFile()) return;
        try {
            File[] kids = old.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    if (!f.isFile()) continue;
                    File dst = new File(root, f.getName());
                    if (dst.exists()) continue;
                    java.io.InputStream in = new java.io.FileInputStream(f);
                    java.io.OutputStream out = new java.io.FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                }
            }
            java.io.FileOutputStream m = new java.io.FileOutputStream(marker);
            m.write("ok".getBytes());
            m.close();
        } catch (Exception ignored) {}
    }

    public static String describe(Context c) {
        File root = active(c);
        boolean external = root.getAbsolutePath().startsWith("/sdcard")
                || root.getAbsolutePath().startsWith("/storage");
        return root.getAbsolutePath() + (external ? "（手机存储，可访问整个 /sdcard）" : "（私有目录，未授权外部存储）");
    }
}
