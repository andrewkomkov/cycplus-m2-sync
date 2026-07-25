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

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        autoSync.value = p.getBoolean(KEY_AUTO_SYNC, true)
        autoUpdate.value = p.getBoolean(KEY_AUTO_UPDATE, true)
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

    fun setAutoUpdate(ctx: Context, enabled: Boolean) {
        autoUpdate.value = enabled
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled).apply()
        if (!enabled) AppState.update.value = null
    }
}
