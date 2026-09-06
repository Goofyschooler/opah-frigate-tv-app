package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

fun interface PlaybackElapsedRealtimeClock {
    fun elapsedRealtimeMillis(): Long
}

/**
 * Coordinates the store's two-phase persistence protocol with reducer events. Each attempt may
 * emit one intermediate decision request and one terminal result. Unknown outcomes are provisional:
 * they never consume either delivery slot, so an authoritative retry can still make progress.
 */
class PlaybackStrategyPersistenceCoordinator(
    private val store: VerifiedPlaybackStrategyStore,
    private val clock: PlaybackElapsedRealtimeClock,
    private val recoveryPort: PlaybackPersistenceRecoveryPort =
        UnavailablePlaybackPersistenceRecoveryPort,
    private val persistedRecoveryHandler: PersistedPlaybackPersistenceRecoveryHandler =
        PersistedPlaybackPersistenceRecoveryHandler {
            PlaybackPersistenceRecoveryHandlingOutcome.UNAVAILABLE
        },
    private val storeOperationTimeoutMillis: Long = 2_000,
) {
    private val outcomeMutex = Mutex()
    private val decisionRequiredAttempts = LinkedHashSet<PlaybackAttemptId>()
    private val resolvedAttempts = LinkedHashSet<PlaybackAttemptId>()

    init {
        require(storeOperationTimeoutMillis > 0) { "Store operation timeout must be positive" }
    }

    suspend fun persist(
        command: PlaybackCommand.PersistVerifiedStrategy,
    ): PlaybackEvent? {
        val write = command.write
        val outcome = boundedStoreCall { store.save(write) }
        val event = when (outcome) {
            is StrategyWriteOutcome.DecisionRequired ->
                return deliverDecisionRequiredOnce(
                    attemptId = write.attemptId,
                    event = PlaybackEvent.StrategyPersistenceDecisionRequired(
                        attemptId = write.attemptId,
                        recordGeneration = outcome.recordGeneration,
                        elapsedMillis = clock.elapsedRealtimeMillis(),
                    ),
                )

            is StrategyWriteOutcome.FinalizedReplay ->
                return deliverFinalizationOnce(write.attemptId, outcome.outcome)

            StrategyWriteOutcome.WriteFailed -> PlaybackEvent.StrategyPersistenceFailed(
                attemptId = write.attemptId,
                reason = PersistenceFailureReason.WRITE_FAILED,
                elapsedMillis = clock.elapsedRealtimeMillis(),
            )

            StrategyWriteOutcome.TimedOutBeforeCommit -> PlaybackEvent.StrategyPersistenceFailed(
                attemptId = write.attemptId,
                reason = PersistenceFailureReason.TIMED_OUT,
                elapsedMillis = clock.elapsedRealtimeMillis(),
            )

            null -> PlaybackEvent.StrategyPersistenceFailed(
                attemptId = write.attemptId,
                reason = PersistenceFailureReason.RESOLUTION_UNAVAILABLE,
                elapsedMillis = clock.elapsedRealtimeMillis(),
            )
        }
        // A local timeout says only that this caller no longer knows the durable outcome. Do not
        // consume the attempt's authoritative result: a concurrent or later cancel may still prove
        // that the write committed (or was cancelled) before the reducer watchdog fires.
        if (outcome == null) return event
        return deliverOnce(write.attemptId, event)
    }

    suspend fun cancel(
        command: PlaybackCommand.CancelVerifiedStrategyPersistence,
    ): PlaybackEvent? {
        val context = command.context
        val attemptId = context.attemptId
        val outcome = boundedStoreCall { store.cancel(context) }
        return when (outcome) {
            is StrategyWriteCancellationOutcome.DecisionRequired ->
                deliverDecisionRequiredOnce(
                    attemptId = attemptId,
                    event = PlaybackEvent.StrategyPersistenceDecisionRequired(
                        attemptId = attemptId,
                        recordGeneration = outcome.recordGeneration,
                        elapsedMillis = clock.elapsedRealtimeMillis(),
                    ),
                )

            is StrategyWriteCancellationOutcome.FinalizedReplay ->
                deliverFinalizationOnce(attemptId, outcome.outcome)

            StrategyWriteCancellationOutcome.ResolutionUnavailable,
            null,
            -> unresolvedEvent(attemptId)
        }
    }

    suspend fun finalize(
        command: PlaybackCommand.FinalizeStrategyPersistence,
    ): PlaybackEvent? {
        val attemptId = command.finalization.context.attemptId
        val outcome = boundedStoreCall { store.finalize(command.finalization) }
            ?: return unresolvedEvent(attemptId)
        return when (outcome) {
            is StrategyPersistenceFinalizationOutcome.Committed,
            StrategyPersistenceFinalizationOutcome.Cancelled,
            -> deliverFinalizationOnce(attemptId, outcome)

            StrategyPersistenceFinalizationOutcome.ResolutionUnavailable ->
                unresolvedEvent(attemptId)
        }
    }

    suspend fun recordFailure(command: PlaybackCommand.RecordImplicatedStrategyFailure) {
        boundedStoreCall {
            store.recordImplicatedFailure(command.failure)
        }
    }

    suspend fun quarantine(
        command: PlaybackCommand.QuarantineUnresolvedStrategyPersistence,
    ): Boolean {
        val registration = boundedStoreCall { recoveryPort.register(command.request) }
            ?: return false
        val token = when (registration) {
            is PlaybackPersistenceRecoveryRegistrationOutcome.Registered -> registration.token
            is PlaybackPersistenceRecoveryRegistrationOutcome.ExactReplay -> registration.token
            PlaybackPersistenceRecoveryRegistrationOutcome.CapacityReached,
            PlaybackPersistenceRecoveryRegistrationOutcome.Conflict,
            PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt,
            PlaybackPersistenceRecoveryRegistrationOutcome.Unavailable,
            -> return false
        }
        val handled = boundedStoreCall {
            recoveryPort.handleAndResolveExact(token) { persistedObligation ->
                persistedRecoveryHandler.handle(persistedObligation)
            }
        }
        return handled == PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
    }

    private suspend fun <T> boundedStoreCall(block: suspend () -> T): T? = try {
        withTimeoutOrNull(storeOperationTimeoutMillis) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Persistence failures cross this boundary only as allowlisted outcomes; raw database
        // exception text is neither logged nor exposed to the reducer or compatibility report.
        null
    }

    private suspend fun deliverOnce(
        attemptId: PlaybackAttemptId,
        event: PlaybackEvent,
    ): PlaybackEvent? = outcomeMutex.withLock {
        if (!resolvedAttempts.add(attemptId)) return@withLock null
        trimToBound(resolvedAttempts)
        event
    }

    private suspend fun deliverDecisionRequiredOnce(
        attemptId: PlaybackAttemptId,
        event: PlaybackEvent.StrategyPersistenceDecisionRequired,
    ): PlaybackEvent? = outcomeMutex.withLock {
        if (attemptId in resolvedAttempts || !decisionRequiredAttempts.add(attemptId)) {
            return@withLock null
        }
        trimToBound(decisionRequiredAttempts)
        event
    }

    private suspend fun deliverFinalizationOnce(
        attemptId: PlaybackAttemptId,
        outcome: StrategyPersistenceFinalizationOutcome,
    ): PlaybackEvent? {
        val event = when (outcome) {
            is StrategyPersistenceFinalizationOutcome.Committed -> PlaybackEvent.StrategyPersisted(
                attemptId = attemptId,
                recordGeneration = outcome.recordGeneration,
                elapsedMillis = clock.elapsedRealtimeMillis(),
            )

            StrategyPersistenceFinalizationOutcome.Cancelled ->
                PlaybackEvent.StrategyPersistenceFailed(
                    attemptId = attemptId,
                    reason = PersistenceFailureReason.CANCELLED_BEFORE_COMMIT,
                    elapsedMillis = clock.elapsedRealtimeMillis(),
                )

            StrategyPersistenceFinalizationOutcome.ResolutionUnavailable ->
                return unresolvedEvent(attemptId)
        }
        return deliverOnce(attemptId, event)
    }

    private fun unresolvedEvent(attemptId: PlaybackAttemptId): PlaybackEvent =
        PlaybackEvent.StrategyPersistenceFailed(
            attemptId = attemptId,
            reason = PersistenceFailureReason.RESOLUTION_UNAVAILABLE,
            elapsedMillis = clock.elapsedRealtimeMillis(),
        )

    private fun trimToBound(attempts: LinkedHashSet<PlaybackAttemptId>) {
        while (attempts.size > MAX_TRACKED_ATTEMPTS) {
            attempts.remove(attempts.first())
        }
    }

    private companion object {
        const val MAX_TRACKED_ATTEMPTS = 512
    }
}
