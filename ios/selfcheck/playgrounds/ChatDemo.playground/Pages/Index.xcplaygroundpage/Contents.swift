// 带界面的单文件版（iPad / Swift Playgrounds）
//
// 由 build/make_playgrounds_bundle.py 生成，不要手改。
// 用法：Playgrounds 新建 Playground，粘贴本文件，运行 → 出现聊天界面。
// 首次运行点右上角钥匙图标填 DeepSeek API key。
//
// 这份代码里的 SwiftUI 部分**从未编译过**（Windows 没有 iOS SDK）。
// Playgrounds 的报错就是我们要的第一手验证信息。

import Foundation
import SwiftUI
import PlaygroundSupport

// ===== 引擎 =====
// ---------- Tool.swift ----------
/// 一轮对话里模型可以调用的工具。
///
/// iOS 版没有 shell、没有 Python 解释器，所以工具必须是**宿主用 Swift 实现的原语**。
/// 插件（用户装的）只能组合这些原语 + 提示词，不能执行任意代码 —— 这是 App Store
/// 2.5.2 的边界，也是我们在 iOS 上做"真 agent"的可行形态。
public protocol Tool: Sendable {
    /// 模型看到的工具名（小写下划线）。
    var name: String { get }
    /// 给模型看的说明：什么时候用、参数含义。
    var description: String { get }
    /// 参数 JSON Schema（与 DeepSeek function calling 兼容）。
    var parameters: JSONSchema { get }
    /// 执行。返回值会作为 tool 消息回给模型。
    func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String
}

/// 工具执行上下文：只给必要的、可审计的能力。
public struct ToolContext: Sendable {
    /// 工作区根目录（沙盒内）。所有相对路径都以它为基准。
    public let workspace: URL
    /// 是否允许联网（插件可以声明不需要）。
    public let networkAllowed: Bool
    /// 审计回调：每次工具调用都会经过它（iOS 版保留这张表）。
    public let audit: @Sendable (String, String) -> Void

    public init(workspace: URL, networkAllowed: Bool = true,
                audit: @escaping @Sendable (String, String) -> Void = { _, _ in }) {
        self.workspace = workspace
        self.networkAllowed = networkAllowed
        self.audit = audit
    }


    /// 路径归一化：解开软链接 + 标准化 + 去掉尾部斜杠。
    /// iOS 上 `/var` 是指向 `/private/var` 的软链接，不归一就会误判越界。
    static func normalized(_ url: URL) -> String {
        var p = url.resolvingSymlinksInPath().standardizedFileURL.path
        while p.count > 1 && p.hasSuffix("/") { p.removeLast() }
        return p
    }

    /// 把模型给的相对路径解析到工作区内，并挡住 `../` 逃逸。
    public func resolve(_ path: String) throws -> URL {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ToolError.badArgument("path 为空") }

        // 先把**根**解析成真实路径（根一定存在，能解析 /var → /private/var）。
        // 然后用真实根去拼候选路径——候选文件通常还不存在，这时候
        // resolvingSymlinksInPath() **不会**解析它的前缀，会得到 /var 开头，
        // 与真实根的 /private/var 对不上，进而把合法路径误判成越界
        // （iPad 上实测：写入/读取/分页/替换全部失败，根因就是这一处）。
        let realRoot = workspace.resolvingSymlinksInPath().standardizedFileURL
        let candidate = trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed).resolvingSymlinksInPath()
            : realRoot.appendingPathComponent(trimmed)

        let rootPath = normalized(realRoot)
        let targetPath = normalized(candidate)
        guard targetPath == rootPath || targetPath.hasPrefix(rootPath + "/") else {
            throw ToolError.badArgument("越界：\(path) 不在工作区内（根 \(rootPath)）")
        }
        return candidate
    }
}

public enum ToolError: Error, CustomStringConvertible {
    case badArgument(String)
    case denied(String)
    case failed(String)

    public var description: String {
        switch self {
        case .badArgument(let s): return "参数错误：\(s)"
        case .denied(let s): return "被拒绝：\(s)"
        case .failed(let s): return "失败：\(s)"
        }
    }
}

/// 极简 JSON 值：工具参数用，避免依赖 Foundation 的 Any 桥接。
public enum JSONValue: Sendable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var intValue: Int? {
        if case .number(let n) = self { return Int(n) }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public subscript(key: String) -> JSONValue? {
        if case .object(let d) = self { return d[key] }
        return nil
    }
}

/// 参数 schema 的最小表达：类型 + 说明 + 必填。
public struct JSONSchema: Sendable {
    public struct Property: Sendable {
        public let name: String
        public let type: String
        public let description: String
        public init(_ name: String, _ type: String, _ description: String) {
            self.name = name; self.type = type; self.description = description
        }
    }

    public let properties: [Property]
    public let required: [String]

    public init(properties: [Property], required: [String]) {
        self.properties = properties
        self.required = required
    }

    public static let none = JSONSchema(properties: [], required: [])

    /// 转成 DeepSeek function calling 需要的 JSON。
    public func jsonObject() -> [String: Any] {
        var props: [String: Any] = [:]
        for p in properties {
            props[p.name] = ["type": p.type, "description": p.description]
        }
        return [
            "type": "object",
            "properties": props,
            "required": required,
        ]
    }
}

// ---------- BuiltinTools.swift ----------
/// 内置原语工具。
///
/// iOS 上这些"原语"就是能力上限：没有 shell，没有解释器，插件只能组合它们 + 提示词。
/// 所以每个原语都做成小而明确的能力，并且全部走 `ToolContext`（路径沙箱 + 审计）。
public enum BuiltinTools {

