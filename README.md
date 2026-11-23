# DSHS —— deepseek harness of smartphones

原生 Android 上的 agent。不是 WebView 外壳，不是远程连电脑：**模型在本机执行工具**。

**目标形态**：一个 APK，一个 API key，**无 root 的手机**上干活，**不装任何第二个 App**。

- 设计文档：[docs/架构方案.md](docs/架构方案.md)
- 插件格式：[docs/插件格式.md](docs/插件格式.md)
- **验证报告：[docs/验证报告.md](docs/验证报告.md)** —— 安全内核 18/18、客户端链路 17/17、模拟器端到端 24/24
- 构建：`.\build-apk.ps1` → `DSHS.apk`（40 KB，已签名）
- 安装：允许「未知来源」后直接装，或 `adb install DSHS.apk`

## 现版本 0.2-p1 有什么

| 能力 | 状态 |
|---|---|
| DeepSeek 流式对话（`deepseek-flash` / `deepseek-v4-pro`） | ✅ |
| 工具调用闭环 + 缓存账本（命中率 / token / 美元 / 耗时） | ✅ |
| **策略闸门**：能力按档位兑现，破坏性动作弹确认 | ✅ |
| **审计日志**：append-only + hash 链，记 `auto` / `human` / 被拒 | ✅ |
| `fs_list` `fs_read` `fs_write` | ✅ |
| `app_list` `app_launch`（Intent 拉起，含 `QUERY_ALL_PACKAGES`） | ✅ |
| `clip_read` `clip_write` `notify` | ✅ |
| **`ui_dump` `ui_tap`**：无障碍读界面树 + 按文字/序号/坐标点击 | ✅ |
| `sh`（仅 root 机型，且会话开始时解锁） | ✅ |
| 内置 ADB 自举（配对 → uid 2000，免装 Shizuku） | ❌ P1 待做 |
| **API key 加密**：Android Keystore + AES-256-GCM，磁盘只有 `iv:密文` | ✅ |
| 前台服务 `/` 「所有文件访问」引导 | ❌ P1 待做 |
| MCP 客户端（HTTP/SSE） | ❌ P2 |
| `.dshp` 插件装载 | ❌ P2 |

## 渲染（HTML / 网页 / 图片）

`render {html?, url?}` 工具：模型画表格、图表、示意图时调用，卡片直接在对话流里显示。

- HTML 走 `loadDataWithBaseURL(null, html, ...)`，**不联网**，只渲染模型自己产出的内容
- `url` 模式会真的打开网页（图片链接也走这条）
- 卡片固定 240dp 高，可滚动
- 开关在插件面板（`预览渲染`）；极简模式下不注册
- 清会话/切会话时会 `destroy()` 掉所有 WebView，避免内存堆积

**为什么不是"看到 ![](url) 就自动加载"**：一屏十几个 WebView 会把内存和滚动性能吃光，
而且那等于让模型输出直接触发任意网络请求。现在是"模型显式调用才渲染"，路径可控。
## 定时自动化

抽屉 → 定时任务：起个名字、写清要它做什么、给个间隔（分钟）。到点在后台跑一轮，
结果写进一个新会话并发通知。

- 调度用 `AlarmManager.setAndAllowWhileIdle`（**非精确**闹钟）：不需要 `SCHEDULE_EXACT_ALARM`
  权限，代价是系统可能推迟几分钟。要精确到秒得申请精确闹钟权限。
- 到点 → `ScheduleReceiver` → `ScheduleRunService`（前台服务，进程不会被掐）→ 跑完重新挂下一轮
- 开机后首次启动 App 会重新 `arm()`，因为进程被杀闹钟可能丢

### 无人值守的安全约束（这条最重要）

计划任务里**所有需要人工确认的工具一律被拒绝**——`Headless.confirm()` 直接返回 `false`。
理由：没人看着的时候，宁可不做，也不要假装有人点过「执行」。所以定时任务适合"读+总结+通知"
这类工作，不适合"改文件/点界面"这类要确认的动作。想让它能写文件，得显式放宽策略（还没做，
也不建议默认开）。

实测：22:40:32 建了个 1 分钟任务 `ping`，22:42 自己触发，会话里落下
`system + "reply-with-exactly-OK" + "OK"` 三条记录，全程没有任何弹窗。
## MCP 客户端

DeepSeek 服务端**不支持** MCP（`mcp_servers` 会被忽略），所以客户端必须自己做：
把 MCP 的 tools 映射成 function tools。抽屉 → MCP 添加 `名字 + URL`，可开关、可删。

- 走 JSON-RPC over HTTP：`initialize` → `tools/list` → `tools/call`
- 兼容 SSE 返回（`Accept: text/event-stream`，按 `data:` 行取 JSON）
- `Mcp-Session-Id` 会被记住并在后续请求带上
- 工具名洗成 `mcp__<server>__<tool>`（只留 `a-zA-Z0-9_-`，避开和内置工具撞名）
- 调用走 `mcp.call` 能力档位 → 进审计；极简模式不给

实测（`tools/mock_mcp.py`）：添加后工具数 23 → 25，模型调用 `mcp__mock__add(2,3)` 拿到 `5`。

### 又一个"主线程联网"的坑

MCP 工具发现是在 `Agent` 构造里做的，而构造发生在 UI 线程 → Android 抛
`NetworkOnMainThreadException`，被 catch 吞掉，表现为"server 明明连着但映射 0 个工具"。

修法：**Agent 构造整体挪到后台线程**（`onSend` / `initSession` / `loadSession` 三处），
联网必须在工作线程上。这个 bug 不会崩，只会静默变成"工具没出现"，很难查。
## 与电脑版的对照

| 电脑版能力 | DSHS | 说明 |
|---|---|---|
| 多会话 / 历史对话 | ✅ | `会话` → 列表；标题取首条用户消息，带 token 与花费；点一下就切过去 |
| 工作区 | ✅ | `工作区` → 列文件、看内容（等宽显示） |
| 模式（agent preset） | ✅ | 标准 / 创造 / 只读；**换模式会开新会话**（改 system prompt 会让缓存前缀作废） |
| 读 / 写 / 改代码 | ✅ | `fs_read` `fs_write` `fs_edit`（改代码优先 fs_edit，不整文件重写） |
| 联网下载 | ✅ | `net_fetch`，首次域名确认，20KB 截断 |
| 操作界面 | ✅ | `ui_dump` / `ui_tap`（无障碍档） |
| 搜索（glob / grep） | ✅ | `fs_glob` 支持 `*` `?` `**`；`fs_grep` 正则搜内容，返回 文件:行号: 内容 |
| 插件（`.dshp`） | ✅ | T0 声明式装载器；三种 impl：`http` / `app` / `file`；不跑任意代码 |
| 前台服务（锁屏继续跑） | ✅ | `specialUse` + 常驻通知，运行中显示、结束自动停 |
| 渲染（HTML / 图片预览） | ✅ | `render` 工具 → 内嵌 WebView 卡片，清会话时销毁 |
| 子 agent / workflow | ❌ | 手机上先不铺开 |
| MCP | ✅ | HTTP/JSON-RPC 客户端，抽屉里增删启停 |
| 终端 bash | ❌ | 无 root 手机上按设计不提供 |

### 查找：glob / grep

- `fs_glob {pattern, path?}`：`*` / `?` / `**`，返回相对路径 + 字节数
- `fs_grep {pattern, path?, include?}`：正则搜文件内容，返回 `文件:行号: 内容`，上限 100 处，
  超过 512KB 的文件跳过（别把上下文撑爆）

