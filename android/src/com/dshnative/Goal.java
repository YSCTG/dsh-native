package com.dshnative;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * 目标：一句话讲清"这一轮到底要干什么、什么算完成"。
 *
 * 和待办的分工：待办是步骤（怎么做），目标是目的（为什么做）。
 * 长任务跑偏，通常不是忘了下一步，而是忘了终点——所以目标要反复回到眼前。
 *
 * 每个会话一份，存 <files>/goals/<session>.txt。写文件而不是内存，
 * 因为手机进程随时可能被杀，回来还得记得自己在做什么。
 */
public final class Goal {

    private Goal() {}

    private static File file(Context c, String sessionId) {
        File d = new File(c.getFilesDir(), "goals");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return new File(d, (sessionId == null || sessionId.isEmpty() ? "default" : sessionId) + ".txt");
    }

    public static void set(Context c, String sessionId, String text) {
        File f = file(c, sessionId);
        if (f == null) return;
        try {
            FileOutputStream os = new FileOutputStream(f);
            os.write(text.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }

    public static String get(Context c, String sessionId) {
        File f = file(c, sessionId);
        if (f == null || !f.isFile()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context c, String sessionId) {
        File f = file(c, sessionId);
        if (f != null && f.isFile()) f.delete();
    }
}
