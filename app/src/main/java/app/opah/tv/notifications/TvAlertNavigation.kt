package app.opah.tv.notifications

/** A privacy-safe destination resolved only after revalidating a notification action. */
internal sealed interface TvAlertOpenResolution {
    data class LiveCamera(val cameraId: String) : TvAlertOpenResolution

    data class RecordedReview(val reviewId: String) : TvAlertOpenResolution

    data class Activity(val expired: Boolean) : TvAlertOpenResolution

    data object PrivacyBlocked : TvAlertOpenResolution

    data object RetryLater : TvAlertOpenResolution

    data object Unavailable : TvAlertOpenResolution
}
