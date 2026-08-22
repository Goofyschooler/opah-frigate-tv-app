package app.opah.tv.playback

data class PlaybackRequest(
    val title: String,
    val uri: String,
    val kind: PlaybackKind,
    val cameraName: String? = null,
    val detail: String? = null,
    val startupFallbackUri: String? = null,
    val startupFallbackDetail: String? = null,
    val activityItemId: String? = null,
    val recordingStartTime: Double? = null,
    val recordingEndTime: Double? = null,
    val recordingSaveKey: String? = null,
)

enum class PlaybackKind {
    LIVE,
    RECORDED,
}
