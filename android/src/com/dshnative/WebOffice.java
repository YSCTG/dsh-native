package com.dshnative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import android.util.Base64;

/**
 * Office 文件的渲染预览。
 *
 * 为什么不内置 LibreOffice：它在这台设备上初始化就 abort（官方 Viewer 同样崩），
 * 而且要背 159 MB。这里换成三条轻得多的路：
 *
 *   docx / xlsx → WebView + docx-preview / SheetJS（assets 里共 1 MB，离线可用）
 *   pdf         → 系统自带的 PdfRenderer（渲染成位图，零额外体积、无 JS）
 *   pptx        → 结构解析后按页列文字大纲（没有可靠的离线渲染器，就不假装能渲染）
 *
 * 决策依据写在 docs/libreoffice-verdict.md。
 */
public final class WebOffice {

    private static final String TAG = "DSHN";
    private static final String BASE = "file:///android_asset/weboffice/";
    /** 内联 base64 的字节上限：再大就别塞进 WebView 了，提示用户用系统应用打开。 */
    private static final int MAX_INLINE = 12 * 1024 * 1024;
    /** PDF 最多渲染几页（每页一张位图，页数多了内存吃不住）。 */
    private static final int MAX_PDF_PAGES = 8;
    /** 打包进 assets 的脚本，读一次缓存住（合计约 1 MB）。 */
    private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>();

