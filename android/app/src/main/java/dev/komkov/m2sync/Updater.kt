package dev.komkov.m2sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Что происходит с обновлением прямо сейчас — для полосы на карточке. */
data class UpdateProgress(
    val stage: Stage,
    val bytes: Long = 0,
    val total: Long = 0,
) {
    enum class Stage { DOWNLOAD, VERIFY, INSTALL }

    /** Доля скачанного или null, пока длина ответа неизвестна. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Установка новой версии, не выходя из приложения.
 *
 * Раньше кнопка открывала браузер, а дальше пользователь сам искал файл в
 * загрузках и запускал установщик. Здесь APK скачивается внутрь приложения,
 * сверяется с опубликованной суммой и отдаётся системному [PackageInstaller] —
 * пользователь видит один штатный диалог «обновить приложение?».
 *
 * Магазина в этой схеме нет, поэтому Play-обновлений тоже нет: единственный
 * источник — релизы GitHub, и подпись проверяет сама система, отказываясь
 * ставить пакет с чужим ключом поверх нашего.
 */
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Хватит на любой наш APK; больше — повод не тянуть это в память устройства. */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    fun start(
        ctx: Context,
        update: UpdateChecker.Update,
    ) {
        val apkUrl = update.apkUrl
        if (apkUrl == null) {
            // Релиз без собранного APK: показать страницу — единственное, что
            // мы можем предложить.
            openPage(ctx, update.pageUrl)
            return
        }
        // Разрешение «ставить неизвестные приложения» выдаётся один раз и
        // только руками, в системных настройках — туда и отправляем.
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            LogBus.e(R.string.log_update_needs_permission)
            runCatching {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { openPage(ctx, update.pageUrl) }
            return
        }

        if (AppState.updateProgress.value != null) return
        val app = ctx.applicationContext
        scope.launch { run(app, update, apkUrl) }
    }

    private suspend fun run(
        ctx: Context,
        update: UpdateChecker.Update,
        apkUrl: String,
    ) {
        try {
            AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.DOWNLOAD)
            LogBus.i(R.string.log_update_downloading, update.version)
            val apk = download(ctx, apkUrl, update.version)

            AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.VERIFY)
            if (!verify(apk, update.sha256Url)) {
                apk.delete()
                LogBus.e(R.string.log_update_checksum_failed)
                return
            }

            AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.INSTALL)
            install(ctx, apk)
        } catch (e: Exception) {
            LogBus.e(R.string.log_update_download_failed, e)
            AppState.updateProgress.value = null
        }
    }

    private suspend fun download(
        ctx: Context,
        url: String,
        version: String,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            // Прошлые попытки не копим: APK крупный, а нужен ровно один.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "m2sync-$version.apk")

            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "m2sync/${BuildConfig.VERSION_NAME}")
                }
            try {
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done > MAX_APK_BYTES) throw M2Error("apk is suspiciously large")
                            AppState.updateProgress.value =
                                UpdateProgress(UpdateProgress.Stage.DOWNLOAD, done, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            target
        }

    /**
     * Сверка с суммой, опубликованной рядом с релизом. Подпись пакета проверит
     * система, а это ловит обрыв закачки и подмену по дороге до неё.
     *
     * Суммы у релиза может не быть — старые сборки её не публиковали, — и это
     * не повод отказывать в установке.
     */
    private suspend fun verify(
        apk: File,
        sha256Url: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (sha256Url == null) return@withContext true
            val published =
                runCatching {
                    val connection =
                        (URL(sha256Url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10_000
                            readTimeout = 10_000
                            setRequestProperty("User-Agent", "m2sync/${BuildConfig.VERSION_NAME}")
                        }
                    try {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull() ?: return@withContext true

            val expected = published.trim().substringBefore(' ').lowercase()
            if (expected.length != 64) return@withContext true
            val actual = sha256(apk)
            if (actual == expected) LogBus.i(R.string.log_update_verified)
            actual == expected
        }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(
        ctx: Context,
        apk: File,
    ) {
        val installer = ctx.packageManager.packageInstaller
        val params =
            PackageInstaller
                .SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(ctx.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback =
                PendingIntent.getBroadcast(
                    ctx,
                    sessionId,
                    Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            session.commit(callback.intentSender)
        }
    }

    private fun openPage(
        ctx: Context,
        url: String,
    ) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
