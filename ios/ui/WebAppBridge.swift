import Foundation

#if canImport(WebKit)
import WebKit
#endif
#if canImport(UserNotifications)
import UserNotifications
#endif

/// web app 的能力桥：让本地 HTML 能读工作区文件、发通知。
///
/// 设计要点：
/// 1. **作用域只到该 app 自己的目录**——桥的根目录是 `apps/<名字>/`，
///    不是整个工作区。web app 是被运行的代码，不该有超出自己目录的权限。
/// 2. Promise 形式：JS 侧 `await dsh.readFile('a.txt')`，
///    走 `postMessage` → 原生处理 → `evaluateJavaScript` 回填。
/// 3. 只读/写文本。二进制、删除、执行一律不给——这是"应用"不是"终端"。
public final class WebAppBridge: NSObject, WKScriptMessageHandler {

    public static let handlerName = "dsh"

    private let root: URL
    private weak var webView: WKWebView?

    public init(root: URL, webView: WKWebView?) {
        self.root = root
        self.webView = webView
        super.init()
    }

    /// 注入到每个页面的桥脚本（在文档开始时加载）。
    public static func bootstrapScript() -> String {
        """
        (function(){
          var callbacks = {};
          window.__dshResolve = function(r){
            var cb = callbacks[r.id];
            if (cb) { delete callbacks[r.id]; cb(r); }
          };
          function call(op, args){
            return new Promise(function(resolve){
              var id = String(Date.now()) + Math.random().toString(16).slice(2);
              callbacks[id] = resolve;
              try {
                window.webkit.messageHandlers.\(handlerName).postMessage({id: id, op: op, args: args || {}});
              } catch (e) {
                resolve({ok: false, error: '没有原生桥：' + e});
              }
            });
          }
          window.dsh = {
            readFile: function(p){ return call('read', {path: p}); },
            writeFile: function(p, text){ return call('write', {path: p, text: text}); },
            list: function(p){ return call('list', {path: (p || '.')}); },
            notify: function(title, body){ return call('notify', {title: title, body: body}); }
          };
        })();
        """
    }

    // MARK: - 消息处理

    public func userContentController(_ controller: WKUserContentController,
                                      didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let id = body["id"] as? String,
              let op = body["op"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]

        switch op {
        case "read":
            let path = args["path"] as? String ?? ""
            reply(id: id, result: readText(path))
        case "write":
            let path = args["path"] as? String ?? ""
            let text = args["text"] as? String ?? ""
            reply(id: id, result: writeText(path, text))
        case "list":
            let path = args["path"] as? String ?? "."
            reply(id: id, result: listDir(path))
        case "notify":
            let title = args["title"] as? String ?? "DSH"
            let text = args["body"] as? String ?? ""
            postNotification(title: title, body: text)
            reply(id: id, result: ["ok": true, "value": "已请求通知"])
        default:
            reply(id: id, result: ["ok": false, "error": "不支持的操作：\(op)"])
        }
    }

    // MARK: - 操作

    /// 把相对路径解析到 app 目录内，挡 `../` 逃逸。
    private func resolve(_ path: String) -> URL? {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate = trimmed.hasPrefix("/")
            ? URL(fileURLWithPath: trimmed)
            : root.appendingPathComponent(trimmed.isEmpty ? "." : trimmed)
        let rootPath = root.standardizedFileURL.path
        let target = candidate.standardizedFileURL.path
        guard target == rootPath || target.hasPrefix(rootPath + "/") else { return nil }
        return candidate
    }

    private func readText(_ path: String) -> [String: Any] {
        guard let url = resolve(path),
              let data = try? Data(contentsOf: url) else {
            return ["ok": false, "error": "读不到 \(path)"]
        }
        if data.count > 512 * 1024 {
            return ["ok": false, "error": "文件太大（超过 512 KB）"]
        }
        return ["ok": true, "value": String(decoding: data, as: UTF8.self)]
    }

    private func writeText(_ path: String, _ text: String) -> [String: Any] {
        guard let url = resolve(path) else {
            return ["ok": false, "error": "路径越界：\(path)"]
        }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
            return ["ok": true, "value": "已写入 \(text.utf8.count) 字节"]
        } catch {
            return ["ok": false, "error": "写入失败：\(error.localizedDescription)"]
        }
    }

    private func listDir(_ path: String) -> [String: Any] {
        guard let url = resolve(path),
              let items = try? FileManager.default.contentsOfDirectory(atPath: url.path) else {
            return ["ok": false, "error": "读不到目录 \(path)"]
        }
        return ["ok": true, "value": items.sorted()]
    }

    private func postNotification(title: String, body: String) {
        #if canImport(UserNotifications)
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            let req = UNNotificationRequest(identifier: UUID().uuidString,
                                            content: content, trigger: nil)
            center.add(req) { _ in }
        }
        #endif
    }

    /// 把结果回填给页面的 Promise。
    ///
    /// 做法：把结果序列化成 JSON，再**转义成单引号 JS 字符串**里的字面量，
    /// 页面侧 `JSON.parse` 还原。这样引号/换行/反斜杠都不用逐个猜。
    private func reply(id: String, result: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(result),
              let data = try? JSONSerialization.data(withJSONObject: result),
              var json = String(data: data, encoding: .utf8) else { return }
        json = json
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript("window.__dshResolve(JSON.parse('\(json)'))") { _, _ in }
        }
    }
}
