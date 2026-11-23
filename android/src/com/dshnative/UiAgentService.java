package com.dshnative;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍档：无 root 手机上"操作任意 App"的通用兜底。
 *
 * 为什么要它：没有 root、没有 ADB 自举时，框架不给你任何往别的 App 里点击的 API。
 * 无障碍是唯一的内置路径，而且不需要用户装任何东西 —— 只需要在系统设置里打开本服务。
 *
 * 关键设计：dump 返回的是**压缩过的树**，不是原始 XML。
 * 手机上界面上千节点，回灌原文等于每轮烧掉几千 token。这里只留可点击/可读的节点。
 */
public class UiAgentService extends AccessibilityService {

    private static volatile UiAgentService instance;

    public static UiAgentService get() { return instance; }

    public static boolean isReady() { return instance != null; }

    /** 上一次 dump 的节点边界，供 tap 按序号定位。存 Rect 而不是 node，node 会被回收。 */
    private final List<int[]> lastBounds = new ArrayList<>();
    private final List<String> lastLabels = new ArrayList<>();

    @Override public void onServiceConnected() { instance = this; }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    /**
     * 压缩界面树。每行：#序号 [类型] "文字" (x,y,w,h) 标记
     * 只保留有文字、有描述、可点击、或可编辑的节点 —— 其余是布局噪声。
     */
    public synchronized String dump(int maxNodes) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "（没有活动窗口：可能锁屏了，或者当前是系统界面）";
        lastBounds.clear();
        lastLabels.clear();
        StringBuilder sb = new StringBuilder();
        walk(root, 0, maxNodes, sb);
        if (lastBounds.isEmpty()) return "（当前界面没有可交互节点）";
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, int depth, int max, StringBuilder sb) {
        if (n == null || lastBounds.size() >= max || depth > 14) return;

        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        boolean clickable = n.isClickable();
        boolean editable = n.isEditable();
        String label = text != null && text.length() > 0 ? text.toString()
                : (desc != null && desc.length() > 0 ? desc.toString() : "");

        boolean interesting = !label.isEmpty() || clickable || editable;
        if (interesting) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                int idx = lastBounds.size();
                lastBounds.add(new int[]{r.left, r.top, r.right, r.bottom});
                lastLabels.add(label);

                sb.append('#').append(idx)
                  .append(" [").append(shortClass(n.getClassName())).append(']');
                if (!label.isEmpty()) {
                    String t = label.replace('\n', ' ');
                    sb.append(" \"").append(t.length() > 60 ? t.substring(0, 60) + "…" : t).append('"');
                }
                sb.append(" (").append(r.centerX()).append(',').append(r.centerY()).append(')');
                if (clickable) sb.append(" 可点");
                if (editable) sb.append(" 可输入");
                sb.append('\n');
            }
        }

        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), depth + 1, max, sb);
            if (lastBounds.size() >= max) return;
        }
    }

    private static String shortClass(CharSequence cn) {
        if (cn == null) return "?";
        String s = cn.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    public synchronized boolean tapIndex(int index) {
        if (index < 0 || index >= lastBounds.size()) return false;
        int[] b = lastBounds.get(index);
        return tap((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f);
    }

    /** 按文字找可点节点并点它 —— 比坐标稳，模型也更好表达。 */
    public synchronized boolean tapText(String needle) {
        if (needle == null || needle.isEmpty()) return false;
        for (int i = 0; i < lastLabels.size(); i++) {
            String l = lastLabels.get(i);
            if (l != null && l.contains(needle)) {
                int[] b = lastBounds.get(i);
                if (tap((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)) return true;
            }
        }
        return false;
    }

    public boolean tap(float x, float y) {
        return gesture(x, y, x, y, 40);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        return gesture(x1, y1, x2, y2, ms);
    }

    private boolean gesture(float x1, float y1, float x2, float y2, long ms) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, Math.max(20, ms));
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized List<String> labels() {
        return new ArrayList<>(lastLabels);
    }
}
