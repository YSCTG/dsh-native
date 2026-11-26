import SwiftUI
import PlaygroundSupport

// 极简 DeepSeek 客户端（iPad Playgrounds 单文件版）
// 目标：先能真的对话。非流式请求，代码尽量短。

struct Msg: Identifiable {
    let id = UUID()
    let mine: Bool
    var text: String
}

@MainActor
final class VM: ObservableObject {
    @Published var rows: [Msg] = []
    @Published var input = ""
    @Published var key = UserDefaults.standard.string(forKey: "k") ?? ""
    @Published var busy = false
    @Published var status = "就绪"

    func send() {
        let q = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !busy else { return }
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else { status = "先填 API key"; return }

        input = ""
        busy = true
        status = "请求中…"
        rows.append(Msg(mine: true, text: q))
        rows.append(Msg(mine: false, text: "…"))

        var messages: [[String: String]] = []
        for m in rows.dropLast() {
            messages.append(["role": m.mine ? "user" : "assistant", "content": m.text])
        }
        let body: [String: Any] = [
            "model": "deepseek-chat",
            "messages": messages,
            "stream": false
        ]

        var req = URLRequest(url: URL(string: "https://api.deepseek.com/chat/completions")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(k)", forHTTPHeaderField: "Authorization")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 120

        URLSession.shared.dataTask(with: req) { data, resp, err in
            Task { @MainActor in
                defer { self.busy = false }
                if let err = err {
                    self.finish("网络错误：\(err.localizedDescription)")
                    return
                }
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                guard let data = data, code == 200 else {
                    let raw = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    self.finish("HTTP \(code)：\(raw.prefix(200))")
                    return
                }
                guard
                    let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                    let choices = obj["choices"] as? [[String: Any]],
                    let msg = choices.first?["message"] as? [String: Any],
                    let text = msg["content"] as? String
                else {
                    self.finish("返回结构看不懂")
                    return
                }
                self.finish(text)
            }
        }.resume()
    }

    func finish(_ text: String) {
        if rows.last?.mine == false { rows.removeLast() }
        rows.append(Msg(mine: false, text: text))
        status = "完成"
    }
}

struct Root: View {
    @StateObject var vm = VM()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    SecureField("sk-… API key", text: $vm.key)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("存") {
                        UserDefaults.standard.set(vm.key, forKey: "k")
                        vm.status = "已保存"
                    }
                    .buttonStyle(.bordered)
                }
                .padding(10)

                Divider()

                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(vm.rows) { m in
                            Text(m.text)
                                .padding(10)
                                .background(m.mine ? Color.accentColor.opacity(0.2)
                                                   : Color.secondary.opacity(0.12))
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .frame(maxWidth: .infinity,
                                       alignment: m.mine ? .trailing : .leading)
                        }
                    }
                    .padding(12)
                }

                Divider()

                HStack {
                    TextField("说点什么…", text: $vm.input)
                        .textFieldStyle(.roundedBorder)
                    Button(vm.busy ? "…" : "发送") { vm.send() }
                        .buttonStyle(.borderedProminent)
                        .disabled(vm.busy)
                }
                .padding(10)

                Text(vm.status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 8)
            }
            .navigationTitle("DSH mini")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

PlaygroundPage.current.setLiveView(Root())
