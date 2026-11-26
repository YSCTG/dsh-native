import XCTest
@testable import DSHEngine

/// 会话存取的测试。
///
/// 这类测试的价值在"崩了以后还能不能读"——JSONL 的整个意义就是半行不坏全篇，
/// 所以专门有一条用例造一个半行文件。
final class SessionStoreTests: XCTestCase {

    private func tempRoot() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("dshstore-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testAppendAndLoad() throws {
        let store = SessionStore(root: try tempRoot())
        let s = try store.create()
        try store.append(.init(role: "user", content: "你好"), to: s.id)
        try store.append(.init(role: "assistant", content: "在"), to: s.id)

        let msgs = try store.load(s.id)
        XCTAssertEqual(msgs.count, 2)
        XCTAssertEqual(msgs[0].role, "user")
        XCTAssertEqual(msgs[1].content, "在")
    }

    func testHalfLineIsSkipped() throws {
        let root = try tempRoot()
        let store = SessionStore(root: root)
        let s = try store.create()
        try store.append(.init(role: "user", content: "第一条"), to: s.id)

        // 手工追加一行被截断的 JSON（模拟写到一半进程被杀）
        let file = root.appendingPathComponent("sessions/\(s.id).jsonl")
        let h = try FileHandle(forWritingTo: file)
        try h.seekToEnd()
        try h.write(contentsOf: Data(#"{"role":"assistant","cont"#.utf8))
        try h.close()

        let msgs = try store.load(s.id)
        XCTAssertEqual(msgs.count, 1, "半行应被跳过，不能因此丢掉前面的消息")
        XCTAssertEqual(msgs[0].content, "第一条")
    }

    func testCreateInsertsAtTopAndList() throws {
        let store = SessionStore(root: try tempRoot())
        let a = try store.create(title: "A")
        let b = try store.create(title: "B")
        let all = try store.list()
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all[0].id, b.id, "新会话应在最前")
        XCTAssertEqual(all[1].id, a.id)
    }

    func testTouchRenamesFromFirstUserText() throws {
        let store = SessionStore(root: try tempRoot())
        let s = try store.create()
        try store.touch(s.id, firstUserText: "帮我把这个 Excel 里的公式修好")
        let all = try store.list()
        XCTAssertEqual(all[0].title, "帮我把这个 Excel 里的公式修…")
    }

    func testTitleFrom() {
        XCTAssertEqual(SessionStore.titleFrom("  \n "), "新会话")
        XCTAssertEqual(SessionStore.titleFrom("短标题"), "短标题")
        let long = String(repeating: "字", count: 30)
        let t = SessionStore.titleFrom(long)
        XCTAssertTrue(t.hasSuffix("…"))
        XCTAssertEqual(t.count, 21)
    }

    func testDeleteRemovesBody() throws {
        let root = try tempRoot()
        let store = SessionStore(root: root)
        let s = try store.create()
        try store.append(.init(role: "user", content: "x"), to: s.id)
        try store.delete(s.id)
        XCTAssertTrue(try store.list().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: root.appendingPathComponent("sessions/\(s.id).jsonl").path))
    }

    func testIdIsFilesystemSafe() throws {
        let store = SessionStore(root: try tempRoot())
        // 就算外部塞了带 ../ 的 id，也不能写到目录外
        let s = try store.create()
        try store.append(.init(role: "user", content: "x"), to: "../evil")
        let msgs = try store.load(s.id)
        XCTAssertTrue(msgs.isEmpty, "别的 id 不应读到这个会话")
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: FileManager.default.temporaryDirectory.appendingPathComponent("evil.jsonl").path))
    }
}
