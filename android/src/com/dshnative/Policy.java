package com.dshnative;

import java.io.File;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 能力表 + 策略 + 审计。
 *
 * 这是整个 App 的安全边界，也是"模型看不到 root"的落点：
 * 模型只看到工具名，工具对应一条能力；能力落在哪一档由这里决定。
 *
 * 无 root 手机上的四档（由弱到强）：
 *   APP  —— 普通应用权限。文件、剪贴板、通知、Intent 拉起应用。
 *   A11Y —— 无障碍服务。读界面树、派发手势。这是"无 root 也能操作手机"的通用兜底。
 *   ADB  —— 内置自举到 uid 2000（自己配对，不装 Shizuku）。让 pm/settings/input 变快变稳。
 *   ROOT —— 有就用，没有就降级。永远不是默认档。
 *
 * 失败即关闭：能力所在档位不可用时，对应工具**不进工具清单**，而不是进了再报错。
 */
public final class Policy {

    public enum Tier { APP, A11Y, ADB, ROOT }
    public enum Decision { AUTO, CONFIRM, DENY }

    public static final class Cap {
        public final String name;
        public final Tier tier;
        public final Decision decision;
        public final String desc;
        Cap(String name, Tier tier, Decision decision, String desc) {
            this.name = name; this.tier = tier; this.decision = decision; this.desc = desc;
        }
    }

    private static final Map<String, Cap> CAPS = new LinkedHashMap<>();

    private static void put(String name, Tier t, Decision d, String desc) {
        CAPS.put(name, new Cap(name, t, d, desc));
    }

    static {
        put("fs.read",        Tier.APP,  Decision.AUTO,    "读文件/列目录");
        put("fs.write",       Tier.APP,  Decision.AUTO,    "写文件（工作区=整个手机，默认不拦）");
        put("app.list",       Tier.APP,  Decision.AUTO,    "列出已安装应用");
        put("app.launch",     Tier.APP,  Decision.AUTO,    "启动应用");
        put("clip.read",      Tier.APP,  Decision.AUTO,    "读剪贴板");
        put("clip.write",     Tier.APP,  Decision.AUTO,    "写剪贴板");
        put("notify",         Tier.APP,  Decision.AUTO,    "发通知");
        put("net.fetch",      Tier.APP,  Decision.AUTO,    "网络访问");
        put("agent.spawn",    Tier.APP,  Decision.AUTO,    "子智能体 / 循环");
        put("mcp.call",       Tier.APP,  Decision.AUTO,    "调用 MCP server 的工具");
        put("agent.render",   Tier.APP,  Decision.AUTO,    "在对话里渲染 HTML/网页");
        put("screen.capture", Tier.APP,  Decision.AUTO,    "截屏（MediaProjection，需一次性授权）");
        put("ui.read",        Tier.A11Y, Decision.AUTO,    "读界面树");
        put("ui.act",         Tier.A11Y, Decision.AUTO,    "在界面上点击/滑动/输入");
        put("sys.settings",   Tier.ADB,  Decision.CONFIRM, "改系统设置");
        put("pkg.install",    Tier.ADB,  Decision.CONFIRM, "安装应用");
        put("pkg.uninstall",  Tier.ADB,  Decision.CONFIRM, "卸载应用");
        put("sys.input",      Tier.ADB,  Decision.CONFIRM, "注入按键");
        // shell 不再只属于 root：以 app 自己的身份跑 /system/bin/sh 是允许的，
        // 有 root 就用 su，没有就用 app uid —— 终端能力对所有人开放，但每次都要确认。
        put("shell",          Tier.APP,  Decision.AUTO,    "终端（app 身份；不可回滚的命令仍会问一次）");
    }

    public static Cap cap(String name) { return CAPS.get(name); }

    /** 档位是否可用。ADB 档在 P2 自举完成前恒为 false。 */
    public static boolean tierReady(Tier t, boolean rootOk, boolean a11yOk, boolean adbOk) {
        switch (t) {
            case APP:  return true;
            // shell（Shizuku）能兑现无障碍档位：uiautomator dump / input tap / screencap 都是 shell 命令。
            case A11Y: return a11yOk || adbOk;
            case ADB:  return adbOk;
            case ROOT: return rootOk;
            default:   return false;
        }
    }

    /** 某能力此刻的策略。档位不可用 → DENY（而不是降级去猜）。 */
    public static Decision decide(String capName, boolean rootOk, boolean a11yOk, boolean adbOk) {
        Cap c = CAPS.get(capName);
        if (c == null) return Decision.DENY;
        if (!tierReady(c.tier, rootOk, a11yOk, adbOk)) return Decision.DENY;
        return c.decision;
    }

    public static String summary(boolean rootOk, boolean a11yOk, boolean adbOk) {
        StringBuilder sb = new StringBuilder();
        for (Cap c : CAPS.values()) {
            if (!tierReady(c.tier, rootOk, a11yOk, adbOk)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.name);
        }
        return sb.toString();
    }

    // ---------------- 审计：append-only + hash 链 ----------------

    /** 每条记录以上一条的哈希做前缀，改一条就断链。手机被拿走也能看出有没有被动过。 */
    public static final class Audit {
        private static final String FILE = "audit.log";
        private static String lastHash = null;
        private static int cachedCount = -1;   // 每次 UI 刷新都全量读一遍太浪费，缓存住
        private static String cachedDir = null;

        public static synchronized void append(File dir, String cap, String actor, String detail) {
            try {
                File f = new File(dir, FILE);
                if (lastHash == null) lastHash = tailHash(f);
                long t = System.currentTimeMillis();
                String body = t + "\t" + cap + "\t" + actor + "\t" + oneLine(detail, 300);
                String h = sha256(lastHash + body);
                FileWriter w = new FileWriter(f, true);
                w.write(h + "\t" + body + "\n");
                w.close();
                lastHash = h;
                if (cachedCount >= 0) cachedCount++;
            } catch (Exception ignored) {}
        }

        public static synchronized int count(File dir) {
            if (cachedCount >= 0 && dir != null && dir.getAbsolutePath().equals(cachedDir)) return cachedCount;
            try {
                File f = new File(dir, FILE);
                if (!f.isFile()) return 0;
                int n = 0;
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                while (r.readLine() != null) n++;
                r.close();
                cachedCount = n;
                cachedDir = dir == null ? null : dir.getAbsolutePath();
                return n;
            } catch (Exception e) { return -1; }
        }

        private static String tailHash(File f) {
            try {
                if (!f.isFile()) return "GENESIS";
                String last = null;
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = r.readLine()) != null) if (!line.isEmpty()) last = line;
                r.close();
                if (last == null) return "GENESIS";
                int tab = last.indexOf('\t');
                return tab > 0 ? last.substring(0, tab) : "GENESIS";
            } catch (Exception e) { return "GENESIS"; }
        }

        private static String sha256(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(s.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { return "00000000"; }
        }

        private static String oneLine(String s, int max) {
            if (s == null) return "";
            String o = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            return o.length() > max ? o.substring(0, max) + "…" : o;
        }
    }
}
