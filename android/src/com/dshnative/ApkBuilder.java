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
            if (!hasClass(classes)) return "编译失败：\n" + log;

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
            if (!signed.isFile()) return "签名失败：\n" + log;

            Log.i(TAG, "APK 构建完成 " + signed.getAbsolutePath());
            return "构建完成：" + signed.getAbsolutePath()
                    + "\n大小 " + (signed.length() / 1024) + " KB"
                    + "\n（自签证书，只能自用/侧载；上架需要自己的密钥）\n---\n" + log;
        } catch (Throwable t) {
            return "错误：" + t + "\n" + log;
        }
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
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            p.waitFor();
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
