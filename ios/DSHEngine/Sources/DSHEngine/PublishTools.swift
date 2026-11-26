import Foundation

/// 产物发布：web app、Swift 项目、IPA 包。
///
/// 三条路对应"在手机上做出能跑的东西"的三种现实：
///
/// 1. **web app** —— 纯手机闭环：写完直接在内置 WebView 跑，不需要签名
/// 2. **Swift 项目** —— 交给 iPad 上的 Swift Playgrounds 编译运行（编译/签名是 Apple 做的）
/// 3. **IPA 打包** —— 只能打包，**不能签名**；未签名的 IPA 装不上任何设备
///
/// 第 3 条必须把限制写进输出里，否则用户会以为拿到了能装的东西。
public enum PublishTools {

    public static func all() -> [any Tool] {
        [WebAppNew(), WebAppList(), SwiftProjectNew(), IpaPack()]
    }

    // MARK: - web app

    /// web app 的模板：能直接跑，演示了最小可用的交互与持久化。
    static func webAppHTML(title: String) -> String {
        """
        <!doctype html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <title>\(XML.escape(title))</title>
        <style>
          :root { color-scheme: dark; }
          body { margin:0; font-family: -apple-system, system-ui, sans-serif;
                 background:#111318; color:#e8eaed; padding:20px; }
          h1 { font-size:20px; margin:0 0 6px; }
          .hint { color:#9aa0a6; font-size:13px; margin-bottom:18px; }
          button { background:#2a6df4; color:#fff; border:0; border-radius:10px;
                   padding:12px 16px; font-size:15px; }
          #count { font-size:40px; font-weight:600; margin:16px 0; }
        </style>
        </head>
        <body>
        <h1>\(XML.escape(title))</h1>
        <div class="hint">这是一个本地 web app：文件在工作区里，改完重新打开就是新版本。</div>
        <div id="count">0</div>
        <button id="btn">点一下 +1</button>
        <script>
          var n = Number(localStorage.getItem('count') || 0);
          var el = document.getElementById('count');
          el.textContent = n;
          document.getElementById('btn').onclick = function () {
            n += 1;
            el.textContent = n;
            localStorage.setItem('count', String(n));
          };
        </script>
        </body>
        </html>
        """
    }

