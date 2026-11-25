package com.dshnative;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 在手机上把 .java 源码编成可安装的 APK。
 *
 * 全链路都在这台设备上跑（不需要电脑）：
 *   ecj 编译 → d8 去糖 → aapt2 打包 → 塞 classes.dex → zipalign 对齐 → apksigner 签名
 *
 * 注意几个 Android 特有的坑，都是实测踩出来的：
 *   · Java 编译器要 -proc:none，Android 没有注解处理 API
 *   · aapt2 的框架 jar 必须是它解析得了的年代（2.19 配 API 28）
 *   · 签名不能读 keystore：Android 没有 JKS，PKCS12 的 MAC 又缺 provider
 *     → 直接用 PKCS#8 私钥 + DER 证书（--key/--cert）
 */
public final class ApkBuilder {

    private static final String TAG = "DSHN";
    private static final String KEY_PKG = "com.example.hello";

    /** 最近一次成功构建的 APK（app_run 用它接着装）。工具调用是串行的，这样最简单。 */
    /** 上一次 run() 的退出码。ecj 报错但退出码非零时必须停下，不能照样出货。 */
    private static volatile int LAST_EXIT = 0;
    private static volatile File LAST_APK = null;
    private static volatile String LAST_PKG = "";
    private static volatile String LAST_ACT = "";

    private ApkBuilder() {}

