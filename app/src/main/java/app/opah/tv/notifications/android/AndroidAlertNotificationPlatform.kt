package app.opah.tv.notifications.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.opah.tv.MainActivity
import app.opah.tv.R
import app.opah.tv.notifications.AlertNotificationPlatform
import app.opah.tv.notifications.AlertNotificationPost
import app.opah.tv.notifications.AlertThumbnailRequest
import app.opah.tv.notifications.AlertContentIntentKind
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.notifications.NotificationActionKind
import app.opah.tv.notifications.NotificationAlertBehavior
import app.opah.tv.notifications.NotificationClass
import app.opah.tv.data.model.ConnectionProfile
import okhttp3.OkHttpClient

/** Native Android/TV presentation boundary. Thumbnail storage is added behind this boundary. */
class AndroidAlertNotificationPlatform(
    context: Context,
    client: OkHttpClient,
    profile: suspend (String) -> ConnectionProfile?,
) : AlertNotificationPlatform {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)
    private val thumbnails = AndroidNotificationThumbnailStore(client, profile)
    private val overlays = TvAlertOverlayPresenter(appContext)

    override suspend fun prepareThumbnail(request: AlertThumbnailRequest): String? =
        thumbnails.prepare(request)

    override suspend fun post(request: AlertNotificationPost): Boolean = runCatching {
        if (!canPostNotifications()) return false
        AndroidNotificationChannels.create(appContext)
        val channelId = request.model.notificationClass.channelId()
        if (!channelAvailable(channelId)) return false
        val silent = request.alertBehavior == NotificationAlertBehavior.SILENT_UPDATE
        val contentIntent = contentIntent(request)
        val thumbnail = request.model.thumbnailContentVersion?.let { version ->
            if (
                request.contentIntentKind == AlertContentIntentKind.LOCAL_TEST &&
                version == LOCAL_TEST_THUMBNAIL_CONTENT_VERSION
            ) {
                localTestThumbnail()
            } else {
                thumbnails.bitmap(request.profileKey, request.reviewId, version)
            }
        }
        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(request.model.title)
            .setContentText(request.model.body)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceActionIntent(request, TvAlertForegroundService.ACTION_DISMISS))
            .extend(
                NotificationCompat.TvExtender()
                    .setChannelId(channelId)
                    .setContentIntent(contentIntent)
                    .setSuppressShowOverApps(false),
            )
        if (thumbnail == null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(request.model.body))
        } else {
            builder
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .setSummaryText(request.model.body),
                )
        }
        if (NotificationActionKind.SNOOZE in request.model.actions) {
            builder.addAction(
                0,
                "Snooze 15 min",
                serviceActionIntent(request, TvAlertForegroundService.ACTION_SNOOZE),
            )
        }
        val notification = builder.build()
        notifyAfterPermissionCheck(request.identity.tag, request.identity.id, notification)
        if (!silent) {
            overlays.show(
                profileKey = request.profileKey,
                reviewId = request.reviewId,
                identity = request.identity,
                title = request.model.title,
                body = request.model.body,
                thumbnail = thumbnail,
            )
        }
        true
    }.getOrDefault(false)

    override suspend fun cancel(identity: AndroidNotificationIdentity): Boolean = runCatching {
        notifications.cancel(identity.tag, identity.id)
        overlays.dismiss(identity)
        true
    }.getOrDefault(false)

    override suspend fun purgeEphemeralContent(profileKey: String, reviewId: String): Boolean {
        overlays.dismiss(profileKey, reviewId)
        return thumbnails.purge(profileKey, reviewId)
    }

    override suspend fun purgeProfileEphemeralContent(profileKey: String): Boolean {
        overlays.dismissProfile(profileKey)
        return thumbnails.purgeProfile(profileKey)
    }

    override suspend fun postSignInRequired(): Boolean = runCatching {
        if (!canPostNotifications()) return false
        AndroidNotificationChannels.create(appContext)
        if (!channelAvailable(AndroidNotificationChannels.ALERT_CHANNEL_ID)) return false
        val contentIntent = PendingIntent.getActivity(
            appContext,
            SIGN_IN_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            appContext,
            AndroidNotificationChannels.ALERT_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle("Sign in to Frigate")
            .setContentText("Open Opah to keep TV alerts working")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Open Opah to keep TV alerts working"),
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .extend(
                NotificationCompat.TvExtender()
                    .setChannelId(AndroidNotificationChannels.ALERT_CHANNEL_ID)
                    .setContentIntent(contentIntent)
                    .setSuppressShowOverApps(false),
            )
            .build()
        notifyAfterPermissionCheck(SIGN_IN_NOTIFICATION_TAG, SIGN_IN_NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    override suspend fun cancelSignInRequired(): Boolean = runCatching {
        notifications.cancel(SIGN_IN_NOTIFICATION_TAG, SIGN_IN_NOTIFICATION_ID)
        true
    }.getOrDefault(false)

    fun clearEphemeralContent() {
        overlays.dismissAll()
        thumbnails.clear()
    }

    fun dismissOverlay() = overlays.dismissAll()

    private fun localTestThumbnail(): Bitmap {
        val bitmap = createBitmap(480, 270)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(43, 57, 72))
        paint.color = Color.rgb(104, 142, 163)
        canvas.drawRect(0f, 0f, 480f, 108f, paint)
        paint.color = Color.rgb(68, 82, 69)
        canvas.drawRect(0f, 108f, 480f, 270f, paint)
        paint.color = Color.rgb(212, 198, 170)
        canvas.drawRoundRect(RectF(150f, 54f, 330f, 244f), 8f, 8f, paint)
        paint.color = Color.rgb(52, 61, 70)
        canvas.drawRect(202f, 94f, 278f, 244f, paint)
        paint.color = Color.rgb(112, 214, 198)
        canvas.drawCircle(266f, 170f, 5f, paint)
        paint.color = Color.argb(210, 14, 19, 27)
        canvas.drawRoundRect(RectF(14f, 14f, 170f, 48f), 8f, 8f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("FICTIONAL TEST", 28f, 37f, paint)
        return bitmap
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notifyAfterPermissionCheck(tag: String, id: Int, notification: Notification) {
        // Both callers check the runtime permission immediately before building and posting.
        // Their outer runCatching also handles revocation in the small race before this call.
        notifications.notify(tag, id, notification)
    }

    private fun contentIntent(request: AlertNotificationPost): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(
                when (request.contentIntentKind) {
                    AlertContentIntentKind.EVENT -> TvAlertIntentContract.ACTION_OPEN
                    AlertContentIntentKind.LOCAL_TEST -> TvAlertIntentContract.ACTION_OPEN_LOCAL_TEST
                },
            )
            .setData(
                Uri.Builder()
                    .scheme(INTERNAL_SCHEME)
                    .authority(INTERNAL_AUTHORITY)
                    .appendPath(request.identity.tag)
                    .build(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (request.contentIntentKind == AlertContentIntentKind.EVENT) intent.putAlertPayload(request)
        return PendingIntent.getActivity(
            appContext,
            request.identity.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceActionIntent(request: AlertNotificationPost, action: String): PendingIntent {
        val intent = Intent(appContext, TvAlertForegroundService::class.java)
            .setAction(action)
            .setData(
                Uri.Builder()
                    .scheme(INTERNAL_SCHEME)
                    .authority(INTERNAL_AUTHORITY)
                    .appendPath(action.substringAfterLast('.'))
                    .appendPath(request.identity.tag)
                    .build(),
            )
            .putAlertPayload(request)
        return PendingIntent.getService(
            appContext,
            request.identity.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun Intent.putAlertPayload(request: AlertNotificationPost): Intent =
        putExtra(TvAlertIntentContract.EXTRA_PROFILE_KEY, request.profileKey)
            .putExtra(TvAlertIntentContract.EXTRA_REVIEW_ID, request.reviewId)
            .putExtra(TvAlertIntentContract.EXTRA_ACTION_NONCE, request.actionNonce)

    private fun channelAvailable(channelId: String): Boolean {
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = appContext.getSystemService(NotificationManager::class.java)
        return manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun NotificationClass.channelId(): String = when (this) {
        NotificationClass.ALERT -> AndroidNotificationChannels.ALERT_CHANNEL_ID
        NotificationClass.DETECTION -> AndroidNotificationChannels.DETECTION_CHANNEL_ID
    }

    private companion object {
        const val INTERNAL_SCHEME = "opah-internal"
        const val INTERNAL_AUTHORITY = "notification"
        const val SIGN_IN_NOTIFICATION_TAG = "opah-tv-alert-sign-in"
        const val SIGN_IN_NOTIFICATION_ID = 50_052
    }
}

internal const val LOCAL_TEST_THUMBNAIL_CONTENT_VERSION = "opah-local-fictional-thumbnail-v1"
