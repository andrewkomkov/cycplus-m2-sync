package dev.komkov.m2sync

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI

/**
 * Проверка релизов на GitHub.
 *
 * Ходить в сеть из теста нельзя, поэтому на время теста весь исходящий трафик
 * заворачивается в заведомо закрытый порт: запрос падает сразу и одинаково на
 * любой машине, а проверяем мы ровно то, что вокруг него.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    private val prefs get() = ctx.getSharedPreferences("m2sync_updates", android.content.Context.MODE_PRIVATE)

    private var saved: ProxySelector? = null
    private var deadPort = 0

    @Before
    fun cutTheWire() {
        deadPort = ServerSocket(0).use { it.localPort }
        saved = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", deadPort)))

                override fun connectFailed(
                    uri: URI,
                    sa: SocketAddress,
                    ioe: IOException,
                ) = Unit
            },
        )
        prefs.edit().clear().commit()
        LogBus.init(ctx)
        LogBus.lines.value = emptyList()
        AppState.update.value = null
        Settings.autoUpdate.value = true
    }

    @After
    fun reconnectTheWire() {
        ProxySelector.setDefault(saved)
        Settings.autoUpdate.value = true
    }

    private fun logged(id: Int): Boolean = LogBus.lines.value.any { it.contains(ctx.getString(id)) }

    // --- сравнение версий ---

    @Test
    fun `versions are compared by numbers not by letters`() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.3") > 0)
        assertTrue(UpdateChecker.compareVersions("1.9.3", "1.10.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun `a missing part counts as zero`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.1", "1.2") > 0)
        assertTrue(UpdateChecker.compareVersions("1", "1.0.1") < 0)
    }

    /** Отладочная сборка носит суффикс, и он не должен путать сравнение. */
    @Test
    fun `a suffix after the number is not part of it`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2.3-debug", "1.2.3"))
        assertTrue(UpdateChecker.compareVersions("1.3.0-rc1", "1.2.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("what", "0.0.0"))
    }

    // --- файлы релиза ---

    private fun asset(
        name: String,
        url: String?,
    ): JSONObject =
        JSONObject().apply {
            put("name", name)
            url?.let { put("browser_download_url", it) }
        }

    /** Файл без ссылки скачать нельзя — он для нас всё равно что отсутствует. */
    @Test
    fun `an asset without a link is skipped`() {
        val list =
            JSONArray().apply {
                put(asset("m2sync.apk", null))
                put(asset("m2sync.apk.sha256", ""))
            }
        assertEquals(null to null, UpdateChecker.assets(list))
    }

    @Test
    fun `the first package in the release wins`() {
        val list =
            JSONArray().apply {
                put(asset("first.apk", "https://example.invalid/first.apk"))
                put(asset("second.apk", "https://example.invalid/second.apk"))
                put(asset("first.apk.sha256", "https://example.invalid/first.apk.sha256"))
                put(asset("second.apk.sha256", "https://example.invalid/second.apk.sha256"))
            }
        assertEquals(
            "https://example.invalid/first.apk" to "https://example.invalid/first.apk.sha256",
            UpdateChecker.assets(list),
        )
    }

    /** В списке может оказаться что угодно, вплоть до не-объекта. */
    @Test
    fun `junk in the list of files does not stop the search`() {
        val list =
            JSONArray().apply {
                put("строка вместо файла")
                put(asset("m2sync.apk", "https://example.invalid/m2sync.apk"))
            }
        assertEquals("https://example.invalid/m2sync.apk", UpdateChecker.assets(list).first)
    }

    // --- тихая проверка ---

    @Test
    fun `a switched off check does not even remember the attempt`() =
        runBlocking {
            Settings.autoUpdate.value = false

            UpdateChecker.checkIfDue(ctx)

            assertEquals(0L, prefs.getLong("last_check", 0))
            assertFalse(logged(R.string.log_update_failed))
        }

    /** Не чаще раза в двенадцать часов: свежая отметка обязана остановить проверку. */
    @Test
    fun `a check made an hour ago is not repeated`() =
        runBlocking {
            val hourAgo = System.currentTimeMillis() - 60 * 60 * 1000L
            prefs.edit().putLong("last_check", hourAgo).commit()

            UpdateChecker.checkIfDue(ctx)

            assertEquals(hourAgo, prefs.getLong("last_check", 0))
        }

    @Test
    fun `a check from a day ago runs again and is written down`() =
        runBlocking {
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            prefs.edit().putLong("last_check", dayAgo).commit()

            UpdateChecker.checkIfDue(ctx)

            assertTrue(prefs.getLong("last_check", 0) > dayAgo)
            assertNull(AppState.update.value)
        }

    /** Тихая проверка на то и тихая: сорвалась — в журнале пусто. */
    @Test
    fun `a silent check stays silent when the network is down`() =
        runBlocking {
            UpdateChecker.checkIfDue(ctx)

            assertFalse(logged(R.string.log_update_failed))
        }

    // --- проверка по кнопке ---

    @Test
    fun `a check by hand says out loud that it failed`() =
        runBlocking {
            assertNull(UpdateChecker.check(ctx))

            assertTrue(logged(R.string.log_update_failed))
            assertNull(AppState.update.value)
        }

    /** Найденное раньше обновление сорвавшаяся проверка не отменяет. */
    @Test
    fun `a failed check leaves the previous offer alone`() =
        runBlocking {
            val found = UpdateChecker.Update("9.9.9", null, "https://example.invalid", null)
            AppState.update.value = found

            UpdateChecker.check(ctx)

            assertEquals(found, AppState.update.value)
        }

    // --- описание релиза ---

    @Test
    fun `an update knows where to download from and where to read about it`() {
        val update =
            UpdateChecker.Update(
                version = "1.2.3",
                apkUrl = "https://example.invalid/m2sync.apk",
                pageUrl = UpdateChecker.RELEASES_URL,
                notes = "исправлено всё",
                sha256Url = "https://example.invalid/m2sync.apk.sha256",
            )

        assertEquals("1.2.3", update.version)
        assertTrue(update.pageUrl.contains(UpdateChecker.REPO))
        assertEquals("исправлено всё", update.notes)
        assertEquals(update, update.copy())
    }
}
