import CoreLocation
import HealthKit

/// Запись поездки в Apple Health. Что именно пишем, решает WorkoutPlan; здесь только HealthKit.
@MainActor
final class HealthWriter {
    enum HealthError: LocalizedError {
        case unavailable
        case notAuthorized
        case notSaved

        var errorDescription: String? {
            switch self {
            case .unavailable: String(localized: "Apple Health is not available on this device")
            case .notAuthorized: String(localized: "No permission to write workouts to Apple Health")
            case .notSaved: String(localized: "Apple Health did not save the workout")
            }
        }
    }

    /// Что уже лежит в Health по поездке: есть ли тренировка по текущим правилам и профилю
    /// и какие записаны по старым.
    struct Previous {
        let current: Bool
        let outdated: [HKWorkout]
    }

    /// Метка поездки на каждом сэмпле. HKWorkoutBuilder сохраняет сэмплы сразу, а тренировку —
    /// только в конце: если приложение закрыли посреди записи, сэмплы остаются без тренировки
    /// и продолжают считаться в итогах «Здоровья». По этой метке их находим и убираем.
    static let rideMetadataKey = "M2SyncRide"

    static let device = HKDevice(
        name: "Cycplus M2",
        manufacturer: "CYCPLUS",
        model: "M2",
        hardwareVersion: nil,
        firmwareVersion: nil,
        softwareVersion: nil,
        localIdentifier: nil,
        udiDeviceIdentifier: nil
    )

    private static let shareTypes: Set<HKSampleType> = [
        HKObjectType.workoutType(),
        HKSeriesType.workoutRoute(),
        HKQuantityType(.heartRate),
        HKQuantityType(.distanceCycling),
        HKQuantityType(.cyclingCadence),
        HKQuantityType(.cyclingSpeed),
        HKQuantityType(.activeEnergyBurned),
    ]

    /// Тренировки — проверить, не записана ли поездка раньше; вес, дата рождения и пол — для калорий.
    private static let readTypes: Set<HKObjectType> = [
        HKObjectType.workoutType(),
        HKQuantityType(.bodyMass),
        HKCharacteristicType(.dateOfBirth),
        HKCharacteristicType(.biologicalSex),
    ]

    private let store = HKHealthStore()

