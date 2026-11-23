package com.dshnative;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区：一个目录 + 一个名字，**对话历史按工作区分开**。
 *
 * 内置一个「完全权限」工作区（id=full），它就是老行为：根目录 /sdcard/DSHNative，
 * 绝对路径不受限。新建的工作区只是给相对路径换个根，并把它的会话单独成组——
 * 这样不同项目的上下文不会互相污染。
 */
public final class Workspaces {

    public static final String FULL_ID = "full";
    private static final String KEY_LIST = "workspaces";
    private static final String KEY_CURRENT = "ws_current";

    public static final class W {
        public String id;
        public String name;
        public String path;

        W(String id, String name, String path) {
            this.id = id;
            this.name = name;
            this.path = path;
        }

        public File dir() { return new File(path); }
    }

    private Workspaces() {}

    private static SharedPreferences p(Context c) { return Prefs.get(c); }

    /** 全部工作区；保证「完全权限」永远存在且排第一。 */
    public static List<W> all(Context c) {
        List<W> out = new ArrayList<>();
        String fullPath = Ws.preferred(c).getAbsolutePath();
        out.add(new W(FULL_ID, "完全权限", fullPath));
        try {
            String raw = p(c).getString(KEY_LIST, "");
            if (raw != null && !raw.isEmpty()) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    if (id.isEmpty() || FULL_ID.equals(id)) continue;
                    out.add(new W(id, o.optString("name", id), o.optString("path", "")));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static W get(Context c, String id) {
        for (W w : all(c)) if (w.id.equals(id)) return w;
        return all(c).get(0);
    }

    public static String currentId(Context c) {
        String id = p(c).getString(KEY_CURRENT, FULL_ID);
        for (W w : all(c)) if (w.id.equals(id)) return id;
        return FULL_ID;
    }

    public static W current(Context c) { return get(c, currentId(c)); }

    public static void setCurrent(Context c, String id) {
        p(c).edit().putString(KEY_CURRENT, id).apply();
    }

    /** 当前工作区目录；建不出来就退回「完全权限」的目录。 */
    public static File currentDir(Context c) {
        W w = current(c);
        File d = new File(w.path);
        if (d.isDirectory() || d.mkdirs()) return d;
        return Ws.preferred(c);
    }

    public static W create(Context c, String name, String path) {
        String id = "ws" + System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY_LIST, "[]"));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("path", path);
            a.put(o);
            p(c).edit().putString(KEY_LIST, a.toString()).apply();
        } catch (Throwable ignored) {}
        File d = new File(path);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new W(id, name, path);
    }

    public static void remove(Context c, String id) {
        if (FULL_ID.equals(id)) return;
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY_LIST, "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id", ""))) keep.put(o);
            }
            p(c).edit().putString(KEY_LIST, keep.toString()).apply();
        } catch (Throwable ignored) {}
        if (id.equals(currentId(c))) setCurrent(c, FULL_ID);
    }

    public static void rename(Context c, String id, String name) {
        if (FULL_ID.equals(id)) return;
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id", ""))) o.put("name", name);
            }
            p(c).edit().putString(KEY_LIST, a.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
