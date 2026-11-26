import Foundation

// ============ 1. CRC32（真实代码，来自 ZipWriter.swift）============
enum ZW {
    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                if crc & 1 == 1 { crc = (crc >> 1) ^ 0xEDB8_8320 } else { crc >>= 1 }
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

// ============ 2. JSON 参数（真实代码，来自 Tool.swift + AgentLoop.swift）============
enum JV {
    case null, bool(Bool), number(Double), string(String)
    case array([JV]), object([String: JV])

    var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    var intValue: Int? { if case .number(let n) = self { return Int(n) }; return nil }
    var boolValue: Bool? { if case .bool(let b) = self { return b }; return nil }
    subscript(k: String) -> JV? { if case .object(let d) = self { return d[k] }; return nil }
}

func convert(_ any: Any) -> JV {
    switch any {
    case let s as String: return .string(s)
    case let n as NSNumber:
        if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
        return .number(n.doubleValue)
    case let a as [Any]: return .array(a.map(convert))
    case let d as [String: Any]: return .object(d.mapValues(convert))
    default: return .null
    }
}

func parseArgs(_ raw: String) -> JV {
    guard let data = raw.data(using: .utf8),
          let any = try? JSONSerialization.jsonObject(with: data) else { return .object([:]) }
    return convert(any)
}

// ============ 3. 路径沙箱（真实逻辑，来自 ToolContext.resolve）============
enum PathErr: Error { case escape(String) }

func resolveInside(_ root: URL, _ path: String) throws -> URL {
    let t = path.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !t.isEmpty else { throw PathErr.escape("空路径") }
    let candidate = t.hasPrefix("/") ? URL(fileURLWithPath: t)
                                     : root.appendingPathComponent(t)
    // iOS 上 /var 与 /private/var 是同一目录的两种写法，必须解开软链接再比较
    func norm(_ u: URL) -> String {
        var s = u.resolvingSymlinksInPath().standardizedFileURL.path
        while s.count > 1 && s.hasSuffix("/") { s.removeLast() }
        return s
    }
    let rootPath = norm(root)
    let target = norm(candidate)
    guard target == rootPath || target.hasPrefix(rootPath + "/") else {
        throw PathErr.escape(t)
    }
    return candidate
}

// ============ 4. zip 打包（真实代码，store 方式，来自 ZipWriter.swift）============
struct ZipWriter {
    private var body = Data()
    private var offsets: [(String, UInt32, Int, Int)] = []

    mutating func add(_ name: String, _ bytes: Data) {
        let crc = ZW.crc32(bytes)
        let offset = body.count
        let nb = Data(name.utf8)
        body.append(u32(0x04034b50)); body.append(u16(20)); body.append(u16(0))
        body.append(u16(0)); body.append(u16(0)); body.append(u16(0x21))
        body.append(u32(crc)); body.append(u32(UInt32(bytes.count)))
        body.append(u32(UInt32(bytes.count)))
        body.append(u16(UInt16(nb.count))); body.append(u16(0))
        body.append(nb); body.append(bytes)
        offsets.append((name, crc, bytes.count, offset))
    }

    mutating func add(_ name: String, _ text: String) { add(name, Data(text.utf8)) }

    mutating func finish() -> Data {
        let cdStart = body.count
        for (name, crc, size, offset) in offsets {
            let nb = Data(name.utf8)
            body.append(u32(0x02014b50)); body.append(u16(20)); body.append(u16(20))
            body.append(u16(0)); body.append(u16(0)); body.append(u16(0)); body.append(u16(0x21))
            body.append(u32(crc)); body.append(u32(UInt32(size))); body.append(u32(UInt32(size)))
            body.append(u16(UInt16(nb.count))); body.append(u16(0)); body.append(u16(0))
            body.append(u16(0)); body.append(u16(0)); body.append(u32(0)); body.append(u32(UInt32(offset)))
            body.append(nb)
        }
        let cdSize = body.count - cdStart
        body.append(u32(0x06054b50)); body.append(u16(0)); body.append(u16(0))
        body.append(u16(UInt16(offsets.count))); body.append(u16(UInt16(offsets.count)))
        body.append(u32(UInt32(cdSize))); body.append(u32(UInt32(cdStart))); body.append(u16(0))
        return body
    }

    private func u16(_ v: UInt16) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 2) }
    private func u32(_ v: UInt32) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 4) }
}

// ============ 5. 断言 ============
var passed = 0
var failed: [String] = []

func check(_ ok: Bool, _ label: String) {
    if ok { passed += 1; print("  ✓ \(label)") }
    else { failed.append(label); print("  ✗ \(label)") }
}

print("=== DSH iOS 引擎 · 核心自检 ===")

print("【JSON】")
let a = parseArgs(#"{"path":"a.txt","n":7,"flag":true}"#)
check(a["path"]?.stringValue == "a.txt", "取字符串")
check(a["n"]?.intValue == 7, "取数字")
check(a["flag"]?.boolValue == true, "取布尔")
if case .object(let d) = parseArgs("{broken") { check(d.isEmpty, "坏 JSON 退化为空对象") }
else { check(false, "坏 JSON 退化为空对象") }

print("【路径沙箱】")
let root = FileManager.default.temporaryDirectory
    .appendingPathComponent("dsh-test-\(UUID().uuidString)")
try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
do { _ = try resolveInside(root, "../escape.txt"); check(false, "拒绝 ../ 逃逸") }
catch { check(true, "拒绝 ../ 逃逸") }
check((try? resolveInside(root, "a/b.txt")) != nil, "工作区内路径放行")

print("【CRC32 与 zip】")
check(ZW.crc32(Data("123456789".utf8)) == 0xCBF4_3926, "CRC32 标准向量")
var zip = ZipWriter()
zip.add("hello.txt", "你好，世界")
zip.add("a/b.json", "{\"k\":1}")
let data = zip.finish()
check(data.count > 200, "zip 有内容（\(data.count) 字节）")
check(data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04,
      "zip 以 PK\\x03\\x04 开头")

// 真写一个 zip 出去，看能不能被系统读回（System 有解压能力）
let out = root.appendingPathComponent("test.zip")
try? data.write(to: out)
check(FileManager.default.fileExists(atPath: out.path), "zip 落盘成功")

print("")
if failed.isEmpty {
    print("=== 全部通过（\(passed)/\(passed)）===")
} else {
    print("=== 失败 \(failed.count) 项 / 共 \(passed + failed.count) 项 ===")
    for f in failed { print("  · \(f)") }
}
