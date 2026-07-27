package dev.komkov.m2sync

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Кадр полёта целиком: небо, земля, натянутые тайлы, лента трека и метка райдера.
 *
 * Сцена рисуется в настоящий bitmap (`@GraphicsMode(NATIVE)`), поэтому проверять
 * можно то же, что видит глаз: где проходит горизонт, чем застелена земля, где
 * оказалась метка. Тайлы для этого заранее кладутся в дисковый кэш — ровно те
 * квадраты, которые кадр и запросит, так что в сеть тесты не ходят.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlySceneTest {
    private companion object {
        const val W = 240
        const val H = 400

        /** Дальность обзора сцены в метрах: константа в исходнике приватная. */
        const val FAR = 1400.0

        val SKY = Color(0xFFFF0000)
        val GROUND = Color(0xFF0000FF)
        val GRID = Color(0xFF00FF00)
        val RIBBON = Color(0xFFFFFF00)
        val MARKER = Color(0xFFFF00FF)
        val MILESTONE = Color(0xFF00FFFF)

        /** Цвет тестового тайла: серый ни с чем в палитре не пересекается. */
        const val TILE_COLOR = 0xFF808080.toInt()
        const val TILE_PX = 16
        const val WAIT_MS = 10_000L
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** Источник, который заведомо ничего не грузит: область корутин закрыта. */
    private fun idleTiles() = TileSource(ctx, CoroutineScope(Job().also { it.cancel() }))

    @Before
    fun clearCache() {
        File(ctx.cacheDir, "tiles").deleteRecursively()
    }

    // --- горизонт и слои кадра ---

    /** Порядок рисования и есть глубина: небо сверху, земля снизу, стык — горизонт. */
    @Test
    fun `the horizon splits the sky from the ground`() {
        val track = track()
        val camera = camera(track, 500.0)
        val frame = render(track, 500.0, palette(), MapLayer.NONE, idleTiles())
        val horizon = camera.horizonY.toInt()

        assertTrue("горизонт обязан попасть в кадр", horizon in 1 until H - 1)
        for (x in 0 until W) {
            assertEquals("над горизонтом только небо", SKY.toArgb(), frame.at(x, horizon - 2))
            assertEquals("под горизонтом только земля", GROUND.toArgb(), frame.at(x, horizon + 2))
        }
    }

    /** Запасная сетка — только на случай, когда подложки нет. */
    @Test
    fun `the fallback grid is drawn when there is no basemap`() {
        val track = track()
        val frame = render(track, 500.0, palette(grid = GRID), MapLayer.NONE, idleTiles())

        assertTrue("сетка по сто метров должна быть видна", frame.count(::greenish) > 0)
    }

    @Test
    fun `laid tiles replace the fallback grid`() {
        val track = track()
        val camera = camera(track, 500.0)
        val tiles = tilesAround(track, camera)
        val frame = render(track, 500.0, palette(grid = GRID), MapLayer.MAP, tiles)

        assertTrue("земля должна быть застелена тайлами", frame.count(::grayish) > W * H / 20)
        assertEquals("поверх тайлов сетка не нужна", 0, frame.count(::greenish))
    }

    /**
     * Тайл, задевающий камеру, целиком выбрасывать нельзя: под ногами зияла бы
     * дыра в полкилометра. Он раскладывается по ячейкам — и низ кадра застелен.
     */
    @Test
    fun `the tile under the camera is laid cell by cell`() {
        val track = track()
        val camera = camera(track, 500.0)
        val tiles = tilesAround(track, camera)
        val frame = render(track, 500.0, palette(), MapLayer.MAP, tiles)

        val bottom = frame.count(::grayish, rows = (H - 20) until H)
        assertTrue("под самой камерой земли не оказалось", bottom > 0)
    }

    /** Земля обязана кончаться на горизонте: выше начинается небо. */
    @Test
    fun `the ground never leaks above the horizon`() {
        val track = track()
        val camera = camera(track, 500.0)
        val tiles = tilesAround(track, camera)
        val frame = render(track, 500.0, palette(), MapLayer.MAP, tiles)
        val horizon = camera.horizonY.toInt()

        assertEquals(0, frame.count(::grayish, rows = 0 until (horizon - 4).coerceAtLeast(1)))
    }

    /** Дымка у горизонта прячет край тайлов, но землю под ногами не трогает. */
    @Test
    fun `the haze thickens at the horizon and clears towards the camera`() {
        val track = track()
        val camera = camera(track, 500.0)
        val palette = palette(fogAlpha = 0.7f)
        val frame = render(track, 500.0, palette, MapLayer.NONE, idleTiles())
        val horizon = camera.horizonY.toInt()

        // Дымка — это небо, налитое поверх земли: чем ближе к горизонту, тем краснее.
        val near = red(frame.at(W / 2, H - 2))
        val far = red(frame.at(W / 2, horizon + 3))
        assertTrue("у горизонта земля должна уходить в цвет неба", far > 120)
        assertTrue("под ногами дымки быть не должно", near < 40)
    }

    // --- трек ---

    /** Метка райдера стоит ровно там, куда камера проецирует его самого. */
    @Test
    fun `the rider marker sits where the camera projects him`() {
        val track = track()
        val camera = camera(track, 500.0)
        val frame = render(track, 500.0, palette(marker = MARKER), MapLayer.NONE, idleTiles())

        val rider = track.poseAt(500.0)
        val at = camera.project(Vec3(rider.x, rider.y, rider.z + 1.6))
        assertNotNull(at)

        val (markerX, markerY) = frame.centroid(::magenta)
        assertEquals(at!!.x, markerX, 4f)
        assertEquals(at.y, markerY, 4f)
    }

    /** Столбики стоят на круглых километрах и только в пределах заезда. */
    @Test
    fun `the mile posts stop where the ride ends`() {
        val long = track()
        // Тот же трек, но заезд кончился раньше километровой отметки.
        val short = track(totalDistance = 600.0)
        val palette = palette(milestone = MILESTONE)
        // Подъезжаем к отметке вплотную: издали двенадцать метров столбика
        // занимают три пикселя и прячутся под самой лентой.
        val camera = camera(long, 900.0)

        val whole = render(long, 900.0, palette, MapLayer.NONE, idleTiles())
        val cut = render(short, 900.0, palette, MapLayer.NONE, idleTiles())

        assertTrue("столбик первого километра обязан стоять", whole.count(::cyanish) > 0)
        assertEquals("за финишем столбиков нет", 0, cut.count(::cyanish))

        // И стоит он ровно на треке, а не где придётся.
        val post = long.poseAt(1000.0)
        val at = camera.project(Vec3(post.x, post.y, post.z))
        assertNotNull(at)
        assertEquals(at!!.x, whole.centroidX(::cyanish), 3f)
    }

    /** Лента идёт по треку от самой камеры до горизонта и сходится к нему. */
    @Test
    fun `the ribbon runs along the track and narrows towards the horizon`() {
        val track = track()
        val camera = camera(track, 500.0)
        val frame = render(track, 500.0, palette(ribbon = RIBBON), MapLayer.NONE, idleTiles())
        val horizon = camera.horizonY.toInt()
        val depth = H - horizon

        val near = frame.count(::yellowish, rows = (horizon + depth * 3 / 4) until H)
        val far = frame.count(::yellowish, rows = (horizon + 2) until (horizon + depth / 6))

        assertTrue("под камерой лента широкая", near > 400)
        assertTrue("у горизонта она обязана быть на месте, а не обрываться", far > 0)
        assertTrue("и заметно уже: $near против $far", near > far * 3)
    }

    @Test
    fun `a single point makes no ribbon`() {
        val one = track(points = listOf(point(0)))
        val palette = palette(ribbon = RIBBON, marker = MARKER)
        val frame = render(one, 0.0, palette, MapLayer.NONE, idleTiles())

        assertEquals("ленту не из чего строить", 0, frame.count(::yellowish))
        assertTrue("а метка райдера всё равно на месте", frame.count(::magenta) > 0)
    }

    /** Заезд без координат: рисовать нечего, но кадр обязан остаться целым. */
    @Test
    fun `an empty track falls back to the grid even with the basemap on`() {
        val empty = track(points = emptyList())
        val frame = render(empty, 0.0, palette(grid = GRID), MapLayer.MAP, idleTiles())

        assertTrue("без точек тайлы не к чему привязать — остаётся сетка", frame.count(::greenish) > 0)
        assertEquals(0, frame.count(::grayish))
    }

    // --- палитра и фильтр земли ---

    @Test
    fun `the ribbon colour walks from slow through mid to fast`() {
        val ramp = ramp()

        assertEquals("медленно — один конец палитры", 0f, ramp.speedColor(0.0, 40.0).red, 0.02f)
        assertEquals("половина хода — середина", 0.5f, ramp.speedColor(20.0, 40.0).red, 0.03f)
        assertEquals("быстро — другой конец", 1f, ramp.speedColor(40.0, 40.0).red, 0.02f)
        assertEquals("быстрее максимума не бывает", ramp.speedColor(40.0, 40.0), ramp.speedColor(400.0, 40.0))
        assertTrue(
            "цвет обязан идти по палитре монотонно",
            ramp.speedColor(10.0, 40.0).red > ramp.speedColor(5.0, 40.0).red,
        )
    }

    /** Стоянка на месте: делить не на что, поэтому весь трек красится серединой. */
    @Test
    fun `a ride that never moved is painted with the middle colour`() {
        val ramp = ramp()

        assertEquals(ramp.mid, ramp.speedColor(0.0, 0.0))
        assertEquals(ramp.mid, ramp.speedColor(0.5, 1.0))
    }

    /** Тайлы приглушаются, чтобы лента трека оставалась главной. */
    @Test
    fun `the ground filter dims the map and leaves the satellite alone`() {
        val red = 0xFFFF0000.toInt()
        val mapDark = filtered(red, groundPaint(dark = true, layer = MapLayer.MAP))
        val mapLight = filtered(red, groundPaint(dark = false, layer = MapLayer.MAP))
        val satelliteDark = filtered(red, groundPaint(dark = true, layer = MapLayer.SATELLITE))

        // Чистый красный после фильтра перестаёт быть чистым — это и есть приглушение.
        assertTrue("приглушение обязано снимать насыщенность", green(mapDark) > 5)
        assertTrue("и заодно притемнять", red(mapDark) < 200)
        assertTrue("тёмная тема гасит карту сильнее светлой", red(mapLight) > red(mapDark))
        val mapSaturation = red(mapDark) - green(mapDark)
        val satelliteSaturation = red(satelliteDark) - green(satelliteDark)
        assertTrue("снимку приглушение почти не нужно", satelliteSaturation > mapSaturation)
    }

    @Test
    fun `the ground paint keeps the tiles smooth`() {
        val paint = groundPaint(dark = false, layer = MapLayer.MAP)

        assertTrue(paint.isAntiAlias)
        assertTrue(paint.isFilterBitmap)
        assertTrue(paint.isDither)
        assertNotNull(paint.colorFilter)
    }

    // --- ручная камера ---

    /** Отвернувшись назад, смотреть на дорогу впереди незачем: взгляд съезжает на райдера. */
    @Test
    fun `turning the camera round makes it look back at the rider`() {
        val track = track()
        val rider = track.poseAt(500.0)
        val camera = flyCamera(track, 500.0, W.toFloat(), H.toFloat(), yaw = Math.PI.toFloat())

        assertTrue("камера должна оказаться впереди райдера", camera.eye.y > rider.y)
        assertTrue("и смотреть назад по треку", camera.forward.y < 0)
        assertNotNull("райдер обязан остаться в кадре", camera.project(Vec3(rider.x, rider.y, rider.z + 1.6)))
    }

    @Test
    fun `pulling the camera away lifts it as well`() {
        val track = track()
        val rider = track.poseAt(500.0)
        val close = flyCamera(track, 500.0, W.toFloat(), H.toFloat())
        val far = flyCamera(track, 500.0, W.toFloat(), H.toFloat(), distanceScale = 2f)
        val high = flyCamera(track, 500.0, W.toFloat(), H.toFloat(), heightScale = 2f)

        val closeAway = abs(close.eye.y - rider.y)
        val farAway = abs(far.eye.y - rider.y)
        assertEquals("удаление ровно вдвое", 2.0, farAway / closeAway, 0.05)
        assertEquals("и высота вместе с ним", 2.0, (far.eye.z - rider.z) / (close.eye.z - rider.z), 0.05)
        assertEquals("подъём отдельно от удаления", 2.0, (high.eye.z - rider.z) / (close.eye.z - rider.z), 0.05)
        assertEquals("а расстояние при этом прежнее", closeAway, abs(high.eye.y - rider.y), 0.5)
    }

    // --- отрисовка ---

    private fun render(
        track: RideTrack,
        meters: Double,
        palette: FlyPalette,
        layer: MapLayer,
        tiles: TileSource,
    ): IntArray {
        val camera = camera(track, meters)
        val image = ImageBitmap(W, H)
        val size = Size(W.toFloat(), H.toFloat())
        // Тайлы кладём обычной кистью: приглушение искажало бы цвета проверок.
        val paint = Paint()
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(image), size) {
            drawFlyScene(track, camera, meters, tiles, palette, paint, layer)
        }
        val pixels = IntArray(W * H)
        image.asAndroidBitmap().getPixels(pixels, 0, W, 0, 0, W, H)
        return pixels
    }

    /** Камера кадра: та же, что построит экран полёта на этой отметке. */
    private fun camera(
        track: RideTrack,
        meters: Double,
    ): FlyCamera = flyCamera(track, meters, W.toFloat(), H.toFloat())

    /** Прогоняет цвет через фильтр земли и возвращает то, что легло на холст. */
    private fun filtered(
        color: Int,
        paint: Paint,
    ): Int {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.eraseColor(color)
        val target = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(target).drawBitmap(source, 0f, 0f, paint)
        return target.getPixel(0, 0)
    }

    // --- тайлы под кадром ---

    /**
     * Кладёт в дисковый кэш все квадраты, которые кадр может запросить, и ждёт,
     * пока они доедут в память. Границы считаются так же, как в самой сцене:
     * коробка вокруг точки в трети пути до горизонта, плюс запас в один тайл.
     */
    private fun tilesAround(
        track: RideTrack,
        camera: FlyCamera,
    ): TileSource {
        val origin = track.points.first()
        val ahead = Vec3(camera.forward.x, camera.forward.y, 0.0).normalized()
        val centerX = camera.eye.x + ahead.x * FAR * 0.35
        val centerY = camera.eye.y + ahead.y * FAR * 0.35
        val half = FAR * 0.62
        val metersPerLon = Geo.metersPerDegLon(origin.lat)

        val minLon = origin.lon + (centerX - half) / metersPerLon
        val maxLon = origin.lon + (centerX + half) / metersPerLon
        val minLat = origin.lat + (centerY - half) / 111_320.0
        val maxLat = origin.lat + (centerY + half) / 111_320.0

        val packed = ArrayList<Long>()
        val fromX = floor(Geo.tileX(minLon, FLY_GROUND_ZOOM)).toInt() - 1
        val toX = floor(Geo.tileX(maxLon, FLY_GROUND_ZOOM)).toInt() + 1
        val fromY = floor(Geo.tileY(maxLat, FLY_GROUND_ZOOM)).toInt() - 1
        val toY = floor(Geo.tileY(minLat, FLY_GROUND_ZOOM)).toInt() + 1
        for (y in fromY..toY) {
            for (x in fromX..toX) {
                writeTile(x, y)
                packed += (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
            }
        }

        val tiles = TileSource(ctx, CoroutineScope(Dispatchers.IO))
        // Прогрев идёт одной корутиной по порядку, поэтому счётчик доставленных
        // тайлов растёт ровно на каждый файл. Спрашивать сами тайлы, пока они
        // едут, нельзя: запрос лезет в те же кэши, что и загрузчик.
        tiles.prefetch(MapLayer.MAP, FLY_GROUND_ZOOM, packed)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (tiles.revision < packed.size && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("тайлы не доехали с диска", packed.size, tiles.revision)
        return tiles
    }

    private fun writeTile(
        x: Int,
        y: Int,
    ) {
        val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(TILE_COLOR)
        val name = MapLayer.MAP.name.lowercase()
        val file = File(ctx.cacheDir, "tiles/$name/$FLY_GROUND_ZOOM/${wrapTileX(x, FLY_GROUND_ZOOM)}/$y.png")
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.writeBytes(bytes.toByteArray())
    }

    // --- разбор кадра ---

    private fun IntArray.at(
        x: Int,
        y: Int,
    ) = this[y * W + x]

    private fun IntArray.count(
        test: (Int) -> Boolean,
        rows: IntRange = 0 until H,
    ): Int {
        var count = 0
        for (y in rows) {
            for (x in 0 until W) if (test(at(x, y))) count++
        }
        return count
    }

    /** Середина пятна нужного цвета по горизонтали. */
    private fun IntArray.centroidX(test: (Int) -> Boolean): Float = centroid(test).first

    private fun IntArray.centroid(test: (Int) -> Boolean): Pair<Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until H) {
            for (x in 0 until W) {
                if (test(at(x, y))) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        assertTrue("искомого цвета в кадре нет", count > 0)
        return (sumX / count).toFloat() to (sumY / count).toFloat()
    }

    private fun red(c: Int) = (c shr 16) and 0xFF

    private fun green(c: Int) = (c shr 8) and 0xFF

    private fun blue(c: Int) = c and 0xFF

    private fun greenish(c: Int) = green(c) > 100 && red(c) < 80 && blue(c) < 80

    private fun cyanish(c: Int) = green(c) > 100 && blue(c) > 100 && red(c) < 80

    // Белое ядро метки райдера рисуется всегда и мимо палитры, поэтому жёлтое
    // отличаем ещё и по синему: у ленты его мало, у белого — под завязку.
    private fun yellowish(c: Int) = red(c) > 100 && green(c) > 100 && blue(c) < 200

    private fun magenta(c: Int) = red(c) > 100 && blue(c) > 100 && green(c) < 80

    private fun grayish(c: Int): Boolean {
        val r = red(c)
        return r in 0x60..0xA0 && abs(r - green(c)) < 12 && abs(r - blue(c)) < 12
    }

    // --- заготовки ---

    /**
     * Палитра под проверки: всё, чего тест не касается, красится в цвет земли
     * и тем самым пропадает — тогда в кадре остаются только те цвета, за
     * которыми тест и следит. Прозрачный тут не годится: сцена берёт из палитры
     * оттенок и подставляет свою прозрачность, так что чёрный проступил бы.
     */
    private fun palette(
        grid: Color = Color.Transparent,
        ribbon: Color = GROUND,
        marker: Color = GROUND,
        milestone: Color = GROUND,
        fogAlpha: Float = 0f,
    ) = FlyPalette(
        skyTop = SKY,
        skyHorizon = SKY,
        ground = GROUND,
        grid = grid,
        slow = ribbon,
        mid = ribbon,
        fast = ribbon,
        marker = marker,
        milestone = milestone,
        fogAlpha = fogAlpha,
    )

    /** Палитра-градиент от чёрного к белому: по ней видно, куда уехал цвет. */
    private fun ramp() =
        FlyPalette(
            skyTop = SKY,
            skyHorizon = SKY,
            ground = GROUND,
            grid = Color.Transparent,
            slow = Color.Black,
            mid = Color(0xFF808080),
            fast = Color.White,
            marker = GROUND,
            milestone = GROUND,
        )

    private fun point(index: Int) =
        TrackPoint(
            lat = 60.0 + index * 1e-4,
            lon = 30.0,
            x = 0.0,
            y = index * 10.0,
            // Волнистый рельеф: у стены профиля должна быть высота, а у трека — уклон.
            altitude = 100.0 + 20.0 * sin(index / 20.0),
            distance = index * 10.0,
            speedKmh = 20.0 + index % 10,
            heartRate = 130,
            cadence = 70,
            elapsed = index.toLong(),
        )

    private fun track(
        points: List<TrackPoint> = (0..300).map { point(it) },
        totalDistance: Double = 3000.0,
    ) = RideTrack(
        summary =
            RideSummary(
                file = "t.fit",
                start = Instant.parse("2026-07-25T10:00:00Z"),
                distanceM = totalDistance,
                elapsedMin = 5,
                movingMin = 5,
                avgHeartRate = 130,
                avgCadence = 70,
                ascent = 40,
                points = points.size,
                hasRoute = points.size >= 2,
                imported = false,
            ),
        points = points,
        bounds = GeoBounds.of(points),
        totalDistance = totalDistance,
        duration = Duration.ofSeconds(points.size.toLong()),
        start = Instant.parse("2026-07-25T10:00:00Z"),
        ascent = 40,
        calories = 200,
    )
}
