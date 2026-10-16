import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: GalaxyAppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("التطبيق") {
                    LabeledContent("الاسم", value: "Galaxy TV")
                    LabeledContent("الإصدار", value: "1.0.0")
                    LabeledContent("المنصة", value: platformName)
                }

                Section("الخادم") {
                    LabeledContent("نوع الاتصال", value: "HTTP (غير آمن)")
                    Text("يستخدم اتصال HTTP غير آمن. يُنصح بتحديث إلى HTTPS.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Button("تغيير عنوان الخادم") {
                        // إضافة وظيفة تغيير الخادم
                    }
                }

                Section("ال accounts") {
                    Button("Sign out", role: .destructive) {
                        model.signOut()
                        dismiss()
                    }
                }

                Section("الدعم") {
                    Link("الصفحة الرئيسية", destination: URL(string: "https://galaxytv.example.com")!)
                    Link("الmmas", destination: URL(string: "https://galaxytv.example.com/support")!)
                }
            }
            .navigationTitle("الإعدادات")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("إغلاق") {
                        dismiss()
                    }
                }
            }
        }
    }

    private var platformName: String {
        #if os(tvOS)
        return "Apple TV"
        #else
        return "iPhone"
        #endif
    }
}

#Preview {
    NavigationStack {
        SettingsView(model: GalaxyAppModel())
    }
}