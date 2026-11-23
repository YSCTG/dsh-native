package com.dshnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Office 文件生成：docx / xlsx / pptx / pdf。
 *
 * 为什么不用 python-docx 那套：它们依赖 lxml（C 扩展），手机上编不出来。
 * 但 docx/xlsx/pptx 本身就是 zip + XML，标准库就够——所以这里直接手写 OOXML。
 *
 * PDF 例外：中文要靠字体，自己写 PDF 得嵌入 CID 字体，太脆。
 * Android 有 android.graphics.pdf.PdfDocument，走系统 Skia 和系统字体，
 * 中文、粗体、换行全都免费。
 *
 * spec 是一段 JSON，具体形状见 office_write 工具的描述。
 */
public final class Office {

    private Office() {}

    public static String write(Context c, String format, String path, String specJson) {
        try {
            JSONObject spec = specJson == null || specJson.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(specJson);
            File out = new File(path);
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            if ("docx".equals(format)) return docx(out, spec);
            if ("xlsx".equals(format)) return xlsx(out, spec);
            if ("pptx".equals(format)) return pptx(out, spec);
            if ("pdf".equals(format)) return pdf(out, spec);
            if ("wav".equals(format) || "m4a".equals(format) || "mp4".equals(format)) {
                return Media.write(out, format, spec);
            }
            return "错误：format 只能是 docx / xlsx / pptx / pdf / wav / m4a / mp4";
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    // ---------------- docx ----------------

    private static String docx(File out, JSONObject spec) throws Exception {
        StringBuilder body = new StringBuilder();
        String title = spec.optString("title", "");
        if (!title.isEmpty()) body.append(para(title, true, 32));
        JSONArray blocks = spec.optJSONArray("blocks");
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b == null) continue;
                String type = b.optString("type", "p");
                if ("table".equals(type)) {
                    body.append(table(b.optJSONArray("rows")));
                } else if ("h1".equals(type)) {
                    body.append(para(b.optString("text", ""), true, 30));
                } else if ("h2".equals(type)) {
                    body.append(para(b.optString("text", ""), true, 26));
                } else if ("bullet".equals(type)) {
                    body.append(para("• " + b.optString("text", ""), false, 22));
                } else {
                    body.append(para(b.optString("text", ""), false, 22));
                }
            }
        }
        String doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";

