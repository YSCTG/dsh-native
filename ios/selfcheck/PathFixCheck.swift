import Foundation

// 诊断 + 验证：iOS 上"根能解析、候选路径不能解析"导致前缀对不上
struct Ctx {
    let realRoot: URL
    init(_ root: URL) {
        realRoot = root.resolvingSymlinksInPath().standardizedFileURL
    }
    static func norm(_ u: URL) -> String {
        var p = u.standardizedFileURL.path
        while p.count > 1 && p.hasSuffix("/") { p.removeLast() }
        return p
    }
    func path(_ rel: String) throws -> URL {
        let t = rel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { throw NSError(domain: "空路径", code: 1) }
        let c = t.hasPrefix("/") ? URL(fileURLWithPath: t).resolvingSymlinksInPath()
                                 : realRoot.appendingPathComponent(t)
        let r = Ctx.norm(realRoot), g = Ctx.norm(c)
        guard g == r || g.hasPrefix(r + "/") else {
            throw NSError(domain: "越界 \(rel)：根=\(r) 目标=\(g)", code: 2)
        }
        return c
    }
}

var pass = 0
var bad: [String] = []
func ck(_ ok: Bool, _ name: String, _ why: String = "") {
    if ok { pass += 1; print("OK \(name)") }
    else { bad.append(name); print("NG \(name) \(why)") }
}

let tmp = FileManager.default.temporaryDirectory
let dir = tmp.appendingPathComponent("t-\(UUID().uuidString)")
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

// 关键诊断：这三行的差异就是上一轮失败的原因
print("tmp 原始   :", tmp.path)
print("tmp 解析后 :", tmp.resolvingSymlinksInPath().path)
print("dir 解析后 :", dir.resolvingSymlinksInPath().path)
print("候选(不存在):", dir.appendingPathComponent("a/b.txt").resolvingSymlinksInPath().path)
print("---")

let c = Ctx(dir)
do { _ = try c.path("../x"); ck(false, "拒绝越界") } catch { ck(true, "拒绝越界") }
ck((try? c.path("a/b.txt")) != nil, "区内放行")
ck((try? c.path("a/b.txt"))?.path.hasPrefix("/") == true, "候选路径可用")

// 写入 / 读回
let f = try! c.path("p/a.txt")
try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(),
                                         withIntermediateDirectories: true)
try? Data("l1\nl2\nl3".utf8).write(to: f)
let back = String(decoding: (try? Data(contentsOf: f)) ?? Data(), as: UTF8.self)
ck(back.contains("l2"), "写入读回")

print("---")
if bad.isEmpty { print("全部通过 \(pass)/\(pass)") }
else { print("失败 \(bad.count) 项: \(bad)") }
