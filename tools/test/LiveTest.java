import com.dshs.DeepSeekClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 真 API 探针 —— 用发货的 DeepSeekClient 打真的 api.deepseek.com。
 *
 * 这一步验的是整个 token 架构的赌注：
 *   1. 缓存命中真的存在吗？命中率有多高？
 *   2. 相同前缀再发一次，命中会涨吗？（append-only 策略到底值不值）
 *   3. 真 API 下工具调用 + reasoning_content 回传这条链能不能走通？
 *   4. 真实成本是多少？
 *
 * key 从环境变量 DSHS_KEY 读，不进 argv、不落盘。
 */

public final class LiveTest {

    public static void main(String[] args) throws Exception {
        String key = System.getenv("DSHS_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.out.println("DSHS_KEY not set");
            System.exit(2);
        }
        String model = args.length > 0 ? args[0] : "deepseek-flash";
        DeepSeekClient client = new DeepSeekClient(key, model);

        JSONArray messages = new JSONArray();
        messages.put(msg("system", "你是测试用 agent。需要看目录就调用 fs_list 工具，然后用一句话说明结果。"));
        messages.put(msg("user", "请调用 fs_list 看看当前目录"));

        JSONArray tools = new JSONArray();
        tools.put(fsListTool());

        System.out.println("model = " + model);
        System.out.println();

        DeepSeekClient.Result r1 = client.complete(messages, tools, quiet());
        report("第 1 轮（冷启动）", r1);

        // 完全相同的前缀再发一次：缓存单元应该命中
        DeepSeekClient.Result r2 = client.complete(messages, tools, quiet());
        report("第 2 轮（同前缀）", r2);

        JSONArray calls = r1.assistant.optJSONArray("tool_calls");
        if (calls == null || calls.length() == 0) {
            System.out.println();
            System.out.println("模型没有调用工具，跳过工具回灌那一步。");
            System.out.println("assistant = " + abbreviate(r1.assistant.toString(), 400));
        } else {
            String toolName = calls.getJSONObject(0).getJSONObject("function").optString("name");
            System.out.println("模型调用了工具: " + toolName);

            messages.put(r1.assistant);
            JSONObject toolMsg = new JSONObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", calls.getJSONObject(0).optString("id"));
            toolMsg.put("content", "- a.txt (12B)\n- b.txt (34B)");
            messages.put(toolMsg);

            DeepSeekClient.Result r3 = client.complete(messages, tools, quiet());
            report("第 3 轮（回灌工具结果 + reasoning 回传）", r3);
            System.out.println("  收尾正文: " + abbreviate(
                    r3.assistant.optString("content", ""), 200));
        }

        System.out.println();
        System.out.println("缓存结论：" + verdict(r1, r2));
    }

    private static void report(String label, DeepSeekClient.Result r) {
        DeepSeekClient.Usage u = r.usage;
        System.out.println(label);
        System.out.println(String.format(
                "  in %d = 命中 %d + 未命中 %d   命中率 %.1f%%   out %d   %.0fms   $%.6f",
                u.promptTokens(), u.hit, u.miss, u.hitRate() * 100, u.out, (double) u.ms, u.costUsd()));
        String c = r.assistant.optString("content", "");
        if (!c.isEmpty()) System.out.println("  正文: " + abbreviate(c, 160));
        String rc = r.assistant.optString("reasoning_content", "");
        if (!rc.isEmpty()) System.out.println("  推理: " + abbreviate(rc, 120));
        System.out.println();
    }

    private static String verdict(DeepSeekClient.Result r1, DeepSeekClient.Result r2) {
        if (r2.usage.promptTokens() == 0) return "第二轮没有 usage，无法判断";
        double d = r2.usage.hitRate() - r1.usage.hitRate();
        if (r2.usage.hitRate() >= 0.5) {
            return String.format("命中 %.1f%%，比第一轮高 %.1f 个百分点 —— 前缀缓存确实生效，append-only 策略成立",
                    r2.usage.hitRate() * 100, d * 100);
        }
        return String.format("第二轮命中只有 %.1f%%，缓存没有按预期工作", r2.usage.hitRate() * 100);
    }

    private static JSONObject fsListTool() throws Exception {
        JSONObject props = new JSONObject();
        JSONObject path = new JSONObject();
        path.put("type", "string");
        path.put("description", "目录路径");
        props.put("path", path);
        JSONArray req = new JSONArray();
        req.put("path");
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", req);
        JSONObject fn = new JSONObject();
        fn.put("name", "fs_list");
        fn.put("description", "列目录");
        fn.put("parameters", params);
        JSONObject t = new JSONObject();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static DeepSeekClient.Hooks quiet() {
        return new DeepSeekClient.Hooks() {
            @Override public void onContent(String d) { }
            @Override public void onReasoning(String d) { }
            @Override public void onNote(String n) { }
        };
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > n ? one.substring(0, n) + "…" : one;
    }
}
