package com.dshnative;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Web UI：把电脑版那份真实前端（assets/webdist）用 http 托管起来。
 *
 * 为什么要这么做：桌面 UI 是一个 React 前端，必须跑在 http 源上（file:// 会被 CORS 拦）。
 * 起一个只监听 127.0.0.1 的小服务，WebView 加载 http://127.0.0.1:端口/ 就是**真的那份界面**，
 * 不是照着画的假 UI。
 *
 * 同时它记录前端发出的每个请求（含 method 和 body 前若干字节）——
 * 电脑版的接口清单不用猜，从流量里读出来即可。
 */
public final class WebUi {

    private WebUi() {}

    private static ServerSocket server;
    private static int port = 0;
    private static Thread acceptor;
    private static final List<String> LOG = new ArrayList<>();

    public static synchronized int start(Context context) {
        if (server != null && !server.isClosed()) return port;
        final Context ctx = context.getApplicationContext();
        try {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
        } catch (Exception e) {
            android.util.Log.e("DSHWEB", "起服务失败：" + e);
            return 0;
        }
        acceptor = new Thread(new Runnable() {
            @Override public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handle(ctx, s); }
                        }, "webui-conn").start();
                    } catch (Exception e) {
                        break;
                    }
                }
            }
        }, "webui");
        acceptor.setDaemon(true);
        acceptor.start();
        android.util.Log.i("DSHWEB", "本地 UI 起在 http://127.0.0.1:" + port + "/");
        return port;
    }

    public static String url() {
        return port == 0 ? "" : "http://127.0.0.1:" + port + "/index.html";
    }

    /** 前端到目前为止发过哪些请求（接口清单从这里读）。 */
    public static synchronized String traffic() {
        if (LOG.isEmpty()) return "（还没有请求）";
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 把已记录的请求落成文件，方便事后梳理接口清单。 */
    public static String dumpTo(Context ctx, java.io.File out) {
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            os.write(traffic().getBytes("UTF-8"));
            os.close();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return "写失败：" + t.getMessage();
        }
    }

    private static synchronized void record(String line) {
        LOG.add(line);
        if (LOG.size() > 200) LOG.remove(0);
        android.util.Log.i("DSHWEB", line);
    }

    private static void handle(Context ctx, Socket socket) {
        try {
            socket.setSoTimeout(8000);
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (head == null) { socket.close(); return; }

            String firstLine = head.split("\r\n")[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            String query = q >= 0 ? path.substring(q + 1) : "";
            String file = q >= 0 ? path.substring(0, q) : path;

            int bodyLen = 0;
            for (String h : head.split("\r\n")) {
                String lower = h.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { bodyLen = Integer.parseInt(h.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (bodyLen > 0 && bodyLen < 100000) {
                byte[] buf = new byte[bodyLen];
                int read = 0;
                while (read < bodyLen) {
                    int n = in.read(buf, read, bodyLen - read);
                    if (n <= 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, "UTF-8");
            }

            record(method + " " + path + (body.isEmpty() ? ""
                    : "  body=" + body.substring(0, Math.min(body.length(), 160))));

            android.util.Log.i("DSHWEB", "handle " + method + " " + file);
            if (file.equals("/") || file.isEmpty()) file = "/index.html";

            // 静态资源
            byte[] content = readAsset(ctx, "webdist" + file);
            android.util.Log.i("DSHWEB", "asset " + file + " -> " + (content == null ? "null" : content.length + "B"));
            if (content != null) {
                respond(socket, 200, mimeOf(file), content);
                return;
            }
            // 未命中：如实回 404，并记录——这正是我们要的接口清单
            String msg = "{\"error\":\"not implemented on device\",\"path\":\"" + file + "\"}";
            respond(socket, 404, "application/json", msg.getBytes("UTF-8"));
        } catch (Throwable t) {
            android.util.Log.e("DSHWEB", "handle failed: " + t);
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        // HTTP 头以 \r\n\r\n 结束；只判两个连续 \n 是错的（CRLF 永远凑不出来），
        // 这里按最后 4 个字节判断，同时兼容只有 \n 的客户端。
        while (bo.size() < 65536) {
            int c = in.read();
            if (c < 0) break;
            bo.write(c);
            int n = bo.size();
            if (n >= 4) {
                byte[] b = bo.toByteArray();
                if (b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n') break;
            }
            if (n >= 2) {
                byte[] b = bo.toByteArray();
                if (b[n - 2] == '\n' && b[n - 1] == '\n') break;
            }
        }
        return bo.size() == 0 ? null : bo.toString("UTF-8");
    }

    private static void respond(Socket socket, int code, String type, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
        socket.close();
    }

    private static byte[] readAsset(Context ctx, String path) {
        AssetManager am = ctx.getAssets();
        try {
            InputStream in = am.open(path.substring(1));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }
}
