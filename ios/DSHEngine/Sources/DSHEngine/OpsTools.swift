import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// 运维类工具：screenshot / dev_export / task_* / goal_*。
///
/// 对齐安卓版同名能力，但有几处必须说明的差别：
/// - `screenshot`：iOS 只能截**自己**的界面（抓不到别的 App），已如实写进工具说明
/// - `dev_export`：把工作区与会话打成一个 zip（安卓那边是写到 .migrate 目录）
/// - `task_*`：本地 JSON 任务板；安卓版是内存 + UI 面板
/// - `goal_*`：单条目标状态，供 agent 自查"我在做什么"
public enum OpsTools {

    public static func all() -> [any Tool] {
        [Screenshot(), Export(), TaskCreate(), TaskList(), TaskGet(), TaskUpdate(),
         GoalSet(), GoalGet(), GoalClear()]
    }

    // MARK: - screenshot

    struct Screenshot: Tool {
        let name = "screenshot"
        let description = "截取**本 App** 的界面并保存成 png。注意：iOS 不允许截其他 App 的屏幕。"
        var parameters: JSONSchema { .none }

        @MainActor
        func capture() -> (Data, Int, Int)? {
            #if canImport(UIKit)
            guard let scene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first(where: { $0.activationState == .foregroundActive }),
                  let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first
            else { return nil }
            let size = window.bounds.size
            let renderer = UIGraphicsImageRenderer(size: size)
            let image = renderer.image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
            }
            guard let data = image.pngData() else { return nil }
            return (data, Int(size.width), Int(size.height))
            #else
            return nil
            #endif
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let result = await capture()
            guard let (data, w, h) = result else {
                throw ToolError.failed("拿不到窗口（App 可能在后台）")
            }
            let dir = ctx.workspace.appendingPathComponent("shots", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let file = dir.appendingPathComponent("screen-\(Int(Date().timeIntervalSince1970)).png")
            try data.write(to: file)
            return "已截取本 App 界面 \(w)×\(h)，保存为 \(file.lastPathComponent)（\(data.count) 字节）"
        }
    }

    // MARK: - dev_export

