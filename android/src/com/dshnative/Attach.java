package com.dshnative;

import java.io.File;

/**
 * 判断一个文件该不该作为"结果卡片"挂在回答下面。
 *
 * 抽成独立类的两个理由：一是有单元可测（自检里直接跑用例），
 * 二是这条规则本来就不属于任何 Activity——它是产品判断，不是界面细节。
 *
 * 背景：以前一个回合里动过的文件全挂，于是 build_ppt.py、probe_min.docx、
 * pv_1.html 这些中间产物都出现在回答下面，真正要交付的东西被淹没。
 */
public final class Attach {

    private Attach() {}

    /** 脚本、数据、日志、压缩包：属于过程，不属于结果。 */
    private static final String[] JUNK_EXT = {
            ".py", ".sh", ".js", ".json", ".jsonl", ".log", ".tmp", ".bak",
            ".class", ".zip", ".tar", ".gz", ".patch", ".diff"};

    /** 明显的中间产物命名。 */
    private static final String[] JUNK_PREFIX = {
            "probe", "pv_", "preview-", "test-", "tmp-", "build_", "session-", "update-", "."};

    /** 值得交付的类型。 */
    private static final String[] GOOD_EXT = {
            ".docx", ".xlsx", ".pptx", ".pdf", ".wav", ".m4a", ".mp4", ".mp3",
            ".png", ".jpg", ".jpeg", ".webp", ".md", ".html", ".csv", ".txt"};


    /** 真正的交付型文件：出现这种扩展名基本可以确定是给用户的东西。 */
    private static final String[] DELIVERABLE_EXT = {
            ".docx", ".xlsx", ".pptx", ".pdf", ".mp4", ".wav", ".m4a"};

    /**
     * 该不该挂成卡片。
     *
     * worth() 只回答"这个类型像不像结果"，但一轮里写出的 .md/.png 仍可能是草稿。
     * 所以再加一条：**回答正文里提到这个文件名**，或者它本身就是交付型文档。
     * 否则就只是过程文件，不该占据回答下面那块地方。
     */
    public static boolean shouldCard(File f, String answerText) {
        if (!worth(f)) return false;
        String name = f.getName().toLowerCase();
        if (answerText != null && answerText.toLowerCase().contains(name)) return true;
        for (String e : DELIVERABLE_EXT) if (name.endsWith(e)) return true;
        return false;
    }

    public static boolean worth(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName().toLowerCase();
        // 由 office_write 顺手写出的预览副本不进卡片（pptx 的 *.preview.html）
        if (n.contains(".preview.")) return false;
        for (String p : JUNK_PREFIX) if (n.startsWith(p)) return false;
        for (String e : JUNK_EXT) if (n.endsWith(e)) return false;
        for (String g : GOOD_EXT) if (n.endsWith(g)) return true;
        return false;
    }
}
