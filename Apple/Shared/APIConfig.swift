import Foundation

/// إعدادات API النهائية لمشروع Galaxy TV (Apple)
/// متطابقة مع Android وتستخدم نفس مفتاح XOR
struct GalaxyAPIConfig {
    static let serverHost = "http://localhost:8080" // Docker فعال
    static let apiPath = "/iptv/V6/API-V6.php"
    static let fullEndpoint = "\(serverHost)\(apiPath)"
    
    static let xorEncryptionKey = "y]w(7r+W3e>@wEiM0a9"
    
    /// إذا كنت تستخدم Docker server المحلي، قم بتعديل هذا العنوان
    /// static let serverHost = "http://localhost:8080"
    
    /// نوع الاتصال (HTTP كما هو مطلوب)
    static let connectionType = "cleartext HTTP"
    
    /// إعدادات Lite API (لأجهزة ذات ذاكرة ≤ 2GB أو معالجات ≤ 2)
    static let liteMemoryLimitGB = 2
    static let liteProcessorLimit = 2
}

/// إعدادات فقط — GalaxyAPIClient موجود في APIClient.swift
// لا تضف واجهة مكررة هنا لتجنب تضارب الاسم
