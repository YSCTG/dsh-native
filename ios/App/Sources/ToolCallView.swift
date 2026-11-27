import SwiftUI
import DSHEngine

/// 工具调用端到端演示：让模型真的调 `fs_write` 写一个文件，再读回来。
///
/// 这是「agent」和「聊天机器人」的分界线：
///   模型输出 tool_call → 本地执行 → 结果回传 → 模型基于结果继续说话
/// 任何一环写错（参数分片、消息顺序、schema 形状）都会在这里暴露。
@MainActor
final class ToolCallModel: ObservableObject {
    @Published var status = "准备中…"
    @Published var lines: [String] = []
    @Published var filePath = ""
    @Published var fileExists = false
    @Published var fileContent = ""

    func run() async {
        let env = ProcessInfo.processInfo.environment
        let key = (env["DEEPSEEK_KEY"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            status = "没有拿到 DEEPSEEK_KEY"
            return
        }

        UserDefaults.standard.set(key, forKey: "dsh.apiKey")
        let target = "tool-demo.txt"
        let prompt = "用 fs_write 工具在工作区写一个文件 \(target)，内容写「工具调用成功」，"
            + "然后用 fs_read 读回来，把读到的内容原样告诉我。不要问我，直接做。"

        // CI 的出口网络偶尔在握手阶段抖动（NSURLErrorDomain -1200），
        // 这类错误重试一次基本就好；业务错误（401/400）不重试。
        var model = ChatModel()
        for attempt in 1...3 {
            model = ChatModel()
            model.bootstrap()
            model.newSession()          // 每次用干净会话，避免继承上一轮的消息
            model.input = prompt
            model.send()
            status = "第 \(attempt) 次尝试，等待模型调用工具…"

            for _ in 0..<120 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if !model.busy { break }
            }

            let last = model.rows.last?.text ?? ""
            let transient = last.contains("NSURLErrorDomain")
                || last.contains("-1200") || last.contains("TLS")
                || last.contains("timed out") || last.contains("网络")
            if !transient { break }
            status = "第 \(attempt) 次网络失败，重试…"
            try? await Task.sleep(nanoseconds: 3_000_000_000)
        }

        lines = model.rows.map { "\($0.role.prefix(4))  \($0.text.prefix(90))" }
        let bad = model.rows.contains { $0.role == "error" }
        status = model.busy ? "超时" : (bad ? "结束，但有错误" : "完成（\(model.status)）")

        // 从磁盘直接确认文件真的被写出来了（不依赖模型的说法）
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let ws = docs.appendingPathComponent("workspace", isDirectory: true)
        let f = ws.appendingPathComponent(target)
        filePath = f.path
        fileExists = FileManager.default.fileExists(atPath: f.path)
        if fileExists, let data = try? Data(contentsOf: f) {
            fileContent = String(decoding: data, as: UTF8.self)
        }

        print("[tools] status=\(status)")
        print("[tools] file exists=\(fileExists) path=\(filePath)")
        print("[tools] content=\(fileContent)")
        for l in lines { print("[tools] \(l)") }
    }
}

struct ToolCallView: View {
    @StateObject private var model = ToolCallModel()

    var body: some View {
        VStack(spacing: 0) {
            Text(model.status)
                .font(.headline)
                .foregroundStyle(model.status.hasPrefix("完成") ? Color.green
                                 : (model.status.contains("错误") || model.status.contains("超时") ? Color.red : Color.secondary))
                .padding(12)

            HStack(spacing: 6) {
                Text(model.fileExists ? "✓ 文件已被写出来" : "✗ 磁盘上没有这个文件")
                    .font(.caption).bold()
                    .foregroundStyle(model.fileExists ? Color.green : Color.red)
                Spacer()
            }
            .padding(.horizontal, 14)

            if model.fileExists {
                Text("内容：\(model.fileContent)")
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.top, 2)
            }

            Divider().padding(.top, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(model.lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption2, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }
        }
        .task { await model.run() }
    }
}