踩过的坑：`**/*.txt` 一开始匹配不到根目录下的文件——`**` 后面跟 `/` 时必须能匹配**零层目录**，
正则得写成 `(?:.*/)?` 而不是 `.*/`。这个 bug 是模型自己在用的时候报出来的。
## 四个内置能力（对应电脑版的四个内置插件）

| 电脑版 | 手机版 | 实现 | 说明 |
|---|---|---|---|
| 子智能体 | `subagent` | `SubAgent.java` | 独立上下文跑一件独立的事，只把结论带回父会话。嵌套调用用父会话的闸门，深度受限（剔除 subagent/loop 防自我复制） |
| 网页搜索 | `web_search` | Bing RSS | 不抠 HTML——用 `cn.bing.com/search?format=rss&q=`，结构稳定、体积小；cn.bing 失败自动退 www.bing |
| agent 循环 | `loop` | `subagent` 复用 | 同一件事跑最多 5 轮，结果里出现「完成」就停 |
| 终端 | `sh` | `/system/bin/sh` | **不再要求 root**：app 身份执行（有 root 则用 su），每次调用都要确认 |

终端这条是设计修正：最早我把 `sh` 划成 ROOT 档，等于无 root 手机没有终端。
实际上以 app 自己的 uid 跑 `/system/bin/sh` 本来就是允许的，`ls`/`cat`/`grep`/`getprop` 全都能用。

### 一个必须记住的坑：悬空 tool_calls 会毒死会话

任何一次中断（用户点停、进程被杀、崩溃）都会在历史里留下
「assistant 有 tool_calls 但缺 tool 结果」的记录，下一次请求直接：

```
HTTP 400: An assistant message with 'tool_calls' must be followed by
tool messages responding to each 'tool_call_id'
```

而且是**永久性**的——之后每一轮都 400。所以载入历史时和每轮结束时都跑一次
`repairHistory()`：给缺失的 tool_call 补一条「（上一轮被中断，这个工具没有返回结果）」。
宁可让模型知道它没拿到结果，也不要把会话弄坏。
### 插件可开关

内置能力和 `.dshp` 都能单独开关（抽屉 → 插件，点一下切换 ✓/○，`.dshp` 长按卸载）。
改完**要开新会话才生效**——工具清单是会话内冻结的。

实测：关掉「终端」工具数 21 → 20，打开回到 21。

| 内置 | id | 说明 |
|---|---|---|
| 子智能体 | `builtin.subagent` | 独立上下文跑一件事 |
| 网页搜索 | `builtin.web_search` | Bing RSS |
| agent 循环 | `builtin.loop` | 反复做直到「完成」 |
| 终端 | `builtin.terminal` | `/system/bin/sh` |
| 智能体团队 | `builtin.agent_team` | 后台并行 teammate |
| 开发者工具 | `builtin.devtools` | 诊断 / 导出会话 |

### 智能体团队

`team_spawn` / `team_list` / `team_collect`：teammate 在后台线程跑（不阻塞主对话），
共享同一个工作区，结果挂在那儿等你来收。实测派 `scout` 去写 `scout.txt`，
`team_list` 当场显示"跑了 0s"，随后文件真的出现在工作区。

**局限**：团队注册表在内存里，进程被杀就清空（文件不会丢，它们在工作区）。
电脑版是常驻 teammate，手机上没有那个余量。

### 开发者工具

- `dev_info`：版本、机型、档位、工具数、六个内置开关状态、`.dshp` 数量、会话用量、审计条数、工作区项数
- `dev_export`：把当前会话的原始 jsonl 导出到工作区，方便排查
## 模式（对齐电脑版的四个 preset）

电脑版 `dsh-web-app/presets/` 里有四个 preset，手机版按同样的 id 和顺序落地：

| id | 手机显示 | 能力 | 和电脑版的差异 |
|---|---|---|---|
| `standard` | 标准 | 读写/改代码 · 看界面 · 点按 · 联网 | 无（就是主力模式） |
| `ptc` | PTC | 同上 + 「先计划后动手」的行为约束 | 电脑版 ptc 多 todo / subagent / present / 插件管理；手机上这些还没有，所以差异只体现在提示词 |
| `minimal` | 极简 | 只回答：不写、不点按、不联网 | 电脑版 minimal 连 fs 读写都关；手机保留了读取 |
| `cordis` | Cordis | 插件 / 预设开发，允许写文件 | 对应电脑版 cordis（技能目录负责教怎么写） |

换模式**会开新会话**——改 system prompt 会让缓存前缀整段作废，这是硬约束不是偷懒。

## 图标

取自桌面版的 `resources/icon.png`（1024×1024，深色鲸鱼 + 浅色圆角底板），
用 System.Drawing 生成 mdpi→xxxhdpi 五档全幅位图，启动器自己裁形（模拟器上裁成圆形，正常）。

坑：一开始做了 adaptive icon（背景 `#0F1115`），因为源图自带了浅色底板，
套上去会变成"深底上一个白方块"，所以改成纯位图。
## 插件

`.dshp` = 一个目录：`manifest.json` + `tools/*.json`（可选）+ `skills/*.md`（可选）。
**插件不跑任意代码**，工具只有三种声明式动作：

| impl.kind | 做什么 | 需要的能力 |
|---|---|---|
| `http` | GET 一个地址，支持 `{arg}` 占位 | `net.fetch` |
| `app` | Intent 拉起一个包 | `app.launch` |
| `file` | 读工作区里的一个文件 | `fs.read` |

装载时两道校验：`capabilities` 必须是 Policy 认识的档位（否则拒绝装载），
工具名不许撞内置工具。执行时还要过同一套策略闸门——**插件不能给自己加权限，只能申请既有能力**。

抽屉 → 插件 → 「装示例插件」会写入一个真实可用的示例（`fetch_example_page` + `open_wifi_settings`），
它同时也是装载器的活体测试。
## 环境适配（各家 ROM 的坑）

手机上真正卡人的不是代码，是 ROM 差异。`Compat.java` 只做两件事：**探测状态** + **把人送到正确的那个系统页**。
不做绕过 —— 那是系统的安全策略，App 不该也没有权限改。

抽屉里的「环境自检」逐项显示：

| 项 | 探测方式 | 点击后跳到 |
|---|---|---|
| 无障碍服务 | `accessibility_enabled` + 服务是否真的连上 | 无障碍列表 |
| 所有文件访问 | `Environment.isExternalStorageManager()` | 本应用的「所有文件访问」页 |
| 电池优化白名单 | `PowerManager.isIgnoringBatteryOptimizations` | 系统的电池优化请求 |
| 通知权限 | `NotificationManager.areNotificationsEnabled` | 本应用的通知设置 |
| 自启动 / 后台管理 | 探测不到，只能引导 | 华为/小米/OPPO/vivo 各自的 Activity，失败退回应用详情 |

**关于 ADB 安装时的「风险提示」**：华为会连弹两层（`com.android.packageinstaller` 的风险提示 +
`com.huawei.appmarket` 纯净模式的 `InstallDistActivity`）。这是系统 UI，脚本化点击既脆弱也绕不过去。
实测背后两个开关（`settings list secure` 可查）：

```
pure_enhanced_mode_state=1    # 纯净增强模式 —— 华为应用市场那层风险检测
app_check_risk=1              # 应用风险检查
```

