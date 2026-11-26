import SwiftUI

// 极简版：把下面 KEY 换成你的 key，然后运行
let KEY = "sk-在这里填key"

func ask(_ q: String) async throws -> String {
    var r = URLRequest(url: URL(string: "https://api.deepseek.com/chat/completions")!)
    r.httpMethod = "POST"
    r.setValue("application/json", forHTTPHeaderField: "Content-Type")
    r.setValue("Bearer \(KEY)", forHTTPHeaderField: "Authorization")
    let b: [String: Any] = ["model": "deepseek-chat", "stream": false,
                            "messages": [["role": "user", "content": q]]]
    r.httpBody = try JSONSerialization.data(withJSONObject: b)
    let (d, resp) = try await URLSession.shared.data(for: r)
    let c = (resp as? HTTPURLResponse)?.statusCode ?? -1
    guard c == 200 else {
        throw NSError(domain: "HTTP \(c) \(String(decoding: d, as: UTF8.self).prefix(150))", code: c)
    }
    guard let o = try JSONSerialization.jsonObject(with: d) as? [String: Any],
          let ch = o["choices"] as? [[String: Any]],
          let m = ch.first?["message"] as? [String: Any],
          let t = m["content"] as? String else {
        throw NSError(domain: "看不懂返回", code: -2)
    }
    return t
}

struct V: View {
    @State var input = ""
    @State var out = "（还没有对话）"
    @State var busy = false

    var body: some View {
        VStack(spacing: 8) {
            ScrollView {
                Text(out).font(.system(size: 15))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack {
                TextField("消息", text: $input).textFieldStyle(.roundedBorder)
                Button(busy ? "…" : "发") { send() }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
            }
        }
        .padding(10)
    }

    func send() {
        let q = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !busy else { return }
        input = ""
        busy = true
        out += "\n\n你：\(q)"
        Task {
            do {
                let a = try await ask(q)
                out += "\n助手：\(a)"
            } catch {
                out += "\n错误：\(error.localizedDescription)"
            }
            busy = false
        }
    }
}

@main
struct DSHMini: App {
    var body: some Scene { WindowGroup { V() } }
}
