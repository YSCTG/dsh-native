import SwiftUI
import DSHEngine

/// 在 iOS 上跑一遍引擎自检，并把结果画出来。
///
/// 为什么要放进 App：`swift test` 跑的是 macOS。真正要验的是 **iOS 沙盒**——
/// 那里 Documents 目录、软链接、大小写敏感、文件权限都不一样。
/// CI 用 `simctl launch ... -selfcheck` 启动它，截图就是证据。
@MainActor
final class SelfCheckModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var running = true
    @Published var summary = "运行中…"

    private var pass = 0
    private var fail: [String] = []

    private func check(_ ok: Bool, _ name: String) {
        if ok {
            pass += 1
            lines.append("✓ \(name)")
        } else {
            fail.append(name)
            lines.append("✗ \(name)")
        }
    }

    func run() async {
        lines = []
        pass = 0
        fail = []

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let ws = docs.appendingPathComponent("selfcheck-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: ws)

        // ---- 沙箱 ----
        lines.append("【路径沙箱】")
        do {
            _ = try ctx.resolve("../escape.txt")
            check(false, "拒绝 ../ 逃逸")
        } catch {
            check(true, "拒绝 ../ 逃逸")
        }
        check((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")

        // ---- fs 工具 ----
        lines.append("【文件工具】")
        do {
            let w = try await FSTools.WriteFile().run(
                .object(["path": .string("p/a.txt"),
                         "content": .string("l1\nl2\nl3")]), in: ctx)
            check(w.contains("已写入"), "fs_write 写入")

            let r = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt")]), in: ctx)
            check(r.contains("1\tl1"), "fs_read 带行号")

            let page = try await FSTools.ReadFile().run(
                .object(["path": .string("p/a.txt"),
                         "offset": .number(2), "limit": .number(1)]), in: ctx)
            check(page.contains("2\tl2") && !page.contains("1\tl1"), "fs_read 分页")

            let e = try await FSTools.EditFile().run(
                .object(["path": .string("p/a.txt"),
                         "old": .string("l2"), "new": .string("L2")]), in: ctx)
            check(e.contains("已替换 1 处"), "fs_edit 替换")

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

            let gr = try await FSTools.Grep().run(
                .object(["pattern": .string("L2")]), in: ctx)
            check(gr.contains(":2:"), "fs_grep 行号")
        } catch {
            check(false, "fs 工具异常：\(error)")
        }

        // ---- 会话存储 ----
        lines.append("【会话存储】")
        do {
            let store = SessionStore(root: ws)
            let s = try store.create()
            try store.append(.init(role: "user", content: "你好"), to: s.id)
            try store.append(.init(role: "assistant", content: "在"), to: s.id)
            let msgs = try store.load(s.id)
            check(msgs.count == 2, "追加两条读回两条")

            let file = ws.appendingPathComponent("sessions/\(s.id).jsonl")
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
        lines.append("【文档生成】")
        let docx = OfficeTools.buildDocx(title: "自检", paragraphs: ["正文"], table: nil)
        check(docx.count > 400, "docx 生成（\(docx.count) 字节）")
        check(docx.count > 3 && docx[0] == 0x50 && docx[1] == 0x4B, "docx 是 zip 头")
        check(ZipWriter.crc32(Data("123456789".utf8)) == 0xCBF4_3926, "CRC32 标准向量")

        // ---- 网络客户端（不发请求，只确认能构造）----
        lines.append("【客户端】")
        _ = DeepSeekClient(config: .init(apiKey: "sk-test"))
        check(true, "DeepSeekClient 可构造")

        running = false
        if fail.isEmpty {
            summary = "全部通过（\(pass)/\(pass)）"
        } else {
            summary = "失败 \(fail.count) 项：\(fail.joined(separator: "、"))"
        }
        print("[selfcheck] " + summary)
    }
}

struct SelfCheckView: View {
    @StateObject private var model = SelfCheckModel()

    var body: some View {
        VStack(spacing: 0) {
            Text(model.summary)
                .font(.headline)
                .foregroundStyle(model.running ? Color.secondary
                                 : (model.summary.hasPrefix("全部通过") ? Color.green : Color.red))
                .padding(14)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(model.lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(line.hasPrefix("✗") ? Color.red : Color.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }
        }
        .task { await model.run() }
    }
}
