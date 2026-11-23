package com.dshnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一屏对话界面，视觉对齐桌面版 DSH：
 * 纯白/近黑底、near-black 文字、4–10% 细边、蓝色只用于链接与状态。
 * 正文无衬线 15sp，代码等宽 12sp。
 *
 * 结构：顶栏（模型/tier）· 账本行 · 对话流 · 输入区 · 底部开关。
 */
public class MainActivity extends Activity implements Agent.Listener {

    private static final String PREFS = "dshnative";
    private static final String DEFAULT_BASE = "https://api.deepseek.com";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private ScrollView scroller;
    private LinearLayout transcript;
    private EditText input;
    private TextView sendBtn;

    private TextView headerTitle;
    private TextView headerMeta;
    private View drawerScrim;
    private LinearLayout drawerPanel;
    private LinearLayout drawerBody;
    private int drawerWidth;
    private TextView envBanner;
    private TextView chipTier;
    private TextView chipModel;
    private TextView chipA11y;
    private TextView chipSh;
    private TextView ledger;
    private TextView chipCtx;
    private TextView chipWs;
    private TextView chipHit;
    /** 待办摘要（模型更新清单时变化）。 */
    private String todoSummary = "";

    /** 生成进度提示（只在跑的时候可见）。 */
    private TextView genStatus;
    private long genStart;
    private android.app.AlertDialog currentDialog;
    private static final int REQ_PICK = 1001;
    private final java.util.List<String> pendingImages = new java.util.ArrayList<>();
    private TextView chipNew;

    private Agent agent;
    private Mode mode;
    private Session session;
    private boolean root;
    private volatile boolean running;
    private volatile boolean abortRequested;
    private boolean assistantClosed;
    private final java.util.HashSet<String> fileCardsShown = new java.util.HashSet<>();   // 上一段文字后面已经跟了工具卡
    private String migratedNote;   // 用户按了停止；构造 Agent 期间也要认账
    private boolean dark;
    private volatile String balance;      // 账户余额，形如 ¥61.53
    private volatile long balanceAge = 0; // 上次拉取时间，60 秒内不重复请求

    // 流式渲染
    private TextView assistantView;
    private final StringBuilder assistantBuf = new StringBuilder();
    private Runnable pendingRender;
    private LinearLayout thinkingBlock;
    /** 思考计时：每 200ms 刷一次，桌面版也是跳着涨的。 */
    private final Runnable thinkTick = new Runnable() {
        @Override public void run() {
            if (thinkingBlock == null) return;
            View h = thinkingBlock.findViewWithTag("head");
            if (h instanceof TextView) {
                double s = (System.currentTimeMillis() - thinkingStart) / 1000.0;
                ((TextView) h).setText("正在分析… " + thinkingChars + " 字 · "
                        + String.format("%.1fs", s));
            }
            ui.postDelayed(this, 200);
        }
    };
    private boolean thinkingOpen;
    private int thinkingChars;
    private long thinkingStart;
    private final StringBuilder thinkingBuf = new StringBuilder();
    private TextView lastToolMeta;   // 最近一个工具卡的详情行
    private TextView lastToolName;   // 最近一个工具卡的名字行（收尾时把 ▸ 换成 ✓）
    private String lastToolLabel;
    private long toolStart;
    private String lastToolBase;

    /** 工具在跑时每 500ms 把已用时间写进卡片：长任务看得见，就不会被当成卡死。 */
    private final Runnable toolTick = new Runnable() {
        @Override public void run() {
            if (lastToolMeta == null || lastToolBase == null) return;
            double s = (System.currentTimeMillis() - toolStart) / 1000.0;
            lastToolMeta.setText(lastToolBase + "   " + String.format("%.1fs…", s));
            ui.postDelayed(this, 500);
        }
    };

    private String model;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 有些网络/模拟器只有 IPv6 出网会直接连不上；所有 HTTP 都走 Java 栈，这里统一压到 IPv4。
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Exception ignored) {}
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 只用 deepseek-flash：旧版本可能存了 deepseek-v4-pro，这里强制回落
        model = "deepseek-flash";
        if (!"deepseek-flash".equals(prefs.getString("model", ""))) {
            prefs.edit().putString("model", model).apply();
        }
        // 换包名后第一次启动：把上一版导出的东西搬回来（必须早于任何 prefs 读取）
        Prefs.migrate(this);
        Browser.init(this);
        android.util.Log.i("DSHN", "新 prefs 键数=" + Prefs.get(this).getAll().size()
                + " onboarded=" + Prefs.get(this).getBoolean("onboarded", false)
                + " file=" + new File(getFilesDir().getParentFile(), "shared_prefs").getAbsolutePath());
        migratedNote = Migrate.importIfPresent(this);
        Secret.migrate(prefs);
        Schedule.arm(this);
        dark = isNight();
        applySystemBars();
        if (!prefs.getBoolean("onboarded", false)) {
            startActivity(new android.content.Intent(this, SetupActivity.class));
            finish();
            return;
        }
        // 冷启动底色：主题是 Material.Light，窗口先白一下再被内容盖住，