    public static func all() -> [any Tool] {
        [ListFiles(), ReadFile(), WriteFile(), TextStats(), FetchURL()]
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

// ---------- FSTools.swift ----------
/// 文件工具：对齐 Android 版的 fs_list / fs_read / fs_write / fs_edit / fs_glob / fs_grep。
///
/// iOS 的沙箱决定了这些路径都在 App 容器里（`Documents`），或者在用户显式授权的
/// 外部目录（Files App 给的 security-scoped bookmark）。越界一律拒绝——
/// 和 Android 版一样，`ToolContext.resolve` 负责挡 `../`。
///
/// 和 Android 版的一个差别：Android 的 `fs_read` 带行号（大文件分页），这里保持一致，
/// 因为模型看图省 token，看行号更省。
public enum FSTools {

    public static func all() -> [any Tool] {
        [ListDir(), ReadFile(), WriteFile(), EditFile(), Glob(), Grep()]
    }

    /// 相对工作区的路径。用归一化后的根比较，避免 /var 与 /private/var 不一致
    /// 导致把绝对路径当成相对路径。
    static func relative(_ url: URL, root: String) -> String {
        let p = ToolContext.normalized(url)
        if p.hasPrefix(root + "/") {
            return String(p.dropFirst(root.count + 1))
        }
        return p
    }

    // MARK: - fs_list

    struct ListDir: Tool {
        let name = "fs_list"
        let description = "列目录：名字、类型、字节数。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("path", "string", "相对工作区的目录路径，默认 .")],
                       required: [])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let rel = args["path"]?.stringValue ?? "."
            let dir = try ctx.resolve(rel)
            let items = try FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey])
            if items.isEmpty { return "（空目录）" }
            var lines: [String] = []
            for item in items.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let v = try? item.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
                let isDir = v?.isDirectory ?? false
                let size = v?.fileSize ?? 0
                lines.append(isDir ? "\(item.lastPathComponent)/" : "\(item.lastPathComponent)\t\(size) B")
            }
            return lines.joined(separator: "\n")
        }
    }

    // MARK: - fs_read

    struct ReadFile: Tool {
        let name = "fs_read"
        let description = "按行读文本（带行号）。大文件用 offset/limit 分页。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "文件路径"),
                .init("offset", "number", "从第几行开始（1 起），默认 1"),
                .init("limit", "number", "最多读几行，默认 400"),
            ], required: ["path"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue else { throw ToolError.badArgument("缺少 path") }
            let url = try ctx.resolve(rel)
            guard let data = try? Data(contentsOf: url) else {
                throw ToolError.failed("读不到 \(rel)")
            }
            let text = String(decoding: data, as: UTF8.self)
            let all = text.split(separator: "\n", omittingEmptySubsequences: false)
            let offset = max(1, args["offset"]?.intValue ?? 1)
            let limit = max(1, args["limit"]?.intValue ?? 400)
            if offset > all.count { return "（文件只有 \(all.count) 行）" }

            var out: [String] = []
            let end = min(all.count, offset - 1 + limit)
            for i in (offset - 1)..<end {
                out.append(String(format: "%5d\t%@", i + 1, String(all[i])))
            }
            var tail = ""
            if end < all.count {
                tail = "\n…（共 \(all.count) 行，已显示 \(offset)-\(end)，用 offset 继续）"
            }
            return out.joined(separator: "\n") + tail
        }
    }

    // MARK: - fs_write

    struct WriteFile: Tool {
        let name = "fs_write"
        let description = "写入文本文件（父目录会自动创建，整文件覆盖）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "文件路径"),
                .init("content", "string", "文件内容"),
            ], required: ["path", "content"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue,
                  let content = args["content"]?.stringValue else {
                throw ToolError.badArgument("缺少 path 或 content")
            }
            let url = try ctx.resolve(rel)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try Data(content.utf8).write(to: url)
            return "已写入 \(rel)（\(content.utf8.count) 字节）"
        }
    }

    // MARK: - fs_edit

    struct EditFile: Tool {
        let name = "fs_edit"
        let description = "把文件里的一段文本替换成另一段（要求原文只出现一次，避免改错地方）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "文件路径"),
                .init("old", "string", "要被替换的原文（必须唯一）"),
                .init("new", "string", "替换成什么"),
            ], required: ["path", "old", "new"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let rel = args["path"]?.stringValue,
                  let old = args["old"]?.stringValue,
                  let new = args["new"]?.stringValue else {
                throw ToolError.badArgument("缺少 path / old / new")
            }
            if old.isEmpty { throw ToolError.badArgument("old 不能为空") }
            let url = try ctx.resolve(rel)
            let text = String(decoding: try Data(contentsOf: url), as: UTF8.self)

            let hits = text.components(separatedBy: old).count - 1
            if hits == 0 { throw ToolError.failed("文件里找不到这段原文") }
            if hits > 1 { throw ToolError.badArgument("原文出现 \(hits) 次，请给更长的上下文让它唯一") }

            let updated = text.replacingOccurrences(of: old, with: new)
            try Data(updated.utf8).write(to: url)
            return "已替换 1 处（文件 \(updated.utf8.count) 字节）"
        }
    }

    // MARK: - fs_glob

    struct Glob: Tool {
        let name = "fs_glob"
        let description = "按通配符找文件。支持 * ? 和 **（跨目录）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("pattern", "string", "例如 **/*.swift 或 src/*.md"),
                .init("path", "string", "从哪个目录开始，默认 ."),
            ], required: ["pattern"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let pattern = args["pattern"]?.stringValue else {
                throw ToolError.badArgument("缺少 pattern")
            }
            let base = try ctx.resolve(args["path"]?.stringValue ?? ".")
            let regex = try NSRegularExpression(pattern: Self.globToRegex(pattern))
            var hits: [String] = []
            let rootPath = ToolContext.normalized(ctx.workspace)

            let walker = FileManager.default.enumerator(at: base,
                                                        includingPropertiesForKeys: [.isDirectoryKey])
            while let item = walker?.nextObject() as? URL {
                if hits.count >= 300 { break }
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                if isDir { continue }
                let rel = Self.relative(item, root: rootPath)
                let range = NSRange(rel.startIndex..<rel.endIndex, in: rel)
                if regex.firstMatch(in: rel, range: range) != nil {
                    hits.append(rel)
                }
            }
            return hits.isEmpty ? "（没有匹配）" : hits.sorted().joined(separator: "\n")
        }

        /// `**` 跨目录、`*` 不跨目录、`?` 单字符。其余字符转义。
        static func globToRegex(_ glob: String) -> String {
            var out = "^"
            var i = glob.startIndex
            while i < glob.endIndex {
                let c = glob[i]
                if c == "*" {
                    let next = glob.index(after: i)
                    if next < glob.endIndex, glob[next] == "*" {
                        out += ".*"
                        i = glob.index(after: next)
                        if i < glob.endIndex, glob[i] == "/" { i = glob.index(after: i) }
                        continue
                    }
                    out += "[^/]*"
                } else if c == "?" {
                    out += "[^/]"
                } else if "\\.[]{}()+-^$|".contains(c) {
                    out += "\\" + String(c)
                } else {
                    out += String(c)
                }
                i = glob.index(after: i)
            }
            return out + "$"
        }
    }

    // MARK: - fs_grep

    struct Grep: Tool {
        let name = "fs_grep"
        let description = "按正则搜文件内容，返回 路径:行号: 内容。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("pattern", "string", "正则表达式"),
                .init("path", "string", "目录或文件，默认 ."),
                .init("include", "string", "只看这些文件，例如 **/*.java（可选）"),
            ], required: ["pattern"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let pattern = args["pattern"]?.stringValue else {
                throw ToolError.badArgument("缺少 pattern")
            }
            let regex = try NSRegularExpression(pattern: pattern)
            let base = try ctx.resolve(args["path"]?.stringValue ?? ".")
            var includeRegex: NSRegularExpression? = nil
            if let inc = args["include"]?.stringValue, !inc.isEmpty {
                includeRegex = try NSRegularExpression(pattern: Self.Glob.globToRegex(inc))
            }

            let rootPath = ToolContext.normalized(ctx.workspace)
            var files: [URL] = []
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: base.path, isDirectory: &isDir), isDir.boolValue {
                let walker = FileManager.default.enumerator(at: base,
                                                            includingPropertiesForKeys: [.isDirectoryKey])
                while let item = walker?.nextObject() as? URL {
                    if files.count >= 800 { break }
                    let d = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                    if d { continue }
                    files.append(item)
                }
            } else {
                files = [base]
            }

            var out: [String] = []
            var scanned = 0
            for file in files {
                if out.count >= 200 { break }
                let rel = Self.relative(file, root: rootPath)
                if let inc = includeRegex {
                    let r = NSRange(rel.startIndex..<rel.endIndex, in: rel)
                    if inc.firstMatch(in: rel, range: r) == nil { continue }
                }
                guard let data = try? Data(contentsOf: file), data.count <= 2 * 1024 * 1024 else { continue }
                scanned += 1
                let text = String(decoding: data, as: UTF8.self)
                for (i, line) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
                    let s = String(line)
                    let r = NSRange(s.startIndex..<s.endIndex, in: s)
                    if regex.firstMatch(in: s, range: r) != nil {
                        out.append("\(rel):\(i + 1): \(s.prefix(200))")
                        if out.count >= 200 { break }
                    }
                }
            }
            if out.isEmpty { return "（扫了 \(scanned) 个文件，没有匹配）" }
            return out.joined(separator: "\n")
        }
    }
}

// ---------- SessionStore.swift ----------
/// 会话存储：一条消息一行 JSON（JSONL），和 Android 版同一套格式。
///
/// 为什么不用 SwiftData / Core Data：会话是**只追加**的日志，读的时候整段回放，
/// 不需要查询、不需要索引。JSONL 的好处是崩在半路也不会毁掉整个文件，
/// 而且导出的 Markdown、跨端同步都简单。
public struct SessionStore: Sendable {

    public struct Message: Codable, Sendable, Equatable {
        public var role: String          // system / user / assistant / tool
        public var content: String
        public var toolCallId: String?   // role == tool 时用
        public var name: String?         // 工具名
        public var createdAt: Date

        public init(role: String, content: String,
                    toolCallId: String? = nil, name: String? = nil,
                    createdAt: Date = Date()) {
            self.role = role
            self.content = content
            self.toolCallId = toolCallId
            self.name = name
            self.createdAt = createdAt
        }

        enum CodingKeys: String, CodingKey {
            case role, content, name
            case toolCallId = "tool_call_id"
            case createdAt = "created_at"
        }
    }

    public struct Session: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var title: String
        public var updated: Date
        public init(id: String = SessionStore.newId(), title: String = "新会话", updated: Date = Date()) {
            self.id = id
            self.title = title
            self.updated = updated
        }
    }

    private let root: URL

    public init(root: URL) {
        self.root = root
    }

    public static func newId() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        return f.string(from: Date()) + "-" + String(UUID().uuidString.prefix(4)).lowercased()
    }

    // MARK: - 路径

    private func bodyURL(_ id: String) throws -> URL {
        let dir = root.appendingPathComponent("sessions")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // id 来自我们自己生成，但仍然挡一下路径分隔符，避免被外部数据带歪
        let safe = id.replacingOccurrences(of: "/", with: "_")
                      .replacingOccurrences(of: "..", with: "_")
        return dir.appendingPathComponent(safe + ".jsonl")
    }

    private func indexURL() throws -> URL {
        let dir = root.appendingPathComponent("sessions")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("index.json")
    }

    // MARK: - 写

    /// 追加一条消息（崩溃安全：一次 write 一行）。
    public func append(_ message: Message, to id: String) throws {
        let url = try bodyURL(id)
        var line = try JSONEncoder().encode(message)
        line.append(0x0A)                                   // '\n'
        if FileManager.default.fileExists(atPath: url.path) {
            let h = try FileHandle(forWritingTo: url)
            defer { try? h.close() }
            try h.seekToEnd()
            try h.write(contentsOf: line)
        } else {
            try line.write(to: url)
        }
    }

    /// 读整段。
    public func load(_ id: String) throws -> [Message] {
        let url = try bodyURL(id)
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        var out: [Message] = []
        for line in data.split(separator: 0x0A) {
            if line.isEmpty { continue }
            if let m = try? decoder.decode(Message.self, from: Data(line)) {
                out.append(m)
            }
            // 半行（崩在半路）直接跳过——不要因为最后一行坏了就丢掉整个会话
        }
        return out
    }

    // MARK: - 索引（列表）

    public func list() throws -> [Session] {
        let url = try indexURL()
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Session].self, from: data)) ?? []
    }

    public func save(_ sessions: [Session]) throws {
        let url = try indexURL()
        let data = try JSONEncoder().encode(sessions)
        try data.write(to: url, options: .atomic)
    }

    /// 新建会话并写进索引（置顶在最前）。
    public func create(title: String = "新会话") throws -> Session {
        var s = Session(title: title)
        s.updated = Date()
        var all = try list()
        all.insert(s, at: 0)
        try save(all)
        return s
    }

    /// 更新标题与时间；标题为空时从首条用户消息里取。
    public func touch(_ id: String, firstUserText: String? = nil) throws {
        var all = try list()
        guard let i = all.firstIndex(where: { $0.id == id }) else { return }
        all[i].updated = Date()
        if let t = firstUserText, all[i].title == "新会话" {
            all[i].title = Self.titleFrom(t)
        }
        try save(all)
    }

    public func delete(_ id: String) throws {
        try save(try list().filter { $0.id != id })
        if let url = try? bodyURL(id), FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// 由首条用户消息生成标题：去掉换行、截到 20 字。
    public static func titleFrom(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ")
                       .trimmingCharacters(in: .whitespacesAndNewlines)
        if flat.isEmpty { return "新会话" }
        return flat.count <= 20 ? flat : String(flat.prefix(20)) + "…"
    }
}

