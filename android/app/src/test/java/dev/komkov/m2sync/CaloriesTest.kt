package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CaloriesTest {

    private val start: Instant = Instant.parse("2026-07-24T08:30:00Z")

    /** Заезд из [seconds] секундных точек с постоянным пульсом и скоростью. */
    private fun ride(
        seconds: Int,
        heartRate: Int? = null,
        speed: Double? = null,
        gapAfter: Int? = null,
        gapSeconds: Long = 0,
    ): FitParser.Ride {
        var t = start
        val points = (0 until seconds).map { i ->
            if (gapAfter != null && i == gapAfter) t = t.plusSeconds(gapSeconds)
            FitParser.Point(
                time = t.also { t = t.plusSeconds(1) },
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
            fileName = "test.fit",
            start = points.first().time,
            end = points.last().time,
            sport = "cycling",
            totalDistance = 1000.0,
            totalTimerTime = seconds.toDouble(),
            totalAscent = null,
            totalCalories = null,
            avgHeartRate = heartRate,
            points = points,
            activeSpans = listOf(points.first().time to points.last().time),
        )
    }

    @Test
    fun `without weight there is nothing to compute`() {
        assertNull(Calories.estimate(ride(600, heartRate = 140), null, 38, Calories.Sex.MALE))
    }

    @Test
    fun `heart rate model matches Keytel for a known case`() {
        // Keytel, мужчины: (-55.0969 + 0.6309*140 + 0.1988*80 + 0.2017*38) / 4.184
        // = 56.7977 кДж/мин = 13.575 ккал/мин; за 60 минут — 814.5 ккал.
        val kcal = Calories.estimate(ride(3601, heartRate = 140), 80.0, 38, Calories.Sex.MALE)
        assertEquals(814.5, kcal!!.toDouble(), 2.0)
    }

    @Test
    fun `women burn less than men on the same ride`() {
        val r = ride(3601, heartRate = 140)
        val male = Calories.estimate(r, 80.0, 38, Calories.Sex.MALE)!!
        val female = Calories.estimate(r, 80.0, 38, Calories.Sex.FEMALE)!!
        assertTrue("$female should be below $male", female < male)
    }

    @Test
    fun `falls back to speed when heart rate is missing`() {
        // 20 км/ч попадает в MET 8.0: 8 * 3.5 * 80 / 200 = 11.2 ккал/мин, 60 мин — 672.
        val kcal = Calories.estimate(ride(3601, speed = 5.56), 80.0, 38, Calories.Sex.MALE)
        assertEquals(672.0, kcal!!.toDouble(), 5.0)
    }

    @Test
    fun `profile is optional — speed model works without age and sex`() {
        val kcal = Calories.estimate(ride(3601, heartRate = 140, speed = 5.56), 80.0, null, null)
        assertEquals(672.0, kcal!!.toDouble(), 5.0)
    }

    @Test
    fun `a pause is not paid for`() {
        // Полчаса стоянки между точками не должны превратиться в калории.
        val moving = Calories.estimate(ride(1200, heartRate = 140), 80.0, 38, Calories.Sex.MALE)!!
        val withPause = Calories.estimate(
            ride(1200, heartRate = 140, gapAfter = 600, gapSeconds = 1800),
            80.0, 38, Calories.Sex.MALE,
        )!!
        assertEquals(moving.toDouble(), withPause.toDouble(), 15.0)
    }

    @Test
    fun `a resting pulse does not go negative`() {
        val kcal = Calories.estimate(ride(3601, heartRate = 45), 80.0, 38, Calories.Sex.MALE)
        assertTrue("$kcal must not be negative", kcal == null || kcal >= 0)
    }
}
