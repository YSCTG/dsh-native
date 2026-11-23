package com.dshnative;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台任务：把耗时的命令挪出这一轮，别把对话卡死。
 *
 * 为什么需要：`sh` 是同步的，装包、编译、下载这类几分钟的活，
 * 一跑就把整个回合占了——用户看到的就是"停住了"。桌面版有 job_list/job_output，
 * 手机上一直缺。
 *
 * 这里是进程内实现：命令用 sh -c 起，输出边跑边收集（只留尾部，避免吃内存），
 * 结束后保留退出码。进程随 App 退出而结束——需要跨进程存活的场景以后再说。
 */
public final class Jobs {

    private Jobs() {}

    /** 一个后台任务。 */
    public static final class Job {
        public final String id;
        public final String label;
        public final String command;
        public final long startedAt = System.currentTimeMillis();
        public final StringBuilder out = new StringBuilder();
        public volatile boolean running = true;
        public volatile int exit = Integer.MIN_VALUE;
        public volatile long endedAt;

        Job(String id, String label, String command) {
            this.id = id;
            this.label = label;
            this.command = command;
        }

        public String status() {
            if (running) {
                long s = (System.currentTimeMillis() - startedAt) / 1000;
                return "运行中（" + s + "s）";
            }
            return exit == 0 ? "已完成" : ("失败，退出码 " + exit);
        }
    }

    private static final int MAX_OUT = 200_000;
    private static final Map<String, Job> RUNNING = new LinkedHashMap<>();
    private static final Map<String, Job> DONE = new LinkedHashMap<>();
    private static int seq = 0;

    public static synchronized Job start(final Context c, String command, String label) {
        String id = "job" + (++seq);
        final Job job = new Job(id, label == null || label.isEmpty() ? "任务" + seq : label, command);
        RUNNING.put(id, job);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "/system/bin/sh", "-c", "cd " + quote(Ws.active(c)) + " && " + job.command);
                    pb.redirectErrorStream(true);
                    pb.environment().put("HOME", Ws.active(c).getAbsolutePath());
                    pb.environment().put("PATH", pb.environment().get("PATH") + ":" + c.getFilesDir());
                    p = pb.start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (job.out) {
                            job.out.append(line).append('\n');
                            if (job.out.length() > MAX_OUT) {
                                job.out.delete(0, job.out.length() - MAX_OUT);
                            }
                        }
                    }
                    job.exit = p.waitFor();
                } catch (Throwable e) {
                    synchronized (job.out) {
                        job.out.append("[启动失败] ").append(e.getMessage()).append('\n');
                    }
                    job.exit = -1;
                } finally {
                    job.running = false;
                    job.endedAt = System.currentTimeMillis();
                    synchronized (Jobs.class) {
                        RUNNING.remove(job.id);
                        DONE.put(job.id, job);
                        while (DONE.size() > 20) {
                            String first = DONE.keySet().iterator().next();
                            DONE.remove(first);
                        }
                    }
                    if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, "dsh-job-" + id);
        t.setDaemon(true);
        t.start();
        return job;
    }

    public static synchronized List<Job> all() {
        List<Job> out = new ArrayList<>(RUNNING.values());
        out.addAll(DONE.values());
        return out;
    }

    public static synchronized Job get(String id) {
        Job j = RUNNING.get(id);
        if (j == null) j = DONE.get(id);
        return j;
    }

    /** 取输出。waitMs 大于 0 时先等一会儿（任务可能马上就完）。 */
    public static String output(Job job, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        while (job.running && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        String text;
        synchronized (job.out) {
            text = job.out.toString();
        }
        if (text.isEmpty()) text = "(还没有输出)";
        return text;
    }

    public static boolean kill(Job job) {
        // 进程内没法精确杀子进程树；标记结束后由 sh 自己退出
        job.running = false;
        return true;
    }

    private static String quote(File f) {
        return "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
    }
}
