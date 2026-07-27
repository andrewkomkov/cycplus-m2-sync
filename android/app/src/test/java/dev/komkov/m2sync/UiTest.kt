package dev.komkov.m2sync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Экранные тесты на Robolectric: гоняются обычным `./gradlew test`, без эмулятора,
 * поэтому живут в CI рядом с остальными юнит-тестами.
 */
@RunWith(RobolectricTestRunner::class)
class UiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun setContent(content: @Composable () -> Unit) = compose.setContent { M2Theme { content() } }

    private fun ride(
        file: String = "20260725102049.fit",
        distanceM: Double = 7350.0,
        elapsedMin: Long = 65,
        movingMin: Long = 33,
        avgHeartRate: Int? = 128,
        avgCadence: Int? = 52,
        ascent: Int? = 13,
        points: Int = 2023,
        hasRoute: Boolean = true,
        imported: Boolean = true,
        kcal: Int? = 232,
    ) = RideSummary(
        file = file,
        start = Instant.parse("2026-07-25T10:20:49Z"),
        distanceM = distanceM,
        elapsedMin = elapsedMin,
        movingMin = movingMin,
        avgHeartRate = avgHeartRate,
        avgCadence = avgCadence,
        ascent = ascent,
        points = points,
        hasRoute = hasRoute,
        imported = imported,
        kcal = kcal,
    )

    @Before
    fun resetProfile() {
        Settings.setProfile(compose.activity, null, null)
    }

    // --- карточка заезда ---

    @Test
    fun `ride card shows distance and metrics`() {
        setContent {
            RideCard(ride(), selected = false, selectionMode = false, onClick = {}, onLongClick = {}, onShare = {})
        }

        compose.onNodeWithText(string(R.string.ride_distance, "7.35")).assertExists()
        compose.onNodeWithText(string(R.string.chip_moving, 33)).assertExists()
        compose.onNodeWithText(string(R.string.chip_total, 65)).assertExists()
        compose.onNodeWithText(string(R.string.chip_hr, 128)).assertExists()
        compose.onNodeWithText(string(R.string.chip_cadence, 52)).assertExists()
        compose.onNodeWithText(string(R.string.chip_kcal, 232)).assertExists()
        compose.onNodeWithText(string(R.string.chip_ascent, 13)).assertExists()
        compose.onNodeWithText(string(R.string.chip_points, 2023)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.cd_in_health)).assertExists()
    }

    /** Калорий может не быть: расчёт требует веса и профиля, а их могли не задать. */
    @Test
    fun `ride card omits calories when they are unknown`() {
        setContent {
            RideCard(
                ride(kcal = null),
                selected = false,
                selectionMode = false,
                onClick = {},
                onLongClick = {},
                onShare = {},
            )
        }

        compose.onNodeWithText(string(R.string.chip_kcal, 232)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.chip_moving, 33)).assertExists()
    }

    @Test
    fun `ride card hides zero ascent and route chip without a track`() {
        setContent {
            RideCard(
                ride(ascent = 0, hasRoute = false),
                selected = false,
                selectionMode = false,
                onClick = {},
                onLongClick = {},
                onShare = {},
            )
        }

        compose.onNodeWithText(string(R.string.chip_ascent, 0)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.chip_points, 2023)).assertDoesNotExist()
    }

    @Test
    fun `ride card reports long click and click`() {
        var longClicked = false
        var clicked = false
        setContent {
            RideCard(
                ride(),
                selected = false,
                selectionMode = false,
                onClick = { clicked = true },
                onLongClick = { longClicked = true },
                onShare = {},
            )
        }

        compose.onNodeWithText(string(R.string.ride_distance, "7.35")).performTouchInput { longClick() }
        assertTrue(longClicked)

        compose.onNodeWithText(string(R.string.ride_distance, "7.35")).performClick()
        assertTrue(clicked)
    }

    /** В режиме выбора «поделиться» уезжает в нижнюю панель, иначе кнопок две. */
    @Test
    fun `ride card drops the share button in selection mode`() {
        setContent {
            RideCard(ride(), selected = true, selectionMode = true, onClick = {}, onLongClick = {}, onShare = {})
        }

        compose.onNodeWithContentDescription(string(R.string.cd_share)).assertDoesNotExist()
    }

    @Test
    fun `ride card share button calls back`() {
        var shared = false
        setContent {
            RideCard(
                ride(),
                selected = false,
                selectionMode = false,
                onClick = {},
                onLongClick = {},
                onShare = { shared = true },
            )
        }

        compose.onNodeWithContentDescription(string(R.string.cd_share)).performClick()
        assertTrue(shared)
    }

    // --- список ---

    @Test
    fun `rides list explains itself when empty`() {
        setContent {
            RidesList(
                rides = emptyList(),
                selection = emptySet(),
                onToggle = {},
                onLongClick = {},
                onShare = {},
                header = {},
            )
        }

        compose.onNodeWithText(string(R.string.rides_empty)).assertExists()
    }

    @Test
    fun `rides list renders every ride and the header`() {
        val rides =
            listOf(
                ride(file = "a.fit", distanceM = 7350.0),
                ride(file = "b.fit", distanceM = 40990.0),
            )
        setContent {
            RidesList(
                rides = rides,
                selection = emptySet(),
                onToggle = {},
                onLongClick = {},
                onShare = {},
                header = { TotalsRow(rides) },
            )
        }

        compose.onNodeWithText(string(R.string.ride_distance, "7.35")).assertExists()
        compose.onNodeWithText(string(R.string.ride_distance, "40.99")).assertExists()
        compose.onNodeWithText(string(R.string.rides_empty)).assertDoesNotExist()
    }

    /** Непустой набор выбранных переводит весь список в режим выбора. */
    @Test
    fun `selection in the list switches cards to selection mode`() {
        val rides = listOf(ride(file = "a.fit"), ride(file = "b.fit", distanceM = 40990.0))
        setContent {
            RidesList(
                rides = rides,
                selection = setOf("a.fit"),
                onToggle = {},
                onLongClick = {},
                onShare = {},
                header = {},
            )
        }

        compose.onAllNodesWithContentDescription(string(R.string.cd_share)).assertCountEquals(0)
    }

    // --- итоги ---

    @Test
    fun `totals row sums distance moving time and imported rides`() {
        val rides =
            listOf(
                ride(file = "a.fit", distanceM = 23_000.0, movingMin = 60, imported = true),
                ride(file = "b.fit", distanceM = 40_990.0, movingMin = 120, imported = false),
            )
        setContent { TotalsRow(rides) }

        compose.onNodeWithText(string(R.string.stat_rides)).assertExists()
        compose.onNodeWithText("2").assertExists() // заездов
        compose.onNodeWithText("64").assertExists() // километров, округление вниз до целого
        compose.onNodeWithText("3.0").assertExists() // часов в движении
        compose.onNodeWithText("1").assertExists() // доехало до Health Connect
    }

    // --- вес ---

    @Test
    fun `weight card shows the last measurement`() {
        setContent {
            WeightCard(
                weight = WeightReading(72.8, Instant.parse("2026-07-25T08:30:00Z")),
                onWeigh = {},
                enabled = true,
            )
        }

        compose.onNodeWithText(string(R.string.weight_value, "72.80")).assertExists()
        compose.onNodeWithText(string(R.string.btn_weigh)).assertIsEnabled()
    }

    @Test
    fun `weight card asks to step on the scale when nothing is known`() {
        setContent { WeightCard(weight = null, onWeigh = {}, enabled = true) }

        compose.onNodeWithText(string(R.string.weight_unknown)).assertExists()
        compose.onNodeWithText(string(R.string.weight_hint)).assertExists()
    }

    /** Пока идёт другая операция по BLE, взвешиваться нельзя: радио занято. */
    @Test
    fun `weight card blocks weighing while busy`() {
        var weighed = false
        setContent { WeightCard(weight = null, onWeigh = { weighed = true }, enabled = false) }

        compose.onNodeWithText(string(R.string.btn_weigh)).assertIsNotEnabled().performClick()
        assertEquals(false, weighed)
    }

    // --- профиль ---

    @Test
    fun `profile dialog saves year and sex`() {
        var dismissed = false
        setContent { ProfileDialog(onDismiss = { dismissed = true }) }

        compose.onNodeWithText(string(R.string.profile_birth_year)).performTextInput("1992")
        compose.onNodeWithText(string(R.string.profile_male)).performClick()
        compose.onNodeWithText(string(R.string.profile_save)).performClick()

        assertEquals(1992, Settings.birthYear.value)
        assertEquals(Calories.Sex.MALE, Settings.sex.value)
        assertTrue(dismissed)
    }

    @Test
    fun `profile dialog refuses an impossible year`() {
        setContent { ProfileDialog(onDismiss = {}) }

        compose.onNodeWithText(string(R.string.profile_birth_year)).performTextInput("18")

        compose.onNodeWithText(string(R.string.profile_year_invalid)).assertExists()
        compose.onNodeWithText(string(R.string.profile_save)).assertIsNotEnabled()
        assertNull(Settings.birthYear.value)
    }

    /** Поле принимает только цифры и ровно четыре: год не бывает другим. */
    @Test
    fun `profile dialog keeps only four digits`() {
        setContent { ProfileDialog(onDismiss = {}) }

        val field = compose.onNodeWithText(string(R.string.profile_birth_year))
        field.performTextInput("19a92xx7")

        compose.onNodeWithText("1992").assertExists()
    }

    @Test
    fun `profile dialog leaves settings alone on cancel`() {
        setContent { ProfileDialog(onDismiss = {}) }

        compose.onNodeWithText(string(R.string.profile_birth_year)).performTextInput("1992")
        compose.onNodeWithText(string(R.string.profile_cancel)).performClick()

        assertNull(Settings.birthYear.value)
        assertNull(Settings.sex.value)
    }

    // --- действия ---

    @Test
    fun `actions row calls back on every button`() {
        var synced = false
        var polled = false
        var verified = false
        setContent {
            ActionsRow(
                onSync = { synced = true },
                onInfo = { polled = true },
                onVerify = { verified = true },
                enabled = true,
            )
        }

        compose.onNodeWithText(string(R.string.btn_sync)).performClick()
        compose.onNodeWithText(string(R.string.btn_poll)).performClick()
        compose.onNodeWithText(string(R.string.btn_verify)).performClick()

        assertTrue(synced && polled && verified)
    }

    /** Во время работы кнопки гаснут: параллельный синк порвал бы BLE-сессию. */
    @Test
    fun `actions row is dead while a sync runs`() {
        var touched = false
        setContent {
            ActionsRow(
                onSync = { touched = true },
                onInfo = { touched = true },
                onVerify = { touched = true },
                enabled = false,
            )
        }

        compose.onNodeWithText(string(R.string.btn_sync)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(string(R.string.btn_poll)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(string(R.string.btn_verify)).assertIsNotEnabled().performClick()

        assertEquals(false, touched)
    }

    // --- журнал ---

    @Test
    fun `log card shows the last line and unfolds the rest`() {
        val lines = listOf("ищу устройства", "качаю 20260725102049.fit", "готово")
        setContent { LogCard(lines) }

        // Свёрнутый журнал — это одна строка-хвост, развёрнутый — все строки одним блоком.
        compose.onNodeWithText("готово").assertExists()
        compose.onNodeWithText("ищу устройства", substring = true).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.log_title)).performClick()

        compose.onNodeWithText("ищу устройства", substring = true).assertExists()
    }

    // --- обновление ---

    @Test
    fun `update card offers the new version`() {
        var downloaded = false
        setContent {
            UpdateCard(
                update =
                    UpdateChecker.Update(
                        version = "0.9.9",
                        apkUrl = "https://example.invalid/app.apk",
                        pageUrl = "https://example.invalid/releases",
                        notes = null,
                    ),
                onDownload = { downloaded = true },
            )
        }

        compose.onNodeWithText(string(R.string.update_available, "0.9.9")).assertExists()
        compose.onNodeWithText(string(R.string.update_download)).performClick()
        assertTrue(downloaded)
    }

    /** Пока идёт установка, нажимать нечего: кнопка уступает место полосе. */
    @Test
    fun `update card swaps the button for progress while installing`() {
        setContent {
            UpdateCard(
                update =
                    UpdateChecker.Update(
                        version = "0.9.9",
                        apkUrl = "https://example.invalid/app.apk",
                        pageUrl = "https://example.invalid/releases",
                        notes = null,
                    ),
                onDownload = {},
                progress = UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 5, 10),
            )
        }

        compose.onNodeWithText(string(R.string.update_downloading)).assertExists()
        compose.onNodeWithText(string(R.string.update_download)).assertDoesNotExist()
    }

    @Test
    fun `update card names the stage it is on`() {
        setContent {
            UpdateCard(
                update = UpdateChecker.Update("0.9.9", null, "https://example.invalid", null),
                onDownload = {},
                progress = UpdateProgress(UpdateProgress.Stage.INSTALL),
            )
        }

        compose.onNodeWithText(string(R.string.update_installing)).assertExists()
    }

    // --- карточка устройства ---

    @Test
    fun `device card shows what was read off the device`() {
        setContent {
            DeviceCard(
                device =
                    DeviceSnapshot(
                        name = "Cycplus M2",
                        address = "E3:E8:F7:E3:09:44",
                        firmware = "V1.4.0",
                        battery = 100,
                        freeKb = 708,
                        totalKb = 16384,
                        seenAt = Instant.parse("2026-07-25T08:35:00Z"),
                    ),
                busy = false,
                action = null,
            )
        }

        compose.onNodeWithText("Cycplus M2").assertExists()
        compose.onNodeWithText("E3:E8:F7:E3:09:44").assertExists()
    }

    // --- заезд крупным планом ---

    /** Прямой трек на север: координаты, высота, пульс и каденс — всё есть. */
    private fun track(
        count: Int = 120,
        heartRate: Int? = 140,
        cadence: Int? = 80,
    ): RideTrack {
        val begin = Instant.parse("2026-07-25T10:20:49Z")
        val points =
            (0 until count).map { i ->
                FitParser.Point(
                    time = begin.plusSeconds(i.toLong()),
                    lat = 60.0 + i * 0.0001,
                    lon = 30.0,
                    altitude = 100.0 + i * 0.5,
                    speed = 8.0,
                    heartRate = heartRate,
                    cadence = cadence,
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
                avgHeartRate = heartRate,
                points = points,
                activeSpans = listOf(points.first().time to points.last().time),
            )
        return RideTrack.build(ride(), parsed)
    }

    @Test
    fun `chart card offers only the metrics the ride has`() {
        setContent {
            RideChartCard(
                track = track(heartRate = null, cadence = null),
                metric = TrackMetric.ELEVATION,
                onMetric = {},
                highlight = null,
                onHighlight = {},
            )
        }

        compose.onNodeWithText(string(R.string.chart_elevation)).assertExists()
        compose.onNodeWithText(string(R.string.chart_speed)).assertExists()
        compose.onNodeWithText(string(R.string.chart_heart_rate)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.chart_cadence)).assertDoesNotExist()
    }

    @Test
    fun `chart card switches metric on a chip`() {
        var picked: TrackMetric? = null
        setContent {
            RideChartCard(
                track = track(),
                metric = TrackMetric.ELEVATION,
                onMetric = { picked = it },
                highlight = null,
                onHighlight = {},
            )
        }

        compose.onNodeWithText(string(R.string.chart_heart_rate)).performClick()
        assertEquals(TrackMetric.HEART_RATE, picked)
    }

    /** Без протяжки подпись говорит про среднее, с ней — про точку под пальцем. */
    @Test
    fun `chart card labels the average until a point is picked`() {
        setContent {
            RideChartCard(
                track = track(),
                metric = TrackMetric.HEART_RATE,
                onMetric = {},
                highlight = null,
                onHighlight = {},
            )
        }

        compose.onNodeWithText(string(R.string.chart_average)).assertExists()
        compose.onNodeWithText("140 " + string(R.string.unit_bpm)).assertExists()
    }

    @Test
    fun `chart card reports the picked point`() {
        setContent {
            RideChartCard(
                track = track(),
                metric = TrackMetric.SPEED,
                onMetric = {},
                highlight = 40,
                onHighlight = {},
            )
        }

        compose.onNodeWithText(string(R.string.chart_at, "0.45"), substring = true).assertExists()
    }

    // --- полёт ---

    @Test
    fun `fly view shows the controls and the starting readings`() {
        // Полёт крутит бесконечный кадровый цикл, поэтому часы двигаем вручную:
        // иначе тест никогда не дождётся простоя.
        compose.mainClock.autoAdvance = false
        val flown = track()
        setContent {
            FlyView(flown, tiles = TileSource(compose.activity, kotlinx.coroutines.MainScope())) {}
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithContentDescription(string(R.string.cd_close)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.cd_pause)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.cd_restart)).assertExists()
        compose.onNodeWithText(string(R.string.fly_rate, 4)).assertExists()
        compose.onNodeWithText(string(R.string.unit_kmh)).assertExists()
    }

    @Test
    fun `fly view closes on the cross`() {
        compose.mainClock.autoAdvance = false
        var closed = false
        val flown = track()
        setContent {
            FlyView(flown, tiles = TileSource(compose.activity, kotlinx.coroutines.MainScope())) {
                closed = true
            }
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithContentDescription(string(R.string.cd_close)).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(closed)
    }

    /** Вид не трогали — кнопки возврата камеры быть не должно. */
    @Test
    fun `fly view hides the reset until the camera is moved`() {
        compose.mainClock.autoAdvance = false
        val flown = track()
        setContent {
            FlyView(flown, tiles = TileSource(compose.activity, kotlinx.coroutines.MainScope())) {}
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithContentDescription(string(R.string.cd_reset_view)).assertDoesNotExist()
    }
}
