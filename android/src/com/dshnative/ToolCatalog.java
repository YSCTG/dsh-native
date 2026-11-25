package com.dshnative;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置工具目录（纯数据 + 参数 schema 辅助）。
 *
 * 从 Agent 里抽出来的：{@code buildTools()} 原先 433 行，夹在会话循环、HTTP 解析、
 * 思考模式处理之间，加或改一个工具要在一大坨状态代码里翻。
 * 工具定义本身只是"名字 + 描述 + 参数 + 需要的能力"，与对话逻辑无关。
 *
 * 能力门禁仍留在 Agent.add(...)：只有对应能力此刻开启，工具才进清单。
 */
final class ToolCatalog {

    private ToolCatalog() {}

    static void fill(Agent a) throws Exception {

        try {
            a.add("fs.read", tool("fs_list", "列目录：名字、类型、字节数。",
                    schema(obj("path", "string", "绝对路径或相对工作区的路径"), new String[]{"path"})));
            a.add("fs.write", tool("office_restyle",
                    "改现成 Office 文件的样式（只改样式部件，其余字节原样拷贝）：\n"
                            + "docx → styles=[{id:'Heading1', size:32, color:'#1F6FEB', font:'思源宋体'}]\n"
                            + "xlsx → xlsx={size:14, color:'#B4531F', font:'Calibri'}\n"
                            + "pptx → pptx={ink:'#111111', page:'#FFFFFF', accent:'#1F6FEB'}",
                    schema(props(new String[]{"path", "out", "styles", "xlsx", "pptx"},
                                 new String[]{"string", "string", "array", "object", "object"},
                                 new String[]{"源文件", "输出（默认覆盖源文件）", "docx 样式补丁",
                                         "xlsx 字体/字号/字色", "pptx 主题色"}),
                           new String[]{"path"})));
            a.add("fs.read", tool("office_read",
                    "读现成的 Office 文件：docx 段落与表格、xlsx 各工作表的行与公式、pptx 每页文字。"
                            + "改文档之前先用它看清楚里面有什么。",
                    schema(props(new String[]{"path", "max_chars"},
                                 new String[]{"string", "integer"},
                                 new String[]{"文件绝对路径", "最多返回多少字符，默认 6000"}),
                           new String[]{"path"})));

            a.add("fs.read", tool("fs_read", "按行读文本（带行号）。大文件请分页。",
                    schema(props(new String[]{"path", "offset", "limit"},
                                 new String[]{"string", "integer", "integer"},
                                 new String[]{"路径", "起始行号，1 起", "读多少行，默认 " + Agent.READ_LIMIT}),
                           new String[]{"path"})));

            a.add("fs.read", tool("fs_glob",
                    "按通配符找文件：支持 * ? 和 **。返回相对路径 + 字节数。比一层层 fs_list 快得多。",
                    schema(props(new String[]{"pattern", "path"},
                                 new String[]{"string", "string"},
                                 new String[]{"通配符，如 **/*.md 或 *.txt", "起始目录，默认工作区"}),
                           new String[]{"pattern"})));

            a.add("fs.read", tool("fs_grep",
                    "在文件内容里按正则搜索，返回 文件:行号: 内容。改代码前先拿它定位。",
                    schema(props(new String[]{"pattern", "path", "include"},
                                 new String[]{"string", "string", "string"},
                                 new String[]{"正则", "起始目录，默认工作区", "只搜匹配的文件名通配符，如 *.tsx"}),
                           new String[]{"pattern"})));

            a.add("fs.write", tool("fs_write", "写文件（覆盖）。调用前会弹确认。",
                    schema(props(new String[]{"path", "content"},
                                 new String[]{"string", "string"},
                                 new String[]{"路径", "完整内容"}),
                           new String[]{"path", "content"})));

                a.add("fs.write", tool("fs_edit", "按原文精确替换（改代码优先用它，别整文件重写）。",
                        schema(props(new String[]{"path", "old", "new"},
                                     new String[]{"string", "string", "string"},
                                     new String[]{"路径", "要被替换的原文", "替换成什么"}),
                               new String[]{"path", "old", "new"})));

            if (Plugin.isOn(a.ctx, "builtin.office")) a.add("fs.write", tool("office_preview",
                    "把刚生成的 pdf / html 渲染成图，**直接把图给你看**，用来检查排版效果。\n"
                            + "不要为了看效果去起本地服务器（http.server 这类命令永不返回，会把任务挂死）。\n"
                            + "pdf 渲染指定页（默认第 1 页）；html 整页截图。渲染完这张图会在下一轮作为图片给你。\n"
                            + "同类文件（docx/xlsx/pptx）无法渲染，直接用 browser_open 或让用户打开即可。",
                    schema(props(new String[]{"path", "page", "pages"},
                                 new String[]{"string", "integer", "string"},
                                 new String[]{"pdf / html / pptx 的路径", "PDF 页码，1 起，默认 1",
                                              "多页对照：\"1-3\" / \"1,3,5\" / \"all\"，给这个就忽略 page"}),
                           new String[]{"path"})));
            if (Plugin.isOn(a.ctx, "builtin.office")) a.add("fs.write", tool("office_write",
                    "生成文档与媒体文件：docx / xlsx / pptx / pdf / wav / m4a / mp4。纯本地生成，不需要联网，不用 ffmpeg。\n"
                            + "docx: spec={title, blocks:[{type:'h1|h2|p|bullet',text} | {type:'table',rows:[[..],[..]]}]}\n"
                            + "xlsx: spec={sheets:[{name, header:true, widths:[12,20], rows:[[\"表头\",\"值\"],[1,2]]}]}"
                            + "  （数字写成数字、公式以 = 开头）\n"
                            + "pptx: spec={style:'report|editorial|swiss|magazine|minimal', slides:[{title, bullets:[\"第一条\",\"第二条\"],\n"
                            + "        image:'out/chart.png'（可选，放右侧）}]}  —— 顶部色带 + 主题色标题 + 页码\n"
                            + "pdf:  排好版的 A4。**要好看就用 blocks，不要只用 lines**：\n"
                            + "      spec={style:'report|editorial|swiss|magazine|minimal', cover:{title, subtitle, meta},\n"
                            + "            theme:{accent, ink, muted}（可选，覆盖预设的个别颜色）,\n"
                            + "            margin:56, pages:[{title:'章节名', blocks:[\n"
                            + "              {type:'h1',text:'小标题'}, {type:'h2',text:'更小的标题'}, {type:'p',text:'正文'},\n"
                            + "              {type:'bullet',text:'要点'}, {type:'quote',text:'引用'},\n"
                            + "              {type:'kv',rows:[['指标','数值']]}, {type:'table',rows:[['表头','值'],['a','b']]},\n"
                            + "              {type:'divider'}, {type:'spacer',h:16}\n"
                            + "            ]}]}\n"
                            + "      自带封面页、标题下色条、表格斑马底、页脚页码；主题色贯穿全篇。\n"
                            + "wav/m4a: spec={notes:[{freq:440,dur:0.4},{freq:523,dur:0.4}], waveform:'sine|square|saw|noise', volume:0.5}\n"
                            + "         或 spec={tone:440, seconds:1} —— m4a 是 AAC 编码（平台没有 MP3 编码器）\n"
                            + "mp4:  spec={size:[1280,720], fps:30, slides:[{title, bullets:[\"要点\"], seconds:2.5, bg:'#101418', fg:'#FFFFFF', accent:'#7FB2FF'}]}"
                            + "  —— H.264 硬件编码，用来做幻灯片视频\n"
                            + "路径给绝对路径或相对工作区的路径；父目录会自动建。",
                    schema(props(new String[]{"format", "path", "spec"},
                                 new String[]{"string", "string", "object"},
                                 new String[]{"docx / xlsx / pptx / pdf / wav / m4a / mp4", "输出路径，如 out/report.docx", "结构见描述"}),
                           new String[]{"format", "path"})));
            a.add("app.list", tool("app_list", "列出已安装应用（名字 + 包名）。",
                    schema(obj("query", "string", "按名字或包名过滤，可省略"), new String[]{})));
            a.add("app.launch", tool("app_launch", "启动一个应用。",
                    schema(obj("package", "string", "包名，如 com.tencent.mm"), new String[]{"package"})));

            a.add("clip.read", tool("clip_read", "读剪贴板文字。",
                    schema(new JSONObject(), new String[]{})));
            a.add("clip.write", tool("clip_write", "写剪贴板。",
                    schema(obj("text", "string", "要放入剪贴板的文字"), new String[]{"text"})));
            a.add("notify", tool("notify", "发一条系统通知。",
                    schema(props(new String[]{"title", "body"},
                                 new String[]{"string", "string"},
                                 new String[]{"标题", "正文"}),
                           new String[]{"title", "body"})));

                a.add("net.fetch", tool("net_fetch",
                        "抓取网址的文本内容（仅 http/https）。域名不在允许列表时会确认。",
                        schema(obj("url", "string", "完整 URL"), new String[]{"url"})));

            a.add("fs.write", tool("app_new",
                    "生成一个能在手机上编成 APK 的最小 Android 项目（Java）："
                            + "工作区 apps/<name>/ 下有 AndroidManifest.xml 和 src 源码。",
                    schema(props(new String[]{"name", "workspace"},
                                 new String[]{"string", "string"},
                                 new String[]{"项目名", "工作区目录（可空）"}),
                           new String[]{"name"})));

            a.add("shell", tool("app_build",
                    "在手机上把 app_new 生成的项目编成可安装的 APK（ecj+d8+aapt2+zipalign+apksigner "
                            + "全部在设备上跑）。首次会下载约 16 MB 构建工具链。",
                    schema(props(new String[]{"name", "workspace"},
                                 new String[]{"string", "string"},
                                 new String[]{"项目名", "工作区目录（可空）"}),
                           new String[]{"name"})));

            a.add("fs.write", tool("game_new",
                    "生成一个可玩的手机端游戏骨架（纯 canvas，不依赖 npm）："
                            + "工作区 games/<name>/ 下有 index.html、game.js、README。生成即可 play。",
                    schema(props(new String[]{"name", "workspace"},
                                 new String[]{"string", "string"},
                                 new String[]{"项目名（目录名）", "工作区目录（可空）"}),
                           new String[]{"name"})));

            a.add("fs.write", tool("game_build",
                    "把游戏打成单文件 HTML：把同目录的 .js 内联进 index.html，"
                            + "产出 <name>.single.html，可以直接发给别人玩。",
                    schema(props(new String[]{"name", "workspace"},
                                 new String[]{"string", "string"},
                                 new String[]{"项目名", "工作区目录（可空）"}),
                           new String[]{"name"})));

            a.add("net.fetch", tool("npm_install",
                    "从 npm registry 装一个纯 JS 包到工作区 node_modules/（不需要 npm 命令）。"
                            + "用法：package 写包名（PixiJS 就写 pixi.js），version 可留空取 latest。"
                            + "只解这个包本身，不装它的依赖树。",
                    schema(props(new String[]{"package", "version", "workspace"},
                                 new String[]{"string", "string", "string"},
                                 new String[]{"包名，如 pixi.js", "版本号（可空）", "工作区目录（可空）"}),
                           new String[]{"package"})));

            a.add("ui.read", tool("ui_dump",
                    "读当前界面：每行 #序号 [类型] \"文字\" (x,y) 标记。已压缩，只含有内容的节点。"
                            + "image:true 会同时截一张图并返回编号 sN —— 后面点坐标时带上 observation:\"sN\"，"
                            + "执行前会核对界面有没有变，防止点错。",
                    schema(props(new String[]{"max_nodes", "image"},
                                 new String[]{"integer", "boolean"},
                                 new String[]{"最多返回多少节点，默认 " + Agent.UI_MAX_NODES, "是否截图并返回编号"}),
                           new String[]{})));
            a.add("ui.act", tool("ui_tap",
                    "点击界面元素。优先 text（匹配最近一次 ui_dump 里的文字），其次 index，最后才坐标。"
                            + "用坐标时带上 observation:\"sN\" 更安全（会先核对界面没变）。",
                    schema(props(new String[]{"text", "index", "x", "y", "observation"},
                                 new String[]{"string", "integer", "integer", "integer", "string"},
                                 new String[]{"节点文字（包含匹配）", "ui_dump 里的 #序号", "横坐标", "纵坐标",
                                         "截图编号 sN（可选，校验界面未变）"}),
                           new String[]{})));

            // ---- 插件市场的工具：清单里声明的 Python 工具 ----
            for (JSONObject mp : Market.enabledPlugins(a.ctx)) {
                String pid = mp.optString("id", "");
                JSONArray mtools = mp.optJSONArray("tools");
                if (mtools == null) continue;
                for (int mi = 0; mi < mtools.length(); mi++) {
                    JSONObject mt = mtools.optJSONObject(mi);
                    if (mt == null) continue;
                    JSONObject params = mt.optJSONObject("params");
                    JSONObject props = params == null ? null : params.optJSONObject("properties");
                    if (props == null) props = new JSONObject();
                    a.add("shell", tool(Market.toolName(pid, mt.optString("name")),
                            mt.optString("desc", "插件工具"),
                            schema(props, requiredOf(params))));
                }
            }

            a.add("ui.act", tool("ui_gesture",
                    "坐标手势脚本，一次调用跑完整串：steps 里每步 {kind, x, y, ...}。"
                            + "kind: tap / long（任意时长）/ double / swipe / path（points 折线）/ pinch（spread）。"
                            + "步与步之间自动留 gap。适合列表长按拖动、双指缩放、沿路径滑动。",
                    schema(props(new String[]{"steps", "gap", "observation"},
                                 new String[]{"array", "integer", "string"},
                                 new String[]{"步骤数组", "步间隔毫秒，默认 120", "截图编号 sN（可选）"}),
                           new String[]{"steps"})));

            a.add("ui.act", tool("ui_input",
                    "点坐标并写字，一次完成：点 → 找焦点 → 写入 → 回读确认 → （可选）提交。"
                            + "比 ui_tap + ui_type 两次调用稳，写不进去会自动改用剪贴板粘贴。密码框只回报字数。",
                    schema(props(new String[]{"x", "y", "text", "submit", "observation"},
                                 new String[]{"integer", "integer", "string", "boolean", "string"},
                                 new String[]{"横坐标", "纵坐标", "要写入的文字",
                                         "写完是否按一次回车（默认 false）", "截图编号 sN（可选）"}),
                           new String[]{"x", "y", "text"})));

            a.add("ui.act", tool("ui_type",
                    "往输入框写字（无障碍 ACTION_SET_TEXT，不需要 root）。"
                            + "先 ui_dump 找到输入框的 #序号，再用 index 指定；"
                            + "不传 index 就写进当前聚焦的输入框。整段替换，不是逐字敲。",
                    schema(props(new String[]{"text", "index"},
                                 new String[]{"string", "integer"},
                                 new String[]{"要写入的文字", "ui_dump 里的 #序号（输入框那一行）"}),
                           new String[]{"text"})));

            if (Plugin.isOn(a.ctx, "builtin.terminal")) a.add("shell", tool("sh",
                    "在手机上执行 shell 命令（app 身份；有 root 则以 root 执行）。不需要确认，直接跑；只有不可回滚的命令会问一次。"
                            + "慢任务（编译/解压/下载）用 timeout 指定秒数，默认 120、最大 600；不要跑会一直等输入的命令。",
                    schema(props(new String[]{"command", "description", "timeout"},
                                 new String[]{"string", "string", "integer"},
                                 new String[]{"完整命令行，例如 ls -l /sdcard",
                                         "这条命令要做什么（一句话自然语言，会显示在消息流里）",
                                         "超时秒数，默认 120，最大 600"}),
                           new String[]{"command"})));

            a.add("net.fetch", tool("browser_open",
                    "打开一个网址（内置浏览器）。加载完再读正文；比 net_fetch 强在能跑 JS、能点、能填表单。",
                    schema(obj("url", "string", "完整网址，含 https://"), new String[]{"url"})));
            a.add("net.fetch", tool("browser_text",
                    "读当前网页的可见正文。分页/懒加载的页面先 open 再 text。",
                    schema(new JSONObject(), new String[]{})));
            a.add("net.fetch", tool("browser_js",
                    "在当前页面执行一段 JS 并把返回值给我（字符串）。做不了别的手段时用它。",
                    schema(obj("code", "string", "JS 表达式或 IIFE"), new String[]{"code"})));
            a.add("net.fetch", tool("browser_enter",
                    "在当前页面的某个元素上按回车并提交所在表单。搜索框用这个提交最可靠。",
                    schema(obj("selector", "string", "CSS 选择器"), new String[]{"selector"})));
            a.add("net.fetch", tool("browser_click",
                    "按 CSS 选择器点击当前页面上的元素。",
                    schema(obj("selector", "string", "CSS 选择器，如 #login .btn"), new String[]{"selector"})));
            a.add("net.fetch", tool("browser_type",
                    "按 CSS 选择器往输入框里填内容。",
                    schema(props(new String[]{"selector", "value"}, new String[]{"string", "string"},
                                 new String[]{"CSS 选择器", "要填的内容"}), new String[]{"selector", "value"})));

            if (Plugin.isOn(a.ctx, "builtin.web_search")) a.add("net.fetch", tool("web_search",
                    "网页搜索：返回若干条 标题 + 链接。用来查实时信息，比直接猜可靠。",
                    schema(obj("query", "string", "搜索词"), new String[]{"query"})));

            if (Plugin.isOn(a.ctx, "builtin.subagent")) a.add("agent.spawn", tool("subagent",
                    "派一个子智能体去做一件独立的事。它有自己的上下文，只把结论带回来——"
                            + "适合「读一堆东西再总结」，不污染当前会话。",
                    schema(obj("task", "string", "要它做的事，写清楚目标和产出"), new String[]{"task"})));

            if (Plugin.isOn(a.ctx, "builtin.loop")) a.add("agent.spawn", tool("loop",
                    "循环执行同一件事若干轮，直到结果里出现「完成」或到达上限。用于「改到能跑」这类迭代任务。",
                    schema(props(new String[]{"instruction", "rounds"},
                                 new String[]{"string", "integer"},
                                 new String[]{"每一轮要做的事", "最多几轮，默认 3，上限 5"}),
                           new String[]{"instruction"})));

            if (Plugin.isOn(a.ctx, "builtin.agent_team")) {
                a.add("agent.spawn", tool("team_spawn",
                        "派一个 teammate 在后台并行跑（不阻塞你）。多个 teammate 可以同时写工作区不同文件。",
                        schema(props(new String[]{"name", "task"},
                                     new String[]{"string", "string"},
                                     new String[]{"给它起个短名字，如 scout", "要它做什么"}),
                               new String[]{"name", "task"})));
                a.add("agent.spawn", tool("team_list", "看团队里每个人的进度。", schema(new JSONObject(), new String[]{})));
                a.add("agent.spawn", tool("team_send",
                    "给正在跑的 teammate 发一条消息，它在下一步读到。用来中途加要求或纠正方向。",
                    schema(props(new String[]{"to", "message"}, new String[]{"string", "string"},
                                 new String[]{"teammate 名字", "要说的话"}), new String[]{"to", "message"})));
            a.add("agent.spawn", tool("team_wait",
                    "等团队出现变化（有人跑完），最多等 timeout 秒。返回当前全队状态。",
                    schema(obj("timeout", "integer", "最多等几秒，默认 60"), new String[]{})));
            a.add("agent.spawn", tool("team_stop",
                    "中断某个 teammate，它会在下一步停下。",
                    schema(obj("name", "string", "teammate 名字"), new String[]{"name"})));

            a.add("agent.spawn", tool("task_create",
                    "在共享任务板上建一条任务。并行干活前先把活拆成条目，谁领了谁做一目了然。",
                    schema(props(new String[]{"subject", "description", "blockedBy"},
                                 new String[]{"string", "string", "string"},
                                 new String[]{"一句话标题", "详细说明/验收标准", "依赖的任务 id，逗号分隔，可空"}),
                           new String[]{"subject"})));
            a.add("agent.spawn", tool("task_list",
                    "看任务板。status 可过滤 pending / in_progress / completed。",
                    schema(obj("status", "string", "状态过滤，可空"), new String[]{})));
            a.add("agent.spawn", tool("task_get",
                    "看一条任务的详情（含 revision，更新时要带上）。",
                    schema(obj("id", "string", "任务 id，如 t3"), new String[]{"id"})));
            a.add("agent.spawn", tool("task_update",
                    "改一条任务：claim 领走 / complete 做完 / reopen 重开 / edit 改内容 / delete 删掉。",
                    schema(props(new String[]{"id", "action", "expectedRevision", "owner", "subject", "description"},
                                 new String[]{"string", "string", "integer", "string", "string", "string"},
                                 new String[]{"任务 id", "claim/complete/reopen/edit/delete", "上一次读到的 revision",
                                              "谁在做", "新标题（edit）", "新说明（edit）"}),
                           new String[]{"id", "action"})));

            a.add("agent.spawn", tool("team_collect",
                        "取某个 teammate 的结论。没跑完会告诉你没跑完。",
                        schema(obj("name", "string", "teammate 名字"), new String[]{"name"})));
            }

            if (Plugin.isOn(a.ctx, "builtin.core")) {
                a.add("fs.write", tool("todo",
                        "待办清单：多步任务先摊开，做完一条就更新。\n"
                                + "items 是整张表（不是增量），每项 {text, status}，status 取 pending/doing/done。\n"
                                + "不带 items 就返回当前清单。",
                        schema(props(new String[]{"items", "clear"},
                                     new String[]{"array", "boolean"},
                                     new String[]{"整张清单 [{text,status}]", "true 表示清空"}),
                               new String[]{})));
            }

            if (Plugin.isOn(a.ctx, "builtin.core")) {
                a.add("notify", tool("schedule_list", "列出已配置的定时任务。",
                        schema(props(new String[]{}, new String[]{}, new String[]{}), new String[]{})));
                a.add("notify", tool("schedule_add",
                        "建一个周期性任务：每隔 N 分钟自动在一个新会话里跑这段 prompt，结果发通知。",
                        schema(props(new String[]{"name", "prompt", "minutes"},
                                     new String[]{"string", "string", "integer"},
                                     new String[]{"任务名", "要跑的指令", "间隔分钟数，默认 60"}),
                               new String[]{"name", "prompt"})));
                a.add("notify", tool("schedule_remove", "删掉一个定时任务。",
                        schema(props(new String[]{"id"}, new String[]{"string"},
                                     new String[]{"任务 id"}), new String[]{"id"})));
                a.add("notify", tool("goal_set",
                        "设定本轮任务的目标（一句话讲清做什么、什么算完成）。会写进系统提示，\n"
                                + "后来轮次你都看得到，用来防止长任务跑偏。",
                        schema(props(new String[]{"text"}, new String[]{"string"},
                                     new String[]{"目标"}), new String[]{"text"})));
                a.add("notify", tool("goal_get", "读当前目标。",
                        schema(props(new String[]{}, new String[]{}, new String[]{}), new String[]{})));
                a.add("notify", tool("goal_clear", "清掉当前目标（任务做完时调用）。",
                        schema(props(new String[]{}, new String[]{}, new String[]{}), new String[]{})));
                a.add("agent.spawn", tool("workflow",
                        "并行跑多个子任务并汇总：tasks=[{name, task}]，最多 6 个，同时开跑。\n"
                                + "适合同一件事分几个方向分别查；要做成串行依赖请自己分多轮。",
                        schema(props(new String[]{"tasks"}, new String[]{"array"},
                                     new String[]{"[{name, task}]"}), new String[]{"tasks"})));
                a.add("notify", tool("ask_user",
                        "让用户做选择：给 title + 若干 options，界面会弹按钮，返回用户选中的那个。\n"
                                + "需要确认方向、方案二选一时用它，比在正文里问更明确。",
                        schema(props(new String[]{"title", "options", "multi"},
                                     new String[]{"string", "array", "boolean"},
                                     new String[]{"问题", "选项文本数组", "true 表示可多选"}),
                               new String[]{"title", "options"})));
                a.add("notify", tool("plan_present",
                        "把计划交给用户确认：给 plan 文本，界面弹出计划 + 「执行 / 再想想」。\n"
                                + "用户点执行才继续动手。大改动前先过一遍，比做完再返工便宜。",
                        schema(props(new String[]{"plan"}, new String[]{"string"},
                                     new String[]{"计划正文"}), new String[]{"plan"})));
                a.add("mcp.call", tool("mcp_resources",
                        "列出一个 MCP 服务暴露的资源（resources/list）。",
                        schema(props(new String[]{"server"}, new String[]{"string"},
                                     new String[]{"服务名"}), new String[]{"server"})));
                a.add("mcp.call", tool("mcp_read",
                        "读一个 MCP 资源（resources/read），返回其文本内容。",
                        schema(props(new String[]{"server", "uri"},
                                     new String[]{"string", "string"},
                                     new String[]{"服务名", "资源 uri"}), new String[]{"server", "uri"})));
            }

            if (Plugin.isOn(a.ctx, "builtin.terminal")) {
                a.add("shell", tool("python",
                        "用内置 Python 3.14 跑一段代码（有标准库，没有第三方包）。"
                                + "算数、解析文本、生成文件都该用它，不要心算。",
                        schema(props(new String[]{"code", "description", "timeout"},
                                     new String[]{"string", "string", "integer"},
                                     new String[]{"Python 源码",
                                             "这段代码要做什么（一句话自然语言，会显示在消息流里）",
                                             "超时秒数，默认 30，最大 120"}),
                               new String[]{"code"})));
            }

            if (Plugin.isOn(a.ctx, "builtin.terminal")) {
                a.add("shell", tool("job_start",
                        "把耗时命令丢到后台跑，立刻返回任务 id（不占用当前回合）。\n"
                                + "装包、编译、大文件下载、长时间脚本都用它；短命令仍然用 sh。",
                        schema(props(new String[]{"command", "label"},
                                     new String[]{"string", "string"},
                                     new String[]{"要执行的 shell 命令", "任务名，用于列表显示"}),
                               new String[]{"command"})));
                a.add("shell", tool("job_list", "看后台任务：id、名字、状态。",
                        schema(props(new String[]{}, new String[]{}, new String[]{}), new String[]{})));
                a.add("shell", tool("job_output",
                        "读后台任务的输出。给 wait 就先等几秒，适合刚起就想看结果的场景。",
                        schema(props(new String[]{"id", "wait"},
                                     new String[]{"string", "integer"},
                                     new String[]{"任务 id", "最多等几秒，默认 0"}),
                               new String[]{"id"})));
                a.add("shell", tool("job_kill", "结束一个后台任务。",
                        schema(props(new String[]{"id"}, new String[]{"string"},
                                     new String[]{"任务 id"}), new String[]{"id"})));
            }
            if (Plugin.isOn(a.ctx, "builtin.skills")) {
                a.add("fs.read", tool("skill",
                        "读取一个技能的完整流程说明。调用前先看系统提示里的技能目录；"
                                + "做那类工作之前先读它，再按步骤做。",
                        schema(obj("name", "string", "技能 id，如 ds-tdd"), new String[]{"name"})));
            }

            a.add("screen.capture", tool("screenshot",
                    "截取当前手机屏幕并**看到**它（图片会作为视觉输入发给模型）。"
                            + "看界面细节、图表、别人 App 的排版时用它；要文字用 ui_dump 更省。",
                    schema(new JSONObject(), new String[]{})));

            if (Plugin.isOn(a.ctx, "builtin.render")) {
                a.add("agent.render", tool("render",
                        "把一段 HTML 或一个网页地址渲染成卡片显示在对话里。"
                                + "适合画表格、图表、示意图；纯文本不要用它。",
                        schema(props(new String[]{"html", "url"},
                                     new String[]{"string", "string"},
                                     new String[]{"要渲染的 HTML 片段（可选）", "要打开的网址（可选）"}),
                               new String[]{})));
            }

            if (Plugin.isOn(a.ctx, "builtin.devtools")) {
                if (Plugin.isOn(a.ctx, "builtin.devtools")) a.add("fs.read", tool("dev_selfcheck",
                    "跑一遍端上自检：OOXML 结构、媒体文件头、工作区增删、会话落盘、孤儿消息清理。"
                            + "不需要网络，几秒完成，返回逐项结果。",
                    schema(new JSONObject(), new String[]{})));
            a.add("fs.read", tool("dev_info",
                        "开发者诊断：版本、档位、工具数、内置插件开关、会话用量、审计条数、工作区文件。",
                        schema(new JSONObject(), new String[]{})));
                a.add("fs.write", tool("dev_export",
                        "把当前会话的原始记录导出到工作区，方便排查。",
                        schema(new JSONObject(), new String[]{})));
                a.add("agent.render", tool("dev_webui",
                        "在本机起一个只监听 127.0.0.1 的 http 服务，托管电脑版那份真实前端，返回可访问地址。"
                                + "它同时记录前端发出的每个请求（接口清单从流量里读）。traffic=true 只读记录。",
                        schema(props(new String[]{"traffic"}, new String[]{"boolean"},
                                     new String[]{"true 表示只读请求记录"}), new String[]{})));
            }
        } catch (Exception ignored) {}
    
    }

