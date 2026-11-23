package com.dshnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown 渲染：只做桌面版对话里实际会出现的那几种。
 *
 * 支持：围栏代码块、行内 code、**粗体**、# / ## / ### 标题、- 列表。
 * 不做：链接、表格、图片、嵌套引用 —— 手机上不需要，做了也不像。
 *
 * 流式场景会反复调用（每次增量重渲染），所以这里必须便宜：单趟扫描，不建正则。
 *
 * 代码块的背景用 LineBackgroundSpan 画整行矩形 —— 用 BackgroundColorSpan 只会给字形上色，
 * 短行会变成斑马纹，看起来不像一块代码。
 */
public final class Markdown {

    private Markdown() {}

    public static CharSequence render(Context c, String src) {
        int codeBg = c.getColor(R.color.bg_subtle);
        int codeInlineBg = c.getColor(R.color.bg_code_inline);

        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null || src.isEmpty()) return out;

        String[] lines = src.split("\n", -1);
        boolean inCode = false;
        List<int[]> codeRanges = new ArrayList<>();

        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                } else {
                    inCode = false;
                    styleCodeBlock(out, codeRanges, codeBg);
                    codeRanges.clear();
                    out.append('\n');
                }
                continue;
            }

            if (inCode) {
                int s = out.length();
                // 行首补两个空格当内边距：整行背景是从 left 画的，不补字就贴在色块边缘上
                out.append("  ").append(raw).append('\n');
                codeRanges.add(new int[]{s, out.length()});
                continue;
            }

            int start = out.length();

            // 标题
            int hash = 0;
            while (hash < t.length() && t.charAt(hash) == '#') hash++;
            if (hash > 0 && hash <= 4 && t.length() > hash && t.charAt(hash) == ' ') {
                appendInline(out, t.substring(hash + 1), codeInlineBg);
                out.append('\n');
                int end = out.length();
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                float scale = hash == 1 ? 1.24f : hash == 2 ? 1.14f : hash == 3 ? 1.07f : 1.0f;
                if (scale != 1.0f) {
                    out.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }

            // 列表：保留一层缩进，标记换成中点，省得每行都吃一个字符宽度
            if (t.startsWith("- ") || t.startsWith("* ")) {
                int indent = raw.indexOf(t.charAt(0));
                for (int i = 0; i < Math.min(indent, 6); i++) out.append(' ');
                out.append("· ");
                appendInline(out, t.substring(2), codeInlineBg);
                out.append('\n');
                continue;
            }

            appendInline(out, raw, codeInlineBg);
            out.append('\n');
        }

        // 流式时可能还没等到收尾的 ```，先按代码样式画，别等
        if (inCode) styleCodeBlock(out, codeRanges, codeBg);

        while (out.length() > 1 && out.charAt(out.length() - 1) == '\n'
                && out.charAt(out.length() - 2) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static void styleCodeBlock(SpannableStringBuilder out, List<int[]> ranges, int bg) {
        for (int[] r : ranges) {
            if (r[1] <= r[0]) continue;
            out.setSpan(new TypefaceSpan("monospace"), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.86f), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new CodeLineBg(bg), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 整行铺底色，短行也不会只剩一条斑马纹。 */
    private static final class CodeLineBg implements LineBackgroundSpan {
        private final int color;
        CodeLineBg(int color) { this.color = color; }

        @Override public void drawBackground(Canvas c, Paint p, int left, int right, int top,
                                             int baseline, int bottom, CharSequence text,
                                             int start, int end, int lnum) {
            int old = p.getColor();
            p.setColor(color);
            c.drawRect(left, top - 2, right, bottom + 2, p);
            p.setColor(old);
        }
    }

    private static void appendInline(SpannableStringBuilder out, String s, int codeBg) {
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);

            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = out.length();
                    out.append(s, i + 1, end);
                    int en = out.length();
                    out.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBg), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.92f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (ch == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = out.length();
                    out.append(s, i + 2, end);
                    int en = out.length();
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            out.append(ch);
            i++;
        }
    }
}
