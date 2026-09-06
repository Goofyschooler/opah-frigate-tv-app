package app.opah.tv.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.opah.tv.awareness.AwarenessReviewSeverity
import app.opah.tv.notifications.AlertDayOfWeek
import app.opah.tv.notifications.AlertMode
import app.opah.tv.notifications.AlertPolicy
import app.opah.tv.notifications.AlertScheduleWindow
import app.opah.tv.notifications.AlertSnooze
import app.opah.tv.notifications.AlertSnoozeScope
import app.opah.tv.notifications.NotificationPrivacy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class AlertConfigurationRecord(
    val profileKey: String,
    val enabled: Boolean,
    val policyVersion: Long,
    val policy: AlertPolicy,
    val snoozes: List<AlertSnooze>,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(profileKey.isNotBlank() && profileKey.length <= 128 && profileKey.none(Char::isISOControl))
        require(policyVersion >= 0)
        require(snoozes.size <= 512)
        require(updatedAtEpochMillis >= 0)
    }
}

sealed interface AlertConfigurationReadResult {
    data object Missing : AlertConfigurationReadResult
    data class Available(val record: AlertConfigurationRecord) : AlertConfigurationReadResult
    data object Corrupt : AlertConfigurationReadResult
}

interface AlertConfigurationStore {
    suspend fun read(profileKey: String): AlertConfigurationReadResult
    suspend fun write(record: AlertConfigurationRecord)
    suspend fun delete(profileKey: String)
}

@Entity(tableName = "alert_configuration")
data class AlertConfigurationEntity(
    @PrimaryKey val profileKey: String,
    val enabled: Boolean,
    val policyVersion: Long,
    val mode: String,
    val significantMotionEnabled: Boolean,
    val cameraIdsJson: String,
    val labelsJson: String,
    val zonesJson: String,
    val subLabelsJson: String,
    val plateLabelsJson: String,
    val minimumThreatLevel: Int?,
    val frigateModesJson: String,
    val customSeveritiesJson: String,
    val schedulesJson: String,
    val notificationPrivacy: String,
    val snoozesJson: String,
    val updatedAtEpochMillis: Long,
)

@Dao
interface AlertConfigurationDao {
    @Query("SELECT * FROM alert_configuration WHERE profileKey = :profileKey LIMIT 1")
    suspend fun record(profileKey: String): AlertConfigurationEntity?

    @Upsert
    suspend fun upsert(entity: AlertConfigurationEntity)

    @Query("DELETE FROM alert_configuration WHERE profileKey = :profileKey")
    suspend fun delete(profileKey: String)
}

class RoomAlertConfigurationStore(
    private val dao: AlertConfigurationDao,
    private val json: Json = Json,
) : AlertConfigurationStore {
    override suspend fun read(profileKey: String): AlertConfigurationReadResult {
        val entity = dao.record(profileKey) ?: return AlertConfigurationReadResult.Missing
        return runCatching { entity.toDomain(json) }
            .fold(AlertConfigurationReadResult::Available) { AlertConfigurationReadResult.Corrupt }
    }

    override suspend fun write(record: AlertConfigurationRecord) {
        dao.upsert(record.toEntity(json))
    }

    override suspend fun delete(profileKey: String) = dao.delete(profileKey)
}

