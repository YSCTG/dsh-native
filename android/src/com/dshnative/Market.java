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

/**
 * Native 插件市场。
 *
 * 和电脑版的区别（这是本节的核心取舍）：
 *  - 电脑版的市场索引列的是 **npm/Cordis 组合包**，装进来要能跑 Node 与 Cordis 运行时；
 *  - 手机端没有 Node，也不该为了装插件塞一套进去。所以 Native 用**声明式清单**：
 *      插件 = 技能（提示词）+ 工具（一段 Python），清单本身就是安装包。
 *  - 于是"装插件"= 把一个 JSON 存到 files/plugins/<id>.json，模型下一轮就多出这些工具；
 *    "卸插件"= 删文件。没有下载、没有依赖、没有版本冲突。
 *
 * 目录来源：内置 assets/market.json；设置里可以填一个远程目录 URL，拉回覆盖成本地缓存。
 * 字段对齐电脑版索引的语义（id / name / version / description / category），
 * 便于以后把桌面索引里的条目手工转成 Native 清单。
 */
public final class Market {

    private Market() {}

    /** 官方示例目录。设置里可以用任意同格式地址覆盖它。 */
    public static final String OFFICIAL_INDEX =
            "https://raw.githubusercontent.com/YSCTG/dsh-native/master/market-index/market.json";