    private WebOffice() {}

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }


    /**
     * 取打包好的渲染脚本。
     *
     * 注意：这台设备上 `getAssets().open("子目录/文件")` 会抛 FileNotFoundException
     * （assets/scaffold 那次已经踩过），所以脚本统一塞进顶层 weboffice.zip，
     * 这里一次性解到内存缓存。
     */
    private static String asset(Context c, String name) {
        if (CACHE.isEmpty()) loadZip(c);
        String v = CACHE.get(name);
        return v == null ? "" : v;
    }

    private static void loadZip(Context c) {
        try {
            java.io.InputStream in = c.getAssets().open("weboffice.zip");
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                CACHE.put(e.getName(), bo.toString("UTF-8"));
            }
            zin.close();
            Log.i(TAG, "渲染脚本已就绪: " + CACHE.keySet());
        } catch (Throwable t) {
            Log.i(TAG, "读不到 assets/weboffice.zip: " + t);
        }
    }

    // ---------- 入口 ----------

    /** 这种文件我们能不能自己画出来。 */
    public static boolean canRender(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pdf") || n.endsWith(".pptx");
    }

    /**
     * 造一个预览视图；画不了就返回 null（调用方自己退化成文本或"用系统应用打开"）。
     */
    public static View build(Context c, File f) {
        String n = f.getName().toLowerCase();
        try {
            if (n.endsWith(".docx")) return wordView(c, f);
            if (n.endsWith(".xlsx")) return sheetView(c, f);
            if (n.endsWith(".pdf")) return pdfView(c, f);
            if (n.endsWith(".pptx")) return slideOutline(c, f);
        } catch (Throwable t) {
            Log.i(TAG, "预览失败 " + f.getName() + ": " + t);
            return errorView(c, f, t);
        }
        return null;
    }

    // ---------- docx / xlsx（WebView） ----------

    private static View wordView(Context c, File f) throws Exception {
        String b64 = inline(f);
        String html = page("正在渲染 Word 文档…",
                "<script>" + asset(c, "jszip.min.js") + "</script>\n"
                        + "<script>" + asset(c, "docx-preview.min.js") + "</script>\n"
                        + "<div id=\"out\" class=\"docx\"></div>\n"
                        + "<script>\n"
                        + "const bytes = Uint8Array.from(atob(DOC), ch => ch.charCodeAt(0));\n"
                        + "docx.renderAsync(new Blob([bytes]), document.getElementById('out'), null,\n"
                        + "  { className: 'docx', inWrapper: true, ignoreLastRenderedPageBreak: true })\n"
                        + "  .then(() => done())\n"
                        + "  .catch(e => fail('渲染失败：' + e));\n"
                        + "</script>", b64);
        return webView(c, html);
    }

    private static View sheetView(Context c, File f) throws Exception {
        String b64 = inline(f);
        String html = page("正在渲染表格…",
                "<script>" + asset(c, "xlsx.full.min.js") + "</script>\n"
                        + "<div id=\"out\"></div>\n"
                        + "<script>\n"
                        + "const bytes = Uint8Array.from(atob(DOC), ch => ch.charCodeAt(0));\n"
                        + "const wb = XLSX.read(bytes, { type: 'array' });\n"
                        + "const out = document.getElementById('out');\n"
                        + "wb.SheetNames.forEach((name, i) => {\n"
                        + "  const h = document.createElement('div');\n"
                        + "  h.className = 'sheetname'; h.textContent = name; out.appendChild(h);\n"
                        + "  const t = XLSX.utils.sheet_to_html(wb.Sheets[name], { editable: false });\n"
                        + "  const wrap = document.createElement('div');\n"
                        + "  wrap.className = 'sheetwrap'; wrap.innerHTML = t; out.appendChild(wrap);\n"
                        + "});\n"
                        + "done();\n"
                        + "</script>", b64);
        return webView(c, html);
    }

    private static WebView webView(Context c, String html) {
        WebView wv = new WebView(c);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        try { s.setAllowFileAccessFromFileURLs(true); } catch (Throwable ignored) {}
        try { s.setAllowUniversalAccessFromFileURLs(true); } catch (Throwable ignored) {}
        s.setAllowContentAccess(true);
        wv.setBackgroundColor(0xFFFFFFFF);
        // baseUrl 指到 assets/weboffice/，页面里的 <script src="x.js"> 才能相对解析到本地包
        wv.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
        return wv;
    }

    // ---------- pdf（系统 PdfRenderer） ----------

    private static View pdfView(Context c, File f) throws Exception {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF101014);
        int pad = dp(c, 10);
        col.setPadding(pad, pad, pad, pad);

        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            int pages = Math.min(renderer.getPageCount(), MAX_PDF_PAGES);
            TextView head = new TextView(c);
            head.setText("PDF · 共 " + renderer.getPageCount() + " 页"
                    + (renderer.getPageCount() > pages ? "（显示前 " + pages + " 页）" : ""));
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            head.setTextColor(0xFF9AA0A6);
            head.setPadding(0, 0, 0, dp(c, 8));
            col.addView(head);

            for (int i = 0; i < pages; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                int w = dp(c, 760);
                int h = (int) (w * (float) page.getHeight() / Math.max(1, page.getWidth()));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();

                ImageView iv = new ImageView(c);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(c, 8);
                col.addView(iv, lp);
            }
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) {}
            try { if (fd != null) fd.close(); } catch (Throwable ignored) {}
        }
        ScrollView sc = new ScrollView(c);
        sc.addView(col);
        return sc;
    }

    // ---------- pptx（文字大纲） ----------

    private static View slideOutline(Context c, File f) throws Exception {
        String text = pptxOutline(f);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));

        TextView note = new TextView(c);
        note.setText("幻灯片文字大纲（没有可靠的离线渲染器，这里只列内容，不画版式）");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(0xFF9AA0A6);
        note.setPadding(0, 0, 0, dp(c, 8));
        col.addView(note);

        TextView body = new TextView(c);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(0xFFE8EAED);
        body.setTextIsSelectable(true);
        col.addView(body);

        ScrollView sc = new ScrollView(c);
        sc.addView(col);
        return sc;
    }


    /**
     * pptx 的幻灯片在 ppt/slides/slideN.xml 里，文字都在 <a:t> 中。
     * 按页码排序抽出来即可；版式不画，界面上会注明这是文字大纲。
     */
    static String pptxOutline(File f) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.matches("ppt/slides/slide[0-9]+[.]xml")) names.add(n);
        }
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) { return slideNo(a) - slideNo(b); }
        });
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (String n : names) {
            byte[] raw = readZip(zf, n);
            if (raw == null) continue;
            String xml = new String(raw, "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<a:t>([^<]*)</a:t>").matcher(xml);
            StringBuilder line = new StringBuilder();
            while (m.find()) {
                String t = m.group(1).trim();
                if (t.isEmpty()) continue;
                if (line.length() > 0) line.append(" · ");
                line.append(t);
            }
            sb.append("第 ").append(idx++).append(" 页：")
              .append(line.length() == 0 ? "（这一页没有文字）" : line).append('\n');
        }
        zf.close();
        return sb.length() == 0 ? "（这个 pptx 里没有文字，可能是纯图片页）" : sb.toString();
    }

    private static int slideNo(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("slide([0-9]+)[.]xml").matcher(path);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static byte[] readZip(java.util.zip.ZipFile zf, String name) {
        try {
            java.util.zip.ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            java.io.InputStream in = zf.getInputStream(e);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable t) { return null; }
    }

    // ---------- helpers ----------

    private static View errorView(Context c, File f, Throwable t) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        TextView tv = new TextView(c);
        tv.setText("这个文件渲染不了：" + f.getName() + "\n" + t
                + "\n\n可以用「用其他应用打开」交给系统试试。");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xFFE8EAED);
        col.addView(tv);
        return col;
    }

    /** 读文件并 base64；超过上限抛错（不悄悄截断）。 */
    private static String inline(File f) throws Exception {
        if (f.length() > MAX_INLINE) {
            throw new IllegalStateException("文件太大（" + (f.length() / 1048576)
                    + " MB），预览只支持 " + (MAX_INLINE / 1048576) + " MB 以内");
        }
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private static String page(String hint, String body, String b64) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "body{margin:0;padding:10px;background:#fff;color:#111;"
                + "font-family:system-ui,'Noto Sans CJK SC',sans-serif;font-size:13px}"
                + "#hint{color:#666;font-size:12px;margin-bottom:8px}"
                + ".docx{background:#fff}"
                + ".docx .docx-wrapper{background:#fff;padding:0}"
                + ".docx .docx-wrapper>section.docx{padding:18px;margin:0 0 12px 0;"
                + "box-shadow:0 0 0 1px #e5e7eb;border-radius:6px}"
                + ".sheetname{font-weight:600;margin:12px 0 6px;color:#111}"
                + ".sheetwrap{overflow:auto;border:1px solid #e5e7eb;border-radius:6px;margin-bottom:12px}"
                + ".sheetwrap table{border-collapse:collapse;font-size:12px}"
                + ".sheetwrap td,.sheetwrap th{border:1px solid #d0d5dd;padding:3px 6px;white-space:nowrap}"
                + "table{border-collapse:collapse}"
                + "td,th{border:1px solid #d0d5dd;padding:3px 6px}"
                + "</style></head><body>"
                + "<div id=\"hint\">" + hint + "</div>"
                + "<script>const DOC=\"" + b64 + "\";"
                + "function done(){var h=document.getElementById('hint');if(h)h.style.display='none';}"
                + "function fail(m){var h=document.getElementById('hint');"
                + "if(h){h.style.color='#c0392b';h.textContent=m;}}"
                + "window.onerror=(m)=>{fail('脚本错误：'+m);};"
                + "</script>"
                + body
                + "</body></html>";
    }
}
