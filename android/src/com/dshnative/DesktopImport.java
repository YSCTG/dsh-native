package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把**电脑版插件**转成 Native 插件。
 *
 * 能转什么、不能转什么，先说清楚（这决定了这套东西的可信度）：
 *
 *  - 电脑版插件是 npm/Cordis 的 **JS 模块**，需要 Node 运行时。手机上没有 Node，
 *    所以它的代码**不能原样运行**——任何声称"一键迁移 JS"的做法都是假的。
 *  - 但它带的**知识**能迁移：README / 描述 / 关键词描述了"什么时候该用它、
 *    按什么步骤做"，这正是 Native 的"技能（提示词）"格式。
 *  - 所以转换的产物是：一个 Native 清单 + 一份技能正文（来自 npm 包 README），
 *    并在清单里写明哪些能力需要 Node，无法在本机执行。
 *
 * 网络：只读 npm registry 的公开元数据（GET /<pkg>/latest），不下载 tarball。
 */
public final class DesktopImport {

    private DesktopImport() {}

    /** 转换结果。 */
    public static final class Result {
        public JSONObject manifest;
        public String error;
        public boolean hasSkills;
        public boolean needsNode;
    }

    /** 从 npm 包名转换。阻塞调用，请放在后台线程。 */
    public static Result fromNpm(Context c, String pkg) {
        Result r = new Result();
        String name = pkg == null ? "" : pkg.trim();
        if (name.isEmpty()) { r.error = "包名为空"; return r; }
        try {
            String url = "https://registry.npmjs.org/" + name.replace("/", "%2F") + "/latest";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                r.error = "npm 上没有这个包（HTTP " + conn.getResponseCode() + "）";
                return r;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject meta = new JSONObject(bo.toString("UTF-8"));

            String pkgName = meta.optString("name", name);
            String version = meta.optString("version", "0.0.0");
            String desc = meta.optString("description", "");

            // README 可能在 /latest 里，也可能要单独取；取不到就用描述兜底
            String readme = meta.optString("readme", "");
            if (readme == null || readme.trim().isEmpty()) readme = desc;

            String id = "desk_" + pkgName.replaceAll("[^A-Za-z0-9]+", "_").toLowerCase();
            if (id.length() > 40) id = id.substring(0, 40);

            JSONObject manifest = new JSONObject();
            manifest.put("id", id);
            manifest.put("name", pkgName);
            manifest.put("version", version);
            manifest.put("category", "桌面转换");
            manifest.put("description", (desc.isEmpty() ? "（无描述）" : desc));
            manifest.put("convertedFrom", "npm:" + pkgName);
            manifest.put("needsNode", true);
            manifest.put("note", "电脑版插件是 Node/Cordis 模块，代码无法在手机上执行；"
                    + "这里迁移的是它的用法知识（技能提示词），不是它的工具实现。");

            // 技能正文：README 截断 + 一段说明，避免模型以为能跑 JS
            StringBuilder body = new StringBuilder();
            body.append("# ").append(pkgName).append("（来自电脑版插件，已转换为提示词）\n\n");
            body.append("本条目由电脑版插件自动转换。原始实现是 npm/Cordis 的 JavaScript 模块，")
                .append("手机上无法执行它的代码；请按下面的说明使用它带来的**方法与流程**，")
                .append("需要真正跑代码时改用本机工具（python / sh / 内置能力）。\n\n");
            String cut = readme.length() > 6000 ? readme.substring(0, 6000) + "\n…（已截断）" : readme;
            body.append(cut);

            JSONArray skills = new JSONArray();
            JSONObject skill = new JSONObject();
            skill.put("id", id);
            skill.put("name", pkgName);
            skill.put("desc", desc.isEmpty() ? ("电脑版插件 " + pkgName + " 的用法") : desc);
            skill.put("body", body.toString());
            skills.put(skill);
            manifest.put("skills", skills);
            manifest.put("tools", new JSONArray());

            r.manifest = manifest;
            r.hasSkills = true;
            r.needsNode = true;
            return r;
        } catch (Throwable t) {
            r.error = "转换失败：" + t;
            return r;
        }
    }

    /** 写入插件清单 + 追加到附加技能表（Skill.all 会合并它）。 */
    public static void install(Context c, JSONObject manifest) {
        try {
            String id = manifest.optString("id", "");
            if (id.isEmpty()) return;
            File dir = new File(c.getFilesDir(), "plugins");
            if (!dir.isDirectory()) dir.mkdirs();
            FileOutputStream os = new FileOutputStream(new File(dir, id + ".json"));
            os.write(manifest.toString().getBytes("UTF-8"));
            os.close();

            File extra = new File(c.getFilesDir(), "skills-extra.json");
            JSONArray arr = new JSONArray();
            if (extra.isFile()) {
                try {
                    String s = Market.read(extra);
                    if (s != null && s.trim().startsWith("[")) arr = new JSONArray(s);
                } catch (Throwable ignored) {}
            }
            JSONArray add = manifest.optJSONArray("skills");
            if (add != null) {
                for (int i = 0; i < add.length(); i++) {
                    JSONObject s = add.optJSONObject(i);
                    if (s == null) continue;
                    // 同 id 覆盖，避免重复导入时堆一串
                    for (int k = 0; k < arr.length(); k++) {
                        if (arr.optJSONObject(k) != null
                                && s.optString("id").equals(arr.optJSONObject(k).optString("id"))) {
                            arr.put(k, s);
                            s = null;
                            break;
                        }
                    }
                    if (s != null) arr.put(s);
                }
            }
            FileOutputStream eo = new FileOutputStream(extra);
            eo.write(arr.toString().getBytes("UTF-8"));
            eo.close();
            Skill.invalidate();
        } catch (Throwable ignored) {}
    }
}
