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

    public static boolean installed(Context c, String id) {
        return new File(pluginDir(c), id + ".json").isFile();
    }

    public static void install(Context c, JSONObject plugin) {
        try {
            String id = plugin.optString("id", "");
            if (id.isEmpty()) return;
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
    public static String run(Context c, String fullName, JSONObject args, int timeoutSec) {
        JSONObject[] hit = findTool(c, fullName);
        if (hit == null) return "错误：没有这个插件工具 " + fullName;
        String code = hit[1].optString("python", "");
        if (code.trim().isEmpty()) return "错误：插件工具没有实现";
        String payload = args == null ? "{}" : args.toString();
        String src = "import json\n"
                + "ARGS = json.loads(r'''" + payload.replace("'''", "\\'\\'\\'") + "''')\n"
                + code;
        Python.Result r = Python.run(c, src, (int) Math.max(5, timeoutSec) * 1000);
        String out = r.out == null ? "" : r.out;
        if (out.length() > 8000) out = out.substring(0, 8000) + "\n…已截断";
        return "exit=" + r.code + "  (" + r.ms + "ms)\n" + out;
    }

    /** 插件里附带的技能（提示词），供 Skill 列表合并。 */
    public static List<JSONObject> installedSkills(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : installedPlugins(c)) {
            JSONArray arr = p.optJSONArray("skills");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null) out.add(s);
            }
        }
        return out;
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
