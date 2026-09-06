package app.opah.tv.data.realtime

/**
 * Exhaustive bridge from pure reducer commands to platform adapters.
 *
 * Privacy-clearing and cancellation commands are deliberately never dropped.
 * Camera-bearing commands are revalidated against the latest owner state before
 * they cross a port boundary. Asynchronous adapters must repeat the same check
 * immediately before their eventual side effect.
 */
class PortRealtimeTransportCommandExecutor(
    private val socket: AuthenticatedRealtimeSocketPort,
    private val reconciliation: AuthorizedReviewReconciliationPort,
    private val authorizationScope: AuthorizationScopePort,
    private val timers: RealtimeTimerPort,
    private val sink: AuthorizedRealtimeSinkPort,
    private val ptzRejections: PtzCommandRejectionPort,
) : RealtimeTransportCommandExecutor {
    override fun execute(
        command: RealtimeTransportCommand,
        currentState: () -> RealtimeTransportState,
    ) {
        if (!command.isStillExecutableBy(currentState())) return

        when (command) {
            is RefreshAuthorizationScope -> authorizationScope.refresh(command)
            is CancelAuthorizationScopeRefresh -> authorizationScope.cancel(command)
            is ReplaceAuthorizedSinkScope -> sink.replaceScope(command)
            is PurgeRevokedCameraState -> sink.purge(command)
            is OpenSharedSocket -> socket.open(command)
            is CloseSharedSocket -> socket.close(command)
            is StartSocketConsumption -> socket.startConsumption(command)
            is PublishOnConnect -> socket.publishOnConnect(command)
            is PublishPtz -> socket.publishPtz(command)
            is RejectPtz -> ptzRejections.reject(command)
            is ReconcileAuthorizedReviews -> reconciliation.reconcile(command)
            is CancelReconciliation -> reconciliation.cancel(command)
            is ScheduleRealtimeTimer -> timers.schedule(command)
            is CancelRealtimeTimer -> timers.cancel(command)
            is BufferAuthorizedSocketMessage -> sink.buffer(command)
            is DeliverAuthorizedSocketMessage -> sink.deliver(command)
            is MarkSocketEpochDesynchronized -> sink.markDesynchronized(command)
            is FlushReconciledSocketBuffer -> sink.flush(command)
        }
    }
}
