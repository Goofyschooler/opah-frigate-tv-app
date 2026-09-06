package app.opah.tv.awareness

internal object AwarenessRecordBounds {
    const val MAX_PROFILE_KEY_CHARS = 128
    const val MAX_ID_CHARS = 256
    const val MAX_INPUT_TEXT_CHARS = 4_096
    const val MAX_INPUT_LABEL_CHARS = 512
    const val MAX_INPUT_SET_ITEMS = 256
    const val MAX_OBSERVATION_INPUT_CHARS = 262_144
    const val MAX_LABEL_CHARS = 128
    const val MAX_STORED_SET_ITEMS = 128
    const val MAX_RAW_VALUE_CHARS = 128
    const val MAX_CAMERA_CHARS = 256
    const val MAX_TITLE_CHARS = 160
    const val MAX_SUMMARY_CHARS = 1_000
    const val MAX_SCENE_CHARS = 500
    const val MAX_THUMBNAIL_PATH_CHARS = 2_048
}

/**
 * The lifecycle of a Frigate Review as understood by Opah.
 *
 * Realtime message kinds are deliberately modeled separately. A GENAI message,
 * for example, can enrich an ended Review without changing this lifecycle.
 */
enum class AwarenessReviewLifecycle {
    ACTIVE,
    ENDED,
    UNKNOWN,
}

enum class AwarenessReviewSeverity {
    UNKNOWN,
    SIGNIFICANT_MOTION,
    DETECTION,
    ALERT,
}

enum class AwarenessRealtimeUpdateKind {
    NEW,
    UPDATE,
    END,
    GENAI,
    UNKNOWN,
}

enum class AwarenessObservationSource {
    REALTIME,
    AUTHORITATIVE_REST,
}

/**
 * Stable metadata used to order observations without depending on arrival order.
 *
 * [observedAtEpochMillis] is the primary total ordering key and must be fixed by
 * the adapter before reduction. [sourceRevision] is an optional tie-break only;
 * it is never compared as though it shared units with wall time. [observationId]
 * is both an idempotency key and the final deterministic tie-break.
 */
data class AwarenessObservationMetadata(
    val profileKey: String,
    val observationId: String,
    val observedAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sourceRevision: Long? = null,
) {
    init {
        profileKey.requireSafeIdentity("Profile key", AwarenessRecordBounds.MAX_PROFILE_KEY_CHARS)
        observationId.requireSafeIdentity("Observation ID", AwarenessRecordBounds.MAX_ID_CHARS)
        require(observedAtEpochMillis >= 0L) { "Observation time must not be negative" }
        require(receivedAtEpochMillis >= 0L) { "Receipt time must not be negative" }
        require(sourceRevision == null || sourceRevision >= 0L) {
            "Source revision must not be negative"
        }
    }
}

data class AwarenessSummary(
    val title: String? = null,
    val shortSummary: String? = null,
    val scene: String? = null,
    val otherConcerns: Set<String> = emptySet(),
) {
    init {
        title.requireBoundedInput("Summary title")
        shortSummary.requireBoundedInput("Short summary")
        scene.requireBoundedInput("Summary scene")
        otherConcerns.requireBoundedInputSet("Summary concerns")
    }
}

data class AwarenessSeverityEvidence(
    val severity: AwarenessReviewSeverity,
    val rawSeverity: String? = null,
) {
    init {
        rawSeverity.requireBoundedInput("Raw severity")
    }
}

/**
 * Explicit field presence for partial realtime evidence.
 *
 * [Present] may contain null, so adapters can distinguish an explicit null from
 * a field that was not carried by a realtime message.
 */
sealed interface AwarenessObservedField<out T> {
    data object Absent : AwarenessObservedField<Nothing>

    data class Present<T>(val value: T) : AwarenessObservedField<T>
}

/**
 * Partial, non-authoritative evidence received from Frigate's realtime channel.
 */
