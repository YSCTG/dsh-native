import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// 平台工具：dev_info / dev_selfcheck / web_search / todo / skill。
///
/// 对齐安卓版同名工具。这五个都是纯逻辑或文本抓取，不依赖 UI 与特权，
/// 因此在 iOS 上可以完整实现，且能在模拟器自检里验证。
public enum PlatformTools {

    public static func all() -> [any Tool] {
        [DevInfo(), DevSelfCheck(), WebSearch(), Todo(), Skill()]
    }

    // MARK: - dev_info

    struct DevInfo: Tool {
        let name = "dev_info"
        let description = "这台设备的运行环境：系统版本、机型、App 版本、工作区路径与剩余空间。"
        var parameters: JSONSchema { .none }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            var lines: [String] = []
            let p = ProcessInfo.processInfo
            lines.append("系统：\(p.operatingSystemVersionString)")
            #if canImport(UIKit)
            lines.append("机型：\(UIDevice.current.model)（\(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")）")
            #endif
            let bundle = Bundle.main
            let ver = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
            let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
            lines.append("App：\(ver)（\(build)）")
            lines.append("工作区：\(ctx.workspace.path)")

            if let attrs = try? FileManager.default.attributesOfFileSystem(forPath: ctx.workspace.path),
               let free = attrs[.systemFreeSize] as? NSNumber {
                lines.append("剩余空间：\(ByteCountFormatter.string(fromByteCount: free.int64Value, countStyle: .file))")
            }
            lines.append("工具数：\(FSTools.all().count + BuiltinTools.all().count + WebTools.all().count + SystemTools.all().count + OfficeTools.all().count + PublishTools.all().count + PlatformTools.all().count)")
            return lines.joined(separator: "\n")
        }
    }

    // MARK: - dev_selfcheck

    struct DevSelfCheck: Tool {
        let name = "dev_selfcheck"
        let description = "跑一遍引擎自检（路径沙箱 / 文件 / 会话 / 文档），返回结论。"
        var parameters: JSONSchema { .none }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let report = await EngineSelfCheck.run(workspace: ctx.workspace)
            var out = report.summary
            if !report.failed.isEmpty {
                out += "\n失败项：" + report.failed.joined(separator: "、")
            }
            return out
        }
    }

    // MARK: - web_search

    struct WebSearch: Tool {
        let name = "web_search"
        let description = "用 Bing 的 RSS 结果搜索网页，返回标题 + 链接。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("query", "string", "搜索词"),
                .init("count", "number", "返回条数，默认 5"),
            ], required: ["query"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard ctx.networkAllowed else { throw ToolError.denied("当前上下文不允许联网") }
            guard let q = args["query"]?.stringValue, !q.isEmpty else {
                throw ToolError.badArgument("缺少 query")
            }
            let count = max(1, min(10, args["count"]?.intValue ?? 5))
            guard let encoded = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
                  let url = URL(string: "https://www.bing.com/search?format=rss&count=\(count)&q=\(encoded)")
            else { throw ToolError.badArgument("搜索词无法编码") }

            var req = URLRequest(url: url)
            req.setValue("Mozilla/5.0 (iOS)", forHTTPHeaderField: "User-Agent")
            req.timeoutInterval = 20

            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
                throw ToolError.failed("HTTP \(http.statusCode)")
            }
            let xml = String(decoding: data, as: UTF8.self)
            let items = Self.parseRSS(xml, limit: count)
            if items.isEmpty { return "（没有结果，或 Bing 改了 RSS 格式）" }
            return items.enumerated().map { i, it in
                "\(i + 1). \(it.title)\n   \(it.link)"
            }.joined(separator: "\n")
        }

        struct Item { let title: String; let link: String }

        /// 极简 RSS 解析：只取 <item> 里的 <title>/<link>。
        static func parseRSS(_ xml: String, limit: Int) -> [Item] {
            var out: [Item] = []
            let blocks = xml.components(separatedBy: "<item>").dropFirst()
            for block in blocks {
                guard out.count < limit else { break }
                let head = block.components(separatedBy: "</item>").first ?? block
                let title = value(in: head, tag: "title")
                let link = value(in: head, tag: "link")
                if !title.isEmpty { out.append(Item(title: title, link: link)) }
            }
            return out
        }

        static func value(in block: String, tag: String) -> String {
            guard let s = block.range(of: "<\(tag)>"),
                  let e = block.range(of: "</\(tag)>", range: s.upperBound..<block.endIndex)
            else { return "" }
            return String(block[s.upperBound..<e.lowerBound])
                .replacingOccurrences(of: "<![CDATA[", with: "")
                .replacingOccurrences(of: "]]>", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    // MARK: - todo

    struct Todo: Tool {
        let name = "todo"
        let description = "待办清单：action=add/list/done/clear，存在工作区 todo.json。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("action", "string", "add / list / done / clear"),
                .init("text", "string", "action=add 时的内容"),
                .init("id", "number", "action=done 时的编号（从 1 开始）"),
            ], required: ["action"])
        }

        struct Item: Codable {
            var text: String
            var done: Bool
        }

        static func path(_ ctx: ToolContext) -> URL {
            ctx.workspace.appendingPathComponent("todo.json")
        }

        static func load(_ ctx: ToolContext) -> [Item] {
            guard let d = try? Data(contentsOf: path(ctx)),
                  let items = try? JSONDecoder().decode([Item].self, from: d) else { return [] }
            return items
        }

        static func save(_ ctx: ToolContext, _ items: [Item]) {
            guard let d = try? JSONEncoder().encode(items) else { return }
            try? d.write(to: path(ctx))
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let action = args["action"]?.stringValue else {
                throw ToolError.badArgument("缺少 action")
            }
            var items = Self.load(ctx)

            switch action {
            case "add":
                guard let text = args["text"]?.stringValue, !text.isEmpty else {
                    throw ToolError.badArgument("add 需要 text")
                }
                items.append(Item(text: text, done: false))
                Self.save(ctx, items)
                return "已添加第 \(items.count) 项：\(text)"

            case "list":
                if items.isEmpty { return "（待办为空）" }
                return items.enumerated().map { i, it in
                    "\(i + 1). [\(it.done ? "x" : " ")] \(it.text)"
                }.joined(separator: "\n")

            case "done":
                guard let id = args["id"]?.intValue, id >= 1, id <= items.count else {
                    throw ToolError.badArgument("id 超出范围（1..\(items.count)）")
                }
                items[id - 1].done = true
                Self.save(ctx, items)
                return "已完成第 \(id) 项：\(items[id - 1].text)"

            case "clear":
                let n = items.count
                Self.save(ctx, [])
                return "已清空 \(n) 项"

            default:
                throw ToolError.badArgument("action 只支持 add/list/done/clear")
            }
        }
    }

    // MARK: - skill

    struct Skill: Tool {
        let name = "skill"
        let description = "读工作区 skills/ 下的技能说明（Markdown）。action=list 列名字，action=read 读内容。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("action", "string", "list / read"),
                .init("name", "string", "read 时的技能名（不含 .md）"),
            ], required: ["action"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let action = args["action"]?.stringValue else {
                throw ToolError.badArgument("缺少 action")
            }
            let dir = ctx.workspace.appendingPathComponent("skills", isDirectory: true)

            if action == "list" {
                let names = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [])
                    .filter { $0.hasSuffix(".md") }
                    .map { String($0.dropLast(3)) }
                    .sorted()
                return names.isEmpty ? "（skills/ 下没有 .md 文件）" : names.joined(separator: "\n")
            }

            if action == "read" {
                guard let name = args["name"]?.stringValue, !name.isEmpty else {
                    throw ToolError.badArgument("read 需要 name")
                }
                let clean = name.replacingOccurrences(of: "/", with: "_")
                    .replacingOccurrences(of: "..", with: "_")
                let url = dir.appendingPathComponent(clean + ".md")
                guard let data = try? Data(contentsOf: url) else {
                    throw ToolError.failed("没有这个技能：\(clean)")
                }
                let text = String(decoding: data, as: UTF8.self)
                return text.count > 8000 ? String(text.prefix(8000)) + "\n…（已截断）" : text
            }

            throw ToolError.badArgument("action 只支持 list/read")
        }
    }
}
