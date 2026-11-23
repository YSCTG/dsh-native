package com.dshnative;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 极小的 shell 执行器。
 *
 * 设计要点：root 不是一个"开关"，它是兑现某个能力的一种档位。
 * P0 只用它来兑现「工作区之外的文件访问」和「解锁后的 sh 工具」。
 */
public final class Shell {

    public static final class Res {
        public final int code;
        public final String out;
        public final long ms;
        public Res(int code, String out, long ms) { this.code = code; this.out = out; this.ms = ms; }
    }

    private Shell() {}

    /** 正在跑的子进程：取消时要能立刻杀掉，否则停止按钮看起来是死的。 */
    private static volatile Process current;

    public static void killCurrent() {
        Process p = current;
        current = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /** 探测 root：跑 su -c id，看 uid=0。超时按无 root 处理，绝不阻塞超过 3 秒。 */
    public static boolean hasRoot() {
        Res r = run("su", "id", 3000);
        return r.code == 0 && r.out.contains("uid=0");
    }

    /** 以 root 身份执行；调用方必须已经过策略确认。 */
    public static Res runRoot(String command, long timeoutMs) {
        return run("su", command, timeoutMs);
    }

    /** 以 app 身份执行（无 root 时的降级路径）。 */
    public static Res runApp(String command, long timeoutMs) {
        return run(null, command, timeoutMs);
    }

    /** 指定的工作目录 + 环境（内置工具链的 PATH 由此注入）。 */
    public static Res runIn(String workdir, String command, long timeoutMs) {
        long t0 = System.nanoTime();
        try {
            List<String> argv = new ArrayList<>();
            argv.add("/system/bin/sh");
            argv.add("-c");
            argv.add(command);
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (workdir != null) pb.directory(new java.io.File(workdir));
            applyEnv(pb);
            Process p = pb.start();
            current = p;
            String out = readAll(p.getInputStream()) + readAll(p.getErrorStream());
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            return new Res(p.exitValue(), out, (System.nanoTime() - t0) / 1000000L);
        } catch (Exception e) {
            return new Res(-1, "启动失败：" + e.getMessage(), (System.nanoTime() - t0) / 1000000L);
        }
    }

    private static volatile Context appCtx;

    public static void attach(Context c) { appCtx = c.getApplicationContext(); }

    /** 每次起子进程都调用：工具链装好后会补上 PATH / LD_LIBRARY_PATH / GIT_EXEC_PATH。 */
    public static void applyEnv(ProcessBuilder pb) {
        Context c = appCtx;
        if (c != null) Tools.apply(c, pb);
    }

    /**
     * suWrapper 为 null 时用 /system/bin/sh，否则用 su -c。
     * 关键：command 作为单个 argv 传入，不拼接字符串，避免引号逃逸。
     */
    private static Res run(String suWrapper, String command, long timeoutMs) {
        long t0 = System.nanoTime();
        Process p = null;
        try {
            List<String> argv = new ArrayList<>();
            if (suWrapper != null) {
                argv.add("su");
                argv.add("-c");
                argv.add(command);
                p = new ProcessBuilder(argv).redirectErrorStream(false).start();
            } else {
                argv.add("/system/bin/sh");
                argv.add("-c");
                argv.add(command);
                ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(false);
                applyEnv(pb);
                p = pb.start();
            }

            final Process fp = p;
            final StringBuilder err = new StringBuilder();
            Thread tErr = new Thread(new Runnable() {
                @Override public void run() {
                    try { err.append(readAll(fp.getErrorStream())); } catch (Exception ignored) {}
                }
            });
            tErr.setDaemon(true);
            tErr.start();

            String out = readAll(p.getInputStream());
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            current = null;
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (!done) {
                p.destroy();
                return new Res(124, out + "\n[超时 " + timeoutMs + "ms，已终止]", ms);
            }
            tErr.join(500);
            String combined = out + (err.length() > 0 ? "\n" + err : "");
            return new Res(p.exitValue(), combined, ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new Res(-1, msg, ms);
        } finally {
            if (p != null) { try { p.destroy(); } catch (Exception ignored) {} }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 仅供调试：把 writer 的字节塞进进程（P0 未使用）。 */
    static void write(OutputStream os, String s) throws Exception {
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
