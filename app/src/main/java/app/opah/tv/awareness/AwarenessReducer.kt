package app.opah.tv.awareness

private const val MAX_REMEMBERED_OBSERVATIONS_PER_REVIEW = 128
private const val MAX_SUMMARY_CONCERNS = 32
private const val MAX_REPLAY_TOMBSTONES = 4_096

internal enum class AwarenessMergeField {
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
    SUMMARY_TITLE,
    SUMMARY_SHORT,
    SUMMARY_SCENE,
    SUMMARY_CONCERNS,
    THREAT_LEVEL,
    THUMBNAIL,
    REVIEWED,
    LATEST_OBSERVATION,
}

internal data class AwarenessEvidenceStamp(
    val sourceRevision: Long?,
    val sourceAuthority: Int,
    val observedAtEpochMillis: Long,
    val observationId: String,
) : Comparable<AwarenessEvidenceStamp> {
    override fun compareTo(other: AwarenessEvidenceStamp): Int =
        compareValuesBy(
            this,
            other,
            AwarenessEvidenceStamp::observedAtEpochMillis,
            AwarenessEvidenceStamp::sourceAuthority,
            { it.sourceRevision ?: Long.MIN_VALUE },
            AwarenessEvidenceStamp::observationId,
        )
}

internal data class AwarenessReviewMergeState(
    val fieldStamps: Map<AwarenessMergeField, AwarenessEvidenceStamp> = emptyMap(),
    val authoritativeFieldStamps: Map<AwarenessMergeField, AwarenessEvidenceStamp> = emptyMap(),
    val appliedObservations: Map<AwarenessObservationKey, AwarenessEvidenceStamp> = emptyMap(),
    val latestAuthoritativeObservationId: String? = null,
    val latestAuthoritativeStamp: AwarenessEvidenceStamp? = null,
    val latestRealtimeStamp: AwarenessEvidenceStamp? = null,
    val lifecycleEvidence: AwarenessLifecycleMergeState = AwarenessLifecycleMergeState(),
)

internal data class AwarenessLifecycleEvidence(
    val lifecycle: AwarenessReviewLifecycle,
    val rawLifecycle: String?,
    val stamp: AwarenessEvidenceStamp,
)

internal data class AwarenessRawLifecycleEvidence(
    val rawLifecycle: String?,
    val stamp: AwarenessEvidenceStamp,
)

internal data class AwarenessLifecycleMergeState(
    val latestRealtimeActive: AwarenessEvidenceStamp? = null,
    val latestRealtimeEnd: AwarenessEvidenceStamp? = null,
    val latestRealtimeUnknown: AwarenessRawLifecycleEvidence? = null,
    val latestAuthoritative: AwarenessLifecycleEvidence? = null,
    val latestAuthoritativeActive: AwarenessLifecycleEvidence? = null,
    val latestAuthoritativeEnd: AwarenessLifecycleEvidence? = null,
)

internal data class AwarenessObservationKey(
    val source: AwarenessObservationSource,
    val observationId: String,
) : Comparable<AwarenessObservationKey> {
    override fun compareTo(other: AwarenessObservationKey): Int =
        compareValuesBy(this, other, { it.source.ordinal }, AwarenessObservationKey::observationId)
}

/**
 * A compact, bounded do-not-resurrect marker for a Review removed by retention.
 *
 * The full Review is deliberately not retained as a second archive. Review IDs
 * are immutable Frigate identities, so every later observation for an ID that
 * crossed the retention boundary is ignored until the profile state is reset.
 */
internal data class AwarenessReplayTombstone(
    val latestStamp: AwarenessEvidenceStamp,
)

