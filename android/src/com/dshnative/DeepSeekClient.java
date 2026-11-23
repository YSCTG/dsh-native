package com.dshnative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * DeepSeek Chat Completions 客户端（SSE 流式 + 工具调用 + 缓存计费字段）。
 *
 * 依据官方文档（2026-11 核实）：
 *  - base url 用裸 host，不带 /v1
 *  - usage.prompt_cache_hit_tokens / prompt_cache_miss_tokens 是必读字段
 *  - 带 tools 时，历史轮的 reasoning_content 必须原样回传，否则 400
 *  - tool_choice 在思维模式下不支持 required/指定函数，移动端一律 auto
 */
public final class DeepSeekClient {

    public static final String BASE = "https://api.deepseek.com";

    public interface Hooks {
        void onContent(String delta);
        void onReasoning(String delta);
        void onNote(String note);
    }

    /** 一轮的用量与成本。命中价是未命中的 2%（50 倍差价），所以命中率是核心指标。 */
    public static final class Usage {
        public int hit, miss, out;
        public long ms;
        public int promptTokens() { return hit + miss; }
        public double hitRate() { return (hit + miss) == 0 ? 0 : (double) hit / (hit + miss); }
        /** deepseek-flash 峰时单价（USD / 1M tokens）。 */
        public double costUsd() {
            return hit * 0.006 / 1e6 + miss * 0.30 / 1e6 + out * 1.20 / 1e6;
        }
        public void add(Usage o) { hit += o.hit; miss += o.miss; out += o.out; ms += o.ms; }
    }

    public static final class Result {
        public JSONObject assistant;   // 要追加进历史的那条 assistant 消息
        public Usage usage = new Usage();
        public String finish = "";
    }

