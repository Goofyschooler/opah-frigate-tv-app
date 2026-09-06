package app.opah.tv.data.realtime

data class RealtimeTransportConfig(
    val backoffPolicy: RealtimeBackoffPolicy = RealtimeBackoffPolicy(),
    val restPollIntervalMillis: Long = 15_000,
    val scopeFreshnessMillis: Long = 60_000,
    val scopeRefreshTimeoutMillis: Long = 10_000,
    val coldStartLookbackMillis: Long = 5 * 60_000,
    val maximumReconciliationLookbackMillis: Long = 24 * 60 * 60_000,
    val reconciliationOverlapSeconds: Long = 30,
    val maximumCameraCount: Int = 256,
    val maximumCameraIdLength: Int = 128,
    val maximumReviewIdLength: Int = 256,
    val maximumLeaseCount: Int = 64,
) {
    init {
        require(restPollIntervalMillis > 0)
        require(scopeFreshnessMillis > 0)
        require(scopeRefreshTimeoutMillis > 0)
        require(coldStartLookbackMillis > 0)
        require(maximumReconciliationLookbackMillis >= coldStartLookbackMillis)
        require(reconciliationOverlapSeconds in 1..3_600)
        require(reconciliationOverlapSeconds <= maximumReconciliationLookbackMillis / 1_000)
        require(maximumCameraCount > 0)
        require(maximumCameraIdLength > 0)
        require(maximumReviewIdLength > 0)
        require(maximumLeaseCount > 0)
    }
}

/**
 * Android- and OkHttp-independent state machine for the process-scoped transport.
 * Every callback is bound to both a profile generation and an operation ID.
 */
