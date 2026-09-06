package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One-shot process startup owner for durable playback-persistence recovery.
 *
 * Recovery is deliberately limited to privacy-minimized database facts. It never reconstructs or
 * replays a route, media item, credential, lease, player, decoder, socket, or cleanup capability.
 */
class PlaybackPersistenceStartupRecoveryOwner(
    private val scope: CoroutineScope,
    private val recoveryPort: PlaybackPersistenceRecoveryPort,
    private val handler: PersistedPlaybackPersistenceRecoveryHandler,
    private val priorEpochReconciler: PlaybackPersistencePriorEpochReconciler,
    private val batchSize: Int = DEFAULT_STARTUP_RECOVERY_BATCH_SIZE,
) {
    private val startLock = Any()

    @Volatile
    private var recoveryJob: Job? = null

    init {
        require(batchSize in 1..MAX_STARTUP_RECOVERY_BATCH_SIZE)
    }

    /** Starts recovery exactly once for this process owner and returns the same job on every call. */
    fun start(): Job = synchronized(startLock) {
        recoveryJob ?: scope.launch(start = CoroutineStart.LAZY) {
            drainOnce()
        }.also { created ->
            recoveryJob = created
            created.start()
        }
    }

    private suspend fun drainOnce() {
        var cursor = 0L
        var blocked = false
        val pending = mutableListOf<PersistedPlaybackPersistenceRecoveryObligation>()
        while (true) {
            val batch = recoveryCall {
                recoveryPort.startupBatch(afterRowId = cursor, limit = batchSize)
            } ?: return
            if (batch.isEmpty()) break
            if (batch.size > batchSize) return

            var previousRowId = cursor
            for (entry in batch) {
                // A malformed/repeating page could otherwise spin forever or reorder recovery.
                if (entry.rowId <= previousRowId) return
                previousRowId = entry.rowId
                when (entry) {
                    is PlaybackPersistenceRecoveryStartupEntry.Pending ->
                        pending += entry.obligation
                    is PlaybackPersistenceRecoveryStartupEntry.Conflict,
                    is PlaybackPersistenceRecoveryStartupEntry.Corrupt,
                    -> blocked = true
                }
            }
            cursor = previousRowId
            if (batch.size < batchSize) break
        }

        // Conflict and corruption are global blockers because a damaged row cannot safely prove
        // every identity it implicates. Do not apply any pending obligation in that state.
        if (blocked) return

        pending.forEach { obligation ->
            val handled = recoveryCall {
                recoveryPort.handleAndResolveExact(obligation.token) { exactObligation ->
                    handler.handle(exactObligation)
                }
            }
            blocked = blocked ||
                handled != PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
        }

        // Outbox obligations always have priority. Only after a complete deterministic scan may
        // the store reconcile phase-one rows abandoned by an older process without an obligation.
        if (!blocked) {
            recoveryCall { priorEpochReconciler.reconcilePriorEpochPendingAttempts() }
        }
    }

    private suspend fun <T> recoveryCall(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // No raw database detail crosses the recovery boundary. The durable blocker remains.
        null
    }

    private companion object {
        const val DEFAULT_STARTUP_RECOVERY_BATCH_SIZE = 64
        const val MAX_STARTUP_RECOVERY_BATCH_SIZE = 512
    }
}
