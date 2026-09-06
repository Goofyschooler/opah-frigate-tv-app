package app.opah.tv.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.opah.tv.briefing.BRIEFING_MAX_CANDIDATES
import app.opah.tv.briefing.BriefingAcknowledgement
import app.opah.tv.briefing.BriefingAcknowledgementReason
import app.opah.tv.briefing.BriefingQueryMetadata
import app.opah.tv.briefing.BriefingScopeKey
import app.opah.tv.briefing.BriefingStore
import app.opah.tv.briefing.BriefingStoreSnapshot
import app.opah.tv.briefing.BriefingStoredCandidate
import app.opah.tv.briefing.CompletedBriefingHighlight
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.ReviewSummaryMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Entity(tableName = "briefing_scope", primaryKeys = ["profileKey", "scopeKey"])
data class BriefingScopeEntity(
    val profileKey: String,
    val scopeKey: String,
    val lastSuccessfulQueryAtEpochMillis: Long,
    val lowerBoundEpochMillis: Long,
    val upperBoundEpochMillis: Long,
    val capped: Boolean,
    val privacySchemaVersion: Int,
    val privacyEpoch: Long,
)

@Entity(
    tableName = "briefing_candidate",
    primaryKeys = ["profileKey", "scopeKey", "reviewId"],
    indices = [Index(value = ["profileKey", "scopeKey", "startEpochMillis"])],
)
data class BriefingCandidateEntity(
    val profileKey: String,
    val scopeKey: String,
    val reviewId: String,
    val cameraId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val severity: String,
    val rawSeverity: String?,
    val objectsJson: String,
    val zonesJson: String,
    val audioJson: String,
    val detectionIdsJson: String,
    val subLabelsJson: String,
    val reviewed: Boolean,
    val summaryTitle: String?,
    val summaryShort: String?,
    val threatLevel: Int?,
    val concernsJson: String,
    val contentVersion: String,
)

@Entity(
    tableName = "briefing_acknowledgement",
    primaryKeys = ["profileKey", "scopeKey", "reviewId"],
    indices = [Index(value = ["profileKey", "scopeKey", "retainUntilEpochMillis"])],
)
data class BriefingAcknowledgementEntity(
    val profileKey: String,
    val scopeKey: String,
    val reviewId: String,
    val contentVersion: String,
    val reason: String,
    val acknowledgedAtEpochMillis: Long,
    val retainUntilEpochMillis: Long,
)

@Entity(
    tableName = "briefing_completed_highlight",
    primaryKeys = ["profileKey", "scopeKey", "reviewId"],
    indices = [Index(value = ["profileKey", "scopeKey", "retainUntilEpochMillis"])],
)
data class BriefingCompletedHighlightEntity(
    val profileKey: String,
    val scopeKey: String,
    val reviewId: String,
    val completedAtEpochMillis: Long,
    val retainUntilEpochMillis: Long,
)

@Dao
interface BriefingDao {
    @Query("SELECT * FROM briefing_scope WHERE profileKey = :profileKey AND scopeKey = :scopeKey LIMIT 1")
    suspend fun scope(profileKey: String, scopeKey: String): BriefingScopeEntity?

    @Query(
        "SELECT * FROM briefing_candidate WHERE profileKey = :profileKey AND scopeKey = :scopeKey " +
            "ORDER BY startEpochMillis DESC, reviewId ASC LIMIT :limit",
    )
    suspend fun candidates(profileKey: String, scopeKey: String, limit: Int): List<BriefingCandidateEntity>

    @Query("SELECT * FROM briefing_acknowledgement WHERE profileKey = :profileKey AND scopeKey = :scopeKey")
    suspend fun acknowledgements(
        profileKey: String,
        scopeKey: String,
    ): List<BriefingAcknowledgementEntity>

    @Query("SELECT * FROM briefing_completed_highlight WHERE profileKey = :profileKey AND scopeKey = :scopeKey")
    suspend fun completedHighlights(
        profileKey: String,
        scopeKey: String,
    ): List<BriefingCompletedHighlightEntity>

