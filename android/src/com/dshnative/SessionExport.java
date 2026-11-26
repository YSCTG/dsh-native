package com.dshnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把一段会话导出成 Markdown。
 *
 * 为什么要：会话只躺在应用私有目录里，换手机、发给别人、或者想在外面复盘都拿不出来。
 * 导出的文件放在工作区 `export/` 下，能直接分享，也能用「文件」页打开。
 */
public final class SessionExport {

    private SessionExport() {}

    /** 单条工具输出最多保留这么多字符，避免一份导出几千行。 */
    private static final int TOOL_KEEP = 1500;

    /** 写一份 Markdown 到 `<workspace>/export/`，返回文件。 */
    public static File write(Context c, Session s) throws Exception {
        String title = s == null || s.title == null || s.title.isEmpty() ? "会话" : s.title;
        JSONArray msgs = s == null ? new JSONArray() : Session.load(c, s.id);
        String md = toMarkdown(title, msgs);

        File dir = new File(Workspaces.currentDir(c), "export");
        if (!dir.isDirectory()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File out = new File(dir, safeName(title) + "-" + stamp + ".md");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
        fo.write(md.getBytes("UTF-8"));
        fo.close();
        return out;
    }

    /** 会话 → Markdown。纯函数，便于单测。 */
    public static String toMarkdown(String title, JSONArray msgs) {
        StringBuilder sb = new StringBuilder();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        sb.append("# ").append(title == null ? "会话" : title).append("\n\n");
        sb.append("> 导出时间 ").append(stamp)
          .append(" · ").append(msgs == null ? 0 : msgs.length()).append(" 条消息\n\n");

        if (msgs == null) return sb.toString();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String text = SessionSearch.textOf(m);
            if ("system".equals(role)) continue;              // 系统提示不外泄
            if (text.isEmpty()) continue;

            if ("user".equals(role)) {
                sb.append("## 你\n\n").append(text).append("\n\n");
            } else if ("assistant".equals(role)) {
                sb.append("## 助手\n\n").append(text).append("\n\n");
            } else if ("tool".equals(role)) {
                String body = text.length() > TOOL_KEEP
                        ? text.substring(0, TOOL_KEEP) + "\n…（已截断，原文 " + text.length() + " 字符）"
                        : text;
                sb.append("> **工具返回**\n>\n");
                for (String line : body.split("\n")) {
                    sb.append("> ").append(line).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 文件名里去掉路径分隔符等危险字符。 */
    static String safeName(String title) {
        if (title == null || title.trim().isEmpty()) return "session";
        String s = title.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "session" : s;
    }

    /** 分享导出好的 Markdown。 */
    public static String share(Activity a, File md) {
        try {
            Uri uri = FileProviderX.uriFor(a, md);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/markdown");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, md.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "分享会话"));
            return null;
        } catch (Throwable t) {
            return "分享失败：" + t.getMessage();
        }
    }
}
