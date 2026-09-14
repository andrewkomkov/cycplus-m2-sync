import Foundation
import Testing
@testable import M2Sync

struct DeviceSnapshotTests {
    @Test func memoryReplyIsFreeSlashTotal() throws {
        let memory = try #require(DeviceSnapshot.parseMemory("13396/16384"))
        #expect(memory.free == 13396)
        #expect(memory.total == 16384)
    }

    @Test func unreadableMemoryReplyIsIgnored() {
        #expect(DeviceSnapshot.parseMemory(nil) == nil)
        #expect(DeviceSnapshot.parseMemory("16384") == nil)
        #expect(DeviceSnapshot.parseMemory("a/b") == nil)
    }

    @Test func progressNeverExceedsTheWholeFile() {
        var progress = SyncProgress(fileName: "ride.fit", index: 1, count: 2, received: 0, size: 1000)
        #expect(progress.fraction == 0)
        progress.received = 500
        #expect(progress.fraction == 0.5)
        progress.received = 1024
        #expect(progress.fraction == 1)
    }
}
