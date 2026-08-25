package app.opah.tv.data

import app.opah.tv.data.model.BatchExportResult
import app.opah.tv.data.model.BatchExportStart
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.FrigateMode
import app.opah.tv.data.model.FrigateModes
import app.opah.tv.data.model.MotionSearchJobState
import app.opah.tv.data.model.MotionSearchResult
import app.opah.tv.data.model.MotionSearchStatus
import app.opah.tv.data.model.OnDemandRecordingStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Tolerant parsers for tagged 0.18 additions; unknown optional fields are ignored. */
class Frigate018JsonParsers(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseModes(rawJson: String): FrigateModes {
        val root = parseObject(rawJson) ?: return FrigateModes(emptyList(), null, null)
        val activeMode = root.string("active_profile")?.takeIf(String::isNotBlank)
        val modes = (root["profiles"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            FrigateMode(
                name = name,
                displayName = item.string("friendly_name")?.takeIf(String::isNotBlank) ?: name,
            )
        }.distinctBy(FrigateMode::name)
        return FrigateModes(
            modes = modes,
            activeMode = activeMode,
            lastActivatedAt = activeMode
                ?.let { (root["last_activated"] as? JsonObject)?.double(it) }
                ?.takeIf(Double::isFinite),
        )
    }

    fun parseActiveMode(rawJson: String): String? = parseObject(rawJson)
        ?.string("active_profile")
        ?.takeIf(String::isNotBlank)

    fun parseMotionSearchJobId(rawJson: String): String {
        val root = parseObject(rawJson) ?: error("Frigate returned an invalid Motion Search response")
        if (root.bool("success") != true) error("Frigate could not start Motion Search")
        return root.string("job_id")
            ?.takeIf { it.isNotBlank() && it.length <= 256 && '/' !in it && '\\' !in it }
            ?: error("Frigate did not return a valid Motion Search job ID")
    }

    fun parseMotionSearchStatus(rawJson: String): MotionSearchStatus {
        val root = parseObject(rawJson) ?: return MotionSearchStatus(
            MotionSearchJobState.UNKNOWN, emptyList(), null, null, null, null,
        )
        val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val timestamp = item.double("timestamp")?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val changed = item.double("change_percentage")?.takeIf(Double::isFinite) ?: return@mapNotNull null
            MotionSearchResult(timestamp, changed.coerceIn(0.0, 100.0))
        }.distinctBy(MotionSearchResult::timestamp).sortedBy(MotionSearchResult::timestamp)
        return MotionSearchStatus(
            state = when (root.string("status")?.lowercase()) {
                "queued" -> MotionSearchJobState.QUEUED
                "running" -> MotionSearchJobState.RUNNING
                "success", "completed" -> MotionSearchJobState.SUCCESS
                "failed" -> MotionSearchJobState.FAILED
                "cancelled", "canceled" -> MotionSearchJobState.CANCELLED
                else -> MotionSearchJobState.UNKNOWN
            },
            results = results,
            progress = root.double("progress")?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0),
            scanningTimestamp = root.double("scanning_timestamp")?.takeIf(Double::isFinite),
            framesProcessed = root.int("total_frames_processed")?.coerceAtLeast(0),
            message = root.string("message")?.takeIf(String::isNotBlank),
        )
    }

    fun parseBatchExportStart(rawJson: String, allowedCameras: Set<String>): BatchExportStart {
        val root = parseObject(rawJson) ?: return BatchExportStart(null, emptyList(), emptyList())
        val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val camera = item.string("camera")?.takeIf { it in allowedCameras } ?: return@mapNotNull null
            BatchExportResult(
                camera = camera,
                exportId = item.string("export_id")?.takeIf(String::isNotBlank),
                success = item.bool("success") == true,
                status = item.string("status")?.takeIf(String::isNotBlank),
                error = item.string("error")?.takeIf(String::isNotBlank),
                itemIndex = item.int("item_index")?.takeIf { it >= 0 },
                clientItemId = item.string("client_item_id")?.takeIf(String::isNotBlank),
            )
        }
        val visibleExportIds = results.mapNotNull(BatchExportResult::exportId).toSet()
        val ids = (root["export_ids"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { it in visibleExportIds }
            .distinct()
        return BatchExportStart(
            incidentId = root.string("export_case_id")?.takeIf(String::isNotBlank),
            exportIds = ids,
            results = results,
        )
    }

    /**
     * The tagged Cases list is not camera-filtered. Callers must supply IDs
     * correlated with already authorized exports. Empty Incidents are visible
     * only when the administrator flow explicitly created them in this session.
     */
    fun parseIncidents(
        rawJson: String,
        authorizedIncidentIds: Set<String>,
        explicitlyCreatedEmptyIncidentIds: Set<String> = emptySet(),
    ): List<ExportIncident> {
        val visibleIds = authorizedIncidentIds + explicitlyCreatedEmptyIncidentIds
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id")?.takeIf { it in visibleIds } ?: return@mapNotNull null
            ExportIncident(
                id = id,
                name = item.string("name")?.takeIf(String::isNotBlank) ?: id,
                description = item.string("description")?.takeIf(String::isNotBlank),
                createdAt = item.double("created_at")?.takeIf(Double::isFinite),
                updatedAt = item.double("updated_at")?.takeIf(Double::isFinite),
            )
        }.sortedByDescending { it.updatedAt ?: it.createdAt ?: 0.0 }
    }

    fun parseIncidentMutationId(rawJson: String): String {
        val root = parseObject(rawJson) ?: error("Frigate returned an invalid Incident response")
        return sequenceOf("id", "case_id", "export_case_id")
            .mapNotNull { key -> root.string(key) }
            .firstOrNull(String::isNotBlank)
            ?: error("Frigate did not return an Incident ID")
    }

    fun parseOnDemandRecordingStart(rawJson: String): OnDemandRecordingStart {
        val root = parseObject(rawJson) ?: error("Frigate returned an invalid recording response")
        if (root.bool("success") != true) error("Frigate could not start recording")
        val eventId = root.string("event_id")?.takeIf(String::isNotBlank)
            ?: error("Frigate did not return a recording event ID")
        return OnDemandRecordingStart(eventId, root.string("message")?.takeIf(String::isNotBlank))
    }

    private fun parseObject(rawJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
}
