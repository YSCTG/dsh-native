import XCTest
@testable import DSHEngine

/// 引擎单测：不联网、不碰用户目录，全部用临时目录与纯函数。
///
/// 这些用例的作用和 Android 版的 `Tests.java` 一样：把"安静出错"的地方钉住——
/// 例如路径逃逸（`../..`）、工具参数解析、文本统计的口径。
final class EngineTests: XCTestCase {

    private func tempContext() throws -> ToolContext {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("dshengine-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return ToolContext(workspace: dir)
    }

    // MARK: 路径沙箱

    func testResolveRejectsEscape() throws {
        let ctx = try tempContext()
        XCTAssertThrowsError(try ctx.resolve("../etc/passwd")) { err in
            XCTAssertTrue("\(err)".contains("越界"), "应该报越界，实际：\(err)")
        }
        XCTAssertThrowsError(try ctx.resolve("/etc/passwd"))
    }

    func testResolveAcceptsInsidePath() throws {
        let ctx = try tempContext()
        let url = try ctx.resolve("sub/a.txt")
        XCTAssertTrue(url.path.hasPrefix(ctx.workspace.standardizedFileURL.path))
    }

    // MARK: 工具参数

    func testParseArguments() {
        let v = AgentLoop.parseArguments(#"{"path":"a.txt","n":3,"ok":true}"#)
        XCTAssertEqual(v["path"]?.stringValue, "a.txt")
        XCTAssertEqual(v["n"]?.intValue, 3)
        XCTAssertEqual(v["ok"]?.boolValue, true)
    }

    func testParseArgumentsBrokenJSONDoesNotCrash() {
        let v = AgentLoop.parseArguments("{not json")
        if case .object(let d) = v { XCTAssertTrue(d.isEmpty) } else { XCTFail("应退化为空对象") }
    }

    // MARK: 文本统计

    func testTextStats() {
        XCTAssertEqual(BuiltinTools.TextStats.summarize("a b\nc"), "字符 5 · 行 2 · 词 3")
        XCTAssertEqual(BuiltinTools.TextStats.summarize(""), "字符 0 · 行 0 · 词 0")
    }

    // MARK: 文件原语

    func testWriteThenRead() async throws {
        let ctx = try tempContext()
        let write = BuiltinTools.WriteFile()
        _ = try await write.run(.object(["path": .string("d/x.txt"),
                                          "content": .string("hello 世界")]), in: ctx)
        let read = BuiltinTools.ReadFile()
        let text = try await read.run(.object(["path": .string("d/x.txt")]), in: ctx)
        XCTAssertEqual(text, "hello 世界")
    }

    func testListFiles() async throws {
        let ctx = try tempContext()
        _ = try await BuiltinTools.WriteFile()
            .run(.object(["path": .string("a.txt"), "content": .string("x")]), in: ctx)
        let listed = try await BuiltinTools.ListFiles()
            .run(.object(["path": .string(".")]), in: ctx)
        XCTAssertTrue(listed.contains("a.txt"))
    }

    // MARK: schema

    func testSchemaJSONShape() {
        let schema = JSONSchema(properties: [.init("path", "string", "路径")], required: ["path"])
        let obj = schema.jsonObject()
        XCTAssertEqual(obj["type"] as? String, "object")
        let props = obj["properties"] as? [String: Any]
        XCTAssertNotNil(props?["path"])
        XCTAssertEqual(obj["required"] as? [String], ["path"])
    }
}
