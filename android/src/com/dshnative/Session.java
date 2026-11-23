package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 会话存储：一条会话 = 一个 jsonl + 索引里的一行。
 *
 * 为什么用 jsonl：手机随时会被杀，append-only 的行式日志最抗崩溃 —— 写坏一行不影响前面。
 * 索引单独存（index.json），列表页只读索引，不去扫所有会话文件。
 */
public final class Session {

    public final String id;
    public String title = "";
    public long updated;
    public long tokensIn, tokensOut;
    /** 置顶：抽屉里永远排最前。 */
    public boolean pinned;
    public double cost;

    public Session(String id) {
        this.id = id;
        this.updated = System.currentTimeMillis();
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File body(Context c, String id) {
        return new File(dir(c), id + ".jsonl");
    }

    private static File indexFile(Context c) {
        return new File(dir(c), "index.json");
    }

    public static String newId() {
        return "s" + Long.toString(System.currentTimeMillis(), 36);
    }

    public static Session create(Context c) {
        Session s = new Session(newId());
        s.title = "新会话";
        List<Session> all = list(c);
        all.add(0, s);
        saveIndex(c, all);
        return s;
    }

    public static List<Session> list(Context c) {
        List<Session> out = new ArrayList<>();
        File f = indexFile(c);
        if (!f.isFile()) return out;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Session s = new Session(o.optString("id"));
                s.title = o.optString("title", "新会话");
                s.updated = o.optLong("updated", 0);
                s.tokensIn = o.optLong("in", 0);
                s.tokensOut = o.optLong("out", 0);
                s.cost = o.optDouble("cost", 0);
                s.pinned = o.optBoolean("pinned", false);
                out.add(s);
            }
        } catch (Exception ignored) {}
        Collections.sort(out, new Comparator<Session>() {
            @Override public int compare(Session a, Session b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;   // 置顶的在最前
                return Long.compare(b.updated, a.updated);
            }
        });
        return out;
    }

    public static void saveIndex(Context c, List<Session> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Session s : all) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("updated", s.updated);
                o.put("in", s.tokensIn);
                o.put("out", s.tokensOut);
                o.put("cost", s.cost);
                o.put("pinned", s.pinned);
                arr.put(o);
            }
            FileWriter w = new FileWriter(indexFile(c));
            w.write(arr.toString());
            w.close();
        } catch (Exception ignored) {}
    }

    public static void touch(Context c, Session s) {
        s.updated = System.currentTimeMillis();
        List<Session> all = list(c);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(s.id)) {
                all.set(i, s);
                found = true;
                break;
            }
        }
        if (!found) all.add(0, s);
        saveIndex(c, all);
    }

    public static void append(Context c, String id, JSONObject msg) {
        try {
            FileWriter w = new FileWriter(body(c, id), true);
            w.write(msg.toString());
            w.write('\n');
            w.close();
        } catch (Exception ignored) {}
    }

    public static JSONArray load(Context c, String id) {
        JSONArray arr = new JSONArray();
        File f = body(c, id);
        if (!f.isFile()) return arr;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { arr.put(new JSONObject(line)); } catch (Exception ignored) {}
            }
            r.close();
        } catch (Exception ignored) {}
        return arr;
    }

    public static void delete(Context c, String id) {
        //noinspection ResultOfMethodCallIgnored
        body(c, id).delete();
        List<Session> all = list(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        saveIndex(c, all);
    }

    /** 用第一条用户消息当标题，长消息截断。 */
    /** 会话最后一行的 role；空会话返回 ""。用来判断上一轮是不是被打断在工具调用上。 */
    public static String lastRole(Context c, String id) {
        try {
            JSONArray a = load(c, id);
            if (a.length() == 0) return "";
            return a.getJSONObject(a.length() - 1).optString("role", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 这一轮是不是被打断了？
     * 两种情况：最后一条是 tool 结果（工具跑完了但模型没来得及总结），
     * 或者最后一条是 assistant 且带着 tool_calls（工具还没执行就没了）。
     */
    /** 会话里有多少条消息（用来判断"同一个中断状态"是否已经自动续过）。 */
    public static int count(Context c, String id) {
        try { return load(c, id).length(); } catch (Exception e) { return 0; }
    }

    public static boolean needsResume(Context c, String id) {
        try {
            JSONArray a = load(c, id);
            if (a.length() == 0) return false;
            JSONObject last = a.getJSONObject(a.length() - 1);
            String role = last.optString("role", "");
            if ("tool".equals(role)) return true;
            if ("assistant".equals(role)) {
                JSONArray calls = last.optJSONArray("tool_calls");
                return calls != null && calls.length() > 0;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static String titleFrom(String userText) {
        if (userText == null) return "新会话";
        String t = userText.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }
}
