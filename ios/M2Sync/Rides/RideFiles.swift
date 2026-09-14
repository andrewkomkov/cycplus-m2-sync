import Foundation

/// Скачанные .fit лежат в Application Support/fit под своими именами с устройства.
struct RideFiles {
    let directory: URL

    enum FileError: LocalizedError, Equatable {
        case unsafeName(String)

        var errorDescription: String? {
            switch self {
            case .unsafeName(let name): String(localized: "Refusing an unsafe file name: \(name)")
            }
        }
    }

    static func standard() throws -> RideFiles {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return try RideFiles(directory: base.appendingPathComponent("fit", isDirectory: true))
    }

    init(directory: URL) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// Имя приходит с устройства, поэтому путь из него не собираем: только голое имя файла.
    func url(for name: String) throws -> URL {
        guard !name.isEmpty, !name.hasPrefix("."), !name.contains("/"), !name.contains("\\") else {
            throw FileError.unsafeName(name)
        }
        return directory.appendingPathComponent(name, isDirectory: false)
    }

    /// Качаем заново, если файла нет или размер не совпадает с filelist.txt:
    /// так подтягивается и поездка, которую велокомп успел дописать.
    func needsDownload(_ file: DeviceFile) throws -> Bool {
        let path = try url(for: file.name).path
        let attributes = try? FileManager.default.attributesOfItem(atPath: path)
        return (attributes?[.size] as? Int) != file.size
    }

    func save(_ data: Data, as name: String) throws {
        try data.write(to: url(for: name), options: .atomic)
    }

    func rideURLs() throws -> [URL] {
        try FileManager.default
            .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension.lowercased() == "fit" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
}
