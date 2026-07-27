package dev.komkov.m2sync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Приёмники широковещательных сообщений. Система дёргает у них ровно один метод,
 * поэтому и тест зовёт `onReceive` напрямую с подготовленным Intent.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiversTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        // Журнал и состояние — синглтоны, живущие дольше одного теста.
        LogBus.lines.value = emptyList()
        AppState.update.value = null
        AppState.updateProgress.value = null
    }

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = app.getString(id, *args)

    private fun lastLine(): String = LogBus.lines.value.last()

    private fun startedService(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    private fun startedActivity(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    // --- команды из терминала ---

    @Test
    fun `a command from the terminal is forwarded to the service`() {
        val intent =
            Intent(SyncService.ACTION_SYNC)
                .putExtra(SyncService.EXTRA_NAME, "M2_1234")
                .putExtra(SyncService.EXTRA_ADDRESS, "E3:E8:F7:E3:09:44")

        CommandReceiver().onReceive(app, intent)

        val forwarded = requireNotNull(startedService())
        assertEquals(SyncService::class.java.name, forwarded.component?.className)
        assertEquals(SyncService.ACTION_SYNC, forwarded.action)
        assertEquals("M2_1234", forwarded.getStringExtra(SyncService.EXTRA_NAME))
        assertEquals("E3:E8:F7:E3:09:44", forwarded.getStringExtra(SyncService.EXTRA_ADDRESS))
        assertTrue(lastLine().endsWith(string(R.string.log_command, SyncService.ACTION_SYNC)))
    }

    /** Чего в команде не было, того и в пересылке быть не должно. */
    @Test
    fun `absent extras are not invented`() {
        CommandReceiver().onReceive(app, Intent(SyncService.ACTION_STATUS))

        val forwarded = requireNotNull(startedService())
        assertEquals(SyncService.ACTION_STATUS, forwarded.action)
        assertNull(forwarded.getStringExtra(SyncService.EXTRA_NAME))
        assertNull(forwarded.getStringExtra(SyncService.EXTRA_ADDRESS))
    }

    /** Intent без действия пересылать некуда — и в журнал такое не пишем. */
    @Test
    fun `an intent without an action is ignored`() {
        CommandReceiver().onReceive(app, Intent())

        assertNull(startedService())
        assertEquals(emptyList<String>(), LogBus.lines.value)
    }

    /**
     * Из фона foreground-сервис не запустить, и раньше это роняло приёмник:
     * команда из терминала не должна убивать приложение.
     */
    @Test
    fun `a refused service start is logged instead of crashing`() {
        val refusing =
            object : ContextWrapper(app) {
                override fun startForegroundService(service: Intent): android.content.ComponentName? =
                    throw IllegalStateException("app is in background")
            }

        CommandReceiver().onReceive(refusing, Intent(SyncService.ACTION_SCAN))

        assertNull(startedService())
        assertTrue(lastLine().contains(string(R.string.log_command_background)))
        assertTrue(lastLine().contains("IllegalStateException"))
    }

    // --- ответ установщика ---

    @Test
    fun `a pending confirmation dialog is shown as a new task`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val intent =
            Intent(InstallReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm)

        InstallReceiver().onReceive(app, intent)

        val shown = requireNotNull(startedActivity())
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shown.action)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            shown.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    /** Система обещала диалог, но не приложила — ждать нечего, снимаем прогресс. */
    @Test
    fun `a pending status without a dialog ends the update`() {
        AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.INSTALL)
        val intent =
            Intent(InstallReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)

        InstallReceiver().onReceive(app, intent)

        assertNull(startedActivity())
        assertNull(AppState.updateProgress.value)
        assertEquals(
            string(R.string.log_update_install_failed, "no confirmation intent"),
            lastLine().substringAfter(' '),
        )
    }

    @Test
    fun `a dialog that cannot be shown ends the update`() {
        AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.INSTALL)
        val refusing =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) = throw ActivityNotFoundException("no ui")
            }
        val intent =
            Intent(InstallReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL"))

        InstallReceiver().onReceive(refusing, intent)

        assertNull(AppState.updateProgress.value)
        assertTrue(lastLine().contains("no ui"))
    }

    @Test
    fun `a successful install clears both the update and its progress`() {
        AppState.update.value = UpdateChecker.Update("0.9.9", null, "https://example.invalid", null)
        AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.INSTALL)
        val intent =
            Intent(InstallReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)

        InstallReceiver().onReceive(app, intent)

        assertNull(AppState.update.value)
        assertNull(AppState.updateProgress.value)
        assertTrue(lastLine().endsWith(string(R.string.log_update_installed)))
    }

    /** Отказ установщика приходит с человеческим объяснением — его и показываем. */
    @Test
    fun `a failure is reported with the message from the installer`() {
        AppState.updateProgress.value = UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 5, 10)
        val intent =
            Intent(InstallReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_CONFLICT)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "signature mismatch")

        InstallReceiver().onReceive(app, intent)

        assertNull(AppState.updateProgress.value)
        assertEquals(
            string(R.string.log_update_install_failed, "signature mismatch"),
            lastLine().substringAfter(' '),
        )
    }

    /** Ни статуса, ни объяснения: в журнал уходит хотя бы код, а не пустота. */
    @Test
    fun `a broadcast without a status still says something`() {
        InstallReceiver().onReceive(app, Intent(InstallReceiver.ACTION))

        assertNull(AppState.updateProgress.value)
        assertEquals(
            string(R.string.log_update_install_failed, Int.MIN_VALUE.toString()),
            lastLine().substringAfter(' '),
        )
    }
}
