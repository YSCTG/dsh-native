package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置浏览器：用系统 WebView 当无头浏览器。
 *
 * 手机上能等价于电脑版 Playwright 的部分是：
 * 打开页面、执行 JS、读正文、按选择器点击、拿 HTML。
 * 拿不到的是：多标签、请求拦截、真实 UA 指纹（有，但 WebView 自己会带）。
 *
 * WebView 必须在主线程创建和使用，所以这里的公开方法都自己切线程。
 */
public final class Browser {

    private static WebView view;
    private static volatile String lastUrl = "";

    private Browser() {}

    /** 在 Activity 里建一个不显示的 WebView。重复调用无副作用。 */
    public static void init(final Activity a) {
        if (view != null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    // 必须挂到视图树里：没有窗口的 WebView 很多机型不会真正开始加载，
                    // onPageFinished 永远不来，调用方就卡到超时。
                    WebView w = new WebView(a);
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setLoadsImagesAutomatically(true);
                    s.setUserAgentString(s.getUserAgentString() + " DSHNative/1.0");
                    w.setWebViewClient(new WebViewClient());
                    w.setAlpha(0.01f);   // 透明但可见：INVISIBLE 的 WebView 很多机型不加载
                    android.view.ViewGroup root = (android.view.ViewGroup)
                            a.getWindow().getDecorView();
                    root.addView(w, new android.view.ViewGroup.LayoutParams(2, 2));
                    view = w;
                } catch (Throwable ignored) {}
            }
        });
    }

    public static boolean ready() { return view != null || BrowserActivity.alive(); }

    /** 真正干活的 WebView：可见窗口优先，没有就退回那个隐藏的。 */
    private static WebView wv() {
        WebView w = BrowserActivity.webview();
        return w != null ? w : view;
    }

    /** 在可见窗口里打开（agent 调 browser_open 时用，用户能看见）。 */
    public static String openVisible(Context c, String url, int timeoutSec) {
        if (timeoutSec <= 0 || timeoutSec > 120) timeoutSec = 30;
        BrowserActivity.openInWindow(c, url);
        // 等窗口把页面加载完；已打开的情况下 openInWindow 会走 onNewIntent 重新加载
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            CountDownLatch l = BrowserActivity.finished;
            if (l != null) {
                try { if (l.await(500, TimeUnit.MILLISECONDS)) return null; }
                catch (InterruptedException ignored) {}
            } else {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            // 窗口还没起来时继续等
            if (BrowserActivity.alive() && url.equals(BrowserActivity.lastUrl)) {
                CountDownLatch f = BrowserActivity.finished;
                if (f != null && f.getCount() == 0) return null;
            }
        }
        return BrowserActivity.alive() ? null : "浏览器窗口没打开";
    }

    /** 打开页面并等加载完成。返回错误文本，成功为 null。 */
    public static String open(final String url, int timeoutSec) {
        if (wv() == null) return "浏览器还没打开（先调 browser_open）";
        if (timeoutSec <= 0 || timeoutSec > 120) timeoutSec = 30;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> err = new AtomicReference<>();
        final CountDownLatch inited = new CountDownLatch(1);

        view.post(new Runnable() {
            @Override public void run() {
                try {
                    view.setWebViewClient(new WebViewClient() {
                        @Override public void onPageFinished(WebView v, String u) {
                            lastUrl = u;
                            android.util.Log.i("DSHN", "page finished: " + u);
                            done.countDown();
                        }
                        @Override public void onPageCommitVisible(WebView v, String u) {
                            android.util.Log.i("DSHN", "page commit visible: " + u);
                        }
                        @Override public void onReceivedError(WebView v, int code, String desc, String failing) {
                            err.set("加载失败 " + code + " " + desc);
                            done.countDown();
                        }
                    });
                    view.loadUrl(url);
                } catch (Throwable t) {
                    err.set("打不开：" + t.getMessage());
                    done.countDown();
                } finally {
                    inited.countDown();
                }
            }
        });
        try {
            inited.await(5, TimeUnit.SECONDS);
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                android.util.Log.i("DSHN", "page 超时未完成: " + url);
            }
        } catch (InterruptedException ignored) {}
        return err.get();
    }

    /** 执行一段 JS，把结果当字符串拿回来。 */
    public static String js(final String code, int timeoutSec) {
        final WebView w = wv();
        if (w == null) return "错误：浏览器还没打开（先调 browser_open）";
        if (timeoutSec <= 0 || timeoutSec > 60) timeoutSec = 20;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>("");

        view.post(new Runnable() {
            @Override public void run() {
                try {
                    w.evaluateJavascript(code, new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            out.set(v);
                            done.countDown();
                        }
                    });
                } catch (Throwable t) {
                    out.set("错误：" + t.getMessage());
                    done.countDown();
                }
            }
        });
        try { done.await(timeoutSec, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        String s = out.get();
        // evaluateJavascript 返回的是 JSON 字面量：字符串带引号，null 是 "null"
        if (s == null || "null".equals(s)) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1)
                    .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return s;
    }

    /** 页面正文（可见文字）。 */
    public static String text(int timeoutSec) {
        String t = "";
        for (int i = 0; i < 10; i++) {
            t = js("(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()", timeoutSec);
            if (t != null && !t.trim().isEmpty()) return t;
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        return t == null ? "" : t;
    }

    /** 用选择器点击第一个匹配元素。 */
    public static String click(String selector) {
        String q = selector.replace("\\", "\\\\").replace("'", "\\'");
        return js("(function(){var e=document.querySelector('" + q + "');"
                + "if(!e)return 'NOT_FOUND';e.click();return 'CLICKED';})()", 20);
    }

    /**
     * 往输入框填内容。
     *
     * 直接 e.value='x' 对受控输入框（React、Bing 的搜索框）无效，必须走原型上的
     * 原生 setter 再派发 input/change，框架才会认。
     */
    public static String type(String selector, String value) {
        String q = esc(selector);
        String val = esc(value);
        String code = "(function(){"
                + "var e=document.querySelector('" + q + "');"
                + "if(!e)return 'NOT_FOUND';"
                + "e.focus();"
                + "if(e.isContentEditable){e.textContent='" + val + "';}"
                + "else{"
                + "var proto=(e.tagName==='TEXTAREA')?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(d&&d.set){d.set.call(e,'" + val + "');}else{e.value='" + val + "';}"
                + "}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'OK';})()";
        return js(code, 20);
    }

    /** 在元素上按回车并提交所在表单（搜索框通常靠这个）。 */
    public static String enter(String selector) {
        String q = esc(selector);
        String code = "(function(){"
                + "var e=document.querySelector('" + q + "');"
                + "if(!e)return 'NOT_FOUND';"
                + "e.focus();"
                + "['keydown','keypress','keyup'].forEach(function(t){"
                + "e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));"
                + "});"
                + "var f=e.form;if(f){f.submit();return 'SUBMITTED';}"
                + "return 'ENTERED';})()";
        return js(code, 20);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
    }

    public static String currentUrl() { return lastUrl; }

    /** 当前页面标题 + URL，方便判断加载到哪了。 */
    public static String where() {
        String t = js("document.title", 10);
        return "url: " + (lastUrl.isEmpty() ? "（未加载）" : lastUrl) + "\ntitle: " + t;
    }
}
