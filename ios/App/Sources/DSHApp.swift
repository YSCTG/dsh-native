import SwiftUI
import DSHEngine

// iOS App 入口（Xcode 工程用；Playgrounds 单文件版本用各自的 setLiveView 入口）
@main
struct DSHNativeApp: App {
    // CI 用 `simctl launch ... -selfcheck` 启动时，直接进入自检页
    private let selfCheck = CommandLine.arguments.contains("-selfcheck")
    private let uiDemo = CommandLine.arguments.contains("-uidemo")
    private let live = CommandLine.arguments.contains("-live")
    private let tools = CommandLine.arguments.contains("-tools")
    private let settings = CommandLine.arguments.contains("-settings")
    private let sessions = CommandLine.arguments.contains("-sessions")
    private let mdDemo = CommandLine.arguments.contains("-md")
    private let filesDemo = CommandLine.arguments.contains("-files")
    private let tasksDemo = CommandLine.arguments.contains("-tasks")
    private let askDemo = CommandLine.arguments.contains("-ask")

    var body: some Scene {
        WindowGroup {
            if selfCheck {
                SelfCheckView()
            } else if uiDemo {
                UIDemoView()
            } else if tools {
                ToolCallView()
            } else if askDemo {
                // 演示 ask_user：进主界面后主动挂起一个提问，界面应弹出输入框
                RootTabs()
                    .task {
                        try? await Task.sleep(nanoseconds: 900_000_000)
                        _ = await AskBroker.shared.ask("用哪种格式？markdown 还是纯文本")
                    }
            } else if filesDemo {
                FileBrowserView()
            } else if tasksDemo {
                TaskPanelDemo()
            } else if sessions {
                SessionListDemo()
            } else if mdDemo {
                MarkdownDemo()
            } else if settings {
                SettingsView()
            } else if live {
                LiveChatView()
            } else {
                RootTabs()
            }
        }
    }
}

struct RootTabs: View {
    var body: some View {
        TabView {
            ChatView(model: ChatModel())
                .tabItem { Label("聊天", systemImage: "bubble.left.and.bubble.right") }
            WebAppLauncher()
                .tabItem { Label("应用", systemImage: "square.grid.2x2") }
            FileBrowserView()
                .tabItem { Label("文件", systemImage: "folder") }
            JSPlayground()
                .tabItem { Label("沙盒", systemImage: "chevron.left.forwardslash.chevron.right") }
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
    }
}
