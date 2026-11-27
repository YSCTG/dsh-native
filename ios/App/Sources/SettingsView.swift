import SwiftUI

/// 设置页：填 API key、选模型、配 MCP 地址。
///
/// 没有这一页，装到手机上等于没法用——之前只有 CI 能靠环境变量注入 key。
/// 存在 UserDefaults 里（iOS 沙盒内）；真正的密钥存储应该走 Keychain，
/// 这里先把可用性做出来，Keychain 留作后续（页面上已注明）。
struct SettingsView: View {

    @AppStorage("dsh.apiKey") private var apiKey = ""
    @AppStorage("dsh.model") private var model = "deepseek-chat"
    @AppStorage("dsh.baseUrl") private var baseUrl = ""
    @AppStorage("dsh.mcpUrl") private var mcpUrl = ""

    @State private var revealKey = false
    @State private var savedAt: Date?
    @State private var testing = false
    @State private var testResult = ""

    private let models = ["deepseek-chat", "deepseek-reasoner"]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        if revealKey {
                            TextField("sk-…", text: $apiKey)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .font(.system(.footnote, design: .monospaced))
                        } else {
                            SecureField("sk-…", text: $apiKey)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }
                        Button {
                            revealKey.toggle()
                        } label: {
                            Image(systemName: revealKey ? "eye.slash" : "eye")
                        }
                        .buttonStyle(.borderless)
                    }

                    Link(destination: URL(string: "https://platform.deepseek.com/api_keys")!) {
                        Label("还没有 key？去获取", systemImage: "arrow.up.right.square")
                            .font(.footnote)
                    }

                    Button {
                        Task { await testKey() }
                    } label: {
                        HStack {
                            if testing { ProgressView().controlSize(.small) }
                            Text(testing ? "测试中…" : "测试连接")
                        }
                    }
                    .disabled(apiKey.isEmpty || testing)

                    if !testResult.isEmpty {
                        Text(testResult)
                            .font(.footnote)
                            .foregroundStyle(testResult.hasPrefix("✓") ? Color.green : Color.red)
                    }
                } header: {
                    Text("DeepSeek API key")
                } footer: {
                    Text("key 只存在本机。当前版本存在 UserDefaults 里，后续会换成 Keychain。")
                }

                Section {
                    Picker("模型", selection: $model) {
                        ForEach(models, id: \.self) { Text($0).tag($0) }
                    }
                    TextField("自定义 base URL（留空用官方）", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                } header: {
                    Text("模型")
                }

                Section {
                    TextField("MCP 服务器地址（可留空）", text: $mcpUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                } header: {
                    Text("MCP")
                } footer: {
                    Text("填了之后模型就能用 mcp_resources / mcp_read 读那个服务器上的资源。")
                }

                Section {
                    Button("保存") {
                        savedAt = Date()
                    }
                    .disabled(apiKey.isEmpty)

                    if let savedAt {
                        Text("已保存（\(savedAt.formatted(date: .omitted, time: .standard))）")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("改动即时生效，不用重启 App。")
                }

                Section {
                    LabeledContent("工具数", value: "52")
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")
                } header: {
                    Text("关于")
                }
            }
            .navigationTitle("设置")
        }
    }

    private func testKey() async {
        testing = true
        testResult = ""
        defer { testing = false }

        let trimmedBase = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let config = DeepSeekClient.Config(
            baseURL: trimmedBase.isEmpty ? URL(string: "https://api.deepseek.com")!
                                        : (URL(string: trimmedBase) ?? URL(string: "https://api.deepseek.com")!),
            apiKey: apiKey,
            model: model)
        let client = DeepSeekClient(config: config)
        let answer = await ClientRun.once(client: client, prompt: "只回两个字：收到")
        let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.contains("收到") {
            testResult = "✓ 连接正常（模型回了「收到」）"
        } else if trimmed.contains("401") || trimmed.lowercased().contains("invalid") {
            testResult = "✗ key 无效（服务器不认这个 key）"
        } else {
            testResult = "✗ " + String(trimmed.prefix(80))
        }
    }
}
