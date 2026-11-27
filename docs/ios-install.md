# iOS 安装与签名

这个包是 **未签名 ipa**（`DSHNative-unsigned.ipa`，约 570 KB）。
iOS 要求每个 App 都有签名，所以必须先用自己的证书签一次才能装。

包本身需要的东西很少：没有推送、没有后台模式、没有特殊 entitlement，
**不需要 JIT**（JavaScriptCore 用解释模式也能跑），所以普通签名就够。

---

## 方案总览

| 方案 | 成本 | 有效期 | 需要什么 | 适合谁 |
|---|---|---|---|---|
| A. Sideloadly（免费 Apple ID） | 免费 | **7 天** | Windows/Mac + 数据线 | 想先试试 |
| B. AltStore / SideStore | 免费 | 7 天（SideStore 可设备内续签） | PC 或 一台常开设备 | 愿意折腾续签 |
| C. Xcode 直装 | 免费 | 7 天 | **必须 Mac** | 有 Mac |
| D. 付费开发者账号 + Xcode | $99/年 | 1 年 | Mac | 长期自用 |
| E. 付费账号 + Ad Hoc | $99/年 | 1 年 | Mac + 设备 UDID | 少量设备分发 |
| F. TestFlight | $99/年 | 90 天/构建 | Mac | 给别人测试 |
| G. TrollStore | 免费 | **永久** | 特定 iOS 版本 | 版本刚好合适最爽 |
| H. App Store | $99/年 | 长期 | 审核 | 公开分发（大概率不过审） |
| I. 企业证书 | $299/年 | 1 年 | 公司资质 | **不合规，别用** |
| J. 淘宝/第三方签名服务 | 几十元 | 不稳定 | 无 | **有掉签和隐私风险** |

---

## A. Sideloadly（推荐先试这个）

1. PC 上装 [Sideloadly](https://sideloadly.io/)
2. iPad 用数据线连电脑，信任这台电脑
3. Sideloadly 里：
   - IPA 选 `DSHNative-unsigned.ipa`
   - Apple ID 填你的（会走 Apple 服务器取签名证书）
   - 点 Start，输入 Apple ID 密码（有些账号要 App 专用密码）
4. iPad：**设置 → 通用 → VPN与设备管理 → 开发者 App → 信任你的 Apple ID**
5. 回桌面打开 DSH

**注意**
- iOS 16+ 要先开 **设置 → 隐私与安全性 → 开发者模式**，重启一次
- 7 天后打不开，重新走一遍（App 数据不会丢）
- 一个免费账号在单台设备上最多 3 个自签 App

---

## B. AltStore / SideStore

比 A 多一步"自动续签"：
- **AltStore**：PC 上装 AltServer，iPad 同 Wi-Fi，7 天自动续
- **SideStore**：不用常开 PC，但要在设备上装一个 WireGuard 配置做本地回环

适合不想每周插线的人。配置比 A 麻烦。

---

## C. Xcode 直装（有 Mac 最省事）

```bash
open ios/App/DSHNative.xcodeproj
# Signing & Capabilities → Team 选你的个人 Apple ID
# 选你的 iPad 作为运行目标 → Run
```
同样 7 天，但不用第三方工具。

---

## D–F. 付费开发者账号（$99/年）

有账号之后：

```bash
# D: 直接 Xcode 装，签名 1 年有效
# E: Ad Hoc 分发（需在后台登记设备 UDID，上限 100 台/年）
xcodebuild -project DSHNative.xcodeproj -scheme DSHNative \
  -sdk iphoneos -configuration Release \
  -archivePath build/DSHNative.xcarchive archive
xcodebuild -exportArchive -archivePath build/DSHNative.xcarchive \
  -exportPath build/export -exportOptionsPlist ExportOptions.plist
# F: TestFlight 上传
xcrun altool --upload-app -f build/export/DSHNative.ipa \
  -t ios --apiKey <KEY> --apiIssuer <ISSUER>
```

---

## G. TrollStore（版本合适就最省事）

利用 CoreTrust 漏洞做**永久签名**，不用证书、不会掉签。

支持的版本（取决于设备和版本组合，装之前先查 [ios.cfw.guide](https://ios.cfw.guide/)）：
- iOS 14.0 – 16.6.1（多数设备）
- iOS 17.0（部分设备）
- 不支持 iOS 17.1 及以上

用法：装 TrollStore → 把 ipa 拷进 iPad（AirDrop / 文件 App）→ 点开选 "Install"。

**如果你的 iPad 是 iOS 17.0 或更低，这是最优解。**

---

## H. App Store

技术上能提审，但这类"能执行任意工具、能读写文件、能联网"的 agent 壳
大概率不符合 2.5.2（下载可执行代码）以外的多条规则，**不建议走**。

---

## I / J. 不推荐的

- **企业证书（$299）**：苹果明确限制只能内部员工用，公开分发会被吊销，连累所有装了的设备
- **淘宝代签 / 共享证书**：你的 App 会被别人的证书托管，存在被植入、被吊销、掉签的风险

---

## 怎么看装完有没有问题

```bash
# 从 PC 看设备日志（需要 libimobiledevice 或 Xcode）
idevicesyslog | grep DSHNative
```

首次装上如果闪退，把控制台报错发 issue。最可能的三个原因：
1. 没开开发者模式
2. 签名证书和 bundle id 不匹配
3. iOS 版本低于 17.0（工程的 deploymentTarget 是 17.0，需要改 `ios/App/project.yml` 后重新构建）
