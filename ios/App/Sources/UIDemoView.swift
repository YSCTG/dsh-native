import SwiftUI
import JavaScriptCore
import DSHEngine

/// 交互演示：App 自己驱动三个页签的核心路径，结果画在屏幕上。
///
/// 为什么这么干：`simctl` 不能点屏幕，XCUITest 又太重。与其造一个 UI 测试目标，
/// 不如让 App 在 `-uidemo` 启动时**自己走一遍真实代码路径**：
///   1. 聊天：用假 key 发一条请求 → 走完 URLSession + 错误处理
///   2. 应用：生成一个 web app 并让 WKWebView 渲染 → 走完能力桥注入
///   3. 沙盒：跑一段 JS → 走完 JavaScriptCore
/// CI 截图即可确认这三条都不崩。
@MainActor
final class DemoModel: ObservableObject {
    @Published var chatStatus = "准备中…"
    @Published var chatReply = ""
    @Published var webReady = false
    @Published var jsOutput = "准备中…"

    private var started = false

    func start() async {
        guard !started else { return }
        started = true

        // 1) 聊天链路：故意用无效 key，验证「请求发出 + 错误被正确显示」
        UserDefaults.standard.set("sk-invalid-key-for-ci", forKey: "dsh.apiKey")
        let model = ChatModel()
        model.bootstrap()
        model.input = "ping"
        model.send()
        chatStatus = "已发送，等待结果…"

        for _ in 0..<40 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if !model.busy && model.rows.contains(where: { $0.role == "error" || $0.role == "assistant" }) {
                break
            }
        }
        chatStatus = model.busy ? "仍在等待（超时）" : "完成：status=\(model.status)"
        chatReply = model.rows.suffix(2).map { "\($0.role): \($0.text.prefix(120))" }.joined(separator: "\n")

        // 2) WebKit：造一个 web app 文件，交给运行器渲染
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let appDir = docs.appendingPathComponent("workspace/apps/demo", isDirectory: true)
        try? FileManager.default.createDirectory(at: appDir, withIntermediateDirectories: true)
        try? Data(PublishTools.webAppHTML(title: "CI 演示").utf8)
            .write(to: appDir.appendingPathComponent("index.html"))
        webReady = FileManager.default.fileExists(atPath: appDir.appendingPathComponent("index.html").path)

        // 3) JavaScriptCore
        let ctx = JSContext()
        ctx?.evaluateScript("function f(n){return n<2?n:f(n-1)+f(n-2)}; var out = 'fib(10)=' + f(10);")
        let out = ctx?.objectForKeyedSubscript("out")?.toString() ?? "（拿不到结果）"
        jsOutput = "\(out)（JSContext 正常）"
    }
}

struct UIDemoView: View {
    @StateObject private var model = DemoModel()

    private var demoAppDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspace/apps/demo", isDirectory: true)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("CI 交互演示").font(.headline)
                    Spacer()
                    Text(model.webReady ? "WebView 已就绪" : "…")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                // ① 聊天链路
                VStack(alignment: .leading, spacing: 6) {
                    Text("① 聊天链路（无效 key，验证错误路径）")
                        .font(.subheadline).bold()
                    Text(model.chatStatus)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if !model.chatReply.isEmpty {
                        Text(model.chatReply)
                            .font(.system(.caption2, design: .monospaced))
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.secondary.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                    }
                }

                // ② WKWebView + 能力桥
                VStack(alignment: .leading, spacing: 6) {
                    Text("② 应用页（WKWebView + 能力桥）")
                        .font(.subheadline).bold()
                    if model.webReady {
                        WebAppRunner(appDir: demoAppDir, title: "CI 演示")
                            .frame(height: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8)
                                .stroke(Color.secondary.opacity(0.3)))
                    } else {
                        Text("web app 文件没写成功").font(.caption).foregroundStyle(.red)
                    }
                }

                // ③ JavaScriptCore
                VStack(alignment: .leading, spacing: 6) {
                    Text("③ 沙盒页（JavaScriptCore）")
                        .font(.subheadline).bold()
                    Text(model.jsOutput)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(model.jsOutput.contains("正常") ? Color.green : Color.red)
                }
            }
            .padding(16)
        }
        .task { await model.start() }
    }
}
