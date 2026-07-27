package dev.komkov.m2sync

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * Плоская карта заезда.
 *
 * В сеть тесты не ходят: либо источнику тайлов подсунут диспетчер, который
 * ничего не выполняет, либо нужные квадраты заранее разложены по дисковому кэшу.
 */
@RunWith(RobolectricTestRunner::class)
class RouteMapTest {
    private companion object {
        /** Сторона кадра карты; тайлы прогреваем с запасом в столько квадратов. */
        const val MAP_W = 320
        const val MAP_H = 260
        const val RING = 3
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Диспетчер-заглушка: задачи принимает и молча выбрасывает. */
    private object Nowhere : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = Unit
    }

    /** Источник, который физически не может ничего загрузить. */
    private fun idleTiles() = TileSource(compose.activity, CoroutineScope(Nowhere))

    /** Настоящий источник: тайлы берёт с диска, если их туда заранее положили. */
    private fun liveTiles() = TileSource(compose.activity, CoroutineScope(Dispatchers.Unconfined))

    @Before
    fun basemapOff() {
        Settings.mapLayer.value = MapLayer.NONE
    }

    @After
    fun restoreBasemap() {
        Settings.mapLayer.value = MapLayer.NONE
    }

    private fun summary() =
        RideSummary(
            file = "20260725102049.fit",
            start = Instant.parse("2026-07-25T10:20:49Z"),
            distanceM = 1300.0,
            elapsedMin = 2,
            movingMin = 2,
            avgHeartRate = 140,
            avgCadence = 80,
            ascent = 59,
            points = 120,
            hasRoute = true,
            imported = false,
            kcal = 42,
        )

    /** Прямой трек на северо-восток: хватает и на маршрут, и на охватывающий прямоугольник. */
    private fun track(
        count: Int = 120,
        withCoords: Boolean = true,
    ): RideTrack {
        val begin = Instant.parse("2026-07-25T10:20:49Z")
        val points =
            (0 until count).map { i ->
                FitParser.Point(
                    time = begin.plusSeconds(i.toLong()),
                    lat = if (withCoords) 60.0 + i * 0.0001 else null,
                    lon = if (withCoords) 30.0 + i * 0.0001 else null,
                    altitude = 100.0 + i * 0.5,
                    speed = 8.0,
                    heartRate = 140,
                    cadence = 80,
                    distance = i * 11.132,
                )
            }
        val parsed =
            FitParser.Ride(
                fileName = "20260725102049.fit",
                start = points.first().time,
                end = points.last().time,
                sport = "cycling",
                totalDistance = (count - 1) * 11.132,
                totalTimerTime = count.toDouble(),
                totalAscent = 59,
                totalCalories = null,
                avgHeartRate = 140,
                points = points,
                activeSpans = listOf(points.first().time to points.last().time),
            )
        return RideTrack.build(summary(), parsed)
    }

    private fun show(
        track: RideTrack,
        tiles: TileSource = idleTiles(),
        highlight: MutableState<Int?> = mutableStateOf(null),
    ) = compose.setContent {
        M2Theme {
            RouteMap(
                track = track,
                tiles = tiles,
                modifier = Modifier.testTag("map").size(MAP_W.dp, MAP_H.dp),
                highlight = highlight.value,
            )
        }
    }

    /**
     * Robolectric сам фазу отрисовки не гоняет, а вся карта — один Canvas,
     * поэтому кадр снимаем руками: без этого рисующий код просто не выполнится.
     */
    private fun drawFrame() {
        compose.onNodeWithTag("map").captureToImage()
    }

    /** Зум, который карта подберёт под свой кадр: тайл растягивается вдвое. */
    private fun zoomOf(track: RideTrack): Int {
        val size = compose.onNodeWithTag("map").fetchSemanticsNode().size
        return track.bounds!!.fitZoom(size.width / 2f, size.height / 2f)
    }

