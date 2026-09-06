package app.opah.tv.data.realtime

sealed interface RealtimeTransportCommand {
    val generation: Long
}

/** Commands that may touch `/ws`; useful for enforcing zero-upgrade policies. */
sealed interface SocketTransportCommand : RealtimeTransportCommand

sealed interface AuthorizationScopedRealtimeCommand : RealtimeTransportCommand {
    val scopeToken: AuthorizationScopeToken
}

data class RefreshAuthorizationScope(
    override val generation: Long,
    val operationId: RealtimeOperationId,
) : RealtimeTransportCommand

data class CancelAuthorizationScopeRefresh(
    override val generation: Long,
    val operationId: RealtimeOperationId,
) : RealtimeTransportCommand

data class ReplaceAuthorizedSinkScope(
    override val generation: Long,
    val scopeEpoch: Long,
    val sourceRevision: Long?,
    val authorizedCameraIds: Set<String>,
) : RealtimeTransportCommand

data class PurgeRevokedCameraState(
    override val generation: Long,
    val scopeEpoch: Long,
    val revokedCameraIds: Set<String>,
) : RealtimeTransportCommand

data class OpenSharedSocket(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    val profileId: String,
    val authorizedCameraIds: Set<String>,
    override val scopeToken: AuthorizationScopeToken,
) : SocketTransportCommand, AuthorizationScopedRealtimeCommand

data class CloseSharedSocket(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    val reason: SocketCloseReason,
) : SocketTransportCommand

data class StartSocketConsumption(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    val authorizedCameraIds: Set<String>,
    override val scopeToken: AuthorizationScopeToken,
) : SocketTransportCommand, AuthorizationScopedRealtimeCommand

data class PublishOnConnect(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    override val scopeToken: AuthorizationScopeToken,
) : SocketTransportCommand, AuthorizationScopedRealtimeCommand

data class PublishPtz(
    override val generation: Long,
    val requestOperationId: RealtimeOperationId,
    val socketOperationId: RealtimeOperationId,
    val leaseId: RealtimeLeaseId,
    val cameraId: String,
    val operation: PtzOperation,
    override val scopeToken: AuthorizationScopeToken,
) : SocketTransportCommand, AuthorizationScopedRealtimeCommand

data class RejectPtz(
    override val generation: Long,
    val reason: PtzRejectionReason,
) : RealtimeTransportCommand

data class ReconcileAuthorizedReviews(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    val authorizedCameraIds: Set<String>,
    val reason: ReconciliationReason,
    val window: ReconciliationWindow,
    val overlapSeconds: Long,
    val rangeStartEpochMillis: Long,
    val rangeEndEpochMillis: Long,
    override val scopeToken: AuthorizationScopeToken,
) : AuthorizationScopedRealtimeCommand {
    init {
        require(overlapSeconds >= 0)
        require(rangeStartEpochMillis >= 0)
        require(rangeEndEpochMillis >= rangeStartEpochMillis)
    }
}

data class CancelReconciliation(
    override val generation: Long,
    val operationId: RealtimeOperationId,
) : RealtimeTransportCommand

data class ScheduleRealtimeTimer(
    override val generation: Long,
    val operationId: RealtimeOperationId,
    val purpose: RealtimeTimerPurpose,
    val delayMillis: Long,
) : RealtimeTransportCommand

data class CancelRealtimeTimer(
    override val generation: Long,
    val operationId: RealtimeOperationId,
) : RealtimeTransportCommand

data class BufferAuthorizedSocketMessage(
    override val generation: Long,
    val socketOperationId: RealtimeOperationId,
    val message: RealtimeInboundMessage,
    override val scopeToken: AuthorizationScopeToken,
) : AuthorizationScopedRealtimeCommand

data class DeliverAuthorizedSocketMessage(
    override val generation: Long,
    val socketOperationId: RealtimeOperationId,
    val message: RealtimeInboundMessage,
    override val scopeToken: AuthorizationScopeToken,
) : AuthorizationScopedRealtimeCommand

data class MarkSocketEpochDesynchronized(
    override val generation: Long,
    val socketOperationId: RealtimeOperationId,
) : RealtimeTransportCommand

data class FlushReconciledSocketBuffer(
    override val generation: Long,
    val socketOperationId: RealtimeOperationId,
    val reconciliationOperationId: RealtimeOperationId,
    override val scopeToken: AuthorizationScopeToken,
) : AuthorizationScopedRealtimeCommand

