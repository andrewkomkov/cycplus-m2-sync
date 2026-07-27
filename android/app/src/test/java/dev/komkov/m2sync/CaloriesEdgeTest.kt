package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.Year

/**
 * Края расчёта калорий: боевая точка входа [Calories.forRide], возраст из года
 * рождения и верхние ступени MET, куда обычный городской заезд не доезжает.
 */
class CaloriesEdgeTest {
    private val start: Instant = Instant.parse("2026-07-24T08:30:00Z")

    /** Час езды секундными точками с постоянной скоростью и пульсом. */
    private fun ride(
        seconds: Int = 3601,
        speed: Double? = null,
        heartRate: Int? = null,
        totalCalories: Int? = null,
    ): FitParser.Ride {
        val points =
            (0 until seconds).map { i ->
                FitParser.Point(
                    time = start.plusSeconds(i.toLong()),
                    lat = null,
                    lon = null,
                    altitude = null,
                    speed = speed,
                    heartRate = heartRate,
                    cadence = null,
                    distance = null,
                )
            }
        return FitParser.Ride(
            fileName = "edge.fit",
            start = points.first().time,
            end = points.last().time,
            sport = "cycling",
            totalDistance = 20_000.0,
            totalTimerTime = seconds.toDouble(),
            totalAscent = null,
            totalCalories = totalCalories,
            avgHeartRate = heartRate,
            points = points,
            activeSpans = listOf(points.first().time to points.last().time),
        )
    }

    /** Если велокомп посчитал калории сам, своей моделью его не переубеждаем. */
    @Test
    fun `a value from the fit file wins over the estimate`() {
        val profile = Calories.Profile(1988, Calories.Sex.MALE)
        val kcal = Calories.forRide(ride(speed = 5.56, totalCalories = 77), 80.0, profile)
        assertEquals(77, kcal)
    }

    @Test
    fun `without a value in the file the ride is estimated`() {
        val profile = Calories.Profile(1988, Calories.Sex.MALE)
        // Пульсовая модель Keytel для 38 лет и 140 уд/мин за час — около 814 ккал.
        val kcal = Calories.forRide(ride(heartRate = 140), 80.0, profile)!!
        assertEquals(814.5, kcal.toDouble(), 25.0)
    }

    @Test
    fun `an empty profile leaves only the speed model`() {
        // 20 км/ч — ступень MET 8.0: 8 * 3.5 * 80 / 200 * 60 = 672 ккал.
        val kcal = Calories.forRide(ride(speed = 5.56, heartRate = 140), 80.0, Calories.Profile.EMPTY)!!
        assertEquals(672.0, kcal.toDouble(), 5.0)
    }

    @Test
    fun `without weight forRide gives nothing`() {
        assertNull(Calories.forRide(ride(heartRate = 140), null, Calories.Profile(1988, Calories.Sex.MALE)))
    }

    /**
     * Год рождения из медкарты бывает мусорным. Возраст вне 1..120 лет к
     * пульсовой модели не подпускаем — она откатывается на скорость.
     */
    @Test
    fun `an impossible year of birth falls back to the speed model`() {
        val bySpeed = Calories.forRide(ride(speed = 5.56, heartRate = 140), 80.0, Calories.Profile.EMPTY)!!
        val ancient =
            Calories.forRide(
                ride(speed = 5.56, heartRate = 140),
                80.0,
                Calories.Profile(1850, Calories.Sex.MALE),
            )!!
        val unborn =
            Calories.forRide(
                ride(speed = 5.56, heartRate = 140),
                80.0,
                Calories.Profile(Year.now().value, Calories.Sex.MALE),
            )!!
        assertEquals(bySpeed, ancient)
        assertEquals(bySpeed, unborn)
    }

