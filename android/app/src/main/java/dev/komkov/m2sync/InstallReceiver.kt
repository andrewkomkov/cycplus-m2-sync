package dev.komkov.m2sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Ответ системы на сессию установки.
 *
 * Сама установка — дело системы, и подтверждает её пользователь: сюда прилетает
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] с готовым диалогом, который
 * остаётся только показать.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "dev.komkov.m2sync.INSTALL_RESULT"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        LogBus.init(ctx)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Диалог запускается, пока приложение на экране — пользователь
                // только что нажал кнопку, так что запрет на старт активити из
                // фона здесь не срабатывает.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    AppState.updateProgress.value = null
                    LogBus.e(R.string.log_update_install_failed, "no confirmation intent")
                    return
                }
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure {
                        AppState.updateProgress.value = null
                        LogBus.e(R.string.log_update_install_failed, it.message ?: "")
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppState.updateProgress.value = null
                AppState.update.value = null
                LogBus.i(R.string.log_update_installed)
            }

            else -> {
                AppState.updateProgress.value = null
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                LogBus.e(R.string.log_update_install_failed, message ?: status.toString())
            }
        }
    }
}
