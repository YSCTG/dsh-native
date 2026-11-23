package com.dshnative;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 换包名 / 换机时搬家。
 *
 * 为什么需要：Android 的数据都是按包名隔离的，改包名等于换了个应用——
 * 会话历史、插件、偏好全在新包名下看不见。授权（无障碍、Shizuku、文件、通知）
 * 也只能重新给一遍，这个没有技术手段绕。
 *
 * 能搬的：会话历史、插件、偏好（含 API key）、定时任务。
 * 走 /sdcard/DSHNative/.migrate 中转（两个包名都能读写共享存储）。
 *
 * 安全说明：API key 在旧包里是 Keystore 加密的，密文换包名解不开，
 * 所以搬家时写的是**明文**。导入完成后立刻删掉中转目录，别让它长期躺在存储里。
 */
public final class Migrate {

    public static final String DIR = ".migrate";

    private Migrate() {}

    private static File dir(Context c) {
        return new File(Ws.active(c), DIR);
    }

    public static boolean pending(Context c) {
        File d = dir(c);
        return new File(d, "meta.json").isFile();
    }

    /** 把当前应用的数据写到共享存储，供新包名的版本导入。 */
    public static String export(Context c) {
        try {
            File d = dir(c);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            int sessions = copyDir(new File(c.getFilesDir(), "sessions"), new File(d, "sessions"));
            int plugins = copyDir(new File(c.getFilesDir(), "plugins"), new File(d, "plugins"));

            // 偏好：除了 "api_key_enc"（换包名解不开），整份 xml 复制过去也没用，
            // 拆成 JSON 更可控。
            SharedPreferences p = Prefs.get(c);
            JSONObject prefs = new JSONObject();
            for (String k : p.getAll().keySet()) {
                if ("api_key_enc".equals(k)) continue;
                Object v = p.getAll().get(k);
                if (v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long) {
                    prefs.put(k, v);
                }
            }
            write(new File(d, "prefs.json"), prefs.toString(2).getBytes("UTF-8"));

            // API key 明文（见类注释里的安全说明）
            String key = Secret.get(p);
            write(new File(d, "key.txt"), (key == null ? "" : key).getBytes("UTF-8"));

            JSONObject meta = new JSONObject();
            meta.put("from", c.getPackageName());
            meta.put("at", System.currentTimeMillis());
            meta.put("sessions", sessions);
            meta.put("plugins", plugins);
            write(new File(d, "meta.json"), meta.toString(2).getBytes("UTF-8"));

            return "已导出：" + sessions + " 个会话、" + plugins + " 个插件 → " + d.getAbsolutePath();
        } catch (Exception e) {
            return "导出失败：" + e.getMessage();
        }
    }

    /** 新包首次启动时调用：有中转目录就搬回来，搬完删掉。 */
    public static String importIfPresent(Context c) {
        File d = dir(c);
        if (!new File(d, "meta.json").isFile()) return null;
        try {
            int sessions = copyDir(new File(d, "sessions"), new File(c.getFilesDir(), "sessions"));
            int plugins = copyDir(new File(d, "plugins"), new File(c.getFilesDir(), "plugins"));

            File pf = new File(d, "prefs.json");
            if (pf.isFile()) {
                JSONObject prefs = new JSONObject(read(pf));
                SharedPreferences.Editor e = Prefs.get(c).edit();
                java.util.Iterator<String> it = prefs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = prefs.get(k);
                    if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) e.putInt(k, (Integer) v);
                    else if (v instanceof Long) e.putLong(k, (Long) v);
                    else e.putString(k, String.valueOf(v));
                }
                e.apply();
            }

            File kf = new File(d, "key.txt");
            if (kf.isFile()) {
                String key = read(kf).trim();
                if (!key.isEmpty()) Secret.put(Prefs.get(c), key);
            }

            // 搬完就删：明文 key 不该长期留在共享存储里
            deleteTree(d);
            return "已从上一版导入：" + sessions + " 个会话、" + plugins + " 个插件";
        } catch (Exception e) {
            return "导入失败：" + e.getMessage();
        }
    }

    // ---------------- 工具 ----------------

    private static int copyDir(File from, File to) {
        if (from == null || !from.isDirectory()) return 0;
        //noinspection ResultOfMethodCallIgnored
        to.mkdirs();
        File[] fs = from.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            File dst = new File(to, f.getName());
            try {
                if (f.isDirectory()) {
                    n += copyDir(f, dst);
                } else {
                    FileInputStream in = new FileInputStream(f);
                    FileOutputStream out = new FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    in.close();
                    out.close();
                    n++;
                }
            } catch (Exception ignored) {}
        }
        return n;
    }

    private static void write(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int n = in.read(buf);
        in.close();
        return new String(buf, 0, Math.max(n, 0), "UTF-8");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
