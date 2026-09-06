package app.opah.tv.notifications

import app.opah.tv.data.Frigate018JsonParsers
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.realtime.FrigateRealtimeContract
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.realtime.RealtimeProfileState
import app.opah.tv.data.realtime.RealtimeTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class AlertModeProcessState(
    val profileKey: String? = null,
    val currentMode: String? = null,
    val modeNames: Set<String> = emptySet(),
    val fresh: Boolean = false,
)

/** Polls Frigate Mode only while an enabled alert rule or snooze actually depends on it. */
internal class ProcessAlertModeOwner(
    private val scope: CoroutineScope,
    private val transportState: StateFlow<RealtimeTransportState>,
    private val configuration: AlertConfigurationRepository,
    private val profile: suspend (String) -> ConnectionProfile?,
    private val gateway: FrigateGateway,
    private val parser: Frigate018JsonParsers = Frigate018JsonParsers(),
    private val pollDelayMillis: Long = 60_000L,
) {
    init {
        require(pollDelayMillis in 1_000L..3_600_000L)
    }

    private val mutableState = MutableStateFlow(AlertModeProcessState())
    val state: StateFlow<AlertModeProcessState> = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(transportState, configuration.state, ::modeDemand)
                .collectLatest { demand ->
                    if (demand == null) {
                        mutableState.value = AlertModeProcessState()
                        return@collectLatest
                    }
                    while (isActive) {
                        refresh(demand)
                        delay(pollDelayMillis)
                    }
                }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        mutableState.value = AlertModeProcessState()
    }

    private suspend fun refresh(demand: AlertModeDemand) {
        val connection = profile(demand.profileKey)
        val modes = connection?.let { saved ->
            runCatching { parser.parseModes(gateway.getProfiles(saved)) }.getOrNull()
        }
        mutableState.value = if (modes == null) {
            AlertModeProcessState(profileKey = demand.profileKey)
        } else {
            AlertModeProcessState(
                profileKey = demand.profileKey,
                currentMode = modes.activeMode,
                modeNames = modes.modes.mapTo(linkedSetOf()) { it.name },
                fresh = true,
            )
        }
        if (modes != null) configuration.cleanInactiveSnoozes(modes.activeMode)
    }
}

private data class AlertModeDemand(val profileKey: String)

private fun modeDemand(
    transport: RealtimeTransportState,
    configuration: AlertConfigurationState,
): AlertModeDemand? = alertModePollingProfileKey(transport, configuration)?.let(::AlertModeDemand)

internal fun alertModePollingProfileKey(
    transport: RealtimeTransportState,
    configuration: AlertConfigurationState,
): String? {
    val profile = (transport.profileState as? RealtimeProfileState.Ready)?.profile ?: return null
    if (
        profile.contract != FrigateRealtimeContract.FRIGATE_0_18 ||
        transport.authenticationState != RealtimeAuthenticationState.AUTHENTICATED
    ) return null
    val ready = (configuration as? AlertConfigurationState.Ready)?.configuration
        ?.takeIf { it.profileKey == profile.id && it.enabled }
        ?: return null
    val modeRequired = ready.policy.frigateModes.isNotEmpty() ||
        ready.snoozes.any { it.untilModeChangesFrom != null }
    return profile.id.takeIf { modeRequired }
}
