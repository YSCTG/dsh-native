package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务板：给并行干活的人一个共同的待办清单。
 *
 * 为什么需要它：team_spawn 派出去的人只看得到自己那段描述，互相不知道对方在做什么，
 * 也不知道哪些事已经有人领了。电脑版的 team task board 就是解决这个的。
 *
 * 存在工作区的 .dshnative/board.json 里（JSON，纯文本），所以：
 * - 进程被杀，板子还在
 * - 子 agent 用 fs_read 也能看到
 * - 出问题能直接打开文件看
 *
 * 每个任务带 revision：更新时必须带对上的 revision，防止两个人同时改同一条
 * （和电脑版一样的乐观锁语义）。
 */
public final class TeamBoard {

    private static final Object LOCK = new Object();

    private TeamBoard() {}

    private static File file(Context c) {
        File dir = new File(Ws.active(c), ".dshnative");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "board.json");
    }

    private static JSONObject load(Context c) {
        try {
            File f = file(c);
            if (!f.isFile()) return empty();
            String s = readText(f);
            JSONObject o = new JSONObject(s);
            if (!o.has("tasks")) o.put("tasks", new JSONArray());
            return o;
        } catch (Exception e) {
            return empty();
        }
    }

    private static JSONObject empty() {
        try {
            JSONObject o = new JSONObject();
            o.put("next", 1);
            o.put("tasks", new JSONArray());
            return o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void save(Context c, JSONObject board) {
        try {
            FileOutputStream os = new FileOutputStream(file(c));
            os.write(board.toString(2).getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {}
    }

    private static JSONObject find(JSONObject board, String id) {
        JSONArray ts = board.optJSONArray("tasks");
        if (ts == null) return null;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t != null && id.equals(t.optString("id"))) return t;
        }
        return null;
    }

    public static String create(Context c, String subject, String description, List<String> blockedBy) {
        if (subject == null || subject.trim().isEmpty()) return "错误：subject 不能为空";
        synchronized (LOCK) {
            JSONObject board = load(c);
            try {
                int next = board.optInt("next", 1);
                String id = "t" + next;
                board.put("next", next + 1);

                JSONObject t = new JSONObject();
                t.put("id", id);
                t.put("subject", subject.trim());
                t.put("description", description == null ? "" : description);
                t.put("status", "pending");
                t.put("owner", "");
                t.put("revision", 1);
                t.put("created", System.currentTimeMillis());
                JSONArray b = new JSONArray();
                if (blockedBy != null) for (String x : blockedBy) if (x != null && !x.trim().isEmpty()) b.put(x.trim());
                t.put("blockedBy", b);

                board.optJSONArray("tasks").put(t);
                save(c, board);
                return "已创建 " + id + "：" + subject.trim();
            } catch (Exception e) {
                return "错误：" + e.getMessage();
            }
        }
    }

    public static String get(Context c, String id) {
        synchronized (LOCK) {
            JSONObject t = find(load(c), id);
            if (t == null) return "错误：没有 " + id + " 这个任务。task_list 看看。";
            return render(t);
        }
    }

    public static String list(Context c, String status) {
        synchronized (LOCK) {
            JSONObject board = load(c);
            JSONArray ts = board.optJSONArray("tasks");
            if (ts == null || ts.length() == 0) return "任务板是空的。task_create 建一条。";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                String st = t.optString("status", "pending");
                if (status != null && !status.trim().isEmpty() && !status.equals(st)) continue;
                sb.append(mark(st)).append(' ').append(t.optString("id"))
                  .append("  ").append(t.optString("subject"));
                String owner = t.optString("owner", "");
                if (!owner.isEmpty()) sb.append("  ← ").append(owner);
                sb.append('\n');
            }
            return sb.length() == 0 ? "没有符合 status=" + status + " 的任务。" : sb.toString();
        }
    }

    /**
     * 改一条任务。
     * action: claim（领走）、complete（做完）、reopen（重新打开）、edit（改标题/描述）、delete
     */
    public static String update(Context c, String id, String action, int expectedRevision,
                                String owner, String subject, String description) {
        synchronized (LOCK) {
            JSONObject board = load(c);
            JSONObject t = find(board, id);
            if (t == null) return "错误：没有 " + id + " 这个任务。";
            int rev = t.optInt("revision", 1);
            if (expectedRevision > 0 && expectedRevision != rev) {
                return "错误：revision 对不上（现在是 " + rev + "）。重新 task_get 一次再改。";
            }
            try {
                if ("delete".equals(action)) {
                    JSONArray ts = board.optJSONArray("tasks");
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < ts.length(); i++) {
                        JSONObject x = ts.optJSONObject(i);
                        if (x != null && !id.equals(x.optString("id"))) out.put(x);
                    }
                    board.put("tasks", out);
                    save(c, board);
                    return "已删除 " + id;
                }
                if ("claim".equals(action)) {
                    t.put("status", "in_progress");
                    t.put("owner", owner == null ? "" : owner.trim());
                } else if ("complete".equals(action)) {
                    t.put("status", "completed");
                    if (owner != null && !owner.trim().isEmpty()) t.put("owner", owner.trim());
                } else if ("reopen".equals(action)) {
                    t.put("status", "pending");
                } else if ("edit".equals(action)) {
                    if (subject != null && !subject.trim().isEmpty()) t.put("subject", subject.trim());
                    if (description != null) t.put("description", description);
                } else {
                    return "错误：action 只能是 claim / complete / reopen / edit / delete";
                }
                t.put("revision", rev + 1);
                t.put("updated", System.currentTimeMillis());
                save(c, board);
                return "已更新 " + id + "（revision " + (rev + 1) + "）\n" + render(t);
            } catch (Exception e) {
                return "错误：" + e.getMessage();
            }
        }
    }

    private static String mark(String status) {
        if ("completed".equals(status)) return "✓";
        if ("in_progress".equals(status)) return "▸";
        return "○";
    }

    private static String render(JSONObject t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.optString("id")).append("  ").append(t.optString("subject")).append('\n');
        sb.append("状态: ").append(t.optString("status")).append("   revision: ").append(t.optInt("revision", 1)).append('\n');
        String owner = t.optString("owner", "");
        if (!owner.isEmpty()) sb.append("负责人: ").append(owner).append('\n');
        JSONArray b = t.optJSONArray("blockedBy");
        if (b != null && b.length() > 0) {
            sb.append("依赖: ");
            for (int i = 0; i < b.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(b.optString(i));
            }
            sb.append('\n');
        }
        String d = t.optString("description", "");
        if (!d.isEmpty()) sb.append("说明: ").append(d).append('\n');
        return sb.toString();
    }

    private static String readText(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int n = in.read(buf);
        in.close();
        return new String(buf, 0, Math.max(n, 0), "UTF-8");
    }

    /** 给子 agent 的一段摘要（放在它的 system prompt 里）。 */
    public static String digest(Context c) {
        synchronized (LOCK) {
            JSONObject board = load(c);
            JSONArray ts = board.optJSONArray("tasks");
            if (ts == null || ts.length() == 0) return "";
            StringBuilder sb = new StringBuilder();
            int open = 0;
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                if ("completed".equals(t.optString("status"))) continue;
                if (open++ == 0) sb.append("\n\n【任务板】\n");
                sb.append(mark(t.optString("status"))).append(' ').append(t.optString("id"))
                  .append("  ").append(t.optString("subject")).append('\n');
            }
            return sb.toString();
        }
    }

    public static List<String> asList(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
