package app.opah.tv.data.realtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only mutable owner of the process-wide realtime reducer.
 *
 * Ports may schedule asynchronous work, but must re-read [currentState] and apply
 * [AuthorizationScopedRealtimeCommand.isAuthorizedBy] immediately before any
 * camera-bearing side effect.
 */
class ProcessRealtimeTransportOwner(
    private val reducer: RealtimeTransportReducer,
    private val commandExecutor: RealtimeTransportCommandExecutor,
) {
    private val mutableState = MutableStateFlow(RealtimeTransportState())
    val state: StateFlow<RealtimeTransportState> = mutableState.asStateFlow()

    @Synchronized
    fun dispatch(event: RealtimeTransportEvent): RealtimeTransportState {
        val transition = reducer.reduce(mutableState.value, event)
        mutableState.value = transition.state
        transition.commands.forEach { command ->
            commandExecutor.execute(command, ::currentState)
        }
        return transition.state
    }

    fun currentState(): RealtimeTransportState = mutableState.value

    fun acquireLease(
        leaseId: RealtimeLeaseId,
        owner: RealtimeDemandOwner,
    ): RealtimeTransportState = dispatch(
        RealtimeTransportEvent.LeaseAcquired(
            generation = currentState().generation,
            leaseId = leaseId,
            owner = owner,
        ),
    )

    fun releaseLease(leaseId: RealtimeLeaseId): RealtimeTransportState = dispatch(
        RealtimeTransportEvent.LeaseReleased(
            generation = currentState().generation,
            leaseId = leaseId,
        ),
    )
}

fun interface RealtimeTransportCommandExecutor {
    fun execute(
        command: RealtimeTransportCommand,
        currentState: () -> RealtimeTransportState,
    )
}

/** Execution-time helper shared by every asynchronous command adapter. */
fun RealtimeTransportCommand.isStillExecutableBy(state: RealtimeTransportState): Boolean =
    when (this) {
        is AuthorizationScopedRealtimeCommand -> isAuthorizedBy(state)
        is RefreshAuthorizationScope ->
            generation == state.generation &&
                state.authenticationState == RealtimeAuthenticationState.AUTHENTICATED &&
                state.leases.isNotEmpty() &&
                state.scopeRefresh?.operationId == operationId
        is ScheduleRealtimeTimer ->
            generation == state.generation &&
                (state.timer?.operationId == operationId || state.scopeTimer?.operationId == operationId)
        is CancelAuthorizationScopeRefresh,
        is ReplaceAuthorizedSinkScope,
        is PurgeRevokedCameraState,
        is CloseSharedSocket,
        is RejectPtz,
        is CancelReconciliation,
        is CancelRealtimeTimer,
        is MarkSocketEpochDesynchronized -> true
    }