    /** Ровесник этого года — уже год от роду, и пульсовая модель включается. */
    @Test
    fun `a plausible year of birth switches the model on`() {
        val bySpeed = Calories.forRide(ride(speed = 5.56, heartRate = 140), 80.0, Calories.Profile.EMPTY)!!
        val byPulse =
            Calories.forRide(
                ride(speed = 5.56, heartRate = 140),
                80.0,
                Calories.Profile(Year.now().value - 1, Calories.Sex.MALE),
            )!!
        assertTrue("$byPulse should differ from $bySpeed", byPulse != bySpeed)
    }

    /** Ключ кэша обязан меняться от любого входа расчёта, иначе покажем старое число. */
    @Test
    fun `the profile key covers weight year and sex`() {
        val base = Calories.profileKey(80.0, Calories.Profile(1988, Calories.Sex.MALE))
        assertTrue(base.contains("80.0"))
        assertTrue(base.contains("1988"))
        assertTrue(base.contains("MALE"))

        assertTrue(base != Calories.profileKey(81.0, Calories.Profile(1988, Calories.Sex.MALE)))
        assertTrue(base != Calories.profileKey(80.0, Calories.Profile(1989, Calories.Sex.MALE)))
        assertTrue(base != Calories.profileKey(80.0, Calories.Profile(1988, Calories.Sex.FEMALE)))
    }

    @Test
    fun `an unknown profile still gives a stable key`() {
        assertEquals("-/-/-", Calories.profileKey(null, Calories.Profile.EMPTY))
    }
}

/**
 * Ступени MET по скорости. Compendium даёт шесть градаций, и городские 20 км/ч
 * лежат в третьей — верхние проверяем отдельно.
 */
class CaloriesMetTiersTest {
    private val start: Instant = Instant.parse("2026-07-24T08:30:00Z")

    /** Час без пульса на постоянной скорости: считать будет только MET-модель. */
    private fun kcalAt(kmh: Double): Int {
        val points =
            (0 until 3601).map { i ->
                FitParser.Point(
                    time = start.plusSeconds(i.toLong()),
                    lat = null,
                    lon = null,
                    altitude = null,
                    speed = kmh / 3.6,
                    heartRate = null,
                    cadence = null,
                    distance = null,
                )
            }
        val ride =
            FitParser.Ride(
                fileName = "met.fit",
                start = points.first().time,
                end = points.last().time,
                sport = "cycling",
                totalDistance = kmh * 1000,
                totalTimerTime = 3601.0,
                totalAscent = null,
                totalCalories = null,
                avgHeartRate = null,
                points = points,
                activeSpans = listOf(points.first().time to points.last().time),
            )
        return Calories.estimate(ride, 80.0, null, null)!!
    }

    /** MET * 3.5 * 80 кг / 200 * 60 мин = MET * 84 ккал за час. */
    private fun expected(met: Double) = met * 84.0

    @Test
    fun `a light pace is paid at four MET`() {
        assertEquals(expected(4.0), kcalAt(14.0).toDouble(), 5.0)
    }

    @Test
    fun `seventeen kilometres per hour move to six point eight MET`() {
        assertEquals(expected(6.8), kcalAt(17.0).toDouble(), 5.0)
    }

    @Test
    fun `twenty four kilometres per hour move to ten MET`() {
        assertEquals(expected(10.0), kcalAt(24.0).toDouble(), 5.0)
    }

    @Test
    fun `twenty eight kilometres per hour move to twelve MET`() {
        assertEquals(expected(12.0), kcalAt(28.0).toDouble(), 5.0)
    }

    @Test
    fun `a racing pace tops out at fifteen point eight MET`() {
        assertEquals(expected(15.8), kcalAt(36.0).toDouble(), 5.0)
        // Выше верхней ступени ничего нет: 60 км/ч оплачиваются так же.
        assertEquals(kcalAt(36.0), kcalAt(60.0))
    }

    /** Ступени монотонны: быстрее — дороже, иначе таблица собрана неверно. */
    @Test
    fun `the tiers grow with speed`() {
        val ladder = listOf(14.0, 17.0, 20.0, 24.0, 28.0, 36.0).map { kcalAt(it) }
        assertEquals(ladder.sorted(), ladder)
        assertEquals(ladder.distinct(), ladder)
    }
}
