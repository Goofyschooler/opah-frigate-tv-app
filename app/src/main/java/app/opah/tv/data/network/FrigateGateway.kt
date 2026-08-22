package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.RecordingExport

interface FrigateGateway {
    suspend fun login(profile: ConnectionProfile, password: String): FrigateUserProfile
    suspend fun refreshSession(profile: ConnectionProfile): FrigateUserProfile
    suspend fun logout(profile: ConnectionProfile)
    suspend fun getVersion(profile: ConnectionProfile): String
    suspend fun getConfig(profile: ConnectionProfile): String
    suspend fun getStats(profile: ConnectionProfile): String
    suspend fun getRecordingsStorage(profile: ConnectionProfile): String
    suspend fun getGo2RtcStreams(profile: ConnectionProfile): String
    suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String): String
    suspend fun getPtzInfo(profile: ConnectionProfile, camera: String): String
    suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery): String
    suspend fun getReviewSummary(
        profile: ConnectionProfile,
        cameras: Set<String>,
        timezone: String,
    ): String = throw UnsupportedOperationException("Review summaries are not implemented by this gateway")
    suspend fun getReviewMotionActivity(
        profile: ConnectionProfile,
        cameras: Set<String>,
        after: Double,
        before: Double,
    ): String = throw UnsupportedOperationException("Motion activity is not implemented by this gateway")
    suspend fun getEventsByIds(
        profile: ConnectionProfile,
        eventIds: Set<String>,
    ): String = throw UnsupportedOperationException("Linked events are not implemented by this gateway")
    suspend fun searchEvents(profile: ConnectionProfile, query: EventSearchQuery): String
    suspend fun setReviewsViewed(
        profile: ConnectionProfile,
        reviewIds: Set<String>,
        reviewed: Boolean = true,
    )
    suspend fun getRecordings(
        profile: ConnectionProfile,
        camera: String,
        after: Double,
        before: Double,
    ): String
    suspend fun getRecordingSummary(
        profile: ConnectionProfile,
        camera: String,
        timezone: String,
    ): String = throw UnsupportedOperationException("Recording summaries are not implemented by this gateway")
    suspend fun getExports(profile: ConnectionProfile): String =
        throw UnsupportedOperationException("Saved clips are not implemented by this gateway.")
    suspend fun startRecordingExport(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
        name: String,
    ): String = throw UnsupportedOperationException("Saved clips are not implemented by this gateway.")
    suspend fun deleteExport(profile: ConnectionProfile, exportId: String): Unit =
        throw UnsupportedOperationException("Deleting saved recordings is not implemented by this gateway.")
    suspend fun deleteExports(profile: ConnectionProfile, exportIds: Set<String>): Unit =
        throw UnsupportedOperationException("Deleting saved recordings is not implemented by this gateway.")
    fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String
    fun recordingPlaybackUrl(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
    ): String
    fun exportPlaybackUrl(profile: ConnectionProfile, export: RecordingExport): String? = null
}
