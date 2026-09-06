package app.opah.tv.playback.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs adapter cleanup outside the sole drain coroutine. A cancellation-resistant adapter may
 * retain its own child until it returns, but it cannot block cleanup scheduling for other IDs.
 */
internal class PlaybackCleanupOperationRunner(
    processScope: CoroutineScope,
    operationDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) {
    init {
        require(timeoutMillis > 0L)
    }

    private val operationScope = CoroutineScope(processScope.coroutineContext + operationDispatcher)

    suspend fun <T> run(operation: suspend () -> T): T? {
        val outcome = CompletableDeferred<T?>()
        val operationJob = operationScope.launch {
            val value = try {
                operation()
            } catch (_: CancellationException) {
                // Preserve process shutdown, but treat an adapter-originated cancellation as a
                // failed attempt so the drain owner stays alive.
                currentCoroutineContext().ensureActive()
                null
            } catch (_: Exception) {
                null
            }
            outcome.complete(value)
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { outcome.await() }
        } finally {
            if (!outcome.isCompleted) operationJob.cancel()
        }
    }
}
