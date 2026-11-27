import DSHEngine
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
        let tools = BuiltinTools.all() + FSTools.all() + WebTools.all()
            + SystemTools.all() + OfficeTools.all() + PublishTools.all()
            + PlatformTools.all() + OpsTools.all()
        let svc = AgentService(client: client,
                               store: store ?? SessionStore(root: docs),
                               context: ctx,
                               tools: tools)
        service = svc
        return svc
    }
}
