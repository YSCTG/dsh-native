package com.dshnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 更新下载与安装的界面流程。
 *
 * 从 MainActivity 里抽出来的（那个类当时 4600 多行）：这里只关心"下载一个更新包并且装上"，
 * 与聊天、会话、抽屉都无关，抽出来既能单独读，也让 MainActivity 少一百行。
 */
public final class UpdateFlow {

    private static final String TAG = "DSHN";

    private UpdateFlow() {}

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    /** 下载并交给系统安装器；带进度条与取消。 */
    public static void start(final Activity a, final Updater.Info info) {
        if (!Updater.canInstall(a)) {
            toast(a, "先允许「安装未知应用」，然后再点一次检查更新");
            Updater.openInstallPermission(a);
            return;
        }
        Log.i(TAG, "更新 开始下载 " + info.apkUrl);

        final Handler ui = new Handler(Looper.getMainLooper());
        final int[] lastPct = {-2};

        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        // AlertDialog 里的自定义控件默认没有高度，得显式给，否则只看到文字看不到条
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 8));
        blp.bottomMargin = dp(a, 10);
        bar.setLayoutParams(blp);
        bar.setIndeterminate(true);      // 总大小未知先跑马灯

        final TextView pct = new TextView(a);
        pct.setText("正在下载 " + info.tag + "（0%）");
        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pct.setTextColor(a.getColor(R.color.label_secondary));

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 22), dp(a, 18), dp(a, 22), dp(a, 6));
        box.addView(bar, blp);
        box.addView(pct);

        final AlertDialog dlg = new AlertDialog.Builder(a, R.style.DshDialog)
                .setTitle("更新 " + info.tag)
                .setView(box)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Updater.cancelRequested = true;
                        toast(a, "已取消下载");
                    }
                })
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File apk = Updater.download(a, info, new Updater.Progress() {
                        @Override public void onProgress(final long got, final long total) {
                            final int p2 = total > 0 ? (int) (got * 100 / total) : -1;
                            if (p2 == lastPct[0]) return;
                            lastPct[0] = p2;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    try {
                                        if (p2 >= 0) bar.setIndeterminate(false);
                                        bar.setProgress(Math.max(0, p2));
                                        pct.setText(p2 >= 0
                                                ? String.format("正在下载 %s（%d%%）· %.1f/%.1f MB",
                                                    info.tag, p2, got / 1048576.0, total / 1048576.0)
                                                : String.format("正在下载 %s · %.1f MB",
                                                    info.tag, got / 1048576.0));
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                    });

                    // 装之前核对 versionCode：比当前旧的包一律不装（缓存旧包是真实踩过的坑）
                    try {
                        int got = Updater.apkVersionCode(a, apk);
                        int cur = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
                        if (got > 0 && got <= cur) {
                            apk.delete();
                            final int curCopy = cur;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    dismiss(dlg);
                                    toast(a, "下载到的包不比当前版本新（" + curCopy + "），已删除；请重新检查更新");
                                }
                            });
                            return;
                        }
                    } catch (Throwable ignored) {}

                    ui.post(new Runnable() {
                        @Override public void run() {
                            Log.i(TAG, "更新 下载完成，转安装器");
                            dismiss(dlg);
                            toast(a, "下载完成，交给系统安装");
                        }
                    });
                    Updater.install(a, apk);
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Log.i(TAG, "更新 下载失败: " + e);
                            dismiss(dlg);
                            toast(a, "下载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private static void dismiss(AlertDialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
    }
}
