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
        /** 本地命令（stdio）。非空就走子进程，忽略 url。 */
        public String command;
        public boolean enabled = true;

        public boolean isStdio() { return command != null && !command.trim().isEmpty(); }

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
                Server sv = new Server(o.optString("name"), o.optString("url"),
                        o.optBoolean("enabled", true));
                sv.command = o.optString("command", "");
                out.add(sv);
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
                if (s.command != null && !s.command.isEmpty()) o.put("command", s.command);
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

    public static void addCommand(Context c, String name, String command) {
        List<Server> all = servers(c);
        Server s2 = new Server(name.trim(), "", true);
        s2.command = command.trim();
        all.add(s2);
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
        if (s.isStdio()) return stdioTools(s);
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
        if (s.isStdio()) return stdioCall(s, tool, args);
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



    // ---------------- stdio 传输 ----------------

    /**
     * 本地进程 + 按行 JSON-RPC。
     *
     * 读线程在构造里立刻启动，把所有带 id 的回复丢进 inbox 并唤醒等待者——
     * 和自检里"连续请求应答"验证过的写法一致（先证明地基，再叠协议）。
     * stderr 合并进 stdout，出错时能从原始行里看到原因。
     */
    private static final class Stdio {
        final Process proc;
        final java.io.OutputStream in;
        final java.io.BufferedReader out;
        final java.util.Map<Long, JSONObject> inbox = new java.util.HashMap<>();
        final java.util.Map<Long, java.util.concurrent.CountDownLatch> waits = new java.util.HashMap<>();
        volatile boolean dead;
        boolean initialized;

        Stdio(Context c, Server s) throws Exception {
            proc = new ProcessBuilder("/system/bin/sh", "-c", s.command)
                    .directory(Ws.active(c))
                    .redirectErrorStream(true)
                    .start();
            in = proc.getOutputStream();
            out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String line;
                        while ((line = out.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            android.util.Log.i("DSHMCP", "raw: " + line);
                            JSONObject msg;
                            try {
                                msg = new JSONObject(line);
                            } catch (Throwable e) {
                                continue;   // stderr 合并进来的噪声，跳过
                            }
                            if (!msg.has("id")) continue;
                            long id = msg.optLong("id");
                            synchronized (Stdio.this) {
                                inbox.put(id, msg);
                                java.util.concurrent.CountDownLatch l = waits.get(id);
                                if (l != null) l.countDown();
                            }
                        }
                    } catch (Throwable t2) {
                        android.util.Log.w("DSHMCP", "reader: " + t2);
                    } finally {
                        dead = true;
                        synchronized (Stdio.this) {
                            for (java.util.concurrent.CountDownLatch l : waits.values()) l.countDown();
                        }
                    }
                }
            }, "mcp-stdio");
            t.setDaemon(true);
            t.start();
        }

        boolean alive() { return !dead && proc.isAlive(); }

        JSONObject rpc(String method, JSONObject params, boolean notify) throws Exception {
            long id = System.nanoTime();
            java.util.concurrent.CountDownLatch latch = null;
            if (!notify) {
                latch = new java.util.concurrent.CountDownLatch(1);
                synchronized (this) { waits.put(id, latch); }
            }
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            if (!notify) req.put("id", id);
            req.put("method", method);
            req.put("params", params == null ? new JSONObject() : params);
            synchronized (this) {
                in.write((req.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
            if (notify) return new JSONObject();
            boolean ok = latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
            JSONObject resp;
            synchronized (this) {
                waits.remove(id);
                resp = inbox.remove(id);
            }
            if (!ok || resp == null) throw new Exception("MCP 本地进程没回应（" + method + "）");
            if (resp.has("error")) throw new Exception("MCP 错误：" + resp.optJSONObject("error"));
            JSONObject result = resp.optJSONObject("result");
            return result == null ? new JSONObject() : result;
        }

        void ensureInit() throws Exception {
            if (initialized) return;
            JSONObject params = new JSONObject();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", new JSONObject());
            JSONObject info = new JSONObject();
            info.put("name", "dshnative");
            info.put("version", "16.4");
            params.put("clientInfo", info);
            rpc("initialize", params, false);
            rpc("notifications/initialized", new JSONObject(), true);
            initialized = true;
        }
    }

    private static final java.util.Map<String, Stdio> STDIO = new java.util.HashMap<>();
    private static Context APP_CTX;

    public static void setAppContext(Context c) { APP_CTX = c; }

    private static Stdio stdio(Context c, Server s) throws Exception {
        synchronized (STDIO) {
            Stdio p = STDIO.get(s.name);
            if (p != null && p.alive()) return p;
            Context app = c != null ? c : APP_CTX;
            if (app == null) throw new Exception("缺少上下文");
            p = new Stdio(app, s);
            STDIO.put(s.name, p);
            return p;
        }
    }

    private static List<JSONObject> stdioTools(Server s) {
        List<JSONObject> out = new ArrayList<>();
        try {
            Stdio p = stdio(null, s);
            p.ensureInit();
            JSONObject r = p.rpc("tools/list", new JSONObject(), false);
            JSONArray arr = r.optJSONArray("tools");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t != null) out.add(t);
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHMCP", "listTools: " + e);
        }
        return out;
    }

    private static String stdioCall(Server s, String tool, JSONObject args) {
        try {
            Stdio p = stdio(null, s);
            p.ensureInit();
            JSONObject params = new JSONObject();
            params.put("name", tool);
            params.put("arguments", args == null ? new JSONObject() : args);
            JSONObject result = p.rpc("tools/call", params, false);
            JSONArray content = result.optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) {
                        sb.append(part.optString("text"));
                    }
                }
            }
            String text = sb.toString();
            if (text.isEmpty()) text = result.toString();
            return (result.optBoolean("isError", false) ? "工具报错：" : "") + text;
        } catch (Throwable e) {
            return "错误：" + e.getMessage();
        }
    }


    // ---------------- resources ----------------

    /** 列资源：resources/list。 */
    public static String listResources(Context c, String serverName) {
        Server sv = find(c, serverName);
        if (sv == null) return "错误：没有配置这个 MCP 服务 " + serverName;
        try {
            JSONObject r = request(sv, "resources/list", new JSONObject());
            JSONArray arr = r.optJSONArray("resources");
            if (arr == null || arr.length() == 0) return "这个服务没有暴露资源。";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                sb.append("- ").append(o.optString("uri")).append("  ")
                  .append(o.optString("name")).append('\n');
            }
            return sb.toString();
        } catch (Throwable e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 读资源：resources/read，取第一个 text 内容。 */
    public static String readResource(Context c, String serverName, String uri) {
        Server sv = find(c, serverName);
        if (sv == null) return "错误：没有配置这个 MCP 服务 " + serverName;
        try {
            JSONObject params = new JSONObject();
            params.put("uri", uri);
            JSONObject r = request(sv, "resources/read", params);
            JSONArray contents = r.optJSONArray("contents");
            if (contents == null) return r.toString();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contents.length(); i++) {
                JSONObject o = contents.optJSONObject(i);
                if (o == null) continue;
                if (o.has("text")) sb.append(o.optString("text"));
                else sb.append("[").append(o.optString("mimeType", "binary")).append("]");
            }
            return sb.toString();
        } catch (Throwable e) {
            return "错误：" + e.getMessage();
        }
    }

    private static Server find(Context c, String name) {
        for (Server s : servers(c)) if (s.name.equals(name)) return s;
        return null;
    }

    /** 统一发一个 JSON-RPC 请求，按传输类型分流。 */
    private static JSONObject request(Server s, String method, JSONObject params) throws Exception {
        if (s.isStdio()) {
            Stdio p = stdio(null, s);
            p.ensureInit();
            return p.rpc(method, params, false);
        }
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", nextHttpId());
        req.put("method", method);
        req.put("params", params == null ? new JSONObject() : params);
        JSONObject resp = post(s.url, req, session.get(s.url));
        if (resp == null) throw new Exception("MCP server 没响应");
        if (resp.has("error")) throw new Exception("MCP 错误：" + resp.optJSONObject("error"));
        JSONObject result = resp.optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    private static long httpSeq = 100;
    private static long nextHttpId() { return ++httpSeq; }

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
