import com.dshs.DeepSeekClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 客户端链路测试 —— 直接在 JVM 上跑真实发货的 DeepSeekClient.java（它没有 android 依赖）。
 *
 * 验证四件事：
 *  1. SSE 流式增量解析（含 reasoning_content）
 *  2. tool_calls 分片拼装
 *  3. usage 的 cache hit/miss 字段解析与成本计算
 *  4. 带 tools 时把历史 reasoning_content 原样回传（漏了官方会 400，mock 也会 400）
 *
 * 跑法：
 *   javac -encoding UTF-8 -cp build/json.jar -d build/wire -sourcepath android/src tools/test/WireTest.java
 *   java -cp "build/wire;build/json.jar" WireTest
 */

public final class WireTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8899";
        DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-flash", base);

        JSONArray messages = new JSONArray();
        messages.put(msg("system", "你是测试用 agent"));
        messages.put(msg("user", "看看当前目录"));

        JSONArray tools = new JSONArray();
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", new JSONObject());
        JSONObject fn = new JSONObject();
        fn.put("name", "fs_list");
        fn.put("description", "列目录");
        fn.put("parameters", params);
        JSONObject t = new JSONObject();
        t.put("type", "function");
        t.put("function", fn);
        tools.put(t);

        final StringBuilder sawContent = new StringBuilder();
        final StringBuilder sawReasoning = new StringBuilder();

        // ---- 第一轮：应当拿到 tool_call ----
        DeepSeekClient.Result r1 = client.complete(messages, tools, new DeepSeekClient.Hooks() {
            @Override public void onContent(String d) { sawContent.append(d); }
            @Override public void onReasoning(String d) { sawReasoning.append(d); }
            @Override public void onNote(String n) { }
        });

        check("流式正文拼接", "我先看一眼当前目录。".equals(sawContent.toString()),
                "got=" + sawContent);
        // 只断言"收到过"，不断言具体文案 —— 否则 mock 一改文案测试就假红
        check("流式 reasoning 回调", sawReasoning.length() > 0,
                "got=" + sawReasoning);

        JSONArray calls = r1.assistant.optJSONArray("tool_calls");
        check("第一轮返回了 tool_calls", calls != null && calls.length() == 1, "calls=" + calls);
        if (calls == null || calls.length() == 0) {
            System.out.println("  没有 tool_calls，后面无法继续，直接退出");
            System.out.println("PASS " + pass + "   FAIL " + fail);
            System.exit(1);
        }
        {
            JSONObject c0 = calls.getJSONObject(0);
            check("tool 名称正确", "fs_list".equals(c0.getJSONObject("function").optString("name")), "");
            check("tool 参数完整", c0.getJSONObject("function").optString("arguments").contains("path"), "");
            check("tool_call_id 保留", !c0.optString("id").isEmpty(), "");
        }
        check("assistant 消息保留了 reasoning_content",
                !r1.assistant.optString("reasoning_content", "").isEmpty(), "");

        checkInt("usage.hit", 896, r1.usage.hit);
        checkInt("usage.miss", 128, r1.usage.miss);
        checkInt("usage.out", 42, r1.usage.out);
        checkInt("usage.promptTokens", 1024, r1.usage.promptTokens());
        checkNear("usage.hitRate", 0.875, r1.usage.hitRate());
        double cost = r1.usage.costUsd();
        check("成本计算在合理区间", cost > 0 && cost < 0.001, "cost=" + cost);

        // ---- 第二轮：把工具结果回灌。mock 会校验 reasoning_content 是否回传 ----
        messages.put(r1.assistant);
        JSONObject toolMsg = new JSONObject();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", calls.getJSONObject(0).optString("id"));
        toolMsg.put("content", "- a.txt\n- b.txt");
        messages.put(toolMsg);

        sawContent.setLength(0);
        DeepSeekClient.Result r2 = client.complete(messages, tools, new DeepSeekClient.Hooks() {
            @Override public void onContent(String d) { sawContent.append(d); }
            @Override public void onReasoning(String d) { }
            @Override public void onNote(String n) { }
        });

        check("第二轮没有 tool_calls（收尾）", r2.assistant.optJSONArray("tool_calls") == null,
                "assistant=" + r2.assistant);
        check("第二轮正文包含 mock 标记", sawContent.toString().contains("mock 服务的回答"),
                "got=" + sawContent);
        checkInt("第二轮 usage.hit", 1216, r2.usage.hit);
        check("第二轮命中率 > 90%", r2.usage.hitRate() > 0.9, "rate=" + r2.usage.hitRate());

        System.out.println();
        System.out.println("PASS " + pass + "   FAIL " + fail);
        if (fail > 0) System.exit(1);
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  " + detail));
        if (ok) pass++; else fail++;
    }

    private static void checkInt(String what, int want, int got) {
        check(what + " = " + want, want == got, "got=" + got);
    }

    private static void checkNear(String what, double want, double got) {
        check(what + " ≈ " + want, Math.abs(want - got) < 1e-6, "got=" + got);
    }
}
