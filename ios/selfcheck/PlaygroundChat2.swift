import SwiftUI
import PlaygroundSupport

// Playground 版聊天（配色完全显式，不依赖系统深/浅色）
//
// 上一版的问题：输入框是深色底，却给了黑字 → 打进去的字看不见。
// 这一版每个控件自带底色与字色。

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

struct Box: ViewModifier {
    func body(content: Content) -> some View {
        content
            .textFieldStyle(.plain)
            .padding(8)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4), lineWidth: 1))
            .foregroundStyle(Color.black)
    }
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
                    .modifier(Box())
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button("存") {
                    UserDefaults.standard.set(key, forKey: "k")
                    status = "已保存"
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(10)

            Rectangle().fill(Color.gray.opacity(0.3)).frame(height: 1)

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(log.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 15))
                            .foregroundStyle(line.hasPrefix("错误") ? Color.red : Color.black)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(12)
            }
            .frame(maxHeight: .infinity)

            Rectangle().fill(Color.gray.opacity(0.3)).frame(height: 1)

            HStack(spacing: 8) {
                TextField("说点什么…", text: $input)
                    .modifier(Box())
                Button(busy ? "…" : "发送") { send() }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
            }
            .padding(10)

            Text(status)
                .font(.caption)
                .foregroundStyle(Color.gray)
                .padding(.bottom, 6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.96, green: 0.96, blue: 0.97))
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
