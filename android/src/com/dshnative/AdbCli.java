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

    /**
     * 从 {@code adb devices} 的输出里挑一个可用的串口（纯函数，便于单测）。
     * 只认状态为 device 的行；unauthorized / offline 都不算。
     */
    public static String parseSerial(String devicesOutput) {
        if (devicesOutput == null) return "";
        for (String line : devicesOutput.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("List of") || t.startsWith("*")) continue;
            String[] parts = t.split("\\s+");
            if (parts.length < 2) continue;
            if ("device".equals(parts[parts.length - 1])) return parts[0];
        }
        return "";
    }

    /** 输出里有没有"连上了但没授权"。 */
    public static boolean looksUnauthorized(String devicesOutput) {
        return devicesOutput != null && devicesOutput.contains("unauthorized");
    }

    /** 已连上的设备串口。 */
    public static String serial(Context c) {
        return parseSerial(run(c, new String[]{"devices"}, 8000));
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

    /** 候补连接地址：上次成功的、以及默认的本地 5555。 */
    private static String[] candidates(Context c) {
        String last = Prefs.get(c).getString("adb_host", "");
        if (last == null || last.trim().isEmpty()) return new String[]{"127.0.0.1:5555"};
        return new String[]{last.trim(), "127.0.0.1:5555"};
    }

    /**
     * 确保有一条可用的 shell 通道：没有就重试连接，最多两轮。
     * 返回串口，失败返回空串。
     */
    public static String ensureConnected(Context c) {
        if (!installed(c)) return "";
        String s = serial(c);
        if (!s.isEmpty()) return s;
        String dev = run(c, new String[]{"devices"}, 8000);
        if (looksUnauthorized(dev)) return "";      // 授权问题，重连也没用
        for (int round = 0; round < 2; round++) {
            for (String h : candidates(c)) {
                run(c, new String[]{"connect", h}, 15000);
                s = serial(c);
                if (!s.isEmpty()) {
                    Prefs.get(c).edit().putString("adb_host", h).apply();
                    Log.i(TAG, "adb 自愈成功 " + h);
                    return s;
                }
            }
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        }
        return "";
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
        if (out.contains("connected to")) Prefs.get(c).edit().putString("adb_host", target).apply();
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
        String s = ensureConnected(c);
        if (s.isEmpty()) {
            String dev = run(c, new String[]{"devices"}, 8000);
            if (looksUnauthorized(dev)) {
                return "错误：设备未授权（unauthorized）。在设置里导入手已授权的 adbkey，"
                        + "或用无线调试配对码配对后再连接。";
            }
            return "错误：连不上 adbd。adbd 需要在 TCP 上监听：插电脑执行一次 adb tcpip 5555，"
                    + "或打开无线调试配对（当前候补地址 " + java.util.Arrays.toString(candidates(c)) + "）";
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


    /**
     * 一键安装：{@code pm install -r <path>}。
     *
     * 为什么用 shell 而不是 Intent：应用内起安装器要用户点一次"安装"，
     * 而 shell 身份下 pm install 是直接装完，适合"编完立刻装"的闭环。
     * 只能装 workspace 这类 shell 读得到的位置（app 私有目录不行）。
     */
    public static String install(Context c, String apkPath) {
        if (!enabled(c)) return "错误：Android shell 未启用（设置 → Android shell 打开后再用）";
        String path = apkPath == null ? "" : apkPath.trim();
        if (path.isEmpty()) return "错误：apk 路径为空";
        if (!path.startsWith("/")) return "错误：请给绝对路径（例 /sdcard/DSHNative/apps/hello/hello-signed.apk）";
        // pm install 的 system_server 读不了 /sdcard（SELinux: fuse），
        // 必须先由 shell 拷到 /data/local/tmp 再装 —— 这是它自己的报错给的建议。
        String staged = "/data/local/tmp/dsh-install.apk";
        String cp = shell(c, "cp -f \"" + path + "\" " + staged + " && ls -l " + staged, 60000);
        if (cp.contains("No such file") || cp.contains("Permission denied")) {
            return "错误：复制 APK 到 /data/local/tmp 失败：" + oneLine(cp);
        }
        String out = shell(c, "pm install -r " + staged, 120000);
        shell(c, "rm -f " + staged, 20000);
        Log.i(TAG, "adb install " + path + " → " + oneLine(out));
        Audit.record(c, "adb", "安装", path + " · " + oneLine(out));
        if (out.contains("Success")) return "安装成功：" + path;
        return out.isEmpty() ? "安装没有输出（可能失败）" : out;
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
