package app.opah.tv.briefing

import app.opah.tv.data.model.ReviewItem

enum class BriefingAudience {
    OWNER,
    GUEST,
}

@JvmInline
value class BriefingScopeKey(val value: String) {
    init {
        require(value.matches(Regex("h1:[0-9a-f]{64}")))
    }
}

enum class BriefingAcknowledgementReason {
    DISMISSED,
    HIGHLIGHT_COMPLETED,
    EXPLICIT_SKIP,
    MARKED_REVIEWED,
}

data class BriefingQueryMetadata(
    val lastSuccessfulQueryAtEpochMillis: Long,
    val lowerBoundEpochMillis: Long,
    val upperBoundEpochMillis: Long,
    val capped: Boolean,
    val privacySchemaVersion: Int,
    val privacyEpoch: Long,
) {
    init {
        require(lastSuccessfulQueryAtEpochMillis >= 0L)
        require(lowerBoundEpochMillis in 0L..upperBoundEpochMillis)
        require(upperBoundEpochMillis <= lastSuccessfulQueryAtEpochMillis)
        require(privacySchemaVersion > 0)
        require(privacyEpoch >= 0L)
    }
}

data class BriefingAcknowledgement(
    val reviewId: String,
    val contentVersion: String,
    val reason: BriefingAcknowledgementReason,
    val acknowledgedAtEpochMillis: Long,
    val retainUntilEpochMillis: Long,
) {
    init {
        requireSafeBriefingId(reviewId)
        require(contentVersion.matches(Regex("v1:[0-9a-f]{64}")))
        require(acknowledgedAtEpochMillis >= 0L)
        require(retainUntilEpochMillis >= acknowledgedAtEpochMillis)
    }
}

data class CompletedBriefingHighlight(
    val reviewId: String,
    val completedAtEpochMillis: Long,
    val retainUntilEpochMillis: Long,
) {
    init {
        requireSafeBriefingId(reviewId)
        require(completedAtEpochMillis >= 0L)
        require(retainUntilEpochMillis >= completedAtEpochMillis)
    }
}

data class BriefingStoredCandidate(
    val item: ReviewItem,
    val contentVersion: String,
) {
    init {
        requireSafeBriefingId(item.id)
        require(item.camera.isNotBlank() && item.camera.length <= 256 && item.camera.none(Char::isISOControl))
        require(item.startTime.isFinite() && item.startTime >= 0.0)
        require(contentVersion.matches(Regex("v1:[0-9a-f]{64}")))
    }
}

data class BriefingDismissalTarget(
    val reviewId: String,
    val cameraId: String,
    val contentVersion: String,
    val containsRecognition: Boolean,
) {
    init {
        requireSafeBriefingId(reviewId)
        require(cameraId.isNotBlank() && cameraId.length <= 256 && cameraId.none(Char::isISOControl))
        require(contentVersion.matches(Regex("v1:[0-9a-f]{64}")))
    }
}

data class BriefingStoreSnapshot(
    val metadata: BriefingQueryMetadata?,
    val candidates: List<BriefingStoredCandidate>,
    val acknowledgements: Map<String, BriefingAcknowledgement>,
    val completedHighlights: Map<String, CompletedBriefingHighlight>,
)

interface BriefingStore {
    suspend fun read(profileKey: String, scopeKey: BriefingScopeKey): BriefingStoreSnapshot

    suspend fun commitQuery(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        metadata: BriefingQueryMetadata,
        candidates: List<BriefingStoredCandidate>,
        retentionLowerBoundEpochMillis: Long,
    )

    suspend fun acknowledge(
        profileKey: String,
        scopeKey: BriefingScopeKey,
        acknowledgements: List<BriefingAcknowledgement>,
        completedHighlights: List<CompletedBriefingHighlight> = emptyList(),
        nowEpochMillis: Long,
    )

    suspend fun deleteProfile(profileKey: String)
}

internal fun requireSafeBriefingId(value: String) {
    require(value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl))
}

internal const val BRIEFING_INITIAL_LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L
internal const val BRIEFING_MAX_LOOKBACK_MILLIS = 7L * 24L * 60L * 60L * 1_000L
internal const val BRIEFING_QUERY_OVERLAP_MILLIS = 15L * 60L * 1_000L
internal const val BRIEFING_RETENTION_MARGIN_MILLIS = 24L * 60L * 60L * 1_000L
internal const val BRIEFING_MAX_CANDIDATES = 500
internal const val BRIEFING_MAX_PRESENTED = 20
