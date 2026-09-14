import FITSwiftSDK
import Foundation
import Testing
@testable import M2Sync

struct FitParserTests {
    /// Целые секунды: FIT хранит время с точностью до секунды.
    private let start = Date(timeIntervalSince1970: 1_780_000_000)

    @Test func readsPointsSessionAndPauses() throws {
        // 0…9 с, пауза 11 с, 20…24 с, разрыв 2 с (не пауза), 26…29 с.
        let seconds = Array(0...9) + Array(20...24) + Array(26...29)
        let data = try encode(seconds: seconds, session: .init(elapsed: 30, timer: 20, distance: 290, ascent: 12))

        let ride = try FitParser.parse(data: data, fileName: "ride.fit")

        #expect(ride.points.count == seconds.count)
        #expect(ride.start == start)
        #expect(ride.end == start.addingTimeInterval(30))
        #expect(ride.totalDistance == 290)
        #expect(ride.totalTimerTime == 20)
        #expect(ride.totalAscent == 12)
        #expect(ride.sport == .cycling)
        #expect(ride.activeSpans == [
            DateInterval(start: start, end: start.addingTimeInterval(10)),
            DateInterval(start: start.addingTimeInterval(20), end: start.addingTimeInterval(30)),
        ])
        #expect(ride.movingSeconds == 20)

        let first = try #require(ride.points.first)
        #expect(abs(try #require(first.latitude) - 50.0) < 1e-6)
        #expect(abs(try #require(first.longitude) - 30.5) < 1e-6)
        #expect(first.heartRate == 120)
        #expect(first.cadence == 80)
        #expect(abs(try #require(first.speed) - 5.5) < 1e-3)
        #expect(abs(try #require(first.altitude) - 150) < 1e-3)
        #expect(ride.hasRoute)
    }

    @Test func pointWithoutFixHasNoCoordinates() throws {
        let data = try encode(seconds: [0, 1, 2], session: nil, withoutFix: [1])
        let ride = try FitParser.parse(data: data, fileName: "ride.fit")
        #expect(ride.points[1].latitude == nil)
        #expect(ride.points[1].longitude == nil)
        #expect(ride.points[0].latitude != nil)
    }

    @Test func withoutSessionFallsBackToPoints() throws {
        let data = try encode(seconds: [0, 1, 2, 3], session: nil)
        let ride = try FitParser.parse(data: data, fileName: "ride.fit")
        #expect(ride.start == start)
        #expect(ride.end == start.addingTimeInterval(3))
        #expect(ride.totalDistance == 30) // distance последней точки
        #expect(ride.movingSeconds == 4)
    }

    @Test func implausibleElapsedTimeIsCappedAtLastPoint() throws {
        // На настоящих поездках встречается total_elapsed_time в двое суток.
        let data = try encode(seconds: [0, 1, 2], session: .init(elapsed: 200_000, timer: 3, distance: 20, ascent: 0))
        let ride = try FitParser.parse(data: data, fileName: "ride.fit")
        #expect(ride.end == start.addingTimeInterval(2))
    }

    @Test func fileWithoutRecordsIsRejected() throws {
        let data = try encode(seconds: [], session: .init(elapsed: 10, timer: 10, distance: 0, ascent: 0))
        #expect(throws: FitParser.ParseError.noTrackPoints("empty.fit")) {
            _ = try FitParser.parse(data: data, fileName: "empty.fit")
        }
    }

    /// Сверка с tools/ на настоящих поездках. В репозиторий поездки не попадают (это GPS-треки),
    /// поэтому тест включается только локально:
    /// `TEST_RUNNER_M2SYNC_FIT_DIR=<папка с .fit> TEST_RUNNER_M2SYNC_REPORT=<файл> xcodebuild test …`
    @Test(.enabled(if: ProcessInfo.processInfo.environment["M2SYNC_FIT_DIR"] != nil))
    func summarisesRealRides() throws {
        let environment = ProcessInfo.processInfo.environment
        let directory = URL(fileURLWithPath: try #require(environment["M2SYNC_FIT_DIR"]), isDirectory: true)
        let urls = try FileManager.default
            .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "fit" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        #expect(!urls.isEmpty)

        let lines = try urls.map { url in
            let ride = try FitParser.parse(url: url)
            let gps = ride.points.filter { $0.latitude != nil }.count
            let plan = WorkoutPlan(ride: ride)
            // Health должен получить то же время в движении, что и в .fit.
            #expect(
                plan.movingSeconds == ride.movingSeconds,
                "\(ride.fileName): Health \(plan.movingSeconds) s, .fit \(ride.movingSeconds) s"
            )
            #expect(plan.route.count == gps)

            // Дистанция и энергия по отрезкам записи складываются в итоги и не выходят за тренировку.
            let parted = WorkoutPlan(ride: ride, profile: Self.profile)
            let workout = DateInterval(start: parted.start, end: parted.end)
            let distanceSum = parted.distance.map(\.value).reduce(0, +)
            #expect(abs(distanceSum - (parted.distanceMeters ?? 0)) < 1e-6, "\(ride.fileName): distance parts")
            let energySum = parted.activeEnergy.map(\.value).reduce(0, +)
            #expect(abs(energySum - (parted.activeEnergyKilocalories ?? 0)) < 1e-6, "\(ride.fileName): energy parts")
            #expect((parted.distance + parted.activeEnergy).allSatisfy {
                workout.contains($0.interval.start) && workout.contains($0.interval.end)
            })

            return "\(ride.fileName) points=\(ride.points.count) gps=\(gps) dist=\(ride.totalDistance ?? -1) "
                + "timer=\(ride.totalTimerTime ?? -1) span=\(Int(ride.end.timeIntervalSince(ride.start))) "
                + "moving_s=\(ride.movingSeconds) pauses=\(ride.activeSpans.count - 1) "
                + "health_moving_s=\(plan.movingSeconds) health_pauses=\(plan.pauses.count) "
                + "distance_parts=\(parted.distance.count) energy_parts=\(parted.activeEnergy.count) "
                + calories(ride)
        }
        if let report = environment["M2SYNC_REPORT"] {
            try lines.joined(separator: "\n").write(toFile: report, atomically: true, encoding: .utf8)
        }
    }

    /// Условный профиль для калорий — тот же, что у расчёта на Python по .fit.
    private static let profile = Calories.Profile(weightKg: 72.8, birthYear: 1990, sex: .male)

    private func calories(_ ride: FitParser.Ride) -> String {
        let profile = Self.profile
        guard let estimate = Calories.forRide(ride, profile: profile) else { return "kcal_total=- kcal_active=-" }
        return String(format: "kcal_total=%.1f kcal_active=%.1f", estimate.total, estimate.active)
    }

    private struct SessionValues {
        let elapsed: Double
        let timer: Double
        let distance: Double
        let ascent: UInt16
    }

    private func encode(seconds: [Int], session: SessionValues?, withoutFix: Set<Int> = []) throws -> Data {
        var messages: [Mesg] = []
        for (index, second) in seconds.enumerated() {
            let record = RecordMesg()
            try record.setTimestamp(DateTime(date: start.addingTimeInterval(TimeInterval(second))))
            if !withoutFix.contains(index) {
                try record.setPositionLat(Int32((50.0 / FitParser.semicirclesToDegrees).rounded()))
                try record.setPositionLong(Int32((30.5 / FitParser.semicirclesToDegrees).rounded()))
            }
            try record.setEnhancedAltitude(150)
            try record.setEnhancedSpeed(5.5)
            try record.setHeartRate(120)
            try record.setCadence(80)
            try record.setDistance(Double(index * 10))
            messages.append(record)
        }
        if let session {
            let mesg = SessionMesg()
            try mesg.setStartTime(DateTime(date: start))
            try mesg.setSport(.cycling)
            try mesg.setTotalElapsedTime(session.elapsed)
            try mesg.setTotalTimerTime(session.timer)
            try mesg.setTotalDistance(session.distance)
            try mesg.setTotalAscent(session.ascent)
            messages.append(mesg)
        }
        let encoder = Encoder()
        encoder.write(mesgs: messages)
        return encoder.close()
    }
}
