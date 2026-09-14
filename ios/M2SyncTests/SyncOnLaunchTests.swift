import Foundation
import Testing
@testable import M2Sync

struct SyncOnLaunchTests {
    private let device = DeviceSnapshot(
        name: "M2_E8E3",
        firmware: "V1.4.0",
        battery: 100,
        freeKB: 13392,
        totalKB: 16384,
        seenAt: Date(timeIntervalSince1970: 1_789_390_000)
    )

    @Test func syncsOnLaunchOnceTheBikeComputerIsKnown() {
        #expect(SyncController.syncsOnLaunch(enabled: true, device: device))
    }

    @Test func firstLaunchWaitsForTheSyncButton() {
        #expect(!SyncController.syncsOnLaunch(enabled: true, device: nil))
    }

    @Test func settingTurnsItOff() {
        #expect(!SyncController.syncsOnLaunch(enabled: false, device: device))
    }
}
