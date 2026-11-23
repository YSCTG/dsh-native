package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 端上自检：不需要电脑、不需要网络，直接在手机上验证关键路径。
 *
 * 为什么要有它：这个项目没有 AndroidX、没有 Gradle，也就没有现成的测试框架。
 * 但"没法写单元测试"是借口——真正需要验证的东西（OOXML 结构、媒体文件头、
 * 工作区读写、会话落盘）都能在这里断言。
 *
 * 用法：工具 dev_selfcheck，或者在设置里点一下。
 */
public final class SelfCheck {

    private SelfCheck() {}

    /** 一条检查的结果。 */
    public static final class Item {
        public final String name;
        public final boolean ok;
        public final String detail;

        Item(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    public static List<Item> run(Context c) {
        List<Item> out = new ArrayList<>();
        File dir = new File(c.getCacheDir(), "selfcheck");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        clean(dir);

        checkMimes(out);
        checkDocx(out, c, dir);
        checkXlsx(out, c, dir);
        checkPptx(out, c, dir);
        checkPdf(out, c, dir);
        checkTypography(out, c, dir);
        checkAttach(out, c, dir);
        checkProcessIo(out);
        checkMcpStdio(out, c, dir);
        checkWav(out, c, dir);
        checkM4a(out, c, dir);
        checkMp4(out, c, dir);
        checkWorkspaces(out, c);
        checkSession(out, c);
        checkJsonPairing(out);
        checkCompact(out);

        clean(dir);
        return out;
    }

    public static String report(Context c) {
        List<Item> items = run(c);
        int pass = 0;
        StringBuilder sb = new StringBuilder("自检 · " + items.size() + " 项\n");
        for (Item it : items) {
            if (it.ok) pass++;
            sb.append(it.ok ? "  ✓ " : "  ✗ ").append(it.name);
            if (!it.detail.isEmpty()) sb.append("  ").append(it.detail);
            sb.append('\n');
        }
        sb.append(pass == items.size()
                ? "全部通过（" + pass + "/" + items.size() + "）"
                : "有失败：" + (items.size() - pass) + " 项");
        return sb.toString();
    }

    // ---------------- 各项检查 ----------------

    private static void checkMimes(List<Item> out) {
        String[][] cases = {
                {"a.xlsx", "spreadsheet"},
                {"a.docx", "wordprocessingml"},
                {"a.pptx", "presentationml"},
                {"a.pdf", "pdf"},
                {"a.mp4", "video/"},
                {"a.wav", "audio/"},
        };
        StringBuilder bad = new StringBuilder();
        for (String[] cs : cases) {
            if (!Mimes.of(cs[0]).contains(cs[1])) bad.append(cs[0]).append(' ');
        }
        out.add(new Item("MIME 推断", bad.length() == 0,
                bad.length() == 0 ? "6 种扩展名" : "错： " + bad));
    }

    private static void checkDocx(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.docx");
        String r = Office.write(c, "docx", f.getAbsolutePath(),
                "{\"title\":\"自检\",\"blocks\":[{\"type\":\"p\",\"text\":\"正文\"},"
                        + "{\"type\":\"table\",\"rows\":[[\"a\",\"b\"],[\"1\",\"2\"]]}]}");
        boolean ok = f.isFile() && zipHas(f, "word/document.xml");
        String detail = ok ? "含表格" : (r.startsWith("错误") ? r : "结构缺失");
        out.add(new Item("docx 生成", ok, detail));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkXlsx(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.xlsx");
        Office.write(c, "xlsx", f.getAbsolutePath(),
                "{\"sheets\":[{\"name\":\"S\",\"rows\":[[\"月份\",\"收入\"],[\"1月\",42],[\"合计\",\"=SUM(B2:B2)\"]]}]}");
        boolean ok = f.isFile() && zipHas(f, "xl/workbook.xml") && zipHas(f, "xl/worksheets/sheet1.xml");
        boolean formula = ok && zipContains(f, "xl/worksheets/sheet1.xml", "<f>SUM");
        out.add(new Item("xlsx 生成", ok, ok ? (formula ? "含公式" : "无公式") : "结构缺失"));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkPptx(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.pptx");
        Office.write(c, "pptx", f.getAbsolutePath(),
                "{\"slides\":[{\"title\":\"标题\",\"bullets\":[\"要点一\",\"要点二\"]}]}");
        boolean ok = f.isFile() && zipHas(f, "ppt/presentation.xml") && zipHas(f, "ppt/slides/slide1.xml");
        out.add(new Item("pptx 生成", ok, ok ? "1 页" : "结构缺失"));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkPdf(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.pdf");
        // 把写入器返回的原文带出来：失败时要能直接看到原因，而不是"文件头不对"
        String msg = Office.write(c, "pdf", f.getAbsolutePath(),
                "{\"pages\":[{\"title\":\"标题\",\"lines\":[\"第一行\",\"第二行\"]}]}");
        byte[] head = head(f, 5);
        boolean ok = head != null && new String(head).startsWith("%PDF");
        String detail = ok ? (f.length() / 1024) + " KB"
                : (msg == null ? "" : msg.replace("\n", " "));
        out.add(new Item("pdf 生成", ok, detail));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkWav(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.wav");
        Office.write(c, "wav", f.getAbsolutePath(), "{\"tone\":440,\"seconds\":0.2}");
        byte[] h = head(f, 44);
        boolean ok = false;
        String why = "太短";
        if (h != null && h.length >= 44) {
            String riff = new String(h, 0, 4);
            String wave = new String(h, 8, 4);
            int rate = (h[24] & 0xff) | ((h[25] & 0xff) << 8) | ((h[26] & 0xff) << 16) | ((h[27] & 0xff) << 24);
            int ch = (h[22] & 0xff) | ((h[23] & 0xff) << 8);
            ok = "RIFF".equals(riff) && "WAVE".equals(wave) && rate == 44100 && ch == 1;
            why = "小端 " + rate + "Hz/" + ch + "声道";
        }
        out.add(new Item("wav 文件头", ok, why));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkM4a(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.m4a");
        String r = Office.write(c, "m4a", f.getAbsolutePath(),
                "{\"notes\":[{\"freq\":440,\"dur\":0.2}]}");
        boolean ok = ftyp(f);
        out.add(new Item("m4a（AAC）生成", ok,
                ok ? "ftyp 正常" : (r.startsWith("错误") ? r : "无 ftyp")));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkMp4(List<Item> out, Context c, File dir) {
        File f = new File(dir, "t.mp4");
        String r = Office.write(c, "mp4", f.getAbsolutePath(),
                "{\"size\":[320,180],\"fps\":10,\"slides\":[{\"title\":\"T\",\"seconds\":0.5}]}");
        boolean ok = ftyp(f);
        out.add(new Item("mp4（H.264）生成", ok,
                ok ? "ftyp 正常" : (r.startsWith("错误") ? r : "无 ftyp")));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void checkWorkspaces(List<Item> out, Context c) {
        String name = "__selfcheck__";
        File d = new File(c.getCacheDir(), "wscheck");
        Workspaces.W w = Workspaces.create(c, name, d.getAbsolutePath());
        boolean created = Workspaces.exists(c, w.id);
        Workspaces.remove(c, w.id);
        boolean removed = !Workspaces.exists(c, w.id);
        out.add(new Item("工作区增删", created && removed,
                created ? (removed ? "" : "删不掉") : "建不了"));
    }

    private static void checkSession(List<Item> out, Context c) {
        try {
            Session s = Session.create(c);
            // 新建时只有索引；正文要等第一条消息写下去。所以这里跑一遍完整往返：
            // 写一条 → 文件存在 → 读回来还是那一条。
            JSONArray one = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "selfcheck");
            one.put(m);
            Session.saveAll(c, s.id, one);
            File body = Session.body(c, s.id);
            boolean written = body.isFile() && body.length() > 0;
            boolean listed = false;
            for (Session x : Session.list(c)) if (x.id.equals(s.id)) listed = true;
            JSONArray back = Session.load(c, s.id);
            boolean roundtrip = back.length() == 1
                    && "selfcheck".equals(back.optJSONObject(0).optString("content"));
            out.add(new Item("会话读写往返", written && listed && roundtrip,
                    !written ? "正文没写出来"
                            : !listed ? "索引里没有"
                            : !roundtrip ? "读回来对不上" : "1 条往返一致"));
        } catch (Throwable e) {
            out.add(new Item("会话读写往返", false, e.toString()));
        }
    }

    /** tool 消息必须跟在它对应的 tool_calls 后面，否则 API 直接 400。 */
    /** 上下文压缩：超长工具结果要截断，历史要能收缩。 */
    private static void checkCompact(List<Item> out) {
        try {
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "sys");
            msgs.put(sys);
            for (int i = 0; i < 40; i++) {
                JSONObject u = new JSONObject();
                u.put("role", "user");
                u.put("content", "第" + i + "轮提问");
                msgs.put(u);
                JSONObject a = new JSONObject();
                a.put("role", "assistant");
                a.put("content", "第" + i + "轮回答");
                msgs.put(a);
                JSONObject t = new JSONObject();
                t.put("role", "tool");
                t.put("content", repeat('x', 30000));   // 每轮 3 万字符的输出
                msgs.put(t);
            }
            int before = History.chars(msgs);
            boolean changed = History.compact(msgs, 14);
            int after = History.chars(msgs);
            boolean shorten = after < before / 2;
            boolean keptSys = "system".equals(msgs.optJSONObject(0).optString("role"));
            out.add(new Item("上下文压缩", changed && shorten && keptSys,
                    before + " → " + after + " 字符"));
        } catch (Throwable e) {
            out.add(new Item("上下文压缩", false, e.toString()));
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
    private static void checkJsonPairing(List<Item> out) {
        try {
            JSONArray msgs = new JSONArray();
            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            JSONArray calls = new JSONArray();
            JSONObject call = new JSONObject();
            call.put("id", "call_1");
            JSONObject fn = new JSONObject();
            fn.put("name", "sh");
            fn.put("arguments", "{}");
            call.put("function", fn);
            calls.put(call);
            a.put("tool_calls", calls);
            msgs.put(a);

            JSONObject good = new JSONObject();
            good.put("role", "tool");
            good.put("tool_call_id", "call_1");
            msgs.put(good);

            JSONObject orphan = new JSONObject();
            orphan.put("role", "tool");
            orphan.put("tool_call_id", "call_999");
            msgs.put(orphan);

            int dropped = History.dropOrphans(msgs);
            out.add(new Item("孤儿 tool 消息清理", dropped == 1 && msgs.length() == 2,
                    "丢掉 " + dropped + " 条，剩 " + msgs.length()));
        } catch (Throwable e) {
            out.add(new Item("孤儿 tool 消息清理", false, e.toString()));
        }
    }

    // ---------------- 小工具 ----------------

    private static void clean(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** 排版与附件规则：这些都是"悄悄坏掉"的类型，必须有断言盯着。 */
    private static void checkTypography(List<Item> out, Context c, File dir) {
        File d = new File(dir, "typo.docx");
        String r1 = Office.write(c, "docx", d.getAbsolutePath(),
                "{\"title\":\"标题\",\"blocks\":[{\"type\":\"h1\",\"text\":\"小标题\"},"
                        + "{\"type\":\"p\",\"text\":\"正文\"},{\"type\":\"table\",\"rows\":[[\"a\",\"b\"]]}]}");
        String xml = unzipEntry(d, "word/document.xml");
        boolean ok = xml != null && xml.contains("<w:color w:val=") && xml.contains("<w:spacing")
                && xml.contains("<w:pgMar");
        out.add(new Item("docx 排版", ok, ok ? "颜色/间距/页边距" : r1));

        File p = new File(dir, "typo.pptx");
        String r2 = Office.write(c, "pptx", p.getAbsolutePath(),
                "{\"slides\":[{\"title\":\"第一页\",\"bullets\":[\"要点\"]},{\"title\":\"第二页\"}]}");
        String s1 = unzipEntry(p, "ppt/slides/slide1.xml");
        File side = new File(dir, "typo.preview.html");
        boolean ok2 = s1 != null && s1.contains("srgbClr") && s1.contains(">1 / 2<")
                && side.isFile() && side.length() > 200;
        out.add(new Item("pptx 排版与预览", ok2, ok2 ? "色条/页码/配套 HTML" : r2));
    }

    /** 附件规则：脚本和中间产物不能挂卡片，成品必须能挂。 */
    private static void checkAttach(List<Item> out, Context c, File dir) {
        File script = new File(dir, "build_ppt.py");
        File probe = new File(dir, "probe_min.docx");
        File pdf = new File(dir, "报告.pdf");
        File dex = new File(dir, "sheet.xlsx");
        try {
            for (File f : new File[]{script, probe, pdf, dex}) {
                FileOutputStream os = new FileOutputStream(f);
                os.write("x".getBytes("UTF-8"));
                os.close();
            }
        } catch (Throwable ignored) {}
        boolean ok = !Attach.worth(script) && !Attach.worth(probe)
                && Attach.worth(pdf) && Attach.worth(dex);
        out.add(new Item("附件筛选", ok, ok ? "挡掉脚本/探针，放行成品" : "规则漏了"));
    }

    /**
     * 地基：app 沙箱里能不能起子进程并稳定收发文本。
     *
     * MCP 的 stdio 传输就建在这上面。上一轮直接做 MCP，失败了却分不清是
     * "进程读写出问题"还是"MCP 协议处理有问题"。这两项先单独证明。
     */
    private static void checkProcessIo(List<Item> out) {
        // 1) cat 回声：写一行、读一行
        try {
            Process p = new ProcessBuilder("/system/bin/cat").start();
            java.io.OutputStream in = p.getOutputStream();
            in.write("hello-io\n".getBytes("UTF-8"));
            in.flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            p.destroy();
            boolean ok = "hello-io".equals(line);
            out.add(new Item("子进程读写", ok, ok ? "cat 回声正常" : ("收到 " + line)));
        } catch (Throwable e) {
            out.add(new Item("子进程读写", false, String.valueOf(e.getMessage())));
        }

        // 2) 一行一答：连续三轮，验证不会在第二次之后失联
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c",
                    "while read l; do echo \"got:$l\"; done").start();
            java.io.OutputStream in = p.getOutputStream();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder seen = new StringBuilder();
            for (int i = 1; i <= 3; i++) {
                in.write(("m" + i + "\n").getBytes("UTF-8"));
                in.flush();
                String line = r.readLine();
                seen.append(line == null ? "(null)" : line).append(' ');
            }
            p.destroy();
            boolean ok = "got:m1 got:m2 got:m3 ".equals(seen.toString());
            out.add(new Item("连续请求应答", ok, ok ? "三轮都收到" : seen.toString().trim()));
        } catch (Throwable e) {
            out.add(new Item("连续请求应答", false, String.valueOf(e.getMessage())));
        }
    }

    /** MCP stdio 端到端：initialize → tools/list → tools/call 走一遍。 */
    private static void checkMcpStdio(List<Item> out, Context c, File dir) {
        File srv = new File(dir, "mock_mcp.sh");
        String script = "#!/system/bin/sh\n"
                + "while IFS= read -r line; do\n"
                + "  id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                + "  [ -z \"$id\" ] && continue\n"
                + "  printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"ping\",\"description\":\"pong\",\"inputSchema\":{\"type\":\"object\"}}],\"content\":[{\"type\":\"text\",\"text\":\"pong from mock\"}]}}\\n' \"$id\"\n"
                + "done\n";
        try {
            FileOutputStream os = new FileOutputStream(srv);
            os.write(script.getBytes("UTF-8"));
            os.close();
            Runtime.getRuntime().exec(new String[]{"chmod", "755", srv.getAbsolutePath()}).waitFor();
        } catch (Throwable e) {
            out.add(new Item("MCP stdio 端到端", false, "写假 server 失败"));
            return;
        }
        try {
            Mcp.setAppContext(c.getApplicationContext());
            Mcp.Server s = new Mcp.Server("selftest" + System.currentTimeMillis(), "", true);
            s.command = "sh " + srv.getAbsolutePath();
            java.util.List<JSONObject> tools = Mcp.listTools(s);
            String listed = tools.isEmpty() ? "" : tools.get(0).optString("name");
            String called = Mcp.call(s, "ping", new JSONObject());
            boolean ok = "ping".equals(listed) && called.contains("pong");
            out.add(new Item("MCP stdio 端到端", ok,
                    ok ? "list=" + listed + " call=ok"
                       : ("list=" + listed + " call=" + called)));
        } catch (Throwable e) {
            out.add(new Item("MCP stdio 端到端", false, String.valueOf(e.getMessage())));
        }
    }

    private static String unzipEntry(File zipF, String name) {
        try {
            ZipFile z = new ZipFile(zipF);
            ZipEntry e = z.getEntry(name);
            if (e == null) { z.close(); return null; }
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            z.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
    private static byte[] head(File f, int n) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[n];
            int read = 0;
            while (read < n) {
                int r = in.read(b, read, n - read);
                if (r <= 0) break;
                read += r;
            }
            in.close();
            return read == n ? b : java.util.Arrays.copyOf(b, read);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean ftyp(File f) {
        byte[] h = head(f, 12);
        return h != null && h.length >= 8 && "ftyp".equals(new String(h, 4, 4));
    }

    private static boolean zipHas(File f, String entry) {
        try {
            ZipFile z = new ZipFile(f);
            ZipEntry e = z.getEntry(entry);
            boolean ok = e != null && e.getSize() != 0;
            z.close();
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean zipContains(File f, String entry, String needle) {
        try {
            ZipFile z = new ZipFile(f);
            ZipEntry e = z.getEntry(entry);
            if (e == null) { z.close(); return false; }
            java.io.InputStream in = z.getInputStream(e);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            z.close();
            return bo.toString("UTF-8").contains(needle);
        } catch (Throwable e) {
            return false;
        }
    }
}
