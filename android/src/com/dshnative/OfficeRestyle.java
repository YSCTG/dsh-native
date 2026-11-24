package com.dshnative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 改现有 docx 的样式。
 *
 * 只做一件事：把 word/styles.xml 里指定样式（styleId）的字号/颜色/字体改掉，其余部件**原样字节拷贝**
 * ——图片、字体、嵌入对象一律不动。所以不能用那个只接受文本的 zip() 助手。
 *
 * 边界说清楚：不改正文里写死在段落上的直接格式；不处理 pptx / xlsx。
 */
public final class OfficeRestyle {

    private OfficeRestyle() {}

    public static String run(File in, File out, JSONObject spec) {
        if (in == null || !in.isFile()) return "错误：源文件不存在";
        JSONArray styles = spec.optJSONArray("styles");
        boolean hasDocx = styles != null && styles.length() > 0;
        boolean hasXlsx = spec.optJSONObject("xlsx") != null;
        boolean hasPptx = spec.optJSONObject("pptx") != null;
        if (!hasDocx && !hasXlsx && !hasPptx) {
            return "错误：至少要给一种目标——styles（docx 样式）、xlsx（字体/字号/字色）、"
                    + "或 pptx（ink/page/accent 主题色）";
        }
        try {
            String patched = null;
            // 关键：永远先写临时文件再改名。out 可能等于 in（默认覆盖），
            // 直接 new FileOutputStream(out) 会在读之前把源文件截断，产出坏 zip。
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            ZipFile zf = new ZipFile(in);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                ZipEntry ne = new ZipEntry(e.getName());
                zos.putNextEntry(ne);
                String nm = e.getName();
                if ("word/styles.xml".equals(nm)) {
                    patched = hasDocx ? patch(read(zf.getInputStream(e)), styles)
                            : read(zf.getInputStream(e));
                    zos.write(patched.getBytes("UTF-8"));
                } else if ("xl/styles.xml".equals(nm) && spec.has("xlsx")) {
                    patched = patchXlsx(read(zf.getInputStream(e)), spec.optJSONObject("xlsx"));
                    zos.write(patched.getBytes("UTF-8"));
                } else if ("ppt/theme/theme1.xml".equals(nm) && spec.has("pptx")) {
                    patched = patchTheme(read(zf.getInputStream(e)), spec.optJSONObject("pptx"));
                    zos.write(patched.getBytes("UTF-8"));
                } else {
                    copy(zf.getInputStream(e), zos);
                }
                zos.closeEntry();
            }
            zos.close();
            zf.close();
            if (patched == null && (hasDocx || hasXlsx || hasPptx)) {
                tmp.delete();
                return "错误：这份 docx 没有 word/styles.xml，无法按样式改（可能是别的工具生成的）";
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) return "错误：改名失败，结果留在 " + tmp.getAbsolutePath();
            return "已改写样式：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB）";
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    /** 在 styles.xml 里逐个样式块打补丁。 */
    private static String patch(String xml, JSONArray styles) {
        String out = xml;
        for (int i = 0; i < styles.length(); i++) {
            JSONObject s = styles.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id", "");
            if (id.isEmpty()) continue;
            Pattern pb = Pattern.compile(
                    "(<w:style[^>]*w:styleId=\"" + Pattern.quote(id) + "\"[^>]*>)(.*?)(</w:style>)",
                    Pattern.DOTALL);
            Matcher m = pb.matcher(out);
            if (!m.find()) continue;
            String head = m.group(1), body = m.group(2), tail = m.group(3);
            StringBuilder rpr = new StringBuilder();
            int size = s.optInt("size", 0);          // 半磅
            if (size > 0) rpr.append("<w:sz w:val=\"").append(size).append("\"/>");
            String color = s.optString("color", "");
            if (!color.isEmpty()) {
                String hex = color.startsWith("#") ? color.substring(1) : color;
                rpr.append("<w:color w:val=\"").append(hex.toUpperCase()).append("\"/>");
            }
            String font = s.optString("font", "");
            if (!font.isEmpty()) {
                rpr.append("<w:rFonts w:ascii=\"").append(font)
                   .append("\" w:hAnsi=\"").append(font)
                   .append("\" w:eastAsia=\"").append(font).append("\"/>");
            }
            if (rpr.length() == 0) continue;
            // 先摘掉同名的旧值，否则会留下两份（后出现的可能覆盖我们写的）
            String cleaned = body.replaceAll("<w:sz\\s+w:val=\"\\d+\"\\s*/>", "")
                                  .replaceAll("<w:color\\s+w:val=\"[0-9A-Fa-f]+\"\\s*/>", "")
                                  .replaceAll("<w:rFonts[^>]*/>", "");
            String newBody;
            if (cleaned.contains("<w:rPr>")) {
                body = cleaned;
                newBody = body.replaceFirst("<w:rPr>", "<w:rPr>" + rpr);
            }
            if (body.contains("<w:rPr>")) {
                newBody = body.replaceFirst("<w:rPr>", "<w:rPr>" + rpr);
            } else if (body.contains("<w:rPr/>")) {
                newBody = body.replace("<w:rPr/>", "<w:rPr>" + rpr + "</w:rPr>");
            } else {
                newBody = body + "<w:rPr>" + rpr + "</w:rPr>";
            }
            out = out.substring(0, m.start()) + head + newBody + tail + out.substring(m.end());
        }
        return out;
    }

    /** xlsx：改默认字体名、字号(pt)与字色。 */
    private static String patchXlsx(String xml, org.json.JSONObject s) {
        String out = xml;
        String font = s.optString("font", "");
        int pt = s.optInt("size", 0);
        String color = s.optString("color", "");
        if (!font.isEmpty()) out = out.replaceAll("<name val=\"[^\"]*\"/>", "<name val=\"" + font + "\"/>");
        if (pt > 0) out = out.replaceAll("<sz val=\"[0-9.]+\"/>", "<sz val=\"" + pt + "\"/>");
        if (!color.isEmpty()) {
            String hex = "FF" + (color.startsWith("#") ? color.substring(1) : color).toUpperCase();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<font>(.*?)</font>", java.util.regex.Pattern.DOTALL).matcher(out);
            StringBuffer sbx = new StringBuffer();
            while (m.find()) {
                String inner = m.group(1);
                if (inner.matches("(?s).*<color[^>]*/>.*")) {
                    inner = inner.replaceAll("<color[^>]*/>", "<color rgb=\"" + hex + "\"/>");
                } else {
                    inner = inner + "<color rgb=\"" + hex + "\"/>";
                }
                m.appendReplacement(sbx, java.util.regex.Matcher.quoteReplacement("<font>" + inner + "</font>"));
            }
            m.appendTail(sbx);
            out = sbx.toString();
        }
        return out;
    }

    /** pptx：改主题配色（dk1=正文/lt1=背景/accent1=强调）。 */
    private static String patchTheme(String xml, org.json.JSONObject s) {
        String out = xml;
        out = setSchemeColor(out, "dk1", s.optString("ink", ""));
        out = setSchemeColor(out, "lt1", s.optString("page", ""));
        out = setSchemeColor(out, "accent1", s.optString("accent", ""));
        return out;
    }

    private static String setSchemeColor(String xml, String slot, String color) {
        if (color == null || color.isEmpty()) return xml;
        String hex = (color.startsWith("#") ? color.substring(1) : color).toUpperCase();
        return xml.replaceAll("(<a:" + slot + ">\\s*<a:srgbClr val=\")[0-9A-Fa-f]{6}(\")",
                "$1" + hex + "$2");
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static void copy(InputStream in, ZipOutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
    }
}
