import Foundation

#if canImport(WebKit)
import WebKit

/// 内嵌浏览器工具：对齐 Android 版的 browser_open / browser_text / browser_js /
/// browser_click / browser_type。
///
/// iOS 上这件事比 Android 那边干净：WKWebView 是系统原生组件，页面可见、可调试，
/// 用 `evaluateJavaScript` 就能读文本、点按钮、填表单。
///
/// 三个实现要点：
/// 1. WKWebView 必须在主线程创建与操作 → 整个类标 `@MainActor`
/// 2. 不用 WKNavigationDelegate 判断加载完成（那要写一堆代理方法），改成轮询 `isLoading`
/// 3. JS 结果统一转成字符串，并对长度设上限（网页里的对象可能巨大）
@MainActor
public final class WebBrowser {

    public static let shared = WebBrowser()

    private let webView: WKWebView
    private static let maxText = 20_000

    init() {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        webView = WKWebView(frame: .zero, configuration: config)
        webView.customUserAgent =
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }

    // MARK: - 加载

    /// 打开网址，等加载结束（或超时）。返回提示文字。
    public func open(_ urlString: String, timeout: Double = 25) async -> String {
        guard let url = URL(string: urlString) else { return "网址无效：\(urlString)" }
        webView.load(URLRequest(url: url))
        return await waitLoaded(timeout: timeout, label: urlString)
    }

    /// 直接加载一段 HTML（自检用：不依赖网络）。
    public func loadHTML(_ html: String) async -> String {
        webView.loadHTMLString(html, baseURL: nil)
        return await waitLoaded(timeout: 10, label: "内联 HTML")
    }

    private func waitLoaded(timeout: Double, label: String) async -> String {
        let start = Date()
        // 先给一次机会让 isLoading 变真，否则可能在 load 还没开始时就读到 false
        try? await Task.sleep(nanoseconds: 60_000_000)
        while webView.isLoading, Date().timeIntervalSince(start) < timeout {
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        if webView.isLoading { return "加载超时（\(Int(timeout)) 秒）：\(label)" }
        let title = webView.title ?? ""
        return title.isEmpty ? "已加载：\(label)" : "已加载：\(title)"
    }

    // MARK: - 读取

    public func text() async -> String {
        let raw = (try? await js("document.body ? document.body.innerText : ''")) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= Self.maxText { return trimmed }
        return String(trimmed.prefix(Self.maxText)) + "\n…（已截断，原文 \(trimmed.count) 字符）"
    }

    public func title() async -> String {
        (try? await js("document.title")) ?? ""
    }

    public func url() -> String {
        webView.url?.absoluteString ?? ""
    }

    // MARK: - 执行 JS

    public func js(_ code: String) async throws -> String {
        let value: Any? = try await withCheckedThrowingContinuation { cont in
            webView.evaluateJavaScript(code) { result, error in
                if let error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume(returning: result)
                }
            }
        }
        return Self.stringify(value)
    }

    static func stringify(_ value: Any?) -> String {
        switch value {
        case .none: return ""
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        case let a as [Any]: return a.map { stringify($0) }.joined(separator: "\n")
        case let d as [String: Any]:
            let data = (try? JSONSerialization.data(withJSONObject: d,
                                                    options: [.prettyPrinted, .sortedKeys])) ?? Data()
            return String(decoding: data, as: UTF8.self)
        default: return "\(value!)"
        }
    }
}

/// 浏览器工具集。
public enum WebTools {

    public static func all() -> [any Tool] {
        [Open(), Text(), JS(), Click(), TypeIn()]
    }

    // MARK: - 工具实现

    struct Open: Tool {
        let name = "browser_open"
        let description = "在内嵌浏览器里打开网址，返回标题。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("url", "string", "完整网址（含 https://）")],
                       required: ["url"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard ctx.networkAllowed else { throw ToolError.denied("当前上下文不允许联网") }
            guard let url = args["url"]?.stringValue, !url.isEmpty else {
                throw ToolError.badArgument("缺少 url")
            }
            return await WebBrowser.shared.open(url)
        }
    }

    struct Text: Tool {
        let name = "browser_text"
        let description = "读当前页面的正文（纯文本，最多 2 万字符）。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            await WebBrowser.shared.text()
        }
    }

    struct JS: Tool {
        let name = "browser_js"
        let description = "在当前页面执行一段 JavaScript，返回结果（字符串化）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("script", "string", "要执行的 JS")], required: ["script"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let script = args["script"]?.stringValue else { throw ToolError.badArgument("缺少 script") }
            do {
                return try await WebBrowser.shared.js(script)
            } catch {
                throw ToolError.failed("JS 执行失败：\(error.localizedDescription)")
            }
        }
    }

    struct Click: Tool {
        let name = "browser_click"
        let description = "用 CSS 选择器点一个元素。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("selector", "string", "CSS 选择器，例如 #submit")],
                       required: ["selector"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let sel = args["selector"]?.stringValue else { throw ToolError.badArgument("缺少 selector") }
            let code = """
            (function(){
              var el = document.querySelector(\(jsLiteral(sel)));
              if (!el) return 'NOT_FOUND';
              el.scrollIntoView({block:'center'});
              el.click();
              return 'CLICKED';
            })()
            """
            let r = try await WebBrowser.shared.js(code)
            return r == "NOT_FOUND" ? "没找到元素：\(sel)" : "已点击 \(sel)"
        }
    }

    struct TypeIn: Tool {
        let name = "browser_type"
        let description = "往输入框里填文字（会触发 input/change 事件，React 页面也能识别）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("selector", "string", "输入框的 CSS 选择器"),
                .init("text", "string", "要填入的文字"),
            ], required: ["selector", "text"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let sel = args["selector"]?.stringValue,
                  let text = args["text"]?.stringValue else {
                throw ToolError.badArgument("缺少 selector 或 text")
            }
            let code = """
            (function(){
              var el = document.querySelector(\(jsLiteral(sel)));
              if (!el) return 'NOT_FOUND';
              var proto = el instanceof HTMLTextAreaElement
                  ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, \(jsLiteral(text)));
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return 'TYPED';
            })()
            """
            let r = try await WebBrowser.shared.js(code)
            return r == "NOT_FOUND" ? "没找到输入框：\(sel)" : "已填入 \(sel)"
        }
    }

    /// 把字符串安全地嵌进 JS 字面量（转义引号、反斜杠、换行）。
    static func jsLiteral(_ s: String) -> String {
        var out = "\""
        for ch in s {
            switch ch {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default: out.append(ch)
            }
        }
        return out + "\""
    }
}
#endif
