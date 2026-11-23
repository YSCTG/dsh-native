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

    private History() {}

    /**
     * 删掉"没有对应 tool_calls"的孤儿 tool 消息。
     *
     * 这类消息会让 API 直接 400（tool 必须是某个 tool_calls 的回应），
     * 一旦会话被写脏，后续每一轮都会失败——所以必须在发请求前清理。
     *
     * @return 丢掉了几条
     */
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
