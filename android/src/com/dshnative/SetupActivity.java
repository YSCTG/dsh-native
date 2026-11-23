package com.dshnative;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 环境准备（首次启动的闸门）。
 *
 * 四项能力不齐，agent 就只能当聊天机器人用：
 *   无障碍 —— 读界面 / 点按    所有文件访问 —— 读写共享存储
 *   电池白名单 —— 锁屏不被回收   通知 —— 定时任务的结果看得见
 *
 * 默认要求全开。可以「稍后再说」，但主界面会常驻一条提醒，不是偷偷放行。
 */
public class SetupActivity extends Activity {

    private LinearLayout list;
    private TextView progress;
    private TextView primary;
    private static final int REQUIRED = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.bg_base));
        getWindow().setNavigationBarColor(getColor(R.color.bg_base));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg_base));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(22), dp(26), dp(22), dp(10));

        TextView title = new TextView(this);
        title.setText("环境准备");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(getColor(R.color.label_primary));
        head.addView(title);

        progress = new TextView(this);
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        progress.setTextColor(getColor(R.color.label_tertiary));
        progress.setPadding(0, dp(6), 0, 0);
        head.addView(progress);

        TextView intro = new TextView(this);
        intro.setText("这四项不开，DSH Native 只能当聊天窗口用——碰不到文件、点不了界面、锁屏就被回收。\n"
                + "每一项都只授予本 App，随时可以在这里或系统设置里收回。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setTextColor(getColor(R.color.label_secondary));
        intro.setLineSpacing(dp(4), 1f);
        intro.setPadding(0, dp(12), 0, 0);
        head.addView(intro);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(10));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(20), dp(8), dp(20), dp(18));

        primary = new TextView(this);
        primary.setGravity(Gravity.CENTER);
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        primary.setPadding(dp(16), dp(14), dp(16), dp(14));
        primary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (missing() == 0) { finishSetup(); return; }
                openFirstMissing();
            }
        });
        bottom.addView(primary);

        TextView later = new TextView(this);
        later.setText("稍后再说（主界面会一直提醒）");
        later.setGravity(Gravity.CENTER);
        later.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        later.setTextColor(getColor(R.color.label_tertiary));
        later.setPadding(0, dp(12), 0, 0);
        later.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishSetup(); }
        });
        bottom.addView(later);
        root.addView(bottom);

        setContentView(root);
        refresh();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** 看"授权"而不是"此刻连没连上"：服务在进程重启后要过一会儿才 onServiceConnected，
     *  拿 isReady() 当门槛会误判成"没开"，把人挡在外面。 */
    private boolean a11y() { return Compat.a11yInSettings(this) || UiAgentService.isReady(); }
    private boolean files() { return Compat.allFilesGranted(this); }
    private boolean battery() { return Compat.batteryIgnored(this); }
    private boolean notif() { return Compat.notificationsOn(this); }

    private int missing() {
        int n = 0;
        if (!a11y()) n++;
        if (!files()) n++;
        if (!battery()) n++;
        if (!notif()) n++;
        if (!Screen.ready()) n++;
        return n;
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Screen.REQ_CONSENT) {
            String err = Screen.start(this, res, data);
            toast(err == null ? "截屏已授权" : err);
            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        int left = missing();
        progress.setText((REQUIRED - left) + " / " + REQUIRED + " 已完成");

        if (left == 0) {
            primary.setText("开始使用");
            primary.setBackgroundResource(R.drawable.bg_send);
            primary.setTextColor(getColor(R.color.brand_fg));
            primary.setAlpha(1f);
        } else {
            primary.setText("还差 " + left + " 项 · 点这里去开");
            primary.setBackgroundResource(R.drawable.bg_ghost);
            primary.setTextColor(getColor(R.color.label_tertiary));
            primary.setAlpha(1f);
        }

        list.removeAllViews();
        item("无障碍服务", "让 DSH Native 读取界面文字、按文字点击。",
                "这是无 root 手机上操作其他应用的唯一内置路径。\n"
                        + "华为会要求「按住音量上+下键 2 秒」确认，那是系统防诈骗弹窗，不是我们加的。",
                a11y(), new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openAccessibility(SetupActivity.this); }
                }, "去开启");
        item("所有文件访问", "读写共享存储：相册、下载、文档。",
                "不开的话只能读写 App 自己的私有目录。",
                files(), new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openAllFiles(SetupActivity.this); }
                }, "去开启");
        item("电池优化白名单", "锁屏后任务不被系统回收。",
                "定时任务和长任务要靠它活下来。",
                battery(), new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openBattery(SetupActivity.this); }
                }, "去设置");
        item("截屏授权", "让 agent 能看见屏幕（截图后作为图片发给模型）。",
                "一次性授权；App 重启后需要再点一次（Android 的规矩）。",
                Screen.ready(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        android.content.Intent i = Screen.consentIntent(SetupActivity.this);
                        if (i == null) { toast("这台设备不支持截屏授权"); return; }
                        try { startActivityForResult(i, Screen.REQ_CONSENT); }
                        catch (Exception e) { toast("打不开授权页：" + e.getMessage()); }
                    }
                }, Screen.ready() ? "已授权" : "去授权");
        item("通知权限", "定时任务跑完的结果发到通知栏。",
                "不开的话任务照跑，但你看不到结果。",
                notif(), new View.OnClickListener() {
                    @Override public void onClick(View v) { Compat.openNotifications(SetupActivity.this); }
                }, "去开启");
    }

    private void item(String title, String sub, String why, boolean ok,
                      View.OnClickListener onGo, String goText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(10);
        list.addView(card, lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText((ok ? "✓  " : "✗  ") + title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(getColor(ok ? R.color.label_primary : R.color.danger));
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub + "\n" + why);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        s.setTextColor(getColor(R.color.label_tertiary));
        s.setLineSpacing(dp(3), 1f);
        s.setPadding(0, dp(4), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!ok) {
            TextView go = new TextView(this);
            go.setText(goText);
            go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            go.setTextColor(getColor(R.color.accent));
            go.setPadding(dp(10), dp(10), 0, dp(10));
            go.setOnClickListener(onGo);
            row.addView(go);
        }
        card.addView(row);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 一次只引导一项：开完回来 onResume 会刷新，再点就是下一项。 */
    private void openFirstMissing() {
        if (!a11y()) { Compat.openAccessibility(this); return; }
        if (!files()) { Compat.openAllFiles(this); return; }
        if (!battery()) { Compat.openBattery(this); return; }
        if (!notif()) { Compat.openNotifications(this); return; }
        if (!Screen.ready()) {
            android.content.Intent i = Screen.consentIntent(this);
            if (i == null) { toast("这台设备不支持截屏授权"); return; }
            try { startActivityForResult(i, Screen.REQ_CONSENT); }
            catch (Exception e) { toast("打不开授权页：" + e.getMessage()); }
        }
    }

    private void finishSetup() {
        SharedPreferences p = getSharedPreferences("dshnative", MODE_PRIVATE);
        p.edit().putBoolean("onboarded", true).apply();
        startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
    }
}
