package com.dshnative;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话搜索。
 *
 * 为什么要有：会话多了以后，抽屉只能按时间翻，"上次那个命令到底写在哪个会话里"就没法查了。
 * 这里在本地全文扫（JSONL 一条条读），不联网、不建索引——一次几十个会话扫下来是毫秒级。
 */
public final class SessionSearch {

    private SessionSearch() {}

    /** 一条命中：哪个会话、第几条消息、上下文片段。 */
    public static final class Hit {
        public final Session session;
        public final int index;
        public final String role;
        public final String snippet;

        Hit(Session s, int index, String role, String snippet) {
            this.session = s;
            this.index = index;
            this.role = role;
            this.snippet = snippet;
        }
    }

    /**
     * 搜索。标题命中排前面，正文命中按会话时间顺序。
     * query 为空返回空列表（不要把全部会话当结果）。
     */
    public static List<Hit> find(Context c, String query, int limit) {
        List<Hit> out = new ArrayList<>();
        if (query == null) return out;
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return out;

        List<Session> all = Session.list(c);
        // 标题先扫一遍：命中标题时片段就是标题，比正文片段更有用
        for (Session s : all) {
            String title = s.title == null ? "" : s.title;
            if (title.toLowerCase().contains(q)) {
                out.add(new Hit(s, -1, "标题", title));
                if (out.size() >= limit) return out;
            }
        }

        for (Session s : all) {
            if (out.size() >= limit) break;
            if (s.title != null && s.title.toLowerCase().contains(q)) continue;  // 已按标题命中过
            JSONArray msgs = Session.load(c, s.id);
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String text = textOf(m);
                if (text.isEmpty()) continue;
                if (text.toLowerCase().contains(q)) {
                    out.add(new Hit(s, i, m.optString("role", ""), snippet(text, q, 30)));
                    if (out.size() >= limit) break;
                }
            }
        }
        return out;
    }

    /** 取一条消息里能被搜到的文本（content 可能是字符串、也可能是分片数组）。 */
    public static String textOf(JSONObject m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        Object content = m.opt("content");
        if (content instanceof String) {
            sb.append((String) content);
        } else if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof String) sb.append(part).append(' ');
                else if (part instanceof JSONObject) {
                    JSONObject o = (JSONObject) part;
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) sb.append(t).append(' ');
                }
            }
        }
        String reasoning = m.optString("reasoning_content", "");
        if (!reasoning.isEmpty()) sb.append(' ').append(reasoning);
        return sb.toString().trim();
    }

    /**
     * 命中点前后各取 radius 个字符，把换行压成空格，两端加省略号。
     *
     * 窗口两端会向外扩到空白处（最多再多 20 字）：否则容易把词切断，
     * 出现过 "...npm insta…" 这种读不出意思的片段。
     */
    public static String snippet(String text, String query, int radius) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        int at = flat.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0) return flat.length() <= radius * 2 ? flat : flat.substring(0, radius * 2) + "…";

        int from = Math.max(0, at - radius);
        while (from > 0 && !Character.isWhitespace(flat.charAt(from - 1))
                && (at - from) < radius + 20) {
            from--;
        }
        int afterQuery = at + query.length();
        int to = Math.min(flat.length(), afterQuery + radius);
        while (to < flat.length() && !Character.isWhitespace(flat.charAt(to))
                && (to - afterQuery) < radius + 20) {
            to++;
        }
        return (from > 0 ? "…" : "") + flat.substring(from, to)
                + (to < flat.length() ? "…" : "");
    }
}