想省事：设置里退出纯净增强模式 + 开发者选项关掉「监控 ADB 安装应用」，一次设置之后不再弹。
不想动安全设置：**迭代用模拟器，真机只在里程碑装一次**。

`tools/install.ps1` 是给前一种情况准备的安装驱动：先 `pm install`，不行再退回 `adb install`
并轮询屏幕、按文字点掉多层弹窗（标签覆盖 继续安装 / 仍要安装 / Install anyway 等）。

### 工作区浏览器：可写（v3.4）

长按条目 → `重命名` / `删除` / `复制路径`：

- 重命名：弹输入框（预填原名），同名会拦住，`renameTo` 失败就如实报"权限或跨分区"
- 删除：先算目录里有多少项再确认（空目录 / N 项 / 文件大小），递归删除，不能恢复
- 复制路径：写进剪贴板，方便粘到对话里让模型直接操作那个文件

实测：`todelete.txt` 长按删除 → 磁盘上消失；`test.txt` 长按重命名 → `notes.md`。
### 工作区浏览器（v3.3）

之前是抽屉里一行长路径 + 一个平铺的弹窗列表，既看不出"整个手机是工作区"，目录也进不去。现在：

- 独立页面：`‹ 工作区` + 右上「上一级」
- 顶部一句话说清范围：**「整部手机都能读写。相对路径默认落在下面的默认目录，其它位置用绝对路径。」**
- 快捷入口一排：`默认 DSHS` `全部` `下载` `相册` `文档` `私有`
- 一条等宽字体的当前路径
- 卡片列表：目录（▸）在前、文件（· + 大小）在后，点目录进入、点文件预览（图片直接显示，文本按 ≤40KB 截断）
- 顺手删掉了旧的 `workspaceDialog` / `viewFile` 两个死方法
### 工作区 = 整个手机（v3.2）

不再造沙箱笼子：

- 相对路径落到 **`/sdcard/DSHS`**（唯一新建的目录，默认产出区）
- 绝对路径原样访问，`/sdcard` 下任何位置都能读写
- 没有「所有文件访问」权限时自动退回私有目录，功能不消失、范围变小
- 老版本 `files/workspace` 里的文件首次启动自动搬过去（一次性，有 `.migrated` 标记）

同时把 `fs.write` 和 `net.fetch` 的策略从 CONFIRM 改成 AUTO——「默认完全权限」。
`ui.act`（在别的应用里点击/输入）**仍然是 CONFIRM**：那是唯一能替用户"真的操作"的一类，
误点会买东西、发消息。要一起放开说一声。

模型侧的说法也改了：系统提示现在写「工作区：整个手机（WORKSPACE_ROOT = …）；相对路径落到那里，
绝对路径原样访问；`/sdcard` 下任意读写不需要征求同意」。

**注意**：系统提示是会话内冻结的。改完这版，**旧会话仍按老提示（私有目录）回答**，
要开新会话才会用新说法。
### 团队与任务板（v5.4，34 个工具）

从电脑版搬过来的**有用的那几个**（fish_tts 这类"身外之物"没搬）：

**任务板 4 个**：`task_create` / `task_list` / `task_get` / `task_update`
- 存在 `<工作区>/.dshs/board.json`，纯文本，进程被杀板子还在，子 agent 用 `fs_read` 也看得到
- 每个任务带 `revision`：更新时必须带上一次读到的值，对不上就拒绝——两个 agent 同时改同一条不会互相覆盖
- `action`: claim / complete / reopen / edit / delete；还支持 `blockedBy` 依赖
- 子 agent 的 system prompt 里会自动附上未完成任务，所以它知道队里在干什么

**团队 3 个**：`team_send` / `team_wait` / `team_stop`
- `team_send`：中途给 teammate 加要求。消息存在它自己的收件箱，**在它每一步结束时被读进去**
- `team_wait`：等有人跑完（最多 600s），返回全队状态
- `team_stop`：请求中断，下一步停下

**实测**：派 `scout` 先 `sleep 20` 再写 `scout.txt = hello`；派出后立刻
`team_send` 让它改成 `bye`；`team_wait` 45 秒后收到。
结果 `scout.txt` 内容是 **bye** —— 消息确实进了子 agent 的上下文并改变了它的行为。

**没搬**：`workflow`（脚本级编排，手机上收益不大）、`subagent_fork`（继承父上下文的分叉）。
要的话可以加，但 `subagent` + 任务板已经覆盖了大部分场景。
### 长任务：允许跑，但必须收敛（v5.3）

上一版我把看门狗设成 150 秒，方向是错的——它会把**正在正常工作**的长命令杀掉。
正确的语义是：**过程看得见 + 每条命令有明确的、可调的上限 + 卡住时给出原因**。

| | 之前 | 现在 |
|---|---|---|
| `sh` 超时 | 固定 60s | 参数 `timeout`，默认 120s，**最大 600s**（模型自己按任务要） |
| `python` 超时 | 上限 120s | 上限 **600s** |
| 兜底看门狗 | 150s（会误杀） | **660s**，只作为最后一道保险，永远晚于工具自己的超时 |
| 运行中反馈 | 只有一个转圈 | 卡上**实时秒数**：`正在运行命令 · sh   sleep 90 …   33.8s…` |
| 用户主动停止 | 等工具跑完 | 立刻 `destroy` 子进程 |

**实测**：`sh sleep 90; echo DONE-90`，timeout 给 200 →
中途截图显示 `33.8s…` 在涨，结束时卡片是 `✓ 运行了命令 · sh   90.1s`，
回复「命令跑完了：耗时约 90 秒，输出 DONE-90，退出码 0」。

真正不会收敛的命令（等输入、死循环）会撞上它自己的 timeout，返回 `exit=124`，
模型接着往下走——这才叫"卡住也能出来"，而不是把正常的长任务砍掉。
### 卡死与暂停：根因和验证（v5.2）

**问题**：冒烟测试时界面卡住，按停止没反应。

**根因有三层，逐层修**：

1. `onAbort()` 只有 `if (agent != null) agent.cancel()`
   —— Agent 构造要联网发现 MCP、解压工具链（几秒），这期间按停止**没有任何代码处理**。
   加 `abortRequested` 标志，构造完看到标志就不发请求。
2. `cancel()` 只设 `cancelled = true`，而循环正卡在 `execute()` 里等工具返回
   —— 标志要等工具结束才被检查到。加 `Shell.killCurrent()`：取消时立刻 destroy 子进程。
3. 插件工具、MCP、界面调用**没有统一超时**（sh/python/Shizuku 各自有）
   —— 加 `executeGuarded()`：任何工具 150 秒不返回就放弃这次调用。

**验证**：发 `sh sleep 60`，第 7 秒按停止 → 立刻出现「错误 · 已中止」，
发送键恢复 `↑`，不再需要等工具自己超时。
### Shizuku 打通：一键开无障碍（v5.1）

**结论先说**：Shizuku 能替代**截屏**，但**替代不了读界面**。

| 能力 | shell（Shizuku） | 结论 |
|---|---|---|
| `settings put secure …` | ✅ | 实测 App 自己把无障碍开了 |
| `screencap -p` | ✅ | 截图不再要 MediaProjection 授权 |
| `input tap/text` | ✅ | 坐标点击可用 |
| `uiautomator dump` | ❌ Killed | 同一个二进制用 `adb shell` 跑正常，经 Shizuku 起进程被系统杀 |

所以「去掉无障碍」在这台华为上不成立——读界面节点只能靠无障碍。但**摩擦没了**：

