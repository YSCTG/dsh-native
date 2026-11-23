package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自动更新：查 GitHub Releases、下载 APK、交给系统安装。
 *
 * 为什么自己写：这个项目没有 Gradle，也就没有插件商店的更新通道。
 * 直接用 GitHub 的公开 API 就够，不需要服务端。
 *
 * 安装这一步有个坑：从 Android 7 起不能把 file:// 交给安装器。
 * 所以复用 FileProviderX 生成 content:// 再带读权限传过去。
 */
public final class Updater {

    private static final String API =
            "https://api.github.com/repos/YSCTG/dsh-native/releases/latest";

    /** 一次查到的结果。 */
    public static final class Info {
        public final String tag;
        public final String apkUrl;
        public final long size;
        public final String notes;

        Info(String tag, String apkUrl, long size, String notes) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.size = size;
            this.notes = notes;
        }
    }

    private Updater() {}

    public static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "0";
        }
    }

    /** 查最新版本；网络或解析失败返回 null。 */
    public static Info latest() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "DSH-Native");
            if (conn.getResponseCode() != 200) return null;
            String body = read(conn.getInputStream());
            JSONObject o = new JSONObject(body);
            String tag = o.optString("tag_name", "");
            String notes = o.optString("body", "");
            JSONArray assets = o.optJSONArray("assets");
            String url = null;
            long size = 0;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                if (!a.optString("name", "").endsWith(".apk")) continue;
                url = a.optString("browser_download_url", "");
                size = a.optLong("size", 0);
                break;
            }
            if (tag.isEmpty() || url == null || url.isEmpty()) return null;
            return new Info(tag, url, size, notes);
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 版本号比较：1.10 > 1.9。返回 true 表示 remote 更新。 */
    public static boolean isNewer(String remote, String local) {
        String[] a = strip(remote).split("\\.");
        String[] b = strip(local).split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static String strip(String v) {
        if (v == null) return "0";
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s.isEmpty() ? "0" : s;
    }

    private static int num(String s) {
        try {
            StringBuilder d = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) d.append(c);
                else break;
            }
            return d.length() == 0 ? 0 : Integer.parseInt(d.toString());
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 下载镜像。直连 github.com 在国内经常被阻断（实测 connect timeout），
     * 所以直连失败就依次换镜像重试；镜像只是前缀，原始 URL 拼在后面。
     */
    private static final String[] MIRRORS = {
            "",
            "https://ghproxy.net/",
            "https://gh-proxy.com/",
    };

    /** 下载 APK 到缓存目录，直连不通就换镜像。 */
    public static File download(Context c, Info info, Progress p) throws Exception {
        Exception last = null;
        for (String prefix : MIRRORS) {
            try {
                String url = prefix.isEmpty() ? info.apkUrl : prefix + info.apkUrl;
                android.util.Log.i("DSHN", "更新 尝试下载 " + (prefix.isEmpty() ? "直连" : prefix));
                return fetch(c, url, info, p);
            } catch (Exception e) {
                last = e;
                android.util.Log.i("DSHN", "更新 该源失败: " + e);
            }
        }
        throw last == null ? new Exception("全部下载源都失败") : last;
    }

    private static File fetch(Context c, String srcUrl, Info info, Progress p) throws Exception {
        File out = new File(c.getCacheDir(), "update-" + info.tag + ".apk");
        if (out.isFile() && info.size > 0 && out.length() == info.size) return out;
        HttpURLConnection conn = (HttpURLConnection) new URL(srcUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(180000);
        conn.setRequestProperty("User-Agent", "DSH-Native");
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        FileOutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            got += n;
            if (p != null) p.onProgress(got, info.size);
        }
        os.close();
        in.close();
        conn.disconnect();
        return out;
    }

    /** 交给系统安装器。 */
    public static void install(Activity a, File apk) {
        Uri uri = FileProviderX.uriFor(a, apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    /** 有没有"安装未知应用"的权限。 */
    public static boolean canInstall(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return c.getPackageManager().canRequestPackageInstalls();
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    public static void openInstallPermission(Activity a) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** 下载进度回调。 */
    public interface Progress {
        void onProgress(long got, long total);
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