data class AwarenessRealtimeObservation(
    override val reviewId: String,
    val updateKind: AwarenessRealtimeUpdateKind,
    override val metadata: AwarenessObservationMetadata,
    val rawUpdateKind: String? = null,
    val camera: AwarenessObservedField<String> = AwarenessObservedField.Absent,
    val severity: AwarenessObservedField<AwarenessSeverityEvidence> = AwarenessObservedField.Absent,
    val startEpochSeconds: AwarenessObservedField<Double> = AwarenessObservedField.Absent,
    val endEpochSeconds: AwarenessObservedField<Double?> = AwarenessObservedField.Absent,
    val objects: AwarenessObservedField<Set<String>> = AwarenessObservedField.Absent,
    val zones: AwarenessObservedField<Set<String>> = AwarenessObservedField.Absent,
    val audio: AwarenessObservedField<Set<String>> = AwarenessObservedField.Absent,
    val subLabels: AwarenessObservedField<Set<String>> = AwarenessObservedField.Absent,
    val detectionIds: AwarenessObservedField<Set<String>> = AwarenessObservedField.Absent,
    val summary: AwarenessObservedField<AwarenessSummary?> = AwarenessObservedField.Absent,
    val threatLevel: AwarenessObservedField<Int?> = AwarenessObservedField.Absent,
    val thumbnail: AwarenessObservedField<String?> = AwarenessObservedField.Absent,
    val reviewed: AwarenessObservedField<Boolean?> = AwarenessObservedField.Absent,
) : AwarenessReviewObservation {
    init {
        reviewId.requireSafeIdentity("Review ID", AwarenessRecordBounds.MAX_ID_CHARS)
        rawUpdateKind.requireBoundedInput("Raw update kind")
        camera.presentValueOrNull()?.let { value ->
            require(value.isNotBlank()) { "Camera must not be blank when observed" }
            value.requireSafeDisplayIdentity("Camera")
        }
        startEpochSeconds.presentValueOrNull()?.requireEpochSeconds("Start time")
        when (endEpochSeconds) {
            AwarenessObservedField.Absent -> Unit
            is AwarenessObservedField.Present -> endEpochSeconds.value?.requireEpochSeconds("End time")
        }
        objects.presentValueOrNull()?.requireBoundedInputSet("Objects")
        zones.presentValueOrNull()?.requireBoundedInputSet("Zones")
        audio.presentValueOrNull()?.requireBoundedInputSet("Audio labels")
        subLabels.presentValueOrNull()?.requireBoundedInputSet("Sub-labels")
        detectionIds.presentValueOrNull()?.requireBoundedInputSet("Detection IDs")
        thumbnail.presentValueOrNull().requireBoundedInput("Thumbnail path")
        require(inputCharacterCount() <= AwarenessRecordBounds.MAX_OBSERVATION_INPUT_CHARS) {
            "Realtime observation exceeds its aggregate input bound"
        }
    }
}

/**
 * A complete REST representation used to reconcile partial realtime evidence.
 */
data class AwarenessAuthoritativeSnapshot(
    override val reviewId: String,
    val camera: String,
    val lifecycle: AwarenessReviewLifecycle,
    val severity: AwarenessSeverityEvidence,
    val startEpochSeconds: Double,
    val endEpochSeconds: Double?,
    val objects: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val audio: Set<String> = emptySet(),
    val subLabels: Set<String> = emptySet(),
    val detectionIds: Set<String> = emptySet(),
    val summary: AwarenessSummary? = null,
    val threatLevel: Int? = null,
    val thumbnail: String? = null,
    val reviewed: Boolean? = null,
    val rawLifecycle: String? = null,
    override val metadata: AwarenessObservationMetadata,
) : AwarenessReviewObservation {
    init {
        reviewId.requireSafeIdentity("Review ID", AwarenessRecordBounds.MAX_ID_CHARS)
        require(camera.isNotBlank()) { "Camera must not be blank" }
        camera.requireSafeDisplayIdentity("Camera")
        startEpochSeconds.requireEpochSeconds("Start time")
        endEpochSeconds?.requireEpochSeconds("End time")
        objects.requireBoundedInputSet("Objects")
        zones.requireBoundedInputSet("Zones")
        audio.requireBoundedInputSet("Audio labels")
        subLabels.requireBoundedInputSet("Sub-labels")
        detectionIds.requireBoundedInputSet("Detection IDs")
        thumbnail.requireBoundedInput("Thumbnail path")
        rawLifecycle.requireBoundedInput("Raw lifecycle")
        require(inputCharacterCount() <= AwarenessRecordBounds.MAX_OBSERVATION_INPUT_CHARS) {
            "Authoritative snapshot exceeds its aggregate input bound"
        }
    }
}

sealed interface AwarenessReviewObservation {
    val reviewId: String
    val metadata: AwarenessObservationMetadata
}

