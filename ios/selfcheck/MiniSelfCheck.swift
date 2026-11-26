// 最小自检（~15 KB）：只含 Tool.swift + ZipWriter.swift + 断言
//
// 用途：如果 105 KB 那份"点了没反应"，先跑这份。
//   这份有输出 → Playgrounds 流程没问题，问题在大文件里（看编辑区红字）
//   这份也没输出 → 是粘贴/运行方式的问题，不是代码问题
//
// 由 build/make_playgrounds_bundle.py 生成。

import Foundation

// ===== 引擎（两个文件）=====
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

// ===== 断言 =====

import Foundation

/// 最小自检：只用纯 Foundation 的两个文件（Tool + ZipWriter）。
///
/// 为什么单独做一个：如果 105 KB 那份"点了没反应"，可能是
///   a) Playgrounds 根本没跑起来（粘贴/运行方式问题）
///   b) 代码里有编译错误（编辑区应该显示红字）
///   c) 跑了但输出在别的地方没看到
/// 这份只有 ~15 KB，编译快、出错点少，用它先把 (a) 排除掉。

enum MiniCheck {
    static var passed = 0
    static var failed: [String] = []

    static func expect(_ ok: Bool, _ label: String) {
        if ok {
            passed += 1
            print("  ✓ \(label)")
        } else {
            failed.append(label)
            print("  ✗ \(label)")
        }
    }

    static func run() {
        print("=== 最小自检开始 ===")

        // 1. JSON 解析
        let args = AgentLoop.parseArguments(#"{"path":"a.txt","n":7,"flag":true}"#)
        expect(args["path"]?.stringValue == "a.txt", "JSON 取字符串")
        expect(args["n"]?.intValue == 7, "JSON 取数字")
        expect(args["flag"]?.boolValue == true, "JSON 取布尔")

        // 2. 坏 JSON 不崩
        if case .object(let d) = AgentLoop.parseArguments("{broken") {
            expect(d.isEmpty, "坏 JSON 退化为空对象")
        } else {
            expect(false, "坏 JSON 退化为空对象")
        }

        // 3. 路径沙箱
        let ws = FileManager.default.temporaryDirectory
            .appendingPathComponent("mini-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: ws)
        do {
            _ = try ctx.resolve("../escape.txt")
            expect(false, "拒绝 ../ 逃逸")
        } catch {
            expect(true, "拒绝 ../ 逃逸")
        }
        expect((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")

        // 4. CRC32 标准向量
        expect(ZipWriter.crc32(Data("123456789".utf8)) == 0xCBF4_3926,
               "CRC32 标准向量（123456789 → 0xCBF43926）")

        // 5. 造一个 zip，看签名
        var zip = ZipWriter()
        zip.add("hello.txt", "你好，世界")
        let data = zip.finish()
        expect(data.count > 100, "zip 有内容（\(data.count) 字节）")
        expect(data.count > 3 && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04,
               "zip 以 PK\\x03\\x04 开头")

        print("")
        if failed.isEmpty {
            print("=== 全部通过（\(passed)/\(passed)）===")
        } else {
            print("=== 失败 \(failed.count) 项 / 共 \(passed + failed.count) 项 ===")
            failed.forEach { print("  · \($0)") }
        }
    }
}

MiniCheck.run()
