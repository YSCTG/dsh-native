// 引擎自检（iPad / Swift Playgrounds 单文件版）
//
// 由 build/make_playgrounds_bundle.py 生成，不要手改。
// 用法：在 iPad 的 Swift Playgrounds 里新建一个 Playground，粘贴本文件全部内容，运行。
// 结尾会打印「全部通过（N/N）」或失败清单。
//
// 注意：这里包含引擎全部源码（不含 SwiftUI），所以它同时验证两件事：
//   1) 这些 Swift 能不能通过**类型检查**（Playgrounds 会真编译）
//   2) 关键行为对不对（断言）

import Foundation

// ===== 引擎源码开始 =====
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

    /// 把模型给的相对路径解析到工作区内，并挡住 `../` 逃逸。
    public func resolve(_ path: String) throws -> URL {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ToolError.badArgument("path 为空") }
        let candidate = trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed)
            : workspace.appendingPathComponent(trimmed)
        let root = workspace.standardizedFileURL.path
        let target = candidate.standardizedFileURL.path
        guard target == root || target.hasPrefix(root + "/") else {
            throw ToolError.badArgument("越界：\(path) 不在工作区内")
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
            let rootPath = ctx.workspace.standardizedFileURL.path

            let walker = FileManager.default.enumerator(at: base,
                                                        includingPropertiesForKeys: [.isDirectoryKey])
            while let item = walker?.nextObject() as? URL {
                if hits.count >= 300 { break }
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                if isDir { continue }
                let rel = item.path.replacingOccurrences(of: rootPath + "/", with: "")
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

            let rootPath = ctx.workspace.standardizedFileURL.path
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
                let rel = file.path.replacingOccurrences(of: rootPath + "/", with: "")
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

// ===== 引擎源码结束 =====

import Foundation

/// iPad / macOS 上都能跑的自检（不需要 XCTest）。
///
/// 为什么不用 XCTest：Swift Playgrounds 没有测试运行器，`swift test` 也要 SPM 环境。
/// 所以把关键断言写成运行时检查，直接跑、直接看结果——和 Android 版的 dev_selfcheck 一个思路。
///
/// 用法：在 Playgrounds 里运行本文件，看底部输出。
enum SelfCheck {

    private static var passed = 0
    private static var failed: [String] = []

    private static func expect(_ cond: Bool, _ label: String) {
        if cond {
            passed += 1
            print("  ✓ \(label)")
        } else {
            failed.append(label)
            print("  ✗ \(label)")
        }
    }

    private static func expectThrows(_ label: String, _ body: () throws -> Void) {
        do {
            try body()
            expect(false, label + "（应抛错但没抛）")
        } catch {
            expect(true, label)
        }
    }

    static func run() async {
        passed = 0
        failed = []

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dsh-selfcheck-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: root)

        // ---------- 路径沙箱 ----------
        print("【路径沙箱】")
        expectThrows("拒绝 ../etc/passwd") { _ = try ctx.resolve("../etc/passwd") }
        expect((try? ctx.resolve("sub/a.txt"))?.path.hasPrefix(root.standardizedFileURL.path) == true,
               "工作区内路径放行")

        // ---------- 工具参数 ----------
        print("【工具参数】")
        let args = AgentLoop.parseArguments(#"{"path":"a.txt","n":3,"ok":true}"#)
        expect(args["path"]?.stringValue == "a.txt", "解析 path")
        expect(args["n"]?.intValue == 3, "解析数字")
        expect(args["ok"]?.boolValue == true, "解析布尔")
        if case .object(let d) = AgentLoop.parseArguments("{not json") {
            expect(d.isEmpty, "坏 JSON 退化为空对象")
        } else {
            expect(false, "坏 JSON 退化为空对象")
        }

        // ---------- 文本 ----------
        print("【文本统计】")
        expect(BuiltinTools.TextStats.summarize("a b\nc") == "字符 5 · 行 2 · 词 3", "英文统计")
        expect(BuiltinTools.TextStats.summarize("") == "字符 0 · 行 0 · 词 0", "空文本")

        // ---------- 文件原语 ----------
        print("【文件原语】")
        do {
            _ = try await BuiltinTools.WriteFile().run(
                .object(["path": .string("d/x.txt"), "content": .string("hello 世界")]), in: ctx)
            let text = try await BuiltinTools.ReadFile().run(
                .object(["path": .string("d/x.txt")]), in: ctx)
            let listed = try await BuiltinTools.ListFiles().run(
                .object(["path": .string("d")]), in: ctx)
            expect(text == "hello 世界", "写入 + 读回（UTF-8 中文）")
            expect(listed.contains("x.txt"), "列目录能看到文件")
        } catch {
            expect(false, "文件原语异常：\(error)")
        }

        // ---------- fs_* ----------
        print("【文件工具 fs_*】")
        do {
            _ = try await FSTools.WriteFile().run(
                .object(["path": .string("proj/a.txt"),
                         "content": .string("line1\nline2\nline3")]), in: ctx)
            let listOut = try await FSTools.ListDir().run(
                .object(["path": .string("proj")]), in: ctx)
            let readOut = try await FSTools.ReadFile().run(
                .object(["path": .string("proj/a.txt"),
                         "offset": .number(2), "limit": .number(2)]), in: ctx)
            let editOut = try await FSTools.EditFile().run(
                .object(["path": .string("proj/a.txt"),
                         "old": .string("line2"), "new": .string("LINE-TWO")]), in: ctx)

            var editRejected = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("proj/a.txt"),
                             "old": .string("line"), "new": .string("x")]), in: ctx)
            } catch { editRejected = true }

            let globOut = try await FSTools.Glob().run(
                .object(["pattern": .string("**/*.txt")]), in: ctx)
            let grepOut = try await FSTools.Grep().run(
                .object(["pattern": .string("LINE-TWO")]), in: ctx)

            expect(listOut.contains("a.txt"), "fs_list 能看到文件")
            expect(readOut.contains("2\tline2") && readOut.contains("3\tline3"), "fs_read 带行号分页")
            expect(editOut.contains("已替换 1 处"), "fs_edit 成功一次")
            expect(editRejected, "原文不唯一时拒绝（避免改错地方）")
            expect(globOut.contains("proj/a.txt"), "fs_glob 匹配 **/*.txt")
            expect(grepOut.contains("a.txt:2:"), "fs_grep 给出 路径:行号")
        } catch {
            expect(false, "fs 工具异常：\(error)")
        }

        // ---------- 系统能力（只测不需要授权的部分） ----------
        print("【系统工具】")
        #if canImport(UIKit)
        do {
            _ = try await SystemTools.ClipWrite().run(
                .object(["text": .string("dsh-clip-测试-42")]), in: ctx)
            let back = try await SystemTools.ClipRead().run(.null, in: ctx)
            expect(back.contains("dsh-clip-测试-42"), "剪贴板写入后能读回")
        } catch {
            expect(false, "剪贴板异常：\(error)")
        }
        #endif

        do {
            let addOut = try await SystemTools.ScheduleAdd().run(
                .object(["title": .string("自检提醒"), "after_seconds": .number(3600)]), in: ctx)
            let listOut = try await SystemTools.ScheduleList().run(.null, in: ctx)
            expect(addOut.contains("已加提醒"), "schedule_add 记录成功")
            expect(listOut.contains("自检提醒"), "schedule_list 能列出")
            if let id = addOut.split(separator: "（").last?.split(separator: "）").first {
                let rmOut = try await SystemTools.ScheduleRemove().run(
                    .object(["id": .string(String(id))]), in: ctx)
                expect(rmOut.contains("已删除"), "schedule_remove 按 id 删除")
            } else {
                expect(false, "解析不出提醒 id")
            }
        } catch {
            expect(false, "日程异常：\(error)")
        }

        // ---------- 内嵌浏览器（用内联 HTML，不联网） ----------
        print("【浏览器 WebKit】")
        do {
            let html = """
            <html><head><title>自检页</title></head><body>
            <h1 id="h">标题文字</h1>
            <button id="b" onclick="document.getElementById('h').textContent='点过了'">按我</button>
            <input id="i" value="">
            </body></html>
            """
            _ = await WebBrowser.shared.loadHTML(html)
            let title = await WebBrowser.shared.title()
            let body = await WebBrowser.shared.text()
            expect(title == "自检页", "读到页面标题")
            expect(body.contains("标题文字"), "读到正文")

            _ = try? await WebBrowser.shared.js(
                "document.getElementById('b').click()")
            let afterClick = await WebBrowser.shared.text()
            expect(afterClick.contains("点过了"), "click() 生效")

            _ = try? await WebBrowser.shared.js("""
            (function(){
              var el = document.getElementById('i');
              var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              setter.call(el, '填入成功');
              el.dispatchEvent(new Event('input', {bubbles:true}));
              return 'ok';
            })()
            """)
            let val = (try? await WebBrowser.shared.js(
                "document.getElementById('i').value")) ?? ""
            expect(val == "填入成功", "输入框能被填值")
        } catch {
            expect(false, "浏览器用例异常：\(error)")
        }

        // ---------- 会话存储 ----------
        print("【会话存储】")
        let store = SessionStore(root: root)
        do {
            let s = try store.create()
            try store.append(.init(role: "user", content: "你好"), to: s.id)
            try store.append(.init(role: "assistant", content: "在"), to: s.id)
            let msgs = try store.load(s.id)
            expect(msgs.count == 2, "追加两条能读回两条")
            expect(msgs.first?.content == "你好", "首条内容正确")

            // 造一个半行，模拟崩在半路
            let file = root.appendingPathComponent("sessions/\(s.id).jsonl")
            if let h = try? FileHandle(forWritingTo: file) {
                try? h.seekToEnd()
                try? h.write(contentsOf: Data(#"{"role":"assistant","cont"#.utf8))
                try? h.close()
            }
            expect(try store.load(s.id).count == 2, "半行被跳过、不丢前面的消息")
        } catch {
            expect(false, "会话存储异常：\(error)")
        }

        expect(SessionStore.titleFrom("  \n ") == "新会话", "空标题回落")
        expect(SessionStore.titleFrom(String(repeating: "字", count: 30)).hasSuffix("…"), "长标题截断")

        // ---------- schema ----------
        print("【schema】")
        let schema = JSONSchema(properties: [.init("path", "string", "路径")], required: ["path"])
        let obj = schema.jsonObject()
        expect(obj["type"] as? String == "object", "schema type")
        expect((obj["required"] as? [String]) == ["path"], "schema required")

        // ---------- 汇总 ----------
        print("")
        if failed.isEmpty {
            print("全部通过（\(passed)/\(passed)）")
        } else {
            print("失败 \(failed.count) 项 / 共 \(passed + failed.count) 项：")
            failed.forEach { print("  · \($0)") }
        }
    }
}

// 运行方式分两种环境，别用信号量阻塞主线程：
//   Playgrounds：顶层代码占着主线程，`done.wait()` 会和 Task 抢主线程 → 直接卡死。
//                正确做法是打开 needsIndefiniteExecution，让异步任务自己跑完打印。
//   其它环境（命令行 / macOS 脚本）：没有 Playgrounds 的调度，才用信号量。
#if canImport(PlaygroundSupport)
import PlaygroundSupport
PlaygroundPage.current.needsIndefiniteExecution = true
Task { await SelfCheck.run() }
#else
let done = DispatchSemaphore(value: 0)
Task {
    await SelfCheck.run()
    done.signal()
}
done.wait()
#endif
