import Foundation

/// 文件工具（fs_*）的验证块。
/// 和 Tool.swift + FSTools.swift 拼成一个单文件，约 20 KB，方便整块粘贴。
enum FSCheck {
    static var passed = 0
    static var failed: [String] = []

    static func check(_ ok: Bool, _ label: String) {
        if ok { passed += 1; print("  ✓ \(label)") }
        else { failed.append(label); print("  ✗ \(label)") }
    }

    static func run() async {
        print("=== 文件工具验证 ===")

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("fs-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: root)

        // ---- 沙箱（含上一轮在 iPad 上发现软链接问题的回归）----
        print("【沙箱】")
        do {
            _ = try ctx.resolve("../escape.txt")
            check(false, "拒绝 ../ 逃逸")
        } catch { check(true, "拒绝 ../ 逃逸") }
        check((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")
        check(ToolContext.normalized(root) == ToolContext.normalized(ctx.workspace),
              "软链接归一（/var 与 /private/var）")

        // ---- 写 / 读 / 列 ----
        print("【写读列】")
        do {
            let w = try await FSTools.WriteFile().run(
                .object(["path": .string("p/a.txt"),
                         "content": .string("line1\nline2\nline3")]), in: ctx)
            check(w.contains("已写入"), "fs_write 写入")

            let r = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt")]), in: ctx)
            check(r.contains("1\tline1") && r.contains("3\tline3"), "fs_read 带行号")

            let page = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt"),
                         "offset": .number(2), "limit": .number(1)]), in: ctx)
            check(page.contains("2\tline2") && !page.contains("1\tline1"), "fs_read 分页")

            let list = try await FSTools.ListDir().run(
                .object(["path": .string("p")]), in: ctx)
            check(list.contains("a.txt"), "fs_list 列出文件")
        } catch {
            check(false, "写读列异常：\(error)")
        }

        // ---- 编辑（唯一性校验）----
        print("【编辑】")
        do {
            let ok = try await FSTools.EditFile().run(
                .object(["path": .string("p/a.txt"),
                         "old": .string("line2"), "new": .string("LINE-TWO")]), in: ctx)
            check(ok.contains("已替换 1 处"), "fs_edit 替换成功")

            var rejected = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("p/a.txt"),
                             "old": .string("line"), "new": .string("x")]), in: ctx)
            } catch { rejected = true }
            check(rejected, "原文不唯一时拒绝")

            var missing = false
            do {
                _ = try await FSTools.EditFile().run(
                    .object(["path": .string("p/a.txt"),
                             "old": .string("不存在的文本"), "new": .string("x")]), in: ctx)
            } catch { missing = true }
            check(missing, "找不到原文时报错")
        } catch {
            check(false, "编辑异常：\(error)")
        }

        // ---- glob / grep ----
        print("【查找】")
        do {
            _ = try await FSTools.WriteFile().run(
                .object(["path": .string("p/sub/b.md"), "content": .string("# md")]), in: ctx)
            let g = try await FSTools.Glob().run(
                .object(["pattern": .string("**/*.md")]), in: ctx)
            check(g.contains("p/sub/b.md"), "fs_glob 跨目录匹配")

            let one = try await FSTools.Glob().run(
                .object(["pattern": .string("*.md")]), in: ctx)
            check(!one.contains("p/sub/b.md"), "fs_glob 单个 * 不跨目录")

            let gr = try await FSTools.Grep().run(
                .object(["pattern": .string("LINE-TWO")]), in: ctx)
            check(gr.contains(":2:"), "fs_grep 给出行号")
        } catch {
            check(false, "查找异常：\(error)")
        }

        print("")
        if failed.isEmpty {
            print("=== 全部通过（\(passed)/\(passed)）===")
        } else {
            print("=== 失败 \(failed.count) 项 / 共 \(passed + failed.count) 项 ===")
            for f in failed { print("  · \(f)") }
        }
    }
}

// 顶层入口：Playgrounds 需要它才会执行
let fsDone = Task { await FSCheck.run() }
_ = fsDone
