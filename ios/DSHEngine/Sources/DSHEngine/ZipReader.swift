import Foundation
import Compression

/// 最小 ZIP 读取器（支持 store 与 deflate）。
///
/// 为什么需要：docx / xlsx 都是 zip，条目通常用 deflate 压缩。
/// Foundation 在 iOS 上没有解压 API，`AppleArchive` 又不是 zip 格式，
/// 所以自己解析中央目录，再用系统 `Compression` 框架解 raw deflate。
///
/// 只做读取，不写入（写入见 `ZipWriter`）。
public struct ZipReader {

    public struct Entry {
        public let name: String
        public let method: UInt16      // 0 = store, 8 = deflate
        public let compressedSize: Int
        public let uncompressedSize: Int
        public let localOffset: Int
    }

    private let data: Data

    public init(data: Data) {
        self.data = data
    }

    // MARK: - 中央目录

    public func entries() -> [Entry] {
        guard let eocd = findEOCD() else { return [] }
        let count = int(eocd + 10, 2)
        var offset = int(eocd + 16, 4)
        var out: [Entry] = []

        for _ in 0..<count {
            guard offset + 46 <= data.count,
                  u32(offset) == 0x02014b50 else { break }
            let method = UInt16(truncatingIfNeeded: int(offset + 10, 2))
            let csize = int(offset + 20, 4)
            let usize = int(offset + 24, 4)
            let nameLen = int(offset + 28, 2)
            let extraLen = int(offset + 30, 2)
            let commentLen = int(offset + 32, 2)
            let localOff = int(offset + 42, 4)
            let nameStart = offset + 46
            guard nameStart + nameLen <= data.count else { break }
            let name = String(decoding: data[nameStart..<(nameStart + nameLen)], as: UTF8.self)
            out.append(Entry(name: name, method: method,
                             compressedSize: csize, uncompressedSize: usize,
                             localOffset: localOff))
            offset = nameStart + nameLen + extraLen + commentLen
        }
        return out
    }

    /// 按名字读一个条目（已解压）。
    public func read(_ name: String) -> Data? {
        guard let entry = entries().first(where: { $0.name == name }) else { return nil }
        return read(entry)
    }

    public func read(_ entry: Entry) -> Data? {
        let lo = entry.localOffset
        guard lo + 30 <= data.count, u32(lo) == 0x04034b50 else { return nil }
        let nameLen = int(lo + 26, 2)
        let extraLen = int(lo + 28, 2)
        let start = lo + 30 + nameLen + extraLen
        guard start + entry.compressedSize <= data.count else { return nil }
        let payload = data[start..<(start + entry.compressedSize)]

        if entry.method == 0 {
            return Data(payload)
        }
        if entry.method == 8 {
            return Self.inflate(Data(payload), expectedSize: entry.uncompressedSize)
        }
        return nil
    }

    // MARK: - deflate

    /// raw deflate 解压（不含 zlib/gzip 头）。
    static func inflate(_ input: Data, expectedSize: Int) -> Data? {
        guard !input.isEmpty else { return Data() }
        var capacity = max(expectedSize, 1024)
        // 尺寸未知时逐步放大，最多试到 32 MB
        for _ in 0..<8 {
            var out = Data(count: capacity)
            let written: Int = out.withUnsafeMutableBytes { raw in
                guard let dst = raw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return input.withUnsafeBytes { srcRaw -> Int in
                    guard let src = srcRaw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                    return compression_decode_buffer(dst, capacity, src, input.count,
                                                     nil, COMPRESSION_ZLIB)
                }
            }
            if written > 0 && (written < capacity || expectedSize > 0) {
                out.removeSubrange(written..<out.count)
                return out
            }
            capacity *= 4
            if capacity > 32 * 1024 * 1024 { break }
        }
        return nil
    }

    // MARK: - 字节读取

    private func u32(_ at: Int) -> UInt32 {
        guard at + 4 <= data.count else { return 0 }
        return UInt32(data[at])
            | (UInt32(data[at + 1]) << 8)
            | (UInt32(data[at + 2]) << 16)
            | (UInt32(data[at + 3]) << 24)
    }

    private func int(_ at: Int, _ bytes: Int) -> Int {
        var value = 0
        for i in 0..<bytes {
            guard at + i < data.count else { return value }
            value |= Int(data[at + i]) << (8 * i)
        }
        return value
    }

    /// 从尾部找中央目录结束记录（注释最长 64 KB）。
    private func findEOCD() -> Int? {
        guard data.count >= 22 else { return nil }
        let start = max(0, data.count - 22 - 65535)
        var i = data.count - 22
        while i >= start {
            if u32(i) == 0x06054b50 { return i }
            i -= 1
        }
        return nil
    }
}
