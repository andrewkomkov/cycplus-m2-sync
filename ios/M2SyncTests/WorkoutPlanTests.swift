import Foundation
import Testing
@testable import M2Sync

struct WorkoutPlanTests {
    private let start = Date(timeIntervalSince1970: 1_780_000_000)

    @Test func pausesAreGapsBetweenRecordingSpans() {
        let plan = WorkoutPlan(ride: ride(seconds: Array(0...9) + Array(20...29)))
        #expect(plan.pauses == [
            DateInterval(start: start.addingTimeInterval(10), end: start.addingTimeInterval(20)),
        ])
        #expect(plan.syncIdentifier == "m2:ride.fit")
        #expect(plan.start == start)
        #expect(plan.end == start.addingTimeInterval(30))
        #expect(plan.movingSeconds == 20)
    }

    @Test func continuousRideHasNoPauses() {
        let plan = WorkoutPlan(ride: ride(seconds: Array(0...20)))
        #expect(plan.pauses.isEmpty)
        #expect(plan.movingSeconds == 21)
    }

    @Test func sessionOpenBeforeTheFirstPointAndAfterTheLastIsPaused() {
        // Так было на поездке 23 июля: 2822 с до первой точки и 239 с после последней.
        let points = (0...9).map { point(at: $0) }
        let standing = ride(
            points: points,
            start: start.addingTimeInterval(-13),
            end: start.addingTimeInterval(20)
        )

        let plan = WorkoutPlan(ride: standing)

        #expect(plan.pauses == [
            DateInterval(start: start.addingTimeInterval(-13), end: start),
            DateInterval(start: start.addingTimeInterval(10), end: start.addingTimeInterval(20)),
        ])
        #expect(plan.movingSeconds == standing.movingSeconds)
    }

    @Test func endCappedAtTheLastPointKeepsItsSecond() {
        // FitParser обрезает конец по последней точке, если total_elapsed_time неправдоподобен.
        let points = (0...9).map { point(at: $0) }
        let capped = ride(points: points, start: start, end: start.addingTimeInterval(9))

        let plan = WorkoutPlan(ride: capped)

        #expect(plan.end == start.addingTimeInterval(10))
        #expect(plan.pauses.isEmpty)
        #expect(plan.movingSeconds == capped.movingSeconds)
    }

    @Test func pausesStayInsideTheWorkout() {
        let spans = [
            DateInterval(start: start.addingTimeInterval(-5), end: start.addingTimeInterval(2)),
            DateInterval(start: start.addingTimeInterval(4), end: start.addingTimeInterval(6)),
            DateInterval(start: start.addingTimeInterval(20), end: start.addingTimeInterval(30)),
        ]
        let pauses = WorkoutPlan.pauses(spans: spans, start: start, end: start.addingTimeInterval(8))
        #expect(pauses == [
            DateInterval(start: start.addingTimeInterval(2), end: start.addingTimeInterval(4)),
            DateInterval(start: start.addingTimeInterval(6), end: start.addingTimeInterval(8)),
        ])
    }

    @Test func zeroAndMissingReadingsAreNotWritten() {
        var points = (0...3).map { point(at: $0) }
        points[1] = point(at: 1, heartRate: 0, cadence: 0, speed: 0)
        points[2] = point(at: 2, heartRate: nil, cadence: nil, speed: nil, fix: false)

        let plan = WorkoutPlan(ride: ride(points: points))

        #expect(plan.heartRate.map(\.time) == [start, start.addingTimeInterval(3)])
        #expect(plan.cadence.count == 2)
        #expect(plan.speed.count == 2)
        // Нулевые показания не мешают координатам: без фикса только точка 2.
        #expect(plan.route.map(\.time) == [0, 1, 3].map { start.addingTimeInterval(TimeInterval($0)) })
    }

    @Test func totalsComeFromTheSessionAndZeroIsOmitted() {
        let plan = WorkoutPlan(ride: ride(seconds: [0, 1], distance: 1500, ascent: 12))
        #expect(plan.distanceMeters == 1500)
        #expect(plan.ascentMeters == 12)

        let flat = WorkoutPlan(ride: ride(seconds: [0, 1], distance: 0, ascent: 0))
        #expect(flat.distanceMeters == nil)
        #expect(flat.ascentMeters == nil)
    }

    @Test func readingsOutsideTheWorkoutAreDropped() {
        // Сессия началась позже первой точки — HealthKit такие сэмплы отвергнет.
        let points = (0...4).map { point(at: $0) }
        let late = ride(points: points, start: start.addingTimeInterval(2), end: start.addingTimeInterval(5))

        let plan = WorkoutPlan(ride: late)

        #expect(plan.heartRate.first?.time == start.addingTimeInterval(2))
        #expect(plan.route.count == 3)
        #expect(plan.pauses.isEmpty)
    }

    @Test func routeKeepsAltitudeAndSpeed() throws {
        let plan = WorkoutPlan(ride: ride(seconds: [0]))
        let first = try #require(plan.route.first)
        #expect(first.latitude == 50)
        #expect(first.longitude == 30)
        #expect(first.altitude == 100)
        #expect(first.speed == 5)
    }

    private func point(
        at second: Int,
        heartRate: Int? = 120,
        cadence: Int? = 80,
        speed: Double? = 5,
        fix: Bool = true
    ) -> FitParser.Point {
        FitParser.Point(
            time: start.addingTimeInterval(TimeInterval(second)),
            latitude: fix ? 50 : nil,
            longitude: fix ? 30 : nil,
            altitude: 100,
            speed: speed,
            heartRate: heartRate,
            cadence: cadence,
            distance: Double(second * 5)
        )
    }

    private func ride(seconds: [Int], distance: Double? = 100, ascent: Int? = 5) -> FitParser.Ride {
        ride(points: seconds.map { point(at: $0) }, distance: distance, ascent: ascent)
    }

    private func ride(
        points: [FitParser.Point],
        start: Date? = nil,
        end: Date? = nil,
        distance: Double? = 100,
        ascent: Int? = 5
    ) -> FitParser.Ride {
        FitParser.Ride(
            fileName: "ride.fit",
            start: start ?? points[0].time,
            end: end ?? points[points.count - 1].time.addingTimeInterval(1),
            sport: nil,
            totalDistance: distance,
            totalTimerTime: nil,
            totalAscent: ascent,
            totalCalories: nil,
            avgHeartRate: nil,
            points: points,
            activeSpans: FitParser.activeSpans(points)
        )
    }
}
