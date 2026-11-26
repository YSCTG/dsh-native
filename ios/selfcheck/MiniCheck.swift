import Foundation

/// 最小自检：只用纯 Foundation 的两个文件（Tool + ZipWriter）。
///
/// 为什么单独做一个：如果 105 KB 那份"点了没反应"，可能是
///   a) Playgrounds 根本没跑起来（粘贴/运行方式问题）
///   b) 代码里有编译错误（编辑区应该显示红字）
///   c) 跑了但输出在别的地方没看到
/// 这份只有 ~15 KB，编译快、出错点少，用它先把 (a) 排除掉。

enum MiniCheck {
    static var passed = 0
    static var failed: [String] = []

    static func expect(_ ok: Bool, _ label: String) {
        if ok {
            passed += 1
            print("  ✓ \(label)")
        } else {
            failed.append(label)
            print("  ✗ \(label)")
        }
    }

    static func run() {
        print("=== 最小自检开始 ===")

        // 1. JSON 解析
        let args = AgentLoop.parseArguments(#"{"path":"a.txt","n":7,"flag":true}"#)
        expect(args["path"]?.stringValue == "a.txt", "JSON 取字符串")
        expect(args["n"]?.intValue == 7, "JSON 取数字")
        expect(args["flag"]?.boolValue == true, "JSON 取布尔")

        // 2. 坏 JSON 不崩
        if case .object(let d) = AgentLoop.parseArguments("{broken") {
            expect(d.isEmpty, "坏 JSON 退化为空对象")
        } else {
            expect(false, "坏 JSON 退化为空对象")
        }

        // 3. 路径沙箱
        let ws = FileManager.default.temporaryDirectory
            .appendingPathComponent("mini-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true)
        let ctx = ToolContext(workspace: ws)
        do {
            _ = try ctx.resolve("../escape.txt")
            expect(false, "拒绝 ../ 逃逸")
        } catch {
            expect(true, "拒绝 ../ 逃逸")
        }
        expect((try? ctx.resolve("a/b.txt")) != nil, "工作区内路径放行")

        // 4. CRC32 标准向量
        expect(ZipWriter.crc32(Data("123456789".utf8)) == 0xCBF4_3926,
               "CRC32 标准向量（123456789 → 0xCBF43926）")

        // 5. 造一个 zip，看签名
        var zip = ZipWriter()
        zip.add("hello.txt", "你好，世界")
        let data = zip.finish()
        expect(data.count > 100, "zip 有内容（\(data.count) 字节）")
        expect(data.count > 3 && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04,
               "zip 以 PK\\x03\\x04 开头")

        print("")
        if failed.isEmpty {
            print("=== 全部通过（\(passed)/\(passed)）===")
        } else {
            print("=== 失败 \(failed.count) 项 / 共 \(passed + failed.count) 项 ===")
            failed.forEach { print("  · \($0)") }
        }
    }
}

MiniCheck.run()
