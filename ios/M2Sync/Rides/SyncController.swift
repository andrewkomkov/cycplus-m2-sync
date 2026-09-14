import Foundation
import UIKit

/// Сводка поездки для списка — то же, что RideSummary в Android-версии.
struct RideSummary: Identifiable, Equatable, Codable {
    var id: String { fileName }
    let fileName: String
    let start: Date
    let distanceMeters: Double
    let movingMinutes: Int
    let elapsedMinutes: Int
    let avgHeartRate: Int?
    let avgCadence: Int?
    let ascent: Int?
    let pointCount: Int
    let hasRoute: Bool
}

extension RideSummary {
    init(_ ride: FitParser.Ride) {
        let cadences = ride.points.compactMap(\.cadence).filter { $0 > 0 }
        self.init(
            fileName: ride.fileName,
            start: ride.start,
            distanceMeters: ride.totalDistance ?? 0,
            movingMinutes: ride.movingSeconds / 60,
            elapsedMinutes: Int(ride.end.timeIntervalSince(ride.start)) / 60,
            avgHeartRate: ride.avgHeartRate,
            avgCadence: cadences.isEmpty ? nil : cadences.reduce(0, +) / cadences.count,
            ascent: ride.totalAscent,
            pointCount: ride.points.count,
            hasRoute: ride.hasRoute
        )
    }
}

/// Синк: найти велокомп, запомнить его состояние, скачать новые файлы, перечитать список.
@MainActor
final class SyncController: ObservableObject {
    private static let logLimit = 500
    private static let deviceKey = "device"

    @Published private(set) var busy = false
    @Published private(set) var loading = false
    @Published private(set) var device: DeviceSnapshot?
    @Published private(set) var progress: SyncProgress?
    @Published private(set) var rides: [RideSummary] = []
    @Published private(set) var log: [String] = []

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.deviceKey) {
            device = try? JSONDecoder().decode(DeviceSnapshot.self, from: data)
        }
    }

    func sync() async {
        guard !busy else { return }
        busy = true
        // Пока идёт передача, экран не гаснет: при блокировке iOS может приостановить приложение
        // посреди файла, и велокомп отвалится по таймауту.
        UIApplication.shared.isIdleTimerDisabled = true
        defer {
            busy = false
            UIApplication.shared.isIdleTimerDisabled = false
        }

        let client = M2Client()
        client.log = { [weak self] in self?.append($0) }
        do {
            let files = try RideFiles.standard()
            append(String(localized: "looking for the device…"))
            let name = try await client.connect()
            append(name)
            await snapshot(of: client, name: name)

            let onDevice = try await client.listFiles()
            append(String(localized: "rides on the device: \(onDevice.count)"))

            let pending = try onDevice.filter { try files.needsDownload($0) }
            for (offset, file) in pending.enumerated() {
                progress = SyncProgress(
                    fileName: file.name,
                    index: offset + 1,
                    count: pending.count,
                    received: 0,
                    size: file.size
                )
                append(String(localized: "downloading \(file.name) (\(file.size) bytes)"))
                let data = try await client.fetch(file.name) { [weak self] received in
                    self?.progress?.received = received
                }
                try files.save(data, as: file.name)
                append(String(localized: "saved \(file.name)"))
            }
            append(String(localized: "new files downloaded: \(pending.count)"))
        } catch M2Error.notFound {
            append(String(localized: "nothing found — is the bike computer switched on?"))
        } catch {
            append(error.localizedDescription)
        }
        progress = nil
        client.disconnect()
        await reload()
    }

    /// Перечитывает список поездок. Разбираются только новые файлы, остальное — из кэша,
    /// и всё это вне главного потока.
    func reload() async {
        guard let files = try? RideFiles.standard() else { return }
        loading = true
        defer { loading = false }

        let cache = RideSummaryCache(
            url: files.directory.deletingLastPathComponent().appendingPathComponent("ride-summaries.json")
        )
        let (summaries, failures) = await Task.detached(priority: .userInitiated) {
            cache.refresh(urls: (try? files.rideURLs()) ?? [])
        }.value
        rides = summaries
        failures.forEach(append)
    }

    private func snapshot(of client: M2Client, name: String) async {
        let firmware = await client.firmware()
        let battery = await client.battery()
        let memory = DeviceSnapshot.parseMemory(await client.diskSpace())
        let snapshot = DeviceSnapshot(
            name: name,
            firmware: firmware,
            battery: battery,
            freeKB: memory?.free,
            totalKB: memory?.total,
            seenAt: Date()
        )
        device = snapshot
        if let data = try? JSONEncoder().encode(snapshot) {
            defaults.set(data, forKey: Self.deviceKey)
        }
    }

    private func append(_ line: String) {
        #if DEBUG
        // Аналог `adb logcat -s M2SYNC`: читается через `devicectl device process launch --console`.
        print("[M2SYNC] \(line)")
        #endif
        log.append(line)
        if log.count > Self.logLimit { log.removeFirst(log.count - Self.logLimit) }
    }
}