    /// Системный экран разрешений показывается, пока есть не спрошенные типы; дальше вызов
    /// возвращается сразу.
    func authorize() async throws {
        guard HKHealthStore.isHealthDataAvailable() else { throw HealthError.unavailable }
        try await store.requestAuthorization(toShare: Self.shareTypes, read: Self.readTypes)
        guard store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
            throw HealthError.notAuthorized
        }
    }

    /// Вес, год рождения и пол — что из этого есть в «Здоровье». Без разрешения на чтение
    /// HealthKit просто ничего не отдаёт.
    func readProfile() async -> Calories.Profile {
        var profile = Calories.Profile.empty
        if let year = (try? store.dateOfBirthComponents())?.year {
            profile.birthYear = year
        }
        switch (try? store.biologicalSex())?.biologicalSex {
        case .some(.male): profile.sex = .male
        case .some(.female): profile.sex = .female
        default: break
        }
        let latestWeight = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(.bodyMass))],
            sortDescriptors: [SortDescriptor(\.endDate, order: .reverse)],
            limit: 1
        )
        if let sample = try? await latestWeight.result(for: store).first {
            profile.weightKg = sample.quantity.doubleValue(for: .gramUnit(with: .kilo))
        }
        return profile
    }

    /// Тренировки с меткой этой поездки. Без разрешения на чтение HealthKit вернёт пусто —
    /// тогда от дублей страхует локальная отметка о записи.
    func previous(_ plan: WorkoutPlan) async throws -> Previous {
        let predicate = HKQuery.predicateForObjects(
            withMetadataKey: HKMetadataKeySyncIdentifier,
            allowedValues: [plan.syncIdentifier]
        )
        let workouts = try await HKSampleQueryDescriptor(predicates: [.workout(predicate)], sortDescriptors: [])
            .result(for: store)
        let isCurrent: (HKWorkout) -> Bool = { workout in
            let version = (workout.metadata?[HKMetadataKeySyncVersion] as? NSNumber)?.intValue ?? 0
            let profile = workout.metadata?[WorkoutPlan.caloriesProfileMetadataKey] as? String
            return version >= WorkoutPlan.syncVersion && profile == plan.caloriesProfileKey
        }
        return Previous(
            current: workouts.contains(where: isCurrent),
            outdated: workouts.filter { !isCurrent($0) }
        )
    }

    /// Удаляет тренировку вместе с её пульсом, каденсом, скоростью, дистанцией, энергией и маршрутом:
    /// сами по себе они в Health остаются.
    func delete(_ workout: HKWorkout) async throws {
        let related = HKQuery.predicateForObjects(from: workout)
        for type in Self.shareTypes where type != HKObjectType.workoutType() {
            _ = try await store.deleteObjects(of: type, predicate: related)
        }
        try await store.delete(workout)
    }

    /// Сэмплы этой поездки, оставшиеся от прерванной записи. Вызывать, когда тренировок поездки
    /// в Health уже нет, — иначе вместе с сиротами уйдут и данные живой тренировки.
    func deleteLeftovers(of plan: WorkoutPlan) async throws {
        let leftovers = HKQuery.predicateForObjects(
            withMetadataKey: Self.rideMetadataKey,
            allowedValues: [plan.syncIdentifier]
        )
        for type in Self.shareTypes where type != HKObjectType.workoutType() && type != HKSeriesType.workoutRoute() {
            _ = try await store.deleteObjects(of: type, predicate: leftovers)
        }
    }

    func write(_ plan: WorkoutPlan) async throws -> HKWorkout {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = .cycling
        configuration.locationType = .outdoor

        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: Self.device)
        let workout: HKWorkout
        do {
            try await builder.beginCollection(at: plan.start)
            for batch in Self.samples(for: plan).chunks(of: 1000) {
                try await builder.addSamples(batch)
            }
            // Паузы размечаем событиями — тогда длительность тренировки равна времени в движении.
            if !plan.pauses.isEmpty {
                try await builder.addWorkoutEvents(Self.events(for: plan))
            }
            try await builder.addMetadata(Self.metadata(for: plan))
            try await builder.endCollection(at: plan.end)
            guard let finished = try await builder.finishWorkout() else { throw HealthError.notSaved }
            workout = finished
        } catch {
            // Уже сохранённые сэмплы без тренировки не оставляем.
            builder.discardWorkout()
            throw error
        }

        if !plan.route.isEmpty {
            let route = HKWorkoutRouteBuilder(healthStore: store, device: Self.device)
            for batch in Self.locations(for: plan).chunks(of: 500) {
                try await route.insertRouteData(batch)
            }
            try await route.finishRoute(with: workout, metadata: nil)
        }
        return workout
    }

    private static func samples(for plan: WorkoutPlan) -> [HKSample] {
        let perMinute = HKUnit.count().unitDivided(by: .minute())
        let metersPerSecond = HKUnit.meter().unitDivided(by: .second())
        let tag = [rideMetadataKey: plan.syncIdentifier]

        func instant(_ identifier: HKQuantityTypeIdentifier, _ unit: HKUnit, _ items: [WorkoutPlan.Sample]) -> [HKSample] {
            items.map {
                HKQuantitySample(
                    type: HKQuantityType(identifier),
                    quantity: HKQuantity(unit: unit, doubleValue: $0.value),
                    start: $0.time,
                    end: $0.time,
                    device: device,
                    metadata: tag
                )
            }
        }

        func wholeRide(_ identifier: HKQuantityTypeIdentifier, _ unit: HKUnit, _ value: Double) -> HKSample {
            HKQuantitySample(
                type: HKQuantityType(identifier),
                quantity: HKQuantity(unit: unit, doubleValue: value),
                start: plan.start,
                end: plan.end,
                device: device,
                metadata: tag
            )
        }

        var samples = instant(.heartRate, perMinute, plan.heartRate)
        samples += instant(.cyclingCadence, perMinute, plan.cadence)
        samples += instant(.cyclingSpeed, metersPerSecond, plan.speed)
        if let distance = plan.distanceMeters {
            samples.append(wholeRide(.distanceCycling, .meter(), distance))
        }
        if let energy = plan.activeEnergyKilocalories {
            samples.append(wholeRide(.activeEnergyBurned, .kilocalorie(), energy))
        }
        return samples
    }

    private static func events(for plan: WorkoutPlan) -> [HKWorkoutEvent] {
        plan.pauses.flatMap { gap in
            [
                HKWorkoutEvent(type: .pause, dateInterval: DateInterval(start: gap.start, duration: 0), metadata: nil),
                HKWorkoutEvent(type: .resume, dateInterval: DateInterval(start: gap.end, duration: 0), metadata: nil),
            ]
        }
    }

    private static func metadata(for plan: WorkoutPlan) -> [String: Any] {
        var metadata: [String: Any] = [
            HKMetadataKeySyncIdentifier: plan.syncIdentifier,
            HKMetadataKeySyncVersion: WorkoutPlan.syncVersion,
            HKMetadataKeyIndoorWorkout: false,
            WorkoutPlan.caloriesProfileMetadataKey: plan.caloriesProfileKey,
        ]
        if let ascent = plan.ascentMeters {
            metadata[HKMetadataKeyElevationAscended] = HKQuantity(unit: .meter(), doubleValue: ascent)
        }
        return metadata
    }

    private static func locations(for plan: WorkoutPlan) -> [CLLocation] {
        plan.route.map { point in
            CLLocation(
                coordinate: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude),
                altitude: point.altitude ?? 0,
                horizontalAccuracy: 5,
                verticalAccuracy: point.altitude == nil ? -1 : 5,
                course: -1,
                speed: point.speed ?? -1,
                timestamp: point.time
            )
        }
    }
}

private extension Array {
    func chunks(of size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}
