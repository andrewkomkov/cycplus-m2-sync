import Foundation

/// Сводки поездок на диске. Разбор .fit небыстрый — в отладочной сборке секунды на двадцать
/// поездок, — а скачанный файл не меняется. Ключ — имя, размер и профиль калорий: дописанную
/// велокомпом поездку или поездку после смены веса разберём заново.
struct RideSummaryCache {
    /// Поднять, если меняется смысл полей RideSummary без изменения их набора.
    static let version = 2

    struct Entry: Codable, Equatable {
        let size: Int
        let profileKey: String
        let summary: RideSummary
    }

    private struct Stored: Codable {
        let version: Int
        let entries: [String: Entry]
    }

    let url: URL

    func load() -> [String: Entry] {
        guard let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode(Stored.self, from: data),
              stored.version == Self.version
        else { return [:] }
        return stored.entries
    }

    func save(_ entries: [String: Entry]) throws {
        let data = try JSONEncoder().encode(Stored(version: Self.version, entries: entries))
        try data.write(to: url, options: .atomic)
    }

    /// Сводки для файлов: из кэша, если имя, размер и профиль совпали, иначе через `parse`.
    /// Файлы, которых больше нет, из кэша выпадают; неразобранные туда не попадают.
    func refresh(
        urls: [URL],
        profileKey: String,
        parse: (URL) throws -> RideSummary
    ) -> (summaries: [RideSummary], failures: [String]) {
        let stored = load()
        var fresh: [String: Entry] = [:]
        var failures: [String] = []

        for url in urls {
            let name = url.lastPathComponent
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1
            if let entry = stored[name], entry.size == size, entry.profileKey == profileKey {
                fresh[name] = entry
                continue
            }
            do {
                fresh[name] = Entry(size: size, profileKey: profileKey, summary: try parse(url))
            } catch {
                failures.append("\(name): \(error.localizedDescription)")
            }
        }

        if fresh != stored {
            try? save(fresh)
        }
        return (fresh.values.map(\.summary).sorted { $0.start > $1.start }, failures)
    }
}
