import Foundation
import Testing
@testable import M2Sync

struct RideExportTests {
    private let belgrade = TimeZone(identifier: "Europe/Belgrade")!

    @Test func fileNameMatchesTheAndroidApp() throws {
        // 24 июля 2026, 10:30 по Белграду (UTC+2) — это 08:30 UTC.
        let start = try #require(ISO8601DateFormatter().date(from: "2026-07-24T08:30:05Z"))
        let name = RideExport.fileName(start: start, distanceMeters: 40992.37, timeZone: belgrade)
        #expect(name == "2026-07-24_10-30_40.99km_cycplus-m2.fit")
    }

    @Test func rideWithoutDistanceStillGetsAName() throws {
        let utc = try #require(TimeZone(identifier: "UTC"))
        let name = RideExport.fileName(start: Date(timeIntervalSince1970: 0), distanceMeters: 0, timeZone: utc)
        #expect(name == "1970-01-01_00-00_0.00km_cycplus-m2.fit")
    }

    @Test func stagedCopyKeepsTheBytesAndIsReused() throws {
        let root = temporaryDirectory()
        let files = try RideFiles(directory: root.appendingPathComponent("fit", isDirectory: true))
        try files.save(Data([1, 2, 3]), as: "20260724103005.fit")
        let start = try #require(ISO8601DateFormatter().date(from: "2026-07-24T08:30:05Z"))
        let export = try RideExport(summary: summary("20260724103005.fit", start: start), files: files, timeZone: belgrade)
        let share = root.appendingPathComponent("share", isDirectory: true)

        let first = try export.staged(in: share)
        #expect(first.lastPathComponent == "2026-07-24_10-30_40.99km_cycplus-m2.fit")
        #expect(try Data(contentsOf: first) == Data([1, 2, 3]))

        #expect(try export.staged(in: share) == first)

        // Велокомп дописал поездку — копия обновляется.
        try files.save(Data([1, 2, 3, 4]), as: "20260724103005.fit")
        #expect(try Data(contentsOf: export.staged(in: share)) == Data([1, 2, 3, 4]))
    }

    /// То же, что делает окно «Поделиться»: берёт представление через NSItemProvider.
    @Test func shareSheetReceivesTheFitFileUnderItsReadableName() async throws {
        let root = temporaryDirectory()
        let files = try RideFiles(directory: root.appendingPathComponent("fit", isDirectory: true))
        try files.save(Data([1, 2, 3]), as: "20260724103005.fit")
        let start = try #require(ISO8601DateFormatter().date(from: "2026-07-24T08:30:05Z"))
        let export = try RideExport(summary: summary("20260724103005.fit", start: start), files: files, timeZone: belgrade)

        let provider = NSItemProvider()
        provider.register(export)
        let types = provider.registeredContentTypes.map(\.identifier)
        #expect(types == ["com.garmin.fit"], "offered types: \(types)")

        let received: URL = try await withCheckedThrowingContinuation { continuation in
            _ = provider.loadFileRepresentation(for: .fit, openInPlace: false) { url, _, error in
                guard let url else {
                    continuation.resume(throwing: error ?? CocoaError(.fileNoSuchFile))
                    return
                }
                // Файл провайдера живёт только внутри обработчика — забираем копию.
                let copy = root.appendingPathComponent("received", isDirectory: true)
                do {
                    try FileManager.default.createDirectory(at: copy, withIntermediateDirectories: true)
                    let target = copy.appendingPathComponent(url.lastPathComponent)
                    try FileManager.default.copyItem(at: url, to: target)
                    continuation.resume(returning: target)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }

        #expect(received.lastPathComponent == "2026-07-24_10-30_40.99km_cycplus-m2.fit")
        #expect(try Data(contentsOf: received) == Data([1, 2, 3]))
    }

    @Test func unsafeRideNameIsRefused() throws {
        let files = try RideFiles(directory: temporaryDirectory())
        #expect(throws: RideFiles.FileError.self) {
            _ = try RideExport(summary: summary("../escape.fit", start: Date()), files: files)
        }
    }

    private func summary(_ fileName: String, start: Date) -> RideSummary {
        RideSummary(
            fileName: fileName,
            start: start,
            distanceMeters: 40992.37,
            movingMinutes: 105,
            elapsedMinutes: 150,
            avgHeartRate: 152,
            avgCadence: nil,
            ascent: 69,
            activeKilocalories: nil,
            pointCount: 6335,
            hasRoute: true
        )
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }
}
