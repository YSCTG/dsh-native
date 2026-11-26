import Foundation

// 最后一块未验的：glob / grep（模式匹配与搜索）
struct Ctx {
    let real: URL
    init(_ root: URL) { real = root.resolvingSymlinksInPath().standardizedFileURL }
    static func norm(_ u: URL) -> String {
        var p = u.standardizedFileURL.path
        while p.count > 1 && p.hasSuffix("/") { p.removeLast() }
        return p
    }
    func path(_ rel: String) throws -> URL {
        let t = rel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { throw NSError(domain: "空", code: 1) }
        let c = t.hasPrefix("/") ? URL(fileURLWithPath: t).resolvingSymlinksInPath()
                                 : real.appendingPathComponent(t)
        let r = Ctx.norm(real), g = Ctx.norm(c)
        guard g == r || g.hasPrefix(r + "/") else { throw NSError(domain: "越界", code: 2) }
        return c
    }
    func rel(_ u: URL) -> String {
        let p = Ctx.norm(u), r = Ctx.norm(real)
        return p.hasPrefix(r + "/") ? String(p.dropFirst(r.count + 1)) : p
    }
}

func rx(_ g: String) -> String {
    var out = "^"
    var i = g.startIndex
    while i < g.endIndex {
        let ch = g[i]
        if ch == "*" {
            let n = g.index(after: i)
            if n < g.endIndex, g[n] == "*" {
                out += ".*"; i = g.index(after: n)
                if i < g.endIndex, g[i] == "/" { i = g.index(after: i) }
                continue
            }
            out += "[^/]*"
        } else if ch == "?" { out += "[^/]" }
        else if "\\.[]{}()+-^$|".contains(ch) { out += "\\" + String(ch) }
        else { out.append(ch) }
        i = g.index(after: i)
    }
    return out + "$"
}

func glob(_ c: Ctx, _ pattern: String) throws -> [String] {
    let re = try NSRegularExpression(pattern: rx(pattern))
    var out: [String] = []
    let w = FileManager.default.enumerator(at: c.real,
                                           includingPropertiesForKeys: [.isDirectoryKey])
    while let item = w?.nextObject() as? URL {
        let d = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
        if d { continue }
        let n = c.rel(item)
        if re.firstMatch(in: n, range: NSRange(n.startIndex..<n.endIndex, in: n)) != nil {
            out.append(n)
        }
    }
    return out.sorted()
}

func grep(_ c: Ctx, _ pattern: String) throws -> [String] {
    let re = try NSRegularExpression(pattern: pattern)
    var out: [String] = []
    for n in try glob(c, "**") {
        guard let data = try? Data(contentsOf: try c.path(n)) else { continue }
        let text = String(decoding: data, as: UTF8.self)
        for (i, l) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let s = String(l)
            if re.firstMatch(in: s, range: NSRange(s.startIndex..<s.endIndex, in: s)) != nil {
                out.append("\(n):\(i + 1): \(s.prefix(40))")
            }
        }
    }
    return out
}

var pass = 0
var bad: [String] = []
func ck(_ ok: Bool, _ name: String) {
    if ok { pass += 1; print("OK \(name)") } else { bad.append(name); print("NG \(name)") }
}

let dir = FileManager.default.temporaryDirectory.appendingPathComponent("g-\(UUID())")
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
let c = Ctx(dir)
func put(_ p: String, _ s: String) {
    let u = (try? c.path(p)) ?? dir
    try? FileManager.default.createDirectory(at: u.deletingLastPathComponent(),
                                             withIntermediateDirectories: true)
    try? Data(s.utf8).write(to: u)
}

put("a.md", "root file")
put("sub/b.md", "deep #1")
put("sub/deep/c.txt", "AAA\nBBB")

ck((try? glob(c, "**/*.md"))?.count == 2, "glob ** 跨目录命中 2 个")
ck((try? glob(c, "*.md"))?.contains("a.md") == true, "glob * 命中根层")
ck((try? glob(c, "*.md"))?.contains("sub/b.md") == false, "glob * 不跨目录")
ck((try? glob(c, "sub/*.md"))?.contains("sub/b.md") == true, "glob 指定子目录")

ck((try? grep(c, "BBB"))?.first?.contains(":2:") == true, "grep 行号正确")
ck((try? grep(c, "AAA"))?.first?.contains("c.txt") == true, "grep 命中文件名")
ck((try? grep(c, "不存在的内容"))?.isEmpty == true, "grep 无匹配返回空")

print("---")
if bad.isEmpty { print("全部通过 \(pass)/\(pass)") } else { print("失败 \(bad) 项: \(bad)") }
