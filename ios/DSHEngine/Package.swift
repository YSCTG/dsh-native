// swift-tools-version: 5.9
//
// iOS 引擎（跨平台部分）
//
// 这里只放"与界面无关、也不依赖 iOS SDK"的东西：模型调用、会话循环、工具协议、
// 沙箱内的文件与文本原语。这样它能在 macOS / Windows(Linux) 上用 `swift test` 验证，
// 而 SwiftUI 那一层留给 Mac 或 CI 的 Xcode 去编。
//
// 为什么这么切：Android 版最贵的部分就是引擎（对话循环 + 工具协议 + 原语），
// 界面反而是最便宜的。先把引擎在本机跑通，iOS 版才有意义。

import PackageDescription

let package = Package(
    name: "DSHEngine",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "DSHEngine", targets: ["DSHEngine"]),
    ],
    targets: [
        .target(name: "DSHEngine", path: "Sources/DSHEngine"),
        .testTarget(name: "DSHEngineTests", dependencies: ["DSHEngine"], path: "Tests/DSHEngineTests"),
    ]
)
