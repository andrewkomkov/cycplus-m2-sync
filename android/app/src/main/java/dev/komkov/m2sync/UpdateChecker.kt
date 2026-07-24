package dev.komkov.m2sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка новой версии по релизам GitHub.
 *
 * Единственный сетевой запрос во всём приложении: GET к api.github.com, без токенов
 * и без отправки чего-либо о пользователе. Отключается в меню.
 */
object UpdateChecker {

    const val REPO = "andrewkomkov/cycplus-m2-sync"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    private const val PREFS = "m2sync_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Update(val version: String, val apkUrl: String?, val pageUrl: String, val notes: String?)

    /** Тихая проверка при запуске: не чаще раза в 12 часов и только если включена. */
    suspend fun checkIfDue(ctx: Context) {
        if (!Settings.autoUpdate.value) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        check(ctx, verbose = false)
    }

    /** Возвращает обновление, если на GitHub версия новее установленной. */
    suspend fun check(ctx: Context, verbose: Boolean = true): Update? = withContext(Dispatchers.IO) {
        try {
            val json = fetch()
            val tag = json.getString("tag_name").removePrefix("v")
            val current = BuildConfig.VERSION_NAME
            if (compareVersions(tag, current) <= 0) {
                if (verbose) LogBus.i(R.string.log_update_none, current)
                AppState.update.value = null
                return@withContext null
            }
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apk = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            val update = Update(
                version = tag,
                apkUrl = apk,
                pageUrl = json.optString("html_url", RELEASES_URL),
                notes = json.optString("body").takeIf { it.isNotBlank() },
            )
            LogBus.i(R.string.log_update_found, tag)
            AppState.update.value = update
            update
        } catch (e: Exception) {
            if (verbose) LogBus.e(R.string.log_update_failed, e)
            null
        }
    }

    private fun fetch(): JSONObject {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "m2sync/${BuildConfig.VERSION_NAME}")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Сравнение вида 1.10.0 против 1.9.3 по числам, а не по строкам. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}
