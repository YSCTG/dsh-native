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

    /**
     * 给真实前端注入启动门面。
     *
     * 电脑版由 dsh web 在 index.html 里注入 __ModuleLoader__ / __DSH_BOOT__ / __DSH_TRANSPORT__，
     * 前端缺了它直接报 "bootstrap facade is missing"。这里先给一个最小实现：
     * 能创建 loader、给出空插件清单，让界面先起来——缺什么它会自己点名，按名字补。
     */
    /**
     * 启动门面。
     *
     * 契约是从 bundle 里读出来的，不是猜的：
     *   modules.manifest.plugins[].id / .immediately
     *   modules.entries.start(ids, ctx)      —— 之前写成数组，所以报 "entries.start is not a function"
     *   modules.prefetch(id)                 —— 预取 immediately 的插件
     *   modules.loader.entries()             —— 遍历已注册条目，读 options.name
     *   modules.importError(name)            —— 导入失败时取原因
     * 插件清单先给空数组：界面骨架（侧栏/头部/输入区）应该能先渲染出来。
     */
    private static final String BOOT_SHIM =
        "<script>(function(){"
      + "globalThis.__DSH_BOOT_READY__={promise:Promise.resolve()};"
      + "globalThis.__DSH_BOOT__={version:'0.0.0-native',sessions:[],plugins:[],settings:{}};"
      + "globalThis.__DSH_TRANSPORT__={};"
      + "globalThis.__ModuleLoader__={create:function(o){"
      + "  o=o||{};"
      + "  var modules={"
      + "    manifest:{plugins:[],version:'0.0.0-native'},"
      + "    entries:{start:function(ids,ctx){"
      + "      try{"
      + "        var proto=Object.getOwnPropertyNames(Object.getPrototypeOf(ctx)||{});"
      + "        var own=Object.getOwnPropertyNames(ctx||{});"
      + "        var d=document.createElement('pre');"
      + "        d.id='dsh-probe';"
      + "        d.textContent='CTXPROTO['+proto.join(',')+'] CTXOWN['+own.join(',')+']';"
      + "        document.body.appendChild(d);"
      + "      }catch(e){}"
      + "      return Promise.resolve();"
      + "    }},"
      + "    prefetch:function(id){return Promise.resolve();},"
      + "    load:function(id){return Promise.resolve();},"
      + "    loadEntry:function(id){return Promise.resolve();},"
      + "    resolve:function(id){return null;},"
      + "    importError:function(name){return undefined;},"
      + "    loader:{entries:function(){return [];}},"
      + "    create:function(){return modules;}"
      + "  };"
      + "  return modules;"
      + "}};"
      + "})();</script>";

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
        // 自检：App 自己看到的 zip 里到底有什么
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getPackageCodePath());
            StringBuilder sb = new StringBuilder("zip 条目样例：");
            int seen = 0, assets = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements() && seen < 4000) {
                String n = en.nextElement().getName();
                seen++;
                if (n.startsWith("assets/")) {
                    assets++;
                    if (assets <= 6) sb.append(n).append(' ');
                }
            }
            sb.append("| total=").append(seen).append(" assets=").append(assets);
            z.close();
            fileLog(sb.toString());
        } catch (Throwable t) {
            fileLog("zip 打不开：" + t);
        }
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

    private static void fileLog(String line) {
        try {
            java.io.File f = new java.io.File("/sdcard/DSHNative/webui.log");
            f.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss")
                    .format(new java.util.Date()) + "  " + line + "\n");
            w.close();
        } catch (Throwable ignored) {}
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
            boolean wantRaw = q >= 0 && path.substring(q).contains("raw=1");
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
            if (file.equals("/__test")) {
                String probe = "";
                for (String cand : new String[]{"skills.json", "webdist/index.html",
                        "assets/webdist/index.html", "webdist", "webdist/assets",
                        "assets/webdist/assets/index-CN7NIWNw.js"}) {
                    try {
                        InputStream t = ctx.getAssets().open(cand);
                        probe += cand + "=OK(" + t.available() + ") ";
                        t.close();
                    } catch (Throwable e) {
                        probe += cand + "=" + e.getClass().getSimpleName() + " ";
                    }
                }
                StringBuilder roots = new StringBuilder();
                try {
                    String[] w = ctx.getAssets().list("webdist");
                    roots.append("|webdist 下: ");
                    for (String a : w) roots.append(a).append(',');
                } catch (Throwable t) {
                    roots.append("|webdist 列不出: ").append(t);
                }
                roots.append("|包名: ").append(ctx.getPackageName());
                respond(socket, 200, "text/plain; charset=utf-8",
                        (probe + "|assets 根: " + roots).getBytes("UTF-8"));
                return;
            }
            if (file.equals("/") || file.isEmpty()) file = "/index.html";
            boolean isIndex = file.equals("/index.html");

            // 静态资源
            byte[] content = readAsset(ctx, "webdist" + file);
            if (content != null && isIndex && !wantRaw) {
                content = withShim(content);
                fileLog("index.html 已注入 shim（" + content.length + "B）");
            }
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

    /**
     * 从 APK 的 zip 里直接取资源，不走 AssetManager。
     *
     * 实测 AssetManager 读不到这 90 个文件（open/list 都说没有），但 APK 里明明有；
     * 与其猜它的压缩/索引规则，不如直接读自己的 APK——路径稳定，行为可预期。
     */
    private static java.util.zip.ZipFile apk;
    private static String apkPath;

    private static byte[] readAsset(Context ctx, String path) {
        try {
            if (apk == null || !ctx.getPackageCodePath().equals(apkPath)) {
                apkPath = ctx.getPackageCodePath();
                apk = new java.util.zip.ZipFile(apkPath);
            }
            String entryName = path.startsWith("/") ? "assets" + path : "assets/" + path;
            fileLog("read " + entryName + " apk=" + ctx.getPackageCodePath());
            java.util.zip.ZipEntry e = apk.getEntry(entryName);
            if (e == null) {
                // Windows 上的 aapt2 会把嵌套目录写成反斜杠：assets/webdist\index.html
                e = apk.getEntry(entryName.replace('/', '\\'));
            }
            if (e == null) {
                e = apk.getEntry("assets/" + path.replace("/", "\\"));
            }
            if (e == null) {
                fileLog("  MISS " + entryName);
                return null;
            }
            InputStream in = apk.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            fileLog("  OK " + entryName + " " + bo.size() + "B");
            return bo.toByteArray();
        } catch (Throwable t) {
            fileLog("  ERR " + path + " " + t);
            return null;
        }
    }

    /** 在已读到的 index.html 里插入启动门面。不再二次读文件：同一个路径两种结果，查不动。 */
    private static byte[] withShim(byte[] raw) {
        try {
            String html = new String(raw, "UTF-8");
            String shim = BOOT_SHIM;
            int at = html.indexOf("<script");
            if (at < 0) at = html.indexOf("</head>");
            if (at < 0) at = html.length();
            String out = html.substring(0, at) + shim + html.substring(at);
            return out.getBytes("UTF-8");
        } catch (Throwable t) {
            fileLog("注入失败：" + t);
            return raw;
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