```
装内置 Shizuku → 无线调试配对一次 → 设置里点一下「开启无障碍」→ 完
```

不用连电脑、不用按音量键。这才是真正的目标形态。

**踩过的坑（都记在这）**：

1. 只加 `api` 构件不够：缺 `moe.shizuku.server.*` 的 AIDL 类 → 要 `aidl` 构件
2. 客户端必须自己声明 `ShizukuProvider` + `V3_SUPPORT` meta-data（Gradle 会合并，手搓构建不会）
3. 13.x 去掉了 `Shizuku.newProcess`，但 binder 上的 `IShizukuService.newProcess` 还在
   → 直接 `IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(...)`，
   比 UserService 简单得多，也不用等 onServiceConnected
4. **注册时把 shell 算进档位、执行时 `gate()` 没算** → 工具在表里但一调就被拒（已修）
5. **暂停按钮在 Agent 构造期间是死的** → `onAbort` 只看 `agent != null`，加 `abortRequested` 标志
6. shell 命令读流可能永久阻塞 → 加 20s 超时 + destroy
### Shizuku 可以完全替代无障碍（v4.9）

拿到 shell（Shizuku）之后，无障碍能做的三件事都有 shell 等价物——我这几轮做测试用的
`adb shell` 就是同一个身份，所以这是已验证的事实，不是推测：

| 无障碍/MediaProjection | shell 等价命令 |
|---|---|
| `ui_dump`（读界面树） | `uiautomator dump <file>` + 解析 XML |
| `ui_tap`（点按/输入） | `input tap x y`（text/index 先 dump 再算坐标） |
| 截屏（MediaProjection，每次要授权） | `screencap -p <file>`（**不需要任何弹窗**） |

实现上：`Policy.tierReady(A11Y)` 改成 `a11yOk || adbOk`——shell 能兑现无障碍档位；
`uiDump` / `uiTap` / `screenshot` 三个工具都加了 shell 后备，有哪条走哪条，都没有才报错。

所以对你这台机器（音量键坏了、截屏授权烦人）的目标形态是：
**不装无障碍、不点截屏授权，只靠 Shizuku 的 shell。**
代价是 Shizuku 自己要先启动一次（无线调试配对或 adb），之后一直可用。

**还没验证**：手机 USB 掉了，这一版（4.9）没装上。
### 内置 Shizuku：免 root 拿 shell（v4.8）

目的：让"开无障碍"变成点一下，而不是每次都连电脑。

- **安装包内置在 assets 里**（官方 Shizuku v13.6.0，2.45 MB），设置 → 环境 →「Shizuku：未安装」→ 安装
- 集成的是 **Shizuku 13.x 的 UserService 模型**：不是 `newProcess`（13 起没有公开的这个 API），
  而是把我们自己的 `ShellUserService` 交给 Shizuku 以 **shell 身份**启动，
  再用 Messenger 收发命令（省掉一个 .aidl 和配套构建步骤）
- 拿到 shell 后，`settings put secure enabled_accessibility_services …` 就能自己跑了

四步走（设置 → 环境 → Shizuku 那一行会显示当前卡在哪一步）：

| 状态 | 点一下做什么 |
|---|---|
| 未安装 | 拉起内置 APK 的安装器 |
| 已安装，服务没运行 | 打开 Shizuku，按它的指引启动（无线调试配对，或 adb 一次） |
| 已安装，未授权 | 调 `requestPermission`，在 Shizuku 弹窗里选允许 |
| 可用 | 直接以 shell 身份写系统设置，开无障碍 |

**依赖说明**：`libs/shizuku-api.jar`（25 KB，来自 `dev.rikka.shizuku:api:13.1.5` 的 AAR），
构建脚本已把它加进 javac classpath 与 d8 输入；manifest 加了
`moe.shizuku.manager.permission.API_V23`。

**做不到的**：Shizuku 自己也要一个启动来源（无线调试配对或 adb）。这一步无法再省——
adb 协议要求用户在设备上确认新密钥，而那个确认框又需要点击权限，是真正的死循环。
### 状态文案对齐电脑版（v4.6）

动作名改成**成对**的进行时/完成时，和电脑版一样是"正在X → X了"：

| 进行时 | 完成后 | 耗时 |
|---|---|---|
| 正在分析… 312 字 · 4.1s | 已完成分析 · 312 字 · 用时 4.1s | ✓ |
| 正在运行命令 · sh | ✓ 运行了命令 · sh | 2.1s |
| 正在读取 · fs_read | ✓ 读取了 · fs_read | 114ms |
| 正在截屏 | ✓ 截取了屏幕 | 300ms |
| 正在点按 · ui_tap | ✓ 点击了 · ui_tap | 28ms |

**思考块碎片化修掉了**：以前 `onReasoning` 里有句 `if (assistantView != null) finishThinking()`，
正文一开始流式，后面每个思考增量都会"关一块开一块"，产生一堆 1~2 字的残块。
现在思考块只在**工具调用**或**本轮结束**时收口，正文流式不再打断它。

**`ui.act` 改成 AUTO**：点击/滑动/输入不再弹确认（实测 `✓ 点击了 · ui_tap (1000,265) 28ms` 直接执行）。
真正还会弹框的只剩不可回滚命令的白名单，以及两个系统级授权（无障碍、截屏）——那是 Android/华为的闸门。

**关于"思考+输出+思考+输出"**：这是 agent 循环的正常形态——每次工具调用后模型要重新
基于结果推理一轮，所以是一段思考、一次动作、再一段思考。电脑版也是这个结构，
差别只在它把每一步都标了状态，而现在手机版也标了。
### 环境收敛成一处（v4.3）

五项都在「设置 → 环境」和首次启动的环境页：无障碍 / 所有文件 / 电池白名单 / 通知 / **截屏授权**。
抽屉里那条单独的「授权截屏」删掉了。环境页的按钮从"还差 N 项"变成**可点的引导**：
点一下跳去开当前缺的那一项，回来 `onResume` 刷新，再点就是下一项。

**哪些能自动化，哪些不能（系统硬规矩）：**

| 项 | 能自动吗 |
|---|---|
| 所有文件访问 / 电池白名单 / 通知 | 只能打开系统页面，用户点一下 |
| **无障碍** | **不能**。Android 只允许用户自己在系统设置里启用；华为还加了「按住音量上+下键 2 秒」的防诈骗确认。App 没有 WRITE_SECURE_SETTINGS，没 root 绕不过 |
| **截屏授权** | **不能省**。MediaProjection 每次进程启动都要用户同意一次（Android 10+ 的规定，没有持久化授权） |

唯一能真正"一口气"的办法是 adb（电脑端一条命令写 secure settings），但那就回到连电脑了。
### 截屏看屏幕（v4.2）

`screenshot` 工具：MediaProjection 抓一帧 → 存成 PNG → **作为 image_url 内容块**塞进下一轮，
模型真的"看见"屏幕。实测能读出状态栏时间、标题栏、正文、甚至自己上一条回复里的工具名列表。

- 授权：抽屉 →「授权截屏」，系统弹一次同意框
- **授权是进程级的**：App 被杀/重启后要重新点一次（Android 的规矩，救不回来）
- 工具结果的通道是纯文本，所以截完图会额外注入一条带图的用户消息——这是模型能"看见"的唯一方式

### 两个必须记住的坑

1. **`screen.capture` 在 Policy 里被注册了两次**（APP/AUTO 和 A11Y/CONFIRM），后者覆盖前者；
   无障碍没连上 → DENY → 工具静默消失。`dev_info` 现在会把**工具名**打出来，这类问题一眼可见。
