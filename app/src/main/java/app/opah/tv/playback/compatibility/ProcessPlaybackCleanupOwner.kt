package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlaybackCleanupRetryPolicy(
    val operationTimeoutMillis: Long = 2_000L,
    val initialRetryMillis: Long = 100L,
    val maximumRetryMillis: Long = 5_000L,
    val batchSize: Int = 32,
) {
    init {
        require(operationTimeoutMillis > 0L)
        require(initialRetryMillis > 0L)
        require(maximumRetryMillis >= initialRetryMillis)
        require(batchSize in 1..32)
    }
}

/**
 * Process-scoped owner for cleanup which could not finish inside a playback session's deadline.
 * Opaque backend handles and lease capabilities deliberately never cross a process boundary.
 */
class ProcessPlaybackCleanupOwner(
    private val backend: PlaybackAttemptBackend,
    private val resourceLeases: PlaybackResourceLeasePort,
    private val processScope: CoroutineScope,
    private val clock: PlaybackElapsedRealtimeClock,
    private val retryPolicy: PlaybackCleanupRetryPolicy = PlaybackCleanupRetryPolicy(),
    operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackCleanupHandoffPort {
    private val lock = Any()
    private val pending = LinkedHashMap<PlaybackAttemptId, PendingCleanup>()
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

    override fun handoff(request: PlaybackCleanupHandoff) {
        val lease = request.lease ?: return
        val observedAt = safeNow()
        synchronized(lock) {
            val existing = pending[request.attemptId]
            pending[request.attemptId] = when {
                existing == null -> PendingCleanup(
                    attemptId = request.attemptId,
                    handle = request.handle,
                    lease = lease,
                    backendReleased = request.backendReleased,
                    nextAttemptElapsedMillis = observedAt,
                )

                existing.lease != lease -> existing.copy(
                    conflicted = true,
                    nextAttemptElapsedMillis = observedAt,
                )

                existing.handle != null &&
                    request.handle != null &&
                    existing.handle != request.handle -> existing.copy(
                    conflicted = true,
                    nextAttemptElapsedMillis = observedAt,
                )

                else -> {
                    val backendReleased = existing.backendReleased || request.backendReleased
                    existing.copy(
                        handle = if (backendReleased) null else existing.handle ?: request.handle,
                        backendReleased = backendReleased,
                        nextAttemptElapsedMillis = minOf(
                            existing.nextAttemptElapsedMillis,
                            observedAt,
                        ),
                    )
                }
            }
        }
        runCatching { wakeups.trySend(Unit) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    internal fun isConflicted(attemptId: PlaybackAttemptId): Boolean =
        synchronized(lock) { pending[attemptId]?.conflicted == true }

    private suspend fun drainOneBatch() {
        retryJob?.cancel()
        retryJob = null
        val observedAt = safeNow()
        val work = synchronized(lock) {
            pending.values
                .filter { it.nextAttemptElapsedMillis <= observedAt }
                .take(retryPolicy.batchSize)
                .also { selected ->
                    selected.forEach { item ->
                        pending.remove(item.attemptId)
                        pending[item.attemptId] = item
                    }
                }
        }
        work.forEach { item -> process(item) }
        scheduleNextWakeup()
    }

    private suspend fun process(snapshot: PendingCleanup) {
        var current = synchronized(lock) { pending[snapshot.attemptId] } ?: return
        if (!current.backendReleased) {
            val backendOutcome = cleanupOperations.run {
                backend.release(
                    attemptId = current.attemptId,
                    handle = current.handle,
                    force = true,
                )
            }
            if (backendOutcome != PlaybackBackendReleaseOutcome.RELEASED) {
                recordFailure(current.attemptId)
                return
            }
            synchronized(lock) {
                val latest = pending[current.attemptId] ?: return@synchronized
                pending[current.attemptId] = latest.copy(
                    handle = null,
                    backendReleased = true,
                    failureCount = 0,
                    nextAttemptElapsedMillis = safeNow(),
                )
            }
            current = synchronized(lock) { pending[current.attemptId] } ?: return
        }
        if (current.conflicted) {
            recordFailure(current.attemptId)
            return
        }
        val leaseOutcome = cleanupOperations.run {
            resourceLeases.release(current.attemptId, current.lease)
        }
        if (leaseOutcome == PlaybackResourceLeaseReleaseOutcome.RELEASED) {
            synchronized(lock) {
                val latest = pending[current.attemptId]
                if (latest?.lease == current.lease && !latest.conflicted) {
                    pending.remove(current.attemptId)
                }
            }
        } else {
            recordFailure(current.attemptId)
        }
    }

    private fun recordFailure(attemptId: PlaybackAttemptId) {
        val observedAt = safeNow()
        synchronized(lock) {
            val current = pending[attemptId] ?: return
            val failureCount = (current.failureCount + 1).coerceAtMost(MAX_FAILURE_COUNT)
            pending[attemptId] = current.copy(
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
            pending.values.minOfOrNull(PendingCleanup::nextAttemptElapsedMillis)
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

    private data class PendingCleanup(
        val attemptId: PlaybackAttemptId,
        val handle: PlaybackAttemptHandleToken?,
        val lease: PlaybackResourceLeaseToken,
        val backendReleased: Boolean,
        val conflicted: Boolean = false,
        val failureCount: Int = 0,
        val nextAttemptElapsedMillis: Long,
    )

    private companion object {
        const val MAX_FAILURE_COUNT = 63
        const val MAX_BACKOFF_DOUBLINGS = 16
    }
}