    /** Раскладывает квадраты по дисковому кэшу источника — тогда он не пойдёт в сеть. */
    private fun seedTiles(
        layer: MapLayer,
        zoom: Int,
        bounds: GeoBounds,
    ) {
        val png =
            ByteArrayOutputStream()
                .also { out ->
                    Bitmap
                        .createBitmap(Geo.TILE_PX, Geo.TILE_PX, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.PNG, 100, out)
                }.toByteArray()
        val centerX = Geo.tileX(bounds.centerLon, zoom).toInt()
        val centerY = Geo.tileY(bounds.centerLat, zoom).toInt()
        for (x in centerX - RING..centerX + RING) {
            for (y in centerY - RING..centerY + RING) {
                File(compose.activity.cacheDir, "tiles/${layer.name.lowercase()}/$zoom/$x/$y.png").apply {
                    parentFile?.mkdirs()
                    writeBytes(png)
                }
            }
        }
    }

    @Test
    fun `map takes the size it was given`() {
        show(track())

        compose
            .onNodeWithTag("map")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(MAP_W.dp)
            .assertHeightIsEqualTo(MAP_H.dp)
    }

    /** Лицензия обоих источников требует подписи — без неё их тайлы брать нельзя. */
    @Test
    fun `map credits the source of the tiles`() {
        Settings.mapLayer.value = MapLayer.MAP
        show(track())
        drawFrame()

        compose.onNodeWithText("© OpenStreetMap").assertIsDisplayed()
    }

    @Test
    fun `satellite is credited to its own owner`() {
        Settings.mapLayer.value = MapLayer.SATELLITE
        show(track())
        drawFrame()

        compose.onNodeWithText("Esri, Maxar, Earthstar Geographics").assertIsDisplayed()
        compose.onNodeWithText("© OpenStreetMap").assertDoesNotExist()
    }

    /** Подложка выключена — чужих данных на экране нет, и подписывать нечего. */
    @Test
    fun `a map without a basemap has nothing to credit`() {
        show(track())
        drawFrame()

        compose.onNodeWithText("© OpenStreetMap").assertDoesNotExist()
        compose.onNodeWithText("Esri, Maxar, Earthstar Geographics").assertDoesNotExist()
    }

    /** Карта обязана запросить квадраты под маршрутом и дождаться их. */
    @Test
    fun `map asks the source for the tiles under the route`() {
        Settings.mapLayer.value = MapLayer.MAP
        val ride = track()
        val tiles = liveTiles()
        show(ride, tiles)
        seedTiles(MapLayer.MAP, zoomOf(ride), ride.bounds!!)

        drawFrame()
        compose.waitUntil { tiles.revision > 0 }
        // Второй кадр уже кладёт доехавшие квадраты на холст.
        drawFrame()

        assertTrue(tiles.revision > 0)
    }

    /** Выключенная подложка — это обещание не ходить в сеть вообще. */
    @Test
    fun `a basemap that is off asks for no tiles`() {
        val ride = track()
        val tiles = liveTiles()
        show(ride, tiles)
        seedTiles(MapLayer.MAP, zoomOf(ride), ride.bounds!!)

        drawFrame()

        assertEquals(0, tiles.revision)
    }

    /** Заезд без координат рисовать нечем: ни трека, ни тайлов, и без падения. */
    @Test
    fun `a ride without coordinates draws nothing`() {
        Settings.mapLayer.value = MapLayer.MAP
        val tiles = liveTiles()
        show(track(withCoords = false), tiles)

        drawFrame()

        compose.onNodeWithTag("map").assertIsDisplayed()
        assertEquals(0, tiles.revision)
    }

    /** Метку ставим по индексу из графика, и он вполне может указать в пустоту. */
    @Test
    fun `a highlight outside the track is ignored`() {
        val highlight = mutableStateOf<Int?>(60)
        show(track(), highlight = highlight)
        drawFrame()

        compose.runOnUiThread { highlight.value = 10_000 }
        drawFrame()

        compose.onNodeWithTag("map").assertIsDisplayed()
    }
}
