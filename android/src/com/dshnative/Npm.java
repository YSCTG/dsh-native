package com.dshnative;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 npm 安装器。
 *
 * 手机上有没有 npm 命令并不重要——registry 就是一个 HTTP 接口：
 *   GET https://registry.npmjs.org/<pkg>       → 元数据（含 dist.tarball）
 *   GET <tarball>                              → .tgz
 * 下载后交给内置 Python 的 tarfile 解开（stdlib 就有），落到工作区 node_modules/。
 *
 * 这样 PixiJS / Phaser / TypeScript 这些纯 JS 生态就能在手机上用了。
 * 不做依赖树解析（不跟随 dependencies），装什么解什么——够用且可预期。
 */
public final class Npm {

    private static final String TAG = "DSHN";
    private static final String REGISTRY = "https://registry.npmjs.org/";

    private Npm() {}

    private static File cacheDir(Context c) {
        File d = new File(c.getFilesDir(), "npm-cache");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 装一个包到指定工作区。返回给人看的结果。 */
    public static String install(Context c, String workspace, String pkg, String version) {
        if (pkg == null || pkg.trim().isEmpty()) return "错误：包名为空";
        pkg = pkg.trim();
        try {
            JSONObject meta = fetchJson(REGISTRY + urlPath(pkg) + (version == null || version.isEmpty()
                    ? "/latest" : "/" + version));
            if (meta == null) return "错误：registry 里没有这个包（或网络不通）：" + pkg;

            String ver = meta.optString("version", "");
            JSONObject dist = meta.optJSONObject("dist");
            String tarball = dist == null ? "" : dist.optString("tarball", "");
            if (tarball.isEmpty()) return "错误：这个版本没有 tarball 地址";

            File tgz = new File(cacheDir(c), pkg.replace('/', '_') + "-" + ver + ".tgz");
            if (!tgz.isFile() || tgz.length() < 1024) {
                if (!download(tarball, tgz)) return "错误：下载失败 " + tarball;
            }
            Log.i(TAG, "npm 下载完成 " + tgz.getName() + " " + (tgz.length() / 1024) + " KB");

            File ws = workspace == null || workspace.isEmpty()
                    ? new File(c.getFilesDir(), "workspace") : new File(workspace);
            File nodeModules = new File(ws, "node_modules");
            File dest = new File(nodeModules, pkg);
            if (!dest.isDirectory()) dest.mkdirs();

            // 交给 Python 的 tarfile 解包（package/ 前缀要剥掉）
            String py = "import tarfile, os, sys\n"
                    + "src = r'''" + tgz.getAbsolutePath() + "'''\n"
                    + "dst = r'''" + dest.getAbsolutePath() + "'''\n"
                    + "n = 0\n"
                    + "with tarfile.open(src, 'r:gz') as t:\n"
                    + "    for m in t.getmembers():\n"
                    + "        if not m.name.startswith('package/'):\n"
                    + "            continue\n"
                    + "        m.name = m.name[len('package/'):]\n"
                    + "        if not m.name:\n"
                    + "            continue\n"
                    + "        t.extract(m, dst)\n"
                    + "        n += 1\n"
                    + "print(n)\n";
            Python.Result r = Python.run(c, py, 60000);
            String out = (r.out == null ? "" : r.out.trim());
            if (r.code != 0) return "错误：解包失败 " + out;

            JSONObject pkgJson = readJson(new File(dest, "package.json"));
            String main = pkgJson == null ? "" : pkgJson.optString("main", "");
            String module = pkgJson == null ? "" : pkgJson.optString("module", "");
            StringBuilder sb = new StringBuilder();
            sb.append("已安装 ").append(pkg).append("@").append(ver);
            sb.append("（").append(out).append(" 个文件）→ ").append(dest.getAbsolutePath());
            if (!module.isEmpty()) sb.append("\nESM 入口：").append(new File(dest, module).getAbsolutePath());
            else if (!main.isEmpty()) sb.append("\n入口：").append(new File(dest, main).getAbsolutePath());
            return sb.toString();
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    private static String urlPath(String pkg) {
        // 作用域包 @a/b → @a%2Fb
        return pkg.replace("/", "%2F");
    }

    private static JSONObject fetchJson(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "DSH-Native");
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(read(c.getInputStream()));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean download(String url, File out) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "DSH-Native");
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            FileOutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
            return out.length() > 1024;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject readJson(File f) {
        try {
            if (!f.isFile()) return null;
            return new JSONObject(read(new java.io.FileInputStream(f)));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }
}
