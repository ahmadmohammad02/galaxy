import UIKit

struct DeviceIdentity {
    // MAC Address - لا يمكن الوصول إليه على iOS/tvOS
    static let macAddress: String = ""

    // Serial Number - نستخدم identifierForVendor كـ Serial Number
    static var serialNumber: String {
        UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
    }

    // موديل الجهاز
    static var model: String {
        UIDevice.current.model
    }

    // إصدار النظام
    static var firmwareVersion: String {
        ProcessInfo.processInfo.operatingSystemVersionString
    }

    // الذاكرة الفعلية بالبايت
    static var totalMemory: UInt64 {
        ProcessInfo.processInfo.physicalMemory
    }

    // عدد المعالجات النشطة
    static var processorCount: Int {
        ProcessInfo.processInfo.processorCount
    }

    // ما إذا كان يجب استخدام Lite API
    static var shouldUseLite: Bool {
        let memoryGB = totalMemory / (1024 * 1024 * 1024)
        return memoryGB <= 2 || processorCount <= 2
    }
}

#Preview("DeviceIdentity") {
    VStack {
        Text("MAC: \(DeviceIdentity.macAddress)")
        Text("SN: \(DeviceIdentity.serialNumber)")
        Text("Model: \(DeviceIdentity.model)")
    }
}