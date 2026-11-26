import Foundation

/// 把「客户端 + 工具循环 + 会话存储」拼成一个能直接被 UI 绑定的服务。
///
/// 这里刻意不 import SwiftUI：SwiftUI 那一层需要 iOS SDK，而这一层是纯 Foundation，
/// 语法能在 Windows 上校验，逻辑能在任何平台跑测试。UI 只负责订阅事件流。
public actor AgentService {

    public enum Event: Sendable {
        case started
        case reasoning(String)
        case delta(String)
        case toolStarted(name: String, arguments: String)
        case toolFinished(name: String, output: String)
        case finished(text: String)
        case failed(String)
    }

    private let client: DeepSeekClient
    private let store: SessionStore
    private let context: ToolContext
    private let loop: AgentLoop
    private var history: [[String: Any]] = []
    private var running = false
    private var cancelRequested = false

    public init(client: DeepSeekClient, store: SessionStore, context: ToolContext,
                tools: [any Tool] = BuiltinTools.all()) {
        self.client = client
        self.store = store
        self.context = context
        self.loop = AgentLoop(client: client, tools: tools, ctx: context)
    }

    // MARK: - 会话

    public func sessions() -> [SessionStore.Session] {
        (try? store.list()) ?? []
    }

    public func newSession() -> SessionStore.Session {
        (try? store.create()) ?? SessionStore.Session()
    }

    public func sessionTitle(id: String) -> String {
        ((try? store.list()) ?? []).first { $0.id == id }?.title ?? "新会话"
    }

    public func delete(session id: String) {
        try? store.delete(id)
    }

    /// 打开一个会话：把历史读进内存，供继续对话。
    public func open(session id: String) {
        let msgs = (try? store.load(id)) ?? []
        history = msgs.map { m in
            var d: [String: Any] = ["role": m.role, "content": m.content]
            if let tc = m.toolCallId { d["tool_call_id"] = tc }
            if let n = m.name { d["name"] = n }
            return d
        }
        cancelRequested = false
    }

    public func isRunning() -> Bool { running }

    /// 请求取消：当前这轮会在下一个检查点停下（不会粗暴杀掉）。
    public func cancel() {
        cancelRequested = true
    }

    // MARK: - 一轮对话

    /// 发一条用户消息，按事件流回放。
    ///
    /// 所有事件都在同一个 AsyncStream 里，UI 只需按 case 渲染：
    /// 正文追加、工具显示成 chip、结束/失败收尾。
    public func send(_ text: String, to session: String) -> AsyncStream<Event> {
        AsyncStream { continuation in
            Task { [weak self] in
                guard let self else {
                    continuation.yield(.failed("服务已释放"))
                    continuation.finish()
                    return
                }

                let busy = await self.isRunning()
                if busy {
                    continuation.yield(.failed("上一轮还没结束"))
                    continuation.finish()
                    return
                }

                await self.setRunning(true)
                continuation.yield(.started)
                await self.appendHistory(["role": "user", "content": text])
                try? await self.persist(role: "user", content: text, to: session)
                try? await self.store.touch(session, firstUserText: text)

                var messages = await self.snapshotHistory()

                do {
                    let result = try await self.loop.runTurn(
                        messages: &messages,
                        maxSteps: 8,
                        onText: { chunk in continuation.yield(.delta(chunk)) },
                        onTool: { name, args, output in
                            continuation.yield(.toolStarted(name: name, arguments: args))
                            continuation.yield(.toolFinished(name: name, output: output))
                            Task {
                                try? await self.persist(role: "tool", content: output,
                                                        toolCallId: nil, name: name, to: session)
                            }
                        }
                    )
                    await self.setHistory(messages)
                    if !result.text.isEmpty {
                        try? await self.persist(role: "assistant",
                                                content: result.text, to: session)
                    }
                    try? await self.store.touch(session)
                    continuation.yield(.finished(text: result.text))
                } catch {
                    continuation.yield(.failed("\(error)"))
                }

                await self.setRunning(false)
                continuation.finish()
            }
        }
    }

    // MARK: - 内部

    private func setRunning(_ v: Bool) { running = v }

    private func snapshotHistory() -> [[String: Any]] { history }

    private func setHistory(_ h: [[String: Any]]) { history = h }

    private func appendHistory(_ m: [String: Any]) { history.append(m) }

    private func persist(role: String, content: String,
                         toolCallId: String? = nil, name: String? = nil,
                         to session: String) async throws {
        try store.append(.init(role: role, content: content,
                               toolCallId: toolCallId, name: name), to: session)
    }

}
