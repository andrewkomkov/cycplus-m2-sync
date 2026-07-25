package dev.komkov.m2sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** Один замер с весов. */
data class ScaleReading(
    val kilograms: Double,
    /** Весы досчитали и показали цифру, а не «прыгают» под ногами. */
    val stabilized: Boolean,
    /** Пользователь уже сошёл — значение финальное. */
    val removed: Boolean,
)

/**
 * Умные весы поверх стандартного профиля Weight Scale.
 *
 * Читаем только рекламу: весы шлют результат в service data и не требуют
 * подключения, поэтому ничего не спариваем и не держим GATT.
 *
 * Проверено на Mi Smart Scale 2 — она отдаёт 10-байтный 0x181D. Состава тела в
 * эфире нет: импеданс живёт в 13-байтном 0x181B, а его весы не шлют, Mi Fit
 * считает жир на телефоне сам.
 */
@SuppressLint("MissingPermission")
class ScaleClient(private val ctx: Context) {

    companion object {
        /** Weight Scale и Body Composition из списка назначенных номеров Bluetooth. */
        val WEIGHT_SCALE: UUID = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb")
        val BODY_COMPOSITION: UUID = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")

        private const val MIN_PACKET = 10

        // Биты управляющего байта.
        private const val FLAG_POUNDS = 0x01
        private const val FLAG_JIN = 0x10
        private const val FLAG_STABILIZED = 0x20
        private const val FLAG_REMOVED = 0x80

        private const val KG_DIVISOR = 200.0
        private const val OTHER_DIVISOR = 100.0

        private const val JIN_PER_KG = 2.0
        private const val POUNDS_PER_KG = 2.2046226218

        /**
         * Разбор service data. Единицы задаёт сам прибор, поэтому приводим к
         * килограммам здесь, а не там, где показываем.
         */
        fun parse(data: ByteArray): ScaleReading? {
            if (data.size < MIN_PACKET) return null
            val ctrl = data[0].toInt() and 0xFF
            val raw = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)

            val kg = when {
                ctrl and FLAG_POUNDS != 0 -> raw / OTHER_DIVISOR / POUNDS_PER_KG
                ctrl and FLAG_JIN != 0 -> raw / OTHER_DIVISOR / JIN_PER_KG
                else -> raw / KG_DIVISOR
            }
            if (kg <= 0) return null

            return ScaleReading(
                kilograms = kg,
                stabilized = ctrl and FLAG_STABILIZED != 0,
                removed = ctrl and FLAG_REMOVED != 0,
            )
        }
    }

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * Ждём стабилизированный замер.
     *
     * Пока человек становится, весы сыплют промежуточными значениями — они нам
     * не нужны, берём первое с флагом стабилизации.
     */
    suspend fun awaitReading(timeoutMs: Long = 60_000): ScaleReading {
        val scanner = adapter?.bluetoothLeScanner ?: throw M2Error("Bluetooth is off")

        // Колбэк сканера приходит в чужом потоке, а ждём мы в своём.
        val stable = AtomicReference<ScaleReading?>(null)
        val lastSeen = AtomicReference<Double?>(null)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.serviceData[ParcelUuid(WEIGHT_SCALE)]
                    ?: record.serviceData[ParcelUuid(BODY_COMPOSITION)]
                    ?: return
                val reading = parse(data) ?: return

                if (lastSeen.getAndSet(reading.kilograms) != reading.kilograms) {
                    LogBus.i(R.string.log_scale_reading, String.format("%.2f", reading.kilograms))
                }
                if (reading.stabilized) stable.compareAndSet(null, reading)
            }

            override fun onScanFailed(errorCode: Int) = LogBus.e(R.string.log_scan_failed, errorCode)
        }

        // Фильтруем по сервису: чужой рекламы в эфире много, а нам нужен один профиль.
        val filters = listOf(WEIGHT_SCALE, BODY_COMPOSITION).map {
            ScanFilter.Builder().setServiceData(ParcelUuid(it), byteArrayOf(), byteArrayOf()).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, cb)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && stable.get() == null) delay(200)
        } finally {
            scanner.stopScan(cb)
        }
        return stable.get() ?: throw M2Error("no stabilized reading from the scale")
    }
}
