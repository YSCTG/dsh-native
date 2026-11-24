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
    /** 最近一次请求的统计：缓存命中/未命中、输出量、耗时。切会话后状态栏要能复原。 */
    public long hitTokens, missTokens, lastOut, lastMs;
    public long promptTokens;
    /** 置顶：抽屉里永远排最前。 */
    public boolean pinned;
    /** 属于哪个工作区（会话列表按它分组）。 */
    public String ws = Workspaces.FULL_ID;
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

    /** 只列某个工作区的会话。 */
    public static List<Session> list(Context c, String wsId) {
        List<Session> all = list(c);
        if (wsId == null) return all;
        List<Session> out = new java.util.ArrayList<>();
        for (Session s : all) {
            if (wsId.equals(s.ws)) { out.add(s); continue; }
            // 归属的工作区已经不在了 → 归到「完全权限」，别把历史藏起来
            if (Workspaces.FULL_ID.equals(wsId) && !Workspaces.exists(c, s.ws)) out.add(s);
        }
        return out;
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
                s.hitTokens = o.optLong("hit", 0);
                s.missTokens = o.optLong("miss", 0);
                s.lastOut = o.optLong("lout", 0);
                s.lastMs = o.optLong("lms", 0);
                s.promptTokens = o.optLong("ptok", 0);
                s.cost = o.optDouble("cost", 0);
                s.pinned = o.optBoolean("pinned", false);
            s.ws = o.optString("ws", Workspaces.FULL_ID);
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
                o.put("hit", s.hitTokens);
                o.put("miss", s.missTokens);
                o.put("lout", s.lastOut);
                o.put("lms", s.lastMs);
                o.put("ptok", s.promptTokens);
                o.put("cost", s.cost);
                o.put("pinned", s.pinned);
        o.put("ws", s.ws == null ? Workspaces.FULL_ID : s.ws);
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
    /** 把磁盘上最后一行替换成给定消息（用于给工具结果补附件标记）。 */
    public static void replaceLast(Context c, String id, JSONObject msg) {
        try {
            File f = body(c, id);
            if (!f.isFile()) return;
            java.util.List<String> lines = new java.util.ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            for (int i = 0; i < lines.size(); i++) {
                if (i == lines.size() - 1) os.write(msg.toString().getBytes("UTF-8"));
                else os.write(lines.get(i).getBytes("UTF-8"));
                os.write('\n');
            }
            os.close();
        } catch (Throwable ignored) {}
    }

    /** 整份重写会话文件（顺序敏感，不能只改最后一行）。 */
    public static void saveAll(Context c, String id, JSONArray all) {
        try {
            File f = body(c, id);
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            for (int i = 0; i < all.length(); i++) {
                os.write(all.getJSONObject(i).toString().getBytes("UTF-8"));
                os.write('\n');
            }
            os.close();
        } catch (Throwable ignored) {}
    }

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
            // 最后一条是用户消息：说明这一轮连第一个响应都没写下来就被打断了
            // （进程死在模型请求/流式传输期间）。这种情况也要能自动接上，
            // 否则用户只能自己再发一句"继续"。
            if ("user".equals(role)) return true;
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
