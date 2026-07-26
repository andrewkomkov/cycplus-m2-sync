package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyCameraTest {

    private val width = 1080f
    private val height = 2400f

    /** Камера в начале координат смотрит строго на восток. */
    private fun camera(eye: Vec3 = Vec3(0.0, 0.0, 0.0), target: Vec3 = Vec3(100.0, 0.0, 0.0)) =
        FlyCamera(eye, target, width, height)

    @Test
    fun `a point straight ahead lands in the centre of the frame`() {
        val at = camera().project(Vec3(50.0, 0.0, 0.0))
        assertNotNull(at)
        assertEquals(width / 2, at!!.x, 0.01f)
        assertEquals(height / 2, at.y, 0.01f)
    }

    @Test
    fun `what is behind the camera is not drawn`() {
        assertNull(camera().project(Vec3(-10.0, 0.0, 0.0)))
        assertNull(camera().project(Vec3(FlyCamera.NEAR / 2, 0.0, 0.0)))
        assertNotNull(camera().project(Vec3(FlyCamera.NEAR + 0.1, 0.0, 0.0)))
    }

    /** Правый вектор горизонтален, поэтому крена нет и горизонт — прямая. */
    @Test
    fun `the basis is orthonormal and never rolls`() {
        val cam = camera(target = Vec3(100.0, 40.0, -20.0))
        assertEquals(0.0, cam.right.z, 1e-9)
        assertEquals(0.0, cam.forward.dot(cam.right), 1e-9)
        assertEquals(0.0, cam.forward.dot(cam.up), 1e-9)
        assertEquals(1.0, cam.forward.dot(cam.forward), 1e-9)
        assertEquals(1.0, cam.right.dot(cam.right), 1e-9)
    }

    /** На север от камеры, смотрящей на восток, — это влево по экрану. */
    @Test
    fun `screen axes follow the camera, not the world`() {
        val cam = camera()
        val north = cam.project(Vec3(50.0, 20.0, 0.0))!!
        val south = cam.project(Vec3(50.0, -20.0, 0.0))!!
        val above = cam.project(Vec3(50.0, 0.0, 20.0))!!
        assertTrue(north.x < width / 2)
        assertTrue(south.x > width / 2)
        assertTrue(above.y < height / 2)
    }

    /** Дальше — мельче: ровно то, ради чего перспектива и нужна. */
    @Test
    fun `equal offsets shrink with distance`() {
        val cam = camera()
        val near = cam.project(Vec3(20.0, 10.0, 0.0))!!
        val far = cam.project(Vec3(200.0, 10.0, 0.0))!!
        assertTrue(
            "ближняя точка должна отъехать от центра сильнее",
            kotlin.math.abs(near.x - width / 2) > kotlin.math.abs(far.x - width / 2) * 5,
        )
    }

    @Test
    fun `a metre on screen shrinks with distance too`() {
        val cam = camera()
        assertTrue(cam.pixelsPerMeter(Vec3(20.0, 0.0, 0.0)) > cam.pixelsPerMeter(Vec3(200.0, 0.0, 0.0)))
        assertEquals(0f, cam.pixelsPerMeter(Vec3(-5.0, 0.0, 0.0)), 0f)
    }

    /** Смотрим сверху вниз — горизонт уезжает выше середины кадра. */
    @Test
    fun `looking down puts the horizon above the centre`() {
        val down = FlyCamera(Vec3(0.0, 0.0, 30.0), Vec3(100.0, 0.0, 0.0), width, height)
        assertTrue(down.horizonY < height / 2)
        val level = camera()
        assertEquals(height / 2, level.horizonY, 0.5f)
    }

    // --- обрезка по ближней плоскости ---

    @Test
    fun `a segment crossing the camera is clipped, not dropped`() {
        val cam = camera()
        val cut = cam.segment(Vec3(-50.0, 5.0, 0.0), Vec3(50.0, 5.0, 0.0))
        assertNotNull("отрезок сквозь камеру обязан остаться видимым", cut)
    }

    @Test
    fun `a segment entirely behind the camera is dropped`() {
        assertNull(camera().segment(Vec3(-50.0, 5.0, 0.0), Vec3(-10.0, 5.0, 0.0)))
    }

    // --- ход по треку ---

    private fun track(): RideTrack {
        val points = (0..100).map { i ->
            TrackPoint(
                lat = 60.0 + i * 1e-4,
                lon = 30.0,
                x = 0.0,
                y = i * 10.0,
                altitude = 100.0 + i,
                distance = i * 10.0,
                speedKmh = 20.0,
                heartRate = 130,
                cadence = 70,
                elapsed = i.toLong(),
            )
        }
        return RideTrack(
            summary = summary(),
            points = points,
            bounds = GeoBounds.of(points),
            totalDistance = 1000.0,
            duration = java.time.Duration.ofSeconds(100),
            start = java.time.Instant.parse("2026-07-25T10:00:00Z"),
            ascent = 100,
            calories = 200,
        )
    }

    private fun summary() = RideSummary(
        file = "t.fit",
        start = java.time.Instant.parse("2026-07-25T10:00:00Z"),
        distanceM = 1000.0,
        elapsedMin = 2,
        movingMin = 2,
        avgHeartRate = 130,
        avgCadence = 70,
        ascent = 100,
        points = 101,
        hasRoute = true,
        imported = false,
    )

    @Test
    fun `pose interpolates between recorded points`() {
        val at = track().poseAt(55.0)
        assertEquals(55.0, at.y, 1e-6)
        assertEquals(105.5, at.z, 1e-6)
    }

    /** До старта и после финиша камере всё равно надо где-то стоять. */
    @Test
    fun `pose keeps going straight beyond both ends`() {
        val t = track()
        assertEquals(-60.0, t.poseAt(-60.0).y, 1e-6)
        assertEquals(1100.0, t.poseAt(1100.0).y, 1e-6)
    }

    @Test
    fun `the camera trails the rider and looks ahead`() {
        val t = track()
        val cam = flyCamera(t, 500.0, width, height)
        val rider = t.poseAt(500.0)
        assertTrue("камера должна быть позади", cam.eye.y < rider.y)
        assertTrue("и выше трека", cam.eye.z > rider.z + 10)
        assertTrue("и смотреть вперёд по треку", cam.forward.y > 0.5)
    }

    /** Райдер обязан оставаться в кадре — иначе смотреть полёт бессмысленно. */
    @Test
    fun `the rider stays on screen through the whole flight`() {
        val t = track()
        for (meters in 0..1000 step 25) {
            val cam = flyCamera(t, meters.toDouble(), width, height)
            val p = t.poseAt(meters.toDouble())
            val at = cam.project(Vec3(p.x, p.y, p.z + 1.6))
            assertNotNull("на отметке $meters райдер пропал", at)
            assertTrue("на отметке $meters райдер ушёл за край", at!!.x in 0f..width)
            assertTrue("на отметке $meters райдер ушёл по вертикали", at.y in 0f..height)
        }
    }
}
