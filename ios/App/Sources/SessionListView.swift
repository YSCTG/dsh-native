import SwiftUI
import DSHEngine

/// 会话列表：切换、删除、新建。
///
/// 之前只有一个"新建"按钮——历史会话存了却看不见，等于没有。
struct SessionListView: View {
    @ObservedObject var model: ChatModel
    @Environment(\.dismiss) private var dismiss
    @State private var pendingDelete: SessionStore.Session?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        model.newSession()
                        dismiss()
                    } label: {
                        Label("新建会话", systemImage: "square.and.pencil")
                    }
                    .accessibilityIdentifier("new-session")
                }

                Section {
                    if model.sessions.isEmpty {
                        Text("还没有会话")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.sessions) { s in
                            Button {
                                model.open(s.id)
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(s.title)
                                            .lineLimit(1)
                                            .foregroundStyle(.primary)
                                        Text(Self.relative(s.updated))
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if model.currentSession == s.id {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                            .accessibilityIdentifier("session-\(s.id)")
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    pendingDelete = s
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: {
                    Text("历史（\(model.sessions.count)）")
                }
            }
            .navigationTitle("会话")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .confirmationDialog("删除后无法恢复",
                                isPresented: Binding(get: { pendingDelete != nil },
                                                     set: { if !$0 { pendingDelete = nil } }),
                                titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    if let s = pendingDelete { model.delete(s.id) }
                    pendingDelete = nil
                }
                Button("取消", role: .cancel) { pendingDelete = nil }
            }
            .onAppear { model.refreshSessions() }
        }
    }

    static func relative(_ date: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: date, relativeTo: Date())
    }
}