private fun AlertConfigurationRecord.toEntity(json: Json) = AlertConfigurationEntity(
    profileKey = profileKey,
    enabled = enabled,
    policyVersion = policyVersion,
    mode = policy.mode.name,
    significantMotionEnabled = policy.significantMotionEnabled,
    cameraIdsJson = json.encodeStrings(policy.cameraIds),
    labelsJson = json.encodeStrings(policy.labels),
    zonesJson = json.encodeStrings(policy.zones),
    subLabelsJson = json.encodeStrings(policy.subLabels),
    plateLabelsJson = json.encodeStrings(policy.plateLabels),
    minimumThreatLevel = policy.minimumThreatLevel,
    frigateModesJson = json.encodeStrings(policy.frigateModes),
    customSeveritiesJson = json.encodeStrings(policy.customSeverities.map(Enum<*>::name).toSet()),
    schedulesJson = JsonArray(policy.schedules.map { schedule ->
        JsonObject(
            mapOf(
                "days" to JsonArray(schedule.days.map { JsonPrimitive(it.name) }.sortedBy(JsonPrimitive::content)),
                "start" to JsonPrimitive(schedule.startMinuteInclusive),
                "end" to JsonPrimitive(schedule.endMinuteExclusive),
            ),
        )
    }).toString(),
    notificationPrivacy = policy.notificationPrivacy.name,
    snoozesJson = JsonArray(snoozes.map(AlertSnooze::toJson)).toString(),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AlertConfigurationEntity.toDomain(json: Json): AlertConfigurationRecord =
    AlertConfigurationRecord(
        profileKey = profileKey,
        enabled = enabled,
        policyVersion = policyVersion,
        policy = AlertPolicy(
            mode = enumValueOf(mode),
            significantMotionEnabled = significantMotionEnabled,
            cameraIds = json.decodeStrings(cameraIdsJson),
            labels = json.decodeStrings(labelsJson),
            zones = json.decodeStrings(zonesJson),
            subLabels = json.decodeStrings(subLabelsJson),
            plateLabels = json.decodeStrings(plateLabelsJson),
            minimumThreatLevel = minimumThreatLevel,
            frigateModes = json.decodeStrings(frigateModesJson),
            customSeverities = json.decodeStrings(customSeveritiesJson).mapTo(linkedSetOf(), ::enumValueOf),
            schedules = json.decodeSchedules(schedulesJson),
            notificationPrivacy = enumValueOf(notificationPrivacy),
        ),
        snoozes = json.decodeSnoozes(snoozesJson),
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun Json.encodeStrings(values: Set<String>): String =
    JsonArray(values.sorted().map(::JsonPrimitive)).toString()

private fun Json.decodeStrings(encoded: String): Set<String> {
    require(encoded.length <= MAX_JSON_CHARS)
    val array = parseToJsonElement(encoded) as? JsonArray ?: error("Expected array")
    require(array.size <= MAX_ARRAY_ITEMS)
    return array.mapTo(linkedSetOf()) { element ->
        val value = (element as? JsonPrimitive)?.contentOrNull ?: error("Expected string")
        require(value.isNotBlank() && value.length <= MAX_TEXT_CHARS && value.none(Char::isISOControl))
        value
    }
}

private fun Json.decodeSchedules(encoded: String): List<AlertScheduleWindow> {
    require(encoded.length <= MAX_JSON_CHARS)
    val array = parseToJsonElement(encoded) as? JsonArray ?: error("Expected schedules")
    require(array.size <= MAX_SCHEDULES)
    return array.map { element ->
        val item = element as? JsonObject ?: error("Expected schedule")
        val days = (item["days"] as? JsonArray)?.mapTo(linkedSetOf()) {
            enumValueOf<AlertDayOfWeek>(it.jsonPrimitive.content)
        } ?: error("Expected days")
        AlertScheduleWindow(
            days = days,
            startMinuteInclusive = item["start"]?.jsonPrimitive?.intOrNull ?: error("Expected start"),
            endMinuteExclusive = item["end"]?.jsonPrimitive?.intOrNull ?: error("Expected end"),
        )
    }
}

private fun AlertSnooze.toJson(): JsonObject = JsonObject(
    mapOf(
        "scope" to JsonPrimitive(scope.name),
        "cameras" to JsonArray(cameraIds.sorted().map(::JsonPrimitive)),
        "expires" to (expiresAtEpochMillis?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?)),
        "mode" to (untilModeChangesFrom?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?)),
    ),
)

private fun Json.decodeSnoozes(encoded: String): List<AlertSnooze> {
    require(encoded.length <= MAX_JSON_CHARS)
    val array = parseToJsonElement(encoded) as? JsonArray ?: error("Expected snoozes")
    require(array.size <= MAX_SNOOZES)
    return array.map { element ->
        val item = element as? JsonObject ?: error("Expected snooze")
        AlertSnooze(
            scope = enumValueOf(item["scope"]?.jsonPrimitive?.content ?: error("Expected scope")),
            cameraIds = (item["cameras"] as? JsonArray)?.mapTo(linkedSetOf()) {
                it.jsonPrimitive.content
            } ?: error("Expected cameras"),
            expiresAtEpochMillis = item["expires"]?.jsonPrimitive?.longOrNull,
            untilModeChangesFrom = item["mode"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

private const val MAX_JSON_CHARS = 262_144
private const val MAX_ARRAY_ITEMS = 512
private const val MAX_SCHEDULES = 64
private const val MAX_SNOOZES = 512
private const val MAX_TEXT_CHARS = 256
