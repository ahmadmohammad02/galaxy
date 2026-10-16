import Foundation
import UserNotifications

class ActivationSessionWatcher: ObservableObject {
    static let shared = ActivationSessionWatcher()

    @Published var activeCode: String?
    @Published var expirationTime: Date?
    @Published var showExpirationNotice = false
    @Published var isRestoring = true

    private let keychain = ActivationCodeStore.shared
    private let client = GalaxyAPIClient.shared
    private var timer: Timer?

    func startWatching() {
        scheduleNextCheck()
    }

    func stopWatching() {
        timer?.invalidate()
        timer = nil
    }

    private func scheduleNextCheck() {
        stopWatching()

        let now = Date()
        guard let exp = expirationTime, exp > now else {
            if let code = activeCode {
                Task { await client.activate(code: code) }
            }
            timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { _ in
                checkExpiration()
            }
            return
        }

        let interval = exp.timeIntervalSince(now)
        timer = Timer.scheduledTimer(withTimeInterval: min(interval, 60 * 60), repeats: false) { _ in
            checkExpiration()
        }
    }

    @MainActor
    private func checkExpiration() {
        let now = Date()
        guard let exp = expirationTime, exp <= now else { return }

        showExpirationNotice = true
        requestNotificationPermission()
        scheduleNotification()
        signOut()
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func scheduleNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Galaxy TV"
        content.body = "رمز التفعيل الخاص بك قد انتهت صلاحيته."
        content.sound = .default
        content.badge = NSNumber(value: 1)

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(identifier: "expired", content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request)
    }

    @MainActor
    func signOut() {
        do {
            try keychain.clear()
            activeCode = nil
            expirationTime = nil
            showExpirationNotice = false
        } catch {
            print("Error signing out: \(error.localizedDescription)")
        }
    }

    // استعادة الجلسة بعد تشغيل التطبيق
    func restoreSession() async {
        guard !isRestoring else { return }
        isRestoring = true

        do {
            let savedCode = try keychain.read()
            if let savedCode, isValidCode(savedCode) {
                activeCode = savedCode
                // يمكن جلب وقت انتهاء الصلاحية من التخزين المحلي أو من الخادم
                if let savedExpiry = UserDefaults.standard.object(forKey: "galaxy.expiry_date") as? Date {
                    expirationTime = savedExpiry
                } else {
                    // محاولة التحقق من الخادم
                    let resp = try await client.activate(code: savedCode)
                    if let exp = parseExpireToDate(resp.expires) {
                        expirationTime = exp
                        UserDefaults.standard.set(exp, forKey: "galaxy.expiry_date")
                    }
                }
            } else if savedCode != nil {
                try keychain.clear()
            }
        } catch {
            print("Error restoring session: \(error.localizedDescription)")
        }
        isRestoring = false
    }

    // مساعدة: التحقق من صحة الرمز (12 رقم)
    private func isValidCode(_ code: String) -> Bool {
        return code.count == 12 && code.allSatisfy(\$0.isNumber)
    }

    private func parseExpireToDate(_ expireText: String) -> Date? {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")

        if let date = formatter.date(from: expireText) {
            return date
        }

        // محاولة أخرى مع تاريخ فقط
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: expireText)
    }
}