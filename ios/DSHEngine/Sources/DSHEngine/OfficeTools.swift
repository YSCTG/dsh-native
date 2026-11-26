import Foundation

/// Office 文档生成：docx / xlsx。
///
/// 对齐 Android 版的 `office_write`（那边是手写 OOXML，这里是同一套思路的 Swift 版）。
///
/// 范围：**只做生成**。读取（office_read）还没做——iOS 没有解压 API，
/// 读别人的 docx 要自己解 deflate，那是另一摊活。
///
/// 生成的包都用 `ZipWriter`（store 方式）。Excel/Word 打开 store 方式的 zip 完全没问题。
public enum OfficeTools {

    public static func all() -> [any Tool] {
        [Write()]
    }

    // MARK: - office_write

    struct Write: Tool {
        let name = "office_write"
        let description = """
        生成 Word 或 Excel 文件。\
        kind=docx 时用 title/paragraphs/table；kind=xlsx 时用 sheets。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("kind", "string", "docx 或 xlsx"),
                .init("path", "string", "保存路径（工作区内）"),
                .init("title", "string", "docx 的标题（可选）"),
                .init("paragraphs", "array", "docx 的正文段落，字符串数组（可选）"),
                .init("table", "object", "docx 的表格 {headers:[..], rows:[[..]]}（可选）"),
                .init("sheets", "array", "xlsx 的工作表 [{name, rows:[[..]]}]"),
            ], required: ["kind", "path"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let kind = args["kind"]?.stringValue,
                  let rel = args["path"]?.stringValue else {
                throw ToolError.badArgument("需要 kind 和 path")
            }
            let url = try ctx.resolve(rel)

            let bytes: Data
            switch kind.lowercased() {
            case "docx":
                bytes = Self.buildDocx(title: args["title"]?.stringValue ?? "",
                                       paragraphs: Self.stringList(args["paragraphs"]),
                                       table: args["table"])
            case "xlsx":
                bytes = try Self.buildXlsx(sheets: args["sheets"])
            default:
                throw ToolError.badArgument("kind 只支持 docx 或 xlsx")
            }

            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try bytes.write(to: url)
            return "已生成 \(rel)（\(bytes.count) 字节）"
        }
    }

    // MARK: - docx

    static func buildDocx(title: String, paragraphs: [String], table: JSONValue?) -> Data {
        var zip = ZipWriter()
        zip.add("[Content_Types].xml", XML.contentTypes(
            main: "/word/document.xml",
            mainType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))
        zip.add("_rels/.rels", XML.rootRels(target: "word/document.xml"))
        zip.add("word/document.xml", documentXML(title: title, paragraphs: paragraphs, table: table))
        return zip.finish()
    }

    static func documentXML(title: String, paragraphs: [String], table: JSONValue?) -> String {
        var body = ""
        if !title.isEmpty {
            // 标题：加粗 + 18pt（w:sz 单位是半磅）
            body += paragraph(title, bold: true, sizeHalfPoints: 36)
        }
        for p in paragraphs {
            body += paragraph(p, bold: false, sizeHalfPoints: 22)
        }
        if let table, case .object = table {
            body += tableXML(table)
        }

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>\(body)<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>\
        <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>
        </w:document>
        """
    }

    static func paragraph(_ text: String, bold: Bool, sizeHalfPoints: Int) -> String {
        let rPr = """
        <w:rPr>\(bold ? "<w:b/>" : "")<w:sz w:val="\(sizeHalfPoints)"/>\
        <w:szCs w:val="\(sizeHalfPoints)"/></w:rPr>
        """
        return "<w:p><w:pPr>\(rPr)</w:pPr><w:r>\(rPr)<w:t xml:space=\"preserve\">\(XML.escape(text))</w:t></w:r></w:p>"
    }

    static func tableXML(_ table: JSONValue) -> String {
        let headers = stringList(table["headers"])
        var rows: [[String]] = []
        if case .array(let arr) = table["rows"] ?? .null {
            for r in arr {
                rows.append(stringList(r))
            }
        }
        if headers.isEmpty && rows.isEmpty { return "" }

        let border = """
        <w:tblBorders>\
        <w:top w:val="single" w:sz="4" w:color="999999"/>\
        <w:left w:val="single" w:sz="4" w:color="999999"/>\
        <w:bottom w:val="single" w:sz="4" w:color="999999"/>\
        <w:right w:val="single" w:sz="4" w:color="999999"/>\
        <w:insideH w:val="single" w:sz="4" w:color="999999"/>\
        <w:insideV w:val="single" w:sz="4" w:color="999999"/>\
        </w:tblBorders>
        """
        var xml = "<w:tbl><w:tblPr>\(border)</w:tblPr>"
        if !headers.isEmpty {
            xml += rowXML(headers, bold: true)
        }
        for r in rows {
            xml += rowXML(r, bold: false)
        }
        xml += "</w:tbl><w:p/>"
        return xml
    }

