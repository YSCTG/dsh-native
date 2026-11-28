# iOS 版能力清单

> 最后更新：随 v0.1 开发期。数字都是从代码里统计出来的，不是估的。
> 验证方式见文末"怎么验的"，每一条都有对应的 CI 步骤。

## 一、工具（53 个固定 + 动态插件）

### 文件（6）
| 工具 | 作用 |
|---|---|
| `fs_list` | 列目录（带大小/时间，可递归） |
| `fs_read` | 读文本（带行号，支持 offset/limit 分页） |
| `fs_write` | 写文件（自动建目录） |
| `fs_edit` | 精确替换（原文必须唯一，否则拒绝） |
| `fs_glob` | 按 `**/*.swift` 这类模式找文件 |
| `fs_grep` | 按正则搜内容，返回 `文件:行号:` |

### 文本与网络（3）
| 工具 | 作用 |
|---|---|
| `text_stats` | 字数、行数、字符统计 |
| `net_fetch` | 抓一个 URL 的文本 |
| `web_search` | Bing RSS 搜索，返回标题 + 链接 |

### 浏览器（5）
`browser_open` `browser_text` `browser_js` `browser_click` `browser_type`
—— 真的开一个 WKWebView 去打开、读文本、执行 JS、点元素、输入。

### 系统（6）
`clip_read` `clip_write` `notify` `schedule_add` `schedule_list` `schedule_remove`
—— 剪贴板、本地通知、定时提醒。

### 文档（2）
| 工具 | 作用 |
|---|---|
| `office_write` | 生成 docx / xlsx（自写 zip 打包） |
| `office_read` | 读 docx / xlsx 的文字（自写 zip 解压 + deflate） |

### 发布（4）
`webapp_new` `webapp_list` `swiftpm_new` `ipa_pack`
—— 造本地 web app、造 Swift 项目骨架、打包成 ipa（打包不等于签名）。

### 平台（5）
`dev_info` `dev_selfcheck` `todo` `skill` `plugin_list`

### 运维（9）
`task_create` `task_list` `task_get` `task_update`（本地任务板）
`goal_set` `goal_get` `goal_clear`（目标状态）
`screenshot`（截**本 App** 的界面）
`dev_export`（把工作区打成 zip）

### 子 agent 与团队（9）
`subagent` `workflow` `loop`
`team_spawn` `team_list` `team_send` `team_wait` `team_collect` `team_stop`

### MCP（2）
`mcp_resources` `mcp_read` —— JSON-RPC over HTTP，兼容 SSE 响应。

### 交互（2）
`ask_user`（**真的等你回答**，界面弹输入框）
`plan_present`（把计划写进 plan.md 并回显）

### 拉起（1）
`app_launch` —— 按 URL Scheme 打开（iOS 不允许按包名启动任意 App）。

### 插件（动态）
把 `workspace/plugins/<名字>.js` 放进去，每个插件都会变成一个模型可调用的工具。
格式见 `ios/DSHEngine/Sources/DSHEngine/PluginHost.swift` 顶部注释。

与内置工具**重名会被跳过**，不让插件偷偷覆盖 `fs_write` 这类关键工具。

## 二、界面（5 个页签）

| 页签 | 能力 |
|---|---|
| 聊天 | 气泡、Markdown（标题/粗体/列表/**代码块**）、工具卡片可展开、图片附加、发送/停止、会话列表（切换/删除/新建）、待办与任务面板、ask_user 弹窗 |
| 应用 | 运行工作区里的 web app（WKWebView + 能力桥） |
| 文件 | 浏览工作区、进目录、预览文本与图片 |
| 沙盒 | 跑 JavaScriptCore 代码 |
| 设置 | API key（**Keychain**，打码 + 测试连接）、模型、base URL、MCP 地址、模式（标准/极简） |

## 三、做不到的（平台限制，不是没做）

| 能力 | 为什么 |
|---|---|
| 跑 shell / python / npm | iOS 不允许 App 创建进程 |
| 点别的 App、看别的 App 屏幕 | 没有 AccessibilityService 等价物 |
| 枚举已装应用、按包名启动 | 沙箱不提供 |
| adb 通道 | 没有 shell |
| 直接安装 IPA | 需要 Apple 签名 |
| 后台长时间运行 | 系统会挂起 App |

## 四、怎么验的

| 层 | 内容 | 在哪 |
|---|---|---|
| 引擎单测 | 15 条 XCTest | `ios-engine` 工作流 |
| 沙盒自检 | **71 条断言**（路径沙箱/文件/会话/文档/平台/运维/子agent/读文档/提问/MCP/团队/插件/密钥） | 模拟器 `-selfcheck` 截图 |
| 真实对话 | 用 Secret 里的 key 发一次请求 | `-live` 截图 |
| 工具调用 | 模型调 `fs_write` 写文件，CI 再 `cat` 容器里的文件 | `-tools` 截图 + 日志 |
| 交互 | 10 条 XCUITest（启动/会话/任务/页签/输入/勾选/文件/卡片/代码块/弹窗） | `-uitest` 步骤 |
| 界面 | 11 张截图 | artifact |

## 五、已知限制

- **从没在真机上跑过**：全部验证在模拟器（GitHub macOS runner）里
- **未签名**：需要自己签（见 `docs/ios-install.md`）
- **Keychain 依赖签名**：未签名的构建会退回 UserDefaults（自检里会打印当前后端）
- **图片只存不读**：DeepSeek 对话接口不收图片，附加的图片只是把路径给模型
