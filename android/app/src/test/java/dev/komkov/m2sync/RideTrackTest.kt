package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RideTrackTest {

    private val start: Instant = Instant.parse("2026-07-24T08:30:00Z")

    /** Прямой отрезок на север по 10 м в секунду, с плавным подъёмом. */
    private fun ride(
        count: Int = 60,
        withCoords: Boolean = true,
        heartRate: Int? = 140,
        cadence: Int? = 80,
        altitudeNoise: Double = 0.0,
    ): FitParser.Ride {
        val points = (0 until count).map { i ->
            FitParser.Point(
                time = start.plusSeconds(i.toLong()),
                lat = if (withCoords) 60.0 + i * 0.0001 else null,
                lon = if (withCoords) 30.0 else null,
                altitude = 100.0 + i * 0.5 + if (i % 2 == 0) altitudeNoise else -altitudeNoise,
                speed = 10.0,
                heartRate = heartRate,
                cadence = cadence,
                distance = i * 11.132,
            )
        }
        return FitParser.Ride(
            fileName = "t.fit",
            start = points.first().time,
            end = points.last().time,
            sport = "cycling",
            totalDistance = (count - 1) * 11.132,
            totalTimerTime = count.toDouble(),
            totalAscent = 30,
            totalCalories = null,
            avgHeartRate = heartRate,
            points = points,
            activeSpans = listOf(points.first().time to points.last().time),
        )
    }

    private fun summary() = RideSummary(
        file = "t.fit",
        start = start,
        distanceM = 657.0,
        elapsedMin = 1,
        movingMin = 1,
        avgHeartRate = 140,
        avgCadence = 80,
        ascent = 30,
        points = 60,
        hasRoute = true,
        imported = false,
        kcal = 42,
    )

    private fun track(vararg args: FitParser.Ride) = RideTrack.build(summary(), args.first())

    @Test
    fun `track carries every recorded point with local metres`() {
        val t = track(ride())
        assertEquals(60, t.points.size)
        assertTrue(t.hasRoute)
        // Ноль в стартовой точке: от неё и считаются метры.
        assertEquals(0.0, t.points.first().x, 1e-9)
        assertEquals(0.0, t.points.first().y, 1e-9)
        // 59 шагов по 0.0001° широты — это примерно 657 м на север.
        assertEquals(656.8, t.points.last().y, 1.0)
    }

    @Test
    fun `speed is converted from metres per second to kilometres per hour`() {
        assertEquals(36.0, track(ride()).points.first().speedKmh, 1e-6)
    }

    @Test
    fun `elapsed seconds are counted from the start of the ride`() {
        val t = track(ride())
        assertEquals(0L, t.points.first().elapsed)
        assertEquals(59L, t.points.last().elapsed)
    }

    /** Заезд без координат — не маршрут: карту и полёт для него не показываем. */
    @Test
    fun `a ride without coordinates has no route`() {
        val t = track(ride(withCoords = false))
        assertFalse(t.hasRoute)
        assertNull(t.bounds)
        assertTrue(t.points.isEmpty())
    }

    @Test
    fun `available metrics follow what the sensors recorded`() {
        val full = track(ride())
        assertTrue(full.has(TrackMetric.SPEED))
        assertTrue(full.has(TrackMetric.HEART_RATE))
        assertTrue(full.has(TrackMetric.CADENCE))
        assertTrue(full.has(TrackMetric.ELEVATION))

        val bare = track(ride(heartRate = null, cadence = null))
        assertFalse(bare.has(TrackMetric.HEART_RATE))
        assertFalse(bare.has(TrackMetric.CADENCE))
    }

    @Test
    fun `metric values are read off the right field`() {
        val t = track(ride())
        assertEquals(140.0, t.valueAt(0, TrackMetric.HEART_RATE)!!, 1e-9)
        assertEquals(80.0, t.valueAt(0, TrackMetric.CADENCE)!!, 1e-9)
        assertEquals(36.0, t.valueAt(0, TrackMetric.SPEED)!!, 1e-9)
        assertNull(t.valueAt(9999, TrackMetric.SPEED))
    }

    /** Нулевой каденс — это накат, а не значение: на графике его быть не должно. */
    @Test
    fun `zero cadence counts as no reading`() {
        val t = track(ride(cadence = 0))
        assertNull(t.valueAt(0, TrackMetric.CADENCE))
        assertFalse(t.has(TrackMetric.CADENCE))
    }

    @Test
    fun `lookup by distance finds the nearest point`() {
        val t = track(ride())
        assertEquals(0, t.indexAtDistance(-5.0))
        assertEquals(t.points.lastIndex, t.indexAtDistance(1e9))
        val mid = t.indexAtDistance(t.points[30].distance)
        assertEquals(30, mid)
    }

    /**
     * Барометр шумит на метр-два, и в полёте это видно как дрожание земли.
     * Сглаживание обязано снимать шум, не убивая сам подъём.
     */
    @Test
    fun `altitude smoothing removes noise but keeps the climb`() {
        val noisy = track(ride(altitudeNoise = 3.0))
        val clean = track(ride(altitudeNoise = 0.0))

        var jitter = 0.0
        for (i in 10 until 50) {
            jitter += kotlin.math.abs(noisy.points[i].altitude - clean.points[i].altitude)
        }
        assertTrue("остаточный шум $jitter слишком велик", jitter / 40 < 0.6)

        // Подъём на месте: за заезд высота всё так же растёт примерно на 30 м.
        val climb = noisy.points.last().altitude - noisy.points.first().altitude
        assertEquals(29.5, climb, 3.0)
    }

    @Test
    fun `summary numbers reach the track`() {
        val t = track(ride())
        assertEquals(30, t.ascent)
        assertEquals(42, t.calories)
        assertEquals(657.0, t.totalDistance, 1.0)
        assertNotNull(t.bounds)
        assertEquals(140, t.maxHeartRate)
    }
}