    static func rowXML(_ cells: [String], bold: Bool) -> String {
        var xml = "<w:tr>"
        for c in cells {
            xml += "<w:tc><w:tcPr/>\(paragraph(c, bold: bold, sizeHalfPoints: 22))</w:tc>"
        }
        return xml + "</w:tr>"
    }

    // MARK: - xlsx

    static func buildXlsx(sheets: JSONValue?) throws -> Data {
        guard case .array(let sheetArr) = sheets ?? .null, !sheetArr.isEmpty else {
            throw ToolError.badArgument("xlsx 需要 sheets: [{name, rows:[[...]]}]")
        }

        var names: [String] = []
        var sheetXMLs: [String] = []
        for (i, s) in sheetArr.enumerated() {
            let name = s["name"]?.stringValue ?? "Sheet\(i + 1)"
            names.append(name)
            sheetXMLs.append(worksheetXML(s["rows"]))
        }

        var zip = ZipWriter()
        zip.add("[Content_Types].xml", XML.xlsxContentTypes(sheetCount: sheetArr.count))
        zip.add("_rels/.rels", XML.rootRels(target: "xl/workbook.xml"))
        zip.add("xl/workbook.xml", XML.xlsxWorkbook(names: names))
        zip.add("xl/_rels/workbook.xml.rels", XML.xlsxWorkbookRels(count: sheetArr.count))
        for (i, xml) in sheetXMLs.enumerated() {
            zip.add("xl/worksheets/sheet\(i + 1).xml", xml)
        }
        return zip.finish()
    }

    static func worksheetXML(_ rowsValue: JSONValue?) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        """
        guard case .array(let rows) = rowsValue ?? .null else {
            return xml + "</sheetData></worksheet>"
        }
        for (r, rowValue) in rows.enumerated() {
            guard case .array(let cells) = rowValue else { continue }
            xml += "<row r=\"\(r + 1)\">"
            for (c, cell) in cells.enumerated() {
                let ref = "\(columnName(c))\(r + 1)"
                switch cell {
                case .number(let n):
                    if n == n.rounded(), abs(n) < 1e15 {
                        xml += "<c r=\"\(ref)\"><v>\(Int64(n))</v></c>"
                    } else {
                        xml += "<c r=\"\(ref)\"><v>\(n)</v></c>"
                    }
                case .bool(let b):
                    xml += "<c r=\"\(ref)\" t=\"b\"><v>\(b ? 1 : 0)</v></c>"
                case .string(let s):
                    if s.hasPrefix("=") {
                        // 公式：没有求值器，只写公式本身（缓存值留空）
                        xml += "<c r=\"\(ref)\"><f>\(XML.escape(String(s.dropFirst())))</f></c>"
                    } else {
                        xml += "<c r=\"\(ref)\" t=\"inlineStr\">"
                            + "<is><t xml:space=\"preserve\">\(XML.escape(s))</t></is></c>"
                    }
                default:
                    break
                }
            }
            xml += "</row>"
        }
        return xml + "</sheetData></worksheet>"
    }

    static func columnName(_ index: Int) -> String {
        var n = index + 1
        var out = ""
        while n > 0 {
            let rem = (n - 1) % 26
            out = String(UnicodeScalar(65 + rem)!) + out
            n = (n - 1) / 26
        }
        return out
    }

    // MARK: - 辅助

    static func stringList(_ v: JSONValue?) -> [String] {
        guard case .array(let arr) = v ?? .null else {
            if case .string(let s) = v ?? .null { return [s] }
            return []
        }
        return arr.map { item in
            switch item {
            case .string(let s): return s
            case .number(let n): return n == n.rounded() ? String(Int64(n)) : String(n)
            case .bool(let b): return b ? "true" : "false"
            default: return ""
            }
        }
    }
}

// MARK: - XML 片段

enum XML {

    static func escape(_ s: String) -> String {
        var out = ""
        for ch in s {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            default: out.append(ch)
            }
        }
        return out
    }

    static func contentTypes(main: String, mainType: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="\(main)" ContentType="\(mainType)"/>
        </Types>
        """
    }

    static func rootRels(target: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" \
        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
        Target="\(target)"/>
        </Relationships>
        """
    }

    static func xlsxContentTypes(sheetCount: Int) -> String {
        var overrides = """
        <Override PartName="/xl/workbook.xml" \
        ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        """
        for i in 1...max(1, sheetCount) {
            overrides += """
            <Override PartName="/xl/worksheets/sheet\(i).xml" \
            ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            """
        }
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        \(overrides)
        </Types>
        """
    }

    static func xlsxWorkbook(names: [String]) -> String {
        var sheets = ""
        for (i, name) in names.enumerated() {
            sheets += "<sheet name=\"\(escape(name))\" sheetId=\"\(i + 1)\" r:id=\"rId\(i + 1)\"/>"
        }
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets>\(sheets)</sheets>
        </workbook>
        """
    }

    static func xlsxWorkbookRels(count: Int) -> String {
        var rels = ""
        for i in 1...max(1, count) {
            rels += """
            <Relationship Id="rId\(i)" \
            Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" \
            Target="worksheets/sheet\(i).xml"/>
            """
        }
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        \(rels)
        </Relationships>
        """
    }
}
