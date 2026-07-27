package dev.komkov.m2sync

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Общее состояние: снимок устройства, вес и список заездов переживают
 * перезапуск, а битые prefs не роняют приложение.
 */
@RunWith(RobolectricTestRunner::class)
class AppStateTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    /** Имя хранилища и ключи из [AppState] — их видно только отсюда, снаружи они приватные. */
    private val prefs get() = ctx.getSharedPreferences("m2sync_state", android.content.Context.MODE_PRIVATE)

    private fun snapshot(
        firmware: String? = "V1.4.0",
        battery: Int? = 87,
        freeKb: Int? = 708,
        totalKb: Int? = 16_384,
    ) = DeviceSnapshot(
        name = "Cycplus M2",
        address = "E3:E8:F7:E3:09:44",
        firmware = firmware,
        battery = battery,
        freeKb = freeKb,
        totalKb = totalKb,
        seenAt = Instant.ofEpochMilli(1_784_000_000_000),
    )

    private fun ride(
        file: String = "20260725102049.fit",
        start: Instant = Instant.ofEpochMilli(1_784_000_000_000),
        avgHeartRate: Int? = 128,
        avgCadence: Int? = 52,
        ascent: Int? = 13,
        kcal: Int? = 232,
        kcalKey: String? = "72.8/1992/MALE",
    ) = RideSummary(
        file = file,
        start = start,
        distanceM = 7350.5,
        elapsedMin = 65,
        movingMin = 33,
        avgHeartRate = avgHeartRate,
        avgCadence = avgCadence,
        ascent = ascent,
        points = 2023,
        hasRoute = true,
        imported = true,
        kcal = kcal,
        kcalKey = kcalKey,
    )

    /** Состояние — синглтон и живёт дольше теста, поэтому обнуляем его руками. */
    @Before
    fun reset() {
        prefs.edit().clear().commit()
        AppState.device.value = null
        AppState.weight.value = null
        AppState.rides.value = emptyList()
        AppState.update.value = null
        AppState.updateProgress.value = null
        AppState.transfer.value = null
        AppState.busy.value = false
        AppState.action.value = null
    }

    // --- снимок устройства ---

    @Test
    fun `used memory is what the device does not report as free`() {
        assertEquals(15_676, snapshot().usedKb)
        assertNull(snapshot(freeKb = null).usedKb)
        assertNull(snapshot(totalKb = null).usedKb)
    }

    @Test
    fun `device snapshot survives a round trip through json`() {
        val original = snapshot()
        assertEquals(original, DeviceSnapshot.fromJson(JSONObject(original.toJson().toString())))
    }

    /** Прошивку и заряд читают не всегда — пустые поля обязаны остаться пустыми. */
    @Test
    fun `device snapshot keeps unknown fields empty through json`() {
        val original = snapshot(firmware = null, battery = null, freeKb = null, totalKb = null)
        val back = DeviceSnapshot.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, back)
        assertNull(back.firmware)
        assertNull(back.battery)
        assertNull(back.usedKb)
    }

    /** Старые записи хранили отсутствие прошивки строкой «null» — за версию она сойти не должна. */
    @Test
    fun `a firmware spelled null is no firmware at all`() {
        val o = snapshot().toJson().put("firmware", "null")
        assertNull(DeviceSnapshot.fromJson(o).firmware)
    }

    @Test
    fun `a snapshot without a timestamp falls back to the epoch`() {
        val o = snapshot().toJson()
        o.remove("seenAt")
        assertEquals(Instant.EPOCH, DeviceSnapshot.fromJson(o).seenAt)
    }

    // --- заезд ---

    @Test
    fun `ride summary survives a round trip through json`() {
        val original = ride()
        val back = RideSummary.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, back)
        assertEquals(7350.5, back.distanceM, 1e-9)
        assertTrue(back.hasRoute)
        assertTrue(back.imported)
    }

    @Test
    fun `ride summary keeps unknown metrics empty through json`() {
        val original = ride(avgHeartRate = null, avgCadence = null, ascent = null, kcal = null, kcalKey = null)
        val back = RideSummary.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, back)
        assertNull(back.avgHeartRate)
        assertNull(back.kcal)
        assertNull(back.kcalKey)
    }

    /** Ключ расчёта — обычная строка, и через json она обязана дойти как есть. */
    @Test
    fun `the calorie key comes back as the same string`() {
        val back = RideSummary.fromJson(ride(kcalKey = "72.8/1992/FEMALE").toJson())
        assertEquals("72.8/1992/FEMALE", back.kcalKey)
        assertEquals(232, back.kcal)
    }

    // --- prefs ---

    @Test
    fun `a saved device comes back after a restart`() {
        val original = snapshot()
        AppState.saveDevice(ctx, original)
        assertEquals(original, AppState.device.value)

        AppState.device.value = null
        AppState.load(ctx)
        assertEquals(original, AppState.device.value)
    }

    @Test
    fun `a saved weight comes back after a restart`() {
        val reading = WeightReading(72.8, Instant.ofEpochMilli(1_784_000_000_000))
        AppState.saveWeight(ctx, reading)
        assertEquals(reading, AppState.weight.value)

        AppState.weight.value = null
        AppState.load(ctx)
        assertEquals(72.8, AppState.weight.value!!.kilograms, 1e-9)
        assertEquals(reading.at, AppState.weight.value!!.at)
    }

    /** Сброс веса должен стирать и запись в prefs, иначе он вернётся при следующем запуске. */
    @Test
    fun `clearing the weight wipes it from prefs`() {
        AppState.saveWeight(ctx, WeightReading(72.8, Instant.ofEpochMilli(1_784_000_000_000)))
        AppState.saveWeight(ctx, null)
        assertNull(AppState.weight.value)

        AppState.load(ctx)
        assertNull(AppState.weight.value)
    }

    @Test
    fun `saved rides come back after a restart in the same order`() {
        val list =
            listOf(
                ride(file = "b.fit", start = Instant.ofEpochMilli(2_000_000_000_000)),
                ride(file = "a.fit", start = Instant.ofEpochMilli(1_000_000_000_000)),
            )
        AppState.saveRides(ctx, list)
        assertEquals(list, AppState.rides.value)

        AppState.rides.value = emptyList()
        AppState.load(ctx)
        assertEquals(list, AppState.rides.value)
        assertEquals(listOf("b.fit", "a.fit"), AppState.rides.value.map { it.file })
    }

    @Test
    fun `saving an empty list of rides leaves nothing behind`() {
        AppState.saveRides(ctx, listOf(ride()))
        AppState.saveRides(ctx, emptyList())

        AppState.rides.value = listOf(ride())
        AppState.load(ctx)
        assertTrue(AppState.rides.value.isEmpty())
    }

    /** Prefs правит только приложение, но пережить чужую правку оно обязано без падения. */
    @Test
    fun `broken prefs are ignored instead of crashing`() {
        prefs
            .edit()
            .putString("device", "не json")
            .putString("weight", "{")
            .putString("rides", "[{\"file\":\"a.fit\"}]")
            .commit()

        AppState.load(ctx)

        assertNull(AppState.device.value)
        assertNull(AppState.weight.value)
        assertTrue(AppState.rides.value.isEmpty())
    }

    /** Один битый заезд в списке губит весь список — но не приложение. */
    @Test
    fun `a half readable list of rides is dropped whole`() {
        val arr =
            JSONArray().apply {
                put(ride(file = "good.fit").toJson())
                put(JSONObject().put("file", "bad.fit"))
            }
        prefs.edit().putString("rides", arr.toString()).commit()

        AppState.load(ctx)
        assertTrue(AppState.rides.value.isEmpty())
    }

    @Test
    fun `a first run finds nothing and keeps the defaults`() {
        AppState.load(ctx)

        assertNull(AppState.device.value)
        assertNull(AppState.weight.value)
        assertTrue(AppState.rides.value.isEmpty())
        assertFalse(AppState.busy.value)
        assertNull(AppState.action.value)
        assertNull(AppState.transfer.value)
    }

    /** Прогресс скачивания в prefs не живёт: он про текущую сессию и только про неё. */
    @Test
    fun `transfer progress is in memory only`() {
        AppState.transfer.value = Triple("20260725102049.fit", 512, 2048)
        AppState.busy.value = true
        AppState.action.value = "sync"

        AppState.load(ctx)

        assertNotNull(AppState.transfer.value)
        assertEquals("20260725102049.fit", AppState.transfer.value!!.first)
        assertTrue(AppState.busy.value)
        assertEquals("sync", AppState.action.value)
    }
}
