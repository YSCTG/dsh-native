import SwiftUI

/// 待办 + 任务面板。
///
/// 模型用 `todo` / `task_*` 工具写的东西以前只存在 json 里，用户完全看不到。
/// 这里直接把 todo.json 和 tasks.json 读出来，并且能点掉（写回文件）。
struct TaskPanelView: View {

    @Environment(\.dismiss) private var dismiss

    struct Todo: Codable, Identifiable {
        var text: String
        var done: Bool
        var id: Int { text.hashValue }
    }

    struct Task: Codable, Identifiable {
        var id: Int
        var text: String
        var status: String
    }

    @State private var todos: [Todo] = []
    @State private var tasks: [Task] = []

    private var workspace: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspace", isDirectory: true)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if todos.isEmpty {
                        Text("没有待办").foregroundStyle(.secondary)
                    }
                    ForEach(Array(todos.enumerated()), id: \.offset) { index, item in
                        Button {
                            toggle(index)
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(item.done ? Color.green : .secondary)
                                Text(item.text)
                                    .strikethrough(item.done)
                                    .foregroundStyle(item.done ? .secondary : .primary)
                                Spacer()
                            }
                        }
                        .accessibilityIdentifier("todo-\(index)")
                    }
                } header: {
                    Text("待办（\(todos.filter { !$0.done }.count) 项未完成）")
                }

                Section {
                    if tasks.isEmpty {
                        Text("没有任务").foregroundStyle(.secondary)
                    }
                    ForEach(tasks) { t in
                        HStack {
                            Text("#\(t.id)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(t.text).lineLimit(2)
                            Spacer()
                            Text(statusLabel(t.status))
                                .font(.caption2)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(statusColor(t.status).opacity(0.16))
                                .foregroundStyle(statusColor(t.status))
                                .clipShape(Capsule())
                        }
                    }
                } header: {
                    Text("任务（\(tasks.count)）")
                }
            }
            .navigationTitle("待办与任务")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { load() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .onAppear { load() }
        }
    }

    private func load() {
        let todoURL = workspace.appendingPathComponent("todo.json")
        if let d = try? Data(contentsOf: todoURL),
           let list = try? JSONDecoder().decode([Todo].self, from: d) {
            todos = list
        } else {
            todos = []
        }

        let taskURL = workspace.appendingPathComponent("tasks.json")
        if let d = try? Data(contentsOf: taskURL),
           let list = try? JSONDecoder().decode([Task].self, from: d) {
            tasks = list
        } else {
            tasks = []
        }
    }

    private func toggle(_ index: Int) {
        guard todos.indices.contains(index) else { return }
        todos[index].done.toggle()
        if let d = try? JSONEncoder().encode(todos) {
            try? d.write(to: workspace.appendingPathComponent("todo.json"))
        }
    }

    private func statusLabel(_ s: String) -> String {
        switch s {
        case "doing": return "进行中"
        case "done": return "完成"
        default: return "待办"
        }
    }

    private func statusColor(_ s: String) -> Color {
        switch s {
        case "doing": return .orange
        case "done": return .green
        default: return .secondary
        }
    }
}
