package app.opah.tv.data.model

data class MotionSearchPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x in 0.0..1.0 && y in 0.0..1.0) { "Motion Search points must be normalized." }
    }
}

data class MotionSearchRequest(
    val camera: String,
    val startTime: Double,
    val endTime: Double,
    val polygon: List<MotionSearchPoint>,
    val threshold: Int = 30,
    val minimumAreaPercent: Double = 5.0,
    val parallel: Boolean = false,
    val maximumResults: Int = 25,
) {
    init {
        require(camera.isNotBlank()) { "Camera is required." }
        require(endTime > startTime) { "Motion Search end must follow its start." }
        require(polygon.size >= 3) { "Motion Search needs at least three points." }
        require(threshold in 1..255) { "Motion Search threshold is invalid." }
        require(minimumAreaPercent in 0.1..100.0) { "Motion Search minimum area is invalid." }
        require(maximumResults in 1..200) { "Motion Search result limit is invalid." }
    }
}

data class BatchExportItem(
    val camera: String,
    val startTime: Double,
    val endTime: Double,
    val friendlyName: String? = null,
    val clientItemId: String? = null,
) {
    init {
        require(camera.isNotBlank()) { "Camera is required." }
        require(endTime > startTime) { "Clip end must follow its start." }
        require(friendlyName == null || friendlyName.length <= 256) { "Clip name is too long." }
        require(clientItemId == null || clientItemId.length <= 128) { "Clip item ID is too long." }
    }
}

data class BatchExportRequest(
    val items: List<BatchExportItem>,
    val existingIncidentId: String? = null,
    val newIncidentName: String? = null,
    val newIncidentDescription: String? = null,
) {
    init {
        require(items.size in 1..50) { "Choose between one and 50 clips." }
        require(existingIncidentId == null || newIncidentName == null) {
            "Choose an existing or a new Incident, not both."
        }
        require(existingIncidentId == null || existingIncidentId.length <= 30) {
            "Incident ID is too long."
        }
        require(newIncidentName == null || newIncidentName.length <= 100) {
            "Incident name is too long."
        }
    }
}

data class IncidentDraft(
    val name: String,
    val description: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 100) { "Incident name is invalid." }
    }
}

data class FrigateMode(
    val name: String,
    val displayName: String,
)

data class FrigateModes(
    val modes: List<FrigateMode>,
    val activeMode: String?,
    val lastActivatedAt: Double?,
)

enum class MotionSearchJobState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, UNKNOWN }

data class MotionSearchResult(
    val timestamp: Double,
    val changedAreaPercent: Double,
)

data class MotionSearchStatus(
    val state: MotionSearchJobState,
    val results: List<MotionSearchResult>,
    val progress: Double?,
    val scanningTimestamp: Double?,
    val framesProcessed: Int?,
    val message: String?,
)

data class BatchExportResult(
    val camera: String,
    val exportId: String?,
    val success: Boolean,
    val status: String?,
    val error: String?,
    val itemIndex: Int?,
    val clientItemId: String?,
)

data class BatchExportStart(
    val incidentId: String?,
    val exportIds: List<String>,
    val results: List<BatchExportResult>,
)

data class ExportIncident(
    val id: String,
    val name: String,
    val description: String?,
    val createdAt: Double?,
    val updatedAt: Double?,
)

data class OnDemandRecordingStart(
    val eventId: String,
    val message: String?,
)
