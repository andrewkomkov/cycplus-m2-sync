package dev.komkov.m2sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Пользовательские тумблеры. Хранятся в prefs, читаются экраном как потоки. */
object Settings {
    private const val PREFS = "m2sync_settings"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_AUTO_UPDATE = "auto_update"

    /** Синхронизировать сразу при открытии приложения. */
    val autoSync = MutableStateFlow(true)

    /** Проверять релизы на GitHub. */
    val autoUpdate = MutableStateFlow(true)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        autoSync.value = p.getBoolean(KEY_AUTO_SYNC, true)
        autoUpdate.value = p.getBoolean(KEY_AUTO_UPDATE, true)
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
