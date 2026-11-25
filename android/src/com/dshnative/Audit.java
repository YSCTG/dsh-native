package com.dshnative;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 插件审计：只记"发生了什么"，不给插件任何权限。
 *
 * 现在记两类：钩子拦截、MCP 服务启动。保存在 prefs 的 audit_<pluginId> 里，
 * 保留最近 50 条——够用户判断"这个插件到底在干什么"，又不会无限膨胀。
 */
public final class Audit {

    private Audit() {}

    private static String key(String pluginId) { return "audit_" + pluginId; }

    public static synchronized void record(Context c, String pluginId, String what, String detail) {
        try {
            JSONArray arr = read(c, pluginId);
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("what", what);
            o.put("detail", detail == null ? "" : detail);
            arr.put(o);
            while (arr.length() > 50) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < arr.length(); i++) trimmed.put(arr.opt(i));
                arr = trimmed;
            }
            Prefs.get(c).edit().putString(key(pluginId), arr.toString()).apply();
            Log.i("DSHN", "审计[" + pluginId + "] " + what + ": " + detail);
        } catch (Throwable ignored) {}
    }

    public static JSONArray read(Context c, String pluginId) {
        try {
            return new JSONArray(Prefs.get(c).getString(key(pluginId), "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** 某类事件的次数。 */
    public static int count(Context c, String pluginId, String what) {
        JSONArray arr = read(c, pluginId);
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && what.equals(o.optString("what"))) n++;
        }
        return n;
    }

    /** 某类事件最近一条的说明。 */
    public static String last(Context c, String pluginId, String what) {
        JSONArray arr = read(c, pluginId);
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && what.equals(o.optString("what"))) return o.optString("detail", "");
        }
        return "";
    }
}
