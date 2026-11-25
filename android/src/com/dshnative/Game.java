package com.dshnative;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 手机端做游戏的两件事：生成骨架、打成单文件。
 *
 * 脚手架刻意**不依赖任何 npm 包**（纯 canvas 2D + 回合制）：
 * 生成即能玩，打包成单文件时也没有外部依赖。
 * 想换 PixiJS/Phaser 只改渲染层，地图/怪物/战斗那套逻辑能原样留用。
 */
public final class Game {

    private static final String TAG = "DSHN";
    private static final String[] FILES = {"index.html", "game.js", "README.md"};

    private Game() {}

    /** 在工作区 games/<name>/ 下生成骨架。 */
    public static String scaffold(Context c, String workspace, String name) {
        if (name == null || name.trim().isEmpty()) name = "game";
        name = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        try {
            File ws = (workspace == null || workspace.isEmpty())
                    ? new File(c.getFilesDir(), "workspace") : new File(workspace);
            File dir = new File(new File(ws, "games"), name);
            if (!dir.isDirectory()) dir.mkdirs();

            int copied = 0;
            // 从 assets/scaffold.zip 读（顶层文件，和 tools.zip 同一条可靠路径）。
            // 直接读 assets/scaffold/ 子目录在这台设备上 list() 返回空、open() 抛
            // FileNotFoundException，而包内确实有这些文件——不在这个坑里纠缠。
            java.util.Map<String, String> files = readScaffoldZip(c);
            for (String f : FILES) {
                String body = files.get(f);
                if (body == null) continue;
                FileOutputStream os = new FileOutputStream(new File(dir, f));
                os.write(body.getBytes("UTF-8"));
                os.close();
                copied++;
            }
            if (copied == 0) {
                return "错误：scaffold.zip 里没读到文件（entries=" + files.keySet() + "）";
            }

            Log.i(TAG, "游戏骨架已生成 " + dir.getAbsolutePath());
            return "已生成游戏骨架：" + dir.getAbsolutePath()
                    + "\n入口：" + new File(dir, "index.html").getAbsolutePath()
                    + "\n（用 browser_open 打开入口就能玩；改 game.js 即可）";
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    /**
     * 打成单文件 HTML：把同目录下所有 .js 内联进 index.html。
     * 这样发一个 .html 就能玩，不需要带 node_modules 或本地服务器。
     */
    public static String buildSingle(String workspace, String name) {
        try {
            File dir = new File(new File(workspace, "games"), name);
            File index = new File(dir, "index.html");
            if (!index.isFile()) return "错误：找不到 " + index.getAbsolutePath();
            String html = read(index);

            // 逐个把 <script src="x.js"></script> 换成就地内联
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<script\\s+src=\"([^\"]+\\.js)\"\\s*></script>")
                    .matcher(html);
            StringBuffer sb = new StringBuffer();
            int inlined = 0;
            while (m.find()) {
                File js = new File(dir, m.group(1));
                if (!js.isFile()) continue;
                String code = read(js).replace("</script>", "<\\/script>");
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        "<script>\n" + code + "\n</script>"));
                inlined++;
            }
            m.appendTail(sb);

            File out = new File(dir, name + ".single.html");
            FileOutputStream os = new FileOutputStream(out);
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
            return "已打成单文件：" + out.getAbsolutePath()
                    + "（内联 " + inlined + " 个脚本，" + (out.length() / 1024) + " KB）";
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    // ---------- io ----------

    /** 把 scaffold.zip 读成 文件名 → 内容。 */
    private static java.util.Map<String, String> readScaffoldZip(Context c) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            java.io.InputStream raw = c.getAssets().open("scaffold.zip");
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(raw);
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                out.put(name, bo.toString("UTF-8"));
            }
            zin.close();
        } catch (Throwable t) {
            Log.i(TAG, "读 scaffold.zip 失败: " + t);
        }
        return out;
    }

    private static String readAsset(Context c, String path) {
        try {
            InputStream in = c.getAssets().open(path);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }
}
