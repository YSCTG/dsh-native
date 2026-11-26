import Foundation

/// 工作流 / 子任务调度。
///
/// 对齐 Android 版的 `workflow` `subagent` `team_*` 那一类能力。它们的共同点是：
/// **同时跑多个模型调用，再按顺序把结果收回来**。这一层和「调模型」解耦——
/// 具体怎么跑由注入的 `runner` 决定，所以能拿假的 runner 做离线测试。
///
/// 三条必须成立的语义（也是最容易写错的）：
/// 1. 并发上限：在飞的子任务不超过 `concurrency`
/// 2. 顺序保持：结果按输入顺序返回，而不是完成顺序
/// 3. 错误隔离：一个子任务失败不影响其它；也可以选择遇错即停
public actor WorkflowRunner {

    public struct Item: Sendable {
        public let id: String
        public let prompt: String
        public init(id: String, prompt: String) {
            self.id = id
            self.prompt = prompt
        }
    }

    public enum Outcome: Sendable {
        case ok(String)
        case failed(String)

        public var text: String {
            switch self {
            case .ok(let s): return s
            case .failed(let s): return s
            }
        }

        public var isFailure: Bool {
            if case .failed = self { return true }
            return false
        }
    }

    public struct Result: Sendable {
        public let id: String
        public let outcome: Outcome
        public init(id: String, outcome: Outcome) {
            self.id = id
            self.outcome = outcome
        }
    }

    /// 跑一个子任务。可以是真模型调用，也可以是测试里的假实现。
    public typealias Runner = @Sendable (Item) async -> Outcome

    private let concurrency: Int
    private let runner: Runner
    private var maxInFlight = 0
    private var inFlight = 0

    public init(concurrency: Int = 3, runner: @escaping Runner) {
        self.concurrency = max(1, concurrency)
        self.runner = runner
    }

    /// 观察到的最大并发数（测试用：证明上限真的生效，而不是顺序执行）。
    public func observedMaxInFlight() -> Int { maxInFlight }

    /// 跑一批。`stopOnError` 为真时，出错后不再启动新的子任务（已在飞的会跑完）。
    public func run(_ items: [Item], stopOnError: Bool = false) async -> [Result] {
        maxInFlight = 0
        inFlight = 0
        var outcomes = [Outcome?](repeating: nil, count: items.count)
        let limit = concurrency
        let r = runner

        await withTaskGroup(of: (Int, Outcome).self) { group in
            var next = 0
            var stopped = false

            // 先把窗口填满
            let head = min(limit, items.count)
            for i in 0..<head {
                let item = items[i]
                group.addTask {
                    await self.trackStart()
                    let outcome = await r(item)
                    await self.trackEnd()
                    return (i, outcome)
                }
            }
            next = head

            // 完成一个补一个：这样既守住上限，又不是死板的分批
            while let (index, outcome) = await group.next() {
                outcomes[index] = outcome
                if stopOnError, outcome.isFailure {
                    stopped = true
                }
                if stopped {
                    while next < items.count {
                        outcomes[next] = .failed("跳过：前面的子任务失败后停止")
                        next += 1
                    }
                } else if next < items.count {
                    let i = next
                    next += 1
                    let item = items[i]
                    group.addTask {
                        await self.trackStart()
                        let outcome = await r(item)
                        await self.trackEnd()
                        return (i, outcome)
                    }
                }
            }
        }

        var results: [Result] = []
        for (i, o) in outcomes.enumerated() {
            if let o {
                results.append(Result(id: items[i].id, outcome: o))
            }
        }
        return results
    }

    private func trackStart() {
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
    }

    private func trackEnd() {
        inFlight = max(0, inFlight - 1)
    }
}
