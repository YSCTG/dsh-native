import Foundation

/// iPad / macOS 上都能跑的自检（不需要 XCTest）。
///
/// 为什么不用 XCTest：Swift Playgrounds 没有测试运行器，`swift test` 也要 SPM 环境。
/// 所以把关键断言写成运行时检查，直接跑、直接看结果——和 Android 版的 dev_selfcheck 一个思路。
///
/// 用法：在 Playgrounds 里运行本文件，看底部输出。
enum SelfCheck {

    private static var passed = 0
    private static var failed: [String] = []

    private static func expect(_ cond: Bool, _ label: String) {
        if cond {
            passed += 1
            print("  ✓ \(label)")
        } else {
            failed.append(label)
            print("  ✗ \(label)")
        }
    }

    private static func expectThrows(_ label: String, _ body: () throws -> Void) {
        do {
            try body()
            expect(false, label + "（应抛错但没抛）")
        } catch {
            expect(true, label)
        }
    }

    static func run() async {
        passed = 0
        failed = []

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dsh-selfcheck-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: root)

        // ---------- 路径沙箱 ----------
        print("【路径沙箱】")
        expectThrows("拒绝 ../etc/passwd") { _ = try ctx.resolve("../etc/passwd") }
        expect((try? ctx.resolve("sub/a.txt"))?.path.hasPrefix(root.standardizedFileURL.path) == true,
               "工作区内路径放行")

