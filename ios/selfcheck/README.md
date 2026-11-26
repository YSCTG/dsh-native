# 用 iPad 验证 iOS 引擎

## 先说结论：iPad 能给什么、不能给什么

| 能力 | iPad（Swift Playgrounds） | Mac（Xcode） |
|---|---|---|
| 编译并运行 Swift 代码 | ✅ | ✅ |
| **类型检查**（我们最缺的一环） | ✅ | ✅ |
| 跑 SwiftUI 界面看效果 | ✅（App 项目里） | ✅ |
| XCTest 单元测试 | ❌ 没有测试运行器 | ✅ |
| iOS 模拟器 | ❌ | ✅ |
| 打包归档 / 上传 App Store | ⚠️ App 项目可以提交，但没有 Xcode 的归档流程 | ✅ |

依据：XCTest 无法在 Playgrounds 里运行（[Apple 论坛](https://developer.apple.com/forums/thread/760694)、
[StackOverflow](https://stackoverflow.com/questions/25051159/how-do-you-call-xctest-in-a-swift-playground)）。

**所以 iPad 的用处非常具体**：把"从没编译过的 Swift"变成"真的通过了类型检查、并在真机上跑出结果"。
测试仍然没有运行器，我们用自检脚本代替——检查项和 `Tests/` 里那 16 条是同一批。

## 三步跑起来

1. **装 Swift Playgrounds**（App Store，免费）。
2. **拿到 `EngineSelfCheck.swift`**（约 31 KB，引擎全源码 + 自检断言拼成的单文件）
   - 方式 A：Safari 打开
     `https://raw.githubusercontent.com/YSCTG/dsh-native/master/ios/selfcheck/EngineSelfCheck.swift`
     → 分享 → 存储到「文件」
   - 方式 B：电脑上 AirDrop / iCloud 云盘传过去
3. **运行**：Playgrounds → 新建 Playground（Blank）→ 打开那个 `.swift` 文件（或把内容粘贴进去）→ ▶ 运行。

## 期望输出

```
【路径沙箱】
  ✓ 拒绝 ../etc/passwd
  ✓ 工作区内路径放行
【工具参数】
  ✓ 解析 path
  ...
全部通过（19/19）
```

**如果出现编译错误**——那就是最有价值的信息：说明这堆 Swift 里有类型问题，
把红字截图发回来，我按报错改。这正是目前 Windows 上做不到的验证。

## 之后

如果自检能过，下一步就可以在 Playgrounds 的 **App 项目**里写 SwiftUI 界面，
直接在 iPad 上跑起来看效果。届时：
- 引擎代码从单文件拆回包结构（或先继续用单文件，够用）
- 界面只订阅 `AgentService.send()` 的事件流
- 依然没有 XCTest，所以每次改完仍跑一遍自检
