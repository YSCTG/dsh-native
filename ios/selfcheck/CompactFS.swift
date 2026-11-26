import Foundation

enum ToolErr: Error { case bad(String), failed(String) }

struct Ctx {
    let root: URL
    static func norm(_ u: URL) -> String {
        var p = u.resolvingSymlinksInPath().standardizedFileURL.path
        while p.count > 1 && p.hasSuffix("/") { p.removeLast() }
        return p
    }
    func path(_ rel: String) throws -> URL {
        let t = rel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { throw ToolErr.bad("空路径") }
        let c = t.hasPrefix("/") ? URL(fileURLWithPath: t) : root.appendingPathComponent(t)
        let r = Ctx.norm(root), g = Ctx.norm(c)
        guard g == r || g.hasPrefix(r + "/") else { throw ToolErr.bad("越界 \(rel)") }
        return c
    }
    func rel(_ u: URL) -> String {
        let p = Ctx.norm(u), r = Ctx.norm(root)
        return p.hasPrefix(r + "/") ? String(p.dropFirst(r.count + 1)) : p
    }
}

func write(_ c: Ctx, _ p: String, _ text: String) throws {
    let u = try c.path(p)
    try FileManager.default.createDirectory(at: u.deletingLastPathComponent(),
                                            withIntermediateDirectories: true)
    try Data(text.utf8).write(to: u)
}

func read(_ c: Ctx, _ p: String, offset: Int = 1, limit: Int = 400) throws -> String {
    let text = String(decoding: try Data(contentsOf: try c.path(p)), as: UTF8.self)
    let all = text.split(separator: "\n", omittingEmptySubsequences: false)
    let end = min(all.count, offset - 1 + limit)
    guard offset <= all.count else { return "" }
    return ((offset - 1)..<end).map { String(format: "%d\t%@", $0 + 1, String(all[$0])) }
        .joined(separator: "\n")
}

func edit(_ c: Ctx, _ p: String, _ old: String, _ new: String) throws -> Int {
    let u = try c.path(p)
    let text = String(decoding: try Data(contentsOf: u), as: UTF8.self)
    let hits = text.components(separatedBy: old).count - 1
    if hits == 0 { throw ToolErr.failed("找不到原文") }
    if hits > 1 { throw ToolErr.bad("原文出现 \(hits) 次") }
    try Data(text.replacingOccurrences(of: old, with: new).utf8).write(to: u)
    return hits
}

func globRegex(_ g: String) -> String {
    var out = "^"
    var i = g.startIndex
    while i < g.endIndex {
        let ch = g[i]
        if ch == "*" {
            let n = g.index(after: i)
            if n < g.endIndex, g[n] == "*" {
                out += ".*"
                i = g.index(after: n)
                if i < g.endIndex, g[i] == "/" { i = g.index(after: i) }
                continue
            }
            out += "[^/]*"
        } else if ch == "?" {
            out += "[^/]"
        } else if "\\.[]{}()+-^$|".contains(ch) {
            out += "\\" + String(ch)
        } else {
            out.append(ch)
        }
        i = g.index(after: i)
    }
    return out + "$"
}

func glob(_ c: Ctx, _ pattern: String) throws -> [String] {
    let re = try NSRegularExpression(pattern: globRegex(pattern))
    var out: [String] = []
    let walker = FileManager.default.enumerator(at: c.root,
                                                includingPropertiesForKeys: [.isDirectoryKey])
    while let item = walker?.nextObject() as? URL {
        let d = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
        if d { continue }
        let name = c.rel(item)
        if re.firstMatch(in: name, range: NSRange(name.startIndex..<name.endIndex, in: name)) != nil {
            out.append(name)
        }
    }
    return out.sorted()
}

func grep(_ c: Ctx, _ pattern: String) throws -> [String] {
    let re = try NSRegularExpression(pattern: pattern)
    var out: [String] = []
    for name in try glob(c, "**") {
        guard let data = try? Data(contentsOf: try c.path(name)) else { continue }
        let text = String(decoding: data, as: UTF8.self)
        for (i, line) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let s = String(line)
            if re.firstMatch(in: s, range: NSRange(s.startIndex..<s.endIndex, in: s)) != nil {
                out.append("\(name):\(i + 1): \(s.prefix(60))")
            }
        }
    }
    return out
}

// ===== 断言 =====
var pass = 0
var bad: [String] = []
func ck(_ ok: Bool, _ name: String) {
    if ok { pass += 1; print("  OK \(name)") } else { bad.append(name); print("  NG \(name)") }
}

let dir = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString)")
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
let c = Ctx(root: dir)

print("root:", Ctx.norm(dir))

do { _ = try c.path("../x"); ck(false, "拒绝 ../") } catch { ck(true, "拒绝 ../") }
ck((try? c.path("a/b.txt")) != nil, "区内放行")
ck(Ctx.norm(dir).hasPrefix("/") && !Ctx.norm(dir).hasSuffix("/"), "路径归一")

try? write(c, "p/a.txt", "l1\nl2\nl3")
ck((try? read(c, "p/a.txt"))?.contains("1\tl1") == true, "写入后带行号读回")
ck((try? read(c, "p/a.txt", offset: 2, limit: 1))?.contains("2\tl2") == true, "分页")

ck((try? edit(c, "p/a.txt", "l2", "L2")) == 1, "编辑唯一原文")
do { _ = try edit(c, "p/a.txt", "l", "x"); ck(false, "唯一性拒绝") } catch { ck(true, "唯一性拒绝") }
do { _ = try edit(c, "p/a.txt", "没有这段", "x"); ck(false, "缺失报错") } catch { ck(true, "缺失报错") }

try? write(c, "p/s/b.md", "# md")
ck((try? glob(c, "**/*.md"))?.contains("p/s/b.md") == true, "glob ** 跨目录")
ck((try? glob(c, "*.md"))?.contains("p/s/b.md") == false, "glob * 不跨目录")
ck((try? grep(c, "L2"))?.first?.contains(":2:") == true, "grep 行号")

print("")
if bad.isEmpty { print("=== 全部通过 \(pass)/\(pass) ===") }
else { print("=== 失败 \(bad.count)/\(pass + bad.count) ==="); bad.forEach { print("  - \($0)") } }
