import SwiftUI
import AVKit

struct PlaybackControlsView: View {
    let url: URL
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: AVPlayer(url: url))
            .aspectRatio(contentMode: .fit)
            .frame(height: 300)
            .cornerRadius(12)
            .shadow(color: .cyan.opacity(0.3), radius: 8)
    }
}
