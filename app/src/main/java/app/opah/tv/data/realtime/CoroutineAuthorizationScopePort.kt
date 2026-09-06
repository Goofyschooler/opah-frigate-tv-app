package app.opah.tv.data.realtime

import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.network.OpahErrorCode
import app.opah.tv.data.network.OperationFailureKind
import app.opah.tv.data.network.toOpahFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AuthenticatedCameraScopeSnapshot(
    val role: CameraRoleEvidence,
    val allowedCameraIds: Set<String>,
    val configuredCameraIds: Set<String>?,
    val ptzCameraIds: Set<String>,
    val boundsValid: Boolean = true,
    val cameraDisplayNames: Map<String, String> = emptyMap(),
)

fun interface AuthenticatedCameraScopeSource {
    suspend fun load(profileId: String): AuthenticatedCameraScopeSnapshot
}

/** Refreshes authorization with the existing session only; it never submits a saved password. */
class FrigateAuthorizationScopeSource(
    private val profiles: RealtimeConnectionProfileResolver,
    private val gateway: FrigateGateway,
    private val parsers: FrigateJsonParsers,
) : AuthenticatedCameraScopeSource {
    override suspend fun load(profileId: String): AuthenticatedCameraScopeSnapshot {
        val profile = profiles.resolve(profileId) ?: throw InvalidRealtimeProfileException()
        val user = gateway.refreshSession(profile)
        val config = gateway.getConfig(profile)
        val configured = parsers.parseConfiguredCameraNames(config)
        val allowed = user.allowedCameras.intersect(configured)
        val displayNames = parsers.parseAuthorizedCameraNames(config, allowed)
        val ptzConfigured = parsers.parsePtzConfiguredCameraNames(config).intersect(allowed)
        val ptz = buildSet {
            for (cameraId in ptzConfigured) {
                val info = try {
                    parsers.parsePtzInfo(cameraId, gateway.getPtzInfo(profile, cameraId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (info?.hasControls == true) add(cameraId)
            }
        }
        return authenticatedCameraScopeSnapshot(user, allowed, configured, ptz, displayNames)
    }
}

internal fun authenticatedCameraScopeSnapshot(
    user: FrigateUserProfile,
    allowedCameraIds: Set<String>,
    configuredCameraIds: Set<String>?,
    ptzCameraIds: Set<String>,
    cameraDisplayNames: Map<String, String> = emptyMap(),
): AuthenticatedCameraScopeSnapshot = AuthenticatedCameraScopeSnapshot(
    role = when {
        user.role.equals("admin", ignoreCase = true) -> CameraRoleEvidence.ADMINISTRATOR
        user.role.equals("viewer", ignoreCase = true) -> CameraRoleEvidence.VIEWER
        user.role.isNotBlank() -> CameraRoleEvidence.CUSTOM
        else -> CameraRoleEvidence.UNKNOWN
    },
    allowedCameraIds = allowedCameraIds,
    configuredCameraIds = configuredCameraIds,
    ptzCameraIds = ptzCameraIds.intersect(allowedCameraIds),
    cameraDisplayNames = cameraDisplayNames.filterKeys(allowedCameraIds::contains),
)

class CoroutineAuthorizationScopePort(
    private val scope: CoroutineScope,
    private val source: AuthenticatedCameraScopeSource,
    private val events: RealtimeEventSink,
    private val currentState: () -> RealtimeTransportState,
) : AuthorizationScopePort {
    private val lock = Any()
    private val jobs = mutableMapOf<ScopeOperationKey, Job>()
    private var nextRevision = 1L

    override fun refresh(command: RefreshAuthorizationScope) {
        val profileId = currentRefreshProfileId(command) ?: return
        val key = ScopeOperationKey(command.generation, command.operationId)
        val job = scope.launch {
            try {
                val snapshot = source.load(profileId)
                if (!isCurrentRefresh(command)) return@launch
                val revision = synchronized(lock) {
                    val allocated = nextRevision
                    nextRevision = if (allocated == Long.MAX_VALUE) 1L else allocated + 1L
                    allocated
                }
                events.dispatch(
                    RealtimeTransportEvent.ScopeRefreshSucceeded(
                        generation = command.generation,
                        operationId = command.operationId,
                        evidence = CameraScopeEvidence(
                            revision = revision,
                            role = snapshot.role,
                            allowedCameraIds = snapshot.allowedCameraIds,
                            configuredCameraIds = snapshot.configuredCameraIds,
                            ptzCameraIds = snapshot.ptzCameraIds,
                            boundsValid = snapshot.boundsValid,
                            cameraDisplayNames = snapshot.cameraDisplayNames,
                        ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentRefresh(command)) {
                    events.dispatch(
                        RealtimeTransportEvent.ScopeRefreshFailed(
                            command.generation,
                            command.operationId,
                            classifyScopeRefreshFailure(error),
                        ),
                    )
                }
            } finally {
                synchronized(lock) { jobs.remove(key) }
            }
        }
        synchronized(lock) { jobs.put(key, job)?.cancel() }
    }

    override fun cancel(command: CancelAuthorizationScopeRefresh) {
        val key = ScopeOperationKey(command.generation, command.operationId)
        synchronized(lock) { jobs.remove(key) }?.cancel()
    }

    fun cancelAll() {
        val pending = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach(Job::cancel)
    }

    private fun currentRefreshProfileId(command: RefreshAuthorizationScope): String? {
        val state = currentState()
        if (
            command.generation != state.generation ||
            state.authenticationState != RealtimeAuthenticationState.AUTHENTICATED ||
            state.leases.isEmpty() ||
            state.scopeRefresh?.operationId != command.operationId
        ) {
            return null
        }
        return (state.profileState as? RealtimeProfileState.Ready)?.profile?.id
    }

    private fun isCurrentRefresh(command: RefreshAuthorizationScope): Boolean =
        currentRefreshProfileId(command) != null

    private data class ScopeOperationKey(
        val generation: Long,
        val operationId: RealtimeOperationId,
    )
}

internal fun classifyScopeRefreshFailure(error: Throwable): SafeTransportFailure {
    if (error is InvalidRealtimeProfileException) return SafeTransportFailure.INVALID_PROFILE
    val failure = error.toOpahFailure()
    return when {
        failure.code == OpahErrorCode.DNS_FAILURE -> SafeTransportFailure.DNS
        failure.code == OpahErrorCode.TIMEOUT -> SafeTransportFailure.TIMEOUT
        failure.kind == OperationFailureKind.AUTHENTICATION -> SafeTransportFailure.AUTHENTICATION
        failure.kind == OperationFailureKind.AUTHORIZATION -> SafeTransportFailure.AUTHORIZATION
        failure.kind == OperationFailureKind.TRANSIENT_SERVER ||
            failure.kind == OperationFailureKind.RATE_LIMIT -> SafeTransportFailure.SERVER
        failure.kind == OperationFailureKind.INVALID_REQUEST ||
            failure.kind == OperationFailureKind.RESOURCE_OR_ENDPOINT_MISSING ||
            failure.kind == OperationFailureKind.INVALID_RESPONSE -> SafeTransportFailure.INVALID_PROFILE
        else -> SafeTransportFailure.CONNECTION
    }
}

private class InvalidRealtimeProfileException : Exception()
