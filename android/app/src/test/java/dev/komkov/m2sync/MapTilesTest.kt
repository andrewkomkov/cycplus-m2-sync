package dev.komkov.m2sync

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Подложка карты: имена и номера квадратов, кэш и обход сетки.
 *
 * Сеть тесты не трогают: всё, что должно доехать, заранее кладётся в дисковый
 * кэш, а на случай промаха прокси уводится в закрытый порт — тогда загрузка
 * падает сразу, а не висит восемь секунд на таймауте.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapTilesTest {
    private companion object {
        /** Сторона тестового тайла: рисовать его некуда, важен только цвет. */
        const val TILE = 16
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val WAIT_MS = 5_000L
    }

    private val ctx get() = RuntimeEnvironment.getApplication()
    private var proxyHost: String? = null

    @Before
    fun cutTheNetwork() {
        File(ctx.cacheDir, "tiles").deleteRecursively()
        proxyHost = System.getProperty("http.proxyHost")
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", "1")
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", "1")
    }

    @After
    fun restoreTheNetwork() {
        if (proxyHost == null) {
            System.clearProperty("http.proxyHost")
            System.clearProperty("http.proxyPort")
            System.clearProperty("https.proxyHost")
            System.clearProperty("https.proxyPort")
        } else {
            System.setProperty("http.proxyHost", proxyHost!!)
        }
    }

    private fun source() = TileSource(ctx, CoroutineScope(Dispatchers.IO))

    /** Кладёт готовый тайл ровно туда, откуда [TileSource] читает дисковый кэш. */
    private fun writeTile(
        layer: MapLayer,
        z: Int,
        x: Int,
        y: Int,
        color: Int,
    ) {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(ctx.cacheDir, "tiles/${layer.name.lowercase()}/$z/$x/$y.png")
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.writeBytes(bytes.toByteArray())
    }

    /** Загрузка идёт в своей корутине, поэтому кадр ждёт её результата. */
    private fun awaitTiles(
        tiles: TileSource,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (tiles.revision < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("тайлы не доехали с диска: ${tiles.revision} из $count", tiles.revision >= count)
    }

    // --- слои ---

    @Test
    fun `each provider gets the tile numbers in the order it wants them`() {
        assertEquals("https://tile.openstreetmap.org/16/12/34.png", MapLayer.MAP.urlOf(16, 12, 34))
        // World Imagery ждёт z/y/x, поэтому номера в адресе меняются местами.
        assertTrue(MapLayer.SATELLITE.urlOf(16, 12, 34)!!.endsWith("/16/34/12"))
        assertNull(MapLayer.NONE.urlOf(16, 12, 34))
        assertFalse(MapLayer.NONE.enabled)
        assertTrue(MapLayer.MAP.enabled)
        assertTrue(MapLayer.SATELLITE.enabled)
    }

    @Test
    fun `the basemap button walks all three layers in a circle`() {
        assertEquals(MapLayer.MAP, MapLayer.NONE.next())
        assertEquals(MapLayer.SATELLITE, MapLayer.MAP.next())
        assertEquals(MapLayer.NONE, MapLayer.SATELLITE.next())
    }

    /** Ключ кэша упаковывает слой, зум и номер в одно число — и не имеет права пересечься. */
    @Test
    fun `cache keys stay unique across layers, zooms and squares`() {
        val keys = HashSet<Long>()
        var count = 0
        for (layer in MapLayer.entries) {
            for (z in intArrayOf(2, 16, 18)) {
                for (x in intArrayOf(0, 1, 4095, (1 shl z) - 1)) {
                    for (y in intArrayOf(0, 1, 4095)) {
                        keys += TileSource.key(layer, z, x, y)
                        count++
                    }
                }
            }
        }
        assertEquals(count, keys.size)
    }

    // --- кэш ---

    @Test
    fun `a square outside the grid never turns into a request`() {
        val tiles = source()
        assertNull(tiles.tile(MapLayer.NONE, FLY_GROUND_ZOOM, 0, 0))
        assertNull(tiles.tile(MapLayer.MAP, GeoBounds.MIN_ZOOM - 1, 0, 0))
        assertNull(tiles.tile(MapLayer.MAP, GeoBounds.MAX_ZOOM + 1, 0, 0))
        assertNull(tiles.tile(MapLayer.MAP, 4, -1, 0))
        assertNull(tiles.tile(MapLayer.MAP, 4, 0, -1))
        assertNull(tiles.tile(MapLayer.MAP, 4, 1 shl 4, 0))
        assertNull(tiles.tile(MapLayer.MAP, 4, 0, 1 shl 4))
        assertEquals("ни один запрос не должен был уехать", 0, tiles.revision)
    }

    /** Второй просмотр заезда сети не требует: квадрат уже лежит на диске. */
    @Test
    fun `a tile cached on disk arrives without the network`() {
        writeTile(MapLayer.MAP, FLY_GROUND_ZOOM, 100, 200, GREEN)
        val tiles = source()

        assertNull("первый запрос только ставит тайл в очередь", tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 100, 200))
        awaitTiles(tiles, 1)

        val tile = tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 100, 200)
        assertNotNull(tile)
        assertEquals(GREEN, tile!!.bitmap.getPixel(1, 1))
        assertSame("повтор обязан браться из памяти", tile, tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 100, 200))
        assertEquals("память не считается новой доставкой", 1, tiles.revision)
    }

    /** Схема и снимок — разные картинки на один и тот же номер квадрата. */
    @Test
    fun `layers keep their own squares apart`() {
        writeTile(MapLayer.MAP, FLY_GROUND_ZOOM, 5, 6, GREEN)
        writeTile(MapLayer.SATELLITE, FLY_GROUND_ZOOM, 5, 6, BLUE)
        val tiles = source()

        tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 5, 6)
        tiles.tile(MapLayer.SATELLITE, FLY_GROUND_ZOOM, 5, 6)
        awaitTiles(tiles, 2)

        assertEquals(GREEN, tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 5, 6)!!.bitmap.getPixel(1, 1))
        assertEquals(BLUE, tiles.tile(MapLayer.SATELLITE, FLY_GROUND_ZOOM, 5, 6)!!.bitmap.getPixel(1, 1))
    }

    /** Прогрев кладёт коридор маршрута в память до того, как его попросит кадр. */
    @Test
    fun `prefetch warms the route before the frame asks for it`() {
        val packed = listOf(pack(10, 20), pack(11, 20), pack(12, 20))
        for (p in packed) writeTile(MapLayer.MAP, FLY_GROUND_ZOOM, (p shr 32).toInt(), p.toInt(), GREEN)
        val tiles = source()

        tiles.prefetch(MapLayer.MAP, FLY_GROUND_ZOOM, packed)
        awaitTiles(tiles, packed.size)

        assertNotNull(tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 10, 20))
        assertNotNull(tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 11, 20))
        assertNotNull(tiles.tile(MapLayer.MAP, FLY_GROUND_ZOOM, 12, 20))
    }

    /** Выключенная подложка — это полностью офлайновый заезд, без единого запроса. */
    @Test
    fun `prefetch keeps quiet when the basemap is off`() {
        writeTile(MapLayer.MAP, FLY_GROUND_ZOOM, 10, 20, GREEN)
        val tiles = source()

        tiles.prefetch(MapLayer.NONE, FLY_GROUND_ZOOM, listOf(pack(10, 20)))
        Thread.sleep(200)

        assertEquals(0, tiles.revision)
    }

    @Test
    fun `a tile hands the same picture to the flat map and to the flight`() {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(GREEN)
        val tile = MapTile(bitmap)

        assertEquals(TILE, tile.image.width)
        assertEquals(TILE, tile.image.height)
        assertSame("картинка считается один раз", tile.image, tile.image)
    }

    // --- обход сетки ---

    @Test
    fun `the sweep covers the rectangle row by row`() {
        val seen = ArrayList<Pair<Int, Int>>()
        forEachTile(4, minTileX = 1.2, maxTileX = 3.9, minTileY = 2.1, maxTileY = 3.4) { x, y -> seen += x to y }

        assertEquals(listOf(1 to 2, 2 to 2, 3 to 2, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    /** По долготе мир бесконечен, по широте — нет: строки за краем отсекаются. */
    @Test
    fun `the sweep clips rows to the grid but leaves columns as they are`() {
        val seen = ArrayList<Pair<Int, Int>>()
        forEachTile(1, minTileX = -1.5, maxTileX = 0.5, minTileY = -3.0, maxTileY = 9.0) { x, y -> seen += x to y }

        assertEquals(listOf(-2 to 0, -1 to 0, 0 to 0, -2 to 1, -1 to 1, 0 to 1), seen)
    }

    @Test
    fun `the world wraps round by longitude`() {
        assertEquals(15, wrapTileX(-1, 4))
        assertEquals(14, wrapTileX(-2, 4))
        assertEquals(0, wrapTileX(16, 4))
        assertEquals(1, wrapTileX(17, 4))
        assertEquals(7, wrapTileX(7, 4))
    }

    // --- квадраты маршрута ---

    @Test
    fun `route squares follow the ride without repeats`() {
        val track = track((0..40).map { point(lat = 59.9, lon = 30.0 + it * 0.01, index = it) })
        val squares = routeTiles(track, 12, ring = 0)

        assertEquals("повторы означали бы лишние запросы", squares.distinct(), squares)
        assertTrue("ход на восток обязан набирать колонки", squares.size > 1)
        assertEquals(Geo.tileX(30.0, 12).toInt(), (squares.first() shr 32).toInt())
        assertEquals(Geo.tileY(59.9, 12).toInt(), squares.first().toInt())
        // Порядок движения: прогрев начинается с начала заезда, а не с конца.
        val columns = squares.map { (it shr 32).toInt() }
        assertEquals(columns.sorted(), columns)
    }

    /** Камера смотрит и по сторонам, поэтому одной нитки вдоль трека мало. */
    @Test
    fun `the ring adds the neighbours around every point`() {
        val track = track(listOf(point(lat = 59.9, lon = 30.0, index = 0)))

        assertEquals(1, routeTiles(track, 12, ring = 0).size)
        assertEquals(9, routeTiles(track, 12, ring = 1).size)
        assertEquals(25, routeTiles(track, 12, ring = 2).size)
    }

    @Test
    fun `route squares drop the rows that fall off the grid`() {
        // У самого верха меркатора соседей сверху нет — строка −1 не существует.
        val track = track(listOf(point(lat = 85.0, lon = 0.0, index = 0)))
        val squares = routeTiles(track, 2, ring = 1)

        assertEquals(6, squares.size)
        assertTrue("строк за краем сетки быть не может", squares.all { it.toInt() in 0..1 })
    }

    // --- заготовки ---

    private fun pack(
        x: Int,
        y: Int,
    ): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun point(
        lat: Double,
        lon: Double,
        index: Int,
    ) = TrackPoint(
        lat = lat,
        lon = lon,
        x = index * 10.0,
        y = 0.0,
        altitude = 100.0,
        distance = index * 10.0,
        speedKmh = 20.0,
        heartRate = 130,
        cadence = 70,
        elapsed = index.toLong(),
    )

    private fun track(points: List<TrackPoint>) =
        RideTrack(
            summary =
                RideSummary(
                    file = "t.fit",
                    start = Instant.parse("2026-07-25T10:00:00Z"),
                    distanceM = 1000.0,
                    elapsedMin = 2,
                    movingMin = 2,
                    avgHeartRate = 130,
                    avgCadence = 70,
                    ascent = 10,
                    points = points.size,
                    hasRoute = true,
                    imported = false,
                ),
            points = points,
            bounds = GeoBounds.of(points),
            totalDistance = points.last().distance,
            duration = Duration.ofSeconds(points.size.toLong()),
            start = Instant.parse("2026-07-25T10:00:00Z"),
            ascent = 10,
            calories = 200,
        )
}