    struct WebAppNew: Tool {
        let name = "webapp_new"
        let description = "新建一个本地 web app（工作区 apps/<名字>/index.html），可直接运行。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "目录名，建议英文或中文短语"),
                .init("title", "string", "显示标题（可选）"),
            ], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let appName = args["name"]?.stringValue, !appName.isEmpty else {
                throw ToolError.badArgument("缺少 name")
            }
            let title = args["title"]?.stringValue ?? appName
            let dir = try ctx.resolve("apps/\(appName)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let index = dir.appendingPathComponent("index.html")
            try Data(PublishTools.webAppHTML(title: title).utf8).write(to: index)
            return "已创建 web app：apps/\(appName)/index.html（\(title)）"
        }
    }

    struct WebAppList: Tool {
        let name = "webapp_list"
        let description = "列出工作区里已有的 web app。"
        var parameters: JSONSchema { .none }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            let dir = try ctx.resolve("apps")
            guard let items = try? FileManager.default.contentsOfDirectory(at: dir,
                                        includingPropertiesForKeys: [.isDirectoryKey]) else {
                return "（还没有 web app）"
            }
            var out: [String] = []
            for item in items.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let index = item.appendingPathComponent("index.html")
                if FileManager.default.fileExists(atPath: index.path) {
                    out.append(item.lastPathComponent)
                }
            }
            return out.isEmpty ? "（还没有 web app）" : out.joined(separator: "\n")
        }
    }

    // MARK: - Swift 项目（交给 Playgrounds）

    static func packageSwift(name: String) -> String {
        """
        // swift-tools-version: 5.9
        import PackageDescription

        let package = Package(
            name: "\(name)",
            platforms: [.iOS(.v16)],
            products: [.executable(name: "\(name)", targets: ["\(name)"])],
            targets: [.executableTarget(name: "\(name)", path: "Sources/\(name)")]
        )
        """
    }

    static func appSwift(title: String) -> String {
        """
        import SwiftUI

        @main
        struct \(Self.swiftIdentifier(title))App: App {
            var body: some Scene {
                WindowGroup { ContentView() }
            }
        }

        struct ContentView: View {
            @State private var count = 0
            var body: some View {
                VStack(spacing: 18) {
                    Text("\(title)")
                        .font(.title2).bold()
                    Text("\\(count)")
                        .font(.system(size: 56, weight: .semibold, design: .rounded))
                    Button("点一下 +1") { count += 1 }
                        .buttonStyle(.borderedProminent)
                }
                .padding()
            }
        }
        """
    }

    /// 把标题变成合法的 Swift 标识符（只保留字母数字）。
    static func swiftIdentifier(_ text: String) -> String {
        var out = ""
        for ch in text {
            // 只保留 ASCII 字母数字：中文标题直接当类型名虽然合法，但拼出
            // 「记账App」这种名字在 Playgrounds 里排查起来更绕
            if ch.isASCII, ch.isLetter || ch.isNumber {
                out.append(ch)
            }
        }
        if out.isEmpty || out.first!.isNumber { out = "App" + out }
        return out
    }

    struct SwiftProjectNew: Tool {
        let name = "swiftpm_new"
        let description = """
        生成一个 SwiftUI App 的 Swift Package 项目（Package.swift + Sources）。\
        生成后可用「文件」交给 Swift Playgrounds 打开并运行。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "项目名（同时用作目录名）"),
                .init("title", "string", "界面上显示的标题（可选）"),
            ], required: ["name"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let project = args["name"]?.stringValue, !project.isEmpty else {
                throw ToolError.badArgument("缺少 name")
            }
            let title = args["title"]?.stringValue ?? project
            let root = try ctx.resolve("projects/\(project)")
            let srcDir = root.appendingPathComponent("Sources/\(project)")
            try FileManager.default.createDirectory(at: srcDir, withIntermediateDirectories: true)

            try Data(PublishTools.packageSwift(name: project).utf8)
                .write(to: root.appendingPathComponent("Package.swift"))
            try Data(PublishTools.appSwift(title: title).utf8)
                .write(to: srcDir.appendingPathComponent("\(project)App.swift"))

            let readme = """
            # \(title)

            用 Swift Playgrounds 打开这个目录：

            1. 在「文件」App 里找到本目录（工作区 `projects/\(project)`）
            2. 长按 → 共享 → 用 Playgrounds 打开（或先压缩成 zip 再打开）
            3. Playgrounds 会自己编译运行；它没有 XCTest，所以本项目的验证靠手动

            为什么编译这一步要交给 Playgrounds：iOS 上没有可用的 Swift 编译器，
            而且未签名的二进制不允许执行。Apple 只给了自家 Playgrounds 这个能力。
            """
            try Data(readme.utf8).write(to: root.appendingPathComponent("README.md"))

            return "已生成 Swift 项目：projects/\(project)（Package.swift + Sources）"
        }
    }

    // MARK: - IPA 打包

    static func infoPlist(bundleId: String, name: String, version: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
        "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>CFBundleIdentifier</key><string>\(XML.escape(bundleId))</string>
            <key>CFBundleName</key><string>\(XML.escape(name))</string>
            <key>CFBundleDisplayName</key><string>\(XML.escape(name))</string>
            <key>CFBundleExecutable</key><string>\(XML.escape(name))</string>
            <key>CFBundleShortVersionString</key><string>\(XML.escape(version))</string>
            <key>CFBundleVersion</key><string>1</string>
            <key>CFBundlePackageType</key><string>APPL</string>
            <key>MinimumOSVersion</key><string>16.0</string>
        </dict>
        </plist>
        """
    }

    struct IpaPack: Tool {
        let name = "ipa_pack"
        let description = """
        把当前项目打包成 .ipa 结构（Payload/名字.app/）。\
        注意：产出的是**未签名**包，装不上任何设备，只能用于存档或拿到电脑上签名。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("name", "string", "App 名（同时是可执行文件名）"),
                .init("bundle_id", "string", "例如 com.example.demo"),
                .init("version", "string", "版本号，默认 1.0"),
            ], required: ["name", "bundle_id"])
        }
        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let appName = args["name"]?.stringValue,
                  let bundleId = args["bundle_id"]?.stringValue else {
                throw ToolError.badArgument("需要 name 和 bundle_id")
            }
            let version = args["version"]?.stringValue ?? "1.0"

            var zip = ZipWriter()
            let base = "Payload/\(appName).app/"
            zip.add(base + "Info.plist",
                    PublishTools.infoPlist(bundleId: bundleId, name: appName, version: version))
            zip.add(base + "UNSIGNED-README.txt", """
            这个 IPA 是**未签名**的。

            - iOS 只运行通过校验的签名二进制，未签名的包在任何设备上都装不上
            - 想真正安装需要：Apple 签发的证书 + provisioning profile，在 macOS 上用
              `codesign` + `security cms` 签完再安装（或走 App Store / TestFlight）
            - 手机上无法完成签名：私钥在电脑钥匙串或 Apple 服务器上

            本文件的用途：检查包结构、存档、或拿到电脑上继续处理。
            """)

            let dist = try ctx.resolve("dist")
            try FileManager.default.createDirectory(at: dist, withIntermediateDirectories: true)
            let out = dist.appendingPathComponent("\(appName).ipa")
            let data = zip.finish()
            try data.write(to: out)

            return """
            已打包 dist/\(appName).ipa（\(data.count) 字节）
            ⚠️ 未签名：装不上任何设备。签名需要 Apple 证书，手机上做不到。
            """
        }
    }
}