// 看起来就像"闪了一下旧界面"。先把窗口刷成页面底色。
        getWindow().setBackgroundDrawableResource(R.color.bg_base);
        setContentView(withDrawer(buildUi()));
        initSession();
        refreshBalance();
        if (migratedNote != null) note(migratedNote);
        autoResumeIfNeeded();

        new Thread(new Runnable() {
            @Override public void run() {
                final boolean r = Shell.hasRoot();
                ui.post(new Runnable() {
                    @Override public void run() { setRoot(r); }
                });
            }
        }).start();

        if (apiKey().isEmpty()) {
            askKey();
        } else {
            refreshMeta();
        }

        if (getIntent() != null && getIntent().getBooleanExtra("edit_key", false)) {
            askKey();
        }
        String p = getIntent() == null ? null : getIntent().getStringExtra("prompt");
        if (p != null && !p.trim().isEmpty()) {
            input.setText(p.trim());
            if (!apiKey().isEmpty()) onSend();
        }
    }

    private boolean isNight() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBars() {
        int bg = getColor(R.color.bg_base);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------- 布局 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg_base));

        // 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(6));

        TextView menuBtn = new TextView(this);
        menuBtn.setText("☰");
        menuBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menuBtn.setTextColor(getColor(R.color.label_secondary));
        menuBtn.setPadding(0, 0, dp(14), 0);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        });
        header.addView(menuBtn);

        headerTitle = new TextView(this);
        headerTitle.setText("DSH Native");
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerTitle.setLetterSpacing(0.08f);
        headerTitle.setTextColor(getColor(R.color.label_primary));
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 新会话挪到右上角：原来挤在底栏最右、字号最小，最难找
        TextView newBtn = new TextView(this);
        newBtn.setText("＋");
        newBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        newBtn.setTextColor(getColor(R.color.label_primary));
        newBtn.setGravity(Gravity.CENTER);
        newBtn.setPadding(dp(16), dp(2), dp(2), dp(8));
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onFresh(); }
        });
        header.addView(newBtn);

        // 设过 key 之后没有入口改：长按表头重新打开设置
        header.setLongClickable(true);
        header.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                askKey();
                return true;
            }
        });
        root.addView(header);

        // 第二行：模式 · 模型（点一下换模型）            状态
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(dp(20), 0, dp(20), dp(10));

        headerMeta = new TextView(this);
        headerMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        headerMeta.setTextColor(getColor(R.color.label_secondary));
        headerMeta.setSingleLine(true);
        headerMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerMeta.setBackgroundResource(R.drawable.bg_chip);
        headerMeta.setPadding(dp(11), dp(6), dp(11), dp(6));
        headerMeta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showQuickSettings(); }
        });
        metaRow.addView(headerMeta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右：余额胶囊。只显示余额——每轮的 token/花费不再占界面。
        ledger = new TextView(this);
        ledger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ledger.setTextColor(getColor(R.color.label_secondary));
        ledger.setBackgroundResource(R.drawable.bg_chip);
        ledger.setPadding(dp(11), dp(6), dp(11), dp(6));
        ledger.setSingleLine(true);
        ledger.setGravity(Gravity.CENTER);
        ledger.setText("余额 –");
        ledger.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Secret.hasKey(prefs)) { askKey(); return; }
                balanceAge = 0;
                refreshBalance();
                toast("正在查余额…");
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        metaRow.addView(ledger, blp);

        root.addView(metaRow);

        View div1 = new View(this);
        div1.setBackgroundColor(getColor(R.color.border_l1));
        root.addView(div1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 对话流
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        transcript = new LinearLayout(this);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(dp(20), dp(14), dp(20), dp(20));
        transcript.addView(buildEmptyState());
        scroller.addView(transcript, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View div2 = new View(this);
        div2.setBackgroundColor(getColor(R.color.border_l1));
        root.addView(div2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 环境提醒（缺东西时常驻，但不吵人：灰字 + 低对比底）
        envBanner = new TextView(this);
        envBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        envBanner.setTextColor(getColor(R.color.label_caption));
        envBanner.setBackgroundColor(getColor(R.color.bg_subtle));
        envBanner.setPadding(dp(20), dp(9), dp(20), dp(9));
        envBanner.setVisibility(View.GONE);
        envBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        root.addView(envBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 生成进度：长回复时让用户知道"还在写"，而不是盯着一个不动的界面
        genStatus = new TextView(this);
        genStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        genStatus.setTextColor(getColor(R.color.label_caption));
        genStatus.setPadding(dp(22), dp(2), dp(22), dp(2));
        genStatus.setVisibility(View.GONE);
        genStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTodoSheet(); }
        });
        root.addView(genStatus);

        // 输入区
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout inputWrap = new LinearLayout(this);
        inputWrap.setOrientation(LinearLayout.VERTICAL);
        inputWrap.setBackgroundResource(R.drawable.bg_input);
        inputWrap.setPadding(dp(12), dp(9), dp(12), dp(9));

        input = new EditText(this);
        input.setBackground(null);
         input.setHint("说点什么…");
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { refreshSendIcon(); }
        });
        input.setHintTextColor(getColor(R.color.label_caption));
        input.setTextColor(getColor(R.color.label_primary));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setPadding(0, 0, 0, 0);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE);
        inputWrap.addView(input);
        LinearLayout.LayoutParams iw = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        iw.rightMargin = dp(10);
        composer.addView(inputWrap, iw);

        sendBtn = new TextView(this);
        sendBtn.setText("↑");
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(getColor(R.color.brand_fg));
        sendBtn.setBackgroundResource(R.drawable.bg_send);
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(dp(44), dp(44));
        composer.addView(sendBtn, sb);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (running) onAbort(); else onSend();
            }
        });

        root.addView(composer);

        // 底部开关（低对比，桌面版那种"不抢戏"的工具条）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(4), dp(14), dp(8));

        chipCtx = chip("ctx —", null);
        bar.addView(chipCtx, chipLp());

        // 工作区选项卡：完全权限 / 自建工作区，会话历史按它分开
        chipHit = chip("命中 —", null);
        chipHit.setBackground(null);
        bar.addView(chipHit, chipLp());

        chipWs = chip(Workspaces.current(this).name + " ▾", new View.OnClickListener() {
            @Override public void onClick(View v) { showWorkspaceSheet(); }
        });
        chipWs.setTag("ws");
        bar.addView(chipWs, chipLp());

        View s2 = new View(this);
        bar.addView(s2, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView attach = chip("📎", new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        bar.addView(attach, chipLp());

        TextView gear = chip("⚙", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        bar.addView(gear, chipLp());

        root.addView(bar);
        return root;
    }



    private TextView chip(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(getColor(R.color.label_tertiary));
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(dp(11), dp(6), dp(11), dp(6));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        return lp;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------- 消息渲染 ----------------

    /** 空状态：对齐桌面版的欢迎页——字标 + 一句话 + 几个可以直接点的起手式。 */
    private View buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(6), dp(72), dp(6), dp(20));
        box.setTag("empty");

        TextView w = new TextView(this);
        w.setText("DSH Native");
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        w.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        w.setLetterSpacing(0.08f);
        w.setTextColor(getColor(R.color.label_primary));
        w.setPadding(0, 0, 0, dp(4));
        box.addView(w);

        TextView sub = new TextView(this);
        sub.setText("在手机上做事的 agent");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(getColor(R.color.label_caption));
        sub.setPadding(0, 0, 0, dp(26));
        box.addView(sub);

        String[] hints = {
                "看看当前屏幕上有什么",
                "在工作区写个 hello.py",
                "用 net_fetch 抓一下 example.com",
        };
        for (final String h : hints) {
            TextView t = new TextView(this);
            t.setText(h);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTextColor(getColor(R.color.label_secondary));
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setPadding(dp(18), dp(15), dp(18), dp(15));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    input.setText(h);
                    input.setSelection(h.length());
                    input.requestFocus();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(7);
            box.addView(t, lp);
        }
        return box;
    }

    private void hideEmptyState() {
        if (transcript == null) return;
        for (int i = transcript.getChildCount() - 1; i >= 0; i--) {
            if ("empty".equals(transcript.getChildAt(i).getTag())) {
                transcript.removeViewAt(i);
            }
        }
    }

    private void addView(View v) {
        hideEmptyState();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        transcript.addView(v, lp);
        scrollDown();
    }

    private void scrollDown() {
        scroller.post(new Runnable() {
            @Override public void run() { scroller.fullScroll(View.FOCUS_DOWN); }
        });
    }

    /** 用户消息：右对齐气泡，和桌面版一致（浅蓝底，不是自己的头像）。 */
    private void addUserBubble(String text) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.86f) - dp(40);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bubble.setTextColor(getColor(R.color.label_primary));
        bubble.setLineSpacing(dp(5), 1f);
        bubble.setBackgroundResource(R.drawable.bg_bubble);
        bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
        bubble.setTextIsSelectable(true);
        // 用 setMaxWidth 而不是事后改 LayoutParams：后者不会触发重新测量，文字会被裁掉
        bubble.setMaxWidth(maxW);

        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(row);
    }

    /** 系统提示：居中的小字，不抢视觉。 */
    private void addNotice(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(getColor(R.color.label_caption));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(2), 0, dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        transcript.addView(t, lp);
    }

    /** 工具调用：一行卡片，等宽名字 + 灰色细节。 */
    /** 桌面版把每一步说成人话（运行命令 / 读文件 …），这里照做。 */
    /** 动作名成对：进行时说"正在X"，完成后说"X了"（电脑版就是这样）。 */
    private static String verbOf(String tool, boolean done) {
        String a, b;
        if (tool == null) { a = "正在调用工具"; b = "调用了工具"; }
        else if ("sh".equals(tool)) { a = "正在运行命令"; b = "运行了命令"; }
        else if ("python".equals(tool)) { a = "正在跑 Python"; b = "跑了 Python"; }
        else if ("fs_read".equals(tool) || "fs_list".equals(tool)
                || "fs_glob".equals(tool) || "fs_grep".equals(tool)) { a = "正在读取"; b = "读取了"; }
        else if ("fs_write".equals(tool) || "fs_edit".equals(tool)) { a = "正在写入"; b = "写入了"; }
        else if ("web_search".equals(tool)) { a = "正在搜索网页"; b = "搜索了网页"; }
        else if ("net_fetch".equals(tool)) { a = "正在抓取网页"; b = "抓取了网页"; }
        else if ("screenshot".equals(tool)) { a = "正在读取图片"; b = "已读取图片"; }
        else if ("ui_dump".equals(tool)) { a = "正在看界面"; b = "查看了界面"; }
        else if ("ui_tap".equals(tool)) { a = "正在点按"; b = "点击了"; }
        else if ("subagent".equals(tool)) { a = "正在派子智能体"; b = "派出了子智能体"; }
        else if ("loop".equals(tool)) { a = "正在循环"; b = "循环结束"; }
        else if (tool.startsWith("team_")) { a = "正在调度团队"; b = "调度了团队"; }
        else if ("skill".equals(tool)) { a = "正在读技能"; b = "读取了技能"; }
        else if ("render".equals(tool)) { a = "正在渲染"; b = "渲染了"; }
        else if ("mcp".equals(tool)) { a = "正在调用 MCP"; b = "调用了 MCP"; }
        else if (tool.startsWith("app_")) { a = "正在操作应用"; b = "操作了应用"; }
        else if (tool.startsWith("clip_")) { a = "正在读写剪贴板"; b = "读写了剪贴板"; }
        else if ("notify".equals(tool)) { a = "正在发通知"; b = "发了通知"; }
        else if (tool.startsWith("dev_")) { a = "正在诊断"; b = "做了诊断"; }
        else { a = "正在调用工具"; b = "调用了工具"; }
        return done ? b : a;
    }
    /** 还没收到完成回执的工具芯片。一次请求可能并行发多个工具，所以要按名字配对。 */
    private static final class PendingTool {
        final String name;
        final TextView view;
        final TextView meta;
        final String base;
        final String doneLabel;

        PendingTool(String name, TextView view, TextView meta, String base, String doneLabel) {
            this.name = name;
            this.view = view;
            this.meta = meta;
            this.base = base;
            this.doneLabel = doneLabel;
        }
    }

    private final java.util.List<PendingTool> pendingTools = new java.util.ArrayList<>();

    private void addToolChip(String name, String detail) {
        addToolChip(name, detail, false);
    }

    /**
     * 工具芯片。
     *
     * done=true 用于历史重放：那些调用早就结束了，不该显示"正在…"。
     * 之前重放一律画成进行时，所以老会话里满屏"正在运行命令"。
     */
    private void addToolChip(String name, String detail, boolean done) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(2), dp(5), dp(6), dp(5));

        // 左侧细竖线：工具调用是旁注，不该用一整块底色和正文抢
        View rule = new View(this);
        rule.setBackgroundColor(getColor(done ? R.color.border_l1 : R.color.border_l2));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(2), dp(15));
        rlp.rightMargin = dp(10);
        card.addView(rule, rlp);

        TextView nm = new TextView(this);
        String doneLabel = verbOf(name, true) + " · " + name;
        nm.setText((done ? "✓ " : "▸ ") + verbOf(name, done) + " · " + name);
        nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        nm.setTypeface(Typeface.MONOSPACE);
        nm.setTextColor(getColor(done ? R.color.label_secondary : R.color.label_primary));
        card.addView(nm);

        TextView dt = new TextView(this);
        dt.setText(detail == null ? "" : detail);
        dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dt.setTypeface(Typeface.MONOSPACE);
        dt.setTextColor(getColor(R.color.label_tertiary));
        dt.setSingleLine(true);
        dt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(10);
        card.addView(dt, dlp);

        if (!done) {
            lastToolName = nm;
            lastToolMeta = dt;
            lastToolBase = detail == null ? "" : detail;
            lastToolLabel = doneLabel;
            pendingTools.add(new PendingTool(name, nm, dt, lastToolBase, doneLabel));
        }

        addView(card);
    }

    private void addError(String msg) {
        TextView t = new TextView(this);
        t.setText("错误 · " + msg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(getColor(R.color.danger));
        t.setBackgroundResource(R.drawable.bg_card);
        t.setPadding(dp(11), dp(8), dp(11), dp(8));
        t.setTextIsSelectable(true);
        addView(t);
    }

    /** 思考块：默认展开，正文一来就收起成一行（桌面版是这个行为）。 */
    /**
     * 思考块：一行摘要 + 可展开原文。
     *
     * 流式期间**不刷正文**（折叠着刷是纯浪费），只在收口时一次性写进去；
     * 原文在缓冲里保留，展开时直接显示。超过 2 万字只留尾部。
     */
    private void ensureThinkingBlock() {
        if (thinkingBlock != null) return;
        thinkingBlock = new LinearLayout(this);
        thinkingBlock.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(this);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        head.setTextColor(getColor(R.color.label_caption));
        head.setText("正在分析…");
        head.setPadding(dp(2), dp(10), dp(2), dp(10));   // 加大可点高度
        head.setTag("head");

        View rule = new View(this);
        rule.setBackgroundColor(getColor(R.color.border_l1));

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTextColor(getColor(R.color.label_tertiary));
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(dp(2), dp(8), dp(2), dp(6));
        body.setTag("body");
        body.setVisibility(View.GONE);

        thinkingBlock.addView(head);
        thinkingBlock.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        thinkingBlock.addView(body);

        // 直接把监听挂在 head 上：用户点的就是这一行字。
        // 之前只挂在容器上，而 body 是 setTextIsSelectable 的（会吃掉触摸），
        // 容器经常收不到点击。
        final TextView fHead = head;
        final TextView fBody = body;
        View.OnClickListener toggle = new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = fBody.getVisibility() != View.VISIBLE;
                fBody.setVisibility(show ? View.VISIBLE : View.GONE);
                thinkingOpen = show;
                String base = fHead.getText().toString()
                        .replace(" 展开 ▾", "").replace(" 收起 ▴", "");
                fHead.setText(base + (show ? " 收起 ▴" : " 展开 ▾"));
                android.util.Log.i("DSHN", "think toggle show=" + show
                        + " bodyLen=" + fBody.getText().length());
                if (show) {
                    // 关键：展开的原文在这一块里，不能滚到底（滚到底就把它挤出屏幕，
                    // 看起来就像"点了没反应"）。让 body 自己请求进入可视区。
                    fBody.post(new Runnable() {
                        @Override public void run() {
                            fBody.requestRectangleOnScreen(new android.graphics.Rect(
                                    0, 0, Math.max(1, fBody.getWidth()),
                                    Math.max(1, fBody.getHeight())), true);
                        }
                    });
                }
            }
        };
        head.setOnClickListener(toggle);
        thinkingBlock.setOnClickListener(toggle);

        addView(thinkingBlock);
        thinkingOpen = false;
        thinkingStart = System.currentTimeMillis();
        ui.removeCallbacks(thinkTick);
        ui.postDelayed(thinkTick, 200);
    }

    private void finishThinking() {
        if (thinkingBlock == null) return;
        TextView head = (TextView) thinkingBlock.findViewWithTag("head");
        TextView body = (TextView) thinkingBlock.findViewWithTag("body");
        ui.removeCallbacks(thinkTick);
        double secs = thinkingStart == 0 ? 0 : (System.currentTimeMillis() - thinkingStart) / 1000.0;
        String all = thinkingBuf.toString();
        boolean has = !all.trim().isEmpty();
        if (body != null && has) {
            String show = all;
            if (all.length() > 20000) {
                show = "…（前面省略 " + (all.length() - 20000) + " 字）\n" + all.substring(all.length() - 20000);
            }
            body.setText(show);
        }
        if (head != null) {
            head.setText(String.format("已完成分析 · %d 字 · 用时 %.1fs%s",
                    thinkingChars, secs, has ? (thinkingOpen ? " 收起 ▴" : " 展开 ▾") : ""));
        }
        thinkingBlock = null;
        thinkingOpen = false;
        thinkingChars = 0;
        thinkingBuf.setLength(0);
    }

    private void ensureAssistantView() {
        if (assistantView != null) return;
        finishThinking();
        assistantView = new TextView(this);
        assistantView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        assistantView.setTextColor(getColor(R.color.label_primary));
        assistantView.setLineSpacing(dp(5), 1f);
        assistantView.setTextIsSelectable(true);

        // 气泡 + 右下角「复制」装进同一个容器
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(assistantView);

        TextView copy = new TextView(this);
        copy.setText("⧉ 复制");
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        copy.setTextColor(getColor(R.color.label_caption));
        copy.setPadding(dp(8), dp(2), dp(2), dp(4));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyToClipboard(assistantBuf.toString()); }
        });
        LinearLayout copyRow = new LinearLayout(this);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(android.view.Gravity.END);
        copyRow.addView(copy);
        box.addView(copyRow);

        addView(box);
    }

    /** 把文字放进剪贴板。 */
    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH Native",
                    text == null ? "" : text));
            toast("已复制");
        } catch (Throwable ignored) {}
    }

    /**
     * 回复里提到的文件 → 变成可点的卡片。
     *
     * 模型写完文件通常会把路径写在正文里（“已保存到 /sdcard/DSHNative/x.md”）。
     * 以前那就是一行字；现在附一张卡片，点一下直接打开（图片用系统看图，文本用系统查看器）。
     */
    /**
     * 回复里提到的文件 → 一张像样的卡片（标题行 + 内嵌预览）。
     *
     * 以前只是个浅色小字，看不出来是能点的东西。现在做成框：
     * 上行是图标、文件名、大小和"展开"；点开在卡片里直接看（图片渲染、文本读前几 KB）。
     */
    private void appendFileChips(String text) {
        if (text == null || text.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("((?:/sdcard|/storage/emulated/0)/[^\\s，。；:：)\\]】\"'`<>|*?]+)").matcher(text);
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String p = m.group(1).trim();
            if (p.length() > 1 && new File(p).isFile()) paths.add(p);
        }
        if (paths.isEmpty()) return;
        for (String p : paths) {
            if (fileCardsShown.contains(p)) continue;
            File f = new File(p);
            if (!Attach.worth(f)) continue;      // 正文里提到的脚本/中间文件也不挂
            fileCardsShown.add(p);
            addView(buildFileCard(f));
        }
        scrollDown();
    }

    /**
     * 判断一个文件值得不值得挂卡片。
     *
     * 以前把一个回合里动过的文件全挂上，于是 build_ppt.py、probe_min.docx、
     * pv_1.html 这些中间产物也出现在回答下面，真正要交付的东西反而被淹没。
     * 规则：只留"成品类型"，并且排除明显是工作文件的命名。
     */
    private View buildFileCard(final File f) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_input);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0x00000000);

        String lower = f.getName().toLowerCase();
        boolean image = lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp");
        boolean pdf = lower.endsWith(".pdf");
        boolean audio = lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
                || lower.endsWith(".ogg") || lower.endsWith(".aac") || lower.endsWith(".flac");
        boolean video = lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".3gp")
                || lower.endsWith(".mkv");
        boolean html = lower.endsWith(".html") || lower.endsWith(".htm");
        String sz = f.length() < 1024 ? (f.length() + " B")
                : String.format("%.1f KB", f.length() / 1024.0);

        TextView icon = new TextView(this);
        icon.setPadding(0, 0, dp(4), 0);
        icon.setText(image ? "🖼" : audio ? "🎵" : video ? "🎬" : html ? "🌐" : "📄");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(icon);

        TextView name = new TextView(this);
        name.setText("  " + f.getName());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(getColor(R.color.label_primary));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(name, nlp);

        final TextView toggle = new TextView(this);
        toggle.setText(sz + "  ▾");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // 夜间主题里 brand_fg 是近黑 #0F1115，压在深色卡片上等于看不见
        toggle.setTextColor(getColor(R.color.label_secondary));
        head.addView(toggle);

        card.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        card.addView(body, blp);

        final boolean isImage = image;
        final boolean isPdf = pdf;
        final boolean isAudio = audio;
        final boolean isVideo = video;
        final boolean isHtml = html;
        final String sizeText = sz;
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openExternally(f);      // 点文件名 → 交给系统应用打开
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean open = body.getVisibility() != View.VISIBLE;
                if (open && body.getChildCount() == 0) fillFileBody(body, f, isImage, isPdf, isAudio, isVideo, isHtml);
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(sizeText + (open ? "  ▴" : "  ▾"));
            }
        });
        return card;
    }

    /**
     * 用系统应用打开文件。
     *
     * 走自己实现的 FileProviderX 生成 content:// —— 直接把 file:// 给外部应用
     * 从 Android 7 起就会抛 FileUriExposedException，之前"点了没反应"就是这个坑。
     */
    private void openExternally(File f) {
        try {
            android.net.Uri uri = FileProviderX.uriFor(this, f);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, Mimes.of(f.getName()));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(i, "打开 " + f.getName()));
        } catch (android.content.ActivityNotFoundException e) {
            toast("这台手机没有能打开 " + Mimes.of(f.getName()) + " 的应用");
        } catch (Throwable e) {
            toast("打不开：" + e.getMessage());
        }
    }

    /** 内嵌预览：图片渲染出来，文本读前几 KB，二进制给提示。 */
    private void fillFileBody(LinearLayout body, File f, boolean image, boolean pdf,
                             boolean audio, boolean video, boolean html) {
        try {
            if (html) {
                // HTML 直接在内嵌 WebView 里渲染，带 JS
                android.webkit.WebView wv = new android.webkit.WebView(this);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.setBackgroundColor(0xFFFFFFFF);
                wv.loadUrl("file://" + f.getAbsolutePath());
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
                body.addView(wv, wlp);
                TextView tip = new TextView(this);
                tip.setText("（内嵌渲染 · 点标题可用浏览器打开）");
                tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tip.setTextColor(getColor(R.color.label_tertiary));
                body.addView(tip);
                return;
            }
            if (audio) {
                final android.media.MediaPlayer mp = new android.media.MediaPlayer();
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
                int dur = mp.getDuration() / 1000;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                final TextView play = new TextView(this);
                play.setText("▶  播放");
                play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                play.setTextColor(getColor(R.color.brand_fg));
                play.setBackgroundResource(R.drawable.bg_chip);
                play.setPadding(dp(14), dp(8), dp(14), dp(8));
                final android.media.MediaPlayer player = mp;
                play.setOnClickListener(new View.OnClickListener() {
                    boolean on;
                    @Override public void onClick(View v) {
                        try {
                            if (player.isPlaying()) { player.pause(); play.setText("▶  播放"); }
                            else { player.start(); play.setText("⏸  暂停"); }
                        } catch (Throwable ignored) {}
                    }
                });
                row.addView(play);
                TextView info = new TextView(this);
                info.setText("   时长 " + (dur / 60) + ":" + String.format("%02d", dur % 60)
                        + " · " + (f.length() / 1024) + " KB");
                info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                info.setTextColor(getColor(R.color.label_tertiary));
                row.addView(info);
                body.addView(row);
                return;
            }
            if (video) {
                android.widget.VideoView vv = new android.widget.VideoView(this);
                vv.setVideoPath(f.getAbsolutePath());
                android.widget.MediaController mc = new android.widget.MediaController(this);
                mc.setAnchorView(vv);
                vv.setMediaController(mc);
                body.addView(vv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
                vv.seekTo(1);
                TextView tip = new TextView(this);
                tip.setText("（点播放开始；控件在画面下方）");
                tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tip.setTextColor(getColor(R.color.label_tertiary));
                body.addView(tip);
                return;
            }
            if (pdf) {
                // PDF 首页渲染成图：Android 自带 PdfRenderer，不用第三方
                try {
                    android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor
                            .open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                    android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(pfd);
                    if (pr.getPageCount() > 0) {
                        android.graphics.pdf.PdfRenderer.Page pg = pr.openPage(0);
                        int w = Math.min(pg.getWidth() * 2, 900);
                        int h = (int) ((float) pg.getHeight() / pg.getWidth() * w);
                        android.graphics.Bitmap bm = android.graphics.Bitmap
                                .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                        bm.eraseColor(android.graphics.Color.WHITE);
                        pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        pr.close();
                        pfd.close();
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setImageBitmap(bm);
                        iv.setAdjustViewBounds(true);
                        body.addView(iv);
                        TextView more = new TextView(this);
                        more.setText("（第 1 页 / 共 " + pr.getPageCount() + " 页，点标题用系统应用打开全文）");
                        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                        more.setTextColor(getColor(R.color.label_tertiary));
                        body.addView(more);
                        return;
                    }
                    pr.close();
                    pfd.close();
                } catch (Throwable ignored) {}
            }
            if (image) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int sample = 1;
                while (o.outWidth > 0 && o.outWidth / sample > 900) sample *= 2;
                android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
                d.inSampleSize = sample;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory
                        .decodeFile(f.getAbsolutePath(), d);
                if (bm != null) {
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageBitmap(bm);
                    iv.setAdjustViewBounds(true);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
                    body.addView(iv);
                    return;
                }
            }
            if (f.length() > 512 * 1024) {
                TextView t = new TextView(this);
                t.setText("文件较大（" + (f.length() / 1024) + " KB），点标题用系统应用打开。");
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTextColor(getColor(R.color.label_tertiary));
                body.addView(t);
                return;
            }
            String content = new String(readBytes(f), "UTF-8");
            if (content.indexOf('\u0000') >= 0) {
                TextView t = new TextView(this);
                t.setText("这类文件没法内嵌预览，点上面的文件名用系统应用打开。");
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTextColor(getColor(R.color.label_tertiary));
                body.addView(t);
                return;
            }
            boolean md = f.getName().toLowerCase().endsWith(".md");
            String show = content.length() > 4000
                    ? content.substring(0, 4000) + "\n…（截断，共 " + content.length() + " 字）"
                    : content;
            TextView t = new TextView(this);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(getColor(R.color.label_secondary));
            t.setTextIsSelectable(true);
            t.setText(md ? Markdown.render(this, show) : show);
            body.addView(t);
        } catch (Exception e) {
            TextView t = new TextView(this);
            t.setText("预览失败：" + e.getMessage());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(getColor(R.color.danger));
            body.addView(t);
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[(int) Math.min(f.length(), 600 * 1024)];
        int n = in.read(buf);
        in.close();
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
    private void scheduleAssistantRender() {
        if (pendingRender != null) return;
        pendingRender = new Runnable() {
            @Override public void run() {
                pendingRender = null;
                if (assistantView != null) {
                    assistantView.setText(Markdown.render(MainActivity.this, assistantBuf.toString()));
                    scrollDown();
                }
            }
        };
        ui.postDelayed(pendingRender, 70);
    }

    // ---------------- 交互 ----------------

    /** 选一个文件，复制进工作区的 uploads/，把绝对路径写进输入框。 */

    /** 查 GitHub Releases，有新版本就问一句。 */
    private void checkUpdate() {
        toast("正在检查更新…");
        new Thread(new Runnable() {
            @Override public void run() {
                final Updater.Info info = Updater.latest();
                final String cur = Updater.currentVersion(MainActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (info == null) { toast("查不到更新（网络或 API 不可达）"); return; }
                        if (!Updater.isNewer(info.tag, cur)) {
                            android.util.Log.i("DSHN", "更新 已是最新 " + cur);
                            toast("已是最新版本 " + cur);
                            return;
                        }
                        String notes = info.notes == null ? "" : info.notes.trim();
                        if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
                        showPanelDialog(buildConfirmPanel(
                                "发现新版本 " + info.tag,
                                "当前 " + cur + " → " + info.tag + "（"
                                        + (info.size / 1024 / 1024) + " MB）\n\n" + notes,
                                "下载并安装", R.color.accent,
                                new Runnable() {
                                    @Override public void run() { downloadAndInstall(info); }
                                }));
                    }
                });
            }
        }).start();
    }

    private void downloadAndInstall(final Updater.Info info) {
        if (!Updater.canInstall(this)) {
            toast("先允许「安装未知应用」，然后再点一次检查更新");
            Updater.openInstallPermission(this);
            return;
        }
        android.util.Log.i("DSHN", "更新 开始下载 " + info.apkUrl);
        toast("开始下载 " + info.tag + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File apk = Updater.download(MainActivity.this, info,
                            new Updater.Progress() {
                                @Override public void onProgress(long got, long total) {
                                    // 进度只打日志，避免每 64KB 弹一次 toast
                                    if (total > 0 && got % (8L * 1024 * 1024) < 64 * 1024) {
                                        android.util.Log.i("DSHN", "更新下载 " + got / 1024 / 1024
                                                + "/" + total / 1024 / 1024 + " MB");
                                    }
                                }
                            });
                    ui.post(new Runnable() {
                        @Override public void run() {
                            android.util.Log.i("DSHN", "更新 下载完成，转安装器");
                            toast("下载完成，交给系统安装");
                        }
                    });
                    Updater.install(MainActivity.this, apk);
                } catch (Throwable e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            android.util.Log.i("DSHN", "更新 下载失败: " + e);
                            toast("下载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
    private void pickFile() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(android.content.Intent.createChooser(i, "选择要附上的文件"), REQ_PICK);
        } catch (Exception e) {
            toast("打不开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Screen.REQ_CONSENT) {
            String err = Screen.start(this, res, data);
            toast(err == null ? "截屏已授权，模型现在能看见屏幕" : err);
            refreshDrawer();
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        String name = "file";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                c.close();
            }
        } catch (Exception ignored) {}
        File dir = new File(Ws.active(this), "uploads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name.replace("/", "_"));
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            in.close();
            out.close();
            String lower = name.toLowerCase();
            boolean isImage = lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".webp");
            if (isImage) pendingImages.add(dst.getAbsolutePath());
            String line = (isImage ? "[图片] " : "[附件] ") + dst.getAbsolutePath()
                    + "  (" + (total / 1024) + " KB)";
            input.append(input.getText().length() > 0 ? "\n" + line : line);
            toast("已附上 " + name);
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }
    private void onSend() {
        if (running) {
            // 跑着的时候：输入框有字就是"插话"，没字才是"停"。
            // 手机打字慢，想纠偏不该逼用户先停再重发。
            String s = input.getText().toString().trim();
            if (!s.isEmpty() && agent != null && agent.steer(s)) {
                input.setText("");
                addSteerChip(s);
                toast("已插话，当前任务不会中断");
            }
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (apiKey().isEmpty()) { askKey(); return; }

        input.setText("");
        addUserBubble(text);
        assistantView = null;
        assistantBuf.setLength(0);
        thinkingBlock = null;
        thinkingChars = 0;

        // 附件既可以来自选择器（pendingImages），也可以来自文本里手写的 [图片] 路径——
        // 后者让"引用行"自足：粘贴一行路径就能发图，不依赖选择器。
        final java.util.List<String> imgsNow = new java.util.ArrayList<>(pendingImages);
        pendingImages.clear();
        java.util.regex.Matcher im = java.util.regex.Pattern
                .compile("\\[图片\\]\\s*(\\S+)").matcher(text);
        while (im.find()) {
            String p = im.group(1).trim();
            if (!imgsNow.contains(p) && new File(p).isFile()) imgsNow.add(p);
        }
        running = true;
        abortRequested = false;
        assistantClosed = false;
        fileCardsShown.clear();
        pendingTools.clear();
        setSendState(true);
        RunService.start(this);
        KeepAlive.acquire(this);   // 锁屏也别让这一轮被打断

        new Thread(new Runnable() {
            @Override public void run() {
                // 无障碍服务可能比 Agent 晚连上：连上了就重建一份带 ui_* 的清单
                if (agent != null && !agent.hasA11y() && UiAgentService.isReady()) agent = null;
                // Agent 构造会联网发现 MCP 工具，必须在后台线程
                final Agent a = ensureAgent();
                // 构造 Agent 可能要几秒（联网发现 MCP、解压工具链）。
                // 这期间用户按了停止 → 这里必须认账，否则按钮看起来是死的。
                if (!abortRequested) a.run(text, imgsNow);
                ui.post(new Runnable() {
                    @Override public void run() {
                        running = false;
                        setSendState(false);
                        RunService.stop(MainActivity.this);
                        KeepAlive.release();
                        refreshMeta();
                    }
                });
            }
        }).start();
    }

    private void setSendState(boolean busy) {
        if (busy) {
            refreshSendIcon();
        } else {
            sendBtn.setText("↑");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            sendBtn.setTextColor(getColor(R.color.brand_fg));
            sendBtn.setBackgroundResource(R.drawable.bg_send);
        }
    }

    /** 跑着的时候，按钮随输入框内容切换：有字=插话，没字=停止。 */
    private void refreshSendIcon() {
        if (!running) return;
        boolean hasText = input != null && input.getText().length() > 0;
        if (hasText) {
            sendBtn.setText("↪");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            sendBtn.setTextColor(getColor(R.color.brand_fg));
            sendBtn.setBackgroundResource(R.drawable.bg_send);
        } else {
            sendBtn.setText("■");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sendBtn.setTextColor(getColor(R.color.danger));
            sendBtn.setBackgroundResource(R.drawable.bg_ghost);
        }
    }

    /** 插话在对话里的痕迹：一条小块，跟用户气泡区分开。 */
    private void addSteerChip(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END);
        TextView t = new TextView(this);
        String body = text.startsWith("插话") ? text : "插话：" + text;
        t.setText("↪ " + body);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.addView(t, lp);
        addView(row);
        scrollDown();
    }

    private Agent ensureAgent() {
        if (agent == null) {
            agent = new Agent(MainActivity.this,
                    apiKey(),
                    model,
                    prefs.getString("base_url", DEFAULT_BASE),
                    mode,
                    session,
    
                    root,
                    this);
            final String note0 = agent.toolCount() + " 个工具 · " + agent.tiers();
            final boolean warn = a11yEnabledButNotConnected();
            ui.post(new Runnable() {
                @Override public void run() {
                    addNotice(note0 + (warn ? " · 无障碍已开但尚未连上，开新会话后生效" : ""));
                }
            });
        }
        return agent;
    }

    /** 设置里开了无障碍、但服务还没连上：这时开出的会话拿不到 ui_*，得说清楚。 */
    private boolean a11yEnabledButNotConnected() {
        if (UiAgentService.isReady()) return false;
        try {
            int on = android.provider.Settings.Secure.getInt(getContentResolver(), "accessibility_enabled");
            if (on != 1) return false;
            String s = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_accessibility_services");
            return s != null && s.contains("com.dshnative");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上一轮被打断（进程被杀、崩溃、装了新版本）时，历史最后一条是工具结果。
     * 这时给一个明确的入口接着跑——否则用户看到的就是"任务突然断了"。
     */
    /**
     * 被打断后**自动接上**，不给用户按钮。
     *
     * 进程被杀（装包、重启、OOM）时线程必死，这躲不掉；能做的只有：重开就接着跑，
     * 让人感觉不到断过。
     *
     * 防死循环：同一个中断状态只自动续一次。如果续跑本身又崩了，消息条数会停在原地，
     * 第二次就只留一行提示，不再自动重试。
     */
    private void autoResumeIfNeeded() {
        if (session == null || running) return;
        if (!Session.needsResume(this, session.id)) return;

        int count = Session.count(this, session.id);
        String key = "resume_at_" + session.id;
        if (prefs.getInt(key, -1) == count) {
            note("（上一轮又被中断了，自动续跑已试过一次，不再重试。发条消息我接着干。）");
            return;
        }
        prefs.edit().putInt(key, count).apply();

        note("（进程重启过，已自动接上被打断的那一轮）");
        ui.postDelayed(new Runnable() {
            @Override public void run() { onResumeTurn(); }
        }, 800);
    }

    /** 一行浅色提示，不占按钮。 */
    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(getColor(R.color.label_caption));
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        addView(t);
    }
    /** 第一次启动时往往还没给文件权限，导入会失败；拿到权限后回来再试一次。 */
    private void retryImport() {
        if (prefs.getBoolean("migrated_done", false)) return;
        if (!Ws.hasAllFiles(this)) {
            // 没有共享存储权限，读不到中转目录，等授权后再说
            return;
        }
        String r = Migrate.importIfPresent(this);
        if (r != null) {
            prefs.edit().putBoolean("migrated_done", true).apply();
            note(r);
            initSession();
        }
    }

    private void onResumeTurn() {
        if (running) return;
        running = true;
        abortRequested = false;
        assistantClosed = false;
        fileCardsShown.clear();
        pendingTools.clear();
        setSendState(true);
        RunService.start(this);
        KeepAlive.acquire(this);   // 锁屏也别让这一轮被打断
        new Thread(new Runnable() {
            @Override public void run() {
                final Agent a = ensureAgent();
                if (!abortRequested) a.resume();
                ui.post(new Runnable() {
                    @Override public void run() {
                        running = false;
                        setSendState(false);
                        RunService.stop(MainActivity.this);
                        KeepAlive.release();
                        refreshMeta();
                    }
                });
            }
        }).start();
    }

    private void onAbort() {
        abortRequested = true;
        if (agent != null) {
            agent.cancel();
        } else {
            // Agent 还没构造完（还在联网/解压），没有可取消的对象：
            // 先把界面恢复，后台线程看到标志后不会再发请求。
            running = false;
            setSendState(false);
            RunService.stop(this);
            toast("已停止");
        }
    }

    private void onFresh() {
        if (agent != null) agent.cancel();
        agent = null;
        // 设置页可能改过模式：新会话要用新的（模式在会话内冻结，只在这里重读）
        mode = Mode.byId(prefs.getString("mode", "standard"));
        assistantView = null;
        assistantBuf.setLength(0);
        thinkingBlock = null;
        destroyWebviews();
        transcript.removeAllViews();
        transcript.addView(buildEmptyState());
        session = Session.create(this);
        session.ws = Workspaces.currentId(this);   // 新会话归到当前工作区
        Session.touch(this, session);
        prefs.edit().putString("session_id", session.id).apply();
        refreshMeta();
    }

    /** 拉一次余额。60 秒内复用缓存，别每轮都打一次接口。 */
    private void refreshBalance() {
        if (!Secret.hasKey(prefs)) return;
        if (System.currentTimeMillis() - balanceAge < 60000L) return;
        final String key = apiKey();
        final String base = prefs.getString("base_url", DEFAULT_BASE);
        if (key == null || key.isEmpty()) return;
        balanceAge = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                final String b = DeepSeekClient.fetchBalance(key, base);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (b != null) balance = b;
                        refreshMeta();
                    }
                });
            }
        }).start();
    }
    private String effortLabel() {
        String e = prefs.getString("reasoning_effort", "max");
        if ("none".equals(e)) return "Off";
        if ("low".equals(e)) return "Low";
        if ("medium".equals(e)) return "Medium";
        if ("high".equals(e)) return "High";
        return "Max";
    }

    private TextView quickLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        v.setTextColor(getColor(R.color.label_caption));
        v.setLetterSpacing(0.06f);
        v.setPadding(dp(6), dp(14), dp(6), dp(6));
        return v;
    }

    private LinearLayout quickCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        return c;
    }

    private void quickDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(getColor(R.color.border_l1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(14);
        parent.addView(d, lp);
    }

    private void quickRow(LinearLayout card, String title, String sub, boolean on,
                          final View.OnClickListener l, final AlertDialog[] holder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.label_primary));
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s.setTextColor(getColor(R.color.label_tertiary));
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(this);
        check.setText(on ? "✓" : "");
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        check.setTextColor(getColor(R.color.accent));
        check.setPadding(dp(10), 0, 0, 0);
        row.addView(check);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                l.onClick(v);
                if (holder[0] != null) holder[0].dismiss();
            }
        });
        card.addView(row);
    }

    /** 顶栏第二行点开的快捷面板：卡片式，模型 + 推理等级一屏选完。 */
    private void showQuickSettings() {
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(4));

        // 模型选择去掉了：deepseek-v4-pro 已经不可用，这里固定用 deepseek-flash。
        box.addView(quickLabel("推理等级"));
        LinearLayout ecard = quickCard();
        final String[][] lv = {
                {"max", "Max", null},
                {"high", "High", null},
                {"medium", "Medium", null},
                {"low", "Low", null},
                {"none", "Off", null},
        };
        String cur = prefs.getString("reasoning_effort", "max");
        for (int k = 0; k < lv.length; k++) {
            final String id = lv[k][0];
            quickRow(ecard, lv[k][1], lv[k][2], id.equals(cur), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("reasoning_effort", id).apply();
                    agent = null;
                    refreshMeta();
                }
            }, holder);
            if (k < lv.length - 1) quickDivider(ecard);
        }
        box.addView(ecard);

        holder[0] = new AlertDialog.Builder(this, R.style.DshDialog)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
        // 系统默认对话框是白底，和深色界面割裂
        try {
            holder[0].getWindow().setBackgroundDrawableResource(R.drawable.bg_sheet);
        } catch (Exception ignored) {}
    }

    private void setRoot(boolean r) {
        root = r;
        refreshMeta();
    }

    private void refreshMeta() {
        boolean a11y = UiAgentService.isReady();
        headerMeta.setText(mode.label + " · " + model + " · " + effortLabel());
        if (headerTitle != null) {
            String name = (session != null && session.title != null && !session.title.isEmpty())
                    ? session.title : "DSH Native";
            headerTitle.setText(name);
            headerTitle.setLetterSpacing("DSH Native".equals(name) ? 0.08f : 0f);
            headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, "DSH Native".equals(name) ? 13 : 14);
        }

        if (chipCtx != null) {
            if (agent == null) {
                chipCtx.setText("ctx —");
            } else {
                int used = agent.contextUsed();
                int lim = agent.contextLimit();
                // 百分比比绝对 token 更有意义：用户关心的是"还剩多少空间"
                int pct = lim <= 0 ? 0 : (int) Math.round(used * 100.0 / lim);
                chipCtx.setText("ctx " + pct + "%");
                chipCtx.setTextColor(getColor(pct >= 80 ? R.color.danger
                        : pct >= 60 ? R.color.label_primary : R.color.label_tertiary));
            }
        }
        if (ledger != null) {
            if (!Secret.hasKey(prefs)) {
                ledger.setText("未设置 key");
                ledger.setTextColor(getColor(R.color.danger));
            } else {
                ledger.setText(balance == null ? "余额 –" : "余额 " + balance);
                ledger.setTextColor(getColor(R.color.label_secondary));
            }
        }
        refreshDrawer();
        if (envBanner != null) {
            int missing = 0;
            StringBuilder what = new StringBuilder();
            if (!(Compat.a11yInSettings(this) || UiAgentService.isReady())) { missing++; what.append("无障碍 "); }
            if (!Compat.allFilesGranted(this)) { missing++; what.append("文件访问 "); }
            if (!Compat.batteryIgnored(this)) { missing++; what.append("电池白名单 "); }
            if (!Compat.notificationsOn(this)) { missing++; what.append("通知 "); }
            if (missing == 0) {
                envBanner.setVisibility(View.GONE);
            } else {
                envBanner.setText("⚠ 还差 " + what.toString().trim() + "· 点此补全");
                envBanner.setVisibility(View.VISIBLE);
            }
        }
    }

    // ---------------- 抽屉侧栏（桌面版侧栏在手机上的等价物） ----------------

    /** 环境自检：每项显示状态，点一下就跳到对应的系统页。不做绕过，只做导航。 */

    // ---------------- 渲染卡片 ----------------

    private final java.util.List<WebView> webviews = new java.util.ArrayList<>();

    @Override public void onRender(final String html, final String url) {
        ui.post(new Runnable() {
            @Override public void run() { addRenderCard(html, url); }
        });
    }

    private void addRenderCard(String html, String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));

        WebView wv = new WebView(this);
        wv.setBackgroundColor(getColor(R.color.bg_base));
        try {
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(false);
            wv.getSettings().setAllowFileAccess(false);
        } catch (Exception ignored) {}
        webviews.add(wv);
        card.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));

        if (html != null) {
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        } else {
            wv.loadUrl(url);
        }
        addView(card);
    }

    private void destroyWebviews() {
        for (WebView w : webviews) {
            try {
                w.loadUrl("about:blank");
                w.destroy();
            } catch (Exception ignored) {}
        }
        webviews.clear();
    }
    // ---------------- 定时任务 ----------------

    private View withDrawer(View content) {
        FrameLayout outer = new FrameLayout(this);
        outer.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); }
        });
        outer.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(getColor(R.color.bg_base));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(18), dp(20), dp(18), dp(10));
        TextView title = new TextView(this);
        title.setText("DSH Native");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.06f);
        title.setTextColor(getColor(R.color.label_primary));
        head.addView(title);

        drawerPanel.addView(head);

        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border_l1));
        drawerPanel.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView sv = new ScrollView(this);
        drawerBody = new LinearLayout(this);
        drawerBody.setOrientation(LinearLayout.VERTICAL);
        drawerBody.setPadding(0, dp(6), 0, dp(20));
        sv.addView(drawerBody);
        drawerPanel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部固定：设置永远可见，不再被会话列表挤下去
        View div2 = new View(this);
        div2.setBackgroundColor(getColor(R.color.border_l1));
        drawerPanel.addView(div2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(4), 0, dp(10));
        footer.addView(drawerRow("设置", "插件开关 · MCP · 定时 · 环境自检", new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); openSettings(); }
        }));
        footer.addView(drawerRow("文件", "浏览手机里的文件", new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeDrawer();
                try { startActivity(new android.content.Intent(MainActivity.this, FilesActivity.class)); }
                catch (Exception e) { toast("打不开：" + e.getMessage()); }
            }
        }));
        footer.addView(drawerRow("检查更新", "当前 " + Updater.currentVersion(this), new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); checkUpdate(); }
        }));
        footer.addView(drawerRow("审计", Policy.Audit.count(getFilesDir()) + " 条记录", new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); showAudit(); }
        }));
        drawerPanel.addView(footer);

        drawerPanel.setTranslationX(-drawerWidth);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        outer.addView(drawerPanel, dlp);
        return outer;
    }

    /** 长按会话：置顶 / 取消置顶 / 删除。 */
    private void sessionMenu(final Session s) {
        String pin = s.pinned ? "取消置顶" : "置顶";
        new android.app.AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(s.title == null || s.title.isEmpty() ? "新会话" : s.title)
                .setItems(new String[]{pin, "删除", "取消"}, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            togglePin(s);
                        } else if (which == 1) {
                            confirmDeleteSession(s);
                        }
                    }
                })
                .show();
    }

    private void togglePin(Session s) {
        List<Session> all = Session.list(this);
        for (Session x : all) {
            if (x.id.equals(s.id)) {
                x.pinned = !x.pinned;
                x.updated = x.updated;   // 时间不动，置顶靠 pinned 排序
                break;
            }
        }
        Session.saveIndex(this, all);
        toast(s.pinned ? "已取消置顶" : "已置顶");
        refreshDrawer();
    }
    private View drawerRow(String title, String meta, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(11), dp(18), dp(11));
        if (l != null) row.setOnClickListener(l);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(getColor(R.color.label_primary));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);

        if (meta != null && !meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            m.setTextColor(getColor(R.color.label_tertiary));
            m.setPadding(0, dp(3), 0, 0);
            row.addView(m);
        }
        return row;
    }

    private View sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(getColor(R.color.label_caption));
        t.setPadding(dp(18), dp(16), dp(18), dp(6));
        return t;
    }

    private void refreshDrawer() {
        if (drawerBody == null) return;
        drawerBody.removeAllViews();

        drawerBody.addView(drawerRow("＋  新会话", "开一段新的对话", new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); onFresh(); }
        }));

        drawerBody.addView(sectionLabel("工作区 · " + Workspaces.current(this).name));
        drawerBody.addView(drawerRow("⇄  切换 / 新建工作区", Workspaces.current(this).path,
                new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); showWorkspaceSheet(); }
        }));

        drawerBody.addView(sectionLabel("历史会话 · 长按置顶 / 删除"));
        List<Session> all = Session.list(this, Workspaces.currentId(this));
        if (all.isEmpty()) {
            drawerBody.addView(drawerRow("（还没有会话）", null, null));
        } else {
            int shown = 0;
            for (final Session s : all) {
                // 只渲染最近 20 条：抽屉每轮都会重建，历史多了会明显卡
                if (shown++ >= 20) break;
                boolean cur = session != null && session.id.equals(s.id);
                // 置顶不用 emoji（📌 跟深色主题不搭）：改成句尾一个小标签
                String title = s.title == null || s.title.isEmpty() ? "新会话" : s.title;
                String name = (cur ? "●  " : "    ") + title + (s.pinned ? "   ·置顶" : "");
                View row = drawerRow(name,
                        s.tokensIn + " in / " + s.tokensOut + " out · $" + String.format("%.4f", s.cost),
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { closeDrawer(); loadSession(s.id); }
                        });
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        sessionMenu(s);
                        return true;
                    }
                });
                drawerBody.addView(row);
            }
        }

    }

    private void confirmDeleteSession(Session s) {
        String title = s.title == null || s.title.isEmpty() ? "新会话" : s.title;
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("删除「" + title + "」?")
                .setMessage("这条会话的记录会一并删除，不能恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Session.delete(MainActivity.this, s.id);
                        if (session != null && session.id.equals(s.id)) {
                            onFresh();          // 删的是当前会话，开一段新的
                        } else {
                            refreshDrawer();
                        }
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private void openDrawer() {
        refreshDrawer();
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        drawerScrim.animate().alpha(1f).setDuration(160).start();
        drawerPanel.animate().translationX(0f).setDuration(200).start();
    }

    private void closeDrawer() {
        drawerPanel.animate().translationX(-drawerWidth).setDuration(180).start();
        drawerScrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() { drawerScrim.setVisibility(View.GONE); }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerScrim != null && drawerScrim.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }
    private void openSettings() {
        try { startActivity(new android.content.Intent(this, SettingsActivity.class)); }
        catch (Exception e) { toast("打不开设置：" + e.getMessage()); }
    }

    private String apiKey() { return Secret.get(prefs); }

    private String tiers(boolean a11y) {
        StringBuilder sb = new StringBuilder("app");
        if (a11y) sb.append("+a11y");
        if (root) sb.append("+root");
        return sb.toString();
    }

    // ---------------- 会话 / 模式 / 工作区 ----------------

    private void initSession() {
        mode = Mode.byId(prefs.getString("mode", "standard"));
        String sid = prefs.getString("session_id", "");
        if (!sid.isEmpty()) {
            for (Session s : Session.list(this)) {
                if (s.id.equals(sid)) { session = s; break; }
            }
            if (session == null) session = new Session(sid);
        }
        // 上次的会话可能属于别的工作区：恢复它会让标题和底栏的工作区对不上。
        // 这种情况改成"打开当前工作区最近的一段会话"，没有就新建。
        if (session != null && !Workspaces.currentId(this).equals(session.ws)) {
            List<Session> mine = Session.list(this, Workspaces.currentId(this));
            session = mine.isEmpty() ? null : mine.get(0);
        }
        if (session == null) {
            session = Session.create(this);
            session.ws = Workspaces.currentId(this);
            Session.touch(this, session);
            prefs.edit().putString("session_id", session.id).apply();
        }
        if (Session.body(this, session.id).isFile()) {
            // 有历史就别先闪一下空状态：把"DSH Native + 建议"先撤掉，
            // 换成一行极轻的"正在载入"，等 renderHistory 顶上来。
            if (transcript != null && transcript.findViewWithTag("empty") != null) {
                transcript.removeAllViews();
                TextView loading = new TextView(this);
                loading.setText("载入中…");
                loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                loading.setTextColor(getColor(R.color.label_caption));
                loading.setPadding(dp(2), dp(20), dp(2), dp(20));
                transcript.addView(loading);
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    ensureAgent();
                    ui.post(new Runnable() {
                        @Override public void run() { renderHistory(); refreshMeta(); autoResumeIfNeeded(); }
                    });
                }
            }).start();
        }
        refreshMeta();
    }

    /**
     * 工作区选择面板。
     *
     * 用自绘的纵向列表而不是 AlertDialog.setItems：这样名字和路径能分两行排版，
     * 当前工作区有个圆点标记，长按非「完全权限」的项可以重命名 / 删除。
     */
    private void showWorkspaceSheet() {
        final List<Workspaces.W> all = Workspaces.all(this);
        final String cur = Workspaces.currentId(this);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sheet);
        panel.setPadding(dp(6), dp(12), dp(6), dp(10));

        TextView title = new TextView(this);
        title.setText("工作区");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(getColor(R.color.label_primary));
        title.setPadding(dp(14), dp(6), dp(14), dp(10));
        panel.addView(title);

        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        for (final Workspaces.W w : all) {
            boolean active = w.id.equals(cur);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            if (active) row.setBackgroundResource(R.drawable.bg_card);

            TextView dot = new TextView(this);
            dot.setText(active ? "●" : "○");
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dot.setTextColor(getColor(active ? R.color.brand_fg : R.color.label_caption));
            dot.setPadding(0, 0, dp(10), 0);
            row.addView(dot);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(w.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTextColor(getColor(R.color.label_primary));
            TextView path = new TextView(this);
            path.setText(w.path);
            path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            path.setTextColor(getColor(R.color.label_caption));
            path.setSingleLine(true);
            col.addView(name);
            col.addView(path);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    switchWorkspace(w.id);
                }
            });
            if (!Workspaces.FULL_ID.equals(w.id)) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        if (holder[0] != null) holder[0].dismiss();
                        workspaceMenu(w);
                        return true;
                    }
                });
            }
            panel.addView(row);
        }

        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border_l1));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(8);
        dlp.bottomMargin = dp(4);
        panel.addView(div, dlp);

        TextView add = new TextView(this);
        add.setText("＋  新建工作区");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        add.setTextColor(getColor(R.color.accent));
        add.setPadding(dp(14), dp(14), dp(14), dp(14));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                promptNewWorkspace();
            }
        });
        panel.addView(add);

        // 自己画「关闭」，不用系统的按钮条——那条是白底，跟深色面板打架
        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        close.setTextColor(getColor(R.color.label_caption));
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding(dp(14), dp(10), dp(14), dp(12));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
            }
        });
        panel.addView(close);

        holder[0] = new android.app.AlertDialog.Builder(this).setView(panel).create();
        holder[0].show();
        if (holder[0].getWindow() != null) {
            // 透明窗口背景：圆角由 bg_sheet 自己负责，否则四角会露出白底
            holder[0].getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            holder[0].getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 长按工作区：重命名 / 删除。 */
    private void workspaceMenu(final Workspaces.W w) {
        String[] items = {"重命名", "删除（会话保留在列表里）"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(w.name)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) promptRenameWorkspace(w);
                        else confirmDeleteWorkspace(w);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptRenameWorkspace(final Workspaces.W w) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sheet);
        panel.setPadding(dp(20), dp(16), dp(20), dp(10));

        TextView title = new TextView(this);
        title.setText("重命名工作区");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(getColor(R.color.label_primary));
        panel.addView(title);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(w.name);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(getColor(R.color.label_primary));
        input.setBackgroundResource(R.drawable.bg_field);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(12);
        panel.addView(input, ilp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        buttons.setPadding(0, dp(14), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cancel.setTextColor(getColor(R.color.label_caption));
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        buttons.addView(cancel);

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ok.setTextColor(getColor(R.color.accent));
        ok.setPadding(dp(16), dp(10), dp(4), dp(10));
        buttons.addView(ok);
        panel.addView(buttons);

        showPanelDialog(panel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { toast("名字不能为空"); return; }
                if (currentDialog != null) currentDialog.dismiss();
                Workspaces.rename(MainActivity.this, w.id, name);
                if (chipWs != null) {
                    chipWs.setText(Workspaces.current(MainActivity.this).name + " ▾");
                }
                refreshDrawer();
                showWorkspaceSheet();
            }
        });
    }

    private void confirmDeleteWorkspace(final Workspaces.W w) {
        showPanelDialog(buildConfirmPanel(
                "删除工作区",
                "「" + w.name + "」\n\n只移除这个入口，目录和里面的文件都保留：\n" + w.path,
                "删除", R.color.danger,
                new Runnable() {
                    @Override public void run() {
                        Workspaces.remove(MainActivity.this, w.id);
                        if (chipWs != null) {
                            chipWs.setText(Workspaces.current(MainActivity.this).name + " ▾");
                        }
                        switchWorkspace(Workspaces.currentId(MainActivity.this));
                        refreshDrawer();
                        toast("已删除工作区");
                    }
                }));
    }

    /**
     * 统一的深色面板对话框。
     *
     * 系统的 AlertDialog 在深色主题下会给一条白底按钮栏，跟面板打架；
     * 所以一律自绘：面板自己带标题、内容、按钮。
     */
    private void showPanelDialog(View panel) {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(panel).create();
        currentDialog = dlg;          // 按钮要用它来关闭；忘了这行就"点了不关"
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 标题 + 说明 + [取消][确认] 的面板。 */
    private View buildConfirmPanel(String title, String message, String okLabel,
                                   int okColorRes, final Runnable onOk) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sheet);
        panel.setPadding(dp(20), dp(16), dp(20), dp(10));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.label_primary));
        panel.addView(t);

        TextView m = new TextView(this);
        m.setText(message);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        m.setTextColor(getColor(R.color.label_secondary));
        m.setLineSpacing(dp(4), 1f);
        m.setPadding(0, dp(10), 0, dp(6));
        panel.addView(m);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        buttons.setPadding(0, dp(14), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cancel.setTextColor(getColor(R.color.label_caption));
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        buttons.addView(cancel);

        TextView ok = new TextView(this);
        ok.setText(okLabel);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ok.setTextColor(getColor(okColorRes));
        ok.setPadding(dp(16), dp(10), dp(4), dp(10));
        buttons.addView(ok);
        panel.addView(buttons);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
                onOk.run();
            }
        });
        return panel;
    }

    /** 新建工作区：自绘深色面板，名字 + 目录（可挑手机里的文件夹）。 */
    private void promptNewWorkspace() {
        final String[] chosen = {null};   // null = 用默认目录

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sheet);
        panel.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("新建工作区");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(getColor(R.color.label_primary));
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText("每个工作区有自己的对话历史和目录");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(getColor(R.color.label_caption));
        hint.setPadding(0, dp(4), 0, dp(14));
        panel.addView(hint);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("名字，例如 论文 / 网站 / 实验");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(getColor(R.color.label_primary));
        input.setHintTextColor(getColor(R.color.label_caption));
        input.setBackgroundResource(R.drawable.bg_field);
        input.setSingleLine(true);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.addView(input);

        final TextView pathView = new TextView(this);
        pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pathView.setTextColor(getColor(R.color.label_caption));
        pathView.setPadding(dp(2), dp(10), dp(2), 0);
        pathView.setText("目录：自动（/sdcard/DSHNative/ws_<名字>）");
        panel.addView(pathView);

        TextView pick = new TextView(this);
        pick.setText("📁  选择手机里的文件夹…");
        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pick.setTextColor(getColor(R.color.accent));
        pick.setPadding(dp(2), dp(12), dp(2), dp(8));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickFolder(new View.OnClickListener() {
                    @Override public void onClick(View ignore) {}
                }, new FolderPick() {
                    @Override public void onPick(String path) {
                        chosen[0] = path;
                        pathView.setText("目录：" + path);
                    }
                });
            }
        });
        panel.addView(pick);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        buttons.setPadding(0, dp(16), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cancel.setTextColor(getColor(R.color.label_caption));
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        buttons.addView(cancel);

        TextView ok = new TextView(this);
        ok.setText("创建");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ok.setTextColor(getColor(R.color.accent));
        ok.setPadding(dp(16), dp(10), dp(4), dp(10));
        buttons.addView(ok);
        panel.addView(buttons);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(panel).create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { toast("先起个名字"); return; }
                String path = chosen[0];
                if (path == null) {
                    File base = Ws.preferred(MainActivity.this);
                    path = new File(base, "ws_" + name.replaceAll("[^\\w\\u4e00-\\u9fa5]", "_"))
                            .getAbsolutePath();
                }
                dlg.dismiss();
                Workspaces.W w = Workspaces.create(MainActivity.this, name, path);
                switchWorkspace(w.id);
                showWorkspaceSheet();
            }
        });
    }

    /** 目录选择回调。 */
    private interface FolderPick { void onPick(String path); }

    /**
     * 手机内的文件夹选择器。
     *
     * 不用 SAF 的 ACTION_OPEN_DOCUMENT_TREE：它给的是 content:// URI，
     * 转成真实路径在各种 ROM 上都不稳；这里直接读 /sdcard 的目录树
     * （有「所有文件访问」权限就够）。
     */
    private void pickFolder(View.OnClickListener unused, final FolderPick cb) {
        final File root = android.os.Environment.getExternalStorageDirectory();
        final File[] cur = {root};

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sheet);
        panel.setPadding(dp(16), dp(14), dp(16), dp(10));

        final TextView head = new TextView(this);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setTextColor(getColor(R.color.label_primary));
        head.setPadding(dp(4), 0, dp(4), dp(8));
        panel.addView(head);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        panel.addView(sc, slp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cancel.setTextColor(getColor(R.color.label_caption));
        cancel.setPadding(dp(16), dp(12), dp(16), dp(10));
        buttons.addView(cancel);
        TextView ok = new TextView(this);
        ok.setText("选择这个文件夹");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ok.setTextColor(getColor(R.color.accent));
        ok.setPadding(dp(16), dp(12), dp(4), dp(10));
        buttons.addView(ok);
        panel.addView(buttons);

        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        final Runnable render = new Runnable() {
            @Override public void run() {
                head.setText("📁 " + cur[0].getAbsolutePath());
                list.removeAllViews();
                if (!cur[0].equals(root)) {
                    addFolderRow(list, "↩ 上一级", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            File up = cur[0].getParentFile();
                            if (up != null) { cur[0] = up; run(); }
                        }
                    });
                }
                File[] kids = cur[0].listFiles();
                if (kids == null) return;
                java.util.Arrays.sort(kids, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (final File f : kids) {
                    if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                    addFolderRow(list, "📂 " + f.getName(), new View.OnClickListener() {
                        @Override public void onClick(View v) { cur[0] = f; run(); }
                    });
                }
            }
        };
        render.run();

        holder[0] = new android.app.AlertDialog.Builder(this).setView(panel).create();
        holder[0].show();
        if (holder[0].getWindow() != null) {
            holder[0].getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            holder[0].getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                holder[0].dismiss();
                cb.onPick(cur[0].getAbsolutePath());
            }
        });
    }

    private void addFolderRow(LinearLayout parent, String label, View.OnClickListener click) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setTextColor(getColor(R.color.label_primary));
        row.setPadding(dp(8), dp(11), dp(8), dp(11));
        row.setOnClickListener(click);
        parent.addView(row);
    }

    private void switchWorkspace(String id) {
        if (id.equals(Workspaces.currentId(this))) return;
        Workspaces.setCurrent(this, id);
        if (chipWs != null) chipWs.setText(Workspaces.current(this).name + " ▾");
        // 切到该工作区最近的一段会话；没有就开一段新的
        List<Session> mine = Session.list(this, id);
        if (mine.isEmpty()) {
            onFresh();
        } else {
            loadSession(mine.get(0).id);
        }
        refreshDrawer();
        toast("已切到工作区：" + Workspaces.current(this).name);
    }

    private void loadSession(String id) {
        if (agent != null) agent.cancel();
        agent = null;
        assistantView = null;
        assistantBuf.setLength(0);
        thinkingBlock = null;
        destroyWebviews();
        transcript.removeAllViews();
        session = null;
        for (Session s : Session.list(this)) {
            if (s.id.equals(id)) { session = s; break; }
        }
        if (session == null) session = new Session(id);
        prefs.edit().putString("session_id", id).apply();
        new Thread(new Runnable() {
            @Override public void run() {
                ensureAgent();
                ui.post(new Runnable() {
                    @Override public void run() { renderHistory(); refreshMeta(); autoResumeIfNeeded(); }
                });
            }
        }).start();
        refreshMeta();
    }

    /** 把历史消息重绘到对话流里（跳过第 0 条 system）。 */
    private void renderHistory() {
        if (agent == null) return;
        JSONArray h = agent.history();
        for (int i = 1; i < h.length(); i++) {
            JSONObject m;
            try { m = h.getJSONObject(i); } catch (Exception e) { continue; }
            String role = m.optString("role", "");
            if ("user".equals(role)) {
                addUserBubble(m.optString("content", ""));
            } else if ("assistant".equals(role)) {
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int k = 0; k < calls.length(); k++) {
                        try {
                            JSONObject fn = calls.getJSONObject(k).getJSONObject("function");
                            addToolChip(fn.optString("name"), fn.optString("arguments", ""), true);
                        } catch (Exception ignored) {}
                    }
                }
                String c = m.optString("content", "");
                if (!c.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setTextColor(getColor(R.color.label_primary));
                    tv.setLineSpacing(dp(5), 1f);
                    tv.setTextIsSelectable(true);
                    tv.setText(Markdown.render(this, c));
                    addView(tv);
                }
            }
        }
        scrollDown();
    }

    private void showAudit() {
        int n = Policy.Audit.count(getFilesDir());
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("审计")
                .setMessage(n + " 条记录（append-only + hash 链）\n文件：files/audit.log")
                .setPositiveButton("好", null)
                .show();
    }

    private void askKey() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(0));

        final EditText etKey = new EditText(this);
        etKey.setHint("sk-…");
        etKey.setSingleLine(true);
        etKey.setText(apiKey());

        final EditText etBase = new EditText(this);
        etBase.setHint(DEFAULT_BASE);
        etBase.setSingleLine(true);
        etBase.setText(prefs.getString("base_url", ""));

        box.addView(etKey);
        box.addView(etBase);

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("API key")
                .setMessage("base URL 留空即用官方地址。")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String k = etKey.getText().toString().trim();
                        Secret.put(prefs, k);
                        prefs.edit()
                                .putString("base_url", etBase.getText().toString().trim())
                                .apply();
                        refreshMeta();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- Agent.Listener ----------------

    /**
     * 待办的详情面板。
     *
     * 第一版是 AlertDialog 里塞纯文本（"1. [ ] xxx"），简陋得不像这个 App 的一部分。
     * 这版按界面语言重画：进度条 + 每项一个状态圆点 + 完成项划掉、进行项用强调色。
     */
    private void showTodoSheet() {
        String sid = session == null ? "" : session.id;
        java.util.List<Todo.Item> items = Todo.load(this, sid);
        if (items.isEmpty()) {
            toast("当前没有待办");
            return;
        }
        int done = 0;
        for (Todo.Item it : items) if ("done".equals(it.status)) done++;

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(16), pad, dp(8));

        // 头部：完成度
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("待办");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t1.setTextColor(getColor(R.color.label_primary));
        t1.setTypeface(t1.getTypeface(), Typeface.BOLD);
        head.addView(t1, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t2 = new TextView(this);
        t2.setText(done + " / " + items.size());
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t2.setTextColor(getColor(R.color.label_tertiary));
        head.addView(t2);
        box.addView(head);

        // 进度条：两条 View 叠出来的细条，比 ProgressBar 更贴合界面
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(getColor(R.color.border_l2));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        barLp.topMargin = dp(12);
        barLp.bottomMargin = dp(18);
        box.addView(bar, barLp);
        View fill = new View(this);
        fill.setBackgroundColor(getColor(R.color.accent));
        bar.addView(fill, new LinearLayout.LayoutParams(0, dp(4),
                items.isEmpty() ? 0f : (float) done / items.size()));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, dp(4),
                items.isEmpty() ? 1f : 1f - (float) done / items.size()));

        // 每项一行：圆点 + 文本 + 右侧状态
        for (Todo.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(9), 0, dp(9));

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            boolean isDone = "done".equals(it.status);
            boolean isDoing = "doing".equals(it.status);
            g.setColor(isDone ? getColor(R.color.success)
                    : (isDoing ? getColor(R.color.accent) : getColor(R.color.border_l3)));
            dot.setBackground(g);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dotLp.topMargin = dp(6);
            dotLp.rightMargin = dp(10);
            row.addView(dot, dotLp);

            TextView tx = new TextView(this);
            tx.setText(it.text);
            tx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            if (isDone) {
                tx.setTextColor(getColor(R.color.label_caption));
                tx.setPaintFlags(tx.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            } else if (isDoing) {
                tx.setTextColor(getColor(R.color.label_primary));
                tx.setTypeface(tx.getTypeface(), Typeface.BOLD);
            } else {
                tx.setTextColor(getColor(R.color.label_secondary));
            }
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (isDoing || isDone) {
                TextView tag = new TextView(this);
                tag.setText(isDoing ? "在做" : "完成");
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tag.setTextColor(isDoing ? getColor(R.color.accent) : getColor(R.color.label_caption));
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                tagLp.leftMargin = dp(8);
                tagLp.topMargin = dp(2);
                row.addView(tag, tagLp);
            }
            box.addView(row);
        }
        sc.addView(box);

        currentDialog = new AlertDialog.Builder(this, R.style.DshDialog)
                .setView(sc)
                .setPositiveButton("关闭", null)
                .create();
        currentDialog.show();
        try {
            currentDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_sheet);
        } catch (Exception ignored) {}
    }

    /** 把「生成进度」和「待办进度」合成一行，空着就整行隐藏。 */
    private void refreshTodoLine() {
        if (genStatus == null) return;
        StringBuilder sb = new StringBuilder();
        if (!todoSummary.isEmpty()) sb.append(todoSummary);
        if (running && genStart > 0) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("正在写 · ").append(assistantBuf.length()).append(" 字 · ")
              .append((System.currentTimeMillis() - genStart) / 1000).append('s');
        }
        if (sb.length() == 0) {
            genStatus.setVisibility(View.GONE);
        } else {
            genStatus.setText(sb.toString());
            genStatus.setVisibility(View.VISIBLE);
        }
    }

    /** 生成过程中刷新进度提示（当前假设：只在写正文时显示）。 */
    private void bumpGenStatus(boolean writing) {
        if (genStatus == null) return;
        if (genStart == 0) genStart = System.currentTimeMillis();
        long sec = (System.currentTimeMillis() - genStart) / 1000;
        int chars = assistantBuf.length();
        if (chars <= 0 && !writing) { genStatus.setVisibility(View.GONE); return; }
        genStatus.setVisibility(View.VISIBLE);
        genStatus.setText("正在写 · " + chars + " 字 · " + sec + "s");
    }

    @Override public void onAssistant(final String delta) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (assistantClosed) {
                    appendFileChips(assistantBuf.toString());
                    // 保持"文字 → 工具 → 文字"的真实顺序：不新开一段的话，
                    // 后一段文字会被追加到最上面那个气泡里，交错就压平了。
                    assistantView = null;
                    assistantBuf.setLength(0);
                    assistantClosed = false;
        fileCardsShown.clear();
        pendingTools.clear();
                }
                ensureAssistantView();
                assistantBuf.append(delta);
                scheduleAssistantRender();
                bumpGenStatus(true);
            }
        });
    }

    @Override public void onReasoning(final String delta) {
        ui.post(new Runnable() {
            @Override public void run() {
                // 以前这里有一句 if (assistantView != null) finishThinking();
                // 正文一开始流式，后面每个思考增量都会"关一块开一块"，产生一堆 1~2 字的残块。
                // 思考块只在工具调用或本轮结束时收口。
                ensureThinkingBlock();
                thinkingChars += delta.length();
                thinkingBuf.append(delta);
                if (thinkingBuf.length() > 40000) {
                    thinkingBuf.delete(0, thinkingBuf.length() - 20000);
                }

                scrollDown();
            }
        });
    }

    @Override public void onTool(final String name, final String detail) {
        ui.post(new Runnable() {
            @Override public void run() {
                finishThinking();      // 开始动手了，思考块在这里收口
                addToolChip(name, detail);
                assistantClosed = true;   // 后面的文字要另起一段
                toolStart = System.currentTimeMillis();
                ui.removeCallbacks(toolTick);
                ui.postDelayed(toolTick, 500);
            }
        });
    }

    /** 本轮工具改动过的文件（来自 Agent 的 collectTouched + 工作区快照）。 */
    private final java.util.LinkedHashSet<String> turnFiles = new java.util.LinkedHashSet<>();

    @Override public void onFilesTouched(final java.util.List<String> paths) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (paths == null) return;
                for (String p : paths) {
                    if (p != null && !p.isEmpty() && new File(p).isFile()) turnFiles.add(p);
                saveFileMark();
                }
            }
        });
    }

    /** 把"这一刻改了哪些文件"追加到会话旁边的索引里（一行一条：序号<TAB>路径）。 */
    private void saveFileMark() {
        try {
            if (agent == null || session == null || turnFiles.isEmpty()) return;
            int at = agent.history().length();
            StringBuilder sb = new StringBuilder();
            for (String p : turnFiles) sb.append(at).append('\t').append(p).append('\n');
            File f = new File(Session.dir(this), "files_" + session.id + ".txt");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }

    /** 读回索引：消息序号 → 文件列表。 */
    private java.util.Map<Integer, java.util.List<String>> loadFileMarks() {
        java.util.Map<Integer, java.util.List<String>> out = new java.util.HashMap<>();
        try {
            if (session == null) return out;
            File f = new File(Session.dir(this), "files_" + session.id + ".txt");
            if (!f.isFile()) return out;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                int idx = Integer.parseInt(line.substring(0, tab));
                String p = line.substring(tab + 1).trim();
                if (p.isEmpty()) continue;
                java.util.List<String> list = out.get(idx);
                if (list == null) { list = new java.util.ArrayList<>(); out.put(idx, list); }
                list.add(p);
            }
            r.close();
        } catch (Throwable ignored) {}
        return out;
    }
    /** 把本轮改动的文件做成卡片；只留成品，和正文里已经挂过的去重。 */
    private void flushTurnFiles() {
        android.util.Log.i("DSHN", "flushTurnFiles: " + turnFiles.size() + " 个");
        if (turnFiles.isEmpty()) return;
        int shown = 0;
        for (String p : turnFiles) {
            if (shown >= 4) break;                      // 最多 4 张，别刷屏
            if (fileCardsShown.contains(p)) continue;
            File f = new File(p);
            if (!Attach.worth(f)) continue;           // 中间产物不挂
            fileCardsShown.add(p);
            addView(buildFileCard(f));
            shown++;
        }
        turnFiles.clear();
        scrollDown();
    }
    @Override public void onToolDone(final String name, final long ms) {
        ui.post(new Runnable() {
            @Override public void run() {
                ui.removeCallbacks(toolTick);
                String dur = ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0);
                // 按名字找最早那个还没完成的芯片——并行调用时不能只认最后一个
                PendingTool hit = null;
                for (PendingTool p : pendingTools) {
                    if (p.name.equals(name)) { hit = p; break; }
                }
                if (hit == null && !pendingTools.isEmpty()) hit = pendingTools.get(0);
                if (hit != null) {
                    hit.view.setText("✓ " + hit.doneLabel);
                    hit.view.setTextColor(getColor(R.color.label_secondary));
                    hit.meta.setText(hit.base + "   " + dur);
                    pendingTools.remove(hit);
                } else if (lastToolName != null && lastToolLabel != null) {
                    lastToolName.setText("✓ " + lastToolLabel);
                    if (lastToolMeta != null) {
                        lastToolMeta.setText((lastToolBase == null ? "" : lastToolBase) + "   " + dur);
                    }
                }
            }
        });
    }

    @Override public void onTodo(final String summary) {
        ui.post(new Runnable() {
            @Override public void run() {
                todoSummary = summary == null ? "" : summary;
                refreshTodoLine();
            }
        });
    }

    @Override public void onStatus(final String status) {
        // 状态行不再占用界面（原来挂在顶部的余额胶囊上）。
        // 有需要时它仍然会被写进日志，便于排查。
        android.util.Log.i("DSHN", "status: " + status);
    }

    /** 每次请求后的用量：更新底栏的缓存命中率。 */
    @Override public void onUsage(final int hit, final int miss, final int out, final double costUsd) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (chipHit == null) return;
                int total = hit + miss;
                if (total <= 0) { chipHit.setText("命中 —"); return; }
                int pct = (int) Math.round(hit * 100.0 / total);
                chipHit.setText("命中 " + pct + "%");
                chipHit.setTextColor(getColor(pct >= 80 ? R.color.label_secondary
                        : pct >= 40 ? R.color.label_tertiary : R.color.danger));
            }
        });
    }

    @Override public void onError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                finishThinking();
                addError(message);
            }
        });
    }

    @Override public void onBusy(final boolean busy) {
        ui.post(new Runnable() {
            @Override public void run() {
                    flushTurnFiles();                          // 本轮改过的文件做成卡片
                    appendFileChips(assistantBuf.toString());   // 收尾时把文件挂成卡片
                if (!busy) {
                    genStart = 0;
                    // 回合结束了，但待办进度要留着——不能连整行一起关掉
                    refreshTodoLine();
                    finishThinking();
                    refreshBalance();
        if (migratedNote != null) note(migratedNote);
        autoResumeIfNeeded();
                    sendBtn.postDelayed(new Runnable() {
                        @Override public void run() { setSendState(false); }
                    }, 120);
                }
            }
        });
    }

    /**
     * 让用户从选项里选一个。阻塞式：后台线程等结果，主线程弹按钮。
     * 多选时把选中的拼成「A / B」返回。
     */
    @Override public String askChoice(final String title, final String[] options, final boolean multi) {
        if (options == null || options.length == 0) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] picked = new String[1];
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (multi) {
                    final boolean[] sel = new boolean[options.length];
                    new AlertDialog.Builder(MainActivity.this, R.style.DshDialog)
                            .setTitle(title)
                            .setMultiChoiceItems(options, sel,
                                    new android.content.DialogInterface.OnMultiChoiceClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d,
                                                                      int which, boolean on) {
                                            sel[which] = on;
                                        }
                                    })
                            .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < options.length; i++) {
                                        if (!sel[i]) continue;
                                        if (sb.length() > 0) sb.append(" / ");
                                        sb.append(options[i]);
                                    }
                                    picked[0] = sb.toString();
                                    latch.countDown();
                                }
                            })
                            .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    latch.countDown();
                                }
                            })
                            .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                                @Override public void onCancel(android.content.DialogInterface d) {
                                    latch.countDown();
                                }
                            })
                            .show();
                } else {
                    new AlertDialog.Builder(MainActivity.this, R.style.DshDialog)
                            .setTitle(title)
                            .setItems(options, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    picked[0] = options[which];
                                    latch.countDown();
                                }
                            })
                            .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                                @Override public void onCancel(android.content.DialogInterface d) {
                                    latch.countDown();
                                }
                            })
                            .show();
                }
            }
        });
        try { latch.await(120, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) {}
        return picked[0];
    }

    @Override public boolean confirm(String title, String body) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        runOnUiThread(new Runnable() {
            @Override public void run() {
                TextView msg = new TextView(MainActivity.this);
                msg.setText(body);
                msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                msg.setTypeface(Typeface.MONOSPACE);
                msg.setTextColor(getColor(R.color.label_primary));
                msg.setPadding(dp(20), dp(12), dp(20), dp(0));
                msg.setTextIsSelectable(true);

                new AlertDialog.Builder(MainActivity.this, R.style.DshDialog)
                        .setTitle(title)
                        .setView(msg)
                        .setPositiveButton("执行", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                ok[0] = true;
                                latch.countDown();
                            }
                        })
                        .setNegativeButton("拒绝", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                latch.countDown();
                            }
                        })
                        .setCancelable(false)
                        .show();
            }
        });
        try { latch.await(180, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = intent == null ? null : intent.getStringExtra("prompt");
        if (p != null && !p.trim().isEmpty()) {
            input.setText(p.trim());
            // 忙的时候也要交给 onSend：它自己判断是"插话"还是"发送"
            onSend();
        }
    }

    @Override
    protected void onResume() {
        retryImport();
        super.onResume();
        refreshMeta();
    }

    @Override
    protected void onDestroy() {
        if (agent != null) agent.cancel();
        super.onDestroy();
    }
}
