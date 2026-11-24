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


    /**
     * 往输入框写字。
     *
     * 不能用 InputManager.injectInputEvent —— 那是 INJECT_EVENTS 签名权限，
     * 普通应用调用直接 SecurityException。无障碍的 ACTION_SET_TEXT 只需要本服务，
     * 而且是"整段替换"，比逐字符敲键盘更稳。
     */
    public synchronized String setTextAt(int index, String text) {
        if (text == null) text = "";
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "没有活动窗口：目标 App 可能已退到后台";

        AccessibilityNodeInfo target = null;
        if (index >= 0 && index < lastBounds.size()) {
            int[] b = lastBounds.get(index);
            int cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
            target = findEditableAt(root, cx, cy, 0);
        }
        if (target == null) target = findFocusedEditable(root);
        if (target == null) return "没找到可输入的框：先 tap 一下输入框，或先调用 ui_dump 看 #序号";

        boolean ok = applyText(target, text);
        if (!ok) {
            // 有的输入框不认 SET_TEXT，退回复制粘贴
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text));
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {}
        }
        if (!ok) return "写入失败：这个输入框不接受无障碍写入，可以试试 tap 后用系统输入法";
        return "已写入 " + text.length() + " 个字符";
    }

    private boolean applyText(AccessibilityNodeInfo n, String text) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找"落在某个坐标上的可编辑节点"（点过输入框后按 #序号 写最可靠）。 */
    private AccessibilityNodeInfo findEditableAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 16) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.contains(x, y) && (n.isEditable() || isTextField(n))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findEditableAt(n.getChild(i), x, y, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isFocused() && (n.isEditable() || isTextField(n))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findFocusedEditable(n.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    private boolean isTextField(AccessibilityNodeInfo n) {
        CharSequence cn = n.getClassName();
        if (cn == null) return false;
        String s = cn.toString();
        return s.contains("EditText") || s.contains("TextInput");
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
