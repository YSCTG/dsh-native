package com.dshnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区浏览器。
 *
 * 之前是一个平铺的弹窗列表：看不出"整个手机是工作区"，目录也进不去。
 * 现在：说明一行、快捷入口一排、一条路径、可逐层进入的列表。
 */
public class FilesActivity extends Activity {

    private LinearLayout list;
    private TextView pathView;
    private TextView titleView;
    private File current;
    private File home;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int bg = getColor(R.color.bg_base);
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);

        home = Ws.active(this);
        current = home;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.addView(toolbar());

        // 说明：一句话讲清"工作区"是什么
        TextView intro = new TextView(this);
        intro.setText("整部手机都能读写。相对路径默认落在下面的默认目录，其它位置用绝对路径。长按条目可以重命名或删除。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        intro.setTextColor(getColor(R.color.label_tertiary));
        intro.setLineSpacing(dp(3), 1f);
        intro.setPadding(dp(20), dp(8), dp(20), dp(10));
        root.addView(intro);

        root.addView(quickRow());

        pathView = new TextView(this);
        pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextColor(getColor(R.color.label_caption));
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathView.setPadding(dp(20), dp(4), dp(20), dp(8));
        root.addView(pathView);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        open(home);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(getColor(R.color.label_secondary));
        back.setPadding(dp(10), dp(2), dp(12), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setText("工作区");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setTextColor(getColor(R.color.label_primary));
        texts.addView(titleView);
        bar.addView(texts);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView up = new TextView(this);
        up.setText("上一级");
        up.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        up.setTextColor(getColor(R.color.accent));
        up.setPadding(dp(12), dp(8), dp(6), dp(8));
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File p = current.getParentFile();
                if (p != null && p.canRead()) open(p);
            }
        });
        bar.addView(up);

        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border_l1));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar);
        wrap.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    /** 快捷入口：默认目录 + 手机上最常找的几个位置 */
    private View quickRow() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), 0, dp(16), dp(6));

        File ext = android.os.Environment.getExternalStorageDirectory();
        addQuick(row, "默认 DSH Native", home);
        if (ext != null) {
            addQuick(row, "全部", ext);
            addQuick(row, "下载", new File(ext, "Download"));
            addQuick(row, "相册", new File(ext, "DCIM"));
            addQuick(row, "文档", new File(ext, "Documents"));
        }
        addQuick(row, "私有", getFilesDir());
        hs.addView(row);
        return hs;
    }

    private void addQuick(LinearLayout row, String label, final File dir) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (dir != null && dir.isDirectory()) open(dir);
                else toast("这个位置不存在");
            }
        });
        row.addView(t);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void open(File dir) {
        current = dir;
        titleView.setText(home.equals(dir) ? "工作区" : dir.getName());
        pathView.setText(dir.getAbsolutePath());

        list.removeAllViews();
        File[] kids = dir.listFiles();
        if (kids == null) {
            list.addView(note("这个位置读不了（可能没有权限）"));
            return;
        }
        List<File> files = new ArrayList<>(Arrays.asList(kids));
        files.sort(new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        if (files.isEmpty()) {
            list.addView(note("这里是空的"));
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        for (int i = 0; i < files.size(); i++) {
            final File f = files.get(i);
            card.addView(row(f));
            if (i < files.size() - 1) {
                View d = new View(this);
                d.setBackgroundColor(getColor(R.color.border_l1));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dlp.leftMargin = dp(46);
                card.addView(d, dlp);
            }
        }
        list.addView(card);
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(getColor(R.color.label_tertiary));
        t.setPadding(dp(6), dp(16), dp(6), dp(16));
        return t;
    }

    private View row(final File f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(11), dp(14), dp(11));

        TextView glyph = new TextView(this);
        glyph.setText(f.isDirectory() ? "▸" : "·");
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        glyph.setTextColor(getColor(R.color.label_caption));
        glyph.setWidth(dp(32));
        r.addView(glyph);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(getColor(R.color.label_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);

        final boolean apk = !f.isDirectory() && f.getName().toLowerCase().endsWith(".apk");
        final String installed = apk ? Apks.installedVersion(this, f) : null;

        TextView meta = new TextView(this);
        meta.setText(f.isDirectory() ? "目录"
                : (humanSize(f.length())
                   + (apk ? (installed == null ? " · 未安装"
                             : (" · 已安装" + (installed.isEmpty() ? "" : " " + installed)))
                          : "")));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setTextColor(getColor(R.color.label_tertiary));
        meta.setPadding(0, dp(2), 0, 0);
        texts.addView(meta);

        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (f.isDirectory()) { open(f); return; }
                if (apk) {                       // APK 的默认动作是装，不是预览
                    String err = Apks.install(FilesActivity.this, f);
                    if (err != null) toast(err);
                    return;
                }
                preview(f);
            }
        });
        r.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { actions(f); return true; }
        });
        return r;
    }

    /** 长按一个条目：按类型给出可用动作。 */
    private void actions(final File f) {
        boolean apk = f.getName().toLowerCase().endsWith(".apk");
        java.util.List<String> items = new java.util.ArrayList<>();
        if (apk) items.add("安装");
        if (!f.isDirectory()) items.add("分享");
        if (!f.isDirectory()) items.add("用其他应用打开");
        items.add("重命名");
        items.add("删除");
        items.add("复制路径");
        final String[] arr = items.toArray(new String[0]);

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(f.getName())
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String what = arr[which];
                        if ("安装".equals(what)) {
                            String err = Apks.install(FilesActivity.this, f);
                            if (err != null) toast(err);
                        } else if ("分享".equals(what)) {
                            String err = Apks.share(FilesActivity.this, f);
                            if (err != null) toast(err);
                        } else if ("用其他应用打开".equals(what)) {
                            openWith(f);
                        } else if ("重命名".equals(what)) {
                            rename(f);
                        } else if ("删除".equals(what)) {
                            delete(f);
                        } else {
                            copyPath(f);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 交给系统：任何类型的文件都能试一次（APK 会走安装器）。 */
    private void openWith(File f) {
        try {
            android.net.Uri uri = FileProviderX.uriFor(this, f);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, Mimes.of(f.getName()));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(i, "打开 " + f.getName()));
        } catch (Throwable t) {
            toast("没有能打开它的应用：" + t.getMessage());
        }
    }

    private void rename(final File f) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(f.getName());
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(6), dp(20), dp(0));
        box.addView(et);

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("重命名")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty() || name.contains("/")) { toast("名字不合法"); return; }
                        File dst = new File(f.getParentFile(), name);
                        if (dst.exists()) { toast("已经有同名文件"); return; }
                        if (f.renameTo(dst)) { toast("已重命名"); open(current); }
                        else toast("重命名失败（权限或跨分区）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(final File f) {
        int count = f.isDirectory() ? countInside(f) : 0;
        String msg = f.isDirectory()
                ? (count == 0 ? "空目录，可以直接删。" : "这个目录里还有 " + count + " 项，会一起删掉，不能恢复。")
                : humanSize(f.length()) + "，删掉不能恢复。";
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("删除 " + f.getName() + " ?")
                .setMessage(msg)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (erase(f)) { toast("已删除"); open(current); }
                        else toast("删除失败（权限不足？）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int countInside(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) n += k.isDirectory() ? 1 + countInside(k) : 1;
        return n;
    }

    private boolean erase(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) erase(k);
        }
        return f.delete();
    }

    private void copyPath(File f) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.getAbsolutePath()));
            toast("路径已复制");
        }
    }
    private String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / 1048576.0);
    }

    /** 预览：图片直接显示，其它按文本截断显示；二进制告知大小。 */
    private void preview(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".gif")) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setImageURI(android.net.Uri.fromFile(f));
            if (iv.getDrawable() == null) { toast("这张图打不开"); return; }
            ScrollView sv = new ScrollView(this);
            sv.addView(iv);
            new AlertDialog.Builder(this, R.style.DshDialog)
                    .setTitle(f.getName())
                    .setView(sv)
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        if (f.length() > 200000) { toast(humanSize(f.length()) + "，太大不预览"); return; }
        String text;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 40000)];
            FileInputStream in = new FileInputStream(f);
            int got = in.read(buf);
            in.close();
            for (int i = 0; i < got; i++) {
                if (buf[i] == 0) { toast("二进制文件，不预览"); return; }
            }
            text = new String(buf, 0, Math.max(got, 0), "UTF-8");
        } catch (Exception e) {
            toast("读失败：" + e.getMessage());
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getColor(R.color.label_secondary));
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(f.getName())
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();
    }
}
