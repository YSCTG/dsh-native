import SwiftUI

/// 文件浏览页：看工作区里到底有什么。
///
/// 之前文件只能靠模型 fs_list 写出来，用户自己看不到——这是最影响可用性的一块。
struct FileBrowserView: View {

    @State private var path: [String] = []          // 相对工作区的路径栈
    @State private var items: [Item] = []
    @State private var preview: PreviewTarget?
    @State private var errorText = ""

    struct Item: Identifiable {
        let name: String
        let isDir: Bool
        let size: Int
        let modified: Date?
        var id: String { name }
    }

    enum PreviewTarget: Identifiable {
        case text(String, String)      // 名字, 内容
        case image(String, URL)
        var id: String {
            switch self {
            case .text(let n, _): return "t-" + n
            case .image(let n, _): return "i-" + n
            }
        }
    }

    private var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspace", isDirectory: true)
    }

    private var current: URL {
        path.reduce(root) { $0.appendingPathComponent($1, isDirectory: true) }
    }

    var body: some View {
        NavigationStack {
            List {
                if !errorText.isEmpty {
                    Text(errorText).foregroundStyle(.red).font(.footnote)
                }

                if items.isEmpty {
                    Text("这个目录是空的").foregroundStyle(.secondary)
                }

                ForEach(items) { item in
                    Button {
                        if item.isDir {
                            path.append(item.name)
                            reload()
                        } else {
                            open(item)
                        }
                    } label: {
                        HStack {
                            Image(systemName: item.isDir ? "folder" : icon(for: item.name))
                                .foregroundStyle(item.isDir ? Color.accentColor : .secondary)
                                .frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .lineLimit(1)
                                    .foregroundStyle(.primary)
                                if !item.isDir {
                                    Text(Self.displaySize(item.size))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if item.isDir {
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                    .accessibilityIdentifier("file-\(item.name)")
                }
            }
            .navigationTitle(path.last ?? "文件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !path.isEmpty {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button {
                            path.removeLast()
                            reload()
                        } label: {
                            Image(systemName: "chevron.left")
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { reload() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .sheet(item: $preview) { target in
                switch target {
                case .text(let name, let content):
                    NavigationStack {
                        ScrollView {
                            Text(content)
                                .font(.system(.footnote, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(14)
                        }
                        .navigationTitle(name)
                        .navigationBarTitleDisplayMode(.inline)
                    }
                case .image(let name, let url):
                    NavigationStack {
                        ScrollView {
                            if let data = try? Data(contentsOf: url), let img = UIImage(data: data) {
                                Image(uiImage: img).resizable().scaledToFit()
                            } else {
                                Text("读不出这张图").foregroundStyle(.secondary)
                            }
                        }
                        .navigationTitle(name)
                        .navigationBarTitleDisplayMode(.inline)
                    }
                }
            }
            .onAppear { reload() }
        }
    }

    private func reload() {
        errorText = ""
        let fm = FileManager.default
        do {
            let names = try fm.contentsOfDirectory(atPath: current.path)
            items = names.compactMap { name in
                guard !name.hasPrefix(".") else { return nil }
                let url = current.appendingPathComponent(name)
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { return nil }
                let attrs = try? fm.attributesOfItem(atPath: url.path)
                let size = (attrs?[.size] as? NSNumber)?.intValue ?? 0
                let modified = attrs?[.modificationDate] as? Date
                return Item(name: name, isDir: isDir.boolValue, size: size, modified: modified)
            }
            .sorted { a, b in
                if a.isDir != b.isDir { return a.isDir }
                return a.name.localizedStandardCompare(b.name) == .orderedAscending
            }
        } catch {
            items = []
            errorText = "读不了这个目录：\(error.localizedDescription)"
        }
    }

    private func open(_ item: Item) {
        let url = current.appendingPathComponent(item.name)
        let lower = item.name.lowercased()
        if ["png", "jpg", "jpeg", "gif", "webp", "heic"].contains(where: { lower.hasSuffix($0) }) {
            preview = .image(item.name, url)
            return
        }
        guard let data = try? Data(contentsOf: url) else {
            errorText = "读不到 \(item.name)"
            return
        }
        // 只预览文本；二进制给个提示，避免把乱码糊到屏幕上
        if let text = String(data: data.prefix(200_000), encoding: .utf8) {
            preview = .text(item.name, text)
        } else {
            preview = .text(item.name, "（二进制文件，\(Self.displaySize(item.size))，这里只预览文本）")
        }
    }

    private func icon(for name: String) -> String {
        let lower = name.lowercased()
        if lower.hasSuffix(".md") { return "doc.text" }
        if lower.hasSuffix(".json") { return "curlybraces" }
        if lower.hasSuffix(".swift") || lower.hasSuffix(".py") || lower.hasSuffix(".js") { return "chevron.left.forwardslash.chevron.right" }
        if lower.hasSuffix(".png") || lower.hasSuffix(".jpg") { return "photo" }
        if lower.hasSuffix(".docx") || lower.hasSuffix(".xlsx") { return "tablecells" }
        return "doc"
    }

    static func displaySize(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}
