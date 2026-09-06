package app.opah.tv.awareness

import app.opah.tv.data.realtime.AuthorizedRealtimeSinkPort
import app.opah.tv.data.realtime.BufferAuthorizedSocketMessage
import app.opah.tv.data.realtime.CameraActivitySignal
import app.opah.tv.data.realtime.DeliverAuthorizedSocketMessage
import app.opah.tv.data.realtime.FlushReconciledSocketBuffer
import app.opah.tv.data.realtime.MarkSocketEpochDesynchronized
import app.opah.tv.data.realtime.PurgeRevokedCameraState
import app.opah.tv.data.realtime.RealtimeInboundMessage
import app.opah.tv.data.realtime.RealtimeProfileState
import app.opah.tv.data.realtime.RealtimeTransportEvent
import app.opah.tv.data.realtime.RealtimeTransportState
import app.opah.tv.data.realtime.RealtimeEventSink
import app.opah.tv.data.realtime.ReplaceAuthorizedSinkScope
import app.opah.tv.data.realtime.RealtimeWallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AwarenessProcessState(
    val ledger: AwarenessState = AwarenessState.Empty,
    val authorizedCameraIds: Set<String> = emptySet(),
    val cameraActivity: Map<String, CameraActivitySignal> = emptyMap(),
    val generation: Long? = null,
    val scopeEpoch: Long? = null,
    val desynchronized: Boolean = false,
    val changeSequence: Long = 0,
    val latestFacts: List<AwarenessTransitionFact> = emptyList(),
)

/** Single process owner for reconciled Review state and bounded synchronization buffering. */
class ProcessAwarenessOwner(
    private val transportState: () -> RealtimeTransportState,
    private val transportEvents: RealtimeEventSink,
    private val wallClock: RealtimeWallClock,
    private val maximumBufferedMessages: Int = 512,
) : AuthorizedRealtimeSinkPort {
    init {
        require(maximumBufferedMessages in 1..10_000)
    }

    private val mutableState = MutableStateFlow(AwarenessProcessState())
    val state: StateFlow<AwarenessProcessState> = mutableState.asStateFlow()
    private val buffered = ArrayDeque<BufferAuthorizedSocketMessage>()
    private var nextRealtimeSequence = 1L

    @Synchronized
    override fun replaceScope(command: ReplaceAuthorizedSinkScope) {
        val profileKey = (transportState().profileState as? RealtimeProfileState.Ready)?.profile?.id
        if (profileKey == null || command.authorizedCameraIds.isEmpty()) {
            clearLocked(command.generation, command.scopeEpoch)
            return
        }
        val current = mutableState.value
        val sameProfile = current.ledger.profileKey == null || current.ledger.profileKey == profileKey
        val nextLedger = if (sameProfile) {
            current.ledger
                .filterToAuthorized(command.authorizedCameraIds)
                .withProfileKey(profileKey)
        } else {
            AwarenessState.Empty
        }
        buffered.removeAll { bufferedCommand ->
            bufferedCommand.generation != command.generation ||
                bufferedCommand.scopeToken.epoch != command.scopeEpoch ||
                !bufferedCommand.message.isWithin(command.authorizedCameraIds)
        }
        mutableState.value = current.copy(
            ledger = nextLedger,
            authorizedCameraIds = command.authorizedCameraIds,
            cameraActivity = current.cameraActivity.filterKeys(command.authorizedCameraIds::contains),
            generation = command.generation,
            scopeEpoch = command.scopeEpoch,
            desynchronized = false,
            changeSequence = current.changeSequence.incremented(),
            latestFacts = emptyList(),
        )
    }

    @Synchronized
    override fun purge(command: PurgeRevokedCameraState) {
        if (command.revokedCameraIds.isEmpty()) return
        val current = mutableState.value
        buffered.removeAll { it.message.referencesAny(command.revokedCameraIds) }
        mutableState.value = current.copy(
            ledger = current.ledger.filterToAuthorized(current.authorizedCameraIds - command.revokedCameraIds),
            authorizedCameraIds = current.authorizedCameraIds - command.revokedCameraIds,
            cameraActivity = current.cameraActivity.filterKeys { it !in command.revokedCameraIds },
            changeSequence = current.changeSequence.incremented(),
            latestFacts = emptyList(),
        )
    }

    override fun buffer(command: BufferAuthorizedSocketMessage) {
        val overflow = synchronized(this) {
            if (!matchesCurrentScope(command.generation, command.scopeToken.epoch)) return
            if (buffered.size >= maximumBufferedMessages) {
                buffered.clear()
                mutableState.value = mutableState.value.copy(
                    desynchronized = true,
                    changeSequence = mutableState.value.changeSequence.incremented(),
                    latestFacts = emptyList(),
                )
                true
            } else {
                buffered.addLast(command)
                false
            }
        }
        if (overflow) {
            transportEvents.dispatch(
                RealtimeTransportEvent.AuthoritativeQueueOverflow(
                    command.generation,
                    command.socketOperationId,
                ),
            )
        }
    }

    override fun deliver(command: DeliverAuthorizedSocketMessage) {
        val repair = synchronized(this) {
            if (!matchesCurrentScope(command.generation, command.scopeToken.epoch)) return
            applyMessageLocked(command.socketOperationId.value, command.message)
        }
        if (repair) requestRepair(command.generation)
    }

    @Synchronized
    override fun markDesynchronized(command: MarkSocketEpochDesynchronized) {
        val current = mutableState.value
        if (current.generation != command.generation) return
        buffered.clear()
        mutableState.value = current.copy(
            desynchronized = true,
            changeSequence = current.changeSequence.incremented(),
            latestFacts = emptyList(),
        )
    }

    override fun flush(command: FlushReconciledSocketBuffer) {
        val needsRepair = synchronized(this) {
            if (!matchesCurrentScope(command.generation, command.scopeToken.epoch)) return
            var repair = false
            while (buffered.isNotEmpty()) {
                val pending = buffered.removeFirst()
                if (
                    pending.generation == command.generation &&
                    pending.socketOperationId == command.socketOperationId &&
                    pending.scopeToken == command.scopeToken
                ) {
                    repair = applyMessageLocked(command.socketOperationId.value, pending.message) || repair
                }
            }
            mutableState.value = mutableState.value.copy(desynchronized = false)
            repair
        }
        if (needsRepair) requestRepair(command.generation)
    }

    @Synchronized
    fun applyAuthoritative(
        generation: Long,
        scopeEpoch: Long,
        observations: List<AwarenessAuthoritativeSnapshot>,
    ): Boolean {
        if (!matchesCurrentScope(generation, scopeEpoch)) return false
        val authorized = mutableState.value.authorizedCameraIds
        val safe = observations.filter { it.camera in authorized }
        val reduction = AwarenessReducer.reduceAll(mutableState.value.ledger, safe)
        val current = mutableState.value
        mutableState.value = current.copy(
            ledger = reduction.state,
            changeSequence = current.changeSequence.incremented(),
            latestFacts = reduction.facts,
        )
        return true
    }

    private fun applyMessageLocked(socketOperation: Long, message: RealtimeInboundMessage): Boolean {
        val current = mutableState.value
        return when (message) {
            is RealtimeInboundMessage.ReviewChanged -> {
                val update = message.update ?: return true
                if (message.cameraId !in current.authorizedCameraIds) return false
                val profileKey = current.ledger.profileKey
                    ?: (transportState().profileState as? RealtimeProfileState.Ready)?.profile?.id
                    ?: return true
                val sequence = nextRealtimeSequence
                nextRealtimeSequence = sequence.incremented()
                val observation = runCatching {
                    update.toAwarenessObservation(
                        profileKey = profileKey,
                        observationId = "ws:$socketOperation:$sequence",
                        receivedAtEpochMillis = wallClock.nowEpochMillis(),
                        sourceRevision = sequence,
                    )
                }.getOrNull() ?: return true
                val reduction = AwarenessReducer.reduce(current.ledger, observation)
                mutableState.value = current.copy(
                    ledger = reduction.state,
                    changeSequence = current.changeSequence.incremented(),
                    latestFacts = reduction.facts,
                )
                false
            }
            is RealtimeInboundMessage.CameraActivity -> {
                val filtered = message.activityByCamera.filterKeys(current.authorizedCameraIds::contains)
                mutableState.value = current.copy(
                    cameraActivity = current.cameraActivity + filtered,
                    changeSequence = current.changeSequence.incremented(),
                    latestFacts = emptyList(),
                )
                false
            }
            is RealtimeInboundMessage.UnknownTopic -> false
        }
    }

    private fun requestRepair(generation: Long) {
        transportEvents.dispatch(RealtimeTransportEvent.ForegroundRefreshRequested(generation))
    }

    private fun matchesCurrentScope(generation: Long, scopeEpoch: Long): Boolean =
        mutableState.value.generation == generation && mutableState.value.scopeEpoch == scopeEpoch

    private fun clearLocked(generation: Long, scopeEpoch: Long) {
        buffered.clear()
        nextRealtimeSequence = 1
        val sequence = mutableState.value.changeSequence.incremented()
        mutableState.value = AwarenessProcessState(
            generation = generation,
            scopeEpoch = scopeEpoch,
            changeSequence = sequence,
        )
    }
}