    struct Export: Tool {
        let name = "dev_export"
        let description = "把工作区里的文件打成一个 zip，方便导出/备份。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("path", "string", "要打包的子目录（默认整个工作区）"),
            ], required: [])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let root = try ctx.resolve(args["path"]?.stringValue ?? ".")
            var zip = ZipWriter()
            var count = 0
            let base = ctx.workspace.standardizedFileURL.path

            let walker = FileManager.default.enumerator(at: root,
                                                        includingPropertiesForKeys: [.isDirectoryKey])
            while let item = walker?.nextObject() as? URL {
                if count >= 800 { break }
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                if isDir { continue }
                guard let data = try? Data(contentsOf: item) else { continue }
                if data.count > 5 * 1024 * 1024 { continue }   // 跳过超大文件
                let rel = relativePath(item, base: base)
                zip.add(rel, data)
                count += 1
            }
            if count == 0 { return "工作区里没有可打包的文件" }

            let name = "export-\(Int(Date().timeIntervalSince1970)).zip"
            let out = ctx.workspace.appendingPathComponent(name)
            let data = zip.finish()
            try data.write(to: out)
            return "已打包 \(count) 个文件 → \(name)（\(data.count) 字节）"
        }

        private func relativePath(_ url: URL, base: String) -> String {
            let p = ToolContext.normalized(url)
            return p.hasPrefix(base + "/") ? String(p.dropFirst(base.count + 1)) : url.lastPathComponent
        }
    }

    // MARK: - 任务板

    struct TaskItem: Codable {
        var id: Int
        var text: String
        var status: String      // todo / doing / done
    }

    static func tasksPath(_ ctx: ToolContext) -> URL {
        ctx.workspace.appendingPathComponent("tasks.json")
    }

    static func loadTasks(_ ctx: ToolContext) -> [TaskItem] {
        guard let d = try? Data(contentsOf: tasksPath(ctx)),
              let items = try? JSONDecoder().decode([TaskItem].self, from: d) else { return [] }
        return items
    }

    static func saveTasks(_ ctx: ToolContext, _ items: [TaskItem]) {
        guard let d = try? JSONEncoder().encode(items) else { return }
        try? d.write(to: tasksPath(ctx))
    }

    struct TaskCreate: Tool {
        let name = "task_create"
        let description = "往任务板加一条任务，返回编号。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("text", "string", "任务内容")], required: ["text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let text = args["text"]?.stringValue, !text.isEmpty else {
                throw ToolError.badArgument("缺少 text")
            }
            var items = OpsTools.loadTasks(ctx)
            let id = (items.map { $0.id }.max() ?? 0) + 1
            items.append(TaskItem(id: id, text: text, status: "todo"))
            OpsTools.saveTasks(ctx, items)
            return "已创建任务 #\(id)：\(text)"
        }
    }

    struct TaskList: Tool {
        let name = "task_list"
        let description = "列出任务板上的全部任务。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let items = OpsTools.loadTasks(ctx)
            if items.isEmpty { return "（任务板是空的）" }
            return items.map { "#\($0.id) [\($0.status)] \($0.text)" }.joined(separator: "\n")
        }
    }

    struct TaskGet: Tool {
        let name = "task_get"
        let description = "按编号读一条任务的详情。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("id", "number", "任务编号")], required: ["id"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["id"]?.intValue else {
                throw ToolError.badArgument("缺少 id")
            }
            let items = OpsTools.loadTasks(ctx)
            guard let t = items.first(where: { $0.id == id }) else {
                throw ToolError.failed("没有 #\(id) 这条任务")
            }
            return "#\(t.id) [\(t.status)] \(t.text)"
        }
    }

    struct TaskUpdate: Tool {
        let name = "task_update"
        let description = "改任务状态：status = todo / doing / done。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("id", "number", "任务编号"),
                .init("status", "string", "todo / doing / done"),
            ], required: ["id", "status"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["id"]?.intValue, let status = args["status"]?.stringValue else {
                throw ToolError.badArgument("需要 id 和 status")
            }
            guard ["todo", "doing", "done"].contains(status) else {
                throw ToolError.badArgument("status 只支持 todo/doing/done")
            }
            var items = OpsTools.loadTasks(ctx)
            guard let i = items.firstIndex(where: { $0.id == id }) else {
                throw ToolError.failed("没有 #\(id) 这条任务")
            }
            items[i].status = status
            OpsTools.saveTasks(ctx, items)
            return "任务 #\(id) → \(status)"
        }
    }

    // MARK: - 目标

    struct GoalState: Codable {
        var text: String
        var done: String
    }

    static func goalPath(_ ctx: ToolContext) -> URL {
        ctx.workspace.appendingPathComponent("goal.json")
    }

    struct GoalSet: Tool {
        let name = "goal_set"
        let description = "设定当前目标（一句话）与完成标准。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("text", "string", "目标"),
                .init("done", "string", "什么算完成（可选）"),
            ], required: ["text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let text = args["text"]?.stringValue, !text.isEmpty else {
                throw ToolError.badArgument("缺少 text")
            }
            let state = GoalState(text: text, done: args["done"]?.stringValue ?? "")
            let data = (try? JSONEncoder().encode(state)) ?? Data()
            try data.write(to: OpsTools.goalPath(ctx))
            return "目标已设定：\(text)"
        }
    }

    struct GoalGet: Tool {
        let name = "goal_get"
        let description = "读回当前目标。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let d = try? Data(contentsOf: OpsTools.goalPath(ctx)),
                  let g = try? JSONDecoder().decode(GoalState.self, from: d) else {
                return "（还没有设定目标）"
            }
            return "目标：\(g.text)" + (g.done.isEmpty ? "" : "\n完成标准：\(g.done)")
        }
    }

    struct GoalClear: Tool {
        let name = "goal_clear"
        let description = "清掉当前目标。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let p = OpsTools.goalPath(ctx)
            if FileManager.default.fileExists(atPath: p.path) {
                try? FileManager.default.removeItem(at: p)
                return "目标已清除"
            }
            return "本来就没有目标"
        }
    }
}
