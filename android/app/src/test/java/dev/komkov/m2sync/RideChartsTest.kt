package dev.komkov.m2sync

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * График заезда: холст и протяжка пальцем.
 *
 * Экранные тесты в [UiTest] проверяют чипы и подписи, но не доводят дело до
 * отрисовки — Robolectric по умолчанию не рисует. Здесь включён нативный
 * графический режим, поэтому холст действительно исполняется, и его можно
 * проверять по пикселям.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideChartsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private val begin: Instant = Instant.parse("2026-07-25T10:20:49Z")

    private fun summary() =
        RideSummary(
            file = "20260725102049.fit",
            start = begin,
            distanceM = 1100.0,
            elapsedMin = 2,
            movingMin = 2,
            avgHeartRate = 140,
            avgCadence = 80,
            ascent = 50,
            points = 100,
            hasRoute = true,
            imported = false,
            kcal = null,
        )

    /**
     * Прямой трек на север по 11 м в секунду. Значения датчиков задаются
     * функцией от номера точки — так можно вырезать в них дыру.
     */
    private fun track(
        count: Int = 100,
        heartRate: (Int) -> Int? = { 140 },
        cadence: (Int) -> Int? = { 80 },
        speed: (Int) -> Double = { 8.0 },
        altitude: (Int) -> Double = { 100.0 + it * 0.5 },
    ): RideTrack {
        val points =
            (0 until count).map { i ->
                FitParser.Point(
                    time = begin.plusSeconds(i.toLong()),
                    lat = 60.0 + i * 0.0001,
                    lon = 30.0,
                    altitude = altitude(i),
                    speed = speed(i),
                    heartRate = heartRate(i),
                    cadence = cadence(i),
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
                totalAscent = 50,
                totalCalories = null,
                avgHeartRate = 140,
                points = points,
                activeSpans = listOf(points.first().time to points.last().time),
            )
        return RideTrack.build(summary(), parsed)
    }

    private fun card(
        track: RideTrack,
        metric: TrackMetric,
        highlight: Int? = null,
        onHighlight: (Int?) -> Unit = {},
    ) = compose.setContent {
        M2Theme {
            RideChartCard(
                track = track,
                metric = metric,
                onMetric = {},
                highlight = highlight,
                onHighlight = onHighlight,
                modifier = Modifier.testTag(CARD),
            )
        }
    }

    private fun node(): SemanticsNodeInteraction = compose.onNodeWithTag(CARD)

    /**
     * Полоса холста в координатах карточки: между подписью значения и осью
     * километров стоит только сам график.
     */
    private fun band(caption: String): IntRange {
        val cardTop = node().fetchSemanticsNode().boundsInRoot.top
        val above =
            compose
                .onNodeWithText(caption)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val below =
            compose
                .onNodeWithText(AXIS_ZERO)
                .fetchSemanticsNode()
                .boundsInRoot.top
        return ((above - cardTop).toInt() + PADDING)..((below - cardTop).toInt() - PADDING)
    }

    /** Сколько разных цветов встречается в полосе холста. */
    private fun colorsOnCanvas(caption: String): Int {
        val rows = band(caption)
        val pixels = node().captureToImage().toPixelMap()
        val colors = HashSet<Int>()
        for (y in rows) {
            for (x in PADDING until pixels.width - PADDING) colors += pixels[x, y].toArgb()
        }
        return colors.size
    }

    /** Вертикаль посреди холста — по ней и водим пальцем. */
    private fun canvasY(caption: String): Float = band(caption).let { (it.first + it.last) / 2f }

    private fun width(): Float =
        node()
            .fetchSemanticsNode()
            .size.width
            .toFloat()

    // --- подписи ---

    /** Каденс меряется оборотами в минуту, а не километрами в час. */
    @Test
    fun `cadence is labelled in revolutions per minute`() {
        card(track(), TrackMetric.CADENCE)

        compose.onNodeWithText("80 " + string(R.string.unit_rpm)).assertExists()
    }

    // --- холст ---

    @Test
    fun `the curve is really drawn on the canvas`() {
        card(track(), TrackMetric.ELEVATION)

        // Сетка, заливка градиентом и сама кривая дают заведомо больше одного цвета.
        assertTrue(colorsOnCanvas(string(R.string.chart_average)) > MANY_COLORS)
    }

    /**
     * Датчика не было — рисовать нечего. Холст обязан остаться пустым, а не
     * показывать кривую по нулям.
     */
    @Test
    fun `a metric with no readings leaves the canvas empty`() {
        card(track(heartRate = { null }), TrackMetric.HEART_RATE)

        assertTrue(colorsOnCanvas(string(R.string.chart_average)) <= BLANK_COLORS)
    }

    /**
     * Пульсометр отвалился в середине заезда. Пропуск — дыра в кривой, а не ноль:
     * иначе среднее за заезд обвалится.
     */
    @Test
    fun `a gap in the readings is a hole, not a zero`() {
        // 40 точек по 100, дыра на 20 точках, 40 точек по 160 — среднее 130.
        card(
            track(
                heartRate = { i ->
                    when {
                        i < 40 -> 100
                        i < 60 -> null
                        else -> 160
                    }
                },
            ),
            TrackMetric.HEART_RATE,
        )

        compose.onNodeWithText("130 " + string(R.string.unit_bpm)).assertExists()
        assertTrue(colorsOnCanvas(string(R.string.chart_average)) > MANY_COLORS)
    }

    /** Под пальцем показывается значение точки, а не среднее за заезд. */
    @Test
    fun `the highlighted point gets its own readout`() {
        card(
            track(heartRate = { i -> if (i < 50) 100 else 160 }),
            TrackMetric.HEART_RATE,
            highlight = 70,
        )

        compose.onNodeWithText("160 " + string(R.string.unit_bpm)).assertExists()
        // Маркер точки — ещё и вертикаль с кружком поверх кривой.
        assertTrue(colorsOnCanvas(string(R.string.chart_at, "0.78")) > MANY_COLORS)
    }

    /**
     * Заезд из одной точки: шаг по горизонтали считается делением на число
     * промежутков, и их здесь ноль.
     */
    @Test
    fun `a single point chart does not divide by zero`() {
        card(track(count = 1), TrackMetric.SPEED)

        compose.onNodeWithText("29 " + string(R.string.unit_kmh)).assertExists()
    }

    // --- палец ---

    @Test
    fun `a tap picks the point under the finger`() {
        var picked: Int? = null
        card(track(), TrackMetric.SPEED, onHighlight = { picked = it })

        val y = canvasY(string(R.string.chart_average))
        node().performTouchInput { click(Offset(width() * 0.25f, y)) }
        compose.waitForIdle()

        // Четверть ширины на сотне точек — это район двадцать пятой.
        assertTrue("получили $picked", picked in 20..30)
    }

    @Test
    fun `dragging follows the finger and lets go at the end`() {
        val seen = ArrayList<Int?>()
        card(track(), TrackMetric.SPEED, onHighlight = { seen += it })

        val y = canvasY(string(R.string.chart_average))
        val w = width()
        node().performTouchInput {
            down(Offset(w * 0.15f, y))
            moveTo(Offset(w * 0.45f, y))
            moveTo(Offset(w * 0.85f, y))
            up()
        }
        compose.waitForIdle()

        val picked = seen.filterNotNull()
        assertTrue("получили $seen", picked.size >= 2)
        assertEquals(picked.sorted(), picked)
        // Палец отпустили — подсветка снимается, подпись возвращается к среднему.
        assertNull(seen.last())
        compose.onNodeWithText(string(R.string.chart_average)).assertExists()
    }

    /** Жест перехватила система (скролл, звонок) — подсветку тоже снимаем. */
    @Test
    fun `a cancelled drag drops the highlight`() {
        val seen = ArrayList<Int?>()
        card(track(), TrackMetric.SPEED, onHighlight = { seen += it })

        val y = canvasY(string(R.string.chart_average))
        val w = width()
        node().performTouchInput {
            down(Offset(w * 0.2f, y))
            moveTo(Offset(w * 0.6f, y))
            cancel()
        }
        compose.waitForIdle()

        assertNull(seen.last())
    }

    private companion object {
        const val CARD = "chartCard"

        /** Левая подпись оси: ниже неё холста уже нет. */
        const val AXIS_ZERO = "0"

        /** Отступ от краёв полосы: сглаживание границ в счёт не берём. */
        const val PADDING = 6

        const val MANY_COLORS = 8
        const val BLANK_COLORS = 2
    }
}
