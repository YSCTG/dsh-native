import Foundation

#if canImport(SwiftUI) && canImport(PlaygroundSupport)
import SwiftUI
import PlaygroundSupport

/// 把自检结果**画在屏幕上**，不依赖 Playgrounds 的 console。
///
/// 为什么需要这个：iPad 版 Playgrounds 默认不显示 `print` 的输出（console 要手动开），
/// 所以"点了没效果"很可能是输出看不见，而不是代码没跑。
/// 这个版本把每一行都渲染成文字，跑完在顶部显示大的结论横幅。

@MainActor
final class ReportModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var running = false
    @Published var finished = false
    @Published var summary = "点下面的按钮开始"

    func run() {
        guard !running else { return }
        lines = []
        running = true
        finished = false
        summary = "运行中…"

        SelfCheck.sink = { line in
            Task { @MainActor in
                self.lines.append(line)
            }
        }

        Task { @MainActor in
            await SelfCheck.run()
            self.running = false
            self.finished = true
            if let last = self.lines.last(where: { $0.contains("全部通过") || $0.contains("失败") }) {
                self.summary = last.trimmingCharacters(in: .whitespaces)
            } else {
                self.summary = "已结束，但没有结论行（检查是否编译失败）"
            }
            SelfCheck.sink = { line in Swift.print(line) }
        }
    }
}

struct ReportView: View {
    @StateObject private var model = ReportModel()

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(model.summary)
                    .font(.headline)
                    .foregroundStyle(color)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(model.running ? "运行中…" : "重新运行") {
                    model.run()
                }
                .disabled(model.running)
            }
            .padding(14)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(model.lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(line.contains("✗") ? Color.red : Color.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }
        }
        .onAppear { model.run() }
    }

    private var color: Color {
        if model.running { return .secondary }
        if model.summary.contains("全部通过") { return .green }
        if model.summary.contains("失败") { return .red }
        return .secondary
    }
}

PlaygroundPage.current.needsIndefiniteExecution = true
PlaygroundPage.current.setLiveView(ReportView())
#endif
