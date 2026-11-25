package com.dshnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.graphics.Typeface;
import android.widget.CompoundButton;
import android.widget.Switch;
import java.util.List;
import android.view.Gravity;
import android.view.View;
import org.json.JSONObject;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：模式、插件、推理等级、MCP、定时、环境、关于。 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private LinearLayout content;
    private ScrollView scrollView;
    private boolean showMcpForm, showSchedForm, showKeyForm, showGhForm;
    private EditText fA, fB, fC;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dshnative", MODE_PRIVATE);
        // 支持从外部直接导入：am start … --es import_src "lodash" 或一个清单 URL
        String direct = getIntent() == null ? null : getIntent().getStringExtra("import_src");
        if (direct != null && !direct.trim().isEmpty()) {
            prefs.edit().putString("market_pending", direct.trim()).apply();
        }
        int bg = getColor(R.color.bg_base);
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int night = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        w.getDecorView().setSystemUiVisibility(flags);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg_base));
        root.addView(toolbar());

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(28));
        sv.addView(content);
        scrollView = sv;
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        build();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(getColor(R.color.label_secondary));
        back.setPadding(dp(10), dp(2), dp(12), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(getColor(R.color.label_primary));
        bar.addView(title);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView ver = new TextView(this);
        ver.setText("v" + versionName());
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ver.setTextColor(getColor(R.color.label_caption));
        bar.addView(ver);

        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border_l1));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------------- 组件 ----------------


    /** 是否已在电池优化白名单里。不在就直说，用户才能判断要不要点。 */
    private String batteryOptHint() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(POWER_SERVICE);
            boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            return ok ? "已允许后台不受限（锁屏可继续跑）" : "未允许：点这里申请，否则锁屏后可能被系统掐断";
        } catch (Throwable t) {
            return "无法读取（系统版本限制）";
        }
    }

    private void askIgnoreBattery() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                android.widget.Toast.makeText(this, "请到系统设置里允许本应用后台运行",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }


    /** 拉远程目录：URL 存在 prefs.market_url，拉回来写 files/market-cache.json。 */

    /** 目录来源：填一个返回 market JSON 的地址；留空则回到内置示例目录。 */

    /** 从电脑版插件导入：走 npm 元数据 → Native 技能包。 */
    /**
     * 添加来源：一个 npm 包名，或一个返回 Native 清单/目录的 URL。
     * 导入后自动转译（补全字段、把用法变成技能、标注不可执行部分），并进入「已安装」。
     */
    private void importFromDesktop() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("npm 包名 或 https://…/market.json");
        input.setTextSize(13);
        input.setSingleLine(true);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("添加来源")
                .setMessage("两种都行：\n"
                        + "· npm 包名（如 @deepseek-ai/dsh-skill）：读取它的元数据与 README，\n"
                        + "  转成技能——JS 实现在手机上没有运行时，只能迁移\"怎么用\"。\n"
                        + "· Native 清单/目录 URL：按其声明的技能与 Python 工具安装，工具可直接用。")
                .setView(input)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String src = input.getText().toString().trim();
                        if (src.isEmpty()) return;
                        toast("正在导入…");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final StringBuilder log = new StringBuilder();
                                int ok = 0;
                                if (src.startsWith("http://") || src.startsWith("https://")) {
                                    java.util.List<JSONObject> list = DesktopImport.fromUrl(SettingsActivity.this, src);
                                    for (JSONObject m : list) {
                                        DesktopImport.install(SettingsActivity.this, m);
                                        ok++;
                                    }
                                    if (ok == 0) log.append("这个地址没有可用的插件清单");
                                } else {
                                    DesktopImport.Result r = DesktopImport.fromNpm(SettingsActivity.this, src);
                                    if (r.manifest != null) {
                                        DesktopImport.install(SettingsActivity.this, r.manifest);
                                        ok = 1;
                                    } else {
                                        log.append(r.error == null ? "转换失败" : r.error);
                                    }
                                }
                                final int done = ok;
                                final String msg = log.toString();
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (done > 0) {
                                            toast("已导入 " + done + " 个，去「已安装」看看（新会话生效）");
                                            recreate();
                                        } else {
                                            toast(msg.isEmpty() ? "导入失败" : msg);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editMarketSource() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(prefs.getString("market_url", ""));
        input.setHint("https://…/market.json（留空用内置目录）");
        input.setTextSize(13);
        input.setSingleLine(true);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("插件目录来源")
                .setMessage("任何返回 Native 清单 JSON 的地址都能当目录。留空 = 用内置示例。")
                .setView(input)
                .setPositiveButton("保存并刷新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("market_url", input.getText().toString().trim()).apply();
                        refreshMarket();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshMarket() {
        final String url = prefs.getString("market_url", "");
        if (url == null || !url.startsWith("http")) {
            toast("先在设置里填目录 URL（或留空用内置目录）");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    body = bo.toString("UTF-8");
                } catch (Throwable ignored) {}
                final String text = body;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (text == null || !text.trim().startsWith("{")) {
                            toast("拉取失败或格式不对");
                            return;
                        }
                        try {
                            java.io.FileOutputStream os = new java.io.FileOutputStream(
                                    new java.io.File(getFilesDir(), "market-cache.json"));
                            os.write(text.getBytes("UTF-8"));
                            os.close();
                            toast("目录已更新");
                            recreate();
                        } catch (Throwable t) {
                            toast("写入失败：" + t.getMessage());
                        }
                    }
                });
            }
        }).start();
    }


    /** 有 --es import_src 时，进来就开始导入并把结果写进"最近一次导入"。 */

    /** 插件配置：按声明的字段自动生成表单，不涉及任何 WebView / JS。 */
    /** 插件声明的钩子数量（用于判断要不要给它一个状态面板）。 */
    private int pluginHooks(org.json.JSONObject p) {
        org.json.JSONObject cap = p.optJSONObject("capabilities");
        org.json.JSONArray h = cap == null ? p.optJSONArray("hooks") : cap.optJSONArray("hooks");
        return h == null ? 0 : h.length();
    }

    private void openPluginConfig(final org.json.JSONObject plugin) {
        final String id = plugin.optString("id", "");
        final org.json.JSONArray fields = Market.settings(plugin);
        // 没有配置项也要能打开：钩子/审计面板同样在这里（status 面板）
        boolean anyInteraction = fields.length() > 0 || pluginHooks(plugin) > 0
                || Audit.read(this, plugin.optString("id", "")).length() > 0;
        if (!anyInteraction) { toast("这个插件没有配置项，也没有审计记录"); return; }
        final org.json.JSONObject cur = Market.cfg(this, plugin);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(4));
        final java.util.List<View> widgets = new java.util.ArrayList<>();
        final java.util.List<String> keys = new java.util.ArrayList<>();
        final java.util.List<String> types = new java.util.ArrayList<>();

        // ---- 状态（只读）：权限 / 指纹 / 拦截次数 ----
        try {
            String[] pm2 = Market.perms(plugin);
            int blocks = Audit.count(this, id, "拦截");
            String lastReason = Audit.last(this, id, "拦截");
            String sha = plugin.optString("_sha256", "");
            StringBuilder st = new StringBuilder();
            st.append("-net".equals(pm2[0]) ? "禁止联网" : "允许联网").append(" · ")
              .append("-shell".equals(pm2[1]) ? "禁止命令" : "允许命令");
            if (!sha.isEmpty()) st.append(" · 指纹 ").append(sha);
            st.append("\n钩子拦截 ").append(blocks).append(" 次");
            if (blocks > 0 && !lastReason.isEmpty()) {
                st.append("（最近：").append(lastReason).append("）");
            }
            TextView stv = new TextView(this);
            stv.setText(st.toString());
            stv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            stv.setTextColor(getColor(R.color.label_tertiary));
            stv.setPadding(0, 0, 0, dp(10));
            box.addView(stv);
        } catch (Throwable ignored) {}

        for (int i = 0; i < fields.length(); i++) {
            org.json.JSONObject f = fields.optJSONObject(i);
            if (f == null) continue;
            String key = f.optString("key", "");
            if (key.isEmpty()) continue;
            String label = f.optString("label", key);
            String type = f.optString("type", "string");

            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(getColor(R.color.label_secondary));
            box.addView(t);

            if ("bool".equals(type)) {
                android.widget.Switch sw = new android.widget.Switch(this);
                sw.setChecked(cur.optBoolean(key, f.optBoolean("default", false)));
                sw.setTag(key);
                box.addView(sw);
                widgets.add(sw); keys.add(key); types.add("bool");
            } else {
                android.widget.EditText e = new android.widget.EditText(this);
                e.setText(cur.optString(key, f.optString("default", "")));
                e.setHint(f.optString("hint", ""));
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                if ("secret".equals(type)) {
                    e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                } else if ("number".equals(type)) {
                    e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                            | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                }
                box.addView(e);
                widgets.add(e); keys.add(key); types.add(type);
            }
        }

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(plugin.optString("name", id) + " 配置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        org.json.JSONObject out = new org.json.JSONObject();
                        for (int i = 0; i < keys.size(); i++) {
                            try {
                                String key = keys.get(i);
                                View ctl = widgets.get(i);
                                if ("bool".equals(types.get(i))) {
                                    out.put(key, ((android.widget.Switch) ctl).isChecked());
                                } else if ("number".equals(types.get(i))) {
                                    String v = ((android.widget.EditText) ctl).getText().toString().trim();
                                    out.put(key, v.isEmpty() ? 0 : Double.parseDouble(v));
                                } else {
                                    out.put(key, ((android.widget.EditText) ctl).getText().toString());
                                }
                            } catch (Throwable ignored) {}
                        }
                        Market.saveCfg(SettingsActivity.this, id, out);
                        toast("已保存，新会话生效");
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runPendingImport() {
        final String src = prefs.getString("market_pending", "");
        if (src == null || src.isEmpty()) return;
        prefs.edit().remove("market_pending").apply();

        // 本地清单（file:// 或 /sdcard 路径）：离线安装
        if (src.startsWith("file://") || src.startsWith("/sdcard") || src.startsWith("/storage")) {
            String path = src.startsWith("file://") ? src.substring(7) : src;
            java.util.List<org.json.JSONObject> list = DesktopImport.fromFile(new java.io.File(path));
            if (list.isEmpty()) { toast("清单读不出来：" + path); return; }
            int n = 0;
            for (org.json.JSONObject m : list) { DesktopImport.install(this, m); n++; }
            toast("已导入 " + n + " 个本地插件");
            recreate();
            return;
        }

        // catalog:<url> = 把某个地址设为目录来源（便于分享"目录链接"、也便于自动化测试）
        if (src.startsWith("catalog:")) {
            String url = src.substring("catalog:".length()).trim();
            prefs.edit().putString("market_url", url).apply();
            new java.io.File(getFilesDir(), "market-cache.json").delete();
            toast("已设为目录来源，正在刷新…");
            refreshMarket();
            return;
        }
        toast("正在导入 " + src + " …");
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                String err = "";
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    java.util.List<JSONObject> list = DesktopImport.fromUrl(SettingsActivity.this, src);
                    for (JSONObject m : list) { DesktopImport.install(SettingsActivity.this, m); ok++; }
                    if (ok == 0) err = "地址里没有可用的插件清单";
                } else {
                    DesktopImport.Result r = DesktopImport.fromNpm(SettingsActivity.this, src);
                    if (r.manifest != null) { DesktopImport.install(SettingsActivity.this, r.manifest); ok = 1; }
                    else err = r.error == null ? "转换失败" : r.error;
                }
                String stamp = new java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(new java.util.Date());
                prefs.edit().putString("market_last", stamp + " · " + src
                        + (ok > 0 ? " · 已导入 " + ok + " 个" : " · 失败：" + err)).apply();
                final int done = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(done > 0 ? "已导入 " + done + " 个（新会话生效）" : "导入失败，见市场里的记录");
                        recreate();
                    }
                });
            }
        }).start();
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(getColor(R.color.label_caption));
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(22), dp(20), dp(22), dp(7));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        content.addView(c, lp);
        return c;
    }

    private void divider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(getColor(R.color.border_l1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        parent.addView(d, lp);
    }

    /** 一行：左边标题 + 说明，右边是开关。 */
    private LinearLayout switchRow(LinearLayout parent, String label, String sub,
                           boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.label_primary));
        text.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setTextColor(getColor(R.color.label_tertiary));
            s.setPadding(0, dp(2), 0, 0);
            text.addView(s);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        parent.addView(row);
        return row;
    }

    /** 一行：标题 + 说明，可点。 */
    private void actionRow(LinearLayout parent, String label, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        if (l != null) row.setOnClickListener(l);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.label_primary));
        row.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setTextColor(getColor(R.color.label_tertiary));
            s.setPadding(0, dp(2), 0, 0);
            row.addView(s);
        }
        parent.addView(row);
    }

    // ---------------- 内联表单（替代系统默认对话框） ----------------

    private EditText field(LinearLayout parent, String hint, boolean multiline, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(getColor(R.color.label_primary));
        e.setHintTextColor(getColor(R.color.label_caption));
        e.setBackgroundResource(R.drawable.bg_field);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (multiline) {
            e.setMinLines(2);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(e, lp);
        return e;
    }

    private void formButtons(LinearLayout parent, String submit, View.OnClickListener ok,
                             final Runnable cancel) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(10), 0, 0);

        TextView c = new TextView(this);
        c.setText("取消");
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        c.setTextColor(getColor(R.color.label_tertiary));
        c.setPadding(dp(14), dp(8), dp(16), dp(8));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel.run(); }
        });
        row.addView(c);

        TextView s = new TextView(this);
        s.setText(submit);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setTextColor(getColor(R.color.accent));
        s.setPadding(dp(12), dp(8), dp(4), dp(8));
        s.setOnClickListener(ok);
        row.addView(s);

        parent.addView(row);
    }
    // ---------------- 内容 ----------------

    /** 无障碍的两条路：系统页（可能要按音量键）或电脑上一条 adb（不需要按）。 */

    private void a11yHelp() {
        final String cmd =
                "adb shell settings put secure enabled_accessibility_services com.dshnative/com.dshnative.UiAgentService\n"
              + "adb shell settings put secure accessibility_enabled 1";
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("开启无障碍")
                .setMessage("方法一：点「打开系统设置」→ 无障碍 → 已下载的服务 → DSH Native。\n"
                        + "华为可能要求按住音量上+下键 2 秒确认。\n\n"
                        + "方法二（音量键坏了就用这个）：手机连电脑，跑这两条：\n\n" + cmd)
                .setPositiveButton("复制 adb 命令", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd));
                            toast("命令已复制");
                        }
                    }
                })
                .setNeutralButton("打开系统设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Compat.openAccessibility(SettingsActivity.this);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Screen.REQ_CONSENT) {
            String err = Screen.start(this, res, data);
            toast(err == null ? "截屏已授权" : err);
            build();
        }
    }

    private void build() {
        runPendingImport();
        final int keepY = scrollView == null ? 0 : scrollView.getScrollY();
        content.removeAllViews();

        // 模式
        section("模式");
        LinearLayout modeCard = card();
        final Mode cur = Mode.byId(getSharedPreferences("dshnative", MODE_PRIVATE).getString("mode", "standard"));
        actionRow(modeCard, "✓  " + cur.label, "当前模式 · " + cur.hint, null);
        for (Mode m : Mode.ALL) {
            if (m.id.equals(cur.id)) continue;
            divider(modeCard);
            actionRow(modeCard, m.label, m.hint, new View.OnClickListener() {
                @Override public void onClick(View v) { setMode(m); }
            });
        }

        // 插件（内置）
        section("插件 · 内置能力");
        LinearLayout plugCard = card();
        for (int i = 0; i < Plugin.BUILTINS.length; i++) {
            final String[] b = Plugin.BUILTINS[i];
            String sub = "builtin.skills".equals(b[0])
                    ? Skill.all(this).size() + " 个技能 · 一个开关" : b[2];
            switchRow(plugCard, b[1], sub, Plugin.isOn(this, b[0]),
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(CompoundButton btn, boolean on) {
                            Plugin.setOn(SettingsActivity.this, b[0], on);
                        }
                    });
            if (i < Plugin.BUILTINS.length - 1) divider(plugCard);
        }

        // 插件市场：独立板块（和「已装的插件」分开——这里只列可安装的）
        section("插件市场");
        LinearLayout marketCard = card();
        String src = Market.sourceLabel(this);
        int n = Market.plugins(this).size();
        actionRow(marketCard, n == 0 ? "还没有插件" : (n + " 个可安装插件"),
                (n == 0 ? "用下面的「添加来源」导入你需要的插件；导入即转译，装完开新会话生效"
                        : "点按安装，装完开新会话生效 · 目录来源：" + src), null);
        java.util.List<JSONObject> catalog = Market.plugins(this);
        for (final JSONObject mp : catalog) {
            final String pid = mp.optString("id", "");
            boolean on = Market.installed(this, pid);
            String sub = mp.optString("version", "") + " · " + mp.optString("category", "")
                    + " · " + mp.optString("description", "");
            divider(marketCard);
            actionRow(marketCard, (on ? "✓ " : "") + mp.optString("name", pid)
                            + (on ? "（已安装，点按移除）" : "（点按安装）"),
                    sub,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (Market.installed(SettingsActivity.this, pid)) {
                                Market.uninstall(SettingsActivity.this, pid);
                                toast("已移除，新会话生效");
                            } else {
                                Market.install(SettingsActivity.this, mp);
                                toast("已安装，新会话生效");
                            }
                            recreate();
                        }
                    });
        }
        divider(marketCard);
        divider(marketCard);
        actionRow(marketCard, "添加来源",
                "填 npm 包名（如 @deepseek-ai/dsh-skill）。电脑版插件是 Node 模块，代码跑不了，"
                        + "能迁过来的是它的用法→变成技能提示词",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { importFromDesktop(); }
                });
        divider(marketCard);
        String last = prefs.getString("market_last", "");
        if (!last.isEmpty()) {
            divider(marketCard);
            actionRow(marketCard, "最近一次导入", last, null);
        }
        actionRow(marketCard, "目录来源 / 刷新",
                Market.sourceDetail(this),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { editMarketSource(); }
                });



        // 插件（.dshp）
        List<Plugin> ds = Plugin.installed(this);
        section("插件 · 已安装");
        LinearLayout dshpCard = card();

        // 市场装的原生插件：和内置插件一样用开关控制启停；卸载放到长按
        for (final JSONObject np : Market.installedPlugins(this)) {
            final String nid = np.optString("id", "");
            String[] pm = Market.perms(np);
            String permLine = "v" + np.optString("version", "") + " · 市场插件 · "
                    + ("-net".equals(pm[0]) ? "禁联网" : "可联网") + " · "
                    + ("-shell".equals(pm[1]) ? "禁命令" : "可执行命令")
                    + (np.optString("_sha256", "").isEmpty() ? "" : " · 校验 " + np.optString("_sha256", ""));
            switchRow(dshpCard, np.optString("name", nid), permLine,
                    Market.isOn(this, nid),
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(CompoundButton btn, boolean on) {
                            Market.setOn(SettingsActivity.this, nid, on);
                            toast(on ? "已启用，新会话生效" : "已停用，新会话生效");
                        }
                    });
            final View nativeRow = dshpCard.getChildAt(dshpCard.getChildCount() - 1);
            final org.json.JSONArray cfgFields = Market.settings(np);
            nativeRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 有配置项、有钩子、或有审计记录，都值得点开
                    boolean hasCfg = Market.settings(np).length() > 0;
                    boolean hasHooks = pluginHooks(np) > 0;
                    boolean hasAudit = Audit.read(SettingsActivity.this, nid).length() > 0;
                    if (hasCfg || hasHooks || hasAudit) openPluginConfig(np);
                }
            });
            nativeRow.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    new AlertDialog.Builder(SettingsActivity.this, R.style.DshDialog)
                            .setTitle("卸载 " + np.optString("name", nid))
                            .setMessage("会删除这个插件的清单与技能，不可恢复。")
                            .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    Market.uninstall(SettingsActivity.this, nid);
                                    toast("已卸载");
                                    recreate();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                }
            });
            divider(dshpCard);
        }
        if (ds.isEmpty()) {
            actionRow(dshpCard, "还没有 .dshp 插件", "插件 = manifest.json + tools/*.json；长按可卸载", null);
        } else {
            for (int i = 0; i < ds.size(); i++) {
                final Plugin p = ds.get(i);
                LinearLayout row = switchRow(dshpCard, p.name, p.id + " · " + p.summary(),
                        Plugin.isOn(this, "dshp:" + p.id),
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton btn, boolean on) {
                                Plugin.setOn(SettingsActivity.this, "dshp:" + p.id, on);
                            }
                        });
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this, R.style.DshDialog)
                                .setTitle("卸载 " + p.name + " ?")
                                .setMessage("插件文件会从 plugins/ 里删掉。")
                                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        Plugin.uninstall(SettingsActivity.this, p.id);
                                        toast("已卸载，开新会话生效");
                                        build();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                        return true;
                    }
                });
                if (i < ds.size() - 1) divider(dshpCard);
            }
        }
        LinearLayout addCard = card();
        actionRow(addCard, "装示例 .dshp", "写一个能跑的完整例子到 plugins/；长按已有插件可卸载", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String err = Plugin.installSample(SettingsActivity.this);
                toast(err == null ? "已装，开新会话生效" : err);
                build();
            }
        });

        // 推理等级
        section("推理等级");
        LinearLayout thinkCard = card();
        String curEffort = getSharedPreferences("dshnative", MODE_PRIVATE).getString("reasoning_effort", "max");
        String[][] levels = {
                {"max", "Max", "最久，质量最高"},
                {"high", "High", "平衡"},
                {"medium", "Medium", "省钱"},
                {"low", "Low", "快"},
                {"none", "Off", "不推理"},
        };
        for (int i = 0; i < levels.length; i++) {
            final String lid = levels[i][0];
            boolean on = lid.equals(curEffort);
            actionRow(thinkCard, (on ? "✓  " : "    ") + levels[i][1],
                    (on ? "当前 · " : "") + levels[i][2], new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            getSharedPreferences("dshnative", MODE_PRIVATE).edit()
                                    .putString("reasoning_effort", lid).apply();
                            toast("已设为「" + lid + "」，新会话生效");
                            build();
                        }
                    });
            if (i < levels.length - 1) divider(thinkCard);
        }
        // MCP
        section("MCP");
        LinearLayout mcpCard = card();
        List<Mcp.Server> ms = Mcp.servers(this);
        if (ms.isEmpty()) {
            actionRow(mcpCard, "还没有 server", "支持 HTTP 传输的 MCP server", null);
        } else {
            for (int i = 0; i < ms.size(); i++) {
                final Mcp.Server s = ms.get(i);
                switchRow(mcpCard, s.name, s.isStdio() ? ("命令：" + s.command) : s.url, s.enabled,
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton btn, boolean on) {
                                Mcp.setEnabled(SettingsActivity.this, s.name, on);
                            }
                        });
                if (i < ms.size() - 1) divider(mcpCard);
            }
        }
        if (showMcpForm) {
            LinearLayout f = card();
            fA = field(f, "名字，如 studio", false, false);
            fB = field(f, "http://host:port/mcp 或本地命令 sh mcp.sh", false, false);
            formButtons(f, "添加", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String n = fA.getText().toString().trim();
                    String u = fB.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) { toast("名字和目标都要填"); return; }
                    // 像 URL 走 HTTP，否则当本地命令（stdio）：两种完全不同的传输
                    if (u.startsWith("http://") || u.startsWith("https://")) {
                        Mcp.add(SettingsActivity.this, n, u);
                    } else {
                        Mcp.addCommand(SettingsActivity.this, n, u);
                    }
                    showMcpForm = false;
                    build();
                }
            }, new Runnable() { @Override public void run() { showMcpForm = false; build(); } });
        } else {
            LinearLayout mcpAdd = card();
            actionRow(mcpAdd, "添加 MCP server", "名字 + URL（HTTP）或本地命令（stdio）",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { askMcp(); }
                    });
        }

        // 定时
        section("定时任务");
        LinearLayout schCard = card();
        List<Schedule.Task> ss = Schedule.list(this);
        if (ss.isEmpty()) {
            actionRow(schCard, "还没有定时任务", "到点在后台跑一轮，结果写进新会话并发通知", null);
        } else {
            for (int i = 0; i < ss.size(); i++) {
                final Schedule.Task t = ss.get(i);
                switchRow(schCard, t.name,
                        "每 " + t.intervalMin + " 分钟 · " + (t.lastRun == 0 ? "还没跑过"
                                : android.text.format.DateFormat.format("MM-dd HH:mm", t.lastRun)),
                        t.enabled, new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton btn, boolean on) {
                                Schedule.setEnabled(SettingsActivity.this, t.id, on);
                            }
                        });
                if (i < ss.size() - 1) divider(schCard);
            }
        }
        if (showSchedForm) {
            LinearLayout f = card();
            fA = field(f, "名字，如 每日简报", false, false);
            fB = field(f, "要它做什么（越具体越好）", true, false);
            fC = field(f, "间隔分钟，如 60", false, true);
            formButtons(f, "创建", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String n = fA.getText().toString().trim();
                    String p = fB.getText().toString().trim();
                    int m;
                    try { m = Integer.parseInt(fC.getText().toString().trim()); } catch (Exception e) { m = 60; }
                    if (n.isEmpty() || p.isEmpty()) { toast("名字和内容都要填"); return; }
                    Schedule.add(SettingsActivity.this, n, p, m);
                    showSchedForm = false;
                    build();
                }
            }, new Runnable() { @Override public void run() { showSchedForm = false; build(); } });
        } else {
            LinearLayout schAdd = card();
            actionRow(schAdd, "新建定时任务", "无人值守时不会执行需要确认的操作",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { askSchedule(); }
                    });
        }

        // 环境自检
        section("环境");
        LinearLayout envCard = card();
        envRow(envCard, UiAgentService.isReady(), "无障碍服务",
                UiAgentService.isReady() ? "已连接" : "未连接：音量键坏了就用 adb（点这里看命令）",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { a11yHelp(); }
                });
        divider(envCard);
        envRow(envCard, Compat.allFilesGranted(this), "所有文件访问",
                Compat.allFilesGranted(this) ? "已授权" : "只能读写 App 自己的工作区",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openAllFiles(SettingsActivity.this); }
                });
        divider(envCard);
        envRow(envCard, Compat.batteryIgnored(this), "电池优化白名单",
                (Compat.batteryIgnored(this) ? "已白名单" : "锁屏久了可能被回收")
                        + " · 华为/小米还需允许「自启动」「后台运行」",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openBattery(SettingsActivity.this); }
                });
        divider(envCard);
        envRow(envCard, Compat.notificationsOn(this), "通知权限",
                Compat.notificationsOn(this) ? "可以发通知" : "未开启",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openNotifications(SettingsActivity.this); }
                });
        divider(envCard);
        envRow(envCard, Screen.ready(), "截屏授权",
                Screen.ready() ? "已授权（重启 App 后要重新授权）" : "未授权：agent 不能看屏幕",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        android.content.Intent i = Screen.consentIntent(SettingsActivity.this);
                        if (i == null) { toast("这台设备不支持"); return; }
                        try { startActivityForResult(i, Screen.REQ_CONSENT); }
                        catch (Exception e) { toast("打不开授权页：" + e.getMessage()); }
                    }
                });
        actionRow(envCard, "导出数据（换包名/换机用）",
                "把会话、插件、偏好和 API key 写到工作区的 .migrate 目录", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        toast(Migrate.export(SettingsActivity.this));
                    }
                });
        divider(envCard);
        actionRow(envCard, "自启动 / 后台管理", "各家 ROM 不一样，点开自己确认", new View.OnClickListener() {
            @Override public void onClick(View v) { Compat.openAutostart(SettingsActivity.this); }
        });

        // 关于
        section("关于");
        LinearLayout aboutCard = card();
        actionRow(aboutCard, "DSH Native " + versionName(),
                Build.MANUFACTURER + " · Android " + Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT, null);
        divider(aboutCard);
        boolean enc = Secret.isEncrypted(getSharedPreferences("dshnative", MODE_PRIVATE));
        actionRow(aboutCard, enc ? "API key：已加密（Keystore）" : "API key：明文", "长按主界面顶栏可以更换", null);
        divider(aboutCard);
        actionRow(aboutCard, "审计 " + Policy.Audit.count(getFilesDir()) + " 条", "append-only + hash 链", null);
        divider(aboutCard);
        if (showGhForm) {
            LinearLayout f = card();
            final android.content.SharedPreferences sp2 = getSharedPreferences("dshnative", MODE_PRIVATE);
            fA = field(f, "ghp_… 或 github_pat_…", false, false);
            fA.setText(sp2.getString("github_token", ""));
            formButtons(f, "保存", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sp2.edit().putString("github_token", fA.getText().toString().trim()).apply();
                    toast("已保存，git/gh 立刻可用");
                    showGhForm = false;
                    build();
                }
            }, new Runnable() { @Override public void run() { showGhForm = false; build(); } });
        } else {
            boolean has = !getSharedPreferences("dshnative", MODE_PRIVATE).getString("github_token", "").isEmpty();
            actionRow(aboutCard, has ? "GitHub token：已配置" : "GitHub token：未配置",
                    "给 gh / git 用（GH_TOKEN），PR 类技能靠它", new View.OnClickListener() {
                        @Override public void onClick(View v) { showGhForm = true; build(); }
                    });
        }
        divider(aboutCard);
        if (showKeyForm) {
            LinearLayout f = card();
            final android.content.SharedPreferences sp = getSharedPreferences("dshnative", MODE_PRIVATE);
            fA = field(f, "sk-…", false, false);
            fA.setText(Secret.get(sp));
            fB = field(f, "base URL（留空用官方地址）", false, false);
            fB.setText(sp.getString("base_url", ""));
            formButtons(f, "保存", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Secret.put(sp, fA.getText().toString().trim());
                    sp.edit().putString("base_url", fB.getText().toString().trim()).apply();
                    toast("已保存");
                    showKeyForm = false;
                    build();
                }
            }, new Runnable() { @Override public void run() { showKeyForm = false; build(); } });
        } else {
            actionRow(aboutCard, "换 API key", "改完直接生效，不用重开 App", new View.OnClickListener() {
                @Override public void onClick(View v) { showKeyForm = true; build(); }
            });
        }

        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override public void run() { scrollView.scrollTo(0, keepY); }
            });
        }
    }

    private String brief(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
    private void envRow(LinearLayout parent, boolean ok, String label, String sub, View.OnClickListener l) {
        actionRow(parent, (ok ? "✓  " : "✗  ") + label, sub, l);
    }

    private void setMode(Mode m) {
        getSharedPreferences("dshnative", MODE_PRIVATE).edit().putString("mode", m.id).apply();
        toast("已切到「" + m.label + "」，回主界面开新会话生效");
        build();
    }

    private void askMcp() {
        if (true) { showMcpForm = true; build(); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(0));
        final EditText etName = new EditText(this);
        etName.setHint("名字，如 studio");
        final EditText etUrl = new EditText(this);
        etUrl.setHint("http://host:port/mcp");
        box.addView(etName);
        box.addView(etUrl);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("添加 MCP server")
                .setView(box)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = etName.getText().toString().trim();
                        String u = etUrl.getText().toString().trim();
                        if (n.isEmpty() || u.isEmpty()) { toast("名字和地址都要填"); return; }
                        Mcp.add(SettingsActivity.this, n, u);
                        build();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void askSchedule() {
        if (true) { showSchedForm = true; build(); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(0));
        final EditText etName = new EditText(this);
        etName.setHint("名字，如 每日简报");
        final EditText etPrompt = new EditText(this);
        etPrompt.setHint("要它做什么");
        etPrompt.setMinLines(2);
        final EditText etMin = new EditText(this);
        etMin.setHint("间隔分钟，如 60");
        etMin.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(etName);
        box.addView(etPrompt);
        box.addView(etMin);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("新建定时任务")
                .setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = etName.getText().toString().trim();
                        String p = etPrompt.getText().toString().trim();
                        int m;
                        try { m = Integer.parseInt(etMin.getText().toString().trim()); } catch (Exception e) { m = 60; }
                        if (n.isEmpty() || p.isEmpty()) { toast("名字和内容都要填"); return; }
                        Schedule.add(SettingsActivity.this, n, p, m);
                        build();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
