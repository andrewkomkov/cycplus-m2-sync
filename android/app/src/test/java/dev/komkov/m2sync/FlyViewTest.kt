package dev.komkov.m2sync

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * Экран полёта: управление ходом, ручная камера и показания под райдером.
 *
 * Часы Compose здесь ведутся вручную: полёт крутит бесконечный кадровый цикл, и
 * с автоматическим ходом тест никогда не дождался бы простоя. Сеть не трогается
 * — область корутин у источника тайлов закрыта, поэтому прогрев ничего не грузит.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Экран по умолчанию у Robolectric — 320×470: панель управления на нём
// схлопывается, и до кнопок скорости не дотянуться. Берём обычный телефон.
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class FlyViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun startFromTheMap() {
        Settings.setMapLayer(compose.activity, MapLayer.MAP)
    }

    /** Настройка живёт в статике: оставить её сдвинутой — испортить соседние тесты. */
    @After
    fun restoreTheLayer() {
        Settings.setMapLayer(compose.activity, MapLayer.MAP)
    }

    // --- часы ---

    @Test
    fun `the clock hides the hours until there are any`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:59", formatClock(59))
        assertEquals("1:01", formatClock(61))
        assertEquals("59:59", formatClock(3599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("2:03:04", formatClock(7384))
    }

    // --- подложка ---

    @Test
    fun `every basemap has an icon of its own`() {
        val icons = ArrayList<ImageVector>()
        compose.setContent {
            for (layer in MapLayer.entries) icons += layerIcon(layer)
        }

        assertEquals(Icons.Rounded.LayersClear, icons[MapLayer.NONE.ordinal])
        assertEquals(Icons.Rounded.Map, icons[MapLayer.MAP.ordinal])
        assertEquals(Icons.Rounded.SatelliteAlt, icons[MapLayer.SATELLITE.ordinal])
        assertEquals("иконка и есть указатель на текущий слой", 3, icons.distinct().size)
    }

    /** Один источник на экран: пересоздать его — значит выбросить весь кэш тайлов. */
    @Test
    fun `the tile source survives recomposition`() {
        val seen = ArrayList<TileSource>()
        var tick by mutableStateOf(0)
        compose.setContent {
            seen += rememberTileSource()
            Text(tick.toString())
        }

        tick = 1
        compose.waitForIdle()

        assertTrue("перерисовки не было", seen.size > 1)
        assertSame(seen.first(), seen.last())
    }

    @Test
    fun `the basemap button switches the layer for the whole app`() {
        show()

        tap(string(R.string.cd_basemap))
        assertEquals(MapLayer.SATELLITE, Settings.mapLayer.value)

        tap(string(R.string.cd_basemap))
        assertEquals(MapLayer.NONE, Settings.mapLayer.value)
    }

    /** Над снимком и в тёмной теме палитра другая, а экран — тот же. */
    @Test
    @Config(qualifiers = "+night")
    fun `the flight opens over the satellite in the dark theme`() {
        Settings.setMapLayer(compose.activity, MapLayer.SATELLITE)
        show()

        compose.onNodeWithContentDescription(string(R.string.cd_basemap)).assertExists()
        compose.onNodeWithText(string(R.string.unit_kmh)).assertExists()
    }

    // --- ход полёта ---

    @Test
    fun `the flight starts at the beginning and runs by itself`() {
        show()
        assertEquals(0f, progress(), 1e-4f)

        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("полёт обязан двигаться сам", progress() > 0f)
        assertTrue("но за секунду проходить далеко не весь заезд", progress() < 0.2f)
    }

    @Test
    fun `the speed chips make the flight run faster`() {
        show()
        compose.mainClock.advanceTimeBy(1_000)
        val single = progress()

        tap(string(R.string.cd_restart))
        compose.onNodeWithText(string(R.string.fly_rate, 4)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(string(R.string.fly_rate, 4)).assertIsSelected()
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("вчетверо быстрее: $single против ${progress()}", progress() > single * 3)
    }

    /** Волнистый индикатор — он же ползунок: тащишь и перелетаешь по заезду. */
    @Test
    fun `dragging the progress ribbon stops the flight at the spot`() {
        show()

        slider().performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
            up()
        }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0.8f, progress(), 0.02f)
        compose.onNodeWithContentDescription(string(R.string.cd_play)).assertExists()
    }

    @Test
    fun `a tap on the ribbon jumps without stopping the flight`() {
        show()

        slider().performTouchInput { click(Offset(width * 0.5f, center.y)) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0.5f, progress(), 0.02f)
        compose.onNodeWithContentDescription(string(R.string.cd_pause)).assertExists()
    }

    @Test
    fun `the restart button rewinds and starts the flight again`() {
        show()
        slider().performTouchInput {
            down(center)
            moveTo(Offset(width * 0.7f, center.y))
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(progress() > 0.5f)

        tap(string(R.string.cd_restart))

        assertEquals(0f, progress(), 0.01f)
        compose.onNodeWithContentDescription(string(R.string.cd_pause)).assertExists()
    }

    /** С финиша кнопка пуска не топчется на месте, а начинает заезд заново. */
    @Test
    fun `play at the finish starts the flight over`() {
        show()
        // За правый край: отметка упирается в конец заезда.
        slider().performTouchInput {
            down(center)
            moveTo(Offset(width * 2f, center.y))
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, progress(), 1e-4f)

        tap(string(R.string.cd_play))

        assertEquals(0f, progress(), 0.01f)
        compose.onNodeWithContentDescription(string(R.string.cd_pause)).assertExists()
    }

    /** Долетев до финиша, полёт останавливается сам и не улетает за конец заезда. */
    @Test
    fun `the flight stops at the finish`() {
        show()
        slider().performTouchInput {
            down(center)
            moveTo(Offset(width * 0.99f, center.y))
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        tap(string(R.string.cd_play))

        compose.mainClock.advanceTimeBy(2_000)

        assertEquals("отметка обязана упереться в конец", 1f, progress(), 1e-4f)
        compose.onNodeWithContentDescription(string(R.string.cd_play)).assertExists()
    }

    // --- ручная камера ---

    /** Кнопка «вернуть как было» появляется, только когда камеру увели. */
    @Test
    fun `moving the camera brings up the button that puts it back`() {
        show()
        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertDoesNotExist()

        turnTheCamera()
        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertExists()

        tap(string(R.string.cd_reset_view))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertDoesNotExist()
    }

    @Test
    fun `a double tap puts the camera back too`() {
        show()
        turnTheCamera()
        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertExists()

        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.3f, height * 0.25f)) }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertDoesNotExist()
    }

    // --- показания ---

    @Test
    fun `the readings come from the point under the rider`() {
        show(track(heartRate = 150, cadence = 90))

        compose.onNodeWithText("150").assertExists()
        compose.onNodeWithText(string(R.string.unit_bpm)).assertExists()
        compose.onNodeWithText("90").assertExists()
        compose.onNodeWithText(string(R.string.unit_rpm)).assertExists()
        compose.onNodeWithText(string(R.string.unit_m)).assertExists()
        compose.onNodeWithText(string(R.string.unit_kmh)).assertExists()
    }

    /** Без пульсометра и датчика каденса показывать нечего — и не показываем. */
    @Test
    fun `a ride without a belt and a sensor shows neither`() {
        show(track(heartRate = null, cadence = null))

        compose.onNodeWithText(string(R.string.unit_bpm)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.unit_rpm)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.unit_kmh)).assertExists()
    }

    /** Отвалившийся ремень пишет в заезд нули — это тоже «показывать нечего». */
    @Test
    fun `zeroed sensors are hidden just the same`() {
        show(track(heartRate = 0, cadence = 0))

        compose.onNodeWithText(string(R.string.unit_bpm)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.unit_rpm)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.unit_m)).assertExists()
    }

    /** Заезд без единой точки с координатами: показаний нет, а экран открывается. */
    @Test
    fun `a ride without points at all still opens`() {
        show(track(count = 0))

        compose.onNodeWithContentDescription(string(R.string.cd_close)).assertExists()
        compose.onNodeWithText(string(R.string.unit_kmh)).assertExists()
        compose.onNodeWithText(string(R.string.unit_m)).assertDoesNotExist()
        assertEquals(0f, progress(), 1e-4f)
    }

    // --- кадр ---

    /**
     * Холст полёта считает камеру и рисует всю сцену прямо в кадре: если это
     * место сломается, экран останется пустым, а тесты по семантике — зелёными.
     */
    @Test
    fun `the canvas really draws the sky over the ground`() {
        show()
        compose.mainClock.advanceTimeBy(500)

        val frame = compose.onRoot().captureToImage().asAndroidBitmap()
        val sky = frame.getPixel(2, 2)
        val ground = frame.getPixel(2, frame.height - 3)

        assertNotEquals("кадр не нарисовался", 0, sky)
        assertNotEquals("небо и земля обязаны отличаться", sky, ground)
    }

    @Test
    fun `the cross closes the flight`() {
        var closed = false
        show(onClose = { closed = true })

        tap(string(R.string.cd_close))

        assertTrue(closed)
    }

    // --- заготовки ---

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    /** Нажатие с прокруткой кадра: часы стоят, и без него перерисовки не будет. */
    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun show(
        track: RideTrack = track(),
        onClose: () -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        val tiles = TileSource(compose.activity, CoroutineScope(Job().also { it.cancel() }))
        compose.setContent { M2Theme { FlyView(track, tiles, onClose) } }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Ползунок хода: единственный узел кадра с долей пройденного. */
    private fun slider(): SemanticsNodeInteraction {
        val range = SemanticsProperties.ProgressBarRangeInfo
        return compose.onNode(SemanticsMatcher.keyIsDefined(range))
    }

    private fun progress(): Float {
        val info = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        return (info as ProgressBarRangeInfo).current
    }

    /** Ведёт пальцем по холсту: один палец облетает райдера вокруг. */
    private fun turnTheCamera() {
        compose.onRoot().performTouchInput {
            // Верхняя четверть экрана — чистый холст: панель управления внизу.
            val from = Offset(width * 0.3f, height * 0.25f)
            down(from)
            advanceEventTime(16)
            moveTo(from + Offset(120f, 0f))
            advanceEventTime(16)
            moveTo(from + Offset(300f, 0f))
            advanceEventTime(16)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun track(
        count: Int = 300,
        heartRate: Int? = 150,
        cadence: Int? = 90,
    ): RideTrack {
        val points =
            (0 until count).map { i ->
                TrackPoint(
                    lat = 60.0 + i * 1e-4,
                    lon = 30.0,
                    x = 0.0,
                    y = i * 10.0,
                    altitude = 100.0 + i * 0.5,
                    distance = i * 10.0,
                    speedKmh = 28.8,
                    heartRate = heartRate,
                    cadence = cadence,
                    elapsed = i.toLong(),
                )
            }
        return RideTrack(
            summary =
                RideSummary(
                    file = "20260725102049.fit",
                    start = Instant.parse("2026-07-25T10:00:00Z"),
                    distanceM = points.lastOrNull()?.distance ?: 0.0,
                    elapsedMin = 5,
                    movingMin = 5,
                    avgHeartRate = heartRate,
                    avgCadence = cadence,
                    ascent = 40,
                    points = points.size,
                    hasRoute = points.size >= 2,
                    imported = false,
                ),
            points = points,
            bounds = GeoBounds.of(points),
            totalDistance = points.lastOrNull()?.distance ?: 0.0,
            duration = Duration.ofSeconds(count.toLong()),
            start = Instant.parse("2026-07-25T10:00:00Z"),
            ascent = 40,
            calories = 200,
        )
    }
}
