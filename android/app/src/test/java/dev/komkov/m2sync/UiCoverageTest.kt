package dev.komkov.m2sync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Добор к [UiTest]: рисунок велокомпьютера, состояния занятости и ветки, куда
 * основной набор не заходит. Графика включена нативная — иначе тела `Canvas`
 * под Robolectric не исполняются вовсе.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiCoverageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun setContent(content: @Composable () -> Unit) = compose.setContent { M2Theme { content() } }

    /** Профиль живёт в статике и переезжает между тестами — гасим до и после. */
    @Before
    fun clearProfile() {
        Settings.setProfile(compose.activity, null, null)
    }

    @After
    fun restoreProfile() {
        Settings.setProfile(compose.activity, null, null)
    }

    private fun device(
        firmware: String? = "V1.4.0",
        battery: Int? = 100,
        freeKb: Int? = 708,
        totalKb: Int? = 16384,
    ) = DeviceSnapshot(
        name = "Cycplus M2",
        address = "E3:E8:F7:E3:09:44",
        firmware = firmware,
        battery = battery,
        freeKb = freeKb,
        totalKb = totalKb,
        seenAt = Instant.parse("2026-07-25T08:35:00Z"),
    )

    private fun ride(imported: Boolean) =
        RideSummary(
            file = "20260725102049.fit",
            start = Instant.parse("2026-07-25T10:20:49Z"),
            distanceM = 7350.0,
            elapsedMin = 65,
            movingMin = 33,
            avgHeartRate = 128,
            avgCadence = 52,
            ascent = 13,
            points = 2023,
            hasRoute = true,
            imported = imported,
            kcal = 232,
        )

    /** Все пиксели узла подряд: по ним и сравниваем два рисунка. */
    private fun pixels(tag: String): List<Int> {
        val map = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val all = ArrayList<Int>(map.width * map.height)
        for (y in 0 until map.height) {
            for (x in 0 until map.width) all += map[x, y].toArgb()
        }
        return all
    }

    // --- рисунок велокомпьютера ---

    /**
     * Кольцо заряда рисуется только когда заряд известен: пустое кольцо на
     * весь круг врало бы про полную батарею.
     */
    @Test
    fun `the battery ring is drawn only when the charge is known`() {
        setContent {
            Column {
                DeviceArt(battery = 100, modifier = Modifier.testTag(CHARGED))
                DeviceArt(battery = null, modifier = Modifier.testTag(UNKNOWN))
            }
        }

        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText(string(R.string.dash)).assertExists()
        compose.onAllNodesWithText(string(R.string.battery_caption)).assertCountEquals(2)

        val charged = pixels(CHARGED)
        val unknown = pixels(UNKNOWN)
        // Оба рисунка непустые — корпус, экран и кнопки на месте.
        assertTrue(charged.distinct().size > FEW_COLORS)
        assertTrue(unknown.distinct().size > FEW_COLORS)
        // А дуга заряда есть только там, где заряд известен.
        assertNotEquals(charged, unknown)
    }

    @Test
    fun `a partial charge draws a shorter arc than a full one`() {
        setContent {
            Column {
                DeviceArt(battery = 100, modifier = Modifier.testTag(CHARGED))
                DeviceArt(battery = 5, modifier = Modifier.testTag(LOW))
            }
        }

        compose.onNodeWithText("5%").assertExists()
        assertNotEquals(pixels(CHARGED), pixels(LOW))
    }

    // --- карточка устройства ---

    /** Занято место, а не свободное: пользователю важно, сколько уже занял он. */
    @Test
    fun `the memory line counts what is used`() {
        setContent { DeviceCard(device = device(), busy = false, action = null) }

        compose.onNodeWithText(string(R.string.memory_line, 16384 - 708, 16384)).assertExists()
        compose.onNodeWithText(string(R.string.firmware_label) + ": ").assertExists()
    }

    /** Устройство ни разу не опрашивали — вместо данных подсказка, что нажать. */
    @Test
    fun `an unpolled card explains what to do`() {
        setContent { DeviceCard(device = null, busy = false, action = null) }

        compose.onNodeWithText(string(R.string.device_unknown)).assertExists()
        compose.onNodeWithText(string(R.string.device_hint)).assertExists()
        compose.onNodeWithText(string(R.string.dash)).assertExists()
        compose.onNodeWithText(string(R.string.memory_line, 15676, 16384)).assertDoesNotExist()
    }

    /** Память без общего объёма не показать — полоска рисовалась бы от балды. */
    @Test
    fun `a device without memory figures hides the bar`() {
        setContent {
            DeviceCard(device = device(firmware = null, freeKb = null, totalKb = null), busy = false, action = null)
        }

        compose.onNodeWithText("Cycplus M2").assertExists()
        compose.onNodeWithText(string(R.string.firmware_label) + ": ").assertDoesNotExist()
    }

    /**
     * Пока идёт работа по BLE, карточка подписывает именно ту операцию, которая
     * идёт: «синхронизирую» вместо «работаю» — единственный способ понять, чего ждём.
     */
    @Test
    fun `a busy card names the operation it runs`() {
        val actions = listOf("SYNC", "INFO", "IMPORT", "VERIFY", "WEIGH", "SCAN", null)
        setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.forEach { DeviceCard(device = device(), busy = true, action = it) }
            }
        }

        listOf(
            R.string.busy_sync,
            R.string.busy_info,
            R.string.busy_import,
            R.string.busy_verify,
            R.string.busy_weigh,
            R.string.busy_scan,
            R.string.busy_other,
        ).forEach { compose.onNodeWithText(string(it)).assertExists() }
    }

    /** Не занято — никакой строки о работе быть не должно. */
    @Test
    fun `an idle card says nothing about work`() {
        setContent { DeviceCard(device = device(), busy = false, action = "SYNC") }

        compose.onNodeWithText(string(R.string.busy_sync)).assertDoesNotExist()
    }

    // --- карточка заезда ---

    /** Облачко — отметка «доехало до Health Connect», и без импорта его нет. */
    @Test
    fun `a ride outside health connect has no cloud mark`() {
        setContent {
            RideCard(
                ride(imported = false),
                selected = false,
                selectionMode = false,
                onClick = {},
                onLongClick = {},
                onShare = {},
            )
        }

        compose.onNodeWithContentDescription(string(R.string.cd_in_health)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.ride_distance, "7.35")).assertExists()
    }

    // --- журнал ---

    @Test
    fun `an empty log says so instead of showing a blank tail`() {
        setContent { LogCard(emptyList()) }

        compose.onNodeWithText(string(R.string.log_empty)).assertExists()
    }

    // --- обновление ---

    /** Между скачиванием и установкой APK ещё сверяется с суммой — это видно. */
    @Test
    fun `update card names the verifying stage`() {
        setContent {
            UpdateCard(
                update = UpdateChecker.Update("0.9.9", null, "https://example.invalid", null),
                onDownload = {},
                progress = UpdateProgress(UpdateProgress.Stage.VERIFY),
            )
        }

        compose.onNodeWithText(string(R.string.update_verifying)).assertExists()
        compose.onNodeWithText(string(R.string.update_downloading)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.update_download)).assertDoesNotExist()
    }

    // --- профиль ---

    @Test
    fun `profile dialog saves the female sex`() {
        setContent { ProfileDialog(onDismiss = {}) }

        compose.onNodeWithText(string(R.string.profile_birth_year)).performTextInput("1990")
        compose.onNodeWithText(string(R.string.profile_female)).performClick()
        compose.onNodeWithText(string(R.string.profile_save)).performClick()

        assertEquals(1990, Settings.birthYear.value)
        assertEquals(Calories.Sex.FEMALE, Settings.sex.value)
    }

    /** Год необязателен: один пол уже сужает расчёт, и сохранить это можно. */
    @Test
    fun `profile dialog saves a sex without a year`() {
        setContent { ProfileDialog(onDismiss = {}) }

        compose.onNodeWithText(string(R.string.profile_female)).performClick()
        compose.onNodeWithText(string(R.string.profile_save)).performClick()

        assertEquals(null, Settings.birthYear.value)
        assertEquals(Calories.Sex.FEMALE, Settings.sex.value)
    }

    private companion object {
        const val CHARGED = "charged"
        const val UNKNOWN = "unknown"
        const val LOW = "low"
        const val FEW_COLORS = 3
    }
}
