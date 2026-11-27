import XCTest

/// 真正"用手指点"的测试：跑在模拟器里，能点击、输入、滑动。
///
/// 之前所有验证都是静态截图——画面对不对看得出来，点了会不会崩看不出来。
/// 这一组补的就是那块。
final class UIBasics: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ args: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = args
        app.launch()
        return app
    }

    /// 主界面能起来，三个关键控件都在
    func testAppLaunches() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["open-sessions"].waitForExistence(timeout: 20),
                      "会话按钮没出现")
        XCTAssertTrue(app.buttons["open-tasks"].exists, "任务按钮没出现")
        XCTAssertTrue(app.textFields["composer"].exists || app.textViews["composer"].exists,
                      "输入框没出现")
    }

    /// 打开会话列表 → 点"新建会话" → 列表消失（真实点击链路）
    func testOpenSessionsAndCreate() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["open-sessions"].waitForExistence(timeout: 20))

        app.buttons["open-sessions"].tap()

        let newSession = app.buttons["new-session"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 5), "会话列表没弹出")

        newSession.tap()

        // 新建之后列表应该关掉，回到聊天页
        XCTAssertFalse(newSession.waitForExistence(timeout: 3),
                       "点新建后列表没关闭")
        XCTAssertTrue(app.buttons["open-sessions"].exists, "没回到聊天页")
    }

    /// 打开任务面板 → 关闭
    func testOpenTasksAndClose() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["open-tasks"].waitForExistence(timeout: 20))
        app.buttons["open-tasks"].tap()

        let close = app.buttons["关闭"]
        XCTAssertTrue(close.waitForExistence(timeout: 5), "任务面板没弹出")
        close.tap()
        XCTAssertFalse(close.waitForExistence(timeout: 3), "任务面板没关掉")
    }

    /// 切到文件页签，看到"文件"标题
    func testSwitchToFilesTab() throws {
        let app = launch()
        let filesTab = app.tabBars.buttons["文件"]
        XCTAssertTrue(filesTab.waitForExistence(timeout: 20), "文件页签没出现")
        filesTab.tap()
        XCTAssertTrue(app.navigationBars["文件"].waitForExistence(timeout: 5),
                      "文件页没打开")
    }

    /// 在输入框打字，发送按钮从禁用变可用（键盘输入链路）
    func testTypingEnablesSend() throws {
        let app = launch()
        let field = app.textFields["composer"].exists
            ? app.textFields["composer"] : app.textViews["composer"]
        XCTAssertTrue(field.waitForExistence(timeout: 20))

        let send = app.buttons["send"]
        XCTAssertFalse(send.isEnabled, "空输入时发送按钮不该可用")

        field.tap()
        field.typeText("你好")
        XCTAssertTrue(send.isEnabled, "输入文字后发送按钮应该可用")
    }

    /// 任务面板里勾选一条待办（数据由 -tasks 演示准备）
    func testToggleTodo() throws {
        let app = launch(["-tasks"])
        let first = app.buttons["todo-0"]
        guard first.waitForExistence(timeout: 20) else {
            XCTFail("待办列表没出现")
            return
        }
        // 勾选前是 2 项未完成，勾掉一项后应变成 3 项（示范数据里有 1 项已完成）
        let before = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH '待办（'")).firstMatch
        XCTAssertTrue(before.exists, "看不到待办计数")
        first.tap()
        let after = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH '待办（'")).firstMatch
        XCTAssertTrue(after.exists)
        XCTAssertNotEqual(before.label, after.label, "勾选后计数没变，点击可能没生效")
    }

    /// 文件页能进子目录（如果工作区里有 apps/）
    func testBrowseFiles() throws {
        let app = launch(["-files"])
        XCTAssertTrue(app.navigationBars["文件"].waitForExistence(timeout: 20))
        let apps = app.buttons["file-apps"]
        if apps.exists {
            apps.tap()
            XCTAssertTrue(app.navigationBars["apps"].waitForExistence(timeout: 5),
                          "没进到 apps 目录")
        }
    }
}