// ---------- AgentLoop.swift ----------
/// 一轮对话的循环：模型 → 工具 → 模型 …，直到模型不再要工具。
///
/// 这里刻意不碰 UI，也不碰网络细节（网络在 DeepSeekClient 里），
/// 这样它能在 macOS / Windows 上用 `swift test` 直接验证。
public actor AgentLoop {

    public struct TurnResult: Sendable {
        public var text: String
        public var toolCalls: [String]
        public var finishedReason: String?
    }

    private let tools: [String: any Tool]
    private let client: DeepSeekClient
    private let ctx: ToolContext

    public init(client: DeepSeekClient, tools: [any Tool], ctx: ToolContext) {
        var map: [String: any Tool] = [:]
        for t in tools { map[t.name] = t }
        self.tools = map
        self.client = client
        self.ctx = ctx
    }

    /// 工具清单 → DeepSeek 需要的 `tools` 数组。
    public func toolSchemaJSON() -> [[String: Any]] {
        tools.values.map { t in
            [
                "type": "function",
                "function": [
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters.jsonObject(),
                ],
            ]
        }
    }

    /// 跑一轮：把 `messages` 当作完整历史（最后一条是新的 user 消息）。
    /// 每产生一次工具调用就执行并把结果追加进历史，最多 `maxSteps` 步防死循环。
    public func runTurn(messages: inout [[String: Any]],
                        maxSteps: Int = 8,
                        onText: @escaping @Sendable (String) -> Void = { _ in },
                        onTool: (@Sendable (String, String, String) -> Void)? = nil) async throws -> TurnResult {
        var collectedText = ""
        var called: [String] = []
        var finish: String?

        for _ in 0..<maxSteps {
            var stepText = ""
            var pending: [String: (name: String, args: String)] = [:]

            try await client.stream(messages: messages, tools: toolSchemaJSON()) { ev in
                switch ev {
                case .delta(let d):
                    stepText += d
                    onText(d)
                case .reasoning:
                    break
                case .toolCall(let id, let name, let arguments):
                    pending[id] = (name, arguments)
                case .done(let reason):
                    finish = reason
                case .usage:
                    break
                }
            }

            collectedText += stepText
            if pending.isEmpty {
                // 没有工具调用 → 这一轮结束，把助手消息写进历史
                messages.append(["role": "assistant", "content": stepText])
                return TurnResult(text: collectedText, toolCalls: called, finishedReason: finish)
            }

            // 有工具调用：先把带 tool_calls 的助手消息落历史（顺序不能反，否则 API 400）
            var calls: [[String: Any]] = []
            for (id, info) in pending {
                calls.append([
                    "id": id, "type": "function",
                    "function": ["name": info.name, "arguments": info.args],
                ])
            }
            messages.append(["role": "assistant", "content": stepText, "tool_calls": calls])

            for (id, info) in pending {
                called.append(info.name)
                let output: String
                if let tool = tools[info.name] {
                    do {
                        let args = Self.parseArguments(info.args)
                        ctx.audit(info.name, info.args)
                        output = try await tool.run(args, in: ctx)
                    } catch {
                        output = "错误：\(error)"
                    }
                } else {
                    output = "错误：没有这个工具 \(info.name)"
                }
                onTool?(info.name, info.args, output)      // 让上层能持久化并显示 chip
                messages.append(["role": "tool", "tool_call_id": id, "content": output])
            }
        }

        return TurnResult(text: collectedText, toolCalls: called, finishedReason: "max_steps")
    }

    /// 模型给的 arguments 是一段 JSON 字符串；解析失败就当空对象，别让一轮直接崩掉。
    static func parseArguments(_ raw: String) -> JSONValue {
        guard let data = raw.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data)
        else { return .object([:]) }
        return convert(any)
    }

    static func convert(_ any: Any) -> JSONValue {
        switch any {
        case let s as String: return .string(s)
        case let n as NSNumber:
            // JSONSerialization 会把 true/false 也解析成 NSNumber
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .number(n.doubleValue)
        case let a as [Any]: return .array(a.map(convert))
        case let d as [String: Any]: return .object(d.mapValues(convert))
        default: return .null
        }
    }
}

// ---------- DeepSeekClient.swift ----------
#if canImport(FoundationNetworking)
import FoundationNetworking   // Linux / Windows 上 URLSession 在这里
#endif

/// DeepSeek 的 chat/completions 客户端（流式）。
///
/// iOS 版与 Android 版的关键差别不在协议，而在**运行时**：iOS 上不存在能跑 shell /
/// Python 的通道，所以这个客户端 + 宿主实现的原语，就是整个 agent 的全部能力边界。
public struct DeepSeekClient: Sendable {

    public struct Config: Sendable {
        public var baseURL: URL
        public var apiKey: String
        public var model: String
        public init(baseURL: URL = URL(string: "https://api.deepseek.com")!,
                    apiKey: String,
                    model: String = "deepseek-chat") {
            self.baseURL = baseURL
            self.apiKey = apiKey
            self.model = model
        }
    }

    public enum Event: Sendable {
        case delta(String)          // 正文增量
        case reasoning(String)      // 思考增量（thinking 模式）
        case toolCall(id: String, name: String, arguments: String)
        case done(finishReason: String?)
        case usage(prompt: Int, completion: Int)
    }

    private let config: Config
    private let session: URLSession

    public init(config: Config, session: URLSession = .shared) {
        self.config = config
        self.session = session
    }

    /// 发一轮请求，按事件流回调。`tools` 传空数组就不可能产生工具调用。
    public func stream(messages: [[String: Any]],
                       tools: [[String: Any]],
                       onEvent: @escaping @Sendable (Event) -> Void) async throws {
        var body: [String: Any] = [
            "model": config.model,
            "messages": messages,
            "stream": true,
        ]
        if !tools.isEmpty {
            body["tools"] = tools
            body["tool_choice"] = "auto"
        }

        var req = URLRequest(url: config.baseURL.appendingPathComponent("chat/completions"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 120

        let (bytes, response) = try await session.bytes(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw ClientError.http(http.statusCode)
        }

        // SSE：逐行 `data: {...}`，`data: [DONE]` 结束
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload == "[DONE]" { break }
            guard let data = payload.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { continue }
            if let usage = obj["usage"] as? [String: Any] {
                onEvent(.usage(prompt: usage["prompt_tokens"] as? Int ?? 0,
                               completion: usage["completion_tokens"] as? Int ?? 0))
            }
            guard let choices = obj["choices"] as? [[String: Any]],
                  let first = choices.first else { continue }
            if let delta = first["delta"] as? [String: Any] {
                if let r = delta["reasoning_content"] as? String, !r.isEmpty {
                    onEvent(.reasoning(r))
                }
                if let c = delta["content"] as? String, !c.isEmpty {
                    onEvent(.delta(c))
                }
                if let calls = delta["tool_calls"] as? [[String: Any]] {
                    for c in calls {
                        let id = c["id"] as? String ?? ""
                        let fn = c["function"] as? [String: Any] ?? [:]
                        onEvent(.toolCall(id: id,
                                          name: fn["name"] as? String ?? "",
                                          arguments: fn["arguments"] as? String ?? ""))
                    }
                }
            }
            if let fr = first["finish_reason"] as? String {
                onEvent(.done(finishReason: fr))
            }
        }
    }

    public enum ClientError: Error, CustomStringConvertible {
        case http(Int)
        public var description: String {
            switch self {
            case .http(let code): return "HTTP \(code)"
            }
        }
    }
}

// ---------- WebTools.swift ----------
#if canImport(WebKit)
import WebKit

/// 内嵌浏览器工具：对齐 Android 版的 browser_open / browser_text / browser_js /
/// browser_click / browser_type。
///
/// iOS 上这件事比 Android 那边干净：WKWebView 是系统原生组件，页面可见、可调试，
/// 用 `evaluateJavaScript` 就能读文本、点按钮、填表单。
///
/// 三个实现要点：
/// 1. WKWebView 必须在主线程创建与操作 → 整个类标 `@MainActor`
/// 2. 不用 WKNavigationDelegate 判断加载完成（那要写一堆代理方法），改成轮询 `isLoading`
/// 3. JS 结果统一转成字符串，并对长度设上限（网页里的对象可能巨大）
@MainActor
public final class WebBrowser {

    public static let shared = WebBrowser()

    private let webView: WKWebView
    private static let maxText = 20_000

    init() {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        webView = WKWebView(frame: .zero, configuration: config)
        webView.customUserAgent =
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }

    // MARK: - 加载

    /// 打开网址，等加载结束（或超时）。返回提示文字。
    public func open(_ urlString: String, timeout: Double = 25) async -> String {
        guard let url = URL(string: urlString) else { return "网址无效：\(urlString)" }
        webView.load(URLRequest(url: url))
        return await waitLoaded(timeout: timeout, label: urlString)
    }

    /// 直接加载一段 HTML（自检用：不依赖网络）。
    public func loadHTML(_ html: String) async -> String {
        webView.loadHTMLString(html, baseURL: nil)
        return await waitLoaded(timeout: 10, label: "内联 HTML")
    }

    private func waitLoaded(timeout: Double, label: String) async -> String {
        let start = Date()
        // 先给一次机会让 isLoading 变真，否则可能在 load 还没开始时就读到 false
        try? await Task.sleep(nanoseconds: 60_000_000)
        while webView.isLoading, Date().timeIntervalSince(start) < timeout {
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        if webView.isLoading { return "加载超时（\(Int(timeout)) 秒）：\(label)" }
        let title = webView.title ?? ""
        return title.isEmpty ? "已加载：\(label)" : "已加载：\(title)"
    }

    // MARK: - 读取

    public func text() async -> String {
        let raw = (try? await js("document.body ? document.body.innerText : ''")) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= Self.maxText { return trimmed }
        return String(trimmed.prefix(Self.maxText)) + "\n…（已截断，原文 \(trimmed.count) 字符）"
    }

    public func title() async -> String {
        (try? await js("document.title")) ?? ""
    }

    public func url() -> String {
        webView.url?.absoluteString ?? ""
    }

    // MARK: - 执行 JS

    public func js(_ code: String) async throws -> String {
        let value: Any? = try await withCheckedThrowingContinuation { cont in
            webView.evaluateJavaScript(code) { result, error in
                if let error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume(returning: result)
                }
            }
        }
        return Self.stringify(value)
    }

