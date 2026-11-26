package com.dshnative;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手机端 APK 构建工具链：按需下载 + 释放 + 权限。
 *
 * 为什么不打进 APK：这套东西约 50 MB（未压缩），主要开销是 aapt2 需要的框架资源表
 * （resources.arsc 一个文件就 27.8 MB）。基础安装包不该为少数使用场景背这个重量，
 * 所以第一次真正构建时才下载（16.5 MB 压缩包），之后常驻 files/abt/。
 *
 * 组成（全部在手机上执行）：
 *   aapt2 + 两个 .so      打包
 *   zipalign              对齐
 *   ecj-dex.jar           Java 编译器（已 dex 化，dalvikvm 直接跑）
 *   android-stub.jar      编译期 API 类桩
 *   d8-dex.jar            dexer
 *   apksigner-dex.jar     签名器
 *   framework.jar         aapt2 的框架资源（API 28，aapt2 2.19 能解析）
 *   key.pk8 / cert.der    自签密钥（PKCS#8 + DER；Android 没有 JKS）
 */
public final class AbTools {

    private static final String TAG = "DSHN";
    private static final String ZIP = "abt.zip";
    /** 工具链发布地址；跟着 App 版本走，改版本时同步。 */
    private static final String[] URLS = {
            "https://github.com/YSCTG/dsh-native/releases/download/v35.2/abt.zip",
            "https://gh-proxy.org/https://github.com/YSCTG/dsh-native/releases/download/v35.2/abt.zip",
    };
    private static final long EXPECT_SIZE = 24541944;   // 约 16.5 MB

    private AbTools() {}

    public static File dir(Context c) { return new File(c.getFilesDir(), "abt"); }

    /** 工具链版本；包内容变了就加一，老解压会被丢掉重来。 */
    private static final String VERSION = "4";

    public static boolean ready(Context c) {
        File d = dir(c);
        if (!new File(d, "aapt2").isFile() || !new File(d, "ecj-dex.jar").isFile()) return false;
        // 版本对不上（比如新版本包里多了 adb）就当没就绪，重新解压
        try {
            File vf = new File(d, "abt.version");
            if (!vf.isFile()) return false;
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(vf), "UTF-8"));
            String v = br.readLine();
            br.close();
            return VERSION.equals(v == null ? "" : v.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 确保工具链就位；返回 null 表示成功，否则返回错误说明。 */
    public static String ensure(Context c) {
        File d = dir(c);
        if (ready(c)) return null;
        try {
            // 旧解压先清掉，否则新版文件不会覆盖进来
            deleteTree(d);
            if (!d.isDirectory()) d.mkdirs();
            // 先就地释放：安装包里自带工具链，装完即可用，不必等下载
            if (extractFromAssets(c, d)) {
                chmod(d);
                if (ready(c)) {
                    Log.i(TAG, "工具链从安装包释放完成 " + d.getAbsolutePath());
                    return null;
                }
            }

            File zip = new File(c.getFilesDir(), ZIP);
            // 缓存包也要看版本内容：只看"条目在不在"会放过旧包（真踩过这个坑）
            boolean zipLooksOld = false;
            try {
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
                java.util.zip.ZipEntry ve = zf.getEntry("abt.version");
                String v = "";
                if (ve != null) {
                    java.io.InputStream in = zf.getInputStream(ve);
                    byte[] b = new byte[16];
                    int n = in.read(b);
                    in.close();
                    if (n > 0) v = new String(b, 0, n, "UTF-8").trim();
                }
                zipLooksOld = !VERSION.equals(v);
                zf.close();
            } catch (Throwable ignored) {}
            if (!zip.isFile() || zip.length() < EXPECT_SIZE / 2 || zipLooksOld) {
                String err = download(zip);
                if (err != null) return err;
            }
            extract(zip, d);
            chmod(d);
            if (!ready(c)) return "错误：解压后仍缺文件，目录 " + d.getAbsolutePath();
            Log.i(TAG, "构建工具链就绪 " + d.getAbsolutePath());
            return null;
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }


    /** 从 APK 的 assets/abt.zip 直接解压；成功返回 true。 */
    private static boolean extractFromAssets(Context c, File dir) {
        java.io.FileOutputStream os = null;
        try {
            java.io.InputStream in = c.getAssets().open(ZIP);
            File tmp = new File(c.getFilesDir(), ZIP + ".asset");
            os = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            os = null;
            in.close();
            // 复用同一套解压逻辑
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                File f = new File(dir, e.getName());
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                java.io.InputStream zi = zf.getInputStream(e);
                java.io.FileOutputStream zo = new java.io.FileOutputStream(f);
                int m;
                byte[] b2 = new byte[64 * 1024];
                while ((m = zi.read(b2)) > 0) zo.write(b2, 0, m);
                zo.close();
                zi.close();
            }
            zf.close();
            tmp.delete();
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "安装包内没有工具链（回退到下载）: " + t);
            if (os != null) try { os.close(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static String download(File out) {
        Exception last = null;
        for (String src : URLS) {
            HttpURLConnection conn = null;
            try {
                Log.i(TAG, "下载构建工具链 " + src);
                conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "DSH-Native");
                if (conn.getResponseCode() != 200) {
                    last = new Exception("HTTP " + conn.getResponseCode());
                    continue;
                }
                InputStream in = conn.getInputStream();
                FileOutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                in.close();
                if (out.length() > EXPECT_SIZE / 2) return null;
                last = new Exception("下载不完整 " + out.length());
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return "错误：工具链下载失败（" + (last == null ? "未知" : last.getMessage()) + "）";
    }

    private static void extract(File zip, File dir) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        byte[] buf = new byte[64 * 1024];
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            File f = new File(dir, e.getName());
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            InputStream in = zf.getInputStream(e);
            FileOutputStream os = new FileOutputStream(f);
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
        }
        zf.close();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void chmod(File dir) {
        // adb 也要可执行位：漏了它 exec 会失败，而失败是静默的（返回空输出）
        String[] exes = {"aapt2", "zipalign", "adb/adb"};
        for (String name : exes) {
            File f = new File(dir, name);
            if (f.isFile()) {
                f.setExecutable(true, false);
                f.setReadable(true, false);
            }
        }
    }
}
