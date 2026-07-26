package dev.komkov.m2sync

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * Чтение заезда с диска. Живёт отдельно от [RideTrackTest]: разбору нужен
 * контекст ради каталога с .fit, а значит и Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class RideTrackLoadTest {

    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    private fun summary(file: String) = RideSummary(
        file = file,
        start = Instant.parse("2026-07-25T10:20:49Z"),
        distanceM = 0.0,
        elapsedMin = 0,
        movingMin = 0,
        avgHeartRate = null,
        avgCadence = null,
        ascent = null,
        points = 0,
        hasRoute = false,
        imported = false,
    )

    /** Файл могли удалить снаружи: экран заезда должен получить null, а не зависнуть. */
    @Test
    fun `a missing file yields no track`() = runBlocking {
        assertNull(RideTrack.load(ctx, summary("nope.fit")))
    }

    /** Битый .fit — то же самое: разбор падает, но приложение продолжает жить. */
    @Test
    fun `a corrupt file yields no track`() = runBlocking {
        File(SyncService.fitDir(ctx), "junk.fit").writeBytes(ByteArray(64) { it.toByte() })
        assertNull(RideTrack.load(ctx, summary("junk.fit")))
    }
}
