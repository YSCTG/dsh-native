import Foundation
import PlaygroundSupport

// 最小网络诊断：只发一次请求，打印状态码和返回原文。
// 不含 SwiftUI、不含 actor，排除界面层的干扰。

PlaygroundPage.current.needsIndefiniteExecution = true

let key = "sk-在这里换成你的key"

let body: [String: Any] = [
    "model": "deepseek-chat",
    "messages": [["role": "user", "content": "只回两个字：收到"]],
    "stream": false
]

var req = URLRequest(url: URL(string: "https://api.deepseek.com/chat/completions")!)
req.httpMethod = "POST"
req.setValue("application/json", forHTTPHeaderField: "Content-Type")
req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
req.httpBody = try? JSONSerialization.data(withJSONObject: body)
req.timeoutInterval = 60

print("开始请求，key 长度 \(key.count)…")

URLSession.shared.dataTask(with: req) { data, resp, err in
    print("---- 结果 ----")
    if let err = err {
        print("网络错误:", err.localizedDescription)
        print("完整错误:", err)
    }
    let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
    print("HTTP:", code)
    print("Content-Type:", (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? "-")
    if let data = data {
        let text = String(decoding: data, as: UTF8.self)
        print("返回长度:", data.count)
        print("返回前 500 字符:")
        print(text.prefix(500))
    } else {
        print("返回体为空")
    }
}.resume()