/** A pure, deterministic reducer for REST and realtime Review evidence. */
object AwarenessReducer {
    fun reduce(
        state: AwarenessState,
        observation: AwarenessReviewObservation,
    ): AwarenessReduction {
        if (state.profileKey != null && state.profileKey != observation.metadata.profileKey) {
            return AwarenessReduction(state, emptyList(), emptyList())
        }
        val boundNow = maxOf(
            observation.metadata.receivedAtEpochMillis,
            state.reviewsById.values.maxOfOrNull {
                it.updateMetadata.latestReceivedAtEpochMillis
            } ?: 0L,
        )
        val workingState = prune(state, boundNow)
        if (observation.reviewId in workingState.replayTombstonesById) {
            return AwarenessReduction(workingState, emptyList(), emptyList())
        }
        val source = when (observation) {
            is AwarenessRealtimeObservation -> AwarenessObservationSource.REALTIME
            is AwarenessAuthoritativeSnapshot -> AwarenessObservationSource.AUTHORITATIVE_REST
        }
        val observationKey = AwarenessObservationKey(source, observation.metadata.observationId)
        val existingReview = workingState.reviewsById[observation.reviewId]
        val existingMerge = workingState.mergeStateById[observation.reviewId] ?: AwarenessReviewMergeState()
        val latestRealtimeStamp = existingMerge.latestRealtimeStamp
        if (observationKey in existingMerge.appliedObservations) {
            return AwarenessReduction(workingState, emptyList(), emptyList())
        }

        val stamp = observation.metadata.toStamp(source)
        val baseReview = existingReview ?: emptyReview(observation.reviewId, observation, source)
        val result = when (observation) {
            is AwarenessRealtimeObservation -> mergeRealtime(baseReview, existingMerge, observation, stamp)
            is AwarenessAuthoritativeSnapshot -> mergeAuthoritative(baseReview, existingMerge, observation, stamp)
        }

        val realtimeEvidenceChanged =
            observation is AwarenessRealtimeObservation && result.mergeState != existingMerge
        var mergeState = result.mergeState
        if (
            observation is AwarenessRealtimeObservation &&
            stamp.isAfter(existingMerge.latestRealtimeStamp)
        ) {
            mergeState = mergeState.copy(latestRealtimeStamp = stamp)
        }
        mergeState = mergeState.remember(observationKey, stamp)
        val latestStamp = mergeState.fieldStamps[AwarenessMergeField.LATEST_OBSERVATION]
        var review = result.review
        var acceptedAuthoritativeMetadata = false
        if (
            source == AwarenessObservationSource.AUTHORITATIVE_REST &&
            stamp.isAfter(mergeState.latestAuthoritativeStamp)
        ) {
            acceptedAuthoritativeMetadata = true
            mergeState = mergeState.copy(
                latestAuthoritativeObservationId = observation.metadata.observationId,
                latestAuthoritativeStamp = stamp,
            )
        }
        var acceptedLatestMetadata = false
        if (stamp.isAfter(latestStamp)) {
            acceptedLatestMetadata = true
            mergeState = mergeState.withStamp(AwarenessMergeField.LATEST_OBSERVATION, stamp)
            review = review.copy(
                updateMetadata = AwarenessReviewUpdateMetadata(
                    latestObservationId = observation.metadata.observationId,
                    latestObservedAtEpochMillis = observation.metadata.observedAtEpochMillis,
                    latestReceivedAtEpochMillis = observation.metadata.receivedAtEpochMillis,
                    latestSourceRevision = observation.metadata.sourceRevision,
                    latestSource = source,
                    latestRealtimeUpdateKind = (observation as? AwarenessRealtimeObservation)?.updateKind,
                    latestRawRealtimeUpdateKind = (observation as? AwarenessRealtimeObservation)
                        ?.rawUpdateKind
                        .sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
                    latestObservedSeverity = observation.observedSeverity()?.severity,
                    latestRawSeverity = observation.observedSeverity()
                        ?.rawSeverity
                        .sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
                    latestAuthoritativeObservationId = mergeState.latestAuthoritativeObservationId,
                ),
            )
        } else if (acceptedAuthoritativeMetadata) {
            review = review.copy(
                updateMetadata = review.updateMetadata.copy(
                    latestAuthoritativeObservationId = mergeState.latestAuthoritativeObservationId,
                ),
            )
        }

        val visibleChanged = existingReview != review
        val reviews = workingState.reviewsById.toMutableMap().apply { put(observation.reviewId, review) }.toSortedMap()
        val mergeStates = workingState.mergeStateById.toMutableMap().apply {
            put(observation.reviewId, mergeState)
        }.toSortedMap()
        val nextState = AwarenessState(
            profileKey = workingState.profileKey ?: observation.metadata.profileKey,
            reviewsById = reviews,
            mergeStateById = mergeStates,
            replayTombstonesById = workingState.replayTombstonesById,
        )
        val boundedNextState = prune(nextState, boundNow)
        val reviewRetained = observation.reviewId in boundedNextState.reviewsById

        val facts = if (reviewRetained) buildFacts(
            before = existingReview,
            after = review,
            changedFields = result.changedFields + if (acceptedLatestMetadata || acceptedAuthoritativeMetadata) {
                setOf(AwarenessReviewField.UPDATE_METADATA)
            } else {
                emptySet()
            },
            observation = observation,
            authoritativeAccepted = observation is AwarenessAuthoritativeSnapshot &&
                (result.acceptedAnyField || acceptedAuthoritativeMetadata),
        ) else emptyList()
        val commands = if (
            reviewRetained &&
            observation is AwarenessRealtimeObservation &&
            (visibleChanged || realtimeEvidenceChanged || stamp.isAfter(latestRealtimeStamp))
        ) {
            listOf(
                AwarenessCommand.ReconcileReview(
                    profileKey = observation.metadata.profileKey,
                    reviewId = observation.reviewId,
                    afterObservationId = observation.metadata.observationId,
                    reason = observation.updateKind,
                ),
            )
        } else {
            emptyList()
        }

        return AwarenessReduction(
            state = if (visibleChanged || boundedNextState != workingState) boundedNextState else workingState,
            facts = facts,
            commands = commands,
        )
    }

    fun reduceAll(
        state: AwarenessState,
        observations: Iterable<AwarenessReviewObservation>,
    ): AwarenessReduction {
        var current = state
        val facts = mutableListOf<AwarenessTransitionFact>()
        val commands = mutableListOf<AwarenessCommand>()
        observations.forEach { observation ->
            val reduction = reduce(current, observation)
            current = reduction.state
            facts += reduction.facts
            commands += reduction.commands
        }
        return AwarenessReduction(current, facts, commands)
    }

