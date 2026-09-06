package app.opah.tv.playback

data class PlaybackRequest(
    val title: String,
    val uri: String,
    val kind: PlaybackKind,
    val cameraName: String? = null,
    val detail: String? = null,
    val startupFallbackUri: String? = null,
    val startupFallbackDetail: String? = null,
    /** Opaque process capability for compatibility-engine live playback; never a URI. */
    val liveCompatibilityRequestId: String? = null,
    val compatibilityTest: Boolean = false,
    val compatibilityTestVideoOnly: Boolean = false,
    val activityItemId: String? = null,
    /** Saved Frigate export identity used to preserve clip-specific privacy authorization. */
    val savedClipId: String? = null,
    val activityContextItemIds: List<String> = emptyList(),
    val activityQueueContext: Boolean = false,
    val briefingHighlight: Boolean = false,
    val recordingStartTime: Double? = null,
    val recordingEndTime: Double? = null,
    val recordingSaveKey: String? = null,
    val returnToLiveCameraName: String? = null,
    val stretchPreferenceKey: String? = cameraName,
)

const val BIRDSEYE_STRETCH_PREFERENCE_KEY = "__opah_birdseye__"

enum class PlaybackKind {
    LIVE,
    RECORDED,
}
