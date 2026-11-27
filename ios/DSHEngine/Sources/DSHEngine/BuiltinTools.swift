import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 内置原语工具。
///
/// iOS 上这些"原语"就是能力上限：没有 shell，没有解释器，插件只能组合它们 + 提示词。
/// 所以每个原语都做成小而明确的能力，并且全部走 `ToolContext`（路径沙箱 + 审计）。
public enum BuiltinTools {

    /// 注意：fs_list / fs_read / fs_write 由 `FSTools` 提供（功能更全）。
    /// 这里只留纯文本与网络两个原语，避免同名工具重复注册。
    public static func all() -> [any Tool] {
        [TextStats(), FetchURL()]
    }

    // MARK: - 文件

    struct ListFiles: Tool {
        let name = "fs_list"
        let description = "列出工作区内某个目录的条目（名称、是否目录、字节数）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "相对工作区的路径，默认 ."),
            ], required: [])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let rel = args["path"]?.stringValue ?? "."
            let dir = try ctx.resolve(rel)
            let items = try FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey])
            if items.isEmpty { return "（空目录）" }
            var lines: [String] = []
            for item in items.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                let size = (try? item.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                lines.append(isDir ? "\(item.lastPathComponent)/" : "\(item.lastPathComponent)  \(size) B")
            }
            return lines.joined(separator: "\n")
        }
    }

    struct ReadFile: Tool {
        let name = "fs_read"
        let description = "读工作区内一个文本文件（超过 40 KB 只返回前 40 KB）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("path", "string", "文件路径")], required: ["path"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue else { throw ToolError.badArgument("缺少 path") }
            let url = try ctx.resolve(rel)
            let data = try Data(contentsOf: url)
            let limit = 40 * 1024
            let slice = data.prefix(limit)
            let text = String(decoding: slice, as: UTF8.self)
            return data.count > limit ? text + "\n…（已截断，共 \(data.count) 字节）" : text
        }
    }

    struct WriteFile: Tool {
        let name = "fs_write"
        let description = "把文本写进工作区里的文件（父目录会自动创建）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "文件路径"),
                .init("content", "string", "要写入的文本"),
            ], required: ["path", "content"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue,
                  let content = args["content"]?.stringValue else {
                throw ToolError.badArgument("缺少 path 或 content")
            }
            let url = try ctx.resolve(rel)
            let parent = url.deletingLastPathComponent()
            if !FileManager.default.fileExists(atPath: parent.path) {
                try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
            }
            try Data(content.utf8).write(to: url)
            return "已写入 \(url.lastPathComponent)（\(content.utf8.count) 字节）"
        }
    }

    // MARK: - 文本

    struct TextStats: Tool {
        let name = "text_stats"
        let description = "统计一段文本的字符数、行数、词数（中英文混排按非空白分词）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("text", "string", "要统计的文本")], required: ["text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let text = args["text"]?.stringValue else { throw ToolError.badArgument("缺少 text") }
            return Self.summarize(text)
        }

        /// 纯函数，便于单测。
        public static func summarize(_ text: String) -> String {
            let chars = text.count
            let lines = text.isEmpty ? 0 : text.split(separator: "\n", omittingEmptySubsequences: false).count
            let words = text.split(whereSeparator: { $0.isWhitespace || $0.isNewline }).count
            return "字符 \(chars) · 行 \(lines) · 词 \(words)"
        }
    }

    // MARK: - 网络

    struct FetchURL: Tool {
        let name = "net_fetch"
        let description = "抓一个 http(s) 地址并返回正文（最多 8 KB，纯文本）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("url", "string", "要抓取的地址")], required: ["url"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard ctx.networkAllowed else { throw ToolError.denied("当前上下文不允许联网") }
            guard let raw = args["url"]?.stringValue, let url = URL(string: raw) else {
                throw ToolError.badArgument("url 无效")
            }
            let (data, response) = try await URLSession.shared.data(from: url)
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
                throw ToolError.failed("HTTP \(http.statusCode)")
            }
            let text = String(decoding: data.prefix(8 * 1024), as: UTF8.self)
            return text
        }
    }
}
