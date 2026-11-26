import Foundation

#if canImport(PlaygroundSupport) && canImport(SwiftUI)
import SwiftUI
import PlaygroundSupport

// iPad / Playgrounds 里的入口：直接把聊天界面挂成实时视图。
//
// 注意：这里用的是**未验证**的 SwiftUI 代码（Windows 上编不了 SwiftUI）。
// 如果 Playgrounds 报红字，把报错发回来——这是唯一的验证通道。

let model = ChatModel()
PlaygroundPage.current.setLiveView(ChatView(model: model))
#endif
