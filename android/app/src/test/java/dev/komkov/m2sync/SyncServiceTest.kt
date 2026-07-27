package dev.komkov.m2sync

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothDevice
import java.io.File

/**
 * Сервис целиком, как его запускает система: через [Robolectric.buildService].
 *
 * Health Connect в Robolectric нет, поэтому ветки «сервис недоступен» тут
 * настоящие, а не подстроенные. Радио подменяем шэдоу-адаптером: сканеру
 * скармливаем результат руками, а соединение роняем — до живого GATT в
 * тестовой среде всё равно не дойти.
 */
@RunWith(RobolectricTestRunner::class)
class SyncServiceTest {
    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
        const val DEVICE_NAME = "M2_1234"
        const val PREFS = "m2sync"
        const val KEY_IMPORTED = "imported"

        /** Сколько ждём фоновую корутину сервиса, прежде чем признать её зависшей. */
        const val TIMEOUT_MS = 40_000L

        /** Статус разрыва соединения, каким его отдаёт стек Android. */
        const val GATT_ERROR = 133
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LogBus.init(ctx)
        LogBus.lines.value = emptyList()
    }

    /**
     * И журнал, и состояние приложения — синглтоны на всю JVM: не почистишь за
     * собой, и следующий тестовый класс увидит чужие заезды.
     */
    @After
    fun tearDown() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        LogBus.lines.value = emptyList()
        AppState.busy.value = false
        AppState.action.value = null
        AppState.device.value = null
        AppState.rides.value = emptyList()
        AppState.transfer.value = null
        AppState.weight.value = null
    }

    // ------------------------------------------------------------ инструменты

    private fun adapter(): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun grantBle() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    private fun intentFor(
        action: String?,
        name: String? = null,
        address: String? = null,
    ): Intent {
        val intent = Intent(ctx, SyncService::class.java)
        action?.let { intent.action = it }
        name?.let { intent.putExtra(SyncService.EXTRA_NAME, it) }
        address?.let { intent.putExtra(SyncService.EXTRA_ADDRESS, it) }
        return intent
    }

    private fun newService(intent: Intent): ServiceController<SyncService> =
        Robolectric.buildService(SyncService::class.java, intent).create()

    private fun fakeDevice(): ScanResult {
        val device = ShadowBluetoothDevice.newInstance(MAC)
        shadowOf(device).setName(DEVICE_NAME)
        return ScanResult(device, null, -55, 0L)
    }

    /**
     * Крутим тест-поток, пока сервис не допишет «done» по каждому действию.
     * Заодно играем за эфир: подсовываем сканеру находку и рвём соединение,
     * иначе клиент честно прождёт свои таймауты.
     */
    private fun awaitDone(
        vararg actions: String,
        inTheAir: Boolean = false,
    ) {
        val markers = actions.map { ctx.getString(R.string.log_done, it) }
        val found = if (inTheAir) fakeDevice() else null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (markers.all { marker -> LogBus.lines.value.any { it.endsWith(marker) } }) return
            val bt = adapter()
            if (found != null) {
                bt?.bluetoothLeScanner?.let { scanner ->
                    shadowOf(scanner).scanCallbacks.forEach { it.onScanResult(1, found) }
                }
            }
            bt?.getRemoteDevice(MAC)?.let { remote ->
                if (shadowOf(remote).bluetoothGatts.isNotEmpty()) {
                    shadowOf(remote).simulateGattConnectionChange(
                        GATT_ERROR,
                        BluetoothProfile.STATE_DISCONNECTED,
                    )
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        fail("сервис не доделал ${actions.toList()}; журнал: ${LogBus.lines.value}")
    }

    /** Запускает одну команду и дожидается её конца. */
    private fun run(
        action: String?,
        name: String? = null,
        address: String? = null,
        inTheAir: Boolean = false,
    ): ServiceController<SyncService> {
        val controller = newService(intentFor(action, name, address))
        controller.startCommand(0, 1)
        awaitDone(action ?: SyncService.ACTION_STATUS, inTheAir = inTheAir)
        return controller
    }

    private fun logged(text: String): Boolean = LogBus.lines.value.any { it.endsWith(text) }

    private fun logged(
        id: Int,
        vararg args: Any,
    ): Boolean = logged(ctx.getString(id, *args))

    /** Строка о провале договаривает, что именно упало, поэтому ищем по вхождению. */
    private fun failed(action: String): Boolean {
        val head = ctx.getString(R.string.log_failed, action)
        return LogBus.lines.value.any { it.contains(head) }
    }

    private fun ride(
        name: String,
        bytes: Int = 16,
    ): File = File(SyncService.fitDir(ctx), name).apply { writeBytes(ByteArray(bytes)) }

    private fun markImported(vararg names: String) {
        ctx
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPORTED, names.toSet())
            .apply()
    }

    // -------------------------------------------------------------- статика

    @Test
    fun `bluetooth is considered granted only with both permissions`() {
        assertFalse(SyncService.bleGranted(ctx))

        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertFalse(SyncService.bleGranted(ctx))

        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(SyncService.bleGranted(ctx))
    }

    /** Каталог заездов создаётся сам: скачивать некуда — синхронизация умрёт. */
    @Test
    fun `the rides folder is created inside the app files`() {
        val dir = SyncService.fitDir(ctx)

        assertTrue(dir.isDirectory)
        assertEquals("fit", dir.name)
        assertEquals(ctx.getExternalFilesDir(null), dir.parentFile)
    }

    // ------------------------------------------------------------ без радио

    @Test
    fun `status lists local rides and marks the imported ones`() {
        ride("20260725102049.fit")
        ride("20260726084500.fit", bytes = 32)
        ride("notes.txt")
        markImported("20260725102049.fit")

        val controller = run(SyncService.ACTION_STATUS)

        assertTrue(logged(R.string.log_local_rides, 2))
        assertTrue(logged(R.string.log_folder, SyncService.fitDir(ctx).absolutePath))
        assertTrue(
            logged(
                R.string.log_file_line,
                "20260725102049.fit",
                16L,
                ctx.getString(R.string.log_imported_mark),
            ),
        )
        assertTrue(
            logged(
                R.string.log_file_line,
                "20260726084500.fit",
                32L,
                ctx.getString(R.string.log_new_mark),
            ),
        )
        controller.destroy()
    }

    /** Health Connect в тестовой среде нет — и статус обязан сказать это прямо. */
    @Test
    fun `status reports that health connect is missing`() {
        val controller = run(SyncService.ACTION_STATUS)

        assertTrue(logged(R.string.log_hc_status, ctx.getString(R.string.log_unavailable)))
        controller.destroy()
    }

    /**
     * Разбирать локальные файлы можно и с выключенным Bluetooth, поэтому сервис
     * поднимается как dataSync: connectedDevice система без разрешений не даёт.
     */
    @Test
    fun `a command without radio runs as a data sync service`() {
        val controller = run(SyncService.ACTION_STATUS)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            controller.get().foregroundServiceType,
        )
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    /** Неизвестное действие — не повод молчать: показываем состояние. */
    @Test
    fun `an unknown action falls back to status`() {
        val controller = run("dev.komkov.m2sync.NOT_A_COMMAND")

        assertTrue(logged(R.string.log_folder, SyncService.fitDir(ctx).absolutePath))
        controller.destroy()
    }

    @Test
    fun `perms prints every permission health connect expects`() {
        val controller = run(SyncService.ACTION_PERMS)

        assertTrue(logged(R.string.log_perms_header))
        val expected = HealthWriter.permissions + HealthWriter.readPermissions
        expected.forEach { assertTrue(it, logged("  $it")) }
        assertEquals(expected.size + 2, LogBus.lines.value.size)
        controller.destroy()
    }

    @Test
    fun `import without health connect stops right away`() {
        ride("20260725102049.fit")

        val controller = run(SyncService.ACTION_IMPORT)

        assertTrue(logged(R.string.log_hc_unavailable))
        assertFalse(logged(R.string.log_local_files, 1, 0))
        controller.destroy()
    }

    /** Самопроверка без Health Connect падает — но сервис переживает и досказывает. */
    @Test
    fun `verify reports its failure and still finishes`() {
        val controller = run(SyncService.ACTION_VERIFY)

        assertTrue(failed(SyncService.ACTION_VERIFY))
        assertTrue(logged(R.string.log_done, SyncService.ACTION_VERIFY))
        controller.destroy()
    }

    /**
     * STATUS и следом SYNC — обычное дело при старте приложения. Пока не доделаны
     * обе, останавливаться нельзя, иначе вторая команда умрёт не начавшись.
     */
    @Test
    fun `the service serves every queued command before stopping`() {
        val controller = newService(intentFor(SyncService.ACTION_PERMS))
        controller.startCommand(0, 1)
        controller.get().onStartCommand(intentFor(SyncService.ACTION_IMPORT), 0, 2)

        awaitDone(SyncService.ACTION_PERMS, SyncService.ACTION_IMPORT)

        assertTrue(logged(R.string.log_perms_header))
        assertTrue(logged(R.string.log_hc_unavailable))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    /** Работа кончилась — экран обязан выйти из состояния «занят». */
    @Test
    fun `a finished command leaves no busy state behind`() {
        val controller = run(SyncService.ACTION_PERMS)

        assertFalse(AppState.busy.value)
        assertNull(AppState.action.value)
        assertNull(AppState.transfer.value)
        controller.destroy()
    }

    @Test
    fun `the service is not meant to be bound`() {
        val controller = newService(intentFor(SyncService.ACTION_STATUS))

        assertNull(controller.get().onBind(intentFor(SyncService.ACTION_STATUS)))
        controller.destroy()
    }

    /** Убитый сервис не берётся за новую работу: очередь уходит вместе со scope. */
    @Test
    fun `a destroyed service picks up no more work`() {
        val controller = newService(intentFor(SyncService.ACTION_PERMS))
        controller.destroy()

        controller.get().onStartCommand(intentFor(SyncService.ACTION_PERMS), 0, 1)
        Thread.sleep(300)

        assertFalse(logged(R.string.log_perms_header))
        assertFalse(logged(R.string.log_done, SyncService.ACTION_PERMS))
    }

    // --------------------------------------------------------------- радио

    /**
     * Без разрешений на Bluetooth команда до радио не доходит: система убила бы
     * сервис прямо на старте, поэтому мы сами объясняем, чего не хватает.
     */
    @Test
    fun `a radio command without permissions asks for them and quits`() {
        val controller = newService(intentFor(SyncService.ACTION_SCAN))

        val result = controller.get().onStartCommand(intentFor(SyncService.ACTION_SCAN), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(logged(R.string.log_missing_perms, "${Manifest.permission.BLUETOOTH_SCAN}, ${Manifest.permission.BLUETOOTH_CONNECT}"))
        assertTrue(logged(R.string.log_ble_grant_hint))
        assertFalse(logged(R.string.log_scanning, "M2_"))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            controller.get().foregroundServiceType,
        )
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `scan reports the bike computer it hears on the air`() {
        grantBle()

        val controller = run(SyncService.ACTION_SCAN, inTheAir = true)

        assertTrue(logged(R.string.log_scanning, "M2_"))
        assertTrue(logged(R.string.log_found, DEVICE_NAME, MAC, -55))
        assertFalse(logged(R.string.log_nothing_found))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            controller.get().foregroundServiceType,
        )
        controller.destroy()
    }

    /** Префикс задаётся в команде: чужие устройства в список попадать не должны. */
    @Test
    fun `scan with another prefix finds nothing`() {
        grantBle()

        val controller = run(SyncService.ACTION_SCAN, name = "XOSS", inTheAir = true)

        assertTrue(logged(R.string.log_scanning, "XOSS"))
        assertFalse(logged(R.string.log_found, DEVICE_NAME, MAC, -55))
        assertTrue(logged(R.string.log_nothing_found))
        controller.destroy()
    }

    /** Адреса не дали — берём первое, что откликнулось, и говорим какое. */
    @Test
    fun `sync takes the first device it found`() {
        grantBle()

        val controller = run(SyncService.ACTION_SYNC, inTheAir = true)

        assertTrue(logged(R.string.log_scanning, "M2_"))
        assertTrue(logged(R.string.log_taking, DEVICE_NAME, MAC))
        assertTrue(failed(SyncService.ACTION_SYNC))
        assertNull(AppState.transfer.value)
        controller.destroy()
    }

    /** Адрес известен — поиск лишний: карточка устройства зовёт сервис именно так. */
    @Test
    fun `sync with a known address does not scan`() {
        grantBle()

        val controller = run(SyncService.ACTION_SYNC, address = MAC)

        assertFalse(logged(R.string.log_scanning, "M2_"))
        assertTrue(failed(SyncService.ACTION_SYNC))
        controller.destroy()
    }

    @Test
    fun `info with a known address does not scan either`() {
        grantBle()

        val controller = run(SyncService.ACTION_INFO, address = MAC)

        assertFalse(logged(R.string.log_scanning, "M2_"))
        assertTrue(failed(SyncService.ACTION_INFO))
        assertNull(AppState.device.value)
        controller.destroy()
    }

    /** Адреса нет — быстрый опрос сам ищет велокомпьютер, как и синхронизация. */
    @Test
    fun `info without an address looks for the device first`() {
        grantBle()

        val controller = run(SyncService.ACTION_INFO, inTheAir = true)

        assertTrue(logged(R.string.log_scanning, "M2_"))
        assertTrue(logged(R.string.log_found, DEVICE_NAME, MAC, -55))
        assertTrue(failed(SyncService.ACTION_INFO))
        assertNull(AppState.device.value)
        controller.destroy()
    }

    /** Взвешивание сперва зовёт человека на весы, а уже потом ловит эфир. */
    @Test
    fun `weighing asks to step on the scale and gives up without radio`() {
        grantBle()
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)

        val controller = run(SyncService.ACTION_WEIGH)

        assertTrue(logged(R.string.log_scale_waiting))
        assertTrue(failed(SyncService.ACTION_WEIGH))
        assertNull(AppState.weight.value)
        controller.destroy()
    }
}
