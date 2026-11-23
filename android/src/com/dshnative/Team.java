package com.dshnative;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体团队：后台并行跑子 agent，父会话边干边收。
 *
 * 和电脑版的差别说明白：电脑版的 teammate 是常驻的，能随时被叫醒；
 * 手机上不常驻进程，所以这里是"跑完就结束"的形态。但**投递消息、等待、中断**这几件事
 * 都能做到——消息在子 agent 每一步结束时被读进去。
 *
 * 团队共享同一个工作区（同一个文件目录），所以协作是真的：
 * 几个人写不同文件，父会话最后一起看。
 */
public final class Team {

    /** 子 agent 每走一步回来问一次：有没有新消息？该不该停？ */
    public interface Hook {
        String takeMessages();
        boolean cancelled();
    }

    public static final class Mate {
        public final String name;
        public final String task;
        public volatile String result = "";
        public volatile boolean done = false;
        public volatile boolean cancel = false;
        public final long started = System.currentTimeMillis();
        public volatile long lastActive = System.currentTimeMillis();
        public int in, out;
        private final List<String> inbox = new ArrayList<>();

        Mate(String name, String task) { this.name = name; this.task = task; }

        synchronized void push(String msg) { inbox.add(msg); }
        synchronized String drain() {
            if (inbox.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String m : inbox) sb.append(m).append('\n');
            inbox.clear();
            return sb.toString().trim();
        }
        synchronized int pending() { return inbox.size(); }
    }

    private static final Map<String, Mate> MATES = new LinkedHashMap<>();
    private static final Object SIGNAL = new Object();

    public static String spawn(Context ctx, String apiKey, String model, String baseUrl,
                               String name, String task, SubAgent.Tools tools, int maxTurns) {
        if (name == null || name.trim().isEmpty()) return "错误：teammate 需要一个名字";
        if (MATES.containsKey(name)) return "错误：已经有叫 " + name + " 的 teammate 了";
        final Mate m = new Mate(name.trim(), task == null ? "" : task);
        MATES.put(m.name, m);

        final Hook hook = new Hook() {
            @Override public String takeMessages() {
                String s = m.drain();
                if (!s.isEmpty()) m.lastActive = System.currentTimeMillis();
                return s;
            }
            @Override public boolean cancelled() { return m.cancel; }
        };

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String board = TeamBoard.digest(ctx);
                SubAgent.Result r = SubAgent.run(ctx, apiKey, model, baseUrl,
                        "你是 teammate「" + m.name + "」。只做交代给你的事，做完给一段不超过 150 字的结论。"
                                + "工作区是共享的，文件名前面加上自己的名字避免冲突。"
                                + "中途可能收到父会话的消息，按新要求调整。" + board,
                        m.task, tools, maxTurns, hook);
                m.result = r.text;
                m.in = r.in;
                m.out = r.out;
                m.done = true;
                synchronized (SIGNAL) { SIGNAL.notifyAll(); }
            }
        });
        t.setDaemon(true);
        t.start();
        return "已派出 teammate「" + m.name + "」。team_list 看进度，team_collect 收结果，"
                + "team_send 可以中途给它加要求。";
    }

    public static String list() {
        if (MATES.isEmpty()) return "团队里还没有人。用 team_spawn 派一个。";
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Mate m : MATES.values()) {
            sb.append(m.done ? "✓ " : "… ").append(m.name)
              .append(m.done ? "（已完成）" : "（跑了 " + ((now - m.started) / 1000) + "s）");
            if (m.pending() > 0) sb.append("  📨").append(m.pending());
            sb.append('\n').append("   ").append(shorten(m.task)).append('\n');
        }
        return sb.toString();
    }

    public static String collect(String name) {
        Mate m = MATES.get(name);
        if (m == null) return "错误：没有叫 " + name + " 的 teammate。先 team_list 看看。";
        if (!m.done) return "「" + m.name + "」还在跑，等几秒再收。";
        return "「" + m.name + "」的结论：\n" + m.result;
    }

    /** 给某个 teammate 发一条消息；它会在下一步读到。 */
    public static String send(String name, String message) {
        Mate m = MATES.get(name);
        if (m == null) return "错误：没有叫 " + name + " 的 teammate。先 team_list 看看。";
        if (message == null || message.trim().isEmpty()) return "错误：消息不能为空";
        if (m.done) {
            return "「" + m.name + "」已经跑完了，收不到新消息。"
                    + "如果要改它的活，重新 team_spawn 一个（名字要换）。";
        }
        m.push(message.trim());
        synchronized (SIGNAL) { SIGNAL.notifyAll(); }
        return "已投递给「" + m.name + "」，它下一步会读到。";
    }

    /**
     * 等团队有变化：谁跑完了、或者谁收到了消息。
     * 返回当前状态；超时也返回状态，不报错（等待本身不算失败）。
     */
    public static String waitChange(int timeoutSec) {
        if (timeoutSec <= 0) timeoutSec = 60;
        if (timeoutSec > 600) timeoutSec = 600;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        synchronized (SIGNAL) {
            while (System.currentTimeMillis() < deadline) {
                if (anyDone()) break;
                long left = Math.min(1000, deadline - System.currentTimeMillis());
                if (left <= 0) break;
                try { SIGNAL.wait(left); } catch (InterruptedException ignored) {}
            }
        }
        return (anyDone() ? "有 teammate 完成了。\n" : "还在跑（等了 " + timeoutSec + "s）。\n") + list();
    }

    /** 中断某个 teammate：它会在下一步停手。 */
    public static String interrupt(String name) {
        Mate m = MATES.get(name);
        if (m == null) return "错误：没有叫 " + name + " 的 teammate。";
        if (m.done) return "「" + m.name + "」已经跑完了。";
        m.cancel = true;
        return "已请求中断「" + m.name + "」，它会在下一步停下。";
    }

    private static boolean anyDone() {
        for (Mate m : MATES.values()) if (m.done) return true;
        return false;
    }

    public static void clear() { MATES.clear(); }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
