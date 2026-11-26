package com.dshnative;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 「怎么拿到 API key」的说明。
 *
 * 为什么要有：填 key 的地方只写了一个 `sk-…` 占位符，对没用过 AI 接口的人来说，
 * 那等于让人自己猜。这里把从注册到复制 key 的步骤写清楚，并给一个直达按钮。
 *
 * 刻意不写"技术术语"：不提 API、token、endpoint，只说"登录 → 创建 → 复制以 sk- 开头的那串"。
 */
final class KeyHelp {

    static final String KEYS_URL = "https://platform.deepseek.com/api_keys";

    private KeyHelp() {}

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static void show(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4));

        box.addView(line(a,
                "三步拿到 key",
                "DeepSeek 的 key 是一串以 sk- 开头的字符，作用类似密码：拿着它就能用你的额度。",
                15, R.color.label_primary, true));

        String[][] steps = {
                {"1", "打开网站", "点下面的「打开获取页面」，用手机号注册或登录（不用翻墙）。"},
                {"2", "创建一个 key", "左侧菜单找「API keys」→ 点「创建 API key」→ 名字随便填，比如 dsh。"},
                {"3", "复制那串字符", "生成后会出现 sk-… 一直到最后，点复制。注意：它只完整显示一次，关掉就看不到了。"},
        };
        for (String[] s : steps) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(a, 10), 0, dp(a, 2));

            TextView num = new TextView(a);
            num.setText(s[0]);
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            num.setTextColor(a.getColor(R.color.brand_fg));
            num.setGravity(Gravity.CENTER);
            num.setBackgroundResource(R.drawable.bg_chip);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
            nlp.rightMargin = dp(a, 10);
            row.addView(num, nlp);

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(a);
            t.setText(s[1]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(a.getColor(R.color.label_primary));
            texts.addView(t);
            TextView d = new TextView(a);
            d.setText(s[2]);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            d.setTextColor(a.getColor(R.color.label_secondary));
            d.setPadding(0, dp(a, 2), 0, 0);
            texts.addView(d);
            row.addView(texts, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(row);
        }

        box.addView(divider(a));
        box.addView(line(a, "要花钱吗",
                "按用量计费，没有月费。第一次充值最低几块钱，用来聊天、读文件能撑很久；"
                        + "具体价格在网站「充值」页面能看到。", 12, R.color.label_secondary, false));
        box.addView(line(a, "注意",
                "· key 等于密码，别发给别人、别贴到公开的地方\n"
                        + "· 丢了就直接新建一个，旧的可以删掉\n"
                        + "· 这 App 把 key 加密存在本机，不会上传到别处",
                12, R.color.label_secondary, false));

        ScrollView sc = new ScrollView(a);
        sc.addView(box);

        new android.app.AlertDialog.Builder(a, R.style.DshDialog)
                .setTitle("怎么获取 API key")
                .setView(sc)
                .setPositiveButton("打开获取页面", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KEYS_URL)));
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(a, "打不开浏览器：" + t.getMessage(),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private static TextView line(Activity a, String title, String body,
                                 int titleSp, int colorRes, boolean lead) {
        TextView tv = new TextView(a);
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) sb.append(title).append("\n");
        sb.append(body);
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        tv.setTextColor(a.getColor(colorRes));
        tv.setLineSpacing(dp(a, 2), 1f);
        tv.setPadding(0, dp(a, lead ? 2 : 10), 0, dp(a, lead ? 8 : 2));
        return tv;
    }

    private static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(a.getColor(R.color.border_l1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1));
        lp.topMargin = dp(a, 10);
        lp.bottomMargin = dp(a, 6);
        v.setLayoutParams(lp);
        return v;
    }
}
