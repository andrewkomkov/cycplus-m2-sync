package dev.komkov.m2sync

/**
 * Оценка расхода энергии за заезд.
 *
 * Cycplus M2 калорий не пишет — ни в session, ни в lap, — поэтому считаем сами.
 * Основной путь — формула Keytel et al. (2005) по пульсу: она построена ровно
 * на том, что у нас есть посекундно. Там, где пульса нет, откатываемся на MET
 * из Compendium of Physical Activities по скорости.
 *
 * Обе модели дают полный (gross) расход за время движения, включая базовый
 * обмен, — это и есть семантика TotalCaloriesBurnedRecord.
 */
object Calories {
    /** Разрыв между точками больше этого считаем остановкой и не оплачиваем. */
    private const val MAX_GAP_SECONDS = 30L

    private const val KJ_PER_KCAL = 4.184

    enum class Sex { MALE, FEMALE }

    /** Год рождения и пол: либо из медкарты Health Connect, либо из диалога. */
    data class Profile(
        val birthYear: Int?,
        val sex: Sex?,
    ) {
        val usable: Boolean get() = birthYear != null && sex != null

        companion object {
            val EMPTY = Profile(null, null)
        }
    }

    /**
     * Разбор FHIR-ресурса Patient из Personal Health Records: там дата рождения
     * лежит в `birthDate`, пол — в `gender`. Оба поля необязательные.
     */
    fun profileFromFhir(json: String): Profile? =
        runCatching {
            val o = org.json.JSONObject(json)
            if (o.optString("resourceType") != "Patient") return null
            val year = o.optString("birthDate").take(4).toIntOrNull()
            val sex =
                when (o.optString("gender").lowercase()) {
                    "male" -> Sex.MALE
                    "female" -> Sex.FEMALE
                    else -> null
                }
            Profile(year, sex).takeIf { it.birthYear != null || it.sex != null }
        }.getOrNull()

    /**
     * Боевой путь: готовое значение из .fit уважаем — свой расчёт нужен ровно
     * там, где велокомп смолчал.
     */
    fun forRide(
        ride: FitParser.Ride,
        weightKg: Double?,
        profile: Profile,
    ): Int? =
        ride.totalCalories
            ?: estimate(ride, weightKg, ageNow(profile.birthYear), profile.sex)

    private fun ageNow(birthYear: Int?): Int? =
        birthYear
            ?.let {
                java.time.Year
                    .now()
                    .value - it
            }?.takeIf { it in 1..120 }

    /**
     * Отпечаток входных данных расчёта. Кэш заездов держится на нём: поменялся
     * вес или профиль — прежнее число калорий недействительно.
     */
    fun profileKey(
        weightKg: Double?,
        profile: Profile,
    ): String = "${weightKg ?: "-"}/${profile.birthYear ?: "-"}/${profile.sex ?: "-"}"

    /**
     * @param weightKg вес из Health Connect; без него считать нечего
     * @param age полных лет, нужен только пульсовой модели
     * @return килокалории или null, если данных не хватает
     */
    fun estimate(
        ride: FitParser.Ride,
        weightKg: Double?,
        age: Int?,
        sex: Sex?,
    ): Int? {
        if (weightKg == null || weightKg <= 0) return null
        val points = ride.points
        if (points.size < 2) return null

        var kcal = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val seconds = cur.time.epochSecond - prev.time.epochSecond
            if (seconds <= 0 || seconds > MAX_GAP_SECONDS) continue

            val minutes = seconds / 60.0
            val hr = cur.heartRate ?: prev.heartRate
            val perMinute =
                if (hr != null && hr > 0 && age != null && sex != null) {
                    keytelKcalPerMinute(hr, weightKg, age, sex)
                } else {
                    metKcalPerMinute(cur.speed ?: prev.speed ?: 0.0, weightKg)
                }
            kcal += perMinute * minutes
        }
        return kcal.takeIf { it > 0 }?.toInt()
    }

    /**
     * Keytel et al., «Prediction of energy expenditure from heart rate monitoring
     * during submaximal exercise» (2005). Формула даёт кДж/мин.
     *
     * На низком пульсе выражение уходит в минус — такие интервалы не вычитаем
     * из общей суммы, а считаем нулевыми.
     */
    private fun keytelKcalPerMinute(
        hr: Int,
        weightKg: Double,
        age: Int,
        sex: Sex,
    ): Double {
        val kj =
            when (sex) {
                Sex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age
                Sex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age
            }
        return (kj / KJ_PER_KCAL).coerceAtLeast(0.0)
    }

    /** MET для велосипеда по скорости; speed в м/с, как в .fit. */
    private fun metKcalPerMinute(
        speedMs: Double,
        weightKg: Double,
    ): Double {
        val kmh = speedMs * 3.6
        val met =
            when {
                kmh < 16.0 -> 4.0
                kmh < 19.2 -> 6.8
                kmh < 22.4 -> 8.0
                kmh < 25.6 -> 10.0
                kmh < 30.6 -> 12.0
                else -> 15.8
            }
        // Классическое MET-уравнение: 1 MET = 3.5 мл O2/кг/мин, 5 ккал на литр O2.
        return met * 3.5 * weightKg / 200.0
    }
}
