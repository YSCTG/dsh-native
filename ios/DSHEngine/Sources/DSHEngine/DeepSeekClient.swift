import Foundation
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

        // 工具调用在流里是**分片**到达的：id/name 只在第一片，arguments 会被切成很多片。
        // 所以这里按 index 累积，等流结束再一次性发出去——覆盖式写法会丢掉参数。
        var toolAcc: [Int: (id: String, name: String, args: String)] = [:]

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
                    for (pos, c) in calls.enumerated() {
                        let idx = (c["index"] as? Int) ?? pos
                        var acc = toolAcc[idx] ?? (id: "", name: "", args: "")
                        if let id = c["id"] as? String, !id.isEmpty { acc.id = id }
                        if let fn = c["function"] as? [String: Any] {
                            if let n = fn["name"] as? String, !n.isEmpty { acc.name = n }
                            if let a = fn["arguments"] as? String { acc.args += a }
                        }
                        toolAcc[idx] = acc
                    }
                }
            }
            if let fr = first["finish_reason"] as? String {
                // 参数已经收完，先把工具调用一次性发出去，再报完成
                for (_, acc) in toolAcc.sorted(by: { $0.key < $1.key }) {
                    if !acc.name.isEmpty {
                        onEvent(.toolCall(id: acc.id.isEmpty ? UUID().uuidString : acc.id,
                                          name: acc.name,
                                          arguments: acc.args))
                    }
                }
                toolAcc.removeAll()
                onEvent(.done(finishReason: fr))
            }
        }
        // 流没给 finish_reason 也要把攒下的发出去，否则工具调用会默默丢掉
        for (_, acc) in toolAcc.sorted(by: { $0.key < $1.key }) {
            if !acc.name.isEmpty {
                onEvent(.toolCall(id: acc.id.isEmpty ? UUID().uuidString : acc.id,
                                  name: acc.name,
                                  arguments: acc.args))
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
