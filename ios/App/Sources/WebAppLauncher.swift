import Foundation

#if canImport(SwiftUI) && canImport(WebKit)
import SwiftUI
import DSHEngine
import WebKit

/// 本地 web app 的启动器：列出 `apps/*/index.html`，点一个就运行。
@MainActor
public struct WebAppLauncher: View {

    @State private var apps: [Entry] = []
    @State private var showingNew = false
    @State private var newName = ""
    @State private var running: Entry?

    public struct Entry: Identifiable, Hashable {
        public var id: String { dir.lastPathComponent }
        public let dir: URL
        public var title: String
    }

    private var workspace: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("workspace", isDirectory: true)
    }

    public init() {}

    public var body: some View {
        NavigationStack {
            List {
                if apps.isEmpty {
                    Text("还没有 web app。点右上角 + 新建一个。")
                        .foregroundStyle(.secondary)
                }
                ForEach(apps) { app in
                    Button {
                        running = app
                    } label: {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 9)
                                .fill(Color.accentColor.opacity(0.18))
                                .frame(width: 38, height: 38)
                                .overlay(Text(String(app.title.prefix(1)))
                                            .font(.headline))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.title)
                                Text(app.dir.lastPathComponent)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "play.circle").font(.title3)
                        }
                    }
                }
            }
            .navigationTitle("应用")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        newName = ""
                        showingNew = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingNew) { newAppSheet }
            .sheet(item: $running) { app in
                NavigationStack {
                    WebAppRunner(appDir: app.dir, title: app.title)
                        .navigationTitle(app.title)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarTrailing) {
                                Button("完成") { running = nil }
                            }
                        }
                }
            }
            .task { reload() }
        }
    }

    private var newAppSheet: some View {
        NavigationStack {
            Form {
                TextField("名字（目录名）", text: $newName)
                    .autocorrectionDisabled()
            }
            .navigationTitle("新建 web app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showingNew = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !name.isEmpty {
                            create(name)
                            reload()
                        }
                        showingNew = false
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    // MARK: - 数据

    private func reload() {
        let appsDir = workspace.appendingPathComponent("apps", isDirectory: true)
        let fm = FileManager.default
        guard let dirs = try? fm.contentsOfDirectory(at: appsDir,
                                                     includingPropertiesForKeys: [.isDirectoryKey])
        else {
            apps = []
            return
        }
        var found: [Entry] = []
        for dir in dirs {
            let index = dir.appendingPathComponent("index.html")
            guard fm.fileExists(atPath: index.path) else { continue }
            let html = (try? String(contentsOf: index, encoding: .utf8)) ?? ""
            found.append(Entry(dir: dir, title: Self.title(from: html) ?? dir.lastPathComponent))
        }
        apps = found.sorted { $0.title < $1.title }
    }

    private func create(_ name: String) {
        let dir = workspace.appendingPathComponent("apps/\(name)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let html = PublishTools.webAppHTML(title: name)
        try? Data(html.utf8).write(to: dir.appendingPathComponent("index.html"))
    }

    /// 从 `<title>` 里取显示名。
    static func title(from html: String) -> String? {
        guard let start = html.range(of: "<title>"),
              let end = html.range(of: "</title>", range: start.upperBound..<html.endIndex)
        else { return nil }
        let t = String(html[start.upperBound..<end.lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

/// 跑一个本地 web app：WKWebView + 能力桥。
@MainActor
public struct WebAppRunner: UIViewRepresentable {

    let appDir: URL
    let title: String

    public init(appDir: URL, title: String) {
        self.appDir = appDir
        self.title = title
    }

    /// 协调器负责**持有**桥对象：桥需要 webView 才能回填结果，
    /// 而 webView 在 makeUIView 里才建出来，所以两边都得留在这里。
    public final class Coordinator {
        var bridge: WebAppBridge?
        init() {}
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true

        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: WebAppBridge.bootstrapScript(),
                                              injectionTime: .atDocumentStart,
                                              forMainFrameOnly: true))
        config.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: config)
        let bridge = WebAppBridge(root: appDir, webView: webView)
        controller.add(bridge, name: WebAppBridge.handlerName)
        context.coordinator.bridge = bridge

        let index = appDir.appendingPathComponent("index.html")
        webView.loadFileURL(index, allowingReadAccessTo: appDir)
        return webView
    }

    public func updateUIView(_ uiView: WKWebView, context: Context) {}
}
#endif
