package com.dshnative;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端（HTTP 传输）。
 *
 * DeepSeek 服务端不支持 MCP，所以必须客户端自己把 MCP 的工具映射成 function tools。
 * 这里实现最小可用的 JSON-RPC：initialize → tools/list → tools/call。
 *
 * 有几件必须做对的事：
 *  - 有些 server 返回 SSE（text/event-stream），要让 Accept 带上它，并按 data: 行取 JSON
 *  - initialize 可能回 Mcp-Session-Id，后续请求要带上
 *  - 工具名要洗成 [a-zA-Z0-9_-] 并加前缀，避免和其它工具撞名
 *  - server 是用户自己配的，但调用仍然进审计；能力上归到 mcp.call
 */
public final class Mcp {

    public static final class Server {
        public String name;
        public String url;
        public boolean enabled = true;

        Server(String name, String url, boolean enabled) {
            this.name = name; this.url = url; this.enabled = enabled;
        }
    }

    private static final String PREF = "mcp_servers";

    private Mcp() {}

    // ---------------- 配置 ----------------

    public static List<Server> servers(Context c) {
        List<Server> out = new ArrayList<>();
        String raw = prefs(c).getString(PREF, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Server(o.optString("name"), o.optString("url"), o.optBoolean("enabled", true)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context c, List<Server> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Server s : all) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("enabled", s.enabled);
                arr.put(o);
            }
            prefs(c).edit().putString(PREF, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void add(Context c, String name, String url) {
        List<Server> all = servers(c);
        all.add(new Server(name.trim(), url.trim(), true));
        save(c, all);
    }

    public static void remove(Context c, String name) {
        List<Server> all = servers(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).name.equals(name)) all.remove(i);
        }
        save(c, all);
    }

    public static void setEnabled(Context c, String name, boolean on) {
        List<Server> all = servers(c);
        for (Server s : all) if (s.name.equals(name)) s.enabled = on;
        save(c, all);
    }

    private static SharedPreferences prefs(Context c) {
        return Prefs.get(c);
    }

    // ---------------- 工具映射 ----------------

    /** 工具名洗成 a-zA-Z0-9_-，并加 mcp__<server>__ 前缀。 */
    public static String toolName(String server, String tool) {
        String s = server.replaceAll("[^a-zA-Z0-9_-]", "_");
        String t = tool.replaceAll("[^a-zA-Z0-9_-]", "_");
        String full = "mcp__" + s + "__" + t;
        return full.length() > 64 ? full.substring(0, 64) : full;
    }

    /** 拉一个 server 的工具清单。失败返回空表（失败即关闭，不注册半个）。 */
    public static List<JSONObject> listTools(Server s) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONObject init = new JSONObject();
            init.put("jsonrpc", "2.0");
            init.put("id", 1);
            init.put("method", "initialize");
            JSONObject params = new JSONObject();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", new JSONObject());
            JSONObject info = new JSONObject();
            info.put("name", "dshnative");
            info.put("version", "0.9");
            params.put("clientInfo", info);
            init.put("params", params);

            String sid = null;
            JSONObject initResp = post(s.url, init, null);
            if (initResp == null || initResp.has("error")) return out;

            JSONObject listReq = new JSONObject();
            listReq.put("jsonrpc", "2.0");
            listReq.put("id", 2);
            listReq.put("method", "tools/list");
            listReq.put("params", new JSONObject());

            JSONObject listResp = post(s.url, listReq, session.get(s.url));
            if (listResp == null) return out;
            JSONObject result = listResp.optJSONObject("result");
            if (result == null) return out;
            JSONArray tools = result.optJSONArray("tools");
            if (tools == null) return out;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t != null) out.add(t);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 调用一个 MCP 工具，把 content 里的 text 拼起来返回。 */
    public static String call(Server s, String tool, JSONObject args) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", 10);
            req.put("method", "tools/call");
            JSONObject params = new JSONObject();
            params.put("name", tool);
            params.put("arguments", args == null ? new JSONObject() : args);
            req.put("params", params);

            JSONObject resp = post(s.url, req, session.get(s.url));
            if (resp == null) return "错误：MCP server 没响应";
            if (resp.has("error")) {
                return "错误：MCP " + resp.optJSONObject("error").optString("message");
            }
            JSONObject result = resp.optJSONObject("result");
            if (result == null) return "错误：MCP 返回里没有 result";
            boolean isError = result.optBoolean("isError", false);
            JSONArray content = result.optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part == null) continue;
                    if ("text".equals(part.optString("type"))) sb.append(part.optString("text"));
                }
            }
            String text = sb.toString();
            if (text.isEmpty()) text = result.toString();
            return (isError ? "工具报错：" : "") + text;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    // ---------------- HTTP / JSON-RPC ----------------

    private static final java.util.Map<String, String> session = new java.util.HashMap<>();

    private static JSONObject post(String url, JSONObject body, String sessionId) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json, text/event-stream");
            if (sessionId != null) c.setRequestProperty("Mcp-Session-Id", sessionId);

            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code >= 400) return null;

            String sid = c.getHeaderField("Mcp-Session-Id");
            if (sid != null && !sid.isEmpty() && body.optString("method").equals("initialize")) {
                session.put(url, sid);   // 记下来，后续请求带上
            }

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                // SSE 模式：只取 data: 行
                if (line.startsWith("data:")) sb.append(line.substring(5).trim());
                else if (line.startsWith("{")) sb.append(line);
                if (sb.length() > 200000) break;
            }
            r.close();
            if (sb.length() == 0) return null;
            try {
                return new JSONObject(sb.toString());
            } catch (Exception e) {
                // 可能粘了多行，取第一个完整对象
                int end = sb.indexOf("}{");
                if (end > 0) {
                    try { return new JSONObject(sb.substring(0, end + 1)); } catch (Exception ignored) {}
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Exception ignored) {} }
        }
    }
}
