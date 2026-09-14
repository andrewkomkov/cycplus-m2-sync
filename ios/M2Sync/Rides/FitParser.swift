import FITSwiftSDK
import Foundation

/// Разбор .fit с велокомпьютера. M2 пишет одну сессию на файл, точки с частотой 1 Гц:
/// координаты, скорость, высота, каденс, пульс. Калорий и мощности в файле нет.
/// Порт android/.../FitParser.kt — поведение должно совпадать.
enum FitParser {
    static let semicirclesToDegrees = 180.0 / 2_147_483_648.0

    /// Пауза = разрыв в записи больше этого числа секунд.
    static let pauseGapSeconds: TimeInterval = 5

    struct Point: Equatable {
        let time: Date
        let latitude: Double?
        let longitude: Double?
        let altitude: Double?
        let speed: Double? // м/с
        let heartRate: Int?
        let cadence: Int?
        let distance: Double? // м от старта
    }

    struct Ride {
        let fileName: String
        let start: Date
        let end: Date
        let sport: Sport?
        let totalDistance: Double? // м
        let totalTimerTime: Double? // с в движении
        let totalAscent: Int?
        let totalCalories: Int?
        let avgHeartRate: Int?
        let points: [Point]
        /// Отрезки, когда запись реально шла: между ними — паузы велокомпьютера.
        let activeSpans: [DateInterval]

        var hasRoute: Bool {
            points.contains { $0.latitude != nil && $0.longitude != nil }
        }

        var movingSeconds: Int {
            activeSpans.reduce(0) { $0 + Int($1.duration) }
        }
    }

    enum ParseError: LocalizedError, Equatable {
        case noTrackPoints(String)

        var errorDescription: String? {
            switch self {
            case .noTrackPoints(let file): String(localized: "No track points in \(file)")
            }
        }
    }

    static func parse(url: URL) throws -> Ride {
        try parse(data: Data(contentsOf: url), fileName: url.lastPathComponent)
    }

    static func parse(data: Data, fileName: String) throws -> Ride {
        let collector = Collector()
        let broadcaster = MesgBroadcaster()
        broadcaster.addListener(collector as RecordMesgListener)
        broadcaster.addListener(collector as SessionMesgListener)

        let decoder = Decoder(stream: FITSwiftSDK.InputStream(data: data))
        decoder.addMesgListener(broadcaster)
        try decoder.read()

        let points = collector.points.sorted { $0.time < $1.time }
        guard let first = points.first, let last = points.last else {
            throw ParseError.noTrackPoints(fileName)
        }

        let start = collector.start ?? first.time
        // Конец считаем по последней точке, но не раньше старта плюс общее время;
        // больше часа сверх последней точки не верим.
        var end = max(last.time, start.addingTimeInterval(TimeInterval(Int(collector.elapsed ?? 0))))
        if end > last.time.addingTimeInterval(3600) { end = last.time }
        if end <= start { end = start.addingTimeInterval(1) }

        return Ride(
            fileName: fileName,
            start: start,
            end: end,
            sport: collector.sport,
            totalDistance: collector.totalDistance ?? last.distance,
            totalTimerTime: collector.totalTimer,
            totalAscent: collector.ascent,
            totalCalories: collector.calories,
            avgHeartRate: collector.avgHeartRate,
            points: points,
            activeSpans: activeSpans(points)
        )
    }

    static func activeSpans(_ points: [Point]) -> [DateInterval] {
        guard let first = points.first else { return [] }
        var spans: [DateInterval] = []
        var spanStart = first.time
        var previous = first.time
        for point in points.dropFirst() {
            if point.time.timeIntervalSince(previous) > pauseGapSeconds {
                spans.append(DateInterval(start: spanStart, end: previous.addingTimeInterval(1)))
                spanStart = point.time
            }
            previous = point.time
        }
        spans.append(DateInterval(start: spanStart, end: previous.addingTimeInterval(1)))
        return spans
    }

    private final class Collector: RecordMesgListener, SessionMesgListener {
        var points: [Point] = []
        var start: Date?
        var totalDistance: Double?
        var totalTimer: Double?
        var elapsed: Double?
        var ascent: Int?
        var calories: Int?
        var avgHeartRate: Int?
        var sport: Sport?

        init() {
            points.reserveCapacity(8192)
        }

        func onMesg(_ mesg: RecordMesg) {
            guard let timestamp = mesg.getTimestamp() else { return }
            points.append(Point(
                time: timestamp.date,
                latitude: mesg.getPositionLat().map { Double($0) * FitParser.semicirclesToDegrees },
                longitude: mesg.getPositionLong().map { Double($0) * FitParser.semicirclesToDegrees },
                altitude: mesg.getEnhancedAltitude() ?? mesg.getAltitude(),
                speed: mesg.getEnhancedSpeed() ?? mesg.getSpeed(),
                heartRate: mesg.getHeartRate().map(Int.init),
                cadence: mesg.getCadence().map(Int.init),
                distance: mesg.getDistance()
            ))
        }

        func onMesg(_ mesg: SessionMesg) {
            if let value = mesg.getStartTime() { start = value.date }
            if let value = mesg.getTotalDistance() { totalDistance = value }
            if let value = mesg.getTotalTimerTime() { totalTimer = value }
            if let value = mesg.getTotalElapsedTime() { elapsed = value }
            if let value = mesg.getTotalAscent() { ascent = Int(value) }
            if let value = mesg.getTotalCalories() { calories = Int(value) }
            if let value = mesg.getAvgHeartRate() { avgHeartRate = Int(value) }
            sport = mesg.getSport()
        }
    }
}
