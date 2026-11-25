package com.dshnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 待办面板。
 *
 * 从 MainActivity 里抽出来的：它只依赖"某个会话的待办列表"和主题色，与聊天流、
 * 抽屉、附件都无关，抽出来以后 MainActivity 那段一百多行的视图拼装就没有了。
 */
public final class TodoSheet {

    private TodoSheet() {}

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /**
     * 造一个待办对话框；没有待办时返回 null（由调用方决定提示文案）。
     * 返回的对话框还没有 show()，调用方自己管生命周期。
     */
    public static AlertDialog build(Activity a, String sessionId) {
        List<Todo.Item> items = Todo.load(a, sessionId == null ? "" : sessionId);
        if (items.isEmpty()) return null;

        int done = 0;
        for (Todo.Item it : items) if ("done".equals(it.status)) done++;

        ScrollView sc = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 16), pad, dp(a, 8));

        // 头部：标题 + 完成度
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t1 = new TextView(a);
        t1.setText("待办");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t1.setTextColor(a.getColor(R.color.label_primary));
        t1.setTypeface(t1.getTypeface(), Typeface.BOLD);
        head.addView(t1, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t2 = new TextView(a);
        t2.setText(done + " / " + items.size());
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t2.setTextColor(a.getColor(R.color.label_tertiary));
        head.addView(t2);
        box.addView(head);

        // 进度条：两条 View 叠出来的细条，比 ProgressBar 更贴合界面
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(a.getColor(R.color.border_l2));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 4));
        barLp.topMargin = dp(a, 12);
        barLp.bottomMargin = dp(a, 18);
        box.addView(bar, barLp);
        View fill = new View(a);
        fill.setBackgroundColor(a.getColor(R.color.accent));
        bar.addView(fill, new LinearLayout.LayoutParams(0, dp(a, 4),
                items.isEmpty() ? 0f : (float) done / items.size()));
        View rest = new View(a);
        bar.addView(rest, new LinearLayout.LayoutParams(0, dp(a, 4),
                items.isEmpty() ? 1f : 1f - (float) done / items.size()));

        // 每项一行：圆点 + 文本 + 右侧状态
        for (Todo.Item it : items) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(a, 9), 0, dp(a, 9));

            View dot = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            boolean isDone = "done".equals(it.status);
            boolean isDoing = "doing".equals(it.status);
            g.setColor(isDone ? a.getColor(R.color.success)
                    : (isDoing ? a.getColor(R.color.accent) : a.getColor(R.color.border_l3)));
            dot.setBackground(g);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(a, 9), dp(a, 9));
            dotLp.topMargin = dp(a, 6);
            dotLp.rightMargin = dp(a, 10);
            row.addView(dot, dotLp);

            TextView tx = new TextView(a);
            tx.setText(it.text);
            tx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            if (isDone) {
                tx.setTextColor(a.getColor(R.color.label_caption));
                tx.setPaintFlags(tx.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else if (isDoing) {
                tx.setTextColor(a.getColor(R.color.label_primary));
                tx.setTypeface(tx.getTypeface(), Typeface.BOLD);
            } else {
                tx.setTextColor(a.getColor(R.color.label_secondary));
            }
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (isDoing || isDone) {
                TextView tag = new TextView(a);
                tag.setText(isDoing ? "在做" : "完成");
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                tag.setTextColor(isDoing ? a.getColor(R.color.accent)
                        : a.getColor(R.color.label_caption));
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                tagLp.leftMargin = dp(a, 8);
                tagLp.topMargin = dp(a, 2);
                row.addView(tag, tagLp);
            }
            box.addView(row);
        }
        sc.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(a, R.style.DshDialog)
                .setView(sc)
                .setPositiveButton("关闭", null)
                .create();
        try {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_sheet);
        } catch (Throwable ignored) {}
        return dlg;
    }
}
