# iOS 版构想（dsh-native · iOS）

> 状态：设计稿。**本机是 Windows，没有 Xcode / iOS SDK，无法编译或验证**。
> 下面所有"可实现"的判断依据是 iOS 平台公开能力与 App Store 审核规则，不是实机验证结论。

---

## 1. 先说底线：Android 版哪些东西在 iOS 上根本不存在

| Android 版能力 | iOS | 原因 |
|---|---|---|
| Python 沙箱（插件工具） | ❌ | 禁止解释器加载/执行脚本；2.5.2 不允许下载或执行改变功能的代码 |
| Node / npm install | ❌ | 同上（下载并执行可执行代码） |
| shell / `sh` 工具 | ❌ | 无 `Process`、无 fork/exec |
| 编译 APK（ecj/d8/aapt2） | ❌ | 无 shell，也不允许自带编译器产出可执行物 |
| 设备内 adb / shell 身份 | ❌ | 无 adb，无跨应用特权通道 |
| 操控别的 App（无障碍） | ❌ | 无第三方 UI 自动化 API |
| 任意路径文件访问（`/sdcard`） | ❌ | 只能访问自己沙盒 + 用户经文件选择器授权的目录 |
| 后台长跑 agent | ❌ | BGTaskScheduler 只给短窗口，系统随时会杀 |
| 自更新（下载 APK 安装） | ❌ | 只能走 App Store 更新 |
| Office/PDF 生成 | ✅ | 纯 Swift 可行（OOXML 手写、PDFKit） |
| 网页渲染 / HTML 应用 | ✅ | WKWebView |
| 语音合成、相机、照片、分享 | ✅ | 系统框架 |

**一句话**：iOS 版不可能是 Android 版的移植，只能是**重新定位**。

---

## 2. 三条可行路线

### A. 瘦客户端 + 可选的"执行端"（推荐）

```
┌──────────────┐   HTTPS/WebSocket   ┌─────────────────────┐
│ iOS App      │ ──────────────────► │ 执行端（三选一）      │
│ SwiftUI UI   │ ◄────────────────── │ ① Mac 上的 dsh 桌面  │
│ 会话 / 文件 / │                     │ ② 自建服务器 runner   │
│ 文档 / 分享   │                     │ ③ 家里的 Android 机  │
└──────────────┘                     └─────────────────────┘
```

- iOS 端：聊天、会话管理、工作区文件、文档生成、网页预览、分享
- 需要 shell / Python / 编译时：转发到执行端（用户自己的设备）
- **合规**：iOS 端不执行下载来的代码，只是 HTTP 客户端 + 本地文件处理 → 不触碰 2.5.2
- **代价**：要有人跑执行端；纯手机用户用不了高级能力

### B. 纯本地"声明式工具"（无代码执行）

把 Android 的插件模型从"Python 脚本"改成**声明式能力组合**：

```json
{
  "id": "charcount",
  "name": "字数统计",
  "tools": [{ "kind": "text.stats" }]        // 内置原语，不是脚本
}
```

- 原语由 App 用 Swift 实现（文本统计、正则、日期、单位换算、文件转换）
- 插件 = 选择原语 + 拼提示词 + 配参数，**不执行任意代码**
- 合规，且不需要任何执行端；但能力上限就是"内置原语库"的并集

### C. 自签 / sideload（个人向，不上架）

- 用免费 Apple ID 自签（AltStore 等），7 天重签一次
- 或付费开发者账号（$99/年）自签，1 年有效
- 能力上仍受平台限制（没有 shell），但**可以不做审核妥协**（例如内置更激进的自动化）
- 现实中仍拿不到 shell/Python，所以相对 B 的增益主要是"不受审核约束"，而不是"能做更多"

**建议：A + B 组合**。App Store 上架用 A（无执行端也能用聊天/文件/文档），B 作为本地插件模型；执行端作为可选增强。

---

## 3. Android 现有模块 → iOS 映射

