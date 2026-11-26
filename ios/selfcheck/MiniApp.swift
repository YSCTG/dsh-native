import SwiftUI
import PlaygroundSupport

// Swift 6 安全版：不用 ObservableObject、不用 @Sendable 闭包抓 UI 状态，
// 全部走 async/await + @State。Playgrounds（iPadOS 26）默认就是 Swift 6 模式。

func askDeepSeek(_ q: String, key: String) async throws -> String {
    var req = URLRequest(url: URL(string: "https://api.deepseek.com/chat/completions")!)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
    let body: [String: Any] = [
        "model": "deepseek-chat",
        "messages": [["role": "user", "content": q]],
        "stream": false
    ]
    req.httpBody = try JSONSerialization.data(withJSONObject: body)
    req.timeoutInterval = 120

    let (data, resp) = try await URLSession.shared.data(for: req)
    let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
    let text = String(decoding: data, as: UTF8.self)
    guard code == 200 else {
        throw NSError(domain: "HTTP \(code): \(text.prefix(200))", code: code)
    }
    guard
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
        let choices = obj["choices"] as? [[String: Any]],
        let msg = choices.first?["message"] as? [String: Any],
        let content = msg["content"] as? String
    else {
        throw NSError(domain: "返回结构看不懂: \(text.prefix(200))", code: -2)
    }
    return content
}

struct Root: View {
    @State private var key = UserDefaults.standard.string(forKey: "k") ?? ""
    @State private var input = ""
    @State private var log: [String] = []
    @State private var busy = false
    @State private var status = "就绪"

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                SecureField("sk-… API key", text: $key)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                Button("存") {
                    UserDefaults.standard.set(key, forKey: "k")
                    status = "已保存"
                }
                .buttonStyle(.bordered)
            }
            .padding(10)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(log.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 15))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }

            Divider()

            HStack {
                TextField("说点什么…", text: $input)
                    .textFieldStyle(.roundedBorder)
                Button(busy ? "…" : "发送") { send() }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
            }
            .padding(10)

            Text(status)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.bottom, 6)
        }
    }

    func send() {
        let q = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !busy else { return }
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else { status = "先填 key"; return }

        input = ""
        busy = true
        status = "请求中…"
        log.append("你：" + q)

        Task {
            do {
                let reply = try await askDeepSeek(q, key: k)
                log.append("助手：" + reply)
                status = "完成"
            } catch {
                log.append("错误：" + error.localizedDescription)
                status = "失败"
            }
            busy = false
        }
    }
}

PlaygroundPage.current.needsIndefiniteExecution = true
PlaygroundPage.current.setLiveView(Root())