2. **`pb.environment()` 改的是 App 进程自己的 environ**。以前 Python 往里写 `LD_LIBRARY_PATH`，
   结果整个进程里所有子进程（包括系统 `screencap`）都带着我们的库路径 → `CANNOT LINK EXECUTABLE`。
   现在改成 `sh -c "LD_LIBRARY_PATH=... exec ..."` 内联，只影响那一条命令。

**另**：这台华为上 `/system/bin/screencap` 本身就跑不起来（`libsqlite.so` 要 `OpenSSL_add_all_algorithms`，
linker 里没有），跟我们的库无关；所以截屏只能走 MediaProjection，提示词里已写明别再试。
### 真视觉（v4.1）

**我此前说错了一句**：说过"DeepSeek 这条线没有多模态"。实测**是有的**——

同一张图发三个纯色方块，模型分别答对「蓝色 / 红色 / 绿色」（`finish=stop`）；
发一张 App 截图，它读出了状态栏时间、标题栏被截断的文案、正文里的数学内容、
甚至指出 LaTeX 没被渲染、底部有曲别针和齿轮图标。

实现：附件里的图片走 `image_url` 内容块（`data:image/jpeg;base64,…`），
发之前用 `BitmapFactory` 降采样到 ≤1024px + JPEG 80，单轮最多 4 张。

两种触发方式：

1. 点 `📎` 选图（复制进 `<工作区>/uploads/`，并向输入框插入 `[图片] <路径>`）
2. **直接在输入里写** `[图片] /path/to/x.png` —— 引用行自足，不依赖选择器
   （这一点是被逼出来的：自动化测试时选择器反复点空，索性让它自己解析文本）

历史里的图片会随每轮重发（ctx 会明显变大），这是"能追问同一张图"的代价。

**还没做**：截取**当前屏幕**交给模型。那需要 MediaProjection + 用户授权弹窗，
是独立一块工程；现在能看图，但看的是你选/写的文件。
### 底栏清理（v4.0）

去掉 `app` / `无障碍` / `sh` 三个状态胶囊——它们既占位置又几乎没人点。底栏现在只有：

```
[ctx 28.8K/1M]                    [📎] [⚙]
```

功能没丢，换了入口：

| 原来 | 现在 |
|---|---|
| `app` → 审计日志 | 抽屉底部「审计」 |
| `无障碍` → 系统无障碍页 | 设置 → 环境 → 无障碍服务 |
| `sh` → 开关终端工具 | 设置 → 插件 · 内置能力 → 终端 |

顺手删了 `Agent` 构造里那个从没用上的 `shUnlocked` 参数（终端现在只由 `builtin.terminal` 控制），
并把 sh 工具的描述里过期的「每次调用都要人工确认」改成实际行为。

**坑**：删 chips 时我用的是"从 `chipTier` 到 `View s2` 整段删除"，结果把刚加在 `s2` 前面的
`ctx` 胶囊一起切掉了。教训：按区间删代码要先把区间里的**新增**项找出来。
### 附件 + 上下文用量（v3.9）

底栏现在是：`[app] [无障碍] [sh] [ctx 18.2K/1M] … [📎] [⚙]`

**附件**：点 `📎` → 系统文件选择器 → 选中的文件**复制**到 `<工作区>/uploads/`，
并在输入框插入一行 `[附件] /storage/emulated/0/DSHS/uploads/pk.png  (347 KB)`。
实测选 `pk.png` → `uploads/` 里出现 356108 字节，输入框插入对应的绝对路径。

说明：这是"把文件交到模型手上"，不是"把图片当视觉输入"。DeepSeek 这条线没有多模态，
所以图片给的是路径（模型可以用 python/其它工具处理它）；文本文件模型可以直接 `fs_read`。

**上下文用量**：`ctx 18.2K/1M` —— 取最近一次请求实际吃掉的输入 token，
除以模型上限（flash 是 1M 精确值；其它模型标 `≈` 保守按 128K）。超过 80% 变红。

底栏一度挤到把 `⚙` 切掉，所以把「⚙ 设置」缩成 `⚙`、「＋ 附件」缩成 `📎`、
`ctx 0 / 1M` 缩成 `ctx 0/1M`。
### 过程显示对齐桌面版：带时间（v3.8）

**思考实时计时**：`思考中 · 2798 字 · 5.3s`，每 200ms 刷一次（和桌面版一样是跳着涨的）。
结束后变成 `思考了 2798 字 · 用时 5.3s · 展开 ▾`。

**每一步工具带耗时**：`▸ 运行命令 · sh  $ sleep 2; echo done   2.1s`、`▸ 跑 Python · python  126ms`。
耗时是在 `execute()` 外面量的（`onToolDone(name, ms)` 回调），不是各工具自己报的，
所以每个工具都有一致的数字。

一行 `思考中` 而不是一整段原文，加上每步的时间和动作名——这就是桌面版轨迹区在做的事。

**坑**：`思考了 N 字` 里的字数取的是 `thinkingChars`（按 delta 累加），不是最终文本长度；
两者在极少数情况下会差几个字（模型重发同一段），不影响"它在想"的观感。
### 过程显示对齐桌面版（v3.7）

之前：思考是一整段原文铺开（"一大坨文字"），工具只有 `▸ fs_glob`。

现在：

```
思考了 164 字 · 展开 ▾
│ ▸ 运行命令 · sh        $ date +%H:%M:%S
│ ▸ 跑 Python · python   print(2**10)
用 sh 执行 date 得到 01:02:53，用 python 算出 2**10=1024。
```

- **思考默认收起**，只留一行 `思考了 N 字 · 展开 ▾`；流式过程中显示 `思考中 · N 字` 实时涨字数。
  要原文点一下展开，不再边想边糊一屏。
- **工具卡有人话动作名**：`运行命令` `读文件` `写文件` `搜索网页` `抓网页` `看界面` `点按`
  `子智能体` `循环` `团队` `读技能` `渲染` `MCP` `应用` `剪贴板` `通知` `诊断` `跑 Python`。
  后面跟工具名和原始参数，一眼知道"它干了什么"，而不是先解码 `fs_glob`。
### 插件开关与创造模式的实测（v3.6）

**开关有效**：关掉「网页搜索」→ 新会话工具数 26 → 25；开回来 → 26。

**创造模式跑通**：让它写 `demo.greet`（工具 `greet`，读 `greetings/{name}.txt`）→
目录落盘 → 新会话加载 → `greet(name=hello)` 返回 `Hello, hello! 这是 demo.greet 插件的示例数据文件。`
→ 设置里长按卸载 → 新会话 25 工具 / 0 插件。

过程中暴露了两个真 bug：

1. **提示词缺 schema，模型只能瞎猜**。初版写的是 `"tools": ["tools/greet.json"]`（路径列表），
   但装载器要的是**内联对象数组** `[{"name","schema","impl"}]`，`optJSONObject` 拿到 null →
   `valid()` 失败 → 插件被静默跳过（`dev_info` 显示 0 个）。修法：把精确格式写进创造模式的
   `promptExtra`，连"写完必须 fs_read 自查"都写进去。
2. **`impl.kind=file` 不做 `{arg}` 替换**。`http` 走了 `subst()`，`file` 直接用了模板原文，
   报"工作区里没有 greetings/{name}.txt"。修法：`file` 分支同样调 `subst()`。

