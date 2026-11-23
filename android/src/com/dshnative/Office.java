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

    private static String pdf(File out, JSONObject spec) throws Exception {
        JSONArray pages = spec.optJSONArray("pages");
        if (pages == null || pages.length() == 0) return "错误：pdf 需要至少一页";

        int w = 595, h = 842;   // A4 @72dpi
        PdfDocument doc = new PdfDocument();
        Paint titlePaint = new Paint();
        titlePaint.setAntiAlias(true);
        titlePaint.setTextSize(20);
        titlePaint.setFakeBoldText(true);
        titlePaint.setColor(Color.BLACK);
        Paint bodyPaint = new Paint();
        bodyPaint.setAntiAlias(true);
        bodyPaint.setTextSize(12);
        bodyPaint.setColor(Color.BLACK);

        for (int i = 0; i < pages.length(); i++) {
            JSONObject pg = pages.optJSONObject(i);
            if (pg == null) continue;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, i + 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas cv = page.getCanvas();
            cv.drawColor(Color.WHITE);
            float y = 64;
            String title = pg.optString("title", "");
            if (!title.isEmpty()) {
                cv.drawText(title, 48, y, titlePaint);
                y += 30;
            }
            JSONArray lines = pg.optJSONArray("lines");
            if (lines != null) {
                for (int k = 0; k < lines.length(); k++) {
                    String line = lines.optString(k, "");
                    for (String part : wrap(line, bodyPaint, w - 96)) {
                        if (y > h - 56) break;
                        cv.drawText(part, 48, y, bodyPaint);
                        y += 18;
                    }
                }
            }
            doc.finishPage(page);
        }
        FileOutputStream os = new FileOutputStream(out);
        doc.writeTo(os);
        os.close();
        doc.close();
        return "已生成 pdf：" + out.getAbsolutePath() + "（" + out.length() / 1024 + " KB，"
                + pages.length() + " 页）";
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
