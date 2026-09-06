package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A sanitized admission failure; no deadline identity is exposed across this boundary. */
class PlaybackDeadlineCapacityExceededException internal constructor() :
    IllegalStateException("Playback deadline scheduler capacity exhausted")

/** A sanitized lifecycle failure; no scope or deadline identity is exposed. */
class PlaybackDeadlineSchedulerUnavailableException internal constructor() :
    IllegalStateException("Playback deadline scheduler unavailable")

/**
 * Process-local monotonic timer owner for playback sessions.
 *
 * An exact [PlaybackDeadlineKey] replacement is admitted even at capacity. Every other new key is
 * rejected without evicting an existing timer, allowing the session runner to fail closed. Timer
 * jobs compare their entry identity immediately before firing, so a replaced or cancelled job
 * cannot emit after a newer entry has been installed.
 */
class MonotonicCoroutinePlaybackDeadlineScheduler(
    private val schedulerScope: CoroutineScope,
    private val clock: PlaybackElapsedRealtimeClock,
    private val maximumScheduledDeadlines: Int = DEFAULT_MAXIMUM_SCHEDULED_DEADLINES,
) : PlaybackDeadlineScheduler {
    private val lock = Any()
    private val entries = LinkedHashMap<PlaybackDeadlineKey, ScheduledEntry>()

    init {
        require(maximumScheduledDeadlines in 1..MAXIMUM_SCHEDULED_DEADLINES_LIMIT) {
            "Playback deadline capacity is outside the supported bound"
        }
    }

    override suspend fun schedule(
        deadline: PlaybackScheduledDeadline,
        events: PlaybackDeadlineEventSink,
    ) {
        val entry: ScheduledEntry
        val replaced: ScheduledEntry?
        synchronized(lock) {
            replaced = entries[deadline.key]
            if (replaced == null && entries.size >= maximumScheduledDeadlines) {
                throw PlaybackDeadlineCapacityExceededException()
            }

            entry = ScheduledEntry(deadline, events)
            entry.job = schedulerScope.launch(start = CoroutineStart.LAZY) {
                awaitAndEmit(entry)
            }
            entries[deadline.key] = entry
        }

        replaced?.let(::cancelAndAwaitCallback)
        if (!entry.job.start()) {
            val wasStillCurrent = removeIfCurrent(entry)
            // A concurrent exact replacement or cancellation linearized after this schedule call;
            // this entry was admitted successfully and the later operation owns the result.
            if (wasStillCurrent) throw PlaybackDeadlineSchedulerUnavailableException()
            return
        }
        entry.job.invokeOnCompletion { removeIfCurrent(entry) }
    }

    override suspend fun cancel(key: PlaybackDeadlineKey) {
        val removed = synchronized(lock) { entries.remove(key) }
        removed?.let(::cancelAndAwaitCallback)
    }

    override suspend fun cancelAll(attemptId: PlaybackAttemptId) {
        val removed = synchronized(lock) {
            val matching = entries.values.filter { it.deadline.key.attemptId == attemptId }
            matching.forEach { entries.remove(it.deadline.key) }
            matching
        }
        removed.forEach(::cancelAndAwaitCallback)
    }

    internal fun pendingDeadlineCount(): Int = synchronized(lock) { entries.size }

    private suspend fun awaitAndEmit(entry: ScheduledEntry) {
        try {
            while (isCurrent(entry)) {
                val observedAt = clock.elapsedRealtimeMillis()
                if (observedAt < 0L) return
                val remainingMillis = entry.deadline.notBeforeElapsedMillis - observedAt
                if (remainingMillis > 0L) {
                    delay(remainingMillis)
                    continue
                }

                emitIfCurrent(entry, observedAt)
                return
            }
        } catch (_: CancellationException) {
            // Exact replacement and cancellation use ordinary structured coroutine cancellation.
        } catch (_: Throwable) {
            // The production clock is trusted, but an adapter fault must remain isolated to this
            // entry rather than cancelling the shared scheduler scope.
        }
    }

    private fun isCurrent(entry: ScheduledEntry): Boolean = synchronized(lock) {
        entries[entry.deadline.key] === entry
    }

    private fun emitIfCurrent(entry: ScheduledEntry, elapsedRealtimeMillis: Long) {
        synchronized(entry.callbackGate) {
            if (!isCurrent(entry)) return
            try {
                entry.events.emit(entry.deadline.event(elapsedRealtimeMillis))
            } catch (_: Throwable) {
                // Event sinks are an isolation boundary. A callback fault must not cancel the
                // process/session scope or prevent unrelated deadlines from firing.
            } finally {
                removeIfCurrent(entry)
            }
        }
    }

    private fun cancelAndAwaitCallback(entry: ScheduledEntry) {
        entry.job.cancel()
        // Serialize only this exact key with a callback already in progress. Once cancellation or
        // replacement returns, its superseded sink can no longer emit on another thread.
        synchronized(entry.callbackGate) { }
    }

    private fun removeIfCurrent(entry: ScheduledEntry): Boolean = synchronized(lock) {
        if (entries[entry.deadline.key] === entry) {
            entries.remove(entry.deadline.key)
            true
        } else {
            false
        }
    }

    private class ScheduledEntry(
        val deadline: PlaybackScheduledDeadline,
        val events: PlaybackDeadlineEventSink,
    ) {
        val callbackGate = Any()
        lateinit var job: Job
    }

    private companion object {
        const val DEFAULT_MAXIMUM_SCHEDULED_DEADLINES = 256
        const val MAXIMUM_SCHEDULED_DEADLINES_LIMIT = 4_096
    }
}
