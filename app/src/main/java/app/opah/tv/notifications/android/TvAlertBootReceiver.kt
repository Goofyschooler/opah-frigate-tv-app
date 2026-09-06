package app.opah.tv.notifications.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opah.tv.OpahApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Restores a previously enabled alert listener after an allowed system restart signal. */
class TvAlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val application = context.applicationContext as? OpahApplication ?: return@launch
                val shouldRestore = withTimeoutOrNull(RESTORE_GATE_TIMEOUT_MILLIS) {
                    application.container.restoreTvAlertsAfterProcessRecreation()
                } == true
                if (shouldRestore) {
                    TvAlertServiceController.startAfterSystemRestart(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        const val RESTORE_GATE_TIMEOUT_MILLIS = 8_000L
    }
}