    /** 生成一个最小可运行的 App 项目。 */
    public static String scaffold(Context c, String workspace, String name) {
        String pkg = KEY_PKG;
        String safe = (name == null || name.trim().isEmpty()) ? "myapp" : name.trim()
                .replaceAll("[^A-Za-z0-9_]+", "");
        if (safe.isEmpty()) safe = "myapp";
        pkg = "com.dsh." + safe.toLowerCase();
        String cls = safe.substring(0, 1).toUpperCase() + safe.substring(1);

        File dir = new File(new File(workspace, "apps"), safe);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return "错误：建不了目录 " + dir;

            String manifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    package=\"" + pkg + "\"\n"
                    + "    android:versionCode=\"1\" android:versionName=\"1.0\">\n"
                    + "    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"28\" />\n"
                    + "    <application android:label=\"" + cls + "\">\n"
                    + "        <activity android:name=\"" + pkg + "." + cls + "\" android:exported=\"true\">\n"
                    + "            <intent-filter>\n"
                    + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                    + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                    + "            </intent-filter>\n"
                    + "        </activity>\n"
                    + "    </application>\n"
                    + "</manifest>\n";
            write(new File(dir, "AndroidManifest.xml"), manifest);

            String java = "package " + pkg + ";\n\n"
                    + "import android.app.Activity;\n"
                    + "import android.graphics.Color;\n"
                    + "import android.os.Bundle;\n"
                    + "import android.view.Gravity;\n"
                    + "import android.widget.TextView;\n\n"
                    + "public class " + cls + " extends Activity {\n"
                    + "    @Override protected void onCreate(Bundle b) {\n"
                    + "        super.onCreate(b);\n"
                    + "        TextView t = new TextView(this);\n"
                    + "        t.setText(getString(R.string.app_name) + \" · 资源可用\");\n"
                    + "        t.setTextSize(24);\n"
                    + "        t.setTextColor(Color.WHITE);\n"
                    + "        t.setGravity(Gravity.CENTER);\n"
                    + "        t.setBackgroundColor(0xFF101014);\n"
                    + "        setContentView(t);\n"
                    + "    }\n"
                    + "}\n";
            File srcDir = new File(dir, "src/" + pkg.replace('.', '/'));
            if (!srcDir.isDirectory()) srcDir.mkdirs();
            write(new File(srcDir, cls + ".java"), java);

            // 资源：aapt2 compile + R 类由 link 生成，App 里可以直接用 R.string.*
            String strings = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<resources>\n"
                    + "    <string name=\"app_name\">" + cls + "</string>\n"
                    + "    <string name=\"hint\">在手机上编出来的</string>\n"
                    + "</resources>\n";
            write(new File(dir, "res/values/strings.xml"), strings);

            return "已生成 App 项目：" + dir.getAbsolutePath()
                    + "\n包名：" + pkg + " · 入口：" + pkg + "." + cls
                    + "\n源码：" + new File(srcDir, cls + ".java").getAbsolutePath()
                    + "\n用 app_build 编成 APK（首次会下载约 16 MB 构建工具链）。";
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    /** 编译项目并返回 APK 路径说明。 */
    public static String build(Context c, String workspace, String name) {
        String safe = (name == null || name.trim().isEmpty()) ? "myapp" : name.trim()
                .replaceAll("[^A-Za-z0-9_]+", "");
        File proj = new File(new File(workspace, "apps"), safe);
        if (!new File(proj, "AndroidManifest.xml").isFile()) {
            return "错误：没有这个项目 " + proj.getAbsolutePath() + "（先用 app_new 生成）";
        }

        String err = AbTools.ensure(c);
        if (err != null) return err;

        File abt = AbTools.dir(c);
        StringBuilder log = new StringBuilder();
        String out;
        try {
            File classes = new File(proj, "classes");
            File dexOut = new File(proj, "dexout");
            deleteTree(classes); deleteTree(dexOut);
            classes.mkdirs(); dexOut.mkdirs();

            // 0) 资源编译（有 res/ 才做）：aapt2 compile → 中间产物
            File resZip = new File(proj, "res.zip");
            File gen = new File(proj, "gen");
            deleteTree(gen);
            File resDir = new File(proj, "res");
            boolean hasRes = resDir.isDirectory();
            if (hasRes) {
                List<String> rc = new ArrayList<>();
                rc.add(new File(abt, "aapt2").getAbsolutePath());
                rc.add("compile");
                rc.add("--dir"); rc.add(resDir.getAbsolutePath());
                rc.add("-o"); rc.add(resZip.getAbsolutePath());
                out = run(rc, abt);
                log.append("资源编译：").append(out.isEmpty() ? "OK" : out).append('\n');
                if (!resZip.isFile()) return "资源编译失败：\n" + log;
            }

            // 1) 编译（有资源时先把 R.java 生成出来，再和源码一起编）
            if (hasRes) {
                List<String> rl = new ArrayList<>();
                rl.add(new File(abt, "aapt2").getAbsolutePath());
                rl.add("link");
                rl.add("-o"); rl.add(new File(proj, "rgen.apk").getAbsolutePath());
                rl.add("--manifest"); rl.add(new File(proj, "AndroidManifest.xml").getAbsolutePath());
                rl.add("-I"); rl.add(new File(abt, "framework.jar").getAbsolutePath());
                rl.add("--java"); rl.add(gen.getAbsolutePath());
                rl.add(resZip.getAbsolutePath());
                out = run(rl, abt);
                log.append("R 类生成：").append(out.isEmpty() ? "OK" : out).append('\n');
            }

            List<File> javaFiles = new ArrayList<>();
            collect(proj, javaFiles, ".java");
            if (gen.isDirectory()) collect(gen, javaFiles, ".java");   // R.java 也要编
            if (javaFiles.isEmpty()) return "错误：项目里没有 .java";
            List<String> cmd = new ArrayList<>();
            cmd.add("dalvikvm"); cmd.add("-Xmx512m");
            cmd.add("-cp"); cmd.add(new File(abt, "ecj-dex.jar").getAbsolutePath());
            cmd.add("org.eclipse.jdt.internal.compiler.batch.Main");
            cmd.add("-source"); cmd.add("1.8");
            cmd.add("-target"); cmd.add("1.8");
            cmd.add("-nowarn"); cmd.add("-proc:none");
            cmd.add("-cp"); cmd.add(new File(abt, "android-stub.jar").getAbsolutePath());
            cmd.add("-d"); cmd.add(classes.getAbsolutePath());
            for (File f : javaFiles) cmd.add(f.getAbsolutePath());
            out = run(cmd, abt);
            log.append("编译：").append(out.isEmpty() ? "OK" : out).append('\n');
            // ecj 可能"报了错仍产出部分 class"，只要退出码非零就当失败，绝不打包
            if (LAST_EXIT != 0) {
                return "编译失败（ecj 退出码 " + LAST_EXIT + "），已停止，不会打包：\n" + log;
            }
            if (!hasClass(classes)) return "编译失败：没有产出 class\n" + log;

            // 2) dex
            List<File> classFiles = new ArrayList<>();
            collect(classes, classFiles, ".class");
            List<String> d8 = new ArrayList<>();
            d8.add("dalvikvm"); d8.add("-Xmx512m");
            d8.add("-cp"); d8.add(new File(abt, "d8-dex.jar").getAbsolutePath());
            d8.add("com.android.tools.r8.D8");
            d8.add("--min-api"); d8.add("26");
            d8.add("--lib"); d8.add(new File(abt, "android-stub.jar").getAbsolutePath());
            d8.add("--output"); d8.add(dexOut.getAbsolutePath());
            for (File f : classFiles) d8.add(f.getAbsolutePath());
            out = run(d8, abt);
            log.append("dex：").append(out.isEmpty() ? "OK" : out).append('\n');
            if (LAST_EXIT != 0) return "dex 失败（d8 退出码 " + LAST_EXIT + "）：\n" + log;
            File dex = new File(dexOut, "classes.dex");
            if (!dex.isFile()) return "dex 失败：\n" + log;

            // 3) aapt2 打包
            File unsigned = new File(proj, "unsigned.apk");
            List<String> link = new ArrayList<>();
            link.add(new File(abt, "aapt2").getAbsolutePath());
            link.add("link");
            link.add("-o"); link.add(unsigned.getAbsolutePath());
            link.add("--manifest"); link.add(new File(proj, "AndroidManifest.xml").getAbsolutePath());
            link.add("-I"); link.add(new File(abt, "framework.jar").getAbsolutePath());
            if (hasRes && resZip.isFile()) link.add(resZip.getAbsolutePath());
            out = run(link, abt);
            log.append("打包：").append(out.isEmpty() ? "OK" : out).append('\n');
            if (LAST_EXIT != 0) return "打包失败（aapt2 退出码 " + LAST_EXIT + "）：\n" + log;
            if (!unsigned.isFile() || unsigned.length() < 500) return "打包失败：\n" + log;

            // 4) 把 classes.dex 塞进 APK（java.util.zip，不依赖 zip 命令）
            File withDex = new File(proj, "with-dex.apk");
            injectDex(unsigned, dex, withDex);

            // 5) 对齐
            File aligned = new File(proj, "aligned.apk");
            List<String> align = new ArrayList<>();
            align.add(new File(abt, "zipalign").getAbsolutePath());
            align.add("-f"); align.add("4");
            align.add(withDex.getAbsolutePath());
            align.add(aligned.getAbsolutePath());
            out = run(align, abt);
            log.append("对齐：").append(out.isEmpty() ? "OK" : out).append('\n');

            // 6) 签名
            File signed = new File(proj, safe + "-signed.apk");
            List<String> sign = new ArrayList<>();
            sign.add("dalvikvm"); sign.add("-Xmx512m");
            sign.add("-cp"); sign.add(new File(abt, "apksigner-dex.jar").getAbsolutePath());
            sign.add("com.android.apksigner.ApkSignerTool");
            sign.add("sign");
            sign.add("--key"); sign.add(new File(abt, "key.pk8").getAbsolutePath());
            sign.add("--cert"); sign.add(new File(abt, "cert.der").getAbsolutePath());
            sign.add("--out"); sign.add(signed.getAbsolutePath());
            sign.add(aligned.getAbsolutePath());
            out = run(sign, abt);
            log.append("签名：").append(out.isEmpty() ? "OK" : out).append('\n');
            if (LAST_EXIT != 0) return "签名失败（apksigner 退出码 " + LAST_EXIT + "）：\n" + log;
            if (!signed.isFile()) return "签名失败：\n" + log;

            LAST_APK = signed;
            String[] pa = pkgAndActivity(proj);
            LAST_PKG = pa[0];
            LAST_ACT = pa[1];
            Log.i(TAG, "APK 构建完成 " + signed.getAbsolutePath()
                    + " · " + LAST_PKG + "/" + LAST_ACT);
            return "构建完成：" + signed.getAbsolutePath()
                    + "\n大小 " + (signed.length() / 1024) + " KB"
                    + "\n（自签证书，只能自用/侧载；上架需要自己的密钥）\n---\n" + log;
        } catch (Throwable t) {
            return "错误：" + t + "\n" + log;
        }
    }


    /** 从 AndroidManifest.xml 里读 package 与入口 activity 的全名。 */

    private static String readSmall(File f) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String[] pkgAndActivity(File proj) {
        String pkg = "", act = "";
        try {
            String xml = readSmall(new File(proj, "AndroidManifest.xml"));
            java.util.regex.Matcher m1 = java.util.regex.Pattern
                    .compile("package=\"([^\"]+)\"").matcher(xml);
            if (m1.find()) pkg = m1.group(1);
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("<activity[^>]*android:name=\"([^\"]+)\"").matcher(xml);
            if (m2.find()) {
                act = m2.group(1);
                if (act.startsWith(".")) act = pkg + act;
                else if (!act.contains(".")) act = pkg + "." + act;
            }
        } catch (Throwable ignored) {}
        return new String[]{pkg, act};
    }


    /**
     * 一步到位：编 → 装 → 起。
     * 装和起都走设备内 adb（需要「Android shell」开启）；没开就只报告"编好了但没装"。
     */
    /**
     * 一步到位：编 → 停旧进程 → 装 → 起 → 自查是否真的在运行。
     *
     * 为什么要"自查"：安装成功不等于跑得起来。启动后读一次 dumpsys 确认前台是不是它，
     * 起不来就把这包的 logcat 尾巴带回来 —— 否则调用方只能靠猜。
     */
    public static String runApp(Context c, String workspace, String name) {
        String built = build(c, workspace, name);
        if (!built.startsWith("构建完成")) return built;
        File apk = LAST_APK;
        String pkg = LAST_PKG, act = LAST_ACT;
        StringBuilder sb = new StringBuilder(built).append('\n');

        if (!AdbCli.enabled(c)) {
            sb.append("已构建，但没安装：Android shell 未启用（设置 → Android shell 打开后可一键装+起）。");
            return sb.toString();
        }
        if (apk == null || !apk.isFile()) {
            sb.append("已构建，但找不到产物路径，跳过安装。");
            return sb.toString();
        }
        if (pkg.isEmpty() || act.isEmpty()) {
            sb.append("manifest 里没解析到包名/入口，跳过。");
            return sb.toString();
        }

        // 旧实例先停掉，否则装完起来的可能还是老的
        AdbCli.shell(c, "am force-stop " + pkg, 15000);

        String ins = AdbCli.install(c, apk.getAbsolutePath());
        sb.append("安装：").append(ins).append('\n');
        if (!ins.startsWith("安装成功")) return sb.toString();

        // 先清日志：否则拿回来的是上一轮的陈旧输出（我在测试里就被它骗过一次）
        AdbCli.shell(c, "logcat -c", 15000);
        String launch = AdbCli.shell(c, "am start -n " + pkg + "/" + act, 30000);
        sb.append("启动：").append(launch.isEmpty() ? "（无输出）" : oneLine(launch)).append('\n');
        if (launch.contains("Error") || launch.contains("Exception")) {
            sb.append("—— 启动就失败了，日志见下 ——\n");
        }

        // 起来没有：sleep 一下再看前台
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        String resumed = AdbCli.shell(c, "dumpsys activity activities | grep -m1 ResumedActivity", 20000);
        boolean running = resumedIs(resumed, pkg);

        if (running) {
            sb.append("运行中：").append(pkg).append("/").append(act).append("（已在前台）");
            // 前台正常也顺手看看有没有异常（有些错误不阻止启动）
            String warn = crashHint(AdbCli.shell(c,
                    "logcat -d -t 800 | grep -iE \"FATAL|AndroidRuntime|Exception\"", 20000));
            if (!warn.isEmpty()) sb.append("\n但日志里有报错：\n").append(warn);
        } else {
            sb.append("没起来：前台不是 ").append(pkg).append("。日志尾巴：\n");
            String log = crashHint(AdbCli.shell(c,
                    "logcat -d -t 1200 | grep -iE \"FATAL|AndroidRuntime|" + pkg + "\"", 20000));
            sb.append(log.isEmpty() ? "（logcat 里没有这个包的输出，可能根本没被拉起）" : log);
        }
        return sb.toString();
    }

    /** dumpsys 的前台记录里是不是这个包。 */
    static boolean resumedIs(String dumpsysOut, String pkg) {
        if (dumpsysOut == null || pkg == null || pkg.isEmpty()) return false;
        return dumpsysOut.contains(pkg + "/") || dumpsysOut.contains(pkg + " ");
    }

    /**
     * 从 logcat 里挑出"最能说明问题"的几行：优先 FATAL/AndroidRuntime（崩溃栈），
     * 没有就退化成普通行。别把几百行系统广播倒给调用方。
     */
    static String crashHint(String log) {
        if (log == null || log.trim().isEmpty()) return "";
        String[] lines = log.split("\n");
        StringBuilder fatal = new StringBuilder();
        int fatalCount = 0;
        for (String line : lines) {
            if (line.contains("FATAL") || line.contains("AndroidRuntime")) {
                fatal.append("  ").append(clip(line)).append('\n');
                if (++fatalCount >= 8) break;
            }
        }
        if (fatal.length() > 0) return fatal.toString().trim();
        return tailMatching(log, 6);
    }

    private static String clip(String t) {
        String x = t.trim();
        return x.length() > 200 ? x.substring(0, 200) + "…" : x;
    }

    /** 取文本最后 n 行（日志回传别一股脑塞给模型）。 */
    static String tailMatching(String text, int n) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            sb.append("  ").append(t.length() > 200 ? t.substring(0, 200) + "…" : t).append('\n');
        }
        return sb.toString().trim();
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replace("\r", "").replace("\n", " ⏎ ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }


    // ---------- helpers ----------

    private static void injectDex(File apk, File dex, File out) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(out));
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        byte[] buf = new byte[64 * 1024];
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            if (e.getName().equals("classes.dex")) continue;
            zos.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
            java.io.InputStream in = zf.getInputStream(e);
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
            in.close();
            zos.closeEntry();
        }
        zos.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
        java.io.FileInputStream fin = new java.io.FileInputStream(dex);
        int n;
        while ((n = fin.read(buf)) > 0) zos.write(buf, 0, n);
        fin.close();
        zos.closeEntry();
        zos.close();
        zf.close();
    }

    /** 跑一个命令，返回合并后的输出（截断到 1500 字符）。 */
    private static String run(List<String> cmd, File abtDir) {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(abtDir);
            pb.environment().put("LD_LIBRARY_PATH", abtDir.getAbsolutePath());
            Process p = pb.start();
            LAST_EXIT = -1;
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            LAST_EXIT = p.waitFor();
        } catch (Throwable t) {
            sb.append("执行失败：").append(t).append('\n');
        }
        String s = sb.toString().trim();
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    private static void collect(File dir, List<File> out, String suffix) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                // gen/ 是 aapt2 生成的 R.java，由调用方显式加；classes/dexout 是产物。
                // 不排除的话 R.java 会被收两次，ecj 直接报 "specified more than once"。
                String n = f.getName();
                if (n.equals("gen") || n.equals("classes") || n.equals("dexout")) continue;
                collect(f, out, suffix);
            } else if (f.getName().endsWith(suffix)) {
                out.add(f);
            }
        }
    }

    private static boolean hasClass(File dir) {
        List<File> l = new ArrayList<>();
        collect(dir, l, ".class");
        return !l.isEmpty();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8"));
        os.close();
    }
}
