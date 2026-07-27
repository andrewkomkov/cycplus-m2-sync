package dev.komkov.m2sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Instant

/**
 * Обёртка вокруг теста целиком: `@Before` для подготовки состояния уже поздно —
 * compose-правило к тому моменту подняло активити и прогнало onCreate, а
 * `@After` ещё рано — экран жив. Правило ставится самым внешним, поэтому
 * готовит до старта и прибирает после того, как активити уже разобрана.
 */
private class Around(
    private val setUp: () -> Unit,
    private val tearDown: () -> Unit,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                setUp()
                try {
                    base.evaluate()
                } finally {
                    tearDown()
                }
            }
        }
}

/**
 * Settings, AppState и LogBus — синглтоны, и весь прогон делит их между собой:
 * Robolectric поднимает приложение заново, а статику не трогает. Поэтому и до,
 * и после теста возвращаем их ровно к тому, что видит чистый запуск, — иначе
 * соседний тест унаследует чужой экран.
 */
private fun resetSingletons() {
    // Автосинк помнится «один раз на процесс», без сброса тесты зависели бы от
    // порядка запуска.
    MainActivity::class.java
        .getDeclaredField("autoSyncDone")
        .apply { isAccessible = true }
        .setBoolean(null, false)
    Settings.autoSync.value = true
    Settings.autoUpdate.value = true
    Settings.mapLayer.value = MapLayer.MAP
    Settings.birthYear.value = null
    Settings.sex.value = null
    LogBus.lines.value = emptyList()
    AppState.busy.value = false
    AppState.action.value = null
    AppState.device.value = null
    AppState.rides.value = emptyList()
    AppState.transfer.value = null
    AppState.weight.value = null
    AppState.update.value = null
    AppState.updateProgress.value = null
    forgetFileProviderRoots()
}

/**
 * FileProvider держит разобранный file_paths.xml в статической карте по имени
 * authority, а Robolectric выдаёт каждому тесту свой каталог данных. Без сброса
 * второй тест, который делится файлом, получил бы корни от первого и упал бы на
 * «failed to find configured root».
 */
private fun forgetFileProviderRoots() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    (cache.get(null) as MutableMap<*, *>).clear()
}

/** Незабранные «запуски» — хвост, по которому следующий тест прочитает чужое. */
private fun drainStartedIntents() {
    val shadow = shadowOf(RuntimeEnvironment.getApplication())
    generateSequence { shadow.nextStartedService }.count()
    generateSequence { shadow.nextStartedActivity }.count()
}

private fun startedServiceActions(): List<String> {
    val shadow = shadowOf(RuntimeEnvironment.getApplication())
    return generateSequence { shadow.nextStartedService }.map { it.action.orEmpty() }.toList()
}

private fun ride(
    file: String,
    distanceM: Double,
) = RideSummary(
    file = file,
    start = Instant.parse("2026-07-25T10:20:49Z"),
    distanceM = distanceM,
    elapsedMin = 65,
    movingMin = 33,
    avgHeartRate = 128,
    avgCadence = 52,
    ascent = 13,
    points = 2023,
    hasRoute = true,
    imported = true,
    kcal = 232,
)

/**
 * Экран длинный, а тестовый телефон низкий: карточки, кнопки и журнал лежат
 * ниже сгиба и в компоновку не попадают. Ищем их так же, как человек, —
 * докрутив список. Растянуть экран было бы проще, но тогда каждый тест
 * раскладывает втрое больше текста, и прогон упирается в память.
 */
private fun AndroidComposeTestRule<*, *>.reachText(
    text: String,
    substring: Boolean = false,
): SemanticsNodeInteraction {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
    waitForIdle()
    return onNodeWithText(text, substring = substring)
}

