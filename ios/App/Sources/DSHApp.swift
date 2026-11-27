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

    var body: some Scene {
        WindowGroup {
            if selfCheck {
                SelfCheckView()
            } else if uiDemo {
                UIDemoView()
            } else if tools {
                ToolCallView()
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
            JSPlayground()
                .tabItem { Label("沙盒", systemImage: "chevron.left.forwardslash.chevron.right") }
        }
    }
}
