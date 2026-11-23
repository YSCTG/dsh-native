package com.dshnative;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 偏好存储的名字，集中在这里。
 *
 * 改过名（旧名 → 新名），所以第一次启动要搬家：把旧文件里的键值整份复制过来。
 * 不搬的话，用户会突然发现模式、API key、开关全没了。
 */
public final class Prefs {

    public static final String NAME = "dshnative";
    private static final String LEGACY = "dshnative";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 旧名字里的东西复制到新名字里（只做一次）。 */
    public static void migrate(Context c) {
        SharedPreferences cur = get(c);
        if (cur.getBoolean("_migrated_legacy", false)) return;
        try {
            SharedPreferences old = c.getSharedPreferences(LEGACY, Context.MODE_PRIVATE);
            Map<String, ?> all = old.getAll();
            android.util.Log.i("DSHN", "prefs migrate: 旧键 " + all.size()
                    + " onboarded=" + old.getBoolean("onboarded", false));
            if (!all.isEmpty()) {
                SharedPreferences.Editor e = cur.edit();
                for (Map.Entry<String, ?> en : all.entrySet()) {
                    Object v = en.getValue();
                    if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                    else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                    else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                    else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
                    else e.putString(en.getKey(), String.valueOf(v));
                }
                e.putBoolean("_migrated_legacy", true);
                e.apply();
            }
        } catch (Exception ignored) {}
    }
}
