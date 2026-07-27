package dev.komkov.m2sync

import androidx.test.core.app.ApplicationProvider
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import com.garmin.fit.File as FitFileType

/**
 * Список заездов для экрана. Файлы кладём настоящие: собираем .fit тем же
 * форматом, что пишет велокомпьютер, и проверяем, что из него выходит на экран.
 */
@RunWith(RobolectricTestRunner::class)
class RideStoreTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    private val dir: File get() = SyncService.fitDir(ctx)

    private val begin: Instant = Instant.parse("2026-07-25T10:20:49Z")

    /** Отпечаток расчёта калорий, когда ни веса, ни профиля ещё нет. */
    private val noProfileKey: String = Calories.profileKey(null, Calories.Profile.EMPTY)

    /** Каталог общий на весь прогон, а каждый тест хочет видеть только своё. */
    @Before
    fun clean() {
        dir.listFiles()?.forEach { it.delete() }
        AppState.rides.value = emptyList()
    }

    /**
     * Прямой трек на север с постоянной скоростью: столько же сообщений, сколько
     * пишет M2 — одна сессия и точки раз в секунду.
     */
    private fun writeRide(
        name: String,
        start: Instant = begin,
        count: Int = 120,
        heartRate: Short? = 128,
        cadence: Short? = 52,
    ): File {
        val file = File(dir, name)
        val encoder = FileEncoder(file, com.garmin.fit.Fit.ProtocolVersion.V2_0)

        encoder.write(
            FileIdMesg().apply {
                type = FitFileType.ACTIVITY
                manufacturer = 1
                product = 1
                serialNumber = 1L
                timeCreated = DateTime(start)
            },
        )

        for (i in 0 until count) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = DateTime(start.plusSeconds(i.toLong()))
                    positionLat = ((60.0 + i * 0.0001) / SEMICIRCLE).toInt()
                    positionLong = (30.0 / SEMICIRCLE).toInt()
                    altitude = (100.0 + i * 0.1).toFloat()
                    speed = 8.0f
                    distance = i * 8.0f
                    heartRate?.let { this.heartRate = it }
                    cadence?.let { this.cadence = it }
                },
            )
        }

        encoder.write(
            SessionMesg().apply {
                timestamp = DateTime(start.plusSeconds(count.toLong()))
                startTime = DateTime(start)
                sport = Sport.CYCLING
                totalDistance = (count - 1) * 8.0f
                totalTimerTime = 100f
                totalElapsedTime = (count - 1).toFloat()
                totalAscent = ASCENT
                heartRate?.let { avgHeartRate = it }
            },
        )
        encoder.close()
        return file
    }

    private fun summary(
        file: String,
        distanceM: Double,
        kcalKey: String?,
        imported: Boolean = false,
    ) = RideSummary(
        file = file,
        start = begin,
        distanceM = distanceM,
        elapsedMin = 1,
        movingMin = 2,
        avgHeartRate = null,
        avgCadence = null,
        ascent = null,
        points = 0,
        hasRoute = false,
        imported = imported,
        kcal = null,
        kcalKey = kcalKey,
    )

    // --- пустой каталог ---

    @Test
    fun `nothing on disk means nothing on the screen`() {
        val list = RideStore.refresh(ctx, emptySet())

        assertTrue(list.isEmpty())
        assertTrue(AppState.rides.value.isEmpty())
    }

    @Test
    fun `files that are not rides are left alone`() {
        File(dir, "notes.txt").writeText("не заезд")
        File(dir, "20260725102049.fit.part").writeText("недокачано")

        assertTrue(RideStore.refresh(ctx, emptySet()).isEmpty())
    }

    // --- разбор ---

    @Test
    fun `a fit file turns into a card for the screen`() {
        writeRide("20260725102049.fit")

        val ride = RideStore.refresh(ctx, emptySet()).single()

        assertEquals("20260725102049.fit", ride.file)
        assertEquals(begin, ride.start)
        assertEquals(952.0, ride.distanceM, 1.0)
        assertEquals(1, ride.elapsedMin)
        assertEquals(2, ride.movingMin)
        assertEquals(128, ride.avgHeartRate)
        assertEquals(52, ride.avgCadence)
        assertEquals(ASCENT, ride.ascent)
        assertEquals(120, ride.points)
        assertTrue(ride.hasRoute)
        assertFalse(ride.imported)
    }

    /** Без пульса и каденса поля обязаны остаться пустыми, а не превратиться в нули. */
    @Test
    fun `a ride without heart rate and cadence keeps those fields empty`() {
        writeRide("20260725102049.fit", heartRate = null, cadence = null)

        val ride = RideStore.refresh(ctx, emptySet()).single()

        assertNull(ride.avgCadence)
        assertNull(ride.avgHeartRate)
    }

    @Test
    fun `a ride already in health connect is marked as such`() {
        writeRide("20260725102049.fit")

        val ride = RideStore.refresh(ctx, setOf("20260725102049.fit")).single()
        assertTrue(ride.imported)
    }

    /** Разбор одного файла не должен уносить с собой весь список. */
    @Test
    fun `a broken file drops out and the rest stays`() {
        writeRide("20260725102049.fit")
        File(dir, "20260726090000.fit").writeBytes(ByteArray(64) { it.toByte() })

        val list = RideStore.refresh(ctx, emptySet())

        assertEquals(listOf("20260725102049.fit"), list.map { it.file })
    }

    @Test
    fun `the newest ride comes first`() {
        writeRide("20260724080000.fit", start = Instant.parse("2026-07-24T08:00:00Z"), count = 30)
        writeRide("20260726090000.fit", start = Instant.parse("2026-07-26T09:00:00Z"), count = 30)
        writeRide("20260725102049.fit", count = 30)

        val list = RideStore.refresh(ctx, emptySet())

        assertEquals(
            listOf("20260726090000.fit", "20260725102049.fit", "20260724080000.fit"),
            list.map { it.file },
        )
    }

    @Test
    fun `what is returned is what the screen gets`() {
        writeRide("20260725102049.fit")

        val list = RideStore.refresh(ctx, emptySet())
        assertEquals(list, AppState.rides.value)
    }

    // --- калории ---

    @Test
    fun `calories stay unknown until the weight is`() {
        writeRide("20260725102049.fit")

        val ride = RideStore.refresh(ctx, emptySet()).single()

        assertNull(ride.kcal)
        assertEquals(noProfileKey, ride.kcalKey)
    }

    @Test
    fun `weight and profile turn into calories`() {
        writeRide("20260725102049.fit")
        val profile = Calories.Profile(1992, Calories.Sex.MALE)

        val ride = RideStore.refresh(ctx, emptySet(), weightKg = 72.8, profile = profile).single()

        assertNotNull(ride.kcal)
        assertTrue(ride.kcal!! > 0)
        assertEquals(Calories.profileKey(72.8, profile), ride.kcalKey)
    }

    // --- кэш ---

    /** Разбор .fit дорогой: уже посчитанный заезд второй раз не трогаем. */
    @Test
    fun `a ride counted before is taken from the cache`() {
        writeRide("20260725102049.fit")
        val cached = summary("20260725102049.fit", distanceM = 12_345.0, kcalKey = noProfileKey)
        AppState.rides.value = listOf(cached)

        val ride = RideStore.refresh(ctx, emptySet()).single()

        assertEquals(cached, ride)
        assertEquals(12_345.0, ride.distanceM, 1e-9)
    }

    /** Поменялся вес — прежнее число калорий недействительно, файл читаем заново. */
    @Test
    fun `a new weight throws the cached ride away`() {
        writeRide("20260725102049.fit")
        val cached = summary("20260725102049.fit", distanceM = 12_345.0, kcalKey = noProfileKey)
        AppState.rides.value = listOf(cached)

        val ride = RideStore.refresh(ctx, emptySet(), weightKg = 72.8).single()

        assertEquals(952.0, ride.distanceM, 1.0)
        assertEquals(Calories.profileKey(72.8, Calories.Profile.EMPTY), ride.kcalKey)
    }

    /** Заезд уехал в Health Connect — отметку надо обновить, а не показывать старую. */
    @Test
    fun `a ride that reached health connect is re read`() {
        writeRide("20260725102049.fit")
        AppState.rides.value = listOf(summary("20260725102049.fit", distanceM = 12_345.0, kcalKey = noProfileKey))

        val ride = RideStore.refresh(ctx, setOf("20260725102049.fit")).single()

        assertTrue(ride.imported)
        assertEquals(952.0, ride.distanceM, 1.0)
    }

    /** Кэш от исчезнувшего файла — мусор: списка он касаться не должен. */
    @Test
    fun `a cached ride whose file is gone disappears too`() {
        AppState.rides.value =
            listOf(
                summary("gone.fit", distanceM = 12_345.0, kcalKey = noProfileKey),
            )

        assertTrue(RideStore.refresh(ctx, emptySet()).isEmpty())
    }

    private companion object {
        /** Полуокружности FIT в градусы — и обратно. */
        const val SEMICIRCLE = 180.0 / 2147483648.0

        /** Набор высоты в собранном .fit: одинаковый для всех заездов теста. */
        const val ASCENT = 13
    }
}