/**
 * Главный экран целиком: активити поднимается Robolectric со всем, что она
 * делает в onCreate, — загрузкой сохранённого состояния, запросом разрешений и
 * первой командой сервису.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(Around(::prepare, ::cleanUp))
            .around(compose)

    private fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        resetSingletons()
        // Проверка обновлений сама ходит на GitHub — в тестах сеть не нужна.
        Settings.setAutoUpdate(app, false)
        // Синк на старте выключен: иначе каждая проверка отправленных команд
        // разбиралась бы ещё и с ним.
        Settings.setAutoSync(app, false)
        AppState.saveRides(app, listOf(ride("a.fit", 7350.0), ride("b.fit", 40_990.0)))
    }

    private fun cleanUp() {
        drainStartedIntents()
        resetSingletons()
    }

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    // --- старт ---

    @Test
    fun `the screen shows the rides that were stored`() {
        compose.onNodeWithText(string(R.string.title_rides)).assertExists()
        compose.onNodeWithText(string(R.string.subtitle_rides, 2)).assertExists()

        compose.reachText(string(R.string.ride_distance, "7.35")).assertExists()
        compose.reachText(string(R.string.ride_distance, "40.99")).assertExists()
    }

    /**
     * На старте экран просит сервис рассказать, что уже есть локально, а у
     * системы — разрешения на радио: без них синк всё равно был бы невозможен.
     */
    @Test
    fun `the activity asks the service for the state and the system for the radio`() {
        assertEquals(listOf(SyncService.ACTION_STATUS), startedServiceActions())

        val requested = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull(requested)
        val names = requested.requestedPermissions.toList()
        assertTrue(names.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(names.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(names.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    /** Ответ системы на запрос разрешений, как его приносит платформа. */
    private fun answerPermissionRequest(granted: Boolean) {
        val request = shadowOf(compose.activity).lastRequestedPermission
        val verdict =
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { verdict },
            )
        }
        compose.waitForIdle()
    }

    /**
     * Разрешения только что дали — синк, отложенный на старте, выполняется сам,
     * не заставляя жать кнопку вручную.
     */
    @Test
    fun `granting the radio runs the sync that was postponed`() {
        Settings.autoSync.value = true

        answerPermissionRequest(granted = true)

        assertEquals(
            listOf(SyncService.ACTION_STATUS, SyncService.ACTION_SYNC),
            startedServiceActions(),
        )
    }

    /** Отказ не молчит: без радио синка не будет, и это должно попасть в журнал. */
    @Test
    fun `a refused radio permission is named in the log`() {
        Settings.autoSync.value = true

        answerPermissionRequest(granted = false)

        assertTrue(
            LogBus.lines.value
                .last()
                .contains(Manifest.permission.BLUETOOTH_SCAN),
        )
        assertEquals(listOf(SyncService.ACTION_STATUS), startedServiceActions())
    }

    // --- кнопки действий ---

    @Test
    fun `the action buttons send their commands to the service`() {
        compose.reachText(string(R.string.btn_sync)).performClick()
        compose.reachText(string(R.string.btn_poll)).performClick()
        compose.reachText(string(R.string.btn_verify)).performClick()
        compose.reachText(string(R.string.btn_weigh)).performClick()

        assertEquals(
            listOf(
                SyncService.ACTION_STATUS,
                SyncService.ACTION_SYNC,
                SyncService.ACTION_INFO,
                SyncService.ACTION_VERIFY,
                SyncService.ACTION_WEIGH,
            ),
            startedServiceActions(),
        )
    }

    /** Пока идёт работа по BLE, вторая команда порвала бы сессию. */
    @Test
    fun `the buttons go dead while the service is busy`() {
        compose.runOnUiThread { AppState.busy.value = true }
        compose.waitForIdle()

        compose.reachText(string(R.string.btn_sync)).assertIsNotEnabled()
        compose.reachText(string(R.string.btn_weigh)).assertIsNotEnabled()
        assertEquals(listOf(SyncService.ACTION_STATUS), startedServiceActions())
    }

    /** На машине без Health Connect просить разрешения не у кого — так и говорим. */
    @Test
    fun `the permissions button reports a missing health connect`() {
        compose.onNodeWithContentDescription(string(R.string.cd_permissions)).performClick()
        compose.waitForIdle()

        assertTrue(
            LogBus.lines.value
                .last()
                .endsWith(string(R.string.log_hc_unavailable)),
        )
        // Свёрнутый журнал режет строку по сороковому символу — разворачиваем.
        compose.reachText(string(R.string.log_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.log_hc_unavailable), substring = true).assertExists()
    }

    // --- меню ---

    private fun openMenu() {
        compose.onNodeWithContentDescription(string(R.string.cd_menu)).performClick()
        compose.waitForIdle()
    }

    /** Оба тумблера и перебор подложки живут в одном меню и правят настройки. */
    @Test
    fun `the menu flips both switches and cycles the basemap`() {
        assertFalse(Settings.autoSync.value)
        assertFalse(Settings.autoUpdate.value)
        assertEquals(MapLayer.MAP, Settings.mapLayer.value)

        openMenu()
        compose.onNodeWithText(string(R.string.setting_auto_sync)).performClick()
        assertTrue(Settings.autoSync.value)

        compose.onNodeWithText(string(R.string.setting_auto_update)).performClick()
        assertTrue(Settings.autoUpdate.value)

        // Состояний три, поэтому пункт не тумблер, а перебор по кругу.
        compose.onNodeWithText(string(R.string.setting_map_layer)).performClick()
        assertEquals(MapLayer.SATELLITE, Settings.mapLayer.value)
    }

    @Test
    fun `the menu opens the profile dialog`() {
        openMenu()
        compose.onNodeWithText(string(R.string.menu_profile)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.profile_birth_year)).assertExists()
        // Меню при этом закрывается: висеть под диалогом ему незачем.
        compose.onNodeWithText(string(R.string.setting_map_layer)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.profile_cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.profile_birth_year)).assertDoesNotExist()
    }

    // --- выделение ---

    private fun longClickFirstRide() {
        compose
            .reachText(string(R.string.ride_distance, "7.35"))
            .performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test
    fun `a long press starts a selection that grows and shrinks by tapping`() {
        longClickFirstRide()

        compose.onNodeWithText(string(R.string.selected_count, 1)).assertExists()
        compose.onNodeWithText(string(R.string.title_rides)).assertDoesNotExist()
        // Действия шапки уехали в нижнюю панель — там до них дотягивается палец.
        compose.onNodeWithContentDescription(string(R.string.cd_menu)).assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.select_all)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.selected_count, 2)).assertExists()

        // Повторный тап по выбранному заезду убирает его из выделения, а не
        // открывает: режим выбора перехватывает обычное нажатие.
        compose.reachText(string(R.string.ride_distance, "40.99")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.selected_count, 1)).assertExists()

        // ...а следующий тап возвращает заезд обратно.
        compose.reachText(string(R.string.ride_distance, "40.99")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.selected_count, 2)).assertExists()
    }

    /** Нижняя панель отдаёт наружу именно выбранное, а не весь список. */
    @Test
    fun `the toolbar shares the selected rides`() {
        File(SyncService.fitDir(compose.activity), "b.fit").writeBytes(ByteArray(64))
        longClickFirstRide()
        compose.onNodeWithContentDescription(string(R.string.select_all)).performClick()
        compose.waitForIdle()
        drainStartedIntents()

        compose.onNodeWithContentDescription(string(R.string.cd_share)).performClick()
        compose.waitForIdle()

        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send?.action)
    }

    @Test
    fun `the cross clears the selection`() {
        longClickFirstRide()

        compose.onNodeWithContentDescription(string(R.string.cd_clear_selection)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.title_rides)).assertExists()
    }

    /** Системное «назад» снимает выделение, а не закрывает приложение. */
    @Test
    fun `system back drops the selection instead of the app`() {
        longClickFirstRide()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.title_rides)).assertExists()
        assertFalse(compose.activity.isFinishing)
    }

    // --- заезд крупным планом ---

    @Test
    fun `tapping a ride opens it and the arrow brings the list back`() {
        compose.reachText(string(R.string.ride_distance, "7.35")).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(string(R.string.cd_back)).assertExists()
        compose.onNodeWithText(string(R.string.title_rides)).assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.title_rides)).assertExists()
    }

    // --- карточки в шапке списка ---

    /** Всё, что приходит из сервиса, экран показывает без перезапуска. */
    @Test
    fun `the header cards follow the state as it arrives`() {
        compose.reachText(string(R.string.weight_unknown)).assertExists()

        compose.runOnUiThread {
            AppState.device.value =
                DeviceSnapshot(
                    name = "Cycplus M2",
                    address = "E3:E8:F7:E3:09:44",
                    firmware = "V1.4.0",
                    battery = 100,
                    freeKb = 708,
                    totalKb = 16384,
                    seenAt = Instant.parse("2026-07-25T08:35:00Z"),
                )
            AppState.weight.value = WeightReading(72.8, Instant.parse("2026-07-25T08:30:00Z"))
            AppState.update.value =
                UpdateChecker.Update(
                    version = "0.9.9",
                    apkUrl = "https://example.invalid/app.apk",
                    pageUrl = "https://example.invalid/releases",
                    notes = null,
                )
        }
        compose.waitForIdle()

        compose.reachText(string(R.string.update_available, "0.9.9")).assertExists()
        compose.reachText("Cycplus M2").assertExists()
        compose.reachText(string(R.string.weight_value, "72.80")).assertExists()
    }

    // --- отправка наружу ---

    @Test
    fun `share all hands the staged files to the system chooser`() {
        File(SyncService.fitDir(compose.activity), "a.fit").writeBytes(ByteArray(64))
        drainStartedIntents()

        compose
            .onNodeWithContentDescription(string(R.string.cd_share_all))
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send?.action)
    }
}

