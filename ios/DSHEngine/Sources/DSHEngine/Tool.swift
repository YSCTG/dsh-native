import Foundation

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

    /// 跑一次**独立的**模型调用（子 agent / 工作流用）。
    /// 由宿主注入：引擎本身不碰网络配置，测试里也能塞一个假的。
    public let sub: (@Sendable (String) async -> String)?

    /// MCP 客户端（宿主按设置注入；没有就是 nil）。
    public let mcp: McpClient?

    public init(workspace: URL, networkAllowed: Bool = true,
                sub: (@Sendable (String) async -> String)? = nil,
                mcp: McpClient? = nil,
                audit: @escaping @Sendable (String, String) -> Void = { _, _ in }) {
        self.workspace = workspace
        self.networkAllowed = networkAllowed
        self.sub = sub
        self.mcp = mcp
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

        let rootPath = Self.normalized(realRoot)
        let targetPath = Self.normalized(candidate)
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
