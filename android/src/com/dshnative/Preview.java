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

    /** 渲染一页到 PNG。成功返回 null，失败返回错误信息。 */
    public static String render(Context c, File src, int page, File out) {
        String name = src.getName().toLowerCase();
        if (name.endsWith(".pdf")) return pdf(src, page, out);
        if (name.endsWith(".pptx")) return pptx(src, out);
        if (name.endsWith(".html") || name.endsWith(".htm")) return html(c, src, out);
        return "这类文件无法渲染预览（支持 pdf / html / pptx）。docx/xlsx 请让用户打开。";
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
