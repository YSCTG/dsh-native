import Foundation

struct Ctx {
    let root: URL
    static func norm(_ u: URL) -> String {
        var p = u.resolvingSymlinksInPath().standardizedFileURL.path
        while p.count > 1 && p.hasSuffix("/") { p.removeLast() }
        return p
    }
    func path(_ rel: String) throws -> URL {
        let t = rel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { throw NSError(domain: "空路径", code: 1) }
        let c = t.hasPrefix("/") ? URL(fileURLWithPath: t) : root.appendingPathComponent(t)
        let r = Ctx.norm(root), g = Ctx.norm(c)
        guard g == r || g.hasPrefix(r + "/") else { throw NSError(domain: "越界 \(rel)", code: 2) }
        return c
    }
}

func put(_ c: Ctx, _ p: String, _ s: String) throws {
    let u = try c.path(p)
    try FileManager.default.createDirectory(at: u.deletingLastPathComponent(),
                                            withIntermediateDirectories: true)
    try Data(s.utf8).write(to: u)
}

func get(_ c: Ctx, _ p: String, from: Int = 1, n: Int = 400) throws -> String {
    let s = String(decoding: try Data(contentsOf: try c.path(p)), as: UTF8.self)
    let all = s.split(separator: "\n", omittingEmptySubsequences: false)
    guard from <= all.count else { return "" }
    let end = min(all.count, from - 1 + n)
    return ((from - 1)..<end).map { String(format: "%d\t%@", $0 + 1, String(all[$0])) }
        .joined(separator: "\n")
}

func rep(_ c: Ctx, _ p: String, _ a: String, _ b: String) throws {
    let u = try c.path(p)
    let s = String(decoding: try Data(contentsOf: u), as: UTF8.self)
    let hits = s.components(separatedBy: a).count - 1
    if hits == 0 { throw NSError(domain: "找不到原文", code: 3) }
    if hits > 1 { throw NSError(domain: "原文不唯一 \(hits)", code: 4) }
    try Data(s.replacingOccurrences(of: a, with: b).utf8).write(to: u)
}

var pass = 0
var bad: [String] = []
func ck(_ ok: Bool, _ name: String) {
    if ok { pass += 1; print("OK \(name)") } else { bad.append(name); print("NG \(name)") }
}

let dir = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID())")
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
let c = Ctx(root: dir)

do { _ = try c.path("../x"); ck(false, "拒绝越界") } catch { ck(true, "拒绝越界") }
ck((try? c.path("a/b.txt")) != nil, "区内放行")
ck(!Ctx.norm(dir).hasSuffix("/"), "路径归一")

try? put(c, "p/a.txt", "l1\nl2\nl3")
ck((try? get(c, "p/a.txt"))?.contains("1\tl1") == true, "写入读回")
ck((try? get(c, "p/a.txt", from: 2, n: 1))?.contains("2\tl2") == true, "分页")

try? rep(c, "p/a.txt", "l2", "L2")
ck((try? get(c, "p/a.txt"))?.contains("L2") == true, "替换成功")
do { try rep(c, "p/a.txt", "l", "x"); ck(false, "不唯一拒绝") } catch { ck(true, "不唯一拒绝") }
do { try rep(c, "p/a.txt", "没有", "x"); ck(false, "缺失报错") } catch { ck(true, "缺失报错") }

print("---")
if bad.isEmpty { print("全部通过 \(pass)/\(pass)") } else { print("失败 \(bad.count) 项: \(bad)") }