另外补上了 **`.dshp` 长按卸载**（挪进设置页时漏了），并把 `switchRow` 改成返回行视图以便挂长按。
### 29 个技能全部回来 + gh（v3.5）

上一轮删掉的 10 个（Git/PR 那些）现在都装回来了，前提条件也补齐了：

- **`gh` 2.105.0** 内置（Termux 构建，静态二进制 40 MB，走 PATH）
- **GitHub token** 在设置 → 关于里填，注入成 `GH_TOKEN` / `GITHUB_TOKEN`
- 系统提示里明确写：没配 token 时 `gh` 会报未登录，**先问用户要，不要瞎试**

技能数回到 **29**（`assets/skills.json` 67.9 KB），仍然是一个开关。

APK：59 MB → **74.4 MB**（gh 占 ~15 MB 压缩后）。

**注意**：`ds-jury` 要多个模型互评，我们只有 DeepSeek，它会降级成"同一个模型评两遍"；
`ds-help` 在 29 个技能时重新有意义（它就是个目录）。
### 补齐命令行工具链（v3.1）

内置 **git 2.56.1 / curl 8.23.0 / jq 1.8.2 / node 26.13.1**（Termux 的 aarch64 构建 + 全部依赖库：
openssl、libcurl、nghttp2/3、ngtcp2、libssh2、pcre2、libicu、libc++、ncurses…）。

三个必须做对的点，每个都踩过一次：

1. **targetSdk 降到 28**。`targetSdk ≥ 29` 的 W^X 禁止从应用 dataDir `execve`，
   工具会在 `files/tools/bin/` 里躺得好好的、一执行就 `Permission denied`。
   Termux 一直停在 28 就是为了这个。
2. **解压后要补可执行位**。Java 写出的文件默认 `0600`，没有 `+x` 依然 `can't execute`。
3. **软链目标要按"链接所在目录"解析**。deb 里 `lib/libz.so.1 -> libz.so.1.4` 是相对链接目录的，
   按根目录解析会指到 `tools/libz.so.1.4`（少一层 `lib/`），表现为 `CANNOT LINK EXECUTABLE`。

打包方式：`assets/tools.zip`（51 MB，解压 125 MB）。git 的 `libexec/git-core` 在 deb 里是
200 多个硬链接，打包时去重成"存一份 + 一张 link 表"，解压后用 `sh` 批量 `ln -sf`，
省了 500 MB。`GIT_EXEC_PATH` / `GIT_TEMPLATE_DIR` / `SSL_CERT_FILE` / `HOME` 都在
`Tools.apply()` 里注入，`sh` 和 `python` 两条路径都走它。

APK：7.5 MB → **59 MB**（node 二进制 48 MB + libicu 32 MB 是大头）。

实测：`git --version` / `curl https://example.com`（200）/ `jq --version` / `node -v` /
`echo 42 | jq .` 全部通过，且无需任何手工修复。
## 编程环境（v3.0）

**终端不再弹确认**：`shell` 策略从 CONFIRM 改成 AUTO。只有这一类仍然问一次——
`rm -rf /`、`mkfs`、`dd of=/dev/…`、`pm uninstall/clear`、`reboot`、`factory_reset`、
`settings put secure`。其余（ls/grep/cat/echo/python…）直接执行。

**内置 Python 3.14.10**（Termux 的 aarch64 构建）：解释器 + libpython + 完整标准库 + sqlite3 + zlib。

怎么放的（这个平台限制绕不过去）：

- 解释器和 libpython 放在 APK 的 `lib/arm64-v8a/`：`libpyrun.so`（真身是 python3.14）、
  `libpython3.14.so`、`libandroid-support.so`。安装时解到 `nativeLibraryDir`，
  那是应用私有目录里**唯一允许 execve** 的地方——dataDir 在 targetSdk≥29 上被 W^X 拦住。
- 标准库 15.8 MB 压成 `assets/py_stdlib.zip`（4.8 MB），首次调用解到 `files/py/lib/python3.14`。
- 依赖库 `libz.so.1` / `libsqlite3.so` 另打 `assets/py_libs.zip`，解到 `files/py/lib`，
  靠 `LD_LIBRARY_PATH` 找到——dlopen 读 dataDir 是允许的，被拦的只有 execve。
- `PYTHONHOME`/`PYTHONPATH` 指到 filesDir，绕开 Termux 写死的 `/data/data/com.termux/...` 前缀。

实测结果：8 个模块全通（json/re/datetime/sqlite3/zipfile/hashlib/decimal/statistics）；
`sqlite3 select 6*7` 返回 42；`zipfile` 往返 `b'hi'`；`urllib` 抓 example.com 拿到 HTTP 200。
**Python 是能联网的**——提示词里一度写"没有网络"，实测后改正。

APK：169 KB → **7.55 MB**（一个解释器 + 标准库 + 两个依赖库）。

**没内置**：node、git、curl、jq、pip（`python -m ensurepip` 可以自己 bootstrap pip，但要联网下载）。
手机上的 grep/sed/awk/find/tar/unzip/base64/sha256sum 是系统自带的，直接用。### 思考块 + 等级名（v2.5）

- **思考块**：不再是一张灰底卡片，改成一行 11sp 小字 + 1dp 细线，正文 12sp 三级色。
  思考中间自动展开（能看见它在想什么），结束后自动收起成「思考了 N 字 · 展开 ▾」。
  这样对话流是三层：正文 → 工具旁注（左侧细竖线）→ 可展开的思考细节。
- **推理等级改用英文**：`Max` / `High` / `Medium` / `Low` / `Off`，面板里不再挂一句解释（顶栏也变成 `· Max`）。

**对话框白底的老问题，根因在主题**：App 用的是系统 Light 主题，`AlertDialog` 跟主题走、不跟
`values-night` 的颜色走。做法是加一个 `DshDialog` 样式（values 用 Light.Dialog，values-night 用
Dialog），把 7 个 `AlertDialog.Builder` 全部指过去，窗口背景统一 `bg_sheet`。
### 实时余额 + 快捷面板（v2.4）

顶栏第二行是两个胶囊：

```
[标准模式 · deepseek-flash · 推理最大]   [余额 ¥61.53]
```

- 余额走 `GET /user/balance`（实测可用，返回 CNY 总额），启动时拉一次、每轮结束再拉一次、点一下强制刷新
- 60 秒内复用缓存，不会每轮都打接口
- 没配 key 时这个胶囊显示「未设置 key」（危险色），点它直接弹设置

快捷面板（点左边胶囊）改成卡片式：模型 2 项 + 推理等级 5 项，各带一句话说明和 ✓。
系统默认对话框是白底，和深色界面割裂——用 `bg_sheet` 把窗口背景换成深色圆角。

**教训**：`AlertDialog` 的窗口背景跟 App 主题走系统默认，不跟 `values-night` 的颜色；要自定义必须显式
`dialog.getWindow().setBackgroundDrawableResource(...)`。
### 推理等级（v2.3）

实测 `reasoning_effort` 是**真参数**（不是被忽略的未知字段）——同一道题，推理长度随等级单调变化：

| 等级 | 推理字符 |
|---|---|
| minimal | 487 |
| 默认（不传） | 623 |
| high | 697 |
| max | 902 |

- 取值：`none` / `low` / `medium` / `high` / `max`，都返回 200
- 默认 **max**，和电脑版 `reasoningEffort: max` 一致
- 改的地方有两处：设置 → 推理等级；或点顶栏第二行「标准模式 · deepseek-flash · 推理最大」弹快捷面板（模型 + 等级一屏选完）
- 改完下一轮生效（client 重建），不用开新会话

