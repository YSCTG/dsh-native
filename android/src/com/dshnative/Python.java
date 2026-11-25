package com.dshnative;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 内置 Python 3.14（aarch64）。
 *
 * 为什么这么放：
 *  - 解释器和 libpython 放在 APK 的 lib/arm64-v8a/ 下，安装时被解到 nativeLibraryDir。
 *    那是应用私有目录里**唯一允许 execve** 的地方（dataDir 在 targetSdk>=29 上被 W^X 拦住）。
 *    文件名必须是 lib*.so 才会被解出来，所以解释器叫 libpyrun.so。
 *  - 标准库太大（15.8 MB），压进 assets/py_stdlib.zip，首次调用时解到 filesDir/py/lib/python3.14。
 *
 * 来源：Termux 的 python 3.14.10 + libandroid-support（都是 aarch64 Android 构建，bionic 兼容）。
 */
public final class Python {

    private static final String MARKER = ".ready-2";   // 加依赖库了，改版本号强制重解

    public static final class Result {
        public final int code;
        public final String out;
        public final long ms;

        Result(int code, String out, long ms) {
            this.code = code;
            this.out = out;
            this.ms = ms;
        }
    }

    private Python() {}

    public static File home(Context c) {
        return new File(c.getFilesDir(), "py");
    }

    private static File bin(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libpyrun.so");
    }

    public static boolean available(Context c) {
        return bin(c).isFile();
    }

    public static boolean installed(Context c) {
        return new File(home(c), MARKER).isFile();
    }