    /**
     * Applies the in-memory history bound without discarding active merge state.
     *
     * [AwarenessRetentionPolicy.maxReviews] is a target for the combined active
     * and ended presentation ledger. Active/incomplete Reviews may temporarily
     * exceed it: evicting their partial CRDT state makes reduction dependent on
     * arrival order. Ended history fills only the remaining slots. Removed ended
     * IDs leave compact, bounded replay tombstones rather than a second archive.
     * Ingress rate limiting and reconciliation remain coordinator responsibilities.
     */
    fun prune(
        state: AwarenessState,
        nowEpochMillis: Long,
        policy: AwarenessRetentionPolicy = AwarenessRetentionPolicy(),
    ): AwarenessState {
        require(nowEpochMillis >= 0L) { "Current time must not be negative" }
        val cutoff = (nowEpochMillis - policy.endedRetentionMillis).coerceAtLeast(0L)
        val activeIds = state.reviewsById.values
            .asSequence()
            .filter { it.lifecycle != AwarenessReviewLifecycle.ENDED }
            .map(AwarenessReview::id)
            .toSet()
        val endedSlots = (policy.maxReviews - activeIds.size).coerceAtLeast(0)
        val endedIds = state.reviewsById.values
            .asSequence()
            .filter { review ->
                review.lifecycle == AwarenessReviewLifecycle.ENDED &&
                    review.updateMetadata.latestReceivedAtEpochMillis >= cutoff
            }
            .sortedWith(
                compareByDescending<AwarenessReview> { it.updateMetadata.latestReceivedAtEpochMillis }
                    .thenBy { it.id },
            )
            .take(endedSlots)
            .map(AwarenessReview::id)
            .toSet()
        val retainedIds = activeIds + endedIds
        val removedIds = state.reviewsById.keys - retainedIds
        val tombstones = (state.replayTombstonesById + removedIds.mapNotNull { reviewId ->
            val mergeState = state.mergeStateById[reviewId] ?: return@mapNotNull null
            val latestStamp = mergeState.latestEvidenceStamp() ?: return@mapNotNull null
            reviewId to AwarenessReplayTombstone(latestStamp)
        })
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AwarenessReplayTombstone>> { it.value.latestStamp }
                    .thenBy { it.key },
            )
            .take(MAX_REPLAY_TOMBSTONES)
            .associateTo(sortedMapOf()) { it.key to it.value }
        if (
            retainedIds.size == state.reviewsById.size &&
            state.reviewsById.keys == retainedIds &&
            tombstones == state.replayTombstonesById
        ) {
            return state
        }
        return AwarenessState(
            profileKey = state.profileKey,
            reviewsById = state.reviewsById.filterKeys(retainedIds::contains).toSortedMap(),
            mergeStateById = state.mergeStateById.filterKeys(retainedIds::contains).toSortedMap(),
            replayTombstonesById = tombstones,
        )
    }
}

private data class MergeResult(
    val review: AwarenessReview,
    val mergeState: AwarenessReviewMergeState,
    val changedFields: Set<AwarenessReviewField>,
    val acceptedAnyField: Boolean,
)

private fun mergeRealtime(
    initial: AwarenessReview,
    initialMerge: AwarenessReviewMergeState,
    observation: AwarenessRealtimeObservation,
    stamp: AwarenessEvidenceStamp,
): MergeResult {
    val draft = AwarenessReviewDraft(initial, initialMerge)

    observation.camera.ifPresent { value ->
        value.sanitized(AwarenessRecordBounds.MAX_CAMERA_CHARS)?.let { sanitized ->
            draft.setScalar(
                AwarenessMergeField.CAMERA,
                AwarenessReviewField.CAMERA,
                stamp,
                sanitized,
            ) {
                camera = it
            }
        }
    }
    draft.observeRealtimeLifecycle(
        updateKind = observation.updateKind,
        rawUpdateKind = observation.rawUpdateKind,
        incomingStamp = stamp,
    )

    observation.severity.ifPresent { value ->
        when (observation.updateKind) {
            AwarenessRealtimeUpdateKind.NEW,
            AwarenessRealtimeUpdateKind.UPDATE,
            AwarenessRealtimeUpdateKind.END,
            -> draft.setSeverity(value, stamp)

            AwarenessRealtimeUpdateKind.GENAI,
            AwarenessRealtimeUpdateKind.UNKNOWN,
            -> if (value.severity == AwarenessReviewSeverity.UNKNOWN) draft.setSeverity(value, stamp)
        }
    }
    observation.startEpochSeconds.ifPresent { value ->
        draft.setScalar(AwarenessMergeField.START_TIME, AwarenessReviewField.START_TIME, stamp, value) {
            startEpochSeconds = it
        }
    }
    observation.endEpochSeconds.ifPresent { value ->
        draft.setScalar(AwarenessMergeField.END_TIME, AwarenessReviewField.END_TIME, stamp, value) {
            endEpochSeconds = it
        }
        if (value != null) draft.observeRealtimeTerminal(stamp)
    }
    observation.objects.ifPresent { value ->
        draft.unionSet(AwarenessMergeField.OBJECTS, AwarenessReviewField.OBJECTS, stamp, value) { objects = it }
    }
    observation.zones.ifPresent { value ->
        draft.unionSet(AwarenessMergeField.ZONES, AwarenessReviewField.ZONES, stamp, value) { zones = it }
    }
    observation.audio.ifPresent { value ->
        draft.unionSet(AwarenessMergeField.AUDIO, AwarenessReviewField.AUDIO, stamp, value) { audio = it }
    }
    observation.subLabels.ifPresent { value ->
        draft.unionSet(AwarenessMergeField.SUB_LABELS, AwarenessReviewField.SUB_LABELS, stamp, value) {
            subLabels = it
        }
    }
    observation.detectionIds.ifPresent { value ->
        draft.unionSet(
            AwarenessMergeField.DETECTION_IDS,
            AwarenessReviewField.DETECTION_IDS,
            stamp,
            value,
        ) { detectionIds = it }
    }
    observation.summary.ifPresent { value -> draft.mergeRealtimeSummary(value, stamp) }
    observation.threatLevel.ifPresent { value ->
        draft.setScalar(AwarenessMergeField.THREAT_LEVEL, AwarenessReviewField.THREAT_LEVEL, stamp, value) {
            threatLevel = it
        }
    }
    observation.thumbnail.ifPresent { value ->
        draft.setScalar(
            AwarenessMergeField.THUMBNAIL,
            AwarenessReviewField.THUMBNAIL,
            stamp,
            value.sanitizedThumbnailPath(),
        ) {
            thumbnail = it
        }
    }

    return draft.result()
}

