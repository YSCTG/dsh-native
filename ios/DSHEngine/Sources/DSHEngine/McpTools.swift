import Foundation

/// MCP（Model Context Protocol）客户端：JSON-RPC 2.0 over HTTP。
///
/// iOS 上只能用 **HTTP 传输**（stdio 需要起子进程，平台不允许）。
/// 对齐安卓版的 `mcp_resources` / `mcp_read` 两个工具。
///
/// 传输层做成协议是为了**能离线测试**：CI 里塞一个假 transport 就能验证
/// 请求形状与响应解析，不需要真的架一台 MCP 服务器。
public protocol McpTransport: Sendable {
    func post(_ body: Data) async throws -> Data
}

public struct HttpMcpTransport: McpTransport {
    public let endpoint: URL
    public let headers: [String: String]

    public init(endpoint: URL, headers: [String: String] = [:]) {
        self.endpoint = endpoint
        self.headers = headers
    }

    public func post(_ body: Data) async throws -> Data {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        req.httpBody = body
        req.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw ToolError.failed("MCP HTTP \(http.statusCode)")
        }
        return data
    }
}

public actor McpClient {

    public struct Resource: Sendable {
        public let uri: String
        public let name: String
        public let mimeType: String?
    }

    private let transport: McpTransport
    private var nextId = 1

    public init(transport: McpTransport) {
        self.transport = transport
    }

    public init(endpoint: URL, headers: [String: String] = [:]) {
        self.transport = HttpMcpTransport(endpoint: endpoint, headers: headers)
    }

    /// 发一次 JSON-RPC 请求，返回 result 字段。
    public func call(method: String, params: [String: Any] = [:]) async throws -> [String: Any] {
        let id = nextId
        nextId += 1
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let data = try await transport.post(payload)
        let text = String(decoding: data, as: UTF8.self)

        // 服务端可能回 SSE（text/event-stream）：取最后一条 data: 行
        let jsonText: String
        if text.contains("data:") && !text.trimmingCharacters(in: .whitespaces).hasPrefix("{") {
            let frames = text.components(separatedBy: "\n")
                .filter { $0.hasPrefix("data:") }
                .map { String($0.dropFirst(5)).trimmingCharacters(in: .whitespaces) }
            jsonText = frames.last(where: { $0.hasPrefix("{") }) ?? "{}"
        } else {
            jsonText = text
        }

        guard let obj = try JSONSerialization.jsonObject(with: Data(jsonText.utf8)) as? [String: Any] else {
            throw ToolError.failed("MCP 返回不是 JSON")
        }
        if let error = obj["error"] as? [String: Any] {
            let message = error["message"] as? String ?? "未知错误"
            throw ToolError.failed("MCP 错误：\(message)")
        }
        return obj["result"] as? [String: Any] ?? [:]
    }

    public func listResources() async throws -> [Resource] {
        let result = try await call(method: "resources/list")
        let items = result["resources"] as? [[String: Any]] ?? []
        return items.map {
            Resource(uri: $0["uri"] as? String ?? "",
                     name: $0["name"] as? String ?? "",
                     mimeType: $0["mimeType"] as? String)
        }.filter { !$0.uri.isEmpty }
    }

    public func read(uri: String) async throws -> String {
        let result = try await call(method: "resources/read", params: ["uri": uri])
        let contents = result["contents"] as? [[String: Any]] ?? []
        let texts = contents.compactMap { $0["text"] as? String }
        if !texts.isEmpty { return texts.joined(separator: "\n") }
        return "（这个资源没有文本内容）"
    }
}

/// MCP 工具：`mcp_resources` / `mcp_read`。
public enum McpTools {

    public static func all() -> [any Tool] {
        [Resources(), Read()]
    }

    static func client(_ ctx: ToolContext) throws -> McpClient {
        guard let client = ctx.mcp else {
            throw ToolError.denied("没有配置 MCP 服务器（宿主未提供）")
        }
        return client
    }

    struct Resources: Tool {
        let name = "mcp_resources"
        let description = "列出已配置的 MCP 服务器提供的资源（uri / name / 类型）。"
        var parameters: JSONSchema { .none }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let client = try McpTools.client(ctx)
            let list = try await client.listResources()
            if list.isEmpty { return "（MCP 服务器没有提供资源）" }
            return list.map { r in
                "\(r.name)\n  uri: \(r.uri)" + (r.mimeType.map { "\n  类型: \($0)" } ?? "")
            }.joined(separator: "\n")
        }
    }

    struct Read: Tool {
        let name = "mcp_read"
        let description = "按 uri 读一个 MCP 资源的内容。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("uri", "string", "资源的 uri")], required: ["uri"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let uri = args["uri"]?.stringValue, !uri.isEmpty else {
                throw ToolError.badArgument("缺少 uri")
            }
            let client = try McpTools.client(ctx)
            let text = try await client.read(uri: uri)
            return text.count > 6000 ? String(text.prefix(6000)) + "\n…（已截断）" : text
        }
    }
}
