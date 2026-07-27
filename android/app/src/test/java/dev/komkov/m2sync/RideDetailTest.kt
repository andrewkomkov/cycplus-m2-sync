package dev.komkov.m2sync

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/**
 * Экран одного заезда.
 *
 * Разобранный трек экран берёт из кэша [RideTrack], поэтому тесты кладут туда
 * готовый заезд: настоящий .fit для этого не нужен, а ждать разбора в фоне —
 * лишний источник дребезга. Подложка карты на время тестов выключена: без неё
 * ни экран, ни прогрев коридора не ходят в сеть.
 */
@RunWith(RobolectricTestRunner::class)
class RideDetailTest {
    private companion object {
        /** Точек в тестовом треке: секунда на точку, две минуты заезда. */
        const val POINTS = 120

        /** Ровный пульс и каденс на всём заезде: среднее совпадает с показанием. */
        const val HEART_RATE = 140
        const val CADENCE = 80
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    /** Тем же локалем, что и экран: числа в тесте и на экране должны совпасть. */
    private fun number(
        format: String,
        value: Double,
    ): String = String.format(compose.activity.resources.configuration.locales[0], format, value)

    @Before
    fun offlineAndEmpty() {
        Settings.mapLayer.value = MapLayer.NONE
        cache(null)
    }

    @After
    fun restoreGlobals() {
        cache(null)
        Settings.mapLayer.value = MapLayer.MAP
    }

    /** Кладёт разобранный заезд в кэш [RideTrack] — оттуда его и берёт экран. */
    private fun cache(track: RideTrack?) {
        RideTrack::class.java
            .getDeclaredField("cached")
            .apply { isAccessible = true }
            .set(null, track)
    }

    private fun ride(
        file: String = "20260725102049.fit",
        avgHeartRate: Int? = 128,
        imported: Boolean = true,
        kcal: Int? = 232,
    ) = RideSummary(
        file = file,
        start = Instant.parse("2026-07-25T10:20:49Z"),
        distanceM = 1324.7,
        elapsedMin = 2,
        movingMin = 2,
        avgHeartRate = avgHeartRate,
        avgCadence = 80,
        ascent = 59,
        points = POINTS,
        hasRoute = true,
        imported = imported,
        kcal = kcal,
    )

    /**
     * Прямой трек на северо-восток: координаты, набор высоты, пульс и каденс.
     * [flat] убирает рельеф и скорость, [withCoords] — сами координаты,
     * [sensors] — показания пульсометра и датчика каденса.
     */
    private fun track(
        summary: RideSummary = ride(),
        withCoords: Boolean = true,
        flat: Boolean = false,
        sensors: Boolean = true,
        ascent: Int? = 59,
    ): RideTrack {
        val begin = summary.start
        val points =
            (0 until POINTS).map { i ->
                FitParser.Point(
                    time = begin.plusSeconds(i.toLong()),
                    lat = if (withCoords) 60.0 + i * 0.0001 else null,
                    lon = if (withCoords) 30.0 + i * 0.0001 else null,
                    altitude = if (flat) 100.0 else 100.0 + i * 0.5,
                    // Скорость гуляет: иначе средняя и максимальная совпадут, и
                    // перепутанные местами плитки тест не заметит.
                    speed = if (flat) 0.0 else 8.0 + (i % 5) * 0.5,
                    heartRate = if (sensors) HEART_RATE else null,
                    cadence = if (sensors) CADENCE else null,
                    distance = i * 11.132,
                )
            }
        val parsed =
            FitParser.Ride(
                fileName = summary.file,
                start = points.first().time,
                end = points.last().time,
                sport = "cycling",
                totalDistance = (POINTS - 1) * 11.132,
                totalTimerTime = POINTS.toDouble(),
                totalAscent = ascent,
                totalCalories = null,
                avgHeartRate = if (sensors) HEART_RATE else null,
                points = points,
                activeSpans = listOf(points.first().time to points.last().time),
            )
        return RideTrack.build(summary, parsed)
    }

    private fun show(
        summary: RideSummary = ride(),
        onBack: () -> Unit = {},
    ) = compose.setContent { M2Theme { RideDetailScreen(summary, onBack) } }

    /** Раскладывает квадраты по дисковому кэшу: оттуда их берут вместо сети. */
    private fun seedTiles(
        layer: MapLayer,
        zoom: Int,
        tiles: List<Long>,
    ) {
        val png =
            ByteArrayOutputStream()
                .also { out ->
                    Bitmap
                        .createBitmap(Geo.TILE_PX, Geo.TILE_PX, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.PNG, 100, out)
                }.toByteArray()
        for (packed in tiles) {
            val x = (packed shr 32).toInt()
            val y = packed.toInt()
            File(compose.activity.cacheDir, "tiles/${layer.name.lowercase()}/$zoom/$x/$y.png").apply {
                parentFile?.mkdirs()
                writeBytes(png)
            }
        }
    }

    // --- цифры заезда ---

    @Test
    fun `detail lays out every number the ride has`() {
        val loaded = track()
        cache(loaded)
        show()

        // Шапка: сколько проехали и что этим заездом можно поделиться.
        compose.onNodeWithText(string(R.string.ride_distance, number("%.2f", 1.3247))).assertExists()
        compose.onNodeWithContentDescription(string(R.string.cd_share)).assertExists()

        compose.onNodeWithText(string(R.string.detail_distance)).assertExists()
        compose.onNodeWithText(number("%.2f", loaded.totalDistance / 1000)).assertExists()
        compose.onNodeWithText(string(R.string.detail_moving)).assertExists()
        compose.onNodeWithText(string(R.string.detail_avg_speed)).assertExists()
        compose.onNodeWithText(number("%.1f", loaded.avgSpeed)).assertExists()
        compose.onNodeWithText(string(R.string.detail_max_speed)).assertExists()
        compose.onNodeWithText(number("%.1f", loaded.maxSpeed)).assertExists()
        compose.onNodeWithText(string(R.string.detail_elapsed)).assertExists()
        compose.onNodeWithText(formatClock(loaded.duration.seconds)).assertExists()
    }

    @Test
    fun `detail shows the readings of the sensors that were on`() {
        cache(track())
        show()

        compose.onNodeWithText(string(R.string.detail_ascent)).assertExists()
        compose.onNodeWithText("59").assertExists()
        compose.onNodeWithText(string(R.string.detail_avg_hr)).assertExists()
        compose.onNodeWithText("128").assertExists()
        compose.onNodeWithText(string(R.string.detail_max_hr)).assertExists()
        compose.onNodeWithText(HEART_RATE.toString()).assertExists()
        compose.onNodeWithText(string(R.string.detail_calories)).assertExists()
        compose.onNodeWithText("232").assertExists()
        compose.onNodeWithText(string(R.string.detail_altitude_range)).assertExists()
    }

    /** Чего датчики не записали — того на экране нет: пустых плиток не рисуем. */
    @Test
    fun `detail drops the tiles it has no numbers for`() {
        val summary = ride(avgHeartRate = null, kcal = null)
        cache(track(summary = summary, flat = true, sensors = false, ascent = 0))
        show(summary)

        compose.onNodeWithText(string(R.string.detail_ascent)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_avg_hr)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_max_hr)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_calories)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_altitude_range)).assertDoesNotExist()
        // Дистанция и время есть всегда — экран не должен остаться пустым.
        compose.onNodeWithText(string(R.string.detail_distance)).assertExists()
        compose.onNodeWithText(string(R.string.detail_elapsed)).assertExists()
    }

    // --- маршрут ---

    @Test
    fun `a ride with a route gets a map and a flight`() {
        cache(track())
        show()

        compose.onNodeWithContentDescription(string(R.string.cd_basemap)).assertExists()
        compose.onNodeWithText(string(R.string.btn_fly)).assertIsDisplayed()
    }

    /** Без координат карту рисовать не из чего, и полёту тоже некуда лететь. */
    @Test
    fun `a ride without a route gets neither map nor flight`() {
        cache(track(withCoords = false))
        show()

        compose.onNodeWithContentDescription(string(R.string.cd_basemap)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.btn_fly)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_distance)).assertExists()
    }

    /**
     * Кнопка перебирает подложки по кругу, и выбранная должна доехать до самой
     * карты. Квадраты коридора заранее лежат в кэше: прогрев после переключения
     * стартует сразу, и в сеть за ними идти не должно.
     */
    @Test
    fun `the basemap button walks the layers round`() {
        val loaded = track()
        seedTiles(MapLayer.MAP, FLY_GROUND_ZOOM, routeTiles(loaded, FLY_GROUND_ZOOM, ring = 3))
        cache(loaded)
        show()

        compose.onNodeWithContentDescription(string(R.string.cd_basemap)).performClick()
        compose.waitForIdle()

        assertEquals(MapLayer.MAP, Settings.mapLayer.value)
        compose.onNodeWithText("© OpenStreetMap").assertExists()
    }

    @Test
    fun `the fly button takes off`() {
        cache(track())
        show()
        compose.onNodeWithText(string(R.string.btn_fly)).assertExists()

        // Полёт крутит бесконечный кадровый цикл, поэтому часы дальше двигаем руками.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(string(R.string.btn_fly)).performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }

        compose.onNodeWithContentDescription(string(R.string.cd_close)).assertExists()
        compose.onNodeWithText(string(R.string.detail_distance)).assertDoesNotExist()
    }

    // --- графики ---

    @Test
    fun `the chart starts on elevation and switches on a chip`() {
        cache(track())
        show()

        compose.onNodeWithText(string(R.string.chart_elevation)).assertExists()
        compose.onNodeWithText(string(R.string.chart_average)).assertExists()

        // Графики лежат ниже сгиба, поэтому сначала доводим их до экрана.
        compose.onNodeWithText(string(R.string.chart_heart_rate)).performScrollTo().performClick()

        compose.onNodeWithText("$HEART_RATE " + string(R.string.unit_bpm)).assertExists()
    }

    /** Без рельефа первый график выбирается по тому, что в заезде вообще есть. */
    @Test
    fun `the first chart falls back to a metric the ride has`() {
        cache(track(flat = true, ascent = 0))
        show()

        compose.onNodeWithText(string(R.string.chart_elevation)).assertDoesNotExist()
        compose.onNodeWithText("$HEART_RATE " + string(R.string.unit_bpm)).assertExists()
    }

    /** Заезд, где молчали все датчики: рисовать нечего, карточки графика нет. */
    @Test
    fun `a ride with nothing to plot gets no chart`() {
        // Набора высоты в .fit может не быть вовсе — это не то же самое, что ноль.
        cache(track(flat = true, sensors = false, ascent = null))
        show()

        compose.onNodeWithText(string(R.string.chart_elevation)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.chart_speed)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.chart_average)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_ascent)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.detail_distance)).assertExists()
    }

    // --- отметка о Health Connect ---

    @Test
    fun `an imported ride says it reached health connect`() {
        cache(track())
        show()

        compose.onNodeWithText(string(R.string.cd_in_health)).assertExists()
    }

    @Test
    fun `a ride that never reached health connect says nothing`() {
        val summary = ride(imported = false)
        cache(track(summary = summary))
        show(summary)

        compose.onNodeWithText(string(R.string.cd_in_health)).assertDoesNotExist()
    }

    // --- шапка ---

    @Test
    fun `the back arrow leaves the ride`() {
        var back = false
        cache(track())
        show(onBack = { back = true })

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()

        assertTrue(back)
    }

    /** «Поделиться» готовит читаемую копию .fit — её и отдают наружу. */
    @Test
    fun `sharing stages a readable copy of the fit file`() {
        val summary = ride()
        File(SyncService.fitDir(compose.activity), summary.file).writeBytes(ByteArray(16))
        cache(track(summary = summary))
        show(summary)

        compose.onNodeWithContentDescription(string(R.string.cd_share)).performClick()

        val staged = File(compose.activity.cacheDir, "share/${Sharing.prettyName(summary)}")
        assertTrue("копия для отправки не появилась", staged.isFile)
    }

    // --- разбор не удался ---

    /**
     * Файл могли удалить снаружи или он побился при передаче: экран обязан
     * сказать об этом, а не крутить индикатор до бесконечности.
     */
    @Test
    fun `a ride whose file is gone says so`() {
        val summary = ride(file = "vanished.fit")
        show(summary)

        compose.waitUntil {
            compose
                .onAllNodesWithText(string(R.string.detail_unreadable))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.btn_fly)).assertDoesNotExist()
    }
}
