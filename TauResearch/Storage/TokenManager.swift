// TokenManager.swift
// Mirrors TokenManager.kt — encrypted (Keychain) JWT storage with a legacy plist migration.

import Foundation
import Security

/// Keychain-backed token store. Equivalent to Android's `EncryptedSharedPreferences`,
/// with the same access/refresh token semantics and a one-time migration from the
/// legacy UserDefaults plain store.
final class TokenManager {

    static let shared = TokenManager()

    private let accessKey = "auth.access_token"
    private let refreshKey = "auth.refresh_token"
    private let legacyDefaults = UserDefaults(suiteName: "auth_prefs") ?? .standard
    private let service = "com.tau.research.auth"

    private init() {
        migrateFromLegacy()
    }

    // MARK: - Public API

    var accessToken: String? {
        get { read(key: accessKey) }
    }

    func save(accessToken: String) {
        write(key: accessKey, value: accessToken)
    }

    func save(refreshToken: String) {
        write(key: refreshKey, value: refreshToken)
    }

    func clear() {
        delete(key: accessKey)
        delete(key: refreshKey)
        legacyDefaults.removeObject(forKey: "access_token")
        legacyDefaults.removeObject(forKey: "refresh_token")
    }

    // MARK: - Migration

    private func migrateFromLegacy() {
        let oldAccess = legacyDefaults.string(forKey: "access_token")
        let oldRefresh = legacyDefaults.string(forKey: "refresh_token")
        if let a = oldAccess { write(key: accessKey, value: a) }
        if let r = oldRefresh { write(key: refreshKey, value: r) }
        if oldAccess != nil || oldRefresh != nil {
            legacyDefaults.removeObject(forKey: "access_token")
            legacyDefaults.removeObject(forKey: "refresh_token")
        }
    }

    // MARK: - Keychain primitives

    private func write(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let baseQuery: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key
        ]
        SecItemDelete(baseQuery as CFDictionary)
        var attrs = baseQuery
        attrs[kSecValueData] = data
        attrs[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attrs as CFDictionary, nil)
        if status != errSecSuccess {
            AppHealthMonitor.shared.reportIssue(
                .init(
                    key: "token_storage_keychain_write_failed",
                    message: "SecItemAdd failed with status \(status)",
                    severity: .high,
                    area: "auth.storage"
                )
            )
        }
    }

    private func read(key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(key: String) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}
