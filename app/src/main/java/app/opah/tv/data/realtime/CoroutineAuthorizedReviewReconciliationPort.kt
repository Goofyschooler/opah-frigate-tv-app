package app.opah.tv.data.realtime

import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.FrigateGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun interface AuthenticatedReviewReconciliationSource {
    suspend fun load(
        profileId: String,
        command: ReconcileAuthorizedReviews,
    ): List<ReviewItem>
}

class FrigateReviewReconciliationSource(
    private val profiles: RealtimeConnectionProfileResolver,
    private val gateway: FrigateGateway,
    private val parsers: FrigateJsonParsers,
    private val pageSize: Int = 200,
    private val maximumPages: Int = 5,
) : AuthenticatedReviewReconciliationSource {
    init {
        require(pageSize in 1..200)
        require(maximumPages in 1..20)
    }

    override suspend fun load(
        profileId: String,
        command: ReconcileAuthorizedReviews,
    ): List<ReviewItem> {
        val profile = profiles.resolve(profileId) ?: throw MissingReconciliationProfileException()
        val after = command.rangeStartEpochMillis / 1_000.0
        var before = command.rangeEndEpochMillis / 1_000.0
        val byId = linkedMapOf<String, ReviewItem>()
        repeat(maximumPages) { pageIndex ->
            val raw = gateway.getReview(
                profile,
                ReviewSearchQuery(
                    cameras = command.authorizedCameraIds,
                    after = after,
                    before = before,
                    limit = pageSize,
                ),
            )
            val page = parsers.parseReviewItems(raw)
                .filter { review ->
                    review.camera in command.authorizedCameraIds &&
                        review.startTime >= after &&
                        review.startTime <= command.rangeEndEpochMillis / 1_000.0
                }
            page.forEach { review -> byId.putIfAbsent(review.id, review) }
            if (page.size < pageSize) return byId.values.toList()
            val oldestStart = page.minOfOrNull(ReviewItem::startTime)
                ?: return byId.values.toList()
            val nextBefore = oldestStart - PAGINATION_EPSILON_SECONDS
            if (nextBefore <= after || nextBefore >= before) return byId.values.toList()
            before = nextBefore
            if (pageIndex == maximumPages - 1) throw ReconciliationCapacityExceededException()
        }
        return byId.values.toList()
    }
}

class CoroutineAuthorizedReviewReconciliationPort(
    private val scope: CoroutineScope,
    private val source: AuthenticatedReviewReconciliationSource,
    private val sink: AuthorizedReviewBatchSink,
    private val events: RealtimeEventSink,
    private val currentState: () -> RealtimeTransportState,
    private val wallClock: RealtimeWallClock,
) : AuthorizedReviewReconciliationPort {
    private val lock = Any()
    private val jobs = mutableMapOf<ReconciliationOperationKey, Job>()

    override fun reconcile(command: ReconcileAuthorizedReviews) {
        val profileId = currentProfileId(command) ?: return
        val key = ReconciliationOperationKey(command.generation, command.operationId)
        val job = scope.launch {
            try {
                val reviews = source.load(profileId, command)
                if (!command.isAuthorizedBy(currentState())) return@launch
                val applied = sink.apply(command, reviews, wallClock.nowEpochMillis())
                if (applied && command.isAuthorizedBy(currentState())) {
                    events.dispatch(
                        RealtimeTransportEvent.ReconciliationSucceeded(
                            command.generation,
                            command.operationId,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (command.isAuthorizedBy(currentState())) {
                    events.dispatch(
                        RealtimeTransportEvent.ReconciliationFailed(
                            command.generation,
                            command.operationId,
                            classifyReconciliationFailure(error),
                        ),
                    )
                }
            } finally {
                synchronized(lock) { jobs.remove(key) }
            }
        }
        synchronized(lock) { jobs.put(key, job)?.cancel() }
    }

    override fun cancel(command: CancelReconciliation) {
        val key = ReconciliationOperationKey(command.generation, command.operationId)
        synchronized(lock) { jobs.remove(key) }?.cancel()
    }

    fun cancelAll() {
        val pending = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach(Job::cancel)
    }

    private fun currentProfileId(command: ReconcileAuthorizedReviews): String? {
        if (!command.isAuthorizedBy(currentState())) return null
        return (currentState().profileState as? RealtimeProfileState.Ready)?.profile?.id
    }

    private data class ReconciliationOperationKey(
        val generation: Long,
        val operationId: RealtimeOperationId,
    )
}

internal fun classifyReconciliationFailure(error: Throwable): SafeTransportFailure = when (error) {
    is MissingReconciliationProfileException -> SafeTransportFailure.INVALID_PROFILE
    is ReconciliationCapacityExceededException -> SafeTransportFailure.SERVER
    else -> classifyScopeRefreshFailure(error)
}

private class MissingReconciliationProfileException : Exception()
private class ReconciliationCapacityExceededException : Exception()
private const val PAGINATION_EPSILON_SECONDS = 0.001
