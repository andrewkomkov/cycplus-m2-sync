import Foundation
import Testing
@testable import M2Sync

struct CaloriesTests {
    /// 28 мая 2026: возраст считается на год поездки.
    private let start = Date(timeIntervalSince1970: 1_780_000_000)
    /// 1 MET для 70 кг, ккал/мин.
    private let restingFor70kg = 3.5 * 70 / 200

    @Test func keytelMatchesThePublishedEquation() {
        let male = Calories.keytelPerMinute(heartRate: 150, weightKg: 70, age: 35, sex: .male)
        #expect(abs(male - 60.5136 / 4.184) < 1e-9)
        let female = Calories.keytelPerMinute(heartRate: 150, weightKg: 60, age: 30, sex: .female)
        #expect(abs(female - 41.3198 / 4.184) < 1e-9)
    }

    @Test func lowHeartRateGivesNoEnergyRatherThanNegative() {
        #expect(Calories.keytelPerMinute(heartRate: 40, weightKg: 70, age: 20, sex: .male) == 0)
    }

    @Test func metFollowsTheCompendiumSpeedBands() {
        #expect(abs(Calories.metPerMinute(speed: 15 / 3.6, weightKg: 70) - 4.0 * restingFor70kg) < 1e-9)
        #expect(abs(Calories.metPerMinute(speed: 20 / 3.6, weightKg: 70) - 8.0 * restingFor70kg) < 1e-9)
        #expect(abs(Calories.metPerMinute(speed: 40 / 3.6, weightKg: 70) - 15.8 * restingFor70kg) < 1e-9)
    }

    @Test func oneMinuteAtSteadyHeartRate() throws {
        let profile = Calories.Profile(weightKg: 70, birthYear: 1991, sex: .male)
        let estimate = try #require(Calories.forRide(ride(seconds: Array(0...60), heartRate: 150, speed: 8), profile: profile))
        let perMinute = 60.5136 / 4.184
        #expect(abs(estimate.total - perMinute) < 1e-3)
        #expect(abs(estimate.active - (perMinute - restingFor70kg)) < 1e-3)
    }

    @Test func stopsLongerThanThirtySecondsAreNotCounted() throws {
        let ride = ride(seconds: Array(0...60) + Array(200...260), heartRate: nil, speed: 20 / 3.6)
        let estimate = try #require(Calories.forRide(ride, profile: .init(weightKg: 70)))
        #expect(abs(estimate.total - 2 * 8.0 * restingFor70kg) < 1e-6)
        #expect(abs(estimate.active - 2 * 7.0 * restingFor70kg) < 1e-6)
    }

    @Test func withoutAgeOrSexHeartRateFallsBackToSpeed() throws {
        let ride = ride(seconds: Array(0...60), heartRate: 150, speed: 20 / 3.6)
        let estimate = try #require(Calories.forRide(ride, profile: .init(weightKg: 70, sex: .male)))
        #expect(abs(estimate.total - 8.0 * restingFor70kg) < 1e-6)
    }

    @Test func withoutWeightThereIsNothingToCount() {
        let ride = ride(seconds: Array(0...60), heartRate: 150, speed: 8)
        #expect(Calories.forRide(ride, profile: .init(birthYear: 1991, sex: .male)) == nil)
    }

    @Test func recordedTotalWinsOverTheEstimate() throws {
        let ride = ride(seconds: Array(0...60), heartRate: 150, speed: 8, totalCalories: 500)
        let estimate = try #require(Calories.forRide(ride, profile: .init(weightKg: 70)))
        #expect(estimate.total == 500)
        #expect(abs(estimate.active - (500 - restingFor70kg)) < 1e-6)
    }

    @Test func profileKeyChangesWithAnyInput() {
        let base = Calories.Profile(weightKg: 72.8, birthYear: 1990, sex: .male)
        #expect(base.key == "72.8/1990/male")
        #expect(Calories.Profile(weightKg: 73, birthYear: 1990, sex: .male).key != base.key)
        #expect(Calories.Profile(weightKg: 72.8, birthYear: 1991, sex: .male).key != base.key)
        #expect(Calories.Profile(weightKg: 72.8, birthYear: 1990, sex: .female).key != base.key)
        #expect(Calories.Profile.empty.key == "-/-/-")
    }

    @Test func missingValuesAreFilledFromTheFallback() {
        let health = Calories.Profile(weightKg: 72.8, birthYear: nil, sex: .male)
        let manual = Calories.Profile(weightKg: 80, birthYear: 1990, sex: .female)
        #expect(health.filled(from: manual) == Calories.Profile(weightKg: 72.8, birthYear: 1990, sex: .male))
    }

    @Test func ageIsTakenAtTheRideAndMustBePlausible() {
        #expect(Calories.age(birthYear: 1990, at: start) == 36)
        #expect(Calories.age(birthYear: 2030, at: start) == nil)
        #expect(Calories.age(birthYear: nil, at: start) == nil)
    }

    private func ride(seconds: [Int], heartRate: Int?, speed: Double?, totalCalories: Int? = nil) -> FitParser.Ride {
        let points = seconds.map { second in
            FitParser.Point(
                time: start.addingTimeInterval(TimeInterval(second)),
                latitude: 50,
                longitude: 30,
                altitude: 100,
                speed: speed,
                heartRate: heartRate,
                cadence: 80,
                distance: Double(second * 5)
            )
        }
        return FitParser.Ride(
            fileName: "ride.fit",
            start: points[0].time,
            end: points[points.count - 1].time.addingTimeInterval(1),
            sport: nil,
            totalDistance: 100,
            totalTimerTime: nil,
            totalAscent: nil,
            totalCalories: totalCalories,
            avgHeartRate: nil,
            points: points,
            activeSpans: FitParser.activeSpans(points)
        )
    }
}
