package com.dshnative;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读现有 Office 文件。
 *
 * 为什么需要：Native 的 office_write 只能"从零生成"，而电脑版（LibreOffice Kit 加持）
 * 能打开一份已有的 docx/xlsx/pptx 看结构、改内容。手机端做不到完整渲染，
 * 但**把结构和文字读出来**是可行且最有用的那一半——模型知道里面有什么，才能改。
 *
 * 这里只做只读提取：段落/表格/工作表/幻灯片文字。不做版式推断，也不假装能还原样式。
 */
public final class OfficeRead {

    private OfficeRead() {}

    /** 按扩展名分派，返回一份可直接进上下文的摘要。 */
    public static String summary(File f, int maxChars) {
        if (f == null || !f.isFile()) return "错误：文件不存在";
        String n = f.getName().toLowerCase();
        try {
            String body;
            if (n.endsWith(".docx")) body = docx(f);
            else if (n.endsWith(".xlsx")) body = xlsx(f);
            else if (n.endsWith(".pptx")) body = pptx(f);
            else return "错误：只支持 .docx / .xlsx / .pptx（PDF 与旧 .doc 需要电脑版）";
            if (body == null) return "错误：解析失败（文件可能损坏或不是 OOXML）";
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars) + "\n…（已截断，" + body.length() + " 字符）";
            }
            return f.getName() + "  " + (f.length() / 1024) + " KB\n" + body;
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    // ---------------- docx ----------------

    private static String docx(File f) {
        String xml = entry(f, "word/document.xml");
        if (xml == null) return null;
        StringBuilder sb = new StringBuilder();
        // 段落：把 <w:p> 内的 <w:t> 拼起来，一行一段
        Matcher p = Pattern.compile("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", Pattern.DOTALL).matcher(xml);
        int paras = 0, tables = 0;
        StringBuilder lines = new StringBuilder();
        while (p.find()) {
            String inner = p.group(1);
            if (inner.contains("<w:drawing")) lines.append("[图片]");
            Matcher t = Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", Pattern.DOTALL).matcher(inner);
            StringBuilder one = new StringBuilder();
            while (t.find()) one.append(unescape(t.group(1)));
            if (one.length() > 0) {
                lines.append(one).append('\n');
                paras++;
            } else if (!inner.contains("<w:drawing")) {
                lines.append('\n');   // 空段落保留节奏
            }
        }
        tables = count(xml, "<w:tbl>");
        // 顺带报告文档里定义的段落样式（restyle 的前置信息：模型得先知道有什么样式）
        String st = entry(f, "word/styles.xml");
        if (st != null) {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            Matcher sm = Pattern.compile("<w:style[^>]*w:styleId=\"([^\"]+)\"").matcher(st);
            while (sm.find()) ids.add(sm.group(1));
            if (!ids.isEmpty()) {
                sb.append("样式：").append(String.join("、", ids)).append('\n');
            }
        }
        sb.append("段落：").append(paras).append("　表格：").append(tables).append('\n');
        sb.append("---- 正文 ----\n").append(lines);
        return sb.toString();
    }

    // ---------------- xlsx ----------------

    private static String xlsx(File f) {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> shared = sharedStrings(f);
        java.util.List<String> sheets = zip_names(f, "xl/worksheets/sheet");
        if (sheets.isEmpty()) return null;
        sb.append("工作表：").append(sheets.size()).append('\n');
        int shown = 0;
        for (String name : sheets) {
            if (shown >= 3) { sb.append("…（只显示前 3 个工作表）\n"); break; }
            String xml = entry(f, name);
            if (xml == null) continue;
            sb.append("\n== ").append(name).append(" ==\n");
            Matcher row = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(xml);
            int r = 0;
            while (row.find() && r < 30) {
                StringBuilder line = new StringBuilder();
                Matcher c = Pattern.compile("<c([^>]*)>(.*?)</c>", Pattern.DOTALL).matcher(row.group(1));
                while (c.find()) {
                    String attrs = c.group(1);
                    String inner = c.group(2);
                    String v = first(inner, "<v>(.*?)</v>");
                    String fm = first(inner, "<f>(.*?)</f>");
                    String is = first(inner, "<is>.*?<t[^>]*>(.*?)</t>");
                    String text = v;
                    if (is != null) text = unescape(is);
                    else if (v != null && attrs.contains("t=\"s\"")) {
                        try { text = shared.get(Integer.parseInt(v.trim())); } catch (Throwable ignored) {}
                    }
                    if (fm != null) {
                        line.append('=').append(unescape(fm));
                        if (text != null && !text.equals(fm)) line.append(" →").append(text);
                    } else if (text != null) {
                        line.append(unescape(text));
                    }
                    line.append(" | ");
                }
                if (line.length() > 3) { sb.append("r").append(r + 1).append(": ").append(line).append('\n'); r++; }
            }
            shown++;
        }
        return sb.toString();
    }

    private static java.util.List<String> sharedStrings(File f) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String xml = entry(f, "xl/sharedStrings.xml");
        if (xml == null) return out;
        Matcher m = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            StringBuilder one = new StringBuilder();
            Matcher t = Pattern.compile("<t(?:\\s[^>]*)?>(.*?)</t>", Pattern.DOTALL).matcher(m.group(1));
            while (t.find()) one.append(unescape(t.group(1)));
            out.add(one.toString());
        }
        return out;
    }

    // ---------------- pptx ----------------

    private static String pptx(File f) {
        java.util.List<String> slides = zip_names(f, "ppt/slides/slide");
        if (slides.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("幻灯片：").append(slides.size()).append('\n');
        int n = 0;
        for (String name : slides) {
            if (n >= 12) { sb.append("…（只显示前 12 张）\n"); break; }
            String xml = entry(f, name);
            if (xml == null) continue;
            sb.append("\n== 第 ").append(n + 1).append(" 张 ==\n");
            Matcher t = Pattern.compile("<a:t>(.*?)</a:t>", Pattern.DOTALL).matcher(xml);
            StringBuilder one = new StringBuilder();
            while (t.find()) one.append(unescape(t.group(1))).append('\n');
            if (one.length() == 0) sb.append("（无文字，可能是纯图片/图表）\n");
            else sb.append(one);
            n++;
        }
        return sb.toString();
    }

    // ---------------- 工具 ----------------

    private static String entry(File f, String name) {
        try {
            ZipFile z = new ZipFile(f);
            ZipEntry e = z.getEntry(name);
            if (e == null) { z.close(); return null; }
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            z.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.util.List<String> zip_names(File f, String prefix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            ZipFile z = new ZipFile(f);
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            java.util.List<String> hits = new java.util.ArrayList<>();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith(prefix) && n.endsWith(".xml")
                        && n.indexOf('/', prefix.length()) < 0) hits.add(n);
            }
            z.close();
            java.util.Collections.sort(hits);
            out.addAll(hits);
        } catch (Throwable ignored) {}
        return out;
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    private static String first(String s, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
