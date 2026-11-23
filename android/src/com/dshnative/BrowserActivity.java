package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 看得见的浏览器窗口。
 *
 * 之前那只 2×2 透明的 WebView 虽然能加载，但用户完全看不到 agent 在干什么，
 * 说是"浏览器"名不副实。这个 Activity 把页面真渲染出来：地址栏 + 刷新 + 关闭。
 *
 * agent 调 browser_open 时会把这一页顶到前台，你能看着它翻页。
 */
public class BrowserActivity extends Activity {

    static volatile BrowserActivity current;

    private WebView web;
    private TextView urlBar;

    /** 加载完成信号：Browser.open 靠它知道页面好了。 */
    static volatile CountDownLatch finished;
    static volatile String lastUrl = "";

    public static boolean alive() { return current != null; }

    public static WebView webview() { return current == null ? null : current.web; }

    /** 打开（或复用）浏览器窗口并加载 url。 */
    public static void openInWindow(Context c, String url) {
        Intent i = new Intent(c, BrowserActivity.class);
        i.putExtra("url", url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;

        // 局部面板：透明窗 + 底部一段，让对话界面还看得见
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                Color.parseColor("#99000000")));

        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#141414"));
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.60);
        android.widget.FrameLayout.LayoutParams panelLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        panelLp.gravity = Gravity.BOTTOM;
        panelLp.setMargins(dp(6), 0, dp(6), dp(6));
        shell.addView(root, panelLp);

        // 顶部工具条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(8), dp(10));
        bar.setBackgroundColor(Color.parseColor("#1C1C1E"));

        urlBar = new TextView(this);
        urlBar.setTextSize(12);
        urlBar.setTextColor(Color.parseColor("#B0B0B0"));
        urlBar.setSingleLine(true);
        urlBar.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(urlBar, ulp);

        TextView reload = chip("刷新");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (web != null) web.reload(); }
        });
        bar.addView(reload, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = chip("关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " DSHNative/1.0");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                lastUrl = u;
                urlBar.setText(u);
                CountDownLatch l = finished;
                if (l != null) l.countDown();
            }
            @Override public void onReceivedError(WebView v, int code, String desc, String failing) {
                urlBar.setText("加载失败：" + desc);
                CountDownLatch l = finished;
                if (l != null) l.countDown();
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(shell);

        String url = getIntent() == null ? "" : getIntent().getStringExtra("url");
        if (url != null && !url.isEmpty()) load(url);
    }

    void load(String url) {
        finished = new CountDownLatch(1);
        lastUrl = url;
        urlBar.setText(url);
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = intent == null ? null : intent.getStringExtra("url");
        if (url != null && !url.isEmpty()) load(url);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#7FB2FF"));
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
