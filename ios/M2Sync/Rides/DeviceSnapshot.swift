import Foundation

/// Что велокомп рассказал о себе при последнем подключении — для карточки устройства.
struct DeviceSnapshot: Codable, Equatable {
    var name: String
    var firmware: String?
    var battery: Int?
    var freeKB: Int?
    var totalKB: Int?
    var seenAt: Date

    /// Ответ на запрос места: `15752/16384` — свободно и всего, КБ.
    static func parseMemory(_ text: String?) -> (free: Int, total: Int)? {
        guard let parts = text?.split(separator: "/"), parts.count == 2,
              let free = Int(parts[0].trimmingCharacters(in: .whitespaces)),
              let total = Int(parts[1].trimmingCharacters(in: .whitespaces))
        else { return nil }
        return (free, total)
    }
}

/// Прогресс скачивания текущего файла.
struct SyncProgress: Equatable {
    let fileName: String
    let index: Int
    let count: Int
    var received: Int
    let size: Int

    var fraction: Double {
        size > 0 ? min(1, Double(received) / Double(size)) : 0
    }
}
