package app.opah.tv.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PrivacySessionEvidence(
    val authenticated: Boolean,
    val authorizationFreshness: AuthorizationFreshness,
    val allowedCameraIds: Set<String>,
)

internal sealed interface PrivacyRepositoryState {
    data object Loading : PrivacyRepositoryState

    data class Ready(
        val policy: LocalPrivacyPolicy,
        val pinConfigured: Boolean,
        val protectedScopes: Set<PinScope>,
        val pinRecordCorrupt: Boolean,
    ) : PrivacyRepositoryState

    data class Unavailable(val epoch: Long) : PrivacyRepositoryState
}

internal sealed interface PrivacyPolicyMutationResult {
    data class Updated(val epoch: Long) : PrivacyPolicyMutationResult
    data class Unchanged(val epoch: Long) : PrivacyPolicyMutationResult
    data object Rejected : PrivacyPolicyMutationResult
    data object Unavailable : PrivacyPolicyMutationResult
}

/** Owns local privacy policy and its revocation epoch; content still goes through the engine. */
internal class PrivacyRepository(
    private val policyStore: PrivacyPolicyStore,
    private val pinStore: PinCredentialStore,
    private val unlockSessions: PinUnlockSessionOwner,
) {
    private val mutationMutex = Mutex()
    private val mutableState = MutableStateFlow<PrivacyRepositoryState>(PrivacyRepositoryState.Loading)
    val state: StateFlow<PrivacyRepositoryState> = mutableState.asStateFlow()

    suspend fun initialize(): PrivacyRepositoryState = mutationMutex.withLock {
        if (mutableState.value !is PrivacyRepositoryState.Loading) return@withLock mutableState.value
        val stored = policyStore.read()
        val policy = when (stored) {
            PrivacyPolicyStoreRead.Missing -> {
                val created = LocalPrivacyPolicy.recommendedDefault()
                if (!policyStore.write(created)) return@withLock publishUnavailable(created.epoch)
                created
            }
            is PrivacyPolicyStoreRead.Available -> stored.policy
            PrivacyPolicyStoreRead.Corrupt -> return@withLock publishUnavailable(1L)
        }
        val currentPolicy = if (
            policy.guestModeActive || policy.startInGuestMode || policy.guestModeFrigateModes.isNotEmpty()
        ) {
            policy.nextEpoch()?.let { next ->
                policy.copy(
                    epoch = next,
                    guestModeActive = false,
                    startInGuestMode = false,
                    guestModeFrigateModes = emptySet(),
                )
            } ?: return@withLock publishUnavailable(policy.epoch)
        } else {
            policy
        }
        if (currentPolicy !== policy && !policyStore.write(currentPolicy)) {
            return@withLock publishUnavailable(currentPolicy.epoch)
        }
        val ready = readyState(currentPolicy, pinStore.read())
        mutableState.value = ready
        ready
    }

    fun snapshot(
        session: PrivacySessionEvidence,
        nowMonotonicMillis: Long,
    ): PrivacySnapshot = when (val current = mutableState.value) {
        PrivacyRepositoryState.Loading -> unavailableSnapshot(0L, session)
        is PrivacyRepositoryState.Unavailable -> unavailableSnapshot(current.epoch, session)
        is PrivacyRepositoryState.Ready -> {
            val policy = current.policy
            PrivacySnapshot(
                epoch = policy.epoch,
                authenticated = session.authenticated,
                authorizationFreshness = session.authorizationFreshness,
                allowedCameraIds = session.allowedCameraIds.toSet(),
                guestMode = policy.guestModeActive,
                privateCameraIds = policy.privateCameraIds.toSet(),
                pinConfigured = current.pinConfigured,
                protectedScopes = current.protectedScopes.toSet(),
                pinUnlockProof = unlockSessions.currentProof(policy.epoch, nowMonotonicMillis),
                ownerRecognitionDisclosure = policy.ownerRecognitionDisclosure,
                guestRecognitionDisclosure = policy.guestRecognitionDisclosure,
                globalNotificationDisclosure = policy.globalNotificationDisclosure,
                guestNotificationDisclosure = policy.guestNotificationDisclosure,
            )
        }
    }

    suspend fun setPrivateCameras(cameraIds: Set<String>): PrivacyPolicyMutationResult =
        mutate(preserveUnlockSession = true) { it.copy(privateCameraIds = cameraIds.toSet()) }

    suspend fun setDisclosurePolicy(
        ownerRecognition: RecognitionDisclosure,
        guestRecognition: RecognitionDisclosure,
        globalNotification: NotificationDisclosure,
        guestNotification: NotificationDisclosure,
    ): PrivacyPolicyMutationResult = mutate(preserveUnlockSession = true) {
        it.copy(
            ownerRecognitionDisclosure = ownerRecognition,
            guestRecognitionDisclosure = guestRecognition,
            globalNotificationDisclosure = globalNotification,
            guestNotificationDisclosure = guestNotification,
        )
    }

    suspend fun relock(
        reason: PinRelockReason,
        forceEpochAdvance: Boolean = false,
    ): PrivacyPolicyMutationResult {
        initialize()
        val revokedGrant = unlockSessions.relock(reason)
        val current = mutableState.value as? PrivacyRepositoryState.Ready
            ?: return PrivacyPolicyMutationResult.Unavailable
        return if (revokedGrant || forceEpochAdvance) {
            mutate(forceEpochAdvance = true) { it }
        } else {
            PrivacyPolicyMutationResult.Unchanged(current.policy.epoch)
        }
    }

    suspend fun refreshPinProtection(
        unlockScopes: Set<PinScope>? = null,
        nowMonotonicMillis: Long? = null,
    ): PrivacyPolicyMutationResult = mutationMutex.withLock {
        val current = mutableState.value as? PrivacyRepositoryState.Ready
            ?: return@withLock PrivacyPolicyMutationResult.Unavailable
        val nextEpoch = current.policy.nextEpoch()
            ?: return@withLock publishUnavailableResult(current.policy.epoch)
        val updatedPolicy = current.policy.copy(epoch = nextEpoch)
        if (!policyStore.write(updatedPolicy)) {
            unlockSessions.relock(PinRelockReason.PRIVACY_EPOCH_CHANGED)
            return@withLock publishUnavailableResult(nextEpoch)
        }
        if (unlockScopes != null && nowMonotonicMillis != null) {
            unlockSessions.unlock(nextEpoch, unlockScopes, nowMonotonicMillis)
        } else {
            unlockSessions.relock(PinRelockReason.PRIVACY_EPOCH_CHANGED)
        }
        mutableState.value = readyState(updatedPolicy, pinStore.read())
        PrivacyPolicyMutationResult.Updated(nextEpoch)
    }

    suspend fun removePin(): PrivacyPolicyMutationResult = mutationMutex.withLock {
        val current = mutableState.value as? PrivacyRepositoryState.Ready
            ?: return@withLock PrivacyPolicyMutationResult.Unavailable
        val nextEpoch = current.policy.nextEpoch()
            ?: return@withLock publishUnavailableResult(current.policy.epoch)
        val updatedPolicy = current.policy.copy(
            epoch = nextEpoch,
            guestModeActive = false,
            startInGuestMode = false,
            guestModeFrigateModes = emptySet(),
        )
        if (!policyStore.write(updatedPolicy) || !pinStore.clear()) {
            unlockSessions.relock(PinRelockReason.PRIVACY_EPOCH_CHANGED)
            return@withLock publishUnavailableResult(nextEpoch)
        }
        unlockSessions.relock(PinRelockReason.MANUAL)
        mutableState.value = readyState(updatedPolicy, PinCredentialStoreRead.Missing)
        PrivacyPolicyMutationResult.Updated(nextEpoch)
    }

    private suspend fun mutate(
        forceEpochAdvance: Boolean = false,
        preserveUnlockSession: Boolean = false,
        transform: (LocalPrivacyPolicy) -> LocalPrivacyPolicy,
    ): PrivacyPolicyMutationResult = mutationMutex.withLock {
        val current = mutableState.value as? PrivacyRepositoryState.Ready
            ?: return@withLock PrivacyPolicyMutationResult.Unavailable
        val transformed = transform(current.policy)
        if (!transformed.isValid()) return@withLock PrivacyPolicyMutationResult.Rejected
        if (!forceEpochAdvance && transformed == current.policy) {
            return@withLock PrivacyPolicyMutationResult.Unchanged(current.policy.epoch)
        }
        val nextEpoch = current.policy.nextEpoch()
            ?: return@withLock publishUnavailableResult(current.policy.epoch)
        val updated = transformed.copy(epoch = nextEpoch)
        if (!policyStore.write(updated)) {
            unlockSessions.relock(PinRelockReason.PRIVACY_EPOCH_CHANGED)
            return@withLock publishUnavailableResult(nextEpoch)
        }
        if (preserveUnlockSession) {
            unlockSessions.advancePrivacyEpoch(current.policy.epoch, nextEpoch)
        } else {
            unlockSessions.relock(PinRelockReason.PRIVACY_EPOCH_CHANGED)
        }
        mutableState.value = current.copy(policy = updated)
        PrivacyPolicyMutationResult.Updated(nextEpoch)
    }

    private fun readyState(
        policy: LocalPrivacyPolicy,
        pinRead: PinCredentialStoreRead,
    ): PrivacyRepositoryState.Ready = when (pinRead) {
        PinCredentialStoreRead.Missing -> PrivacyRepositoryState.Ready(
            policy = policy,
            pinConfigured = false,
            protectedScopes = emptySet(),
            pinRecordCorrupt = false,
        )
        is PinCredentialStoreRead.Available -> PrivacyRepositoryState.Ready(
            policy = policy,
            pinConfigured = true,
            protectedScopes = pinRead.snapshot.verifierRecord.protectedScopes.toSet(),
            pinRecordCorrupt = false,
        )
        PinCredentialStoreRead.Corrupt -> PrivacyRepositoryState.Ready(
            policy = policy,
            pinConfigured = true,
            protectedScopes = PinScope.entries.toSet(),
            pinRecordCorrupt = true,
        )
    }

    private fun publishUnavailable(epoch: Long): PrivacyRepositoryState.Unavailable =
        PrivacyRepositoryState.Unavailable(epoch.coerceAtLeast(0L)).also { mutableState.value = it }

    private fun publishUnavailableResult(epoch: Long): PrivacyPolicyMutationResult {
        publishUnavailable(epoch)
        return PrivacyPolicyMutationResult.Unavailable
    }

    private fun unavailableSnapshot(
        epoch: Long,
        session: PrivacySessionEvidence,
    ): PrivacySnapshot = PrivacySnapshot(
        epoch = epoch.coerceAtLeast(0L),
        authenticated = session.authenticated,
        authorizationFreshness = session.authorizationFreshness,
        allowedCameraIds = session.allowedCameraIds.toSet(),
        guestMode = true,
        privateCameraIds = emptySet(),
        pinConfigured = true,
        protectedScopes = PinScope.entries.toSet(),
        privacyPolicyAvailable = false,
    )
}
