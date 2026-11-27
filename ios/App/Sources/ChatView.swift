import Foundation

#if canImport(SwiftUI)
import SwiftUI
import DSHEngine

/// 聊天界面。
///
/// 只做三件事：把 `ChatModel.rows` 画出来、把输入框的内容交出去、把状态显示在顶部。
/// 所有引擎交互都发生在 `ChatModel` 里——视图不 await、不碰网络。
@MainActor
public struct ChatView: View {

    @ObservedObject private var model: ChatModel
    @State private var showKeySheet = false
    @State private var showSessions = false

    // ask_user：模型提问时弹出输入框（不接就会永久挂起）
    @State private var pendingQuestion: String?
    @State private var answerDraft = ""

    public init(model: ChatModel) {
        _model = ObservedObject(wrappedValue: model)
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                transcript
                Divider()
                composer
            }
            .navigationTitle(model.status.isEmpty ? "DSH" : model.status)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        model.refreshSessions()
                        showSessions = true
                    } label: {
                        Image(systemName: "list.bullet")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showKeySheet = true
                    } label: {
                        Image(systemName: "key")
                    }
                }
            }
            .sheet(isPresented: $showKeySheet) {
                keySheet
            }
            .sheet(isPresented: $showSessions) {
                SessionListView(model: model)
            }
        }
        .onAppear { model.bootstrap() }
        .task {
            // 界面侧轮询：有没有 agent 正在等用户回答
            while !Task.isCancelled {
                let q = await AskBroker.shared.pendingQuestion()
                if q != pendingQuestion {
                    answerDraft = ""
                    pendingQuestion = q
                }
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        .alert("需要你的回答", isPresented: Binding(
            get: { pendingQuestion != nil },
            set: { if !$0 { pendingQuestion = nil } })) {
            TextField("输入回答", text: $answerDraft)
            Button("取消") {
                let q = answerDraft
                answerDraft = ""
                pendingQuestion = nil
                Task { await AskBroker.shared.answer("（跳过：" + q + "）") }
            }
            Button("回答") {
                let text = answerDraft
                answerDraft = ""
                pendingQuestion = nil
                Task { await AskBroker.shared.answer(text) }
            }
        } message: {
            Text(pendingQuestion ?? "")
        }
    }

    // MARK: - 消息列表

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(model.rows) { row in
                        bubble(row).id(row.id)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            .onChange(of: model.rows.count) { _ in
                if let last = model.rows.last {
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func bubble(_ row: ChatModel.Row) -> some View {
        switch row.role {
        case "user":
            HStack {
                Spacer(minLength: 40)
                Text(row.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Color.accentColor.opacity(0.18))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .textSelection(.enabled)
            }
        case "tool":
            ToolChip(raw: row.text)
        case "error":
            Text(row.text)
                .font(.caption)
                .foregroundStyle(.red)
        default:
            VStack(alignment: .leading, spacing: 4) {
                if row.text.isEmpty && row.isStreaming {
                    Text("…").foregroundStyle(.secondary)
                } else {
                    MarkdownView(text: row.text)
                }
                if row.isStreaming {
                    ProgressView().scaleEffect(0.7)
                }
            }
        }
    }

    // MARK: - 输入

    private var composer: some View {
        HStack(spacing: 10) {
            TextField("说点什么…", text: $model.input, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Color.secondary.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 18))

            if model.busy {
                Button {
                    model.stop()
                } label: {
                    Image(systemName: "stop.circle.fill").font(.title2)
                }
            } else {
                Button {
                    model.send()
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(model.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: - key

    private var keySheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("DeepSeek API key")
                    .font(.headline)
                SecureField("sk-…", text: $model.draftKey)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Text("只存在本机的 UserDefaults 里；换成 Keychain 是下一步。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(18)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showKeySheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        model.saveKey()
                        showKeySheet = false
                    }
                }
            }
        }
    }
}
#endif
