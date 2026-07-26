package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `tile coordinates round-trip through longitude and latitude`() {
        for (z in intArrayOf(4, 12, 16, 18)) {
            val x = Geo.tileX(30.3141, z)
            val y = Geo.tileY(59.9386, z)
            assertEquals(30.3141, Geo.lonOfTileX(x, z), 1e-9)
            assertEquals(59.9386, Geo.latOfTileY(y, z), 1e-9)
        }
    }

    /** Нулевой меридиан и экватор — середина мира на любом зуме. */
    @Test
    fun `world centre sits at the middle of the grid`() {
        assertEquals(8.0, Geo.tileX(0.0, 4), 1e-9)
        assertEquals(8.0, Geo.tileY(0.0, 4), 1e-9)
    }

    /** За полюсами меркатор не определён, поэтому широта режется по ±85.05°. */
    @Test
    fun `poles are clamped instead of running to infinity`() {
        val top = Geo.tileY(90.0, 10)
        assertTrue(top.isFinite())
        assertEquals(Geo.tileY(85.05112878, 10), top, 1e-6)
    }

    @Test
    fun `a degree of longitude shrinks towards the poles`() {
        assertEquals(111_320.0, Geo.metersPerDegLon(0.0), 1.0)
        assertTrue(Geo.metersPerDegLon(60.0) < Geo.metersPerDegLon(0.0) * 0.51)
    }

    @Test
    fun `local metres grow east and north from the origin`() {
        val east = Geo.toLocalX(30.01, 30.0, 60.0)
        val north = Geo.toLocalY(60.01, 60.0)
        assertTrue(east > 0)
        assertEquals(1113.2, north, 0.1)
    }

    // --- подбор зума ---

    private fun track(vararg coords: Pair<Double, Double>): List<TrackPoint> =
        coords.map { (lat, lon) ->
            TrackPoint(lat, lon, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, 0)
        }

    @Test
    fun `bounds cover every point`() {
        val b = GeoBounds.of(track(59.9 to 30.1, 60.1 to 30.5, 60.0 to 30.3))!!
        assertEquals(59.9, b.minLat, 1e-9)
        assertEquals(60.1, b.maxLat, 1e-9)
        assertEquals(30.1, b.minLon, 1e-9)
        assertEquals(30.5, b.maxLon, 1e-9)
        assertEquals(60.0, b.centerLat, 1e-9)
    }

    /** Стоянка на месте не должна вырождать прямоугольник и ломать подбор зума. */
    @Test
    fun `a standstill still gets a usable rectangle`() {
        val b = GeoBounds.of(track(60.0 to 30.0, 60.0 to 30.0))!!
        assertTrue(b.maxLat - b.minLat >= 1e-4)
        assertTrue(b.maxLon - b.minLon >= 1e-4)
        assertTrue(b.fitZoom(1000f, 1000f) <= GeoBounds.MAX_ZOOM)
    }

    @Test
    fun `bounds of nothing are nothing`() {
        assertEquals(null, GeoBounds.of(emptyList()))
    }

    /** Чем меньше кадр, тем дальше приходится отъезжать. */
    @Test
    fun `fit zoom shrinks with the viewport`() {
        val b = GeoBounds.of(track(59.9 to 30.1, 60.1 to 30.5))!!
        val big = b.fitZoom(2000f, 2000f)
        val small = b.fitZoom(200f, 200f)
        assertTrue("$big must exceed $small", big > small)
    }

    /** На подобранном зуме маршрут обязан помещаться в кадр целиком. */
    @Test
    fun `the route fits the frame at the chosen zoom`() {
        val b = GeoBounds.of(track(59.90 to 30.10, 60.05 to 30.40))!!
        val width = 1080f
        val height = 720f
        val z = b.fitZoom(width, height)
        val w = (Geo.tileX(b.maxLon, z) - Geo.tileX(b.minLon, z)) * Geo.TILE_PX
        val h = (Geo.tileY(b.minLat, z) - Geo.tileY(b.maxLat, z)) * Geo.TILE_PX
        assertTrue("ширина $w > $width", w <= width)
        assertTrue("высота $h > $height", h <= height)
    }

    @Test
    fun `longitude wraps around the world`() {
        assertEquals(15, wrapTileX(-1, 4))
        assertEquals(0, wrapTileX(16, 4))
        assertEquals(7, wrapTileX(7, 4))
    }

    /** Обход отдаёт каждый квадрат ровно один раз. */
    @Test
    fun `tile walk covers the rectangle without repeats`() {
        val seen = ArrayList<Pair<Int, Int>>()
        forEachTile(z = 10, minTileX = 3.2, maxTileX = 5.7, minTileY = 8.1, maxTileY = 9.9) { x, y ->
            seen += x to y
        }
        assertEquals(listOf(3 to 8, 4 to 8, 5 to 8, 3 to 9, 4 to 9, 5 to 9), seen)
        assertEquals(seen.size, seen.toSet().size)
    }
}
