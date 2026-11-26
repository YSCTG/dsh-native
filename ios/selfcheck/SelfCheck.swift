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

// Playgrounds 里允许顶层 await；用信号量兜住，保证输出打完再结束
let done = DispatchSemaphore(value: 0)
Task {
    await SelfCheck.run()
    done.signal()
}
done.wait()
