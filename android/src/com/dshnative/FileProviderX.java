package com.dshnative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把自己的文件借给别的应用打开。
 *
 * 为什么要它：从 Android 7 起，把 file:// 交给外部应用会抛 FileUriExposedException；
 * 从 10 起基本被彻底封死。官方解法是 FileProvider，但那是 AndroidX 的类，这个项目
 * 没有 AndroidX——所以自己实现一个最小的：content://<包名>.files/open/<编码后的绝对路径>。
 *
 * 只读，exported=false；外部应用能读，是因为调用方显式加了
 * FLAG_GRANT_READ_URI_PERMISSION，权限按单个 URI 临时授予。
 */
public class FileProviderX extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".files";
    private static final String SEG = "open";

    /** 给某个文件生成外部应用可读的 content:// URI。 */
    public static Uri uriFor(Context c, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(c.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(SEG)
                .appendPath(Uri.encode(f.getAbsolutePath()))
                .build();
    }

    private static File fileOf(Uri uri) throws FileNotFoundException {
        String seg = uri.getLastPathSegment();
        if (seg == null) throw new FileNotFoundException("空路径");
        File f = new File(Uri.decode(seg));
        if (!f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try {
            return Mimes.of(fileOf(uri).getName());
        } catch (Throwable e) {
            return "application/octet-stream";
        }
    }

    /**
     * 系统安装器在装 APK 之前会查这两个列：文件名和大小。
     * 以前这里返回 null，结果"交给系统安装"什么都弹不出来——不报错，也不安装。
     */
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File f = fileOf(uri);
            String[] cols = {"_display_name", "_size"};
            Object[] vals = {f.getName(), f.length()};
            android.database.MatrixCursor c =
                    new android.database.MatrixCursor(cols);
            c.addRow(vals);
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