        List<String[]> files = new ArrayList<>();
        files.add(new String[]{"[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                        + "</Types>"});
        files.add(new String[]{"_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                        + "</Relationships>"});
        files.add(new String[]{"word/document.xml", doc});
        zip(out, files);
        return "已生成 docx：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB）";
    }

    private static String para(String text, boolean bold, int sizeHalfPt) {
        String rPr = "<w:rPr>" + (bold ? "<w:b/>" : "") + "<w:sz w:val=\"" + sizeHalfPt + "\"/></w:rPr>";
        return "<w:p><w:r>" + rPr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String table(JSONArray rows) {
        if (rows == null || rows.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int cols = 0;
        for (int r = 0; r < rows.length(); r++) {
            JSONArray tmp = rows.optJSONArray(r);
            if (tmp != null) cols = Math.max(cols, tmp.length());
        }
        sb.append("<w:tbl><w:tblPr>")
                .append("<w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append("<w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
                .append("</w:tblBorders></w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) sb.append("<w:gridCol w:w=\"3000\"/>");
        sb.append("</w:tblGrid>");
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.optJSONArray(r);
            if (row == null) continue;
            sb.append("<w:tr>");
            for (int c = 0; c < row.length(); c++) {
                boolean head = r == 0;
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr><w:p><w:r>")
                        .append(head ? "<w:rPr><w:b/></w:rPr>" : "")
                        .append("<w:t xml:space=\"preserve\">").append(esc(row.optString(c, "")))
                        .append("</w:t></w:r></w:p></w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
        return sb.toString();
    }

    // ---------------- xlsx ----------------

    private static String xlsx(File out, JSONObject spec) throws Exception {
        JSONArray sheets = spec.optJSONArray("sheets");
        if (sheets == null || sheets.length() == 0) return "错误：xlsx 需要至少一个 sheet";

        List<String[]> files = new ArrayList<>();
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");

        for (int i = 0; i < sheets.length(); i++) {
            JSONObject sh = sheets.optJSONObject(i);
            if (sh == null) continue;
            int n = i + 1;
            String name = esc(sh.optString("name", "Sheet" + n));
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            wb.append("<sheet name=\"").append(name).append("\" sheetId=\"").append(n)
              .append("\" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(n).append(".xml\"/>");
            files.add(new String[]{"xl/worksheets/sheet" + n + ".xml", sheetXml(sh)});
        }
        types.append("</Types>");
        wb.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        rels.append("</Relationships>");

        files.add(new String[]{"[Content_Types].xml", types.toString()});
        files.add(new String[]{"_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>"});
        files.add(new String[]{"xl/workbook.xml", wb.toString()});
        files.add(new String[]{"xl/_rels/workbook.xml.rels", rels.toString()});
        files.add(new String[]{"xl/styles.xml", STYLES});
        zip(out, files);
        return "已生成 xlsx：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB）";
    }

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
            + "<borders count=\"1\"><border/></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"2\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

    private static String sheetXml(JSONObject sh) {
        JSONArray rows = sh.optJSONArray("rows");
        boolean header = sh.optBoolean("header", true);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        JSONArray widths = sh.optJSONArray("widths");
        if (widths != null && widths.length() > 0) {
            sb.append("<cols>");
            for (int c = 0; c < widths.length(); c++) {
                sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                  .append("\" width=\"").append(widths.optInt(c, 12)).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        if (rows != null) {
            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.optJSONArray(r);
                if (row == null) continue;
                boolean bold = header && r == 0;
                sb.append("<row r=\"").append(r + 1).append("\">");
                for (int c = 0; c < row.length(); c++) {
                    Object v = row.opt(c);
                    if (v == null || v == JSONObject.NULL) continue;
                    String ref = col(c) + (r + 1);
                    String style = bold ? " s=\"1\"" : "";
                    if (v instanceof Number) {
                        sb.append("<c r=\"").append(ref).append("\"").append(style).append("><v>")
                          .append(v).append("</v></c>");
                    } else {
                        String s = String.valueOf(v);
                        if (s.startsWith("=")) {
                            sb.append("<c r=\"").append(ref).append("\"").append(style).append("><f>")
                              .append(esc(s.substring(1))).append("</f></c>");
                        } else {
                            sb.append("<c r=\"").append(ref).append("\"").append(style)
                              .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                              .append(esc(s)).append("</t></is></c>");
                        }
                    }
                }
                sb.append("</row>");
            }
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static String col(int i) {
        StringBuilder s = new StringBuilder();
        int n = i;
        while (true) {
            s.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
            if (n < 0) break;
        }
        return s.toString();
    }

    // ---------------- pptx ----------------

    private static String pptx(File out, JSONObject spec) throws Exception {
        JSONArray slides = spec.optJSONArray("slides");
        if (slides == null || slides.length() == 0) return "错误：pptx 需要至少一张 slide";

        List<String[]> files = new ArrayList<>();
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                + "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                + "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                + "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");

        StringBuilder pres = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>"
                + "<p:sldIdLst>");
        StringBuilder presRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");

        for (int i = 0; i < slides.length(); i++) {
            JSONObject s = slides.optJSONObject(i);
            if (s == null) continue;
            int n = i + 1;
            types.append("<Override PartName=\"/ppt/slides/slide").append(n)
                 .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
            pres.append("<p:sldId id=\"").append(256 + n).append("\" r:id=\"rId").append(n + 10).append("\"/>");
            presRels.append("<Relationship Id=\"rId").append(n + 10)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                    .append(n).append(".xml\"/>");
            files.add(new String[]{"ppt/slides/slide" + n + ".xml", slideXml(s)});
            files.add(new String[]{"ppt/slides/_rels/slide" + n + ".xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                            + "</Relationships>"});
        }
        types.append("</Types>");
        pres.append("</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
        presRels.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>");
        presRels.append("</Relationships>");

        files.add(new String[]{"[Content_Types].xml", types.toString()});
        files.add(new String[]{"_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "</Relationships>"});
        files.add(new String[]{"ppt/presentation.xml", pres.toString()});
        files.add(new String[]{"ppt/_rels/presentation.xml.rels", presRels.toString()});
        files.add(new String[]{"ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER});
        files.add(new String[]{"ppt/slideMasters/_rels/slideMaster1.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                        + "</Relationships>"});
        files.add(new String[]{"ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT});
        files.add(new String[]{"ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>"
                        + "</Relationships>"});
        files.add(new String[]{"ppt/theme/theme1.xml", THEME});

        zip(out, files);
        return "已生成 pptx：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB，" + slides.length() + " 页）";
    }

    private static String slideXml(JSONObject s) {
        String title = esc(s.optString("title", ""));
        JSONArray bullets = s.optJSONArray("bullets");
        StringBuilder body = new StringBuilder();
        body.append(shape(2, "标题", 457200, 274638, 8229600, 1143000, title, 2800, true));
        if (bullets != null && bullets.length() > 0) {
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < bullets.length(); i++) {
                t.append("<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"1800\"/><a:t>")
                 .append(esc(bullets.optString(i, ""))).append("</a:t></a:r></a:p>");
            }
            body.append(shapeRaw(3, 457200, 1600200, 8229600, 4525963, t.toString()));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
                + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                + body
                + "</p:spTree></p:cSld></p:sld>";
    }

    private static String shape(int id, String name, int x, int y, int cx, int cy,
                                String text, int size, boolean bold) {
        StringBuilder p = new StringBuilder();
        p.append("<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr lang=\"zh-CN\" sz=\"").append(size)
         .append("\"").append(bold ? " b=\"1\"" : "").append("/><a:t>").append(text)
         .append("</a:t></a:r></a:p>");
        return shapeRaw(id, x, y, cx, cy, p.toString());
    }

    private static String shapeRaw(int id, int x, int y, int cx, int cy, String paragraphs) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"Shape" + id + "\"/>"
                + "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/>"
                + "<a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"
                + "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>" + paragraphs + "</p:txBody></p:sp>";
    }

    private static final String SLIDE_MASTER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
            + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
            + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
            + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
            + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" "
            + "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
            + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
            + "</p:sldMaster>";

    private static final String SLIDE_LAYOUT = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">"
            + "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
            + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
            + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
            + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";

    private static final String THEME = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\">"
            + "<a:themeElements>"
            + "<a:clrScheme name=\"Office\">"
            + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
            + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
            + "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>"
            + "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>"
            + "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>"
            + "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>"
            + "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>"
            + "</a:clrScheme>"
            + "<a:fontScheme name=\"Office\">"
            + "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
            + "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
            + "</a:fontScheme>"
            + "<a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>"
            + "<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
            + "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
            + "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>"
            + "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>"
            + "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
            + "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>"
            + "</a:fmtScheme></a:themeElements></a:theme>";

    // ---------------- pdf ----------------
    /**
     * PDF 排版。
     *
     * 以前只有 title + lines，模型想设计也没有把手，出来就是一份白纸黑字。
     * 现在支持主题色、封面、块级排版（标题/正文/列表/表格/引用/分隔/留白）和页脚页码。
     *
     * spec 形状：
     *   theme  { accent, ink, muted }
     *   margin 内边距（默认 56）
     *   cover  { title, subtitle, meta }
     *   pages  [ { title, blocks:[ {type, text|rows|h} ] } ]   // 兼容旧的 lines
     */
    private static String pdf(File out, JSONObject spec) throws Exception {
        JSONArray pages = spec.optJSONArray("pages");
        JSONObject cover = spec.optJSONObject("cover");
        if ((pages == null || pages.length() == 0) && cover == null) {
            return "错误：pdf 需要 pages 或 cover";
        }

        JSONObject theme = spec.optJSONObject("theme");
        int accent = colorOf(theme, "accent", 0xFF2F6FEB);
        int ink = colorOf(theme, "ink", 0xFF111418);
        int muted = colorOf(theme, "muted", 0xFF6B7280);

        int margin = spec.optInt("margin", 56);
        PdfDocument doc = new PdfDocument();
        Pw pw = new Pw(doc, margin, accent, ink, muted);

        // 封面：大标题 + 副标题 + 一版元信息，左上一条色块
        if (cover != null) {
            pw.newPage();
            pw.cover(cover, pages == null ? 0 : pages.length());
        }

        int pageCount = 0;
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject pg = pages.optJSONObject(i);
                if (pg == null) continue;
                pageCount++;
                pw.newPage();
                String title = pg.optString("title", "");
                if (!title.isEmpty()) pw.pageTitle(title);

                JSONArray blocks = pg.optJSONArray("blocks");
                if (blocks != null) {
                    for (int k = 0; k < blocks.length(); k++) {
                        JSONObject b = blocks.optJSONObject(k);
                        if (b != null) pw.block(b);
                    }
                } else {
                    JSONArray lines = pg.optJSONArray("lines");
                    if (lines != null) {
                        for (int k = 0; k < lines.length(); k++) pw.paragraph(lines.optString(k, ""));
                    }
                }
            }
        }
        int total = pw.finish();
        doc.close();
        return "已生成 pdf：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB，"
                + total + " 页）";
    }

    private static int colorOf(JSONObject o, String key, int def) {
        try {
            if (o != null && o.has(key)) return Color.parseColor(o.optString(key));
        } catch (Throwable ignored) {}
        return def;
    }

    /** PDF 画布封装：管理分页、字号层级、页脚。 */
    private static final class Pw {
        final PdfDocument doc;
        final int margin, accent, ink, muted;
        final int w = 595, h = 842;
        PdfDocument.Page page;
        Canvas cv;
        float y;
        int index;

        Pw(PdfDocument doc, int margin, int accent, int ink, int muted) {
            this.doc = doc;
            this.margin = margin;
            this.accent = accent;
            this.ink = ink;
            this.muted = muted;
        }

        Paint p(float size, boolean bold, int color, boolean mono) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(size);
            p.setColor(color);
            p.setFakeBoldText(bold);
            if (mono) p.setTypeface(android.graphics.Typeface.MONOSPACE);
            else p.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.NORMAL));
            return p;
        }

        void newPage() {
            if (page != null) closePage();
            index++;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, index).create();
            page = doc.startPage(info);
            cv = page.getCanvas();
            cv.drawColor(Color.WHITE);
            y = margin;
        }

        void closePage() {
            if (page == null) return;
            // 页脚：细线 + 页码
            Paint foot = p(8.5f, false, muted, false);
            Paint line = new Paint();
            line.setColor(0xFFE5E7EB);
            line.setStrokeWidth(0.8f);
            cv.drawLine(margin, h - margin + 12, w - margin, h - margin + 12, line);
            String no = String.valueOf(index);
            cv.drawText(no, w - margin - foot.measureText(no), h - margin + 26, foot);
            doc.finishPage(page);
            page = null;
        }

        void ensure(float need) {
            if (y + need > h - margin - 18) newPage();
        }

        void cover(JSONObject c, int pages) {
            cv.drawRect(0, 0, w, 6, paintFill(accent));
            float cy = h * 0.30f;
            String t = c.optString("title", "");
            Paint pt = p(30, true, ink, false);
            for (String s : wrap(t, pt, w - margin * 2)) {
                cv.drawText(s, margin, cy, pt);
                cy += 40;
            }
            cv.drawRect(margin, cy + 6, margin + 56, cy + 10, paintFill(accent));
            cy += 34;
            String sub = c.optString("subtitle", "");
            if (!sub.isEmpty()) {
                Paint ps = p(13, false, muted, false);
                for (String s : wrap(sub, ps, w - margin * 2)) {
                    cv.drawText(s, margin, cy, ps);
                    cy += 20;
                }
            }
            String meta = c.optString("meta", "");
            if (!meta.isEmpty()) {
                Paint pm = p(9.5f, false, muted, false);
                float my = h - margin - 40;
                for (String s : wrap(meta, pm, w - margin * 2)) {
                    cv.drawText(s, margin, my, pm);
                    my += 14;
                }
            }
        }

        void pageTitle(String t) {
            Paint pt = p(17, true, ink, false);
            for (String s : wrap(t, pt, w - margin * 2)) {
                cv.drawText(s, margin, y + 14, pt);
                y += 24;
            }
            y += 6;
            cv.drawRect(margin, y, margin + 40, y + 3, paintFill(accent));
            y += 22;
        }

        void block(JSONObject b) {
            String type = b.optString("type", "p");
            switch (type) {
                case "h1": {
                    ensure(50);
                    Paint pt = p(15, true, ink, false);
                    for (String s : wrap(b.optString("text"), pt, w - margin * 2)) {
                        cv.drawText(s, margin, y + 12, pt);
                        y += 22;
                    }
                    y += 8;
                    break;
                }
                case "h2": {
                    ensure(40);
                    Paint pt = p(12.5f, true, ink, false);
                    for (String s : wrap(b.optString("text"), pt, w - margin * 2)) {
                        cv.drawText(s, margin, y + 10, pt);
                        y += 19;
                    }
                    y += 4;
                    break;
                }
                case "bullet": {
                    Paint pb = p(10.5f, false, ink, false);
                    java.util.List<String> ls = wrap(b.optString("text"), pb, w - margin * 2 - 16);
                    ensure(ls.size() * 17 + 6);
                    cv.drawCircle(margin + 3, y + 4, 1.8f, paintFill(accent));
                    float ly = y;
                    for (String s : ls) {
                        cv.drawText(s, margin + 14, ly + 8, pb);
                        ly += 17;
                    }
                    y = ly + 5;
                    break;
                }
                case "quote": {
                    Paint pq = p(10.5f, false, muted, false);
                    java.util.List<String> ls = wrap(b.optString("text"), pq, w - margin * 2 - 14);
                    ensure(ls.size() * 17 + 10);
                    cv.drawRect(margin, y, margin + 2.5f, y + ls.size() * 17 + 2, paintFill(accent));
                    float ly = y;
                    for (String s : ls) {
                        cv.drawText(s, margin + 12, ly + 8, pq);
                        ly += 17;
                    }
                    y = ly + 8;
                    break;
                }
                case "divider": {
                    ensure(14);
                    Paint line = new Paint();
                    line.setColor(0xFFE5E7EB);
                    line.setStrokeWidth(0.8f);
                    cv.drawLine(margin, y + 4, w - margin, y + 4, line);
                    y += 14;
                    break;
                }
                case "spacer": {
                    y += Math.max(4, b.optInt("h", 12));
                    break;
                }
                case "kv": {
                    JSONArray rows = b.optJSONArray("rows");
                    if (rows == null) break;
                    Paint pk = p(10.5f, true, ink, false);
                    Paint pv = p(10.5f, false, ink, false);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray r = rows.optJSONArray(i);
                        if (r == null) continue;
                        ensure(20);
                        cv.drawText(r.optString(0, ""), margin, y + 10, pk);
                        cv.drawText(r.optString(1, ""),
                                margin + (w - margin * 2) * 0.34f, y + 10, pv);
                        y += 19;
                    }
                    y += 5;
                    break;
                }
                case "table": {
                    table(b.optJSONArray("rows"));
                    break;
                }
                default: {
                    paragraph(b.optString("text"));
                }
            }
        }

        void paragraph(String text) {
            if (text == null || text.isEmpty()) return;
            Paint pb = p(10.5f, false, ink, false);
            for (String s : wrap(text, pb, w - margin * 2)) {
                ensure(18);
                cv.drawText(s, margin, y + 9, pb);
                y += 16.5f;
            }
            y += 6;
        }

        void table(JSONArray rows) {
            if (rows == null || rows.length() == 0) return;
            int cols = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONArray r = rows.optJSONArray(i);
                if (r != null) cols = Math.max(cols, r.length());
            }
            if (cols == 0) return;
            float tw = w - margin * 2;
            float cw = tw / cols;
            Paint ph = p(9.5f, true, ink, false);
            Paint pc = p(9.5f, false, ink, false);
            for (int i = 0; i < rows.length(); i++) {
                JSONArray r = rows.optJSONArray(i);
                if (r == null) continue;
                ensure(22);
                if (i == 0) {
                    cv.drawRect(margin, y, margin + tw, y + 20, paintFill(0xFFF3F4F6));
                }
                cv.drawText(clip(r.optString(0, ""), i == 0 ? ph : pc, cw - 10),
                        margin + 5, y + 14, i == 0 ? ph : pc);
                for (int c = 1; c < cols; c++) {
                    cv.drawText(clip(r.optString(c, ""), pc, cw - 10),
                            margin + cw * c + 5, y + 14, pc);
                }
                Paint line = new Paint();
                line.setColor(0xFFE5E7EB);
                line.setStrokeWidth(0.6f);
                cv.drawLine(margin, y + 20, margin + tw, y + 20, line);
                y += 20;
            }
            y += 10;
        }

        String clip(String s, Paint p, float max) {
            if (p.measureText(s) <= max) return s;
            String t = s;
            while (t.length() > 1 && p.measureText(t + "…") > max) {
                t = t.substring(0, t.length() - 1);
            }
            return t + "…";
        }

        Paint paintFill(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            return p;
        }

        int finish() {
            if (page != null) closePage();
            return index;
        }
    }

    /** 按像素宽度折行，中文按字符、英文按空格优先。 */
    private static List<String> wrap(String line, Paint p, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            cur.append(ch);
            if (p.measureText(cur.toString()) > maxWidth) {
                int cut = cur.length() - 1;
                String s = cur.substring(0, cut);
                int space = s.lastIndexOf(' ');
                if (space > s.length() / 2) {
                    out.add(s.substring(0, space));
                    cur.setLength(0);
                    cur.append(s.substring(space + 1)).append(ch);
                } else {
                    out.add(s);
                    cur.setLength(0);
                    cur.append(ch);
                }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ---------------- 公共 ----------------

    private static void zip(File out, List<String[]> files) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        for (String[] f : files) {
            z.putNextEntry(new ZipEntry(f[0]));
            z.write(f[1].getBytes("UTF-8"));
            z.closeEntry();
        }
        z.close();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
