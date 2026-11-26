# iOS 能力对齐安卓版：逐项映射

> 目标：除了"操控别的 App"（iOS 没有 AccessibilityService 等价物，已放弃），其余尽量对齐。
> 依据：Android 版实际注册了 **72 个工具**（`ToolCatalog.java`，按能力门禁分组）。

## 总览：72 个工具的归属

| 归途 | 数量 | 说明 |
|---|---|---|
| 纯 Swift 重写（Foundation） | 26 | 文件、文本、会话、todo、自检 |
| 网络 / WebView | 12 | 抓取、搜索、内嵌浏览器、MCP over HTTP |
| 系统 API + App Intents | 13 | 通知、日程、剪贴板、分享、快捷指令 |
| 文档读写与渲染 | 8 | OOXML 生成 + WebView 渲染（复用安卓的 JS 资源） |
| Agent 编排 | 13 | 子 agent / 团队 / 工作流（都是并发 API 调用） |
| **必须远端执行** | 9 | shell、python、npm、构建 |
| **放弃** | 6 | 操控别的 App、列已装应用、截别的 App |

## 逐项映射

### 1. 纯 Swift 重写（Phase 1，iPad 上就能验）

| Android 工具 | iOS 做法 |
|---|---|
| `fs_list` `fs_read` `fs_write` `fs_edit` `fs_glob` `fs_grep` | 作用域限制在 App 容器（Documents）内；相对路径解析 + `../` 逃逸拦截 |
| `dev_info` `dev_selfcheck` | 版本、设备、存储、自检（就是 `ios/selfcheck` 那套） |
| `todo` | 工作区里的 `todo.json` |
| `skill` | 工作区 `skills/*.md` 读入上下文 |
| `text_stats` 及文本类 | 已在 `BuiltinTools` 里 |

**额外**：iOS 允许用户通过 Files App 授权外部目录（security-scoped bookmark），
所以文件工具可以覆盖 iCloud Drive / 本地"我的 iPhone"，不必只限容器内。

### 2. 网络（Phase 2）

| Android | iOS |
|---|---|
| `net_fetch` | `URLSession`（已实现） |
| `web_search` | 抓搜索引擎 HTML 或接 API；安卓怎么做的就照搬（同一个数据源最稳） |
| `browser_open/text/js/click/type` | **WKWebView + `evaluateJavaScript`**，能力比安卓的 headless 更干净（可见页面可调试） |
| `mcp_read` `mcp_resources` | MCP 走 **Streamable HTTP**；stdio 传输在 iOS 上不可行 |

### 3. 系统能力（Phase 3）

| Android | iOS |
|---|---|
| `notify` | `UNUserNotificationCenter` |
| `schedule_add/list/remove` | 本地通知 + `UNCalendarNotificationTrigger`；需要系统级提醒则用 EventKit |
| `clip_read` `clip_write` | `UIPasteboard` |
| `screenshot` | 只能截**自己**的界面（`UIGraphicsImageRenderer`） |
| `app_launch` | 有限：`UIApplication.open` + URL Scheme / Universal Link，且在 Info.plist 里声明 |
| `app_list` | **放弃**：iOS 不允许枚举已装应用 |
| `ui_tap/type/gesture` `ui_dump` | **放弃**：没有 AccessibilityService 等价物 |
| — | **新增**：把工具暴露成 **App Intents**，让「快捷指令」能调用——这是 iOS 给第三方的唯一跨 App 通道 |

### 4. 文档（Phase 4）

| Android | iOS |
|---|---|
| `office_write` | 自己写 OOXML（docx/xlsx/pptx）+ PDF；zip 用 `libcompression` 打（Foundation 没有 zip 写入） |
| `office_read` | 解 zip 读 XML（`ZIPFoundation` 或自写） |
| `office_preview` | **WKWebView + docx-preview / SheetJS** — 与安卓用的是同一份 JS 资源，直接复用 |
| `office_restyle` | 同 `office_write`，改样式模板 |

### 5. Agent 编排（Phase 5）

| Android | iOS |
|---|---|
| `subagent` `team_*` `workflow` `task_*` `loop` | 都是并发模型调用 + 事件汇总，纯逻辑；`Task` 并发即可 |
| `goal_set/get/clear` `plan_present` `ask_user` | 本地状态 + UI |
| `dev_webui` `render` | 放弃（Android 专有的本地 Web UI 渲染） |

### 6. 必须远端执行（Phase 6，可选）

`sh` `python` `adb` `npm_install` `app_build` `app_run` `job_start/list/output/kill` `game_*`

iOS 上要这些，只有一条路：**瘦客户端 + 执行端**。
执行端可以是自己的 Mac/PC（SSH over Tailscale）或一台服务器。
这决定了两件事：
1. 工具定义里要有"远端执行"这一层抽象（和本地原语同一套协议）
2. 断网时这些工具必须**明确报不可用**，而不是假装能跑

## 阶段划分与验收

| 阶段 | 内容 | 验收方式 |
|---|---|---|
| P1 | 文件/文本/自检/todo/skill | iPad Playgrounds 跑自检（无 XCTest） |
| P2 | 抓取、搜索、WKWebView 浏览器、MCP(HTTP) | iPad 上真机请求 |
| P3 | 通知、日程、剪贴板、分享、App Intents | iPad 上手动触发 |
| P4 | docx/xlsx/pptx 生成 + 预览 | 生成文件在 iPad 打开 |
| P5 | 子 agent / 工作流 | 多轮任务跑通 |
| P6 | 远端执行器 | 连一台机器跑 `ls` |

P1–P3 能拿到**安卓版 8 成日常能力**（写文件、查资料、开网页、提醒、剪贴板、文档），
P4 补齐文档生成，P5 补齐编排，P6 才需要一台常开的机器。

## 明确放弃的（写在最前面，免得反复讨论）

1. **操控别的 App**：没有等价 API。安卓的自我 adb、无障碍点击在 iOS 上不存在。
2. **枚举已装应用**：`UIApplication` 不给这个能力（部分 URL Scheme 探测可以，但那是白名单玩法）。
3. **截别的 App 的屏**：`ReplayKit` 需要用户主动开始录制，不属于 agent 能力。
4. **在手机上跑 shell / 编译**：没有进程模型。P6 才有解，且是远端。

---

## 进度（2026-11-26 续）

- **P1 部分完成**：`fs_list/read/write/edit/glob/grep` 已写（`FSTools.swift`），自检扩到 26 条
- **界面**：`ChatModel` + `ChatView` + Playgrounds 入口已写，产出 `ChatDemo.swift`（50 KB 单文件）
- 语法门禁：12 个文件全过
- 仍未验证：SwiftUI 那一层从未编译过；自检也还没在 iPad 上跑过
