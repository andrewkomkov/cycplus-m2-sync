package dev.komkov.m2sync

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тумблеры из меню: то, что записали, обязано пережить перезапуск, а старые
 * записи прежних версий — прочитаться без потери смысла.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    private val prefs get() = ctx.getSharedPreferences("m2sync_settings", android.content.Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
        AppState.update.value = null
        Settings.load(ctx)
    }

    // --- значения по умолчанию ---

    @Test
    fun `a first run syncs and looks for updates by itself`() {
        assertTrue(Settings.autoSync.value)
        assertTrue(Settings.autoUpdate.value)
        assertEquals(MapLayer.MAP, Settings.mapLayer.value)
        assertNull(Settings.birthYear.value)
        assertNull(Settings.sex.value)
    }

    // --- тумблеры ---

    @Test
    fun `auto sync comes back after a restart`() {
        Settings.setAutoSync(ctx, false)
        assertFalse(Settings.autoSync.value)

        Settings.autoSync.value = true
        Settings.load(ctx)
        assertFalse(Settings.autoSync.value)
    }

    @Test
    fun `auto update comes back after a restart`() {
        Settings.setAutoUpdate(ctx, false)
        Settings.autoUpdate.value = true
        Settings.load(ctx)
        assertFalse(Settings.autoUpdate.value)

        Settings.setAutoUpdate(ctx, true)
        Settings.load(ctx)
        assertTrue(Settings.autoUpdate.value)
    }

    /** Выключили проверку — найденное раньше обновление предлагать больше нечестно. */
    @Test
    fun `switching updates off takes the offer off the screen`() {
        AppState.update.value = UpdateChecker.Update("9.9.9", null, "https://example.invalid", null)

        Settings.setAutoUpdate(ctx, false)
        assertNull(AppState.update.value)
    }

    @Test
    fun `switching updates on leaves the found version alone`() {
        val found = UpdateChecker.Update("9.9.9", null, "https://example.invalid", null)
        AppState.update.value = found

        Settings.setAutoUpdate(ctx, true)
        assertEquals(found, AppState.update.value)
    }

    // --- подложка карты ---

    @Test
    fun `the map layer comes back after a restart`() {
        Settings.setMapLayer(ctx, MapLayer.SATELLITE)

        Settings.mapLayer.value = MapLayer.NONE
        Settings.load(ctx)
        assertEquals(MapLayer.SATELLITE, Settings.mapLayer.value)
    }

    /** До спутника подложка была тумблером: выключенная карта не должна включиться сама. */
    @Test
    fun `an old switched off map stays switched off`() {
        prefs.edit().putBoolean("map_tiles", false).commit()

        Settings.load(ctx)
        assertEquals(MapLayer.NONE, Settings.mapLayer.value)
    }

    @Test
    fun `an old switched on map becomes the map layer`() {
        prefs.edit().putBoolean("map_tiles", true).commit()

        Settings.load(ctx)
        assertEquals(MapLayer.MAP, Settings.mapLayer.value)
    }

    /** Новая запись главнее старой: её и писали последней. */
    @Test
    fun `a chosen layer wins over the old switch`() {
        prefs
            .edit()
            .putBoolean("map_tiles", false)
            .putString("map_layer", "SATELLITE")
            .commit()

        Settings.load(ctx)
        assertEquals(MapLayer.SATELLITE, Settings.mapLayer.value)
    }

    /** Слой мог исчезнуть при откате версии — тогда решает старый тумблер. */
    @Test
    fun `an unknown layer falls back to the old switch`() {
        prefs
            .edit()
            .putBoolean("map_tiles", false)
            .putString("map_layer", "STREET_VIEW")
            .commit()

        Settings.load(ctx)
        assertEquals(MapLayer.NONE, Settings.mapLayer.value)
    }

    // --- профиль ---

    @Test
    fun `the profile comes back after a restart`() {
        Settings.setProfile(ctx, 1992, Calories.Sex.FEMALE)
        assertEquals(Calories.Profile(1992, Calories.Sex.FEMALE), Settings.profile())

        Settings.birthYear.value = null
        Settings.sex.value = null
        Settings.load(ctx)
        assertEquals(1992, Settings.birthYear.value)
        assertEquals(Calories.Sex.FEMALE, Settings.sex.value)
        assertTrue(Settings.profile().usable)
    }

    /** Пустой профиль хранится нулём, а читается как «не задан», а не как нулевой год. */
    @Test
    fun `an erased profile reads back as unset`() {
        Settings.setProfile(ctx, 1992, Calories.Sex.MALE)
        Settings.setProfile(ctx, null, null)

        Settings.load(ctx)
        assertNull(Settings.birthYear.value)
        assertNull(Settings.sex.value)
        assertFalse(Settings.profile().usable)
        assertEquals(Calories.Profile.EMPTY, Settings.profile())
    }

    @Test
    fun `half a profile is still half a profile`() {
        Settings.setProfile(ctx, 1992, null)

        Settings.load(ctx)
        assertEquals(1992, Settings.birthYear.value)
        assertNull(Settings.sex.value)
        assertFalse(Settings.profile().usable)
    }

    /** Пол хранится именем константы — чужое имя не должно валить чтение настроек. */
    @Test
    fun `a nonsense sex in prefs is read as none`() {
        prefs.edit().putString("sex", "OTTER").commit()

        Settings.load(ctx)
        assertNull(Settings.sex.value)
    }
}