### 界面打磨（v2.3）

- **新会话 → 右上角 `＋`**（原在底栏最右、字号最小）
- 第二行：`模式 · 模型 · 推理等级`（可点）+ 右侧 `$成本`；标题单行省略，不再和这些抢位置
- 底栏状态做成圆角胶囊：`[app+a11y]` `[无障碍 ✓]` `[sh]` … `[⚙ 设置]`
- **工具调用卡改成左侧 2dp 竖线 + 无底色**：它是旁注，不该用整块底色和正文抢注意力
### 主界面改版（v2.2）

- **新会话挪到右上角 `＋`**（原来挤在底栏最右侧、字号最小，最难找）
- 标题独占一行，不再被"模式 · 模型"挤
- 第二行：`模式 · 模型`（点一下切换模型） + 右侧状态（就绪 / key 加密 / 会话用量）
- 底栏只剩状态胶囊：`[app]` `[无障碍]` `[sh]` … `[⚙ 设置]`，圆角胶囊背景，间距 7dp
- 顶栏长按仍然是换 API key
### 四个模式的名字（v2.1）

| id | 名字 | 说明 |
|---|---|---|
| `standard` | 标准模式 | 读写文件 · 看界面 · 点按 · 联网 |
| `ptc` | 程序化工具调用模式 | 先计划后动手 · 允许插件工具 |
| `minimal` | 极简模式 | 只回答，不动手 |
| `cordis` | 创造模式 | 插件 / 预设开发 |

改名同时改了 `promptExtra` 里的自称（"你处在程序化工具调用模式"），否则模型会说旧名字，和界面对不上。

**bug**：改完模式回主界面，开新会话仍是旧模式——`mode` 字段只在 `onCreate` 读一次。
修法是在 `onFresh()` 里重读 prefs（模式在会话内冻结，所以只在这里重读，不会串味）。

顶栏那行加了单行 + 省略号 + 最大 190dp：`程序化工具调用模式 · deepseek-flash` 在小屏上不能把标题挤没。
## DSH Ultra Mode（19 个技能 · 一个开关，v1.9）

`dsh-skills-pack` 里的技能整包内置进 APK，**一个开关控制全部**。

- 技能正文随 APK 发（`assets/skills.json`，45.5 KB）
- 就在 **插件 · 内置能力** 列表里，和子智能体/终端并列的一行：
  `DSH Ultra Mode / 19 个技能 · 一个开关`，没有单独分区
- 开：技能目录进系统提示，`skill` 工具注册；关：两者都不出现
- 系统提示只放**目录**（`- ds-tdd — 一句话`），正文按需 `skill` 取 —— 全文塞每轮会把上下文烧光

**删掉的 10 个**（手机上没意义）：`ds-adopt` `ds-pr` `ds-pr-comments` `ds-ship` `ds-ready`
`ds-review-loop` `ds-watch-pr` `ds-revive`（全是 Git/PR 流程，手机没 git）、
`ds-jury`（要多模型，手机只有 DeepSeek）、`ds-help`（只是个菜单，目录已经在提示里了）。

**保留的 19 个**：best-of-n · blast-radius · blueprint · catchup · context-reset · design ·
design-audit · explain · ledger · lessons · mode · plan-loop · principles · sharpen · swarm ·
tdd · unslop · unstick · verify-loop。

实测：让它「调用 skill 读 ds-tdd，贴出前三步」，返回的三步与源文件逐字一致。

**别再犯**：只在一个地方列它。曾经同时放在「内置能力」和独立分区，结果设置页出现两个同名开关。

### 构建坑

`aapt2 link` 默认**不打包 assets**，必须显式 `-A <assets 目录>`。漏了不会报错，
只会让 `Skill.all()` 返回空、整个技能面板静默失效——APK 体积没变就是征兆（145.4 → 173.5 KB 才对）。
### 删除历史会话（v1.7）

抽屉 → 历史会话，**长按任意一行** → 确认框 → 删除。删的是当前会话时会自动开一段新的。
章节标题直接写着「历史会话 · 长按删除」，不用猜。

同时把抽屉会话列表封顶 20 条（每轮都会重建列表，历史多了会卡），超出显示「还有 N 个更早的会话」。

**测试注意**：`uiautomator dump` 抓不到抽屉里那层（它盖在内容上但 dump 只报内容窗口的节点），
所以验证抽屉交互要用截图比对 hash，不能靠 dump 找文字——这次排查绕了弯路就是因为这个。
## 三轮检查记录（v1.6）

### 第一轮：静态

- `javac -Xlint:all`：0 warning / 0 error
- 扫"只定义没引用"的私有成员：11 个死方法（6 个是被设置页取代的弹窗）+ 8 个死字段
- 全删，MainActivity 1647 → 1282 行，APK 149.4 → 145.4 KB
- 删除用的是字符串/注释感知的括号扫描器——朴素的括号计数会被字符串里的 `{` 骗

### 第二轮：真机运行时

| 项 | 结果 |
|---|---|
| 冷启动 | 326ms → **272ms** |
| 崩溃 | 无 FATAL |
| 设置页开关往返 | 关→开，状态正确 |
| 对话 | 正常，命中 91-92% |
| 环境提醒条 | 缺项时出现，补齐后消失 |

### 第三轮：安全

| 项 | 结果 |
|---|---|
| PolicyTest | **18/18**（修了 1 条过期断言，见下） |
| WireTest | **17/17** |
| 源码硬编码 key | 无 |
| 会话文件里的 key | **0 命中 / 7 个文件**（让 App 自己 grep 自己的数据目录） |
| 审计链篡改检测 | 通过（改一行链条就断） |

**修掉的过期断言**：`shell 无 root → DENY` 现在返回 `CONFIRM`。这不是 bug——终端后来改成
app 身份可跑（`ls /sdcard` 实测 exit=0），策略也从"必须 root"改成"每次确认"。测试编码的是旧设计，
所以改测试而不是改行为。

### 顺带的优化

- **审计计数缓存**：`refreshMeta` 每次 UI 刷新都全量读 audit.log，改成内存计数 + append 自增（按目录键控）
- **抽屉会话列表封顶 20 条**：每轮都会重建列表，历史多了明显卡；超出显示"还有 N 个更早的会话"
- **无障碍晚绑问题**：服务比 Agent 晚连上时，首个会话拿不到 ui_*（长期表现是"24 → 22 工具"）。
  现在发送前检查，服务已连上就重建工具清单，不用再开新会话
### 表单页（v1.5）

新建定时任务 / 添加 MCP server / 换 API key 全部改成**页面内联表单**，不再用系统默认对话框：

- 输入框是描边圆角（`bg_field`），和设置卡片同一套视觉
- 提交/取消是行内文字按钮（提交用强调色），没有系统灰按钮
- 展开表单时**保留滚动位置**——`build()` 重建整页会跳回顶部，这是修过的 bug
- 换 key 也挪进设置页了，改完直接生效
### 首次启动的环境闸门（v1.4）

四项能力默认要求开启，不齐就直接挡在环境准备页：

| 项 | 为什么必须 |
|---|---|
| 无障碍服务 | 读界面、按文字点击（无 root 手机操作其他应用的唯一内置路径） |
| 所有文件访问 | 读写共享存储；不开只能碰 App 私有目录 |
| 电池优化白名单 | 锁屏后不被回收，定时任务和长任务靠它 |
| 通知权限 | 定时任务的结果看得见 |

