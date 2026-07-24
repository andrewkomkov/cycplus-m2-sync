package dev.komkov.m2sync

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий журнал: всё пишется и в logcat (тег M2SYNC — под `adb logcat -s M2SYNC`),
 * и в буфер для экрана приложения. Сообщения берутся из ресурсов, поэтому
 * журнал говорит на языке системы.
 */
object LogBus {
    const val TAG = "M2SYNC"
    private const val MAX_LINES = 500

    val lines = MutableStateFlow<List<String>>(emptyList())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun string(@StringRes id: Int, vararg args: Any?): String =
        appContext?.getString(id, *args) ?: args.joinToString(" ")

    fun i(@StringRes id: Int, vararg args: Any?) = add(string(id, *args), false)

    fun i(msg: String) = add(msg, false)

    fun e(@StringRes id: Int, vararg args: Any?) = add(string(id, *args), true)

    fun e(msg: String, t: Throwable? = null) {
        add(if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}", true)
    }

    fun e(@StringRes id: Int, t: Throwable?, vararg args: Any?) {
        val base = string(id, *args)
        add(if (t == null) base else "$base: ${t.javaClass.simpleName}: ${t.message}", true)
    }

    private fun add(msg: String, isError: Boolean) {
        if (isError) Log.e(TAG, msg) else Log.i(TAG, msg)
        val line = "${clock.format(Date())} $msg"
        lines.value = (lines.value + line).takeLast(MAX_LINES)
    }
}
