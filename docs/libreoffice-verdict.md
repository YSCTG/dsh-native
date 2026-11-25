# 内置 LibreOffice：结论与证据

> 结论：**不做**。不是因为工程量大，而是它在这台设备上根本跑不起来——官方 App 也一样崩。
> 本文记录当时的实测数据与判断，免得以后再花时间重走一遍。

## 1. 当时量到的体量

| 项 | 大小 |
|---|---|
| `liblo-native-code.so` | 187 MB |
| 全部 14 个原生库 | ≈ 201 MB |
| assets（share/program/unpack） | 17.9 MB |
| 打进 APK 后 | ≈ 159 MB（当时 APK 从 ~74 MB 涨到这里） |

这是"能做"的前提下的成本。还没算：

- 只暴露了 **48 个 JNI 符号**（`Bootstrap.*` 与 UNO 桥），**没有 `lok_*` 这类 C API**，
  也就是说只能用它的 Java 封装，改不了行为；
- 初始化签名是 `initializeNative(dataDir, cacheDir, getPackageResourcePath(), assets)`，
  必须把 assets 解包到私有目录，冷启动成本很高。

## 2. 真正的阻塞：初始化直接 abort

两种接法都试过：

1. `init(Activity)`（官方封装入口）
2. 直接调 `initializeNative(...)`

结果一致：

```
com::sun::star::uno::DeploymentException
```

进程 abort，连"起来了但渲染失败"这个阶段都没到。

## 3. 关键旁证：官方 App 在这台机器上也崩

从 F-Droid 装官方 LibreOffice Viewer，启动即崩，栈里是：

```
org.libreoffice.LOKitThread.refresh
```

这条最重要：**不是我们的集成方式有问题，而是 LO 在这台设备（HUAWEI ADA-AL10U / Android 12 / arm64）上跑不起来**。
在别人的机器上也许没事，但我们要支持的是**手边这台**。花 159 MB 换一个默认就崩的引擎，不成立。

## 4. 需求本身：我们缺的到底是"编辑"还是"看"

现有能力（`Office.java`，纯 Java，零依赖）：

- ✅ 生成 docx / xlsx / pptx / pdf（含样式、页脚页码、表格底纹、真实图表）
- ✅ 读 docx 段落表格、xlsx 单元格公式、pptx 每页文字
- ❌ **渲染预览**（把 docx 画成页面的样子）——这是 LO 唯一能补的那块

也就是说：LO 要解决的是"看一眼长什么样"，不是"能不能生成"。

## 5. 替代方案：WebView + JS 渲染器

体量（npm registry 实测，解压后）：

| 包 | 用途 | 解压后 |
|---|---|---|
| `docx-preview` | docx 渲染成 HTML | **0.9 MB** |
| `xlsx` (SheetJS) | xlsx 读/写/渲染 | 7.2 MB |
| `pdfjs-dist` | PDF 渲染（可裁到 worker+core） | 35.9 MB（可裁剪） |
| `pptxgenjs` | pptx 生成（我们已有替代） | 2.5 MB |

对比：**0.9–9 MB vs 159 MB**，而且在 WebView 里跑，不用原生库、不用 UNO、不会 abort。

做法：文件卡片里加"预览"，把 docx/xlsx/pdf 的字节 base64 内联进一个本地 HTML，
用对应的 UMD 包在 WebView 里渲染成页面。这套我们已经在用（`Preview.java` 的 WebView 渲染）。

## 6. 如果将来一定要"高保真转换"

真正的需求如果是 **docx → pdf 保真转换**，那属于重活，正确做法是**在电脑上跑**：

```
soffice --headless --convert-to pdf in.docx --outdir out/
```

电脑版 DSH 或一台常开的机器做执行端，手机只负责上传下载——和 iOS 那条"瘦客户端 + 执行端"
是同一个思路。我们已经有 `sh` 工具与网络能力，这条路不需要在手机上背 159 MB。

## 7. 一句话

- **生成**：已有，别动。
- **预览**：用 JS 渲染器，1–9 MB，够用。
- **高保真转换**：放电脑上做。
- **内置 LO**：不做。证据是它在这台设备上 abort，官方 App 同样崩。