/** Execution-time guard for queued commands that can expose or operate on camera data. */
fun AuthorizationScopedRealtimeCommand.isAuthorizedBy(state: RealtimeTransportState): Boolean {
    val evidence = state.cameraScope.evidenceOrNullForCommand() ?: return false
    if (
        generation != state.generation ||
        scopeToken.generation != state.generation ||
        scopeToken.epoch != state.authorizationScopeEpoch ||
        scopeToken.revision != evidence.revision ||
        state.authenticationState != RealtimeAuthenticationState.AUTHENTICATED ||
        !state.networkAvailable ||
        state.leases.isEmpty() ||
        state.profileState !is RealtimeProfileState.Ready
    ) {
        return false
    }
    return when (this) {
        is OpenSharedSocket ->
            state.hasCurrentFreshSocketScope(evidence) &&
                profileId == state.profileState.profile.id &&
                authorizedCameraIds == evidence.allowedCameraIds &&
                state.socket.matches(operationId, SocketSessionStatus.OPENING, scopeToken)

        is StartSocketConsumption ->
            state.hasCurrentFreshSocketScope(evidence) &&
                authorizedCameraIds == evidence.allowedCameraIds &&
                state.socket.matches(operationId, SocketSessionStatus.SYNCHRONIZING, scopeToken)

        is PublishOnConnect ->
            state.hasCurrentFreshSocketScope(evidence) &&
                state.socket.matches(operationId, SocketSessionStatus.SYNCHRONIZING, scopeToken)

        is PublishPtz ->
            state.hasCurrentFreshSocketScope(evidence) &&
                state.latestPtzRequestOperationId == requestOperationId &&
                state.leases[leaseId] == RealtimeDemandOwner.PTZ_CONTROL &&
                state.socket.matches(socketOperationId, SocketSessionStatus.HEALTHY, scopeToken) &&
                cameraId in evidence.allowedCameraIds &&
                cameraId in evidence.ptzCameraIds

        is ReconcileAuthorizedReviews -> {
            val attempt = state.reconciliation
            attempt?.operationId == operationId &&
                attempt.scopeToken == scopeToken &&
                attempt.reason == reason &&
                attempt.window == window &&
                attempt.overlapSeconds == overlapSeconds &&
                attempt.rangeStartEpochMillis == rangeStartEpochMillis &&
                attempt.rangeEndEpochMillis == rangeEndEpochMillis &&
                attempt.mode == state.selectedMode &&
                authorizedCameraIds == evidence.allowedCameraIds &&
                when (attempt.mode) {
                    RealtimeTransportMode.SHARED_WEB_SOCKET ->
                        state.hasCurrentFreshSocketScope(evidence) &&
                            state.socket.matches(
                                attempt.socketOperationId,
                                SocketSessionStatus.SYNCHRONIZING,
                                scopeToken,
                            )
                    RealtimeTransportMode.AUTHORIZED_REST_ONLY -> state.socket == null
                    RealtimeTransportMode.NONE -> false
                }
        }

        is BufferAuthorizedSocketMessage ->
            state.hasCurrentFreshSocketScope(evidence) &&
                state.socket.matches(socketOperationId, SocketSessionStatus.SYNCHRONIZING, scopeToken) &&
                message.isAuthorizedFor(evidence.allowedCameraIds)

        is DeliverAuthorizedSocketMessage ->
            state.hasCurrentFreshSocketScope(evidence) &&
                state.socket.matches(socketOperationId, SocketSessionStatus.HEALTHY, scopeToken) &&
                message.isAuthorizedFor(evidence.allowedCameraIds)

        is FlushReconciledSocketBuffer ->
            state.hasCurrentFreshSocketScope(evidence) &&
                state.socket.matches(socketOperationId, SocketSessionStatus.HEALTHY, scopeToken) &&
                state.socket?.lastSynchronizedReconciliationOperationId == reconciliationOperationId
    }
}

private fun RealtimeTransportState.hasCurrentFreshSocketScope(evidence: CameraScopeEvidence): Boolean =
    cameraScope is CameraScopeState.Fresh &&
        evidence.boundsValid &&
        selectedMode == RealtimeTransportMode.SHARED_WEB_SOCKET

private fun SocketSession?.matches(
    operationId: RealtimeOperationId?,
    status: SocketSessionStatus,
    scopeToken: AuthorizationScopeToken,
): Boolean =
    this != null &&
        this.operationId == operationId &&
        this.status == status &&
        this.scopeToken == scopeToken

private fun RealtimeInboundMessage.isAuthorizedFor(allowedCameraIds: Set<String>): Boolean = when (this) {
    is RealtimeInboundMessage.ReviewChanged -> cameraId in allowedCameraIds
    is RealtimeInboundMessage.CameraActivity -> activityByCamera.keys.all(allowedCameraIds::contains)
    is RealtimeInboundMessage.UnknownTopic -> true
}

private fun CameraScopeState.evidenceOrNullForCommand(): CameraScopeEvidence? = when (this) {
    CameraScopeState.Unknown -> null
    is CameraScopeState.Fresh -> evidence
    is CameraScopeState.Stale -> evidence
}