data class AwarenessReviewUpdateMetadata(
    val latestObservationId: String,
    val latestObservedAtEpochMillis: Long,
    val latestReceivedAtEpochMillis: Long,
    val latestSourceRevision: Long?,
    val latestSource: AwarenessObservationSource,
    val latestRealtimeUpdateKind: AwarenessRealtimeUpdateKind? = null,
    val latestRawRealtimeUpdateKind: String? = null,
    val latestObservedSeverity: AwarenessReviewSeverity? = null,
    val latestRawSeverity: String? = null,
    val latestAuthoritativeObservationId: String? = null,
)

data class AwarenessReview(
    val profileKey: String,
    val id: String,
    val camera: String?,
    val lifecycle: AwarenessReviewLifecycle,
    val rawLifecycle: String?,
    val severity: AwarenessReviewSeverity,
    val rawSeverity: String?,
    val startEpochSeconds: Double?,
    val endEpochSeconds: Double?,
    val objects: Set<String>,
    val zones: Set<String>,
    val audio: Set<String>,
    val subLabels: Set<String>,
    val detectionIds: Set<String>,
    val summary: AwarenessSummary?,
    val threatLevel: Int?,
    val thumbnail: String?,
    val reviewed: Boolean?,
    val updateMetadata: AwarenessReviewUpdateMetadata,
)

@ConsistentCopyVisibility
data class AwarenessState internal constructor(
    val profileKey: String? = null,
    val reviewsById: Map<String, AwarenessReview> = emptyMap(),
    internal val mergeStateById: Map<String, AwarenessReviewMergeState> = emptyMap(),
    internal val replayTombstonesById: Map<String, AwarenessReplayTombstone> = emptyMap(),
) {
    fun review(reviewId: String): AwarenessReview? = reviewsById[reviewId]

    /** Stable presentation order: newest start first, then ID. */
    fun reviewsNewestFirst(): List<AwarenessReview> = reviewsById.values.sortedWith(
        compareByDescending<AwarenessReview> { it.startEpochSeconds ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.id },
    )

    companion object {
        val Empty = AwarenessState()
    }
}

enum class AwarenessReviewField {
    CAMERA,
    LIFECYCLE,
    SEVERITY,
    START_TIME,
    END_TIME,
    OBJECTS,
    ZONES,
    AUDIO,
    SUB_LABELS,
    DETECTION_IDS,
    SUMMARY,
    THREAT_LEVEL,
    THUMBNAIL,
    REVIEWED,
    UPDATE_METADATA,
}

sealed interface AwarenessTransitionFact {
    val profileKey: String
    val reviewId: String
    val notificationEligible: Boolean

    data class ReviewDiscovered(
        override val profileKey: String,
        override val reviewId: String,
        val review: AwarenessReview,
        override val notificationEligible: Boolean = true,
    ) : AwarenessTransitionFact

    data class LifecycleChanged(
        override val profileKey: String,
        override val reviewId: String,
        val from: AwarenessReviewLifecycle,
        val to: AwarenessReviewLifecycle,
        override val notificationEligible: Boolean = true,
    ) : AwarenessTransitionFact

    data class SeverityEscalated(
        override val profileKey: String,
        override val reviewId: String,
        val from: AwarenessReviewSeverity,
        val to: AwarenessReviewSeverity,
        override val notificationEligible: Boolean = true,
    ) : AwarenessTransitionFact

    data class FieldsChanged(
        override val profileKey: String,
        override val reviewId: String,
        val fields: List<AwarenessReviewField>,
        override val notificationEligible: Boolean = true,
    ) : AwarenessTransitionFact

    data class AuthoritativeSnapshotApplied(
        override val profileKey: String,
        override val reviewId: String,
        val observationId: String,
        override val notificationEligible: Boolean = false,
    ) : AwarenessTransitionFact
}

sealed interface AwarenessCommand {
    data class ReconcileReview(
        val profileKey: String,
        val reviewId: String,
        val afterObservationId: String,
        val reason: AwarenessRealtimeUpdateKind,
    ) : AwarenessCommand
}

data class AwarenessReduction(
    val state: AwarenessState,
    val facts: List<AwarenessTransitionFact>,
    val commands: List<AwarenessCommand>,
)

data class AwarenessRetentionPolicy(
    val maxReviews: Int = 1_024,
    val endedRetentionMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
) {
    init {
        require(maxReviews in 1..10_000) { "Review retention count must be between 1 and 10,000" }
        require(endedRetentionMillis > 0L) { "Ended retention must be positive" }
    }
}