private fun AwarenessState.filterToAuthorized(authorizedCameraIds: Set<String>): AwarenessState {
    val retainedIds = reviewsById.values
        .filter { review -> review.camera != null && review.camera in authorizedCameraIds }
        .mapTo(mutableSetOf(), AwarenessReview::id)
    return AwarenessState(
        profileKey = profileKey,
        reviewsById = reviewsById.filterKeys(retainedIds::contains),
        mergeStateById = mergeStateById.filterKeys(retainedIds::contains),
        replayTombstonesById = emptyMap(),
    )
}

private fun AwarenessState.withProfileKey(profileKey: String): AwarenessState = AwarenessState(
    profileKey = profileKey,
    reviewsById = reviewsById,
    mergeStateById = mergeStateById,
    replayTombstonesById = replayTombstonesById,
)

private fun RealtimeInboundMessage.isWithin(cameraIds: Set<String>): Boolean = when (this) {
    is RealtimeInboundMessage.ReviewChanged -> cameraId in cameraIds
    is RealtimeInboundMessage.CameraActivity -> activityByCamera.keys.all(cameraIds::contains)
    is RealtimeInboundMessage.UnknownTopic -> false
}

private fun RealtimeInboundMessage.referencesAny(cameraIds: Set<String>): Boolean = when (this) {
    is RealtimeInboundMessage.ReviewChanged -> cameraId in cameraIds
    is RealtimeInboundMessage.CameraActivity -> activityByCamera.keys.any(cameraIds::contains)
    is RealtimeInboundMessage.UnknownTopic -> false
}

private fun Long.incremented(): Long = if (this == Long.MAX_VALUE) 1 else this + 1