    static func stringify(_ value: Any?) -> String {
        switch value {
        case .none: return ""
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        case let a as [Any]: return a.map { stringify($0) }.joined(separator: "\n")
        case let d as [String: Any]:
            let data = (try? JSONSerialization.data(withJSONObject: d,
                                                    options: [.prettyPrinted, .sortedKeys])) ?? Data()
            return String(decoding: data, as: UTF8.self)
        default: return "\(value!)"
        }
    }
}

/// 浏览器工具集。
public enum WebTools {

    public static func all() -> [any Tool] {
        [Open(), Text(), JS(), Click(), TypeIn()]
    }

    // MARK: - 工具实现

    struct Open: Tool {
        let name = "browser_open"
        let description = "在内嵌浏览器里打开网址，返回标题。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("url", "string", "完整网址（含 https://）")],
                       required: ["url"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard ctx.networkAllowed else { throw ToolError.denied("当前上下文不允许联网") }
            guard let url = args["url"]?.stringValue, !url.isEmpty else {
                throw ToolError.badArgument("缺少 url")
            }
            return await WebBrowser.shared.open(url)
        }
    }

    struct Text: Tool {
        let name = "browser_text"
        let description = "读当前页面的正文（纯文本，最多 2 万字符）。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            await WebBrowser.shared.text()
        }
    }

    struct JS: Tool {
        let name = "browser_js"
        let description = "在当前页面执行一段 JavaScript，返回结果（字符串化）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("script", "string", "要执行的 JS")], required: ["script"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let script = args["script"]?.stringValue else { throw ToolError.badArgument("缺少 script") }
            do {
                return try await WebBrowser.shared.js(script)
            } catch {
                throw ToolError.failed("JS 执行失败：\(error.localizedDescription)")
            }
        }
    }

    struct Click: Tool {
        let name = "browser_click"
        let description = "用 CSS 选择器点一个元素。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("selector", "string", "CSS 选择器，例如 #submit")],
                       required: ["selector"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let sel = args["selector"]?.stringValue else { throw ToolError.badArgument("缺少 selector") }
            let code = """
            (function(){
              var el = document.querySelector(\(jsLiteral(sel)));
              if (!el) return 'NOT_FOUND';
              el.scrollIntoView({block:'center'});
              el.click();
              return 'CLICKED';
            })()
            """
            let r = try await WebBrowser.shared.js(code)
            return r == "NOT_FOUND" ? "没找到元素：\(sel)" : "已点击 \(sel)"
        }
    }

    struct TypeIn: Tool {
        let name = "browser_type"
        let description = "往输入框里填文字（会触发 input/change 事件，React 页面也能识别）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("selector", "string", "输入框的 CSS 选择器"),
                .init("text", "string", "要填入的文字"),
            ], required: ["selector", "text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let sel = args["selector"]?.stringValue,
                  let text = args["text"]?.stringValue else {
                throw ToolError.badArgument("缺少 selector 或 text")
            }
            let code = """
            (function(){
              var el = document.querySelector(\(jsLiteral(sel)));
              if (!el) return 'NOT_FOUND';
              var proto = el instanceof HTMLTextAreaElement
                  ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, \(jsLiteral(text)));
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return 'TYPED';
            })()
            """
            let r = try await WebBrowser.shared.js(code)
            return r == "NOT_FOUND" ? "没找到输入框：\(sel)" : "已填入 \(sel)"
        }
    }

    /// 把字符串安全地嵌进 JS 字面量（转义引号、反斜杠、换行）。
    static func jsLiteral(_ s: String) -> String {
        var out = "\""
        for ch in s {
            switch ch {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default: out.append(ch)
            }
        }
        return out + "\""
    }
}
#endif

// ---------- SystemTools.swift ----------
#if canImport(UIKit)
import UIKit
#endif
#if canImport(UserNotifications)
import UserNotifications
#endif

/// 系统能力工具：剪贴板、通知、日程、打开链接。
///
/// 对齐 Android 版的 `clip_read` `clip_write` `notify` `schedule_add/list/remove`。
///
/// 与 Android 的差别要说清：
/// - **剪贴板**：iOS 上是 `UIPasteboard`，读写都要用户在场（读会弹"允许粘贴"提示，系统行为）
/// - **通知**：`UNUserNotificationCenter` 需要用户授权一次；被拒时工具返回明确原因，不假装成功
/// - **日程**：这里用"本地定时通知"实现提醒；真正的日历事件要用 EventKit（需要日历权限）
/// - **打开链接**：`UIApplication.open`；iOS 不接受任意 App 启动，只认 URL Scheme / Universal Link
public enum SystemTools {

    public static func all() -> [any Tool] {
        [ClipRead(), ClipWrite(), Notify(), ScheduleAdd(), ScheduleList(), ScheduleRemove()]
    }

    // MARK: - 剪贴板

    struct ClipRead: Tool {
        let name = "clip_read"
        let description = "读剪贴板里的文字。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            #if canImport(UIKit)
            let s = await MainActor.run { UIPasteboard.general.string ?? "" }
            return s.isEmpty ? "（剪贴板没有文字；iOS 也可能因为没给「允许粘贴」而读不到）" : s
            #else
            throw ToolError.denied("这个平台没有剪贴板接口")
            #endif
        }
    }

    struct ClipWrite: Tool {
        let name = "clip_write"
        let description = "把文字写进剪贴板。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("text", "string", "要复制的文字")], required: ["text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let text = args["text"]?.stringValue else { throw ToolError.badArgument("缺少 text") }
            #if canImport(UIKit)
            await MainActor.run { UIPasteboard.general.string = text }
            return "已复制 \(text.count) 字符到剪贴板"
            #else
            throw ToolError.denied("这个平台没有剪贴板接口")
            #endif
        }
    }

    // MARK: - 通知

    struct Notify: Tool {
        let name = "notify"
        let description = "发一条本地通知（首次会请求通知权限）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("title", "string", "标题"),
                .init("body", "string", "正文"),
            ], required: ["body"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let body = args["body"]?.stringValue else { throw ToolError.badArgument("缺少 body") }
            let title = args["title"]?.stringValue ?? "DSH"
            #if canImport(UserNotifications)
            let center = UNUserNotificationCenter.current()
            let granted: Bool = await withCheckedContinuation { cont in
                center.requestAuthorization(options: [.alert, .sound]) { ok, _ in
                    cont.resume(returning: ok)
                }
            }
            if !granted { return "没拿到通知权限（到 设置 → 通知 里允许后再试）" }

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let request = UNNotificationRequest(identifier: UUID().uuidString,
                                                content: content, trigger: nil)
            let error: Error? = await withCheckedContinuation { cont in
                center.add(request) { err in cont.resume(returning: err) }
            }
            if let error { return "发送失败：\(error.localizedDescription)" }
            return "已发送通知：\(title)"
            #else
            return "这个平台没有通知接口"
            #endif
        }
    }

    // MARK: - 日程（本地提醒）

    /// 提醒存在工作区的 `schedule.json` 里；通知只负责按时弹出。
    struct Item: Codable, Identifiable {
        let id: String
        var title: String
        var at: Date
        var fired: Bool
    }

    static func storeURL(_ ctx: ToolContext) -> URL {
        ctx.workspace.appendingPathComponent("schedule.json")
    }

    static func loadItems(_ ctx: ToolContext) -> [Item] {
        guard let data = try? Data(contentsOf: storeURL(ctx)) else { return [] }
        return (try? JSONDecoder().decode([Item].self, from: data)) ?? []
    }

    static func saveItems(_ ctx: ToolContext, _ items: [Item]) {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: storeURL(ctx))
    }

    struct ScheduleAdd: Tool {
        let name = "schedule_add"
        let description = "加一条定时提醒。after_seconds 表示多少秒之后。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("title", "string", "提醒内容"),
                .init("after_seconds", "number", "多少秒后提醒"),
            ], required: ["title", "after_seconds"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let title = args["title"]?.stringValue,
                  let seconds = args["after_seconds"]?.intValue, seconds > 0 else {
                throw ToolError.badArgument("需要 title 和正数 after_seconds")
            }
            let when = Date().addingTimeInterval(TimeInterval(seconds))
            let item = Item(id: String(UUID().uuidString.prefix(8)),
                            title: title, at: when, fired: false)
            var items = Self_load(ctx)
            items.append(item)
            Self_save(ctx, items)

            #if canImport(UserNotifications)
            // 尽力排一条通知；没权限也不影响"提醒已记录"
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            if settings.authorizationStatus == .authorized {
                let content = UNMutableNotificationContent()
                content.title = "提醒"
                content.body = title
                content.sound = .default
                let trigger = UNTimeIntervalNotificationTrigger(
                    timeInterval: TimeInterval(seconds), repeats: false)
                let request = UNNotificationRequest(
                    identifier: item.id, content: content, trigger: trigger)
                _ = await withCheckedContinuation { (cont: CheckedContinuation<Error?, Never>) in
                    center.add(request) { err in cont.resume(returning: err) }
                }
            }
            #endif
            return "已加提醒（\(item.id)）：\(title) · \(seconds) 秒后"
        }
    }

    struct ScheduleList: Tool {
        let name = "schedule_list"
        let description = "列出所有提醒（按时间排序）。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let items = Self_load(ctx).sorted { $0.at < $1.at }
            if items.isEmpty { return "（没有提醒）" }
            let f = DateFormatter()
            f.dateFormat = "MM-dd HH:mm:ss"
            return items.map { i in
                "[\(i.id)] \(f.string(from: i.at)) · \(i.title)\(i.fired ? " · 已触发" : "")"
            }.joined(separator: "\n")
        }
    }

    struct ScheduleRemove: Tool {
        let name = "schedule_remove"
        let description = "按 id 删除提醒。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("id", "string", "提醒 id")], required: ["id"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["id"]?.stringValue else { throw ToolError.badArgument("缺少 id") }
            var items = Self_load(ctx)
            let before = items.count
            items.removeAll { $0.id == id }
            Self_save(ctx, items)
            #if canImport(UserNotifications)
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: [id])
            #endif
            return before == items.count ? "没有 id 为 \(id) 的提醒" : "已删除 \(id)"
        }
    }

    // 内部的 load/save 用不同名字，避免和同名的静态方法混淆
    private static func Self_load(_ ctx: ToolContext) -> [Item] { loadItems(ctx) }
    private static func Self_save(_ ctx: ToolContext, _ items: [Item]) { saveItems(ctx, items) }
}