    @Upsert
    suspend fun upsertScope(entity: BriefingScopeEntity)

    @Upsert
    suspend fun upsertCandidates(entities: List<BriefingCandidateEntity>)

    @Upsert
    suspend fun upsertAcknowledgements(entities: List<BriefingAcknowledgementEntity>)

    @Upsert
    suspend fun upsertCompletedHighlights(entities: List<BriefingCompletedHighlightEntity>)

    @Query(
        "DELETE FROM briefing_candidate WHERE profileKey = :profileKey AND scopeKey = :scopeKey " +
            "AND startEpochMillis BETWEEN :lowerBoundEpochMillis AND :upperBoundEpochMillis",
    )
    suspend fun deleteCandidateWindow(
        profileKey: String,
        scopeKey: String,
        lowerBoundEpochMillis: Long,
        upperBoundEpochMillis: Long,
    )

    @Query(
        "DELETE FROM briefing_candidate WHERE profileKey = :profileKey AND scopeKey = :scopeKey " +
            "AND startEpochMillis < :retentionLowerBoundEpochMillis",
    )
    suspend fun pruneCandidates(
        profileKey: String,
        scopeKey: String,
        retentionLowerBoundEpochMillis: Long,
    )

    @Query(
        "DELETE FROM briefing_acknowledgement WHERE profileKey = :profileKey AND scopeKey = :scopeKey " +
            "AND retainUntilEpochMillis < :nowEpochMillis",
    )
    suspend fun pruneAcknowledgements(profileKey: String, scopeKey: String, nowEpochMillis: Long)

    @Query(
        "DELETE FROM briefing_completed_highlight WHERE profileKey = :profileKey AND scopeKey = :scopeKey " +
            "AND retainUntilEpochMillis < :nowEpochMillis",
    )
    suspend fun pruneCompletedHighlights(profileKey: String, scopeKey: String, nowEpochMillis: Long)

    @Query("DELETE FROM briefing_scope WHERE profileKey = :profileKey")
    suspend fun deleteProfileScopes(profileKey: String)

    @Query("DELETE FROM briefing_candidate WHERE profileKey = :profileKey")
    suspend fun deleteProfileCandidates(profileKey: String)

    @Query("DELETE FROM briefing_acknowledgement WHERE profileKey = :profileKey")
    suspend fun deleteProfileAcknowledgements(profileKey: String)

    @Query("DELETE FROM briefing_completed_highlight WHERE profileKey = :profileKey")
    suspend fun deleteProfileCompletedHighlights(profileKey: String)

    @Transaction
    suspend fun commitQuery(
        scope: BriefingScopeEntity,
        candidates: List<BriefingCandidateEntity>,
        retentionLowerBoundEpochMillis: Long,
    ) {
        deleteCandidateWindow(
            scope.profileKey,
            scope.scopeKey,
            scope.lowerBoundEpochMillis,
            scope.upperBoundEpochMillis,
        )
        if (candidates.isNotEmpty()) upsertCandidates(candidates)
        pruneCandidates(scope.profileKey, scope.scopeKey, retentionLowerBoundEpochMillis)
        pruneAcknowledgements(scope.profileKey, scope.scopeKey, scope.lastSuccessfulQueryAtEpochMillis)
        pruneCompletedHighlights(scope.profileKey, scope.scopeKey, scope.lastSuccessfulQueryAtEpochMillis)
        upsertScope(scope)
    }

    @Transaction
    suspend fun acknowledge(
        acknowledgements: List<BriefingAcknowledgementEntity>,
        completed: List<BriefingCompletedHighlightEntity>,
        profileKey: String,
        scopeKey: String,
        nowEpochMillis: Long,
    ) {
        if (acknowledgements.isNotEmpty()) upsertAcknowledgements(acknowledgements)
        if (completed.isNotEmpty()) upsertCompletedHighlights(completed)
        pruneAcknowledgements(profileKey, scopeKey, nowEpochMillis)
        pruneCompletedHighlights(profileKey, scopeKey, nowEpochMillis)
    }