private fun mergeAuthoritative(
    initial: AwarenessReview,
    initialMerge: AwarenessReviewMergeState,
    snapshot: AwarenessAuthoritativeSnapshot,
    stamp: AwarenessEvidenceStamp,
): MergeResult {
    val draft = AwarenessReviewDraft(initial, initialMerge)
    snapshot.camera.sanitized(AwarenessRecordBounds.MAX_CAMERA_CHARS)?.let { camera ->
        draft.setScalar(
            AwarenessMergeField.CAMERA,
            AwarenessReviewField.CAMERA,
            stamp,
            camera,
        ) {
            this.camera = it
        }
    }
    val authoritativeLifecycle = if (snapshot.endEpochSeconds != null) {
        AwarenessReviewLifecycle.ENDED
    } else {
        snapshot.lifecycle
    }
    draft.observeAuthoritativeLifecycle(authoritativeLifecycle, snapshot.rawLifecycle, stamp)
    draft.setSeverity(snapshot.severity, stamp)
    draft.setScalar(
        AwarenessMergeField.START_TIME,
        AwarenessReviewField.START_TIME,
        stamp,
        snapshot.startEpochSeconds,
    ) { startEpochSeconds = it }
    draft.setScalar(AwarenessMergeField.END_TIME, AwarenessReviewField.END_TIME, stamp, snapshot.endEpochSeconds) {
        endEpochSeconds = it
    }
    draft.replaceSet(AwarenessMergeField.OBJECTS, AwarenessReviewField.OBJECTS, stamp, snapshot.objects) {
        objects = it
    }
    draft.replaceSet(AwarenessMergeField.ZONES, AwarenessReviewField.ZONES, stamp, snapshot.zones) { zones = it }
    draft.replaceSet(AwarenessMergeField.AUDIO, AwarenessReviewField.AUDIO, stamp, snapshot.audio) { audio = it }
    draft.replaceSet(
        AwarenessMergeField.SUB_LABELS,
        AwarenessReviewField.SUB_LABELS,
        stamp,
        snapshot.subLabels,
    ) { subLabels = it }
    draft.replaceSet(
        AwarenessMergeField.DETECTION_IDS,
        AwarenessReviewField.DETECTION_IDS,
        stamp,
        snapshot.detectionIds,
    ) { detectionIds = it }
    draft.replaceAuthoritativeSummary(snapshot.summary, stamp)
    draft.setScalar(
        AwarenessMergeField.THREAT_LEVEL,
        AwarenessReviewField.THREAT_LEVEL,
        stamp,
        snapshot.threatLevel,
    ) { threatLevel = it }
    draft.setScalar(
        AwarenessMergeField.THUMBNAIL,
        AwarenessReviewField.THUMBNAIL,
        stamp,
        snapshot.thumbnail.sanitizedThumbnailPath(),
    ) { thumbnail = it }
    draft.setScalar(AwarenessMergeField.REVIEWED, AwarenessReviewField.REVIEWED, stamp, snapshot.reviewed) {
        reviewed = it
    }
    return draft.result()
}