// ---------- ZipWriter.swift ----------
/// 最小 ZIP 写入器（store 方式，不压缩）。
///
/// 为什么需要：docx / xlsx / pptx 都是 zip 包，而 Foundation 在 iOS 上**没有写 zip 的 API**
/// （只有解压的方向）。`Archive`/`AppleArchive` 不通用。所以自己拼字节。
///
/// 为什么用 store 不用 deflate：deflate 要走 Compression 框架、还要处理窗口和 flush，
/// 出错点很多；而这些文档的 XML 本身只有几 KB，不压缩也就大几 KB。
/// 正确性优先——先能打开，再谈体积。
///
/// 格式细节（都是小端）：
///   本地文件头 0x04034b50 → 数据 → … → 中央目录 0x02014b50 → 目录结束 0x06054b50
public struct ZipWriter {

    private struct Entry {
        let name: String
        let crc: UInt32
        let size: Int
        let offset: Int
    }

    private var body = Data()
    private var entries: [Entry] = []

    public init() {}

    /// 追加一个文件。文件名用 `/` 分隔（zip 规范）。
    public mutating func add(_ name: String, _ bytes: Data) {
        let crc = ZipWriter.crc32(bytes)
        let offset = body.count
        let nameBytes = Data(name.utf8)

        // 本地文件头
        body.append(le32(0x04034b50))
        body.append(le16(20))            // version needed
        body.append(le16(0))             // flags
        body.append(le16(0))             // method: 0 = store
        body.append(le16(0))             // mod time
        body.append(le16(0x21))          // mod date 1980-01-01
        body.append(le32(crc))
        body.append(le32(UInt32(bytes.count)))
        body.append(le32(UInt32(bytes.count)))
        body.append(le16(UInt16(nameBytes.count)))
        body.append(le16(0))             // extra len
        body.append(nameBytes)
        body.append(bytes)

        entries.append(Entry(name: name, crc: crc, size: bytes.count, offset: offset))
    }

    public mutating func add(_ name: String, _ text: String) {
        add(name, Data(text.utf8))
    }

    /// 收尾，返回完整 zip 字节。
    public mutating func finish() -> Data {
        let cdStart = body.count
        for e in entries {
            let nameBytes = Data(e.name.utf8)
            body.append(le32(0x02014b50))
            body.append(le16(20))        // version made by
            body.append(le16(20))        // version needed
            body.append(le16(0))         // flags
            body.append(le16(0))         // method
            body.append(le16(0))         // time
            body.append(le16(0x21))      // date
            body.append(le32(e.crc))
            body.append(le32(UInt32(e.size)))
            body.append(le32(UInt32(e.size)))
            body.append(le16(UInt16(nameBytes.count)))
            body.append(le16(0))         // extra
            body.append(le16(0))         // comment
            body.append(le16(0))         // disk
            body.append(le16(0))         // internal attrs
            body.append(le32(0))         // external attrs
            body.append(le32(UInt32(e.offset)))
            body.append(nameBytes)
        }
        let cdSize = body.count - cdStart

        body.append(le32(0x06054b50))
        body.append(le16(0))             // disk
        body.append(le16(0))             // disk with CD
        body.append(le16(UInt16(entries.count)))
        body.append(le16(UInt16(entries.count)))
        body.append(le32(UInt32(cdSize)))
        body.append(le32(UInt32(cdStart)))
        body.append(le16(0))             // comment len
        return body
    }

    // MARK: - 小工具

