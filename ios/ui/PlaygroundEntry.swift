import Foundation

#if canImport(PlaygroundSupport) && canImport(SwiftUI)
import SwiftUI
import PlaygroundSupport

// iPad / Playgrounds 入口：三个页签——
//   聊天：DeepSeek agent（引擎 + 工具）
//   应用：本地 web app 启动器（点一个就跑）
//   沙盒：内置 JS playground（真跑 JavaScriptCore）
//
// 三个页签都只用系统框架（SwiftUI / WebKit / JavaScriptCore），不依赖任何第三方库。

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

PlaygroundPage.current.setLiveView(RootTabs())
#endif