/** Заездов ещё нет: экран должен объяснить, что делать, а не молчать пустотой. */
@RunWith(RobolectricTestRunner::class)
class MainActivityEmptyTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                Around(
                    setUp = {
                        val app = RuntimeEnvironment.getApplication()
                        resetSingletons()
                        Settings.setAutoUpdate(app, false)
                        Settings.setAutoSync(app, false)
                        AppState.saveRides(app, emptyList())
                    },
                    tearDown = {
                        drainStartedIntents()
                        resetSingletons()
                    },
                ),
            ).around(compose)

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    @Test
    fun `an empty list explains what to do and has nothing to share`() {
        compose.onNodeWithText(string(R.string.title_rides)).assertExists()
        // Подзаголовок со счётчиком без заездов был бы «0 в списке».
        compose.onNodeWithText(string(R.string.subtitle_rides, 0)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.cd_share_all)).assertIsNotEnabled()

        compose.reachText(string(R.string.rides_empty)).assertExists()
    }
}

/**
 * Разрешения на радио уже есть и синк при запуске включён — экран идёт к
 * велокомпьютеру сам, не заставляя жать кнопку.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityAutoSyncTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                Around(
                    setUp = {
                        val app = RuntimeEnvironment.getApplication()
                        resetSingletons()
                        Settings.setAutoUpdate(app, false)
                        Settings.setAutoSync(app, true)
                        shadowOf(app).grantPermissions(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        )
                        AppState.saveRides(app, emptyList())
                    },
                    tearDown = {
                        drainStartedIntents()
                        resetSingletons()
                    },
                ),
            ).around(compose)

    @Test
    fun `a granted radio starts the sync right away`() {
        assertEquals(
            listOf(SyncService.ACTION_STATUS, SyncService.ACTION_SYNC),
            startedServiceActions(),
        )
    }

    /** Автосинк — один раз на запуск процесса, а не на каждый поворот экрана. */
    @Test
    fun `a recreated activity does not sync a second time`() {
        assertEquals(
            listOf(SyncService.ACTION_STATUS, SyncService.ACTION_SYNC),
            startedServiceActions(),
        )

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        assertEquals(listOf(SyncService.ACTION_STATUS), startedServiceActions())
    }
}
