package com.dshnative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * 把产出物渲染成图，交给模型**自己看**。
 *
 * 为什么要它：模型写完排版只能靠猜。以前它想看效果就去起一个静态服务器，
 * 命令永不返回，整轮挂死。现在给一个直接渲染的入口：
 *   pdf  → PdfRenderer 渲染指定页
 *   html → 内置 WebView 整页截图
 * 渲染出的 PNG 作为图片消息进入下一轮，模型能真的"看见"自己排的版。
 */
public final class Preview {

    private Preview() {}

    /** 渲染一页到 PNG。返回错误信息，成功返回 null。 */
    public static String render(Context c, File src, int page, File out) {
        String name = src.getName().toLowerCase();
        if (name.endsWith(".pdf")) return pdf(src, page, out);
        if (name.endsWith(".html") || name.endsWith(".htm")) return html(c, src, out);
        return "这类文件无法渲染预览（只支持 pdf / html）。文档类请用 browser_open 或让用户打开。";
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
            FileOutputStream os = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.PNG, 92, os);
            os.close();
            bm.recycle();
            return null;
        } catch (Throwable e) {
            return "渲染 PDF 失败：" + e.getMessage();
        } finally {
            try { if (pr != null) pr.close(); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
    }

    /** HTML：在内置 WebView 里加载后整页截图。 */
    private static String html(final Context c, File src, final File out) {
        String url = "file://" + src.getAbsolutePath();
        String opened = Browser.open(url, 25);
        if (opened != null && opened.startsWith("加载失败")) return opened;

        final Bitmap[] shot = new Bitmap[1];
        final String[] err = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.webkit.WebView w = Browser.viewForCapture();
                    if (w == null) { err[0] = "没有可用的浏览器视图"; return; }
                    int width = w.getWidth() > 0 ? w.getWidth() : 1080;
                    int contentH = (int) (w.getContentHeight() * w.getScale());
                    int height = Math.min(Math.max(contentH, 400), 4000);
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
            FileOutputStream os = new FileOutputStream(out);
            shot[0].compress(Bitmap.CompressFormat.PNG, 92, os);
            os.close();
            shot[0].recycle();
            return null;
        } catch (Throwable e) {
            return "保存失败：" + e.getMessage();
        }
    }
}