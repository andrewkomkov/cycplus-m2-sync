package dev.komkov.m2sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Пользовательские тумблеры. Хранятся в prefs, читаются экраном как потоки. */
object Settings {
    private const val PREFS = "m2sync_settings"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_BIRTH_YEAR = "birth_year"
    private const val KEY_SEX = "sex"
    private const val KEY_MAP_TILES = "map_tiles"
    private const val KEY_MAP_LAYER = "map_layer"

    /** Синхронизировать сразу при открытии приложения. */
    val autoSync = MutableStateFlow(true)

    /** Проверять релизы на GitHub. */
    val autoUpdate = MutableStateFlow(true)

    /**
     * Возраст и пол для расчёта калорий. В Health Connect их нет — это не типы
     * записей, а профиль Google, — поэтому спрашиваем сами. Вес берём из
     * Health Connect, его пользователь уже где-то ведёт.
     */
    val birthYear = MutableStateFlow<Int?>(null)
    val sex = MutableStateFlow<Calories.Sex?>(null)

    /**
     * Чем застилать землю под треком. Единственное место, где заезд
     * соприкасается с сетью, поэтому выключить можно совсем: тогда карта и
     * полёт остаются полностью офлайновыми, только трек на сетке.
     */
    val mapLayer = MutableStateFlow(MapLayer.MAP)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        autoSync.value = p.getBoolean(KEY_AUTO_SYNC, true)
        autoUpdate.value = p.getBoolean(KEY_AUTO_UPDATE, true)
        // До появления спутника подложка была простым тумблером — переносим
        // старое значение, чтобы выключенная карта не включилась сама.
        val legacy = if (p.getBoolean(KEY_MAP_TILES, true)) MapLayer.MAP else MapLayer.NONE
        mapLayer.value = p.getString(KEY_MAP_LAYER, null)
            ?.let { name -> MapLayer.entries.firstOrNull { it.name == name } }
            ?: legacy
        birthYear.value = p.getInt(KEY_BIRTH_YEAR, 0).takeIf { it > 0 }
        sex.value = p.getString(KEY_SEX, null)?.let { runCatching { Calories.Sex.valueOf(it) }.getOrNull() }
    }

    /** Ручной ввод — запасной путь, когда в медкарте Health Connect пусто. */
    fun profile(): Calories.Profile = Calories.Profile(birthYear.value, sex.value)

    fun setProfile(ctx: Context, year: Int?, value: Calories.Sex?) {
        birthYear.value = year
        sex.value = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BIRTH_YEAR, year ?: 0)
            .putString(KEY_SEX, value?.name)
            .apply()
    }

    fun setAutoSync(ctx: Context, enabled: Boolean) {
        autoSync.value = enabled
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun setMapLayer(ctx: Context, layer: MapLayer) {
        mapLayer.value = layer
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAP_LAYER, layer.name).apply()
    }

    fun setAutoUpdate(ctx: Context, enabled: Boolean) {
        autoUpdate.value = enabled
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled).apply()
        if (!enabled) AppState.update.value = null
    }
}
