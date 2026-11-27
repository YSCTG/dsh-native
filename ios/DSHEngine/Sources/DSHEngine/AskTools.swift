import Foundation

/// 「向用户提问」的等待机制。
///
/// iOS 上没有安卓那种阻塞式对话框可以随手弹，但可以让 agent **真的等**：
///   1. 工具调用 `AskBroker.ask(question)` → 挂起
///   2. 界面轮询 `pendingQuestion()` → 弹出输入框
///   3. 用户答完 → `answer(text)` → 挂起处被唤醒
///
/// 这样做的好处：工具语义和安卓版一致（调用方拿到的是用户的回答，不是"已发送"）。
public actor AskBroker {

    public static let shared = AskBroker()

    private var question: String?
    private var continuation: CheckedContinuation<String, Never>?

    public init() {}

    /// 挂起直到用户回答。同一时间只支持一个问题（多问题会串，先保持简单）。
    public func ask(_ q: String) async -> String {
        // 上一个没结束就先返回它，避免覆盖导致调用方永久挂起
        if continuation != nil {
            return "（上一个问题还没回答，请先回答它）"
        }
        question = q
        return await withCheckedContinuation { cont in
            continuation = cont
        }
    }

    /// 界面轮询：有没有待回答的问题。
    public func pendingQuestion() -> String? { question }

    /// 用户提交答案。
    public func answer(_ text: String) {
        guard let cont = continuation else { return }
        continuation = nil
        question = nil
        cont.resume(returning: text)
    }

    /// 放弃当前问题（例如界面被关掉）。
    public func cancel() {
        guard let cont = continuation else { return }
        continuation = nil
        question = nil
        cont.resume(returning: "（用户没有回答）")
    }
}

/// 提问 / 计划 两个工具。
public enum AskTools {

    public static func all() -> [any Tool] {
        [AskUser(), PlanPresent()]
    }

    struct AskUser: Tool {
        let name = "ask_user"
        let description = "问用户一个问题，**等**用户回答后拿到答案再继续。适合需要澄清或确认时。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("question", "string", "要问用户的话"),
            ], required: ["question"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let q = args["question"]?.stringValue, !q.isEmpty else {
                throw ToolError.badArgument("缺少 question")
            }
            let answer = await AskBroker.shared.ask(q)
            return answer.isEmpty ? "（用户没有输入内容）" : "用户回答：\(answer)"
        }
    }

    struct PlanPresent: Tool {
        let name = "plan_present"
        let description = "把执行计划亮出来（存到工作区 plan.md 并返回），让用户能看到你要做什么。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("steps", "array", "计划步骤（字符串数组）"),
            ], required: ["steps"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard case .array(let arr) = args["steps"] ?? .null, !arr.isEmpty else {
                throw ToolError.badArgument("steps 需要非空数组")
            }
            let steps = arr.compactMap { $0.stringValue }
            guard !steps.isEmpty else { throw ToolError.badArgument("steps 里没有字符串") }

            var md = "# 执行计划\n\n"
            for (i, s) in steps.enumerated() {
                md += "\(i + 1). \(s)\n"
            }
            let url = ctx.workspace.appendingPathComponent("plan.md")
            try? Data(md.utf8).write(to: url)
            return "计划已写入 plan.md（\(steps.count) 步）\n" + md
        }
    }
}
