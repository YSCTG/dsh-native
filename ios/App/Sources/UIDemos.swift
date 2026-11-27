import SwiftUI
import DSHEngine

/// `-sessions` 演示：造几条会话，直接展示会话列表与消息渲染。
///
/// 存在的意义是 CI 能拍到这两块的画面——否则"会话管理做了没有"只能靠嘴说。
struct SessionListDemo: View {
    @State private var model = ChatModel()
    @State private var ready = false

    var body: some View {
        Group {
            if ready {
                SessionListView(model: model)
            } else {
                ProgressView("准备示例会话…")
            }
        }
        .task { await seed() }
    }

    private func seed() async {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let store = SessionStore(root: docs)

        // 造 3 条会话，标题不同、时间错开
        let samples: [(String, String)] = [
            ("帮我把这个 Excel 里的公式修好", "assistant"),
            ("查一下明天上海的天气", "assistant"),
            ("给项目写一份 README", "assistant"),
        ]
        for (i, item) in samples.enumerated() {
            if let s = try? store.create() {
                try? store.append(.init(role: "user", content: item.0), to: s.id)
                try? store.append(.init(role: "assistant",
                                        content: "这是第 \(i + 1) 条示例会话的回复。"), to: s.id)
                try? store.touch(s.id, firstUserText: item.0)
            }
        }
        // 关键：先 bootstrap 让 ChatModel 拿到 store，否则 refreshSessions 永远是空
        model.bootstrap()
        model.refreshSessions()
        // 留一点时间让列表渲染出来再截图
        try? await Task.sleep(nanoseconds: 300_000_000)
        ready = true
    }
}

/// `-md` 演示：一段带标题、列表、代码块的回复，用来看渲染效果。
struct MarkdownDemo: View {
    private let text = """
    # 修复结果

    我把 `Sheet1!B2` 的公式改成了 **相对引用**，这样下拉填充不会错位。

    主要改动：
    - `=$A$1+B2` → `=A1+B2`
    - 去掉了硬编码的行号
    - 补了一列校验

    ```swift
    let formula = "=A1+B2"
    print(formula)
    ```

    代码块和行内代码都应该用等宽字体显示，粗体要真的加粗。
    """

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Spacer(minLength: 40)
                        Text("帮我把这个 Excel 里的公式修好")
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(Color.accentColor.opacity(0.18))
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                    }

                    MarkdownView(text: text)

                    ToolChip(raw: "· fs_write\n已写入 formulas.txt（42 字节）")
                    ToolChip(raw: "· office_read\n读到 3 个工作表，共 128 个单元格")

                    Text("^ 上面两个是工具卡片（可点开）")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(16)
            }
            .navigationTitle("渲染效果")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// `-tasks` 演示：造待办与任务，展示面板。
struct TaskPanelDemo: View {
    @State private var ready = false

    var body: some View {
        Group {
            if ready { TaskPanelView() } else { ProgressView("准备示例数据…") }
        }
        .task { await seed() }
    }

    private func seed() async {
        let ws = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspace", isDirectory: true)
        try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true)

        let todos = """
        [{"text":"修好 Excel 公式","done":true},
         {"text":"写一份 README","done":false},
         {"text":"把结果发给同事","done":false}]
        """
        try? Data(todos.utf8).write(to: ws.appendingPathComponent("todo.json"))

        let tasks = """
        [{"id":1,"text":"读原始表格","status":"done"},
         {"id":2,"text":"改公式并校验","status":"doing"},
         {"id":3,"text":"导出新文件","status":"todo"}]
        """
        try? Data(tasks.utf8).write(to: ws.appendingPathComponent("tasks.json"))

        try? await Task.sleep(nanoseconds: 300_000_000)
        ready = true
    }
}
