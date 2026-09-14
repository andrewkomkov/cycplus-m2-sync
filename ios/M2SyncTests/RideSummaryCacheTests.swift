import Foundation
import Testing
@testable import M2Sync

struct RideSummaryCacheTests {
    @Test func parsesOnlyNewOrChangedFiles() throws {
        let fixture = try Fixture()
        let first = try fixture.write("20260913085741.fit", bytes: 10)
        try fixture.write("20260909192825.fit", bytes: 20)

        var parsed: [String] = []
        let parse: (URL) throws -> RideSummary = { url in
            parsed.append(url.lastPathComponent)
            return Fixture.summary(url.lastPathComponent)
        }

        #expect(fixture.cache.refresh(urls: try fixture.urls(), parse: parse).summaries.count == 2)
        #expect(parsed.count == 2)

        parsed.removeAll()
        #expect(fixture.cache.refresh(urls: try fixture.urls(), parse: parse).summaries.count == 2)
        #expect(parsed.isEmpty)

        // Велокомп дописал поездку: имя то же, размер другой.
        try Data(count: 11).write(to: first)
        parsed.removeAll()
        _ = fixture.cache.refresh(urls: try fixture.urls(), parse: parse)
        #expect(parsed == ["20260913085741.fit"])
    }

    @Test func dropsDeletedFilesAndSortsNewestFirst() throws {
        let fixture = try Fixture()
        try fixture.write("20260723122156.fit", bytes: 1)
        let gone = try fixture.write("20260913085741.fit", bytes: 1)
        try fixture.write("20260909192825.fit", bytes: 1)
        let parse: (URL) throws -> RideSummary = { Fixture.summary($0.lastPathComponent) }

        _ = fixture.cache.refresh(urls: try fixture.urls(), parse: parse)
        try FileManager.default.removeItem(at: gone)
        let result = fixture.cache.refresh(urls: try fixture.urls(), parse: parse)

        #expect(result.summaries.map(\.fileName) == ["20260909192825.fit", "20260723122156.fit"])
        #expect(fixture.cache.load().keys.sorted() == ["20260723122156.fit", "20260909192825.fit"])
    }

    @Test func unreadableFileIsReportedAndRetriedNextTime() throws {
        let fixture = try Fixture()
        try fixture.write("broken.fit", bytes: 3)
        var attempts = 0
        let parse: (URL) throws -> RideSummary = { _ in
            attempts += 1
            throw FitParser.ParseError.noTrackPoints("broken.fit")
        }

        let first = fixture.cache.refresh(urls: try fixture.urls(), parse: parse)
        #expect(first.summaries.isEmpty)
        #expect(first.failures.count == 1)

        _ = fixture.cache.refresh(urls: try fixture.urls(), parse: parse)
        #expect(attempts == 2)
    }

    @Test func cacheFromAnotherVersionIsIgnored() throws {
        let fixture = try Fixture()
        try Data(#"{"version":0,"entries":{}}"#.utf8).write(to: fixture.cache.url)
        #expect(fixture.cache.load().isEmpty)
    }

    private struct Fixture {
        let directory: URL
        let cache: RideSummaryCache

        init() throws {
            directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            cache = RideSummaryCache(url: directory.appendingPathComponent("summaries.json"))
        }

        @discardableResult
        func write(_ name: String, bytes: Int) throws -> URL {
            let url = directory.appendingPathComponent(name)
            try Data(count: bytes).write(to: url)
            return url
        }

        func urls() throws -> [URL] {
            try FileManager.default
                .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "fit" }
        }

        /// Время старта берётся из имени файла, как у M2: `yyyyMMddHHmmss.fit`.
        static func summary(_ name: String) -> RideSummary {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMddHHmmss"
            formatter.timeZone = TimeZone(identifier: "UTC")
            let start = formatter.date(from: String(name.prefix(14))) ?? Date(timeIntervalSince1970: 0)
            return RideSummary(
                fileName: name,
                start: start,
                distanceMeters: 1000,
                movingMinutes: 10,
                elapsedMinutes: 12,
                avgHeartRate: nil,
                avgCadence: nil,
                ascent: nil,
                pointCount: 600,
                hasRoute: true
            )
        }
    }
}
