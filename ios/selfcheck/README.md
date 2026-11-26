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
全部通过（62/62）
```

**如果出现编译错误**——那就是最有价值的信息：说明这堆 Swift 里有类型问题，
把红字截图发回来，我按报错改。这正是目前 Windows 上做不到的验证。

## 之后

如果自检能过，下一步就可以在 Playgrounds 的 **App 项目**里写 SwiftUI 界面，
直接在 iPad 上跑起来看效果。届时：
- 引擎代码从单文件拆回包结构（或先继续用单文件，够用）
- 界面只订阅 `AgentService.send()` 的事件流
- 依然没有 XCTest，所以每次改完仍跑一遍自检

---

## 带界面的单文件：ChatDemo.swift

`ios/selfcheck/ChatDemo.swift`（约 50 KB）= 引擎 + SwiftUI 界面 + Playgrounds 入口。

用法：Playgrounds 新建 Playground，粘贴全文，运行 → 出现聊天界面；右上角钥匙图标填 API key。

界面包含：消息气泡（用户右、助手左）、流式追加、工具 chip（`· fs_read`）、
出错红字、发送/停止、新建会话、状态栏。

**它从未被编译过**（Windows 没有 iOS SDK）。Playgrounds 的报错就是第一手验证信息。

## 三个分层冒烟文件（iPad 更新后按顺序跑）

| 文件 | 内容 | 通过意味着 |
|---|---|---|
| `EngineSelfCheck.swift` | 引擎 + 62 条断言 | 引擎的类型和逻辑都对 |
| `ModelSmoke.swift` | 引擎 + 视图模型（**不含 SwiftUI**） | 并发 / Combine / `@MainActor` 那层没编错 |
| `ChatDemo.swift` | 全部 + SwiftUI 界面 | 界面能跑 |

**为什么要分层**：如果只看一个 50 KB 的文件报错，很难判断是引擎、视图模型还是 SwiftUI 的问题。
三层分开跑，第一次报错就能定位到层。顺序是 1 → 2 → 3，哪一层挂了就停在那里。

### 演示模式（不需要 API key）

`ChatModel.send()` 在没有 key 时走本地回显：把你说的话原样回一遍，并注明是演示模式。
所以界面（气泡、滚动、状态栏）不花钱、不依赖网络也能验证。
填上 key 之后才真的调 DeepSeek。

### 已提前降风险的几处（这层从未编译过）

- `@MainActor` 标在整个 `ChatView` 上（Swift 6 下非隔离闭包调主线程方法会报错）
- `@ObservedObject` 用显式 `ObservedObject(wrappedValue:)` 初始化
- `onChange` 用单参数闭包（双参数版是 iOS 17 才有的 API）
- 工具栏用 `navigationBarLeading/Trailing`（`topBarLeading` 要 iOS 17+）
- 进度指示用 `scaleEffect` 而不是 `controlSize`

### 浏览器工具（P2）

| 工具 | 作用 |
|---|---|
| `browser_open` | 打开网址，等加载完，返回标题 |
| `browser_text` | 读正文纯文本（上限 2 万字符） |
| `browser_js` | 执行任意 JS，结果字符串化返回 |
| `browser_click` | 按 CSS 选择器点击 |
| `browser_type` | 往输入框填值（触发 input/change，React 页面也认） |

自检里这 4 条浏览器用例**不联网**：用 `loadHTMLString` 载入一段内联 HTML，
再读标题、点按钮（验证 onclick 生效）、填输入框（验证 setter 与事件派发）。

### 系统工具（P3）

| 工具 | 作用 | 验证方式 |
|---|---|---|
| `clip_read` / `clip_write` | 读写剪贴板 | 自检里写→读（离线） |
| `notify` | 本地通知 | 需要授权，手动触发 |
| `schedule_add` / `schedule_list` / `schedule_remove` | 定时提醒（存 `workspace/schedule.json`） | 自检里加→列→删（离线） |
| 打开链接 | 只认 URL Scheme / Universal Link，不能任意启动 App | 手动 |

`schedule_*` 的设计取舍：**提醒先记在 JSON 里**，通知只是"按时弹出来"的尽力而为。
这样即使用户拒了通知权限，"提醒已记录"这件事仍然成立，不会静默丢。

### Office 生成（P4 部分）

| 工具 | 作用 |
|---|---|
| `office_write` | 生成 docx（标题/段落/表格）或 xlsx（多工作表，字符串/数字/布尔/公式） |

底层是自己写的 `ZipWriter`（store 方式）：iOS 上 Foundation **没有写 zip 的 API**，
`AppleArchive` 又是另一种格式，所以按 zip 规范拼字节——本地头、中央目录、EOCD 三块。

**这套布局用真库验证过**：把同一套字节和 XML 在 Python 里镜像实现，
再用 `python-docx` 和 `openpyxl` 打开，结果：

```
段落数: 4  · 第一段正文。 · 表格首行: ['项目','数值'] · 表格[示例, 42]
工作表: ['数据','第二张'] · A1/B1: 甲/乙 · B4: =SUM(B2:B4)（公式保留）
```

自检里对应 9 条断言：CRC32 标准向量（`123456789 → 0xCBF43926`）、zip 签名、
部件名是否存在、中文是否保真、以及真写一个 `.docx` 到工作区并确认落盘。

**未做**：`office_read`（读别人的 docx/xlsx 要自己解 deflate，是另一摊活）。

### 工作流调度（P5 部分）

WorkflowRunner：同时跑多个子任务，按输入顺序收结果。

三条语义都有断言钉住（用假 runner，不联网）：

| 语义 | 断言 |
|---|---|
| 并发上限 3 | 实测峰值 ≤ 3 且 ≥ 2（证明真并发，不是顺序跑） |
| 顺序保持 | 结果 id 序列 == 输入 id 序列（完成顺序打乱也不影响） |
| 错误隔离 | 一个失败，其余 5 个照常完成 |
| 遇错即停 | stopOnError 时剩余任务标记为"跳过"，不静默丢弃 |

### 发布产物（A / B / C 三条路）

| 工具 | 产物 | 能干什么 |
|---|---|---|
| webapp_new / webapp_list | pps/<名字>/index.html | **纯手机闭环**：写完直接在内置 WebView 跑，不用签名 |
| swiftpm_new | projects/<名字>/（Package.swift + SwiftUI 源码） | 交给 iPad 上的 **Swift Playgrounds** 编译运行（编译/签名是 Apple 做的） |
| ipa_pack | dist/<名字>.ipa（Payload/…app/） | **只能打包，不能签名**；未签名装不上任何设备 |

ipa_pack 的输出里会明确写"⚠️ 未签名：装不上任何设备"，
并在包里放一份 UNSIGNED-README.txt 说明为什么——避免用户以为拿到了可用安装包。
