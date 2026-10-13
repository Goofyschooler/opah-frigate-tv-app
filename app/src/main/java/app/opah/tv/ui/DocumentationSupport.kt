package app.opah.tv.ui

import android.content.Context
import app.opah.tv.BuildConfig
import app.opah.tv.data.CameraImage
import app.opah.tv.data.ReviewImage
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.FrigateInformationSummary
import app.opah.tv.data.model.MotionActivity
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.RecordingHourSummary
import app.opah.tv.data.model.RecordingSegment
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.SearchEvent
import app.opah.tv.playback.PlaybackRequest

internal const val DOCUMENTATION_URI_PREFIX = "documentation://"

/** Optional presentation data; the implementation is absent from ordinary app builds. */
internal interface DocumentationDataProvider {
    fun state(rawScenario: String?): Phase0UiState
    fun reviewItems(): List<ReviewItem>
    fun information(): FrigateInformationSummary
    fun livePlayback(camera: Camera): PlaybackRequest
    fun compatibilityPlayback(camera: Camera): PlaybackRequest
    fun birdseyePlayback(): PlaybackRequest
    fun recordedPlayback(item: ReviewItem): PlaybackRequest
    fun recordingHistory(hourStartSeconds: Double, endSeconds: Double): List<RecordingSegment>
    fun searchEvents(query: String, cameras: Set<String>): List<SearchEvent>
    fun reviewItemForEvent(event: SearchEvent): ReviewItem?
    fun historyPlayback(camera: Camera, startTime: Double, endTime: Double): PlaybackRequest
    fun searchPlayback(camera: Camera, event: SearchEvent): PlaybackRequest
    fun historySummaries(nowSeconds: Double): List<RecordingHourSummary>
    fun motionActivity(camera: String, hourStartSeconds: Double): List<MotionActivity>
    fun modes(): ModesUiState
    fun recordingEventId(): String
    fun incident(name: String, description: String?): ExportIncident
    fun motionReview(camera: String, regionIndex: Int, end: Double): MotionReviewUiState
    fun playbackTelemetry(camera: String?, recorded: Boolean, videoOnly: Boolean): PlaybackTelemetry
    fun nativePlaybackPositionMillis(): Long
    fun nativePlaybackDurationMillis(): Long
}

internal interface DocumentationImageProvider {
    fun camera(cameraName: String): CameraImage?
    fun review(item: ReviewItem): ReviewImage?
    fun export(item: RecordingExport): ReviewImage?
}

internal interface DocumentationEnvironmentProvider {
    val data: DocumentationDataProvider
    fun images(context: Context): DocumentationImageProvider
    fun cameraDrawable(cameraName: String): Int
}

internal object DocumentationSupport {
    private val provider: DocumentationEnvironmentProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(BuildConfig.DOCUMENTATION_MODE) {
            "Documentation data is unavailable in production builds"
        }
        val providerClass = Class.forName("app.opah.tv.ui.DocumentationEnvironmentImpl")
        providerClass.getDeclaredConstructor().newInstance() as DocumentationEnvironmentProvider
    }

    val data: DocumentationDataProvider get() = provider.data
    fun images(context: Context): DocumentationImageProvider = provider.images(context)
    fun cameraDrawable(cameraName: String): Int = provider.cameraDrawable(cameraName)
}
