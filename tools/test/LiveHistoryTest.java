import com.dshs.DeepSeekClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 历史到底吃不吃缓存？—— 修正版
 *
 * 上一次（LiveScaleTest）结论是"命中量恒定，历史不吃缓存"，但每轮输出只有 1 个 token，
 * 缓存单元小到看不出来。真机上跑了三轮，命中量从 1150 涨到 1405，说明历史是在被缓存的。
 *
 * 这个测试让每轮输出足够长（约 200 token），跑 6 轮，看命中量是不是跟着历史长。
 * 结论决定：历史要不要压缩、压缩阈值定在哪。
 */

public final class LiveHistoryTest {

    public static void main(String[] args) throws Exception {
        String key = System.getenv("DSHS_KEY");
        if (key == null || key.trim().isEmpty()) { System.out.println("DSHS_KEY not set"); System.exit(2); }
        DeepSeekClient client = new DeepSeekClient(key, "deepseek-flash");

        StringBuilder sys = new StringBuilder("你是测试用 agent。固定守则：\n");
        for (int i = 0; i < 20; i++) {
            sys.append(i).append(". 只回答被问到的；不要展开；不要列清单；不要复述问题。\n");
        }

        JSONArray messages = new JSONArray();
        messages.put(msg("system", sys.toString()));

        JSONArray tools = new JSONArray();
        tools.put(fsListTool());

        String[] asks = {
            "用大约一百五十个字描述一个安静的清晨街道，只描述，不要评论。",
            "用大约一百五十个字描述一间旧书店的内部。",
            "用大约一百五十个字描述雨落在铁皮屋顶上的样子。",
            "用大约一百五十个字描述一个人在地铁上读信。",
            "用大约一百五十个字描述深夜里的一间加油站。",
            "用大约一百五十个字描述冬天操场上的一个篮球架。",
        };

        System.out.println(String.format("%-4s %8s %8s %8s %9s %8s", "轮", "in", "命中", "未命中", "命中率", "out"));
        int prevHit = 0;
        for (int i = 0; i < asks.length; i++) {
            messages.put(msg("user", asks[i]));
            DeepSeekClient.Result r = client.complete(messages, tools, quiet());
            DeepSeekClient.Usage u = r.usage;
            System.out.println(String.format("%-4d %8d %8d %8d %8.1f%% %8d   (命中增量 %+d)",
                    i + 1, u.promptTokens(), u.hit, u.miss, u.hitRate() * 100, u.out, u.hit - prevHit));
            prevHit = u.hit;
            messages.put(r.assistant);
        }

        System.out.println();
        System.out.println("读法：如果'命中增量'≈上一轮的 out，说明历史按轮被缓存（有一轮延迟）。");
        System.out.println("      如果增量一直是 0，说明只有头部吃缓存，历史全价。");
    }

    private static void report(String label, DeepSeekClient.Result r) { }

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
}