private class AwarenessReviewDraft(
    initial: AwarenessReview,
    initialMerge: AwarenessReviewMergeState,
) {
    var camera: String? = initial.camera
    var lifecycle: AwarenessReviewLifecycle = initial.lifecycle
    var rawLifecycle: String? = initial.rawLifecycle
    var severity: AwarenessReviewSeverity = initial.severity
    var rawSeverity: String? = initial.rawSeverity
    var startEpochSeconds: Double? = initial.startEpochSeconds
    var endEpochSeconds: Double? = initial.endEpochSeconds
    var objects: Set<String> = initial.objects
    var zones: Set<String> = initial.zones
    var audio: Set<String> = initial.audio
    var subLabels: Set<String> = initial.subLabels
    var detectionIds: Set<String> = initial.detectionIds
    private var summaryTitle: String? = initial.summary?.title
    private var summaryShort: String? = initial.summary?.shortSummary
    private var summaryScene: String? = initial.summary?.scene
    private var summaryConcerns: Set<String> = initial.summary?.otherConcerns.orEmpty()
    var threatLevel: Int? = initial.threatLevel
    var thumbnail: String? = initial.thumbnail
    var reviewed: Boolean? = initial.reviewed

    private val original = initial
    private val fieldStamps = initialMerge.fieldStamps.toMutableMap()
    private val authoritativeFieldStamps = initialMerge.authoritativeFieldStamps.toMutableMap()
    private var lifecycleEvidence = initialMerge.lifecycleEvidence
    private val changed = linkedSetOf<AwarenessReviewField>()
    private var acceptedAny = false
    private val initialMergeState = initialMerge

    fun stamp(field: AwarenessMergeField): AwarenessEvidenceStamp? = fieldStamps[field]

    fun accept(field: AwarenessMergeField, stamp: AwarenessEvidenceStamp) {
        fieldStamps[field] = stamp
        acceptedAny = true
    }

    private fun acceptMax(field: AwarenessMergeField, incomingStamp: AwarenessEvidenceStamp) {
        val current = fieldStamps[field]
        if (current == null || incomingStamp > current) fieldStamps[field] = incomingStamp
        acceptedAny = true
    }

    fun observeRealtimeLifecycle(
        updateKind: AwarenessRealtimeUpdateKind,
        rawUpdateKind: String?,
        incomingStamp: AwarenessEvidenceStamp,
    ) {
        val nextEvidence = when (updateKind) {
            AwarenessRealtimeUpdateKind.NEW,
            AwarenessRealtimeUpdateKind.UPDATE,
            -> if (incomingStamp.isAfter(lifecycleEvidence.latestRealtimeActive)) {
                lifecycleEvidence.copy(latestRealtimeActive = incomingStamp)
            } else {
                lifecycleEvidence
            }

            AwarenessRealtimeUpdateKind.END ->
                if (incomingStamp.isAfter(lifecycleEvidence.latestRealtimeEnd)) {
                    lifecycleEvidence.copy(latestRealtimeEnd = incomingStamp)
                } else {
                    lifecycleEvidence
                }

            AwarenessRealtimeUpdateKind.UNKNOWN -> {
                val existing = lifecycleEvidence.latestRealtimeUnknown
                if (existing == null || incomingStamp > existing.stamp) {
                    lifecycleEvidence.copy(
                        latestRealtimeUnknown = AwarenessRawLifecycleEvidence(
                            rawUpdateKind.sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
                            incomingStamp,
                        ),
                    )
                } else {
                    lifecycleEvidence
                }
            }

            AwarenessRealtimeUpdateKind.GENAI -> lifecycleEvidence
        }
        if (nextEvidence != lifecycleEvidence) {
            lifecycleEvidence = nextEvidence
            acceptMax(AwarenessMergeField.LIFECYCLE, incomingStamp)
            resolveLifecycle()
        }
    }

    fun observeRealtimeTerminal(incomingStamp: AwarenessEvidenceStamp) {
        if (!incomingStamp.isAfter(lifecycleEvidence.latestRealtimeEnd)) return
        lifecycleEvidence = lifecycleEvidence.copy(latestRealtimeEnd = incomingStamp)
        acceptMax(AwarenessMergeField.LIFECYCLE, incomingStamp)
        resolveLifecycle()
    }

    fun observeAuthoritativeLifecycle(
        value: AwarenessReviewLifecycle,
        rawValue: String?,
        incomingStamp: AwarenessEvidenceStamp,
    ) {
        val evidence = AwarenessLifecycleEvidence(
            lifecycle = value,
            rawLifecycle = rawValue.sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
            stamp = incomingStamp,
        )
        var nextEvidence = lifecycleEvidence
        if (incomingStamp.isAfter(lifecycleEvidence.latestAuthoritative?.stamp)) {
            nextEvidence = nextEvidence.copy(latestAuthoritative = evidence)
        }
        nextEvidence = when (value) {
            AwarenessReviewLifecycle.ACTIVE -> if (
                incomingStamp.isAfter(lifecycleEvidence.latestAuthoritativeActive?.stamp)
            ) {
                nextEvidence.copy(latestAuthoritativeActive = evidence)
            } else {
                nextEvidence
            }

            AwarenessReviewLifecycle.ENDED -> if (
                incomingStamp.isAfter(lifecycleEvidence.latestAuthoritativeEnd?.stamp)
            ) {
                nextEvidence.copy(latestAuthoritativeEnd = evidence)
            } else {
                nextEvidence
            }

            AwarenessReviewLifecycle.UNKNOWN -> nextEvidence
        }
        if (nextEvidence == lifecycleEvidence) return
        lifecycleEvidence = nextEvidence
        acceptMax(AwarenessMergeField.LIFECYCLE, incomingStamp)
        resolveLifecycle()
    }

    private fun resolveLifecycle() {
        val authoritative = lifecycleEvidence.latestAuthoritative
        val authoritativeActive = lifecycleEvidence.latestAuthoritativeActive
        val authoritativeEnd = lifecycleEvidence.latestAuthoritativeEnd
        val realtimeEnd = lifecycleEvidence.latestRealtimeEnd
        val terminalStamp = listOfNotNull(authoritativeEnd?.stamp, realtimeEnd).maxOrNull()
        val authoritativeActiveWins = authoritativeActive != null &&
            (terminalStamp == null || authoritativeActive.stamp > terminalStamp)
        val authoritativeEndWins = authoritativeEnd != null &&
            (realtimeEnd == null || authoritativeEnd.stamp >= realtimeEnd)
        val resolvedLifecycle = when {
            authoritativeActiveWins -> AwarenessReviewLifecycle.ACTIVE
            terminalStamp != null -> AwarenessReviewLifecycle.ENDED
            lifecycleEvidence.latestRealtimeActive != null -> AwarenessReviewLifecycle.ACTIVE
            else -> AwarenessReviewLifecycle.UNKNOWN
        }
        val resolvedRaw = when {
            authoritativeActiveWins -> authoritativeActive.rawLifecycle
            authoritativeEndWins -> authoritativeEnd.rawLifecycle
            realtimeEnd != null -> null
            resolvedLifecycle == AwarenessReviewLifecycle.UNKNOWN ->
                authoritative
                    ?.takeIf { it.lifecycle == AwarenessReviewLifecycle.UNKNOWN }
                    ?.rawLifecycle
                    ?: lifecycleEvidence.latestRealtimeUnknown?.rawLifecycle
            else -> null
        }
        setLifecycle(resolvedLifecycle, resolvedRaw)
    }

    fun setLifecycle(value: AwarenessReviewLifecycle, rawValue: String?) {
        if (lifecycle != value || rawLifecycle != rawValue) {
            lifecycle = value
            rawLifecycle = rawValue
            changed += AwarenessReviewField.LIFECYCLE
        }
    }

    fun setSeverity(value: AwarenessSeverityEvidence, incomingStamp: AwarenessEvidenceStamp) {
        val currentRank = severity.rank
        val incomingRank = value.severity.rank
        val existingStamp = stamp(AwarenessMergeField.SEVERITY)
        val isEscalation = incomingRank > currentRank
        if (!isEscalation && (incomingRank != currentRank || !incomingStamp.isAfter(existingStamp))) return

        accept(AwarenessMergeField.SEVERITY, incomingStamp)
        val nextSeverity = if (isEscalation) value.severity else severity
        val nextRaw = value.rawSeverity.sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS)
        if (severity != nextSeverity || rawSeverity != nextRaw) {
            severity = nextSeverity
            rawSeverity = nextRaw
            changed += AwarenessReviewField.SEVERITY
        }
    }

    fun <T> setScalar(
        mergeField: AwarenessMergeField,
        publicField: AwarenessReviewField,
        incomingStamp: AwarenessEvidenceStamp,
        value: T,
        assign: AwarenessReviewDraft.(T) -> Unit,
    ) {
        if (!incomingStamp.isAfter(stamp(mergeField))) return
        val before = publicValue(publicField)
        accept(mergeField, incomingStamp)
        assign(value)
        if (before != publicValue(publicField)) changed += publicField
    }

    fun unionSet(
        mergeField: AwarenessMergeField,
        publicField: AwarenessReviewField,
        incomingStamp: AwarenessEvidenceStamp,
        value: Set<String>,
        assign: AwarenessReviewDraft.(Set<String>) -> Unit,
    ) {
        val authoritativeStamp = authoritativeFieldStamps[mergeField]
        if (authoritativeStamp != null && incomingStamp <= authoritativeStamp) return
        val current = publicValue(publicField) as Set<*>
        val merged = stableSet(current.filterIsInstance<String>() + value)
        acceptMax(mergeField, incomingStamp)
        if (merged != current) {
            assign(merged)
            changed += publicField
        }
    }

    fun replaceSet(
        mergeField: AwarenessMergeField,
        publicField: AwarenessReviewField,
        incomingStamp: AwarenessEvidenceStamp,
        value: Set<String>,
        assign: AwarenessReviewDraft.(Set<String>) -> Unit,
    ) {
        val existingAuthoritative = authoritativeFieldStamps[mergeField]
        if (existingAuthoritative != null && incomingStamp <= existingAuthoritative) return
        authoritativeFieldStamps[mergeField] = incomingStamp
        val current = publicValue(publicField) as Set<*>
        val stableValue = stableSet(value)
        val next = if (incomingStamp.isAfter(stamp(mergeField))) {
            stableValue
        } else {
            stableSet(current.filterIsInstance<String>() + stableValue)
        }
        acceptMax(mergeField, incomingStamp)
        if (next != current) {
            assign(next)
            changed += publicField
        }
    }

    fun mergeRealtimeSummary(value: AwarenessSummary?, incomingStamp: AwarenessEvidenceStamp) {
        if (value == null) return
        value.title?.let {
            setSummaryScalar(AwarenessMergeField.SUMMARY_TITLE, incomingStamp, it) { summaryTitle = it }
        }
        value.shortSummary?.let {
            setSummaryScalar(AwarenessMergeField.SUMMARY_SHORT, incomingStamp, it) { summaryShort = it }
        }
        value.scene?.let {
            setSummaryScalar(AwarenessMergeField.SUMMARY_SCENE, incomingStamp, it) { summaryScene = it }
        }
        unionSummaryConcerns(value.otherConcerns, incomingStamp)
    }

    fun replaceAuthoritativeSummary(value: AwarenessSummary?, incomingStamp: AwarenessEvidenceStamp) {
        setSummaryScalar(AwarenessMergeField.SUMMARY_TITLE, incomingStamp, value?.title) { summaryTitle = it }
        setSummaryScalar(AwarenessMergeField.SUMMARY_SHORT, incomingStamp, value?.shortSummary) { summaryShort = it }
        setSummaryScalar(AwarenessMergeField.SUMMARY_SCENE, incomingStamp, value?.scene) { summaryScene = it }
        replaceAuthoritativeSummaryConcerns(value?.otherConcerns.orEmpty(), incomingStamp)
    }

    private fun setSummaryScalar(
        field: AwarenessMergeField,
        incomingStamp: AwarenessEvidenceStamp,
        value: String?,
        assign: (String?) -> Unit,
    ) {
        if (!incomingStamp.isAfter(stamp(field))) return
        val before = currentSummary()
        val maxChars = when (field) {
            AwarenessMergeField.SUMMARY_TITLE -> AwarenessRecordBounds.MAX_TITLE_CHARS
            AwarenessMergeField.SUMMARY_SHORT -> AwarenessRecordBounds.MAX_SUMMARY_CHARS
            AwarenessMergeField.SUMMARY_SCENE -> AwarenessRecordBounds.MAX_SCENE_CHARS
            else -> error("Not a summary scalar field: $field")
        }
        accept(field, incomingStamp)
        assign(value.sanitized(maxChars))
        if (before != currentSummary()) changed += AwarenessReviewField.SUMMARY
    }

    private fun unionSummaryConcerns(
        value: Set<String>,
        incomingStamp: AwarenessEvidenceStamp,
    ) {
        val field = AwarenessMergeField.SUMMARY_CONCERNS
        val authoritativeStamp = authoritativeFieldStamps[field]
        if (authoritativeStamp != null && incomingStamp <= authoritativeStamp) return
        val before = currentSummary()
        summaryConcerns = stableSet(summaryConcerns + value, maxItems = MAX_SUMMARY_CONCERNS)
        acceptMax(field, incomingStamp)
        if (before != currentSummary()) changed += AwarenessReviewField.SUMMARY
    }

    private fun replaceAuthoritativeSummaryConcerns(
        value: Set<String>,
        incomingStamp: AwarenessEvidenceStamp,
    ) {
        val field = AwarenessMergeField.SUMMARY_CONCERNS
        val existingAuthoritative = authoritativeFieldStamps[field]
        if (existingAuthoritative != null && incomingStamp <= existingAuthoritative) return
        authoritativeFieldStamps[field] = incomingStamp
        val before = currentSummary()
        summaryConcerns = if (incomingStamp.isAfter(stamp(field))) {
            stableSet(value, maxItems = MAX_SUMMARY_CONCERNS)
        } else {
            stableSet(summaryConcerns + value, maxItems = MAX_SUMMARY_CONCERNS)
        }
        acceptMax(field, incomingStamp)
        if (before != currentSummary()) changed += AwarenessReviewField.SUMMARY
    }

    private fun currentSummary(): AwarenessSummary? =
        if (summaryTitle == null && summaryShort == null && summaryScene == null && summaryConcerns.isEmpty()) {
            null
        } else {
            AwarenessSummary(
                title = summaryTitle,
                shortSummary = summaryShort,
                scene = summaryScene,
                otherConcerns = summaryConcerns,
            )
        }

    fun result(): MergeResult {
        val review = original.copy(
            camera = camera,
            lifecycle = lifecycle,
            rawLifecycle = rawLifecycle,
            severity = severity,
            rawSeverity = rawSeverity,
            startEpochSeconds = startEpochSeconds,
            endEpochSeconds = endEpochSeconds,
            objects = objects,
            zones = zones,
            audio = audio,
            subLabels = subLabels,
            detectionIds = detectionIds,
            summary = currentSummary(),
            threatLevel = threatLevel,
            thumbnail = thumbnail,
            reviewed = reviewed,
        )
        return MergeResult(
            review = review,
            mergeState = initialMergeState.copy(
                fieldStamps = fieldStamps.toSortedMap(),
                authoritativeFieldStamps = authoritativeFieldStamps.toSortedMap(),
                lifecycleEvidence = lifecycleEvidence,
            ),
            changedFields = changed,
            acceptedAnyField = acceptedAny,
        )
    }

    private fun publicValue(field: AwarenessReviewField): Any? = when (field) {
        AwarenessReviewField.CAMERA -> camera
        AwarenessReviewField.LIFECYCLE -> lifecycle to rawLifecycle
        AwarenessReviewField.SEVERITY -> severity to rawSeverity
        AwarenessReviewField.START_TIME -> startEpochSeconds
        AwarenessReviewField.END_TIME -> endEpochSeconds
        AwarenessReviewField.OBJECTS -> objects
        AwarenessReviewField.ZONES -> zones
        AwarenessReviewField.AUDIO -> audio
        AwarenessReviewField.SUB_LABELS -> subLabels
        AwarenessReviewField.DETECTION_IDS -> detectionIds
        AwarenessReviewField.SUMMARY -> currentSummary()
        AwarenessReviewField.THREAT_LEVEL -> threatLevel
        AwarenessReviewField.THUMBNAIL -> thumbnail
        AwarenessReviewField.REVIEWED -> reviewed
        AwarenessReviewField.UPDATE_METADATA -> original.updateMetadata
    }
}

