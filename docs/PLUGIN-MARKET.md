# 插件市场：两套运行时，一份语义

## 电脑版是什么

电脑版的插件市场读**一份 JSON 索引**（`dsh-plugin-market-index` 定义的格式 1），索引里列的是
**npm / Cordis 组合包**：每条有 `name`、`versions[]`、`category`、`keywords`、`icon`、
`engines.dsh`、`source`（npm registry 或 git）。安装时把它拼成 pnpm spec（`name@version` 或
`git+https` 固定提交），下载进 profile 的 `node_modules`，由 Cordis 加载器在**Node 运行时**里
实例化。所以"插件"= 能在 Node 里跑的 JS 模块。

## 手机端为什么不能照抄

Native 里没有 Node，也不该为了装插件塞一套进去（体积、启动时间、安全面全变）。所以 Native 换了
**声明式清单**：一份 JSON 就是一个插件，安装 = 把这个 JSON 存到 `files/plugins/<id>.json`。

| 维度 | 电脑版 | Native |
|---|---|---|
| 索引格式 | 格式 1 的 JSON（npm 组合包） | `format: 1` 的 JSON（Native 清单） |
| 安装物 | npm 包 + Cordis 插件 | 一个 JSON 文件 |
| 插件能提供 | Service / Tool / UI 页面 / 提示词 | 工具（Python 片段）+ 技能（提示词） |
| 运行时 | Node + Cordis | 内置 CPython 3.14 + 现有工具表 |
| 生效时机 | 热加载 | **新会话**（工具表按会话冻结） |

## Native 清单格式

```json
{
  "format": 1,
  "plugins": [
    {
      "id": "units",
      "name": "单位换算",
      "version": "1.0.0",
      "category": "工具",
      "description": "长度/重量/温度/数据量换算",
      "tools": [
        {
          "name": "convert",
          "desc": "单位换算。参数 value、from、to",
          "params": {"type": "object",
                     "properties": {"value": {"type": "number"}},
                     "required": ["value"]},
          "python": "v = float(ARGS['value'])\nprint(v * 1000)"
        }
      ],
      "skills": []
    }
  ]
}
```

约定：

- `params` 就是喂给模型看的 JSON Schema，键名与 `properties` 一致；
- `python` 片段里可以直接用 `ARGS`（已解析好的 dict），把结果 `print` 到 stdout；
- 工具在模型侧的名字是 `mkt_<插件id>_<工具名>`，挂在 `shell` 能力下（它跑的是本机 Python）；
- 插件里声明的 `skills` 会合并进技能列表。

## 生态怎么衔接

1. **同一套语义**：`id / name / version / category / description` 与桌面索引字段对齐，桌面条目可以
   手写转换（或写个小脚本）成 Native 清单。
2. **不假装兼容**：npm 组合包在手机上**不能跑**。市场界面里它们只会显示为"桌面专用"，不提供安装
   按钮——宁可少列，也不做一个装完必然报错的按钮。
3. **目录可远程**：`market_url` 指向任意返回同一格式 JSON 的地址，拉回后缓存到
   `files/market-cache.json`。以后要把社区目录挂上去，只要它输出这个格式。
4. **安全边界**：插件代码在本机以 app 身份跑，和 `python` 工具同权限。安装是显式动作，卸载即删除
   文件；不引入新的权限模型。

## 现状

- 内置目录 3 个示例插件：二维码、单位换算、系统体检。
- 设置 → 插件市场：安装 / 移除 / 从网络刷新目录。
- 安装后**开新会话**才生效（工具表冻结）。
