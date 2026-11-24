package com.dshnative;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DSH Ultra Mode：29 个 ds-* 技能，随 APK 一起发（assets/skills.json）。
 *
 * 「集成」在这里的含义很具体：
 *  - 一个总开关（builtin.skills），关掉就完全不进系统提示、也不注册 skill 工具
 *  - 每个技能一个开关，默认全开；关掉的不进提示、也不能被 skill 读到
 *  - 模型看到的是「技能目录 + 什么时候该读」，正文按需用 skill 工具取，不塞进每一轮
 */
public final class Skill {

    public static final class S {
        public final String id;
        public final String name;
        public final String desc;
        public final String body;

        S(String id, String name, String desc, String body) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.body = body;
        }
    }

    private static List<S> cache;

    private Skill() {}

    /** 技能表变了（装了/卸了插件）要作废缓存。 */
    public static synchronized void invalidate() { cache = null; }

    public static synchronized List<S> all(Context c) {
        if (cache != null) return cache;
        List<S> out = new ArrayList<>();
        try {
            InputStream in = c.getAssets().open("skills.json");
            byte[] buf = new byte[in.available()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) break;
                read += n;
            }
            in.close();
            JSONArray arr = new JSONArray(new String(buf, 0, read, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new S(o.optString("id"), o.optString("name"),
                        o.optString("desc"), o.optString("body")));
            }
        } catch (Exception ignored) {}
        // 插件带来的技能（含电脑版转换过来的）：files/skills-extra.json
        try {
            java.io.File extra = new java.io.File(c.getFilesDir(), "skills-extra.json");
            if (extra.isFile()) {
                String txt = Market.read(extra);
                if (txt != null && txt.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(txt);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String id = o.optString("id", "");
                        if (id.isEmpty()) continue;
                        boolean dup = false;
                        for (S e : out) if (e.id.equals(id)) { dup = true; break; }
                        if (!dup) out.add(new S(id, o.optString("name", id),
                                o.optString("desc", ""), o.optString("body", "")));
                    }
                }
            }
        } catch (Throwable ignored) {}
        cache = out;
        return out;
    }

    public static S byId(Context c, String id) {
        if (id == null) return null;
        for (S s : all(c)) if (s.id.equals(id) || s.name.equals(id)) return s;
        return null;
    }

    /** 整包一起用：开关只有总开关一个，所以这里就是全部。 */
    public static List<S> enabled(Context c) {
        return all(c);
    }

    public static int enabledCount(Context c) {
        return enabled(c).size();
    }

    /** 一行一个：给系统提示用。描述截到 first sentence，省上下文。 */
    public static String catalog(Context c) {
        StringBuilder sb = new StringBuilder();
        for (S s : enabled(c)) {
            String d = s.desc == null ? "" : s.desc.replace('\n', ' ').trim();
            int dot = d.indexOf(". ");
            if (dot > 20) d = d.substring(0, dot + 1);
            if (d.length() > 110) d = d.substring(0, 110) + "…";
            sb.append("- ").append(s.id);
            if (!d.isEmpty()) sb.append(" — ").append(d);
            sb.append('\n');
        }
        return sb.toString();
    }
}
