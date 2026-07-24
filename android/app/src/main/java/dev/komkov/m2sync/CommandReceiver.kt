package dev.komkov.m2sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Мостик из терминала в сервис:
 *
 *   adb shell am broadcast -a dev.komkov.m2sync.SCAN
 *   adb shell am broadcast -a dev.komkov.m2sync.SYNC -e name M2_XXXX
 *   adb shell am broadcast -a dev.komkov.m2sync.IMPORT
 *   adb shell am broadcast -a dev.komkov.m2sync.STATUS
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        LogBus.init(context)
        LogBus.i(R.string.log_command, action)
        val forward = Intent(context, SyncService::class.java).apply {
            this.action = action
            intent.getStringExtra(SyncService.EXTRA_NAME)?.let {
                putExtra(SyncService.EXTRA_NAME, it)
            }
            intent.getStringExtra(SyncService.EXTRA_ADDRESS)?.let {
                putExtra(SyncService.EXTRA_ADDRESS, it)
            }
        }
        context.startForegroundService(forward)
    }
}
