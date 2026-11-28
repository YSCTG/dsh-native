import Foundation

/// 引擎自检：在**引擎模块内部**跑一遍关键行为，返回结果。
///
/// 为什么放在引擎里：各工具的 struct 是 internal 的（不对外开放）。
/// 与其把它们全部 public（那会把实现细节漏出去），不如在这里提供一个 public 入口，
/// 让 App、Playgrounds、CI 共用同一套检查。
public struct CheckReport: Sendable {
    public var lines: [String] = []
    public var passed: Int = 0
    public var failed: [String] = []
    public var isEmpty: Bool { passed == 0 && failed.isEmpty }

    public var summary: String {
        failed.isEmpty ? "全部通过（\(passed)/\(passed)）"
                       : "失败 \(failed.count) 项：\(failed.joined(separator: "、"))"
    }
}

/// 假的 MCP 传输：不联网，按 method 返回固定结果，并记录收到过哪些方法。
private final class FakeMcpTransport: McpTransport, @unchecked Sendable {
    private var seen: [String] = []
    private let lock = NSLock()

    func methods() -> [String] { lock.lock(); defer { lock.unlock() }; return seen }

    func post(_ body: Data) async throws -> Data {
        let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
        let method = obj["method"] as? String ?? ""
        let id = obj["id"] as? Int ?? 1
        lock.lock(); seen.append(method); lock.unlock()

        let result: [String: Any]
        switch method {
        case "resources/list":
            result = ["resources": [
                ["uri": "mcp://demo/notes", "name": "notes.md", "mimeType": "text/markdown"],
                ["uri": "mcp://demo/data", "name": "data.json"],
            ]]
        case "resources/read":
            result = ["contents": [["uri": "mcp://demo/notes", "text": "MCP 内容测试"]]]
        default:
            result = [:]
        }
        let response: [String: Any] = ["jsonrpc": "2.0", "id": id, "result": result]
        return (try? JSONSerialization.data(withJSONObject: response)) ?? Data()
    }
}

private actor FakeCounter {
    private(set) var calls: [String] = []
    func hit(_ prompt: String) { calls.append(prompt) }
    func all() -> [String] { calls }
}

public enum EngineSelfCheck {

    public static func run(workspace: URL) async -> CheckReport {
        var r = CheckReport()

        func check(_ ok: Bool, _ name: String) {
            if ok {
                r.passed += 1
                r.lines.append("✓ \(name)")
            } else {
                r.failed.append(name)
                r.lines.append("✗ \(name)")
            }
        }

        try? FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: workspace)

        // ---- 沙箱 ----
        r.lines.append("【路径沙箱】")
        do {
            _ = try ctx.resolve("../escape.txt")
            check(false, "拒绝 ../ 逃逸")
        } catch {
            check(true, "拒绝 ../ 逃逸")
        }
        check((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")

        // ---- 文件工具 ----
        r.lines.append("【文件工具】")
        do {
            let w = try await FSTools.WriteFile().run(
                .object(["path": .string("p/a.txt"), "content": .string("l1\nl2\nl3")]), in: ctx)
            check(w.contains("已写入"), "fs_write 写入")

            let read = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt")]), in: ctx)
            check(read.contains("1\tl1"), "fs_read 带行号")

            let page = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt"),
                         "offset": .number(2), "limit": .number(1)]), in: ctx)
            check(page.contains("2\tl2") && !page.contains("1\tl1"), "fs_read 分页")

            let edited = try await FSTools.EditFile().run(
                .object(["path": .string("p/a.txt"),
                         "old": .string("l2"), "new": .string("L2")]), in: ctx)
            check(edited.contains("已替换 1 处"), "fs_edit 替换")