private fun buildFacts(
    before: AwarenessReview?,
    after: AwarenessReview,
    changedFields: Set<AwarenessReviewField>,
    observation: AwarenessReviewObservation,
    authoritativeAccepted: Boolean,
): List<AwarenessTransitionFact> = buildList {
    val enrichmentOnly = observation is AwarenessRealtimeObservation &&
        observation.updateKind in setOf(
            AwarenessRealtimeUpdateKind.GENAI,
            AwarenessRealtimeUpdateKind.UNKNOWN,
        )
    val notificationEligible = !enrichmentOnly
    if (before == null) {
        if (notificationEligible) {
            add(AwarenessTransitionFact.ReviewDiscovered(after.profileKey, after.id, after))
        }
    } else {
        if (before.lifecycle != after.lifecycle) {
            add(
                AwarenessTransitionFact.LifecycleChanged(
                    after.profileKey,
                    after.id,
                    before.lifecycle,
                    after.lifecycle,
                    notificationEligible,
                ),
            )
        }
        if (after.severity.rank > before.severity.rank) {
            add(
                AwarenessTransitionFact.SeverityEscalated(
                    after.profileKey,
                    after.id,
                    before.severity,
                    after.severity,
                    notificationEligible,
                ),
            )
        }
        val remaining = changedFields
            .filterNot { it == AwarenessReviewField.LIFECYCLE || it == AwarenessReviewField.SEVERITY }
            .sortedBy(AwarenessReviewField::ordinal)
        if (remaining.isNotEmpty()) {
            add(AwarenessTransitionFact.FieldsChanged(after.profileKey, after.id, remaining, notificationEligible))
        }
    }
    if (authoritativeAccepted) {
        add(
            AwarenessTransitionFact.AuthoritativeSnapshotApplied(
                profileKey = after.profileKey,
                reviewId = after.id,
                observationId = observation.metadata.observationId,
            ),
        )
    }
}

