package app.opah.tv.notifications.android

import android.content.Intent

internal data class TvAlertIntentPayload(
    val profileKey: String,
    val reviewId: String,
    val actionNonce: String,
)

internal object TvAlertIntentContract {
    const val ACTION_OPEN = "app.opah.tv.action.OPEN_FROM_TV_ALERT"
    const val ACTION_OPEN_LOCAL_TEST = "app.opah.tv.action.OPEN_LOCAL_TEST_ALERT"
    const val EXTRA_PROFILE_KEY = "app.opah.tv.extra.ALERT_PROFILE"
    const val EXTRA_REVIEW_ID = "app.opah.tv.extra.ALERT_REVIEW"
    const val EXTRA_ACTION_NONCE = "app.opah.tv.extra.ALERT_NONCE"

    fun payload(intent: Intent): TvAlertIntentPayload? {
        if (intent.action !in setOf(
                ACTION_OPEN,
                TvAlertForegroundService.ACTION_DISMISS,
                TvAlertForegroundService.ACTION_SNOOZE,
            )
        ) return null
        val profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY)?.safeIdentity(MAX_PROFILE_CHARS)
            ?: return null
        val reviewId = intent.getStringExtra(EXTRA_REVIEW_ID)?.safeIdentity(MAX_REVIEW_CHARS)
            ?: return null
        val nonce = intent.getStringExtra(EXTRA_ACTION_NONCE)?.safeIdentity(MAX_NONCE_CHARS)
            ?: return null
        return TvAlertIntentPayload(profileKey, reviewId, nonce)
    }

    fun isLocalTestOpen(intent: Intent): Boolean = intent.action == ACTION_OPEN_LOCAL_TEST

    private fun String.safeIdentity(maxChars: Int): String? = takeIf {
        it.isNotBlank() && it.length <= maxChars && it.none { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }
    }

    private const val MAX_PROFILE_CHARS = 128
    private const val MAX_REVIEW_CHARS = 256
    private const val MAX_NONCE_CHARS = 256
}
