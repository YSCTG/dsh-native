import SwiftUI
import DSHEngine

/// 真实对话演示：从**环境变量**拿 key，走一遍完整对话，把消息画出来。
///
/// key 只从进程环境读（CI 用 `SIMCTL_CHILD_DEEPSEEK_KEY` 注入 GitHub Secret），
/// 不写进代码、不进仓库。
@MainActor
final class LiveModel: ObservableObject {
    @Published var status = "准备中…"
    @Published var lines: [String] = []

    func run() async {
        let env = ProcessInfo.processInfo.environment
        let key = (env["DEEPSEEK_KEY"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            status = "没有拿到 DEEPSEEK_KEY（CI Secret 未注入）"
            return
        }
        status = "已拿到 key（长度 \(key.count)）"

        UserDefaults.standard.set(key, forKey: "dsh.apiKey")
        let model = ChatModel()
        model.bootstrap()
        model.input = "只回五个字：iOS 已连通"
        model.send()

        for _ in 0..<80 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if !model.busy { break }
        }

        lines = model.rows.map { "\($0.role)  \($0.text)" }
        status = model.busy ? "超时" : "完成（\(model.status)）"
        print("[live] \(status)")
        for l in lines { print("[live] \(l)") }
    }
}

struct LiveChatView: View {
    @StateObject private var model = LiveModel()

    var body: some View {
        VStack(spacing: 0) {
            Text(model.status)
                .font(.headline)
                .foregroundStyle(model.status.hasPrefix("完成") ? Color.green
                                 : (model.status.contains("没有") ? Color.red : Color.secondary))
                .padding(14)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(model.lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.callout, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                }
                .padding(14)
            }
        }
        .task { await model.run() }
    }
}
