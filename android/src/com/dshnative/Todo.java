package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * 待办清单：让模型把多步任务摊开、逐条推进、做完打勾。
 *
 * 为什么要它：长任务最容易"做着做着偏题"——模型忘了还剩哪几步，用户也看不出进度。
 * 桌面版有 todo 工具，手机端一直没有。
 *
 * 存法：每个会话一份 <files>/todos/<session>.json。放文件而不是内存，
 * 是因为手机进程随时可能被杀，回来还得接着做。
 */
public final class Todo {

    private Todo() {}

    public static final class Item {
        public String text;
        public String status;   // pending | doing | done

        Item(String text, String status) {
            this.text = text;
            this.status = status;
        }
    }

    private static File file(Context c, String sessionId) {
        File d = new File(c.getFilesDir(), "todos");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return new File(d, (sessionId == null || sessionId.isEmpty() ? "default" : sessionId) + ".json");
    }

    public static java.util.List<Item> load(Context c, String sessionId) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        File f = file(c, sessionId);
        if (f == null || !f.isFile()) return out;
        try {
            JSONArray arr = new JSONArray(read(f));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Item(o.optString("text", ""), o.optString("status", "pending")));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 整表替换。这里不做合并：模型给的就是它此刻认定的计划。 */
    public static void save(Context c, String sessionId, java.util.List<Item> items) {
        File f = file(c, sessionId);
        if (f == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("text", it.text);
                o.put("status", it.status);
                arr.put(o);
            }
            FileOutputStream os = new FileOutputStream(f);
            os.write(arr.toString().getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }

    public static void clear(Context c, String sessionId) {
        File f = file(c, sessionId);
        if (f != null && f.isFile()) f.delete();
    }

    /** 给人看的一行版本：进度 + 当前在做的那条。 */
    public static String summary(Context c, String sessionId) {
        java.util.List<Item> items = load(c, sessionId);
        if (items.isEmpty()) return "";
        int done = 0;
        String doing = null;
        for (Item it : items) {
            if ("done".equals(it.status)) done++;
            if ("doing".equals(it.status) && doing == null) doing = it.text;
        }
        String s = "待办 " + done + "/" + items.size();
        if (doing != null) s += " · 在做：" + doing;
        return s;
    }

    /** 给模型看的清单文本。 */
    public static String render(Context c, String sessionId) {
        java.util.List<Item> items = load(c, sessionId);
        if (items.isEmpty()) return "（清单是空的）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String mark = "done".equals(it.status) ? "[x]"
                    : ("doing".equals(it.status) ? "[>]" : "[ ]");
            sb.append(i + 1).append(". ").append(mark).append(' ').append(it.text).append('\n');
        }
        return sb.toString().trim();
    }

    private static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "[]";
        }
    }
}
