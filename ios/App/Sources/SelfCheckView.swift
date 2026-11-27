import SwiftUI
import DSHEngine

/// 在 iOS 上跑一遍引擎自检，并把结果画出来。
///
/// 检查逻辑在引擎内部的 `EngineSelfCheck`（各工具是 internal 的），
/// 这里只负责显示。CI 用 `simctl launch ... -selfcheck` 启动，截图即证据。
@MainActor
final class SelfCheckModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var running = true
    @Published var summary = "运行中…"

    func run() async {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let ws = docs.appendingPathComponent("selfcheck-\(UUID().uuidString)", isDirectory: true)

        let report = await EngineSelfCheck.run(workspace: ws)

        lines = report.lines
        summary = report.summary
        running = false
        print("[selfcheck] " + summary)
    }
}

struct SelfCheckView: View {
    @StateObject private var model = SelfCheckModel()

    var body: some View {
        VStack(spacing: 0) {
            Text(model.summary)
                .font(.headline)
                .foregroundStyle(model.running
                                 ? Color.secondary
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