| 模块 | iOS 版 | 说明 |
|---|---|---|
| `Agent`（对话循环、工具调用） | ✅ 重写为 Swift `actor` | URLSession + SSE 流式 |
| `Session`（会话持久化） | ✅ | 文件 + SQLite/CoreData |
| `Office`（docx/xlsx/pptx/pdf） | ✅ | 手写 OOXML zip；PDF 用 PDFKit |
| `Preview`（HTML 卡片渲染） | ✅ | WKWebView |
| `Workspaces`（工作区） | ✅ 改造 | App 容器 + 安全作用域书签（用户选的文件夹） |
| `Attach`（附件卡片） | ✅ | QuickLook 缩略图 |
| `Market/Plugin`（插件市场） | 🔶 降级为 B | 声明式插件，无脚本 |
| `Mcp`（MCP 客户端） | ✅ | 纯网络协议，可用 JSON-RPC over stdio? 无 stdio → 只支持 HTTP/SSE 传输 |
| `Python` / `Tools`(shell) / `Npm` | ❌ | 除非走执行端 |
| `ApkBuilder` / `adb` | ❌ | 不可能 |
| `Skills`（技能提示词） | ✅ | 纯文本，原样可用 |
| `UiAgentService`（无障碍） | ❌ | 无对应能力 |
| `Updater`（自更新） | ❌ | App Store 负责 |
| `Audit`（审计） | ✅ | 保留，对网络与文件操作记账 |
| `SelfCheck`（自检） | ✅ | XCTest + 运行时自检面板 |

---

## 4. 合规要点（上架前必须逐条过）

| 规则 | 风险 | 对策 |
|---|---|---|
| **2.5.2** 不得下载/执行改变功能的代码 | ⚠️ 高 | 不做脚本执行；执行端在用户自己的设备上，App 只做客户端 |
| **4.2** 最低功能要求 | ⚠️ 中 | 不能是"套壳网页"：要有本地会话、文件、文档生成等原生功能 |
| 5.1.1 隐私与数据收集 | ⚠️ 中 | 声明只存 API key 于 Keychain、不采集对话 |
| 5.1.2 数据使用 | ⚠️ 中 | 明示内容会发往 DeepSeek API |
| 加密出口（EAR） | ⚠️ 低 | 使用系统 TLS 即可声明豁免 |
| 后台执行 | ⚠️ 中 | 不做"常驻 agent"，只做前台会话 |
| iPad 适配 | - | 上架若声明支持 iPad 需做多窗口/尺寸适配 |

---

## 5. 里程碑（每步都有验收标准）

**M1 原型（1–2 周）**
- SwiftUI 聊天界面、Keychain 存 key、SSE 流式输出、会话列表与持久化
- 验收：真机（或模拟器）里完成一次多轮对话并能重启恢复

**M2 工作区与文档（1–2 周）**
- 文件 App 集成（安全作用域书签）、附件导入、docx/xlsx 最小生成、PDF 导出
- 验收：从"文件"App 选一个目录，生成一份 docx 并能用系统预览打开

**M3 声明式插件 + Shortcuts（1 周）**
- 原语库（文本/正则/日期/单位/文件转换）、插件清单、App Intents 暴露给快捷指令
- 验收：用快捷指令调用"对选中文本做统计"返回结果

**M4 执行端（可选，2 周）**
- 协议：WebSocket + 请求/响应/流式事件；鉴权用一次性配对码
- 验收：手机发 `ls`，Mac 上执行并回传

**M5 TestFlight 与上架（1–2 周）**
- 隐私清单、审核备注（重点解释"无代码执行"）、截图与元数据

---

## 6. 本环境能做什么 / 不能做什么

**能做**
- 写 Swift 源码（SwiftUI 视图、网络层、模型层）、Xcode 工程文件（`project.yml`/`Package.swift`）
- 写设计文档、协议定义、原语实现（纯 Swift、可跨平台测试的部分可在 Linux/Windows 上用 swift-corelibs 跑）
- 用 `swift-format` 之类做静态检查（需装 Swift 工具链）

**不能做（这里没有 macOS）**
- 编译 iOS target、跑模拟器、真机安装、上架、截图验证
- 结论：**iOS 版的任何"已验证"表述都必须由 Mac 上的构建产出**

---

## 7. 需要先定的四件事

1. **分发方式**：App Store 上架 / 自签 sideload / 只给自己用？
2. **执行端**：接受"需要一台电脑或服务器"吗？如果不接受，能力上限就是 B（声明式插件）
3. **最低系统版本**：iOS 17+ 可以用最新 SwiftUI 与 App Intents；iOS 15+ 兼容面更广但要写降级
4. **是否要 iPad / Mac Catalyst**：影响布局与里程碑

