import Foundation

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
                        onText: @escaping @Sendable (String) -> Void = { _ in }) async throws -> TurnResult {
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