private fun emptyReview(
    reviewId: String,
    observation: AwarenessReviewObservation,
    source: AwarenessObservationSource,
): AwarenessReview = AwarenessReview(
    profileKey = observation.metadata.profileKey,
    id = reviewId,
    camera = null,
    lifecycle = AwarenessReviewLifecycle.UNKNOWN,
    rawLifecycle = null,
    severity = AwarenessReviewSeverity.UNKNOWN,
    rawSeverity = null,
    startEpochSeconds = null,
    endEpochSeconds = null,
    objects = emptySet(),
    zones = emptySet(),
    audio = emptySet(),
    subLabels = emptySet(),
    detectionIds = emptySet(),
    summary = null,
    threatLevel = null,
    thumbnail = null,
    reviewed = null,
    updateMetadata = AwarenessReviewUpdateMetadata(
        latestObservationId = observation.metadata.observationId,
        latestObservedAtEpochMillis = observation.metadata.observedAtEpochMillis,
        latestReceivedAtEpochMillis = observation.metadata.receivedAtEpochMillis,
        latestSourceRevision = observation.metadata.sourceRevision,
        latestSource = source,
        latestRealtimeUpdateKind = (observation as? AwarenessRealtimeObservation)?.updateKind,
        latestRawRealtimeUpdateKind = (observation as? AwarenessRealtimeObservation)
            ?.rawUpdateKind
            .sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
        latestObservedSeverity = observation.observedSeverity()?.severity,
        latestRawSeverity = observation.observedSeverity()
            ?.rawSeverity
            .sanitized(AwarenessRecordBounds.MAX_RAW_VALUE_CHARS),
        latestAuthoritativeObservationId = null,
    ),
)

