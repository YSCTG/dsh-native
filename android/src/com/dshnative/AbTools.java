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
            "https://github.com/YSCTG/dsh-native/releases/download/v32.8/abt.zip",
            "https://gh-proxy.org/https://github.com/YSCTG/dsh-native/releases/download/v32.8/abt.zip",
    };
    private static final long EXPECT_SIZE = 17353645L;   // 约 16.5 MB

    private AbTools() {}

    public static File dir(Context c) { return new File(c.getFilesDir(), "abt"); }

    public static boolean ready(Context c) {
        File d = dir(c);
        return new File(d, "aapt2").isFile() && new File(d, "ecj-dex.jar").isFile();
    }

    /** 确保工具链就位；返回 null 表示成功，否则返回错误说明。 */
    public static String ensure(Context c) {
        File d = dir(c);
        if (ready(c)) return null;
        try {
            if (!d.isDirectory()) d.mkdirs();
            File zip = new File(c.getFilesDir(), ZIP);
            if (!zip.isFile() || zip.length() < EXPECT_SIZE / 2) {
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

    private static void chmod(File dir) {
        String[] exes = {"aapt2", "zipalign"};
        for (String name : exes) {
            File f = new File(dir, name);
            if (f.isFile()) {
                f.setExecutable(true, false);
                f.setReadable(true, false);
            }
        }
    }
}
