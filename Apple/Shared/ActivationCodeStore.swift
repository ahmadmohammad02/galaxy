import Foundation
import Security

enum ActivationCodeStore {
    private static let service = "com.galaxytv.activation"
    private static let account = "active-code"

    static func read() throws -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess,
              let data = result as? Data,
              let code = String(data: data, encoding: .utf8) else {
            throw ActivationCodeStoreError.keychain(status)
        }
        return code
    }

    static func save(_ code: String) throws {
        let data = Data(code.utf8)
        let update = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw ActivationCodeStoreError.keychain(updateStatus)
        }

        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(query as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw ActivationCodeStoreError.keychain(addStatus)
        }
    }

    static func clear() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw ActivationCodeStoreError.keychain(status)
        }
    }

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

private enum ActivationCodeStoreError: LocalizedError {
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .keychain(let status):
            return "Unable to access the activation code in Keychain (status \(status))."
        }
    }
}
