package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 单元测试（不依赖任何测试框架，纯手写断言）。
 *
 * 为什么要有：这个项目一直只有真机自检（SelfCheck 那 24 项偏集成，跑得慢、依赖设备状态），
 * 逻辑函数（版本比较、权限默认值、桌面索引转换、卡片判定）没有任何自动化保护。
 * 这些函数出错的方式很安静——比如"1.10 被当成比 1.9 旧"，界面上只是不提示更新。
 *
 * 这里只测纯函数：不碰网络、不碰 UI、不写用户目录。用 {@link #run(Context)} 拿结果，
 * 也可以从 dev_unit 工具调用。
 */
public final class Tests {

    public static final class Result {
        public final int total;
        public final int passed;
        public final List<String> failures;

        Result(int total, int passed, List<String> failures) {
            this.total = total;
            this.passed = passed;
            this.failures = failures;
        }

        public boolean ok() { return failures.isEmpty(); }

        public String summary() {
            return ok() ? ("全部通过（" + passed + "/" + total + "）")
                    : ("失败 " + failures.size() + " / " + total + "：" + join(failures));
        }
    }

    private Tests() {}

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    public static Result run(Context c) {
        List<String> fails = new ArrayList<>();
        int total = 0;

        // ---- 版本比较：点分数字，不能按字符串比 ----
        total++; if (!Market.isNewerThan("1.10.0", "1.9.9")) fails.add("1.10.0 应比 1.9.9 新");
        total++; if (Market.isNewerThan("2.0", "2.0")) fails.add("相同的版本不该算新");
        total++; if (!Market.isNewerThan("1.0.1", "1.0")) fails.add("1.0.1 应比 1.0 新");
        total++; if (Market.isNewerThan("1.9", "1.10")) fails.add("1.9 不该比 1.10 新");

        // ---- 权限默认值：没声明 permissions 时应放行，声明 -net 才拦 ----
        try {
            JSONObject plain = new JSONObject("{\"id\":\"a\"}");
            String[] p = Market.perms(plain);
            total++; if ("-net".equals(p[0])) fails.add("未声明权限的插件不该默认拦网");
            total++; if ("-shell".equals(p[1])) fails.add("未声明权限的插件不该默认拦命令");

            // permissions 是对象 {net:false, shell:false}，不是数组
            JSONObject noNet = new JSONObject("{\"id\":\"b\",\"permissions\":{\"net\":false}}");
            String[] p2 = Market.perms(noNet);
            total++; if (!"-net".equals(p2[0])) fails.add("net:false 应拦网");
            total++; if ("-shell".equals(p2[1])) fails.add("没声明 shell 就不该拦命令");

            JSONObject noShell = new JSONObject("{\"id\":\"c\",\"permissions\":{\"shell\":false}}");
            String[] p3 = Market.perms(noShell);
            total++; if (!"-shell".equals(p3[1])) fails.add("shell:false 应拦命令");
            total++; if ("-net".equals(p3[0])) fails.add("没声明 net 就不该拦网");

            JSONObject both = new JSONObject("{\"id\":\"d\",\"permissions\":{\"net\":false,\"shell\":false}}");
            String[] p4 = Market.perms(both);
            total++; if (!"-net".equals(p4[0]) || !"-shell".equals(p4[1])) fails.add("双禁止没生效");
        } catch (Throwable t) {
            total += 7; fails.add("权限用例异常 " + t);
        }

        // ---- 桌面版索引转换：有 name+versions 且没有 tools/capabilities 才算 ----
        try {
            JSONObject cat = new JSONObject("{\"format\":1,\"plugins\":["
                    + "{\"name\":\"lodash\",\"metadata\":{\"title\":\"Lodash\"},"
                    + "\"versions\":[{\"version\":\"4.17.21\"}]},"
                    + "{\"id\":\"native1\",\"name\":\"原生\",\"tools\":[]}]}");
            List<JSONObject> conv = Market.desktopEntries(cat);
            total++; if (conv.size() != 1) fails.add("桌面索引应只转出 1 条，实际 " + conv.size());
            if (conv.size() == 1) {
                JSONObject e = conv.get(0);
                total++; if (!"lodash".equals(e.optString("npm"))) fails.add("npm 字段错");
                total++; if (!"4.17.21".equals(e.optString("version"))) fails.add("版本没取到");
                total++; if (!Market.isDesktopEntry(e)) fails.add("没打 _desktop 标记");
            } else {
                total += 3;
            }
        } catch (Throwable t) {
            total += 4; fails.add("桌面索引用例异常 " + t);
        }

        // ---- 选哪个 APK 去装：优先 *-signed.apk，而不是 unsigned ----
        try {
            File dir = new File(c.getCacheDir(), "apksel");
            if (!dir.isDirectory()) dir.mkdirs();
            new File(dir, "unsigned.apk").createNewFile();
            new File(dir, "x-signed.apk").createNewFile();
            new File(dir, "aligned.apk").createNewFile();
            File picked = Apks.pick(dir);
            total++; if (picked == null || !picked.getName().endsWith("-signed.apk")) {
                fails.add("应优先挑 *-signed.apk，实际 " + (picked == null ? "null" : picked.getName()));
            }
            File empty = new File(c.getCacheDir(), "apkempty");
            if (!empty.isDirectory()) empty.mkdirs();
            total++; if (Apks.pick(empty) != null) fails.add("空目录应返回 null");
        } catch (Throwable e) {
            total += 2; fails.add("APK 选择用例异常 " + e);
        }

        // ---- 截图临时文件：必须在私有缓存（不进相册）+ 用完即删 ----
        try {
            String shotDir = Shots.dir(c).getAbsolutePath();
            total++; if (!shotDir.startsWith(c.getCacheDir().getAbsolutePath())) {
                fails.add("截图目录不在 cacheDir 下：" + shotDir);
            }
            total++; if (shotDir.startsWith("/sdcard") || shotDir.contains("/DCIM")) {
                fails.add("截图目录落在共享存储/相册目录：" + shotDir);
            }
            java.io.File f = Shots.newFile(c);
            total++; if (!Shots.isShot(c, f.getAbsolutePath())) fails.add("isShot 认不出自己的截图");
            total++; if (Shots.isShot(c, "/sdcard/DSHNative/x.png")) fails.add("isShot 误判外部文件");
            // 落一个真文件再删
            try {
                f.getParentFile().mkdirs();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(new byte[]{1, 2, 3});
                fo.close();
            } catch (Throwable ignored) {}
            Shots.delete(f.getAbsolutePath());
            total++; if (f.exists()) fails.add("delete 之后文件还在");
        } catch (Throwable t) {
            total += 5; fails.add("截图临时文件用例异常 " + t);
        }

        // ---- 会话搜索与导出 ----
        try {
            total++; String sn = SessionSearch.snippet("前文一段，关键命令 npm install 在后文", "NPM", 6);
            if (!sn.contains("npm install")) fails.add("片段应包含命中词，实际 " + sn);
            total++; if (!sn.startsWith("…") && !SessionSearch.snippet(
                    "npm install", "npm", 6).startsWith("npm")) fails.add("片段切分错");

            total++; org.json.JSONObject m1 = new org.json.JSONObject();
            m1.put("role", "user"); m1.put("content", "你好");
            if (!"你好".equals(SessionSearch.textOf(m1))) fails.add("textOf 字符串形式取错");

            total++; org.json.JSONObject m2 = new org.json.JSONObject();
            m2.put("role", "user");
            m2.put("content", new org.json.JSONArray().put(new org.json.JSONObject().put("text", "分片")));
            if (!"分片".equals(SessionSearch.textOf(m2))) fails.add("textOf 分片数组取错");

            total++; if (!"a-b-c".equals(SessionExport.safeName("a/b:c "))) {
                fails.add("safeName 应把危险字符变短横，实际 " + SessionExport.safeName("a/b:c "));
            }

            total++; org.json.JSONArray msgs = new org.json.JSONArray();
            msgs.put(new org.json.JSONObject().put("role", "system").put("content", "系统提示"));
            msgs.put(new org.json.JSONObject().put("role", "user").put("content", "问题"));
            msgs.put(new org.json.JSONObject().put("role", "assistant").put("content", "回答"));
            msgs.put(new org.json.JSONObject().put("role", "tool").put("content", "输出"));
            String md = SessionExport.toMarkdown("标题", msgs);
            total++; if (!md.contains("## 你") || !md.contains("## 助手")) fails.add("Markdown 缺角色小节");
            total++; if (md.contains("系统提示")) fails.add("系统提示不该出现在导出里");
            total++; if (!md.contains("> **工具返回**")) fails.add("工具输出应折成引用块");
        } catch (Throwable t) {
            total += 8; fails.add("会话工具用例异常 " + t);
        }

        // ---- 公式求值：给 xlsx 写缓存值用 ----
        try {
            org.json.JSONArray rows = new org.json.JSONArray(
                    "[ [\"甲\",\"乙\"], [12,30], [45,7], [\"合计\",\"\"] ]");
            total++; Double a = Formulas.eval("SUM(B2:B3)", rows);
            if (a == null || Math.abs(a - 37) > 0.001) fails.add("SUM(B2:B3) 应得 37，实际 " + a);

            total++; Double b = Formulas.eval("B2+B3", rows);
            if (b == null || Math.abs(b - 37) > 0.001) fails.add("B2+B3 应得 37，实际 " + b);

            total++; Double avg = Formulas.eval("AVERAGE(B2:B3)", rows);
            if (avg == null || Math.abs(avg - 18.5) > 0.001) fails.add("AVERAGE 应得 18.5，实际 " + avg);

            total++; if (Formulas.eval("NOSUCHFN(B2)", rows) != null) fails.add("不认识的函数应返回 null");
            total++; if (Formulas.eval("B2/0", rows) != null) fails.add("除零应返回 null");
            total++; if (Formulas.eval("B2+", rows) != null) fails.add("残缺表达式应返回 null");

            total++; int[] pos = Formulas.cellPos("B3");
            if (pos == null || pos[0] != 2 || pos[1] != 1) fails.add("B3 应解析为 [2][1]");
        } catch (Throwable t) {
            total += 7; fails.add("公式用例异常 " + t);
        }

        // ---- 预览类型判定：四种文档才走本地渲染 ----
        total++; if (!WebOffice.canRender("a.docx") || !WebOffice.canRender("b.XLSX")
                || !WebOffice.canRender("c.pdf") || !WebOffice.canRender("d.pptx")) {
            fails.add("四种文档都应判为可渲染");
        }
        total++; if (WebOffice.canRender("a.txt") || WebOffice.canRender("b.png")) {
            fails.add("文本/图片不该判为文档渲染");
        }
        total++; if (WebOffice.canRender("c.doc") || WebOffice.canRender("d.xls")) {
            fails.add("老格式（doc/xls）不该假装能渲染");
        }

        // ---- 列表显示：大小与项目名 ----
        total++; if (!"512 B".equals(Apks.humanSize(512))) fails.add("humanSize 字节档错");
        total++; if (!"2 KB".equals(Apks.humanSize(2048))) fails.add("humanSize KB 档错");
        total++; if (!"4.7 MB".equals(Apks.humanSize(4930000))) fails.add("humanSize MB 档错");
        total++; if (!"tarotduel".equals(Apks.projectName(new java.io.File("/x/apps/tarotduel/tarotduel-signed.apk")))) {
            fails.add("projectName 取错");
        }

        // ---- 停止原因文案：不能把看门狗说成"你按了停止" ----
        total++; if (!Agent.stopNote("user").contains("你按了停止")) fails.add("用户停止文案不对");
        total++; String wt = Agent.stopNote("watchdog-tool:sh");
        if (!wt.contains("sh") || wt.contains("你按了停止")) fails.add("工具超时文案不对");
        total++; String wi = Agent.stopNote("watchdog-idle");
        if (!wi.contains("150 秒") || wi.contains("你按了停止")) fails.add("空转文案不对");

        // ---- 崩溃日志挑拣：优先 FATAL，不刷屏 ----
        total++; String ch = ApkBuilder.crashHint(
                "I/x: 普通行\nE/AndroidRuntime: FATAL EXCEPTION: main\n  at demo.Crashme.onCreate");
        if (!ch.contains("FATAL")) fails.add("crashHint 应优先挑出 FATAL 行");
        total++; if (ApkBuilder.crashHint("I/x: 只有普通行").contains("FATAL")) {
            fails.add("没有 FATAL 时不该凭空出现");
        }
        total++; if (!ApkBuilder.crashHint("").isEmpty()) fails.add("空日志应返回空");

        // ---- 启动自查：前台判定与日志裁剪 ----
        total++; if (!ApkBuilder.resumedIs(
                "mResumedActivity: ActivityRecord{1 u0 com.dsh.x/.Main t1}", "com.dsh.x")) {
            fails.add("前台是这个包时应判为运行中");
        }
        total++; if (ApkBuilder.resumedIs(
                "mResumedActivity: ActivityRecord{1 u0 com.other/.Main t1}", "com.dsh.x")) {
            fails.add("前台是别的包时不该判为运行中");
        }
        total++; if (ApkBuilder.resumedIs("", "com.dsh.x")) fails.add("空输入应判为否");

        total++; String tail = ApkBuilder.tailMatching("a\nb\nc\nd\ne", 2);
        if (!tail.contains("d") || tail.contains("c")) fails.add("tailMatching 取错了行数");
        total++; if (!ApkBuilder.tailMatching("", 3).isEmpty()) fails.add("空文本应返回空");

        // ---- adb devices 解析：只认 device，不认 unauthorized/offline ----
        total++; if (!"127.0.0.1:5555".equals(AdbCli.parseSerial(
                "List of devices attached\n127.0.0.1:5555\tdevice\n"))) {
            fails.add("应认出 device 行");
        }
        total++; if (!AdbCli.parseSerial(
                "List of devices attached\n127.0.0.1:5555\tunauthorized\n").isEmpty()) {
            fails.add("unauthorized 不该当成可用设备");
        }
        total++; if (!AdbCli.parseSerial("List of devices attached\n\n").isEmpty()) {
            fails.add("空列表应返回空");
        }
        total++; if (!AdbCli.looksUnauthorized(
                "List of devices attached\n127.0.0.1:5555\tunauthorized\n")) {
            fails.add("应识别出 unauthorized");
        }
        total++; if (AdbCli.looksUnauthorized("List of devices attached\n")) {
            fails.add("没 unauthorized 时不该误报");
        }

        // ---- 附件卡片判定：只有被"点名"的才挂卡 ----
        File tmp = null;
        try {
            tmp = new File(c.getCacheDir(), "unit-card.html");
            FileOutputStream os = new FileOutputStream(tmp);
            os.write("<html>x</html>".getBytes("UTF-8"));
            os.close();
            total++; if (!Attach.shouldCard(tmp, "结果在 unit-card.html 里")) fails.add("点名了却没判要挂卡");
            total++; if (Attach.shouldCard(tmp, "我做好了，没有提到文件名")) fails.add("没点名却判要挂卡");
            total++; if (Attach.shouldCard(tmp, "")) fails.add("空回答不该挂卡");
        } catch (Throwable t) {
            total += 3; fails.add("卡片用例异常 " + t);
        } finally {
            if (tmp != null) tmp.delete();
        }

        return new Result(total, total - fails.size(), fails);
    }
}
