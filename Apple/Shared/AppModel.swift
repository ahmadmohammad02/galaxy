import Combine
import Foundation

enum GalaxySection: String, CaseIterable, Identifiable {
    case live = "Live TV"
    case sports = "Sports"
    case movies = "Movies"
    case series = "Series"
    case kids = "Kids"
    case ramadan = "Ramadan"

    var id: String { rawValue }
}

@MainActor
final class GalaxyAppModel: ObservableObject {
    @Published private(set) var activeCode: String?
    @Published private(set) var playerUserAgent = GalaxyAPIClient().defaultPlayerUserAgent
    @Published private(set) var activationNotice: String?
    @Published private(set) var isRestoring = true
    @Published private(set) var categories: [GalaxyCategory] = []
    @Published private(set) var packages: [GalaxyPackage] = []
    @Published private(set) var media: [GalaxyMedia] = []
    @Published var errorMessage: String?
    @Published var isLoading = false

    private let api = GalaxyAPIClient()
    private var didRestore = false

    func restoreSession() {
        guard !didRestore else { return }
        didRestore = true
        do {
            let savedCode = try ActivationCodeStore.read()
            if let savedCode, Self.isValidCode(savedCode) {
                activeCode = savedCode
                playerUserAgent = UserDefaults.standard.string(forKey: "galaxy.player-user-agent")
                    ?? api.defaultPlayerUserAgent
            } else if savedCode != nil {
                try ActivationCodeStore.clear()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isRestoring = false
    }

    func activate(code rawCode: String) async {
        let code = rawCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidCode(code) else {
            errorMessage = "Enter a valid 12-digit activation code."
            return
        }
        await perform {
            let response = try await api.activate(code: code)
            guard response.status == 100 else {
                if !response.osd.isEmpty {
                    activationNotice = response.osd
                }
                throw ActivationError.rejected(response.message.isEmpty ? "Activation failed. Check your code." : response.message)
            }
            try ActivationCodeStore.save(code)
            playerUserAgent = response.userAgent.isEmpty ? api.defaultPlayerUserAgent : response.userAgent
            UserDefaults.standard.set(playerUserAgent, forKey: "galaxy.player-user-agent")
            let notice = [response.message, response.expires, response.osd]
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            activationNotice = notice.isEmpty ? nil : notice
            activeCode = code
        }
    }

    func loadCategories(for section: GalaxySection) async {
        guard section != .live && section != .sports, let activeCode else { return }
        await perform {
            categories = try await api.categories(for: section, code: activeCode)
        }
    }

    func loadPackages(for section: GalaxySection) async {
        guard let activeCode else { return }
        await perform {
            packages = try await api.packages(code: activeCode, sportsOnly: section == .sports)
        }
    }

    func loadMedia(for category: GalaxyCategory) async {
        guard let activeCode else { return }
        await perform {
            media = try await api.media(for: category, code: activeCode)
        }
    }

    func loadChannels(for package: GalaxyPackage) async {
        guard let activeCode else { return }
        await perform {
            media = try await api.channels(package: package, code: activeCode)
        }
    }

    func signOut() {
        do {
            try ActivationCodeStore.clear()
            activeCode = nil
            playerUserAgent = api.defaultPlayerUserAgent
            activationNotice = nil
            UserDefaults.standard.removeObject(forKey: "galaxy.player-user-agent")
            categories = []
            packages = []
            media = []
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func dismissActivationNotice() {
        activationNotice = nil
    }

    private func perform(_ operation: @MainActor () async throws -> Void) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await operation()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private static func isValidCode(_ code: String) -> Bool {
        code.count == 12 && code.allSatisfy(\.isNumber)
    }

}

private enum ActivationError: LocalizedError {
    case rejected(String)

    var errorDescription: String? {
        switch self {
        case .rejected(let message): return message
        }
    }
}
