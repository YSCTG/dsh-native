package com.dshnative;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

/**
 * 内置命令行工具：git / curl / jq / node（来自 Termux 的 aarch64 构建 + 全部依赖库）。
 *
 * 为什么打成 assets 而不是放 lib/：
 *  只有 4 个可执行文件，依赖库有 20 多个；放在 assets 里解到 filesDir/tools 更省事，
 *  二进制通过 shell 执行时用绝对路径即可（脚本由 /system/bin/sh 读，不需要 execve 权限）。
 *
 * 软链：git 的 libexec/git-core 里 200 多个名字其实是同一份二进制（deb 里是硬链接），
 *  打包时去重成"存一份 + 一张 link 表"，解压后用 sh 建软链，节省 500 MB。
 */
public final class Tools {

    private static final String MARKER = ".ready-5";   // 加 wrapper 脚本，强制重解
    private static volatile String toolsPath;
    private static volatile boolean configured;

    private Tools() {}

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "tools");
    }

    public static boolean ready(Context c) {
        return new File(dir(c), MARKER).isFile();
    }

    /** 解压工具链。幂等；失败返回错误文本。 */
    public static synchronized String ensure(Context c) {
        if (ready(c)) return null;
        File root = dir(c);
        if (!root.isDirectory() && !root.mkdirs()) return "无法创建 " + root;
        try {
            InputStream raw = c.getAssets().open("tools.zip");
            ZipInputStream zis = new ZipInputStream(raw);
            ZipEntry e;
            byte[] buf = new byte[16384];
            String linksJson = null;
            while ((e = zis.getNextEntry()) != null) {
                if ("_links.json".equals(e.getName())) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    int n0;
                    while ((n0 = zis.read(buf)) > 0) bo.write(buf, 0, n0);
                    linksJson = bo.toString("UTF-8");
                    zis.closeEntry();
                    continue;
                }
                File out = new File(root, e.getName());
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                zis.closeEntry();
            }
            zis.close();

            // 用 sh 批量建软链：Java 没有直接的 symlink API（API 26+ 才能用 Os）
            if (linksJson != null) {
                JSONObject links = new JSONObject(linksJson);
                StringBuilder script = new StringBuilder();
                Iterator<String> it = links.keys();
                while (it.hasNext()) {
                    String link = it.next();
                    String target = links.optString(link);
                    if (link.isEmpty() || target.isEmpty()) continue;
                    File lf = new File(root, link);
                    File tf = new File(target);
                    if (!tf.isAbsolute()) {
                        // deb 里的软链是相对链接所在目录的（lib/libz.so.1 -> libz.so.1.4）
                        File base = lf.getParentFile();
                        tf = new File(base == null ? root : base, target);
                    }
                    script.append("ln -sf '").append(esc(tf.getAbsolutePath())).append("' '")
                          .append(esc(lf.getAbsolutePath())).append("'\n");
                }
                File sh = new File(root, "_links.sh");
                FileOutputStream os = new FileOutputStream(sh);
                os.write(script.toString().getBytes("UTF-8"));
                os.close();
                Shell.Res r = Shell.runIn(root.getAbsolutePath(), "sh _links.sh", 60000);
                //noinspection ResultOfMethodCallIgnored
                sh.delete();
                if (r.code != 0) return "建软链失败：" + r.out;
            }

            // Java 写出的文件默认 0600，没有可执行位就 execve 不了
            makeWrappers(root);
            markExecutable(new File(root, "bin"));
            markExecutable(new File(root, "libexec"));
            markExecutable(new File(root, "wrap"));

            FileOutputStream m = new FileOutputStream(new File(root, MARKER));
            m.write("ok".getBytes());
            m.close();
            return null;
        } catch (Exception ex) {
            return "解压工具链失败：" + ex.getMessage();
        }
    }

    /**
     * 每个工具一个 wrapper：只在自己的进程里设 LD_LIBRARY_PATH。
     * 全局设会把系统二进制（screencap/uiautomator）的库解析带偏，报 CANNOT LINK EXECUTABLE。
     */
    private static void makeWrappers(File root) {
        File wrap = new File(root, "wrap");
        //noinspection ResultOfMethodCallIgnored
        wrap.mkdirs();
        File binDir = new File(root, "bin");
        String[] names = binDir.list();
        if (names == null) return;
        for (String n : names) {
            File real = new File(binDir, n);
            if (!real.isFile()) continue;
            String script = "#!/system/bin/sh\n"
                    + "D='" + root.getAbsolutePath() + "'\n"
                    + "LD_LIBRARY_PATH=\"$D/lib\" exec \"$D/bin/" + n + "\" \"$@\"\n";
            try {
                File w = new File(wrap, n);
                FileOutputStream os = new FileOutputStream(w);
                os.write(script.getBytes("UTF-8"));
                os.close();
                //noinspection ResultOfMethodCallIgnored
                w.setExecutable(true, false);
            } catch (Exception ignored) {}
        }
    }
    private static void markExecutable(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) markExecutable(k);
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        }
    }

    private static String esc(String s) {
        return s.replace("'", "'\\''");
    }

    /** 让子进程能找到这些工具。没有工具链时什么也不做。 */
    public static void apply(Context c, ProcessBuilder pb) {
        if (!ready(c)) return;
        File root = dir(c);
        Map<String, String> env = pb.environment();
        String bin = new File(root, "wrap").getAbsolutePath();
        String lib = new File(root, "lib").getAbsolutePath();
        String old = env.get("PATH");
        env.put("PATH", bin + ":/system/bin:/system/xbin" + (old == null ? "" : ":" + old));
        // 不给整个 shell 设 LD_LIBRARY_PATH：系统二进制（screencap、uiautomator…）
        // 会去加载我们的 libcrypto/libz，直接 CANNOT LINK EXECUTABLE。
        // 每个工具改用 bin/ 里的一个 wrapper，只在自己进程里注入库路径。
        env.put("GIT_EXEC_PATH", new File(root, "libexec/git-core").getAbsolutePath());
        // 这个 git 编译时把模板目录写死成 termux 的前缀，指回来
        env.put("GIT_TEMPLATE_DIR", new File(root, "share/git-core/templates").getAbsolutePath());
        env.put("HOME", new File(root, "home").getAbsolutePath());
        env.put("TMPDIR", new File(root, "tmp").getAbsolutePath());
        env.put("TERM", "dumb");
        // GitHub 凭据：gh 认 GH_TOKEN / GITHUB_TOKEN
        String token = Prefs.get(c).getString("github_token", "");
        if (token != null && !token.isEmpty()) {
            env.put("GH_TOKEN", token);
            env.put("GITHUB_TOKEN", token);
            env.put("GH_NO_UPDATE_NOTIFIER", "1");
        }
        File cert = new File(root, "etc/tls/cert.pem");
        if (cert.isFile()) env.put("SSL_CERT_FILE", cert.getAbsolutePath());
        //noinspection ResultOfMethodCallIgnored
        new File(root, "home").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "tmp").mkdirs();
        toolsPath = root.getAbsolutePath();
        configured = true;
    }

    public static boolean configured() {
        return configured;
    }

    public static String path() {
        return toolsPath;
    }

    /** 给模型看的清单：哪些工具现在真的能用。 */
    public static List<String> inventory(Context c) {
        List<String> out = new ArrayList<>();
        if (!ready(c)) return out;
        File bin = new File(dir(c), "bin");
        for (String name : new String[]{"git", "gh", "curl", "jq", "node", "less"}) {
            if (new File(bin, name).isFile()) out.add(name);
        }
        return out;
    }
}
