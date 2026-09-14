import Foundation

/// Оценка расхода энергии за поездку — порт android/.../Calories.kt.
///
/// Cycplus M2 калорий не пишет — ни в session, ни в lap, — поэтому считаем сами. Основной путь —
/// формула Keytel et al. (2005) по пульсу; там, где пульса нет, — MET из Compendium of Physical
/// Activities по скорости. Обе модели дают полный расход. Apple Health хранит активную и базовую
/// энергию раздельно, поэтому в «Активную энергию» уходит полный расход минус 1 MET покоя
/// (3,5 мл O₂/кг/мин) за то же время.
enum Calories {
    enum Sex: String, Codable, CaseIterable {
        case male
        case female
    }

    /// Всё, от чего зависит расчёт. Значения берутся из «Здоровья», недостающие — из профиля в приложении.
    struct Profile: Equatable, Codable {
        var weightKg: Double?
        var birthYear: Int?
        var sex: Sex?

        static let empty = Profile()

        /// Отпечаток входных данных: поменялся профиль — прежние калории недействительны.
        var key: String {
            let weight = weightKg.map { String(format: "%.1f", $0) } ?? "-"
            return "\(weight)/\(birthYear.map(String.init) ?? "-")/\(sex?.rawValue ?? "-")"
        }

        /// Пустые поля заполняются из `fallback`.
        func filled(from fallback: Profile) -> Profile {
            Profile(
                weightKg: weightKg ?? fallback.weightKg,
                birthYear: birthYear ?? fallback.birthYear,
                sex: sex ?? fallback.sex
            )
        }
    }

    struct Estimate: Equatable {
        /// Полный расход за время движения, ккал.
        let total: Double
        /// Без расхода покоя — то, что пишется в «Активную энергию», ккал.
        let active: Double
    }

    /// Активная энергия, набежавшая к моменту точки, — чтобы разложить итог по отрезкам записи.
    struct Accrual: Equatable {
        let time: Date
        let kilocalories: Double
    }

    /// Разрыв между точками больше этого считаем остановкой и не оплачиваем.
    static let maxGapSeconds: TimeInterval = 30

    private static let kilojoulesPerKilocalorie = 4.184

    /// Готовое значение из .fit уважаем — свой расчёт нужен ровно там, где велокомп смолчал.
    /// Возраст берётся на год поездки, а не на сегодня: иначе с каждым новым годом менялись бы
    /// калории всех старых поездок.
    static func forRide(_ ride: FitParser.Ride, profile: Profile) -> Estimate? {
        let weight = profile.weightKg.flatMap { $0 > 0 ? $0 : nil }
        if let total = ride.totalCalories, total > 0 {
            let resting = weight.map { restingKilocalories(points: ride.points, weightKg: $0) } ?? 0
            return Estimate(total: Double(total), active: max(0, Double(total) - resting))
        }
        guard let weight else { return nil }
        return estimate(
            points: ride.points,
            weightKg: weight,
            age: age(birthYear: profile.birthYear, at: ride.start),
            sex: profile.sex
        )
    }

    /// Расчёт по точкам поездки; nil, если считать было нечего.
    static func estimate(points: [FitParser.Point], weightKg: Double, age: Int?, sex: Sex?) -> Estimate? {
        var total = 0.0
        var active = 0.0
        let resting = restingPerMinute(weightKg: weightKg)
        forEachCountedInterval(points) { previous, current, minutes in
            let rate = perMinute(previous: previous, current: current, weightKg: weightKg, age: age, sex: sex)
            total += rate * minutes
            // На низком пульсе полный расход бывает ниже покоя — такие секунды активными не считаем.
            active += max(0, rate - resting) * minutes
        }
        return total > 0 ? Estimate(total: total, active: active) : nil
    }

    /// Активная энергия по интервалам между точками: пустой список, если без веса считать нечего.
    static func activeAccruals(_ ride: FitParser.Ride, profile: Profile) -> [Accrual] {
        guard let weight = profile.weightKg, weight > 0 else { return [] }
        let age = age(birthYear: profile.birthYear, at: ride.start)
        let resting = restingPerMinute(weightKg: weight)
        var accruals: [Accrual] = []
        forEachCountedInterval(ride.points) { previous, current, minutes in
            let rate = perMinute(previous: previous, current: current, weightKg: weight, age: age, sex: profile.sex)
            accruals.append(Accrual(time: current.time, kilocalories: max(0, rate - resting) * minutes))
        }
        return accruals
    }

    /// Keytel et al., «Prediction of energy expenditure from heart rate monitoring during
    /// submaximal exercise» (2005). Формула даёт кДж/мин; на низком пульсе она уходит в минус —
    /// такие интервалы считаем нулевыми.
    static func keytelPerMinute(heartRate: Int, weightKg: Double, age: Int, sex: Sex) -> Double {
        let hr = Double(heartRate)
        let years = Double(age)
        let kilojoules = switch sex {
        case .male: -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * years
        case .female: -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * years
        }
        return max(0, kilojoules / kilojoulesPerKilocalorie)
    }

    /// MET велосипеда по скорости (м/с, как в .fit).
    static func metPerMinute(speed: Double, weightKg: Double) -> Double {
        let kmh = speed * 3.6
        let met = switch kmh {
        case ..<16.0: 4.0
        case ..<19.2: 6.8
        case ..<22.4: 8.0
        case ..<25.6: 10.0
        case ..<30.6: 12.0
        default: 15.8
        }
        return met * restingPerMinute(weightKg: weightKg)
    }

    /// 1 MET: 3,5 мл O₂/кг/мин при 5 ккал на литр O₂.
    static func restingPerMinute(weightKg: Double) -> Double {
        3.5 * weightKg / 200
    }

    static func age(birthYear: Int?, at date: Date) -> Int? {
        guard let birthYear else { return nil }
        let age = Calendar(identifier: .gregorian).component(.year, from: date) - birthYear
        return (1...120).contains(age) ? age : nil
    }

    /// Полный расход за минуту на интервале: по пульсу, если есть он, возраст и пол, иначе по скорости.
    private static func perMinute(
        previous: FitParser.Point,
        current: FitParser.Point,
        weightKg: Double,
        age: Int?,
        sex: Sex?
    ) -> Double {
        let heartRate = current.heartRate ?? previous.heartRate
        if let heartRate, heartRate > 0, let age, let sex {
            return keytelPerMinute(heartRate: heartRate, weightKg: weightKg, age: age, sex: sex)
        }
        return metPerMinute(speed: current.speed ?? previous.speed ?? 0, weightKg: weightKg)
    }

    private static func restingKilocalories(points: [FitParser.Point], weightKg: Double) -> Double {
        var resting = 0.0
        forEachCountedInterval(points) { _, _, minutes in
            resting += restingPerMinute(weightKg: weightKg) * minutes
        }
        return resting
    }

    private static func forEachCountedInterval(
        _ points: [FitParser.Point],
        _ body: (FitParser.Point, FitParser.Point, Double) -> Void
    ) {
        for (previous, current) in zip(points, points.dropFirst()) {
            let seconds = current.time.timeIntervalSince(previous.time)
            guard seconds > 0, seconds <= maxGapSeconds else { continue }
            body(previous, current, seconds / 60)
        }
    }
}
