package app.opah.tv.awareness

import app.opah.tv.data.model.RealtimeReviewUpdate
import app.opah.tv.data.model.ReviewLifecycle
import app.opah.tv.data.model.ReviewSeverity

/** Maps a bounded Frigate snapshot without treating omitted realtime fields as authoritative clears. */
fun RealtimeReviewUpdate.toAwarenessObservation(
    profileKey: String,
    observationId: String,
    receivedAtEpochMillis: Long,
    sourceRevision: Long? = null,
): AwarenessRealtimeObservation {
    val item = after
    return AwarenessRealtimeObservation(
        reviewId = item.id,
        updateKind = lifecycle.toAwarenessKind(),
        rawUpdateKind = rawLifecycle,
        metadata = AwarenessObservationMetadata(
            profileKey = profileKey,
            observationId = observationId,
            observedAtEpochMillis = receivedAtEpochMillis,
            receivedAtEpochMillis = receivedAtEpochMillis,
            sourceRevision = sourceRevision,
        ),
        camera = AwarenessObservedField.Present(item.camera),
        severity = AwarenessObservedField.Present(
            AwarenessSeverityEvidence(item.severity.toAwarenessSeverity(), item.rawSeverity),
        ),
        startEpochSeconds = AwarenessObservedField.Present(item.startTime),
        endEpochSeconds = when {
            item.endTime != null || lifecycle == ReviewLifecycle.END -> AwarenessObservedField.Present(item.endTime)
            else -> AwarenessObservedField.Absent
        },
        objects = item.objects.presentOnlyWhenNonEmpty(),
        zones = item.zones.presentOnlyWhenNonEmpty(),
        audio = item.audio.presentOnlyWhenNonEmpty(),
        subLabels = item.subLabels.presentOnlyWhenNonEmpty(),
        detectionIds = item.detectionIds.presentOnlyWhenNonEmpty(),
        summary = item.summary?.let { summary ->
            AwarenessObservedField.Present(
                AwarenessSummary(
                    title = summary.title,
                    shortSummary = summary.shortSummary,
                    scene = summary.scene,
                    otherConcerns = summary.otherConcerns.toSet(),
                ),
            )
        } ?: AwarenessObservedField.Absent,
        threatLevel = item.summary?.potentialThreatLevel?.let {
            AwarenessObservedField.Present<Int?>(it)
        }
            ?: AwarenessObservedField.Absent,
        thumbnail = item.thumbnailPath?.let {
            AwarenessObservedField.Present<String?>(it)
        }
            ?: AwarenessObservedField.Absent,
        reviewed = item.hasBeenReviewed?.let {
            AwarenessObservedField.Present<Boolean?>(it)
        }
            ?: AwarenessObservedField.Absent,
    )
}

private fun ReviewLifecycle.toAwarenessKind(): AwarenessRealtimeUpdateKind = when (this) {
    ReviewLifecycle.NEW -> AwarenessRealtimeUpdateKind.NEW
    ReviewLifecycle.UPDATE -> AwarenessRealtimeUpdateKind.UPDATE
    ReviewLifecycle.END -> AwarenessRealtimeUpdateKind.END
    ReviewLifecycle.GENAI -> AwarenessRealtimeUpdateKind.GENAI
    ReviewLifecycle.UNKNOWN -> AwarenessRealtimeUpdateKind.UNKNOWN
}

private fun ReviewSeverity.toAwarenessSeverity(): AwarenessReviewSeverity = when (this) {
    ReviewSeverity.ALERT -> AwarenessReviewSeverity.ALERT
    ReviewSeverity.DETECTION -> AwarenessReviewSeverity.DETECTION
    ReviewSeverity.SIGNIFICANT_MOTION -> AwarenessReviewSeverity.SIGNIFICANT_MOTION
    ReviewSeverity.UNKNOWN -> AwarenessReviewSeverity.UNKNOWN
}

private fun Set<String>.presentOnlyWhenNonEmpty(): AwarenessObservedField<Set<String>> =
    if (isEmpty()) AwarenessObservedField.Absent else AwarenessObservedField.Present(this)