    /** 查账户余额（GET /user/balance）。失败返回 null，不要在 UI 上编一个数字。 */
    public static String fetchBalance(String apiKey, String baseUrl) {
        java.net.HttpURLConnection c = null;
        try {
            String base = (baseUrl == null || baseUrl.isEmpty()) ? BASE : baseUrl;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            c = (java.net.HttpURLConnection) new java.net.URL(base + "/user/balance").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() >= 400) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            JSONArray infos = o.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) return null;
            JSONObject info = infos.getJSONObject(0);
            String cur = info.optString("currency", "CNY");
            String total = info.optString("total_balance", "");
            if (total.isEmpty()) return null;
            return ("CNY".equals(cur) ? "¥" : cur + " ") + total;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Exception ignored) {} }
        }
    }
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    /** 模型的上下文上限。flash 是 1M；其它按 128K 保守估（界面会标 ≈）。 */
    public static int contextLimit(String model) {
        if ("deepseek-flash".equals(model)) return 1000000;
        return 131072;
    }

    public static boolean contextLimitExact(String model) {
        return "deepseek-flash".equals(model);
    }

    /** none / low / medium / high / max —— 实测服务端认这个字段（推理长度随等级单调变化）。 */
    public String reasoningEffort = "";
    private volatile HttpURLConnection conn;
    private volatile boolean cancelled;

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, BASE);
    }

    /** baseUrl 可指向自建网关或本地 mock（验证用）；默认官方裸 host。 */
    public DeepSeekClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        String b = (baseUrl == null || baseUrl.trim().isEmpty()) ? BASE : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        if (c != null) { try { c.disconnect(); } catch (Exception ignored) {} }
    }

    public boolean isCancelled() { return cancelled; }

    /**
     * 请求模型，断了就重试。
     *
     * 为什么必须重试：长思考的 SSE 连接会持续几十秒，运营商 NAT 超时、
     * Wi-Fi 切换、基站切换都会让连接"Socket closed"。以前这直接判死整轮任务，
     * 用户看到的就是"任务卡住了"。
     *
     * 只在**还没吐出任何内容**时重试：已经流式显示过的部分不能重放，
     * 那种情况如实报错，让用户决定。
     */
    public Result complete(JSONArray messages, JSONArray tools, Hooks hooks) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3 && !cancelled; attempt++) {
            final int[] emitted = {0};
            Hooks counted = new Hooks() {
                @Override public void onContent(String d) {
                    emitted[0] += d == null ? 0 : d.length();
                    hooks.onContent(d);
                }

                @Override public void onReasoning(String d) {
                    emitted[0] += d == null ? 0 : d.length();
                    hooks.onReasoning(d);
                }

                @Override public void onNote(String note) { hooks.onNote(note); }
            };
            try {
                if (attempt > 1) {
                    hooks.onNote("网络中断，正在重试（第 " + attempt + " 次）");
                    android.util.Log.i("DSHN", "模型请求重试 #" + attempt + "：" + last);
                }
                return completeOnce(messages, tools, counted);
            } catch (Exception e) {
                last = e;
                if (cancelled) throw e;
                if (!retryable(e) || emitted[0] > 0) {
                    if (emitted[0] > 0 && retryable(e)) {
                        throw new RuntimeException("连接中断（已收到部分回复，未重试）：" + msg(e));
                    }
                    throw e;
                }
                try { Thread.sleep(attempt == 1 ? 800 : 2000); } catch (InterruptedException ignored) {}
            }
        }
        throw last == null ? new RuntimeException("请求失败") : last;
    }

    /** 值得重试的：网络层的，以及 5xx。4xx 重试也没用。 */
    private static boolean retryable(Exception e) {
        if (e instanceof java.io.IOException) return true;
        String m = String.valueOf(e.getMessage());
        if (m.contains("Socket closed") || m.contains("timed out") || m.contains("Connection")) return true;
        if (m.startsWith("HTTP 5")) return true;
        return false;
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.toString() : m;
    }

    /** 阻塞直到完成。UI 增量通过 hooks 回调（调用方负责切主线程）。 */
    private Result completeOnce(JSONArray messages, JSONArray tools, Hooks hooks) throws Exception {
        Result result = new Result();

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            body.put("reasoning_effort", reasoningEffort);
        }
        JSONObject so = new JSONObject();
        so.put("include_usage", true);
        body.put("stream_options", so);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        conn = c;
        long t0 = System.currentTimeMillis();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + apiKey);

        try {
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code != 200) {
                String err = readAll(c.getErrorStream());
                throw new RuntimeException("HTTP " + code + " " + err);
            }

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            TreeMap<Integer, TC> calls = new TreeMap<>();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled) throw new RuntimeException("已中止");
                if (line.isEmpty()) continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                JSONObject chunk;
                try { chunk = new JSONObject(data); }
                catch (Exception ignore) { continue; }

                JSONArray choices = chunk.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject ch = choices.getJSONObject(0);
                    String fr = s(ch, "finish_reason", "");
                    if (!fr.isEmpty()) result.finish = fr;

                    JSONObject delta = ch.optJSONObject("delta");
                    if (delta != null) {
                        String rc = s(delta, "reasoning_content", "");
                        if (!rc.isEmpty()) {
                            reasoning.append(rc);
                            if (hooks != null) hooks.onReasoning(rc);
                        }
                        String cc = s(delta, "content", "");
                        if (!cc.isEmpty()) {
                            content.append(cc);
                            if (hooks != null) hooks.onContent(cc);
                        }
                        JSONArray tcs = delta.optJSONArray("tool_calls");
                        if (tcs != null) {
                            for (int i = 0; i < tcs.length(); i++) {
                                JSONObject d = tcs.getJSONObject(i);
                                int idx = d.optInt("index", i);
                                TC tc = calls.get(idx);
                                if (tc == null) { tc = new TC(); calls.put(idx, tc); }
                                String id = s(d, "id", "");
                                if (!id.isEmpty()) tc.id = id;
                                JSONObject fn = d.optJSONObject("function");
                                if (fn != null) {
                                    String name = s(fn, "name", "");
                                    if (!name.isEmpty()) tc.name = name;
                                    String args = s(fn, "arguments", "");
                                    if (!args.isEmpty()) tc.args.append(args);
                                }
                            }
                        }
                    }
                }

                JSONObject u = chunk.optJSONObject("usage");
                if (u != null) {
                    result.usage.hit = u.optInt("prompt_cache_hit_tokens", 0);
                    result.usage.miss = u.optInt("prompt_cache_miss_tokens", 0);
                    result.usage.out = u.optInt("completion_tokens", 0);
                    if (result.usage.hit == 0 && result.usage.miss == 0) {
                        int pt = u.optInt("prompt_tokens", 0);
                        if (pt > 0) result.usage.miss = pt;  // 老式 usage：全按未命中算，宁高不低
                    }
                }
            }
            result.usage.ms = System.currentTimeMillis() - t0;

            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            if (content.length() > 0) a.put("content", content.toString());
            // 带 tools 时必须回传推理内容，否则下一轮 400
            if (reasoning.length() > 0) a.put("reasoning_content", reasoning.toString());
            if (!calls.isEmpty()) {
                JSONArray arr = new JSONArray();
                Iterator<TC> it = calls.values().iterator();
                int i = 0;
                while (it.hasNext()) {
                    TC tc = it.next();
                    JSONObject o = new JSONObject();
                    o.put("id", tc.id != null ? tc.id : ("call_" + i));
                    o.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", tc.name);
                    fn.put("arguments", tc.args.length() == 0 ? "{}" : tc.args.toString());
                    o.put("function", fn);
                    arr.put(o);
                    i++;
                }
                a.put("tool_calls", arr);
            }
            result.assistant = a;
            return result;
        } finally {
            try { c.disconnect(); } catch (Exception ignored) {}
            conn = null;
        }
    }

    private static final class TC {
        String id;
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    /**
     * 取字符串，但要正确处理 JSON null。
     * Android 的 optString(key, fallback) 在值是 JSONObject.NULL 时返回字面量 "null"，
     * 不是 fallback —— 流式 delta 里经常是 "content": null，直接用就会往正文里灌 "null"。
     */
    private static String s(JSONObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.isNull(key)) return fallback;
        String v = o.optString(key, fallback);
        return v == null ? fallback : v;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = r.readLine()) != null) sb.append(s).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
