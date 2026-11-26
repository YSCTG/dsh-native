import Foundation

#if canImport(UIKit)
import UIKit
#endif
#if canImport(UserNotifications)
import UserNotifications
#endif

/// 系统能力工具：剪贴板、通知、日程、打开链接。
///
/// 对齐 Android 版的 `clip_read` `clip_write` `notify` `schedule_add/list/remove`。
///
/// 与 Android 的差别要说清：
/// - **剪贴板**：iOS 上是 `UIPasteboard`，读写都要用户在场（读会弹"允许粘贴"提示，系统行为）
/// - **通知**：`UNUserNotificationCenter` 需要用户授权一次；被拒时工具返回明确原因，不假装成功
/// - **日程**：这里用"本地定时通知"实现提醒；真正的日历事件要用 EventKit（需要日历权限）
/// - **打开链接**：`UIApplication.open`；iOS 不接受任意 App 启动，只认 URL Scheme / Universal Link
public enum SystemTools {

    public static func all() -> [any Tool] {
        [ClipRead(), ClipWrite(), Notify(), ScheduleAdd(), ScheduleList(), ScheduleRemove()]
    }

    // MARK: - 剪贴板

    struct ClipRead: Tool {
        let name = "clip_read"
        let description = "读剪贴板里的文字。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            #if canImport(UIKit)
            let s = await MainActor.run { UIPasteboard.general.string ?? "" }
            return s.isEmpty ? "（剪贴板没有文字；iOS 也可能因为没给「允许粘贴」而读不到）" : s
            #else
            throw ToolError.denied("这个平台没有剪贴板接口")
            #endif
        }
    }

    struct ClipWrite: Tool {
        let name = "clip_write"
        let description = "把文字写进剪贴板。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("text", "string", "要复制的文字")], required: ["text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let text = args["text"]?.stringValue else { throw ToolError.badArgument("缺少 text") }
            #if canImport(UIKit)
            await MainActor.run { UIPasteboard.general.string = text }
            return "已复制 \(text.count) 字符到剪贴板"
            #else
            throw ToolError.denied("这个平台没有剪贴板接口")
            #endif
        }
    }

    // MARK: - 通知

    struct Notify: Tool {
        let name = "notify"
        let description = "发一条本地通知（首次会请求通知权限）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("title", "string", "标题"),
                .init("body", "string", "正文"),
            ], required: ["body"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let body = args["body"]?.stringValue else { throw ToolError.badArgument("缺少 body") }
            let title = args["title"]?.stringValue ?? "DSH"
            #if canImport(UserNotifications)
            let center = UNUserNotificationCenter.current()
            let granted: Bool = await withCheckedContinuation { cont in
                center.requestAuthorization(options: [.alert, .sound]) { ok, _ in
                    cont.resume(returning: ok)
                }
            }
            if !granted { return "没拿到通知权限（到 设置 → 通知 里允许后再试）" }

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let request = UNNotificationRequest(identifier: UUID().uuidString,
                                                content: content, trigger: nil)
            let error: Error? = await withCheckedContinuation { cont in
                center.add(request) { err in cont.resume(returning: err) }
            }
            if let error { return "发送失败：\(error.localizedDescription)" }
            return "已发送通知：\(title)"
            #else
            return "这个平台没有通知接口"
            #endif
        }
    }

    // MARK: - 日程（本地提醒）

    /// 提醒存在工作区的 `schedule.json` 里；通知只负责按时弹出。
    struct Item: Codable, Identifiable {
        let id: String
        var title: String
        var at: Date
        var fired: Bool
    }

    static func storeURL(_ ctx: ToolContext) -> URL {
        ctx.workspace.appendingPathComponent("schedule.json")
    }

    static func loadItems(_ ctx: ToolContext) -> [Item] {
        guard let data = try? Data(contentsOf: storeURL(ctx)) else { return [] }
        return (try? JSONDecoder().decode([Item].self, from: data)) ?? []
    }

    static func saveItems(_ ctx: ToolContext, _ items: [Item]) {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: storeURL(ctx))
    }

    struct ScheduleAdd: Tool {
        let name = "schedule_add"
        let description = "加一条定时提醒。after_seconds 表示多少秒之后。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("title", "string", "提醒内容"),
                .init("after_seconds", "number", "多少秒后提醒"),
            ], required: ["title", "after_seconds"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let title = args["title"]?.stringValue,
                  let seconds = args["after_seconds"]?.intValue, seconds > 0 else {
                throw ToolError.badArgument("需要 title 和正数 after_seconds")
            }
            let when = Date().addingTimeInterval(TimeInterval(seconds))
            let item = Item(id: String(UUID().uuidString.prefix(8)),
                            title: title, at: when, fired: false)
            var items = Self_load(ctx)
            items.append(item)
            Self_save(ctx, items)

            #if canImport(UserNotifications)
            // 尽力排一条通知；没权限也不影响"提醒已记录"
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            if settings.authorizationStatus == .authorized {
                let content = UNMutableNotificationContent()
                content.title = "提醒"
                content.body = title
                content.sound = .default
                let trigger = UNTimeIntervalNotificationTrigger(
                    timeInterval: TimeInterval(seconds), repeats: false)
                let request = UNNotificationRequest(
                    identifier: item.id, content: content, trigger: trigger)
                _ = await withCheckedContinuation { (cont: CheckedContinuation<Error?, Never>) in
                    center.add(request) { err in cont.resume(returning: err) }
                }
            }
            #endif
            return "已加提醒（\(item.id)）：\(title) · \(seconds) 秒后"
        }
    }

    struct ScheduleList: Tool {
        let name = "schedule_list"
        let description = "列出所有提醒（按时间排序）。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let items = Self_load(ctx).sorted { $0.at < $1.at }
            if items.isEmpty { return "（没有提醒）" }
            let f = DateFormatter()
            f.dateFormat = "MM-dd HH:mm:ss"
            return items.map { i in
                "[\(i.id)] \(f.string(from: i.at)) · \(i.title)\(i.fired ? " · 已触发" : "")"
            }.joined(separator: "\n")
        }
    }

    struct ScheduleRemove: Tool {
        let name = "schedule_remove"
        let description = "按 id 删除提醒。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("id", "string", "提醒 id")], required: ["id"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let id = args["id"]?.stringValue else { throw ToolError.badArgument("缺少 id") }
            var items = Self_load(ctx)
            let before = items.count
            items.removeAll { $0.id == id }
            Self_save(ctx, items)
            #if canImport(UserNotifications)
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: [id])
            #endif
            return before == items.count ? "没有 id 为 \(id) 的提醒" : "已删除 \(id)"
        }
    }

    // 内部的 load/save 用不同名字，避免和同名的静态方法混淆
    private static func Self_load(_ ctx: ToolContext) -> [Item] { loadItems(ctx) }
    private static func Self_save(_ ctx: ToolContext, _ items: [Item]) { saveItems(ctx, items) }
}
