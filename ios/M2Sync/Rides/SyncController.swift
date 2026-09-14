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

/// Синк: найти велокомп, запомнить его состояние, скачать новые файлы, записать их в Apple Health.
@MainActor
final class SyncController: ObservableObject {
    private static let logLimit = 500
    private static let deviceKey = "device"
    /// Отметки о записи — на версию правил: после её смены все поездки проверяются заново,
    /// и записанные по старым правилам тренировки перезаписываются.
    private static var importedKey: String { "imported-v\(WorkoutPlan.syncVersion)" }

    @Published private(set) var busy = false
    @Published private(set) var loading = false
    @Published private(set) var device: DeviceSnapshot?
    @Published private(set) var progress: SyncProgress?
    @Published private(set) var rides: [RideSummary] = []
    /// Имена файлов, уже записанных в Apple Health по текущим правилам.
    @Published private(set) var imported: Set<String>
    @Published private(set) var log: [String] = []

    private let defaults: UserDefaults
    private let health = HealthWriter()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        imported = Set(defaults.stringArray(forKey: Self.importedKey) ?? [])
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
            progress = nil
            UIApplication.shared.isIdleTimerDisabled = false
        }

        let files: RideFiles
        do {
            files = try RideFiles.standard()
        } catch {
            append(error.localizedDescription)
            return
        }

        await download(into: files)
        await reload()
        // Уже скачанные поездки уходят в Health, даже если велокомп сейчас выключен.
        await importToHealth(files)
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

    private func download(into files: RideFiles) async {
        let client = M2Client()
        client.log = { [weak self] in self?.append($0) }
        defer { client.disconnect() }
        do {
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
    }

    /// Новые поездки — в Apple Health. Отметка «записано» ставится по имени файла, как на Android;
    /// метка в самой тренировке страхует от дублей, если отметки потерялись, и находит тренировки,
    /// записанные по старым правилам, чтобы их заменить.
    private func importToHealth(_ files: RideFiles) async {
        let urls = ((try? files.rideURLs()) ?? []).filter { !imported.contains($0.lastPathComponent) }
        guard !urls.isEmpty else { return }

        do {
            try await health.authorize()
        } catch {
            append(error.localizedDescription)
            return
        }

        for (offset, url) in urls.enumerated() {
            let name = url.lastPathComponent
            progress = SyncProgress(
                fileName: name,
                index: offset + 1,
                count: urls.count,
                received: offset,
                size: urls.count,
                phase: .health
            )
            do {
                let ride = try await Task.detached(priority: .userInitiated) {
                    try FitParser.parse(url: url)
                }.value
                let plan = WorkoutPlan(ride: ride)

                let previous = try await health.previous(plan)
                for workout in previous.outdated {
                    try await health.delete(workout)
                }
                if previous.current {
                    append(String(localized: "already in Apple Health: \(name)"))
                } else {
                    let workout = try await health.write(plan)
                    let kilometres = (plan.distanceMeters ?? 0).kilometres
                    let moving = Int(workout.duration)
                    if previous.outdated.isEmpty {
                        append(String(localized: "saved to Apple Health: \(name) — \(kilometres) km, \(moving) s moving, heart rate: \(plan.heartRate.count), route points: \(plan.route.count)"))
                    } else {
                        append(String(localized: "rewritten in Apple Health: \(name) — \(kilometres) km, \(moving) s moving, heart rate: \(plan.heartRate.count), route points: \(plan.route.count)"))
                    }
                }
                markImported(name)
            } catch {
                append("\(name): \(error.localizedDescription)")
            }
        }
        progress = nil
    }

    private func markImported(_ name: String) {
        imported.insert(name)
        defaults.set(imported.sorted(), forKey: Self.importedKey)
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
