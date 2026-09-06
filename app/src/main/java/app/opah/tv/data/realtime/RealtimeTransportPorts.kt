package app.opah.tv.data.realtime

import app.opah.tv.data.model.ReviewItem

/** Platform adapters implement these ports; none exposes OkHttp or Android types. */
interface AuthenticatedRealtimeSocketPort {
    fun open(command: OpenSharedSocket)

    fun close(command: CloseSharedSocket)

    fun startConsumption(command: StartSocketConsumption)

    fun publishOnConnect(command: PublishOnConnect)

    /**
     * Recheck [AuthorizationScopedRealtimeCommand.isAuthorizedBy] immediately before execution
     * and deduplicate by generation plus [PublishPtz.requestOperationId].
     */
    fun publishPtz(command: PublishPtz)
}

interface AuthorizedReviewReconciliationPort {
    fun reconcile(command: ReconcileAuthorizedReviews)

    fun cancel(command: CancelReconciliation)
}

fun interface AuthorizedReviewBatchSink {
    fun apply(
        command: ReconcileAuthorizedReviews,
        reviews: List<ReviewItem>,
        receivedAtEpochMillis: Long,
    ): Boolean
}

interface AuthorizationScopePort {
    fun refresh(command: RefreshAuthorizationScope)

    fun cancel(command: CancelAuthorizationScopeRefresh)
}

interface RealtimeTimerPort {
    fun schedule(command: ScheduleRealtimeTimer)

    fun cancel(command: CancelRealtimeTimer)
}

fun interface RealtimeEventSink {
    fun dispatch(event: RealtimeTransportEvent)
}

interface AuthorizedRealtimeSinkPort {
    fun replaceScope(command: ReplaceAuthorizedSinkScope)

    fun purge(command: PurgeRevokedCameraState)

    fun buffer(command: BufferAuthorizedSocketMessage)

    fun deliver(command: DeliverAuthorizedSocketMessage)

    fun markDesynchronized(command: MarkSocketEpochDesynchronized)

    fun flush(command: FlushReconciledSocketBuffer)
}

fun interface PtzCommandRejectionPort {
    fun reject(command: RejectPtz)
}