    /** 已安装插件的存放目录。 */
    private static File pluginDir(Context c) {
        File d = new File(c.getFilesDir(), "plugins");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 目录：先看远程缓存，再看内置。 */
    public static JSONObject catalog(Context c) {
        try {
            File cache = new File(c.getFilesDir(), "market-cache.json");
            if (cache.isFile()) {
                String s = read(cache);
                if (s != null && s.trim().startsWith("{")) return new JSONObject(s);
            }
        } catch (Throwable ignored) {}
        try {
            String s = readAsset(c);
            if (s != null) return new JSONObject(s);
        } catch (Throwable ignored) {}
        try { return new JSONObject("{\"format\":1,\"plugins\":[]}"); }
        catch (Throwable t) { return null; }
    }

    public static List<JSONObject> plugins(Context c) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject cat = catalog(c);
        if (cat == null) return out;
        JSONArray arr = cat.optJSONArray("plugins");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null) out.add(p);
        }
        return out;
    }


    /** 目录缓存有多旧（毫秒）；没有缓存返回 Long.MAX_VALUE。 */
    public static long cacheAgeMs(Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "market-cache.json");
            if (!f.isFile()) return Long.MAX_VALUE;
            return System.currentTimeMillis() - f.lastModified();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    public static boolean installed(Context c, String id) {
        return new File(pluginDir(c), id + ".json").isFile();
    }

    /** 插件声明的 MCP 脚本落盘路径。 */
    public static java.io.File mcpScript(Context c, String pluginId, String file) {
        java.io.File dir = new java.io.File(new java.io.File(c.getFilesDir(), "plugins"), "mcp_" + pluginId);
        if (!dir.isDirectory()) dir.mkdirs();
        return new java.io.File(dir, file == null || file.isEmpty() ? "server.py" : file);
    }

    public static void install(Context c, JSONObject plugin) {
        try {
            String id = plugin.optString("id", "");
            if (id.isEmpty()) return;
            // 插件若声明了 MCP 服务，把它的脚本落盘，并把真实路径写进清单，
            // 这样运行期（Mcp.all）只认一条绝对路径，不用再猜。
            // 钩子脚本落盘（与导入路径保持一致）
            try {
                org.json.JSONObject capH = plugin.optJSONObject("capabilities");
                org.json.JSONArray hooks = capH == null ? plugin.optJSONArray("hooks")
                        : capH.optJSONArray("hooks");
                for (int hi = 0; hooks != null && hi < hooks.length(); hi++) {
                    org.json.JSONObject h = hooks.optJSONObject(hi);
                    if (h == null || h.optString("code", "").isEmpty()) continue;
                    java.io.File hf = mcpScript(c, id + "_hook" + hi, h.optString("file", "hook" + hi + ".py"));
                    java.io.FileOutputStream ho = new java.io.FileOutputStream(hf);
                    ho.write(h.optString("code", "").getBytes("UTF-8"));
                    ho.close();
                    h.put("script", hf.getAbsolutePath());
                }
            } catch (Throwable ignored) {}

            try {
                org.json.JSONObject cap = plugin.optJSONObject("capabilities");
                org.json.JSONObject mcp = cap == null ? plugin.optJSONObject("mcp") : cap.optJSONObject("mcp");
                if (mcp != null && mcp.optString("code", "").length() > 0) {
                    java.io.File f = mcpScript(c, id, mcp.optString("file", "server.py"));
                    java.io.FileOutputStream mo = new java.io.FileOutputStream(f);
                    mo.write(mcp.optString("code", "").getBytes("UTF-8"));
                    mo.close();
                    mcp.put("script", f.getAbsolutePath());
                }
            } catch (Throwable ignored) {}

            try { plugin.put("_sha256", sha256(plugin.toString())); } catch (Throwable ignored) {}
            FileOutputStream os = new FileOutputStream(new File(pluginDir(c), id + ".json"));
            os.write(plugin.toString().getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }

    public static void uninstall(Context c, String id) {
        try {
            File f = new File(pluginDir(c), id + ".json");
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) {}
    }

    /** 已安装的插件清单（读文件，不信内存）。 */
    public static List<JSONObject> installedPlugins(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] files = pluginDir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject p = new JSONObject(read(f));
                if (!p.optString("id", "").isEmpty()) out.add(p);
            } catch (Throwable ignored) {}
        }
        return out;
    }


    /** 开关存在 prefs 里：plugin_on_<id>，默认开。 */
    private static String enableKey(String id) { return "plugin_on_" + id; }

    public static boolean isOn(Context c, String id) {
        try { return Prefs.get(c).getBoolean(enableKey(id), true); }
        catch (Throwable t) { return true; }
    }

    public static void setOn(Context c, String id, boolean on) {
        try { Prefs.get(c).edit().putBoolean(enableKey(id), on).apply(); }
        catch (Throwable ignored) {}
        Skill.invalidate();
    }

    /** 已安装且**启用**的插件（工具注册与技能合并都只认这一份）。 */
    public static List<JSONObject> enabledPlugins(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : installedPlugins(c)) {
            if (isOn(c, p.optString("id", ""))) out.add(p);
        }
        return out;
    }

    /** 工具名统一前缀，方便在 Agent 里识别与回收。 */
    public static final String PREFIX = "mkt_";

    public static String toolName(String pluginId, String tool) {
        return PREFIX + pluginId + "_" + tool;
    }

    /** 找到某个工具的定义（含它所属插件）。 */
    public static JSONObject[] findTool(Context c, String fullName) {
        for (JSONObject p : installedPlugins(c)) {
            JSONArray tools = p.optJSONArray("tools");
            if (tools == null) continue;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                if (toolName(p.optString("id"), t.optString("name")).equals(fullName)) {
                    return new JSONObject[]{p, t};
                }
            }
        }
        return null;
    }

    /**
     * 执行插件工具。
     *
     * 契约：清单里的 python 片段拿到一个已解析好的 ARGS(dict)，把结果 print 到 stdout。
     * 这样插件作者不需要处理 stdin/参数解析，代码就是普通脚本。
     */

    /**
     * 插件声明的权限。默认全给（老清单不受影响），显式写 false 才收窄。
     * 收窄不是"提示"，是真的拦：见 sandboxPreamble()。
     */
    public static String[] perms(JSONObject p) {
        JSONObject perm = p.optJSONObject("permissions");
        boolean net = perm == null || perm.optBoolean("net", true);
        boolean shell = perm == null || perm.optBoolean("shell", true);
        return new String[]{net ? "net" : "-net", shell ? "shell" : "-shell"};
    }

    /**
     * 在插件 Python 代码前面插入的沙箱代码。
     *
     * 为什么能拦：插件工具是我们自己起解释器跑的，代码由我们拼装，
     * 所以在它之前把 socket / subprocess 换成抛异常的桩，就能真的挡住。
     * （文件系统不在这里限制：路径约束需要逐调用判断，留给钩子插件自己仲裁。）
     */
    public static String sandboxPreamble(JSONObject p) {
        JSONObject perm = p.optJSONObject("permissions");
        if (perm == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!perm.optBoolean("net", true)) {
            sb.append("import socket as _s\n")
              .append("class _NoNet(_s.socket):\n")
              .append("    def __init__(self, *a, **k):\n")
              .append("        raise PermissionError('插件未声明 net 权限，禁止联网')\n")
              .append("_s.socket = _NoNet\n")
              .append("try:\n")
              .append("    import urllib.request as _u\n")
              .append("    def _blocked(*a, **k): raise PermissionError('插件未声明 net 权限，禁止联网')\n")
              .append("    _u.urlopen = _blocked\n")
              .append("except Exception: pass\n");
        }
        if (!perm.optBoolean("shell", true)) {
            sb.append("import os as _os, subprocess as _sp\n")
              .append("def _no(*a, **k): raise PermissionError('插件未声明 shell 权限，禁止起子进程')\n")
              .append("_os.system = _no\n_os.popen = _no\n_os.execv = _no\n_os.spawnv = _no\n")
              .append("_sp.Popen = _no\n_sp.run = _no\n_sp.call = _no\n");
        }
        return sb.toString();
    }

    /** 清单指纹：用于"装的东西没被换过"的校验。 */
    public static String sha256(String text) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }


    /**
     * 插件配置 = 清单里声明的默认值 + 用户保存的值。
     *
     * 只返回用户存的还不够：插件第一次跑、用户还没配置时，默认值应该已经在 CFG 里，
     * 否则工具拿到的全是 None，插件得自己写一堆兜底代码。
     */

    /** 已装插件的版本号；没装返回空串。 */
    public static String installedVersion(Context c, String id) {
        try {
            java.io.File f = new java.io.File(pluginDir(c), id + ".json");
            if (!f.isFile()) return "";
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new org.json.JSONObject(sb.toString()).optString("version", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** a 是否比 b 新（点分数字比较）。 */
    public static boolean isNewerThan(String a, String b) {
        try {
            String[] x = a.replace("v", "").split("\\.");
            String[] y = b.replace("v", "").split("\\.");
            int n = Math.max(x.length, y.length);
            for (int i = 0; i < n; i++) {
                int xi = i < x.length ? Integer.parseInt(x[i].replaceAll("[^0-9]", "0")) : 0;
                int yi = i < y.length ? Integer.parseInt(y[i].replaceAll("[^0-9]", "0")) : 0;
                if (xi != yi) return xi > yi;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static org.json.JSONObject cfg(Context c, org.json.JSONObject plugin) {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            String id = plugin.optString("id", "");
            org.json.JSONArray fields = settings(plugin);
            for (int i = 0; i < fields.length(); i++) {
                org.json.JSONObject f = fields.optJSONObject(i);
                if (f == null) continue;
                String key = f.optString("key", "");
                if (key.isEmpty()) continue;
                if (f.has("default")) out.put(key, f.opt("default"));
            }
            String raw = Prefs.get(c).getString("plugin_cfg_" + id, "{}");
            org.json.JSONObject saved = new org.json.JSONObject(raw);
            java.util.Iterator<String> it = saved.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, saved.opt(k));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveCfg(Context c, String id, org.json.JSONObject v) {
        Prefs.get(c).edit().putString("plugin_cfg_" + id,
                v == null ? "{}" : v.toString()).apply();
    }

    /** 插件声明的配置字段（capabilities.settings）。 */
    public static org.json.JSONArray settings(org.json.JSONObject p) {
        org.json.JSONObject cap = p.optJSONObject("capabilities");
        org.json.JSONArray arr = cap == null ? p.optJSONArray("settings") : cap.optJSONArray("settings");
        return arr == null ? new org.json.JSONArray() : arr;
    }

    public static String run(Context c, String fullName, JSONObject args, int timeoutSec) {
        JSONObject[] hit = findTool(c, fullName);
        if (hit == null) return "错误：没有这个插件工具 " + fullName;
        String code = sandboxPreamble(hit[0]) + hit[1].optString("python", "");
        if (code.trim().isEmpty()) return "错误：插件工具没有实现";
        String payload = args == null ? "{}" : args.toString();
        String cfg = cfg(c, hit[0]).toString();
        String src = "import json\n"
                + "ARGS = json.loads(r'''" + payload.replace("'''", "\\'\\'\\'") + "''')\n"
                + "CFG = json.loads(r'''" + cfg.replace("'''", "\\'\\'\\'") + "''')\n"
                + code;
        Python.Result r = Python.run(c, src, (int) Math.max(5, timeoutSec) * 1000);
        String out = r.out == null ? "" : r.out;
        if (out.length() > 8000) out = out.substring(0, 8000) + "\n…已截断";
        return "exit=" + r.code + "  (" + r.ms + "ms)\n" + out;
    }

    /** 插件里附带的技能（提示词），供 Skill 列表合并。 */
    public static List<JSONObject> installedSkills(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : enabledPlugins(c)) {
            JSONArray arr = p.optJSONArray("skills");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null) out.add(s);
            }
        }
        return out;
    }


    /** 当前目录来源：远程缓存还是内置示例。 */
    public static String sourceLabel(Context c) {
        File cache = new File(c.getFilesDir(), "market-cache.json");
        if (cache.isFile()) {
            String host = "远程";
            try {
                String url = Prefs.get(c).getString("market_url", "");
                if (url != null && url.contains("://")) {
                    host = url.substring(url.indexOf("://") + 3);
                    int slash = host.indexOf('/');
                    if (slash > 0) host = host.substring(0, slash);
                }
            } catch (Throwable ignored) {}
            return host;
        }
        return "内置示例";
    }

    /** 来源详情：远程地址或内置说明，带缓存更新时间。 */
    public static String sourceDetail(Context c) {
        File cache = new File(c.getFilesDir(), "market-cache.json");
        if (cache.isFile()) {
            String stamp = new java.text.SimpleDateFormat("MM-dd HH:mm")
                    .format(new java.util.Date(cache.lastModified()));
            return "远程目录 · 更新于 " + stamp + "（点按修改地址或刷新）";
        }
        String url = "";
        try { url = Prefs.get(c).getString("market_url", ""); } catch (Throwable ignored) {}
        return url == null || url.isEmpty()
                ? "内置示例目录 · 点按填入任意 market.json 地址即可换成社区目录"
                : "未拉取 · 已配置 " + url;
    }

    static String readAsset(Context c) {
        try {
            InputStream in = c.getAssets().open("market.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
