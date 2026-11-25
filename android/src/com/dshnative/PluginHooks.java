package com.dshnative;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 插件的钩子。
 *
 * 插件在清单里声明 capabilities.hooks：
 *   [{"on": "tool.before", "file": "guard.py", "code": "<源码>"}]
 *
 * 运行方式：把事件以一行 JSON 写进子进程 stdin，读一行 JSON 回来：
 *   {"allow": true|false, "reason": "..."}
 *
 * 失败一律**放行**（fail-open）：插件坏了不该把整个 Agent 卡死，
 * 想强制拦截的插件必须在 stdout 明确回 allow:false。
 */
public final class PluginHooks {

    private static final String TAG = "DSHN";

    private PluginHooks() {}

    /** 某个事件的脚本路径（安装时已落盘）。 */
    private static java.util.List<String> scripts(Context c, String event) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JSONObject p : Market.enabledPlugins(c)) {
            JSONObject cap = p.optJSONObject("capabilities");
            org.json.JSONArray hooks = cap == null ? p.optJSONArray("hooks") : cap.optJSONArray("hooks");
            if (hooks == null) continue;
            for (int i = 0; i < hooks.length(); i++) {
                JSONObject h = hooks.optJSONObject(i);
                if (h == null) continue;
                if (!event.equals(h.optString("on", ""))) continue;
                String sc = h.optString("script", "");
                if (!sc.isEmpty() && new File(sc).isFile()) out.add(sc);
            }
        }
        return out;
    }

    /**
     * 跑一次钩子。
     * @return null 表示放行；否则是"拦截原因"（给模型看）
     */
    public static String before(Context c, String event, JSONObject payload) {
        for (String script : scripts(c, event)) {
            try {
                String cmd = Python.commandFor(c, new File(script));
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                OutputStream os = p.getOutputStream();
                os.write((payload.toString() + "\n").getBytes("UTF-8"));
                os.flush();
                os.close();

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line = br.readLine();
                p.waitFor();

                if (line == null || line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line.trim());
                if (!o.optBoolean("allow", true)) {
                    String reason = o.optString("reason", "被插件拦下");
                    Log.i(TAG, "钩子拦截 " + event + "：" + reason);
                    return reason;
                }
            } catch (Throwable t) {
                Log.i(TAG, "钩子执行失败（放行）：" + t);
            }
        }
        return null;
    }
}
