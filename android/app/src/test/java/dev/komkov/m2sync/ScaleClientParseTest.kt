package dev.komkov.m2sync

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** 16-битные идентификаторы профилей из списка назначенных номеров Bluetooth. */
private const val WEIGHT_SCALE_16 = 0x181D
private const val BODY_COMPOSITION_16 = 0x181B

/**
 * Ожидание замера с весов: чужую рекламу пропускаем, промежуточные значения
 * копим в журнале, а возвращаем первое стабилизированное.
 *
 * Разбор самого пакета проверяется в ScaleClientTest, здесь — сканирование.
 */
@RunWith(RobolectricTestRunner::class)
class ScaleClientParseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var client: ScaleClient

    @Before
    fun setUp() {
        // Журнал общий на всю JVM — не оставляем в нём своих строк.
        LogBus.lines.value = emptyList()
        client = ScaleClient(ctx)
    }

    @After
    fun tearDown() {
        LogBus.lines.value = emptyList()
    }

    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** ctrl + сырой вес little-endian + семь байт отметки времени. */
    private fun packet(
        ctrl: Int,
        raw: Int,
    ): ByteArray =
        byteArrayOf(
            ctrl.toByte(),
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
            0xEA.toByte(),
            0x07,
            7,
            25,
            8,
            29,
            24,
        )

    /** Рекламный кадр с service data: длина, тип 0x16, 16-битный UUID, данные. */
    private fun advertisement(
        service: Int,
        data: ByteArray,
    ): ScanRecord {
        val raw =
            byteArrayOf(
                (data.size + 3).toByte(),
                0x16,
                (service and 0xFF).toByte(),
                ((service shr 8) and 0xFF).toByte(),
            ) + data
        return ReflectionHelpers.callStaticMethod(
            ScanRecord::class.java,
            "parseFromBytes",
            ClassParameter.from(ByteArray::class.java, raw),
        )
    }

    private fun result(
        record: ScanRecord?,
        rssi: Int = -50,
    ): ScanResult = ScanResult(adapter().getRemoteDevice("AA:BB:CC:DD:EE:21"), record, rssi, 0)

    /**
     * Тень сканера сама раздаёт заготовленные результаты только тем, кто задал
     * фильтры по данным, а нам нужны и кадры без них — поэтому дёргаем колбэк
     * сами, дождавшись начала сканирования.
     */
    private fun weighing(
        timeoutMs: Long? = null,
        deliver: (ScanCallback) -> Unit,
    ): Result<ScaleReading> =
        runBlocking {
            val scanner = Shadow.extract<ShadowBluetoothLeScanner>(adapter().bluetoothLeScanner)
            val driver =
                launch(Dispatchers.Default) {
                    while (scanner.activeScans.isEmpty()) delay(5)
                    deliver(scanner.activeScans.first().scanCallback()!!)
                }
            val reading =
                runCatching {
                    if (timeoutMs == null) client.awaitReading() else client.awaitReading(timeoutMs)
                }
            driver.join()
            reading
        }

    private fun ScanCallback.send(record: ScanRecord?) = onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(record))

    @Test
    fun `the first stabilized reading ends the scan`() {
        val reading =
            weighing { cb ->
                cb.send(advertisement(WEIGHT_SCALE_16, packet(0x02, 13450)))
                cb.send(advertisement(WEIGHT_SCALE_16, packet(0x22, 14530)))
            }.getOrThrow()

        assertEquals(72.65, reading.kilograms, 0.001)
        assertTrue(reading.stabilized)
    }

    @Test
    fun `body composition advertisements are read as well`() {
        val reading =
            weighing(5_000) { cb ->
                cb.send(advertisement(BODY_COMPOSITION_16, packet(0xA2, 14530)))
            }.getOrThrow()

        assertEquals(72.65, reading.kilograms, 0.001)
        assertTrue(reading.removed)
    }

    @Test
    fun `every new weight is logged once`() {
        weighing(5_000) { cb ->
            cb.send(advertisement(WEIGHT_SCALE_16, packet(0x02, 13450)))
            cb.send(advertisement(WEIGHT_SCALE_16, packet(0x02, 13450)))
            cb.send(advertisement(WEIGHT_SCALE_16, packet(0x02, 14000)))
            cb.send(advertisement(WEIGHT_SCALE_16, packet(0x22, 14530)))
        }.getOrThrow()

        // 67.25, 70.00 и 72.65: повтор того же значения в журнал не идёт.
        assertEquals(3, LogBus.lines.value.size)
    }

    @Test
    fun `advertisements that are not a weight are skipped`() {
        val error =
            weighing(400) { cb ->
                cb.send(null)
                cb.send(advertisement(0x180F, packet(0x22, 14530)))
                cb.send(advertisement(WEIGHT_SCALE_16, byteArrayOf(0x22, 0x02, 0x38)))
                cb.send(advertisement(WEIGHT_SCALE_16, packet(0x02, 13450)))
            }.exceptionOrNull()

        assertTrue(error is M2Error)
        assertTrue(error!!.message!!.contains("no stabilized reading"))
        // В журнал попал только разобранный кадр.
        assertEquals(1, LogBus.lines.value.size)
    }

    @Test
    fun `a failed scan is logged and nothing is weighed`() {
        val error = weighing(400) { cb -> cb.onScanFailed(2) }.exceptionOrNull()

        assertTrue(error is M2Error)
        assertEquals(1, LogBus.lines.value.size)
    }

    @Test
    fun `weighing needs the radio to be on`() {
        val shadow = Shadow.extract<ShadowBluetoothAdapter>(adapter())
        shadow.setEnabled(false)
        shadow.setBleScanAlwaysAvailable(false)
        val error = assertThrows(M2Error::class.java) { runBlocking { client.awaitReading(300) } }
        assertEquals("Bluetooth is off", error.message)
    }
}
