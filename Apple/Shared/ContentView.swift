import AVKit
import SwiftUI

struct ContentView: View {
    @StateObject private var sessionWatcher = ActivationSessionWatcher.shared
    @StateObject private var appModel = GalaxyAppModel()
    @State private var showSplash = true

    var body: some View {
        Group {
            if showSplash {
                SplashScreen()
            } else if sessionWatcher.activeCode == nil {
                ActivationView(model: appModel)
            } else {
                HomeView(model: appModel)
            }
        }
        .onAppear {
            Task {
                await sessionWatcher.restoreSession()
                sessionWatcher.startWatching()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
                withAnimation { showSplash = false }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .splashScreenFinished)) { _ in
            withAnimation { showSplash = false }
        }
        .alert("تنبيه", isPresented: $sessionWatcher.showExpirationNotice) {
            Button("تم") {
                sessionWatcher.showExpirationNotice = false
                sessionWatcher.signOut()
            }
        } message: {
            Text("رمز التفعيل الخاص بك قد انتهت صلاحيته. يرجى تسجيل الدخول مرة أخرى.")
        }
        .environmentObject(sessionWatcher)
    }
}

private struct ActivationView: View {
    @ObservedObject var model: GalaxyAppModel
    @State private var code = ""

    var body: some View {
        VStack(spacing: 24) {
            Text("GALAXY TV")
                .font(.largeTitle.bold())
                .foregroundStyle(Color.cyan)
            Text("Enter your 12-digit activation code")
                .font(.title3)
            TextField("Activation code", text: Binding(
                get: { code },
                set: { code = String($0.filter(\.isNumber).prefix(12)) }
            ))
            .textFieldStyle(.roundedBorder)
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .frame(maxWidth: 420)
            Button {
                Task { await model.activate(code: code) }
            } label: {
                if model.isLoading {
                    ProgressView()
                } else {
                    Text("Activate")
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.isLoading || code.count != 12)
            if let error = model.errorMessage {
                Text(error).foregroundStyle(.red).multilineTextAlignment(.center)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GalaxyStyle.background)
    }
}

private struct HomeView: View {
    @ObservedObject var model: GalaxyAppModel
    private let columns = [GridItem(.adaptive(minimum: 180), spacing: 20)]

    var body: some View {
        VStack(spacing: 24) {
            Text("Galaxy TV")
                .font(.largeTitle.bold())
                .foregroundStyle(Color.cyan)
            LazyVGrid(columns: columns, spacing: 20) {
                ForEach(GalaxySection.allCases) { section in
                    NavigationLink {
                        SectionView(model: model, section: section)
                    } label: {
                        Text(section.rawValue)
                            .font(.title2.bold())
                            .frame(maxWidth: .infinity, minHeight: 112)
                            .background(GalaxyStyle.card, in: RoundedRectangle(cornerRadius: 18))
                    }
                    .buttonStyle(.plain)
                }
                NavigationLink {
                    SettingsView(model: model)
                } label: {
                    Text("Settings")
                        .font(.title2.bold())
                        .frame(maxWidth: .infinity, minHeight: 112)
                        .background(GalaxyStyle.card, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GalaxyStyle.background)
    }
}

private struct SectionView: View {
    @ObservedObject var model: GalaxyAppModel
    let section: GalaxySection

    var body: some View {
        Group {
            if section == .live || section == .sports {
                List(model.packages) { package in
                    NavigationLink {
                        ChannelListView(model: model, package: package)
                    } label: {
                        Text(package.name)
                    }
                }
                .task { await model.loadPackages(for: section) }
            } else {
                List(model.categories) { category in
                    NavigationLink {
                        MediaListView(model: model, section: section, category: category)
                    } label: {
                        Text(category.name)
                    }
                }
                .task { await model.loadCategories(for: section) }
            }
        }
        .navigationTitle(section.rawValue)
        .overlay {
            if model.isLoading { ProgressView() }
            else if let message = model.errorMessage {
                ContentUnavailableView {
                    Label(message, systemImage: "wifi.exclamationmark")
                }
            }
            else if section == .live || section == .sports, model.packages.isEmpty {
                ContentUnavailableView("No packages found", systemImage: "tv")
            } else if section != .live && section != .sports, model.categories.isEmpty {
                ContentUnavailableView("No categories found", systemImage: "rectangle.stack")
            }
        }
    }
}

private struct ChannelListView: View {
    @ObservedObject var model: GalaxyAppModel
    let package: GalaxyPackage

    var body: some View {
        MediaRows(model: model, title: package.name)
            .task { await model.loadChannels(for: package) }
    }
}

private struct MediaListView: View {
    @ObservedObject var model: GalaxyAppModel
    let section: GalaxySection
    let category: GalaxyCategory

    var body: some View {
        MediaRows(model: model, title: category.name)
            .task { await model.loadMedia(for: category) }
    }
}

private struct MediaRows: View {
    @ObservedObject var model: GalaxyAppModel
    let title: String

    var body: some View {
        List(model.media) { item in
            if let url = item.streamURL {
                NavigationLink {
                    PlaybackView(title: item.title, url: url, userAgent: model.playerUserAgent)
                } label: {
                    Text(item.title)
                }
            } else {
                Text(item.title)
            }
        }
        .navigationTitle(Text(title))
        .overlay {
            if model.isLoading { ProgressView() }
            else if let message = model.errorMessage {
                ContentUnavailableView {
                    Label(message, systemImage: "wifi.exclamationmark")
                }
            }
            else if model.media.isEmpty { ContentUnavailableView("No content found", systemImage: "play.rectangle") }
        }
    }
}

private struct PlaybackView: View {
    let title: String
    let url: URL
    let userAgent: String
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: player)
            .navigationTitle(title)
            .task {
                let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPUserAgentKey": userAgent])
                let newPlayer = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                player = newPlayer
                newPlayer.play()
            }
            .onDisappear { player?.pause() }
    }
}

private struct SettingsView: View {
    @ObservedObject var model: GalaxyAppModel

    var body: some View {
        Form {
            LabeledContent("Application", value: "Galaxy TV")
            LabeledContent("Platform", value: platformName)
            Button("Sign out", role: .destructive) { model.signOut() }
            if let error = model.errorMessage {
                Text(error).foregroundStyle(.red)
            }
        }
        .navigationTitle("Settings")
    }

    private var platformName: String {
        #if os(tvOS)
        return "Apple TV"
        #else
        return "iPhone"
        #endif
    }
}

private enum GalaxyStyle {
    static let background = Color(red: 2 / 255, green: 8 / 255, blue: 23 / 255)
    static let card = Color(red: 17 / 255, green: 24 / 255, blue: 39 / 255)
}
