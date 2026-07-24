package dev.komkov.m2sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.health.connect.client.records.ExerciseSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Вся работа идёт здесь, чтобы её можно было запускать без экрана — из adb:
 *
 *   adb shell am start-foreground-service -n dev.komkov.m2sync/.SyncService \
 *       -a dev.komkov.m2sync.SYNC
 *   adb logcat -s M2SYNC
 */
class SyncService : Service() {

    companion object {
        const val ACTION_SCAN = "dev.komkov.m2sync.SCAN"
        const val ACTION_SYNC = "dev.komkov.m2sync.SYNC"
        const val ACTION_IMPORT = "dev.komkov.m2sync.IMPORT"
        const val ACTION_STATUS = "dev.komkov.m2sync.STATUS"
        const val ACTION_PERMS = "dev.komkov.m2sync.PERMS"
        const val ACTION_VERIFY = "dev.komkov.m2sync.VERIFY"
        const val ACTION_INFO = "dev.komkov.m2sync.INFO"

        const val EXTRA_NAME = "name"
        const val EXTRA_ADDRESS = "address"

        /** `-e force 1` — переимпортировать уже загруженные файлы. */
        const val EXTRA_FORCE = "force"

        private const val CHANNEL_ID = "m2sync"
        private const val PREFS = "m2sync"
        private const val KEY_IMPORTED = "imported"
        private const val DEFAULT_PREFIX = "M2_"

        fun fitDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null), "fit").apply { mkdirs() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = Mutex()

    /**
     * Команд может прилететь несколько подряд (например STATUS и сразу SYNC).
     * Останавливаемся только когда доделаны все, иначе scope.cancel() рубит
     * ещё не начатую работу.
     */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LogBus.init(this)
        AppState.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            1,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val action = intent?.action ?: ACTION_STATUS
        val prefix = intent?.getStringExtra(EXTRA_NAME) ?: DEFAULT_PREFIX
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        val force = intent?.getStringExtra(EXTRA_FORCE) != null

        pending.incrementAndGet()
        scope.launch {
            busy.withLock {
                AppState.busy.value = true
                AppState.action.value = action.substringAfterLast('.')
                try {
                    when (action) {
                        ACTION_SCAN -> doScan(prefix)
                        ACTION_SYNC -> doSync(prefix, address)
                        ACTION_IMPORT -> doImport(force)
                        ACTION_PERMS -> doPerms()
                        ACTION_VERIFY -> doVerify()
                        ACTION_INFO -> doInfo(prefix, address)
                        else -> doStatus()
                    }
                } catch (e: Exception) {
                    LogBus.e(R.string.log_failed, e, action)
                } finally {
                    LogBus.i(R.string.log_done, action)
                    AppState.busy.value = false
                    AppState.action.value = null
                    AppState.transfer.value = null
                }
            }
            if (pending.decrementAndGet() == 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- команды

    private suspend fun doScan(prefix: String) {
        LogBus.i(R.string.log_scanning, prefix)
        val found = M2Client(this).scan(prefix)
        if (found.isEmpty()) LogBus.i(R.string.log_nothing_found)
    }

    private suspend fun doSync(prefix: String, address: String?) {
        val client = M2Client(this)
        val target = address ?: run {
            LogBus.i(R.string.log_scanning, prefix)
            client.scan(prefix).firstOrNull()?.also {
                LogBus.i(R.string.log_taking, it.name, it.address)
            }?.address ?: throw M2Error("bike computer not found")
        }

        try {
            client.connect(target)
            snapshot(client, target)

            val files = client.listFiles()
            LogBus.i(R.string.log_rides_on_device, files.size)

            val dir = fitDir(this)
            var fetched = 0
            for (f in files) {
                val local = File(dir, f.name)
                if (local.exists() && local.length() == f.size.toLong()) {
                    LogBus.i(R.string.log_already_have, f.name)
                    continue
                }
                LogBus.i(R.string.log_downloading, f.name, f.size)
                AppState.transfer.value = Triple(f.name, 0, f.size)
                val data = client.fetchFile(f.name)
                local.writeBytes(data)
                fetched++
                AppState.transfer.value = Triple(f.name, f.size, f.size)
                LogBus.i(R.string.log_saved, f.name)
            }
            LogBus.i(R.string.log_downloaded_total, fetched)
        } finally {
            AppState.transfer.value = null
            client.close()
        }

        doImport()
    }

    /** Быстрый опрос устройства без скачивания — для карточки на экране. */
    private suspend fun doInfo(prefix: String, address: String?) {
        val client = M2Client(this)
        val target = address ?: run {
            LogBus.i(R.string.log_scanning, prefix)
            client.scan(prefix).firstOrNull()?.address
        } ?: throw M2Error("bike computer not found")
        try {
            client.connect(target)
            snapshot(client, target)
        } finally {
            client.close()
        }
    }

    private suspend fun snapshot(client: M2Client, address: String) {
        val firmware = client.readFirmware()?.also { LogBus.i(R.string.log_firmware, it) }
        val battery = client.readBattery()?.also { LogBus.i(R.string.log_battery, it) }
        val disk = client.diskSpace()?.also { LogBus.i(R.string.log_memory, it) }
        AppState.saveDevice(
            this,
            DeviceSnapshot(
                name = getString(R.string.device_default_name),
                address = address,
                firmware = firmware,
                battery = battery,
                freeKb = disk?.substringBefore('/')?.trim()?.toIntOrNull(),
                totalKb = disk?.substringAfter('/')?.trim()?.toIntOrNull(),
                seenAt = Instant.now(),
            )
        )
    }

    private fun importedNames(): Set<String> =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IMPORTED, emptySet())!!

    private suspend fun doImport(force: Boolean = false) {
        if (!HealthWriter.available(this)) {
            LogBus.e(R.string.log_hc_unavailable)
            return
        }
        val granted = HealthWriter.granted(this)
        val missing = HealthWriter.permissions - granted
        if (missing.isNotEmpty()) {
            LogBus.e(R.string.log_missing_perms, missing.joinToString())
            LogBus.e(R.string.log_grant_hint)
            return
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val imported = prefs.getStringSet(KEY_IMPORTED, emptySet())!!.toMutableSet()

        val files = fitDir(this).listFiles { f -> f.name.endsWith(".fit") }?.sorted() ?: emptyList()
        LogBus.i(R.string.log_local_files, files.size, imported.size)

        for (file in files) {
            if (!force && file.name in imported) continue
            try {
                val ride = FitParser.parse(file)
                val count = HealthWriter.write(this, ride)
                imported += file.name
                prefs.edit().putStringSet(KEY_IMPORTED, imported).apply()
                LogBus.i(
                    R.string.log_ride_imported,
                    file.name,
                    String.format(Locale.getDefault(), "%.2f", (ride.totalDistance ?: 0.0) / 1000),
                    ride.points.size,
                    ride.avgHeartRate?.toString() ?: getString(R.string.dash),
                    count,
                )
            } catch (e: Exception) {
                LogBus.e(R.string.log_import_failed, e, file.name)
            }
        }
        RideStore.refresh(this, imported)
    }

    private suspend fun doStatus() {
        val available = HealthWriter.available(this)
        LogBus.i(
            R.string.log_hc_status,
            getString(if (available) R.string.log_available else R.string.log_unavailable),
        )
        if (available) {
            val granted = HealthWriter.granted(this)
            LogBus.i(R.string.log_perm_count, granted.size, HealthWriter.permissions.size)
            (HealthWriter.permissions - granted).forEach { LogBus.i(R.string.log_perm_missing, it) }
        }
        val dir = fitDir(this)
        val files = dir.listFiles { f -> f.name.endsWith(".fit") } ?: emptyArray()
        LogBus.i(R.string.log_folder, dir.absolutePath)
        LogBus.i(R.string.log_local_rides, files.size)
        val imported = importedNames()
        RideStore.refresh(this, imported)
        files.sortedBy { it.name }.forEach {
            LogBus.i(
                R.string.log_file_line,
                it.name,
                it.length(),
                getString(
                    if (it.name in imported) R.string.log_imported_mark else R.string.log_new_mark
                ),
            )
        }
    }

    /** Читаем из Health Connect то, что записали — чтобы не верить на слово insertRecords. */
    private suspend fun doVerify() {
        val granted = HealthWriter.granted(this)
        val missing = HealthWriter.readPermissions - granted
        if (missing.isNotEmpty()) {
            LogBus.e(R.string.log_verify_perms, missing.joinToString())
            return
        }
        val since = Instant.now().minus(Duration.ofDays(60))
        val sessions = HealthWriter.readSessions(this, since)
            .filter { it.metadata.dataOrigin.packageName == packageName }
        LogBus.i(R.string.log_verify_count, sessions.size)
        for (s in sessions.sortedBy { it.startTime }) {
            // Считаем по своим сегментам: агрегат Health Connect не фильтруется
            // по источнику и смешал бы наши данные с чужими.
            val moving = s.segments
                .filter { it.segmentType != ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
                .sumOf { Duration.between(it.startTime, it.endTime).seconds } / 60
            LogBus.i(
                R.string.log_verify_line,
                s.startTime.toString(),
                Duration.between(s.startTime, s.endTime).toMinutes(),
                moving,
                String.format(
                    Locale.getDefault(),
                    "%.2f",
                    HealthWriter.readDistanceTotal(this, s.startTime, s.endTime) / 1000,
                ),
                HealthWriter.readHeartRateCount(this, s.startTime, s.endTime),
                getString(
                    if (s.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data)
                        R.string.log_route_yes else R.string.log_route_no
                ),
                s.metadata.dataOrigin.packageName,
            )
            LogBus.i(
                R.string.log_verify_extra,
                HealthWriter.readCadenceCount(this, s.startTime, s.endTime),
                HealthWriter.readSpeedCount(this, s.startTime, s.endTime),
                s.segments.size,
            )
        }
    }

    private fun doPerms() {
        LogBus.i(R.string.log_perms_header)
        (HealthWriter.permissions + HealthWriter.readPermissions).sorted()
            .forEach { LogBus.i("  $it") }
    }
}
