import SwiftUI
import DSHEngine

// iOS App 入口（Xcode 工程用；Playgrounds 单文件版本用各自的 setLiveView 入口）
@main
struct DSHNativeApp: App {
    var body: some Scene {
        WindowGroup {
            RootTabs()
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
