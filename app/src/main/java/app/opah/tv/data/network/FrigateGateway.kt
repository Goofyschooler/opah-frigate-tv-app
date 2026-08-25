package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.EventSearchQuery
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.BatchExportRequest
import app.opah.tv.data.model.IncidentDraft
import app.opah.tv.data.model.MotionSearchRequest

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
    suspend fun getProfiles(profile: ConnectionProfile): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.PROFILE_MODES)
    suspend fun getActiveProfile(profile: ConnectionProfile): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.PROFILE_MODES)
    suspend fun setActiveProfile(profile: ConnectionProfile, profileName: String?): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.PROFILE_MODE_SWITCH)
    suspend fun startMotionSearch(profile: ConnectionProfile, request: MotionSearchRequest): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.MOTION_SEARCH)
    suspend fun getMotionSearch(profile: ConnectionProfile, camera: String, jobId: String): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.MOTION_SEARCH)
    suspend fun cancelMotionSearch(profile: ConnectionProfile, camera: String, jobId: String): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.MOTION_SEARCH)
    suspend fun startOnDemandRecording(
        profile: ConnectionProfile,
        camera: String,
        durationSeconds: Int?,
    ): String = throw UnsupportedFrigateOperationException(FrigateContractOperation.ON_DEMAND_RECORDING)
    suspend fun stopOnDemandRecording(profile: ConnectionProfile, eventId: String): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.ON_DEMAND_RECORDING)
    suspend fun startBatchExport(profile: ConnectionProfile, request: BatchExportRequest): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.BATCH_EXPORT)
    suspend fun getActiveExportJobs(profile: ConnectionProfile): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.EXPORT_JOBS)
    suspend fun getExportJob(profile: ConnectionProfile, exportId: String): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.EXPORT_JOBS)
    suspend fun getIncidents(profile: ConnectionProfile): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENTS)
    suspend fun createIncident(profile: ConnectionProfile, draft: IncidentDraft): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENT_MUTATION)
    suspend fun updateIncident(profile: ConnectionProfile, incidentId: String, draft: IncidentDraft): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENT_MUTATION)
    suspend fun deleteIncident(profile: ConnectionProfile, incidentId: String, deleteClips: Boolean): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENT_MUTATION)
    suspend fun reassignExports(
        profile: ConnectionProfile,
        exportIds: Set<String>,
        incidentId: String?,
    ): String = throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENT_MUTATION)
    suspend fun renameExport(profile: ConnectionProfile, exportId: String, name: String): String =
        throw UnsupportedFrigateOperationException(FrigateContractOperation.INCIDENT_MUTATION)
    fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String
    fun recordingPlaybackUrl(
        profile: ConnectionProfile,
        camera: String,
        startTime: Double,
        endTime: Double,
    ): String
    fun exportPlaybackUrl(profile: ConnectionProfile, export: RecordingExport): String? = null
}
