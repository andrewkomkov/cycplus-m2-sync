import CoreTransferable
import Foundation
import UniformTypeIdentifiers

extension UTType {
    /// Garmin FIT. Объявлен в Info.plist, чтобы «Файлы», Strava и остальные узнавали файл.
    static let fit = UTType(importedAs: "com.garmin.fit", conformingTo: .data)
}

/// Поездка как файл для «Поделиться»: копия .fit с читаемым именем, как в Android-версии —
/// `2026-07-24_10-30_40.99km_cycplus-m2.fit`.
struct RideExport: Transferable {
    let source: URL
    let fileName: String

    init(summary: RideSummary, files: RideFiles, timeZone: TimeZone = .current) throws {
        source = try files.url(for: summary.fileName)
        fileName = Self.fileName(start: summary.start, distanceMeters: summary.distanceMeters, timeZone: timeZone)
    }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .fit) { export in
            SentTransferredFile(try export.staged())
        }
        .suggestedFileName { $0.fileName }
    }

    /// Время старта в часовом поясе телефона и дистанция с двумя знаками через точку.
    static func fileName(start: Date, distanceMeters: Double, timeZone: TimeZone) -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        let formatter = DateFormatter()
        formatter.locale = posix
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd_HH-mm"
        let kilometres = String(format: "%.2f", locale: posix, distanceMeters / 1000)
        return "\(formatter.string(from: start))_\(kilometres)km_cycplus-m2.fit"
    }

    /// Копия под экспортным именем: получатель видит имя файла, а не `20260724103005.fit`.
    /// Готовая копия того же размера переиспользуется.
    func staged(
        in directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
    ) throws -> URL {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent(fileName, isDirectory: false)
        // Размер — через FileManager: URL.resourceValues кэширует значения в самом URL,
        // и дописанная велокомпом поездка выглядела бы прежней.
        let sourceSize = try fileManager.attributesOfItem(atPath: source.path)[.size] as? Int
        let existingSize = (try? fileManager.attributesOfItem(atPath: target.path))?[.size] as? Int
        if let existingSize, existingSize == sourceSize {
            return target
        }
        if fileManager.fileExists(atPath: target.path) {
            try fileManager.removeItem(at: target)
        }
        try fileManager.copyItem(at: source, to: target)
        return target
    }
}
