package app.opah.tv.notifications.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationManager

enum class TvAlertStartResult {
    STARTED,
    NOTIFICATION_PERMISSION_REQUIRED,
    ON_SCREEN_PERMISSION_REQUIRED,
    NOTIFICATIONS_BLOCKED,
    START_REJECTED,
}

enum class TvAlertActivationResult {
    STARTED,
    NOTIFICATION_PERMISSION_REQUIRED,
    ON_SCREEN_PERMISSION_REQUIRED,
    NOTIFICATIONS_BLOCKED,
    START_REJECTED,
    CONFIGURATION_UNAVAILABLE,
}

enum class TvAlertDeliveryStatus {
    AVAILABLE,
    PARTIALLY_BLOCKED,
    BLOCKED,
    NOTIFICATION_PERMISSION_REQUIRED,
}

object TvAlertServiceController {
    fun deliveryStatus(context: Context): TvAlertDeliveryStatus {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return TvAlertDeliveryStatus.NOTIFICATION_PERMISSION_REQUIRED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return TvAlertDeliveryStatus.BLOCKED
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return TvAlertDeliveryStatus.AVAILABLE
        val manager = context.getSystemService(NotificationManager::class.java)
        val availableChannels = listOf(
            AndroidNotificationChannels.ALERT_CHANNEL_ID,
            AndroidNotificationChannels.DETECTION_CHANNEL_ID,
        ).count { channelId ->
            manager.getNotificationChannel(channelId)?.importance
                ?.let { importance -> importance != NotificationManager.IMPORTANCE_NONE } == true
        }
        return when (availableChannels) {
            0 -> TvAlertDeliveryStatus.BLOCKED
            1 -> TvAlertDeliveryStatus.PARTIALLY_BLOCKED
            else -> TvAlertDeliveryStatus.AVAILABLE
        }
    }

    fun startFromVisibleUserAction(context: Context): TvAlertStartResult {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return TvAlertStartResult.NOTIFICATION_PERMISSION_REQUIRED
        val onScreenAlertsEnabled =
            TvAlertOverlaySettingsStore(context).load().displayDurationSeconds > 0
        if (onScreenAlertsEnabled && !TvAlertOverlayPermission.isGranted(context)) {
            return TvAlertStartResult.ON_SCREEN_PERMISSION_REQUIRED
        }

        AndroidNotificationChannels.create(context)
        if (!AndroidNotificationChannels.eventChannelsAvailable(context)) {
            return TvAlertStartResult.NOTIFICATIONS_BLOCKED
        }
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TvAlertForegroundService::class.java).setAction(
                    TvAlertForegroundService.ACTION_START,
                ),
            )
        }.fold(
            onSuccess = { TvAlertStartResult.STARTED },
            onFailure = { TvAlertStartResult.START_REJECTED },
        )
    }

    fun startAfterSystemRestart(context: Context): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TvAlertForegroundService::class.java).setAction(
                TvAlertForegroundService.ACTION_RESTORE,
            ),
        )
    }.isSuccess

    fun stop(context: Context) {
        context.stopService(Intent(context, TvAlertForegroundService::class.java))
    }
}