    /** 首次调用时把标准库解出来。失败返回错误文本，成功返回 null。 */
    public static synchronized String ensure(Context c) {
        if (installed(c)) return null;
        if (!available(c)) return "内置 Python 不可用（设备 ABI 不是 arm64-v8a）";
        File root = home(c);
        File lib = new File(root, "lib/python3.14");
        if (!lib.isDirectory() && !lib.mkdirs()) return "无法创建 " + lib;
        try {
            InputStream raw = c.getAssets().open("py_stdlib.zip");
            ZipInputStream zis = new ZipInputStream(raw);
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(lib, e.getName());
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                FileOutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                zis.closeEntry();
            }
            zis.close();

            // 第三方依赖库（libz.so.1 / libsqlite3.so）：放 home/lib，靠 LD_LIBRARY_PATH 找到。
            // dlopen 读 dataDir 是允许的，被拦的只有 execve。
            InputStream lraw = c.getAssets().open("py_libs.zip");
            ZipInputStream lzip = new ZipInputStream(lraw);
            while ((e = lzip.getNextEntry()) != null) {
                File out = new File(root, "lib/" + e.getName());
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                FileOutputStream os2 = new FileOutputStream(out);
                int n2;
                while ((n2 = lzip.read(buf)) > 0) os2.write(buf, 0, n2);
                os2.close();
                lzip.closeEntry();
            }
            lzip.close();

            File tmp = new File(root, "tmp");
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
            FileOutputStream m = new FileOutputStream(new File(root, MARKER));
            m.write("ok".getBytes());
            m.close();
            return null;
        } catch (Exception ex) {
            return "解压标准库失败：" + ex.getMessage();
        }
    }

    /** 跑一段代码。首次调用会先解标准库（约 15 MB，一两秒）。 */

    /**
     * 生成"以我们的 Python 跑某个脚本"的完整 shell 命令（含环境变量）。
     * MCP 插件要常驻一个 stdio 服务，复用同一套环境，避免两处漂移。
     */
    public static String commandFor(Context c, File script) {
        String nativeDir = c.getApplicationInfo().nativeLibraryDir;
        StringBuilder ld = new StringBuilder(nativeDir);
        ld.append(':').append(new File(home(c), "lib").getAbsolutePath());
        if (Tools.ready(c)) ld.append(':').append(new File(Tools.dir(c), "lib").getAbsolutePath());
        StringBuilder cmd = new StringBuilder();
        cmd.append("LD_LIBRARY_PATH='").append(ld).append("' ");
        cmd.append("PYTHONHOME='").append(home(c).getAbsolutePath()).append("' ");
        cmd.append("PYTHONPATH='").append(new File(home(c), "lib/python3.14").getAbsolutePath()).append("' ");
        cmd.append("PYTHONIOENCODING=utf-8 PYTHONDONTWRITEBYTECODE=1 ");
        cmd.append("TMPDIR='").append(new File(home(c), "tmp").getAbsolutePath()).append("' ");
        if (Tools.ready(c)) {
            cmd.append("PATH='").append(new File(Tools.dir(c), "wrap").getAbsolutePath())
               .append(":/system/bin:/system/xbin' ");
            cmd.append("GIT_EXEC_PATH='")
               .append(new File(Tools.dir(c), "libexec/git-core").getAbsolutePath()).append("' ");
        }
        cmd.append("exec '").append(bin(c).getAbsolutePath()).append("' '")
           .append(script.getAbsolutePath()).append("'");
        return cmd.toString();
    }

    public static Result run(Context c, String code, int timeoutMs) {
        String err = ensure(c);
        if (err != null) return new Result(-1, err, 0);
        long t0 = System.currentTimeMillis();
        File script = new File(home(c), "tmp/run_" + System.currentTimeMillis() + ".py");
        try {
            FileOutputStream os = new FileOutputStream(script);
            os.write(code.getBytes("UTF-8"));
            os.close();

            // 关键：不要用 pb.environment() 设 LD_LIBRARY_PATH。
            // 在 Android 上那个 Map 就是本进程自己的 environ，改了会污染**整个 App 进程**，
            // 之后任何系统二进制（screencap / uiautomator）都会去加载我们的 libcrypto 而崩。
            // 改成在 sh 命令行里内联，只影响这一条命令。
            String nativeDir = c.getApplicationInfo().nativeLibraryDir;
            StringBuilder ld = new StringBuilder(nativeDir);
            ld.append(':').append(new File(home(c), "lib").getAbsolutePath());
            if (Tools.ready(c)) ld.append(':').append(new File(Tools.dir(c), "lib").getAbsolutePath());

            StringBuilder cmd = new StringBuilder();
            cmd.append("LD_LIBRARY_PATH='").append(ld).append("' ");
            cmd.append("PYTHONHOME='").append(home(c).getAbsolutePath()).append("' ");
            cmd.append("PYTHONPATH='").append(new File(home(c), "lib/python3.14").getAbsolutePath()).append("' ");
            cmd.append("PYTHONIOENCODING=utf-8 PYTHONDONTWRITEBYTECODE=1 ");
            cmd.append("TMPDIR='").append(new File(home(c), "tmp").getAbsolutePath()).append("' ");
            if (Tools.ready(c)) {
                cmd.append("PATH='").append(new File(Tools.dir(c), "wrap").getAbsolutePath())
                   .append(":/system/bin:/system/xbin' ");
                cmd.append("GIT_EXEC_PATH='")
                   .append(new File(Tools.dir(c), "libexec/git-core").getAbsolutePath()).append("' ");
            }
            cmd.append("exec '").append(bin(c).getAbsolutePath()).append("' '")
               .append(script.getAbsolutePath()).append("'");

            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd.toString());
            pb.redirectErrorStream(true);

            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        InputStream in = p.getInputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (sb.length() < 24000) sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                    } catch (Exception ignored) {}
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = waitFor(p, timeoutMs);
            if (!done) {
                p.destroyForcibly();
                reader.join(300);
                return new Result(-1, sb + "\n…超时 " + (timeoutMs / 1000) + "s，已终止", System.currentTimeMillis() - t0);
            }
            reader.join(800);
            return new Result(p.exitValue(), sb.toString(), System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            return new Result(-1, "运行失败：" + ex.getMessage(), System.currentTimeMillis() - t0);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            script.delete();
        }
    }

    private static boolean waitFor(Process p, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }
}