private fun AwarenessObservationMetadata.toStamp(source: AwarenessObservationSource) = AwarenessEvidenceStamp(
    sourceRevision = sourceRevision,
    sourceAuthority = when (source) {
        AwarenessObservationSource.REALTIME -> 0
        AwarenessObservationSource.AUTHORITATIVE_REST -> 1
    },
    observedAtEpochMillis = observedAtEpochMillis,
    observationId = observationId,
)

private fun AwarenessEvidenceStamp.isAfter(other: AwarenessEvidenceStamp?): Boolean =
    other == null || this > other

private fun AwarenessReviewMergeState.withStamp(
    field: AwarenessMergeField,
    stamp: AwarenessEvidenceStamp,
): AwarenessReviewMergeState = copy(fieldStamps = fieldStamps + (field to stamp))

private fun AwarenessReviewMergeState.remember(
    observationKey: AwarenessObservationKey,
    stamp: AwarenessEvidenceStamp,
): AwarenessReviewMergeState {
    val combined = appliedObservations + (observationKey to stamp)
    val retained = AwarenessObservationSource.entries
        .flatMap { source ->
            combined.entries
                .asSequence()
                .filter { it.key.source == source }
                .sortedWith(
                    compareByDescending<Map.Entry<AwarenessObservationKey, AwarenessEvidenceStamp>> { it.value }
                        .thenByDescending { it.key },
                )
                .take(MAX_REMEMBERED_OBSERVATIONS_PER_REVIEW)
                .toList()
        }
        .associateTo(sortedMapOf()) { it.key to it.value }
    return copy(appliedObservations = retained)
}

private fun AwarenessReviewMergeState.latestEvidenceStamp(): AwarenessEvidenceStamp? =
    sequenceOf(
        fieldStamps.values.asSequence(),
        authoritativeFieldStamps.values.asSequence(),
        appliedObservations.values.asSequence(),
        listOfNotNull(latestAuthoritativeStamp, latestRealtimeStamp).asSequence(),
    ).flatten().maxOrNull()

private inline fun <T> AwarenessObservedField<T>.ifPresent(block: (T) -> Unit) {
    if (this is AwarenessObservedField.Present) block(value)
}

private fun AwarenessReviewObservation.observedSeverity(): AwarenessSeverityEvidence? = when (this) {
    is AwarenessAuthoritativeSnapshot -> severity
    is AwarenessRealtimeObservation -> (severity as? AwarenessObservedField.Present)?.value
}

private val AwarenessReviewSeverity.rank: Int
    get() = when (this) {
        AwarenessReviewSeverity.UNKNOWN -> 0
        AwarenessReviewSeverity.SIGNIFICANT_MOTION -> 1
        AwarenessReviewSeverity.DETECTION -> 2
        AwarenessReviewSeverity.ALERT -> 3
    }

private fun stableSet(
    values: Iterable<String>,
    maxItems: Int = AwarenessRecordBounds.MAX_STORED_SET_ITEMS,
): Set<String> = values
    .asSequence()
    .mapNotNull { it.sanitized(AwarenessRecordBounds.MAX_LABEL_CHARS) }
    .distinct()
    .sorted()
    .take(maxItems)
    .toCollection(linkedSetOf())

private fun String?.sanitized(maxChars: Int): String? = this
    ?.withoutForbiddenAwarenessCodePoints()
    ?.trim()
    ?.takeAwarenessCodePoints(maxChars)
    ?.takeIf(String::isNotBlank)

private fun String?.sanitizedThumbnailPath(): String? {
    val value = sanitized(AwarenessRecordBounds.MAX_THUMBNAIL_PATH_CHARS) ?: return null
    if (value.startsWith("//") || "://" in value) return null
    return value
}

private fun String.withoutForbiddenAwarenessCodePoints(): String {
    val sanitized = StringBuilder(length)
    var offset = 0
    while (offset < length) {
        val width = validCodePointWidthAt(offset)
        if (width == 0) {
            offset += 1
            continue
        }
        val codePoint = if (width == 2) {
            Character.toCodePoint(this[offset], this[offset + 1])
        } else {
            this[offset].code
        }
        if (!codePoint.isForbiddenAwarenessCodePoint()) {
            sanitized.append(this, offset, offset + width)
        }
        offset += width
    }
    return sanitized.toString()
}

private fun String.takeAwarenessCodePoints(maxCodePoints: Int): String {
    var count = 0
    var offset = 0
    while (offset < length && count < maxCodePoints) {
        val width = validCodePointWidthAt(offset)
        offset += if (width == 0) 1 else width
        count += 1
    }
    return if (offset == length) this else substring(0, offset)
}
