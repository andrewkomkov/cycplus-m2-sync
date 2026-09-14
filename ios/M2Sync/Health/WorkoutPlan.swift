import Foundation

/// Что пишем в Apple Health по одной поездке — без HealthKit, чтобы проверялось тестами.
/// Правила те же, что у HealthWriter в Android-версии: нулевые пульс, каденс и скорость
/// не пишем, дистанция и набор высоты — итоги сессии.
struct WorkoutPlan: Equatable {
    /// Поднять, когда меняется то, как поездка ложится в Health: записанные раньше тренировки
    /// при следующем синке удалятся и запишутся заново.
    /// 2 — паузы до первой и после последней точки; 3 — активная энергия;
    /// 4 — дистанция и энергия по отрезкам записи.
    static let syncVersion = 4

    /// Ключ метаданных с отпечатком профиля, по которому считались калории: поменялся профиль —
    /// тренировка считается устаревшей и перезаписывается.
    static let caloriesProfileMetadataKey = "M2SyncCaloriesProfile"

    struct Sample: Equatable {
        let time: Date
        let value: Double
    }

    struct RoutePoint: Equatable {
        let time: Date
        let latitude: Double
        let longitude: Double
        let altitude: Double?
        let speed: Double?
    }

    /// Часть итога поездки на одном отрезке записи.
    struct Portion: Equatable {
        let interval: DateInterval
        let value: Double
    }

    let syncIdentifier: String
    let start: Date
    let end: Date
    /// Всё время тренировки, когда велокомп не писал точки.
    let pauses: [DateInterval]
    let distanceMeters: Double?
    /// Дистанция по отрезкам записи. «Здоровье» раскладывает значение сэмпла по дням пропорционально
    /// его времени, и одна запись на сессию, которую велокомп растянул на несколько суток, отдала бы
    /// километры дням, когда велосипед стоял.
    let distance: [Portion]
    let ascentMeters: Double?
    let activeEnergyKilocalories: Double?
    /// Активная энергия по тем же отрезкам — там, где она сожжена.
    let activeEnergy: [Portion]
    let caloriesProfileKey: String
    let heartRate: [Sample] // уд/мин
    let cadence: [Sample] // об/мин
    let speed: [Sample] // м/с
    let route: [RoutePoint]

    /// Метка тренировки в Health: по ней повторный синк узнаёт уже записанную поездку.
    static func syncIdentifier(for fileName: String) -> String {
        "m2:\(fileName)"
    }

    /// Время в движении, как его посчитает Health: длительность тренировки минус паузы.
    var movingSeconds: Int {
        Int(end.timeIntervalSince(start) - pauses.reduce(0) { $0 + $1.duration })
    }

