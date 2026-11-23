package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 截屏（MediaProjection）。
 *
 * 授权只要一次：用户在抽屉里点「授权截屏」，系统弹一次同意框；之后本次进程内
 * 反复 capture() 都不再弹窗。投影不关就一直可用，关掉（stop）下次要重新授权。
 *
 * 注意：targetSdk 28 让我们不用为 MediaProjection 起前台服务（29+ 才强制）。
 */
public final class Screen {

    public static final int REQ_CONSENT = 2001;

    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static int width, height, dpi;

    private Screen() {}

    public static Intent consentIntent(Context c) {
        MediaProjectionManager m = (MediaProjectionManager)
                c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (m == null) return null;
        return m.createScreenCaptureIntent();
    }

    public static boolean ready() {
        return reader != null && projection != null;
    }

    /** 拿到授权结果后调用。返回错误文本，成功为 null。 */
    public static synchronized String start(Context c, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return "用户拒绝了截屏授权";
        stop();
        try {
            MediaProjectionManager m = (MediaProjectionManager)
                    c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) return "拿不到投屏句柄";

            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            width = dm.widthPixels;
            height = dm.heightPixels;
            dpi = dm.densityDpi;

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("dshnative-capture", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, new Handler(Looper.getMainLooper()));
            if (display == null) return "创建虚拟屏幕失败";
            return null;
        } catch (Exception e) {
            return "启动截屏失败：" + e.getMessage();
        }
    }

    public static synchronized void stop() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        display = null;
        reader = null;
        projection = null;
    }

    /** 抓一帧。没有新帧时最多等 1.5 秒，再退回取"最新可用"的那帧。 */
    public static synchronized Bitmap capture() {
        if (!ready()) return null;
        Image img = null;
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            img = reader.acquireLatestImage();
            if (img != null) break;
            try { Thread.sleep(60); } catch (InterruptedException e) { break; }
        }
        if (img == null) return null;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
            if (cropped != bmp) bmp.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        } finally {
            try { img.close(); } catch (Exception ignored) {}
        }
    }
}
