import Foundation
import Security

/// API key 的存储：优先 Keychain，拿不到再退回 UserDefaults。
///
/// 为什么改：之前 key 存在 UserDefaults 里——那是明文 plist，iTunes/iCloud 备份里也带着。
/// Keychain 会被系统加密，这是 iOS 上存密钥的常规做法。
///
/// 迁移：读的时候先看 Keychain，没有再看 UserDefaults；
/// 一旦从旧位置读到，`migrateIfNeeded` 会把它搬进 Keychain 并删掉旧值。
public enum SecretStore {

    private static let service = "com.dshnative.secrets"
    private static let fallbackPrefix = "dsh.secret."

    /// 当前实际用的后端。未签名的构建（比如 CI 里 CODE_SIGNING_ALLOWED=NO）
    /// 拿不到 Keychain，会自动退回 UserDefaults，并在界面上如实说明。
    public enum Backend: String, Sendable {
        case keychain
        case userDefaults
    }

    /// 探一次：能写进去就是 keychain，否则 userDefaults。
    public private(set) static var detectedBackend: Backend?

    public static var backend: Backend {
        if let detectedBackend { return detectedBackend }
        let probeKey = "probe"
        let probeValue = "1"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: probeKey,
        ]
        var insert = query
        insert[kSecValueData as String] = Data(probeValue.utf8)
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let ok = SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
                  || SecItemUpdate(query as CFDictionary,
                                   [kSecValueData as String: Data(probeValue.utf8)] as CFDictionary) == errSecSuccess
        SecItemDelete(query as CFDictionary)
        let backend: Backend = ok ? .keychain : .userDefaults
        detectedBackend = backend
        return backend
    }

    public enum StoreError: Error, CustomStringConvertible {
        case status(OSStatus)
        public var description: String {
            switch self {
            case .status(let s): return "Keychain 错误 \(s)"
            }
        }
    }

    /// 写入（已存在则覆盖）。
    @discardableResult
    public static func set(_ value: String, forKey key: String) -> Bool {
        if backend == .userDefaults {
            UserDefaults.standard.set(value, forKey: fallbackPrefix + key)
            return true
        }
        guard let data = value.data(using: .utf8) else { return false }

        // 先试着更新，不存在再新增
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let attrs: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if updateStatus == errSecSuccess { return true }

        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    /// 读取。
    public static func get(_ key: String) -> String? {
        if backend == .userDefaults {
            return UserDefaults.standard.string(forKey: fallbackPrefix + key)
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    /// 删除。
    @discardableResult
    public static func delete(_ key: String) -> Bool {
        if backend == .userDefaults {
            UserDefaults.standard.removeObject(forKey: fallbackPrefix + key)
            return true
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// 把 UserDefaults 里的旧值搬进 Keychain（搬完删掉旧的）。
    /// 返回是否发生了迁移。
    @discardableResult
    public static func migrateIfNeeded(key: String, legacyDefaultsKey: String) -> Bool {
        let defaults = UserDefaults.standard
        guard let legacy = defaults.string(forKey: legacyDefaultsKey), !legacy.isEmpty else {
            return false
        }
        if get(key) == nil {
            _ = set(legacy, forKey: key)
        }
        defaults.removeObject(forKey: legacyDefaultsKey)
        return true
    }
}
