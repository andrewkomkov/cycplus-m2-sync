package dev.komkov.m2sync

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Instant
import java.util.TimeZone

/**
 * Отдача .fit наружу.
 *
 * Два общих на всю JVM состояния приходится возвращать на место руками. Первое —
 * часовой пояс: имя копии считается по местному времени, и без фиксации тест
 * читался бы по-разному в Москве и в Лондоне. Второе — статический кэш
 * [FileProvider]: корни он запоминает по имени authority один раз на процесс, а
 * Robolectric каждому тесту выдаёт свежий каталог данных, и второй тест подряд
 * получал бы «Failed to find configured root».
 */
@RunWith(RobolectricTestRunner::class)
class SharingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val shareDir: File get() = File(app.cacheDir, "share")

    private var zone: TimeZone? = null

    @Before
    fun setUp() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        forgetFileProviderRoots()
        SyncService.fitDir(app).listFiles()?.forEach { it.delete() }
        shareDir.deleteRecursively()
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        shadowOf(app).clearNextStartedActivities()
        shareDir.deleteRecursively()
        SyncService.fitDir(app).listFiles()?.forEach { it.delete() }
        forgetFileProviderRoots()
        zone?.let { TimeZone.setDefault(it) }
        zone = null
    }

    /** Тот самый статический кэш корней: чужой каталог данных нам не подходит. */
    private fun forgetFileProviderRoots() {
        runCatching {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as MutableMap<*, *>).clear()
        }
    }

    private fun ride(
        file: String = "20260724103005.fit",
        distanceM: Double = 40_990.0,
    ) = RideSummary(
        file = file,
        start = Instant.parse("2026-07-24T10:30:05Z"),
        distanceM = distanceM,
        elapsedMin = 120,
        movingMin = 100,
        avgHeartRate = null,
        avgCadence = null,
        ascent = null,
        points = 1,
        hasRoute = false,
        imported = false,
    )

    private fun put(
        name: String,
        body: String = "FIT",
    ) = File(SyncService.fitDir(app), name).apply { writeText(body) }

    /** Chooser заворачивает наш Intent внутрь себя — достаём его обратно. */
    private fun sent(): Intent {
        val started = shadowOf(app).nextStartedActivity!!
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        return started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    }

    // --- имя читаемой копии ---

    @Test
    fun `the readable name carries the date and the distance`() {
        assertEquals("2026-07-24_10-30_40.99km_cycplus-m2.fit", Sharing.prettyName(ride()))
    }

    @Test
    fun `the distance in the name always keeps two digits`() {
        assertEquals("2026-07-24_10-30_7.35km_cycplus-m2.fit", Sharing.prettyName(ride(distanceM = 7350.0)))
        assertEquals("2026-07-24_10-30_0.00km_cycplus-m2.fit", Sharing.prettyName(ride(distanceM = 0.0)))
        assertEquals("2026-07-24_10-30_100.00km_cycplus-m2.fit", Sharing.prettyName(ride(distanceM = 100_000.0)))
    }

    // --- один заезд ---

    @Test
    fun `sharing a ride hands the file to the system chooser`() {
        put("20260724103005.fit")

        Sharing.shareRide(app, ride())

        val send = sent()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/vnd.ant.fit", send.type)
        assertEquals(Sharing.prettyName(ride()), send.getStringExtra(Intent.EXTRA_SUBJECT))
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertTrue(uri.toString().endsWith("2026-07-24_10-30_40.99km_cycplus-m2.fit"))
    }

    @Test
    fun `the shared copy holds the ride itself`() {
        put("20260724103005.fit", body = "содержимое заезда")

        Sharing.shareRide(app, ride())

        val copy = File(shareDir, Sharing.prettyName(ride()))
        assertTrue(copy.exists())
        assertEquals("содержимое заезда", copy.readText())
    }

    /** Файл могли удалить из папки заездов — тогда делиться нечем и chooser не нужен. */
    @Test
    fun `a ride with no file behind it is not shared`() {
        Sharing.shareRide(app, ride())

        assertNull(shadowOf(app).nextStartedActivity)
    }

    /** Копия уже лежит и совпадает по размеру — второй раз её не переписываем. */
    @Test
    fun `an existing copy of the same size is reused`() {
        put("20260724103005.fit", body = "первое")
        Sharing.shareRide(app, ride())

        val copy = File(shareDir, Sharing.prettyName(ride()))
        copy.writeText("второе")
        Sharing.shareRide(app, ride())

        assertEquals("второе", copy.readText())
    }

    /** А вот другой размер — это уже другой заезд, копию надо обновить. */
    @Test
    fun `a copy of a different size is written again`() {
        put("20260724103005.fit", body = "первое")
        Sharing.shareRide(app, ride())

        val copy = File(shareDir, Sharing.prettyName(ride()))
        copy.writeText("совсем другое")
        Sharing.shareRide(app, ride())

        assertEquals("первое", copy.readText())
    }

    // --- пачка заездов ---

    @Test
    fun `sharing everything sends every file at once`() {
        put("20260724103005.fit")
        put("20260725103005.fit")

        Sharing.shareAll(
            app,
            listOf(ride(), ride(file = "20260725103005.fit", distanceM = 7350.0)),
        )

        val send = sent()
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/vnd.ant.fit", send.type)
        val uris = send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(2, uris.size)
    }

    @Test
    fun `sharing everything quietly skips what is missing`() {
        put("20260724103005.fit")

        Sharing.shareAll(app, listOf(ride(), ride(file = "gone.fit")))

        val uris = sent().getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(1, uris.size)
    }

    @Test
    fun `sharing nothing opens nothing`() {
        Sharing.shareAll(app, listOf(ride(), ride(file = "gone.fit")))

        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `an empty selection opens nothing either`() {
        Sharing.shareAll(app, emptyList())

        assertNull(shadowOf(app).nextStartedActivity)
    }
}
