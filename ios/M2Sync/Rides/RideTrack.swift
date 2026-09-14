import CoreLocation
import Foundation

/// Трек поездки для экрана деталей: точки с дистанцией и сглаженной высотой, итоги и выборки
/// для графиков. Порт RideTrack.kt с двумя отличиями: по оси графиков — настоящая дистанция,
/// а не номер точки, и средняя скорость — это дистанция за время в движении, а не среднее по точкам.
struct RideTrack {
    struct Point: Equatable {
        let time: Date
        let latitude: Double?
        let longitude: Double?
        let distanceMeters: Double
        let altitude: Double?
        let speedKmh: Double?
        let heartRate: Int?
        let cadence: Int?

        var coordinate: CLLocationCoordinate2D? {
            guard let latitude, let longitude else { return nil }
            return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
    }

    enum Metric: String, CaseIterable, Identifiable {
        case elevation
        case speed
        case heartRate
        case cadence

        var id: String { rawValue }
    }

    struct Stats: Equatable {
        let distanceMeters: Double
        let movingSeconds: Int
        let elapsedSeconds: Int
        let averageSpeedKmh: Double?
        let maxSpeedKmh: Double?
        let ascentMeters: Int?
        let averageHeartRate: Int?
        let maxHeartRate: Int?
        let altitudeRange: ClosedRange<Double>?
    }

    struct ChartSample: Equatable, Identifiable {
        let distanceKm: Double
        let value: Double
        /// Точка из середины корзины — её и подсвечиваем на карте.
        let pointIndex: Int

        var id: Int { pointIndex }
    }

    /// Окно скользящего среднего для высоты: барометр M2 шумит на метр-два.
    static let smoothingWindow = 7
    /// Столько корзин на графике: больше экран всё равно не покажет.
    static let chartBuckets = 220

    let points: [Point]
    let coordinates: [CLLocationCoordinate2D]
    let stats: Stats

    init(ride: FitParser.Ride) {
        var distance = 0.0
        var lastFix: CLLocation?
        var raw: [Point] = []
        raw.reserveCapacity(ride.points.count)
        for point in ride.points {
            let fix = point.latitude.flatMap { latitude in
                point.longitude.map { CLLocation(latitude: latitude, longitude: $0) }
            }
            // Дистанцию велокомпа берём как есть, но назад она не идёт; без неё — по координатам.
            if let recorded = point.distance {
                distance = max(distance, recorded)
            } else if let fix, let lastFix {
                distance += fix.distance(from: lastFix)
            }
            if let fix { lastFix = fix }

            raw.append(Point(
                time: point.time,
                latitude: point.latitude,
                longitude: point.longitude,
                distanceMeters: distance,
                altitude: point.altitude,
                speedKmh: point.speed.map { $0 * 3.6 },
                heartRate: point.heartRate.flatMap { $0 > 0 ? $0 : nil },
                cadence: point.cadence.flatMap { $0 > 0 ? $0 : nil }
            ))
        }

        let altitudes = Self.smoothedAltitudes(raw.map(\.altitude))
        points = zip(raw, altitudes).map { point, altitude in
            Point(
                time: point.time,
                latitude: point.latitude,
                longitude: point.longitude,
                distanceMeters: point.distanceMeters,
                altitude: altitude,
                speedKmh: point.speedKmh,
                heartRate: point.heartRate,
                cadence: point.cadence
            )
        }
        coordinates = points.compactMap(\.coordinate)

        let speeds = points.compactMap(\.speedKmh).filter { $0 > 0 }
        let heartRates = points.compactMap(\.heartRate)
        let smoothed = points.compactMap(\.altitude)
        let distanceMeters = ride.totalDistance ?? points.last?.distanceMeters ?? 0
        let moving = ride.movingSeconds
        stats = Stats(
            distanceMeters: distanceMeters,
            movingSeconds: moving,
            elapsedSeconds: Int(ride.end.timeIntervalSince(ride.start)),
            averageSpeedKmh: moving > 0 && distanceMeters > 0 ? distanceMeters / Double(moving) * 3.6 : nil,
            maxSpeedKmh: speeds.max(),
            ascentMeters: ride.totalAscent.flatMap { $0 > 0 ? $0 : nil },
            averageHeartRate: ride.avgHeartRate
                ?? (heartRates.isEmpty ? nil : heartRates.reduce(0, +) / heartRates.count),
            maxHeartRate: heartRates.max(),
            altitudeRange: smoothed.min().flatMap { low in smoothed.max().map { low...$0 } }
        )
    }

    /// Графики, для которых есть что показать: плоская высота и пустые датчики не предлагаются.
    var availableMetrics: [Metric] {
        Metric.allCases.filter { metric in
            switch metric {
            case .elevation: (stats.altitudeRange.map { $0.upperBound - $0.lowerBound } ?? 0) > 1
            case .speed: (stats.maxSpeedKmh ?? 0) > 0.5
            case .heartRate: stats.maxHeartRate != nil
            case .cadence: points.contains { $0.cadence != nil }
            }
        }
    }

    static func value(_ metric: Metric, of point: Point) -> Double? {
        switch metric {
        case .elevation: point.altitude
        case .speed: point.speedKmh
        case .heartRate: point.heartRate.map(Double.init)
        case .cadence: point.cadence.map(Double.init)
        }
    }

    /// Средние по равным отрезкам дистанции. Корзина без показаний пропускается.
    func chartSamples(_ metric: Metric, buckets: Int = RideTrack.chartBuckets) -> [ChartSample] {
        let count = min(buckets, points.count)
        guard count > 0 else { return [] }
        let total = points.last?.distanceMeters ?? 0

        var groups = Array(repeating: [Int](), count: count)
        for (index, point) in points.enumerated() {
            let position = total > 0
                ? point.distanceMeters / total
                : Double(index) / Double(max(points.count - 1, 1))
            groups[min(count - 1, Int(position * Double(count)))].append(index)
        }

        return groups.compactMap { indices in
            let values = indices.compactMap { Self.value(metric, of: points[$0]) }
            guard !values.isEmpty else { return nil }
            let middle = indices[indices.count / 2]
            return ChartSample(
                distanceKm: points[middle].distanceMeters / 1000,
                value: values.reduce(0, +) / Double(values.count),
                pointIndex: middle
            )
        }
    }

    /// Точка, ближайшая к отметке на графике. Дистанция не убывает, поэтому двоичный поиск.
    func pointIndex(nearestKm km: Double) -> Int? {
        guard !points.isEmpty else { return nil }
        let target = km * 1000
        var low = 0
        var high = points.count - 1
        while low < high {
            let middle = (low + high) / 2
            if points[middle].distanceMeters < target {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if low > 0, abs(points[low - 1].distanceMeters - target) <= abs(points[low].distanceMeters - target) {
            return low - 1
        }
        return low
    }

    /// Центрированное скользящее среднее; на коротком треке сглаживать нечего.
    static func smoothedAltitudes(_ altitudes: [Double?]) -> [Double?] {
        guard altitudes.count > smoothingWindow else { return altitudes }
        let half = smoothingWindow / 2
        return altitudes.indices.map { index in
            guard altitudes[index] != nil else { return nil }
            let window = altitudes[max(0, index - half)...min(altitudes.count - 1, index + half)].compactMap { $0 }
            return window.reduce(0, +) / Double(window.count)
        }
    }
}
