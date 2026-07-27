package dev.komkov.m2sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Журнал — единственное, что видит пользователь о работе сервиса, поэтому
 * проверяем и формат строки, и то, что буфер не растёт бесконечно.
 *
 * Robolectric нужен из-за android.util.Log и ресурсов: без них LogBus не живёт.
 */
@RunWith(RobolectricTestRunner::class)
class LogBusTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** «12:34:56 » в начале строки. */
    private val stamped = Regex("^\\d{2}:\\d{2}:\\d{2} ")

    private fun last(): String = LogBus.lines.value.last()

    /** Строка без отметки времени: «12:34:56 » — ровно девять знаков. */
    private fun message(): String = last().substring("12:34:56 ".length)

    /**
     * Контекст у LogBus глобальный и переживает тест, а падать на него не должно
     * ни одно поле — обнуляем через рефлексию, чтобы не трогать продакшн-код.
     */
    private fun forgetContext() {
        LogBus::class.java
            .getDeclaredField("appContext")
            .apply { isAccessible = true }
            .set(LogBus, null)
    }

    @Before
    fun setUp() {
        LogBus.lines.value = emptyList()
        LogBus.init(ctx)
    }

    @After
    fun tearDown() {
        LogBus.lines.value = emptyList()
        LogBus.init(ctx)
    }

    // --- формат строки ---

    @Test
    fun `a line carries a timestamp and the message itself`() {
        LogBus.i("hello")

        assertEquals(1, LogBus.lines.value.size)
        assertTrue(last(), stamped.containsMatchIn(last()))
        assertEquals("hello", message())
    }

    @Test
    fun `an error names the exception class and its text`() {
        LogBus.e("could not connect", M2Error("radio is off"))

        assertEquals("could not connect: M2Error: radio is off", message())
    }

    /** Ошибки бывают и без исключения — тогда лишнего двоеточия быть не должно. */
    @Test
    fun `an error without an exception stays as written`() {
        LogBus.e("plain trouble")

        assertEquals("plain trouble", message())
    }

    // --- сообщения из ресурсов ---

    @Test
    fun `a resource message is formatted with its arguments`() {
        LogBus.i(R.string.log_local_rides, 7)

        assertEquals(ctx.getString(R.string.log_local_rides, 7), message())
    }

    @Test
    fun `a resource error is formatted the same way`() {
        LogBus.e(R.string.log_missing_perms, "READ_WEIGHT")

        assertEquals(ctx.getString(R.string.log_missing_perms, "READ_WEIGHT"), message())
    }

    @Test
    fun `a resource error appends the exception it was given`() {
        LogBus.e(R.string.log_import_failed, M2Error("no track points"), "ride.fit")

        assertEquals(
            ctx.getString(R.string.log_import_failed, "ride.fit") + ": M2Error: no track points",
            message(),
        )
    }

    /** Тот же вызов без исключения — сообщение остаётся голым текстом ресурса. */
    @Test
    fun `a resource error without an exception keeps just the text`() {
        val nothingThrown: Throwable? = null
        LogBus.e(R.string.log_import_failed, nothingThrown, "ride.fit")

        assertEquals(ctx.getString(R.string.log_import_failed, "ride.fit"), message())
    }

    // --- запасной путь без контекста ---

    /**
     * Первые строки могут родиться до [LogBus.init] — например из статического
     * инициализатора. Ресурсы тогда недоступны, но сообщение теряться не должно.
     */
    @Test
    fun `without a context the arguments stand in for the text`() {
        forgetContext()

        assertEquals("ride.fit 12", LogBus.string(R.string.log_import_failed, "ride.fit", 12))
        assertEquals("", LogBus.string(R.string.log_nothing_found))
    }

    @Test
    fun `with a context the text comes from resources`() {
        assertEquals(
            ctx.getString(R.string.log_taking, "M2_1234", "AA:BB"),
            LogBus.string(R.string.log_taking, "M2_1234", "AA:BB"),
        )
    }

    // --- счётчик ошибок ---

    /** По нему экран отличает удачный конец длинной работы от неудачного. */
    @Test
    fun `only errors move the failure counter`() {
        val before = LogBus.failures.value

        LogBus.i("all good")
        assertEquals(before, LogBus.failures.value)

        LogBus.e("trouble")
        LogBus.e(R.string.log_missing_perms, "READ_WEIGHT")
        assertEquals(before + 2, LogBus.failures.value)
    }

    // --- размер буфера ---

    /** Экран журнала держит последние 500 строк: длинный импорт не должен съесть память. */
    @Test
    fun `the buffer keeps only the last five hundred lines`() {
        repeat(600) { LogBus.i("line $it") }

        assertEquals(500, LogBus.lines.value.size)
        assertTrue(
            LogBus.lines.value
                .first()
                .endsWith("line 100"),
        )
        assertTrue(
            LogBus.lines.value
                .last()
                .endsWith("line 599"),
        )
    }
}
