package app.opah.tv.notifications.android

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.opah.tv.OpahApplication
import app.opah.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TvAlertForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                container().disableTvAlerts(stopAndroidService = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        return runCatching {
            // Foreground promotion remains the first work for starts and notification actions.
            promoteToForeground()
            when (intent?.action) {
                ACTION_START -> container().startAlertAwareness()
                ACTION_RESTORE -> restoreAfterProcessRecreation(startId)
                ACTION_DISMISS -> restoreAndLaunchAction(intent, dismiss = true, startId)
                ACTION_SNOOZE -> restoreAndLaunchAction(intent, dismiss = false, startId)
                null -> restoreAfterProcessRecreation(startId)
                else -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            START_STICKY
        }.getOrElse {
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun restoreAfterProcessRecreation(startId: Int) {
        serviceScope.launch {
            if (container().restoreTvAlertsAfterProcessRecreation()) {
                container().startAlertAwareness()
            } else {
                container().stopAlertAwareness()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun restoreAndLaunchAction(intent: Intent, dismiss: Boolean, startId: Int) {
        val payload = TvAlertIntentContract.payload(intent) ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }
        serviceScope.launch {
            if (!container().restoreTvAlertsAfterProcessRecreation()) {
                container().stopAlertAwareness()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return@launch
            }
            container().startAlertAwareness()
            if (dismiss) {
                container().dismissTvAlert(payload.profileKey, payload.reviewId, payload.actionNonce)
            } else {
                container().snoozeTvAlertFromNotification(
                    payload.profileKey,
                    payload.reviewId,
                    payload.actionNonce,
                )
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        container().dismissTvAlertOverlay()
        container().stopAlertAwareness()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        AndroidNotificationChannels.create(this)
        ServiceCompat.startForeground(
            this,
            SERVICE_NOTIFICATION_ID,
            statusNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun container() = (application as OpahApplication).container

    private fun statusNotification() = NotificationCompat.Builder(
        this,
        AndroidNotificationChannels.SERVICE_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_videocam)
        .setContentTitle("Opah TV alerts are active")
        .setContentText("Opah is watching for the activity you selected")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .addAction(0, "Turn off", stopIntent())
        .extend(
            NotificationCompat.TvExtender()
                .setChannelId(AndroidNotificationChannels.SERVICE_CHANNEL_ID)
                .setSuppressShowOverApps(true),
        )
        .build()

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_REQUEST_CODE,
        Intent(this, TvAlertForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        internal const val ACTION_START = "app.opah.tv.action.START_TV_ALERTS"
        internal const val ACTION_RESTORE = "app.opah.tv.action.RESTORE_TV_ALERTS"
        internal const val ACTION_STOP = "app.opah.tv.action.STOP_TV_ALERTS"
        internal const val ACTION_DISMISS = "app.opah.tv.action.DISMISS_TV_ALERT"
        internal const val ACTION_SNOOZE = "app.opah.tv.action.SNOOZE_TV_ALERT"
        private const val SERVICE_NOTIFICATION_ID = 50_050
        private const val STOP_REQUEST_CODE = 50_051
    }
}
