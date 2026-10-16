import Foundation
import UIKit

struct GalaxyCategory: Identifiable, Hashable {
    let id: String
    let apiID: String
    let name: String
    let source: GalaxyContentSource
}

enum GalaxyContentSource: String, Hashable {
    case movies
    case series
}

struct GalaxyPackage: Identifiable, Hashable {
    let id: String
    let name: String
    let channels: [GalaxyMedia]
    let usesLiteChannels: Bool

    init(id: String, name: String, channels: [GalaxyMedia] = [], usesLiteChannels: Bool = false) {
        self.id = id
        self.name = name
        self.channels = channels
        self.usesLiteChannels = usesLiteChannels
    }
}

struct GalaxyMedia: Identifiable, Hashable {
    let id: String
    let title: String
    let imageURL: URL?
    let streamURL: URL?
}

enum GalaxyAPIError: LocalizedError {
    case invalidResponse
    case serverStatus(Int)
    case invalidURL

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Server is down, please try again later."
        case .serverStatus(let status):
            return "The server returned HTTP \(status)."
        case .invalidURL:
            return "The server address is invalid."
        }
    }
}

struct GalaxyAPIClient {
    private let primaryEndpoint = "http://localhost:8080/iptv/V6/API-V6.php"

    private let backupEndpoint: String? = "http://localhost:8080/iptv/V6/API-V6.php"
    private let xorKey = Array("y]w(7r+W3e>@wEiM0a9".utf8)
    private let primaryDownUntilKey = "galaxy.primary-down-until"
    private let tenDays: TimeInterval = 10 * 24 * 60 * 60

    var defaultPlayerUserAgent: String {
        "Maghreb \(UIDevice.current.model) \(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    func activate(code: String) async throws -> (
        status: Int,
        message: String,
        expires: String,
        osd: String,
        userAgent: String
    ) {
        let data = try await request(mode: "active", code: code, extra: [
            "osd_test": "Hi this is test osd"
        ])
        let object = try jsonObject(from: data)
        return (
            object["status"] as? Int ?? -1,
            object["message"] as? String ?? "",
            object["expire"] as? String ?? "",
            object["osd"] as? String ?? "",
            object["user_agent"] as? String ?? ""
        )
    }

    func categories(for section: GalaxySection, code: String) async throws -> [GalaxyCategory] {
        let sources: [GalaxyContentSource] = section == .kids || section == .ramadan
            ? [.movies, .series]
            : [section == .series ? .series : .movies]
        var result: [GalaxyCategory] = []
        for source in sources {
            result.append(contentsOf: try await categories(from: source, section: section, code: code))
        }
        return result
    }

    private func categories(
        from source: GalaxyContentSource,
        section: GalaxySection,
        code: String
    ) async throws -> [GalaxyCategory] {
        let data = try await request(mode: source == .series ? "series_cat" : "movies_cat", code: code)
        guard let rows = try jsonObject(from: data) as? [[String: Any]] else { throw GalaxyAPIError.invalidResponse }
        return rows.flatMap { row -> [GalaxyCategory] in
            guard let id = row["cat_id"] as? String else { return [] }
            let parentName = row["cat_name"] as? String ?? ""
            let subcategories = row["sub_cats"] as? [[String: Any]] ?? []
            let values = subcategories.compactMap { sub -> GalaxyCategory? in
                guard let subID = sub["sub_id"] as? String else { return nil }
                let subName = sub["sub_name"] as? String ?? parentName
                guard section != .kids || Self.matchesKids("\(parentName) \(subName)") else { return nil }
                guard section != .ramadan || Self.matchesRamadan("\(parentName) \(subName)") else { return nil }
                return GalaxyCategory(id: "\(source.rawValue)-\(subID)", apiID: subID, name: subName, source: source)
            }
            if !values.isEmpty { return values }
            guard section != .kids || Self.matchesKids(parentName) else { return [] }
            guard section != .ramadan || Self.matchesRamadan(parentName) else { return [] }
            return [GalaxyCategory(id: "\(source.rawValue)-\(id)", apiID: id, name: parentName, source: source)]
        }
    }

