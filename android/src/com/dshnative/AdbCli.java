package com.dshnative;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备内 adb：让本机拿到 <b>shell 身份（uid 2000）</b>，而不是 app 身份。
 *
 * 为什么可行（都实测过）：Termux 的 {@code android-tools} 里有 aarch64 的 adb，
 * 连带 libc++ 等库一起打包后能在设备上运行；adbd 打开 TCP 后，设备内的 adb 客户端
 * 连 {@code 127.0.0.1:<port>} 就能把自己当设备操作，拿到 shell。
 *
 * 前提与边界（诚实说清）：
 *   · 需要 adbd 在 TCP 上监听。要么插电脑执行一次 {@code adb tcpip 5555}，
 *     要么在开发者选项里打开「无线调试」并配对（Android 11+）。
 *   · 拿到的仍是 shell，不是 root：读不了别的应用私有目录。
 *   · 一旦接通，调用方（模型）执行的命令就是 shell 身份 —— 所以默认关闭，
 *     由用户在设置里显式开启，且每次调用都进审计表。
 */
public final class AdbCli {

    private static final String TAG = "DSHN";

    private AdbCli() {}

    private static File bin(Context c) { return new File(new File(AbTools.dir(c), "adb"), "adb"); }
    private static File lib(Context c) { return new File(new File(AbTools.dir(c), "adb"), "lib"); }
    private static File home(Context c) {
        File h = new File(c.getFilesDir(), "adbhome");
        File a = new File(h, ".android");
        if (!a.isDirectory()) a.mkdirs();
        return h;
    }

    /** 二进制在不在（工具链已下载并解压）。 */
    public static boolean installed(Context c) {
        return bin(c).isFile();
    }

    /** 开关是否打开（默认关闭）。 */
    public static boolean enabled(Context c) {
        return Prefs.get(c).getBoolean("adb_shell_enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("adb_shell_enabled", on).apply();
    }

    /** 已连上的设备（从 adb devices 里取，只认 127.0.0.1/localhost 或我们记录过的串口）。 */
    public static String serial(Context c) {
        String out = run(c, new String[]{"devices"}, 8000);
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.endsWith("device") || t.startsWith("List of")) continue;
            String s = t.substring(0, t.length() - "device".length()).trim();
            if (s.isEmpty() || s.startsWith("*")) continue;
            return s;
        }
        return "";
    }

    public static boolean connected(Context c) {
        return !serial(c).isEmpty();
    }

    /** 状态一行字，给设置界面用。 */
    public static String status(Context c) {
        if (!installed(c)) return "工具链未下载（首次构建或使用时会自动下载）";
        if (!enabled(c)) return "未启用";
        String s = serial(c);
        if (s.isEmpty()) return "已启用，但还没有连上（按提示配对或插电脑执行 adb tcpip）";
        return "已连接：" + s + "（shell 身份）";
    }

    /** 配对：hostPort 形如 192.168.1.5:37123，code 是设置页显示的 6 位码。 */
    public static String pair(Context c, String hostPort, String code) {
        String err = AbTools.ensure(c);
        if (err != null) return err;
        String out = run(c, new String[]{"pair", hostPort, code}, 30000);
        Log.i(TAG, "adb pair " + hostPort + " → " + out.replace("\n", " "));
        Audit.record(c, "adb", "配对", hostPort + " · " + oneLine(out));
        return out.isEmpty() ? "无输出（可能已配对）" : out;
    }

    /** 连接某个 端口/主机；不传则连 127.0.0.1:5555。 */
    public static String connect(Context c, String hostPort) {
        String err = AbTools.ensure(c);
        if (err != null) return err;
        String target = (hostPort == null || hostPort.trim().isEmpty())
                ? "127.0.0.1:5555" : hostPort.trim();
        String out = run(c, new String[]{"connect", target}, 20000);
        if (out.isEmpty()) {
            return "（无输出）adb 客户端没吭声：检查 files/abt/adb/adb 是否可执行、"
                    + "以及 adbd 是否在 " + target + " 上监听（插电脑执行一次 adb tcpip 5555）";
        }
        Log.i(TAG, "adb connect " + target + " → " + out.replace("\n", " "));
        Audit.record(c, "adb", "连接", target + " · " + oneLine(out));
        return out;
    }

    /**
     * 以 shell 身份执行一条命令。
     * 未启用时拒绝（返回错误字符串），不静默降级成 app 身份 —— 免得调用方以为自己在 shell 里。
     */
    public static String shell(Context c, String cmd, int timeoutMs) {
        if (!enabled(c)) return "错误：Android shell 未启用（设置 → Android shell 打开后再用）";
        if (cmd == null || cmd.trim().isEmpty()) return "错误：命令为空";
        String s = serial(c);
        if (s.isEmpty()) {
            String e = connect(c, "127.0.0.1:5555");
            s = serial(c);
            if (s.isEmpty()) return "错误：没有已连接的设备（" + oneLine(e) + "）";
        }
        String out = run(c, new String[]{"-s", s, "shell", cmd}, timeoutMs);
        Audit.record(c, "adb", "shell", oneLine(cmd) + " → " + oneLine(out));
        return out.isEmpty() ? "（无输出）" : out;
    }


    /**
     * 导入一把已被本机授权的 adb 私钥（通常来自电脑的 ~/.android/adbkey）。
     *
     * 为什么需要：设备内的 adb 是新客户端，adbd 不认识它的密钥，连接会停在 unauthorized。
     * 常规解法是无线调试配对，但有些 ROM（例如这台华为的开发者选项）没有这一项。
     * 那就把"已经在本机授权过"的那把私钥拿过来用 —— 不是伪造授权，是复用用户已有的信任。
     */
    public static String importKey(Context c, String srcPath) {
        try {
            String path = (srcPath == null || srcPath.trim().isEmpty())
                    ? "/sdcard/DSHNative/adbkey" : srcPath.trim();
            File src = new File(path);
            if (!src.isFile()) return "错误：找不到 " + path + "（把电脑上的 adbkey 放到这里）";
            File dst = new File(new File(home(c), ".android"), "adbkey");
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            dst.setReadable(false, false);
            dst.setReadable(true, true);
            // 让 adb 重新读取密钥
            run(c, new String[]{"kill-server"}, 8000);
            Audit.record(c, "adb", "导入密钥", path);
            return "已导入密钥：" + path + " → " + dst.getAbsolutePath() + "（长度 " + dst.length() + " 字节）";
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    /** 把本机 adb 跑起来；返回合并输出。 */
    private static String run(Context c, String[] args, int timeoutMs) {
        try {
            if (!installed(c)) return "";
            List<String> cmd = new ArrayList<>();
            cmd.add(bin(c).getAbsolutePath());
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File(AbTools.dir(c), "adb"));
            pb.environment().put("HOME", home(c).getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH", lib(c).getAbsolutePath());
            pb.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());
            Process p = pb.start();
            final Process pp = p;
            final StringBuilder sb = new StringBuilder();
            final BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    } catch (Throwable ignored) {}
                }
            });
            reader.start();
            boolean done = p.waitFor(timeoutMs <= 0 ? 15000 : timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                pp.destroyForcibly();
                return sb.toString() + "\n（超时，已终止）";
            }
            reader.join(1500);
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.i(TAG, "adb 执行失败: " + t);
            return "";
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " ⏎ ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
