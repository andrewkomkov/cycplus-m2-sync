package dev.komkov.m2sync

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import android.provider.Settings as AndroidSettings

/**
 * Установка обновления. Сеть настоящая, но своя: рядом поднимается http-сервер
 * на localhost и раздаёт «APK» и сумму к нему — так проходит весь путь от
 * скачивания до сверки, и ни один тест не зависит от GitHub.
 *
 * Разбор файлов релиза и сам подсчёт суммы живут в [UpdaterTest]; здесь — то,
 * что вокруг них.
 */
@RunWith(RobolectricTestRunner::class)
class UpdaterCoverageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer
    private var port = 0

    /** Больше буфера чтения в 64 КБ: скачивание должно пережить несколько заходов. */
    private val apk = ByteArray(300_000) { (it % 251).toByte() }

    private val apkSha: String
        get() =
            MessageDigest
                .getInstance("SHA-256")
                .digest(apk)
                .joinToString("") { "%02x".format(it) }

    private val updateDir: File get() = File(app.cacheDir, "update")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.apk") { serve(it, apk) }
        server.createContext("/right.sha256") { serve(it, "$apkSha  m2sync.apk\n".toByteArray()) }
        server.createContext("/wrong.sha256") { serve(it, ("0".repeat(64) + "  m2sync.apk").toByteArray()) }
        server.createContext("/garbage.sha256") { serve(it, "не сумма".toByteArray()) }
        server.createContext("/boom") {
            it.sendResponseHeaders(500, -1)
            it.close()
        }
        server.start()
        port = server.address.port

        LogBus.init(app)
        LogBus.lines.value = emptyList()
        AppState.updateProgress.value = null
        updateDir.deleteRecursively()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        server.stop(0)
        AppState.updateProgress.value = null
    }

    private fun serve(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun update(
        apkPath: String? = "/app.apk",
        shaPath: String? = "/right.sha256",
        pageUrl: String = "https://example.invalid/releases",
    ) = UpdateChecker.Update(
        version = "9.9.9",
        apkUrl = apkPath?.let { "http://127.0.0.1:$port$it" },
        pageUrl = pageUrl,
        notes = null,
        sha256Url = shaPath?.let { "http://127.0.0.1:$port$it" },
    )

    /**
     * Журнал складывается из ресурсов с подстановками, поэтому и здесь строку
     * собираем ресурсом: без аргументов в ней остались бы `%1$s`, и совпадения
     * не было бы никогда — ни в ту, ни в другую сторону.
     */
    private fun logged(
        id: Int,
        vararg args: Any,
    ): Boolean = LogBus.lines.value.any { it.contains(app.getString(id, *args)) }

    /** Версия из [update]: с ней склеивается строка о начале скачивания. */
    private fun loggedDownloadStart(): Boolean = logged(R.string.log_update_downloading, "9.9.9")

    /**
     * Установка идёт своим потоком. Кончиться она может тремя способами: сумма
     * не сошлась, что-то упало, либо дело дошло до системного установщика —
     * ждём любого из трёх.
     */
    private fun awaitDone() {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val reached =
                logged(R.string.log_update_checksum_failed) ||
                    logged(R.string.log_update_download_failed) ||
                    AppState.updateProgress.value?.stage == UpdateProgress.Stage.INSTALL
            if (reached) return
            Thread.sleep(20)
        }
        throw AssertionError("обновление не дошло до конца: ${LogBus.lines.value}")
    }

    private fun downloaded(): File? = updateDir.listFiles()?.firstOrNull()

    // --- до скачивания дело не доходит ---

    /** Релиз без собранного APK: показать страницу — всё, что мы можем предложить. */
    @Test
    fun `a release without a package only opens its page`() {
        Updater.start(app, update(apkPath = null))

        val opened = shadowOf(app).nextStartedActivity!!
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.invalid/releases", opened.data.toString())
        assertNull(AppState.updateProgress.value)
    }

    /** Разрешение «ставить неизвестные приложения» даётся только руками в настройках. */
    @Test
    fun `without permission the user is sent to the system settings`() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        Updater.start(app, update())

        val opened = shadowOf(app).nextStartedActivity!!
        assertEquals(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals("package:${app.packageName}", opened.data.toString())
        assertTrue(logged(R.string.log_update_needs_permission))
        assertNull(AppState.updateProgress.value)
    }

    /** Пока одна установка идёт, вторую по той же кнопке начинать нельзя. */
    @Test
    fun `an install already running is not started a second time`() {
        AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 10, 100)

        Updater.start(app, update())

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(10L, AppState.updateProgress.value!!.bytes)
        assertFalse(loggedDownloadStart())
    }

    // --- скачивание ---

    @Test
    fun `the package is downloaded whole and its sum checked`() {
        Updater.start(app, update())
        awaitDone()

        assertTrue(loggedDownloadStart())
        assertTrue(logged(R.string.log_update_verified))
        val file = downloaded()!!
        assertEquals("m2sync-9.9.9.apk", file.name)
        assertEquals(apk.size.toLong(), file.length())
        assertEquals(apkSha, Updater.sha256(file))
    }

    /** Сумма не сошлась — ставить нечего, и скачанное надо убрать за собой. */
    @Test
    fun `a package that does not match the published sum is thrown away`() {
        Updater.start(app, update(shaPath = "/wrong.sha256"))
        awaitDone()

        assertTrue(logged(R.string.log_update_checksum_failed))
        assertFalse(logged(R.string.log_update_verified))
        assertNull(downloaded())
    }

    /** Старые релизы суммы не публиковали — это не повод отказывать в установке. */
    @Test
    fun `a release without a published sum is installed anyway`() {
        Updater.start(app, update(shaPath = null))
        awaitDone()

        assertFalse(logged(R.string.log_update_checksum_failed))
        assertNotNull(downloaded())
    }

    /** Сумму не отдали — считаем, что её просто нет, а не что файл плохой. */
    @Test
    fun `an unreachable sum does not block the install`() {
        Updater.start(app, update(shaPath = "/boom"))
        awaitDone()

        assertFalse(logged(R.string.log_update_checksum_failed))
        assertNotNull(downloaded())
    }

    /** В файле суммы оказалось не 64 шестнадцатеричных знака — сверять не с чем. */
    @Test
    fun `a sum that is not a sum is ignored`() {
        Updater.start(app, update(shaPath = "/garbage.sha256"))
        awaitDone()

        assertFalse(logged(R.string.log_update_checksum_failed))
        assertNotNull(downloaded())
    }

    /** Прошлые попытки не копим: APK крупный, а нужен ровно один. */
    @Test
    fun `a previous attempt is wiped before the new one`() {
        updateDir.mkdirs()
        File(updateDir, "m2sync-0.0.1.apk").writeBytes(ByteArray(1024))

        Updater.start(app, update())
        awaitDone()

        assertEquals(listOf("m2sync-9.9.9.apk"), updateDir.listFiles()!!.map { it.name })
    }

    /** Сеть оборвалась — карточка обязана вернуться в исходное состояние. */
    @Test
    fun `a download that cannot start leaves nothing hanging`() {
        server.stop(0)

        Updater.start(app, update())
        awaitDone()

        assertTrue(logged(R.string.log_update_download_failed))
        // Полосу гасят следующей строкой после записи в журнал — даём ей дойти.
        val deadline = System.currentTimeMillis() + 5_000
        while (AppState.updateProgress.value != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertNull(AppState.updateProgress.value)
    }
}