    func packages(code: String, sportsOnly: Bool = false) async throws -> [GalaxyPackage] {
        do {
            let packages = try await packageList(code: code, sportsOnly: sportsOnly, useLite: false)
            if !packages.isEmpty { return packages }
        } catch {
            return try await packageList(code: code, sportsOnly: sportsOnly, useLite: true)
        }
        return try await packageList(code: code, sportsOnly: sportsOnly, useLite: true)
    }

    private func packageList(code: String, sportsOnly: Bool, useLite: Bool) async throws -> [GalaxyPackage] {
        let data = try await request(mode: useLite ? "lite_packages" : "packages", code: code)
        guard let rows = try jsonObject(from: data) as? [[String: Any]] else {
            throw GalaxyAPIError.invalidResponse
        }
        let keywords = ["sport", "football", "soccer", "bein", "espn", "dazn", "رياض"]
        return rows.compactMap { row in
            if !useLite, let packageType = row["pkg_type"] {
                let type = String(describing: packageType)
                guard type == "0" || type.caseInsensitiveCompare("live") == .orderedSame else { return nil }
            }
            let primaryIDKey = useLite ? "id" : "pkg_id"
            let alternateIDKey = useLite ? "pkg_id" : "id"
            let id = (row[primaryIDKey] as? String)
                ?? (row[primaryIDKey] as? NSNumber)?.stringValue
                ?? (row[alternateIDKey] as? String)
                ?? (row[alternateIDKey] as? NSNumber)?.stringValue
            guard let id else { return nil }
            let name = row["pkg_name"] as? String ?? ""
            guard !sportsOnly || keywords.contains(where: { name.localizedCaseInsensitiveContains($0) }) else {
                return nil
            }
            let channels = (row["pkg_channels"] as? [[String: Any]] ?? []).compactMap { channel -> GalaxyMedia? in
                guard let channelID = channel["stream_id"] else { return nil }
                return GalaxyMedia(
                    id: String(describing: channelID),
                    title: channel["stream_name"] as? String ?? "Untitled",
                    imageURL: URL(string: channel["stream_icon"] as? String ?? ""),
                    streamURL: URL(string: channel["stream_url"] as? String ?? "")
                )
            }
            return GalaxyPackage(id: id, name: name, channels: channels, usesLiteChannels: useLite)
        }
    }

    func media(for category: GalaxyCategory, code: String) async throws -> [GalaxyMedia] {
        try await media(from: category.source, categoryID: category.apiID, code: code)
    }

