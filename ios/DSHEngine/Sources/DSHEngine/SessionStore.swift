import Foundation

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