    static JSONObject tool(String name, String desc, JSONObject params) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", params);
        JSONObject t = new JSONObject();
        t.put("type", "function");
        t.put("function", fn);
        return t;
    }

    static JSONObject schema(JSONObject properties, String[] required) throws Exception {
        JSONObject s = new JSONObject();
        s.put("type", "object");
        s.put("properties", properties);
        if (required != null && required.length > 0) {
            JSONArray r = new JSONArray();
            for (String x : required) r.put(x);
            s.put("required", r);
        }
        return s;
    }

    static JSONObject props(String[] names, String[] types, String[] descs) throws Exception {        JSONObject p = new JSONObject();
        for (int i = 0; i < names.length; i++) {
            JSONObject o = new JSONObject();
            o.put("type", types[i]);
            o.put("description", descs[i]);
            p.put(names[i], o);
        }
        return p;
    }

    static String[] requiredOf(JSONObject params) {
        JSONArray req = params == null ? null : params.optJSONArray("required");
        if (req == null) return new String[]{};
        String[] out = new String[req.length()];
        for (int i = 0; i < req.length(); i++) out[i] = req.optString(i, "");
        return out;
    }

    static JSONObject obj(String name, String type, String desc) throws Exception {
        JSONObject p = new JSONObject();
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("description", desc);
        p.put(name, o);
        return p;
    }
}