---

## 当前状态（2026-11-26 实测）

### 能做的

`ios/DSHEngine/` 是一个**跨平台 Swift 包**，不依赖 iOS SDK：

```
Sources/DSHEngine/
  Tool.swift            工具协议 + JSONSchema/JSONValue + 路径沙箱
  DeepSeekClient.swift  SSE 流式客户端（Foundation / FoundationNetworking）
  AgentLoop.swift       工具循环（含 onText / onTool 回调）
  AgentService.swift    服务层：会话 + 发送 + 事件流（actor）
  SessionStore.swift    会话存储（JSONL，半行不坏全篇）
  BuiltinTools.swift    fs_list / fs_read / fs_write / text_stats / net_fetch
Tests/DSHEngineTests/   EngineTests(9) + SessionStoreTests(7)
```

### 做不到的（都是实测，不是推测）

| 想法 | 结果 |
|---|---|
| 本机装 Xcode / iOS 模拟器 | 不可能：Windows 没有 iOS SDK |
| Swift for Windows 构建 | 装了 6.4.3，缺 `link.exe` → `swift build` 直接报 toolchain invalid |
| 补 MSVC（VS Build Tools C++ 工作负载） | **三次尝试全部失败**：`setup.exe modify` 返回 5007，`--includeRecommended` 变体返回 87（参数无效），winget `--override` 说"已安装、无升级"，忽略参数 |
| GitHub macOS runner（免费） | **不可用**：6 次运行 `ios-env-probe` / `ios-engine` 全部长时间 queued，注解写的是 `not acquired by Runner of type hosted`（容量不足），arm64 池尤其严重；换 `macos-13` 也一样排 |

### 因此现在的验证水位

- ✅ **语法**：`pwsh ios/check-syntax.ps1`（`swiftc -parse`）——8 个文件全过。
  这一关抓到过真错：`if let x = ... where cond` 在新版 Swift 已不允许。
- ❌ **类型检查**：需要 Foundation 的 Windows 头文件（来自 MSVC），当前做不到。
- ❌ **单元测试**：16 条用例写好了，但一次都没跑过（`swift test` 要链接）。
- ❌ **SwiftUI 界面**：需要 iOS SDK，完全没写。

### 结论

引擎（最容易写错的部分）已经成形，但**没有任何行为被验证过**。继续在 Windows 上堆代码，
只是在积累"没跑过的 Swift"。要往下走，必须先拿到一条能编译的通道：

1. **一台 Mac**（哪怕是旧的 Mac mini）：`cd ios/DSHEngine && swift test` 立刻就能验。
2. **付费 CI**（GitHub larger runner / Cirrus / Codemagic 免费额度）：不依赖本机。
3. 明确接受"先写完 UI，最后一次性在 Mac 上调"——风险最高，我不推荐。

在拿到通道之前，正确的动作是**停止增加无验证的代码**，而不是继续写界面。

---

## iPad 能做什么（2026-11-26 补充）

有一台 iPad 可用。查证后结论：

- ✅ **能**：编译运行 Swift、**类型检查**、在 App 项目里跑 SwiftUI、真机看效果
- ❌ **不能**：XCTest（Playgrounds 没有测试运行器）、iOS 模拟器、Xcode 的归档流程
- 依据：XCTest 无法在 Playgrounds 内运行，见 Apple 开发者论坛与 StackOverflow 的既有结论

所以 iPad 的实际价值是**补上"类型检查"这一环**——这是 Windows 上完全做不到的。
为了让 iPad 上能跑，已经准备好：

- `ios/selfcheck/EngineSelfCheck.swift`（31 KB）：引擎全部源码 + 19 条断言拼成的**单文件**，
  粘进 Playgrounds 就能运行（Playgrounds 不支持 SPM 多 target 结构）
- `ios/selfcheck/README.md`：三步操作与期望输出
- `build/make_playgrounds_bundle.py`：源码改动后重新生成单文件的脚本

自检通过之后，下一步是在 Playgrounds 的 App 项目里写 SwiftUI，界面只订阅
`AgentService.send()` 的事件流。
