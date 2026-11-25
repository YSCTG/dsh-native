# 游戏项目：手机端 roguelike 骨架

这是 `game_new` 生成的骨架，**不依赖任何 npm 包**：纯 canvas 2D + 回合制。

## 文件

| 文件 | 作用 |
|---|---|
| `index.html` | 页面与样式、HUD、触屏方向键 |
| `game.js` | 地图生成、怪物、战斗、寻路、绘制（约 200 行） |
| `README.md` | 本文件 |

## 现在就能玩

```
浏览器打开 index.html
```

或让 agent 用 `browser_open` 打开它。操作：

- 屏幕滑动 / 下方方向键：走一格
- 撞上怪物 = 攻击；踩到 `▼` 下一层
- 键盘方向键或 WASD（桌面调试用）

## 改什么

- **地图/难度**：`genMap()` 里的房间数、怪物密度、`depth` 影响到的血量与攻击
- **战斗**：`move()` 里的伤害公式、`monstersTurn()` 的追击逻辑
- **绘制**：`draw()` 全部是矩形/圆；想换成贴图或 PixiJS，只改这一层

## 打包成单文件

```
game_build  → 生成 <名字>.single.html
```

把 `game.js` 内联进 HTML，得到一个可以直接发给别人玩的文件，不需要 node_modules、
也不需要跑本地服务器。

## 需要更华丽的渲染

`npm_install package=pixi.js` 装上 PixiJS（或 phaser / kaplay），把 `draw()` 换成引擎的
渲染层即可——逻辑部分（地图、回合、怪物）不用动。注意：单文件打包对第三方库只做简单的
内联，体积会变大；要求更细的产物就得上 esbuild 这类打包器（手机上目前没有）。
