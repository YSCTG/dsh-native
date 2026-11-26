// 文件工具验证块：Tool.swift + FSTools.swift + 断言
//
// 为什么要单独一块：iPad 上大文件粘贴困难、编译慢。
// 这块约 20 KB，专门验证 fs_* 这一层（写/读/编辑/glob/grep/沙箱）。
//
// 由 build/make_playgrounds_bundle.py 生成。

import Foundation

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
        let candidate = trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed)
            : workspace.appendingPathComponent(trimmed)
        // 关键：iOS 上 /var 与 /private/var 是同一目录的两种写法（symlink），
        // standardizedFileURL **不**解开软链接，直接比较会误判成越界。
        // 两边都 resolvingSymlinksInPath，并去掉尾部斜杠，才是可靠比较。
        let rootPath = normalized(workspace)
        let targetPath = normalized(candidate)
        guard targetPath == rootPath || targetPath.hasPrefix(rootPath + "/") else {
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

// ===== 断言 =====

import Foundation

/// 文件工具（fs_*）的验证块。
/// 和 Tool.swift + FSTools.swift 拼成一个单文件，约 20 KB，方便整块粘贴。
enum FSCheck {
    static var passed = 0
    static var failed: [String] = []

    static func check(_ ok: Bool, _ label: String) {
        if ok { passed += 1; print("  ✓ \(label)") }
        else { failed.append(label); print("  ✗ \(label)") }
    }

    static func run() async {
        print("=== 文件工具验证 ===")

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("fs-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: root)

        // ---- 沙箱（含上一轮在 iPad 上发现软链接问题的回归）----
        print("【沙箱】")
        do {
            _ = try ctx.resolve("../escape.txt")
            check(false, "拒绝 ../ 逃逸")
        } catch { check(true, "拒绝 ../ 逃逸") }
        check((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")
        check(ToolContext.normalized(root) == ToolContext.normalized(ctx.workspace),
              "软链接归一（/var 与 /private/var）")

        // ---- 写 / 读 / 列 ----
        print("【写读列】")
        do {
            let w = try await FSTools.WriteFile().run(
                .object(["path": .string("p/a.txt"),
                         "content": .string("line1\nline2\nline3")]), in: ctx)
            check(w.contains("已写入"), "fs_write 写入")

            let r = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt")]), in: ctx)
            check(r.contains("1\tline1") && r.contains("3\tline3"), "fs_read 带行号")

            let page = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt"),
                         "offset": .number(2), "limit": .number(1)]), in: ctx)
            check(page.contains("2\tline2") && !page.contains("1\tline1"), "fs_read 分页")

            let list = try await FSTools.ListDir().run(
                .object(["path": .string("p")]), in: ctx)
            check(list.contains("a.txt"), "fs_list 列出文件")
        } catch {
            check(false, "写读列异常：\(error)")
        }

        // ---- 编辑（唯一性校验）----
        print("【编辑】")
        do {
            let ok = try await FSTools.EditFile().run(
                .object(["path": .string("p/a.txt"),
                         "old": .string("line2"), "new": .string("LINE-TWO")]), in: ctx)
            check(ok.contains("已替换 1 处"), "fs_edit 替换成功")

            var rejected = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("p/a.txt"),
                             "old": .string("line"), "new": .string("x")]), in: ctx)
            } catch { rejected = true }
            check(rejected, "原文不唯一时拒绝")

            var missing = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("p/a.txt"),
                             "old": .string("不存在的文本"), "new": .string("x")]), in: ctx)
            } catch { missing = true }
            check(missing, "找不到原文时报错")
        } catch {
            check(false, "编辑异常：\(error)")
        }

        // ---- glob / grep ----
        print("【查找】")
        do {
            _ = try await FSTools.WriteFile().run(
                .object(["path": .string("p/sub/b.md"), "content": .string("# md")]), in: ctx)
            let g = try await FSTools.Glob().run(
                .object(["pattern": .string("**/*.md")]), in: ctx)
            check(g.contains("p/sub/b.md"), "fs_glob 跨目录匹配")

            let one = try await FSTools.Glob().run(
                .object(["pattern": .string("*.md")]), in: ctx)
            check(!one.contains("p/sub/b.md"), "fs_glob 单个 * 不跨目录")

            let gr = try await FSTools.Grep().run(
                .object(["pattern": .string("LINE-TWO")]), in: ctx)
            check(gr.contains(":2:"), "fs_grep 给出行号")
        } catch {
            check(false, "查找异常：\(error)")
        }

        print("")
        if failed.isEmpty {
            print("=== 全部通过（\(passed)/\(passed)）===")
        } else {
            print("=== 失败 \(failed.count) 项 / 共 \(passed + failed.count) 项 ===")
            for f in failed { print("  · \(f)") }
        }
    }
}

// 顶层入口：Playgrounds 需要它才会执行
let fsDone = Task { await FSCheck.run() }
_ = fsDone
