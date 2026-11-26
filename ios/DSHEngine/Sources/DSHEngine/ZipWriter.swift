import Foundation

/// 最小 ZIP 写入器（store 方式，不压缩）。
///
/// 为什么需要：docx / xlsx / pptx 都是 zip 包，而 Foundation 在 iOS 上**没有写 zip 的 API**
/// （只有解压的方向）。`Archive`/`AppleArchive` 不通用。所以自己拼字节。
///
/// 为什么用 store 不用 deflate：deflate 要走 Compression 框架、还要处理窗口和 flush，
/// 出错点很多；而这些文档的 XML 本身只有几 KB，不压缩也就大几 KB。
/// 正确性优先——先能打开，再谈体积。
///
/// 格式细节（都是小端）：
///   本地文件头 0x04034b50 → 数据 → … → 中央目录 0x02014b50 → 目录结束 0x06054b50
public struct ZipWriter {

    private struct Entry {
        let name: String
        let crc: UInt32
        let size: Int
        let offset: Int
    }

    private var body = Data()
    private var entries: [Entry] = []

    public init() {}

    /// 追加一个文件。文件名用 `/` 分隔（zip 规范）。
    public mutating func add(_ name: String, _ bytes: Data) {
        let crc = ZipWriter.crc32(bytes)
        let offset = body.count
        let nameBytes = Data(name.utf8)

        // 本地文件头
        body.append(le32(0x04034b50))
        body.append(le16(20))            // version needed
        body.append(le16(0))             // flags
        body.append(le16(0))             // method: 0 = store
        body.append(le16(0))             // mod time
        body.append(le16(0x21))          // mod date 1980-01-01
        body.append(le32(crc))
        body.append(le32(UInt32(bytes.count)))
        body.append(le32(UInt32(bytes.count)))
        body.append(le16(UInt16(nameBytes.count)))
        body.append(le16(0))             // extra len
        body.append(nameBytes)
        body.append(bytes)

        entries.append(Entry(name: name, crc: crc, size: bytes.count, offset: offset))
    }

    public mutating func add(_ name: String, _ text: String) {
        add(name, Data(text.utf8))
    }

    /// 收尾，返回完整 zip 字节。
    public mutating func finish() -> Data {
        let cdStart = body.count
        for e in entries {
            let nameBytes = Data(e.name.utf8)
            body.append(le32(0x02014b50))
            body.append(le16(20))        // version made by
            body.append(le16(20))        // version needed
            body.append(le16(0))         // flags
            body.append(le16(0))         // method
            body.append(le16(0))         // time
            body.append(le16(0x21))      // date
            body.append(le32(e.crc))
            body.append(le32(UInt32(e.size)))
            body.append(le32(UInt32(e.size)))
            body.append(le16(UInt16(nameBytes.count)))
            body.append(le16(0))         // extra
            body.append(le16(0))         // comment
            body.append(le16(0))         // disk
            body.append(le16(0))         // internal attrs
            body.append(le32(0))         // external attrs
            body.append(le32(UInt32(e.offset)))
            body.append(nameBytes)
        }
        let cdSize = body.count - cdStart

        body.append(le32(0x06054b50))
        body.append(le16(0))             // disk
        body.append(le16(0))             // disk with CD
        body.append(le16(UInt16(entries.count)))
        body.append(le16(UInt16(entries.count)))
        body.append(le32(UInt32(cdSize)))
        body.append(le32(UInt32(cdStart)))
        body.append(le16(0))             // comment len
        return body
    }

    // MARK: - 小工具

    private func le16(_ v: UInt16) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 2)
    }

    private func le32(_ v: UInt32) -> Data {
        var x = v.littleEndian
        return Data(bytes: &x, count: 4)
    }

    /// 标准 CRC-32（多项式 0xEDB88320）。zip 的每个条目都要它。
    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                if crc & 1 == 1 {
                    crc = (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >>= 1
                }
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}
