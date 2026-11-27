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

        // ---- 客户端可构造 ----
        r.lines.append("【客户端】")
        _ = DeepSeekClient(config: .init(apiKey: "sk-test"))
        check(true, "DeepSeekClient 可构造")

        return r
    }
}