- 可以「稍后再说」，但主界面输入框上方会常驻一条：`环境未完整（缺 N 项：…）· 点这里补全`
- 每项都有「去开启」直达系统页，回来 `onResume` 自动重查
- **检查看的是"设置里有没有授权"，不是"此刻服务连没连上"**——进程重启后服务要过一会儿才连上，
  拿运行时状态当门槛会把人误挡在门外（这个坑踩过）
### 设置页（v1.3 重做）

之前插件开关藏在「抽屉 → 插件 → 弹窗」三层，找不到是必然的。现在：

- 主界面底栏有 **⚙ 设置**，一进入就是整页设置
- 抽屉底部**固定**了 设置 / 工作区 / 审计 三行（不再被会话列表挤到折叠线以下）
- 设置页分区：模式 · 插件（内置）· 插件（.dshp）· MCP · 定时任务 · 环境 · 关于
- 开关用系统原生 `Switch`，一屏能看到全部七个内置能力
- 当前模式有 ✓ 标记，环境项用 ✓/✗ + 一句话说明，点一下跳到系统页
- 关于区显示版本、机型、key 是否加密、审计条数、换 key 入口

没有用 AndroidX，整页是框架 View 手写：分组标题 + 圆角卡片 + 1dp 分隔线（左缩进 16dp）。
## 界面

视觉不是自己设计的，是从**电脑版的 design token 里抄出来的**：
从 `app.asar` 里把 `@deepseek-ai/dsh-client-ui-theme` 的 `design-platform.css` 挖出来，
照它的 alias token 建了 `res/values/colors.xml` 与 `values-night/colors.xml`。

| | 亮色 | 暗色 |
|---|---|---|
| 底 / 文字 | `#FFFFFF` / `#0F1115` | `#151517` / `#F9FAFB` |
| 次级 / 三级文字 | `#61666B` / `#81858C` | `#CFD3D6` / `#ADB2B8` |
| 用户气泡 | `#EDF3FE` | `#2C2C2E` |
| 代码块底 | `#F9FAFB` | `#1B1B1C` |
| 分割线 | 4% 黑 | 4% 白 |
| 正文 / 代码 | 15sp 无衬线 / 12sp 等宽 | 同 |

组件：

- **用户消息** 右对齐气泡，最宽 86%
- **助手消息** 通栏正文，支持围栏代码块、行内 code、`**粗体**`、标题、列表
- **思考过程** 流式时展开，正文到达后折叠成「思考了 N 字 · 点击展开」
- **工具调用** 一行卡片：等宽工具名 + 灰色细节，截断显示
- **账本行** 工具数（**会话冻结时**的档位）+ token + 命中率 + 美元
- **输入区** 圆角描边、圆形发送键（运行中变成红色停止）
- **底部开关** 低对比文字按钮：档位 / 无障碍 / sh / 模型 / 新会话

代码块背景用 `LineBackgroundSpan` 画整行矩形，不是 `BackgroundColorSpan`——后者只给字形上色，
短行会变成斑马纹。这一条踩过一次。

截图：[亮色](build/ui-final.png) · [暗色](build/ui-dark.png)

## 第一次用

1. 装 APK，填 DeepSeek API key（Keystore 加密存储；改 key 长按顶栏那行）
2. 点底部 **无障碍** → 进系统设置 → 找到 DSHS → 打开
   （部分 ROM 要先开「受限设置」；国产 ROM 的省电策略会杀它，需要加白名单）
3. 回到 App，状态条显示 `app+a11y`，说明两档都在

没有打开无障碍时，`ui_dump` / `ui_tap` **不会出现在工具清单里**——这是故意的（失败即关闭），
模型不会去重试一个不存在的工具。

## 怎么验证它真的在跑

1. 问：`列出当前目录` → 看工具卡
2. 问：`现在屏幕上是什么，帮我点一下搜索框` → 会先 `ui_dump` 再 `ui_tap text="搜索"`
3. 看底部第二行：`本轮 in 1234（命中 87%）/ out 210 · 800ms · $0.0003`
   —— **命中率不为 0 说明前缀没被破坏，架构成立**；连问第二句应接近 100%
4. 破坏性动作（`fs_write`、`ui_tap`）会弹确认单，显示确切目标

## 三条必须遵守的硬规则（写进代码了，别改）

1. **system prompt 会话内逐字节冻结** —— 里面没有时间、电量、会话号。加了就等于每轮缓存全 miss。
2. **工具清单在会话开始时冻结** —— 改清单 = 改前缀 = 缓存归零。所以「解锁 sh」只在会话开始生效。
3. **历史只追加** —— 没有压缩、没有摘要、没有就地编辑。

## 目录

```
docs/架构方案.md          设计与取舍（先看这个）
docs/插件格式.md          .dshp 清单规范
docs/验证报告.md          三层验证结果 + 验证中打出来的 4 个真 bug
android/AndroidManifest.xml
android/res/xml/accessibility_service.xml
android/res/xml/network_security_config.xml
android/src/com/dshs/
  MainActivity.java       一屏 UI + 确认单 + 无障碍引导
  Agent.java              循环 · 工具 · 闸门 · 账本
  Policy.java             档位表 · 决策 · hash 链审计
  UiAgentService.java     无障碍：压缩界面树 + 手势点击
  DeepSeekClient.java     SSE 流式 + tool_calls 拼装 + usage
  Shell.java              su/sh 执行，root 探测
tools/env.ps1             统一环境（沙箱下必须：TMP / AVD / SDK 都指进工作区）
tools/test/PolicyTest.java  安全内核测试（纯 JVM，不需要模拟器）
tools/test/WireTest.java    客户端链路测试（纯 JVM + mock）
tools/mock_ds.py          假 DeepSeek 服务（流式 + 工具调用 + 缓存字段 + 400 陷阱）
tools/e2e.ps1             模拟器端到端脚本
build-apk.ps1             手工构建（ASCII-only，PS 5.1/7 都能跑）
```

构建脚本默认 ASCII 文案：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI 读，
中文会破坏解析。改文案请保持 ASCII，或者给文件加 BOM。

## 构建

没有 Gradle、没有 AndroidX，就是 aapt2 → javac → d8 → zipalign → apksigner 五步：

```powershell
. .\tools\env.ps1      # 指向本机的 JDK / Android SDK build-tools
.\build-apk.ps1        # 产出 DSHNative.apk
```

依赖三个外部件（**不在仓库里**，太大）：

| 文件 | 大小 | 用途 |
|---|---|---|
| `android/assets/tools.zip` | 65 MB | git / gh / curl / jq / node / less 的 Termux 二进制 |
| `android/assets/py_stdlib.zip` | 4.8 MB | Python 3.14 标准库（已在仓库） |
| `android/native/arm64-v8a/libpython3.14.so` | 5.7 MB | Python 解释器（已在仓库） |

`tools.zip` 需要自己从 Termux 的 aarch64 包里拼：`git` `gh` `curl` `jq` `nodejs` `less`，
解到 `bin/ lib/ libexec/ share/ etc/` 结构后打包。没有它 App 也能跑，只是终端里没有这些命令。

## 它是什么

一个**跑在手机上的 agent**，不是聊天窗口：

- 能读写任意文件、跑 shell / Python、操作界面（无障碍）、截屏、内置浏览器
- 有子智能体、任务板、并行 team、29 个技能、`.dshp` 插件、MCP
- 一个 API key 就能用，不依赖电脑