    private func le16(_ v: UInt16) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 2)
    }

    private func le32(_ v: UInt32) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 4)
    }

    /// 标准 CRC-32（多项式 0xEDB88320）。zip 的每个条目都要它。
    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                if crc & 1 == 1 {
                    crc = (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >>= 1
                }
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

// ---------- OfficeTools.swift ----------
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

// ---------- Workflow.swift ----------
/// 工作流 / 子任务调度。
///
/// 对齐 Android 版的 `workflow` `subagent` `team_*` 那一类能力。它们的共同点是：
/// **同时跑多个模型调用，再按顺序把结果收回来**。这一层和「调模型」解耦——
/// 具体怎么跑由注入的 `runner` 决定，所以能拿假的 runner 做离线测试。
///
/// 三条必须成立的语义（也是最容易写错的）：
/// 1. 并发上限：在飞的子任务不超过 `concurrency`
/// 2. 顺序保持：结果按输入顺序返回，而不是完成顺序
/// 3. 错误隔离：一个子任务失败不影响其它；也可以选择遇错即停
public actor WorkflowRunner {

    public struct Item: Sendable {
        public let id: String
        public let prompt: String
        public init(id: String, prompt: String) {
            self.id = id
            self.prompt = prompt
        }
    }

    public enum Outcome: Sendable {
        case ok(String)
        case failed(String)

        public var text: String {
            switch self {
            case .ok(let s): return s
            case .failed(let s): return s
            }
        }

        public var isFailure: Bool {
            if case .failed = self { return true }
            return false
        }
    }

    public struct Result: Sendable {
        public let id: String
        public let outcome: Outcome
        public init(id: String, outcome: Outcome) {
            self.id = id
            self.outcome = outcome
        }
    }

    /// 跑一个子任务。可以是真模型调用，也可以是测试里的假实现。
    public typealias Runner = @Sendable (Item) async -> Outcome

    private let concurrency: Int
    private let runner: Runner
    private var maxInFlight = 0
    private var inFlight = 0

    public init(concurrency: Int = 3, runner: @escaping Runner) {
        self.concurrency = max(1, concurrency)
        self.runner = runner
    }

    /// 观察到的最大并发数（测试用：证明上限真的生效，而不是顺序执行）。
    public func observedMaxInFlight() -> Int { maxInFlight }

    /// 跑一批。`stopOnError` 为真时，出错后不再启动新的子任务（已在飞的会跑完）。
    public func run(_ items: [Item], stopOnError: Bool = false) async -> [Result] {
        maxInFlight = 0
        inFlight = 0
        var outcomes = [Outcome?](repeating: nil, count: items.count)
        let limit = concurrency
        let r = runner

        await withTaskGroup(of: (Int, Outcome).self) { group in
            var next = 0
            var stopped = false

            // 先把窗口填满
            let head = min(limit, items.count)
            for i in 0..<head {
                let item = items[i]
                group.addTask {
                    await self.trackStart()
                    let outcome = await r(item)
                    await self.trackEnd()
                    return (i, outcome)
                }
            }
            next = head

            // 完成一个补一个：这样既守住上限，又不是死板的分批
            while let (index, outcome) = await group.next() {
                outcomes[index] = outcome
                if stopOnError, outcome.isFailure {
                    stopped = true
                }
                if stopped {
                    while next < items.count {
                        outcomes[next] = .failed("跳过：前面的子任务失败后停止")
                        next += 1
                    }
                } else if next < items.count {
                    let i = next
                    next += 1
                    let item = items[i]
                    group.addTask {
                        await self.trackStart()
                        let outcome = await r(item)
                        await self.trackEnd()
                        return (i, outcome)
                    }
                }
            }
        }

        var results: [Result] = []
        for (i, o) in outcomes.enumerated() {
            if let o {
                results.append(Result(id: items[i].id, outcome: o))
            }
        }
        return results
    }

    private func trackStart() {
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
    }

    private func trackEnd() {
        inFlight = max(0, inFlight - 1)
    }
}

// ---------- PublishTools.swift ----------
/// 产物发布：web app、Swift 项目、IPA 包。
///
/// 三条路对应"在手机上做出能跑的东西"的三种现实：
///
/// 1. **web app** —— 纯手机闭环：写完直接在内置 WebView 跑，不需要签名
/// 2. **Swift 项目** —— 交给 iPad 上的 Swift Playgrounds 编译运行（编译/签名是 Apple 做的）
/// 3. **IPA 打包** —— 只能打包，**不能签名**；未签名的 IPA 装不上任何设备
///
/// 第 3 条必须把限制写进输出里，否则用户会以为拿到了能装的东西。
public enum PublishTools {

    public static func all() -> [any Tool] {
        [WebAppNew(), WebAppList(), SwiftProjectNew(), IpaPack()]
    }

    // MARK: - web app

    /// web app 的模板：能直接跑，演示了最小可用的交互与持久化。
    static func webAppHTML(title: String) -> String {
        """
        <!doctype html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <title>\(XML.escape(title))</title>
        <style>
          :root { color-scheme: dark; }
          body { margin:0; font-family: -apple-system, system-ui, sans-serif;
                 background:#111318; color:#e8eaed; padding:20px; }
          h1 { font-size:20px; margin:0 0 6px; }
          .hint { color:#9aa0a6; font-size:13px; margin-bottom:18px; }
          button { background:#2a6df4; color:#fff; border:0; border-radius:10px;
                   padding:12px 16px; font-size:15px; }
          #count { font-size:40px; font-weight:600; margin:16px 0; }
        </style>
        </head>
        <body>
        <h1>\(XML.escape(title))</h1>
        <div class="hint">这是一个本地 web app：文件在工作区里，改完重新打开就是新版本。</div>
        <div id="count">0</div>
        <button id="btn">点一下 +1</button>
        <script>
          var n = Number(localStorage.getItem('count') || 0);
          var el = document.getElementById('count');
          el.textContent = n;
          document.getElementById('btn').onclick = function () {
            n += 1;
            el.textContent = n;
            localStorage.setItem('count', String(n));
          };
        </script>
        </body>
        </html>
        """
    }

    struct WebAppNew: Tool {
        let name = "webapp_new"
        let description = "新建一个本地 web app（工作区 apps/<名字>/index.html），可直接运行。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "目录名，建议英文或中文短语"),
                .init("title", "string", "显示标题（可选）"),
            ], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let appName = args["name"]?.stringValue, !appName.isEmpty else {
                throw ToolError.badArgument("缺少 name")
            }
            let title = args["title"]?.stringValue ?? appName
            let dir = try ctx.resolve("apps/\(appName)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let index = dir.appendingPathComponent("index.html")
            try Data(PublishTools.webAppHTML(title: title).utf8).write(to: index)
            return "已创建 web app：apps/\(appName)/index.html（\(title)）"
        }
    }

    struct WebAppList: Tool {
        let name = "webapp_list"
        let description = "列出工作区里已有的 web app。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let dir = try ctx.resolve("apps")
            guard let items = try? FileManager.default.contentsOfDirectory(at: dir,
                                        includingPropertiesForKeys: [.isDirectoryKey]) else {
                return "（还没有 web app）"
            }
            var out: [String] = []
            for item in items.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let index = item.appendingPathComponent("index.html")
                if FileManager.default.fileExists(atPath: index.path) {
                    out.append(item.lastPathComponent)
                }
            }
            return out.isEmpty ? "（还没有 web app）" : out.joined(separator: "\n")
        }
    }

    // MARK: - Swift 项目（交给 Playgrounds）

    static func packageSwift(name: String) -> String {
        """
        // swift-tools-version: 5.9
        import PackageDescription

        let package = Package(
            name: "\(name)",
            platforms: [.iOS(.v16)],
            products: [.executable(name: "\(name)", targets: ["\(name)"])],
            targets: [.executableTarget(name: "\(name)", path: "Sources/\(name)")]
        )
        """
    }

    static func appSwift(title: String) -> String {
        """
        import SwiftUI

        @main
        struct \(Self.swiftIdentifier(title))App: App {
            var body: some Scene {
                WindowGroup { ContentView() }
            }
        }

        struct ContentView: View {
            @State private var count = 0
            var body: some View {
                VStack(spacing: 18) {
                    Text("\(title)")
                        .font(.title2).bold()
                    Text("\\(count)")
                        .font(.system(size: 56, weight: .semibold, design: .rounded))
                    Button("点一下 +1") { count += 1 }
                        .buttonStyle(.borderedProminent)
                }
                .padding()
            }
        }
        """
    }

    /// 把标题变成合法的 Swift 标识符（只保留字母数字）。
    static func swiftIdentifier(_ text: String) -> String {
        var out = ""
        for ch in text {
            // 只保留 ASCII 字母数字：中文标题直接当类型名虽然合法，但拼出
            // 「记账App」这种名字在 Playgrounds 里排查起来更绕
            if ch.isASCII, ch.isLetter || ch.isNumber {
                out.append(ch)
            }
        }
        if out.isEmpty || out.first!.isNumber { out = "App" + out }
        return out
    }

    struct SwiftProjectNew: Tool {
        let name = "swiftpm_new"
        let description = """
        生成一个 SwiftUI App 的 Swift Package 项目（Package.swift + Sources）。\
        生成后可用「文件」交给 Swift Playgrounds 打开并运行。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "项目名（同时用作目录名）"),
                .init("title", "string", "界面上显示的标题（可选）"),
            ], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let project = args["name"]?.stringValue, !project.isEmpty else {
                throw ToolError.badArgument("缺少 name")
            }
            let title = args["title"]?.stringValue ?? project
            let root = try ctx.resolve("projects/\(project)")
            let srcDir = root.appendingPathComponent("Sources/\(project)")
            try FileManager.default.createDirectory(at: srcDir, withIntermediateDirectories: true)

            try Data(PublishTools.packageSwift(name: project).utf8)
                .write(to: root.appendingPathComponent("Package.swift"))
            try Data(PublishTools.appSwift(title: title).utf8)
                .write(to: srcDir.appendingPathComponent("\(project)App.swift"))

            let readme = """
            # \(title)

            用 Swift Playgrounds 打开这个目录：

            1. 在「文件」App 里找到本目录（工作区 `projects/\(project)`）
            2. 长按 → 共享 → 用 Playgrounds 打开（或先压缩成 zip 再打开）
            3. Playgrounds 会自己编译运行；它没有 XCTest，所以本项目的验证靠手动

            为什么编译这一步要交给 Playgrounds：iOS 上没有可用的 Swift 编译器，
            而且未签名的二进制不允许执行。Apple 只给了自家 Playgrounds 这个能力。
            """
            try Data(readme.utf8).write(to: root.appendingPathComponent("README.md"))

            return "已生成 Swift 项目：projects/\(project)（Package.swift + Sources）"
        }
    }

    // MARK: - IPA 打包

    static func infoPlist(bundleId: String, name: String, version: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
        "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>CFBundleIdentifier</key><string>\(XML.escape(bundleId))</string>
            <key>CFBundleName</key><string>\(XML.escape(name))</string>
            <key>CFBundleDisplayName</key><string>\(XML.escape(name))</string>
            <key>CFBundleExecutable</key><string>\(XML.escape(name))</string>
            <key>CFBundleShortVersionString</key><string>\(XML.escape(version))</string>
            <key>CFBundleVersion</key><string>1</string>
            <key>CFBundlePackageType</key><string>APPL</string>
            <key>MinimumOSVersion</key><string>16.0</string>
        </dict>
        </plist>
        """
    }

    struct IpaPack: Tool {
        let name = "ipa_pack"
        let description = """
        把当前项目打包成 .ipa 结构（Payload/名字.app/）。\
        注意：产出的是**未签名**包，装不上任何设备，只能用于存档或拿到电脑上签名。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "App 名（同时是可执行文件名）"),
                .init("bundle_id", "string", "例如 com.example.demo"),
                .init("version", "string", "版本号，默认 1.0"),
            ], required: ["name", "bundle_id"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let appName = args["name"]?.stringValue,
                  let bundleId = args["bundle_id"]?.stringValue else {
                throw ToolError.badArgument("需要 name 和 bundle_id")
            }
            let version = args["version"]?.stringValue ?? "1.0"

            var zip = ZipWriter()
            let base = "Payload/\(appName).app/"
            zip.add(base + "Info.plist",
                    PublishTools.infoPlist(bundleId: bundleId, name: appName, version: version))
            zip.add(base + "UNSIGNED-README.txt", """
            这个 IPA 是**未签名**的。

            - iOS 只运行通过校验的签名二进制，未签名的包在任何设备上都装不上
            - 想真正安装需要：Apple 签发的证书 + provisioning profile，在 macOS 上用
              `codesign` + `security cms` 签完再安装（或走 App Store / TestFlight）
            - 手机上无法完成签名：私钥在电脑钥匙串或 Apple 服务器上

            本文件的用途：检查包结构、存档、或拿到电脑上继续处理。
            """)

            let dist = try ctx.resolve("dist")
            try FileManager.default.createDirectory(at: dist, withIntermediateDirectories: true)
            let out = dist.appendingPathComponent("\(appName).ipa")
            let data = zip.finish()
            try data.write(to: out)

            return """
            已打包 dist/\(appName).ipa（\(data.count) 字节）
            ⚠️ 未签名：装不上任何设备。签名需要 Apple 证书，手机上做不到。
            """
        }
    }
}

// ===== 界面 =====

// ---------- ChatModel.swift ----------
import Foundation
#if canImport(Combine)
import Combine
#endif

/// 界面状态的唯一持有者。
///
/// 分成两层的原因：`AgentService` 是 actor（并发安全），SwiftUI 要的是主线程上的
/// `@Published`。这一层负责把 AsyncStream 的事件翻成界面状态，别让视图直接 await。
@MainActor
public final class ChatModel: ObservableObject {

    public struct Row: Identifiable, Equatable {
        public let id = UUID()
        public var role: String          // user / assistant / tool / error
        public var text: String
        public var isStreaming: Bool = false
    }

    @Published public private(set) var rows: [Row] = []
    @Published public private(set) var sessions: [SessionStore.Session] = []
    @Published public private(set) var busy = false
    @Published public var input = ""
    @Published public var draftKey = ""
    @Published public private(set) var status = ""
    @Published public private(set) var currentSession: String?

    private var service: AgentService?
    private var store: SessionStore?
    private var runTask: Task<Void, Never>?

    public nonisolated init() {}

    // MARK: - 启动

    /// 首次进入：准备目录、读会话列表。
    public func bootstrap() {
        guard store == nil else { return }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let ws = docs.appendingPathComponent("workspace", isDirectory: true)
        try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true)
        store = SessionStore(root: docs)
        refreshSessions()
        status = apiKey().isEmpty ? "先填 API key" : "就绪"
        if let first = sessions.first {
            open(first.id)
        }
    }

    // MARK: - key

    private func apiKey() -> String {
        (UserDefaults.standard.string(forKey: "dsh.apiKey") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public func saveKey() {
        let k = draftKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else { return }
        UserDefaults.standard.set(k, forKey: "dsh.apiKey")
        draftKey = ""
        service = nil                      // 换 key 后重建
        status = "已保存 key"
    }

    // MARK: - 会话

    public func refreshSessions() {
        sessions = (try? store?.list()) ?? []
    }

    public func open(_ id: String) {
        currentSession = id
        rows = []
        guard let store else { return }
        for m in (try? store.load(id)) ?? [] {
            switch m.role {
            case "user": rows.append(Row(role: "user", text: m.content))
            case "assistant": rows.append(Row(role: "assistant", text: m.content))
            default: break                       // 工具返回不上屏，避免刷屏
            }
        }
    }

    public func newSession() {
        guard let store else { return }
        if let s = try? store.create() {
            refreshSessions()
            open(s.id)
        }
    }

    // MARK: - 发送

    public func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        guard let sessionId = currentSession ?? ensureSession() else { return }

        let key = apiKey()
        input = ""
        rows.append(Row(role: "user", text: text))

        // 没有 key 时进「演示模式」：本地回显。这样界面（气泡、滚动、状态）
        // 不依赖网络和余额也能验证，省得第一次跑就卡在 key 上。
        if key.isEmpty {
            rows.append(Row(role: "assistant",
                            text: "（演示模式）收到：\(text)\n\n没有填 API key，所以这是本地回显。"
                                + "填上 key 后就会真的调用 DeepSeek。"))
            status = "演示模式（未填 key）"
            return
        }

        busy = true
        rows.append(Row(role: "assistant", text: "", isStreaming: true))
        status = "思考中"

        let svc = serviceFor(key: key)
        runTask = Task { [weak self] in
            guard let self else { return }
            let stream = await svc.send(text, to: sessionId)
            for await event in stream {
                await self.handle(event)
                if Task.isCancelled { break }
            }
            await self.finishRun()
        }
    }

    public func stop() {
        runTask?.cancel()
        runTask = nil
        Task { [service] in await service?.cancel() }
        busy = false
    }

    private func handle(_ event: AgentService.Event) {
        switch event {
        case .started:
            status = "思考中"
        case .delta(let chunk):
            guard let i = rows.lastIndex(where: { $0.role == "assistant" }) else { return }
            rows[i].text += chunk
        case .toolStarted(let name, _):
            status = "运行工具 \(name)"
        case .toolFinished(let name, _):
            rows.append(Row(role: "tool", text: "· \(name)"))
            status = "工具 \(name) 完成"
        case .finished:
            if let i = rows.lastIndex(where: { $0.role == "assistant" }) {
                rows[i].isStreaming = false
            }
            status = "完成"
        case .failed(let message):
            rows.append(Row(role: "error", text: message))
            status = "出错"
        case .reasoning:
            break
        }
    }

    private func finishRun() {
        busy = false
        refreshSessions()
    }

    // MARK: - 内部

    private func ensureSession() -> String? {
        guard let store else { return nil }
        if let s = try? store.create() {
            refreshSessions()
            currentSession = s.id
            return s.id
        }
        return nil
    }

    private func serviceFor(key: String) -> AgentService {
        if let service { return service }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let ws = docs.appendingPathComponent("workspace", isDirectory: true)
        let client = DeepSeekClient(config: .init(apiKey: key))
        let ctx = ToolContext(workspace: ws)
        let tools = BuiltinTools.all() + FSTools.all() + WebTools.all() + SystemTools.all() + OfficeTools.all() + PublishTools.all()
        let svc = AgentService(client: client,
                               store: store ?? SessionStore(root: docs),
                               context: ctx,
                               tools: tools)
        service = svc
        return svc
    }
}

// ---------- ChatView.swift ----------
import Foundation

#if canImport(SwiftUI)
import SwiftUI

/// 聊天界面。
///
/// 只做三件事：把 `ChatModel.rows` 画出来、把输入框的内容交出去、把状态显示在顶部。
/// 所有引擎交互都发生在 `ChatModel` 里——视图不 await、不碰网络。
@MainActor
public struct ChatView: View {

    @ObservedObject private var model: ChatModel
    @State private var showKeySheet = false

    public init(model: ChatModel) {
        _model = ObservedObject(wrappedValue: model)
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                transcript
                Divider()
                composer
            }
            .navigationTitle(model.status.isEmpty ? "DSH" : model.status)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        model.newSession()
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showKeySheet = true
                    } label: {
                        Image(systemName: "key")
                    }
                }
            }
            .sheet(isPresented: $showKeySheet) {
                keySheet
            }
        }
        .onAppear { model.bootstrap() }
    }

    // MARK: - 消息列表

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(model.rows) { row in
                        bubble(row).id(row.id)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            .onChange(of: model.rows.count) { _ in
                if let last = model.rows.last {
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func bubble(_ row: ChatModel.Row) -> some View {
        switch row.role {
        case "user":
            HStack {
                Spacer(minLength: 40)
                Text(row.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Color.accentColor.opacity(0.18))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .textSelection(.enabled)
            }
        case "tool":
            Text(row.text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.leading, 4)
        case "error":
            Text(row.text)
                .font(.caption)
                .foregroundStyle(.red)
        default:
            VStack(alignment: .leading, spacing: 4) {
                Text(row.text.isEmpty && row.isStreaming ? "…" : row.text)
                    .textSelection(.enabled)
                if row.isStreaming {
                    ProgressView().scaleEffect(0.7)
                }
            }
        }
    }

    // MARK: - 输入

    private var composer: some View {
        HStack(spacing: 10) {
            TextField("说点什么…", text: $model.input, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Color.secondary.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 18))

            if model.busy {
                Button {
                    model.stop()
                } label: {
                    Image(systemName: "stop.circle.fill").font(.title2)
                }
            } else {
                Button {
                    model.send()
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(model.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: - key

    private var keySheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("DeepSeek API key")
                    .font(.headline)
                SecureField("sk-…", text: $model.draftKey)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Text("只存在本机的 UserDefaults 里；换成 Keychain 是下一步。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(18)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showKeySheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        model.saveKey()
                        showKeySheet = false
                    }
                }
            }
        }
    }
}
#endif

// ---------- WebAppBridge.swift ----------
import Foundation

#if canImport(WebKit)
import WebKit
#endif
#if canImport(UserNotifications)
import UserNotifications
#endif

/// web app 的能力桥：让本地 HTML 能读工作区文件、发通知。
///
/// 设计要点：
/// 1. **作用域只到该 app 自己的目录**——桥的根目录是 `apps/<名字>/`，
///    不是整个工作区。web app 是被运行的代码，不该有超出自己目录的权限。
/// 2. Promise 形式：JS 侧 `await dsh.readFile('a.txt')`，
///    走 `postMessage` → 原生处理 → `evaluateJavaScript` 回填。
/// 3. 只读/写文本。二进制、删除、执行一律不给——这是"应用"不是"终端"。
public final class WebAppBridge: NSObject, WKScriptMessageHandler {

    public static let handlerName = "dsh"

    private let root: URL
    private weak var webView: WKWebView?

    public init(root: URL, webView: WKWebView?) {
        self.root = root
        self.webView = webView
        super.init()
    }

    /// 注入到每个页面的桥脚本（在文档开始时加载）。
    public static func bootstrapScript() -> String {
        """
        (function(){
          var callbacks = {};
          window.__dshResolve = function(r){
            var cb = callbacks[r.id];
            if (cb) { delete callbacks[r.id]; cb(r); }
          };
          function call(op, args){
            return new Promise(function(resolve){
              var id = String(Date.now()) + Math.random().toString(16).slice(2);
              callbacks[id] = resolve;
              try {
                window.webkit.messageHandlers.\(handlerName).postMessage({id: id, op: op, args: args || {}});
              } catch (e) {
                resolve({ok: false, error: '没有原生桥：' + e});
              }
            });
          }
          window.dsh = {
            readFile: function(p){ return call('read', {path: p}); },
            writeFile: function(p, text){ return call('write', {path: p, text: text}); },
            list: function(p){ return call('list', {path: (p || '.')}); },
            notify: function(title, body){ return call('notify', {title: title, body: body}); }
          };
        })();
        """
    }

    // MARK: - 消息处理

    public func userContentController(_ controller: WKUserContentController,
                                      didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let id = body["id"] as? String,
              let op = body["op"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]

        switch op {
        case "read":
            let path = args["path"] as? String ?? ""
            reply(id: id, result: readText(path))
        case "write":
            let path = args["path"] as? String ?? ""
            let text = args["text"] as? String ?? ""
            reply(id: id, result: writeText(path, text))
        case "list":
            let path = args["path"] as? String ?? "."
            reply(id: id, result: listDir(path))
        case "notify":
            let title = args["title"] as? String ?? "DSH"
            let text = args["body"] as? String ?? ""
            postNotification(title: title, body: text)
            reply(id: id, result: ["ok": true, "value": "已请求通知"])
        default:
            reply(id: id, result: ["ok": false, "error": "不支持的操作：\(op)"])
        }
    }

    // MARK: - 操作

    /// 把相对路径解析到 app 目录内，挡 `../` 逃逸。
    private func resolve(_ path: String) -> URL? {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate = trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed)
            : root.appendingPathComponent(trimmed.isEmpty ? "." : trimmed)
        let rootPath = root.standardizedFileURL.path
        let target = candidate.standardizedFileURL.path
        guard target == rootPath || target.hasPrefix(rootPath + "/") else { return nil }
        return candidate
    }

    private func readText(_ path: String) -> [String: Any] {
        guard let url = resolve(path),
              let data = try? Data(contentsOf: url) else {
            return ["ok": false, "error": "读不到 \(path)"]
        }
        if data.count > 512 * 1024 {
            return ["ok": false, "error": "文件太大（超过 512 KB）"]
        }
        return ["ok": true, "value": String(decoding: data, as: UTF8.self)]
    }

    private func writeText(_ path: String, _ text: String) -> [String: Any] {
        guard let url = resolve(path) else {
            return ["ok": false, "error": "路径越界：\(path)"]
        }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
            return ["ok": true, "value": "已写入 \(text.utf8.count) 字节"]
        } catch {
            return ["ok": false, "error": "写入失败：\(error.localizedDescription)"]
        }
    }

    private func listDir(_ path: String) -> [String: Any] {
        guard let url = resolve(path),
              let items = try? FileManager.default.contentsOfDirectory(atPath: url.path) else {
            return ["ok": false, "error": "读不到目录 \(path)"]
        }
        return ["ok": true, "value": items.sorted()]
    }

    private func postNotification(title: String, body: String) {
        #if canImport(UserNotifications)
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            let req = UNNotificationRequest(identifier: UUID().uuidString,
                                            content: content, trigger: nil)
            center.add(req) { _ in }
        }
        #endif
    }

    /// 把结果回填给页面的 Promise。
    ///
    /// 做法：把结果序列化成 JSON，再**转义成单引号 JS 字符串**里的字面量，
    /// 页面侧 `JSON.parse` 还原。这样引号/换行/反斜杠都不用逐个猜。
    private func reply(id: String, result: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(result),
              let data = try? JSONSerialization.data(withJSONObject: result),
              var json = String(data: data, encoding: .utf8) else { return }
        json = json
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript("window.__dshResolve(JSON.parse('\(json)'))") { _, _ in }
        }
    }
}

// ---------- WebAppLauncher.swift ----------
import Foundation

#if canImport(SwiftUI) && canImport(WebKit)
import SwiftUI
import WebKit

/// 本地 web app 的启动器：列出 `apps/*/index.html`，点一个就运行。
@MainActor
public struct WebAppLauncher: View {

    @State private var apps: [Entry] = []
    @State private var showingNew = false
    @State private var newName = ""
    @State private var running: Entry?

    public struct Entry: Identifiable, Hashable {
        public var id: String { dir.lastPathComponent }
        public let dir: URL
        public var title: String
    }

    private var workspace: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("workspace", isDirectory: true)
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            List {
                if apps.isEmpty {
                    Text("还没有 web app。点右上角 + 新建一个。")
                        .foregroundStyle(.secondary)
                }
                ForEach(apps) { app in
                    Button {
                        running = app
                    } label: {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 9)
                                .fill(Color.accentColor.opacity(0.18))
                                .frame(width: 38, height: 38)
                                .overlay(Text(String(app.title.prefix(1)))
                                            .font(.headline))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.title)
                                Text(app.dir.lastPathComponent)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "play.circle").font(.title3)
                        }
                    }
                }
            }
            .navigationTitle("应用")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        newName = ""
                        showingNew = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingNew) { newAppSheet }
            .sheet(item: $running) { app in
                NavigationStack {
                    WebAppRunner(appDir: app.dir, title: app.title)
                        .navigationTitle(app.title)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarTrailing) {
                                Button("完成") { running = nil }
                            }
                        }
                }
            }
            .task { reload() }
        }
    }

    private var newAppSheet: some View {
        NavigationStack {
            Form {
                TextField("名字（目录名）", text: $newName)
                    .autocorrectionDisabled()
            }
            .navigationTitle("新建 web app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showingNew = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !name.isEmpty {
                            create(name)
                            reload()
                        }
                        showingNew = false
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    // MARK: - 数据

    private func reload() {
        let appsDir = workspace.appendingPathComponent("apps", isDirectory: true)
        let fm = FileManager.default
        guard let dirs = try? fm.contentsOfDirectory(at: appsDir,
                                                     includingPropertiesForKeys: [.isDirectoryKey])
        else {
            apps = []
            return
        }
        var found: [Entry] = []
        for dir in dirs {
            let index = dir.appendingPathComponent("index.html")
            guard fm.fileExists(atPath: index.path) else { continue }
            let html = (try? String(contentsOf: index, encoding: .utf8)) ?? ""
            found.append(Entry(dir: dir, title: Self.title(from: html) ?? dir.lastPathComponent))
        }
        apps = found.sorted { $0.title < $1.title }
    }

    private func create(_ name: String) {
        let dir = workspace.appendingPathComponent("apps/\(name)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let html = PublishTools.webAppHTML(title: name)
        try? Data(html.utf8).write(to: dir.appendingPathComponent("index.html"))
    }

    /// 从 `<title>` 里取显示名。
    static func title(from html: String) -> String? {
        guard let start = html.range(of: "<title>"),
              let end = html.range(of: "</title>", range: start.upperBound..<html.endIndex)
        else { return nil }
        let t = String(html[start.upperBound..<end.lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

/// 跑一个本地 web app：WKWebView + 能力桥。
@MainActor
public struct WebAppRunner: UIViewRepresentable {

    let appDir: URL
    let title: String

    public init(appDir: URL, title: String) {
        self.appDir = appDir
        self.title = title
    }

    /// 协调器负责**持有**桥对象：桥需要 webView 才能回填结果，
    /// 而 webView 在 makeUIView 里才建出来，所以两边都得留在这里。
    public final class Coordinator {
        var bridge: WebAppBridge?
        init() {}
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true

        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: WebAppBridge.bootstrapScript(),
                                              injectionTime: .atDocumentStart,
                                              forMainFrameOnly: true))
        config.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: config)
        let bridge = WebAppBridge(root: appDir, webView: webView)
        controller.add(bridge, name: WebAppBridge.handlerName)
        context.coordinator.bridge = bridge

        let index = appDir.appendingPathComponent("index.html")
        webView.loadFileURL(index, allowingReadAccessTo: appDir)
        return webView
    }

    public func updateUIView(_ uiView: WKWebView, context: Context) {}
}
#endif

// ---------- JSPlayground.swift ----------
import Foundation

#if canImport(SwiftUI) && canImport(JavaScriptCore)
import SwiftUI
import JavaScriptCore

/// 内置 JS Playground：写代码、点运行、看输出。
///
/// 为什么是 JS 而不是 Swift：iOS 上没有可用的 Swift 编译器，未签名的二进制也不允许执行。
/// JavaScriptCore 是系统自带的解释器，**不需要 JIT 授权**，所以这条路真的能跑。
///
/// 这也正好解释了"内置 playground"的边界：能内置的是解释器，不是编译器。
@MainActor
public struct JSPlayground: View {

    @State private var code: String = JSPlayground.starter
    @State private var output: [Line] = []

    public struct Line: Identifiable {
        public let id = UUID()
        public let text: String
        public let isError: Bool
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextEditor(text: $code)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(8)
                    .frame(maxHeight: .infinity)

                Divider()

                HStack {
                    Button {
                        run()
                    } label: {
                        Label("运行", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    Spacer()
                    Button("清空") { output = [] }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)

                Divider()

                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        if output.isEmpty {
                            Text("输出会显示在这里")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        ForEach(output) { line in
                            Text(line.text)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(line.isError ? Color.red : Color.primary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(12)
                }
                .frame(height: 200)
            }
            .navigationTitle("JS Playground")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - 执行

    private func run() {
        output = []
        guard let ctx = JSContext() else {
            output.append(Line(text: "拿不到 JSContext", isError: true))
            return
        }

        // 把 console.log 收进数组，运行完一次性取出来
        let collector = """
        var __lines = [];
        var console = {
          log: function(){ __lines.push(Array.prototype.slice.call(arguments).map(fmt).join(' ')); },
          error: function(){ __lines.push('错误: ' + Array.prototype.slice.call(arguments).map(fmt).join(' ')); }
        };
        function fmt(v){
          if (typeof v === 'string') return v;
          try { return JSON.stringify(v); } catch (e) { return String(v); }
        }
        """
        ctx.evaluateScript(collector)

        ctx.evaluateScript(code)
        if let exception = ctx.exception {
            let message = exception.toString() ?? "未知错误"
            output.append(Line(text: "异常：\(message)", isError: true))
            ctx.exception = nil
        }

        if let lines = ctx.objectForKeyedSubscript("__lines")?.toArray() as? [Any] {
            for item in lines {
                output.append(Line(text: "\(item)", isError: false))
            }
        }
        if output.isEmpty {
            output.append(Line(text: "（没有输出）", isError: false))
        }
    }

    static let starter = """
    // JavaScriptCore 里能跑的真代码，改完点「运行」
    const nums = [3, 1, 4, 1, 5, 9];
    console.log('原始:', nums);
    console.log('排序:', [...nums].sort((a, b) => a - b));

    const count = {};
    for (const n of nums) count[n] = (count[n] || 0) + 1;
    console.log('频次:', count);

    // 纯计算不受限；没有网络、没有文件权限——这是刻意的
    function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    console.log('fib(10) =', fib(10));
    """
}
#endif

// ---------- PlaygroundEntry.swift ----------
import Foundation

#if canImport(PlaygroundSupport) && canImport(SwiftUI)
import SwiftUI
import PlaygroundSupport

// iPad / Playgrounds 入口：三个页签——
//   聊天：DeepSeek agent（引擎 + 工具）
//   应用：本地 web app 启动器（点一个就跑）
//   沙盒：内置 JS playground（真跑 JavaScriptCore）
//
// 三个页签都只用系统框架（SwiftUI / WebKit / JavaScriptCore），不依赖任何第三方库。

struct RootTabs: View {
    var body: some View {
        TabView {
            ChatView(model: ChatModel())
                .tabItem { Label("聊天", systemImage: "bubble.left.and.bubble.right") }

            WebAppLauncher()
                .tabItem { Label("应用", systemImage: "square.grid.2x2") }

            JSPlayground()
                .tabItem { Label("沙盒", systemImage: "chevron.left.forwardslash.chevron.right") }
        }
    }
}

PlaygroundPage.current.setLiveView(RootTabs())
#endif

