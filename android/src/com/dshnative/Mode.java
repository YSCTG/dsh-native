package com.dshnative;

/**
 * 模式 = 电脑版的 agent preset。四个 preset 直接照搬电脑版的定义：
 *
 *   standard（标准模式）  persona + 文件 + 搜索 + 任务 + 技能
 *   ptc             标准之上加规划类工具（todo / subagent / present / 插件管理）
 *   minimal（极简模式）  纯问答：连文件读写都关掉
 *   cordis（创造模式） 插件/预设开发：技能目录负责教怎么写
 *
 * 手机上没有 bash/子 agent/工作流这些，所以 ptc 的差异落到"先计划后动手"的行为约束上；
 * 这一点在 hint 里写清楚，不要让人以为我们偷偷少了工具。
 *
 * 模式在会话开始时选定并冻结 —— 改 system prompt 会让缓存前缀全部作废，所以换模式 = 换会话。
 */
public final class Mode {

    public final String id;
    public final String label;
    public final String hint;
    public final String promptExtra;
    public final boolean write;
    public final boolean ui;
    public final boolean net;

    private Mode(String id, String label, String hint, String promptExtra,
                 boolean write, boolean ui, boolean net) {
        this.id = id;
        this.label = label;
        this.hint = hint;
        this.promptExtra = promptExtra;
        this.write = write;
        this.ui = ui;
        this.net = net;
    }

    public static final Mode[] ALL = {
            new Mode("standard", "标准模式", "读写文件 · 看界面 · 点按 · 联网", "",
                    true, true, true),

            new Mode("ptc", "程序化工具调用模式", "先计划后动手 · 允许插件工具",
                    "你处在程序化工具调用模式：动手前先用两三行说明你打算怎么做（要调哪些工具、按什么顺序），"
                  + "然后再执行。计划要具体到工具名，不要空泛。",
                    true, true, true),

            new Mode("minimal", "极简模式", "只回答，不动手",
                    "你处在极简模式：只回答问题，不要调用任何会改变手机状态或产生网络请求的工具。"
                  + "需要动手时告诉用户切到标准模式。",
                    false, false, false),

            new Mode("cordis", "创造模式", "插件 / 预设开发",
                    "你处在创造模式：目标是产出一个能被装载的手机版插件（.dshp）。\n"
                  + "插件是「一个目录 + 一个 manifest.json」，目录放在 /data/data/com.dshnative/files/plugins/<id>/。\n"
                  + "**严格照这个结构写，键名不要自创**：\n"
                  + "{\n"
                  + "  \"id\": \"demo.greet\",\n"
                  + "  \"name\": \"问候\",\n"
                  + "  \"version\": \"0.1.0\",\n"
                  + "  \"capabilities\": [\"fs.read\"],\n"
                  + "  \"tools\": [\n"
                  + "    {\"name\": \"greet\", \"schema\": \"tools/greet.json\",\n"
                  + "     \"impl\": {\"kind\": \"file\", \"path\": \"greetings/{name}.txt\"}}\n"
                  + "  ]\n"
                  + "}\n"
                  + "要点：\n"
                  + "1. tools 是**内联对象数组**，不是文件路径列表；每项必须有 name / schema / impl 三个键。\n"
                  + "2. schema 指向参数 JSON Schema 文件（相对插件目录），形如 "
                  + "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}。\n"
                  + "3. impl.kind 只有三种：http{url}（GET，支持 {arg} 占位）、app{package}（拉起应用）、"
                  + "file{path}（读文件，支持 {arg}，相对路径按工作区解析）。\n"
                  + "4. capabilities 只能写：fs.read fs.write app.list app.launch clip.read clip.write "
                  + "notify ui.read ui.act net.fetch。写别的整个插件会被拒绝装载（失败即关闭）。\n"
                  + "5. 写完必须 fs_read 读回 manifest.json 自查：tools 是不是对象数组、三个键齐不齐。\n"
                  + "6. 插件不会热加载：装完要开新会话才生效。别自己假装调用过。",
                    true, true, true),
    };

    public static Mode byId(String id) {
        for (Mode m : ALL) if (m.id.equals(id)) return m;
        return ALL[0];
    }

    public boolean allows(String cap) {
        if ("fs.write".equals(cap)) return write;
        if ("ui.act".equals(cap)) return ui;
        if ("net.fetch".equals(cap)) return net;
        if ("shell".equals(cap)) return write;        // 极简模式不给终端
        if ("agent.spawn".equals(cap)) return net;    // 极简模式不派子智能体
        if ("mcp.call".equals(cap)) return net;       // 极简模式不连 MCP
        return true;
    }
}
