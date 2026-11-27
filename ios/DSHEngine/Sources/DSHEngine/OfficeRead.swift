import Foundation

/// 读文档：docx / xlsx 的文本抽取（对齐安卓版 `office_read`）。
///
/// iOS 上没法和安卓那样用 ZipFile 流式读，这里用 `ZipReader` 解析 zip + 解 deflate。
public enum OfficeRead {

    public static func all() -> [any Tool] {
        [Read()]
    }

    struct Read: Tool {
        let name = "office_read"
        let description = "读 docx / xlsx 的文字内容（段落、表格、单元格），返回纯文本摘要。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "文件路径"),
                .init("limit", "number", "最多返回多少字符，默认 6000"),
            ], required: ["path"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue else {
                throw ToolError.badArgument("缺少 path")
            }
            let url = try ctx.resolve(rel)
            guard let data = try? Data(contentsOf: url) else {
                throw ToolError.failed("读不到 \(rel)")
            }
            let limit = max(500, args["limit"]?.intValue ?? 6000)
            let lower = rel.lowercased()

            let text: String
            if lower.hasSuffix(".docx") {
                text = try docxText(data)
            } else if lower.hasSuffix(".xlsx") {
                text = try xlsxText(data)
            } else {
                throw ToolError.badArgument("只支持 docx / xlsx")
            }

            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return "（这个文件里没有抽取到文字）" }
            return trimmed.count > limit
                ? String(trimmed.prefix(limit)) + "\n…（已截断，原文 \(trimmed.count) 字符）"
                : trimmed
        }
    }

    // MARK: - docx

    static func docxText(_ data: Data) throws -> String {
        let zip = ZipReader(data: data)
        guard let xmlData = zip.read("word/document.xml"),
              let xml = String(data: xmlData, encoding: .utf8) else {
            throw ToolError.failed("这个 docx 里没有 word/document.xml（或解压失败）")
        }
        // 段落 <w:p> 之间换行；文字在 <w:t>
        var out = ""
        var i = xml.startIndex
        // 段落以 </w:p> 结束：按关闭标签切，`<w:p>` 与 `<w:p ...>` 都能覆盖
        while let end = xml.range(of: "</w:p>", range: i..<xml.endIndex) {
            let block = String(xml[i..<end.lowerBound])
            let text = texts(in: block, tag: "w:t")
            if !text.isEmpty { out += text + "\n" }
            i = end.upperBound
        }
        if out.isEmpty { out = texts(in: xml, tag: "w:t") }
        return out
    }

    // MARK: - xlsx

    static func xlsxText(_ data: Data) throws -> String {
        let zip = ZipReader(data: data)
        var shared: [String] = []
        if let ss = zip.read("xl/sharedStrings.xml"),
           let xml = String(data: ss, encoding: .utf8) {
            // 每个 <si> 是一个字符串，内部可能有多段 <t>
            for chunk in xml.components(separatedBy: "<si>").dropFirst() {
                shared.append(texts(in: chunk.components(separatedBy: "</si>").first ?? chunk, tag: "t"))
            }
        }

        var sheetNames: [String] = []
        if let wb = zip.read("xl/workbook.xml"),
           let xml = String(data: wb, encoding: .utf8) {
            var search = xml[..<xml.endIndex]
            while let r = search.range(of: "<sheet ") {
                search = search[r.upperBound...]
                if let nameRange = attribute("name", in: String(search.prefix(400))) {
                    sheetNames.append(nameRange)
                }
            }
        }

        var out = ""
        let sheets = zip.entries()
            .filter { $0.name.hasPrefix("xl/worksheets/sheet") && $0.name.hasSuffix(".xml") }
            .sorted { $0.name < $1.name }

        for (i, entry) in sheets.enumerated() {
            let title = i < sheetNames.count ? sheetNames[i] : "工作表\(i + 1)"
            out += "【\(title)】\n"
            guard let d = zip.read(entry), let xml = String(data: d, encoding: .utf8) else { continue }

            var search = xml[..<xml.endIndex]
            while let rowRange = search.range(of: "<row ") {
                search = search[rowRange.upperBound...]
                let rowXML = String(search.prefix(20000).components(separatedBy: "</row>").first ?? "")
                var cells: [String] = []
                var cellSearch = rowXML[..<rowXML.endIndex]
                while let c = cellSearch.range(of: "<c ") ?? cellSearch.range(of: "<c>") {
                    cellSearch = cellSearch[c.upperBound...]
                    let cellXML = String(cellSearch.prefix(4000).components(separatedBy: "</c>").first ?? "")
                    let isShared = cellXML.contains("t=\"s\"")
                    if let v = tagValue(cellXML, tag: "v") {
                        if isShared, let idx = Int(v), idx >= 0, idx < shared.count {
                            cells.append(shared[idx])
                        } else {
                            cells.append(v)
                        }
                    } else if let inline = tagValue(cellXML, tag: "t") {
                        cells.append(inline)
                    }
                }
                if !cells.isEmpty { out += cells.joined(separator: " | ") + "\n" }
            }
        }
        return out
    }

    // MARK: - 小工具

    /// 取所有 `<tag ...>body</tag>` 的 body。
    ///
    /// 必须严格匹配标签边界：`<w:t` 是 `<w:tbl` 的前缀，宽松匹配会把表格元素
    /// 当成文本元素（这是第一版的 bug，导致标题被吞掉）。
    static func texts(in chunk: String, tag: String) -> String {
        var out = ""
        var i = chunk.startIndex
        let openPrefix = "<\(tag)"
        let closeTag = "</\(tag)>"

        while let open = chunk.range(of: openPrefix, range: i..<chunk.endIndex) {
            // 标签名后面必须是 '>' 或空白，才算同名标签
            let after = open.upperBound
            if after < chunk.endIndex {
                let c = chunk[after]
                if c != ">" && c != " " && c != "\n" && c != "\t" {
                    i = after
                    continue
                }
            }
            guard let gt = chunk[after...].firstIndex(of: ">") else { break }
            let bodyStart = chunk.index(after: gt)
            guard let end = chunk.range(of: closeTag, range: bodyStart..<chunk.endIndex) else {
                i = bodyStart
                continue
            }
            out += unescape(String(chunk[bodyStart..<end.lowerBound]))
            i = end.upperBound
        }
        return out
    }

    static func tagValue(_ chunk: String, tag: String) -> String? {
        guard let s = chunk.range(of: "<\(tag)>"),
              let e = chunk.range(of: "</\(tag)>", range: s.upperBound..<chunk.endIndex)
        else { return nil }
        return unescape(String(chunk[s.upperBound..<e.lowerBound]))
    }

    static func attribute(_ name: String, in chunk: String) -> String? {
        guard let r = chunk.range(of: "\(name)=\"") else { return nil }
        let rest = chunk[r.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return unescape(String(rest[..<end]))
    }

    static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}