    private func media(from source: GalaxyContentSource, categoryID: String, code: String) async throws -> [GalaxyMedia] {
        let mode = source == .series ? "series_list" : "movies_list"
        let data = try await request(mode: mode, code: code, extra: ["catid": categoryID])
        guard let rows = try jsonObject(from: data) as? [[String: Any]] else {
            throw GalaxyAPIError.invalidResponse
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            let title = (row["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Untitled"
            return GalaxyMedia(
                id: id,
                title: title,
                imageURL: URL(string: (row["icon"] as? String) ?? ""),
                streamURL: URL(string: (row["stream_url"] as? String) ?? "")
            )
        }
    }

    func channels(package: GalaxyPackage, code: String) async throws -> [GalaxyMedia] {
        if !package.usesLiteChannels, !package.channels.isEmpty {
            return package.channels
        }
        let channels = try await liteChannels(packageID: package.id, code: code)
        if !channels.isEmpty || package.usesLiteChannels {
            if !channels.isEmpty { return channels }
            let packages = try await packageList(code: code, sportsOnly: false, useLite: false)
            return packages.first {
                $0.id == package.id || $0.name.localizedCaseInsensitiveCompare(package.name) == .orderedSame
            }?.channels ?? []
        }
        return channels
    }

    private func liteChannels(packageID: String, code: String) async throws -> [GalaxyMedia] {
        let data = try await request(mode: "lite_channels", code: code, extra: ["pkg_id": packageID])
        guard let rows = try jsonObject(from: data) as? [[String: Any]] else {
            throw GalaxyAPIError.invalidResponse
        }
        return rows.compactMap { row in
            guard let id = row["id"] else { return nil }
            return GalaxyMedia(
                id: String(describing: id),
                title: (row["stream_display_name"] as? String) ?? "Untitled",
                imageURL: URL(string: (row["stream_icon"] as? String) ?? ""),
                streamURL: URL(string: (row["stream_url"] as? String) ?? "")
            )
        }
    }

    private func request(mode: String, code: String, extra: [String: String] = [:]) async throws -> Data {
        let now = Date().timeIntervalSince1970
        let primaryIsDown = UserDefaults.standard.double(forKey: primaryDownUntilKey) > now
        let selectedEndpoint = primaryIsDown ? (backupEndpoint ?? primaryEndpoint) : primaryEndpoint
        var payload = [
            "mode": mode,
            "code": code,
            "mac": "",
            "sn": UIDevice.current.identifierForVendor?.uuidString ?? "",
            "chipid": "",
            "model": UIDevice.current.model,
            "firmware_ver": ProcessInfo.processInfo.operatingSystemVersionString
        ]
        payload.merge(extra) { _, newValue in newValue }

        let json = try JSONSerialization.data(withJSONObject: payload)
        guard let jsonText = String(data: json, encoding: .utf8) else { throw GalaxyAPIError.invalidResponse }
        let encrypted = Data(xor(Array(jsonText.utf8))).base64EncodedString()
        do {
            return try await performRequest(encrypted: encrypted, endpoint: selectedEndpoint)
        } catch {
            guard !primaryIsDown, selectedEndpoint == primaryEndpoint else { throw error }
            UserDefaults.standard.set(now + tenDays, forKey: primaryDownUntilKey)
            guard let backupEndpoint, backupEndpoint != primaryEndpoint else { throw error }
            return try await performRequest(encrypted: encrypted, endpoint: backupEndpoint)
        }
    }

    private func performRequest(encrypted: String, endpoint: String) async throws -> Data {
        guard let url = URL(string: endpoint) else { throw GalaxyAPIError.invalidURL }
        let boundary = "GalaxyTv-\(UUID().uuidString)"
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("gzip, deflate", forHTTPHeaderField: "Accept-Encoding")
        request.setValue(defaultPlayerUserAgent, forHTTPHeaderField: "User-Agent")
        request.httpBody = Data(
            "--\(boundary)\r\nContent-Disposition: form-data; name=\"json\"\r\n\r\n\(encrypted)\r\n--\(boundary)--\r\n".utf8
        )

        let (responseData, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw GalaxyAPIError.invalidResponse }
        guard (200...299).contains(response.statusCode) else {
            throw GalaxyAPIError.serverStatus(response.statusCode)
        }
        let responseText = try decryptResponse(responseData)
        return Data(responseText.utf8)
    }

    static func shouldUseLite(memoryBytes: UInt64, processorCount: Int) -> Bool {
        memoryBytes <= 2 * 1024 * 1024 * 1024 || processorCount <= 2
    }

    private func decryptResponse(_ data: Data) throws -> String {
        var candidates = [data]
        if let text = String(data: data, encoding: .ascii)?.trimmingCharacters(in: .whitespacesAndNewlines),
           let decoded = Data(base64Encoded: text) {
            candidates.insert(decoded, at: 0)
        }
        for candidate in candidates {
            let plain = Data(xor(Array(candidate)))
            guard let text = String(data: plain, encoding: .utf8) else { continue }
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.first == "{" || trimmed.first == "[" { return trimmed }
        }
        throw GalaxyAPIError.invalidResponse
    }

    private func jsonObject(from data: Data) throws -> Any {
        try JSONSerialization.jsonObject(with: data)
    }

    private func xor(_ bytes: [UInt8]) -> [UInt8] {
        bytes.enumerated().map { index, byte in byte ^ xorKey[index % xorKey.count] }
    }

    private static func matchesKids(_ text: String) -> Bool {
        ["kid", "child", "family", "animation", "cartoon", "أطفال", "اطفال", "عائلي", "كرتون", "رسوم"]
            .contains { text.localizedCaseInsensitiveContains($0) }
    }

    private static func matchesRamadan(_ text: String) -> Bool {
        let normalized = text.lowercased()
            .replacingOccurrences(of: "[\\u064B-\\u065F\\u0670\\u0640]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "[^\\p{L}\\p{N}]", with: "", options: .regularExpression)
        return ["ramadan", "ramadhan", "ramadaan", "ramazaan", "ramazan", "ramzan", "ramdhan", "رمضان", "رمظان", "رمضن"]
            .contains(where: { normalized.contains($0) })
    }
}