    @Transaction
    suspend fun deleteProfile(profileKey: String) {
        deleteProfileCompletedHighlights(profileKey)
        deleteProfileAcknowledgements(profileKey)
        deleteProfileCandidates(profileKey)
        deleteProfileScopes(profileKey)
    }
}

class RoomBriefingStore(
    private val dao: BriefingDao,
    private val json: Json = Json,
) : BriefingStore {
    override suspend fun read(profileKey: String, scopeKey: BriefingScopeKey): BriefingStoreSnapshot {
        requireSafeProfileKey(profileKey)
        val scope = dao.scope(profileKey, scopeKey.value)
        val candidates = dao.candidates(profileKey, scopeKey.value, BRIEFING_MAX_CANDIDATES)
            .mapNotNull { runCatching { it.toDomain(json) }.getOrNull() }
        val acknowledgements = dao.acknowledgements(profileKey, scopeKey.value)
            .mapNotNull { runCatching { it.toDomain() }.getOrNull() }
            .associateBy(BriefingAcknowledgement::reviewId)
        val completed = dao.completedHighlights(profileKey, scopeKey.value)
            .mapNotNull { runCatching { it.toDomain() }.getOrNull() }
            .associateBy(CompletedBriefingHighlight::reviewId)
        return BriefingStoreSnapshot(scope?.toDomain(), candidates, acknowledgements, completed)
    }

    override suspend fun commitQuery(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        metadata: BriefingQueryMetadata,
        candidates: List<BriefingStoredCandidate>,
        retentionLowerBoundEpochMillis: Long,
    ) {
        requireSafeProfileKey(profileKey)
        require(candidates.size <= BRIEFING_MAX_CANDIDATES)
        require(retentionLowerBoundEpochMillis in 0L..metadata.lowerBoundEpochMillis)
        dao.commitQuery(
            metadata.toEntity(profileKey, scopeKey),
            candidates.distinctBy { it.item.id }.map { it.toEntity(profileKey, scopeKey, json) },
            retentionLowerBoundEpochMillis,
        )
    }

    override suspend fun acknowledge(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        acknowledgements: List<BriefingAcknowledgement>,
        completedHighlights: List<CompletedBriefingHighlight>,
        nowEpochMillis: Long,
    ) {
        requireSafeProfileKey(profileKey)
        require(acknowledgements.size <= BRIEFING_MAX_CANDIDATES)
        require(completedHighlights.size <= BRIEFING_MAX_CANDIDATES)
        require(nowEpochMillis >= 0L)
        dao.acknowledge(
            acknowledgements.distinctBy(BriefingAcknowledgement::reviewId)
                .map { it.toEntity(profileKey, scopeKey) },
            completedHighlights.distinctBy(CompletedBriefingHighlight::reviewId)
                .map { it.toEntity(profileKey, scopeKey) },
            profileKey,
            scopeKey.value,
            nowEpochMillis,
        )
    }

    override suspend fun deleteProfile(profileKey: String) {
        requireSafeProfileKey(profileKey)
        dao.deleteProfile(profileKey)
    }
}

private fun BriefingQueryMetadata.toEntity(profileKey: String, scopeKey: BriefingScopeKey) =
    BriefingScopeEntity(
        profileKey,
        scopeKey.value,
        lastSuccessfulQueryAtEpochMillis,
        lowerBoundEpochMillis,
        upperBoundEpochMillis,
        capped,
        privacySchemaVersion,
        privacyEpoch,
    )

private fun BriefingScopeEntity.toDomain() = BriefingQueryMetadata(
    lastSuccessfulQueryAtEpochMillis,
    lowerBoundEpochMillis,
    upperBoundEpochMillis,
    capped,
    privacySchemaVersion,
    privacyEpoch,
)

