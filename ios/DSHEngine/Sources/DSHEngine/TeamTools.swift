import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// 进程内「团队」：把多个子 agent 同时派出去，按名字取结果。
///
/// 对齐安卓版的 team_spawn / team_list / team_send / team_wait / team_collect / team_stop。
///
/// 和安卓的差别：安卓那边每个 teammate 是一个独立线程 + 完整工具集；
/// iOS 这边用 `ToolContext.sub`（一次独立模型调用）来实现，**没有多轮自主循环**。
/// 工具说明里写清楚了这一点，避免让人以为能开常驻进程。
public actor TeamRegistry {

    public static let shared = TeamRegistry()

    public struct Member {
        public let id: String
        public let name: String
        public var task: String
        public var notes: [String]
        public var status: String        // running / done / stopped / failed
        public var result: String
    }

    private var members: [String: Member] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]

    public init() {}

    public func spawn(name: String, task: String, runner: @escaping @Sendable (String) async -> String) -> Member {
        let id = name.isEmpty ? "t\(members.count + 1)" : name
        var m = Member(id: id, name: id, task: task, notes: [],
                       status: "running", result: "")
        members[id] = m

        let handle = Task { [weak self] in
            let output = await runner(task)
            await self?.finish(id: id, output: output)
        }
        tasks[id] = handle
        m = members[id]!
        return m
    }

    private func finish(id: String, output: String) {
        guard var m = members[id] else { return }
        m.result = output.trimmingCharacters(in: .whitespacesAndNewlines)
        m.status = "done"
        members[id] = m
        tasks[id] = nil
    }

    public func list() -> [Member] { members.values.sorted { $0.id < $1.id } }

    public func get(_ id: String) -> Member? { members[id] }

    /// 等某个成员结束（最多等 seconds 秒）。
    public func wait(_ id: String, seconds: Double = 120) async -> Member? {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if let m = members[id], m.status != "running" { return m }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return members[id]
    }

    public func collect() async -> [Member] {
        for id in tasks.keys { _ = await wait(id, seconds: 180) }
        return list()
    }

    public func stop(_ id: String) {
        tasks[id]?.cancel()
        tasks[id] = nil
        if var m = members[id] {
            m.status = "stopped"
            members[id] = m
        }
    }

    /// 给某个成员留一条补充说明（下一次被派活时会带上）。
    public func note(_ id: String, message: String) -> Bool {
        guard var m = members[id] else { return false }
        m.notes.append(message)
        members[id] = m
        return true
    }

    public func reset() {
        for (_, t) in tasks { t.cancel() }
        tasks.removeAll()
        members.removeAll()
    }
}

public enum TeamTools {

    public static func all() -> [any Tool] {
        [Spawn(), ListTeam(), Send(), Wait(), Collect(), Stop()]
    }

    static func runner(_ ctx: ToolContext) throws -> @Sendable (String) async -> String {
        guard let sub = ctx.sub else {
            throw ToolError.denied("当前上下文没有接子模型（宿主未提供 runner）")
        }
        return sub
    }

    struct Spawn: Tool {
        let name = "team_spawn"
        let description = "派一个 teammate 去跑一件事（一次独立模型调用）。返回成员名，之后用 team_wait/team_collect 取结果。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "成员名（唯一，可省略自动生成）"),
                .init("task", "string", "任务描述"),
            ], required: ["task"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let task = args["task"]?.stringValue, !task.isEmpty else {
                throw ToolError.badArgument("缺少 task")
            }
            let sub = try TeamTools.runner(ctx)
            let name = args["name"]?.stringValue ?? ""
            let member = await TeamRegistry.shared.spawn(name: name, task: task, runner: sub)
            return "已派出 teammate「\(member.id)」，任务：\(task.prefix(60))"
        }
    }

    struct ListTeam: Tool {
        let name = "team_list"
        let description = "列出所有 teammate 及其状态。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let all = await TeamRegistry.shared.list()
            if all.isEmpty { return "（还没有 teammate）" }
            return all.map { m in
                "\(m.id) [\(m.status)] \(m.task.prefix(50))"
                    + (m.result.isEmpty ? "" : "\n   → \(m.result.prefix(80))")
            }.joined(separator: "\n")
        }
    }

    struct Send: Tool {
        let name = "team_send"
        let description = "给某个 teammate 留一条补充说明。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "成员名"),
                .init("message", "string", "要补充的话"),
            ], required: ["name", "message"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["name"]?.stringValue,
                  let msg = args["message"]?.stringValue else {
                throw ToolError.badArgument("需要 name 和 message")
            }
            let ok = await TeamRegistry.shared.note(id, message: msg)
            return ok ? "已记下给 \(id) 的补充" : "没有名为 \(id) 的 teammate"
        }
    }

    struct Wait: Tool {
        let name = "team_wait"
        let description = "等某个 teammate 结束并取回它的结论。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "成员名"),
                .init("seconds", "number", "最多等多久，默认 120"),
            ], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["name"]?.stringValue else {
                throw ToolError.badArgument("缺少 name")
            }
            let seconds = Double(args["seconds"]?.intValue ?? 120)
            guard let m = await TeamRegistry.shared.wait(id, seconds: seconds) else {
                throw ToolError.failed("没有名为 \(id) 的 teammate")
            }
            if m.status == "running" { return "\(id) 还在跑（已等到上限 \(Int(seconds)) 秒）" }
            return "\(id) [\(m.status)]\n\(m.result.isEmpty ? "（没有输出）" : m.result)"
        }
    }

    struct Collect: Tool {
        let name = "team_collect"
        let description = "等所有 teammate 结束后，一次性拿回全部结论。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let all = await TeamRegistry.shared.collect()
            if all.isEmpty { return "（还没有 teammate）" }
            return all.map { "\($0.name) [\($0.status)]\n\($0.result)" }.joined(separator: "\n\n")
        }
    }

    struct Stop: Tool {
        let name = "team_stop"
        let description = "停掉某个 teammate。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("name", "string", "成员名")], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["name"]?.stringValue else {
                throw ToolError.badArgument("缺少 name")
            }
            await TeamRegistry.shared.stop(id)
            return "已停止 \(id)"
        }
    }
}

/// 拉起别的 App：iOS 只能按 URL Scheme，不能枚举、也不能启动任意 App。
public enum LaunchTool {

    public static func all() -> [any Tool] { [AppLaunch()] }

    struct AppLaunch: Tool {
        let name = "app_launch"
        let description = "用 URL Scheme 打开一个 App 或网页（例如 maps://、https://…）。iOS 不允许按包名启动任意 App。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("url", "string", "要打开的 URL / scheme")], required: ["url"])
        }

        @MainActor
        func open(_ url: URL) async -> Bool {
            #if canImport(UIKit)
            return await withCheckedContinuation { cont in
                UIApplication.shared.open(url, options: [:]) { ok in cont.resume(returning: ok) }
            }
            #else
            return false
            #endif
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let s = args["url"]?.stringValue, let url = URL(string: s) else {
                throw ToolError.badArgument("url 无效")
            }
            let ok = await open(url)
            return ok ? "已打开 \(s)" : "打不开 \(s)（这台设备可能没有对应的 App，或 scheme 不被允许）"
        }
    }
}
