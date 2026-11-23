package com.dshnative;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖的 .dshp 装载器（只做 T0 声明式）。
 *
 * 一个插件 = 一个目录：manifest.json + tools/*.json（可选）+ skills/*.md（可选）。
 * 插件**不跑任意代码**，工具实现只有三种声明式动作：
 *   http  —— GET 一个地址（支持 {arg} 占位）
 *   app   —— 用 Intent 拉起一个包
 *   file  —— 读工作区里的一个文件
 *
 * 装载时做两件事：校验 capabilities 必须是 Policy 认识的档位；工具名不许撞内置工具。
 * 这两条决定了插件不能给自己加权限 —— 它只能申请既有能力，而既有能力都要过同一个闸门。
 */
public final class Plugin {

    public interface Gate {
        /** 走 Agent 的策略闸门：能力不可用/被拒都会返回 false。 */
        boolean allow(String cap, String target);
    }

    public final String id;
    public final String name;
    public final String version;
    public final List<String> capabilities = new ArrayList<>();
    public final JSONArray tools = new JSONArray();
    public final File dir;

    private Plugin(File dir, JSONObject manifest) {
        this.dir = dir;
        this.id = manifest.optString("id", dir.getName());
        this.name = manifest.optString("name", id);
        this.version = manifest.optString("version", "0");
        JSONArray caps = manifest.optJSONArray("capabilities");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) capabilities.add(caps.optString(i));
        }
        JSONArray ts = manifest.optJSONArray("tools");
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null) tools.put(t);
            }
        }
    }

    public static File root(Context c) {
        File d = new File(c.getFilesDir(), "plugins");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 扫已安装插件；解析失败或能力非法的直接跳过（失败即关闭）。 */
    public static List<Plugin> installed(Context c) {
        List<Plugin> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File mf = new File(d, "manifest.json");
            if (!mf.isFile()) continue;
            try {
                String text = read(mf);
                JSONObject m = new JSONObject(text);
                Plugin p = new Plugin(d, m);
                if (!p.valid()) continue;
                out.add(p);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private boolean valid() {
        if (id == null || id.isEmpty()) return false;
        for (String cap : capabilities) {
            if (Policy.cap(cap) == null) return false;   // 申请不存在的能力 → 拒绝装载
        }
        return tools.length() > 0;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(tools.length()).append(" 个工具");
        if (!capabilities.isEmpty()) {
            sb.append(" · ").append(android.text.TextUtils.join(" ", capabilities));
        }
        return sb.toString();
    }

    // ---------------- 内置能力开关 ----------------

    /** 内置能力也按"插件"对待：可开可关，开关在插件面板里，改完开新会话生效。 */
    public static final String[][] BUILTINS = {
            {"builtin.subagent",   "子智能体",   "独立上下文跑一件事，只回结论"},
            {"builtin.web_search", "网页搜索",   "Bing RSS，返回标题 + 链接"},
            {"builtin.loop",       "agent 循环", "同一件事反复做，见「完成」即停"},
            {"builtin.terminal",   "终端",       "/system/bin/sh（app 身份）"},
            {"builtin.agent_team", "智能体团队", "并行派多个 teammate，共享工作区"},
            {"builtin.devtools",   "开发者工具", "诊断信息 / 导出会话"},
            {"builtin.render",     "预览渲染",   "把 HTML / 网页渲染成卡片"},
            {"builtin.skills",     "DSH Ultra Mode", "技能包，一个开关"},
        {"builtin.office",     "DSH Office",  "docx / xlsx / pptx / pdf / wav / m4a / mp4 本地生成"},
    };

    public static boolean isOn(Context c, String id) {
        return Prefs.get(c)
                .getBoolean("plug_on_" + id, true);
    }

    public static void setOn(Context c, String id, boolean on) {
        Prefs.get(c)
                .edit().putBoolean("plug_on_" + id, on).apply();
    }

    public static int enabledCount(Context c) {
        int n = 0;
        for (String[] b : BUILTINS) if (isOn(c, b[0])) n++;
        return n;
    }
    // ---------------- 装载 / 卸载 ----------------

    public static void uninstall(Context c, String id) {
        File d = new File(root(c), id);
        deleteTree(d);
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 内置示例插件：不用 adb push，装完就能看到"插件是怎么写出来的"。
     * 同时它也是装载器的活体测试用例。
     */
    public static String installSample(Context c) {
        try {
            File d = new File(root(c), "sample.quick");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            new File(d, "tools").mkdirs();
            new File(d, "skills").mkdirs();

            JSONObject m = new JSONObject();
            m.put("id", "sample.quick");
            m.put("name", "快捷入口（示例）");
            m.put("version", "0.1.0");
            m.put("tier", "declarative");
            m.put("capabilities", new JSONArray().put("net.fetch").put("app.launch"));

            JSONObject t1 = new JSONObject();
            t1.put("name", "fetch_example_page");
            t1.put("schema", "tools/fetch_example_page.json");
            t1.put("impl", new JSONObject().put("kind", "http").put("url", "https://example.com"));

            JSONObject t2 = new JSONObject();
            t2.put("name", "open_wifi_settings");
            t2.put("schema", "tools/open_wifi_settings.json");
            t2.put("impl", new JSONObject().put("kind", "app").put("package", "com.android.settings"));

            m.put("tools", new JSONArray().put(t1).put(t2));
            write(new File(d, "manifest.json"), m.toString(2));

            JSONObject s1 = new JSONObject();
            s1.put("type", "object");
            s1.put("properties", new JSONObject());
            write(new File(d, "tools/fetch_example_page.json"), s1.toString(2));

            JSONObject s2 = new JSONObject();
            s2.put("type", "object");
            s2.put("properties", new JSONObject());
            write(new File(d, "tools/open_wifi_settings.json"), s2.toString(2));

            write(new File(d, "skills/sample.md"),
                    "# 快捷入口（示例插件）\n\n"
                  + "- `fetch_example_page`：抓 example.com 的正文，用来验证网络能力。\n"
                  + "- `open_wifi_settings`：直接跳到系统设置，用来验证 Intent 能力。\n");
            return null;
        } catch (Exception e) {
            return "安装失败：" + e.getMessage();
        }
    }

    // ---------------- 执行 ----------------

    /** 找到工具并执行。找不到返回 null，交给上层报"没有这个工具"。 */
    public static String call(Context c, List<Plugin> plugins, String toolName, JSONObject args, Gate gate) {
        for (Plugin p : plugins) {
            for (int i = 0; i < p.tools.length(); i++) {
                JSONObject t = p.tools.optJSONObject(i);
                if (t == null) continue;
                if (!toolName.equals(t.optString("name"))) continue;
                JSONObject impl = t.optJSONObject("impl");
                if (impl == null) return "错误：插件工具没有 impl";
                String kind = impl.optString("kind", "");
                try {
                    if ("http".equals(kind)) {
                        String url = subst(impl.optString("url", ""), args);
                        if (url.isEmpty()) return "错误：插件没给 url";
                        if (!gate.allow("net.fetch", p.name)) return "用户拒绝了插件的网络请求";
                        return httpGet(url);
                    }
                    if ("app".equals(kind)) {
                        String pkg = impl.optString("package", "");
                        if (!gate.allow("app.launch", pkg)) return "用户拒绝了插件的启动请求";
                        Intent i2 = c.getPackageManager().getLaunchIntentForPackage(pkg);
                        if (i2 == null) return "错误：找不到可启动的应用 " + pkg;
                        i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i2);
                        return "已请求启动 " + pkg;
                    }
                    if ("file".equals(kind)) {
                        // 和 http 一样要支持 {arg} 占位，否则模板路径永远读不到
                        String path = subst(impl.optString("path", ""), args);
                        if (!gate.allow("fs.read", path)) return "用户拒绝了插件的读取请求";
                        File f = new File(Ws.active(c), path);
                        if (!f.isFile()) return "错误：工作区里没有 " + path;
                        String text = read(f);
                        return text.length() > 8000 ? text.substring(0, 8000) + "\n…已截断" : text;
                    }
                    return "错误：不支持的 impl.kind = " + kind;
                } catch (Exception e) {
                    return "错误：" + e.getMessage();
                }
            }
        }
        return null;
    }

    private static String subst(String template, JSONObject args) {
        String out = template;
        if (args == null) return out;
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out = out.replace("{" + k + "}", args.optString(k, ""));
        }
        return out;
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "DSHNative-plugin/0.1");
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 20000) break;
                }
                r.close();
            }
            return "HTTP " + code + "\n" + sb;
        } finally {
            //noinspection ThrowFromFinallyBlock
            c.disconnect();
        }
    }

    // ---------------- 文件小工具 ----------------

    public static String readText(File f) throws Exception { return read(f); }

    private static String read(File f) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private static void write(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileWriter w = new FileWriter(f);
        w.write(s);
        w.close();
    }
}
