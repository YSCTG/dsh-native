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
        public final String source;   // 从哪个地址查到的，出错时也能说清"更新到哪查的"
        public java.util.List<String> mirrors = new java.util.ArrayList<>();  // 清单里给的备用下载地址

        Info(String tag, String apkUrl, long size, String notes, String source) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.size = size;
            this.notes = notes;
            this.source = source;
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


    /**
     * 版本清单的多个取法。
     *
     * 以前只连 api.github.com 查版本——国内经常根本连不上，于是"检查更新"直接报查不到，
     * 用户既不知道更新到哪查的，也没法更新。现在按顺序试这些地址，谁通用谁，
     * 并把**实际用到的来源**回传给界面显示。
     */
    private static final String[] MANIFESTS = {
            "https://cdn.jsdelivr.net/gh/YSCTG/dsh-native@master/update.json",
            "https://raw.githubusercontent.com/YSCTG/dsh-native/master/update.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/YSCTG/dsh-native/master/update.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/YSCTG/dsh-native/master/update.json",
    };

    /** 上次检查时每个源的结果，失败时给用户看，避免"不知道为什么不行"。 */
    public static volatile String lastDiagnostic = "";

    public static Info latestFromManifest() {
        StringBuilder diag = new StringBuilder();
        Info best = null;
        for (String u : MANIFESTS) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "DSH-Native");
                int code = conn.getResponseCode();
                if (code != 200) { diag.append(host(u)).append(" → HTTP ").append(code).append("\n"); continue; }
                JSONObject o = new JSONObject(read(conn.getInputStream()));
                String ver = o.optString("version", "");
                String apk = o.optString("apk", "");
                if (ver.isEmpty() || apk.isEmpty()) { diag.append(host(u)).append(" → 字段缺失\n"); continue; }
                Info one = new Info("v" + ver.replace("v", ""), apk, o.optLong("size", 0),
                        o.optString("notes", ""), host(u));
                // 清单里可以带 apk_mirrors（数组），国内可达的地址放前面
                org.json.JSONArray mir = o.optJSONArray("apk_mirrors");
                for (int i = 0; mir != null && i < mir.length(); i++) {
                    String m = mir.optString(i, "");
                    if (!m.isEmpty()) one.mirrors.add(m);
                }
                // CDN 会缓存清单（raw/jsDelivr 都可能滞后几分钟），所以不能"谁先通用谁"——
                // 取所有源里版本最高的那个，才不会因为读到旧清单而误判"已是最新"。
                if (best == null || isNewer(one.tag, best.tag)) best = one;
                diag.append(host(u)).append(" → ").append(one.tag).append("\n");
            } catch (Throwable e) {
                diag.append(host(u)).append(" → ").append(e.getClass().getSimpleName()).append("\n");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        lastDiagnostic = diag.toString();
        return best;
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    /** 查最新版本：先试清单源，再回退到 GitHub API。 */
    public static Info latest() {
        Info m = latestFromManifest();
        if (m != null) return m;
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
            return new Info(tag, url, size, notes, API);
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
        // 1) 清单里指定的下载地址（可放国内对象存储，优先）
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (info.mirrors != null) urls.addAll(info.mirrors);
        urls.add(info.apkUrl);                       // 2) 主地址
        for (String m : MIRRORS) if (!m.isEmpty()) urls.add(m + info.apkUrl);   // 3) 内置代理兜底

        for (String url : urls) {
            try {
                android.util.Log.i("DSHN", "更新 尝试下载 " + url);
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
        // 直连常被阻断：读超时压短，才能快点换镜像；真慢速下载时反复重试总比干等 3 分钟好
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(35000);
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
