package com.dshnative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 会话历史的结构修复。
 *
 * 单独拎出来是为了能被自检直接调用——这段逻辑以前埋在 Agent 私有方法里，
 * 出问题时只能靠线上 400 才发现。
 */
final class History {

    /** 单个 tool 结果最多留多少字符。 */
    private static final int TOOL_KEEP = 1500;
    /** 全部消息加起来超过这个字符数就继续收缩。 */
    private static final int KEEP_CHARS = 120000;

    private History() {}

    /**
     * 删掉"没有对应 tool_calls"的孤儿 tool 消息。
     *
     * 这类消息会让 API 直接 400（tool 必须是某个 tool_calls 的回应），
     * 一旦会话被写脏，后续每一轮都会失败——所以必须在发请求前清理。
     *
     * @return 丢掉了几条
     */
    /**
     * 上下文压缩：像桌面版那样，快到上限时自动收缩历史。
     *
     * 两步，从温和到激进：
     *  1. 把很长的 tool 结果截短（它们通常占九成以上）；
     *  2. 还不够就丢掉最老的一批对话，但保留 system、第一条用户消息和最近若干条。
     *
     * @return 有没有真的改动
     */
    static boolean compact(JSONArray messages, int keepRecent) {
        if (messages == null || messages.length() == 0) return false;
        boolean changed = false;

        // 第一步：截断超长工具结果
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"tool".equals(m.optString("role"))) continue;
            String c = m.optString("content", "");
            if (c.length() <= TOOL_KEEP) continue;
            try {
                m.put("content", c.substring(0, TOOL_KEEP)
                        + "\n…（已压缩：原来 " + c.length() + " 字符）");
                changed = true;
            } catch (Throwable ignored) {}
        }
        if (chars(messages) <= KEEP_CHARS) return changed;

        // 第二步：丢最老的对话，保留 system / 第一条用户消息 / 最近 keepRecent 条
        int n = messages.length();
        int from = Math.max(2, n - keepRecent);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            boolean firstUser = "user".equals(role) && kept.length() <= 1;
            if (i < 2 || i >= from || "system".equals(role) || firstUser) kept.put(m);
        }
        if (kept.length() < n) {
            while (messages.length() > 0) messages.remove(messages.length() - 1);
            for (int i = 0; i < kept.length(); i++) messages.put(kept.opt(i));
            changed = true;
        }
        // 丢掉老的之后可能又出现孤儿 tool 消息
        if (dropOrphans(messages) > 0) changed = true;
        return changed;
    }

    /** 粗略字数：用来判断"够不够小"，不需要精确 token。 */
    static int chars(JSONArray messages) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            total += m.optString("content", "").length();
        }
        return total;
    }
    static int dropOrphans(JSONArray messages) {
        if (messages == null) return 0;
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role"))) continue;
            JSONArray calls = m.optJSONArray("tool_calls");
            if (calls == null) continue;
            for (int k = 0; k < calls.length(); k++) {
                JSONObject c = calls.optJSONObject(k);
                if (c != null) declared.add(c.optString("id", ""));
            }
        }
        int dropped = 0;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            if ("tool".equals(m.optString("role"))
                    && !declared.contains(m.optString("tool_call_id", ""))) {
                messages.remove(i);
                dropped++;
            }
        }
        return dropped;
    }
}