class RealtimeTransportReducer(
    private val config: RealtimeTransportConfig = RealtimeTransportConfig(),
    private val wallClock: RealtimeWallClock = RealtimeWallClock(System::currentTimeMillis),
) {
    fun reduce(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent,
    ): RealtimeTransition {
        val canonicalState = state.copy(leases = state.leases.frozenMapCopy())
        val transition = reduceCanonical(canonicalState, event)
        return RealtimeTransition(
            state = transition.state.copy(leases = transition.state.leases.frozenMapCopy()),
            commands = transition.commands.map { it.frozenCollectionCopy() }.frozenListCopy(),
        )
    }

    private fun reduceCanonical(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent,
    ): RealtimeTransition = when (event) {
        is RealtimeTransportEvent.ProfileChanged -> changeProfile(state, event)
        is RealtimeTransportEvent.ProfileInvalidated -> invalidateProfile(state, event.safeReason)
        RealtimeTransportEvent.SignedOut -> signOut(state)
        is RealtimeTransportEvent.AuthenticationRestored -> restoreAuthentication(state, event)
        is RealtimeTransportEvent.LeaseAcquired -> acquireLease(state, event)
        is RealtimeTransportEvent.LeaseReleased -> releaseLease(state, event)
        is RealtimeTransportEvent.NetworkAvailabilityChanged -> changeNetwork(state, event.available)
        is RealtimeTransportEvent.ScopeRefreshSucceeded -> refreshScopeSucceeded(state, event)
        is RealtimeTransportEvent.ScopeRefreshFailed -> refreshScopeFailed(state, event)
        is RealtimeTransportEvent.ScopeReplaced -> replaceScope(state, event)
        is RealtimeTransportEvent.ScopeExpired -> expireScope(state, event)
        is RealtimeTransportEvent.SocketOpened -> socketOpened(state, event)
        is RealtimeTransportEvent.SocketFailed -> socketFailed(state, event)
        is RealtimeTransportEvent.SocketClosedUnexpectedly -> socketClosed(state, event)
        is RealtimeTransportEvent.SocketMessageReceived -> socketMessage(state, event)
        is RealtimeTransportEvent.AuthoritativeQueueOverflow -> queueOverflow(state, event)
        is RealtimeTransportEvent.ReconciliationSucceeded -> reconciliationSucceeded(state, event)
        is RealtimeTransportEvent.ReconciliationFailed -> reconciliationFailed(state, event)
        is RealtimeTransportEvent.TimerFired -> timerFired(state, event)
        is RealtimeTransportEvent.ForegroundRefreshRequested -> foregroundRefresh(state, event)
        is RealtimeTransportEvent.PublishPtzRequested -> publishPtz(state, event)
    }

    private fun changeProfile(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ProfileChanged,
    ): RealtimeTransition {
        val stopped = stopActiveWork(state, SocketCloseReason.PROFILE_CHANGED, cancelScopeTimer = true)
        val privacyCommands = clearAuthorizedSinkCommands(state)
        val next = stopped.state.copy(
            generation = state.generation + 1,
            authorizationScopeEpoch = 0,
            profileState = if (event.profile.id.isBlank()) {
                RealtimeProfileState.Invalid("invalid_profile")
            } else {
                RealtimeProfileState.Ready(event.profile)
            },
            authenticationState = RealtimeAuthenticationState.UNKNOWN,
            cameraScope = CameraScopeState.Unknown,
            scopeTimer = null,
            selectedMode = RealtimeTransportMode.NONE,
            phase = RealtimeTransportPhase.WAITING_FOR_AUTHENTICATION,
            committedReconciliationWatermarkEpochMillis =
                event.committedReconciliationWatermarkEpochMillis?.takeIf { it >= 0 },
            maximumAcceptedScopeRevision = null,
            latestPtzRequestOperationId = null,
            consecutiveFailures = 0,
            pendingReconciliationReason = null,
            socketEpochDesynchronized = false,
            repairAfterCurrentReconciliation = false,
        )
        return settle(next, stopped.commands + privacyCommands)
    }

    private fun invalidateProfile(
        state: RealtimeTransportState,
        safeReason: String,
    ): RealtimeTransition {
        val stopped = stopActiveWork(state, SocketCloseReason.PROFILE_CHANGED, cancelScopeTimer = true)
        val privacyCommands = clearAuthorizedSinkCommands(state)
        return RealtimeTransition(
            stopped.state.copy(
                generation = state.generation + 1,
                authorizationScopeEpoch = 0,
                profileState = RealtimeProfileState.Invalid(safeReason.take(MAX_SAFE_REASON_LENGTH)),
                authenticationState = RealtimeAuthenticationState.UNKNOWN,
                cameraScope = CameraScopeState.Unknown,
                scopeTimer = null,
                leases = emptyMap(),
                selectedMode = RealtimeTransportMode.NONE,
                phase = RealtimeTransportPhase.INVALID_PROFILE,
                pendingReconciliationReason = null,
                socketEpochDesynchronized = false,
                repairAfterCurrentReconciliation = false,
                maximumAcceptedScopeRevision = null,
                latestPtzRequestOperationId = null,
            ),
            stopped.commands + privacyCommands,
        )
    }

    private fun signOut(state: RealtimeTransportState): RealtimeTransition {
        val stopped = stopActiveWork(state, SocketCloseReason.SIGNED_OUT, cancelScopeTimer = true)
        val privacyCommands = clearAuthorizedSinkCommands(state)
        return RealtimeTransition(
            stopped.state.copy(
                generation = state.generation + 1,
                authorizationScopeEpoch = 0,
                profileState = RealtimeProfileState.Unavailable,
                authenticationState = RealtimeAuthenticationState.UNKNOWN,
                cameraScope = CameraScopeState.Unknown,
                scopeTimer = null,
                leases = emptyMap(),
                selectedMode = RealtimeTransportMode.NONE,
                phase = RealtimeTransportPhase.STOPPED,
                committedReconciliationWatermarkEpochMillis = null,
                maximumAcceptedScopeRevision = null,
                latestPtzRequestOperationId = null,
                consecutiveFailures = 0,
                pendingReconciliationReason = null,
                socketEpochDesynchronized = false,
                repairAfterCurrentReconciliation = false,
            ),
            stopped.commands + privacyCommands,
        )
    }

    private fun restoreAuthentication(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.AuthenticationRestored,
    ): RealtimeTransition {
        if (event.generation != state.generation || state.profileState !is RealtimeProfileState.Ready) {
            return RealtimeTransition(state)
        }
        return settle(
            state.copy(
                authenticationState = RealtimeAuthenticationState.AUTHENTICATED,
                consecutiveFailures = 0,
            ),
        )
    }

    private fun acquireLease(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.LeaseAcquired,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            event.leaseId.value.isBlank() ||
            state.leases.containsKey(event.leaseId) ||
            state.leases.size >= config.maximumLeaseCount
        ) {
            return RealtimeTransition(state)
        }
        var next = state.copy(leases = state.leases + (event.leaseId to event.owner))
        val commands = mutableListOf<RealtimeTransportCommand>()
        if (
            event.owner == RealtimeDemandOwner.FOREGROUND_UI &&
            next.selectedMode == RealtimeTransportMode.AUTHORIZED_REST_ONLY &&
            next.reconciliation == null &&
            next.timer?.purpose == RealtimeTimerPurpose.REST_POLL
        ) {
            commands += CancelRealtimeTimer(
                generation = next.generation,
                operationId = requireNotNull(next.timer).operationId,
            )
            next = next.copy(
                timer = null,
                pendingReconciliationReason = ReconciliationReason.FOREGROUND_REFRESH,
            )
        }
        return settle(next, commands)
    }

    private fun releaseLease(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.LeaseReleased,
    ): RealtimeTransition {
        if (event.generation != state.generation) return RealtimeTransition(state)
        val leaseId = event.leaseId
        if (!state.leases.containsKey(leaseId)) return RealtimeTransition(state)
        val remaining = state.leases - leaseId
        if (remaining.isNotEmpty()) {
            return RealtimeTransition(state.copy(leases = remaining))
        }
        val stopped = stopActiveWork(
            state.copy(leases = emptyMap()),
            SocketCloseReason.LAST_LEASE_RELEASED,
            cancelScopeTimer = true,
        )
        val transitioned = (stopped.state.cameraScope as? CameraScopeState.Fresh)?.let { fresh ->
            applyScope(
                state = stopped.state,
                newScope = CameraScopeState.Stale(fresh.evidence),
                cancelOutstandingRefresh = false,
                initialCommands = stopped.commands,
            )
        } ?: stopped
        return RealtimeTransition(
            transitioned.state.copy(
                selectedMode = RealtimeTransportMode.NONE,
                phase = RealtimeTransportPhase.STOPPED,
                pendingReconciliationReason = null,
                socketEpochDesynchronized = false,
                repairAfterCurrentReconciliation = false,
            ),
            transitioned.commands,
        )
    }

    private fun changeNetwork(
        state: RealtimeTransportState,
        available: Boolean,
    ): RealtimeTransition {
        if (available == state.networkAvailable) return RealtimeTransition(state)
        if (!available) {
            val hadActiveTransport = state.socket != null || state.reconciliation != null
            val stopped = stopActiveWork(
                state.copy(networkAvailable = false),
                SocketCloseReason.NETWORK_LOST,
            )
            return settle(
                stopped.state.copy(
                    pendingReconciliationReason = if (
                        hadActiveTransport || state.committedReconciliationWatermarkEpochMillis != null
                    ) {
                        ReconciliationReason.NETWORK_RECOVERY
                    } else {
                        state.pendingReconciliationReason
                    },
                    phase = if (state.leases.isEmpty()) {
                        RealtimeTransportPhase.STOPPED
                    } else {
                        RealtimeTransportPhase.WAITING_FOR_NETWORK
                    },
                ),
                stopped.commands,
            )
        }
        return settle(
            state.copy(
                networkAvailable = true,
                pendingReconciliationReason = if (state.committedReconciliationWatermarkEpochMillis != null) {
                    ReconciliationReason.NETWORK_RECOVERY
                } else {
                    state.pendingReconciliationReason
                },
            ),
        )
    }

    private fun refreshScopeSucceeded(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ScopeRefreshSucceeded,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            state.scopeRefresh?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        val normalized = normalizeEvidence(event.evidence)
        val current = state.cameraScope.evidenceOrNull()
        val fallback = current?.let(CameraScopeState::Stale) ?: CameraScopeState.Unknown
        val revisionFloor = state.maximumAcceptedScopeRevision
        if (
            !normalized.boundsValid ||
            (revisionFloor != null && normalized.revision < revisionFloor) ||
            (current != null && normalized.revision < current.revision)
        ) {
            return deferScopeRefresh(state, fallback)
        }
        if (current != null && normalized.revision == current.revision && normalized != current) {
            return deferScopeRefresh(
                state,
                CameraScopeState.Stale(conservativeScopeEvidence(current, normalized)),
            )
        }
        return applyScope(
            state.copy(scopeRefresh = null),
            CameraScopeState.Fresh(normalized),
            cancelOutstandingRefresh = false,
        )
    }

    private fun refreshScopeFailed(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ScopeRefreshFailed,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            state.scopeRefresh?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        if (event.failure.isTerminal()) return terminalFailure(state, event.failure)
        val fallback = state.cameraScope.evidenceOrNull()?.let(CameraScopeState::Stale)
            ?: CameraScopeState.Unknown
        return deferScopeRefresh(state, fallback)
    }

    private fun replaceScope(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ScopeReplaced,
    ): RealtimeTransition {
        if (event.generation != state.generation) return RealtimeTransition(state)
        val normalized = when (val scope = event.scope) {
            CameraScopeState.Unknown -> CameraScopeState.Unknown
            is CameraScopeState.Fresh -> CameraScopeState.Fresh(normalizeEvidence(scope.evidence))
            is CameraScopeState.Stale -> CameraScopeState.Stale(normalizeEvidence(scope.evidence))
        }
        val incomingEvidence = normalized.evidenceOrNull()
        val currentEvidence = state.cameraScope.evidenceOrNull()
        if (
            incomingEvidence != null &&
            state.maximumAcceptedScopeRevision?.let { incomingEvidence.revision < it } == true
        ) {
            return RealtimeTransition(state)
        }
        if (incomingEvidence != null && currentEvidence != null) {
            if (incomingEvidence.revision < currentEvidence.revision) return RealtimeTransition(state)
            if (incomingEvidence.revision == currentEvidence.revision && incomingEvidence != currentEvidence) {
                return applyScope(
                    state,
                    CameraScopeState.Stale(conservativeScopeEvidence(currentEvidence, incomingEvidence)),
                    cancelOutstandingRefresh = true,
                )
            }
            if (
                incomingEvidence == currentEvidence &&
                (
                    normalized == state.cameraScope ||
                        (state.cameraScope is CameraScopeState.Stale && normalized is CameraScopeState.Fresh)
                    )
            ) {
                return RealtimeTransition(state)
            }
        }
        val accepted = when (normalized) {
            is CameraScopeState.Fresh -> if (normalized.evidence.boundsValid) {
                normalized
            } else {
                CameraScopeState.Stale(normalized.evidence)
            }
            CameraScopeState.Unknown -> CameraScopeState.Unknown
            is CameraScopeState.Stale -> normalized
        }
        return applyScope(state, accepted, cancelOutstandingRefresh = true)
    }

    private fun expireScope(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ScopeExpired,
    ): RealtimeTransition {
        if (event.generation != state.generation) return RealtimeTransition(state)
        val fresh = state.cameraScope as? CameraScopeState.Fresh ?: return RealtimeTransition(state)
        if (fresh.evidence.revision != event.revision) return RealtimeTransition(state)
        return applyScope(
            state,
            CameraScopeState.Stale(fresh.evidence),
            cancelOutstandingRefresh = false,
        )
    }

    private fun applyScope(
        state: RealtimeTransportState,
        newScope: CameraScopeState,
        cancelOutstandingRefresh: Boolean,
        initialCommands: List<RealtimeTransportCommand> = emptyList(),
    ): RealtimeTransition {
        val oldAllowed = state.cameraScope.evidenceOrNull()?.allowedCameraIds.orEmpty()
        val newAllowed = newScope.evidenceOrNull()?.allowedCameraIds.orEmpty()
        val oldMode = selectMode(state.profileState, state.cameraScope)
        val newMode = selectMode(state.profileState, newScope)
        val scopeChanged = state.cameraScope != newScope
        val commands = initialCommands.toMutableList()
        var next = state

        val mustStopSocket = state.socket != null && newMode != RealtimeTransportMode.SHARED_WEB_SOCKET
        val mustRestartRest =
            oldMode == RealtimeTransportMode.AUTHORIZED_REST_ONLY &&
                newMode == RealtimeTransportMode.AUTHORIZED_REST_ONLY &&
                scopeChanged
        val switchesMode = oldMode != newMode
        val sharedScopeChanged =
            oldMode == RealtimeTransportMode.SHARED_WEB_SOCKET &&
                newMode == RealtimeTransportMode.SHARED_WEB_SOCKET &&
                scopeChanged
        val openingSocketScopeChanged = sharedScopeChanged &&
            state.socket?.status == SocketSessionStatus.OPENING

        if (mustStopSocket || openingSocketScopeChanged) {
            commands += CloseSharedSocket(
                generation = state.generation,
                operationId = requireNotNull(state.socket).operationId,
                reason = SocketCloseReason.SCOPE_NO_LONGER_SOCKET_SAFE,
            )
            next = next.copy(socket = null)
        }
        if ((switchesMode || mustRestartRest || sharedScopeChanged) && next.reconciliation != null) {
            commands += CancelReconciliation(
                generation = state.generation,
                operationId = requireNotNull(next.reconciliation).operationId,
            )
            next = next.copy(reconciliation = null)
        }
        if ((switchesMode || mustRestartRest) && next.timer != null) {
            commands += CancelRealtimeTimer(
                generation = state.generation,
                operationId = requireNotNull(next.timer).operationId,
            )
            next = next.copy(timer = null)
        }
        if (cancelOutstandingRefresh && next.scopeRefresh != null) {
            commands += CancelAuthorizationScopeRefresh(
                generation = state.generation,
                operationId = requireNotNull(next.scopeRefresh).operationId,
            )
            next = next.copy(scopeRefresh = null)
        }
        val existingScopeTimer = next.scopeTimer
        val shouldCancelScopeTimer = existingScopeTimer != null && when {
            newScope is CameraScopeState.Fresh -> true
            existingScopeTimer.purpose == RealtimeTimerPurpose.SCOPE_EXPIRY -> true
            cancelOutstandingRefresh &&
                existingScopeTimer.purpose == RealtimeTimerPurpose.SCOPE_REFRESH_TIMEOUT -> true
            else -> false
        }
        if (shouldCancelScopeTimer) {
            commands += CancelRealtimeTimer(state.generation, requireNotNull(existingScopeTimer).operationId)
            next = next.copy(scopeTimer = null)
        }

        val nextScopeEpoch = if (scopeChanged) incrementEpoch(state.authorizationScopeEpoch) else state.authorizationScopeEpoch
        val acceptedRevision = newScope.evidenceOrNull()
            ?.takeIf(CameraScopeEvidence::boundsValid)
            ?.revision
        next = next.copy(
            authorizationScopeEpoch = nextScopeEpoch,
            cameraScope = newScope,
            selectedMode = newMode,
            maximumAcceptedScopeRevision = listOfNotNull(
                state.maximumAcceptedScopeRevision,
                acceptedRevision,
            ).maxOrNull(),
            pendingReconciliationReason = if (scopeChanged && newAllowed.isNotEmpty()) {
                ReconciliationReason.SCOPE_CHANGE
            } else {
                next.pendingReconciliationReason
            },
            socketEpochDesynchronized = next.socketEpochDesynchronized || sharedScopeChanged,
            socket = if (sharedScopeChanged && next.socket != null) {
                requireNotNull(next.socket).copy(
                    status = SocketSessionStatus.SYNCHRONIZING,
                    scopeToken = requireNotNull(newScope.scopeToken(state.generation, nextScopeEpoch)),
                    lastSynchronizedReconciliationOperationId = null,
                )
            } else {
                next.socket
            },
        )

        // A legacy scope downgrade closes first; only then is the narrower scope published.
        commands += ReplaceAuthorizedSinkScope(
            generation = state.generation,
            scopeEpoch = nextScopeEpoch,
            sourceRevision = newScope.evidenceOrNull()?.revision,
            authorizedCameraIds = newAllowed,
        )
        val revoked = oldAllowed - newAllowed
        if (revoked.isNotEmpty()) {
            commands += PurgeRevokedCameraState(state.generation, nextScopeEpoch, revoked)
        }
        if (newScope is CameraScopeState.Fresh && next.leases.isNotEmpty() && next.scopeTimer == null) {
            val allocation = next.allocateOperation()
            next = allocation.state.copy(
                scopeTimer = PendingRealtimeTimer(
                    operationId = allocation.operationId,
                    purpose = RealtimeTimerPurpose.SCOPE_EXPIRY,
                    delayMillis = config.scopeFreshnessMillis,
                ),
            )
            commands += ScheduleRealtimeTimer(
                generation = next.generation,
                operationId = allocation.operationId,
                purpose = RealtimeTimerPurpose.SCOPE_EXPIRY,
                delayMillis = config.scopeFreshnessMillis,
            )
        }
        return settle(next, commands)
    }

    private fun socketOpened(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.SocketOpened,
    ): RealtimeTransition {
        val socket = state.socket ?: return RealtimeTransition(state)
        if (
            event.generation != state.generation ||
            socket.operationId != event.operationId ||
            socket.status != SocketSessionStatus.OPENING ||
            socket.scopeToken != state.currentScopeToken()
        ) {
            return RealtimeTransition(state)
        }
        if (selectMode(state.profileState, state.cameraScope) != RealtimeTransportMode.SHARED_WEB_SOCKET) {
            return settle(
                state.copy(socket = null),
                listOf(
                    CloseSharedSocket(
                        generation = state.generation,
                        operationId = event.operationId,
                        reason = SocketCloseReason.SCOPE_NO_LONGER_SOCKET_SAFE,
                    ),
                ),
            )
        }
        val allowed = requireNotNull(state.cameraScope.evidenceOrNull()).allowedCameraIds
        val scopeToken = requireNotNull(state.currentScopeToken())
        return settle(
            state.copy(socket = socket.copy(status = SocketSessionStatus.SYNCHRONIZING)),
            listOf(
                StartSocketConsumption(state.generation, event.operationId, allowed, scopeToken),
                PublishOnConnect(state.generation, event.operationId, scopeToken),
            ),
        )
    }

    private fun socketFailed(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.SocketFailed,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            state.socket?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        return handleSocketFailure(state, event.failure, event.jitterUnit)
    }

    private fun socketClosed(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.SocketClosedUnexpectedly,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            state.socket?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        return handleSocketFailure(state, SafeTransportFailure.ABNORMAL_CLOSE, event.jitterUnit)
    }

    private fun handleSocketFailure(
        state: RealtimeTransportState,
        failure: SafeTransportFailure,
        jitterUnit: Double,
    ): RealtimeTransition {
        if (failure.isTerminal()) return terminalFailure(state, failure)
        val commands = mutableListOf<RealtimeTransportCommand>()
        val socket = requireNotNull(state.socket)
        commands += CloseSharedSocket(
            generation = state.generation,
            operationId = socket.operationId,
            reason = SocketCloseReason.RETRY_AFTER_FAILURE,
        )
        if (state.reconciliation != null) {
            commands += CancelReconciliation(state.generation, state.reconciliation.operationId)
        }
        val next = state.copy(
            socket = null,
            reconciliation = null,
            pendingReconciliationReason = ReconciliationReason.RECONNECT,
            socketEpochDesynchronized = true,
            repairAfterCurrentReconciliation = false,
        )
        return scheduleRetry(next, jitterUnit, commands)
    }

    private fun socketMessage(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.SocketMessageReceived,
    ): RealtimeTransition {
        val socket = state.socket ?: return RealtimeTransition(state)
        if (
            event.generation != state.generation ||
            socket.operationId != event.operationId ||
            socket.status == SocketSessionStatus.OPENING ||
            socket.scopeToken != state.currentScopeToken() ||
            selectMode(state.profileState, state.cameraScope) != RealtimeTransportMode.SHARED_WEB_SOCKET
        ) {
            return RealtimeTransition(state)
        }
        val allowed = requireNotNull(state.cameraScope.evidenceOrNull()).allowedCameraIds
        val filtered = filterAuthorizedMessage(event.message, allowed) ?: return RealtimeTransition(state)
        val command = if (socket.status == SocketSessionStatus.HEALTHY) {
            DeliverAuthorizedSocketMessage(state.generation, socket.operationId, filtered, socket.scopeToken)
        } else {
            BufferAuthorizedSocketMessage(state.generation, socket.operationId, filtered, socket.scopeToken)
        }
        return RealtimeTransition(state, listOf(command))
    }

    private fun queueOverflow(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.AuthoritativeQueueOverflow,
    ): RealtimeTransition {
        val socket = state.socket ?: return RealtimeTransition(state)
        if (
            event.generation != state.generation ||
            socket.operationId != event.operationId ||
            socket.status == SocketSessionStatus.OPENING ||
            socket.scopeToken != state.currentScopeToken() ||
            selectMode(state.profileState, state.cameraScope) != RealtimeTransportMode.SHARED_WEB_SOCKET
        ) {
            return RealtimeTransition(state)
        }
        val alreadyDesynchronized = state.socketEpochDesynchronized
        val next = state.copy(
            socket = socket.copy(
                status = SocketSessionStatus.SYNCHRONIZING,
                lastSynchronizedReconciliationOperationId = null,
            ),
            socketEpochDesynchronized = true,
            repairAfterCurrentReconciliation = state.reconciliation != null,
            pendingReconciliationReason = ReconciliationReason.QUEUE_OVERFLOW,
        )
        val commands = if (alreadyDesynchronized) {
            emptyList()
        } else {
            listOf(MarkSocketEpochDesynchronized(state.generation, socket.operationId))
        }
        return settle(next, commands)
    }

    private fun reconciliationSucceeded(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ReconciliationSucceeded,
    ): RealtimeTransition {
        val attempt = state.reconciliation
        if (
            event.generation != state.generation ||
            attempt?.operationId != event.operationId ||
            attempt.scopeToken != state.currentScopeToken()
        ) {
            return RealtimeTransition(state)
        }
        if (attempt.mode == RealtimeTransportMode.SHARED_WEB_SOCKET) {
            val socket = state.socket
            if (socket == null || socket.operationId != attempt.socketOperationId) {
                return RealtimeTransition(state.copy(reconciliation = null))
            }
            if (state.repairAfterCurrentReconciliation) {
                return settle(
                    state.copy(
                        reconciliation = null,
                        repairAfterCurrentReconciliation = false,
                        pendingReconciliationReason = ReconciliationReason.QUEUE_OVERFLOW,
                        committedReconciliationWatermarkEpochMillis = maxOf(
                            state.committedReconciliationWatermarkEpochMillis ?: 0L,
                            attempt.rangeEndEpochMillis,
                        ),
                    ),
                )
            }
            return RealtimeTransition(
                state.copy(
                    socket = socket.copy(
                        status = SocketSessionStatus.HEALTHY,
                        lastSynchronizedReconciliationOperationId = attempt.operationId,
                    ),
                    reconciliation = null,
                    phase = RealtimeTransportPhase.SOCKET_HEALTHY,
                    consecutiveFailures = 0,
                    committedReconciliationWatermarkEpochMillis = maxOf(
                        state.committedReconciliationWatermarkEpochMillis ?: 0L,
                        attempt.rangeEndEpochMillis,
                    ),
                    pendingReconciliationReason = null,
                    socketEpochDesynchronized = false,
                    repairAfterCurrentReconciliation = false,
                ),
                listOf(
                    FlushReconciledSocketBuffer(
                        state.generation,
                        socket.operationId,
                        attempt.operationId,
                        attempt.scopeToken,
                    ),
                ),
            )
        }

        var next = state.copy(
            reconciliation = null,
            phase = RealtimeTransportPhase.REST_ONLY,
            consecutiveFailures = 0,
            committedReconciliationWatermarkEpochMillis = maxOf(
                state.committedReconciliationWatermarkEpochMillis ?: 0L,
                attempt.rangeEndEpochMillis,
            ),
            pendingReconciliationReason = null,
            socketEpochDesynchronized = false,
            repairAfterCurrentReconciliation = false,
        )
        if (next.leases.isEmpty()) return settle(next)
        val allocation = next.allocateOperation()
        next = allocation.state.copy(
            timer = PendingRealtimeTimer(
                operationId = allocation.operationId,
                purpose = RealtimeTimerPurpose.REST_POLL,
                delayMillis = config.restPollIntervalMillis,
            ),
        )
        return RealtimeTransition(
            next,
            listOf(
                ScheduleRealtimeTimer(
                    generation = next.generation,
                    operationId = allocation.operationId,
                    purpose = RealtimeTimerPurpose.REST_POLL,
                    delayMillis = config.restPollIntervalMillis,
                ),
            ),
        )
    }

    private fun reconciliationFailed(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ReconciliationFailed,
    ): RealtimeTransition {
        val attempt = state.reconciliation
        if (
            event.generation != state.generation ||
            attempt?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        if (event.failure.isTerminal()) return terminalFailure(state, event.failure)
        val commands = mutableListOf<RealtimeTransportCommand>()
        var next = state.copy(reconciliation = null)
        if (attempt.mode == RealtimeTransportMode.SHARED_WEB_SOCKET && state.socket != null) {
            commands += CloseSharedSocket(
                state.generation,
                state.socket.operationId,
                SocketCloseReason.RETRY_AFTER_FAILURE,
            )
            next = next.copy(
                socket = null,
                pendingReconciliationReason = ReconciliationReason.RECONNECT,
                socketEpochDesynchronized = true,
            )
        }
        return scheduleRetry(next, event.jitterUnit, commands)
    }

    private fun timerFired(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.TimerFired,
    ): RealtimeTransition {
        val scopeTimer = state.scopeTimer
        if (event.generation == state.generation && scopeTimer?.operationId == event.operationId) {
            return when (scopeTimer.purpose) {
                RealtimeTimerPurpose.SCOPE_EXPIRY -> {
                    val fresh = state.cameraScope as? CameraScopeState.Fresh
                        ?: return RealtimeTransition(state.copy(scopeTimer = null))
                    expireScope(
                        state.copy(scopeTimer = null),
                        RealtimeTransportEvent.ScopeExpired(state.generation, fresh.evidence.revision),
                    )
                }
                RealtimeTimerPurpose.SCOPE_REFRESH_TIMEOUT -> {
                    val fallback = state.cameraScope.evidenceOrNull()?.let(CameraScopeState::Stale)
                        ?: CameraScopeState.Unknown
                    deferScopeRefresh(state, fallback, cancelRefreshOperation = true)
                }
                RealtimeTimerPurpose.SCOPE_REFRESH_RETRY -> settle(state.copy(scopeTimer = null))
                RealtimeTimerPurpose.RETRY,
                RealtimeTimerPurpose.REST_POLL,
                -> RealtimeTransition(state)
            }
        }
        val timer = state.timer
        if (
            event.generation != state.generation ||
            timer?.operationId != event.operationId
        ) {
            return RealtimeTransition(state)
        }
        return settle(
            state.copy(
                timer = null,
                pendingReconciliationReason = if (timer.purpose == RealtimeTimerPurpose.REST_POLL) {
                    ReconciliationReason.PERIODIC_REST_POLL
                } else {
                    state.pendingReconciliationReason
                },
            ),
        )
    }

    private fun foregroundRefresh(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.ForegroundRefreshRequested,
    ): RealtimeTransition {
        if (
            event.generation != state.generation ||
            state.leases.values.none { it == RealtimeDemandOwner.FOREGROUND_UI } ||
            selectMode(state.profileState, state.cameraScope) != RealtimeTransportMode.AUTHORIZED_REST_ONLY ||
            state.reconciliation != null
        ) {
            return RealtimeTransition(state)
        }
        val commands = mutableListOf<RealtimeTransportCommand>()
        if (state.timer != null) {
            commands += CancelRealtimeTimer(state.generation, state.timer.operationId)
        }
        return settle(
            state.copy(
                timer = null,
                pendingReconciliationReason = ReconciliationReason.FOREGROUND_REFRESH,
            ),
            commands,
        )
    }

    private fun publishPtz(
        state: RealtimeTransportState,
        event: RealtimeTransportEvent.PublishPtzRequested,
    ): RealtimeTransition {
        val reason = when {
            event.generation != state.generation -> PtzRejectionReason.STALE_REQUEST
            state.leases[event.leaseId] != RealtimeDemandOwner.PTZ_CONTROL ->
                PtzRejectionReason.NO_PTZ_LEASE
            selectMode(state.profileState, state.cameraScope) != RealtimeTransportMode.SHARED_WEB_SOCKET ->
                PtzRejectionReason.REST_ONLY_MODE
            state.socket?.status != SocketSessionStatus.HEALTHY ->
                PtzRejectionReason.SOCKET_NOT_HEALTHY
            state.socket.scopeToken != state.currentScopeToken() ->
                PtzRejectionReason.STALE_REQUEST
            event.cameraId !in state.cameraScope.evidenceOrNull()?.allowedCameraIds.orEmpty() ->
                PtzRejectionReason.CAMERA_NOT_AUTHORIZED
            event.cameraId !in state.cameraScope.evidenceOrNull()?.ptzCameraIds.orEmpty() ->
                PtzRejectionReason.CAPABILITY_NOT_PROVED
            else -> null
        }
        return if (reason != null) {
            RealtimeTransition(state, listOf(RejectPtz(state.generation, reason)))
        } else {
            val allocation = state.allocateOperation()
            RealtimeTransition(
                allocation.state.copy(latestPtzRequestOperationId = allocation.operationId),
                listOf(
                    PublishPtz(
                        generation = state.generation,
                        requestOperationId = allocation.operationId,
                        socketOperationId = requireNotNull(state.socket).operationId,
                        leaseId = event.leaseId,
                        cameraId = event.cameraId,
                        operation = event.operation,
                        scopeToken = requireNotNull(state.currentScopeToken()),
                    ),
                ),
            )
        }
    }

    private fun settle(
        initialState: RealtimeTransportState,
        initialCommands: List<RealtimeTransportCommand> = emptyList(),
    ): RealtimeTransition {
        var state = initialState
        val commands = initialCommands.toMutableList()
        if (state.leases.isEmpty()) {
            return RealtimeTransition(
                state.copy(selectedMode = RealtimeTransportMode.NONE, phase = RealtimeTransportPhase.STOPPED),
                commands,
            )
        }
        val profile = when (val profileState = state.profileState) {
            RealtimeProfileState.Unavailable -> {
                return RealtimeTransition(
                    state.copy(selectedMode = RealtimeTransportMode.NONE, phase = RealtimeTransportPhase.WAITING_FOR_PROFILE),
                    commands,
                )
            }
            is RealtimeProfileState.Invalid -> {
                return RealtimeTransition(
                    state.copy(selectedMode = RealtimeTransportMode.NONE, phase = RealtimeTransportPhase.INVALID_PROFILE),
                    commands,
                )
            }
            is RealtimeProfileState.Ready -> profileState.profile
        }
        when (state.authenticationState) {
            RealtimeAuthenticationState.UNKNOWN -> {
                return RealtimeTransition(
                    state.copy(phase = RealtimeTransportPhase.WAITING_FOR_AUTHENTICATION),
                    commands,
                )
            }
            RealtimeAuthenticationState.REQUIRED -> {
                return RealtimeTransition(
                    state.copy(phase = RealtimeTransportPhase.AUTHENTICATION_REQUIRED),
                    commands,
                )
            }
            RealtimeAuthenticationState.FORBIDDEN -> {
                return RealtimeTransition(
                    state.copy(phase = RealtimeTransportPhase.AUTHORIZATION_FORBIDDEN),
                    commands,
                )
            }
            RealtimeAuthenticationState.AUTHENTICATED -> Unit
        }
        if (!state.networkAvailable) {
            return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.WAITING_FOR_NETWORK), commands)
        }
        if (state.cameraScope == CameraScopeState.Unknown) {
            if (state.scopeRefresh == null && state.scopeTimer == null) {
                val started = startScopeRefresh(state)
                state = started.state
                commands += started.commands
            }
            return RealtimeTransition(
                state.copy(selectedMode = RealtimeTransportMode.NONE, phase = RealtimeTransportPhase.WAITING_FOR_SCOPE),
                commands,
            )
        }
        if (state.cameraScope is CameraScopeState.Stale && state.scopeRefresh == null && state.scopeTimer == null) {
            val started = startScopeRefresh(state)
            state = started.state
            commands += started.commands
        }
        if (state.cameraScope is CameraScopeState.Fresh && state.scopeTimer == null) {
            val scheduled = scheduleScopeExpiry(state)
            state = scheduled.state
            commands += scheduled.commands
        }

        val mode = selectMode(RealtimeProfileState.Ready(profile), state.cameraScope)
        state = state.copy(selectedMode = mode)
        when (mode) {
            RealtimeTransportMode.NONE -> {
                val phase = if (profile.contract == FrigateRealtimeContract.UNSUPPORTED) {
                    RealtimeTransportPhase.UNSUPPORTED
                } else {
                    RealtimeTransportPhase.WAITING_FOR_SCOPE
                }
                return RealtimeTransition(state.copy(phase = phase), commands)
            }
            RealtimeTransportMode.SHARED_WEB_SOCKET -> {
                if (state.timer != null) {
                    return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.BACKING_OFF), commands)
                }
                val socket = state.socket
                if (socket == null) {
                    val evidence = requireNotNull(state.cameraScope.evidenceOrNull())
                    val allocation = state.allocateOperation()
                    state = allocation.state.copy(
                        socket = SocketSession(
                            allocation.operationId,
                            SocketSessionStatus.OPENING,
                            requireNotNull(state.currentScopeToken()),
                        ),
                        phase = RealtimeTransportPhase.OPENING_SOCKET,
                    )
                    commands += OpenSharedSocket(
                        generation = state.generation,
                        operationId = allocation.operationId,
                        profileId = profile.id,
                        authorizedCameraIds = evidence.allowedCameraIds,
                        scopeToken = requireNotNull(state.currentScopeToken()),
                    )
                    return RealtimeTransition(state, commands)
                }
                if (socket.status == SocketSessionStatus.OPENING) {
                    return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.OPENING_SOCKET), commands)
                }
                if (socket.status == SocketSessionStatus.HEALTHY && state.reconciliation == null) {
                    return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.SOCKET_HEALTHY), commands)
                }
                if (state.reconciliation == null) {
                    val started = startReconciliation(state, RealtimeTransportMode.SHARED_WEB_SOCKET)
                    state = started.state
                    commands += started.commands
                }
                return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.SOCKET_SYNCHRONIZING), commands)
            }
            RealtimeTransportMode.AUTHORIZED_REST_ONLY -> {
                if (state.timer != null) {
                    val phase = if (state.timer.purpose == RealtimeTimerPurpose.RETRY) {
                        RealtimeTransportPhase.BACKING_OFF
                    } else {
                        RealtimeTransportPhase.REST_ONLY
                    }
                    return RealtimeTransition(state.copy(phase = phase), commands)
                }
                if (state.reconciliation == null) {
                    val started = startReconciliation(state, RealtimeTransportMode.AUTHORIZED_REST_ONLY)
                    state = started.state
                    commands += started.commands
                }
                return RealtimeTransition(state.copy(phase = RealtimeTransportPhase.REST_RECONCILING), commands)
            }
        }
    }

    private fun startReconciliation(
        state: RealtimeTransportState,
        mode: RealtimeTransportMode,
    ): RealtimeTransition {
        val evidence = state.cameraScope.evidenceOrNull() ?: return RealtimeTransition(state)
        if (evidence.allowedCameraIds.isEmpty()) return RealtimeTransition(state)
        val committedWatermark = state.committedReconciliationWatermarkEpochMillis
        val reason = if (committedWatermark == null) {
            ReconciliationReason.COLD_START
        } else {
            state.pendingReconciliationReason ?: ReconciliationReason.RECONNECT
        }
        val window = if (committedWatermark == null) {
            ReconciliationWindow.BOUNDED_COLD_START
        } else {
            ReconciliationWindow.BOUNDED_OVERLAP
        }
        // Persisted Review watermarks and query bounds share the wall-clock domain. Retry and
        // authorization timers remain operation-correlated and never compare against this clock.
        val capturedNow = wallClock.nowEpochMillis().coerceAtLeast(0L)
        val maximumStart = capturedNow.saturatingSubtract(config.maximumReconciliationLookbackMillis)
        val overlapSeconds = if (window == ReconciliationWindow.BOUNDED_OVERLAP) {
            config.reconciliationOverlapSeconds
        } else {
            0L
        }
        val desiredStart = when (window) {
            ReconciliationWindow.BOUNDED_COLD_START ->
                capturedNow.saturatingSubtract(config.coldStartLookbackMillis)
            ReconciliationWindow.BOUNDED_OVERLAP -> {
                val clampedWatermark = requireNotNull(committedWatermark).coerceIn(0L, capturedNow)
                clampedWatermark.saturatingSubtract(overlapSeconds * 1_000L)
            }
        }
        val rangeStart = maxOf(maximumStart, desiredStart)
        val allocation = state.allocateOperation()
        val attempt = ReconciliationAttempt(
            operationId = allocation.operationId,
            mode = mode,
            reason = reason,
            window = window,
            overlapSeconds = overlapSeconds,
            rangeStartEpochMillis = rangeStart,
            rangeEndEpochMillis = capturedNow,
            scopeToken = requireNotNull(state.currentScopeToken()),
            socketOperationId = if (mode == RealtimeTransportMode.SHARED_WEB_SOCKET) {
                state.socket?.operationId
            } else {
                null
            },
        )
        return RealtimeTransition(
            allocation.state.copy(
                reconciliation = attempt,
                pendingReconciliationReason = null,
            ),
            listOf(
                ReconcileAuthorizedReviews(
                    generation = state.generation,
                    operationId = allocation.operationId,
                    authorizedCameraIds = evidence.allowedCameraIds,
                    reason = reason,
                    window = window,
                    overlapSeconds = overlapSeconds,
                    rangeStartEpochMillis = rangeStart,
                    rangeEndEpochMillis = capturedNow,
                    scopeToken = attempt.scopeToken,
                ),
            ),
        )
    }

    private fun startScopeRefresh(state: RealtimeTransportState): RealtimeTransition {
        val refreshAllocation = state.allocateOperation()
        val timeoutAllocation = refreshAllocation.state.allocateOperation()
        val timeout = PendingRealtimeTimer(
            operationId = timeoutAllocation.operationId,
            purpose = RealtimeTimerPurpose.SCOPE_REFRESH_TIMEOUT,
            delayMillis = config.scopeRefreshTimeoutMillis,
        )
        return RealtimeTransition(
            state = timeoutAllocation.state.copy(
                scopeRefresh = ScopeRefreshAttempt(refreshAllocation.operationId),
                scopeTimer = timeout,
            ),
            commands = listOf(
                RefreshAuthorizationScope(state.generation, refreshAllocation.operationId),
                ScheduleRealtimeTimer(
                    generation = state.generation,
                    operationId = timeout.operationId,
                    purpose = timeout.purpose,
                    delayMillis = timeout.delayMillis,
                ),
            ),
        )
    }

    private fun scheduleScopeExpiry(state: RealtimeTransportState): RealtimeTransition {
        val allocation = state.allocateOperation()
        val timer = PendingRealtimeTimer(
            operationId = allocation.operationId,
            purpose = RealtimeTimerPurpose.SCOPE_EXPIRY,
            delayMillis = config.scopeFreshnessMillis,
        )
        return RealtimeTransition(
            state = allocation.state.copy(scopeTimer = timer),
            commands = listOf(
                ScheduleRealtimeTimer(
                    generation = state.generation,
                    operationId = timer.operationId,
                    purpose = timer.purpose,
                    delayMillis = timer.delayMillis,
                ),
            ),
        )
    }

    private fun deferScopeRefresh(
        state: RealtimeTransportState,
        fallbackScope: CameraScopeState,
        cancelRefreshOperation: Boolean = false,
    ): RealtimeTransition {
        val commands = mutableListOf<RealtimeTransportCommand>()
        if (cancelRefreshOperation && state.scopeRefresh != null) {
            commands += CancelAuthorizationScopeRefresh(state.generation, state.scopeRefresh.operationId)
        }
        if (state.scopeTimer != null) {
            commands += CancelRealtimeTimer(state.generation, state.scopeTimer.operationId)
        }
        var next = state.copy(scopeRefresh = null, scopeTimer = null)
        if (next.leases.isNotEmpty()) {
            val allocation = next.allocateOperation()
            val retry = PendingRealtimeTimer(
                operationId = allocation.operationId,
                purpose = RealtimeTimerPurpose.SCOPE_REFRESH_RETRY,
                delayMillis = config.restPollIntervalMillis,
            )
            next = allocation.state.copy(scopeTimer = retry)
            commands += ScheduleRealtimeTimer(
                generation = state.generation,
                operationId = retry.operationId,
                purpose = retry.purpose,
                delayMillis = retry.delayMillis,
            )
        }
        return applyScope(
            state = next,
            newScope = fallbackScope,
            cancelOutstandingRefresh = false,
            initialCommands = commands,
        )
    }

    private fun scheduleRetry(
        state: RealtimeTransportState,
        jitterUnit: Double,
        initialCommands: List<RealtimeTransportCommand>,
    ): RealtimeTransition {
        if (state.leases.isEmpty()) return settle(state, initialCommands)
        if (!state.networkAvailable) {
            return RealtimeTransition(
                state.copy(phase = RealtimeTransportPhase.WAITING_FOR_NETWORK, timer = null),
                initialCommands,
            )
        }
        val failures = (state.consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_COUNT)
        val delay = calculateRealtimeBackoffMillis(failures, jitterUnit, config.backoffPolicy)
        val allocation = state.allocateOperation()
        val timer = PendingRealtimeTimer(allocation.operationId, RealtimeTimerPurpose.RETRY, delay)
        return RealtimeTransition(
            allocation.state.copy(
                timer = timer,
                consecutiveFailures = failures,
                phase = RealtimeTransportPhase.BACKING_OFF,
            ),
            initialCommands + ScheduleRealtimeTimer(
                generation = state.generation,
                operationId = allocation.operationId,
                purpose = RealtimeTimerPurpose.RETRY,
                delayMillis = delay,
            ),
        )
    }

    private fun terminalFailure(
        state: RealtimeTransportState,
        failure: SafeTransportFailure,
    ): RealtimeTransition {
        val authState = when (failure) {
            SafeTransportFailure.AUTHENTICATION -> RealtimeAuthenticationState.REQUIRED
            SafeTransportFailure.AUTHORIZATION -> RealtimeAuthenticationState.FORBIDDEN
            SafeTransportFailure.INVALID_PROFILE -> RealtimeAuthenticationState.UNKNOWN
            else -> error("Transient failure passed to terminalFailure")
        }
        val closeReason = when (failure) {
            SafeTransportFailure.AUTHORIZATION -> SocketCloseReason.AUTHORIZATION_FAILED
            else -> SocketCloseReason.AUTHENTICATION_FAILED
        }
        val stopped = stopActiveWork(state, closeReason, cancelScopeTimer = true)
        val privacyCommands = clearAuthorizedSinkCommands(state)
        val profileState = if (failure == SafeTransportFailure.INVALID_PROFILE) {
            RealtimeProfileState.Invalid("invalid_profile")
        } else {
            stopped.state.profileState
        }
        val phase = when (failure) {
            SafeTransportFailure.AUTHENTICATION -> RealtimeTransportPhase.AUTHENTICATION_REQUIRED
            SafeTransportFailure.AUTHORIZATION -> RealtimeTransportPhase.AUTHORIZATION_FORBIDDEN
            SafeTransportFailure.INVALID_PROFILE -> RealtimeTransportPhase.INVALID_PROFILE
        }
        return RealtimeTransition(
            stopped.state.copy(
                generation = state.generation + 1,
                authorizationScopeEpoch = 0,
                profileState = profileState,
                authenticationState = authState,
                cameraScope = CameraScopeState.Unknown,
                scopeTimer = null,
                leases = emptyMap(),
                selectedMode = RealtimeTransportMode.NONE,
                phase = phase,
                consecutiveFailures = 0,
                maximumAcceptedScopeRevision = null,
                latestPtzRequestOperationId = null,
                pendingReconciliationReason = null,
                socketEpochDesynchronized = false,
                repairAfterCurrentReconciliation = false,
            ),
            stopped.commands + privacyCommands,
        )
    }

    private fun clearAuthorizedSinkCommands(state: RealtimeTransportState): List<RealtimeTransportCommand> {
        val allowed = state.cameraScope.evidenceOrNull()?.allowedCameraIds.orEmpty()
        val clearingEpoch = incrementEpoch(state.authorizationScopeEpoch)
        return buildList {
            add(ReplaceAuthorizedSinkScope(state.generation, clearingEpoch, null, emptySet()))
            if (allowed.isNotEmpty()) {
                add(PurgeRevokedCameraState(state.generation, clearingEpoch, allowed))
            }
        }
    }

    private fun stopActiveWork(
        state: RealtimeTransportState,
        closeReason: SocketCloseReason,
        cancelScopeTimer: Boolean = false,
    ): RealtimeTransition {
        val commands = mutableListOf<RealtimeTransportCommand>()
        if (state.socket != null) {
            commands += CloseSharedSocket(state.generation, state.socket.operationId, closeReason)
        }
        if (state.reconciliation != null) {
            commands += CancelReconciliation(state.generation, state.reconciliation.operationId)
        }
        if (state.timer != null) {
            commands += CancelRealtimeTimer(state.generation, state.timer.operationId)
        }
        if (state.scopeRefresh != null) {
            commands += CancelAuthorizationScopeRefresh(state.generation, state.scopeRefresh.operationId)
        }
        val shouldCancelScopeTimer = cancelScopeTimer || state.scopeRefresh != null
        if (shouldCancelScopeTimer && state.scopeTimer != null) {
            commands += CancelRealtimeTimer(state.generation, state.scopeTimer.operationId)
        }
        return RealtimeTransition(
            state.copy(
                socket = null,
                reconciliation = null,
                timer = null,
                scopeRefresh = null,
                scopeTimer = if (shouldCancelScopeTimer) null else state.scopeTimer,
            ),
            commands,
        )
    }

    private fun selectMode(
        profileState: RealtimeProfileState,
        scopeState: CameraScopeState,
    ): RealtimeTransportMode {
        val profile = (profileState as? RealtimeProfileState.Ready)?.profile
            ?: return RealtimeTransportMode.NONE
        val evidence = scopeState.evidenceOrNull() ?: return RealtimeTransportMode.NONE
        if (evidence.allowedCameraIds.isEmpty()) return RealtimeTransportMode.NONE
        if (scopeState is CameraScopeState.Stale || !evidence.boundsValid) {
            return RealtimeTransportMode.AUTHORIZED_REST_ONLY
        }
        return when (profile.contract) {
            FrigateRealtimeContract.FRIGATE_0_18 -> RealtimeTransportMode.SHARED_WEB_SOCKET
            FrigateRealtimeContract.FRIGATE_0_17_2 -> {
                if (evidence.coverage() == CameraScopeCoverage.FULL) {
                    RealtimeTransportMode.SHARED_WEB_SOCKET
                } else {
                    RealtimeTransportMode.AUTHORIZED_REST_ONLY
                }
            }
            FrigateRealtimeContract.UNSUPPORTED -> RealtimeTransportMode.NONE
        }
    }

    private fun CameraScopeEvidence.coverage(): CameraScopeCoverage {
        if (!boundsValid || allowedCameraIds.isEmpty()) return CameraScopeCoverage.AMBIGUOUS
        if (role == CameraRoleEvidence.ADMINISTRATOR) return CameraScopeCoverage.FULL
        val configured = configuredCameraIds
            ?.takeIf { it.isNotEmpty() }
            ?: return CameraScopeCoverage.AMBIGUOUS
        if (!configured.containsAll(allowedCameraIds)) return CameraScopeCoverage.AMBIGUOUS
        return if (allowedCameraIds == configured) {
            CameraScopeCoverage.FULL
        } else {
            CameraScopeCoverage.RESTRICTED
        }
    }

    private fun normalizeEvidence(evidence: CameraScopeEvidence): CameraScopeEvidence {
        val allInputsBounded = evidence.allowedCameraIds.isBoundedIdSet() &&
            (evidence.configuredCameraIds?.isBoundedIdSet() != false) &&
            evidence.ptzCameraIds.isBoundedIdSet() &&
            evidence.revision >= 0
        if (!allInputsBounded) {
            return evidence.copy(
                revision = evidence.revision.coerceAtLeast(0),
                allowedCameraIds = emptySet(),
                configuredCameraIds = null,
                ptzCameraIds = emptySet(),
                cameraDisplayNames = emptyMap(),
                boundsValid = false,
            )
        }
        val allowed = evidence.allowedCameraIds.normalizedIds()
        val configured = evidence.configuredCameraIds?.normalizedIds()
        val ptz = evidence.ptzCameraIds.normalizedIds().intersect(allowed)
        val displayNames = evidence.cameraDisplayNames.asSequence()
            .filter { (cameraId, displayName) ->
                cameraId in allowed &&
                    displayName.isNotBlank() &&
                    displayName.length <= MAX_CAMERA_DISPLAY_NAME_LENGTH &&
                    displayName.none {
                        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                    }
            }
            .take(config.maximumCameraCount)
            .associate { (cameraId, displayName) -> cameraId to displayName.trim() }
        return evidence.copy(
            allowedCameraIds = allowed,
            configuredCameraIds = configured,
            ptzCameraIds = ptz,
            cameraDisplayNames = displayNames,
            boundsValid = evidence.boundsValid && allInputsBounded,
        )
    }

    private fun Set<String>.normalizedIds(): Set<String> = asSequence()
        .filter { it.isNotBlank() && it.length <= config.maximumCameraIdLength }
        .sorted()
        .take(config.maximumCameraCount)
        .toSet()

    private fun Set<String>.isBoundedIdSet(): Boolean =
        size <= config.maximumCameraCount && all { it.isNotBlank() && it.length <= config.maximumCameraIdLength }

    private fun filterAuthorizedMessage(
        message: RealtimeInboundMessage,
        authorizedCameraIds: Set<String>,
    ): RealtimeInboundMessage? = when (message) {
        is RealtimeInboundMessage.ReviewChanged -> message.takeIf {
            it.cameraId in authorizedCameraIds &&
                it.cameraId.length <= config.maximumCameraIdLength &&
                it.reviewId.isNotBlank() &&
                it.reviewId.length <= config.maximumReviewIdLength &&
                it.reviewId.none(Char::isISOControl)
        }
        is RealtimeInboundMessage.CameraActivity -> {
            if (message.activityByCamera.size > config.maximumCameraCount) return null
            val filtered = message.activityByCamera.asSequence()
                .filter { (cameraId) -> cameraId in authorizedCameraIds }
                .take(config.maximumCameraCount)
                .associate { it.toPair() }
            filtered.takeIf { it.isNotEmpty() }
                ?.let(RealtimeInboundMessage::CameraActivity)
        }
        is RealtimeInboundMessage.UnknownTopic -> null
    }

    private fun CameraScopeState.evidenceOrNull(): CameraScopeEvidence? = when (this) {
        CameraScopeState.Unknown -> null
        is CameraScopeState.Fresh -> evidence
        is CameraScopeState.Stale -> evidence
    }

    private fun CameraScopeState.scopeToken(
        generation: Long,
        epoch: Long,
    ): AuthorizationScopeToken? = evidenceOrNull()?.let {
        AuthorizationScopeToken(generation, epoch, it.revision)
    }

    private fun RealtimeTransportState.currentScopeToken(): AuthorizationScopeToken? =
        cameraScope.scopeToken(generation, authorizationScopeEpoch)

    private fun conservativeScopeEvidence(
        current: CameraScopeEvidence,
        incoming: CameraScopeEvidence,
    ): CameraScopeEvidence = CameraScopeEvidence(
        revision = maxOf(current.revision, incoming.revision),
        role = if (current.role == incoming.role) current.role else CameraRoleEvidence.UNKNOWN,
        allowedCameraIds = current.allowedCameraIds.intersect(incoming.allowedCameraIds),
        configuredCameraIds = current.configuredCameraIds
            ?.takeIf { it == incoming.configuredCameraIds },
        ptzCameraIds = current.ptzCameraIds.intersect(incoming.ptzCameraIds),
        cameraDisplayNames = current.cameraDisplayNames.filter { (cameraId, displayName) ->
            cameraId in incoming.allowedCameraIds && incoming.cameraDisplayNames[cameraId] == displayName
        },
        boundsValid = false,
    )

    private fun SafeTransportFailure.isTerminal(): Boolean = when (this) {
        SafeTransportFailure.AUTHENTICATION,
        SafeTransportFailure.AUTHORIZATION,
        SafeTransportFailure.INVALID_PROFILE -> true
        SafeTransportFailure.DNS,
        SafeTransportFailure.ROUTE,
        SafeTransportFailure.CONNECTION,
        SafeTransportFailure.SERVER,
        SafeTransportFailure.TIMEOUT,
        SafeTransportFailure.ABNORMAL_CLOSE -> false
    }

    private data class OperationAllocation(
        val state: RealtimeTransportState,
        val operationId: RealtimeOperationId,
    )

    private fun RealtimeTransportState.allocateOperation(): OperationAllocation {
        val operationId = RealtimeOperationId(nextOperationValue)
        val nextValue = if (nextOperationValue == Long.MAX_VALUE) 1 else nextOperationValue + 1
        return OperationAllocation(copy(nextOperationValue = nextValue), operationId)
    }

    private fun Long.saturatingSubtract(amount: Long): Long =
        if (this < amount) 0L else this - amount

    private fun RealtimeTransportCommand.frozenCollectionCopy(): RealtimeTransportCommand = when (this) {
        is ReplaceAuthorizedSinkScope -> copy(authorizedCameraIds = authorizedCameraIds.frozenSetCopy())
        is PurgeRevokedCameraState -> copy(revokedCameraIds = revokedCameraIds.frozenSetCopy())
        is OpenSharedSocket -> copy(authorizedCameraIds = authorizedCameraIds.frozenSetCopy())
        is StartSocketConsumption -> copy(authorizedCameraIds = authorizedCameraIds.frozenSetCopy())
        is ReconcileAuthorizedReviews -> copy(authorizedCameraIds = authorizedCameraIds.frozenSetCopy())
        is RefreshAuthorizationScope,
        is CancelAuthorizationScopeRefresh,
        is CloseSharedSocket,
        is PublishOnConnect,
        is PublishPtz,
        is RejectPtz,
        is CancelReconciliation,
        is ScheduleRealtimeTimer,
        is CancelRealtimeTimer,
        is BufferAuthorizedSocketMessage,
        is DeliverAuthorizedSocketMessage,
        is MarkSocketEpochDesynchronized,
        is FlushReconciledSocketBuffer -> this
    }

    private fun incrementEpoch(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

    private companion object {
        const val MAX_CAMERA_DISPLAY_NAME_LENGTH = 256
        const val MAX_FAILURE_COUNT = 63
        const val MAX_SAFE_REASON_LENGTH = 128
    }
}
