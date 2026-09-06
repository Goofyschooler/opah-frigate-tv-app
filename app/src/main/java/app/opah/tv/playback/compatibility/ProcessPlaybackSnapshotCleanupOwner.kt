package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-scoped retry owner for snapshot presentation IDs which a session could not cancel
 * conclusively inside its release bound. It retains only opaque IDs and constant-size retry facts;
 * image bytes, route capabilities, and private identifiers never cross this boundary.
 */
class ProcessPlaybackSnapshotCleanupOwner(
    private val backend: PlaybackAttemptBackend,
    private val processScope: CoroutineScope,
    private val clock: PlaybackElapsedRealtimeClock,
    private val retryPolicy: PlaybackCleanupRetryPolicy = PlaybackCleanupRetryPolicy(),
    operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackSnapshotCleanupHandoffPort {
    private val lock = Any()
    private val pending = LinkedHashMap<PlaybackSnapshotPresentationId, PendingSnapshotCleanup>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val cleanupOperations = PlaybackCleanupOperationRunner(
        processScope = processScope,
        operationDispatcher = operationDispatcher,
        timeoutMillis = retryPolicy.operationTimeoutMillis,
    )
    private var retryJob: Job? = null

    init {
        processScope.launch {
            for (ignored in wakeups) {
                drainOneBatch()
            }
        }
    }

    override fun handoff(request: PlaybackSnapshotCleanupHandoff) {
        val observedAt = safeNow()
        synchronized(lock) {
            val existing = pending[request.presentationId]
            pending[request.presentationId] = existing?.copy(
                nextAttemptElapsedMillis = minOf(
                    existing.nextAttemptElapsedMillis,
                    observedAt,
                ),
            ) ?: PendingSnapshotCleanup(
                presentationId = request.presentationId,
                nextAttemptElapsedMillis = observedAt,
            )
        }
        runCatching { wakeups.trySend(Unit) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private suspend fun drainOneBatch() {
        retryJob?.cancel()
        retryJob = null
        val observedAt = safeNow()
        val work = synchronized(lock) {
            pending.values
                .filter { it.nextAttemptElapsedMillis <= observedAt }
                .take(retryPolicy.batchSize)
        }
        work.forEach { process(it) }
        scheduleNextWakeup()
    }

    private suspend fun process(snapshot: PendingSnapshotCleanup) {
        val current = synchronized(lock) { pending[snapshot.presentationId] } ?: return
        val outcome = cleanupOperations.run {
            backend.cancelSnapshot(current.presentationId)
        }
        if (outcome == PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED) {
            synchronized(lock) {
                // Acknowledgment is authoritative for this exact opaque ID, including any
                // duplicate handoff which arrived while cancellation was in flight.
                pending.remove(current.presentationId)
            }
        } else {
            recordFailure(current.presentationId)
        }
    }

    private fun recordFailure(presentationId: PlaybackSnapshotPresentationId) {
        val observedAt = safeNow()
        synchronized(lock) {
            val current = pending[presentationId] ?: return
            val failureCount = (current.failureCount + 1).coerceAtMost(MAX_FAILURE_COUNT)
            pending[presentationId] = current.copy(
                failureCount = failureCount,
                nextAttemptElapsedMillis = safeAdd(
                    observedAt,
                    retryDelayMillis(failureCount),
                ),
            )
        }
    }

    private fun scheduleNextWakeup() {
        val delayMillis = synchronized(lock) {
            pending.values.minOfOrNull(PendingSnapshotCleanup::nextAttemptElapsedMillis)
        }?.let { next -> (next - safeNow()).coerceAtLeast(0L) } ?: return
        retryJob = processScope.launch {
            delay(delayMillis)
            wakeups.trySend(Unit)
        }
    }

    private fun retryDelayMillis(failureCount: Int): Long {
        var result = retryPolicy.initialRetryMillis
        repeat((failureCount - 1).coerceIn(0, MAX_BACKOFF_DOUBLINGS)) {
            result = if (result > retryPolicy.maximumRetryMillis / 2L) {
                retryPolicy.maximumRetryMillis
            } else {
                result * 2L
            }
        }
        return result.coerceAtMost(retryPolicy.maximumRetryMillis)
    }

    private fun safeNow(): Long = runCatching(clock::elapsedRealtimeMillis)
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private data class PendingSnapshotCleanup(
        val presentationId: PlaybackSnapshotPresentationId,
        val failureCount: Int = 0,
        val nextAttemptElapsedMillis: Long,
    )

    private companion object {
        const val MAX_FAILURE_COUNT = 63
        const val MAX_BACKOFF_DOUBLINGS = 16
    }
}
