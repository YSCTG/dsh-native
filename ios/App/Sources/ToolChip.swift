import SwiftUI

/// 工具调用卡片：一行芯片 + 折叠的返回内容。
///
/// 之前工具返回就是一行灰色小字，分不清是"调了什么"还是"返回了什么"。
struct ToolChip: View {
    let raw: String

    @State private var expanded = false

    /// ChatModel 里工具行的格式是 "· fs_write" 或 "· fs_write\n返回内容"。
    private var name: String {
        let firstLine = raw.components(separatedBy: "\n").first ?? raw
        return firstLine
            .replacingOccurrences(of: "·", with: "")
            .trimmingCharacters(in: .whitespaces)
    }

    private var detail: String {
        let parts = raw.components(separatedBy: "\n")
        guard parts.count > 1 else { return "" }
        return parts.dropFirst().joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                guard !detail.isEmpty else { return }
                withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "wrench.and.screwdriver.fill")
                        .font(.caption2)
                    Text(name)
                        .font(.system(.caption, design: .monospaced))
                        .lineLimit(1)
                    if !detail.isEmpty {
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption2)
                    }
                }
                .foregroundStyle(.secondary)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(Color.secondary.opacity(0.12))
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)

            if expanded, !detail.isEmpty {
                Text(detail)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
    }
}