private fun BriefingStoredCandidate.toEntity(
    profileKey: String,
    scopeKey: BriefingScopeKey,
    json: Json,
) = BriefingCandidateEntity(
    profileKey = profileKey,
    scopeKey = scopeKey.value,
    reviewId = item.id,
    cameraId = item.camera,
    startEpochMillis = item.startTime.toEpochMillis(),
    endEpochMillis = item.endTime?.toEpochMillis(),
    severity = item.severity.name,
    rawSeverity = item.rawSeverity,
    objectsJson = json.encodeStrings(item.objects),
    zonesJson = json.encodeStrings(item.zones),
    audioJson = json.encodeStrings(item.audio),
    detectionIdsJson = json.encodeStrings(item.detectionIds),
    subLabelsJson = json.encodeStrings(item.subLabels),
    reviewed = item.hasBeenReviewed,
    summaryTitle = item.summary?.title,
    summaryShort = item.summary?.shortSummary,
    threatLevel = item.summary?.potentialThreatLevel,
    concernsJson = json.encodeStrings(item.summary?.otherConcerns.orEmpty()),
    contentVersion = contentVersion,
)

private fun BriefingCandidateEntity.toDomain(json: Json): BriefingStoredCandidate =
    BriefingStoredCandidate(
        item = ReviewItem(
            id = reviewId,
            camera = cameraId,
            startTime = startEpochMillis / 1_000.0,
            endTime = endEpochMillis?.div(1_000.0),
            severity = enumValueOf<ReviewSeverity>(severity),
            objects = json.decodeStrings(objectsJson),
            zones = json.decodeStrings(zonesJson),
            hasBeenReviewed = reviewed,
            audio = json.decodeStrings(audioJson),
            detectionIds = json.decodeStrings(detectionIdsJson),
            subLabels = json.decodeStrings(subLabelsJson),
            summary = if (
                summaryTitle != null || summaryShort != null || threatLevel != null ||
                concernsJson != "[]"
            ) {
                ReviewSummaryMetadata(
                    title = summaryTitle,
                    shortSummary = summaryShort,
                    potentialThreatLevel = threatLevel,
                    otherConcerns = json.decodeStrings(concernsJson),
                )
            } else {
                null
            },
            rawSeverity = rawSeverity,
        ),
        contentVersion = contentVersion,
    )

private fun BriefingAcknowledgement.toEntity(profileKey: String, scopeKey: BriefingScopeKey) =
    BriefingAcknowledgementEntity(
        profileKey,
        scopeKey.value,
        reviewId,
        contentVersion,
        reason.name,
        acknowledgedAtEpochMillis,
        retainUntilEpochMillis,
    )

private fun BriefingAcknowledgementEntity.toDomain() = BriefingAcknowledgement(
    reviewId,
    contentVersion,
    enumValueOf<BriefingAcknowledgementReason>(reason),
    acknowledgedAtEpochMillis,
    retainUntilEpochMillis,
)

private fun CompletedBriefingHighlight.toEntity(profileKey: String, scopeKey: BriefingScopeKey) =
    BriefingCompletedHighlightEntity(
        profileKey,
        scopeKey.value,
        reviewId,
        completedAtEpochMillis,
        retainUntilEpochMillis,
    )

private fun BriefingCompletedHighlightEntity.toDomain() = CompletedBriefingHighlight(
    reviewId,
    completedAtEpochMillis,
    retainUntilEpochMillis,
)

private fun Json.encodeStrings(values: Collection<String>): String = JsonArray(
    values.distinct().sorted().map(::JsonPrimitive),
).toString()

private fun Json.decodeStrings(raw: String): List<String> =
    (parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull {
        it.jsonPrimitive.contentOrNull
    }.also { values ->
        require(values.size <= 256)
        require(values.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
    }

private fun Double.toEpochMillis(): Long {
    require(isFinite() && this >= 0.0)
    return (this * 1_000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
}

private fun requireSafeProfileKey(profileKey: String) {
    require(profileKey.isNotBlank() && profileKey.length <= 128 && profileKey.none(Char::isISOControl))
}
