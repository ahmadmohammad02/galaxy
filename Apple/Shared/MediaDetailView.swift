import SwiftUI
import AVKit

struct MediaDetailView: View {
    let media: GalaxyMedia
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let url = media.imageURL {
                    AsyncImage(url: url) { image in
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.gray.opacity(0.3))
                    }
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text(media.title)
                        .font(.largeTitle.bold())

                    if let streamURL = media.streamURL {
                        PlaybackControlsView(url: streamURL)
                            .padding(.top, 8)
                    }
                }
                .padding(.horizontal)

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("معلومات المحتوى")
                        .font(.headline)
                        .foregroundStyle(.secondary)

                    Text("النوع: \(media.streamURL != nil ? "فيديو مباشر" : "غير محدد")")
                    Text("الحالة: \(media.streamURL != nil ? "متاح" : "غير متاح")")
                }
                .padding(.horizontal)

                Spacer(minLength: 20)
            }
        }
        .navigationTitle("تفاصيل")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.gray)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        MediaDetailView(media: GalaxyMedia(
            id: "1",
            title: "فيلم تجريبي",
            imageURL: URL(string: "https://via.placeholder.com/500x750"),
            streamURL: URL(string: "http://example.com/stream.m3u8")
        ))
    }
}
