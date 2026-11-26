# iOS 版进度与续接说明

> 写给未来的自己（或下一个接手的人）。iOS 这条路撞了很多平台限制，
> 每次重新摸索都要花几个小时，所以把结论固化在这里。

## 一句话现状

**引擎验证通过，界面卡在 Playgrounds 的显示行为上。** 不是代码逻辑问题。

## 已经验证过的（真机 iPad）

| 项 | 结果 | 验证方式 |
|---|---|---|
| iPad → DeepSeek API | ✅ `HTTP 200`，返回「收到」 | 纯 `print` 的 Playground |
| 路径沙箱（含软链接） | ✅ 7/7 | iPad 上跑断言 |
| HTTP 响应解析 | ✅ | 同上 |
| CRC32 / JSON / zip 打包 | ✅ 11/12（失败那条已修） | iPad 上跑断言 |
| SwiftUI 能渲染 | ✅ 白底正方框出现过 | `setLiveView` |

## 在 iPad 上抓到的两个真 bug（Windows 上永远发现不了）

1. **`standardizedFileURL` 不解软链接**：`/var` 与 `/private/var` 是同一目录，
   不归一就把合法路径判成越界。
2. **对不存在的路径调 `resolvingSymlinksInPath()` 不生效**：根解析成 `/private/var`，
   候选路径仍是 `/var`，前缀比较失败。
   → 正确做法：**先解析根**（根一定存在），再用解析后的根拼候选路径。

两处都已在 `ios/DSHEngine/Sources/DSHEngine/Tool.swift` 修好，并加了回归断言。

## 卡住的地方：Playgrounds 的界面显示

试过且无效的：

| 做法 | 结果 |
|---|---|
| `PlaygroundPage.current.setLiveView(Root())` | 打印 `setLiveView 已调用`，但面板仍显示控制台 |
| 给根视图固定尺寸 `.frame(width:760,height:980)` | 同上 |
| 手写 `.playground` 包（含 `Pages/Index.xcplaygroundpage/`） | 报「预期找到 pages 目录」 |
| 手写 `.swiftpm` 包（普通 executable） | 报「无法载入 app 目标描述」 |
| 手写 `.swiftpm` 包（`AppleProductTypes` + `.iOSApplication`） | 「prewarm failed」 |
| Playground 里放 `@main` | 报「`@main` 不能与顶层代码共存」 |

**还没试的**：App 项目里粘 `@main` 版本时出现的 `build failed`——**缺那一刻的红字原文**。
拿到红字大概率能一次修好。

## 另一条没走完的路：App 项目

Playgrounds 的「App 项目」不需要实时视图面板，界面是原生的。理论上是正解，但：

- 第一次 `build failed`，原因未知（没拿到红字）
- 生成的项目模板有时只给出一个 0 KB 文件

**下次续接的第一件事**：新建 App 项目 → 粘 `ios/selfcheck/MiniUltra.swift`（自带 `@main`）
→ 如果 `build failed`，**立刻截图红字**，那是唯一缺的信息。

## 已经准备好的材料

| 文件 | 用途 |
|---|---|
| `ios/selfcheck/MiniUltra.swift` | 2.3 KB，自带 `@main` + 界面 + 网络，最适合 App 项目 |
| `ios/selfcheck/PlaygroundChat2.swift` | Playground 版（`setLiveView`），界面已修好对比度 |
| `ios/selfcheck/NetProbe.swift` | 1.3 KB 纯网络探测，用于确认链路 |
| `ios/selfcheck/TinyFS.swift` | 2.9 KB 路径/文件断言，已在 iPad 通过 7/7 |
| `ios/DSHEngine/` | 完整引擎（27 个工具、62 条断言、Swift Package 结构） |

## 搬运代码到 iPad 的可行方式（按省力排序）

1. **App 项目 + 微信发小段**（2–3 KB 一段，分几条发）
2. **Safari 打开 gh-proxy 链接 → 文件 App 预览 → 全选拷贝 → 粘贴**
   `https://gh-proxy.org/https://raw.githubusercontent.com/YSCTG/dsh-native/master/ios/selfcheck/<文件名>`
3. 快捷指令：`获取 URL 的内容` → `拷贝到剪贴板`
4. **有电脑就 AirDrop**（最快；本机可以先把文件放桌面）

## 结论

iOS 的**能力上限**已经被摸清（见 `docs/ios-parity.md`）：文件、浏览器、系统能力、
文档生成都能在手机上做；shell / 编译 / 装 IPA 必须在电脑上。

**引擎值得继续做**（可在 iPad 上用 print 测试验证），**界面建议等有 Mac 时再动**——
Xcode 里这些问题一个都不存在。

## 什么时候该回来

- 拿到一台 Mac（哪怕是旧的）→ 直接 `open ios/DSHEngine/Package.swift`，Xcode 一跑就知道
- 或者有耐心继续在 iPad 上摸 App 项目的构建错误（需要红字）
- 或者想先补引擎能力（office_read、更多工具）→ 这部分用 print 测试就能验，不依赖界面
