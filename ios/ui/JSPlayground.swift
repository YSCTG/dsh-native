import Foundation

#if canImport(SwiftUI) && canImport(JavaScriptCore)
import SwiftUI
import JavaScriptCore

/// 内置 JS Playground：写代码、点运行、看输出。
///
/// 为什么是 JS 而不是 Swift：iOS 上没有可用的 Swift 编译器，未签名的二进制也不允许执行。
/// JavaScriptCore 是系统自带的解释器，**不需要 JIT 授权**，所以这条路真的能跑。
///
/// 这也正好解释了"内置 playground"的边界：能内置的是解释器，不是编译器。
@MainActor
public struct JSPlayground: View {

    @State private var code: String = JSPlayground.starter
    @State private var output: [Line] = []

    public struct Line: Identifiable {
        public let id = UUID()
        public let text: String
        public let isError: Bool
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextEditor(text: $code)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(8)
                    .frame(maxHeight: .infinity)

                Divider()

                HStack {
                    Button {
                        run()
                    } label: {
                        Label("运行", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    Spacer()
                    Button("清空") { output = [] }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)

                Divider()

                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        if output.isEmpty {
                            Text("输出会显示在这里")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        ForEach(output) { line in
                            Text(line.text)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(line.isError ? Color.red : Color.primary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(12)
                }
                .frame(height: 200)
            }
            .navigationTitle("JS Playground")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - 执行

    private func run() {
        output = []
        guard let ctx = JSContext() else {
            output.append(Line(text: "拿不到 JSContext", isError: true))
            return
        }

        // 把 console.log 收进数组，运行完一次性取出来
        let collector = """
        var __lines = [];
        var console = {
          log: function(){ __lines.push(Array.prototype.slice.call(arguments).map(fmt).join(' ')); },
          error: function(){ __lines.push('错误: ' + Array.prototype.slice.call(arguments).map(fmt).join(' ')); }
        };
        function fmt(v){
          if (typeof v === 'string') return v;
          try { return JSON.stringify(v); } catch (e) { return String(v); }
        }
        """
        ctx.evaluateScript(collector)

        ctx.evaluateScript(code)
        if let exception = ctx.exception {
            let message = exception.toString() ?? "未知错误"
            output.append(Line(text: "异常：\(message)", isError: true))
            ctx.exception = nil
        }

        if let lines = ctx.objectForKeyedSubscript("__lines")?.toArray() as? [Any] {
            for item in lines {
                output.append(Line(text: "\(item)", isError: false))
            }
        }
        if output.isEmpty {
            output.append(Line(text: "（没有输出）", isError: false))
        }
    }

    static let starter = """
    // JavaScriptCore 里能跑的真代码，改完点「运行」
    const nums = [3, 1, 4, 1, 5, 9];
    console.log('原始:', nums);
    console.log('排序:', [...nums].sort((a, b) => a - b));

    const count = {};
    for (const n of nums) count[n] = (count[n] || 0) + 1;
    console.log('频次:', count);

    // 纯计算不受限；没有网络、没有文件权限——这是刻意的
    function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    console.log('fib(10) =', fib(10));
    """
}
#endif
