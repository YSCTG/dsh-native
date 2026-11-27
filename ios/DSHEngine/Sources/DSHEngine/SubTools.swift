import Foundation

/// 子 agent 工具：subagent / workflow / loop。
///
/// 对齐安卓版同名工具。它们都通过 `ToolContext.sub` 跑**独立的**模型调用，
/// 所以：
///   - 引擎不关心网络怎么配（宿主注入）
///   - 测试里塞一个假的 runner 就能验证调度语义，不用真联调
public enum SubTools {

    public static func all() -> [any Tool] {
        [SubAgent(), WorkflowTool(), LoopTool()]
    }

    /// 取 runner，没接就给出明确错误（而不是静默返回空）。
    static func runner(_ ctx: ToolContext) throws -> @Sendable (String) async -> String {
        guard let sub = ctx.sub else {
            throw ToolError.denied("当前上下文没有接子模型（宿主未提供 runner）")
        }
        return sub
    }

    // MARK: - subagent

    struct SubAgent: Tool {
        let name = "subagent"
        let description = "派一个子 agent 独立跑一件事（独立上下文），只把结论带回来。"
        var parameters: JSONSchema {
            JSONSchema(properties: [.init("task", "string", "交给子 agent 的任务描述")],
                       required: ["task"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let task = args["task"]?.stringValue, !task.isEmpty else {
                throw ToolError.badArgument("缺少 task")
            }
            let sub = try SubTools.runner(ctx)
            ctx.audit("subagent", task)
            let answer = await sub(task)
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "（子 agent 没有返回内容）" : trimmed
        }
    }

    // MARK: - workflow

    struct WorkflowTool: Tool {
        let name = "workflow"
        let description = "并行跑多个子任务，按**输入顺序**返回结果。concurrency 控制并发数（默认 3）。"
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("tasks", "array", "任务描述数组"),
                .init("concurrency", "number", "并发上限，默认 3"),
            ], required: ["tasks"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard case .array(let arr) = args["tasks"] ?? .null, !arr.isEmpty else {
                throw ToolError.badArgument("tasks 需要非空数组")
            }
            let prompts = arr.compactMap { $0.stringValue }
            guard !prompts.isEmpty else { throw ToolError.badArgument("tasks 里没有字符串") }

            let sub = try SubTools.runner(ctx)
            let limit = max(1, args["concurrency"]?.intValue ?? 3)

            let runner = WorkflowRunner(concurrency: limit) { item in
                let text = await sub(item.prompt)
                return .ok(text.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            let items = prompts.enumerated().map {
                WorkflowRunner.Item(id: "t\($0.offset + 1)", prompt: $0.element)
            }
            let results = await runner.run(items)

            var out: [String] = []
            for (i, r) in results.enumerated() {
                let head = "── 任务 \(i + 1)：\(prompts.indices.contains(i) ? prompts[i].prefix(40) : "")"
                out.append("\(head)\n\(r.outcome.text)")
            }
            return out.joined(separator: "\n\n")
        }
    }

    // MARK: - loop

    struct LoopTool: Tool {
        let name = "loop"
        let description = """
        同一件事反复做，直到子 agent 的结论里出现「完成」或达到轮数上限。
        每轮会把上一轮的结论喂回去，避免原地打转。
        """
        var parameters: JSONSchema {
            JSONSchema(properties: [
                .init("task", "string", "要做的事"),
                .init("max_rounds", "number", "最多几轮，默认 3"),
            ], required: ["task"])
        }

        func run(_ args: JSONValue, in ctx: ToolContext) async throws -> String {
            guard let task = args["task"]?.stringValue, !task.isEmpty else {
                throw ToolError.badArgument("缺少 task")
            }
            let sub = try SubTools.runner(ctx)
            let maxRounds = max(1, min(6, args["max_rounds"]?.intValue ?? 3))

            var history: [String] = []
            for round in 1...maxRounds {
                var prompt = task
                if !history.isEmpty {
                    prompt += "\n\n这是前几轮的进展，接着做，别重复：\n"
                        + history.enumerated().map { "第\($0.offset + 1)轮：\($0.element.prefix(300))" }
                            .joined(separator: "\n")
                }
                let answer = await sub(prompt).trimmingCharacters(in: .whitespacesAndNewlines)
                history.append(answer)
                if answer.contains("完成") {
                    return "第 \(round) 轮完成：\n\(answer)"
                }
            }
            return "\(maxRounds) 轮都没看到「完成」，最后的进展：\n"
                + (history.last ?? "（无）")
        }
    }
}

/// 用真实客户端跑一次**不带工具**的独立调用（宿主给 `ToolContext.sub` 用）。
public enum ClientRun {

    public static func once(client: DeepSeekClient,
                            prompt: String,
                            system: String? = nil,
                            maxSeconds: Double = 120) async -> String {
        var messages: [[String: Any]] = []
        if let system, !system.isEmpty {
            messages.append(["role": "system", "content": system])
        }
        messages.append(["role": "user", "content": prompt])

        // 用一个 actor 收流式增量，避免在 @Sendable 闭包里改外部变量
        let box = TextBox()
        do {
            try await client.stream(messages: messages, tools: []) { ev in
                if case .delta(let d) = ev { box.append(d) }
            }
            return box.value
        } catch {
            return "错误：\(error)"
        }
    }

    private final class TextBox: @unchecked Sendable {
        private var text = ""
        private let lock = NSLock()
        func append(_ s: String) {
            lock.lock(); text += s; lock.unlock()
        }
        var value: String {
            lock.lock(); defer { lock.unlock() }; return text
        }
    }
}