            var rejected = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("p/a.txt"),
                             "old": .string("l"), "new": .string("x")]), in: ctx)
            } catch { rejected = true }
            check(rejected, "原文不唯一时拒绝")

            let g = try await FSTools.Glob().run(
                .object(["pattern": .string("**/*.txt")]), in: ctx)
            check(g.contains("p/a.txt"), "fs_glob 匹配")

            let grep = try await FSTools.Grep().run(
                .object(["pattern": .string("L2")]), in: ctx)
            check(grep.contains(":2:"), "fs_grep 行号")
        } catch {
            check(false, "fs 工具异常：\(error)")
        }

        // ---- 会话存储 ----
        r.lines.append("【会话存储】")
        do {
            let store = SessionStore(root: workspace)
            let s = try store.create()
            try store.append(.init(role: "user", content: "你好"), to: s.id)
            try store.append(.init(role: "assistant", content: "在"), to: s.id)
            check(try store.load(s.id).count == 2, "追加两条读回两条")

            let file = workspace.appendingPathComponent("sessions/\(s.id).jsonl")
            if let h = try? FileHandle(forWritingTo: file) {
                try? h.seekToEnd()
                try? h.write(contentsOf: Data(#"{"role":"assistant","cont"#.utf8))
                try? h.close()
            }
            check(try store.load(s.id).count == 2, "半行不坏全篇")
        } catch {
            check(false, "会话异常：\(error)")
        }

        // ---- 文档生成 ----
        r.lines.append("【文档生成】")
        let docx = OfficeTools.buildDocx(title: "自检", paragraphs: ["正文"], table: nil)
        check(docx.count > 400, "docx 生成（\(docx.count) 字节）")
        check(docx.count > 3 && docx[0] == 0x50 && docx[1] == 0x4B, "docx 是 zip 头")
        check(ZipWriter.crc32(Data("123456789".utf8)) == 0xCBF4_3926, "CRC32 标准向量")

        // ---- 平台工具 ----
        r.lines.append("【平台工具】")
        do {
            let info = try await PlatformTools.DevInfo().run(.null, in: ctx)
            check(info.contains("系统") && info.contains("工作区"), "dev_info 有系统与工作区")

            // todo：加两条 → 列出 → 标完成 → 清空
            _ = try await PlatformTools.Todo().run(
                .object(["action": .string("add"), "text": .string("写文档")]), in: ctx)
            _ = try await PlatformTools.Todo().run(
                .object(["action": .string("add"), "text": .string("跑测试")]), in: ctx)
            let list = try await PlatformTools.Todo().run(
                .object(["action": .string("list")]), in: ctx)
            check(list.contains("1. [ ] 写文档") && list.contains("2. [ ] 跑测试"), "todo 添加与列出")
            let done = try await PlatformTools.Todo().run(
                .object(["action": .string("done"), "id": .number(1)]), in: ctx)
            check(done.contains("已完成第 1 项"), "todo 标记完成")
            let after = try await PlatformTools.Todo().run(
                .object(["action": .string("list")]), in: ctx)
            check(after.contains("[x] 写文档"), "todo 完成状态持久化")

            // skill：写一个 md 再读
            let skillsDir = ctx.workspace.appendingPathComponent("skills", isDirectory: true)
            try? FileManager.default.createDirectory(at: skillsDir, withIntermediateDirectories: true)
            try? Data("# 演示技能\n\n这是内容".utf8)
                .write(to: skillsDir.appendingPathComponent("demo.md"))
            let names = try await PlatformTools.Skill().run(
                .object(["action": .string("list")]), in: ctx)
            check(names.contains("demo"), "skill 列出")
            let body = try await PlatformTools.Skill().run(
                .object(["action": .string("read"), "name": .string("demo")]), in: ctx)
            check(body.contains("这是内容"), "skill 读取")

            // RSS 解析（纯函数，不联网）
            let xml = """
            <item><title>标题一</title><link>https://a.example/1</link></item>
            <item><title>标题二</title><link>https://a.example/2</link></item>
            """
            let items = PlatformTools.WebSearch.parseRSS(xml, limit: 5)
            check(items.count == 2 && items[0].title == "标题一", "web_search RSS 解析")
        } catch {
            check(false, "平台工具异常：\(error)")
        }

        // ---- 运维工具 ----
        r.lines.append("【运维工具】")
        do {
            // 任务板
            let c1 = try await OpsTools.TaskCreate().run(.object(["text": .string("第一步")]), in: ctx)
            check(c1.contains("#1"), "task_create 返回编号")
            _ = try await OpsTools.TaskCreate().run(.object(["text": .string("第二步")]), in: ctx)
            let tl = try await OpsTools.TaskList().run(.null, in: ctx)
            check(tl.contains("#1 [todo] 第一步") && tl.contains("#2 [todo] 第二步"), "task_list 列出两条")
            let tu = try await OpsTools.TaskUpdate().run(
                .object(["id": .number(1), "status": .string("done")]), in: ctx)
            check(tu.contains("#1 → done"), "task_update 改状态")
            let tg = try await OpsTools.TaskGet().run(.object(["id": .number(1)]), in: ctx)
            check(tg.contains("#1 [done] 第一步"), "task_get 读单条")

            // 目标
            _ = try await OpsTools.GoalSet().run(
                .object(["text": .string("做完对齐"), "done": .string("工具数量匹配")]), in: ctx)
            let gg = try await OpsTools.GoalGet().run(.null, in: ctx)
            check(gg.contains("做完对齐") && gg.contains("工具数量匹配"), "goal_set/get")
            let gc = try await OpsTools.GoalClear().run(.null, in: ctx)
            check(gc.contains("已清除"), "goal_clear")

            // 导出 zip
            let ex = try await OpsTools.Export().run(.null, in: ctx)
            check(ex.contains("已打包"), "dev_export 打包")
            let zipName = ex.components(separatedBy: "→ ").last?.components(separatedBy: "（").first
            if let name = zipName {
                let z = ctx.workspace.appendingPathComponent(name)
                let d = (try? Data(contentsOf: z)) ?? Data()
                check(d.count > 3 && d[0] == 0x50 && d[1] == 0x4B, "导出的是合法 zip")
            } else {
                check(false, "解析不出导出文件名")
            }
        } catch {
            check(false, "运维工具异常：\(error)")
        }

        // ---- 子 agent（假 runner，不联网）----
        r.lines.append("【子 agent】")
        do {
            // 没接 runner 时要给出明确错误，而不是静默返回空
            var denied = false
            do {
                _ = try await SubTools.SubAgent().run(
                    .object(["task": .string("做点事")]), in: ctx)
            } catch { denied = true }
            check(denied, "没有 runner 时明确报错")

            // 塞一个假 runner：记录调用、模拟并发
            let counter = FakeCounter()
            let fakeCtx = ToolContext(workspace: ctx.workspace, sub: { prompt in
                await counter.hit(prompt)
                try? await Task.sleep(nanoseconds: 40_000_000)
                return "收到：\(prompt)"
            })

            let one = try await SubTools.SubAgent().run(
                .object(["task": .string("写一句介绍")]), in: fakeCtx)
            check(one.contains("收到：写一句介绍"), "subagent 返回子模型结论")

            let wf = try await SubTools.WorkflowTool().run(
                .object(["tasks": .array([.string("A"), .string("B"), .string("C")]),
                         "concurrency": .number(2)]), in: fakeCtx)
            check(wf.contains("收到：A") && wf.contains("收到：C"), "workflow 覆盖全部任务")
            let orderA = wf.range(of: "收到：A")!.lowerBound
            let orderC = wf.range(of: "收到：C")!.lowerBound
            check(orderA < orderC, "workflow 结果按输入顺序")

            let loop = try await SubTools.LoopTool().run(
                .object(["task": .string("收尾"), "max_rounds": .number(2)]), in: fakeCtx)
            check(loop.contains("轮都没看到") || loop.contains("完成"), "loop 有终止条件")
        } catch {
            check(false, "子 agent 异常：\(error)")
        }

        // ---- 读文档（真 deflate 样本）----
        r.lines.append("【读文档】")
        do {
            let docx = Fixtures.docxDeflated
            check(docx.count > 200, "样本 docx 非空（\(docx.count) 字节）")
            let zip = ZipReader(data: docx)
            check(zip.entries().contains { $0.name == "word/document.xml" }, "ZipReader 能读中央目录")
            check(zip.entries().contains { $0.method == 8 }, "样本是真 deflate（method=8）")
            let xmlData = zip.read("word/document.xml")
            check((xmlData?.count ?? 0) > 100, "deflate 解压成功（\(xmlData?.count ?? 0) 字节）")

            let docxPath = ctx.workspace.appendingPathComponent("fixture.docx")
            try? docx.write(to: docxPath)
            let docxText = try await OfficeRead.Read().run(
                .object(["path": .string("fixture.docx")]), in: ctx)
            r.lines.append("  docx 抽取：\(docxText.replacingOccurrences(of: "\n", with: "⏎").prefix(70))")
            check(docxText.contains("季度报告"), "docx 抽到标题")
            check(docxText.contains("第一段"), "docx 抽到正文")
            check(docxText.contains("128"), "docx 抽到表格单元格")

            let xlsxPath = ctx.workspace.appendingPathComponent("fixture.xlsx")
            try? Fixtures.xlsxDeflated.write(to: xlsxPath)
            let xlsxText = try await OfficeRead.Read().run(
                .object(["path": .string("fixture.xlsx")]), in: ctx)
            r.lines.append("  xlsx 抽取：\(xlsxText.replacingOccurrences(of: "\n", with: "⏎").prefix(70))")
            check(xlsxText.contains("名单"), "xlsx 抽到表名")
            check(xlsxText.contains("张三") && xlsxText.contains("上海"), "xlsx 抽到共享字符串")
            check(xlsxText.contains("42"), "xlsx 抽到数字单元格")
        } catch {
            check(false, "读文档异常：\(error)")
        }

        // ---- 提问与计划 ----
        r.lines.append("【提问/计划】")
        do {
            // ask_user：起一个任务挂起，界面侧"回答"后应拿到答案
            let askTask = Task { () -> String in
                (try? await AskTools.AskUser().run(
                    .object(["question": .string("用哪种格式？")]), in: ctx)) ?? "异常"
            }
            // 等它把问题挂上去
            var pending: String? = nil
            for _ in 0..<40 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                pending = await AskBroker.shared.pendingQuestion()
                if pending != nil { break }
            }
            check(pending == "用哪种格式？", "ask_user 会挂起并暴露问题")
            await AskBroker.shared.answer("markdown")
            let reply = await askTask.value
            check(reply.contains("markdown"), "ask_user 拿到用户回答")

            // plan_present：写 plan.md
            let plan = try await AskTools.PlanPresent().run(
                .object(["steps": .array([.string("第一步"), .string("第二步")])]), in: ctx)
            check(plan.contains("第一步") && plan.contains("第二步"), "plan_present 返回计划")
            let planURL = ctx.workspace.appendingPathComponent("plan.md")
            let planText = (try? String(contentsOf: planURL, encoding: .utf8)) ?? ""
            check(planText.contains("1. 第一步"), "plan.md 落盘")
        } catch {
            check(false, "提问/计划异常：\(error)")
        }

        // ---- MCP（假 transport，不联网）----
        r.lines.append("【MCP】")
        do {
            // 没配置时明确报错
            var denied = false
            do {
                _ = try await McpTools.Resources().run(.null, in: ctx)
            } catch { denied = true }
            check(denied, "没有 MCP 时明确报错")

            let fake = FakeMcpTransport()
            let client = McpClient(transport: fake)
            let mcpCtx = ToolContext(workspace: ctx.workspace, mcp: client)

            let list = try await McpTools.Resources().run(.null, in: mcpCtx)
            check(list.contains("notes.md") && list.contains("mcp://demo/notes"), "mcp_resources 解析资源列表")

            let body = try await McpTools.Read().run(
                .object(["uri": .string("mcp://demo/notes")]), in: mcpCtx)
            check(body.contains("MCP 内容测试"), "mcp_read 读到文本")

            let calls = await fake.methods()
            check(calls.contains("resources/list") && calls.contains("resources/read"), "请求方法正确")
        } catch {
            check(false, "MCP 异常：\(error)")
        }

        // ---- 团队（假 runner）----
        r.lines.append("【团队】")
        do {
            await TeamRegistry.shared.reset()
            let teamCtx = ToolContext(workspace: ctx.workspace, sub: { prompt in
                try? await Task.sleep(nanoseconds: 80_000_000)
                return "结论：\(prompt)"
            })

            let s1 = try await TeamTools.Spawn().run(
                .object(["name": .string("alpha"), "task": .string("查资料")]), in: teamCtx)
            check(s1.contains("alpha"), "team_spawn 派活")
            _ = try await TeamTools.Spawn().run(
                .object(["name": .string("beta"), "task": .string("写草稿")]), in: teamCtx)

            let listed = try await TeamTools.ListTeam().run(.null, in: teamCtx)
            check(listed.contains("alpha") && listed.contains("beta"), "team_list 列出成员")

            let waited = try await TeamTools.Wait().run(
                .object(["name": .string("alpha"), "seconds": .number(5)]), in: teamCtx)
            check(waited.contains("结论：查资料"), "team_wait 拿到结论")

            let all = try await TeamTools.Collect().run(.null, in: teamCtx)
            check(all.contains("结论：写草稿"), "team_collect 收齐")
            await TeamRegistry.shared.reset()
        } catch {
            check(false, "团队异常：\(error)")
        }

        // ---- 拉起 App（只验参数）----
        r.lines.append("【拉起】")
        do {
            var bad = false
            do {
                _ = try await LaunchTool.AppLaunch().run(.object(["url": .string("")]), in: ctx)
            } catch { bad = true }
            check(bad, "app_launch 拒绝空 url")
        }

        // ---- 插件（JS，真加载真调用）----
        r.lines.append("【插件】")
        do {
            let dir = PluginHost.directory(in: ctx.workspace)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            let good = """
            var plugin = {
              name: "word_count",
              description: "统计字数",
              parameters: {
                properties: [ { name: "text", type: "string", description: "要统计的文本" } ],
                required: ["text"]
              },
              run: function (argsJSON) {
                var a = JSON.parse(argsJSON);
                return "共 " + String(a.text).length + " 字";
              }
            };
            """
            try? Data(good.utf8).write(to: dir.appendingPathComponent("word_count.js"))

            // 故意写一个和内置工具重名的，应被跳过
            let conflict = """
            var plugin = { name: "fs_write", description: "假冒内置", run: function(){ return "x"; } };
            """
            try? Data(conflict.utf8).write(to: dir.appendingPathComponent("conflict.js"))

            // 故意写坏的，应给出原因
            try? Data("var notAPlugin = 1;".utf8).write(to: dir.appendingPathComponent("broken.js"))

            let reserved: Set<String> = ["fs_write", "fs_read"]
            let tools = PluginHost.tools(in: ctx.workspace, reserved: reserved)
            check(tools.count == 1, "只加载合格插件（\(tools.count) 个）")
            check(tools.first?.name == "word_count", "插件名解析正确")

            if let tool = tools.first {
                let schema = tool.parameters
                check(schema.properties.count == 1 && schema.required == ["text"], "参数 schema 解析")

                let out = try await tool.run(.object(["text": .string("你好世界")]), in: ctx)
                check(out.contains("4"), "插件真的执行了（结果：\(out)）")
            }

            let catalog = PluginHost.catalog(in: ctx.workspace, reserved: reserved)
            check(catalog.contains("✓ 可用"), "catalog 标出可用插件")
            check(catalog.contains("与内置工具重名"), "catalog 标出重名冲突")
            check(catalog.contains("加载失败"), "catalog 标出坏文件")
        } catch {
            check(false, "插件异常：\(error)")
        }

        // ---- 客户端可构造 ----
        r.lines.append("【客户端】")
        _ = DeepSeekClient(config: .init(apiKey: "sk-test"))
        check(true, "DeepSeekClient 可构造")

        return r
    }
}
