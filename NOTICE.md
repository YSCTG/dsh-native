# 第三方组件声明

本项目（DSH Native）自身的代码自 v34.8 起按 [PolyForm Noncommercial 1.0.0](LICENSE)
授权。**随 APK 一起分发的第三方组件仍按它们各自的许可，不受本项目协议约束。**

下面这些是打进 APK 或在构建期使用的组件。列在这里是为了在分享时一并带上。

| 组件 | 用途 | 许可 |
|---|---|---|
| Python 3.14（CPython，arm64） | 内嵌解释器，跑插件脚本 | PSF License |
| Android SDK Build-Tools（aapt2 / zipalign / apksigner / d8） | 在手机上打包 APK | Apache License 2.0 |
| Eclipse Compiler for Java（ecj） | 在手机上编译 Java 源码 | Eclipse Public License 2.0 |
| android.jar（API 28，仅编译期） | 编译 Java 时的类型桩 | Apache License 2.0 |
| Termux android-tools（adb 客户端） | 免电脑的 adb 通道 | Apache License 2.0（上游 AOSP） |
| docx-preview | iOS 端 docx 渲染 | MIT |
| JSZip | iOS 端解压 docx | MIT / GPLv3 双许可（本项目按 MIT 使用） |
| SheetJS (xlsx) | iOS 端表格渲染 | Apache License 2.0 |

说明：

- 这些组件的许可**允许商业使用**，但本项目对自身代码的限制不因此扩展到它们，
  也不因此放松。分发时请不要删掉它们的许可声明。
- 若你只使用本项目自身代码（不含上述二进制），只需遵守根目录 `LICENSE`。
- 本项目不再包含 LibreOffice（曾评估过，因初始化崩溃与体积放弃，见
  `docs/libreoffice-verdict.md`）。
