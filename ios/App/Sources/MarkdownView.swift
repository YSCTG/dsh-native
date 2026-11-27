import SwiftUI

/// 轻量 Markdown 渲染：代码块 + 行内格式。
///
/// 为什么不用第三方库：这个 App 追求零依赖，而模型输出里 90% 的格式需求就三类——
/// 代码块、行内代码/粗体、标题。用 AttributedString 的 markdown 解析处理行内，
/// 代码块单独切出来用等宽字体 + 底色渲染。
struct MarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(Self.blocks(of: text).enumerated()), id: \.offset) { _, block in
                switch block {
                case .code(let language, let code):
                    CodeBlock(language: language, code: code)
                case .heading(let level, let content):
                    Text(content)
                        .font(level <= 2 ? .headline : .subheadline.weight(.semibold))
                        .padding(.top, 2)
                case .text(let content):
                    Text(Self.inline(content))
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    enum Block {
        case text(String)
        case heading(Int, String)
        case code(String, String)
    }

    /// 按 ``` 围栏切块；围栏外的行再按标题识别。
    static func blocks(of raw: String) -> [Block] {
        var out: [Block] = []
        var buffer: [String] = []
        var inCode = false
        var codeLang = ""
        var codeLines: [String] = []

        func flushText() {
            let chunk = buffer.joined(separator: "\n")
            buffer.removeAll()
            guard !chunk.trimmingCharacters(in: .whitespaces).isEmpty else { return }

            // 拆标题行与正文
            var paragraph: [String] = []
            func flushParagraph() {
                let p = paragraph.joined(separator: "\n")
                paragraph.removeAll()
                if !p.trimmingCharacters(in: .whitespaces).isEmpty {
                    out.append(.text(p))
                }
            }
            for line in chunk.components(separatedBy: "\n") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.hasPrefix("#") {
                    let hashes = trimmed.prefix { $0 == "#" }.count
                    if hashes <= 4, trimmed.count > hashes {
                        flushParagraph()
                        let content = trimmed.drop { $0 == "#" }
                            .trimmingCharacters(in: .whitespaces)
                        out.append(.heading(hashes, content))
                        continue
                    }
                }
                paragraph.append(line)
            }
            flushParagraph()
        }

        for line in raw.components(separatedBy: "\n") {
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                if inCode {
                    out.append(.code(codeLang, codeLines.joined(separator: "\n")))
                    codeLines.removeAll()
                    inCode = false
                    codeLang = ""
                } else {
                    flushText()
                    inCode = true
                    codeLang = String(line.trimmingCharacters(in: .whitespaces).dropFirst(3))
                        .trimmingCharacters(in: .whitespaces)
                }
                continue
            }
            if inCode { codeLines.append(line) } else { buffer.append(line) }
        }
        if inCode, !codeLines.isEmpty {
            out.append(.code(codeLang, codeLines.joined(separator: "\n")))
        }
        flushText()
        return out
    }

    /// 行内 markdown（粗体/斜体/行内代码/链接）。
    static func inline(_ s: String) -> AttributedString {
        if let a = try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) {
            return a
        }
        return AttributedString(s)
    }
}

private struct CodeBlock: View {
    let language: String
    let code: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !language.isEmpty {
                Text(language)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