    init(ride: FitParser.Ride, profile: Calories.Profile = .empty) {
        syncIdentifier = Self.syncIdentifier(for: ride.fileName)
        start = ride.start
        // Отрезок записи кончается через секунду после своей последней точки. Если конец поездки
        // обрезан по последней точке (у M2 бывает total_elapsed_time в двое суток), эта секунда
        // выпала бы из времени в движении.
        end = max(ride.end, ride.activeSpans.last?.end ?? ride.end)

        pauses = Self.pauses(spans: ride.activeSpans, start: start, end: end)
        let moving = Self.movingIntervals(start: start, end: end, pauses: pauses)

        let totalDistance = ride.totalDistance.flatMap { $0 > 0 ? $0 : nil }
        distanceMeters = totalDistance
        distance = totalDistance.map { total in
            Self.split(total, over: moving, weights: Self.distanceWeights(ride.points, over: moving))
        } ?? []
        ascentMeters = ride.totalAscent.flatMap { $0 > 0 ? Double($0) : nil }

        let energy = Calories.forRide(ride, profile: profile).flatMap { $0.active > 0 ? $0.active : nil }
        activeEnergyKilocalories = energy
        activeEnergy = energy.map { total in
            let accruals = Calories.activeAccruals(ride, profile: profile)
            let weights = moving.map { interval in
                accruals.filter { interval.contains($0.time) }.reduce(0) { $0 + $1.kilocalories }
            }
            return Self.split(total, over: moving, weights: weights)
        } ?? []
        caloriesProfileKey = profile.key

        // HealthKit не принимает данные вне интервала тренировки.
        let (start, end) = (start, end)
        let points = ride.points.filter { $0.time >= start && $0.time <= end }

        heartRate = points.compactMap { point in
            point.heartRate.flatMap { $0 > 0 ? Sample(time: point.time, value: Double($0)) : nil }
        }
        cadence = points.compactMap { point in
            point.cadence.flatMap { $0 > 0 ? Sample(time: point.time, value: Double($0)) : nil }
        }
        speed = points.compactMap { point in
            point.speed.flatMap { $0 > 0 ? Sample(time: point.time, value: $0) : nil }
        }
        route = points.compactMap { point in
            guard let latitude = point.latitude, let longitude = point.longitude else { return nil }
            return RoutePoint(
                time: point.time,
                latitude: latitude,
                longitude: longitude,
                altitude: point.altitude,
                speed: point.speed
            )
        }
    }

    /// Паузы — дополнение отрезков записи до интервала тренировки. Велокомп открывает сессию
    /// раньше первой точки и держит её после последней, пока велосипед стоит: это тоже остановки,
    /// иначе Health засчитает их как езду.
    static func pauses(spans: [DateInterval], start: Date, end: Date) -> [DateInterval] {
        var pauses: [DateInterval] = []
        var cursor = start
        for span in spans {
            let spanStart = min(max(span.start, start), end)
            if cursor < spanStart {
                pauses.append(DateInterval(start: cursor, end: spanStart))
            }
            cursor = max(cursor, min(span.end, end))
        }
        if cursor < end {
            pauses.append(DateInterval(start: cursor, end: end))
        }
        return pauses
    }

    /// Отрезки движения внутри тренировки — всё, что не паузы.
    static func movingIntervals(start: Date, end: Date, pauses: [DateInterval]) -> [DateInterval] {
        var intervals: [DateInterval] = []
        var cursor = start
        for pause in pauses.sorted(by: { $0.start < $1.start }) {
            if cursor < pause.start {
                intervals.append(DateInterval(start: cursor, end: pause.start))
            }
            cursor = max(cursor, pause.end)
        }
        if cursor < end {
            intervals.append(DateInterval(start: cursor, end: end))
        }
        return intervals
    }

    /// Итог делится между отрезками пропорционально весам, а без весов — пропорционально
    /// длительности. Сумма частей равна итогу.
    static func split(_ total: Double, over intervals: [DateInterval], weights: [Double]) -> [Portion] {
        guard !intervals.isEmpty else { return [] }
        let hasWeights = weights.count == intervals.count && weights.reduce(0, +) > 0
        let shares = hasWeights ? weights : intervals.map(\.duration)
        let sum = shares.reduce(0, +)
        guard sum > 0 else { return [Portion(interval: intervals[0], value: total)] }
        return zip(intervals, shares).compactMap { interval, share in
            share > 0 ? Portion(interval: interval, value: total * share / sum) : nil
        }
    }

    /// Сколько проехано на каждом отрезке по дистанции из точек. Дистанция велокомпа накопительная,
    /// поэтому отрезку достаётся прирост от самой дальней точки предыдущих отрезков до его самой дальней.
    static func distanceWeights(_ points: [FitParser.Point], over intervals: [DateInterval]) -> [Double] {
        var reached = 0.0
        return intervals.map { interval in
            let furthest = points.filter { interval.contains($0.time) }.compactMap(\.distance).max()
            guard let furthest else { return 0 }
            let weight = max(0, furthest - reached)
            reached = max(reached, furthest)
            return weight
        }
    }
}
