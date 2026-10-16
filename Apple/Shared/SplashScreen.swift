import SwiftUI
import AVKit

struct SplashScreen: View {
    @State private var showContent = false
    @State private var videoPlayer: AVPlayer?
    @State private var showLogo = true
    
    let splashDuration: TimeInterval = 4.0
    
    var body: some View {
        ZStack {
            Color(red: 2 / 255, green: 8 / 255, blue: 23 / 255)
                .ignoresSafeArea()
            
            if let player = videoPlayer {
                VideoPlayer(player: player)
                    .aspectRatio(contentMode: .fill)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .opacity(showLogo ? 0 : 0.5)
            }
            
            VStack {
                Image("splash_logo")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 200, height: 200)
                    .shadow(color: .cyan.opacity(0.5), radius: 20)
                Text("GALAXY TV")
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .foregroundStyle(.cyan)
                    .padding(.top, 16)
            }
            .opacity(showLogo ? 1 : 0)
            .transition(.opacity)
        }
        .onAppear {
            setupVideoPlayer()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                withAnimation(.easeInOut(duration: 1.0)) {
                    showLogo = false
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + splashDuration) {
                NotificationCenter.default.post(name: .splashScreenFinished, object: nil)
            }
        }
    }
    
    /// يتطلب إضافة Assets: logo_video.mp4 + splash_logo.png
    private func setupVideoPlayer() {
        guard let videoURL = Bundle.main.url(forResource: "logo_video", withExtension: "mp4") else { return }
        let player = AVPlayer(url: videoURL)
        player.isMuted = true
        player.repeatMode = .one
        self.videoPlayer = player
        player.play()
    }
}

extension Notification.Name {
    static let splashScreenFinished = Notification.Name("splashScreenFinished")
}