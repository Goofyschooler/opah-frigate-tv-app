package app.opah.tv.awareness

import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.realtime.AuthorizedReviewBatchSink
import app.opah.tv.data.realtime.ReconcileAuthorizedReviews

class AwarenessAuthorizedReviewBatchSink(
    private val owner: ProcessAwarenessOwner,
) : AuthorizedReviewBatchSink {
    override fun apply(
        command: ReconcileAuthorizedReviews,
        reviews: List<ReviewItem>,
        receivedAtEpochMillis: Long,
    ): Boolean {
        val profileKey = owner.state.value.ledger.profileKey ?: return false
        val observations = reviews.mapIndexed { index, review ->
            review.toAwarenessSnapshot(
                profileKey = profileKey,
                observationId = "rest:${command.operationId.value}:$index",
                observedAtEpochMillis = command.rangeEndEpochMillis,
                receivedAtEpochMillis = receivedAtEpochMillis,
                sourceRevision = index.toLong(),
            )
        }
        return owner.applyAuthoritative(
            generation = command.generation,
            scopeEpoch = command.scopeToken.epoch,
            observations = observations,
        )
    }
}

fun ReviewItem.toAwarenessSnapshot(
    profileKey: String,
    observationId: String,
    observedAtEpochMillis: Long,
    receivedAtEpochMillis: Long,
    sourceRevision: Long? = null,
): AwarenessAuthoritativeSnapshot = AwarenessAuthoritativeSnapshot(
    reviewId = id,
    camera = camera,
    lifecycle = if (endTime == null) AwarenessReviewLifecycle.ACTIVE else AwarenessReviewLifecycle.ENDED,
    severity = AwarenessSeverityEvidence(severity.toAwarenessSeverity(), rawSeverity),
    startEpochSeconds = startTime,
    endEpochSeconds = endTime,
    objects = objects.toSet(),
    zones = zones.toSet(),
    audio = audio.toSet(),
    subLabels = subLabels.toSet(),
    detectionIds = detectionIds.toSet(),
    summary = summary?.let {
        AwarenessSummary(it.title, it.shortSummary, it.scene, it.otherConcerns.toSet())
    },
    threatLevel = summary?.potentialThreatLevel,
    thumbnail = thumbnailPath,
    reviewed = hasBeenReviewed,
    metadata = AwarenessObservationMetadata(
        profileKey = profileKey,
        observationId = observationId,
        observedAtEpochMillis = observedAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        sourceRevision = sourceRevision,
    ),
)

private fun ReviewSeverity.toAwarenessSeverity(): AwarenessReviewSeverity = when (this) {
    ReviewSeverity.ALERT -> AwarenessReviewSeverity.ALERT
    ReviewSeverity.DETECTION -> AwarenessReviewSeverity.DETECTION
    ReviewSeverity.SIGNIFICANT_MOTION -> AwarenessReviewSeverity.SIGNIFICANT_MOTION
    ReviewSeverity.UNKNOWN -> AwarenessReviewSeverity.UNKNOWN
}