private fun Double.requireEpochSeconds(label: String) {
    require(isFinite() && this >= 0.0) { "$label must be finite and not negative" }
}

private fun String.requireSafeIdentity(label: String, maxChars: Int) {
    require(isNotBlank()) { "$label must not be blank" }
    require(awarenessCodePointCount() <= maxChars) { "$label exceeds its maximum length" }
    require(hasOnlySafeAwarenessCodePoints()) { "$label contains control characters" }
}

private fun String?.requireBoundedInput(label: String) {
    if (this == null) return
    require(awarenessCodePointCount() <= AwarenessRecordBounds.MAX_INPUT_TEXT_CHARS) {
        "$label exceeds its input bound"
    }
}

private fun Set<String>.requireBoundedInputSet(label: String) {
    require(size <= AwarenessRecordBounds.MAX_INPUT_SET_ITEMS) { "$label exceeds its item bound" }
    forEach {
        require(it.awarenessCodePointCount() <= AwarenessRecordBounds.MAX_INPUT_LABEL_CHARS) {
            "$label item exceeds its input bound"
        }
    }
}

private fun String.requireSafeDisplayIdentity(label: String) {
    require(awarenessCodePointCount() <= AwarenessRecordBounds.MAX_CAMERA_CHARS) {
        "$label exceeds its maximum length"
    }
    require(hasOnlySafeAwarenessCodePoints()) { "$label contains control characters" }
}

private fun String.hasOnlySafeAwarenessCodePoints(): Boolean {
    var offset = 0
    while (offset < length) {
        val width = validCodePointWidthAt(offset)
        if (width == 0) return false
        val codePoint = if (width == 2) {
            Character.toCodePoint(this[offset], this[offset + 1])
        } else {
            this[offset].code
        }
        if (codePoint.isForbiddenAwarenessCodePoint()) return false
        offset += width
    }
    return true
}

private fun AwarenessRealtimeObservation.inputCharacterCount(): Long =
    camera.presentValueOrNull().characterCount() +
        rawUpdateKind.characterCount() +
        severity.presentValueOrNull()?.rawSeverity.characterCount() +
        objects.presentValueOrNull().characterCount() +
        zones.presentValueOrNull().characterCount() +
        audio.presentValueOrNull().characterCount() +
        subLabels.presentValueOrNull().characterCount() +
        detectionIds.presentValueOrNull().characterCount() +
        summary.presentValueOrNull().characterCount() +
        thumbnail.presentValueOrNull().characterCount()

private fun AwarenessAuthoritativeSnapshot.inputCharacterCount(): Long =
    camera.characterCount() +
        severity.rawSeverity.characterCount() +
        objects.characterCount() +
        zones.characterCount() +
        audio.characterCount() +
        subLabels.characterCount() +
        detectionIds.characterCount() +
        summary.characterCount() +
        thumbnail.characterCount() +
        rawLifecycle.characterCount()

private fun String?.characterCount(): Long = this?.awarenessCodePointCount()?.toLong() ?: 0L

private fun Set<String>?.characterCount(): Long = this?.sumOf { it.awarenessCodePointCount().toLong() } ?: 0L

private fun AwarenessSummary?.characterCount(): Long = if (this == null) {
    0L
} else {
    title.characterCount() +
        shortSummary.characterCount() +
        scene.characterCount() +
        otherConcerns.characterCount()
}

private fun <T> AwarenessObservedField<T>.presentValueOrNull(): T? =
    (this as? AwarenessObservedField.Present<T>)?.value

internal fun String.awarenessCodePointCount(): Int {
    var count = 0
    var offset = 0
    while (offset < length) {
        val width = validCodePointWidthAt(offset)
        offset += if (width == 0) 1 else width
        count += 1
    }
    return count
}

internal fun String.validCodePointWidthAt(offset: Int): Int {
    val current = this[offset]
    return when {
        Character.isHighSurrogate(current) -> if (
            offset + 1 < length && Character.isLowSurrogate(this[offset + 1])
        ) {
            2
        } else {
            0
        }

        Character.isLowSurrogate(current) -> 0
        else -> 1
    }
}

internal fun Int.isForbiddenAwarenessCodePoint(): Boolean =
    Character.isISOControl(this) ||
        Character.getType(this) == Character.FORMAT.toInt() ||
        Character.getType(this) == Character.SURROGATE.toInt()
