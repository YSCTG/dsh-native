package com.dshnative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * 把产出物渲染成图，交给模型**自己看**。
 *
 * 为什么要它：模型写完排版只能靠猜，以前它想看效果就去起静态服务器，
 * 命令永不返回，整轮挂死。现在给一个直接渲染的入口：
 *   pdf   → PdfRenderer 渲染指定页
 *   pptx  → 读 office_write 顺手写下的 *.preview.json，用 Canvas 直接画
 *   html  → 内置 WebView 整页截图
 * 渲染出的 PNG 作为图片消息进入下一轮，模型能真的"看见"自己排的版。
 */
public final class Preview {

    private Preview() {}

    /**
     * 一次渲染多页，拼成一张对照图。
     *
     * 模型检查多页排版时，一页一次调用太贵也太慢；这里把 N 页排成网格
     * （两列），每页下方标页码，一眼能看出哪里对不齐。
     */
    public static String renderMulti(Context c, File src, String spec, File out) {
        String name = src.getName().toLowerCase();
        if (!name.endsWith(".pdf")) {
            return "多页预览只支持 pdf；pptx 本身就是整份拼图，用 page 参数即可。";
        }
        ParcelFileDescriptor pfd = null;
        PdfRenderer pr = null;
        try {
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY);
            pr = new PdfRenderer(pfd);
            int total = pr.getPageCount();
            if (total == 0) return "PDF 里没有页面";

            java.util.List<Integer> idx = pick(total, spec);
            if (idx.isEmpty()) return "没有匹配的页码（总页数 " + total + "）";

            int cols = idx.size() == 1 ? 1 : 2;
            int cellW = 560, gap = 20, labelH = 26;
            int rows = (idx.size() + cols - 1) / cols;

            // 先用第一页算出单元高度（按 A4 比例，避免各页尺寸不同拼歪）
            PdfRenderer.Page probe = pr.openPage(idx.get(0));
            int cellH = (int) ((float) probe.getHeight() / probe.getWidth() * cellW);
            probe.close();

            int w = cols * cellW + (cols + 1) * gap;
            int h = rows * (cellH + labelH) + (rows + 1) * gap;
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            cv.drawColor(0xFF303030);

            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextSize(15);
            label.setColor(0xFFDDDDDD);

            for (int i = 0; i < idx.size(); i++) {
                int pageNo = idx.get(i);
                int cx = gap + (i % cols) * (cellW + gap);
                int cy = gap + (i / cols) * (cellH + labelH + gap);
                PdfRenderer.Page pg = pr.openPage(pageNo);
                Bitmap pb = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888);
                pb.eraseColor(Color.WHITE);
                // render 到目标尺寸
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale((float) cellW / pg.getWidth(), (float) cellH / pg.getHeight());
                pg.render(pb, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pg.close();
                cv.drawBitmap(pb, cx, cy, null);
                pb.recycle();
                cv.drawText("第 " + (pageNo + 1) + " 页", cx + 4, cy + cellH + 18, label);
            }
            save(bm, out);
            return null;
        } catch (Throwable e) {
            return "渲染失败：" + e.getMessage();
        } finally {
            try { if (pr != null) pr.close(); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
    }

    /** 解析 "1-3" / "1,3,5" / "all" / 单个数字（页码从 1 开始）。 */
    private static java.util.List<Integer> pick(int total, String spec) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String s = spec == null ? "" : spec.trim().toLowerCase();
        if (s.isEmpty() || s.equals("all")) {
            for (int i = 0; i < Math.min(total, 6); i++) out.add(i);
            return out;
        }
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        if (i >= 1 && i <= total && out.size() < 12) out.add(i - 1);
                    }
                } else {
                    int a = Integer.parseInt(part);
                    if (a >= 1 && a <= total && out.size() < 12) out.add(a - 1);
                }
            } catch (Throwable ignored) {}
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 渲染一页到 PNG。成功返回 null，失败返回错误信息。 */
    public static String render(Context c, File src, int page, File out) {
        String name = src.getName().toLowerCase();
        if (name.endsWith(".pdf")) return pdf(src, page, out);
        if (name.endsWith(".pptx")) return pptx(src, out);
        if (name.endsWith(".html") || name.endsWith(".htm")) return html(c, src, out);
        if (name.endsWith(".docx")) return renderDocx(src, out);
        if (name.endsWith(".xlsx")) return renderXlsx(src, out);
        return "这类文件无法渲染预览（支持 pdf / html / pptx / docx / xlsx）。";
    }

    private static String pdf(File src, int page, File out) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer pr = null;
        try {
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY);
            pr = new PdfRenderer(pfd);
            int total = pr.getPageCount();
            if (total == 0) return "PDF 里没有页面";
            int idx = Math.max(0, Math.min(page - 1, total - 1));
            PdfRenderer.Page pg = pr.openPage(idx);
            int w = Math.min(pg.getWidth() * 2, 1600);
            int h = (int) ((float) pg.getHeight() / pg.getWidth() * w);
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pg.close();
            save(bm, out);
            return null;
        } catch (Throwable e) {
            return "渲染 PDF 失败：" + e.getMessage();
        } finally {
            try { if (pr != null) pr.close(); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * pptx：读同目录的 *.preview.json，把所有幻灯片竖着拼成一张图。
     *
     * 不走 WebView 截图——隐藏的 WebView 是硬件图层，draw 到 Bitmap 上是空白，
     * 这条路上已经踩过一次坑。
     */
    private static String pptx(File src, File out) {
        File side = new File(src.getParentFile(),
                src.getName().replaceAll("\\.[^.]+$", "") + ".preview.json");
        if (!side.isFile()) {
            return "这份 pptx 没有配套预览（旧版本生成的）。用 office_write 重新生成一次即可。";
        }
        try {
            JSONObject spec = new JSONObject(readText(side));
            JSONArray slides = spec.optJSONArray("slides");
            if (slides == null || slides.length() == 0) return "预览数据里没有幻灯片";
            int sw = 960, sh = 540, gap = 16;
            int n = slides.length();
            int width = sw;
            int height = n * sh + (n - 1) * gap;

            int accent = Color.parseColor(spec.optString("accent", "#2F6FEB"));
            int ink = Color.parseColor(spec.optString("ink", "#111418"));
            int muted = Color.parseColor(spec.optString("muted", "#6B7280"));
            int pageBg = Color.parseColor(spec.optString("page", "#FFFFFF"));

            Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            cv.drawColor(0xFF222222);

            Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
            pTitle.setTextSize(38);
            pTitle.setColor(accent);
            pTitle.setFakeBoldText(true);
            pTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

            Paint pBody = new Paint(Paint.ANTI_ALIAS_FLAG);
            pBody.setTextSize(22);
            pBody.setColor(ink);

            Paint pNo = new Paint(Paint.ANTI_ALIAS_FLAG);
            pNo.setTextSize(14);
            pNo.setColor(muted);

            Paint band = new Paint();
            band.setColor(accent);

            for (int i = 0; i < n; i++) {
                JSONObject s = slides.optJSONObject(i);
                if (s == null) continue;
                int top = i * (sh + gap);
                cv.drawRect(0, top, sw, top + sh, fill(pageBg));
                cv.drawRect(0, top, sw, top + 8, band);
                cv.drawText(clip(s.optString("title", ""), pTitle, sw - 128),
                        64, top + 96, pTitle);
                // 图片：画在右侧，等比缩进 340×250 的框里
                String imgFile = s.optString("imageFile", "");
                if (!imgFile.isEmpty()) {
                    Bitmap pic = android.graphics.BitmapFactory.decodeFile(imgFile);
                    if (pic != null) {
                        int bx = sw - 380, by = top + 160, bw = 316, bh = 240;
                        float scale = Math.min((float) bw / pic.getWidth(),
                                (float) bh / pic.getHeight());
                        int dw = Math.max(1, (int) (pic.getWidth() * scale));
                        int dh = Math.max(1, (int) (pic.getHeight() * scale));
                        android.graphics.Rect dst = new android.graphics.Rect(
                                bx + (bw - dw) / 2, by + (bh - dh) / 2,
                                bx + (bw + dw) / 2, by + (bh + dh) / 2);
                        cv.drawBitmap(pic, null, dst, null);
                        pic.recycle();
                    }
                }
                JSONArray bullets = s.optJSONArray("bullets");
                float y = top + 150;
                if (bullets != null) {
                    for (int k = 0; k < bullets.length(); k++) {
                        if (y > top + sh - 40) break;
                        cv.drawText("• " + clip(bullets.optString(k, ""), pBody, sw - 150),
                                92, y, pBody);
                        y += 38;
                    }
                }
                String no = (i + 1) + " / " + n;
                cv.drawText(no, sw - 48 - pNo.measureText(no), top + sh - 28, pNo);
            }
            save(bm, out);
            return null;
        } catch (Throwable e) {
            return "渲染 pptx 失败：" + e.getMessage();
        }
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static String clip(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static void save(Bitmap bm, File out) throws Exception {
        FileOutputStream os = new FileOutputStream(out);
        bm.compress(Bitmap.CompressFormat.PNG, 92, os);
        os.close();
        bm.recycle();
    }


    // ---------------- docx / xlsx：自己解析，用 Canvas 画 ----------------
    //
    // 不走 WebView 截图：隐藏的 WebView 是硬件图层，draw 出来是白图（踩过）。
    // 这两类文件结构简单，直接按段落/单元格画更可控。

    /** docx：段落 + 表格，按 A4 宽度连续排，超长自动拉高。 */
    public static String renderDocx(File src, File out) {
        try {
            String xml = readZipEntry(src, "word/document.xml");
            if (xml == null) return "这个 docx 里没有 word/document.xml";
            java.util.List<float[]> dummy = null;   // 占位，保持结构清晰

            int w = 1240, margin = 100;
            int h = 1754;                    // A4 @ 2x
            java.util.List<Object[]> blocks = parseDocx(xml);

            Bitmap bm = Bitmap.createBitmap(w, Math.max(h, blocks.size() * 40 + 200),
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            cv.drawColor(Color.WHITE);

            Paint titleP = new Paint(Paint.ANTI_ALIAS_FLAG);
            titleP.setTextSize(46);
            titleP.setFakeBoldText(true);
            titleP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            Paint bodyP = new Paint(Paint.ANTI_ALIAS_FLAG);
            bodyP.setTextSize(28);
            bodyP.setColor(0xFF111418);
            Paint smallP = new Paint(Paint.ANTI_ALIAS_FLAG);
            smallP.setTextSize(24);
            smallP.setColor(0xFF333333);
            Paint line = new Paint();
            line.setColor(0xFFDDDDDD);
            line.setStrokeWidth(1.4f);

            float y = margin + 40;
            for (Object[] b : blocks) {
                String type = (String) b[0];
                if ("table".equals(type)) {
                    java.util.List<java.util.List<String>> rows =
                            (java.util.List<java.util.List<String>>) b[1];
                    int cols = 0;
                    for (java.util.List<String> r : rows) cols = Math.max(cols, r.size());
                    if (cols == 0) continue;
                    float cw = (w - margin * 2f) / cols;
                    for (int ri = 0; ri < rows.size(); ri++) {
                        java.util.List<String> r = rows.get(ri);
                        if (ri == 0) {
                            Paint head = new Paint();
                            head.setColor(0xFFF3F4F6);
                            cv.drawRect(margin, y - 26, w - margin, y + 6, head);
                        }
                        for (int ci = 0; ci < r.size(); ci++) {
                            cv.drawText(clip(r.get(ci), smallP, cw - 16),
                                    margin + ci * cw + 8, y, smallP);
                        }
                        y += 34;
                        cv.drawLine(margin, y - 22, w - margin, y - 22, line);
                    }
                    y += 24;
                } else {
                    String text = (String) b[1];
                    boolean bold = b.length > 2 && Boolean.TRUE.equals(b[2]);
                    boolean big = b.length > 3 && Boolean.TRUE.equals(b[3]);
                    Paint p = bold || big ? (Paint) titleP : bodyP;
                    float size = big ? 40 : (bold ? 32 : 28);
                    p.setTextSize(size);
                    for (String ln : wrapText(text, p, w - margin * 2)) {
                        cv.drawText(ln, margin, y, p);
                        y += size * 1.5f;
                    }
                    y += big ? 24 : 10;
                }
            }
            save(bm, out);
            return null;
        } catch (Throwable e) {
            return "渲染 docx 失败：" + e.getMessage();
        }
    }

    /** xlsx：第一张工作表，画成网格。 */
    public static String renderXlsx(File src, File out) {
        try {
            java.util.List<String> shared = new java.util.ArrayList<>();
            String ss = readZipEntry(src, "xl/sharedStrings.xml");
            if (ss != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<si>(.*?)</si>", java.util.regex.Pattern.DOTALL).matcher(ss);
                while (m.find()) shared.add(stripTags(m.group(1)));
            }
            String sheet = readZipEntry(src, "xl/worksheets/sheet1.xml");
            if (sheet == null) return "这个 xlsx 里没有第一张工作表";

            java.util.List<java.util.List<String>> grid = new java.util.ArrayList<>();
            java.util.regex.Matcher rm = java.util.regex.Pattern
                    .compile("<row[^>]*>(.*?)</row>", java.util.regex.Pattern.DOTALL).matcher(sheet);
            while (rm.find()) {
                java.util.List<String> row = new java.util.ArrayList<>();
                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("<c([^>]*)>(.*?)</c>", java.util.regex.Pattern.DOTALL).matcher(rm.group(1));
                while (cm.find()) {
                    String attrs = cm.group(1);
                    String body = cm.group(2);
                    boolean isShared = attrs.contains("t=\"s\"");
                    // 三种存法都要认：共享字符串(t="s")、内联字符串(<is><t>)、数值(<v>)
                    String val = "";
                    java.util.regex.Matcher im = java.util.regex.Pattern
                            .compile("<is>\\s*<t(?:\\s[^>]*)?>(.*?)</t>", java.util.regex.Pattern.DOTALL)
                            .matcher(body);
                    if (im.find()) {
                        val = im.group(1);
                    } else {
                        java.util.regex.Matcher vm = java.util.regex.Pattern
                                .compile("<v>(.*?)</v>", java.util.regex.Pattern.DOTALL).matcher(body);
                        val = vm.find() ? vm.group(1) : "";
                        if (isShared) {
                            try {
                                int i = Integer.parseInt(val.trim());
                                val = i >= 0 && i < shared.size() ? shared.get(i) : val;
                            } catch (Throwable ignored) {}
                        }
                    }
                    val = val.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                    row.add(val);
                }
                if (!row.isEmpty()) grid.add(row);
            }
            if (grid.isEmpty()) return "工作表是空的";

            int cols = 0;
            for (java.util.List<String> r : grid) cols = Math.max(cols, r.size());
            int cw = 190, rh = 40, margin = 40;
            int w = margin * 2 + cols * cw;
            int h = margin * 2 + grid.size() * rh;
            Bitmap bm = Bitmap.createBitmap(Math.min(w, 4000), Math.min(h, 6000),
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            cv.drawColor(Color.WHITE);

            Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
            cell.setTextSize(22);
            cell.setColor(0xFF111418);
            Paint gridLine = new Paint();
            gridLine.setColor(0xFFE0E0E0);
            gridLine.setStrokeWidth(1f);
            Paint head = new Paint();
            head.setColor(0xFFF0F2F5);

            for (int ri = 0; ri < grid.size(); ri++) {
                java.util.List<String> r = grid.get(ri);
                float y = margin + ri * rh;
                if (ri == 0) cv.drawRect(margin, y, margin + cols * cw, y + rh, head);
                for (int ci = 0; ci < cols; ci++) {
                    String v = ci < r.size() ? r.get(ci) : "";
                    cv.drawText(clip(v, cell, cw - 12), margin + ci * cw + 6, y + rh - 12, cell);
                }
                cv.drawLine(margin, y, margin + cols * cw, y, gridLine);
            }
            for (int ci = 0; ci <= cols; ci++) {
                cv.drawLine(margin + ci * cw, margin, margin + ci * cw,
                        margin + grid.size() * rh, gridLine);
            }
            save(bm, out);
            return null;
        } catch (Throwable e) {
            return "渲染 xlsx 失败：" + e.getMessage();
        }
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "");
    }

    private static java.util.List<String> wrapText(String text, Paint p, float max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) { out.add(""); return out; }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            if (p.measureText(cur.toString()) > max) {
                cur.setLength(cur.length() - 1);
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** docx 的 document.xml → [{type, ...}]，只取段落和表格。 */
    private static java.util.List<Object[]> parseDocx(String xml) {
        java.util.List<Object[]> blocks = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<w:(p|tbl)[ >].*?</w:\\1>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String chunk = m.group();
            if (chunk.startsWith("<w:tbl")) {
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("<w:tr[ >].*?</w:tr>", java.util.regex.Pattern.DOTALL).matcher(chunk);
                while (rm.find()) {
                    java.util.List<String> cells = new java.util.ArrayList<>();
                    java.util.regex.Matcher cm = java.util.regex.Pattern
                            .compile("<w:tc[ >].*?</w:tc>", java.util.regex.Pattern.DOTALL).matcher(rm.group());
                    while (cm.find()) cells.add(textOf(cm.group()));
                    if (!cells.isEmpty()) rows.add(cells);
                }
                if (!rows.isEmpty()) blocks.add(new Object[]{"table", rows});
            } else {
                String text = textOf(chunk);
                if (text.isEmpty()) continue;
                boolean bold = chunk.contains("<w:b/>");
                boolean big = false;
                java.util.regex.Matcher sm = java.util.regex.Pattern
                        .compile("<w:sz w:val=\"(\\d+)\"").matcher(chunk);
                if (sm.find()) {
                    try { big = Integer.parseInt(sm.group(1)) >= 30; } catch (Throwable ignored) {}
                }
                blocks.add(new Object[]{"p", text, bold, big});
            }
        }
        return blocks;
    }

    private static String textOf(String chunk) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", java.util.regex.Pattern.DOTALL).matcher(chunk);
        while (tm.find()) sb.append(tm.group(1));
        return sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    private static String readZipEntry(File zipF, String name) {
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(zipF);
            java.util.zip.ZipEntry e = z.getEntry(name);
            if (e == null) { z.close(); return null; }
            java.io.InputStream in = z.getInputStream(e);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            z.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** HTML：在内置 WebView 里加载后整页截图（失败时如实回报）。 */
    private static String html(final Context c, File src, final File out) {
        String url = "file://" + src.getAbsolutePath();
        String opened = Browser.open(url, 25);
        if (opened != null && opened.startsWith("加载失败")) return opened;

        final Bitmap[] shot = new Bitmap[1];
        final String[] err = new String[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.webkit.WebView w = Browser.viewForCapture();
                    if (w == null) { err[0] = "没有可用的浏览器视图"; return; }
                    w.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                    int width = 960;
                    w.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                                    width, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(
                                    0, android.view.View.MeasureSpec.UNSPECIFIED));
                    int contentH = (int) (w.getContentHeight() * Math.max(w.getScale(), 1f));
                    if (contentH <= 0) {
                        contentH = (int) (w.getMeasuredHeight() * Math.max(w.getScale(), 1f));
                    }
                    int height = Math.min(Math.max(contentH, 480), 4000);
                    w.layout(0, 0, width, height);
                    Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(bm);
                    cv.drawColor(Color.WHITE);
                    w.draw(cv);
                    shot[0] = bm;
                } catch (Throwable e) {
                    err[0] = "截图失败：" + e.getMessage();
                } finally {
                    done.countDown();
                }
            }
        });
        try { done.await(); } catch (InterruptedException ignored) {}
        if (err[0] != null) return err[0];
        if (shot[0] == null) return "截图失败：空图";
        try {
            save(shot[0], out);
            return null;
        } catch (Throwable e) {
            return "保存失败：" + e.getMessage();
        }
    }
}