        // ---------- 工具参数 ----------
        print("【工具参数】")
        let args = AgentLoop.parseArguments(#"{"path":"a.txt","n":3,"ok":true}"#)
        expect(args["path"]?.stringValue == "a.txt", "解析 path")
        expect(args["n"]?.intValue == 3, "解析数字")
        expect(args["ok"]?.boolValue == true, "解析布尔")
        if case .object(let d) = AgentLoop.parseArguments("{not json") {
            expect(d.isEmpty, "坏 JSON 退化为空对象")
        } else {
            expect(false, "坏 JSON 退化为空对象")
        }

        // ---------- 文本 ----------
        print("【文本统计】")
        expect(BuiltinTools.TextStats.summarize("a b\nc") == "字符 5 · 行 2 · 词 3", "英文统计")
        expect(BuiltinTools.TextStats.summarize("") == "字符 0 · 行 0 · 词 0", "空文本")

        // ---------- 文件原语 ----------
        print("【文件原语】")
        do {
            _ = try await BuiltinTools.WriteFile().run(
                .object(["path": .string("d/x.txt"), "content": .string("hello 世界")]), in: ctx)
            let text = try await BuiltinTools.ReadFile().run(
                .object(["path": .string("d/x.txt")]), in: ctx)
            let listed = try await BuiltinTools.ListFiles().run(
                .object(["path": .string("d")]), in: ctx)
            expect(text == "hello 世界", "写入 + 读回（UTF-8 中文）")
            expect(listed.contains("x.txt"), "列目录能看到文件")
        } catch {
            expect(false, "文件原语异常：\(error)")
        }

        // ---------- fs_* ----------
        print("【文件工具 fs_*】")
        do {
            _ = try await FSTools.WriteFile().run(
                .object(["path": .string("proj/a.txt"),
                         "content": .string("line1\nline2\nline3")]), in: ctx)
            let listOut = try await FSTools.ListDir().run(
                .object(["path": .string("proj")]), in: ctx)
            let readOut = try await FSTools.ReadFile().run(
                .object(["path": .string("proj/a.txt"),
                         "offset": .number(2), "limit": .number(2)]), in: ctx)
            let editOut = try await FSTools.EditFile().run(
                .object(["path": .string("proj/a.txt"),
                         "old": .string("line2"), "new": .string("LINE-TWO")]), in: ctx)

            var editRejected = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("proj/a.txt"),
                             "old": .string("line"), "new": .string("x")]), in: ctx)
            } catch { editRejected = true }

            let globOut = try await FSTools.Glob().run(
                .object(["pattern": .string("**/*.txt")]), in: ctx)
            let grepOut = try await FSTools.Grep().run(
                .object(["pattern": .string("LINE-TWO")]), in: ctx)

            expect(listOut.contains("a.txt"), "fs_list 能看到文件")
            expect(readOut.contains("2\tline2") && readOut.contains("3\tline3"), "fs_read 带行号分页")
            expect(editOut.contains("已替换 1 处"), "fs_edit 成功一次")
            expect(editRejected, "原文不唯一时拒绝（避免改错地方）")
            expect(globOut.contains("proj/a.txt"), "fs_glob 匹配 **/*.txt")
            expect(grepOut.contains("a.txt:2:"), "fs_grep 给出 路径:行号")
        } catch {
            expect(false, "fs 工具异常：\(error)")
        }

        // ---------- 工作流调度（假 runner，不联网） ----------
        print("【工作流调度】")
        do {
            let items = (1...6).map { WorkflowRunner.Item(id: "t\($0)", prompt: "p\($0)") }

            let runner = WorkflowRunner(concurrency: 3) { item in
                try? await Task.sleep(nanoseconds: 60_000_000)
                return .ok("done-\(item.id)")
            }
            let results = await runner.run(items)
            expect(results.count == 6, "6 个子任务都拿到结果")
            expect(results.map { $0.id } == items.map { $0.id }, "结果顺序等于输入顺序")
            let peak = await runner.observedMaxInFlight()
            expect(peak <= 3, "并发不超过上限（实测峰值 \(peak)）")
            expect(peak >= 2, "确实并发执行过（峰值 \(peak)）")

            let flaky = WorkflowRunner(concurrency: 2) { item in
                item.id == "t2" ? .failed("boom") : .ok("ok")
            }
            let r2 = await flaky.run(items)
            expect(r2.contains { $0.outcome.isFailure }, "单个失败被记录下来")
            expect(r2.filter { !$0.outcome.isFailure }.count == 5, "其余任务照常完成（错误隔离）")

            let stopper = WorkflowRunner(concurrency: 1) { item in
                item.id == "t2" ? .failed("stop") : .ok("ok")
            }
            let r3 = await stopper.run(items, stopOnError: true)
            expect(r3.contains { $0.outcome.text.contains("跳过") }, "stopOnError 会把剩余标记为跳过")
        }

        // ---------- Office 生成（docx / xlsx） ----------
        print("【Office 生成】")
        expect(ZipWriter.crc32(Data("123456789".utf8)) == 0xCBF4_3926,
               "CRC32 标准向量（123456789 → 0xCBF43926）")

        let docx = OfficeTools.buildDocx(title: "自检标题",
                                         paragraphs: ["第一段", "第二段"],
                                         table: nil)
        expect(docx.count > 400, "docx 有内容（\(docx.count) 字节）")
        expect(docx.count > 3 && docx[0] == 0x50 && docx[1] == 0x4B
                && docx[2] == 0x03 && docx[3] == 0x04,
               "docx 以 zip 本地头 PK\\x03\\x04 开头")
        let docxText = String(decoding: docx, as: UTF8.self)
        expect(docxText.contains("word/document.xml"), "docx 含 document.xml 部件")
        expect(docxText.contains("first") == false && docxText.contains("第一段"),
               "docx 正文含中文")

        let sheetsArg = AgentLoop.parseArguments(
            #"[{"name":"数据","rows":[["甲",1],[2,"丙"]]}]"#)
        if let xlsx = try? OfficeTools.buildXlsx(sheets: sheetsArg) {
            let xlsxText = String(decoding: xlsx, as: UTF8.self)
            expect(xlsx.count > 400, "xlsx 生成成功（\(xlsx.count) 字节）")
            expect(xlsxText.contains("xl/workbook.xml"), "xlsx 含 workbook 部件")
            expect(xlsxText.contains("worksheets/sheet1.xml"), "xlsx 含 sheet1")
        } else {
            expect(false, "xlsx 生成失败")
        }

        // 真写一个文件，验证 office_write 的落盘路径
        do {
            let out = try await OfficeTools.Write().run(
                .object(["kind": .string("docx"),
                         "path": .string("office/自检.docx"),
                         "title": .string("落盘"),
                         "paragraphs": .array([.string("正文")])]), in: ctx)
            expect(out.contains("已生成"), "office_write 落盘成功")
            let url = try ctx.resolve("office/自检.docx")
            expect(FileManager.default.fileExists(atPath: url.path), "文件真的在")
        } catch {
            expect(false, "office_write 异常：\(error)")
        }

        // ---------- 系统能力（只测不需要授权的部分） ----------
        print("【系统工具】")
        #if canImport(UIKit)
        do {
            _ = try await SystemTools.ClipWrite().run(
                .object(["text": .string("dsh-clip-测试-42")]), in: ctx)
            let back = try await SystemTools.ClipRead().run(.null, in: ctx)
            expect(back.contains("dsh-clip-测试-42"), "剪贴板写入后能读回")
        } catch {
            expect(false, "剪贴板异常：\(error)")
        }
        #endif

        do {
            let addOut = try await SystemTools.ScheduleAdd().run(
                .object(["title": .string("自检提醒"), "after_seconds": .number(3600)]), in: ctx)
            let listOut = try await SystemTools.ScheduleList().run(.null, in: ctx)
            expect(addOut.contains("已加提醒"), "schedule_add 记录成功")
            expect(listOut.contains("自检提醒"), "schedule_list 能列出")
            if let id = addOut.split(separator: "（").last?.split(separator: "）").first {
                let rmOut = try await SystemTools.ScheduleRemove().run(
                    .object(["id": .string(String(id))]), in: ctx)
                expect(rmOut.contains("已删除"), "schedule_remove 按 id 删除")
            } else {
                expect(false, "解析不出提醒 id")
            }
        } catch {
            expect(false, "日程异常：\(error)")
        }

        // ---------- 内嵌浏览器（用内联 HTML，不联网） ----------
        print("【浏览器 WebKit】")
        do {
            let html = """
            <html><head><title>自检页</title></head><body>
            <h1 id="h">标题文字</h1>
            <button id="b" onclick="document.getElementById('h').textContent='点过了'">按我</button>
            <input id="i" value="">
            </body></html>
            """
            _ = await WebBrowser.shared.loadHTML(html)
            let title = await WebBrowser.shared.title()
            let body = await WebBrowser.shared.text()
            expect(title == "自检页", "读到页面标题")
            expect(body.contains("标题文字"), "读到正文")

            _ = try? await WebBrowser.shared.js(
                "document.getElementById('b').click()")
            let afterClick = await WebBrowser.shared.text()
            expect(afterClick.contains("点过了"), "click() 生效")

            _ = try? await WebBrowser.shared.js("""
            (function(){
              var el = document.getElementById('i');
              var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              setter.call(el, '填入成功');
              el.dispatchEvent(new Event('input', {bubbles:true}));
              return 'ok';
            })()
            """)
            let val = (try? await WebBrowser.shared.js(
                "document.getElementById('i').value")) ?? ""
            expect(val == "填入成功", "输入框能被填值")
        } catch {
            expect(false, "浏览器用例异常：\(error)")
        }

        // ---------- 会话存储 ----------
        print("【会话存储】")
        let store = SessionStore(root: root)
        do {
            let s = try store.create()
            try store.append(.init(role: "user", content: "你好"), to: s.id)
            try store.append(.init(role: "assistant", content: "在"), to: s.id)
            let msgs = try store.load(s.id)
            expect(msgs.count == 2, "追加两条能读回两条")
            expect(msgs.first?.content == "你好", "首条内容正确")

            // 造一个半行，模拟崩在半路
            let file = root.appendingPathComponent("sessions/\(s.id).jsonl")
            if let h = try? FileHandle(forWritingTo: file) {
                try? h.seekToEnd()
                try? h.write(contentsOf: Data(#"{"role":"assistant","cont"#.utf8))
                try? h.close()
            }
            expect(try store.load(s.id).count == 2, "半行被跳过、不丢前面的消息")
        } catch {
            expect(false, "会话存储异常：\(error)")
        }

        expect(SessionStore.titleFrom("  \n ") == "新会话", "空标题回落")
        expect(SessionStore.titleFrom(String(repeating: "字", count: 30)).hasSuffix("…"), "长标题截断")

        // ---------- schema ----------
        print("【schema】")
        let schema = JSONSchema(properties: [.init("path", "string", "路径")], required: ["path"])
        let obj = schema.jsonObject()
        expect(obj["type"] as? String == "object", "schema type")
        expect((obj["required"] as? [String]) == ["path"], "schema required")

        // ---------- 汇总 ----------
        print("")
        if failed.isEmpty {
            print("全部通过（\(passed)/\(passed)）")
        } else {
            print("失败 \(failed.count) 项 / 共 \(passed + failed.count) 项：")
            failed.forEach { print("  · \($0)") }
        }
    }
}

// 运行方式分两种环境，别用信号量阻塞主线程：
//   Playgrounds：顶层代码占着主线程，`done.wait()` 会和 Task 抢主线程 → 直接卡死。
//                正确做法是打开 needsIndefiniteExecution，让异步任务自己跑完打印。
//   其它环境（命令行 / macOS 脚本）：没有 Playgrounds 的调度，才用信号量。
#if canImport(PlaygroundSupport)
import PlaygroundSupport
PlaygroundPage.current.needsIndefiniteExecution = true
Task { await SelfCheck.run() }
#else
let done = DispatchSemaphore(value: 0)
Task {
    await SelfCheck.run()
    done.signal()
}
done.wait()
#endif
