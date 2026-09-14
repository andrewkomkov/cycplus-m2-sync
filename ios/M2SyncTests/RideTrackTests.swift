import Foundation
import Testing
@testable import M2Sync

struct RideTrackTests {
    private let start = Date(timeIntervalSince1970: 1_780_000_000)

    @Test func recordedDistanceWinsAndNeverGoesBack() {
        let points = [point(0, distance: 0), point(1, distance: 10), point(2, distance: 8), point(3, distance: 20)]
        #expect(RideTrack(ride: ride(points)).points.map(\.distanceMeters) == [0, 10, 10, 20])
    }

    @Test func missingDistanceFollowsTheCoordinates() throws {
        let track = RideTrack(ride: ride([point(0, latitude: 50.000), point(1, latitude: 50.001)]))
        let second = try #require(track.points.last)
        // Тысячная градуса широты — около 111 м.
        #expect(abs(second.distanceMeters - 111.2) < 1)
    }

    @Test func altitudeIsSmoothedOnlyOnLongerTracks() {
        let bump: [Double] = [0, 0, 0, 7, 0, 0, 0, 0]
        let long = RideTrack(ride: ride(bump.enumerated().map { point($0.offset, altitude: $0.element) }))
        #expect(abs((long.points[3].altitude ?? 0) - 1) < 1e-9)

        let short = RideTrack(ride: ride(bump.prefix(7).enumerated().map { point($0.offset, altitude: $0.element) }))
        #expect(short.points[3].altitude == 7)
    }

    @Test func averageSpeedIsDistanceOverMovingTime() {
        let points = (0...20).map { point($0, speed: 5, distance: Double($0 * 5)) }
        let track = RideTrack(ride: ride(points, totalDistance: 100))
        #expect(track.stats.movingSeconds == 21)
        #expect(abs((track.stats.averageSpeedKmh ?? 0) - 100.0 / 21 * 3.6) < 1e-9)
        #expect(abs((track.stats.maxSpeedKmh ?? 0) - 18) < 1e-9)
        #expect(track.stats.distanceMeters == 100)
    }

    @Test func heartRateStatsComeFromThePoints() {
        let points = [point(0, heartRate: 110), point(1, heartRate: 150), point(2, heartRate: 0), point(3, heartRate: 130)]
        let track = RideTrack(ride: ride(points))
        #expect(track.stats.averageHeartRate == 130)
        #expect(track.stats.maxHeartRate == 150)
    }

    @Test func metricsWithoutDataAreNotOffered() {
        let flat = (0...10).map { point($0, altitude: 100, speed: 5, heartRate: nil, cadence: nil) }
        #expect(RideTrack(ride: ride(flat)).availableMetrics == [.speed])

        let full = (0...10).map { point($0, altitude: Double($0 * 3), speed: 5, heartRate: 120, cadence: 80) }
        #expect(RideTrack(ride: ride(full)).availableMetrics == RideTrack.Metric.allCases)
    }

    @Test func chartSamplesAreBucketedByDistance() {
        let points = (0...999).map { point($0, speed: Double($0 % 10), distance: Double($0)) }
        let samples = RideTrack(ride: ride(points)).chartSamples(.speed, buckets: 100)

        #expect(samples.count == 100)
        #expect(samples.map(\.distanceKm) == samples.map(\.distanceKm).sorted())
        #expect((samples.last?.distanceKm ?? 0) > 0.95)
        #expect(samples.allSatisfy { $0.value >= 0 && $0.value <= 9 * 3.6 })
    }

    @Test func shortTrackGivesOneSamplePerPoint() {
        let points = (0...4).map { point($0, distance: Double($0 * 10)) }
        #expect(RideTrack(ride: ride(points)).chartSamples(.heartRate).count == 5)
    }

    @Test func nearestPointFindsTheClosestDistance() {
        let track = RideTrack(ride: ride((0...10).map { point($0, distance: Double($0 * 100)) }))
        #expect(track.pointIndex(nearestKm: 0.26) == 3)
        #expect(track.pointIndex(nearestKm: 0.24) == 2)
        #expect(track.pointIndex(nearestKm: 5) == 10)
        #expect(track.pointIndex(nearestKm: -1) == 0)
    }

    private func point(
        _ second: Int,
        latitude: Double? = 50,
        altitude: Double? = 100,
        speed: Double? = 5,
        heartRate: Int? = 120,
        cadence: Int? = 80,
        distance: Double? = nil
    ) -> FitParser.Point {
        FitParser.Point(
            time: start.addingTimeInterval(TimeInterval(second)),
            latitude: latitude,
            longitude: latitude == nil ? nil : 30,
            altitude: altitude,
            speed: speed,
            heartRate: heartRate,
            cadence: cadence,
            distance: distance
        )
    }

    private func ride(_ points: [FitParser.Point], totalDistance: Double? = nil) -> FitParser.Ride {
        FitParser.Ride(
            fileName: "ride.fit",
            start: points[0].time,
            end: points[points.count - 1].time.addingTimeInterval(1),
            sport: nil,
            totalDistance: totalDistance,
            totalTimerTime: nil,
            totalAscent: nil,
            totalCalories: nil,
            avgHeartRate: nil,
            points: points,
            activeSpans: FitParser.activeSpans(points)
        )
    }
}
