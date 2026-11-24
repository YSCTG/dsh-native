# 让「检查更新」在国内真正可用

GitHub 的 release 资产（74 MB 的 APK）在手机上经常**卡死**：小请求（JSON）多半能过，长连接的大下载会被掐。
实测记录：

```
00:55:59  更新 开始下载 .../v29.6/DSHNative.apk
00:55:59  更新 尝试下载 直连
00:56:34  更新 该源失败: java.net.SocketTimeoutException: timeout
```

同一时刻在 PC 上同一地址 **74.1 MB / 11 秒 / 6.9 MB/s**。所以问题不在代码，在于链路。

## 结论

| 能力 | 现状 |
|---|---|
| 检查有没有新版 | 可用（多源，谁通用谁，取最高版本） |
| 从 GitHub 下 APK | **不可靠** |
| 从国内可达的地址下 APK | 可用，把地址写进清单即可 |

## 做法：APK 换个地方放，清单指过去

不需要改 App。`update.json` 里的 `apk` 就是下载地址，另有 `apk_mirrors` 数组可放多个备用地址，
更新器按 **apk_mirrors → apk → 内置代理** 的顺序逐个尝试。

```json
{
  "version": "29.7",
  "apk": "https://你的域名/dsh/DSHNative.apk",
  "apk_mirrors": [
    "https://github.com/YSCTG/dsh-native/releases/download/v29.7/DSHNative.apk"
  ],
  "size": 77680,
  "notes": "本次更新说明"
}
```

## Cloudflare 路线（推荐 R2 + 自定义域名）

R2 出站流量免费，配一个自定义域名后国内多数地区可用。

```bash
# 一次性：安装 wrangler 并登录
npm i -g wrangler
wrangler login

# 建桶
wrangler r2 bucket create dsh-native

# 上传（每次发版）
wrangler r2 object put dsh-native/apk/DSHNative.apk --file DSHNative.apk
wrangler r2 object put dsh-native/update.json     --file update.json
```

然后在 Cloudflare 控制台给桶绑定自定义域名（例如 `dl.example.com`），那两个文件的地址就是：

```
https://dl.example.com/apk/DSHNative.apk
https://dl.example.com/update.json
```

**注意**：`update.json` 也需要一个国内可达的地址。如果 jsDelivr / raw 都不稳，就把它也放到同一个域名下，
然后把 App 的检查源指过去——`Updater` 的清单列表里已经有 jsDelivr / raw / 两个代理，再加一个自有域名是
一行常量的事。

## 检查清单

1. `wrangler r2 object put` 上传 APK 与 update.json
2. 浏览器打开 `https://dl.example.com/update.json`，能看到 JSON
3. 浏览器打开 `https://dl.example.com/apk/DSHNative.apk`，**能开始下载**（这一步最关键）
4. 改 `update.json` 的 `version` 为比手机上新一档的号
5. 手机上：设置 → 检查更新 → 应显示来源为你的域名
6. 点「下载并安装」→ 系统安装器 → 装完 `dumpsys package com.dshnative | grep versionName` 应变成新版本

## 常见坑

- **自定义域名没生效**：R2 的公共 `r2.dev` 域名在国内不稳定，别直接用它做生产地址。
- **HTTPS 证书**：自定义域名要等 Cloudflare 签好证书（通常几分钟）。
- **版本号没动**：App 只比较 `versionName`，`versionCode` 也必须递增，否则系统安装器会认为"同版本"。
- **清单缓存**：`update.json` 走 CDN 会有几分钟延迟；更新器取所有源里**最高**版本，能绕过旧缓存，但若只有一个源仍然会滞后，发版后等几分钟再测。
