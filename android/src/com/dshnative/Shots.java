package com.dshnative;

import android.content.Context;
import android.media.MediaScannerConnection;

import java.io.File;

/**
 * 截图的临时存放。
 *
 * 为什么不再放工作区：工作区在 `/sdcard/DSHNative/` 下，属于**共享存储**，
 * 系统媒体库会把那里的 png 全部索引进去——于是 agent 每截一次屏，相册里就多一张。
 * 这些图只是给模型"看一眼"，看完就该消失，不该进用户相册。
 *
 * 所以：
 * 1. 存到应用私有缓存目录（`getCacheDir()/shots`）——系统媒体库根本不看这里
 * 2. 图片作为消息发出去之后立刻删除（见 Agent 里 pendingShot 的消费处）
 * 3. 启动时清掉历史遗留：工作区里以前那些 `screen-*.png` 一并删掉，
 *    并让媒体库把它们从索引里移除
 */
final class Shots {

    private Shots() {}

    /** 保存截图用的目录（应用私有缓存，不进相册）。 */
    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "shots");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File newFile(Context c) {
        return new File(dir(c), "screen-" + System.currentTimeMillis() + ".png");
    }

    /** 这个路径是不是我们自己的临时截图（决定要不要用完即删）。 */
    static boolean isShot(Context c, String path) {
        if (path == null) return false;
        return path.startsWith(dir(c).getAbsolutePath() + "/");
    }

    static void delete(String path) {
        if (path == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(path).delete();
    }

    /** 清掉超过一小时没被用掉的截图（比如中途取消的任务留下的）。 */
    static int cleanup(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return 0;
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        int n = 0;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff && f.delete()) n++;
        }
        return n;
    }

    /**
     * 清掉旧版本留在工作区里的截图，并让媒体库忘掉它们。
     *
     * 这一步解决的是"已经进了相册的那些图"：光删文件不够，
     * 媒体库还有索引记录，需要再扫一次路径才会移除。
     */
    static int purgeLegacy(Context c, File workspace) {
        File uploads = new File(workspace, "uploads");
        File[] files = uploads.listFiles();
        if (files == null) return 0;

        int n = 0;
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith("screen-") || !name.endsWith(".png")) continue;
            if (!f.isFile()) continue;
            if (!f.delete()) continue;
            n++;
            // 让媒体库重新扫这个（已不存在的）路径 → 索引项被移除
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        }
        return n;
    }
}
