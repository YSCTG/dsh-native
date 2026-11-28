import Foundation
import JavaScriptCore

/// 插件系统：把工作区里的 `.js` 文件变成模型可调用的工具。
///
/// 为什么用 JS：iOS 不能装动态库、也不能起子进程，但 JavaScriptCore 是系统自带的。
/// 一个插件就是一个文件，不需要签名、不需要重启 App（下次建会话时生效）。
///
/// 插件文件格式（`workspace/plugins/<任意名>.js`）：
/// ```js
/// var plugin = {
///   name: "word_count",              // 工具名（小写下划线，必填）
///   description: "统计字数",          // 必填
///   parameters: {                    // 可选
///     properties: [ { name: "text", type: "string", description: "要统计的文本" } ],
///     required: ["text"]
///   },
///   run: function (argsJSON) {       // 必填；参数是 JSON 字符串，返回字符串
///     var args = JSON.parse(argsJSON);
///     return "共 " + String(args.text).length + " 字";
///   }
/// };
/// ```
///
/// 限制（如实写在 `plugin_list` 的输出里，避免误以为能干什么都行）：
/// - 插件只能算数和处理字符串，**碰不到文件系统**（要读写文件请让模型调 fs_* 工具）
/// - 没有超时保护：死循环会卡住这一次调用
public enum PluginHost {

    public struct Info: Sendable {
        public let name: String
        public let description: String
        public let file: String
        public let builtinConflicts: Bool
    }

    /// 插件目录。
    public static func directory(in workspace: URL) -> URL {
        workspace.appendingPathComponent("plugins", isDirectory: true)
    }

    /// 读出所有插件文件（只看顶层 *.js）。
    public static func files(in workspace: URL) -> [URL] {
        let dir = directory(in: workspace)
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else {
            return []
        }
        return names.filter { $0.hasSuffix(".js") }.sorted()
            .map { dir.appendingPathComponent($0) }
    }

    /// 解析一个插件文件；失败返回 nil 和原因。
    public static func parse(_ url: URL, reserved: Set<String>) -> (Info?, String) {
        guard let source = try? String(contentsOf: url, encoding: .utf8) else {
            return (nil, "读不到文件")
        }
        guard let ctx = JSContext() else { return (nil, "JSContext 创建失败") }
        ctx.exceptionHandler = { _, _ in }
        ctx.evaluateScript(source)
        guard let plugin = ctx.objectForKeyedSubscript("plugin"), !plugin.isUndefined else {
            return (nil, "没有定义全局变量 plugin")
        }
        let name = plugin.objectForKeyedSubscript("name")?.toString() ?? ""
        let desc = plugin.objectForKeyedSubscript("description")?.toString() ?? ""
        guard !name.isEmpty else { return (nil, "plugin.name 为空") }
        guard ctx.objectForKeyedSubscript("plugin")
            .objectForKeyedSubscript("run")?.isUndefined == false else {
            return (nil, "plugin.run 不是函数")
        }
        // 工具名只允许 [a-z0-9_]
        let ok = name.allSatisfy { $0.isLowercase || $0.isNumber || $0 == "_" }
        guard ok else { return (nil, "工具名只允许小写字母/数字/下划线：\(name)") }

        let info = Info(name: name,
                        description: desc.isEmpty ? "（插件 \(url.lastPathComponent)）" : desc,
                        file: url.lastPathComponent,
                        builtinConflicts: reserved.contains(name))
        return (info, "")
    }

    /// 生成可注册的工具列表。与内置工具重名的会被跳过（不让插件偷偷覆盖 fs_write）。
    public static func tools(in workspace: URL, reserved: Set<String>) -> [any Tool] {
        files(in: workspace).compactMap { url -> (any Tool)? in
            // parse 返回的是**非可选**元组 (Info?, 原因)，所以先解构再解包
            let (maybeInfo, _) = parse(url, reserved: reserved)
            guard let info = maybeInfo, !info.builtinConflicts else {
                return nil
            }
            return PluginTool(info: info, url: url)
        }
    }

    /// 给 `plugin_list` 用：列出全部插件及其状态。
    public static func catalog(in workspace: URL, reserved: Set<String>) -> String {
        let list = files(in: workspace)
        if list.isEmpty {
            return "（没有插件。把 .js 文件放进 workspace/plugins/ 就会出现在这里）"
        }
        var lines: [String] = []
        for url in list {
            let (info, reason) = parse(url, reserved: reserved)
            if let info {
                let mark = info.builtinConflicts ? "⚠️ 与内置工具重名，已跳过" : "✓ 可用"
                lines.append("\(info.name)  \(mark)\n  \(info.description)\n  文件：\(info.file)")
            } else {
                lines.append("\(url.lastPathComponent)  ✗ 加载失败：\(reason)")
            }
        }
        return lines.joined(separator: "\n\n")
    }
}

/// 单个插件对应的工具实现。
struct PluginTool: Tool {
    let info: PluginHost.Info
    let url: URL

    var name: String { info.name }
    var description: String { info.description }

    var parameters: JSONSchema {
        // 每次从 JS 里读一遍 schema（插件文件可能被改过）
        guard let ctx = JSContext() else { return .none }
        ctx.exceptionHandler = { _, _ in }
        ctx.evaluateScript((try? String(contentsOf: url, encoding: .utf8)) ?? "")
        guard let plugin = ctx.objectForKeyedSubscript("plugin"),
              let params = plugin.objectForKeyedSubscript("parameters"),
              !params.isUndefined,
              let props = params.objectForKeyedSubscript("properties")?.toArray() as? [[String: Any]]
        else { return .none }

        let properties = props.compactMap { dict -> JSONSchema.Property? in
            guard let n = dict["name"] as? String else { return nil }
            return JSONSchema.Property(n,
                                       dict["type"] as? String ?? "string",
                                       dict["description"] as? String ?? "")
        }
        let required = (params.objectForKeyedSubscript("required")?.toArray() as? [String]) ?? []
        return JSONSchema(properties: properties, required: required)
    }

    func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
        let source = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        guard let js = JSContext() else {
            throw ToolError.failed("插件引擎不可用")
        }
        var thrown: String?
        js.exceptionHandler = { _, exception in
            thrown = exception?.toString() ?? "未知错误"
        }
        js.evaluateScript(source)

        guard let run = js.objectForKeyedSubscript("plugin")?
            .objectForKeyedSubscript("run"), !run.isUndefined else {
            throw ToolError.failed("插件 \(info.file) 没有 run 函数")
        }

        let argsJSON = JSONValue.stringify(args)
        let result = run.call(withArguments: [argsJSON])
        if let thrown { throw ToolError.failed("插件出错：\(thrown)") }

        if let s = result?.toString(), s != "undefined" { return s }
        return "（插件没有返回内容）"
    }
}

/// `plugin_list` 工具：让模型自己看看有哪些插件。
public enum PluginTools {

    public static func all(reserved: Set<String>) -> [any Tool] {
        [ListPlugins(reserved: reserved)]
    }

    struct ListPlugins: Tool {
        let reserved: Set<String>
        let name = "plugin_list"
        let description = "列出工作区 plugins/ 下的 JS 插件，以及它们是否可用。"
        var parameters: JSONSchema { .none }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            PluginHost.catalog(in: ctx.workspace, reserved: reserved)
        }
    }
}
