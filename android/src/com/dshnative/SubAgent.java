package com.dshnative;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 子智能体：一个独立上下文的 agent，只把结论带回父会话。
 *
 * 为什么要有它：父会话的 token 是稀缺资源。让子 agent 去读十个文件、翻一堆工具结果，
 * 只把一段摘要带回来，父上下文不会被污染。电脑版的 subagent 就是这个思路。
 *
 * 工具执行交给父 agent 提供的回调 —— 这样权限、确认、审计全都沿用同一套，子 agent 不能绕闸门。
 * 有深度限制：子 agent 里不再挂 subagent / loop，避免自我复制。
 */
public final class SubAgent {

    public interface Tools {
        JSONArray defs();
        String call(String name, String argsJson);
    }

    public static final class Result {
        public String text = "";
        public int in, out;
        public double cost;
    }

    /**
     * @param maxTurns 工具轮次上限：手机上一次子任务别超过这个数
     */
    public static Result run(Context ctx, String apiKey, String model, String baseUrl,
                             String systemPrompt, String task, Tools tools, int maxTurns) {
        return run(ctx, apiKey, model, baseUrl, systemPrompt, task, tools, maxTurns, null);
    }

    /** 带 hook 的版本：每一步结束时收父会话发来的消息，并响应中断。 */
    public static Result run(Context ctx, String apiKey, String model, String baseUrl,
                             String systemPrompt, String task, Tools tools, int maxTurns,
                             Team.Hook hook) {
        Result out = new Result();
        DeepSeekClient client = new DeepSeekClient(apiKey, model, baseUrl);
        JSONArray messages = new JSONArray();
        try {
            messages.put(msg("system", systemPrompt));
            messages.put(msg("user", task));

            for (int turn = 0; turn < maxTurns; turn++) {
                DeepSeekClient.Result r = client.complete(messages, tools.defs(), quiet());
                out.in += r.usage.promptTokens();
                out.out += r.usage.out;
                out.cost += r.usage.costUsd();

                messages.put(r.assistant);
                JSONArray calls = r.assistant.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    out.text = r.assistant.optString("content", "");
                    return out;
                }
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    JSONObject fn = call.optJSONObject("function");
                    String name = fn == null ? "" : fn.optString("name", "");
                    String args = fn == null ? "{}" : fn.optString("arguments", "{}");
                    String result = tools.call(name, args);

                    JSONObject t = new JSONObject();
                    t.put("role", "tool");
                    t.put("tool_call_id", call.optString("id", "call_" + i));
                    t.put("content", result == null ? "" : result);
                    messages.put(t);
                }
                if (hook != null) {
                    if (hook.cancelled()) {
                        out.text = "（被中断，未完成）" + (out.text == null ? "" : out.text);
                        return out;
                    }
                    String msg = hook.takeMessages();
                    if (msg != null && !msg.isEmpty()) {
                        messages.put(msg("user", "【父会话的消息】\n" + msg));
                    }
                }
            }
            out.text = "（子任务达到轮次上限 " + maxTurns + "，未收尾）";
        } catch (Exception e) {
            out.text = "子任务失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
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
