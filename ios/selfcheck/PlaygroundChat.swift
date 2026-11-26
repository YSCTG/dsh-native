import SwiftUI
import PlaygroundSupport

// Playground 版聊天界面（setLiveView）
// 关键：显式指定配色 —— 深色模式下默认文字是白色，配白底就看不见了。

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
            HStack(spacing: 8) {
                SecureField("sk-… key", text: $key)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .foregroundStyle(.black)
                Button("存") {
                    UserDefaults.standard.set(key, forKey: "k")
                    status = "已保存"
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(10)

            Divider().background(Color.gray)

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(log.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 15))
                            .foregroundStyle(line.hasPrefix("错误") ? Color.red : Color.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }
            .frame(maxHeight: .infinity)

            Divider().background(Color.gray)

            HStack(spacing: 8) {
                TextField("说点什么…", text: $input)
                    .textFieldStyle(.roundedBorder)
                    .foregroundStyle(.black)
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.97, green: 0.97, blue: 0.98))
        .preferredColorScheme(.light)
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
PlaygroundPage.current.setLiveView(Root().preferredColorScheme(.light))
