import Foundation
import Testing
@testable import M2Sync

struct RideFilesTests {
    @Test func refusesNamesThatLeaveTheDirectory() throws {
        let files = try RideFiles(directory: temporaryDirectory())
        for name in ["../escape.fit", "nested/ride.fit", "..\\escape.fit", ".hidden.fit", ""] {
            #expect(throws: RideFiles.FileError.unsafeName(name)) {
                _ = try files.url(for: name)
            }
            #expect(throws: RideFiles.FileError.unsafeName(name)) {
                _ = try files.needsDownload(DeviceFile(name: name, size: 1))
            }
        }
    }

    @Test func downloadsAgainOnlyWhenSizeDiffers() throws {
        let files = try RideFiles(directory: temporaryDirectory())
        let ride = DeviceFile(name: "20260913085741.fit", size: 4)

        #expect(try files.needsDownload(ride) == true)
        try files.save(Data([1, 2, 3, 4]), as: ride.name)
        #expect(try files.needsDownload(ride) == false)
        // Велокомп дописал поездку — в filelist.txt размер больше.
        #expect(try files.needsDownload(DeviceFile(name: ride.name, size: 5)) == true)
    }

    @Test func listsOnlyFitFilesInNameOrder() throws {
        let files = try RideFiles(directory: temporaryDirectory())
        try files.save(Data([1]), as: "20260913085741.fit")
        try files.save(Data([1]), as: "20260723122156.fit")
        try files.save(Data([1]), as: "Setting.json")
        #expect(try files.rideURLs().map(\.lastPathComponent) == ["20260723122156.fit", "20260913085741.fit"])
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }
}
